package coffee.crema.decent

import coffee.crema.core.DecentMachine
import coffee.crema.core.ShotMachine
import android.content.ContextWrapper
import coffee.crema.history.StoredShot
import coffee.crema.security.DeviceSecretBox
import coffee.crema.security.FakeKeystore
import java.io.File
import java.nio.file.Files
import coffee.crema.ui.UploadOutcome
import coffee.crema.ui.UploadOutcomeKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DecentSyncTest {

    private class MemoryStore(var state: DecentState) : DecentStateStore {
        var saves = 0
        override suspend fun load() = state
        override suspend fun save(state: DecentState): Boolean {
            saves++
            this.state = state
            return true
        }
    }

    /** A scripted server: each POST takes the next answer (a result or a thrown error). */
    private class FakeApi : DecentApi {
        val answers = ArrayDeque<suspend () -> DecentUploadResult>()
        val posts = mutableListOf<Pair<String, Boolean>>()
        var loginToken: String? = "token-123456"
        var machines: suspend () -> List<DecentMachine> = { listOf(DecentMachine("6262", "DE1PRO")) }

        override suspend fun login(email: String, password: String) = loginToken
        override suspend fun fetchMachines(email: String, token: String) = machines()
        override suspend fun uploadShot(email: String, token: String, recordJson: String, replace: Boolean): DecentUploadResult {
            posts += recordJson to replace
            val next = answers.removeFirstOrNull() ?: return DecentUploadResult("id-${posts.size}")
            return next()
        }
    }

    private fun shot(id: String, durationMs: Long = 30_000, at: Long = 1_000) =
        StoredShot(id = id, completedAtMs = at, durationMs = durationMs, machineSerial = "6262")

    private val linked = DecentState(email = "a@b.c", token = "token-123456", serials = listOf("6262"), autoUpload = true)

    private class Harness(
        scope: TestScope,
        initial: DecentState,
        shots: List<StoredShot>,
        /** A real store to run against instead of [store]. */
        backing: DecentStateStore? = null,
    ) {
        val store = MemoryStore(initial)
        val api = FakeApi()
        val history = shots.associateBy { it.id }.toMutableMap()
        val stamped = mutableListOf<Triple<String, String, ShotMachine?>>()
        val outcomes = mutableListOf<Pair<String, UploadOutcome>>()
        val notices = mutableListOf<String>()
        var live: ShotMachine? = null
        var liveUnreadable = false
        val sync = DecentSync(
            store = backing ?: store,
            client = api,
            scope = scope,
            buildRecord = { s, m, _ -> """{"shot":"${s.id}","sn":"${m.serialNumber}"}""" },
            shotViewUrl = { sn, id -> FakeDecentCore().shotViewUrl(sn, id) },
            notify = { notices += it },
            onShotUploaded = { id, did, m ->
                stamped += Triple(id, did, m)
                history[id] = history.getValue(id).copy(decentId = did)
            },
            liveMachine = { live },
            knownSerial = { FakeDecentCore().knownSerial(it) },
            liveSerialUnreadable = { liveUnreadable },
            currentShot = { history[it] },
            now = { 42L },
        ).also { it.onUploadOutcome = { id, o -> outcomes += id to o } }
    }

    private suspend fun TestScope.harness(shots: List<StoredShot>, initial: DecentState = linked) =
        Harness(this, initial, shots).also { it.sync.load() }

    @Test
    fun `a retryable failure is one call here, reported as failed`() = runTest {
        // Retries are the HTTP layer's (net/HttpClients.kt): whatever the client
        // throws is final, so DecentSync never re-POSTs.
        val h = harness(listOf(shot("s1")))
        h.api.answers += { throw DecentError.Network(503, "down") }
        val start = currentTime
        val o = h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = false, fullSamples = null)
        assertTrue(o is DecentSync.Outcome.Failed)
        assertEquals(1, h.api.posts.size)
        assertEquals(0L, currentTime - start)
        assertFalse(h.store.state.lastUpload!!.ok)
        assertFalse("s1" in h.store.state.rejectedShotIds)
    }

    @Test
    fun `a burst of edits to an uploaded shot queues exactly one replace`() = runTest {
        // shot-upload 435acabb: a shotUpdated re-POSTs with replace=1, rapid
        // edits coalescing into one upload of the latest state.
        val h = harness(listOf(shot("s1").copy(decentId = "d-1", notes = "first")))
        assertTrue(h.sync.scheduleReplaceAfterEdit("s1"))
        advanceTimeBy(500)
        h.history["s1"] = h.history.getValue("s1").copy(notes = "latest")
        h.sync.scheduleReplaceAfterEdit("s1")
        h.sync.scheduleReplaceAfterEdit("s1")
        runCurrent()
        assertEquals(0, h.api.posts.size) // still debouncing
        advanceUntilIdle()
        assertEquals(listOf(true), h.api.posts.map { it.second })
        assertEquals("""{"shot":"s1","sn":"6262"}""", h.api.posts.single().first)
    }

    @Test
    fun `an edit replaces nothing for a never-uploaded shot or with auto-upload off`() = runTest {
        val h = harness(listOf(shot("s1"), shot("s2").copy(decentId = "d-2")), initial = linked.copy(autoUpload = false))
        assertFalse(h.sync.scheduleReplaceAfterEdit("s2"))
        val on = harness(listOf(shot("s1")))
        assertFalse(on.sync.scheduleReplaceAfterEdit("s1"))
        advanceUntilIdle()
        assertEquals(0, h.api.posts.size + on.api.posts.size)
    }

    @Test
    fun `an edit during a running upload replaces after it`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val h = harness(listOf(shot("s1").copy(decentId = "d-1")))
        h.api.answers += { gate.await(); DecentUploadResult("d-1") }
        launch { h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = true, fullSamples = null) }
        runCurrent()
        assertTrue(h.sync.scheduleReplaceAfterEdit("s1"))
        advanceTimeBy(DecentSync.EDIT_REPLACE_DEBOUNCE_MS + 1_000)
        assertEquals(1, h.api.posts.size) // waits for the running one
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(true, true), h.api.posts.map { it.second })
    }

    @Test
    fun `the caller's replace flag goes through unchanged`() = runTest {
        val h = harness(listOf(shot("s1")))
        h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = true, fullSamples = null)
        assertEquals(listOf(true), h.api.posts.map { it.second })
    }

    // ── 403: the serial is not on the account (de1app 5b09f6aa) ──────────

    private val notOnAccount6262 =
        "This DE1 (serial 6262) isn’t registered to your Decent account — add it at decentespresso.com, then uploads resume."

    @Test
    fun `a 403 is one POST, names the serial, and leaves the shot in the backlog`() = runTest {
        val h = harness(listOf(shot("s1")))
        h.api.answers += { throw DecentError.NotOnAccount() }
        val o = h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = false, fullSamples = null)
        assertTrue(o is DecentSync.Outcome.Failed && o.error is DecentError.NotOnAccount)
        assertEquals(notOnAccount6262, (o as DecentSync.Outcome.Failed).error.message)
        assertEquals(1, h.api.posts.size)
        assertFalse("s1" in h.store.state.rejectedShotIds)
        assertFalse(h.store.state.needsReauth)
        assertEquals(notOnAccount6262, h.store.state.lastUpload!!.message)
        assertTrue(h.sync.inBacklog(h.history.getValue("s1")))
    }

    @Test
    fun `the 403 reason is shown once - later automatic uploads for that serial skip without a POST`() = runTest {
        val h = harness(listOf(shot("s1", at = 1), shot("s2", at = 2)))
        h.api.answers += { throw DecentError.NotOnAccount() }
        assertTrue(h.sync.maybeAutoUpload(h.history.getValue("s1"), null))
        advanceUntilIdle()
        assertTrue(h.sync.maybeAutoUpload(h.history.getValue("s2"), null))
        advanceUntilIdle()
        assertEquals(1, h.api.posts.size)
        assertEquals(
            listOf(UploadOutcomeKind.Failed to notOnAccount6262, UploadOutcomeKind.Skipped to notOnAccount6262),
            h.outcomes.map { it.second.kind to it.second.message },
        )
        // An explicit tap tries again — the owner may have just added the machine.
        h.sync.uploadNow(h.history.getValue("s2"), manual = true, replace = false, fullSamples = null)
        assertEquals(2, h.api.posts.size)
    }

    @Test
    fun `a backlog stops cleanly for that serial and carries on for another machine`() = runTest {
        val shots = listOf(shot("a", at = 1), shot("b", at = 2), shot("c", at = 3).copy(machineSerial = "7000"), shot("d", at = 4))
        val h = harness(shots)
        h.api.answers += { throw DecentError.NotOnAccount() }
        val r = h.sync.uploadUnsentNow(shots)
        // a: 403; b, d: held back without a POST; c (7000) uploads.
        assertEquals(listOf("a", "c"), h.api.posts.map { it.first.substringAfter("\"shot\":\"").substringBefore('"') })
        assertEquals(1, r.uploaded)
        assertEquals(0, r.failed)
        assertNull(r.stopped)
        assertEquals("3 shots held back: $notOnAccount6262", r.note)
    }

    @Test
    fun `signing in again forgets the 403 serials`() = runTest {
        val h = harness(listOf(shot("s1", at = 1), shot("s2", at = 2)))
        h.api.answers += { throw DecentError.NotOnAccount() }
        h.sync.maybeAutoUpload(h.history.getValue("s1"), null)
        advanceUntilIdle()
        h.sync.signIn("a@b.c", "pw")
        advanceUntilIdle()
        h.sync.maybeAutoUpload(h.history.getValue("s2"), null)
        advanceUntilIdle()
        assertEquals(2, h.api.posts.size)
    }

    // ── serial 0 is unknown (Decenza 6c83ca06) ───────────────────────────

    @Test
    fun `a live serial of 0 is never uploaded - the upload waits and says why`() = runTest {
        val old = StoredShot(id = "old", completedAtMs = 1, durationMs = 30_000)
        val h = harness(listOf(old))
        h.live = ShotMachine("0")
        h.liveUnreadable = true
        val o = h.sync.uploadNow(old, manual = true, replace = false, fullSamples = null)
        assertEquals(DecentSync.Outcome.Skipped(DecentSync.SERIAL_UNREAD_REASON), o)
        assertEquals("Crema couldn’t read this DE1’s serial number yet", DecentSync.SERIAL_UNREAD_REASON)
        assertEquals(0, h.api.posts.size)
        assertFalse(h.sync.maybeAutoUpload(old, null))
    }

    @Test
    fun `a shot stamped 0 borrows the connected DE1 and is restamped`() = runTest {
        val zero = shot("z").copy(machineSerial = "0")
        val h = harness(listOf(zero))
        assertEquals(
            DecentSync.Outcome.Skipped(DecentSync.SERIAL_UNREAD_REASON),
            h.sync.uploadNow(zero, manual = true, replace = false, fullSamples = null),
        )
        h.live = ShotMachine("6262", "1352", "DE1PRO")
        h.sync.uploadNow(zero, manual = true, replace = false, fullSamples = null)
        assertEquals("""{"shot":"z","sn":"6262"}""", h.api.posts.single().first)
        assertEquals(Triple("z", "id-1", ShotMachine("6262", "1352", "DE1PRO")), h.stamped.single())
    }

    // ── replace answered "duplicate" (Decenza f5c3ce3e) ──────────────────

    @Test
    fun `a replace Decent kept the earlier copy for is not an upload and is said once`() = runTest {
        val h = harness(listOf(shot("s1").copy(decentId = "d-1")))
        repeat(2) {
            h.api.answers += { throw DecentError.NotReplaced("d-1") }
            assertTrue(h.sync.scheduleReplaceAfterEdit("s1"))
            advanceUntilIdle()
        }
        assertEquals(listOf(true, true), h.api.posts.map { it.second })
        assertTrue(h.stamped.isEmpty()) // nothing re-recorded
        assertEquals("d-1", h.history.getValue("s1").decentId)
        assertFalse("s1" in h.store.state.rejectedShotIds)
        assertFalse(h.store.state.lastUpload!!.ok)
        assertEquals(listOf("Decent kept its earlier copy of this shot — the edit wasn’t saved there."), h.notices)
    }

    @Test
    fun `auth flags the account for re-linking`() = runTest {
        val h = harness(listOf(shot("s1")))
        h.api.answers += { throw DecentError.Auth() }
        val o = h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = false, fullSamples = null)
        assertTrue((o as DecentSync.Outcome.Failed).error is DecentError.Auth)
        assertTrue(h.store.state.needsReauth)
        assertTrue(h.sync.state.value.needsReauth)
        assertFalse(h.sync.enabled)
        assertEquals(1, h.api.posts.size)
    }

    @Test
    fun `cancellation mid-upload persists nothing and reports nothing`() = runTest {
        val h = harness(listOf(shot("s1")))
        val entered = CompletableDeferred<Unit>()
        h.api.answers += { entered.complete(Unit); awaitCancellation() }
        val savesBefore = h.store.saves
        val job = launch { h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = false, fullSamples = null) }
        entered.await()
        job.cancel()
        advanceUntilIdle()
        assertEquals(savesBefore, h.store.saves)
        assertNull(h.store.state.lastUpload)
        assertTrue(h.outcomes.isEmpty())
        assertTrue(h.sync.state.value.uploadingShotIds.isEmpty())
    }

    @Test
    fun `concurrent uploads of the same shot POST once`() = runTest {
        val h = harness(listOf(shot("s1")))
        val gate = CompletableDeferred<Unit>()
        h.api.answers += { gate.await(); DecentUploadResult("77") }
        assertTrue(h.sync.uploadShot(h.history.getValue("s1"), manual = true))
        runCurrent()
        assertFalse(h.sync.uploadShot(h.history.getValue("s1"), manual = true))
        val second = h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = false, fullSamples = null)
        assertTrue((second as DecentSync.Outcome.Skipped).alreadyRunning)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, h.api.posts.size)
        assertEquals(listOf("s1" to UploadOutcome(UploadOutcomeKind.Uploaded)), h.outcomes)
        // A later request sees the re-read shot: already on Decent.
        val third = h.sync.uploadNow(h.history.getValue("s1").copy(decentId = null), manual = true, replace = false, fullSamples = null)
        assertEquals("Already on Decent", (third as DecentSync.Outcome.Skipped).reason)
        assertEquals(1, h.api.posts.size)
    }

    @Test
    fun `an older shot uploaded with the live DE1 gets that machine stamped`() = runTest {
        val old = StoredShot(id = "old", completedAtMs = 1, durationMs = 30_000)
        val h = harness(listOf(old))
        h.live = ShotMachine("6262", "1352", "DE1PRO")
        h.sync.uploadNow(old, manual = true, replace = false, fullSamples = null)
        assertEquals(Triple("old", "id-1", ShotMachine("6262", "1352", "DE1PRO")), h.stamped.single())
    }

    @Test
    fun `a shot pulled from Visualizer never borrows the live serial and stays out of the backlog`() = runTest {
        val pulled = StoredShot(id = "shot:remote:9", completedAtMs = 1, durationMs = 30_000)
        val h = harness(listOf(pulled))
        h.live = ShotMachine("6262")
        assertFalse(h.sync.inBacklog(pulled))
        assertFalse(h.sync.canUpload(pulled))
        val o = h.sync.uploadNow(pulled, manual = true, replace = false, fullSamples = null)
        assertTrue(o is DecentSync.Outcome.Skipped)
        assertTrue(h.api.posts.isEmpty())
    }

    @Test
    fun `a Brew Log row never uploads, never borrows the live serial, and stays out of the backlog`() = runTest {
        // Issue #10: a manual V60 (and a manual espresso carrying a stale stamp)
        // are brews — no auto, manual or backlog path takes them.
        val v60 = StoredShot(id = "v60", completedAtMs = 1, durationMs = 185_000, brewMethod = "pourover", waterG = 250f)
        val stamped = v60.copy(id = "esp", brewMethod = "espresso", machineSerial = "6262")
        val real = shot("real")
        val h = harness(listOf(v60, stamped, real))
        h.live = ShotMachine("6262")
        for (brew in listOf(v60, stamped)) {
            assertFalse(h.sync.inBacklog(brew))
            assertFalse(h.sync.canUpload(brew))
            assertFalse(h.sync.maybeAutoUpload(brew, null))
            val o = h.sync.uploadNow(brew, manual = true, replace = false, fullSamples = null)
            assertEquals(DecentSync.Outcome.Skipped(coffee.crema.history.BREW_LOG_UPLOAD_SKIP), o)
        }
        assertEquals(listOf(real), h.sync.unsent(listOf(v60, stamped, real)))
        val r = h.sync.uploadUnsentNow(listOf(v60, stamped, real))
        assertEquals(1, r.uploaded)
        assertEquals(listOf("real"), h.stamped.map { it.first })
        assertEquals(1, h.api.posts.size)
    }

    @Test
    fun `a guided brew row with a weight series never uploads either`() = runTest {
        // Issue #10 Phase 2: a guided session's row carries a BrewSeries (weight
        // only, no DE1 telemetry) — still a brew, still local-only.
        val series = coffee.crema.core.BrewSeries(
            samples = listOf(
                coffee.crema.core.BrewSample(elapsedMs = 0, weightG = 0f),
                coffee.crema.core.BrewSample(elapsedMs = 45_000, weightG = 45f, flowGS = 1f),
            ),
            stageMarks = listOf(coffee.crema.core.StageMark(elapsedMs = 0, stepIndex = 0)),
        )
        val guided = StoredShot(
            id = "guided", completedAtMs = 1, durationMs = 180_000, brewMethod = "pourover",
            waterG = 250f, recipeName = "V60 classic", brewSeries = series,
        )
        val real = shot("real")
        val h = harness(listOf(guided, real))
        h.live = ShotMachine("6262")
        assertFalse(h.sync.inBacklog(guided))
        assertFalse(h.sync.canUpload(guided))
        assertFalse(h.sync.maybeAutoUpload(guided, null))
        assertEquals(
            DecentSync.Outcome.Skipped(coffee.crema.history.BREW_LOG_UPLOAD_SKIP),
            h.sync.uploadNow(guided, manual = true, replace = false, fullSamples = null),
        )
        assertEquals(listOf(real), h.sync.unsent(listOf(guided, real)))
        assertEquals(1, h.sync.uploadUnsentNow(listOf(guided, real)).uploaded)
        assertEquals(listOf("real"), h.stamped.map { it.first })
    }

    @Test
    fun `short shots are out of the backlog but a manual push still goes`() = runTest {
        val flush = shot("flush", durationMs = 3_000)
        val h = harness(listOf(flush))
        assertFalse(h.sync.inBacklog(flush))
        assertFalse(h.sync.maybeAutoUpload(flush, null))
        val o = h.sync.uploadNow(flush, manual = true, replace = false, fullSamples = null)
        assertTrue(o is DecentSync.Outcome.Uploaded)
    }

    @Test
    fun `the drain stops at the first offline error`() = runTest {
        val shots = listOf(shot("a", at = 1), shot("b", at = 2), shot("c", at = 3))
        val h = harness(shots)
        repeat(3) { h.api.answers += { throw DecentError.Network(null, "no route") } }
        val r = h.sync.uploadUnsentNow(shots)
        assertEquals("Offline", r.stopped)
        assertEquals(1, r.failed)
        // One (final) answer for "a", nothing for "b" / "c".
        assertEquals(1, h.api.posts.size)
        assertTrue(h.api.posts.all { it.first.contains("\"a\"") })
    }

    @Test
    fun `the drain stops after a streak of rejections, which leave the backlog`() = runTest {
        val shots = (1..5).map { shot("s$it", at = it.toLong()) }
        val h = harness(shots)
        repeat(5) { h.api.answers += { throw DecentError.Rejected(422, "bad") } }
        val r = h.sync.uploadUnsentNow(shots)
        assertEquals(3, r.failed)
        assertTrue(r.stopped!!.contains("in a row"))
        assertEquals(setOf("s1", "s2", "s3"), h.store.state.rejectedShotIds)
        assertEquals(listOf("s4", "s5"), h.sync.unsent(shots).map { it.id })
        // A manual re-upload clears the rejection.
        h.api.answers.clear()
        h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = false, fullSamples = null, clearRejection = true)
        assertFalse("s1" in h.store.state.rejectedShotIds)
    }

    @Test
    fun `the streak counts any failure, and an upload resets it`() = runTest {
        val shots = (1..6).map { shot("s$it", at = it.toLong()) }
        val h = harness(shots)
        h.api.answers += { throw DecentError.Rejected(422, "bad") }
        h.api.answers += { throw DecentError.Rejected(422, "bad") }
        h.api.answers += { DecentUploadResult("ok") }
        h.api.answers += { throw DecentError.Rejected(422, "bad") }
        // s5's final answer is a 5xx (the HTTP layer already retried it).
        h.api.answers += { throw DecentError.Network(500, "boom") }
        h.api.answers += { throw DecentError.Rejected(422, "bad") }
        val r = h.sync.uploadUnsentNow(shots)
        assertEquals(1, r.uploaded)
        assertEquals(5, r.failed)
        assertTrue(r.stopped!!.contains("in a row"))
        // s4 (rejected), s5 (5xx), s6 (rejected) → streak of 3 after the upload.
        assertEquals(setOf("s1", "s2", "s4", "s6"), h.store.state.rejectedShotIds)
    }

    @Test
    fun `a second drain while one runs returns at once`() = runTest {
        val shots = listOf(shot("a"))
        val h = harness(shots)
        val gate = CompletableDeferred<Unit>()
        h.api.answers += { gate.await(); DecentUploadResult("1") }
        val first = launch { h.sync.uploadUnsentNow(shots) }
        runCurrent()
        val second = h.sync.uploadUnsentNow(shots)
        assertTrue(second.stopped != null && second.attempted == 0)
        gate.complete(Unit)
        first.join()
        assertEquals(1, h.api.posts.size)
    }

    /** The real file store over the token box ([DeviceSecretBox]'s key handling) and a fake keystore. */
    private class TokenFiles {
        val dir: File = Files.createTempDirectory("decent-token").toFile()
        val keystore = FakeKeystore()
        val store = DecentStore(
            object : ContextWrapper(null) {
                override fun getFilesDir(): File = dir
            },
            Json { ignoreUnknownKeys = true; coerceInputValues = true },
            DeviceSecretBox.create(keystore),
        )
    }

    /** The real store saves on Dispatchers.IO: run the test dispatcher until [done] (real time, bounded). */
    private fun TestScope.settle(done: () -> Boolean) {
        repeat(500) {
            advanceUntilIdle()
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("never settled")
    }

    @Test
    fun `a lost token key at start reads as sign in again, with nothing running`() = runTest {
        val files = TokenFiles()
        try {
            files.store.save(linked)
            files.keystore.keys.clear() // the key is gone (reinstall, keystore reset)
            val h = Harness(this, DecentState(), listOf(shot("s1")), backing = files.store).also { it.sync.load() }
            advanceUntilIdle()
            val ui = h.sync.state.value
            assertTrue(ui.needsReauth)
            assertFalse(ui.linked)
            assertFalse(ui.busy)
            assertEquals("a@b.c", ui.email)
            assertFalse(h.sync.enabled)
            assertTrue(h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = false, fullSamples = null) is DecentSync.Outcome.Skipped)
            assertEquals(0, h.api.posts.size)
            // Signing in again works and is stored under a new key.
            h.sync.signIn("a@b.c", "pw")
            settle { !h.sync.state.value.busy }
            assertTrue(h.sync.state.value.linked)
            assertFalse(h.sync.state.value.needsReauth)
            assertEquals("token-123456", files.store.load().token)
        } finally {
            files.dir.deleteRecursively()
        }
    }

    @Test
    fun `sign-in with a keystore error is linked for the session, not stuck, and saved later`() = runTest {
        val files = TokenFiles()
        try {
            val h = Harness(this, DecentState(), emptyList(), backing = files.store).also { it.sync.load() }
            files.keystore.failure = java.security.KeyStoreException("keystore2 system error")
            h.sync.signIn("a@b.c", "pw")
            settle { !h.sync.state.value.busy }
            assertTrue(h.sync.state.value.linked)
            assertNull(h.sync.state.value.signInError)
            assertTrue(h.notices.last().contains("couldn’t save the login"))
            assertNull(files.store.load().token)
            // The next save, once the keystore answers, stores the token.
            files.keystore.failure = null
            h.sync.setAutoUpload(false)
            settle { !h.sync.state.value.autoUpload }
            settle { files.dir.resolve("decent.json").let { it.exists() && it.readText().contains("sb1:") } }
            assertEquals("token-123456", files.store.load().token)
        } finally {
            files.dir.deleteRecursively()
        }
    }

    @Test
    fun `sign-in fails when the machines call says the token is dead`() = runTest {
        val h = harness(emptyList(), initial = DecentState())
        h.api.machines = { throw DecentError.Auth() }
        h.sync.signIn("a@b.c", "pw")
        advanceUntilIdle()
        assertFalse(h.sync.state.value.linked)
        assertTrue(h.sync.state.value.signInError != null)
        assertFalse(h.sync.state.value.busy)
    }

    @Test
    fun `a 2xx without an id uploads with a placeholder and no share link`() = runTest {
        val h = harness(listOf(shot("s1")))
        h.api.answers += { DecentUploadResult(null) }
        val o = h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = false, fullSamples = null) as DecentSync.Outcome.Uploaded
        assertNull(o.url)
        val stored = h.history.getValue("s1")
        assertTrue(stored.decentId!!.startsWith("uploaded:"))
        assertNull(h.sync.shareUrl(stored))
        assertEquals(DECENT_HISTORY_URL, h.sync.viewUrl(stored))
    }

    // Final review 04 P3-3: Erase all data leaves no activity log, settings or retry list.
    @Test
    fun `eraseAll resets the whole stored state, log included`() = runTest {
        val h = harness(listOf(shot("s1")))
        h.sync.uploadNow(h.history.getValue("s1"), manual = true, replace = false, fullSamples = null)
        assertTrue(h.store.state.log.isNotEmpty())
        assertTrue(h.sync.state.value.log.isNotEmpty())

        h.sync.eraseAll()

        assertEquals(DecentState(), h.store.state)
        assertFalse(h.sync.state.value.linked)
        assertTrue(h.sync.state.value.log.isEmpty())
        assertNull(h.sync.state.value.email)
    }
}

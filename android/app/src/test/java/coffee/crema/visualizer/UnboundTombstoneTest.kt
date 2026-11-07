package coffee.crema.visualizer

import android.content.ContextWrapper
import coffee.crema.history.StoredShot
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import coffee.crema.ui.TelemetrySample
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * geota/crema#331: a delete records intent, and a binding can arrive later. A
 * shot deleted during its upload used to go with no tombstone: the upload then
 * finished, `markShotSynced` found no row, the remote id was dropped, and the
 * next pull brought the shot back.
 */
class UnboundTombstoneTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File = Files.createTempDirectory("viz-unbound").toFile()
    private val context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    private val box = SecretBox(
        object : SecretCipher {
            override fun seal(plain: ByteArray) = ByteArray(12) + plain
            override fun open(sealed: ByteArray) = sealed.copyOfRange(12, sealed.size)
        },
    )
    private val store = VisualizerStore(context, json, box)

    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val tokens = TokenSet("tok", "rt", expiresAt = Long.MAX_VALUE)

    private var uploadGate: CompletableDeferred<Unit>? = null
    private var uploadReply: Pair<HttpStatusCode, String> = HttpStatusCode.OK to """{"id":"v-1"}"""
    private val posts = CopyOnWriteArrayList<String>()
    private val deletes = CopyOnWriteArrayList<String>()
    private var listing = """{"data":[],"paging":{"count":0,"page":1,"limit":50,"pages":1}}"""
    private var detailStatus = HttpStatusCode.OK
    private val engine = MockEngine { req ->
        val path = req.url.encodedPath
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        when {
            path.endsWith("/shots/upload") -> {
                posts += path
                uploadGate?.await()
                respond(uploadReply.second, uploadReply.first, headers)
            }
            req.method.value == "DELETE" -> {
                deletes += path
                respond("", HttpStatusCode.NoContent, headers)
            }
            path.endsWith("/shots") -> respond(listing, HttpStatusCode.OK, headers)
            else -> respond("""{"id":"v-1","duration":30.4}""", detailStatus, headers)
        }
    }
    private val client = VisualizerClient(json, engine, retryBaseDelayMs = 1)
    private val job = SupervisorJob()
    private val notices = CopyOnWriteArrayList<String>()
    private val history = mutableMapOf<String, StoredShot>()
    private val pulled = CopyOnWriteArrayList<StoredShot>()
    private var clock = 1_800_000_000_000L
    private var series: suspend () -> Unit = {}

    private val sample = TelemetrySample(
        elapsedMs = 0, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null,
        dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f,
        setGroupFlow = 0f, frameNumber = 1,
    )

    private val completedAt = 1_700_000_000_000L

    private fun shot(id: String = "s1") =
        StoredShot(id = id, completedAtMs = completedAt, durationMs = 30_000, profileName = "Test", samples = listOf(sample))
            .also { history[id] = it }

    /** The row Visualizer lists for [shot]: its start second. */
    private fun listRemote() {
        listing = buildJsonObject {
            put(
                "data",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("id", "v-1")
                            put("clock", (completedAt - 30_000) / 1000)
                            put("updated_at", 1_700_000_100L)
                        },
                    )
                },
            )
            put("paging", buildJsonObject { put("count", 1); put("page", 1); put("limit", 50); put("pages", 1) })
        }.toString()
    }

    private fun sync(state: VisualizerState = VisualizerState(tokens = tokens)): VisualizerSync {
        runBlocking { store.save(state) }
        return reopen()
    }

    private fun reopen() = VisualizerSync(
        store = store,
        client = client,
        json = json,
        scope = CoroutineScope(job + Dispatchers.Default),
        clientId = "cid",
        appVersion = "t",
        notify = { notices += it },
        onShotSynced = { local, remote -> history[local]?.let { history[local] = it.copy(visualizerId = remote) } },
        onPulledShots = { rows ->
            pulled += rows
            emptySet()
        },
        currentShot = { history[it] },
        now = { clock },
        revoke = {},
        retryTimers = false,
        seriesReady = { series() },
    ).also { runBlocking { it.load() } }

    @After
    fun tearDown() {
        job.cancel()
        dir.deleteRecursively()
    }

    private fun settle() = runBlocking { repeat(6) { job.children.toList().forEach { it.join() } } }

    private fun stored() = runBlocking { store.load() }

    /** The user deletes [s], as `MainViewModel.deleteShot` does. */
    private fun delete(v: VisualizerSync, s: StoredShot, remote: Boolean = false) {
        history.remove(s.id)
        v.tombstoneDeleted(s, deleteRemote = remote)
    }

    private fun pull(v: VisualizerSync) {
        v.setShotsDirection("pull")
        settle()
        v.syncNow(emptyList(), includeBeans = false)
        settle()
    }

    /** An upload under way, its POST held at the server. */
    private fun uploading(v: VisualizerSync, s: StoredShot) {
        uploadGate = CompletableDeferred()
        assertTrue(v.uploadShot(s, silent = true))
        repeat(200) {
            if (posts.isNotEmpty()) return
            Thread.sleep(10)
        }
        error("the POST never went")
    }

    @Test
    fun `delete_during_upload_stays_deleted - upload first`() {
        val s = shot()
        val v = sync()
        uploading(v, s)
        delete(v, s)
        Thread.sleep(50)
        assertEquals("the intent is down before the answer", listOf("s1"), stored().unboundTombstones.map { it.id })

        uploadGate!!.complete(Unit)
        settle()
        val after = stored()
        assertEquals(listOf("v-1"), after.shotTombstones.map { it.visualizerId })
        assertTrue(after.unboundTombstones.isEmpty())
        assertTrue("nothing to delete remotely: $deletes", deletes.isEmpty())

        listRemote()
        pull(v)
        assertTrue("the shot stays gone: $pulled", pulled.isEmpty())
    }

    @Test
    fun `an upload landing after the delete deletes the copy when asked to`() {
        val s = shot()
        val v = sync()
        uploading(v, s)
        delete(v, s, remote = true)
        Thread.sleep(50)
        uploadGate!!.complete(Unit)
        settle()
        runBlocking { v.drainRetryQueue() }
        settle()
        assertEquals(listOf("/api/shots/v-1"), deletes)
        assertTrue(stored().shotTombstones.isEmpty())
    }

    @Test
    fun `an upload landing with no tombstone at all still leaves one`() {
        val s = shot()
        val v = sync()
        uploading(v, s)
        history.remove(s.id)
        uploadGate!!.complete(Unit)
        settle()
        assertEquals(listOf("v-1"), stored().shotTombstones.map { it.visualizerId })
    }

    @Test
    fun `delete_during_upload_stays_deleted - pull first`() {
        val s = shot()
        val v = sync(VisualizerState(tokens = tokens, uploadAttempted = mapOf("s1" to 1L)))
        delete(v, s, remote = true)
        settle()
        listRemote()
        pull(v)
        assertTrue("no add: $pulled", pulled.isEmpty())
        val after = stored()
        assertEquals(listOf("v-1"), after.shotTombstones.map { it.visualizerId })
        assertTrue("the intent carries over", after.shotTombstones.single().deleteRemote)
        assertTrue(after.unboundTombstones.isEmpty())
    }

    @Test
    fun `delete_during_upload_stays_deleted - kill mid-POST`() {
        val s = shot()
        val v = sync()
        uploading(v, s)
        assertEquals("stored before the POST", setOf("s1"), stored().uploadAttempted.keys)

        // The process dies here; the file is all that's left.
        job.cancel()
        val fresh = VisualizerSync(
            store = store, client = client, json = json,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            clientId = "cid", appVersion = "t", notify = {}, onShotSynced = { _, _ -> },
            currentShot = { null }, revoke = {}, retryTimers = false,
        )
        runBlocking { fresh.load() }
        assertTrue(fresh.uploadPending(s))
        fresh.tombstoneDeleted(s, deleteRemote = false)
        Thread.sleep(200)
        assertEquals(listOf("s1"), stored().unboundTombstones.map { it.id })
    }

    @Test
    fun `a row never offered to Visualizer goes with no tombstone`() {
        val s = shot()
        val v = sync()
        assertFalse(v.uploadPending(s))
        delete(v, s)
        settle()
        assertTrue(stored().unboundTombstones.isEmpty())
        assertTrue(stored().shotTombstones.isEmpty())
    }

    @Test
    fun `a queued create counts as offered`() {
        val s = shot()
        val v = sync(VisualizerState(tokens = tokens, retryQueue = listOf(QueuedUpload("s1", 1, 0L))))
        delete(v, s)
        settle()
        assertEquals(listOf("s1"), stored().unboundTombstones.map { it.id })
    }

    @Test
    fun `the attempt mark is down before the POST and goes on a bind`() {
        val s = shot()
        val v = sync()
        uploading(v, s)
        assertEquals(setOf("s1"), stored().uploadAttempted.keys)
        uploadGate!!.complete(Unit)
        settle()
        assertTrue(stored().uploadAttempted.isEmpty())
    }

    @Test
    fun `a refused upload clears the mark, and one that may have landed keeps it`() {
        val s = shot()
        val v = sync()
        uploadReply = HttpStatusCode.UnprocessableEntity to """{"error":"Could not save the provided file."}"""
        v.uploadShot(s, silent = true)
        settle()
        assertTrue(stored().uploadAttempted.isEmpty())

        uploadReply = HttpStatusCode.ServiceUnavailable to "{}"
        v.uploadShot(s, silent = true)
        settle()
        assertEquals(setOf("s1"), stored().uploadAttempted.keys)
    }

    @Test
    fun `unbound_tombstone_expires_after_complete_pull`() {
        val written = UnboundTombstone("s1", completedAt, 30_000, 1L, writtenRun = 3)
        val v = sync(VisualizerState(tokens = tokens, pullRun = 3, unboundTombstones = listOf(written)))
        pull(v)
        assertTrue(stored().unboundTombstones.isEmpty())
        assertEquals(4L, stored().pullRun)
    }

    @Test
    fun `a pull with the POST still out leaves the tombstone, so the delete isn't lost`() {
        val s = shot()
        val v = sync()
        uploading(v, s)
        delete(v, s, remote = true)
        Thread.sleep(50)
        // Pull in another scope: settling here would wait for the held POST.
        v.setShotsDirection("pull")
        Thread.sleep(50)
        v.syncNow(emptyList(), includeBeans = false)
        for (tick in 1..200) {
            if (stored().pullRun > 0L && !v.state.value.syncing) break
            Thread.sleep(10)
        }
        assertFalse("the pull finished", v.state.value.syncing)
        assertEquals("kept", listOf("s1"), stored().unboundTombstones.map { it.id })

        uploadGate!!.complete(Unit)
        settle()
        runBlocking { v.drainRetryQueue() }
        settle()
        assertEquals(listOf("/api/shots/v-1"), deletes)
    }

    /** The upload went at [attemptedAt]; the row may have landed behind this cursor. */
    private fun pullFrom(cursor: Long?, attemptedAt: Long?): List<String> {
        val written = UnboundTombstone("s1", completedAt, 30_000, 1L, writtenRun = 3, attemptedAt = attemptedAt)
        val v = sync(VisualizerState(tokens = tokens, pullRun = 3, shotPullCursor = cursor, unboundTombstones = listOf(written)))
        pull(v)
        return stored().unboundTombstones.map { it.id }
    }

    private val day = 24 * 60 * 60 * 1000L

    @Test
    fun `a cursor past the attempt can't speak for the row`() {
        val attempt = 1_700_000_000_000L
        assertEquals(listOf("s1"), pullFrom(attempt + 1, attempt))
        assertEquals("nor one inside the margin", listOf("s1"), pullFrom(attempt - day + 1, attempt))
        assertEquals("nor with no attempt time", listOf("s1"), pullFrom(attempt - 30 * day, null))
    }

    @Test
    fun `a cursor a day older than the attempt speaks for it, as does a walk from the start`() {
        val attempt = 1_700_000_000_000L
        assertEquals(emptyList<String>(), pullFrom(attempt - 2 * day, attempt))
        assertEquals(emptyList<String>(), pullFrom(null, attempt))
        assertEquals(emptyList<String>(), pullFrom(null, null))
    }

    @Test
    fun `a delete keeps the attempt's time`() {
        val s = shot()
        val v = sync(VisualizerState(tokens = tokens, uploadAttempted = mapOf("s1" to 77L)))
        delete(v, s)
        settle()
        assertEquals(77L, stored().unboundTombstones.single().attemptedAt)
    }

    @Test
    fun `a parked tombstone isn't this account's to match or expire`() {
        val parked = UnboundTombstone("s1", completedAt, 30_000, 1L, deleteRemote = true, writtenRun = 0, parkedFor = "old")
        val v = sync(VisualizerState(tokens = tokens, pullRun = 3, unboundTombstones = listOf(parked)))
        listRemote()
        pull(v)
        assertEquals("the row is this account's own", 1, pulled.size)
        val after = stored()
        assertEquals("kept for its account", listOf("s1"), after.unboundTombstones.map { it.id })
        assertTrue(after.shotTombstones.isEmpty())
    }

    @Test
    fun `an id landing on a parked tombstone stays parked`() {
        val parked = UnboundTombstone("s1", completedAt, 30_000, 1L, deleteRemote = true, parkedFor = "old")
        val bound = VisualizerState(unboundTombstones = listOf(parked)).withTombstoneBound("s1", "v-1", 5L)
        assertEquals("old", bound.shotTombstones.single().parkedFor)
        assertFalse("its delete waits for that account", bound.shotTombstones.single().owed)
    }

    @Test
    fun `a shot deleted while its upload waited is not POSTed`() {
        val gate = CompletableDeferred<Unit>()
        val s = shot()
        series = { gate.await() }
        val v = sync()
        assertTrue(v.uploadShot(s, silent = true))
        Thread.sleep(50)
        delete(v, s)
        Thread.sleep(50)
        gate.complete(Unit)
        settle()
        assertTrue("nothing went: $posts", posts.isEmpty())
    }

    @Test
    fun `a pull running when it was written leaves it, whatever the clock says`() {
        val written = UnboundTombstone("s1", completedAt, 30_000, deletedAt = Long.MIN_VALUE, writtenRun = 4)
        val v = sync(VisualizerState(tokens = tokens, pullRun = 3, unboundTombstones = listOf(written)))
        pull(v)
        assertEquals(listOf("s1"), stored().unboundTombstones.map { it.id })
    }

    @Test
    fun `a pull that missed a row leaves it`() {
        val written = UnboundTombstone("other", completedAt - 500_000, 30_000, 1L, writtenRun = 3)
        val v = sync(VisualizerState(tokens = tokens, pullRun = 3, unboundTombstones = listOf(written)))
        listRemote()
        detailStatus = HttpStatusCode.InternalServerError
        pull(v)
        assertEquals(listOf("other"), stored().unboundTombstones.map { it.id })
    }

    @Test
    fun `a failed remote delete says so once it is refused`() {
        val tomb = ShotTombstone("gone", "v-gone", 1L, deleteRemote = true)
        val refusing = MockEngine { respond("""{"error":"You are not authorized to perform this action."}""", HttpStatusCode.Forbidden, headersOf(HttpHeaders.ContentType, "application/json")) }
        runBlocking { store.save(VisualizerState(tokens = tokens, shotTombstones = listOf(tomb))) }
        val v = VisualizerSync(
            store = store, client = VisualizerClient(json, refusing, retryBaseDelayMs = 1), json = json,
            scope = CoroutineScope(job + Dispatchers.Default), clientId = "cid", appVersion = "t",
            notify = { notices += it }, onShotSynced = { _, _ -> }, now = { clock }, revoke = {}, retryTimers = false,
        )
        runBlocking {
            v.load()
            v.deleteRemoteShots()
        }
        assertEquals(1, notices.count { "refused to delete" in it })
        val kept = stored().shotTombstones.single()
        assertFalse(kept.deleteRemote)
    }
}

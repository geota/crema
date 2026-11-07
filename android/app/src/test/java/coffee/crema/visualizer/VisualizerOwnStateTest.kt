package coffee.crema.visualizer

import android.app.Application
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * geota/crema#322: everything that touches `visualizer.json` waits for its load.
 * A redirect on a cold start used to run on default state, read as an expired
 * sign-in, and write the defaults over the file: tokens, the pull cursor and
 * tombstones gone. Robolectric for the PKCE helpers' `android.util.Base64`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class VisualizerOwnStateTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File = Files.createTempDirectory("vz-own-state").toFile()
    private val file = File(dir, "visualizer.json")
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
    private val requests = CopyOnWriteArrayList<String>()
    private val engine = MockEngine { req ->
        requests += "${req.method.value} ${req.url.encodedPath}"
        val path = req.url.encodedPath
        when {
            path.endsWith("/me") -> respond(
                """{"id":"u1","name":"Ada","public":true}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
            path.endsWith("/shots/upload") -> respond(
                """{"id":"v-new"}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
            else -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }
    private val client = VisualizerClient(json, engine, retryBaseDelayMs = 1)
    private val job = SupervisorJob()
    private val notices = CopyOnWriteArrayList<String>()
    private val exchanged = CopyOnWriteArrayList<String>()
    private val issued = TokenSet("new-token", "new-refresh", expiresAt = Long.MAX_VALUE)

    private val tombstone = ShotTombstone(id = "gone", visualizerId = "v-gone", deletedAt = 1)
    private val saved = VisualizerState(
        tokens = TokenSet("old-token", "old-refresh", expiresAt = Long.MAX_VALUE),
        account = VisualizerAccount("u1", "Ada"),
        prefs = DEFAULT_VISUALIZER_SYNC_PREFS.copy(privacy = "private", autoUpload = false),
        shotPullCursor = 1234L,
        shotTombstones = listOf(tombstone),
    )

    private fun sync(
        scope: CoroutineScope = CoroutineScope(job + Dispatchers.Default),
        history: Map<String, StoredShot> = emptyMap(),
        shotsLoaded: () -> Boolean = { true },
        seriesReady: suspend () -> Unit = {},
        onSignedIn: () -> Unit = {},
    ) = VisualizerSync(
        store = store,
        client = client,
        json = json,
        scope = scope,
        clientId = "cid",
        appVersion = "t",
        notify = { notices += it },
        onShotSynced = { _, _ -> },
        currentShot = { history[it] },
        exchangeCode = { code, verifier ->
            exchanged += "$code/$verifier"
            issued
        },
        retryTimers = false,
        now = { 1_800_000_000_000L },
        shotsLoaded = shotsLoaded,
        seriesReady = seriesReady,
        onSignedIn = onSignedIn,
    )

    @After
    fun cleanUp() {
        job.cancel()
        dir.deleteRecursively()
    }

    private val queuedShot = StoredShot(
        id = "q1",
        completedAtMs = 1,
        durationMs = 30_000,
        profileName = "Test",
        samples = listOf(
            TelemetrySample(
                elapsedMs = 0, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null,
                dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f,
                setGroupFlow = 0f, frameNumber = 1,
            ),
        ),
    )

    private fun seed(state: VisualizerState) = runBlocking { assertTrue(store.save(state)) }

    private fun stored(): VisualizerState = runBlocking { store.load() }

    private fun waitFor(what: String, done: () -> Boolean) {
        repeat(1000) {
            if (done()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out: $what")
    }

    /** Long enough for a write that shouldn't happen to have happened. */
    private fun settle() = Thread.sleep(150)

    @Test
    fun `cold_redirect_keeps_visualizer_state`() {
        seed(saved)
        val before = file.readBytes()
        val s = sync()

        // The redirect lands in onCreate, before the load reaches this store.
        s.handleCallback("code", "some-state", null)
        settle()
        assertArrayEquals("nothing is written before the load", before, file.readBytes())

        runBlocking { s.load() }
        settle()
        assertArrayEquals("no handshake is pending: the redirect isn't ours", before, file.readBytes())
        assertEquals(emptyList<String>(), exchanged.toList())
        assertEquals(emptyList<String>(), notices.toList())
        assertTrue(s.state.value.signedIn)
        assertEquals("private", s.state.value.privacy)
        val after = stored()
        assertEquals("old-token", after.tokens?.accessToken)
        assertEquals(1234L, after.shotPullCursor)
        assertEquals(listOf(tombstone), after.shotTombstones)
    }

    @Test
    fun `a sign-in the process died during finishes on the stored settings`() {
        seed(saved.copy(tokens = null, pendingVerifier = "verifier", pendingState = "csrf"))
        val s = sync()

        s.handleCallback("code", "csrf", null)
        settle()
        assertEquals(emptyList<String>(), exchanged.toList())
        runBlocking { s.load() }
        waitFor("signed in") { s.state.value.signedIn && notices.any { "Signed in" in it } }

        assertEquals(listOf("code/verifier"), exchanged.toList())
        val after = stored()
        assertEquals("new-token", after.tokens?.accessToken)
        assertNull(after.pendingVerifier)
        assertEquals("private", after.prefs.privacy)
        assertEquals(false, after.prefs.autoUpload)
        assertEquals(1234L, after.shotPullCursor)
        assertEquals(listOf(tombstone), after.shotTombstones)
    }

    @Test
    fun `stray_callback_writes_nothing`() {
        seed(saved)
        val s = sync()
        runBlocking { s.load() }
        val before = file.readBytes()
        val modified = file.lastModified()

        s.handleCallback("code", "anything", null)
        s.handleCallback(null, null, "access_denied")
        settle()

        assertArrayEquals(before, file.readBytes())
        assertEquals(modified, file.lastModified())
        assertEquals(emptyList<String>(), notices.toList())
    }

    @Test
    fun `a replayed redirect leaves a handshake in progress alone`() {
        seed(saved.copy(tokens = null, pendingVerifier = "verifier", pendingState = "csrf"))
        val s = sync()
        runBlocking { s.load() }

        s.handleCallback("old-code", "old-state", null)
        settle()

        assertEquals("csrf", stored().pendingState)
        assertEquals(emptyList<String>(), exchanged.toList())
    }

    @Test
    fun `a refusal names the provider's error, and only a decline reads as cancelled`() {
        seed(saved.copy(tokens = null, pendingVerifier = "verifier", pendingState = "csrf"))
        var s = sync()
        runBlocking { s.load() }
        s.handleCallback(null, "csrf", "invalid_request")
        waitFor("refused") { notices.isNotEmpty() }
        assertEquals("Visualizer sign-in failed: invalid_request", notices.single())
        assertNull(stored().pendingState)

        notices.clear()
        seed(saved.copy(tokens = null, pendingVerifier = "verifier", pendingState = "csrf"))
        s = sync()
        runBlocking { s.load() }
        s.handleCallback(null, "csrf", "access_denied")
        waitFor("cancelled") { notices.isNotEmpty() }
        assertEquals("Visualizer sign-in was cancelled", notices.single())
    }

    @Test
    fun `own_state_timeout_reports_blocked`() {
        seed(saved)
        val before = file.readBytes()
        val test = TestScope()
        val s = sync(scope = test)

        s.handleCallback("code", "some-state", null)
        s.setShotsDirection("two-way")
        test.advanceTimeBy(coffee.crema.sync.OwnStateGate.TIMEOUT_MS + 1)
        test.runCurrent()

        assertEquals("told once, not per caller", 1, notices.size)
        assertArrayEquals("a timed-out wait writes nothing", before, file.readBytes())
    }

    @Test
    fun `startup_drain_waits_for_own_state`() {
        val shot = queuedShot
        seed(saved.copy(retryQueue = listOf(QueuedUpload("q1", 1, 0L))))
        val s = sync(history = mapOf("q1" to shot))

        s.drainSoon()
        s.refreshPremiumIfStale()
        settle()
        assertEquals("nothing goes out before the load", emptyList<String>(), requests.toList())

        runBlocking { s.load() }
        waitFor("the queued upload") { requests.any { it.endsWith("/shots/upload") } }
        waitFor("the Premium probe") { requests.any { "roasters" in it } }
    }

    @Test
    fun `the drain leaves a queued shot alone until the rows are there`() {
        val shot = queuedShot
        seed(saved.copy(retryQueue = listOf(QueuedUpload("q1", 1, 0L))))
        val rows = mutableMapOf<String, StoredShot>()
        val series = CompletableDeferred<Unit>()
        val s = VisualizerSync(
            store = store,
            client = client,
            json = json,
            scope = CoroutineScope(job + Dispatchers.Default),
            clientId = "cid",
            appVersion = "t",
            notify = { notices += it },
            onShotSynced = { _, _ -> },
            currentShot = { rows[it] },
            retryTimers = false,
            now = { 1_800_000_000_000L },
            seriesReady = { series.await() },
        )
        runBlocking { s.load() }

        // Own state is loaded, the history isn't: a network-back drain.
        s.drainSoon()
        settle()
        assertEquals(1, stored().retryQueue.size)

        rows["q1"] = shot
        series.complete(Unit)
        waitFor("the queued upload") { requests.any { it.endsWith("/shots/upload") } }
    }

    @Test
    fun `a drain skips, once told, when the rows never arrive`() {
        val shot = queuedShot
        seed(saved.copy(retryQueue = listOf(QueuedUpload("q1", 1, 0L))))
        val test = TestScope()
        val s = sync(scope = test, history = mapOf("q1" to shot), seriesReady = { awaitCancellation() })
        runBlocking { s.load() }

        repeat(2) {
            s.drainSoon()
            test.runCurrent()
            test.advanceTimeBy(VisualizerSync.ROWS_WAIT_MS + 1)
            test.runCurrent()
        }

        assertEquals("one notice, not one per trigger", 1, notices.count { "hasn’t loaded" in it })
        assertEquals("nothing waits on past the bound", 0, test.coroutineContext[Job]!!.children.count())
        assertEquals(emptyList<String>(), requests.toList())
        assertEquals("the shot stays queued, no attempt spent", listOf(QueuedUpload("q1", 1, 0L)), stored().retryQueue)
    }

    @Test
    fun `the catch-up offer waits for the rows after a cold-start sign-in`() {
        seed(saved.copy(tokens = null, pendingVerifier = "verifier", pendingState = "csrf"))
        val series = CompletableDeferred<Unit>()
        val offers = java.util.concurrent.atomic.AtomicInteger()
        val s = sync(seriesReady = { series.await() }, onSignedIn = { offers.incrementAndGet() })
        runBlocking { s.load() }

        s.handleCallback("code", "csrf", null)
        waitFor("signed in") { notices.any { "Signed in" in it } }
        waitFor("no longer busy") { !s.state.value.busy }
        settle()
        assertEquals("the rows aren't there to count", 0, offers.get())

        series.complete(Unit)
        waitFor("the offer") { offers.get() == 1 }
    }

    @Test
    fun `sync now before the rows load says so rather than work from an empty list`() {
        seed(saved)
        val s = sync(shotsLoaded = { false })
        runBlocking { s.load() }

        s.syncNow(emptyList())
        s.resyncAllShots(emptyList())
        s.uploadAllUnsynced(emptyList())
        settle()

        assertEquals(3, notices.count { "still loading" in it })
        assertEquals(emptyList<String>(), requests.toList())
        assertEquals(1234L, stored().shotPullCursor)
    }

    @Test
    fun `sign-out before the load still revokes the stored token`() {
        seed(saved)
        val s = sync()

        s.signOut(quiet = true)
        settle()
        assertNotNull("not before the load", stored().tokens)
        runBlocking { s.load() }
        // The raw file: a store load purges a save's temp file, so it can't poll mid-save.
        waitFor("signed out") { "accessToken" !in file.readText() }
    }

    @Test
    fun `erase waits for the load, and the file goes`() {
        seed(saved)
        val s = sync()
        val erase = CoroutineScope(job + Dispatchers.Default).launch { s.eraseAll() }
        settle()
        assertFalse(erase.isCompleted)
        assertTrue(file.exists())

        runBlocking {
            s.load()
            erase.join()
        }
        assertFalse(file.exists())
    }

    @Test
    fun `sign-in opens the browser with a handshake stored on the loaded state`() {
        seed(saved.copy(tokens = null))
        val s = sync()
        var url = ""
        s.beginSignIn { url = it }
        settle()
        assertEquals("", url)

        runBlocking { s.load() }
        waitFor("authorize url") { url.isNotEmpty() }
        val state = URI(url).rawQuery.split('&').first { it.startsWith("state=") }.substringAfter('=')
        assertEquals(state, stored().pendingState)
        assertEquals(1234L, stored().shotPullCursor)
    }
}

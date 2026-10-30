package coffee.crema.visualizer

import android.content.ContextWrapper
import coffee.crema.history.StoredShot
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * F17: Android retries Visualizer uploads in the background, and keeps the
 * session on a transient token-refresh failure. Both decisions are the core's
 * (`visualizer_queue_decision`, `classify_token_refresh_failure`, host-built).
 */
class VisualizerRetryQueueTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File = Files.createTempDirectory("viz-retry").toFile()
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

    /** What the server answers each `POST /shots/upload` with, in turn (then the last). */
    private val uploadReplies = ArrayDeque<Pair<HttpStatusCode, String>>()
    private var lastReply = HttpStatusCode.OK to """{"id":"v-1"}"""
    private val uploads = mutableListOf<String>()
    /** Each upload's JSON body, in turn. */
    private val uploadBodies = mutableListOf<String>()
    private val deletes = mutableListOf<String>()
    private var deleteStatus = HttpStatusCode.NoContent
    /** Set: each upload waits on it (an upload in flight holds its shot). */
    @Volatile private var uploadGate: CompletableDeferred<Unit>? = null
    private val engine = MockEngine { req ->
        val path = req.url.encodedPath
        if (path.endsWith("/shots/upload")) {
            uploadGate?.let { gate ->
                uploads += path
                gate.await()
                return@MockEngine respond(lastReply.second, lastReply.first, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            uploadBodies += String(req.body.toByteArray(), Charsets.UTF_8)
            val reply = uploadReplies.removeFirstOrNull() ?: lastReply
            lastReply = reply
            uploads += path
            respond(reply.second, reply.first, headersOf(HttpHeaders.ContentType, "application/json"))
        } else if (req.method.value == "DELETE") {
            deletes += path
            respond("{}", deleteStatus, headersOf(HttpHeaders.ContentType, "application/json"))
        } else {
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }
    private val client = VisualizerClient(json, engine, retryBaseDelayMs = 1)
    private val job = SupervisorJob()
    private val notices = mutableListOf<String>()
    private val synced = mutableMapOf<String, String>()
    private val history = mutableMapOf<String, StoredShot>()
    private var clock = 1_800_000_000_000L
    private var refresh: suspend (String) -> TokenSet = { error("no refresh expected") }

    private fun shot(id: String) = StoredShot(id = id, completedAtMs = 1, durationMs = 30_000, profileName = "Test")
        .also { history[id] = it }

    private fun sync(
        expiredTokens: Boolean = false,
        watcher: NetworkWatcher? = null,
        keepStore: Boolean = false,
        seriesReady: suspend () -> Unit = {},
        waits: MutableList<Long>? = null,
    ) = VisualizerSync(
        store = store,
        client = client,
        json = json,
        scope = CoroutineScope(job + Dispatchers.Default),
        clientId = "cid",
        appVersion = "t",
        notify = { notices += it },
        onShotSynced = { local, remote ->
            synced[local] = remote
            history[local] = history.getValue(local).copy(visualizerId = remote)
        },
        currentShot = { history[it] },
        now = { clock },
        refreshTokens = { refresh(it) },
        networkWatcher = watcher,
        // [waits] turns the timers on, each wait recorded and never ending.
        retryTimers = waits != null,
        seriesReady = seriesReady,
        retryDelay = { ms -> waits?.add(ms); awaitCancellation() },
    ).also { s ->
        runBlocking {
            if (!keepStore) {
                val expiresAt = if (expiredTokens) 0L else System.currentTimeMillis() + 3_600_000
                store.save(VisualizerState(tokens = TokenSet("tok", "rt", expiresAt = expiresAt)))
            }
            s.load()
        }
    }

    private fun settle() = runBlocking { repeat(3) { job.children.toList().forEach { it.join() } } }

    private fun queue() = runBlocking { store.load().retryQueue }

    /** The fake network watcher's registered callback. */
    private val onAvailableHolder = arrayOfNulls<() -> Unit>(1)

    @After
    fun tearDown() {
        job.cancel()
        client.close()
        dir.deleteRecursively()
    }

    // ── F17.3: the session survives a transient refresh failure ─────────────

    @Test
    fun `offline, a 503 or a proxy page keeps the session and queues the upload`() {
        val failures: List<Throwable> = listOf(
            IOException("offline"),
            TokenEndpointError(503, "", "HTTP 503"),
            TokenEndpointError(403, "<html>proxy</html>", "HTTP 403"),
        )
        for ((i, failure) in failures.withIndex()) {
            notices.clear()
            refresh = { throw failure }
            val s = sync(expiredTokens = true)
            s.uploadShot(shot("s$i"), silent = true)
            settle()
            assertNotNull("session kept after $failure", runBlocking { store.load().tokens })
            assertFalse("no expired notice: $notices", notices.any { "expired" in it })
            assertTrue("queued after $failure: ${queue()}", queue().any { it.shotId == "s$i" })
        }
    }

    @Test
    fun `invalid_grant signs out and queues nothing`() {
        refresh = { throw TokenEndpointError(400, """{"error":"invalid_grant"}""", "invalid_grant") }
        val s = sync(expiredTokens = true)
        s.uploadShot(shot("s1"), silent = true)
        settle()
        assertNull(runBlocking { store.load().tokens })
        assertTrue(notices.any { "session expired" in it })
        assertTrue(queue().isEmpty())
    }

    // ── F17.4: background retries ────────────────────────────────────────────

    @Test
    fun `a failed upload is retried at the core backoff and binds the id`() {
        uploadReplies += HttpStatusCode.ServiceUnavailable to "{}"
        uploadReplies += HttpStatusCode.ServiceUnavailable to "{}"
        uploadReplies += HttpStatusCode.ServiceUnavailable to "{}"
        uploadReplies += HttpStatusCode.ServiceUnavailable to "{}"
        val s = sync()
        s.uploadShot(shot("s1"), silent = true)
        settle()
        val entry = queue().single()
        assertEquals(1, entry.attempts)
        assertEquals(clock + 2_000, entry.nextAttemptAt)
        // Not due yet: nothing is retried.
        assertEquals(0, runBlocking { s.drainRetryQueue() })
        lastReply = HttpStatusCode.OK to """{"id":"v-9"}"""
        uploadReplies.clear()
        clock += 2_000
        assertEquals(1, runBlocking { s.drainRetryQueue() })
        assertEquals("v-9", synced["s1"])
        assertTrue(queue().isEmpty())
    }

    @Test
    fun `an upload is dropped after its third attempt`() {
        lastReply = HttpStatusCode.ServiceUnavailable to "{}"
        val s = sync()
        s.uploadShot(shot("s1"), silent = true)
        settle()
        clock += 10_000
        runBlocking { s.drainRetryQueue() }
        assertEquals(2, queue().single().attempts)
        clock += 10_000
        runBlocking { s.drainRetryQueue() }
        assertTrue("dropped after three: ${queue()}", queue().isEmpty())
        assertNull(synced["s1"])
    }

    @Test
    fun `the quota or a 429 stops the drain`() {
        lastReply = HttpStatusCode.ServiceUnavailable to "{}"
        val s = sync()
        s.uploadShot(shot("a"), silent = true)
        settle()
        s.uploadShot(shot("b"), silent = true)
        settle()
        assertEquals(2, queue().size)
        clock += 10_000
        val before = uploads.size
        lastReply = HttpStatusCode.UnprocessableEntity to """{"error":"You've reached your daily limit of 30 shots."}"""
        runBlocking { s.drainRetryQueue() }
        assertEquals("one try, then stop", before + 1, uploads.size)
        assertEquals("the quota drops the first, the second waits", listOf("b"), queue().map { it.shotId })
        assertTrue(notices.any { "free plan" in it })

        lastReply = HttpStatusCode.TooManyRequests to "{}"
        s.uploadShot(shot("c"), silent = true)
        settle()
        clock += 10 * 60_000
        val before429 = uploads.size
        runBlocking { s.drainRetryQueue() }
        assertEquals("a 429 stops after one", before429 + 1, uploads.size)
    }

    @Test
    fun `a deleted or synced shot leaves the queue, and the queue survives a reload`() {
        lastReply = HttpStatusCode.ServiceUnavailable to "{}"
        val s = sync()
        s.uploadShot(shot("gone"), silent = true)
        s.uploadShot(shot("done"), silent = true)
        settle()
        assertEquals(setOf("gone", "done"), queue().map { it.shotId }.toSet())
        // A new controller over the same store sees the queue.
        val reloaded = sync()
        history.remove("gone")
        history["done"] = history.getValue("done").copy(visualizerId = "v-elsewhere")
        clock += 10_000
        val before = uploads.size
        runBlocking { reloaded.drainRetryQueue() }
        assertEquals("nothing uploaded", before, uploads.size)
        assertTrue(queue().isEmpty())
    }

    @Test
    fun `a network coming back drains, at most once per spacing, and a success drains`() {
        lastReply = HttpStatusCode.ServiceUnavailable to "{}"
        var onAvailable: (() -> Unit)? = null
        val watcher = object : NetworkWatcher {
            override fun start(onAvailable: () -> Unit) {
                setCallback(onAvailable)
            }

            override fun stop() = setCallback(null)

            fun setCallback(cb: (() -> Unit)?) {
                onAvailableHolder[0] = cb
            }
        }
        val s = sync(watcher = watcher)
        s.uploadShot(shot("s1"), silent = true)
        settle()
        s.onForegroundChanged(true)
        onAvailable = onAvailableHolder[0]
        assertNotNull("registered in the foreground", onAvailable)
        lastReply = HttpStatusCode.OK to """{"id":"v-net"}"""
        clock += 10_000
        onAvailable!!.invoke()
        settle()
        assertEquals("v-net", synced["s1"])
        // A flapping network can't drain in a loop.
        lastReply = HttpStatusCode.ServiceUnavailable to "{}"
        s.uploadShot(shot("s2"), silent = true)
        settle()
        clock += 10_000
        val before = uploads.size
        onAvailable!!.invoke()
        settle()
        assertEquals("inside the spacing: no drain", before, uploads.size)
        s.onForegroundChanged(false)
        assertNull("unregistered in the background", onAvailableHolder[0])

        // A live upload's success drains what is waiting (the sync tail).
        lastReply = HttpStatusCode.OK to """{"id":"v-tail"}"""
        s.uploadShot(shot("s3"), silent = true)
        settle()
        assertEquals("v-tail", synced["s2"])
    }

    /** AC-F17.6: a remote delete that failed is retried by the next drain; a
     *  signed-out one waits for a sign-in. */
    @Test
    fun `tombstone deletes ride the drain`() {
        deleteStatus = HttpStatusCode.InternalServerError
        val s = sync()
        s.tombstoneShot("s1", "v-1", deleteRemote = true)
        settle()
        assertEquals(listOf("s1"), s.shotTombstones.map { it.id })
        deleteStatus = HttpStatusCode.NoContent
        val before = deletes.size
        runBlocking { s.drainRetryQueue() }
        assertEquals("not retried inside its backoff", before, deletes.size)
        clock += 60 * 60_000
        runBlocking { s.drainRetryQueue() }
        assertTrue("retried", deletes.size > before)
        assertTrue("purged: ${s.shotTombstones}", s.shotTombstones.isEmpty())

        // Signed out: the delete is not attempted, and the tombstone stays.
        deleteStatus = HttpStatusCode.InternalServerError
        s.tombstoneShot("s2", "v-2", deleteRemote = true)
        settle()
        runBlocking { store.save(store.load().copy(tokens = null)) }
        val out = sync(keepStore = true)
        assertEquals(listOf("s2"), out.shotTombstones.map { it.id })
        val tries = deletes.size
        runBlocking { out.drainRetryQueue() }
        assertEquals("parked while signed out", tries, deletes.size)
        assertEquals(listOf("s2"), out.shotTombstones.map { it.id })
    }

    /** F17 validation P3: a remote delete gets the core's ceiling, as web's
     *  queue: the third failure, or a terminal one (403), stops the remote
     *  delete; the tombstone stays, so the copy is still never pulled back. */
    @Test
    fun `tombstone deletes stop after the third failure or a terminal one`() {
        deleteStatus = HttpStatusCode.InternalServerError
        val s = sync()
        s.tombstoneShot("s1", "v-1", deleteRemote = true)
        settle()
        repeat(4) {
            clock += 60 * 60_000
            runBlocking { s.drainRetryQueue() }
        }
        assertEquals("three attempts in all: $deletes", 3, deletes.size)
        val t = s.shotTombstones.single()
        assertFalse("the remote delete stopped", t.deleteRemote)

        deletes.clear()
        deleteStatus = HttpStatusCode.Forbidden
        s.tombstoneShot("s2", "v-2", deleteRemote = true)
        settle()
        clock += 60 * 60_000
        runBlocking { s.drainRetryQueue() }
        assertEquals("a 403 is not retried: $deletes", 1, deletes.size)
        assertEquals(listOf(false, false), s.shotTombstones.map { it.deleteRemote })
    }

    /** F17 validation P2: a due shot another upload holds is pushed back, not
     *  left due, so the scheduled drain doesn't re-run at once. */
    @Test
    fun `a queued shot another upload holds does not make the drain spin`() {
        val waits = mutableListOf<Long>()
        runBlocking {
            store.save(
                VisualizerState(
                    tokens = TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000),
                    retryQueue = listOf(QueuedUpload("s1", 1, clock)),
                ),
            )
        }
        val s = sync(keepStore = true, waits = waits)
        val gate = CompletableDeferred<Unit>().also { uploadGate = it }
        s.uploadShot(shot("s1"), silent = true)
        val deadline = System.currentTimeMillis() + 10_000
        while (uploads.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals("the live upload is in flight", 1, uploads.size)

        assertEquals(0, runBlocking { s.drainRetryQueue() })
        assertEquals(clock + VisualizerSync.CLAIMED_RETRY_MS, queue().single().nextAttemptAt)
        assertEquals("one wait, a few seconds long: $waits", listOf(VisualizerSync.CLAIMED_RETRY_MS), waits)
        assertEquals("no second upload", 1, uploads.size)
        gate.complete(Unit)
    }

    /** F17 validation P2: a pass stopped by a 429 leaves the next entry due;
     *  the drain it schedules still waits at least the minimum. */
    @Test
    fun `a drain never schedules itself for a time already past`() {
        val waits = mutableListOf<Long>()
        runBlocking {
            store.save(
                VisualizerState(
                    tokens = TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000),
                    retryQueue = listOf(QueuedUpload("s1", 1, clock), QueuedUpload("s2", 1, clock)),
                ),
            )
        }
        shot("s1"); shot("s2")
        lastReply = HttpStatusCode.TooManyRequests to "{}"
        val s = sync(keepStore = true, waits = waits)
        runBlocking { s.drainRetryQueue() }
        assertEquals("the 429 stopped the pass", 1, uploads.size)
        assertEquals("s2 is still due", clock, queue().single { it.shotId == "s2" }.nextAttemptAt)
        assertEquals("the next drain waits the minimum: $waits", listOf(VisualizerSync.MIN_DRAIN_DELAY_MS), waits)
    }

    /** F10 validation P1-2: a queued upload waits for the stored series to
     *  load, then sends the shot as history holds it (with its samples). */
    @Test
    fun `the drain waits for the stored series and uploads them`() {
        runBlocking {
            store.save(
                VisualizerState(
                    tokens = TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000),
                    retryQueue = listOf(QueuedUpload("s1", 1, clock)),
                ),
            )
        }
        shot("s1") // the row, before its series has loaded
        val loaded = CompletableDeferred<Unit>()
        val s = sync(keepStore = true, seriesReady = { loaded.await() })
        val drain = CoroutineScope(Dispatchers.Default).async { s.drainRetryQueue() }
        Thread.sleep(200)
        assertTrue("nothing uploaded before the series load: $uploads", uploads.isEmpty())
        history["s1"] = history.getValue("s1").copy(
            samples = List(4) { n -> coffee.crema.ui.TelemetrySample(elapsedMs = n * 250L, pressure = 8f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null, dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1) },
        )
        loaded.complete(Unit)
        assertEquals(1, runBlocking { drain.await() })
        val body = json.parseToJsonElement(uploadBodies.single()).jsonObject
        assertEquals(4, body.getValue("elapsed").jsonArray.size)
    }

    /** F10 validation P3: a restored or re-imported shot drops its tombstone,
     *  so a pending remote delete doesn't remove the copy it points at. */
    @Test
    fun `a restored shot's tombstone is forgotten and its remote delete never runs`() {
        deleteStatus = HttpStatusCode.InternalServerError
        val s = sync()
        s.tombstoneShot("s1", "v-1", deleteRemote = true)
        settle()
        assertEquals("the delete is pending", listOf("s1"), s.shotTombstones.map { it.id })
        val tries = deletes.size
        s.forgetTombstones(listOf("s1"))
        settle()
        assertTrue(runBlocking { store.load() }.shotTombstones.isEmpty())
        clock += 60 * 60_000
        runBlocking { s.drainRetryQueue() }
        assertEquals("no delete after the restore", tries, deletes.size)
    }
}

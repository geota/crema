package coffee.crema.visualizer

import android.content.ContextWrapper
import coffee.crema.core.PoisonEntry
import coffee.crema.history.StoredShot
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import coffee.crema.ui.TelemetrySample
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Android pull run (SYNC-10) against a fake Visualizer: offset paging
 * newest first, strict `updated_after`, details, failing rows and rate limits.
 * The run's rules are the core's; these pin the loop that drives them. The web
 * has the same cases in `shot-sync-pull-run.vitest.ts`.
 */
class VisualizerPullRunTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File = Files.createTempDirectory("viz-pull-run").toFile()
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
    private val tokens = TokenSet("tok", "rt", expiresAt = Long.MAX_VALUE)

    @Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val t0 = 1_700_000_000L

    /** The fake server's rows, newest `updated_at` first when listed. */
    private val rows = CopyOnWriteArrayList<Triple<String, Long, Long>>()
    private val failing = mutableSetOf<String>()
    private val gone = mutableSetOf<String>()
    private val limitedPages = mutableSetOf<Int>()
    private val requests = CopyOnWriteArrayList<String>()

    private fun addRows(n: Int) = repeat(n) { k -> rows += Triple("r$k", t0 + k * 100, t0 + 10_000 + k) }

    /** Holds every list request until completed, when set. */
    @Volatile private var listGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    private val engine = MockEngine { req ->
        val url = req.url
        if (url.encodedPath == "/api/shots") listGate?.await()
        requests += "${req.method.value} ${url.encodedPath}${url.encodedQuery.takeIf { it.isNotEmpty() }?.let { "?$it" }.orEmpty()}"
        val headers = headersOf(HttpHeaders.ContentType, "application/json")
        val path = url.encodedPath
        when {
            path == "/api/shots" -> {
                val page = url.parameters["page"]!!.toInt()
                if (page in limitedPages) return@MockEngine respond("""{"error":"slow down"}""", HttpStatusCode.TooManyRequests, headers)
                val items = url.parameters["items"]!!.toInt()
                val after = url.parameters["updated_after"]?.toLong()
                val listed = rows.filter { after == null || it.third > after }.sortedByDescending { it.third }
                val pages = maxOf(1, (listed.size + items - 1) / items)
                val p = minOf(page, pages)
                val body = buildJsonObject {
                    putJsonArray("data") {
                        listed.drop((p - 1) * items).take(items).forEach { r ->
                            add(buildJsonObject { put("id", r.first); put("clock", r.second); put("updated_at", r.third) })
                        }
                    }
                    putJsonObject("paging") { put("count", listed.size); put("page", p); put("limit", items); put("pages", pages) }
                }
                respond(body.toString(), HttpStatusCode.OK, headers)
            }
            else -> {
                val id = path.removePrefix("/api/shots/")
                when {
                    id in failing -> respond("""{"error":"x"}""", HttpStatusCode.ServiceUnavailable, headers)
                    id in gone || rows.none { it.first == id } -> respond("{}", HttpStatusCode.NotFound, headers)
                    else -> respond(
                        buildJsonObject {
                            put("id", id); put("duration", 30); put("profile_title", "P"); put("drink_weight", "36")
                            rows.firstOrNull { it.first == id }?.let { put("updated_at", it.third) }
                            putJsonArray("timeframe") { add("0.0"); add("0.25") }
                            putJsonObject("data") {
                                putJsonArray("espresso_pressure") { add("9.0"); add("9.0") }
                                putJsonArray("espresso_flow") { add("2.0"); add("2.0") }
                            }
                        }.toString(),
                        HttpStatusCode.OK,
                        headers,
                    )
                }
            }
        }
    }
    private val client = VisualizerClient(json, engine, retryBaseDelayMs = 1)
    private val job = SupervisorJob()
    private val history = java.util.concurrent.ConcurrentHashMap<String, StoredShot>()
    private val waits = CopyOnWriteArrayList<Long>()
    private var clock = 1_800_000_000_000L

    private val sample = TelemetrySample(
        elapsedMs = 0, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = null, weightFlow = null,
        dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f,
        setGroupFlow = 0f, frameNumber = 1,
    )

    private fun sync(state: VisualizerState): VisualizerSync {
        runBlocking { store.save(state.copy(tokens = tokens, syncAccountId = "u1", prefs = DEFAULT_VISUALIZER_SYNC_PREFS.copy(shotsDirection = "pull"))) }
        return VisualizerSync(
            store = store,
            client = client,
            json = json,
            scope = CoroutineScope(job + Dispatchers.Default),
            clientId = "cid",
            appVersion = "t",
            notify = {},
            onShotSynced = { local, remote -> history[local]?.let { history[local] = it.copy(visualizerId = remote) } },
            onPulledShots = { stubs ->
                stubs.forEach { history[it.id] = it }
                emptySet()
            },
            onApplyRemote = { id, patch, pulled ->
                history[id]?.let { row ->
                    history[id] = row.copy(
                        visualizerUpdatedAt = patch.remoteVersion ?: row.visualizerUpdatedAt,
                        samples = if (patch.backfillSamples == true && row.samples.isEmpty()) pulled.samples else row.samples,
                    )
                }
            },
            currentShot = { history[it] },
            allShots = { history.values.toList() },
            now = { clock },
            revoke = {},
            retryTimers = false,
            paceDelay = { waits += it },
        ).also { runBlocking { it.load() } }
    }

    @After
    fun tearDown() {
        job.cancel()
        client.close()
        dir.deleteRecursively()
    }

    private fun settle() = runBlocking { repeat(6) { job.children.toList().forEach { it.join() } } }

    private fun stored() = runBlocking { store.load() }

    private fun run(v: VisualizerSync, fromStart: Boolean = false): VisualizerSync.RunResult =
        runBlocking { v.pullRun(history.values.toList(), fromStart) }

    private fun details() = requests.filter { Regex("^GET /api/shots/[^/?]+$").matches(it) }

    private fun kind(r: VisualizerSync.RunResult) = r.outcome["kind"].toString().trim('"')

    private fun reason(r: VisualizerSync.RunResult) = r.outcome["details"]!!.let { (it as kotlinx.serialization.json.JsonObject)["reason"].toString().trim('"') }

    @Test
    fun `pull_40_shots_gets_40 - every row arrives, and the cursor is the server's newest less a minute`() {
        addRows(40)
        val v = sync(VisualizerState(pullCursorEpoch = 1))
        val r = run(v)
        assertEquals("Complete", kind(r))
        assertEquals(40, r.pulled)
        assertEquals(40, details().size)
        val newest = t0 + 10_000 + 39
        assertEquals((newest - 60) * 1000, stored().shotPullCursor)
        // Versions are written: the next run lists from the cursor and fetches nothing.
        requests.clear()
        val again = run(v)
        assertEquals("Complete", kind(again))
        assertEquals(emptyList<String>(), details())
        assertTrue(requests.first(), requests.first().contains("updated_after=${newest - 61}"))
    }

    @Test
    fun `pull_503_row_holds_cursor - the rest apply, and the next run fetches only that row`() {
        addRows(10)
        failing += "r3"
        val v = sync(VisualizerState(pullCursorEpoch = 1))
        val r = run(v)
        assertEquals("Partial", kind(r))
        assertEquals("RowsFailed", reason(r))
        assertEquals(9, r.pulled)
        assertNull(stored().shotPullCursor)
        // The HTTP layer keeps its two 5xx retries for a GET; the run adds one in place.
        assertEquals(6, details().count { it.endsWith("/r3") })
        failing.clear()
        requests.clear()
        val again = run(v)
        assertEquals("Complete", kind(again))
        assertEquals(listOf("GET /api/shots/r3"), details())
    }

    @Test
    fun `pull_120_429_page_2 - the first page stays applied, the run waits three times, then stops`() {
        addRows(120)
        limitedPages += 2
        val v = sync(VisualizerState(pullCursorEpoch = 1))
        val r = run(v)
        assertEquals("Partial", kind(r))
        assertEquals("RateLimited", reason(r))
        assertEquals(100, r.pulled)
        assertNull(stored().shotPullCursor)
        assertEquals("no 429 retry in the HTTP layer: one request per wait", 4, requests.count { it.contains("page=2") })
        // 60 s, then ten minutes, twice: the run sits each out on the pacer's cooldown.
        assertTrue(waits.toString(), waits.count { it >= 600_000 } >= 2)
        assertTrue("a cooldown is stored", (stored().paceBucket?.cooldownUntilMs ?: 0) > clock)
    }

    @Test
    fun `first_run_repairs_old_cursor - walks from the start once, skipping bound rows at or below the old cursor`() {
        addRows(4)
        listOf("r0", "r1").forEachIndexed { i, vid ->
            history["local-$i"] = StoredShot(id = "local-$i", completedAtMs = i + 1L, durationMs = 30_000, samples = listOf(sample), visualizerId = vid)
        }
        val v = sync(VisualizerState(shotPullCursor = (t0 + 10_001) * 1000))
        val r = run(v)
        assertFalse(requests.first().contains("updated_after"))
        assertEquals(listOf("GET /api/shots/r2", "GET /api/shots/r3"), details().sorted())
        assertEquals("Complete", kind(r))
        assertEquals(1, stored().pullCursorEpoch)
        assertEquals((t0 + 10_003 - 60) * 1000, stored().shotPullCursor)
    }

    @Test
    fun `a poison row is tried alone once a day - a 404 ends it, any other failure waits a day`() {
        val entry = { id: String -> PoisonEntry(id, 3u, clock - 1, 0, clock - 1) }
        gone += "gone"
        rows += Triple("broken", 0L, 1L)
        failing += "broken"
        val v = sync(VisualizerState(pullCursorEpoch = 1, rowFailures = listOf(entry("gone"), entry("broken"))))
        run(v)
        val left = stored().rowFailures
        assertEquals(listOf("broken"), left.map { it.id })
        assertTrue(left.single().retryAtMs >= clock + 24 * 3_600_000L)
    }

    @Test
    fun `a re-pull walks from the start and forgets failing rows`() {
        addRows(2)
        val v = sync(
            VisualizerState(
                pullCursorEpoch = 1,
                shotPullCursor = (t0 + 20_000) * 1000,
                rowFailures = listOf(PoisonEntry("x", 1u, 0, 0, Long.MAX_VALUE)),
            ),
        )
        val r = run(v, fromStart = true)
        assertFalse(requests.first().contains("updated_after"))
        assertEquals(2, r.pulled)
        assertEquals(emptyList<PoisonEntry>(), stored().rowFailures)
    }

    @Test
    fun `a full history spends no detail on new rows, and holds the cursor`() {
        addRows(2)
        repeat(coffee.crema.history.HistoryStore.MAX_SHOTS) { i ->
            history["full-$i"] = StoredShot(id = "full-$i", completedAtMs = i.toLong(), durationMs = 1)
        }
        val v = sync(VisualizerState(pullCursorEpoch = 1))
        val r = run(v)
        assertEquals("HistoryFull", reason(r))
        assertEquals(2, r.refused)
        assertEquals(emptyList<String>(), details())
        assertNull(stored().shotPullCursor)
    }

    @Test
    fun `after a 429 a call outside a run is refused unsent, and the drain waits`() {
        limitedPages += 1
        val v = sync(VisualizerState(pullCursorEpoch = 1))
        runBlocking { runCatching { withFreshTokenList(v) } }
        assertEquals("not retried by the HTTP layer", 1, requests.size)
        requests.clear()
        val refused = runBlocking { runCatching { withFreshTokenList(v) } }.exceptionOrNull()
        assertTrue(refused.toString(), refused is VisualizerError.Http && refused.status == 429)
        assertEquals("nothing was sent", emptyList<String>(), requests)
        assertEquals(0, runBlocking { v.drainRetryQueue() })
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `the pacer gives a burst of 30, then one slot every 4 s`() {
        val pacer = Pacer(json, { op, p -> coffee.crema.core.pullRunCall(op, p) }, { clock }, { waits += it }, {})
        runBlocking { kotlinx.coroutines.withContext(PatientRun) { repeat(31) { pacer.take() } } }
        assertEquals(listOf(4_000L), waits.toList())
    }

    @Test
    fun `a drain during a cooldown spends no attempt on what is queued`() {
        history["s1"] = StoredShot(id = "s1", completedAtMs = 1_700_000_000_000, durationMs = 30_000, samples = listOf(sample))
        val v = sync(
            VisualizerState(
                pullCursorEpoch = 1,
                retryQueue = listOf(QueuedUpload("s1", 0, 0L)),
                paceBucket = coffee.crema.core.PaceBucket(tatMs = 0, lastNowMs = 0, cooldownUntilMs = clock + 600_000),
            ),
        )
        assertEquals(0, runBlocking { v.drainRetryQueue() })
        assertEquals(0, stored().retryQueue.single().attempts)
        assertEquals(emptyList<String>(), requests)
    }

    @Test
    fun `two remote copies of one shot bind it once - the second is added`() {
        val completedAt = 1_700_000_000_000L
        history["local"] = StoredShot(id = "local", completedAtMs = completedAt, durationMs = 30_000, samples = listOf(sample))
        val key = (completedAt - 30_000) / 1000
        rows += Triple("copy-a", key, t0 + 1)
        rows += Triple("copy-b", key, t0 + 2)
        val v = sync(VisualizerState(pullCursorEpoch = 1))
        run(v)
        assertEquals("copy-b", history["local"]?.visualizerId)
        assertEquals(listOf("copy-a"), history.values.filter { it.id != "local" }.mapNotNull { it.visualizerId })
    }

    @Test
    fun `a poison row that comes through takes the detail's updated_at as its version`() {
        addRows(1)
        val v = sync(VisualizerState(pullCursorEpoch = 1, rowFailures = listOf(PoisonEntry("r0", 3u, clock - 1, 0, clock - 1))))
        run(v)
        assertEquals(t0 + 10_000, history.values.single { it.visualizerId == "r0" }.visualizerUpdatedAt)
        assertEquals("the walk skips it: up to date", listOf("GET /api/shots/r0"), details())
    }

    @Test
    fun `one run at a time - a second while one walks is Busy and sends nothing`() {
        addRows(2)
        val v = sync(VisualizerState(pullCursorEpoch = 1))
        listGate = kotlinx.coroutines.CompletableDeferred()
        val first = CoroutineScope(Dispatchers.Default).launch { v.pullRun(emptyList()) }
        repeat(200) { if (requests.isEmpty()) Thread.sleep(10) }
        val sent = requests.size
        // Bounded: without the lock, the second run would wait on the gate too.
        val second = runBlocking { kotlinx.coroutines.withTimeoutOrNull(5_000) { v.pullRun(emptyList()) } }
        val whileHeld = requests.size
        listGate!!.complete(Unit)
        runBlocking { first.join() }
        assertEquals("Busy", second?.let(::kind))
        assertEquals("the second sent nothing", sent, whileHeld)
    }

    private suspend fun withFreshTokenList(v: VisualizerSync) = client.listShotsPage("tok", 1, 100, null)
}

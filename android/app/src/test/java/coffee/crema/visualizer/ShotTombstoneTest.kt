package coffee.crema.visualizer

import android.content.ContextWrapper
import coffee.crema.history.StoredShot
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A shot deleted on Android stays deleted (core/shell fixes F10 PR 4; the web
 * half was F18a). Android used to hard-delete and hand the reconcile
 * `deletedAt: null` for every shot, so a deleted synced shot came straight
 * back on the next pull, and it had no remote shot delete at all. Now a
 * synced shot leaves a tombstone the reconcile sees, and "Also delete on
 * Visualizer" sends `DELETE /shots/{id}` until it lands.
 *
 * The reconcile runs through the host-built core planner (`reconcileShots`).
 */
class ShotTombstoneTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File = Files.createTempDirectory("viz-tombstones").toFile()
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

    /** What the server answers a shot DELETE with. */
    private var deleteStatus = HttpStatusCode.NoContent
    private val requests = mutableListOf<String>()
    private val engine = MockEngine { req ->
        requests += "${req.method.value} ${req.url.encodedPath}"
        val status = if (req.method.value == "DELETE") deleteStatus else HttpStatusCode.OK
        respond("{}", status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    private val client = VisualizerClient(json, engine, retryBaseDelayMs = 1)
    private val job = SupervisorJob()
    private val pulled = mutableListOf<StoredShot>()
    private val refused = mutableSetOf<String>()
    private val filled = mutableListOf<Pair<String, String>>()

    private fun sync() = VisualizerSync(
        store = store,
        client = client,
        json = json,
        scope = CoroutineScope(job + Dispatchers.Default),
        clientId = "cid",
        appVersion = "t",
        notify = {},
        onShotSynced = { _, _ -> },
        onPulledShots = {
            pulled += it
            refused.intersect(it.map(StoredShot::id).toSet())
        },
        onFillBarista = { id, name -> filled += id to name },
        currentShot = { id -> StoredShot(id = id, completedAtMs = 1, durationMs = 30_000, visualizerId = "v-bound") },
    ).also { s ->
        runBlocking {
            store.save(VisualizerState(tokens = TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000)))
            s.load()
        }
    }

    private fun settle() = runBlocking { job.children.toList().forEach { it.join() } }

    private fun deletes() = requests.filter { it.startsWith("DELETE") }

    /** The remote copy a pull returns for the synced shot. */
    private val wire: JsonObject = buildJsonObject {
        put("id", "v-1")
        put("clock", 1_700_000_000_000L)
        put("duration_ms", 30_000)
        put("profile_title", "Test")
        put("updated_at_ms", 1_700_000_000_000L)
        put("tag_list", buildJsonArray {})
    }

    @After
    fun tearDown() {
        client.close()
        dir.deleteRecursively()
    }

    @Test
    fun `cap_refusal_skips_and_continues`() {
        val s = sync()
        val bound = StoredShot(id = "local", completedAtMs = 1, durationMs = 30_000, visualizerId = "v-bound")
        val update = buildJsonObject {
            put("id", "v-bound")
            put("clock", 1L)
            put("duration_ms", 30_000)
            put("profile_title", "Test")
            put("updated_at_ms", 1_700_000_000_000L)
            put("barista", "Ada")
            put("tag_list", buildJsonArray {})
        }
        // The new row's id is the one the pull gives it: refuse it by asking first.
        val id = storedShotFromWire(wire, null, json)!!.id
        refused += id

        val applied = runBlocking { s.reconcileAndApply(listOf(bound), listOf(wire, update), emptyMap(), emptyMap()) }

        assertEquals(PullApplied(pulled = 0, refused = 1), applied)
        assertEquals("a later row in the plan still applies", listOf("local" to "Ada"), filled)
        val log = runBlocking { store.load() }.log
        assertEquals("one line for the run, no pull line for the refused row", listOf("skip"), log.map { it.direction })
    }

    @Test
    fun `a deleted synced shot is not pulled back`() {
        // Control: with no tombstone the planner adds the remote shot.
        val control = sync()
        assertEquals(1, runBlocking { control.reconcileAndApply(emptyList(), listOf(wire), emptyMap(), emptyMap()) }.pulled)
        pulled.clear()

        val s = sync()
        s.tombstoneShot("s1", "v-1", deleteRemote = false)
        settle()
        assertEquals(0, runBlocking { s.reconcileAndApply(emptyList(), listOf(wire), emptyMap(), emptyMap()) }.pulled)
        assertTrue("nothing pulled back: $pulled", pulled.isEmpty())
        // A local-only delete never touches the remote copy and keeps its tombstone.
        assertTrue(deletes().isEmpty())
        assertEquals(listOf("s1"), s.shotTombstones.map { it.id })
    }

    @Test
    fun `a tombstone is deleted remotely then purged`() {
        val s = sync()
        s.tombstoneShot("s1", "v-1", deleteRemote = true)
        settle()
        assertEquals(listOf("DELETE /api/shots/v-1"), deletes())
        assertTrue("purged: ${s.shotTombstones}", s.shotTombstones.isEmpty())
        // Already gone remotely (404) counts as done too.
        deleteStatus = HttpStatusCode.NotFound
        s.tombstoneShot("s2", "v-2", deleteRemote = true)
        settle()
        assertTrue(s.shotTombstones.isEmpty())
    }

    @Test
    fun `a failed remote delete keeps the tombstone`() {
        deleteStatus = HttpStatusCode.InternalServerError
        val s = sync()
        s.tombstoneShot("s1", "v-1", deleteRemote = true)
        settle()
        assertTrue(deletes().isNotEmpty())
        assertEquals(listOf("s1"), s.shotTombstones.map { it.id })
        // The next sync retries it; this time it lands and the tombstone goes.
        deleteStatus = HttpStatusCode.NoContent
        requests.clear()
        s.syncNow(emptyList(), includeBeans = false)
        settle()
        assertEquals("DELETE /api/shots/v-1", deletes().first())
        assertTrue("purged after the retry: ${s.shotTombstones}", s.shotTombstones.isEmpty())
    }
}

package coffee.crema.visualizer

import android.content.ContextWrapper
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.applyRoasterDelete
import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import coffee.crema.beans.planRoasterDelete
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import kotlin.test.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #488 review SF1 end to end, on the host build of the core: a roaster on
 * Visualizer deleted "from this device only" stays a tombstone, the next bean
 * sync's pull doesn't add it back, and the bags it leaves keep their roaster,
 * here and on Visualizer (nothing pushes `roaster_id: null`).
 */
class RoasterTombstoneSyncTest {
    @Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val dir: File = Files.createTempDirectory("roaster-tombstone").toFile()
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

    private val remoteRoasters = listOf(buildJsonObject { put("id", "vz-r"); put("name", "Sey") })
    private val remoteBags = listOf(
        buildJsonObject { put("id", "vz-a"); put("name", "Gesha"); put("roaster_id", "vz-r") },
    )
    private data class Req(val method: String, val path: String, val body: String)
    private val requests = mutableListOf<Req>()

    private val engine = MockEngine { req ->
        val path = req.url.encodedPath.removePrefix("/api")
        val method = req.method.value
        requests += Req(method, path, req.body.toByteArray().decodeToString())
        val (status, body) = when {
            method == "GET" && path == "/roasters" -> HttpStatusCode.OK to page(remoteRoasters)
            method == "GET" && path == "/coffee_bags" -> HttpStatusCode.OK to page(remoteBags)
            method == "GET" && Regex("^/(roasters|coffee_bags)/[^/]+$").matches(path) -> {
                val id = path.substringAfterLast('/')
                val rows = if (path.startsWith("/roasters")) remoteRoasters else remoteBags
                rows.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == id }
                    ?.let { HttpStatusCode.OK to it.toString() } ?: (HttpStatusCode.NotFound to "{}")
            }
            method == "POST" -> HttpStatusCode.Created to """{"id":"vz-new"}"""
            else -> HttpStatusCode.OK to "{}"
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun page(rows: List<JsonObject>) = buildJsonObject {
        put("data", JsonArray(rows))
        put("paging", buildJsonObject { put("pages", 1) })
    }.toString()

    private val client = VisualizerClient(json, engine, retryBaseDelayMs = 1)
    private val job = SupervisorJob()
    private val clock = 2_000_000_000_000L

    private var library = BeanLibrary()

    @After
    fun tearDown() {
        client.close()
        dir.deleteRecursively()
    }

    private fun runSync(): BeanSyncResult {
        runBlocking {
            store.save(
                VisualizerState(
                    tokens = TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000),
                    premium = true,
                    beanLastSyncAt = 5_000,
                ),
            )
        }
        val sync = VisualizerSync(
            store = store,
            client = client,
            json = json,
            scope = CoroutineScope(job + Dispatchers.Default),
            clientId = "cid",
            appVersion = "t",
            notify = {},
            onShotSynced = { _, _ -> },
            now = { clock },
            beanLibrary = { library },
            editBeanLibrary = { edit -> library = edit(library) },
            beanSyncCore = BeanSyncCore.Native,
        )
        runBlocking { sync.load() }
        return runBlocking { sync.runBeanSync() }!!
    }

    @Test
    fun `a roaster deleted from this device only stays deleted after a pull and its bags keep their roaster`() {
        val sey = newRoaster("Sey", 1_000).copy(id = "roaster:sey", visualizerId = "vz-r")
        val gesha = newBean("Gesha", sey.id, null, null, 1_000).copy(id = "bean:a", visualizerId = "vz-a")
        val before = BeanLibrary(beans = listOf(gesha), roasters = listOf(sey))
        val plan = assertNotNull(
            planRoasterDelete(json, before.roasters, before.beans, sey.id, cascade = false, activeBeanId = null, localOnly = true),
        )
        library = applyRoasterDelete(before, plan, nowMs = 9_000)

        val r = runSync()
        assertTrue("the run completes: ${r.error}", r.ok)
        assertEquals("the pull adds no roaster back", listOf(sey.id), library.roasters.map { it.id })
        assertEquals(9_000L, library.roasters.single().deletedAt)
        assertEquals("the bag keeps its roaster", sey.id, library.beans.single().roasterId)
        val cleared = requests.filter { it.method != "GET" }.filter { req ->
            runCatching { json.parseToJsonElement(req.body).jsonObject["coffee_bag"]?.jsonObject?.get("roaster_id") }
                .getOrNull() == JsonNull
        }
        assertTrue("nothing clears a bag's roaster on Visualizer: $cleared", cleared.isEmpty())
        assertTrue("no roaster is created: $requests", requests.none { it.method == "POST" && it.path == "/roasters" })
    }
}

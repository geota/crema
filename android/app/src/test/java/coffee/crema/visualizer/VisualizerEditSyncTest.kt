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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The History edit → Visualizer PATCH on a non-Premium account (upstream
 * Visualizer: `private_notes`, the tasting scores incl. `flavor`, `tag_list`,
 * `coffee_bag_id`, … are Premium-only). The tier is probed with `/me` and
 * cached; an unknown tier counts as free; the drop is silent and a PATCH left
 * with nothing to apply is never sent.
 *
 * The core `visualizer_shot_patch_json` is stood in by a JVM fake applying the
 * same rule for the fields these shots carry (the rule itself is pinned by the
 * core tests), so these tests pin the shell wiring: which tier is passed, that
 * a null body means no request, and that nothing reaches the user.
 */
class VisualizerEditSyncTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir: File = Files.createTempDirectory("viz-edit-sync").toFile()
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

    /** What the server answers the premium probe with. */
    private var probeStatus = HttpStatusCode.UnprocessableEntity
    private val requests = mutableListOf<Pair<String, String>>()
    private val engine = MockEngine { req ->
        val path = req.url.encodedPath
        requests += "${req.method.value} $path" to req.body.toByteArray().decodeToString()
        val (status, body) = when {
            path.endsWith("/me") -> HttpStatusCode.OK to """{"id":"u1","name":"Ada","public":true}"""
            path.contains("/coffee_bags/") -> probeStatus to """{"error":"x"}"""
            else -> HttpStatusCode.OK to "{}"
        }
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
    }
    private val client = VisualizerClient(json, engine, retryBaseDelayMs = 1)

    private val job = SupervisorJob()
    private val notices = mutableListOf<String>()
    private val tiersSeen = mutableListOf<Boolean?>()

    private val premiumOnly = setOf("private_notes", "flavor", "tag_list", "coffee_bag_id", "metadata", "image")

    /** JVM stand-in for core `visualizer_shot_patch_json(inputs, premium)`. */
    private val fakeCore: (String, Boolean?) -> String? = { inputsJson, premium ->
        tiersSeen += premium
        val i = json.parseToJsonElement(inputsJson).jsonObject
        fun str(k: String) = (i[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val body = buildJsonObject {
            (i["rating"] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }?.let { put("flavor", it * 3) }
            (i["notes"] as? JsonPrimitive)?.contentOrNull?.let { put("private_notes", "<p>$it</p>") }
            str("grinderModel")?.let { put("grinder_model", it) }
            str("privacy")?.let { put("privacy", it) }
        }.filterKeys { premium == true || it !in premiumOnly }
        val applies = body.keys.any { it == "grinder_model" || (premium == true && it in premiumOnly) }
        if (applies) JsonObject(body).toString() else null
    }

    private fun sync(grinder: String? = null) = VisualizerSync(
        store = store,
        client = client,
        json = json,
        scope = CoroutineScope(job + Dispatchers.Default),
        clientId = "cid",
        appVersion = "t",
        notify = { notices += it },
        onShotSynced = { _, _ -> },
        grinderModel = { grinder },
        shotPatchBody = fakeCore,
    )

    private fun seed(premium: Boolean?) = runBlocking {
        store.save(
            VisualizerState(
                tokens = TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000),
                premium = premium,
                prefs = DEFAULT_VISUALIZER_SYNC_PREFS.copy(includeNotes = true),
            ),
        )
    }

    /** Wait for everything the controller launched (it runs on real dispatchers). */
    private fun settle() = runBlocking { job.children.toList().forEach { it.join() } }

    private val edited = StoredShot(
        id = "s1",
        completedAtMs = 1,
        durationMs = 30_000,
        rating = 4,
        notes = "juicy",
        visualizerId = "v-1",
    )

    private fun patches() = requests.filter { it.first.startsWith("PATCH") && it.first.contains("/shots/") }

    private fun shotBody(i: Int = 0) = json.parseToJsonElement(patches()[i].second).jsonObject["shot"]!!.jsonObject

    @After
    fun tearDown() {
        client.close()
        dir.deleteRecursively()
    }

    @Test
    fun `an unprobed tier counts as free - a rating and notes edit sends nothing and says nothing`() {
        seed(premium = null)
        val s = sync().also { runBlocking { it.load() } }
        s.patchEditedShot(edited)
        settle()
        assertEquals(listOf<Boolean?>(null), tiersSeen)
        assertTrue("no request for a Premium-only edit: $requests", requests.isEmpty())
        assertTrue("silent: $notices", notices.isEmpty())
    }

    @Test
    fun `a free account probed at refresh never sends the Premium-only fields`() {
        seed(premium = null)
        probeStatus = HttpStatusCode.Forbidden
        val s = sync(grinder = "EG-1").also { runBlocking { it.load() } }
        s.refreshAccount()
        settle()
        assertEquals(false, runBlocking { store.load() }.premium)
        assertTrue(requests.any { it.first == "PATCH /api/coffee_bags/$PREMIUM_PROBE_ID" })

        s.patchEditedShot(edited)
        settle()
        assertEquals(false, tiersSeen.last())
        assertEquals(1, patches().size)
        val body = shotBody()
        assertEquals("EG-1", body["grinder_model"]!!.jsonPrimitive.content)
        for (key in premiumOnly) assertFalse("$key leaked: $body", key in body)
        assertTrue("silent: $notices", notices.isEmpty())
    }

    @Test
    fun `a premium account keeps private_notes and flavor`() {
        seed(premium = null)
        probeStatus = HttpStatusCode.NotFound
        val s = sync().also { runBlocking { it.load() } }
        s.refreshAccount()
        settle()
        assertEquals(true, runBlocking { store.load() }.premium)

        s.patchEditedShot(edited)
        settle()
        assertEquals(true, tiersSeen.last())
        val body = shotBody()
        assertEquals(12, body["flavor"]!!.jsonPrimitive.intOrNull)
        assertEquals("<p>juicy</p>", body["private_notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an inconclusive probe keeps the cached tier`() {
        seed(premium = true)
        probeStatus = HttpStatusCode.UnprocessableEntity
        val s = sync().also { runBlocking { it.load() } }
        s.refreshAccount()
        settle()
        assertEquals(true, runBlocking { store.load() }.premium)
        assertTrue(requests.none { it.first.startsWith("PATCH /api/shots") })
    }
}

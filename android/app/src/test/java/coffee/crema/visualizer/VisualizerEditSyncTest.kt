package coffee.crema.visualizer

import android.content.ContextWrapper
import coffee.crema.core.ShotMergeInput
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
import kotlinx.coroutines.async
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
 * `coffee_bag_id`, … are Premium-only). The tier is the web's bean-sync
 * Premium flag, ported: the sentinel `POST /roasters` probe, cached with its
 * time and refreshed on sign-in, at start (at most daily) and on Test; an
 * unknown tier counts as free; the drop is silent and a PATCH left with
 * nothing to apply is never sent.
 *
 * The core `visualizer_shot_patch_json` is stood in by a JVM fake applying the
 * same rule for the fields these shots carry (the rule itself is pinned by the
 * core tests), so these tests pin the shell wiring: which tier is passed, that
 * a null body means no request, and that nothing reaches the user.
 */
class VisualizerEditSyncTest {
    @org.junit.Before fun openStoreSaves() = coffee.crema.persist.SafeFile.openAllForTest()

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

    /** The body of a 403 to the premium probe: `Authorization#check_premium!`'s, by default. */
    private var probeRefusal = """{"error":"You must be a premium user to access this feature."}"""

    /** What the server answers a shot PATCH with (an error = the edit did not land). */
    private var patchStatus = HttpStatusCode.OK
    private var patchBody = "{}"

    /** Runs as a PATCH arrives: an edit made while it is out. */
    private var onPatch: () -> Unit = {}
    private val requests = mutableListOf<Pair<String, String>>()
    private val engine = MockEngine { req ->
        val path = req.url.encodedPath
        requests += "${req.method.value} $path" to req.body.toByteArray().decodeToString()
        val (status, body) = when {
            path.endsWith("/me") -> HttpStatusCode.OK to """{"id":"u1","name":"Ada","public":true}"""
            req.method.value == "POST" && path.endsWith("/roasters") ->
                probeStatus to when {
                    probeStatus.value < 300 -> """{"id":"r-sentinel"}"""
                    probeStatus == HttpStatusCode.Forbidden -> probeRefusal
                    else -> """{"error":"x"}"""
                }
            req.method.value == "PATCH" -> {
                onPatch()
                patchStatus to patchBody
            }
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
            // Barista: absent (null) = omitted; blank = "" (a clear). A free field.
            (i["barista"] as? JsonPrimitive)?.contentOrNull?.let { put("barista", it.trim()) }
        }.filterKeys { premium == true || it !in premiumOnly }
        val applies = body.keys.any {
            it == "grinder_model" || it == "barista" || (premium == true && it in premiumOnly)
        }
        if (applies) JsonObject(body).toString() else null
    }

    private var clock = 1_000_000_000_000L

    /** The local history the pull's barista fill reads, and the fills it made. */
    private val history = mutableMapOf<String, StoredShot>()
    private val fills = mutableListOf<Pair<String, String>>()

    private fun sync(grinder: String? = null) = VisualizerSync(
        currentShot = { history[it] },
        onApplyRemote = { id, patch, _ -> patch.barista?.let { fills += id to it } },
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
        now = { clock },
        // The tests drain by hand; a timer would outlive a "restart".
        retryTimers = false,
        exchangeCode = { _, _ -> TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000) },
        parseCatalogue = { body -> catalogueBodies += body; coffee.crema.core.CataloguePage(emptyList(), 0u, 1u, 1u) },
        parseCatalogueRoasterPage = { body ->
            catalogueBodies += body
            coffee.crema.core.CatalogueRoasterPage(
                listOf(coffee.crema.core.CatalogueRoaster(id = "cr-1", name = "Onyx", website = "https://onyx", country = "USA")),
                1u, 1u, 1u,
            )
        },
    )

    private val catalogueBodies = mutableListOf<String>()

    private fun seed(premium: Boolean?, checkedAt: Long? = null, signedIn: Boolean = true) = runBlocking {
        store.save(
            VisualizerState(
                tokens = if (signedIn) TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000) else null,
                pendingVerifier = if (signedIn) null else "verifier",
                pendingState = if (signedIn) null else "csrf",
                premium = premium,
                premiumCheckedAt = checkedAt,
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

    /** A pulled shot as the core's `WireShot`. */
    private fun wire(barista: String?) = buildJsonObject {
        put("id", "v-1")
        put("clock", 1)
        put("duration_ms", 30_000)
        put("barista", barista)
    }

    private fun probes() = requests.count { it.first == "POST /api/roasters" }

    private fun patches() = requests.filter { it.first.startsWith("PATCH") && it.first.contains("/shots/") }

    private fun shotBody(i: Int = 0) = json.parseToJsonElement(patches()[i].second).jsonObject["shot"]!!.jsonObject

    @After
    fun tearDown() {
        client.close()
        dir.deleteRecursively()
    }

    @Test
    fun `the catalogue search works on a free account and hands the raw body to the core parser`() {
        seed(premium = false)
        val s = sync().also { runBlocking { it.load() } }
        val page = runBlocking { s.searchCatalogue("hambela") }
        assertTrue(page.entries.isEmpty())
        assertEquals(listOf("GET /api/canonical_coffee_bags"), requests.map { it.first })
        assertEquals(listOf("{}"), catalogueBodies)
        assertEquals(0, probes())
    }

    @Test
    fun `the roaster catalogue search and lookup GET canonical_roasters and match by id`() {
        seed(premium = false)
        val s = sync().also { runBlocking { it.load() } }
        val page = runBlocking { s.searchCatalogueRoasters("onyx") }
        assertEquals("Onyx", page.entries.single().name)
        assertEquals("https://onyx", runBlocking { s.lookupCatalogueRoaster("cr-1", "Onyx") }?.website)
        assertEquals(null, runBlocking { s.lookupCatalogueRoaster("cr-9", "Onyx") })
        assertEquals(List(3) { "GET /api/canonical_roasters" }, requests.map { it.first })
    }

    @Test
    fun `the roaster lookup swallows failures`() {
        seed(premium = null, signedIn = false)
        val s = sync().also { runBlocking { it.load() } }
        assertEquals(null, runBlocking { s.lookupCatalogueRoaster("cr-1", "Onyx") })
    }

    @Test
    fun `the catalogue search needs a session`() {
        seed(premium = null, signedIn = false)
        val s = sync().also { runBlocking { it.load() } }
        val err = runCatching { runBlocking { s.searchCatalogue("hambela") } }.exceptionOrNull()
        assertTrue("signed out → Auth: $err", err is VisualizerError.Auth)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an unprobed tier counts as free - a rating and notes edit sends nothing and says nothing`() {
        seed(premium = null)
        val s = sync().also { runBlocking { it.load() } }
        s.markShotEdited(edited.id, annotations = true)
        s.patchEditedShot(edited)
        settle()
        assertEquals(listOf<Boolean?>(null), tiersSeen)
        assertTrue("no request for a Premium-only edit: $requests", requests.isEmpty())
        assertTrue("silent: $notices", notices.isEmpty())
    }

    @Test
    fun `a 403 that isn't about Premium leaves the tier unknown`() {
        seed(premium = null, signedIn = false)
        probeStatus = HttpStatusCode.Forbidden
        probeRefusal = """{"error":"Confirm your email address first. Sign in at visualizer.coffee to resend the confirmation email."}"""
        val s = sync().also { runBlocking { it.load() } }
        s.handleCallback(code = "code", returnedState = "csrf", error = null)
        settle()
        assertEquals(1, probes())
        assertEquals(null, runBlocking { store.load() }.premium)
    }

    @Test
    fun `a free account probed at sign-in never sends the Premium-only fields`() {
        seed(premium = null, signedIn = false)
        probeStatus = HttpStatusCode.Forbidden
        val s = sync(grinder = "EG-1").also { runBlocking { it.load() } }
        s.handleCallback(code = "code", returnedState = "csrf", error = null)
        settle()
        val stored = runBlocking { store.load() }
        assertEquals(false, stored.premium)
        assertEquals(clock, stored.premiumCheckedAt)
        assertEquals(1, probes())
        assertTrue("no coffee-bag probe any more: $requests", requests.none { it.first.contains("/coffee_bags") })

        s.markShotEdited(edited.id, annotations = true)
        s.patchEditedShot(edited)
        settle()
        assertEquals(false, tiersSeen.last())
        assertEquals(1, patches().size)
        val body = shotBody()
        assertEquals("EG-1", body["grinder_model"]!!.jsonPrimitive.content)
        for (key in premiumOnly) assertFalse("$key leaked: $body", key in body)
    }

    @Test
    fun `a rating edit never sends the barista, so one set on Visualizer survives (final review 06 #1)`() {
        seed(premium = true)
        val s = sync().also { runBlocking { it.load() } }
        // A shot pulled before the barista was read: none locally.
        s.markShotEdited(edited.id, annotations = true)
        s.patchEditedShot(edited.copy(barista = null))
        settle()
        assertEquals(1, patches().size)
        assertFalse("barista must not ride a rating edit: ${shotBody()}", "barista" in shotBody())
    }

    @Test
    fun `a barista edit sends it, cleared as empty, and stops once it landed`() {
        seed(premium = false)
        val s = sync().also { runBlocking { it.load() } }
        s.markBaristaEdited(edited.id)
        s.patchEditedShot(edited.copy(barista = null))
        settle()
        assertEquals("", shotBody(0)["barista"]!!.jsonPrimitive.content)
        s.markBaristaEdited(edited.id)
        s.patchEditedShot(edited.copy(barista = " Ana "))
        settle()
        assertEquals("Ana", shotBody(1)["barista"]!!.jsonPrimitive.content)
        // Landed: a later rating edit on a free account has nothing left to send.
        s.markShotEdited(edited.id, annotations = true)
        s.patchEditedShot(edited.copy(barista = "Ana"))
        settle()
        assertEquals(2, patches().size)
    }

    @Test
    fun `a premium account found at start keeps private_notes and flavor`() {
        seed(premium = null)
        probeStatus = HttpStatusCode.Created
        val s = sync().also { runBlocking { it.load() } }
        s.refreshPremiumIfStale()
        settle()
        assertEquals(true, runBlocking { store.load() }.premium)
        assertTrue("sentinel cleaned up", requests.any { it.first == "DELETE /api/roasters/r-sentinel" })

        s.markShotEdited(edited.id, annotations = true)
        s.patchEditedShot(edited)
        settle()
        assertEquals(true, tiersSeen.last())
        val body = shotBody()
        assertEquals(12, body["flavor"]!!.jsonPrimitive.intOrNull)
        assertEquals("<p>juicy</p>", body["private_notes"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the start check runs at most once per 24 h`() {
        seed(premium = false, checkedAt = clock - 60 * 60 * 1000)
        probeStatus = HttpStatusCode.Created
        val s = sync().also { runBlocking { it.load() } }
        s.refreshPremiumIfStale()
        settle()
        assertEquals(0, probes())
        assertEquals(false, runBlocking { store.load() }.premium)

        clock += VisualizerSync.PREMIUM_REFRESH_INTERVAL_MS
        s.refreshPremiumIfStale()
        settle()
        assertEquals(1, probes())
        val stored = runBlocking { store.load() }
        assertEquals(true, stored.premium)
        assertEquals(clock, stored.premiumCheckedAt)
    }

    @Test
    fun `the start check does nothing while signed out`() {
        seed(premium = null, signedIn = false)
        val s = sync().also { runBlocking { it.load() } }
        s.refreshPremiumIfStale()
        settle()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `Test connection re-probes even when the flag is fresh, and settings open does not`() {
        seed(premium = true, checkedAt = clock)
        probeStatus = HttpStatusCode.Forbidden
        val s = sync().also { runBlocking { it.load() } }
        s.refreshAccount()
        settle()
        assertEquals(0, probes())

        s.testConnection()
        settle()
        assertEquals(1, probes())
        assertEquals(false, runBlocking { store.load() }.premium)
        assertTrue(notices.last(), notices.last().contains("free tier"))
    }

    @Test
    fun `an inconclusive probe keeps the cached tier`() {
        seed(premium = true, checkedAt = 5)
        probeStatus = HttpStatusCode.UnprocessableEntity
        val s = sync().also { runBlocking { it.load() } }
        s.refreshPremiumIfStale()
        settle()
        assertEquals(1, probes())
        val stored = runBlocking { store.load() }
        assertEquals(true, stored.premium)
        assertEquals(5L, stored.premiumCheckedAt)
        assertTrue(requests.none { it.first.startsWith("PATCH /api/shots") })
    }

    @Test
    fun `a barista clear that failed to reach Visualizer is not refilled by a pull, even after a restart (V3 #6, V2 P3-4)`() {
        seed(premium = false)
        val cleared = edited.copy(barista = null)
        history[cleared.id] = cleared
        val remote = wire("Ana")

        // The clear's PATCH fails (Visualizer unreachable).
        patchStatus = HttpStatusCode.ServiceUnavailable
        val s = sync().also { runBlocking { it.load() } }
        s.markBaristaEdited(cleared.id)
        s.patchEditedShot(cleared)
        settle()
        assertEquals("", shotBody(0)["barista"]!!.jsonPrimitive.content)
        // A pull this session: the remote still says "Ana", the local is blank.
        s.mergeAndApply(cleared.id, remote)
        assertEquals(emptyList<Pair<String, String>>(), fills)

        // A restart: the pending mark was stored, so the pull still leaves the
        // clear alone, and the next edit carries it.
        patchStatus = HttpStatusCode.OK
        val restarted = sync().also { runBlocking { it.load() } }
        restarted.mergeAndApply(cleared.id, remote)
        assertEquals(emptyList<Pair<String, String>>(), fills)
        restarted.patchEditedShot(cleared)
        settle()
        assertEquals("", shotBody(1)["barista"]!!.jsonPrimitive.content)

        // The clear landed: the mark is gone (also on disk) and the fill rule applies again.
        assertEquals(emptyMap<String, PendingEdit>(), runBlocking { store.load() }.pendingEdits)
        restarted.mergeAndApply(cleared.id, remote)
        assertEquals(listOf(cleared.id to "Ana"), fills)
    }

    @Test
    fun `a barista edit made before load keeps its mark and the stored sign-in (C3 #9)`() {
        seed(premium = false)
        val cleared = edited.copy(barista = null)
        history[cleared.id] = cleared
        // The clear lands right after launch, before load() has read the file.
        val s = sync()
        s.markBaristaEdited(cleared.id)
        settle()
        runBlocking { s.load() }
        settle()
        // The pull leaves the clear alone, and the mark is on disk next to
        // the stored state (the early store did not overwrite it with defaults).
        s.mergeAndApply(cleared.id, wire("Ana"))
        assertEquals(emptyList<Pair<String, String>>(), fills)
        val stored = runBlocking { store.load() }
        assertEquals(mapOf(cleared.id to PendingEdit(barista = true)), stored.pendingEdits)
        assertTrue("still signed in", stored.tokens != null)
    }

    @Test
    fun `erasing all data forgets pending barista edits`() {
        seed(premium = false)
        val s = sync().also { runBlocking { it.load() } }
        s.markBaristaEdited(edited.id)
        settle()
        assertEquals(mapOf(edited.id to PendingEdit(barista = true)), runBlocking { store.load() }.pendingEdits)
        runBlocking { s.eraseAll() }
        history[edited.id] = edited.copy(barista = null)
        s.mergeAndApply(edited.id, wire("Ana"))
        assertEquals(listOf(edited.id to "Ana"), fills)
    }

    @Test
    fun `the old barista marks migrate into pending edits on load`() {
        runBlocking {
            store.save(
                VisualizerState(
                    tokens = TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000),
                    baristaPending = setOf("s1"),
                    pendingEdits = mapOf("s2" to PendingEdit(annotations = true)),
                ),
            )
        }
        val s = sync().also { runBlocking { it.load() } }
        settle()
        val stored = runBlocking { store.load() }
        assertEquals(mapOf("s1" to PendingEdit(barista = true), "s2" to PendingEdit(annotations = true)), stored.pendingEdits)
        assertEquals(emptySet<String>(), stored.baristaPending)
        // The migrated mark still keeps a pull off the barista.
        history["s1"] = edited.copy(id = "s1", barista = null)
        s.mergeAndApply("s1", wire("Ana"))
        assertEquals(emptyList<Pair<String, String>>(), fills)
    }

    @Test
    fun `a barista edit sends no journal field`() {
        seed(premium = true)
        val s = sync().also { runBlocking { it.load() } }
        s.markBaristaEdited(edited.id)
        s.patchEditedShot(edited.copy(barista = "Ana"))
        settle()
        assertEquals(setOf("barista"), shotBody().keys)
    }

    @Test
    fun `terminal_patch_clears_mark - 404, 403 and 422 clear the barista mark and say so`() {
        for ((status, body) in listOf(
            HttpStatusCode.NotFound to "{}",
            HttpStatusCode.Forbidden to """{"error":"You are not authorized to perform this action."}""",
            HttpStatusCode.UnprocessableEntity to """{"error":"Invalid"}""",
        )) {
            dir.listFiles()?.forEach { it.deleteRecursively() }
            notices.clear()
            seed(premium = true)
            patchStatus = status
            patchBody = body
            val s = sync().also { runBlocking { it.load() } }
            s.markBaristaEdited(edited.id)
            s.patchEditedShot(edited.copy(barista = "Ana"))
            settle()
            val stored = runBlocking { store.load() }
            assertEquals("$status clears it", emptyMap<String, PendingEdit>(), stored.pendingEdits)
            assertTrue("$status says so: $notices", notices.any { it.startsWith("Visualizer won't take this shot's edit") })
            assertTrue("$status logged", stored.log.any { it.error?.startsWith("This edit can't reach Visualizer") == true })
        }
    }

    @Test
    fun `failed_patch_retries_from_current_row - the drain sends the newest values`() {
        seed(premium = true)
        history[edited.id] = edited.copy(rating = 3)
        patchStatus = HttpStatusCode.ServiceUnavailable
        val s = sync().also { runBlocking { it.load() } }
        s.markShotEdited(edited.id, annotations = true)
        s.patchEditedShot(history.getValue(edited.id))
        settle()
        val failed = runBlocking { store.load() }.pendingEdits.getValue(edited.id)
        assertTrue("kept, backed off: $failed", failed.annotations && failed.attempts == 1 && failed.nextAttemptAt > clock)
        // Edited again meanwhile; the drain runs once the backoff is over.
        history[edited.id] = edited.copy(rating = 5)
        patchStatus = HttpStatusCode.OK
        val before = patches().size
        runBlocking { s.drainRetryQueue() }
        assertEquals("not before it's due", before, patches().size)
        clock = failed.nextAttemptAt
        runBlocking { s.drainRetryQueue() }
        assertEquals(15, shotBody(patches().size - 1)["flavor"]!!.jsonPrimitive.intOrNull)
        assertEquals(emptyMap<String, PendingEdit>(), runBlocking { store.load() }.pendingEdits)
    }

    @Test
    fun `an edit made while its PATCH was out stays marked`() {
        seed(premium = true)
        history[edited.id] = edited.copy(rating = 3)
        val s = sync().also { runBlocking { it.load() } }
        s.markShotEdited(edited.id, annotations = true)
        onPatch = { history[edited.id] = edited.copy(rating = 4) }
        runBlocking { s.pushPendingEdit(edited.id) }
        assertEquals(1, patches().size)
        assertEquals(mapOf(edited.id to PendingEdit(annotations = true)), runBlocking { store.load() }.pendingEdits)
    }

    @Test
    fun `a pull leaves a row edited since it began alone, and holds the cursor when it lacks its curve`() {
        seed(premium = true)
        val s = sync().also { runBlocking { it.load() } }
        history["s1"] = edited.copy(id = "s1", barista = "Bo")
        val before = ShotMergeInput(tags = emptyList(), tagsPending = false, barista = null, baristaPending = false, hasSeries = false, hasProfile = false, isBrew = false)
        assertTrue("held: no curve", s.mergeAndApply("s1", wire("Ana"), before = before))
        assertEquals(emptyList<Pair<String, String>>(), fills)
        // Not edited: the merge applies.
        history["s2"] = edited.copy(id = "s2", barista = null)
        val unchanged = ShotMergeInput(tags = emptyList(), tagsPending = false, barista = null, baristaPending = false, hasSeries = false, hasProfile = false, isBrew = false)
        assertFalse(s.mergeAndApply("s2", wire("Ana"), before = unchanged))
        assertEquals(listOf("s2" to "Ana"), fills)
    }

    @Test
    fun `storeMarks has the mark on disk when it returns, even before load`() {
        seed(premium = false)
        val s = sync()
        assertTrue(s.markShotEdited(edited.id, barista = true))
        val stored = CoroutineScope(Dispatchers.Default).async {
            s.storeMarks()
            store.load().pendingEdits
        }
        runBlocking { s.load() }
        assertEquals(mapOf(edited.id to PendingEdit(barista = true)), runBlocking { stored.await() })
    }

    @Test
    fun `a stored barista clear is not refilled by a pull after a kill (V3 #6)`() {
        seed(premium = false)
        val cleared = edited.copy(barista = null)
        history[cleared.id] = cleared
        val s = sync().also { runBlocking { it.load() } }
        // The edit path: the mark first, stored, then the row's save; a kill after.
        s.markShotEdited(cleared.id, barista = true)
        runBlocking { s.storeMarks() }
        val restarted = sync().also { runBlocking { it.load() } }
        restarted.mergeAndApply(cleared.id, wire("Ana"))
        assertEquals(emptyList<Pair<String, String>>(), fills)
    }

    @Test
    fun `a mark already set and due asks for no store, so a keystroke rewrites nothing`() {
        seed(premium = false)
        val s = sync().also { runBlocking { it.load() } }
        assertTrue(s.markShotEdited(edited.id, annotations = true))
        assertFalse(s.markShotEdited(edited.id, annotations = true))
        assertTrue("a new field is a change", s.markShotEdited(edited.id, barista = true))
    }

    @Test
    fun `a Premium refusal probes the tier once a day - Free retries what a free account takes`() {
        seed(premium = true)
        patchStatus = HttpStatusCode.Forbidden
        patchBody = probeRefusal
        probeStatus = HttpStatusCode.Forbidden
        val s = sync().also { runBlocking { it.load() } }
        s.markShotEdited(edited.id, annotations = true)
        runBlocking { s.pushPendingEdit(edited.id, fallback = edited) }
        assertEquals(1, probes())
        val stored = runBlocking { store.load() }
        assertEquals(false, stored.premium)
        assertEquals("due again at once", 0L, stored.pendingEdits.getValue(edited.id).nextAttemptAt)
        // Free now: the journal's Premium-only fields drop, nothing is left, and the mark clears.
        runBlocking { s.pushPendingEdit(edited.id, fallback = edited) }
        assertEquals(emptyMap<String, PendingEdit>(), runBlocking { store.load() }.pendingEdits)
    }

    @Test
    fun `a Premium refusal with Premium confirmed is for good, and an unanswered probe waits a day`() {
        seed(premium = true)
        patchStatus = HttpStatusCode.Forbidden
        patchBody = probeRefusal
        probeStatus = HttpStatusCode.InternalServerError
        val s = sync().also { runBlocking { it.load() } }
        s.markShotEdited(edited.id, annotations = true)
        runBlocking { s.pushPendingEdit(edited.id, fallback = edited) }
        runBlocking { s.pushPendingEdit(edited.id, fallback = edited) }
        assertEquals("stamped, though unanswered", 1, probes())
        assertTrue(runBlocking { store.load() }.pendingEdits.containsKey(edited.id))
        clock += VisualizerSync.PREMIUM_REFRESH_INTERVAL_MS
        probeStatus = HttpStatusCode.Created
        runBlocking { s.pushPendingEdit(edited.id, fallback = edited) }
        assertEquals(2, probes())
        assertEquals(emptyMap<String, PendingEdit>(), runBlocking { store.load() }.pendingEdits)
        assertTrue(runBlocking { store.load() }.log.any { it.error?.startsWith("This edit can't reach Visualizer") == true })
    }

    @Test
    fun `a Premium refusal during a 429 cooldown keeps the day's tier probe`() {
        seed(premium = true)
        patchStatus = HttpStatusCode.Forbidden
        patchBody = probeRefusal
        val s = sync().also { runBlocking { it.load() } }
        // Another request's 429 lands while this PATCH is out.
        onPatch = { runBlocking { client.pacer!!.noteRateLimited(600_000) } }
        s.markShotEdited(edited.id, annotations = true)
        runBlocking { s.pushPendingEdit(edited.id, fallback = edited) }
        assertEquals(0, probes())
        assertEquals(0L, runBlocking { store.load() }.pendingEdits.getValue(edited.id).tierProbedAt)
    }
}

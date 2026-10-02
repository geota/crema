package coffee.crema.visualizer

import android.content.ContextWrapper
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.directoryRoasters
import coffee.crema.beans.mergeSuggestions
import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import coffee.crema.core.Bean
import coffee.crema.core.BeanPushItem
import coffee.crema.core.Roaster
import coffee.crema.core.RoasterDuplicate
import coffee.crema.core.RoasterLinkPatch
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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The Android bean / roaster sync (web `BeanSync.runSync`) driven end to end
 * through [VisualizerSync.runBeanSync] against a MockEngine Visualizer:
 * free vs Premium tier, the mid-run 403 downshift, the 401 → refresh → retry,
 * remote-wins conflicts, and the write bodies (catalogue links omitted when
 * empty, the local duplicate pointer never sent).
 *
 * The native core isn't loadable on the JVM, so [FakeCore] stands in for the
 * `de1_domain::bean_sync` / reconcile / wire functions, applying the same rules
 * for the fields these fixtures carry (the rules themselves are pinned by the
 * core tests). Every write body the shell sends must be exactly what the core
 * returned — that is the wiring these tests pin.
 */
class BeanSyncTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val dir: File = Files.createTempDirectory("bean-sync").toFile()
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

    // ── The fake Visualizer ──────────────────────────────────────────────────
    private var remoteRoasters = listOf<JsonObject>()
    private var remoteBags = listOf<JsonObject>()
    /** `GET /…/{id}` bodies; default = the list row itself. */
    private var bagDetails: List<JsonObject>? = null
    private var failDetails = false
    private val detailGets = mutableListOf<String>()
    private var writeStatus = HttpStatusCode.Created
    private var unauthorizedOnce = false
    private var nextId = 1
    private data class Req(val method: String, val path: String, val auth: String?, val body: String)
    private val requests = mutableListOf<Req>()

    private val engine = MockEngine { req ->
        val path = req.url.encodedPath.removePrefix("/api")
        val method = req.method.value
        val auth = req.headers[HttpHeaders.Authorization]
        requests += Req(method, path, auth, req.body.toByteArray().decodeToString())
        if (unauthorizedOnce && auth == "Bearer tok") {
            return@MockEngine respond("""{"error":"expired"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val (status, body) = when {
            method == "GET" && path == "/roasters" -> HttpStatusCode.OK to page(remoteRoasters)
            method == "GET" && path == "/coffee_bags" -> HttpStatusCode.OK to page(remoteBags)
            method == "GET" && Regex("^/(roasters|coffee_bags)/[^/]+$").matches(path) -> {
                detailGets += path
                val id = path.substringAfterLast('/')
                val rows = if (path.startsWith("/roasters")) remoteRoasters else (bagDetails ?: remoteBags)
                val row = rows.firstOrNull { (it["id"] as? JsonPrimitive)?.contentOrNull == id }
                when {
                    failDetails -> HttpStatusCode.InternalServerError to "{}"
                    row != null -> HttpStatusCode.OK to row.toString()
                    else -> HttpStatusCode.NotFound to "{}"
                }
            }
            method == "POST" -> writeStatus to (if (writeStatus.value < 300) """{"id":"vz-${nextId++}"}""" else """{"error":"premium"}""")
            method == "PATCH" -> writeStatus to "{}"
            method == "DELETE" -> HttpStatusCode.NoContent to ""
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
    private var clock = 2_000_000_000_000L
    private val refreshes = mutableListOf<String>()

    // ── The library the VM would hold ─────────────────────────────────────────
    private var library = BeanLibrary()
    private var applied: BeanSyncResult? = null

    private val core = FakeCore(json)

    private fun sync() = VisualizerSync(
        store = store,
        client = client,
        json = json,
        scope = CoroutineScope(job + Dispatchers.Default),
        clientId = "cid",
        appVersion = "t",
        notify = {},
        onShotSynced = { _, _ -> },
        now = { clock },
        refreshTokens = { rt -> refreshes += rt; TokenSet("tok2", "rt2", expiresAt = System.currentTimeMillis() + 3_600_000) },
        beanLibrary = { library },
        onBeansSynced = { result, beanIds, roasterIds ->
            applied = result
            library = library.copy(
                beans = mergeSyncedRows(library.beans, result.beans, beanIds) { it.id },
                roasters = mergeSyncedRows(library.roasters, result.roasters, roasterIds) { it.id },
            )
        },
        beanSyncCore = core,
    )

    private fun seed(premium: Boolean?, lastSync: Long? = null) = runBlocking {
        store.save(
            VisualizerState(
                tokens = TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000),
                premium = premium,
                beanLastSyncAt = lastSync,
            ),
        )
    }

    private fun run(premium: Boolean?, lastSync: Long? = null): BeanSyncResult {
        seed(premium, lastSync)
        val s = sync()
        runBlocking { s.load() }
        return runBlocking { s.runBeanSync() }!!
    }

    private fun writes() = requests.filter { it.method != "GET" }

    private fun roaster(name: String, vid: String? = null) = newRoaster(name, 1_000).copy(visualizerId = vid)
    private fun bean(name: String, roasterId: String? = null, vid: String? = null, updatedAt: Long = 1_000) =
        newBean(name, roasterId, roastLevel = null, roastedOn = null, nowMs = 1_000).copy(visualizerId = vid, updatedAt = updatedAt)

    @After
    fun tearDown() {
        client.close()
        dir.deleteRecursively()
    }

    // ── Free vs Premium ──────────────────────────────────────────────────────

    @Test
    fun `a free account only pulls - no writes, creates counted as skipped`() {
        val localR = roaster("Local Roaster")
        library = BeanLibrary(beans = listOf(bean("Local bag", localR.id)), roasters = listOf(localR))
        remoteBags = listOf(buildJsonObject { put("id", "vb-1"); put("name", "Remote bag") })
        val r = run(premium = false)
        assertTrue(r.ok)
        assertTrue(r.premiumLocked)
        assertTrue("no writes on a free account: ${writes()}", writes().isEmpty())
        assertEquals(1, r.pulled)
        assertEquals(2, r.skipped) // the local roaster + the local bag
        assertTrue(library.beans.any { it.visualizerId == "vb-1" && it.name == "Remote bag" })
        val stored = runBlocking { store.load() }
        assertEquals(false, stored.premium)
        assertEquals(clock, stored.beanLastSyncAt)
        // The run's lines reach the shared activity log, tagged by entity.
        assertTrue(stored.log.any { it.entity == "bean" && it.direction == "pull" && it.name == "Remote bag" })
        assertTrue(stored.log.any { it.entity == "roaster" && it.direction == "skip" && it.error == "premium required" })
    }

    @Test
    fun `a premium account pushes new roasters and bags and binds the ids`() {
        val localR = roaster("Sey")
        library = BeanLibrary(beans = listOf(bean("Gesha", localR.id)), roasters = listOf(localR))
        val r = run(premium = true)
        assertTrue(r.ok)
        assertEquals(2, r.pushed)
        assertEquals(listOf("POST /roasters", "POST /coffee_bags"), writes().map { "${it.method} ${it.path}" })
        val boundRoaster = library.roasters.single()
        assertEquals("vz-1", boundRoaster.visualizerId)
        assertEquals("vz-2", library.beans.single().visualizerId)
        // The bag is filed under its roaster's NEW remote id.
        val bagBody = json.parseToJsonElement(writes()[1].body).jsonObject["coffee_bag"]!!.jsonObject
        assertEquals("vz-1", (bagBody["roaster_id"] as JsonPrimitive).content)
        assertEquals(true, runBlocking { store.load() }.premium)
    }

    @Test
    fun `the first 403 downshifts the run to read-only and caches free`() {
        val a = roaster("A")
        val b = roaster("B")
        library = BeanLibrary(beans = listOf(bean("Bag", a.id)), roasters = listOf(a, b))
        writeStatus = HttpStatusCode.Forbidden
        val r = run(premium = null)
        assertTrue(r.ok)
        assertTrue(r.premiumLocked)
        assertEquals("one write tried, then read-only: ${writes()}", 1, writes().size)
        assertEquals(1, r.log.count { it.name == "Premium required" })
        assertEquals(false, runBlocking { store.load() }.premium)
    }

    // ── Token refresh ────────────────────────────────────────────────────────

    @Test
    fun `a 401 refreshes the token once and retries with the new one`() {
        unauthorizedOnce = true
        remoteRoasters = listOf(buildJsonObject { put("id", "vr-1"); put("name", "Onyx") })
        val r = run(premium = false)
        assertTrue("run completes after the refresh: ${r.error}", r.ok)
        assertEquals(listOf("rt"), refreshes)
        val first = requests.first()
        assertEquals("Bearer tok", first.auth)
        assertTrue(requests.drop(1).all { it.auth == "Bearer tok2" })
        assertEquals("tok2", runBlocking { store.load() }.tokens?.accessToken)
        assertEquals("Onyx", library.roasters.single().name)
    }

    @Test
    fun `a failed pull ends the run with an error and keeps the last sync time`() {
        seed(premium = true, lastSync = 5)
        val s = VisualizerSync(
            store = store,
            client = VisualizerClient(json, MockEngine { respond("boom", HttpStatusCode.InternalServerError) }, retryBaseDelayMs = 1),
            json = json,
            scope = CoroutineScope(job + Dispatchers.Default),
            clientId = "cid",
            appVersion = "t",
            notify = {},
            onShotSynced = { _, _ -> },
            now = { clock },
            beanLibrary = { library },
            beanSyncCore = core,
        )
        runBlocking { s.load() }
        val r = runBlocking { s.runBeanSync() }!!
        assertFalse(r.ok)
        assertNotNull(r.error)
        val stored = runBlocking { store.load() }
        assertEquals(5L, stored.beanLastSyncAt)
        assertTrue(stored.log.first().error != null)
    }

    // ── Conflicts ────────────────────────────────────────────────────────────

    @Test
    fun `a bound bag edited here since the last sync keeps its edit and pushes it once`() {
        val local = bean("Local edit", vid = "vb-1", updatedAt = 9_000)
        library = BeanLibrary(beans = listOf(local))
        remoteBags = listOf(buildJsonObject { put("id", "vb-1"); put("name", "Remote") })
        val r = run(premium = true, lastSync = 5_000)
        assertTrue(r.ok)
        assertEquals("Local edit", library.beans.single().name)
        val patch = writes().single()
        assertEquals("PATCH /coffee_bags/vb-1", "${patch.method} ${patch.path}")
        assertTrue(patch.body.contains("Local edit"))
    }

    @Test
    fun `a bound bag not edited here takes the remote copy without echoing it back`() {
        val local = bean("Old name", vid = "vb-1", updatedAt = 1_000)
        library = BeanLibrary(beans = listOf(local))
        remoteBags = listOf(buildJsonObject { put("id", "vb-1"); put("name", "Renamed on Visualizer") })
        val r = run(premium = true, lastSync = 5_000)
        assertTrue(r.ok)
        val after = library.beans.single()
        assertEquals(local.id, after.id)
        assertEquals("Renamed on Visualizer", after.name)
        assertEquals("the pull keeps the local stamp", 1_000L, after.updatedAt)
        assertTrue("no echo: ${writes()}", writes().isEmpty())
    }

    // ── No echo across runs (pulled rows keep their stamp) ────────────────────

    /** Run twice against the same remote, carrying the library + store between runs. */
    private fun rerun(): BeanSyncResult {
        val s = sync()
        runBlocking { s.load() }
        return runBlocking { s.runBeanSync() }!!
    }

    @Test
    fun `a pull then an immediate second sync makes zero writes`() {
        remoteRoasters = listOf(buildJsonObject { put("id", "vr-1"); put("name", "Onyx") })
        remoteBags = listOf(buildJsonObject { put("id", "vb-1"); put("name", "Geometry"); put("roaster_id", "vr-1") })
        val first = run(premium = true)
        assertEquals(2, first.pulled)
        assertTrue(writes().isEmpty())
        clock += 60_000
        val second = rerun()
        assertTrue(second.ok)
        assertTrue("second sync wrote: ${writes()}", writes().isEmpty())
    }

    @Test
    fun `a pull then a local edit then a sync makes exactly one write per edited row`() {
        remoteRoasters = listOf(buildJsonObject { put("id", "vr-1"); put("name", "Onyx") })
        remoteBags = listOf(buildJsonObject { put("id", "vb-1"); put("name", "Geometry"); put("roaster_id", "vr-1") })
        run(premium = true)
        val synced = runBlocking { store.load() }.beanLastSyncAt!!
        clock += 60_000
        library = library.copy(
            beans = library.beans.map { it.copy(name = "Geometry (edited)", updatedAt = synced + 1) },
            roasters = library.roasters.map { it.copy(name = "Onyx Coffee Lab", updatedAt = synced + 1) },
        )
        rerun()
        assertEquals(
            listOf("PATCH /roasters/vr-1", "PATCH /coffee_bags/vb-1"),
            writes().map { "${it.method} ${it.path}" },
        )
        assertEquals("Geometry (edited)", library.beans.single().name)
        assertEquals("Onyx Coffee Lab", library.roasters.single().name)
    }

    // ── Direction gates the legs ─────────────────────────────────────────────

    private fun runWith(beansDir: String, roastersDir: String): BeanSyncResult {
        runBlocking {
            store.save(
                VisualizerState(
                    tokens = TokenSet("tok", "rt", expiresAt = System.currentTimeMillis() + 3_600_000),
                    premium = true,
                    beanLastSyncAt = 0,
                    prefs = DEFAULT_VISUALIZER_SYNC_PREFS.copy(beansDirection = beansDir, roastersDirection = roastersDir),
                ),
            )
        }
        return rerun()
    }

    private fun seedDirectionCase() {
        val r = roaster("Local")
        library = BeanLibrary(beans = listOf(bean("Local bag", r.id)), roasters = listOf(r))
        remoteRoasters = listOf(buildJsonObject { put("id", "vr-9"); put("name", "Remote roaster") })
        remoteBags = listOf(buildJsonObject { put("id", "vb-9"); put("name", "Remote bag") })
    }

    @Test
    fun `Pull on Premium pulls and never writes remote`() {
        seedDirectionCase()
        assertTrue(runWith("pull", "pull").ok)
        assertTrue("pull wrote: ${writes()}", writes().isEmpty())
        assertTrue(library.beans.any { it.visualizerId == "vb-9" })
        assertTrue(library.roasters.any { it.visualizerId == "vr-9" })
    }

    @Test
    fun `Backup pushes and never pulls, Off does nothing, mixed directions gate each entity`() {
        seedDirectionCase()
        runWith("backup", "backup")
        assertTrue(requests.none { it.method == "GET" })
        assertEquals(listOf("POST /roasters", "POST /coffee_bags"), writes().map { "${it.method} ${it.path}" })

        seedDirectionCase()
        requests.clear()
        runWith("off", "off")
        assertTrue(requests.isEmpty())

        seedDirectionCase()
        requests.clear()
        runWith("two-way", "pull")
        assertEquals(listOf("POST /coffee_bags"), writes().map { "${it.method} ${it.path}" })
    }

    @Test
    fun `a bound bag not edited since the last sync is not re-sent`() {
        clock = 3_000
        library = BeanLibrary(beans = listOf(bean("Steady", vid = "vb-9", updatedAt = 1_000)))
        val r = run(premium = true, lastSync = 2_000)
        assertTrue(r.ok)
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `a local roaster binds to the same-named remote instead of duplicating`() {
        library = BeanLibrary(roasters = listOf(roaster("onyx")))
        remoteRoasters = listOf(buildJsonObject { put("id", "vr-7"); put("name", "Onyx") })
        val r = run(premium = true)
        assertTrue(r.ok)
        assertEquals(1, library.roasters.size)
        assertEquals("vr-7", library.roasters.single().visualizerId)
        assertTrue("bound, so nothing to POST: ${writes()}", writes().none { it.method == "POST" })
    }

    // ── Write bodies ─────────────────────────────────────────────────────────

    @Test
    fun `catalogue links are sent only when set and the duplicate pointer never leaves`() {
        val canonical = roaster("Sey")
        val dupe = roaster("sey").copy(canonicalRoasterId = canonical.id)
        val linkedBag = bean("Picked", canonical.id).copy(canonicalCoffeeBagId = "cat-bag-1", canonicalRoasterId = "cat-r-1")
        val plainBag = bean("Plain", dupe.id)
        library = BeanLibrary(beans = listOf(linkedBag, plainBag), roasters = listOf(canonical, dupe))
        val r = run(premium = true)
        assertTrue(r.ok)
        val roasterBodies = writes().filter { it.path == "/roasters" }.map { json.parseToJsonElement(it.body).jsonObject["roaster"]!!.jsonObject }
        val bagBodies = writes().filter { it.path == "/coffee_bags" }.map { json.parseToJsonElement(it.body).jsonObject["coffee_bag"]!!.jsonObject }
        // The canonical roaster inherits its picked bag's catalogue roaster id; the dupe sends no link.
        assertEquals("cat-r-1", (roasterBodies[0]["canonical_roaster_id"] as JsonPrimitive).content)
        assertFalse("canonical_roaster_id" in roasterBodies[1])
        assertEquals("cat-bag-1", (bagBodies[0]["canonical_coffee_bag_id"] as JsonPrimitive).content)
        assertFalse("canonical_coffee_bag_id" in bagBodies[1])
        // The LOCAL dedup pointer (a roaster row id) is never on the wire.
        assertTrue(requests.none { it.body.contains(canonical.id) })
        // Every body is the core's, verbatim.
        assertEquals(core.writeBodies, writes().map { json.parseToJsonElement(it.body) }.map { it.toString() })
    }

    @Test
    fun `a bound roaster missing its catalogue link gets a link PATCH`() {
        val r0 = roaster("Onyx", vid = "vr-1")
        val picked = bean("Hambela", r0.id, vid = "vb-1").copy(canonicalRoasterId = "cat-onyx")
        library = BeanLibrary(beans = listOf(picked), roasters = listOf(r0))
        // A row that CARRIES an empty link (a thin row without the key says nothing).
        remoteRoasters = listOf(
            buildJsonObject { put("id", "vr-1"); put("name", "Onyx"); put("canonical_roaster_id", kotlinx.serialization.json.JsonNull) },
        )
        remoteBags = listOf(buildJsonObject { put("id", "vb-1"); put("name", "Hambela"); put("roaster_id", "vr-1") })
        val r = run(premium = true, lastSync = Long.MAX_VALUE)
        assertTrue(r.ok)
        val patch = writes().first { it.method == "PATCH" && it.path == "/roasters/vr-1" }
        assertTrue(patch.body.contains("cat-onyx"))
        assertEquals("cat-onyx", library.roasters.single().catalogueRoasterId)
    }

    // ── A pull never blanks local data (thin list rows) ───────────────────────

    @Test
    fun `a bound bag keeps grams left, bag size and notes when pulled from a summary row`() {
        val r0 = roaster("Onyx", vid = "vr-1").copy(updatedAt = 0)
        val local = bean("Geometry", r0.id, vid = "vb-1", updatedAt = 0)
            .copy(bagSize = 250f, remaining = 180f, notes = "juicy", roastedOn = "2026-09-20")
        library = BeanLibrary(beans = listOf(local), roasters = listOf(r0))
        remoteRoasters = listOf(buildJsonObject { put("id", "vr-1"); put("name", "Onyx") })
        remoteBags = listOf(buildJsonObject { put("id", "vb-1"); put("name", "Geometry Natural"); put("roaster_id", "vr-1") })
        val r = run(premium = true, lastSync = 1_000)
        assertTrue(r.ok)
        val after = library.beans.single()
        assertEquals("Geometry Natural", after.name)
        assertEquals(250f, after.bagSize)
        assertEquals(180f, after.remaining)
        assertEquals("juicy", after.notes)
        assertEquals("2026-09-20", after.roastedOn)
        assertTrue("bound rows merge the summary only: $detailGets", detailGets.isEmpty())
        assertTrue(writes().isEmpty())
    }

    @Test
    fun `a bag new to this device triggers exactly one detail GET`() {
        remoteBags = listOf(buildJsonObject { put("id", "vb-9"); put("name", "Gesha") })
        bagDetails = listOf(
            buildJsonObject {
                put("id", "vb-9"); put("name", "Gesha"); put("roast_date", "2026-09-01")
                put("country", "Panama"); put("notes", "Floral")
            },
        )
        run(premium = true)
        assertEquals(listOf("/coffee_bags/vb-9"), detailGets)
        val added = library.beans.single()
        assertEquals("2026-09-01", added.roastedOn)
        assertEquals("Panama", added.origin?.country)
        assertEquals("Floral", added.notes)
        // Linked now: the next sync fetches no detail.
        clock += 60_000
        rerun()
        assertEquals(listOf("/coffee_bags/vb-9"), detailGets)
    }

    @Test
    fun `a failed detail fetch falls back to the summary and does not abort the run`() {
        failDetails = true
        remoteRoasters = listOf(buildJsonObject { put("id", "vr-9"); put("name", "Remote roaster") })
        remoteBags = listOf(buildJsonObject { put("id", "vb-9"); put("name", "Gesha") })
        val r = run(premium = true)
        assertTrue("run completes: ${r.error}", r.ok)
        assertEquals("Gesha", library.beans.single().name)
        assertEquals("Remote roaster", library.roasters.single().name)
        assertTrue(r.log.any { it.name == "Bag details" && it.error != null })
        assertTrue(r.log.any { it.name == "Roaster details" && it.error != null })
    }

    @Test
    fun `a bound roaster keeps its website from a thin list row and is not link-PATCHed`() {
        val r0 = roaster("Onyx", vid = "vr-1").copy(website = "https://onyx.coffee", catalogueRoasterId = "cr-1", updatedAt = 0)
        library = BeanLibrary(roasters = listOf(r0))
        remoteRoasters = listOf(buildJsonObject { put("id", "vr-1"); put("name", "Onyx Coffee Lab") })
        run(premium = true, lastSync = 1_000)
        val after = library.roasters.single()
        assertEquals("Onyx Coffee Lab", after.name)
        assertEquals("https://onyx.coffee", after.website)
        assertTrue(writes().isEmpty())
    }

    // ── Pure shell helpers ───────────────────────────────────────────────────

    @Test
    fun `merging synced rows keeps concurrent edits, adds pulls and respects mid-run deletes`() {
        val a = bean("A")
        val b = bean("B")
        val c = bean("C")
        val pulled = bean("Pulled")
        val snapshot = setOf(a.id, b.id, c.id)
        // User renamed B and deleted C while the run was in flight; the run touched A, C and pulled one.
        val current = listOf(a, b.copy(name = "B edited"))
        val synced = listOf(a.copy(visualizerId = "v-a"), c.copy(visualizerId = "v-c"), pulled)
        val out = mergeSyncedRows(current, synced, snapshot) { it.id }
        assertEquals(listOf(pulled.id, a.id, b.id), out.map { it.id })
        assertEquals("v-a", out[1].visualizerId)
        assertEquals("B edited", out[2].name)
    }

    @Test
    fun `the directory hides merged duplicates until shown and banners drop dismissed pairs`() {
        val keep = roaster("Sey")
        val tagged = roaster("SEY").copy(canonicalRoasterId = keep.id)
        val loose = roaster("sey ")
        assertEquals(listOf(keep, loose), directoryRoasters(listOf(keep, tagged, loose), showDuplicates = false))
        assertEquals(3, directoryRoasters(listOf(keep, tagged, loose), showDuplicates = true).size)
        val pairs = listOf(RoasterDuplicate(canonicalId = keep.id, dupeId = loose.id))
        val bag = bean("x", loose.id)
        val s = mergeSuggestions(pairs, listOf(keep, tagged, loose), listOf(bag), dismissed = emptySet()).single()
        assertEquals(keep.id, s.canonical.id)
        assertEquals(1, s.bagCount)
        assertTrue(mergeSuggestions(pairs, listOf(keep, loose), emptyList(), dismissed = setOf(loose.id)).isEmpty())
        assertTrue(mergeSuggestions(pairs, listOf(keep), emptyList(), dismissed = emptySet()).isEmpty())
    }

    @Test
    fun `remote delete is skipped on a free account and sends DELETEs on premium`() {
        seed(premium = false)
        val s = sync()
        runBlocking { s.load() }
        s.deleteRemote(listOf("vb-1"), "vr-1")
        runBlocking { job.children.toList().forEach { it.join() } }
        assertTrue(requests.isEmpty())

        seed(premium = true)
        runBlocking { s.load() }
        s.deleteRemote(listOf("vb-1"), "vr-1", label = "Sey")
        runBlocking { job.children.toList().forEach { it.join() } }
        assertEquals(listOf("DELETE /coffee_bags/vb-1", "DELETE /roasters/vr-1"), requests.map { "${it.method} ${it.path}" })
        // A cascade's remote ids: every synced bag first, then the roaster (web order).
        requests.clear()
        s.deleteRemote(listOf("vb-2", "vb-3"), "vr-2", label = "Onyx")
        runBlocking { job.children.toList().forEach { it.join() } }
        assertEquals(
            listOf("DELETE /coffee_bags/vb-2", "DELETE /coffee_bags/vb-3", "DELETE /roasters/vr-2"),
            requests.map { "${it.method} ${it.path}" },
        )
        assertNull(runBlocking { store.load() }.log.firstOrNull { it.direction == "delete" }?.error)
    }
}

/**
 * JVM stand-in for the core's bean-sync surface — the same rules as
 * `de1_domain::{bean_sync, reconcile_*, *_wire}` for the fields the fixtures
 * use. Records every write body it builds.
 */
private class FakeCore(private val json: Json) : BeanSyncCore {
    val writeBodies = mutableListOf<String>()
    private val beans = ListSerializer(Bean.serializer())
    private val roasters = ListSerializer(Roaster.serializer())

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    override fun reconcileRoasters(payload: String): String {
        val p = json.parseToJsonElement(payload).jsonObject
        val local = json.decodeFromJsonElement(roasters, p["local"]!!)
        return buildJsonArray {
            for (w in (p["remote"] as JsonArray).map { it.jsonObject }) {
                val id = w.s("id") ?: continue
                val name = w.s("name").orEmpty()
                val byId = local.firstOrNull { it.visualizerId == id }
                val byName = local.firstOrNull { it.name.trim().equals(name.trim(), ignoreCase = true) }
                add(
                    buildJsonObject {
                        when {
                            byId != null -> { put("kind", "update"); put("localId", byId.id) }
                            byName != null -> { put("kind", "bind"); put("localId", byName.id) }
                            else -> put("kind", "add")
                        }
                        put("remote", w)
                    },
                )
            }
        }.toString()
    }

    override fun reconcileBeans(payload: String): String {
        val p = json.parseToJsonElement(payload).jsonObject
        val local = json.decodeFromJsonElement(beans, p["local"]!!)
        val lastSync = (p["lastSyncAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val now = (p["nowMs"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
        return buildJsonArray {
            for (item in (p["remote"] as JsonArray).map { it.jsonObject }) {
                val w = item["wire"]!!.jsonObject
                val roasterId = item.s("localRoasterId")
                val vid = w.s("id")
                val m = local.firstOrNull { vid != null && it.visualizerId == vid }
                if (m == null) {
                    val b = newBean(w.s("name").orEmpty(), roasterId, null, w.s("roast_date"), now).copy(
                        id = item.s("fallbackId")!!,
                        visualizerId = vid,
                        canonicalCoffeeBagId = w.s("canonical_coffee_bag_id"),
                        notes = w.s("notes").orEmpty(),
                        origin = coffee.crema.core.BeanOrigin(country = w.s("country")),
                    )
                    add(buildJsonObject { put("kind", "add"); put("remote", json.encodeToJsonElement(Bean.serializer(), b)) })
                    continue
                }
                // Core rule: a bound local edited since the baseline is kept; else only
                // the keys the row carries apply (absent keys never clear anything).
                if (lastSync != null && m.updatedAt > lastSync) continue
                var merged = m
                if ("name" in w) merged = merged.copy(name = w.s("name").orEmpty())
                if ("canonical_coffee_bag_id" in w) merged = merged.copy(canonicalCoffeeBagId = w.s("canonical_coffee_bag_id"))
                if ("roaster_id" in w && roasterId != null) merged = merged.copy(roasterId = roasterId)
                if ("notes" in w) merged = merged.copy(notes = w.s("notes").orEmpty())
                if ("roast_date" in w) merged = merged.copy(roastedOn = w.s("roast_date"))
                if (merged == m) continue
                add(
                    buildJsonObject {
                        put("kind", "replace"); put("localId", m.id)
                        put("remote", json.encodeToJsonElement(Bean.serializer(), merged))
                    },
                )
            }
        }.toString()
    }

    override fun remoteIdsNeedingDetail(payload: String): String {
        val p = json.parseToJsonElement(payload).jsonObject
        val bound = (p["local"] as JsonArray).mapNotNull { (it.jsonObject["visualizerId"] as? JsonPrimitive)?.contentOrNull }.toSet()
        val ids = (p["remote"] as JsonArray).mapNotNull { it.jsonObject.s("id") }.filter { it !in bound }
        return buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }.toString()
    }

    override fun remoteRoasterUnlinked(remoteJson: String): Boolean {
        val o = json.parseToJsonElement(remoteJson).jsonObject
        return "canonical_roaster_id" in o && o.s("canonical_roaster_id").isNullOrEmpty()
    }

    override fun roasterFromWire(wireJson: String, fallbackId: String, nowMs: Long): String {
        val w = json.parseToJsonElement(wireJson).jsonObject
        val r = newRoaster(w.s("name").orEmpty(), nowMs).copy(
            id = fallbackId,
            visualizerId = w.s("id"),
            website = w.s("website"),
            catalogueRoasterId = w.s("canonical_roaster_id"),
        )
        return json.encodeToString(Roaster.serializer(), r)
    }

    override fun coffeeBagWriteRequest(beanJson: String, roasterRemoteId: String?): String {
        val b = json.decodeFromString(Bean.serializer(), beanJson)
        return buildJsonObject {
            put(
                "coffee_bag",
                buildJsonObject {
                    put("name", b.name)
                    put("roaster_id", roasterRemoteId)
                    b.canonicalCoffeeBagId?.takeIf { it.isNotEmpty() }?.let { put("canonical_coffee_bag_id", it) }
                    put("notes", b.notes?.takeIf { it.isNotEmpty() })
                },
            )
        }.toString().also { writeBodies += it }
    }

    override fun roasterWriteRequest(roasterJson: String): String {
        val r = json.decodeFromString(Roaster.serializer(), roasterJson)
        return buildJsonObject {
            put(
                "roaster",
                buildJsonObject {
                    put("name", r.name)
                    put("website", r.website)
                    r.catalogueRoasterId?.takeIf { it.isNotEmpty() }?.let { put("canonical_roaster_id", it) }
                },
            )
        }.toString().also { writeBodies += it }
    }

    override fun resolveRoasterCatalogueLink(payload: String): String? {
        val p = json.parseToJsonElement(payload).jsonObject
        val r = json.decodeFromJsonElement(Roaster.serializer(), p["roaster"]!!)
        val bs = json.decodeFromJsonElement(beans, p["beans"]!!)
        return r.catalogueRoasterId?.takeIf { it.isNotEmpty() }
            ?: bs.firstOrNull { it.roasterId == r.id && !it.canonicalRoasterId.isNullOrEmpty() && it.deletedAt == null }?.canonicalRoasterId
    }

    override fun mergePulledRoaster(localJson: String, remoteJson: String, refresh: Boolean, lastSyncAt: Long?): String {
        val l = json.decodeFromString(Roaster.serializer(), localJson)
        val w = json.parseToJsonElement(remoteJson).jsonObject
        val bound = l.copy(visualizerId = w.s("id") ?: l.visualizerId)
        val editedHere = lastSyncAt != null && l.updatedAt > lastSyncAt
        val out = if (refresh && !editedHere) {
            bound.copy(
                name = w.s("name") ?: l.name,
                website = if ("website" in w) w.s("website") else l.website,
                catalogueRoasterId = w.s("canonical_roaster_id")?.takeIf { it.isNotEmpty() } ?: l.catalogueRoasterId,
            )
        } else if (!w.s("canonical_roaster_id").isNullOrEmpty() && l.catalogueRoasterId.isNullOrEmpty()) {
            bound.copy(catalogueRoasterId = w.s("canonical_roaster_id"))
        } else {
            bound
        }
        return json.encodeToString(Roaster.serializer(), out)
    }

    override fun beanSyncScope(beansDirection: String, roastersDirection: String): String {
        fun pulls(d: String) = d == "pull" || d == "two-way"
        fun pushes(d: String) = d == "backup" || d == "two-way"
        return json.encodeToString(
            coffee.crema.core.BeanSyncScope.serializer(),
            coffee.crema.core.BeanSyncScope(pulls(beansDirection), pushes(beansDirection), pulls(roastersDirection), pushes(roastersDirection)),
        )
    }

    override fun planRoasterPush(payload: String): String {
        val p = json.parseToJsonElement(payload).jsonObject
        val last = (p["lastSyncAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
        val skip = (p["skipIds"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty().toSet()
        val plan = json.decodeFromJsonElement(roasters, p["roasters"]!!)
            .filter { it.deletedAt == null && it.id !in skip }
            .mapNotNull {
                when {
                    it.visualizerId == null -> coffee.crema.core.RoasterPushItem(it.id, true)
                    it.updatedAt > last -> coffee.crema.core.RoasterPushItem(it.id, false)
                    else -> null
                }
            }
        return json.encodeToString(ListSerializer(coffee.crema.core.RoasterPushItem.serializer()), plan)
    }

    override fun planBeanPush(payload: String): String {
        val p = json.parseToJsonElement(payload).jsonObject
        val last = (p["lastSyncAt"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0
        val skip = (p["skipIds"] as? JsonArray)?.map { (it as JsonPrimitive).content }.orEmpty().toSet()
        val plan = json.decodeFromJsonElement(beans, p["beans"]!!).filter { it.deletedAt == null && it.id !in skip }.mapNotNull {
            when {
                it.visualizerId == null -> BeanPushItem(it.id, true)
                it.updatedAt > last -> BeanPushItem(it.id, false)
                else -> null
            }
        }
        return json.encodeToString(ListSerializer(BeanPushItem.serializer()), plan)
    }

    override fun planRoasterLinkPatches(payload: String): String {
        val p = json.parseToJsonElement(payload).jsonObject
        val rs = json.decodeFromJsonElement(roasters, p["roasters"]!!)
        val unlinked = (p["unlinkedRemoteIds"] as JsonArray).map { (it as JsonPrimitive).content }.toSet()
        val plan = rs.filter { it.visualizerId in unlinked }.mapNotNull { r ->
            resolveRoasterCatalogueLink(
                buildJsonObject {
                    put("roaster", json.encodeToJsonElement(Roaster.serializer(), r))
                    put("beans", p["beans"]!!)
                }.toString(),
            )?.let { RoasterLinkPatch(r.id, it) }
        }
        return json.encodeToString(ListSerializer(RoasterLinkPatch.serializer()), plan)
    }
}

package coffee.crema.history

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.HistoryLive
import coffee.crema.core.HistoryLiveBean
import coffee.crema.core.ShotBean
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.persist.SafeFile
import coffee.crema.ui.ListMemory
import coffee.crema.ui.coreJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #310 A2, in Robolectric over the real index (the core's SQLite) kept in
 * step with `shots.db` by A1's hook: History's window answers as the
 * in-memory path does ([filterAndSortShots], the `index_fallback` and the
 * dev oracle), including the golden queries the core's reference is held to;
 * the tag filter and the six sorts with SPEC §6's tie rule; scroll memory and
 * deep links through `index_of`; the stat strip worked out on
 * `Dispatchers.Default`; no duplicate key when an edit moves a row across a
 * page; and the fallback whenever the index is stale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HistoryWindowTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val stores = mutableListOf<HistoryStore>()

    /** The window's thread, as Main is the app's: its state is touched from here only. */
    private val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)

    private fun store() = HistoryStore(app, coreJson).also { stores += it }

    @Before fun clean() {
        // P3a's `shots.db` (stage 1): a store that hasn't flipped, or an older build (#310 A5).
        HistoryStore.stage2Enabled = false
        SafeFile.openAllForTest()
        HistoryStore.faults.clear()
        HistoryIndex.FAULTS.clear()
        runBlocking { store().erase() }
        File(app.filesDir, "shot-series").deleteRecursively()
        KeptAsideNotices.drain()
    }

    @After fun tidy() {
        HistoryStore.stage2Enabled = HistoryStore.STAGE2_IN_BUILD
        scope.cancel()
        main.close()
        HistoryIndex.FAULTS.clear()
        IndexWindow.statsThreadForTest = null
        runBlocking { stores.forEach { it.closeIndexForTest() } }
        SafeFile.resetForTest()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** A store holding [shots], booted as the app boots it, with [live] in the index. */
    private suspend fun indexed(shots: List<StoredShot>, live: HistoryLive = HistoryLive(emptyList(), emptyList())): Pair<HistoryStore, HistoryIndexReads> {
        val s = store()
        s.loadAll()
        if (shots.isNotEmpty()) s.save(shots)
        s.syncIndex()
        s.setIndexLive(live)
        return s to assertNotNull(s.indexReads.value, "the index answers: ${s.fallbackReason}")
    }

    private suspend fun window(reads: HistoryIndexReads, f: HistoryFilters, pageSize: Int = IndexWindow.PAGE, maxPages: Int = IndexWindow.MAX_PAGES): IndexWindow =
        withContext(main) { IndexWindow(f.query(), reads, scope, pageSize = pageSize, maxPages = maxPages, settleMs = 0) }

    private suspend fun until(what: String, ms: Long = 10_000, ok: () -> Boolean) {
        withTimeout(ms) { while (!withContext(main) { ok() }) delay(5) }
        assertTrue(withContext(main) { ok() }, what)
    }

    /** Every row the window lists, read through its pages (positions 0 to count). */
    private suspend fun IndexWindow.listed(): List<String> {
        until("the count") { count != null }
        val n = withContext(main) { count!! }
        val out = ArrayList<String>(n)
        var at = 0
        while (at < n) {
            val first = at
            val last = (at + pageSize - 1).coerceAtMost(n - 1)
            withContext(main) { visible(first, last) }
            until("rows $first..$last") { (first..last).all { rowAt(it) != null } }
            withContext(main) { for (i in first..last) out += rowAt(i)!!.id }
            at = last + 1
        }
        return out
    }

    private fun filters(
        text: String = "",
        range: String = "all",
        profile: String? = null,
        bean: String? = null,
        method: String? = null,
        tag: String? = null,
        pinnedOnly: Boolean = false,
        sort: String = "date",
        desc: Boolean = true,
        now: Long = 0L,
    ) = HistoryFilters(text, range, profile, bean, method, tag, pinnedOnly, sort, desc, now)

    /** What the in-memory path lists for [f] over [shots]. */
    private fun memory(shots: List<ShotMeta>, f: HistoryFilters, live: (String) -> Pair<String, String?>? = { null }, liveTags: (String) -> List<String>? = { null }): List<String> =
        filterAndSortShots(
            shots, f.search, f.range, f.profile, f.sort, f.sortDesc, f.nowMs, f.bean, f.method,
            coffeeOf = { shotCoffee(it, live)?.key }, pinnedOnly = f.pinnedOnly, tagsAll = listOfNotNull(f.tag), liveTags = liveTags,
        ).map { it.id }

    private fun shot(id: String, at: Long, bean: ShotBean? = null, rating: Int? = null, barista: String? = null) = StoredShot(
        id = id, completedAtMs = at, durationMs = 27_000, profileName = "Adaptive v3", bean = bean, barista = barista, rating = rating,
    )

    private fun bean(id: String, name: String, roaster: String?, grinder: String? = null) =
        ShotBean(beanId = id, name = name, roasterName = roaster, grinder = grinder)

    // ── ShotFilterTest's cases, through the index ────────────────────────────

    @Test fun `ShotFilterTest's searches and bean filters list the same through the index`() = runBlocking<Unit> {
        val shots = listOf(
            shot("a", 5, bean("bag-1", "Kenya AA", "Sey", grinder = "Niche Zero"), barista = "Ana"),
            shot("b", 4, bean("bag-2", "kenya  aa", "SEY")).copy(drinker = "Ben"),
            shot("c", 3, bean("bag-3", "Kenya AA", "Onyx")).copy(grinderModel = "Niche Zero"),
            shot("d", 2).copy(pinned = true),
            shot("e", 1, bean("bag-4", "Ethiopia", "Onyx")),
        )
        val (_, reads) = indexed(shots)
        val cases = listOf(
            filters(), filters(text = "kenya"), filters(text = "SEY"), filters(text = "niche"), filters(text = "ana"),
            filters(text = " ben "), filters(text = "adaptive"), filters(text = "ethiopia"), filters(text = "nothing here"),
            filters(bean = COFFEE_FILTER_PREFIX + coffeeKey("Kenya AA", "Sey")), filters(bean = "bag-2"), filters(pinnedOnly = true),
        )
        for (f in cases) {
            val w = window(reads, f)
            assertEquals(memory(shots, f), w.listed(), "$f")
            assertEquals(memory(shots, f), w.ids(), "$f: every id, once")
        }
    }

    @Test fun `a one or two character search filters nothing, on both paths`() = runBlocking<Unit> {
        val shots = listOf(shot("a", 2, barista = "Ab"), shot("b", 1))
        val (_, reads) = indexed(shots)
        for (text in listOf("a", "ab", " b ")) {
            val f = filters(text = text)
            assertEquals("", f.search)
            assertEquals(listOf("a", "b"), window(reads, f).listed())
            assertEquals(listOf("a", "b"), memory(shots, f))
        }
    }

    @Test fun `the design size's searches and sorts list the same through the index`() = runBlocking<Unit> {
        val shots = List(600) { i ->
            shot("s${i.toString().padStart(4, '0')}", 1_700_000_000_000 + i / 3 * 1000, bean("bag-${i % 7}", "Coffee ${i % 7}", "Roaster ${i % 3}"), barista = if (i % 5 == 0) "Ana" else null)
                .copy(rating = i % 6, yieldG = (i % 40).toFloat(), durationMs = 20_000L + i % 13 * 1_000)
        }
        val (_, reads) = indexed(shots)
        val rows = shots.rows()
        for (sort in listOf("date", "rating", "profile", "bean", "yield", "time")) {
            for (desc in listOf(true, false)) {
                for (q in listOf("", "ana", "coffee 3")) {
                    val f = filters(text = q, sort = sort, desc = desc)
                    assertEquals(memory(rows, f), window(reads, f).ids(), "$sort ${if (desc) "desc" else "asc"} / $q")
                }
            }
        }
    }

    // ── The golden queries the core's reference is held to ───────────────────

    @Serializable private data class Sort(val key: String, val dir: String)

    @Serializable private data class Query(
        val name: String,
        val range: String,
        val text: String,
        val profile: String? = null,
        val bean: String? = null,
        val method: String? = null,
        val tags: List<String> = emptyList(),
        val pinnedOnly: Boolean = false,
        val sort: Sort,
    )

    @Serializable private data class Queries(val now: Long, val queries: List<Query>)

    @Serializable private data class LiveBean(val beanId: String, val name: String, val roaster: String? = null, val tags: List<String> = emptyList())

    @Serializable private data class Live(val beans: List<LiveBean>)

    @Test fun `the index lists every golden query as Android's golden does, but where SPEC 5 and 6 changed the rule`() = runBlocking<Unit> {
        val fixtures = File("../../core/de1-history/tests/fixtures")
        val json = Json { ignoreUnknownKeys = true }
        val shots = File(fixtures, "rows.android.jsonl").readLines().filter { it.isNotBlank() }.map { json.decodeFromString(StoredShot.serializer(), it) }
        val live = json.decodeFromString(Live.serializer(), File(fixtures, "live.json").readText())
            .let { l -> HistoryLive(l.beans.map { HistoryLiveBean(it.beanId, it.name, it.roaster, it.tags) }, emptyList()) }
        val golden = json.decodeFromString(MapSerializer(String.serializer(), ListSerializer(Int.serializer())), File(fixtures, "golden.android.json").readText())
        val spec = json.decodeFromString(Queries.serializer(), File(fixtures, "queries.json").readText())
        val (_, reads) = indexed(shots, live)
        // As `core/de1-history/tests/shells.rs` lists them: accents, short searches, tags in search, one field at a time.
        val changed = setOf("text-cafe-plain", "text-zoe-plain", "text-two-chars", "text-sour", "text-cafe-accented", "text-dial", "text-bag-tag-floral", "text-legacy-label")
        var compared = 0
        for (q in spec.queries) {
            val want = golden[q.name]?.map { shots[it].id } ?: continue
            if (q.name in changed) continue
            val f = filters(q.text, q.range, q.profile, q.bean, q.method, q.tags.singleOrNull(), q.pinnedOnly, q.sort.key, q.sort.dir == "desc", spec.now)
            if (q.tags.size > 1) continue // one tag at a time on the screens; the core's AND is covered there
            assertEquals(want, window(reads, f).ids(), q.name)
            compared++
        }
        assertTrue(compared >= 30, "$compared golden queries compared")
    }

    // ── The tag filter and the sorts (owner question 2) ──────────────────────

    @Test fun `the tag filter reaches the shot's own tags, else its live bag's, on both paths`() = runBlocking<Unit> {
        val shots = listOf(
            shot("own", 4).copy(tags = listOf("dial-in")),
            shot("bag", 3, bean("bag-1", "Kenya", "Sey")),
            shot("both", 2, bean("bag-1", "Kenya", "Sey")).copy(tags = listOf("milk")),
            shot("none", 1),
        )
        val live = HistoryLive(listOf(HistoryLiveBean("bag-1", "Kenya", "Sey", listOf("dial-in", "fruity"))), emptyList())
        val (_, reads) = indexed(shots, live)
        val tags: (String) -> List<String>? = { if (it == "bag-1") listOf("dial-in", "fruity") else null }
        for ((tag, want) in listOf("dial-in" to listOf("own", "bag"), "fruity" to listOf("bag"), "milk" to listOf("both"), "Dial-in" to emptyList())) {
            val f = filters(tag = tag)
            assertEquals(want, window(reads, f).ids(), tag)
            assertEquals(want, memory(shots, f, liveTags = tags), "$tag in memory")
        }
        val w = window(reads, filters())
        until("facets") { w.facets != null }
        val facets = withContext(main) { w.facets!! }
        assertEquals(mapOf("dial-in" to 2, "fruity" to 1, "milk" to 1), facets.tags.associate { it.value to it.count.toInt() })
    }

    @Test fun `ties follow the sort's way on both paths, for every sort`() = runBlocking<Unit> {
        // Equal keys and equal times everywhere, so each tie rule shows.
        val shots = List(12) { i ->
            shot("t$i", 1_000L + i / 4, rating = i % 2).copy(yieldG = (i % 3).toFloat(), durationMs = 20_000L + i % 2 * 1000, profileName = "P${i % 2}")
        }
        val (_, reads) = indexed(shots)
        for (sort in listOf("date", "rating", "profile", "bean", "yield", "time")) {
            for (desc in listOf(true, false)) {
                val f = filters(sort = sort, desc = desc)
                val listed = window(reads, f).ids()
                assertEquals(memory(shots, f), listed, "$sort $desc")
                // Under one key, the time and then the id run the sort's way.
                val byId = shots.associateBy { it.id }
                for ((a, b) in listed.zipWithNext()) {
                    val x = byId.getValue(a)
                    val y = byId.getValue(b)
                    val sameKey = when (sort) {
                        "rating" -> x.rating == y.rating
                        "profile" -> x.profileName == y.profileName
                        "yield" -> x.yieldG == y.yieldG
                        "time" -> x.durationMs == y.durationMs
                        else -> true
                    }
                    if (!sameKey) continue
                    val order = compareValuesBy(x, y, { it.completedAtMs }, { it.id })
                    assertTrue(if (desc) order > 0 else order < 0, "$sort $desc: $a before $b")
                }
            }
        }
    }

    // ── Scroll memory and deep links ─────────────────────────────────────────

    @Test fun `scroll memory finds its anchor through index_of across a filter change, and a deleted anchor lands next to it`() = runBlocking<Unit> {
        val shots = List(300) { i -> shot("m${i.toString().padStart(3, '0')}", 10_000L - i, rating = i % 5) }
        val (s, reads) = indexed(shots)
        val memory = ListMemory()
        val all = window(reads, filters())
        memory.record("k", ListMemory.Anchor("m150", 3, 17))
        assertEquals(150 to 17, memory.resolveAsync("k") { all.indexOf(it) })
        // Another filter: the same shot, at its place there.
        val rated = window(reads, filters(sort = "rating"))
        val at = rated.ids().indexOf("m150")
        assertEquals(at to 17, memory.resolveAsync("k") { rated.indexOf(it) })
        // Gone: the raw index, at its top.
        s.delete(listOf("m150"))
        assertNull(all.indexOf("m150"))
        assertEquals(3 to 0, memory.resolveAsync("k") { all.indexOf(it) })
        // A skeleton was first on screen: no key, the raw place.
        memory.record("k2", ListMemory.Anchor(null, 80, 5))
        assertEquals(80 to 5, memory.resolveAsync("k2") { all.indexOf(it) })
    }

    @Test fun `a deep link to a row far down finds it and loads its page`() = runBlocking<Unit> {
        val shots = List(2_000) { i -> shot("d${i.toString().padStart(4, '0')}", 100_000L - i) }
        val (_, reads) = indexed(shots)
        val w = window(reads, filters())
        until("the count") { w.count == 2_000 }
        val at = assertNotNull(w.indexOf("d1500"))
        assertEquals(1_500, at)
        withContext(main) { w.visible(at, at + 10) }
        until("its page") { w.rowAt(at) != null }
        assertEquals("d1500", withContext(main) { w.rowAt(at)!!.id })
        assertTrue(withContext(main) { w.loadedPages.size <= IndexWindow.MAX_PAGES })
        assertNull(window(reads, filters(text = "nothing matches")).indexOf("d1500"), "a query that doesn't list it")
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    @Test fun `the stat strip is worked out on Dispatchers Default, on both paths, and they agree`() = runBlocking<Unit> {
        val shots = HistoryFixtures.rows(120, samplesPer = 8)
        val (_, reads) = indexed(shots)
        val threads = java.util.Collections.synchronizedList(mutableListOf<String>())
        IndexWindow.statsThreadForTest = { threads += it }
        val w = window(reads, filters())
        until("the stats") { w.stats != null }
        val viaIndex = withContext(main) { w.stats!! }
        val inMemory = withContext(main) { memoryStats(shots.rows()) }
        assertEquals(2, threads.size, "$threads")
        assertTrue(threads.all { it.startsWith("DefaultDispatcher-worker") }, "$threads")
        assertTrue(threads.none { it.startsWith("pool-") }, "never the window's own (main) thread: $threads")
        assertEquals(inMemory.count, viaIndex.count)
        assertEquals(inMemory.mixedMethods, viaIndex.mixedMethods)
        fun close(a: Float?, b: Float?) = (a == null && b == null) || (a != null && b != null && kotlin.math.abs(a - b) <= 1e-3f * maxOf(1f, kotlin.math.abs(a)))
        assertTrue(close(inMemory.avgRating, viaIndex.avgRating), "rating ${inMemory.avgRating} ${viaIndex.avgRating}")
        assertTrue(close(inMemory.avgTimeS, viaIndex.avgTimeS), "time ${inMemory.avgTimeS} ${viaIndex.avgTimeS}")
        assertTrue(close(inMemory.beansUsedG, viaIndex.beansUsedG), "dose ${inMemory.beansUsedG} ${viaIndex.beansUsedG}")
    }

    // ── Notices and duplicate keys ───────────────────────────────────────────

    @Test fun `an edit that moves a row across a page shows it once, and the list follows`() = runBlocking<Unit> {
        val shots = List(40) { i -> shot("r${i.toString().padStart(2, '0')}", 1_000L - i, rating = 1) }
        val (s, reads) = indexed(shots)
        val f = filters(sort = "rating")
        val w = window(reads, f, pageSize = 10)
        withContext(main) { w.visible(0, 25) }
        until("three pages") { (0..29).all { w.rowAt(it) != null } }
        assertEquals("r15", withContext(main) { w.rowAt(15)!!.id })
        // A star on row 15 moves it to the top: page 1 to page 0.
        s.save(listOf(shots[15].copy(rating = 5)))
        until("moved") { w.rowAt(0)?.id == "r15" && (0..29).all { w.rowAt(it) != null } }
        val keys = withContext(main) { (0 until w.count!!).map { w.keyAt(it) } }
        assertEquals(keys.size, keys.toSet().size, "no key twice")
        val now = withContext(main) { (0..29).map { w.rowAt(it)!!.id } }
        assertEquals(memory(shots.map { if (it.id == "r15") it.copy(rating = 5) else it }, f).take(30), now)
    }

    @Test fun `a page read after a move the window didn't hear of never shows a row twice`() = runBlocking<Unit> {
        val shots = List(40) { i -> shot("q${i.toString().padStart(2, '0')}", 1_000L - i, rating = 1) }
        val (s, real) = indexed(shots)
        // A window that hears no notices: the worst case, a notice still on its way.
        val reads = HistoryIndexReads(real.bridge, MutableSharedFlow(), real.decode)
        val w = window(reads, filters(sort = "rating"), pageSize = 10, maxPages = 2)
        val seen = mutableListOf<List<Any>>()
        suspend fun check(what: String) = withContext(main) {
            val n = w.count ?: 0
            val keys = (0 until n).map { w.keyAt(it) }
            seen += keys
            assertEquals(keys.size, keys.toSet().size, "$what: no key twice in ${keys.filterIsInstance<String>()}")
        }
        withContext(main) { w.visible(10, 19) }
        until("pages 0 to 2") { (0..29).all { w.rowAt(it) != null } }
        check("loaded")
        // q15 moves to the top in the index; the window doesn't know.
        s.save(listOf(shots[15].copy(rating = 5)))
        withContext(main) { w.visible(20, 29) }
        until("page 3, page 0 dropped") { w.rowAt(35) != null && 0 !in w.loadedPages }
        check("scrolled down")
        // Back up: page 0 reads anew, with q15, while page 1 still holds it from before.
        withContext(main) { w.visible(0, 9) }
        until("page 0 again") { w.rowAt(0) != null }
        check("page 0 read again")
        until("settled") { (0..19).all { w.rowAt(it) != null } }
        check("settled")
        assertEquals("q15", withContext(main) { w.rowAt(0)!!.id })
    }

    @Test fun `a star tap off the rating sort patches the row in place`() = runBlocking<Unit> {
        val shots = List(30) { i -> shot("p${i.toString().padStart(2, '0')}", 1_000L - i, rating = 1) }
        val (s, reads) = indexed(shots)
        val w = window(reads, filters(), pageSize = 10)
        withContext(main) { w.visible(0, 9) }
        until("rows") { (0..19).all { w.rowAt(it) != null } }
        val before = withContext(main) { w.rowAt(3) }
        s.save(listOf(shots[3].copy(rating = 4)))
        until("the new star") { w.rowAt(3)?.rating == 4 }
        assertEquals(before?.id, withContext(main) { w.rowAt(3)!!.id })
        assertEquals(30, withContext(main) { w.count })
    }

    @Test fun `a new shot and a delete move the count`() = runBlocking<Unit> {
        val shots = List(5) { i -> shot("n$i", 1_000L - i) }
        val (s, reads) = indexed(shots)
        val w = window(reads, filters())
        until("five") { w.count == 5 }
        s.save(listOf(shot("new", 2_000L)))
        until("six, the new one on top") { w.count == 6 && w.rowAt(0)?.id == "new" }
        s.delete(listOf("n0", "n1"))
        until("four") { w.count == 4 }
        assertEquals(listOf("new", "n2", "n3", "n4"), w.ids())
    }

    // ── index_fallback ───────────────────────────────────────────────────────

    @Test fun `History reads the in-memory rows while the index is stale or can't answer`() = runBlocking<Unit> {
        val s = store()
        s.loadAll()
        // Two diff batches, so the diff is seen under way.
        s.save(List(HistoryIndex.BATCH + 100) { i -> shot("f$i", 10_000L - i) })
        assertNull(s.indexReads.value, "before the boot check: ${s.fallbackReason}")
        s.syncIndex()
        assertNull(s.indexReads.value, "before the live libraries: ${s.fallbackReason}")
        s.setIndexLive(HistoryLive(emptyList(), emptyList()))
        val first = assertNotNull(s.indexReads.value)
        // An index write that fails leaves it behind: the in-memory rows until it settles.
        HistoryIndex.FAULTS += IndexFault.FAIL_WRITE
        s.save(listOf(shot("fx", 20_000L)))
        HistoryIndex.FAULTS.clear()
        assertNull(s.indexReads.value, "a write it missed: ${s.fallbackReason}")
        s.syncIndex()
        val again = assertNotNull(s.indexReads.value, "settled by the diff")
        assertTrue(again !== first, "a new read handle: windows start over")
        assertEquals((HistoryIndex.BATCH + 101).toUInt(), again.bridge.count(HistoryDbBridge.ALL))
        // A diff under way (pending raised) is stale too.
        var midDiff = 0
        HistoryStore.afterDiffBatchForTest = { midDiff++; assertNull(s.indexReads.value, "mid-diff") }
        try {
            s.syncIndex(force = true)
        } finally {
            HistoryStore.afterDiffBatchForTest = null
        }
        assertTrue(midDiff > 0)
        assertNotNull(s.indexReads.value)
        // Closed (an erase, another store): nothing to read.
        s.closeIndexForTest()
        assertNull(s.indexReads.value)
    }

    @Test fun `a window whose index closes under it says so, and the screen falls back`() = runBlocking<Unit> {
        val (s, reads) = indexed(List(3) { i -> shot("c$i", 1_000L - i) })
        val w = window(reads, filters())
        until("count") { w.count == 3 }
        s.closeIndexForTest()
        assertEquals(emptyList(), w.ids())
        assertNotNull(withContext(main) { w.failed })
    }

    // ── Day groups ───────────────────────────────────────────────────────────

    @Test fun `day groups count through the index as over the rows, and the layout places each header`() = runBlocking<Unit> {
        val now = 1_800_000_000_000L
        val start = HistoryFilters.startOfDay(now)
        val day = HistoryFilters.DAY_MS
        val shots = listOf(
            shot("t1", start + 5_000), shot("t2", start + 1), shot("y1", start - 10), shot("w1", start - 3 * day),
            shot("w2", start - 6 * day), shot("e1", start - 6 * day - 1), shot("e2", start - 40 * day),
        )
        val (_, reads) = indexed(shots)
        val edges = DayGroups.edges(start)
        val w = window(reads, filters(now = now))
        withContext(main) { w.dayEdges(edges) }
        until("groups") { w.groups != null }
        assertEquals(listOf(2, 1, 2, 2), withContext(main) { w.groups })
        assertEquals(listOf(2, 1, 2, 2), DayGroups.counts(shots, edges))
        val desc = DayLayout(listOf(2, 1, 2, 2), descending = true, lead = 1)
        assertEquals(1 + 4 + 7, desc.size)
        assertEquals(DayLayout.Item.Lead, desc.itemAt(0))
        assertEquals(DayLayout.Item.Header(0), desc.itemAt(1))
        assertEquals(DayLayout.Item.Row(0), desc.itemAt(2))
        assertEquals(DayLayout.Item.Header(1), desc.itemAt(4))
        assertEquals(DayLayout.Item.Row(2), desc.itemAt(5))
        assertEquals(DayLayout.Item.Row(6), desc.itemAt(11))
        assertEquals(11, desc.itemOfRow(6))
        assertEquals(4, desc.itemOfHeader(1))
        val asc = DayLayout(listOf(2, 0, 2, 2), descending = false)
        assertEquals(DayLayout.Item.Header(3), asc.itemAt(0))
        assertEquals(DayLayout.Item.Header(2), asc.itemAt(3))
        assertNull(asc.itemOfHeader(1), "an empty group has no header")
        val flat = DayLayout.rowsOnly(5, lead = 1)
        assertEquals(6, flat.size)
        assertEquals(DayLayout.Item.Row(4), flat.itemAt(5))
    }
}

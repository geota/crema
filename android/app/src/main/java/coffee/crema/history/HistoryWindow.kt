package coffee.crema.history

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coffee.crema.core.BrewHistoryStats
import coffee.crema.core.HistoryAnchor
import coffee.crema.core.HistoryBeanScope
import coffee.crema.core.HistoryDir
import coffee.crema.core.HistoryFacets
import coffee.crema.core.HistoryQuery
import coffee.crema.core.HistorySort
import coffee.crema.core.HistorySortKey
import coffee.crema.core.historyChangeMovesList
import coffee.crema.core.historyChangeMovesStats
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/*
 * #310 A2: History reads the stage-1 index through a window (SQLite SPEC §7).
 *
 * A [ShotList] is what a History list draws: a [ShotList.count] (null until
 * it's known, so nothing says "empty" before a query has answered, rule 2),
 * and each row by position, null while its page loads (a skeleton of the same
 * height). Two kinds:
 * - [IndexWindow] over the index: pages of [IndexWindow.PAGE] rows loaded
 *   around the visible range, a page of lead each way, at most
 *   [IndexWindow.MAX_PAGES] kept; keyset from a loaded neighbour, an offset
 *   jump otherwise. Its stats, facets and day groups are queries too.
 * - [MemoryShotList] over the in-memory rows, [filterAndSortShots]'s answer:
 *   the `index_fallback` path, while the index is stale or can't answer.
 *
 * Change notices (SPEC §10) drive the window: one that moves the list
 * (`historyChangeMovesList`: the set, this sort's key, the text under a
 * search) refetches the count and the visible pages and drops the others,
 * which also keeps a row whose sort key moved from showing twice
 * (REVIEW-442, the C2b review); one that changes rows already on screen
 * refetches only those. A page read before the last notice applied is
 * dropped and read again (S14), and a gap in the notices refetches
 * everything. Pages are for display only: [ShotList.ids] dedupes by id.
 */

/** What a History list shows. Read on the main thread; its state is Compose state. */
@Stable
interface ShotList {
    /** Rows the query lists; null until it has answered. */
    val count: Int?

    /** The row at [i], or null while it loads. */
    fun rowAt(i: Int): ShotRow?

    /** A `LazyColumn` key for row [i]: its id, or the position itself (an Int, never equal to an id) while it loads. */
    fun keyAt(i: Int): Any = rowAt(i)?.id ?: i

    /** Rows [first] to [last] are on screen: load what's missing around them. */
    fun visible(first: Int, last: Int) {}

    /** [id]'s position in the list, or null when the query doesn't list it. */
    suspend fun indexOf(id: String): Int?

    /** Every id the query lists, in order, each once: for acting on each row (the "current filter" export). */
    suspend fun ids(): List<String>

    /** A loaded row by id, if it's in memory here. */
    fun rowById(id: String): ShotRow?

    /** The index couldn't answer: the screen falls back to the in-memory rows. */
    val failed: Throwable? get() = null
}

/** The in-memory list (`index_fallback`): [rows] already filtered and sorted. */
class MemoryShotList(val rows: List<ShotRow>) : ShotList {
    private val positions by lazy { HashMap<String, Int>(rows.size * 2).apply { rows.forEachIndexed { i, r -> put(r.id, i) } } }

    override val count: Int get() = rows.size

    override fun rowAt(i: Int): ShotRow? = rows.getOrNull(i)

    override suspend fun indexOf(id: String): Int? = positions[id]

    override suspend fun ids(): List<String> = rows.map { it.id }

    override fun rowById(id: String): ShotRow? = positions[id]?.let(rows::get)

    /** [indexOf] without suspending. */
    fun positionOf(id: String): Int? = positions[id]
}

/**
 * The History filters as the screens hold them, and the core query they make
 * (SPEC §6). [nowMs] is the screens' minute-bucketed clock; "today" starts at
 * local midnight.
 */
data class HistoryFilters(
    val text: String,
    val range: String,
    val profile: String?,
    val bean: String?,
    val method: String?,
    val tag: String?,
    val pinnedOnly: Boolean,
    val sort: String,
    val sortDesc: Boolean,
    val nowMs: Long,
) {
    /** The search as both paths run it: under 3 characters is no search (owner question 3). */
    val search: String get() = searchText(text)

    val sinceMs: Long?
        get() = when (range) {
            "today" -> startOfDay(nowMs)
            "7d" -> nowMs - 7L * DAY_MS
            "30d" -> nowMs - 30L * DAY_MS
            else -> null
        }

    fun query(): HistoryQuery = HistoryQuery(
        text = search,
        sinceMs = sinceMs,
        profile = profile,
        bean = bean?.let { if (it.startsWith(COFFEE_FILTER_PREFIX)) HistoryBeanScope.Coffee(it.removePrefix(COFFEE_FILTER_PREFIX)) else HistoryBeanScope.Bag(it) },
        method = method,
        tagsAll = listOfNotNull(tag),
        pinnedOnly = pinnedOnly,
        sort = HistorySort(sortKeyOf(sort), if (sortDesc) HistoryDir.DESC else HistoryDir.ASC),
    )

    companion object {
        const val DAY_MS = 24L * 60L * 60L * 1000L

        fun startOfDay(nowMs: Long): Long = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        fun sortKeyOf(sort: String): HistorySortKey = when (sort) {
            "rating" -> HistorySortKey.RATING
            "profile" -> HistorySortKey.PROFILE
            "bean" -> HistorySortKey.BEAN
            "yield" -> HistorySortKey.YIELD
            "time" -> HistorySortKey.TIME
            else -> HistorySortKey.DATE
        }
    }
}

/** [text] as a search: trimmed, and nothing when under 3 characters (SPEC §5, owner question 3). */
fun searchText(text: String): String {
    val t = text.trim()
    return if (t.codePointCount(0, t.length) >= MIN_SEARCH_CHARS) t else ""
}

/** A search needs this many characters; the box says so below it. */
const val MIN_SEARCH_CHARS = 3

/**
 * The phone's day groups (SPEC §7): Today, Yesterday, This week, Earlier, by
 * local midnight, under the date sort only. The edges are the newest first.
 */
object DayGroups {
    val LABELS = listOf("Today", "Yesterday", "This week", "Earlier")

    fun edges(startOfDay: Long): List<Long> =
        listOf(startOfDay, startOfDay - HistoryFilters.DAY_MS, startOfDay - 6 * HistoryFilters.DAY_MS)

    /** Which group [ms] is in: an index into [LABELS]. */
    fun of(ms: Long, edges: List<Long>): Int = edges.indexOfFirst { ms >= it }.let { if (it < 0) edges.size else it }

    /** [rows]' group counts, as `group_counts` gives them. */
    fun counts(rows: List<ShotMeta>, edges: List<Long>): List<Int> {
        val out = IntArray(edges.size + 1)
        for (r in rows) out[of(r.completedAtMs, edges)]++
        return out.toList()
    }
}

/**
 * A list with day headers: [counts] rows per group (newest group first), the
 * groups in list order ([descending] newest first), a header before each
 * group that has rows, and [lead] items before them all (the save-method
 * prompt). Positions are arithmetic (SPEC §7), so nothing scans the rows.
 */
class DayLayout(counts: List<Int>, descending: Boolean, val lead: Int = 0, private val headers: Boolean = true) {
    /** Each non-empty group in list order: its label index, its first row, its row count. */
    private val groups: List<Triple<Int, Int, Int>>

    /** Items in the list: the lead, the headers and the rows. */
    val size: Int

    init {
        val order = if (descending) counts.indices.toList() else counts.indices.reversed()
        var row = 0
        val g = ArrayList<Triple<Int, Int, Int>>()
        for (i in order) {
            val n = counts[i]
            if (n == 0) continue
            g += Triple(i, row, n)
            row += n
        }
        groups = if (headers) g else emptyList()
        size = lead + groups.size + row
    }

    companion object {
        /** [count] rows after [lead] items, no headers: a sort other than date, or groups not counted yet. */
        fun rowsOnly(count: Int, lead: Int = 0) = DayLayout(listOf(count), descending = true, lead = lead, headers = false)
    }

    /** What list item [item] is: a header (its label index) or a row (its position). */
    sealed interface Item {
        data class Header(val group: Int) : Item
        data class Row(val index: Int) : Item
        data object Lead : Item
    }

    fun itemAt(item: Int): Item {
        if (item < lead) return Item.Lead
        var at = lead
        for ((label, _, n) in groups) {
            if (item == at) return Item.Header(label)
            if (item <= at + n) return Item.Row(rowStart(label) + (item - at - 1))
            at += n + 1
        }
        return Item.Row(item - lead - groups.size)
    }

    private fun rowStart(label: Int): Int = groups.first { it.first == label }.second

    /** The list item that shows row [index]. */
    fun itemOfRow(index: Int): Int {
        var headers = 0
        for ((_, start, _) in groups) if (start <= index) headers++
        return lead + headers + index
    }

    /** The list item of group [label]'s header, if it has rows. */
    fun itemOfHeader(label: Int): Int? {
        var at = lead
        for ((l, _, n) in groups) {
            if (l == label) return at
            at += n + 1
        }
        return null
    }
}

/**
 * A window over the index for one [query] (SPEC §7). Built on the main
 * thread; [scope] runs its reads (the bridge goes to IO itself) and decodes
 * on [decodeOn]. Subscribes to the notices before its first query (S14).
 */
@Stable
class IndexWindow(
    val query: HistoryQuery,
    private val reads: HistoryIndexReads,
    private val scope: CoroutineScope,
    private val decodeOn: CoroutineDispatcher = Dispatchers.Default,
    val pageSize: Int = PAGE,
    private val maxPages: Int = MAX_PAGES,
    /** How long stats and facets wait after a notice, so a burst reads once (SPEC §10). */
    private val settleMs: Long = SETTLE_MS,
) : ShotList {
    private class Loaded(val rows: List<ShotRow?>, val first: String?, val last: String?, val gen: Int)

    private val bridge get() = reads.bridge
    private val pages = mutableStateMapOf<Int, Loaded>()
    private val loading = HashMap<Int, Job>()

    override var count: Int? by mutableStateOf(null)
        private set

    override var failed: Throwable? by mutableStateOf(null)
        private set

    /** The stat strip over [query], off the main thread; null until it has answered. */
    var stats: BrewHistoryStats? by mutableStateOf(null)
        private set

    /** The filter options beside [query] (each over the query minus its own dimension); null until they answer. */
    var facets: HistoryFacets? by mutableStateOf(null)
        private set

    /** The day groups' counts for the edges [dayEdges] set; null until they answer, or when none were asked for. */
    var groups: List<Int>? by mutableStateOf(null)
        private set

    /** Pages read under a list order older than this are stale: a notice moved the list since. */
    private var gen = 0

    /** The last notice's `write_seq`: a page read before it is dropped (S14). */
    private var noticeSeq = 0uL

    private var lastNotice = -1L
    private var shown = 0 to -1
    private var edges: List<Long>? = null
    private var statsJob: Job? = null
    private var facetsJob: Job? = null
    private var sideStarted = false

    private val createdAt = System.nanoTime()

    /** From this window's start to its first page in, ms: B1's "first page" on the device (the debug panel). */
    var firstPageMs: Double? = null
        private set

    /** Pages read and kept, for tests. */
    val loadedPages: Set<Int> get() = pages.keys.toSet()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { reads.notices.collect { onNotice(it) } }
        scope.launch {
            refreshCount()
            ensure()
            // Nothing to page: the stats and facets go now. Otherwise after the first page (below).
            if (count == 0 || failed != null) startSideReads()
        }
    }

    /**
     * The stat strip and the facets, once: after the first page, so the list
     * doesn't queue behind them on the store's one connection (SPEC §7, B1).
     */
    private fun startSideReads() {
        if (sideStarted) return
        sideStarted = true
        if (statsJob == null) statsJob = scope.launch { readStats() }
        if (facetsJob == null) facetsJob = scope.launch { readFacets() }
    }

    override fun rowAt(i: Int): ShotRow? = pages[i / pageSize]?.rows?.getOrNull(i % pageSize)

    override fun rowById(id: String): ShotRow? {
        for (p in pages.values) for (r in p.rows) if (r?.id == id) return r
        return null
    }

    override fun visible(first: Int, last: Int) {
        if (shown == first to last) return
        shown = first to last
        ensure()
    }

    override suspend fun indexOf(id: String): Int? = guard(null) { bridge.indexOf(query, id)?.toInt() }

    override suspend fun ids(): List<String> = guard(emptyList()) {
        // Display pages can show a row twice across an edit (C2b review): each id once.
        val out = LinkedHashSet<String>()
        var page = bridge.page(query, HistoryAnchor.Start, ALL_PAGE.toUInt())
        while (true) {
            page.rows.forEach { out += it.id }
            val last = page.last
            if (page.atEnd || last == null) break
            page = bridge.page(query, HistoryAnchor.After(last), ALL_PAGE.toUInt())
        }
        out.toList()
    }

    /** Ask for the day groups cut at [edgesMs] (newest first), or none. */
    fun dayEdges(edgesMs: List<Long>?) {
        if (edges == edgesMs) return
        edges = edgesMs
        groups = null
        if (edgesMs != null) scope.launch { readGroups() }
    }

    private suspend fun <T> guard(fallback: T, block: suspend () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        failed = e
        fallback
    }

    private suspend fun refreshCount() {
        val c = guard(null) { bridge.count(query).toInt() } ?: return
        count = c
        val lastPage = if (c == 0) -1 else (c - 1) / pageSize
        pages.keys.filter { it > lastPage }.forEach { pages.remove(it) }
    }

    /** The pages around [shown]: those missing load, the farthest past [maxPages] go. */
    private fun ensure() {
        if (failed != null) return
        val wanted = wanted()
        if (wanted.isEmpty()) return
        for (p in wanted) if (p !in pages && p !in loading) load(p)
        if (pages.size > maxPages) {
            val mid = (wanted.first + wanted.last) / 2
            pages.keys.filter { it !in wanted }.sortedByDescending { kotlin.math.abs(it - mid) }
                .take(pages.size - maxPages).forEach { pages.remove(it) }
        }
    }

    private fun load(p: Int) {
        loading[p] = scope.launch {
            try {
                read(p)
            } finally {
                loading.remove(p)
            }
        }
    }

    private suspend fun read(p: Int, offsetOnly: Boolean = false) {
        val atGen = gen
        val prev = pages[p - 1]?.takeIf { it.gen == atGen && it.last != null && !offsetOnly }
        val next = pages[p + 1]?.takeIf { it.gen == atGen && it.first != null && !offsetOnly }
        val anchor = when {
            p == 0 -> HistoryAnchor.Start
            prev != null -> HistoryAnchor.After(prev.last!!)
            next != null -> HistoryAnchor.Before(next.first!!)
            else -> HistoryAnchor.Offset((p * pageSize).toUInt())
        }
        val page = guard(null) { bridge.page(query, anchor, pageSize.toUInt()) } ?: return
        val rows = withContext(decodeOn) { page.rows.map { reads.decode(it.rowJson) } }
        val c = count ?: return
        val expected = (c - p * pageSize).coerceIn(0, pageSize)
        when {
            // The list moved, or a notice came after this read began (S14): read it again.
            gen != atGen || page.seq < noticeSeq -> {
                if (p in wanted()) read(p)
                return
            }
            // A keyset page that doesn't fill its place: the count is behind; place it by offset.
            anchor !is HistoryAnchor.Start && anchor !is HistoryAnchor.Offset && rows.size != expected -> {
                read(p, offsetOnly = true)
                return
            }
        }
        put(p, Loaded(rows, page.first, page.last, atGen))
    }

    /** The visible pages and one of lead each way. */
    private fun wanted(): IntRange {
        val c = count ?: return IntRange.EMPTY
        if (c == 0) return IntRange.EMPTY
        val (first, last) = shown
        val lastPage = (c - 1) / pageSize
        return (first.coerceAtLeast(0) / pageSize - 1).coerceAtLeast(0)..(last.coerceAtLeast(first).coerceAtLeast(0) / pageSize + 1).coerceAtMost(lastPage)
    }

    /**
     * Keep [page] at [p], and drop any other kept page that shows one of its
     * rows: a `LazyColumn` throws on a duplicate key (the C2b review). The
     * newer read wins; a dropped page that's still wanted reads again.
     */
    private fun put(p: Int, page: Loaded) {
        val ids = page.rows.mapNotNullTo(HashSet()) { it?.id }
        val clash = pages.filter { (q, other) -> q != p && other.rows.any { it?.id in ids } }.keys
        clash.forEach { pages.remove(it) }
        pages[p] = page
        if (firstPageMs == null) {
            firstPageMs = (System.nanoTime() - createdAt) / 1e6
            lastFirstPageMs = firstPageMs
            startSideReads()
        }
        if (clash.isNotEmpty()) ensure()
    }

    private suspend fun onNotice(n: IndexNotice) {
        val gap = lastNotice >= 0 && n.n != lastNotice + 1
        lastNotice = n.n
        val change = n.change
        if (change != null && change.seq > noticeSeq) noticeSeq = change.seq
        if (gap || change == null) return restart()
        val moves = historyChangeMovesList(change.kinds, query)
        if (moves) {
            relist()
        } else {
            val onScreen = (change.upserted + change.deleted).filter { id -> rowById(id) != null }
            if (onScreen.isNotEmpty()) patch(onScreen)
        }
        if (historyChangeMovesStats(change.kinds, query)) {
            statsJob?.cancel()
            statsJob = scope.launch { delay(settleMs); readStats() }
        }
        if (change.kinds.facet || change.kinds.set) {
            facetsJob?.cancel()
            facetsJob = scope.launch { delay(settleMs); readFacets() }
        }
        if ((moves || change.kinds.set) && edges != null) readGroups()
    }

    /** Everything again: a missed notice, or the index settled after a diff. */
    private suspend fun restart() {
        relist()
        statsJob?.cancel(); statsJob = scope.launch { readStats() }
        facetsJob?.cancel(); facetsJob = scope.launch { readFacets() }
        if (edges != null) readGroups()
    }

    /** The set or the order moved: the count, the visible pages again, the others dropped. */
    private suspend fun relist() {
        gen++
        val wantedBefore = wanted()
        pages.keys.filter { it !in wantedBefore }.forEach { pages.remove(it) }
        refreshCount()
        // The visible pages stay drawn until their new reads land (no flash of skeletons), by offset.
        for (p in wanted()) {
            if (p in loading) continue
            loading[p] = scope.launch {
                try {
                    read(p, offsetOnly = true)
                } finally {
                    loading.remove(p)
                }
            }
        }
    }

    /** Rows on screen changed in place: read just those and put them where they are. */
    private suspend fun patch(ids: List<String>) {
        val atGen = gen
        val got = guard(null) { bridge.get(ids) } ?: return
        val rows = withContext(decodeOn) { got.associate { it.id to reads.decode(it.rowJson) } }
        if (gen != atGen) return
        for ((p, page) in pages.toMap()) {
            if (page.rows.none { it?.id in rows.keys || it?.id in ids }) continue
            // A row gone from the index (a held delete) shows as loading until the next relist.
            pages[p] = Loaded(page.rows.map { r -> if (r != null && r.id in ids) rows[r.id] else r }, page.first, page.last, page.gen)
        }
    }

    private suspend fun readStats() {
        val text = guard(null) { bridge.statsJson(query) } ?: return
        stats = withContext(statsOn) {
            statsThreadForTest?.invoke(Thread.currentThread().name)
            decodeBrewHistoryStats(text)
        }
    }

    private suspend fun readFacets() {
        facets = guard(null) { bridge.facets(query) }
    }

    private suspend fun readGroups() {
        val e = edges ?: return
        val got = guard(null) { bridge.groupCounts(query, e).map { it.toInt() } } ?: return
        if (edges == e) groups = got
    }

    companion object {
        /** Rows per page (SPEC §7). */
        const val PAGE = 64

        /** Pages kept (SPEC §7). */
        const val MAX_PAGES = 8

        /** Rows per read when every id is wanted. */
        const val ALL_PAGE = 500

        /** The stat strip's debounce after a notice (SPEC §10). */
        const val SETTLE_MS = 300L

        /** The last window's [firstPageMs], for the debug panel. */
        @Volatile var lastFirstPageMs: Double? = null

        /** Where the stat strip is worked out: off the main thread (SPEC §7). A test seam. */
        @Volatile var statsOn: CoroutineDispatcher = Dispatchers.Default

        /** Test seam: told the thread the stat strip was worked out on. */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var statsThreadForTest: ((String) -> Unit)? = null
    }
}

/**
 * The stat strip over in-memory rows (`index_fallback`), on [IndexWindow.statsOn]
 * ([Dispatchers.Default]), never the main thread.
 */
suspend fun memoryStats(rows: List<ShotMeta>): BrewHistoryStats = withContext(IndexWindow.statsOn) {
    IndexWindow.statsThreadForTest?.invoke(Thread.currentThread().name)
    brewHistoryStats(rows)
}

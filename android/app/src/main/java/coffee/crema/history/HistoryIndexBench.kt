package coffee.crema.history

import coffee.crema.core.HistoryAnchor
import coffee.crema.core.HistoryDir
import coffee.crema.core.HistoryQuery
import coffee.crema.core.HistorySort
import coffee.crema.core.HistorySortKey

/**
 * #310 A2: History's reads timed on this device against PLAN B1's budgets,
 * for the debug panel (behind the debug flag). Reads only: count and the
 * first page under each sort, the next page, the offset jump to the end, a
 * search, stats, facets and day groups, each the median of [runs].
 */
object HistoryIndexBench {
    data class Line(val what: String, val ms: Double, val budgetMs: Int?) {
        override fun toString(): String = "$what: ${"%.1f".format(ms)} ms" + (budgetMs?.let { " (budget $it at 20k)" } ?: "")
    }

    suspend fun run(reads: HistoryIndexReads, searches: List<String>, nowMs: Long, runs: Int = 5): List<Line> {
        val db = reads.bridge
        val all = HistoryDbBridge.ALL
        suspend fun time(block: suspend () -> Unit): Double {
            val samples = List(runs) {
                val t = System.nanoTime()
                block()
                (System.nanoTime() - t) / 1e6
            }
            return samples.sorted()[runs / 2]
        }
        val out = ArrayList<Line>()
        val n = db.count(all)
        out += Line("rows", n.toDouble(), null)
        for (key in HistorySortKey.entries) {
            val q = HistoryQuery(sort = HistorySort(key, HistoryDir.DESC))
            out += Line("count + first page, ${key.name.lowercase()}", time { db.count(q); page(reads, q, HistoryAnchor.Start) }, 60)
        }
        val first = db.page(all, HistoryAnchor.Start, IndexWindow.PAGE.toUInt())
        first.last?.let { c -> out += Line("next page (keyset)", time { page(reads, all, HistoryAnchor.After(c)) }, 10) }
        val end = (n.toInt() - IndexWindow.PAGE).coerceAtLeast(0).toUInt()
        out += Line("offset jump to the end", time { page(reads, all, HistoryAnchor.Offset(end)) }, 80)
        for (text in searches) {
            val sq = HistoryQuery(text = text, sort = HistorySort(HistorySortKey.DATE, HistoryDir.DESC))
            val hits = db.count(sq)
            out += Line("search \"$text\" ($hits rows): count + first page", time { db.count(sq); page(reads, sq, HistoryAnchor.Start) }, 60)
        }
        out += Line("stats over all rows", time { decodeBrewHistoryStats(db.statsJson(all)) }, 60)
        out += Line("facets", time { db.facets(all) }, 80)
        val edges = DayGroups.edges(HistoryFilters.startOfDay(nowMs))
        out += Line("day groups", time { db.groupCounts(all, edges) }, null)
        return out
    }

    /** A page as the window reads it: decoded into rows. */
    private suspend fun page(reads: HistoryIndexReads, q: HistoryQuery, at: HistoryAnchor) {
        reads.bridge.page(q, at, IndexWindow.PAGE.toUInt()).rows.forEach { reads.decode(it.rowJson) }
    }
}

package coffee.crema.history

import coffee.crema.core.BrewSeries
import coffee.crema.core.HistoryChangeSet
import coffee.crema.core.HistoryHeavy
import coffee.crema.core.HistoryLive
import coffee.crema.core.HistoryRole
import coffee.crema.core.HistoryRowIn
import coffee.crema.core.ShotSummary
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.concurrent.withLock

/*
 * #310 A5: `history.db` as the store's rows after the flip (SQLite SPEC §2,
 * §9, M10). [HistoryStore] keeps its memory as on `shots.db` and writes here
 * instead; History reads this file through [reads].
 *
 * - **Every write takes the source's lock** ([SqliteHistorySource.lock],
 *   REVIEW-466 note 3), inside `HistoryStore.lock`, so a walk's release of
 *   its held rows never interleaves with a write.
 * - **A row and its heavy parts** go in one transaction, the row first, and
 *   a part the row doesn't carry keeps the stored one (M10).
 * - **Deletes** go through the source ([SqliteHistorySource.delete]): rows
 *   first, then their series, and while a walk is open a row of its set is
 *   held, its series kept until the walk ends (S6).
 * - Each write's change set goes out on [notices], in order (SPEC §10).
 */
internal class HistoryAuthority(
    val generation: Int,
    val db: HistoryDbBridge,
    val source: SqliteHistorySource,
    private val tables: HistoryTables,
) {
    private var sent = 0L
    private val noticeFlow = MutableSharedFlow<IndexNotice>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every write's change set, in order. */
    val notices: SharedFlow<IndexNotice> = noticeFlow.asSharedFlow()

    fun send(change: HistoryChangeSet?) {
        synchronized(noticeFlow) { noticeFlow.tryEmit(IndexNotice(++sent, change)) }
    }

    /** Every row, by `rid`, light, as `shots.db` gave them; a row that won't decode is named in [unreadable]. */
    fun loadRows(unreadable: MutableList<String>): List<DbRow> {
        val out = ArrayList<DbRow>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = db.now { it.idsCreatedBefore(ULong.MAX_VALUE, after, PAGE.toUInt()) }
            if (page.isEmpty()) break
            for (r in db.now { it.get(page.map { p -> p.id }) }) {
                val row = runCatching { tables.decodeRow(r.rowJson) }.getOrNull()
                if (row != null) out += row else unreadable += r.id
            }
            after = page.last().rid
            if (page.size < PAGE) break
        }
        return out.sortedWith(compareByDescending<DbRow> { it.shot.completedAtMs }.thenByDescending { it.shot.id })
    }

    fun idsWithProfile(): Set<String> = db.now { it.idsWithProfile() }.toHashSet()

    /** [id]'s profile and brew series as stored; both null when it has none. */
    fun heavy(id: String): Pair<JsonObject?, BrewSeries?> {
        val h = db.now { it.heavy(listOf(id)) }.firstOrNull() ?: return null to null
        return tables.decodeProfile(h.profileJson) to tables.decodeBrewSeries(h.brewSeriesJson)
    }

    /** One write of rows and their heavy parts, in one transaction. Throws on failure: nothing of it landed. */
    fun write(rows: List<Pair<StoredShot, ShotSummary>>, profiles: Map<String, JsonObject> = emptyMap()) {
        val ins = rows.map { (shot, summary) ->
            HistoryRowIn(
                rowJson = tables.rowJson(shot, summary),
                heavy = HistoryHeavy(tables.profileJson(shot) ?: profiles[shot.id]?.toString(), tables.brewSeriesJson(shot)),
                pulled = false,
            )
        }
        val written = source.lock.withLock { db.now { it.upsert(ins) } }
        send(written.change)
    }

    /** The live libraries (SPEC §5, §6): derived data the coffee scope, effective tags and method labels read. */
    fun setLive(live: HistoryLive) {
        send(source.lock.withLock { db.now { it.setLive(live) } })
    }

    /** Remove [ids] (rule 1), through the source: rows, then their series (S6). On IO, under the store's lock. */
    fun deleteNow(ids: List<String>) {
        send(source.deleteNow(ids))
    }

    fun close() {
        release(db)
        runCatching { db.now { it.closeAll() } }
    }

    companion object {
        /** Rows per page of a load. */
        const val PAGE = 500

        /**
         * Every truth file open in this process, by path (S4: one connection
         * per file). A second store here (a view model made again while the
         * first one's store still holds the file) takes the file over: the
         * first one's bridge closes under the core's lock, so its later writes
         * fail into stage 2's journal rather than refusing the new store.
         */
        private val HELD = HashMap<String, HistoryDbBridge>()

        /** Opens [file] as the truth, taking it over from another store in this process. */
        fun openTakingOver(file: File): HistoryDbBridge = synchronized(HELD) {
            val key = file.absolutePath
            HELD.remove(key)?.let { old -> runCatching { old.now { it.closeAll() } } }
            HistoryDbBridge.openNow(file, HistoryDbBridge.options(HistoryRole.AUTHORITY)).also { HELD[key] = it }
        }

        /** [db], opened elsewhere (the flip's copy), is this process's holder of [file]. */
        fun hold(file: File, db: HistoryDbBridge) = synchronized(HELD) {
            val key = file.absolutePath
            HELD.remove(key)?.takeIf { it !== db }?.let { old -> runCatching { old.now { it.closeAll() } } }
            HELD[key] = db
        }

        private fun release(db: HistoryDbBridge) = synchronized(HELD) { HELD.entries.removeAll { it.value === db } }

        /** Erase (S11): every truth file this process holds, closed under the core's lock, before anything goes. */
        fun closeEveryHeld() {
            val all = synchronized(HELD) { HELD.values.toList().also { HELD.clear() } }
            for (b in all) runCatching { b.now { it.closeAll() } }
        }
    }
}

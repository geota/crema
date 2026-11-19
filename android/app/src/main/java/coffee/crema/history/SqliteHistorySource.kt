package coffee.crema.history

import coffee.crema.core.HistoryChangeSet
import coffee.crema.core.HistoryHeavy
import coffee.crema.core.HistoryRowIn
import coffee.crema.restore.NextShots
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Where the SQLite store's shots keep what isn't in `row_json`: the
 * `.series.gz` curves, and the profile and brew series (`shot_heavy` from
 * A5). A5 gives the app's; tests give their own.
 */
internal interface ShotDetails {
    /** [row] whole. Throws [HistoryReadException] when a read failed just now. */
    fun full(row: ShotRow): StoredShot

    fun hasCurve(row: ShotRow): Boolean

    /** The row of [shot], with its summary. */
    fun rowFor(shot: StoredShot): ShotRow

    /** These shots' series, written before any row names them. Returns how many failed. */
    fun write(shots: List<StoredShot>): Int

    /** These shots' details, for rows that are gone or about to go. */
    fun delete(ids: Collection<String>)
}

/** Where [SqliteHistorySource] can be stopped in a test, as a kill would. */
internal enum class SourceFault {
    /** A hold's rows are deleted; their details aren't yet (S6, SPEC "From R1w"). */
    RELEASE_AFTER_ROWS,

    /** A restore's merge is about to write its next batch. */
    BEFORE_MERGE_BATCH,

    /** A restore's wipe is about to remove its next batch. */
    BEFORE_WIPE_BATCH,
}

/**
 * #310 R1a: the SQLite store as a [HistorySource] (SQLite SPEC §9). From A5
 * the app's backups walk it once the store is on `history.db`.
 *
 * - **Export** uses the store's walk: its set and order are fixed when it
 *   begins (newest first, ties by id, as P3a lists rows), and a delete during
 *   it holds the row, so the header's count is the lines written. When the
 *   walk ends, and when the store opens ([open]), the held rows are released
 *   **rows first** (SPEC "From R1w"): the rows still held go, then the
 *   series of exactly those with no row now. A kill between leaves series
 *   with no row, which is harmless, never a row without its curve.
 * - **Deletes** ([delete]) keep a row's details while a walk is open, since
 *   the walk may still read them; the walk's release takes them.
 * - **Restore** is merge, then remove (M3): [beginRestore] reads the store's
 *   `write_seq`, the merge upserts in batches with incoming rows winning, and
 *   the wipe takes only rows with `created_seq` at or below it that the
 *   backup doesn't hold, read in pages by `rid`, with no `TEMP` table. A shot
 *   written during the restore is newer, so it is never in the wipe.
 * - **Auto-backup** gets [writeMark], the file's generation and `write_seq`.
 *
 * Every call that spans more than one store call (release, delete, a merge
 * batch, a wipe batch) runs under [lock]. A5's writers take it too, so no
 * write lands between a release's read of the held ids and their removal.
 * The `row_json` is P3a's ([HistoryTables.rowJson]), so a row reads back the
 * same from either store.
 */
internal class SqliteHistorySource private constructor(
    private val db: HistoryDbBridge,
    json: Json,
    private val details: ShotDetails,
) : HistorySource {
    private val tables = HistoryTables(json)

    /** Held over every multi-call write (see the class docs). */
    val lock = ReentrantLock()

    /** Walks open on this store now. */
    private var walks = 0

    /** Rows deleted while a walk was open whose details wait for the walk's end. */
    private val deferred: MutableSet<String> = ConcurrentHashMap.newKeySet()

    companion object {
        /** [db] as a source, releasing the holds a closed or killed session left (S6). Blocking. */
        fun open(db: HistoryDbBridge, json: Json, details: ShotDetails): SqliteHistorySource =
            SqliteHistorySource(db, json, details).also { it.releaseHolds() }

        /** Rows per merge or wipe batch, and per page of ids. */
        const val BATCH = 250

        /** Test seam: called at each [SourceFault] point; a test throws [SimulatedCrash] where a kill should land. */
        @androidx.annotation.VisibleForTesting
        @Volatile
        internal var faultHook: ((SourceFault) -> Unit)? = null
    }

    private fun crash(f: SourceFault) {
        faultHook?.invoke(f)
    }

    private fun decode(rowJson: String): ShotRow? =
        runCatching { tables.decodeRow(rowJson).let { it.shot.toRow(it.summary ?: unknownSeriesSummary(it.shot)) } }.getOrNull()

    private fun encode(row: ShotRow): String = tables.rowJson(row.light(), row.summary)

    // ── Export (S6) ──────────────────────────────────────────────────────────

    override fun openWalk(): ShotWalk {
        val begun = lock.withLock {
            db.now { it.exportBegin() }.also { walks++ }
        }
        return object : ShotWalk {
            private var closed = false

            override val count: Int = begun.count.toInt()

            override fun next(n: Int): List<WalkRow> =
                db.now { it.exportNext(begun.token, n.toUInt()) }.map { WalkRow(it.id, decode(it.rowJson)) }

            override fun rewind() = db.now { it.exportRewind(begun.token) }

            override fun full(row: ShotRow): StoredShot = details.full(row)

            override fun close() {
                if (closed) return
                closed = true
                try {
                    // Gone already when the store reopened: its holds are released all the same.
                    runCatching { db.now { it.exportEnd(begun.token) } }
                } finally {
                    lock.withLock { walks-- }
                }
                // A release that fails (the store closed under us) leaves the holds for the next
                // open, which releases them; the backup the walk wrote stands.
                try {
                    releaseHolds()
                } catch (e: SimulatedCrash) {
                    throw e
                } catch (e: Exception) {
                    runCatching { coffee.crema.diag.DiagLog.add("history: releasing a walk's holds failed: $e") }
                }
            }
        }
    }

    /**
     * The held rows no walk needs, released **rows first** (SPEC "From R1w",
     * REVIEW-468 W5-1): the core removes only rows still held
     * (`delete_held`), and only then go the details of exactly those ids,
     * and of rows deleted outright while a walk was open, each only when no
     * row holds its id now. A row an upsert brought back meanwhile keeps its
     * curve. A kill between leaves details with no row, which is harmless
     * (SPEC §2): every writer puts details before the row, so a recreated id
     * overwrites them. Returns the held ids it removed. Blocking.
     */
    fun releaseHolds(): List<String> = lock.withLock {
        val held = db.now { it.releaseHolds() }
        val removed = if (held.isEmpty()) emptyList() else db.now { it.deleteHeld(held) }
        val gone = if (walks == 0 && deferred.isNotEmpty()) {
            val ids = deferred.toList()
            deferred.removeAll(ids.toSet())
            ids
        } else {
            emptyList()
        }
        val candidates = (removed + gone).distinct()
        if (candidates.isEmpty()) return emptyList()
        crash(SourceFault.RELEASE_AFTER_ROWS)
        // Written again since (a restore, a pull): its details are its own again.
        val back = db.now { it.get(candidates) }.mapTo(HashSet()) { it.id }
        val orphaned = candidates.filterNot { it in back }
        if (orphaned.isNotEmpty()) details.delete(orphaned)
        removed
    }

    /**
     * Remove [ids], the one way a row leaves this store (rule 1): the rows,
     * then their details. While a walk is open a row of its set is held, and
     * its details stay until the walk's release.
     */
    suspend fun delete(ids: List<String>): HistoryChangeSet? = withContext(Dispatchers.IO) { deleteNow(ids) }

    /** [delete] on the calling thread, for a caller on IO (the store's writes, under its lock). */
    fun deleteNow(ids: List<String>): HistoryChangeSet? = lock.withLock {
        if (ids.isEmpty()) return@withLock null
        val change = db.now { it.delete(ids) }
        if (walks == 0) details.delete(ids) else deferred += ids
        change
    }

    override fun writeMark(): String? = db.now { "${db.path}#${it.info().generation}#${it.writeSeq()}" }

    // ── Restore (M3) ─────────────────────────────────────────────────────────

    /**
     * Every row the store lists, read in pages by `rid` (never
     * [HistoryDbBridge.page], which is for display), as one set (S3,
     * REVIEW-466 note 4): `write_seq` is read before the first page and
     * after the last, and a walk it moved under starts again. After three
     * tries the pages are read under [lock], which every writer takes, so
     * nothing moves. A planner never sees a partial or moving set.
     */
    fun allRows(): List<ShotRow> {
        repeat(3) {
            val before = db.now { it.writeSeq() }
            val rows = readAllRows()
            if (db.now { it.writeSeq() } == before) return rows
        }
        return lock.withLock { readAllRows() }
    }

    private fun readAllRows(): List<ShotRow> {
        val out = ArrayList<ShotRow>()
        var after = Long.MIN_VALUE
        while (true) {
            val page = db.now { it.idsCreatedBefore(ULong.MAX_VALUE, after, BATCH.toUInt()) }
            if (page.isEmpty()) break
            db.now { it.get(page.map { r -> r.id }) }.mapNotNullTo(out) { decode(it.rowJson) }
            after = page.last().rid
            if (page.size < BATCH) break
        }
        return out
    }

    override suspend fun beginRestore(): ShotsRestore = withContext(Dispatchers.IO) {
        Restore(db.now { it.restoreBegin() })
    }

    /** Step 7 on this store, from [beginSeq]: rows written after it are never in the wipe. */
    inner class Restore(val beginSeq: ULong) : ShotsRestore {
        private val heavy = ConcurrentHashMap<String, HistoryHeavy>()

        override suspend fun rows(): List<ShotRow> = withContext(Dispatchers.IO) { allRows() }

        override fun hasCurve(row: ShotRow): Boolean = details.hasCurve(row)

        override fun rowFor(shot: StoredShot): ShotRow = details.rowFor(shot)

        override suspend fun writeSeries(shots: List<StoredShot>): Int = withContext(Dispatchers.IO) { details.write(shots) }

        override suspend fun settle(next: (List<ShotRow>) -> NextShots): NextShots = next(rows())

        override fun stage(shot: StoredShot) {
            // A backup row without a profile or brew series keeps the stored one (P3 spec §7, #419 N1).
            heavy[shot.id] = HistoryHeavy(tables.profileJson(shot), tables.brewSeriesJson(shot))
        }

        override suspend fun merge(rows: List<ShotRow>): Boolean = withContext(Dispatchers.IO) {
            try {
                crash(SourceFault.BEFORE_MERGE_BATCH)
                val batch = rows.map { HistoryRowIn(encode(it), heavy[it.id]) }
                lock.withLock { db.now { it.restoreBatch(batch) } }
                true
            } catch (e: SimulatedCrash) {
                throw e
            } catch (e: Exception) {
                runCatching { coffee.crema.diag.DiagLog.add("history: restore batch failed: $e") }
                false
            }
        }

        override suspend fun remove(named: List<String>, backupIds: Set<String>) = withContext(Dispatchers.IO) {
            if (named.isEmpty()) return@withContext
            val want = named.toHashSet()
            // Never by what this run wrote: by when each row was made, and the backup's own ids (M3).
            // Each page is read and removed under one hold of the lock, so a row made again
            // between pages (a pull, a capture) has a later seq and is never in a page read
            // before it, as the stepped index removal learned (REVIEW-448 note 1).
            var after = Long.MIN_VALUE
            while (true) {
                val more = lock.withLock {
                    val page = db.now { it.idsCreatedBefore(beginSeq, after, BATCH.toUInt()) }
                    if (page.isEmpty()) return@withLock false
                    after = page.last().rid
                    val wipe = page.mapNotNull { r -> r.id.takeIf { it in want && it !in backupIds } }
                    if (wipe.isNotEmpty()) {
                        crash(SourceFault.BEFORE_WIPE_BATCH)
                        deleteNow(wipe)
                    }
                    page.size == BATCH
                }
                if (!more) break
            }
        }
    }
}

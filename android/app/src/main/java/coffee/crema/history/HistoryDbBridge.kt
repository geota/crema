package coffee.crema.history

import coffee.crema.core.HistoryAnchor
import coffee.crema.core.HistoryApplied
import coffee.crema.core.HistoryChangeSet
import coffee.crema.core.HistoryDestination
import coffee.crema.core.HistoryDir
import coffee.crema.core.HistoryExportWalk
import coffee.crema.core.HistoryFacets
import coffee.crema.core.HistoryFileInfo
import coffee.crema.core.HistoryFtsCheck
import coffee.crema.core.HistoryHealth
import coffee.crema.core.HistoryIndexState
import coffee.crema.core.HistoryIndexWrite
import coffee.crema.core.HistoryJournalMode
import coffee.crema.core.HistoryLive
import coffee.crema.core.HistoryMaintained
import coffee.crema.core.HistoryOpenOptions
import coffee.crema.core.HistoryPage
import coffee.crema.core.HistoryQuery
import coffee.crema.core.HistoryRecovery
import coffee.crema.core.HistoryRidId
import coffee.crema.core.HistoryRidRow
import coffee.crema.core.HistoryRole
import coffee.crema.core.HistoryRow
import coffee.crema.core.HistoryRowIn
import coffee.crema.core.HistorySort
import coffee.crema.core.HistorySortKey
import coffee.crema.core.HistorySyncPage
import coffee.crema.core.HistoryWrite
import coffee.crema.core.HistoryWritten
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import coffee.crema.core.HistoryDb as CoreHistoryDb

/**
 * #310 C3: the core's SQLite history store (`core/de1-ffi/src/history.rs`),
 * one file per bridge, every call on [io] (`Dispatchers.IO` by default).
 *
 * The core keeps **one connection per file behind one mutex** (REVIEW-442
 * note 3): reads and writes from any number of coroutines run one at a time,
 * and the export walk's set, the delete holds, the search index repair and
 * the reopen after an error all stay on that connection. So there is no
 * read connection to route around.
 *
 * Rules for callers:
 * - **[page] is for display only.** An edit that moves a row's sort key
 *   across a cursor shows that row again on a later page. Anything that acts
 *   on each row (bulk upload, CSV, bulk edit) reads a `rid`-ordered call
 *   ([unsent], [syncRows], the export walk) or dedupes by id.
 * - **An `authority` file opens with `create = false`** (REVIEW-439 note 10):
 *   the core refuses `create` for one, so a missing `history.db` is
 *   [coffee.crema.core.HistoryDbException.CantOpen], never a fresh empty
 *   truth. A new one comes from [copyTo] (the flip) or [createRecovery].
 * - **Erase calls [closeAll] first** (S11): it closes the file under the
 *   core's lock, and every later call throws
 *   [coffee.crema.core.HistoryDbException.Closed], so nothing writes to a
 *   file erase is about to remove.
 *
 * Errors are [coffee.crema.core.HistoryDbException]s, one subclass per
 * class (SPEC §12). From A1 the app's one caller is [HistoryIndex], which
 * runs inside `HistoryStore.lock` on IO and so calls through [now] and
 * [openNow] rather than switching dispatchers under the lock.
 */
class HistoryDbBridge private constructor(
    private val db: CoreHistoryDb,
    private val io: CoroutineDispatcher,
) {
    companion object {
        /** Every row, newest first. */
        val ALL: HistoryQuery = HistoryQuery(sort = HistorySort(HistorySortKey.DATE, HistoryDir.DESC))

        /** Options for [role] in WAL, the way Android opens every file. */
        fun options(role: HistoryRole, create: Boolean = false, generation: UInt = 0u, nowMs: Long = System.currentTimeMillis()) =
            HistoryOpenOptions(role = role, create = create, generation = generation, journal = HistoryJournalMode.WAL, nowMs = nowMs)

        /** Opens [file] and checks it. */
        suspend fun open(file: File, options: HistoryOpenOptions, io: CoroutineDispatcher = Dispatchers.IO): HistoryDbBridge =
            withContext(io) { HistoryDbBridge(CoreHistoryDb.open(file.path, options), io) }

        /** [open] on the calling thread, for a caller already on IO inside its own lock (A1's hook). */
        internal fun openNow(file: File, options: HistoryOpenOptions, io: CoroutineDispatcher = Dispatchers.IO): HistoryDbBridge =
            HistoryDbBridge(CoreHistoryDb.open(file.path, options), io)

        /** Creates the recovery file [file] after `lost` (S9) and opens it. */
        suspend fun createRecovery(
            file: File,
            options: HistoryOpenOptions,
            recovery: HistoryRecovery,
            io: CoroutineDispatcher = Dispatchers.IO,
        ): HistoryDbBridge = withContext(io) { HistoryDbBridge(CoreHistoryDb.createRecovery(file.path, options, recovery), io) }
    }

    private suspend inline fun <T> call(crossinline f: (CoreHistoryDb) -> T): T = withContext(io) { f(db) }

    /**
     * [f] on the calling thread: for a caller already on IO that holds a lock
     * the call must stay inside, as A1's hook does under `HistoryStore.lock`
     * (M4). The core's own mutex still orders it with every other call.
     */
    internal fun <T> now(f: (CoreHistoryDb) -> T): T = f(db)

    val path: String get() = db.path()

    /** Closes the file under the core's lock (S11). Idempotent. */
    suspend fun closeAll() = call { it.closeAll() }

    val isClosed: Boolean get() = db.isClosed()

    suspend fun info(): HistoryFileInfo = call { it.info() }

    suspend fun writeSeq(): ULong = call { it.writeSeq() }

    suspend fun health(): HistoryHealth = call { it.health() }

    suspend fun setBusy(busy: Boolean) = call { it.setBusy(busy) }

    suspend fun setBatchRows(rows: UInt) = call { it.setBatchRows(rows) }

    // Writes, each one transaction returning its change set.

    suspend fun upsert(rows: List<HistoryRowIn>): HistoryWritten = call { it.upsert(rows) }

    suspend fun delete(ids: List<String>): HistoryChangeSet = call { it.delete(ids) }

    suspend fun setLive(live: HistoryLive): HistoryChangeSet = call { it.setLive(live) }

    suspend fun apply(tab: String, seq: ULong, atMs: Long, write: HistoryWrite): HistoryApplied = call { it.apply(tab, seq, atMs, write) }

    suspend fun appliedSeq(tab: String): ULong = call { it.appliedSeq(tab) }

    suspend fun pruneTabs(gone: List<String>, beforeMs: Long): ULong = call { it.pruneTabs(gone, beforeMs) }

    // Queries.

    suspend fun count(query: HistoryQuery): UInt = call { it.count(query) }

    /** A page, for display only (see the class docs). */
    suspend fun page(query: HistoryQuery, at: HistoryAnchor, limit: UInt): HistoryPage = call { it.page(query, at, limit) }

    suspend fun indexOf(query: HistoryQuery, id: String): UInt? = call { it.indexOf(query, id) }

    suspend fun groupCounts(query: HistoryQuery, edgesMs: List<Long>): List<UInt> = call { it.groupCounts(query, edgesMs) }

    /** The stat strip, as `BrewHistoryStats` JSON. */
    suspend fun statsJson(query: HistoryQuery): String = call { it.statsJson(query) }

    suspend fun facets(query: HistoryQuery): HistoryFacets = call { it.facets(query) }

    suspend fun get(ids: List<String>): List<HistoryRow> = call { it.get(ids) }

    suspend fun byRemoteId(visualizerId: String): List<HistoryRow> = call { it.byRemoteId(visualizerId) }

    suspend fun byDecentId(decentId: String): List<HistoryRow> = call { it.byDecentId(decentId) }

    suspend fun dosesForBag(beanId: String, limit: UInt): List<Double> = call { it.dosesForBag(beanId, limit) }

    suspend fun unsent(destination: HistoryDestination, afterRid: Long, limit: UInt): List<HistoryRidRow> =
        call { it.unsent(destination, afterRid, limit) }

    suspend fun syncRows(afterRid: Long, limit: UInt): HistorySyncPage = call { it.syncRows(afterRid, limit) }

    suspend fun syncProjection(): HistorySyncPage = call { it.syncProjection() }

    // Export walk and restore (M3, S6).

    suspend fun exportBegin(): HistoryExportWalk = call { it.exportBegin() }

    suspend fun exportNext(token: ULong, n: UInt): List<HistoryRow> = call { it.exportNext(token, n) }

    suspend fun exportEnd(token: ULong) = call { it.exportEnd(token) }

    /** Held ids no walk needs: delete their details, then [delete] them. */
    suspend fun releaseHolds(): List<String> = call { it.releaseHolds() }

    suspend fun restoreBegin(): ULong = call { it.restoreBegin() }

    suspend fun restoreBatch(rows: List<HistoryRowIn>): HistoryWritten = call { it.restoreBatch(rows) }

    suspend fun idsCreatedBefore(beginSeq: ULong, afterRid: Long, limit: UInt): List<HistoryRidId> =
        call { it.idsCreatedBefore(beginSeq, afterRid, limit) }

    // Maintenance.

    suspend fun maintain(): HistoryMaintained = call { it.maintain() }

    suspend fun maintainAll(steps: UInt): UInt = call { it.maintainAll(steps) }

    suspend fun startFtsRebuild() = call { it.startFtsRebuild() }

    suspend fun ftsIntegrityCheck(): HistoryFtsCheck = call { it.ftsIntegrityCheck() }

    suspend fun derivedMismatches(): List<String> = call { it.derivedMismatches() }

    // The stage-1 index; the core refuses these on an `authority` file.

    suspend fun diffBatch(rows: List<HistoryRowIn>): HistoryWritten = call { it.diffBatch(rows) }

    suspend fun indexWrite(write: HistoryIndexWrite): HistoryWritten = call { it.indexWrite(write) }

    suspend fun removeAbsent(ids: List<String>, sinceSeq: ULong): HistoryChangeSet = call { it.removeAbsent(ids, sinceSeq) }

    suspend fun setSourceSeq(seq: ULong) = call { it.setSourceSeq(seq) }

    suspend fun resetSeqs(seq: ULong) = call { it.resetSeqs(seq) }

    suspend fun bumpPending(): ULong = call { it.bumpPending() }

    suspend fun finishAndroidDiff(versionCode: Long) = call { it.finishAndroidDiff(versionCode) }

    suspend fun indexState(): HistoryIndexState = call { it.indexState() }

    // Files.

    suspend fun migrationState(): String? = call { it.migrationState() }

    suspend fun lateBaseline(): String? = call { it.lateBaseline() }

    suspend fun migrationSourceLen(): ULong = call { it.migrationSourceLen() }

    /** Copies this file into [dest] with the online backup API. */
    suspend fun copyTo(dest: File, journal: HistoryJournalMode = HistoryJournalMode.WAL) = call { it.copyTo(dest.path, journal) }
}

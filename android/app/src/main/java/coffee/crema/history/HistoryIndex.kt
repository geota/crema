package coffee.crema.history

import android.database.sqlite.SQLiteDatabase
import coffee.crema.BuildConfig
import coffee.crema.core.HistoryAnchor
import coffee.crema.core.HistoryChangeSet
import coffee.crema.core.HistoryDbException
import coffee.crema.core.HistoryIndexWrite
import coffee.crema.core.HistoryLive
import coffee.crema.core.HistoryRole
import coffee.crema.core.HistoryRowIn
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/*
 * #310 A1: `history-index.db`, the stage-1 index (SQLite SPEC §3, §11 stage 1,
 * M4). It is derived from P3a's `shots.db`, which stays the truth, and nothing
 * reads it yet (A2 does).
 *
 * - **The hook.** Every write to `shots` goes through [HistoryTables.write],
 *   inside `HistoryStore.lock`, which calls this as its [ShotsHook]: `pending`
 *   up in its own transaction, the `shots.db` transaction, then the rows it
 *   touched, read back as committed, into the index with `pending` down in
 *   one index transaction. A crash or a failed index write leaves `pending`
 *   above 0.
 * - **The seq (#310 A1b).** Each `shots` transaction also raises `index_seq`
 *   in `shots.db`'s `meta`, and the index write that mirrors it applies that
 *   seq to the index's `source_seq` (with `seen_seqs` for any past a gap,
 *   as on web). So a write the index missed for any reason (`pending` that
 *   couldn't be raised, another store in the process, a pair of files copied
 *   at different moments) leaves the two seqs apart, recorded by `shots.db`
 *   itself in the write's own transaction.
 * - **Never in the way.** No index failure reaches the P3a write: the
 *   `shots.db` transaction commits whatever happens here, and the next boot's
 *   full diff repairs the index.
 * - **The boot check** passes only when `pending` is 0, the index's
 *   `source_seq` is `shots.db`'s `index_seq` with no seq seen past a gap,
 *   the last full diff ran on this `versionCode`, and the counts match.
 *   Otherwise the **full diff** runs: every row in batches of [BATCH], each
 *   batch under the lock, then the ids the index holds and `shots.db`
 *   doesn't are removed (the one delete by absence, allowed because the
 *   index is derived). It settles only when the index has every seq up to
 *   `shots.db`'s at its end.
 * - **A write that clears the table** (the migration, a lost store rebuilt)
 *   leaves `pending` raised, and the next [HistoryStore.syncIndex] rebuilds,
 *   after the store's marker is written and outside the write.
 * - **Erase** closes the file under the core's lock first ([erasing], S11),
 *   and nothing opens or makes it again until the erase returns.
 *
 * - **Reads (#310 A2).** History's list, search, stats, facets and day
 *   groups read the index through [reader], only while it is **fresh**: the
 *   boot check passed or a full diff settled this session, and every write
 *   since reached it. Anything else (an open that failed, a write it missed,
 *   `pending` raised for a diff, a cleared table, the file closed) makes it
 *   stale until the next settle, and the screens use the in-memory rows
 *   (`index_fallback`). Each index write's change set goes out on [notices],
 *   in order, from under the lock.
 *
 * Rust opens only this file and Kotlin never opens it (decision 11, N3); it
 * lives in `filesDir/history-sqlite/`, outside `databases/`.
 *
 * Every call but [health]'s fields runs under `HistoryStore.lock` on IO.
 */

/** Where a test makes the index hook stop or fail (process-wide, as [HistoryStore.faults]). */
enum class IndexFault {
    /** `pending` is up; the `shots.db` write hasn't run. */
    CRASH_AFTER_RAISE,

    /** `shots.db` committed; the index write hasn't run. */
    CRASH_AFTER_SHOTS,

    /** A multi-batch index write: the first batch is in, `pending` still up. */
    CRASH_BETWEEN_BATCHES,

    /** The index write committed and `pending` is down. */
    CRASH_AFTER_INDEX,

    /** The full diff wrote its first batch. */
    CRASH_IN_DIFF,

    /** Raising `pending` fails. */
    FAIL_RAISE,

    /** The index write fails. */
    FAIL_WRITE,
}

/** The last full diff: why it ran, what it did, how it ended. */
data class IndexDiffReport(
    val atMs: Long,
    val tookMs: Long,
    val reasons: List<String>,
    val rows: Int,
    val upserted: Int,
    val removed: Int,
    /** "ok", or why the diff didn't finish (the next boot runs it again). */
    val outcome: String,
) {
    val finished: Boolean get() = outcome == OK

    companion object {
        const val OK = "ok"
    }
}

/** The index as the debug screen shows it. Null fields couldn't be read. */
data class IndexHealth(
    val open: Boolean,
    val path: String,
    val bytes: Long,
    val error: String?,
    val pending: ULong?,
    val diffVersionCode: Long?,
    val versionCode: Long,
    val indexRows: Int?,
    val shotsRows: Int?,
    /** The last `shots.db` seq the index mirrored, and those it saw past a gap. */
    val sourceSeq: ULong?,
    val seenSeqs: List<ULong>,
    /** `shots.db`'s `index_seq`: its last write. */
    val shotsSeq: ULong?,
    val hookFailures: Long,
    /** Why the boot check misses now: empty when it passes. */
    val misses: List<String>,
    val lastDiff: IndexDiffReport?,
)

/** The index against `shots.db`, row by row (`row_json` as stored). */
data class IndexComparison(val rows: Int, val missing: List<String>, val extra: List<String>, val differ: List<String>) {
    val exact: Boolean get() = missing.isEmpty() && extra.isEmpty() && differ.isEmpty()

    override fun toString(): String =
        if (exact) "equal: $rows rows" else "${missing.size} missing, ${extra.size} extra, ${differ.size} differ (of $rows)"
}

/**
 * One index write's notice (#310 A2, SPEC §10): [n] counts every notice this
 * index sent, so a reader that sees a gap missed one (the buffer dropped it)
 * and refetches everything. A null [change] says the same outright.
 */
class IndexNotice(val n: Long, val change: HistoryChangeSet?)

internal class HistoryIndex(
    private val dir: File,
    private val tables: () -> HistoryTables,
    val versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
    /** Called whenever [fresh] may have changed, from under the store's lock. */
    private val onFreshness: () -> Unit = {},
) : ShotsHook {
    val file: File get() = File(dir, FILE_NAME)

    /** The open index, or null. Closed under us (another store in this process took it over) stays closed. */
    @Volatile private var bridge: HistoryDbBridge? = null

    /** An open was tried this session: a failed one is tried again only at [planDiff] (boot, retry). */
    private var openTried = false

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastDiff: IndexDiffReport? = null
        private set

    private var logged = 0

    /**
     * The index mirrors `shots.db` exactly, as far as this process knows
     * (#310 A2): set when the boot check passes or a full diff settles, and
     * cleared by anything that could leave it behind. Only a fresh index is read.
     */
    @Volatile var fresh = false
        private set

    private fun setFresh(v: Boolean) {
        if (fresh == v) return
        fresh = v
        onFreshness()
    }

    private var sent = 0L
    private val noticeFlow = MutableSharedFlow<IndexNotice>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every index write's change set, in order (SPEC §10). Sent from under the lock; never waits. */
    val notices: SharedFlow<IndexNotice> = noticeFlow.asSharedFlow()

    private fun send(change: HistoryChangeSet?) {
        synchronized(noticeFlow) { noticeFlow.tryEmit(IndexNotice(++sent, change)) }
    }

    /** The open index while it's [fresh]: what History reads (#310 A2). Null otherwise. */
    fun reader(): HistoryDbBridge? = bridge?.takeIf { fresh && !it.isClosed }

    /** What a write's [begin] raised `pending` on. */
    private class Ticket(val db: HistoryDbBridge, val raised: Boolean)

    // ── Open and close ───────────────────────────────────────────────────────

    /** The open index, opening it the first time (or again when [retry]). Never while an erase runs. */
    private fun opened(retry: Boolean = false): HistoryDbBridge? {
        if (ERASING.get() > 0) return null
        bridge?.let { return if (it.isClosed) null else it }
        if (openTried && !retry) return null
        openTried = true
        return try {
            openFresh().also { bridge = it; lastError = null }
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            failure("open", e)
            setFresh(false)
            null
        }
    }

    private fun openFresh(): HistoryDbBridge {
        synchronized(HELD) {
            // Checked again under HELD's lock, which [erasing] takes too: an erase that began
            // meanwhile has closed the file, and nothing makes the directory again under it.
            check(ERASING.get() == 0) { "an erase is running" }
            dir.mkdirs()
            val key = file.absolutePath
            // One process, one connection per file (S4): an earlier store here (a recreated view
            // model) gives it up. Its later writes leave seqs the index never saw.
            HELD.remove(key)?.let { old -> runCatching { old.now { it.closeAll() } } }
            val options = HistoryDbBridge.options(HistoryRole.INDEX, create = true)
            val b = try {
                HistoryDbBridge.openNow(file, options)
            } catch (e: HistoryDbException) {
                if (!disposable(e)) throw e
                // Derived (rule 4): a damaged or foreign index is rebuilt, never repaired.
                persist("index unreadable (${e.javaClass.simpleName}), rebuilding it: ${e.message}")
                for (suffix in SUFFIXES) File(file.path + suffix).delete()
                HistoryDbBridge.openNow(file, options)
            }
            HELD[key] = b
            return b
        }
    }

    /** Close the file under the core's lock. The next write opens it again. */
    fun closeAll() {
        setFresh(false)
        val mine = bridge
        bridge = null
        openTried = false
        val held = synchronized(HELD) { HELD.remove(file.absolutePath) }
        for (b in listOfNotNull(mine, held).distinct()) runCatching { b.now { it.closeAll() } }
    }

    /**
     * Erase (S11, REVIEW-446 note 4): close the file under the core's lock
     * before [block] removes anything, and let no store in this process open
     * or make it again until [block] returns.
     */
    fun <T> erasing(block: () -> T): T {
        synchronized(HELD) { ERASING.incrementAndGet() }
        try {
            closeAll()
            return block()
        } finally {
            ERASING.decrementAndGet()
        }
    }

    // ── The hook (M4) ────────────────────────────────────────────────────────

    override fun begin(): Any? {
        val db = opened() ?: return null.also { setFresh(false) }
        val raised = try {
            fail(IndexFault.FAIL_RAISE)
            db.now { it.bumpPending() }
            true
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            failure("raise pending", e)
            false
        }
        crash(IndexFault.CRASH_AFTER_RAISE)
        return Ticket(db, raised)
    }

    override fun committed(ticket: Any?, db: SQLiteDatabase, write: ShotsWrite) {
        // No ticket: the index isn't open. The write's seq is the record of the miss.
        val t = ticket as? Ticket ?: return
        crash(IndexFault.CRASH_AFTER_SHOTS)
        // A write that cleared the table: `pending` stays up, and the next syncIndex rebuilds (REVIEW-446 note 5).
        if (write.cleared) return setFresh(false)
        try {
            fail(IndexFault.FAIL_WRITE)
            mirror(t.db, db, write.touched, write.seq, lower = t.raised)
        } catch (e: SimulatedCrash) {
            setFresh(false)
            throw e
        } catch (e: Throwable) {
            failure("index write", e)
            setFresh(false)
            return
        }
        crash(IndexFault.CRASH_AFTER_INDEX)
    }

    override fun failed(ticket: Any?, error: Throwable) {
        // The `shots.db` transaction threw and took its seq with it: `pending` stays up, and the next boot's diff settles it.
        if (ticket != null) HOOK_FAILURES.incrementAndGet()
        setFresh(false)
    }

    override fun replaced() {
        // `shots.db` went aside or was deleted: whatever the index holds may be wrong now, and the new file's seq starts again.
        setFresh(false)
        val db = opened() ?: return
        runCatching { db.now { it.bumpPending() } }
    }

    /**
     * [ids]' rows as `shots.db` committed them into the index; an id it lacks
     * leaves the index too. The last batch applies the write's [seq] and lowers
     * `pending`, so a crash between batches leaves both for the next boot.
     */
    private fun mirror(db: HistoryDbBridge, shots: SQLiteDatabase, ids: Set<String>, seq: ULong, lower: Boolean) {
        val list = ids.toList()
        val rows = tables().rawRows(shots, list)
        val batches = list.chunked(BATCH).ifEmpty { listOf(emptyList()) }
        batches.forEachIndexed { i, batch ->
            if (i > 0) crash(IndexFault.CRASH_BETWEEN_BATCHES)
            val last = i == batches.lastIndex
            val write = HistoryIndexWrite(
                upsert = batch.mapNotNull { id -> rows[id]?.let { HistoryRowIn(it) } },
                delete = batch.filter { it !in rows },
                seenSeqs = if (last) listOf(seq) else emptyList(),
                lowerPending = lower && last,
            )
            send(db.now { it.indexWrite(write) }.change)
        }
    }

    /**
     * The live bean and method libraries the coffee scope, the effective tags
     * and a custom method's label read (SPEC §5, §6): derived data, rewritten
     * whole when they change (the core skips an unchanged set). Under the lock.
     * True when the index holds them.
     */
    fun setLive(live: HistoryLive): Boolean {
        val db = opened() ?: return false
        return try {
            send(db.now { it.setLive(live) })
            true
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            failure("live libraries", e)
            false
        }
    }

    // ── The boot check and the full diff ─────────────────────────────────────

    /** A full diff under way: each step runs under the lock. */
    internal class Diff(
        val db: HistoryDbBridge,
        /** The index's own `write_seq` when it began: rows it writes after are never removed by absence. */
        val since: ULong,
        /** `shots.db`'s `index_seq` when it began: every write up to it is in the rows the diff reads. */
        val shotsSeq: ULong,
        val reasons: List<String>,
        val startedAtMs: Long,
    ) {
        var after: String? = null
        var rows = 0
        var upserted = 0
        var outcome: String? = null
    }

    /** Why the boot check misses now, against [shots]: empty when it passes. */
    private fun misses(db: HistoryDbBridge, shots: SQLiteDatabase): List<String> {
        val out = ArrayList<String>()
        val state = db.now { it.indexState() }
        if (state.pending > 0uL) out += "pending is ${state.pending}"
        val shotsSeq = tables().indexSeq(shots)
        if (state.sourceSeq != shotsSeq || state.seenSeqs.isNotEmpty()) {
            val gap = if (state.seenSeqs.isEmpty()) "" else ", and ${state.seenSeqs.size} past a gap"
            out += "the index has shots.db's writes to ${state.sourceSeq}$gap; shots.db is at $shotsSeq"
        }
        if (state.diffVersionCode != versionCode) out += "last full diff on versionCode ${state.diffVersionCode ?: "none"}, this is $versionCode"
        val indexRows = db.now { it.count(HistoryDbBridge.ALL) }.toInt()
        val shotsRows = tables().count(shots)
        if (indexRows != shotsRows) out += "index holds $indexRows rows, shots.db $shotsRows"
        return out
    }

    /**
     * The boot check (SPEC §3): null when it passes or the index won't open;
     * otherwise a full diff begun, with `pending` raised for it, so a crash
     * mid-diff leaves the next boot a miss. [force] runs one anyway.
     *
     * Then, still under the lock, the index's seqs restart at `shots.db`'s
     * (REVIEW-450 B and C): `source_seq` to its `index_seq` and `seen_seqs`
     * cleared. Every write up to that seq is in the rows the diff reads, and
     * a seq the index saw from an older file or a later copy can't stand in
     * for this file's. Writes during the diff then advance from there, so
     * the diff settles exactly when each of them reached the index.
     */
    fun planDiff(shots: SQLiteDatabase, force: Boolean): Diff? {
        val db = opened(retry = true) ?: return null.also { setFresh(false) }
        return try {
            val reasons = misses(db, shots).let { if (force && it.isEmpty()) listOf("asked for") else it }
            if (reasons.isEmpty()) return null.also { setFresh(true) }
            setFresh(false)
            db.now { it.bumpPending() }
            val shotsSeq = tables().indexSeq(shots)
            db.now { it.resetSeqs(shotsSeq) }
            Diff(db, db.now { it.writeSeq() }, shotsSeq, reasons, System.currentTimeMillis())
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            failure("boot check", e)
            setFresh(false)
            null
        }
    }

    /** One batch of [diff]: true while there may be more. */
    fun diffStep(diff: Diff, shots: SQLiteDatabase): Boolean {
        if (diff.outcome != null) return false
        if (bridge !== diff.db || diff.db.isClosed) {
            diff.outcome = "stopped: the index was closed (erase)"
            return false
        }
        return try {
            val rows = tables().rawPage(shots, diff.after, BATCH)
            if (rows.isEmpty()) return false
            val written = diff.db.now { it.diffBatch(rows.map { HistoryRowIn(it.second) }) }
            diff.rows += rows.size
            diff.upserted += written.change.upserted.size
            send(written.change)
            diff.after = rows.last().first
            crash(IndexFault.CRASH_IN_DIFF)
            rows.size == BATCH
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            diff.outcome = "failed: ${e.message ?: e.javaClass.simpleName}"
            failure("full diff", e)
            false
        }
    }

    /**
     * The diff's last step: the rows `shots.db` doesn't hold leave the index
     * (read under the same lock, so none can arrive meanwhile). It settles
     * `pending` to 0 with this `versionCode` only when the index has every
     * seq from the one [planDiff] reset it to up to `shots.db`'s now, with
     * none past a gap: a write since it began that the index missed, from
     * this store or another (REVIEW-446 note 1), leaves it for the next boot.
     * Null [shots]: the store closed (erase, lost) and the diff stops.
     */
    fun finishDiff(diff: Diff, shots: SQLiteDatabase?): IndexDiffReport {
        var removed = 0
        if (diff.outcome == null && shots == null) diff.outcome = "stopped: the store closed"
        if (diff.outcome == null && (bridge !== diff.db || diff.db.isClosed)) diff.outcome = "stopped: the index was closed (erase)"
        if (diff.outcome == null && shots != null) {
            try {
                val gone = diff.db.now { it.removeAbsent(tables().ids(shots).toList(), diff.since) }
                removed = gone.deleted.size
                send(gone)
                val state = diff.db.now { it.indexState() }
                val now = tables().indexSeq(shots)
                if (state.sourceSeq != now || state.seenSeqs.isNotEmpty()) {
                    diff.outcome = "a write since it began didn't reach the index (it has writes to ${state.sourceSeq} of $now): the next boot runs it again"
                } else {
                    diff.db.now { it.finishAndroidDiff(versionCode) }
                    diff.outcome = IndexDiffReport.OK
                    // Settled: every write up to shots.db's last is in. Readers start over.
                    send(null)
                    setFresh(true)
                }
            } catch (e: SimulatedCrash) {
                throw e
            } catch (e: Throwable) {
                diff.outcome = "failed: ${e.message ?: e.javaClass.simpleName}"
                failure("full diff", e)
            }
        }
        val report = IndexDiffReport(
            atMs = diff.startedAtMs,
            tookMs = System.currentTimeMillis() - diff.startedAtMs,
            reasons = diff.reasons,
            rows = diff.rows,
            upserted = diff.upserted,
            removed = removed,
            outcome = diff.outcome ?: IndexDiffReport.OK,
        )
        lastDiff = report
        persist("full diff (${report.reasons.joinToString("; ")}): ${report.rows} rows, ${report.upserted} written, ${report.removed} removed, ${report.tookMs} ms: ${report.outcome}")
        return report
    }

    // ── Health and comparison (debug screen, tests) ──────────────────────────

    fun health(shots: SQLiteDatabase?): IndexHealth {
        val db = opened()
        val state = db?.let { b -> runCatching { b.now { it.indexState() } }.getOrNull() }
        val indexRows = db?.let { b -> runCatching { b.now { it.count(HistoryDbBridge.ALL) }.toInt() }.getOrNull() }
        val shotsRows = shots?.let { s -> runCatching { tables().count(s) }.getOrNull() }
        val shotsSeq = shots?.let { s -> runCatching { tables().indexSeq(s) }.getOrNull() }
        val misses = if (db != null && shots != null) runCatching { misses(db, shots) }.getOrElse { listOf("check failed: ${it.message}") } else emptyList()
        return IndexHealth(
            open = db != null,
            path = file.path,
            bytes = SUFFIXES.sumOf { File(file.path + it).length() },
            error = lastError,
            pending = state?.pending,
            diffVersionCode = state?.diffVersionCode,
            versionCode = versionCode,
            indexRows = indexRows,
            shotsRows = shotsRows,
            sourceSeq = state?.sourceSeq,
            seenSeqs = state?.seenSeqs.orEmpty(),
            shotsSeq = shotsSeq,
            hookFailures = HOOK_FAILURES.get(),
            misses = misses,
            lastDiff = lastDiff,
        )
    }

    /** Every row of the index against `shots.db`'s, by `row_json` as stored. Null when the index isn't open. */
    fun compare(shots: SQLiteDatabase): IndexComparison? {
        val db = opened() ?: return null
        val mine = HashMap<String, String>()
        var after: String? = null
        while (true) {
            val rows = tables().rawPage(shots, after, BATCH)
            for ((id, json) in rows) mine[id] = json
            if (rows.size < BATCH) break
            after = rows.last().first
        }
        val theirs = HashMap<String, String>()
        var page = db.now { it.page(HistoryDbBridge.ALL, HistoryAnchor.Start, BATCH.toUInt()) }
        while (true) {
            for (r in page.rows) theirs[r.id] = r.rowJson
            if (page.atEnd) break
            val last = page.last ?: break
            page = db.now { it.page(HistoryDbBridge.ALL, HistoryAnchor.After(last), BATCH.toUInt()) }
        }
        return IndexComparison(
            rows = mine.size,
            missing = (mine.keys - theirs.keys).sorted(),
            extra = (theirs.keys - mine.keys).sorted(),
            differ = mine.filter { (id, json) -> theirs[id] != null && theirs[id] != json }.keys.sorted(),
        )
    }

    // ── Failures ─────────────────────────────────────────────────────────────

    /** A hook or diff step failed: counted for the debug screen. The seqs and `pending` carry the miss. */
    private fun failure(what: String, e: Throwable) {
        HOOK_FAILURES.incrementAndGet()
        lastError = "$what: ${e.message ?: e.javaClass.simpleName}"
        if (logged++ < 5) persist("index $what failed: $e")
    }

    private fun crash(f: IndexFault) {
        if (FAULTS.remove(f)) throw SimulatedCrash(f.name)
    }

    private fun fail(f: IndexFault) {
        if (f in FAULTS) throw IllegalStateException("index fault ${f.name} (test)")
    }

    private fun persist(line: String) {
        runCatching { coffee.crema.diag.DiagLog.add("history: $line") }
    }

    companion object {
        const val FILE_NAME = "history-index.db"

        /** Rows per index transaction and per diff batch (SPEC §11 stage 1 step 3). */
        const val BATCH = 500

        private val SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        /** Every [HistoryIndex]'s open file in this process, by path (S4). */
        private val HELD = HashMap<String, HistoryDbBridge>()

        /** Erases running in this process: while one runs, nothing opens or makes the index (REVIEW-446 note 4). */
        private val ERASING = AtomicInteger()

        /** Index calls that failed, this process: the debug screen's count. */
        private val HOOK_FAILURES = AtomicLong()

        /**
         * Test seam: where the hook stops or fails. A crash fault fires once
         * (it's removed as it fires); a failure fault holds until removed.
         */
        @androidx.annotation.VisibleForTesting
        internal val FAULTS: MutableSet<IndexFault> = Collections.synchronizedSet(mutableSetOf())

        /** Open errors that make the derived index worth rebuilding rather than leaving. Never `WrongRole`: an authority file is touched by nobody here (§11 stage 1 step 1). */
        private fun disposable(e: HistoryDbException): Boolean =
            e is HistoryDbException.Corrupt || e is HistoryDbException.Foreign ||
                e is HistoryDbException.TooNew || e is HistoryDbException.ReadOnlyTooNew
    }
}

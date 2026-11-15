package coffee.crema.history

import android.database.sqlite.SQLiteDatabase
import coffee.crema.BuildConfig
import coffee.crema.core.HistoryAnchor
import coffee.crema.core.HistoryDbException
import coffee.crema.core.HistoryIndexWrite
import coffee.crema.core.HistoryRole
import coffee.crema.core.HistoryRowIn
import java.io.File
import java.util.Collections
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
 *   above 0, or, when even raising it failed, the [STALE_NAME] mark.
 * - **Never in the way.** No index failure reaches the P3a write: the
 *   `shots.db` transaction commits whatever happens here, and the next boot's
 *   full diff repairs the index.
 * - **The boot check** passes only when `pending` is 0, no stale mark is
 *   there, the last full diff ran on this `versionCode`, and the counts
 *   match. Otherwise the **full diff** runs: every row in batches of
 *   [BATCH], each batch under the lock, then the ids the index holds and
 *   `shots.db` doesn't are removed (the one delete by absence, allowed
 *   because the index is derived).
 * - **Erase** closes the file under the core's lock first ([closeAll], S11).
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
    val staleMarked: Boolean,
    val hookFailures: Long,
    /** Why the boot check would miss now: empty when it passes. */
    val misses: List<String>,
    val lastDiff: IndexDiffReport?,
)

/** The index against `shots.db`, row by row (`row_json` as stored). */
data class IndexComparison(val rows: Int, val missing: List<String>, val extra: List<String>, val differ: List<String>) {
    val exact: Boolean get() = missing.isEmpty() && extra.isEmpty() && differ.isEmpty()

    override fun toString(): String =
        if (exact) "equal: $rows rows" else "${missing.size} missing, ${extra.size} extra, ${differ.size} differ (of $rows)"
}

internal class HistoryIndex(
    private val dir: File,
    private val tables: () -> HistoryTables,
    val versionCode: Long = BuildConfig.VERSION_CODE.toLong(),
) : ShotsHook {
    val file: File get() = File(dir, FILE_NAME)
    private val staleMark: File get() = File(dir, STALE_NAME)

    /** The open index, or null. Closed under us (another store in this process took it over) stays closed. */
    @Volatile private var bridge: HistoryDbBridge? = null

    /** An open was tried this session: a failed one is tried again only at [planDiff] (boot, retry). */
    private var openTried = false

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastDiff: IndexDiffReport? = null
        private set

    private var logged = 0

    /** What a write's [begin] raised `pending` on. */
    private class Ticket(val db: HistoryDbBridge, val raised: Boolean)

    // ── Open and close ───────────────────────────────────────────────────────

    /** The open index, opening it the first time (or again when [retry]). */
    private fun opened(retry: Boolean = false): HistoryDbBridge? {
        bridge?.let { return if (it.isClosed) null else it }
        if (openTried && !retry) return null
        openTried = true
        return try {
            openFresh().also { bridge = it; lastError = null }
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            failure("open", e, count = false)
            null
        }
    }

    private fun openFresh(): HistoryDbBridge {
        dir.mkdirs()
        val key = file.absolutePath
        synchronized(HELD) {
            // One process, one connection per file (S4): an earlier store here (a recreated view
            // model) gives it up. Its next write fails, which marks the index stale.
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

    /** Erase (S11): close the file under the core's lock before anything removes it. The next write opens a new one. */
    fun closeAll() {
        val mine = bridge
        bridge = null
        openTried = false
        val held = synchronized(HELD) { HELD.remove(file.absolutePath) }
        for (b in listOfNotNull(mine, held).distinct()) runCatching { b.now { it.closeAll() } }
    }

    // ── The hook (M4) ────────────────────────────────────────────────────────

    override fun begin(): Any? {
        val db = opened()
        if (db == null) {
            markStale()
            return null
        }
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
        val t = ticket as? Ticket ?: return
        crash(IndexFault.CRASH_AFTER_SHOTS)
        try {
            fail(IndexFault.FAIL_WRITE)
            if (write.cleared) replaceAll(t.db, db) else mirror(t.db, db, write.touched, lower = t.raised)
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            failure("index write", e)
            return
        }
        crash(IndexFault.CRASH_AFTER_INDEX)
    }

    override fun failed(ticket: Any?, error: Throwable) {
        // The `shots.db` transaction threw: `pending` stays up, and the next boot's diff settles it.
        if (ticket != null) HOOK_FAILURES.incrementAndGet()
    }

    override fun replaced() {
        // `shots.db` went aside or was deleted: whatever the index holds may be wrong now.
        val db = opened()
        val raised = db != null && runCatching { db.now { it.bumpPending() } }.isSuccess
        if (!raised) markStale()
    }

    /** [ids]' rows as `shots.db` committed them into the index; an id it lacks leaves the index too. */
    private fun mirror(db: HistoryDbBridge, shots: SQLiteDatabase, ids: Set<String>, lower: Boolean) {
        val list = ids.toList()
        val rows = tables().rawRows(shots, list)
        val batches = list.chunked(BATCH).ifEmpty { listOf(emptyList()) }
        batches.forEachIndexed { i, batch ->
            if (i > 0) crash(IndexFault.CRASH_BETWEEN_BATCHES)
            val write = HistoryIndexWrite(
                upsert = batch.mapNotNull { id -> rows[id]?.let { HistoryRowIn(it) } },
                delete = batch.filter { it !in rows },
                seenSeqs = emptyList(),
                lowerPending = lower && i == batches.lastIndex,
            )
            db.now { it.indexWrite(write) }
        }
    }

    /**
     * A write that cleared `shots` (the migration, a lost store rebuilt): the
     * whole table, under the lock, so this is a full diff with nothing
     * writing beside it, and it settles `pending` as one.
     */
    private fun replaceAll(db: HistoryDbBridge, shots: SQLiteDatabase) {
        val since = db.now { it.writeSeq() }
        var after: String? = null
        var first = true
        while (true) {
            val rows = tables().rawPage(shots, after, BATCH)
            if (rows.isEmpty()) break
            if (!first) crash(IndexFault.CRASH_BETWEEN_BATCHES)
            first = false
            db.now { it.diffBatch(rows.map { HistoryRowIn(it.second) }) }
            after = rows.last().first
            if (rows.size < BATCH) break
        }
        db.now { it.removeAbsent(tables().ids(shots).toList(), since) }
        db.now { it.finishAndroidDiff(versionCode) }
        staleMark.delete()
    }

    // ── The boot check and the full diff ─────────────────────────────────────

    /** A full diff under way: each step runs under the lock. */
    internal class Diff(
        val db: HistoryDbBridge,
        val since: ULong,
        val failuresAtStart: Long,
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
        if (state.diffVersionCode != versionCode) out += "last full diff on versionCode ${state.diffVersionCode ?: "none"}, this is $versionCode"
        if (staleMark.exists()) out += "a write didn't reach the index"
        val indexRows = db.now { it.count(HistoryDbBridge.ALL) }.toInt()
        val shotsRows = tables().count(shots)
        if (indexRows != shotsRows) out += "index holds $indexRows rows, shots.db $shotsRows"
        return out
    }

    /**
     * The boot check (SPEC §3): null when it passes or the index won't open;
     * otherwise a full diff begun, with `pending` raised for it, so a crash
     * mid-diff leaves the next boot a miss. [force] runs one anyway.
     */
    fun planDiff(shots: SQLiteDatabase, force: Boolean): Diff? {
        val db = opened(retry = true) ?: return null
        return try {
            val reasons = misses(db, shots).let { if (force && it.isEmpty()) listOf("asked for") else it }
            if (reasons.isEmpty()) return null
            val failures = HOOK_FAILURES.get()
            db.now { it.bumpPending() }
            Diff(db, db.now { it.writeSeq() }, failures, reasons, System.currentTimeMillis())
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            failure("boot check", e, count = false)
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
            diff.after = rows.last().first
            crash(IndexFault.CRASH_IN_DIFF)
            rows.size == BATCH
        } catch (e: SimulatedCrash) {
            throw e
        } catch (e: Throwable) {
            diff.outcome = "failed: ${e.message ?: e.javaClass.simpleName}"
            failure("full diff", e, count = false)
            false
        }
    }

    /**
     * The diff's last step: the rows `shots.db` doesn't hold leave the index
     * (read under the same lock, so none can arrive meanwhile), then `pending`
     * goes to 0 with this `versionCode`, unless a write failed to mirror since
     * the diff began, which leaves it for the next boot. Null [shots]: the
     * store closed (erase, lost) and the diff stops.
     */
    fun finishDiff(diff: Diff, shots: SQLiteDatabase?): IndexDiffReport {
        var removed = 0
        if (diff.outcome == null && shots == null) diff.outcome = "stopped: the store closed"
        if (diff.outcome == null && (bridge !== diff.db || diff.db.isClosed)) diff.outcome = "stopped: the index was closed (erase)"
        if (diff.outcome == null && shots != null) {
            try {
                removed = diff.db.now { it.removeAbsent(tables().ids(shots).toList(), diff.since) }.deleted.size
                if (HOOK_FAILURES.get() != diff.failuresAtStart) {
                    diff.outcome = "a write didn't reach the index while it ran: the next boot runs it again"
                } else {
                    diff.db.now { it.finishAndroidDiff(versionCode) }
                    staleMark.delete()
                    diff.outcome = IndexDiffReport.OK
                }
            } catch (e: SimulatedCrash) {
                throw e
            } catch (e: Throwable) {
                diff.outcome = "failed: ${e.message ?: e.javaClass.simpleName}"
                failure("full diff", e, count = false)
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
            staleMarked = staleMark.exists(),
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

    /** A hook or diff step failed: counted, and the index marked stale for the next boot. */
    private fun failure(what: String, e: Throwable, count: Boolean = true) {
        if (count) HOOK_FAILURES.incrementAndGet()
        lastError = "$what: ${e.message ?: e.javaClass.simpleName}"
        if (count) markStale()
        if (logged++ < 5) persist("index $what failed: $e")
    }

    /** The durable miss for a write the index couldn't follow, even with `pending` out of reach. */
    private fun markStale() {
        runCatching {
            dir.mkdirs()
            staleMark.createNewFile()
        }
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

        /** The mark a write the index couldn't follow leaves, when `pending` couldn't be raised. */
        const val STALE_NAME = "history-index.stale"

        /** Rows per index transaction and per diff batch (SPEC §11 stage 1 step 3). */
        const val BATCH = 500

        private val SUFFIXES = listOf("", "-wal", "-shm", "-journal")

        /** Every [HistoryIndex]'s open file in this process, by path (S4). */
        private val HELD = HashMap<String, HistoryDbBridge>()

        /** Hook writes that didn't reach the index, this process: a diff that saw one doesn't settle `pending`. */
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

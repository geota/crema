package coffee.crema.history

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import coffee.crema.core.HistoryDbException
import coffee.crema.core.HistoryFlipInit
import coffee.crema.core.HistoryFtsCheck
import coffee.crema.core.HistoryFtsStatus
import coffee.crema.core.HistoryHeavyRow
import coffee.crema.core.HistoryJournalMode
import coffee.crema.core.HistoryLateImport
import coffee.crema.core.HistoryMaintained
import coffee.crema.core.HistoryRole
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/*
 * #310 A5: the flip on Android (SQLite SPEC §11 stage 2, M2, M4, M6, M8, M9,
 * M10, S9, S12, S13). It runs once, at app start, inside the store's open
 * and before its rows reach anyone. `shots.db` stays the truth until the
 * marker says `done`; the flip only reads it, and takes P3a's
 * `HistoryStore.lock` per batch, so a shot captured meanwhile still lands in
 * `shots.db` and is imported by step 8 in the same boot (M9).
 *
 * Steps, as SPEC §11 numbers them (1 and 2 are the store's, before this):
 * 3. Free space: the index twice over, plus `shots.db` for the heavy parts.
 * 4. A `history.db` with no marker and no done-record was never the truth.
 *    One that opens and isn't `done` is overwritten in place; one that
 *    doesn't open is left as it is and the flip goes to the next generation.
 * 5. The index's full diff, then, under one hold of the lock, `shots.db`'s
 *    `index_seq` (the hook's count) as `source_seq`, every id it holds, and
 *    the copy: `role = 'authority'`, the generation, `migration = copying`,
 *    `migration_source` and the late-row baseline in one transaction (the
 *    core's `flip_copy_to`). Then the copy's own search index is rebuilt.
 * 6. `profile_json` and `brew_series_json` from `shots.db`, opened
 *    `OPEN_READONLY` (S12), into `shot_heavy`, a batch at a time.
 * 7. Verify, against `shots.db` row by row: the ids both ways and against
 *    `migration_source`; every `row_json` and both heavy parts byte for
 *    byte; the derived columns; `integrity_check`; the search index's own
 *    check, where `NotReady` is a fail, never a pass (REVIEW-448 note 5);
 *    and nothing but new captures wrote `shots.db` since `source_seq`. Pass:
 *    `done` in the file, the done-record, the marker, and the store moves to
 *    `history.db`, all under one hold of the lock, so no write can land in
 *    `shots.db` after the check. Fail: `history.db` stays `copying`, which
 *    nothing reads, and the index is cleared for a rebuild (M4).
 *
 * Nothing here deletes or writes `shots.db`, the index's role, or any file
 * but the copy, the done-record and the marker.
 */

/** A file's size and modification time, the step 8 prefilter's input (N9). */
@Serializable
internal data class FileStamp(val size: Long, val mtimeMs: Long) {
    companion object {
        fun of(f: File): FileStamp? = if (f.exists()) FileStamp(f.length(), f.lastModified()) else null
    }
}

/**
 * Android's late-row baseline (`meta.late_baseline`, SPEC §11 step 8, N9,
 * S13): what `shots.db`, its `-wal`, P3a's journal and `shots.json` looked
 * like when the ids were last read. One key holds both the P3a baseline and
 * P3a's own pre-P3 one (REVIEW-442 note 9: the two writers share it).
 */
@Serializable
internal data class LateBaseline(
    val shotsDb: FileStamp? = null,
    val wal: FileStamp? = null,
    val p3Journal: FileStamp? = null,
    val shotsJson: FileStamp? = null,
    /** `shots.json`'s SHA-256 when its rows were last read: a stamp that moved with the same bytes reads nothing. */
    val shotsJsonHash: String? = null,
) {
    /** Whether any P3 file moved since: then step 8 reads the ids. */
    fun movedFrom(now: LateBaseline): Boolean = shotsDb != now.shotsDb || wal != now.wal || p3Journal != now.p3Journal || shotsJson != now.shotsJson

    companion object {
        fun now(filesDir: File, shotsDb: File, shotsJsonHash: String?): LateBaseline = LateBaseline(
            shotsDb = FileStamp.of(shotsDb),
            wal = FileStamp.of(File(shotsDb.path + "-wal")),
            p3Journal = FileStamp.of(File(filesDir, HistoryStore.PENDING_NAME)),
            shotsJson = FileStamp.of(File(filesDir, HistoryStore.FILE_NAME)),
            shotsJsonHash = shotsJsonHash,
        )

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/**
 * `shots.db` opened read-only, never through P3a's helper (S12), which may
 * open it read-write, run `onCreate` or `onUpgrade`, or rename it aside.
 * The error handler does nothing, so a damaged file stays exactly as it is.
 * Null when there's no file. An open that fails throws: the caller reads it
 * as "can't tell now", never as "no rows".
 */
internal class ReadOnlyShots private constructor(private val db: SQLiteDatabase) : Closeable {
    companion object {
        fun open(file: File): ReadOnlyShots? {
            if (!file.exists()) return null
            val handler = DatabaseErrorHandler { }
            return ReadOnlyShots(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY, handler))
        }
    }

    fun ids(): Set<String> = db.rawQuery("SELECT id FROM shots", null).use { c ->
        val out = HashSet<String>(c.count)
        while (c.moveToNext()) out += c.getString(0)
        out
    }

    /** Up to [limit] rows' heavy columns as stored, by id, after [afterId]. */
    fun heavyPage(afterId: String?, limit: Int): List<HistoryHeavyRow> {
        val c = if (afterId == null) {
            db.rawQuery("SELECT id, profile_json, brew_series_json FROM shots ORDER BY id LIMIT $limit", null)
        } else {
            db.rawQuery("SELECT id, profile_json, brew_series_json FROM shots WHERE id > ? ORDER BY id LIMIT $limit", arrayOf(afterId))
        }
        return c.use {
            val out = ArrayList<HistoryHeavyRow>(it.count)
            while (it.moveToNext()) out += HistoryHeavyRow(it.getString(0), it.str(1), it.str(2))
            out
        }
    }

    /** [ids]' `row_json` and heavy columns as stored. An id the table lacks is absent. */
    fun rows(ids: Collection<String>): Map<String, Pair<String, HistoryHeavyRow>> {
        val out = HashMap<String, Pair<String, HistoryHeavyRow>>()
        for (chunk in ids.chunked(500)) {
            val marks = chunk.joinToString(",") { "?" }
            db.rawQuery("SELECT id, row_json, profile_json, brew_series_json FROM shots WHERE id IN ($marks)", chunk.toTypedArray()).use { c ->
                while (c.moveToNext()) out[c.getString(0)] = c.getString(1) to HistoryHeavyRow(c.getString(0), c.str(2), c.str(3))
            }
        }
        return out
    }

    /** A `meta` value, or null (no table, no key). */
    fun meta(key: String): String? = runCatching {
        db.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun android.database.Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)

    override fun close() = db.close()
}

/** Where a test stops the flip, as a kill would, or makes one of its checks fail (each verify check has one). */
@androidx.annotation.VisibleForTesting
enum class FlipFault {
    // Kills: the process dies here.
    AFTER_DIFF, AFTER_COPY, AFTER_LATE_IDS, AFTER_FTS, IN_HEAVY, AFTER_HEAVY, IN_VERIFY, AFTER_DONE, AFTER_DONE_RECORD, AFTER_MARKER,

    // Verify mutations: each must fail its own check.
    DROP_ROW, EXTRA_ROW, ALTER_ROW, ALTER_HEAVY, DROP_SOURCE_ID, DERIVED, FTS_NOT_READY, FOREIGN_WRITE,

    // A full disk while the copy's heavy parts are written.
    DISK_FULL,
}

/** What the flip did. */
internal sealed interface FlipOutcome {
    /** Passed: [db] is the truth, open, generation [generation]. [captured]: ids written to `shots.db` meanwhile, for step 8. */
    class Done(val generation: Int, val db: HistoryDbBridge, val captured: Set<String>) : FlipOutcome

    /** Didn't pass: stage 1 goes on, `shots.db` the truth. */
    data class Failed(val why: String) : FlipOutcome
}

/** What the flip needs from the store it runs in. */
internal interface FlipHost {
    /** [block] on `shots.db` under `HistoryStore.lock`; null when the rows aren't in `shots.db` now. */
    suspend fun <T> locked(block: (SQLiteDatabase) -> T): T?

    /** The index's full diff, a batch per hold of the lock ([HistoryStore.syncIndex]). */
    suspend fun fullDiff(): IndexDiffReport?

    /** Called under the lock with the passed copy, after the marker: the store moves to it. */
    fun switchTo(outcome: FlipOutcome.Done)

    fun freeSpace(): Long
}

internal class HistoryFlip(
    private val host: FlipHost,
    private val files: Stage2Files,
    private val tables: HistoryTables,
    private val index: HistoryIndex,
    private val filesDir: File,
    private val shotsDb: File,
    private val json: Json,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** The copy's target: [generation], in place over a leftover or new. */
    private class Target(val generation: Int, val file: File)

    /**
     * The generation whose file says `migration = done`, once it does: from
     * then on it is the truth, whatever fails after (the done-record, the
     * marker, the switch), and `shots.db` takes no more writes.
     */
    @Volatile var doneGeneration: Int? = null
        private set

    /** Diagnostics: an unreadable leftover the flip went around (M8 step 4). */
    var leftover: String? = null
        private set

    suspend fun run(): FlipOutcome = try {
        runOrFail()
    } catch (e: SimulatedCrash) {
        throw e
    } catch (e: Failure) {
        FlipOutcome.Failed(e.message ?: "the flip failed")
    } catch (e: Throwable) {
        FlipOutcome.Failed("${e.javaClass.simpleName}: ${e.message}")
    } finally {
        tables.observer = null
    }

    private class Failure(why: String) : Exception(why)

    private fun fail(why: String): Nothing = throw Failure(why)

    private suspend fun runOrFail(): FlipOutcome {
        // Step 3: the copy takes about the index's size, the heavy parts about shots.db's.
        val need = index.file.length() * 2 + shotsDb.length() + File(shotsDb.path + "-wal").length()
        if (host.freeSpace() < need) fail("Not enough free space to move the shot history into SQLite ($need bytes needed)")
        val target = target()
        // Every write to shots.db from here, in order: only new captures may land before the marker (M2, M9).
        val writes = Collections.synchronizedList(ArrayList<ShotsWrite>())
        tables.observer = { writes += it }

        // Step 5: the index up to date, by a full diff, never the boot check (M4).
        val report = host.fullDiff() ?: fail("the index didn't open")
        if (!report.finished) fail("the index's full diff didn't settle: ${report.outcome}")
        crash(FlipFault.AFTER_DIFF)
        val source = host.locked { shots ->
            val seq = tables.indexSeq(shots)
            val state = index.settled() ?: fail("the index isn't open")
            if (state.pending != 0uL || state.sourceSeq != seq || state.seenSeqs.isNotEmpty()) {
                fail("the index isn't up to date with shots.db (pending ${state.pending}, at ${state.sourceSeq} of $seq)")
            }
            val ids = tables.ids(shots)
            val recordText = tables.meta(shots, HistoryTables.META_MIGRATION)
            val record = recordText?.let { runCatching { json.decodeFromString(MigrationRecord.serializer(), it) }.getOrNull() }
            val hash = runCatching { File(filesDir, HistoryStore.FILE_NAME).takeIf { it.exists() }?.readBytes()?.let(LateBaseline::sha256) }.getOrNull()
            // P3a's own late-row baseline for shots.json (S13): its source ids join migration_source,
            // and its hash is the pre-P3 step's, so the one key serves both (REVIEW-442 note 9).
            val baseline = LateBaseline.now(filesDir, shotsDb, record?.sourceHash?.ifEmpty { null } ?: hash)
            val migrationSource = ids + record?.sourceIds.orEmpty()
            writes.clear()
            index.flipCopy(
                target.file,
                HistoryFlipInit(
                    generation = target.generation.toUInt(),
                    sourceSeq = seq,
                    sourceCount = ids.size.toULong(),
                    migrationSource = migrationSource.toList(),
                    lateBaseline = json.encodeToString(LateBaseline.serializer(), baseline),
                    p3Migration = recordText,
                ),
            )
            Source(seq, ids, migrationSource, record)
        } ?: fail("the shot history isn't in shots.db")
        crash(FlipFault.AFTER_COPY)

        // A role change is read at open: the copy opens as the truth file it now is (REVIEW-442 note 8).
        val db = try {
            HistoryDbBridge.openNow(target.file, HistoryDbBridge.options(HistoryRole.AUTHORITY))
        } catch (e: HistoryDbException) {
            fail("the copy didn't open: ${e.javaClass.simpleName}: ${e.message}")
        }
        var passed = false
        try {
            // P3a's rows still waiting for their series (S13).
            source.record?.lateIds?.takeIf { it.isNotEmpty() }?.let { late ->
                db.now { it.importLate(HistoryLateImport(rows = emptyList(), sourceIds = emptyList(), lateIds = late, lateBaseline = null)) }
            }
            crash(FlipFault.AFTER_LATE_IDS)
            rebuildSearch(db)
            crash(FlipFault.AFTER_FTS)
            copyHeavy(db, source)
            crash(FlipFault.AFTER_HEAVY)
            verify(db, source)?.let { why ->
                // A wrong copy came from a wrong index: rebuilt before the next try (M4).
                host.locked { index.discardForRebuild() }
                fail("Verify failed: $why")
            }
            val done = host.locked { shots ->
                val captured = lastWrites(shots, source, writes)
                db.now { it.setMigrationDone(now()) }
                doneGeneration = target.generation
                crash(FlipFault.AFTER_DONE)
                files.writeDone(target.generation, now())
                crash(FlipFault.AFTER_DONE_RECORD)
                files.writeMarker(Stage2Marker(STAGE2_DONE, generation = target.generation, atMs = now()))
                crash(FlipFault.AFTER_MARKER)
                FlipOutcome.Done(target.generation, db, captured).also { host.switchTo(it) }
            } ?: fail("the shot history left shots.db during the flip")
            passed = true
            return done
        } finally {
            if (!passed) runCatching { db.now { it.closeAll() } }
        }
    }

    private class Source(val seq: ULong, val ids: Set<String>, val migrationSource: Set<String>, val record: MigrationRecord?)

    /** Step 4 (M8): where the copy goes. */
    private fun target(): Target {
        val first = files.db(0)
        return when (val p = files.probe(first)) {
            FileProbe.Absent, FileProbe.Leftover -> Target(0, first)
            // The store checked the done-record and every finished generation before calling this.
            is FileProbe.Finished -> fail("${Stage2Files.DB_NAME} is a finished copy the marker doesn't name")
            // A newer build's truth: never a leftover to overwrite or go around.
            is FileProbe.TooNew -> fail("${Stage2Files.DB_NAME} was written by a newer version of Crema: update the app")
            is FileProbe.Unreadable -> {
                leftover = "${Stage2Files.DB_NAME} doesn't open (${p.why}); left as it is"
                persist("flip: ${leftover!!}")
                // The next generation no file names, or a leftover of an earlier try at it.
                var g = files.nextGeneration()
                while (true) {
                    when (files.probe(files.db(g))) {
                        FileProbe.Absent, FileProbe.Leftover -> break
                        is FileProbe.TooNew, is FileProbe.Finished -> fail("history-$g.db is in the way and isn't a leftover")
                        is FileProbe.Unreadable -> g++
                    }
                }
                Target(g, files.db(g))
            }
        }
    }

    /** The copy's own search index, built in batches, so verify checks the copy's, not the index's (M6). */
    private fun rebuildSearch(db: HistoryDbBridge) {
        db.now { it.startFtsRebuild() }
        var steps = 0
        while (steps++ < MAX_MAINTAIN_STEPS) {
            when (db.now { it.maintain() }) {
                HistoryMaintained.Idle, HistoryMaintained.FtsSwapped -> break
                HistoryMaintained.Busy -> fail("a shot is running: the search index wasn't rebuilt")
                else -> Unit
            }
        }
        if (db.now { it.health() }.fts !is HistoryFtsStatus.Ready) fail("the copy's search index didn't finish building")
    }

    /** Step 6: the heavy parts, read from `shots.db` opened read-only (S12), a batch per hold of the lock (M9). */
    private suspend fun copyHeavy(db: HistoryDbBridge, source: Source) {
        val ro = ReadOnlyShots.open(shotsDb) ?: fail("shots.db is gone")
        ro.use {
            var after: String? = null
            var batches = 0
            while (true) {
                val page = host.locked { _ -> ro.heavyPage(after, BATCH) } ?: fail("the shot history left shots.db during the flip")
                if (page.isEmpty()) break
                if (batches++ > 0) {
                    crash(FlipFault.IN_HEAVY)
                    betweenBatchesForTest?.invoke()
                }
                if (FlipFault.DISK_FULL in FAULTS) throw IllegalStateException("database or disk is full (test)")
                // Rows a capture made after the copy aren't in it: step 8 brings them, heavy parts and all.
                val mine = page.filter { it.id in source.migrationSource }
                val missing = db.now { it.putHeavy(mine) }
                if (missing.isNotEmpty()) fail("heavy parts for ${missing.size} rows the copy lacks")
                after = page.last().id
                if (page.size < BATCH) break
            }
        }
    }

    /**
     * Step 7 against `shots.db`, a batch per hold of the lock. Null when it
     * passes, else which check failed. The writes check is [lastWrites],
     * under the same hold as the marker.
     */
    private suspend fun verify(db: HistoryDbBridge, source: Source): String? {
        // The copy's ids, by rid, and migration_source's.
        val copyIds = HashSet<String>()
        var afterRid = Long.MIN_VALUE
        while (true) {
            val page = db.now { it.idsCreatedBefore(ULong.MAX_VALUE, afterRid, BATCH.toUInt()) }
            if (page.isEmpty()) break
            page.mapTo(copyIds) { it.id }
            afterRid = page.last().rid
            if (page.size < BATCH) break
        }
        if (FlipFault.DROP_ROW in FAULTS) copyIds.remove(copyIds.first())
        if (FlipFault.EXTRA_ROW in FAULTS) copyIds += "extra-row"
        val sourceIds = db.now { it.migrationSourceIds() }.toHashSet()
        if (FlipFault.DROP_SOURCE_ID in FAULTS) sourceIds.remove(sourceIds.first())
        if (copyIds != source.ids) {
            return "ids: ${(source.ids - copyIds).size} missing from ${Stage2Files.DB_NAME}, ${(copyIds - source.ids).size} not in shots.db"
        }
        if (sourceIds != source.migrationSource) {
            return "migration_source: ${(source.migrationSource - sourceIds).size} missing, ${(sourceIds - source.migrationSource).size} extra"
        }
        // Every row and both heavy parts, byte for byte, against shots.db as it is now.
        val ro = ReadOnlyShots.open(shotsDb) ?: return "shots.db is gone"
        ro.use {
            var n = 0
            for (chunk in source.ids.sorted().chunked(BATCH)) {
                if (n++ > 0) crash(FlipFault.IN_VERIFY)
                val theirs = host.locked { _ -> ro.rows(chunk) } ?: return "the shot history left shots.db during the flip"
                val mine = db.now { it.get(chunk) }.associate { it.id to it.rowJson }
                val heavy = db.now { it.heavy(chunk) }.associateBy { it.id }
                for (id in chunk) {
                    val (rowJson, h) = theirs[id] ?: return "row $id left shots.db during the flip"
                    var copy = mine[id]
                    if (FlipFault.ALTER_ROW in FAULTS && id == chunk.first()) copy += " "
                    if (copy != rowJson) return "row $id differs"
                    var got = heavy[id] ?: HistoryHeavyRow(id, null, null)
                    if (FlipFault.ALTER_HEAVY in FAULTS && id == chunk.first()) got = got.copy(profileJson = "{}")
                    if (got.profileJson != h.profileJson || got.brewSeriesJson != h.brewSeriesJson) return "heavy parts of $id differ"
                }
            }
        }
        val stale = db.now { it.derivedMismatches() }.let { if (FlipFault.DERIVED in FAULTS) it + "derived" else it }
        if (stale.isNotEmpty()) return "derived columns of ${stale.size} rows differ from their row_json"
        val problems = db.now { it.integrityCheck() }
        if (problems.isNotEmpty()) return "integrity_check: ${problems.take(3).joinToString("; ")}"
        val fts = if (FlipFault.FTS_NOT_READY in FAULTS) HistoryFtsCheck.NOT_READY else db.now { it.ftsIntegrityCheck() }
        // Not checked is not passed (REVIEW-448 note 5).
        if (fts != HistoryFtsCheck.OK) return "the search index's check: $fts"
        return null
    }

    /**
     * Nothing but new captures wrote `shots.db` since the copy (M2, M9), read
     * under the hold that writes the marker: `index_seq` moved by exactly
     * the writes the flip saw, and each of them put rows `migration_source`
     * doesn't hold and `shots.db` still does. Returns their ids, for step 8.
     */
    private fun lastWrites(shots: SQLiteDatabase, source: Source, writes: List<ShotsWrite>): Set<String> {
        val seen = synchronized(writes) { writes.toList() }
        val seq = tables.indexSeq(shots) + if (FlipFault.FOREIGN_WRITE in FAULTS) 1uL else 0uL
        if (seq != source.seq + seen.size.toULong()) {
            fail("Verify failed: shots.db was written outside this flip (at $seq, expected ${source.seq + seen.size.toULong()})")
        }
        val captured = LinkedHashSet<String>()
        for (w in seen) {
            if (w.cleared) fail("Verify failed: shots.db was cleared during the flip")
            for (id in w.touched) {
                if (id in source.migrationSource) fail("Verify failed: row $id changed during the flip")
                captured += id
            }
        }
        if (captured.isNotEmpty()) {
            val still = tables.ids(shots)
            captured.firstOrNull { it !in still }?.let { fail("Verify failed: row $it was deleted during the flip") }
        }
        return captured
    }

    private fun crash(f: FlipFault) {
        if (FAULTS.remove(f)) throw SimulatedCrash("flip ${f.name}")
    }

    private fun persist(line: String) {
        runCatching { coffee.crema.diag.DiagLog.add("history: $line") }
    }

    companion object {
        /** Rows per batch of the heavy copy and of verify (SPEC §11 stage 1 step 3's size). */
        const val BATCH = 500

        /** Search index rebuild steps before the flip gives up for this boot. */
        private const val MAX_MAINTAIN_STEPS = 100_000

        /**
         * Test seam: where the flip stops or fails. A kill fault fires once
         * (removed as it fires); a verify mutation or a full disk holds until removed.
         */
        @androidx.annotation.VisibleForTesting
        internal val FAULTS: MutableSet<FlipFault> = Collections.synchronizedSet(mutableSetOf())

        /** Test seam: runs between two batches of the heavy copy, outside the lock, where a capture can land (M9). */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var betweenBatchesForTest: (suspend () -> Unit)? = null
    }
}

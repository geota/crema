package coffee.crema.history

import coffee.crema.core.HistoryDbException
import coffee.crema.core.HistoryRole
import coffee.crema.persist.SafeFile
import coffee.crema.persist.SafeLoad
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/*
 * #310 A5: stage 2's files on Android (SQLite SPEC §11, M8, S9, N3).
 *
 * - `filesDir/history-sqlite/history.db` is the truth after the flip, and
 *   `history-<n>.db` after a recovery or a flip that found a leftover it
 *   couldn't open. Never under `databases/`, and only Rust opens them.
 * - The **marker**, `filesDir/history-sqlite.json`, and the **done-record**,
 *   `history-sqlite/done.json`, both through [SafeFile]. The done-record is
 *   written first, the marker after, both only once the file says
 *   `migration = done`. So losing the marker alone can't make a truth file
 *   read as a failed copy: the done-record, or failing that the file itself,
 *   rebuilds it (S9).
 * - `history-sqlite/pending.jsonl` is stage 2's journal, in P3a's format.
 *   P3a's `history-pending.jsonl` is never written after the flip; step 8
 *   reads it as late rows.
 * - `history-sqlite/history-snapshot.db`, with one previous copy, is the
 *   rows snapshot: the online backup API, once a day.
 *
 * Nothing here deletes a file. A file that won't open after the flip is
 * renamed aside ([quarantine]), never removed.
 */

/** `history-sqlite.json`: stage 2's state, kept outside the database. */
@Serializable
internal data class Stage2Marker(
    /** `done`, or `failed` (the flip didn't pass; stage 1 goes on). */
    val state: String,
    /** The truth file's generation: 0 is `history.db`, n is `history-<n>.db`. */
    val generation: Int = 0,
    val failures: Int = 0,
    val nextTryAtMs: Long = 0,
    val lastError: String? = null,
    val atMs: Long = 0,
)

/** `history-sqlite/done.json`: a second record of `done` (M8). */
@Serializable
internal data class DoneRecord(val state: String, val generation: Int, val atMs: Long = 0)

internal const val STAGE2_DONE = "done"
internal const val STAGE2_FAILED = "failed"

/** Written before the flip copies anything: a boot that finds it knows a flip ran and stopped part-way (N3, M8). */
internal const val STAGE2_FLIPPING = "flipping"

/** What the files say at boot (SPEC §11 step 1). */
internal sealed interface Stage2State {
    /** The truth is generation [generation]. [rebuilt]: the marker was missing or damaged, and is written again. */
    data class Done(val generation: Int, val rebuilt: Boolean = false) : Stage2State

    /** Stage 1: no flip has passed. [marker] holds a failed one's backoff, if any. */
    data class NotDone(val marker: Stage2Marker?) : Stage2State

    /**
     * Which file is the truth can't be told now (an I/O error, a done-record
     * that doesn't decode, a file the scan couldn't open): nothing flips,
     * opens or is written. [tooNew]: the file it couldn't open is a newer build's.
     */
    data class Unreadable(val error: String, val tooNew: Boolean = false) : Stage2State
}

/** What a `history*.db` turned out to be, opened by the core and closed again. */
internal sealed interface FileProbe {
    data object Absent : FileProbe

    /** `authority` with `migration = done`: a truth file. */
    data class Finished(val generation: Int) : FileProbe

    /** Ours and not finished: an earlier try's copy, whatever its role. Never the truth; overwritten in place (M8). */
    data object Leftover : FileProbe

    /** It doesn't open (damaged, foreign, locked): left exactly as it is. */
    data class Unreadable(val why: String) : FileProbe

    /** A newer build's file (`min_reader_v` above ours): never a leftover, never flipped over. */
    data class TooNew(val why: String) : FileProbe
}

internal class Stage2Files(filesDir: File, private val json: Json) {
    val dir = File(filesDir, HistoryStore.SQLITE_DIR)
    val marker = File(filesDir, HistoryStore.SQLITE_MARKER_NAME)
    val done = File(dir, HistoryStore.SQLITE_DONE_NAME)
    val pending = File(dir, PENDING_NAME)
    val snapshot = File(dir, SNAPSHOT_NAME)
    val previousSnapshot = File(dir, PREVIOUS_SNAPSHOT_NAME)

    /** The truth file of [generation]. */
    fun db(generation: Int): File = File(dir, if (generation == 0) DB_NAME else "history-$generation.db")

    /** Every generation a file in [dir] names (a quarantined copy's too), so a new one never reuses a name. */
    fun generationsNamed(): Set<Int> {
        val names = dir.list()?.toList().orEmpty()
        val out = HashSet<Int>()
        for (n in names) {
            if (n == DB_NAME || n.startsWith("$DB_NAME-") || n.startsWith("$DB_NAME.")) out += 0
            GENERATION.matchEntire(n.substringBefore(".db") + ".db")?.let { m -> m.groupValues[1].toIntOrNull()?.let(out::add) }
        }
        return out
    }

    /** A generation no file uses yet, and above the one the marker or done-record names (a lost file's own). */
    fun nextGeneration(): Int {
        val named = listOfNotNull(
            (readMarker() as? SafeLoad.Loaded)?.value?.generation,
            (readDone() as? SafeLoad.Loaded)?.value?.generation,
        )
        return ((generationsNamed() + named).maxOrNull() ?: 0) + 1
    }

    fun readMarker(): SafeLoad<Stage2Marker> = SafeFile.peek(marker) { json.decodeFromString(Stage2Marker.serializer(), it) }

    fun readDone(): SafeLoad<DoneRecord> = SafeFile.peek(done) { json.decodeFromString(DoneRecord.serializer(), it) }

    fun writeMarker(m: Stage2Marker) = SafeFile.write(marker, json.encodeToString(Stage2Marker.serializer(), m))

    fun writeDone(generation: Int, atMs: Long) =
        SafeFile.write(done, json.encodeToString(DoneRecord.serializer(), DoneRecord(STAGE2_DONE, generation, atMs)))

    /**
     * SPEC §11 step 1. The done-record outranks the marker: a marker that
     * doesn't say `done` (missing, damaged, an old `failed`) beside a
     * done-record that does is a lost marker, not a failed flip. With
     * neither saying `done`, the generations are scanned, highest first
     * (S9), and when either record is in doubt the answer is "can't tell"
     * rather than a guess (REVIEW-475 N3):
     * - a done-record that exists and doesn't decode says nothing: [Stage2State.Unreadable];
     * - a file that won't open, or is a newer build's, above the highest
     *   finished one may be the truth: [Stage2State.Unreadable], nothing persisted;
     * - a marker from its `.bak` or `.tmp` that says `done` is weighed
     *   against the scan, the higher generation winning (N4);
     * - with nothing finished, a file that won't open is an earlier try's
     *   leftover (M8) only when the marker says a flip ran (`failed` or
     *   `flipping`, written before any copy). With no marker it could be a
     *   truth whose records were lost: [Stage2State.Unreadable].
     */
    fun state(): Stage2State {
        val m = readMarker()
        if (m is SafeLoad.Unavailable) return Stage2State.Unreadable("Couldn't read ${marker.name}: ${m.error}")
        val marker = (m as? SafeLoad.Loaded)?.value
        if (marker?.state == STAGE2_DONE && m.recoveredFrom == null) return Stage2State.Done(marker.generation)
        when (val d = readDone()) {
            is SafeLoad.Unavailable -> return Stage2State.Unreadable("Couldn't read ${done.name}: ${d.error}")
            is SafeLoad.Corrupt -> return Stage2State.Unreadable("${done.name} doesn't decode: ${d.error}")
            is SafeLoad.Loaded -> if (d.value.state == STAGE2_DONE) return Stage2State.Done(d.value.generation, rebuilt = true)
            SafeLoad.Absent -> Unit
        }
        var blocked: FileProbe? = null
        var finished: Int? = null
        for (g in generationsNamed().sortedDescending()) {
            when (val p = probe(db(g))) {
                is FileProbe.Finished -> if (p.generation == g) {
                    finished = g
                    break
                }
                is FileProbe.Unreadable, is FileProbe.TooNew -> if (blocked == null) blocked = p
                FileProbe.Absent, FileProbe.Leftover -> Unit
            }
        }
        fun cantTell(): Stage2State.Unreadable = when (val b = blocked) {
            is FileProbe.TooNew -> Stage2State.Unreadable("The shot history was written by a newer version of Crema: update the app. (${b.why})", tooNew = true)
            is FileProbe.Unreadable -> Stage2State.Unreadable("A shot history file couldn't be opened to tell which is current: ${b.why}")
            else -> Stage2State.Unreadable("The shot history's records can't be read")
        }
        // A file that won't open above the highest finished one may be a newer truth (a recovery's).
        if (blocked != null && (finished != null || marker?.state == STAGE2_DONE || marker == null)) return cantTell()
        if (marker?.state == STAGE2_DONE) return Stage2State.Done(maxOf(marker.generation, finished ?: 0), rebuilt = true)
        if (finished != null) return Stage2State.Done(finished, rebuilt = true)
        return Stage2State.NotDone(marker?.takeIf { it.state == STAGE2_FAILED || it.state == STAGE2_FLIPPING })
    }

    /**
     * Opens [file] through the core as a truth file, reads what it is and
     * closes it. A file that doesn't open is left as it is; an index (a
     * crash between the copy and its promote) or a `copying` copy is a
     * leftover (M8 step 4).
     */
    fun probe(file: File): FileProbe {
        if (!file.exists()) return FileProbe.Absent
        val b = try {
            HistoryDbBridge.openNow(file, HistoryDbBridge.options(HistoryRole.AUTHORITY))
        } catch (e: HistoryDbException.WrongRole) {
            return FileProbe.Leftover
        } catch (e: HistoryDbException.TooNew) {
            return FileProbe.TooNew("${file.name}: ${e.message}")
        } catch (e: HistoryDbException) {
            return FileProbe.Unreadable("${e.javaClass.simpleName}: ${e.message}")
        }
        return try {
            val done = b.now { it.migrationState() } == STAGE2_DONE
            if (done) FileProbe.Finished(b.now { it.info() }.generation.toInt()) else FileProbe.Leftover
        } catch (e: HistoryDbException) {
            FileProbe.Unreadable("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { b.now { it.closeAll() } }
        }
    }

    /**
     * Rename [file] and its `-wal`, `-shm` and `-journal` aside as
     * `<name>.corrupt-<ms>` (SPEC §11: "renamed aside at most, never
     * deleted"), siblings first, as P3a's [QuarantineHandler] does. The
     * marker still names the generation, so it stays `lost` until the user
     * chooses. True when the main file moved, or wasn't there.
     */
    fun quarantine(file: File): Boolean = QuarantineHandler().quarantine(file)

    companion object {
        const val DB_NAME = "history.db"
        const val PENDING_NAME = "pending.jsonl"
        const val SNAPSHOT_NAME = "history-snapshot.db"
        const val PREVIOUS_SNAPSHOT_NAME = "history-snapshot-1.db"
        private val GENERATION = Regex("history-(\\d+)\\.db")
    }
}

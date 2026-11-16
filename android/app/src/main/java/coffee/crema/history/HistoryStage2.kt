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

/** What the files say at boot (SPEC §11 step 1). */
internal sealed interface Stage2State {
    /** The truth is generation [generation]. [rebuilt]: the marker was missing or damaged, and is written again. */
    data class Done(val generation: Int, val rebuilt: Boolean = false) : Stage2State

    /** Stage 1: no flip has passed. [marker] holds a failed one's backoff, if any. */
    data class NotDone(val marker: Stage2Marker?) : Stage2State

    /** The marker or done-record couldn't be read (an I/O error): it says nothing, so nothing flips or opens. */
    data class Unreadable(val error: String) : Stage2State
}

/** What a `history*.db` turned out to be, opened by the core and closed again. */
internal sealed interface FileProbe {
    data object Absent : FileProbe

    /** `authority` with `migration = done`: a truth file. */
    data class Finished(val generation: Int) : FileProbe

    /** Ours and not finished: an earlier try's copy, whatever its role. Never the truth; overwritten in place (M8). */
    data object Leftover : FileProbe

    /** It doesn't open (damaged, foreign, newer, locked): left exactly as it is. */
    data class Unreadable(val why: String) : FileProbe
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

    /** A generation no file uses yet. */
    fun nextGeneration(): Int = (generationsNamed().maxOrNull() ?: 0) + 1

    fun readMarker(): SafeLoad<Stage2Marker> = SafeFile.peek(marker) { json.decodeFromString(Stage2Marker.serializer(), it) }

    fun readDone(): SafeLoad<DoneRecord> = SafeFile.peek(done) { json.decodeFromString(DoneRecord.serializer(), it) }

    fun writeMarker(m: Stage2Marker) = SafeFile.write(marker, json.encodeToString(Stage2Marker.serializer(), m))

    fun writeDone(generation: Int, atMs: Long) =
        SafeFile.write(done, json.encodeToString(DoneRecord.serializer(), DoneRecord(STAGE2_DONE, generation, atMs)))

    /**
     * SPEC §11 step 1. The done-record outranks the marker: a marker that
     * doesn't say `done` (missing, damaged, an old `failed`) beside a
     * done-record that does is a lost marker, not a failed flip. With
     * neither saying `done`, the highest generation that opens as a finished
     * truth file rebuilds both (S9); none means stage 1.
     */
    fun state(): Stage2State {
        val m = readMarker()
        if (m is SafeLoad.Unavailable) return Stage2State.Unreadable("Couldn't read ${marker.name}: ${m.error}")
        val marker = (m as? SafeLoad.Loaded)?.value
        if (marker?.state == STAGE2_DONE && m.recoveredFrom == null) return Stage2State.Done(marker.generation)
        when (val d = readDone()) {
            is SafeLoad.Unavailable -> return Stage2State.Unreadable("Couldn't read ${done.name}: ${d.error}")
            is SafeLoad.Loaded -> if (d.value.state == STAGE2_DONE) return Stage2State.Done(d.value.generation, rebuilt = true)
            else -> Unit
        }
        // A marker read from its .bak or .tmp that says done, with no done-record: still done.
        if (marker?.state == STAGE2_DONE) return Stage2State.Done(marker.generation, rebuilt = true)
        val finished = generationsNamed().sortedDescending().firstNotNullOfOrNull { g ->
            (probe(db(g)) as? FileProbe.Finished)?.takeIf { it.generation == g }
        }
        if (finished != null) return Stage2State.Done(finished.generation, rebuilt = true)
        return Stage2State.NotDone(marker?.takeIf { it.state == STAGE2_FAILED })
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

package coffee.crema.history

import coffee.crema.core.HistoryHeavy
import coffee.crema.core.HistoryLateImport
import coffee.crema.core.HistoryRowIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/*
 * #310 A5: late rows after the flip (SQLite SPEC §11 steps 8 and 9, S13, N9).
 *
 * An older build (a rollback, a hotfix from old code) keeps writing the P3
 * stores: `shots.db`, P3a's `history-pending.jsonl`, and before P3a
 * `shots.json`. Each boot on stage 2:
 *
 * - **The prefilter (N9).** The sizes and mtimes of `shots.db`, its `-wal`,
 *   P3a's journal and `shots.json` against those recorded when the ids were
 *   last read. Unchanged: nothing is read.
 * - **Step 8.** Otherwise every id in `shots.db` is read, read-only (S12),
 *   never `MAX(rowid)`: an older build that deletes the newest row and
 *   records one keeps the count and the max. Rows whose ids
 *   `migration_source` doesn't hold go into `history.db` with their heavy
 *   parts, insert-if-absent, a pulled Visualizer duplicate skipped (N8),
 *   their ids into `migration_source` in the same transaction. So a row
 *   deleted in `history.db` doesn't come back, and a row an older build
 *   deleted comes back from `history.db` as it was, its curve maybe gone
 *   with its details ("curve missing", never a manual log). The same rule
 *   reads P3a's journal (never written after the flip) and `shots.json`'s
 *   rows since P3a's own baseline (S13). The baseline moves to the stamps
 *   read before the ids, so a write during the read is read next time.
 * - **Step 9.** A row that came with no series (`late_ids`): an older build
 *   writes the series after the row. Each boot, one that has landed is
 *   written to its `.series.gz` and its summary updated.
 *
 * A failure reads as "can't tell now": the baseline stays, and the next
 * boot reads again. Nothing here writes or deletes a P3 file.
 */
internal class HistoryLate(
    private val filesDir: File,
    private val shotsDb: File,
    private val tables: HistoryTables,
    private val series: SeriesFiles,
    private val json: Json,
) {
    /** What one run did, for diagnostics and tests. */
    data class Report(val read: Boolean, val imported: List<String>, val filled: List<String>)

    private val shotsJson get() = File(filesDir, HistoryStore.FILE_NAME)
    private val p3Journal get() = File(filesDir, HistoryStore.PENDING_NAME)

    /** Steps 8 and 9 into [a]. [force]: read the ids whatever the prefilter says (the flip's own boot, M9). */
    fun run(a: HistoryAuthority, force: Boolean = false): Report {
        val db = a.db
        val baseline = db.now { it.lateBaseline() }?.let { runCatching { json.decodeFromString(LateBaseline.serializer(), it) }.getOrNull() } ?: LateBaseline()
        // Read before the ids: a write after this moves them again.
        val stamps = LateBaseline.now(filesDir, shotsDb, baseline.shotsJsonHash)
        val imported = ArrayList<String>()
        var read = false
        if (force || baseline.movedFrom(stamps) || TEST_ALWAYS_READ) {
            read = true
            val late = ArrayList<String>()
            fun import(rows: List<Pair<String, HistoryRowIn>>) {
                for (chunk in rows.chunked(BATCH)) {
                    val out = db.now { it.importLate(HistoryLateImport(chunk.map { r -> r.second }, chunk.map { r -> r.first }, null, null)) }
                    a.send(out.written.change)
                    val skipped = out.present.toSet() + out.written.duplicates.map { it.id }
                    imported += chunk.map { it.first }.filterNot { it in skipped }
                }
            }
            // shots.db, read-only (S12). An open or read that fails throws: the baseline stays.
            ReadOnlyShots.open(shotsDb)?.use { ro ->
                val fresh = db.now { it.notInMigrationSource(ro.ids().sorted()) }
                val rows = ro.rows(fresh)
                val ins = rows.map { (id, r) ->
                    val (rowJson, heavy) = r
                    if (zeroSamples(rowJson)) late += id
                    id to HistoryRowIn(rowJson, HistoryHeavy(heavy.profileJson, heavy.brewSeriesJson), pulled = true)
                }
                import(ins)
            }
            // P3a's journal: its puts, by the same id rule. Never written here.
            val entries = journalPuts()
            if (entries.isNotEmpty()) {
                val fresh = db.now { it.notInMigrationSource(entries.map { e -> e.id }) }.toHashSet()
                val ins = entries.filter { it.id in fresh }.distinctBy { it.id }.map { shot ->
                    val samples = (series.read(shot.id) as? SeriesRead.Found)?.samples
                    val summary = if (samples != null) summaryOf(shot.copy(samples = samples), json) else unknownSeriesSummary(shot)
                    if (samples == null && shot.brewMethod == null) late += shot.id
                    shot.id to HistoryRowIn(tables.rowJson(shot, summary), HistoryHeavy(tables.profileJson(shot), tables.brewSeriesJson(shot)), pulled = true)
                }
                import(ins)
            }
            // shots.json's rows since P3a's own baseline (S13): only when its bytes moved.
            var hash = baseline.shotsJsonHash
            var incomplete = false
            if (stamps.shotsJson != baseline.shotsJson || force) {
                val bytes = shotsJson.takeIf { it.exists() }?.readBytes()
                val now = bytes?.let(LateBaseline::sha256)
                if (bytes != null && now != baseline.shotsJsonHash) {
                    val blob = runCatching { json.decodeFromString(ListSerializer(StoredShot.serializer()), bytes.toString(Charsets.UTF_8)) }.getOrNull()
                    if (blob != null) {
                        val fresh = db.now { it.notInMigrationSource(blob.map { s -> s.id }) }.toHashSet()
                        val ins = ArrayList<Pair<String, HistoryRowIn>>()
                        for (row in blob.filter { it.id in fresh }.distinctBy { it.id }) {
                            val samples = row.samples.ifEmpty {
                                when (val r = series.read(row.id)) {
                                    is SeriesRead.Found -> r.samples
                                    is SeriesRead.Unavailable -> { incomplete = true; continue }
                                    else -> emptyList()
                                }
                            }
                            // Its own gz, as at P3a's migration (M2): the old build may prune the .json later.
                            if (samples.isNotEmpty() && !series.write(row.id, samples)) { incomplete = true; continue }
                            if (samples.isEmpty() && row.brewMethod == null) late += row.id
                            ins += row.id to HistoryRowIn(
                                tables.rowJson(row, summaryOf(row.copy(samples = samples), json)),
                                HistoryHeavy(tables.profileJson(row), tables.brewSeriesJson(row)),
                                pulled = true,
                            )
                        }
                        import(ins)
                    }
                    if (!incomplete) hash = now
                }
            }
            val lateIds = (db.now { it.lateIds() } + late).distinct()
            // The stamps as the read left them: a read-only open of a WAL file can make its -wal and
            // -shm. Only this process could write the P3 files meanwhile, and after the flip it never
            // does, so nothing written during the read is missed (an older build can't run beside us).
            val after = LateBaseline.now(filesDir, shotsDb, baseline.shotsJsonHash)
            // A shots.json row that couldn't come in now is looked at again next boot.
            val next = after.copy(shotsJsonHash = hash, shotsJson = if (incomplete) null else stamps.shotsJson)
            db.now { it.importLate(HistoryLateImport(emptyList(), emptyList(), lateIds, json.encodeToString(LateBaseline.serializer(), next))) }
        }
        return Report(read, imported, fillLate(a))
    }

    /** Step 9: each late row whose series has landed gets it, and its summary. */
    private fun fillLate(a: HistoryAuthority): List<String> {
        val ids = a.db.now { it.lateIds() }
        if (ids.isEmpty()) return emptyList()
        val still = ArrayList<String>()
        val filled = ArrayList<String>()
        val rows = a.db.now { it.get(ids) }.associateBy { it.id }
        for (id in ids) {
            val row = rows[id]?.let { runCatching { tables.decodeRow(it.rowJson) }.getOrNull() } ?: continue
            val read = series.read(id)
            if (read is SeriesRead.Found && series.write(id, read.samples)) {
                val shot = row.shot
                a.write(listOf(shot to summaryOf(shot.copy(samples = read.samples), json)))
                filled += id
            } else {
                still += id
            }
        }
        a.db.now { it.importLate(HistoryLateImport(emptyList(), emptyList(), still, null)) }
        return filled
    }

    private fun zeroSamples(rowJson: String): Boolean = runCatching {
        val r = tables.decodeRow(rowJson)
        r.summary?.sampleCount == 0u && r.shot.brewMethod == null
    }.getOrDefault(false)

    /** P3a's journal's rows to put, in order; a line that doesn't decode is skipped. */
    private fun journalPuts(): List<StoredShot> =
        if (!p3Journal.exists()) emptyList()
        else p3Journal.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
            runCatching { json.decodeFromString(JournalEntry.serializer(), line).put }.getOrNull()
        }

    companion object {
        /** Rows per import transaction. */
        const val BATCH = 250

        /** Test seam: the prefilter always says "moved" (its own test turns it off). */
        @androidx.annotation.VisibleForTesting
        @Volatile internal var TEST_ALWAYS_READ = false
    }
}

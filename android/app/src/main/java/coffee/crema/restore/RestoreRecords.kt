package coffee.crema.restore

import coffee.crema.core.BackupImporter
import coffee.crema.persist.ImportLimits
import coffee.crema.persist.ImportTooLargeException
import coffee.crema.core.PlanAction
import coffee.crema.core.RestorePlan
import coffee.crema.ui.BackupDecodeError
import coffee.crema.ui.ParsedBackup
import coffee.crema.ui.parseBackupBytes
import coffee.crema.visualizer.storedShotFromBackupJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** A backup's records, decoded to this shell's types before the first write. */
class RestoreRecords(val parsed: ParsedBackup) {
    /** Whether step 8 has anything to apply. */
    fun hasSingletons(plan: RestorePlan): Boolean =
        ((parsed.common != null || parsed.prefs != null) && plan.settings.common == PlanAction.Apply) ||
            (parsed.maintenance != null && plan.maintenance == PlanAction.Apply) ||
            (parsed.visualizerPrefs != null && plan.visualizerPrefs == PlanAction.Apply)

    companion object {
        /**
         * Read after the plan, which says whether it's a backup, through the
         * core's line-at-a-time importer (#310 P2a-b); throws
         * [coffee.crema.ui.BackupDecodeError] on a row this shell can't take.
         *
         * With [shots], only those shots are kept. Every shot is still decoded,
         * so one this shell can't take refuses the restore as before, but the
         * others go as they're read: what a restore holds is bounded by the
         * history it keeps, not by the backup. The lines kept are then decoded
         * as a backup of their own, by the same parse.
         *
         * What's kept is capped at [maxKeptBytes], as everything but the shots
         * is held whole: past it the restore is refused with
         * [ImportTooLargeException] before any write (security review of #395).
         * The cap follows the heap, not the file budget: buffering, copying and
         * parsing the kept lines peaks at about 7x their size (REVIEW-395).
         */
        fun read(
            prepared: PreparedBackup,
            json: Json,
            nowMs: Long,
            shots: Set<String>? = null,
            maxKeptBytes: Long = defaultMaxKeptBytes(),
        ): RestoreRecords {
            val kept = java.io.ByteArrayOutputStream()
            BackupImporter().use { importer ->
                prepared.forEachLine { piece ->
                    when (piece) {
                        BackupLinePiece.Oversized -> importer.pushOversizedLine()
                        is BackupLinePiece.Line -> {
                            val record = importer.pushLine(piece.bytes)
                            if (record != null && keep(record, json, shots)) {
                                if (kept.size().toLong() + piece.bytes.size + 1 > maxKeptBytes) {
                                    throw ImportTooLargeException(
                                        "This backup holds more than ${ImportLimits.mb(maxKeptBytes)} of records to restore, more than a restore reads.",
                                    )
                                }
                                kept.write(piece.bytes)
                                kept.write('\n'.code)
                            }
                        }
                    }
                }
            }
            return RestoreRecords(parseBackupBytes(kept.toByteArray(), json, nowMs))
        }

        /** Kept records a restore may hold: an eighth of the heap, at least 48 MB. */
        fun defaultMaxKeptBytes(maxHeap: Long = Runtime.getRuntime().maxMemory()): Long =
            maxOf(MIN_KEPT_BYTES, maxHeap / 8)

        /** The floor: 300 shots at about 120 KB, plus a library, fit under it. */
        const val MIN_KEPT_BYTES = 48L * 1024 * 1024

        /** Whether the line behind [record], a `BackupRecord` JSON, is one the run needs. */
        private fun keep(record: String, json: Json, shots: Set<String>?): Boolean {
            val o = json.parseToJsonElement(record).jsonObject
            if ((o["line"] as? JsonPrimitive)?.content != "shot") return true
            val shot = o["shot"] as? JsonObject
            val id = (shot?.get("id") as? JsonPrimitive)?.content
            if (shot == null || storedShotFromBackupJson(shot, json) == null) throw BackupDecodeError("shot", id)
            return shots == null || id in shots
        }

        /** The backup shots a plan's step 7 can write, bring-back included, so one read serves either answer. */
        fun shotsTheRunWrites(plan: RestorePlan): Set<String> = with(plan.shots) {
            (add + overwrite + revive + fill + deletedHere).toSet()
        }
    }
}

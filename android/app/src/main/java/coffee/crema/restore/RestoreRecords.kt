package coffee.crema.restore

import coffee.crema.core.PlanAction
import coffee.crema.core.RestorePlan
import coffee.crema.ui.ParsedBackup
import coffee.crema.ui.parseBackupBytes
import kotlinx.serialization.json.Json

/** A backup's records, decoded to this shell's types before the first write. */
class RestoreRecords(val parsed: ParsedBackup) {
    /** Whether step 8 has anything to apply. */
    fun hasSingletons(plan: RestorePlan): Boolean =
        ((parsed.common != null || parsed.prefs != null) && plan.settings.common == PlanAction.Apply) ||
            (parsed.maintenance != null && plan.maintenance == PlanAction.Apply) ||
            (parsed.visualizerPrefs != null && plan.visualizerPrefs == PlanAction.Apply)

    companion object {
        /** Null when the bytes carry no backup header; throws [coffee.crema.ui.BackupDecodeError] on a row this shell can't take. */
        fun read(prepared: PreparedBackup, json: Json, nowMs: Long): RestoreRecords? =
            parseBackupBytes(prepared.jsonl, json, nowMs)?.let(::RestoreRecords)
    }
}

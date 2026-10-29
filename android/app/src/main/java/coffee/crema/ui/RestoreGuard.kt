package coffee.crema.ui

import coffee.crema.core.GuardedVisualizerPrefs
import coffee.crema.core.VisualizerSyncPrefs
import kotlinx.serialization.json.Json
import coffee.crema.core.guardRestoredVisualizerPrefs as coreGuardRestoredVisualizerPrefs

/*
 * Restoring a backup never widens what Crema shares (review 05 P3-1). A backup
 * can come from someone else, so a Visualizer `privacy` that is more public,
 * `includeNotes` / `includeProfile` / `autoUpload` / `autoSync` turning on, or
 * a sync direction that starts pushing, are NOT applied: this device keeps its
 * current value and the restore notice says so. The rule
 * lives in the core (`de1_domain::guard_restored_visualizer_prefs`, shared with
 * the web shell, which asks for confirmation instead). Android has no webhook
 * setting, so there is nothing else to hold.
 */

/** [incoming] guarded against [current]; on a bridge failure, keep [current] entirely. */
internal fun guardRestoredPrefs(
    current: VisualizerSyncPrefs,
    incoming: VisualizerSyncPrefs,
    json: Json,
): GuardedVisualizerPrefs = runCatching {
    json.decodeFromString(
        GuardedVisualizerPrefs.serializer(),
        coreGuardRestoredVisualizerPrefs(
            json.encodeToString(VisualizerSyncPrefs.serializer(), current),
            json.encodeToString(VisualizerSyncPrefs.serializer(), incoming),
        ),
    )
}.getOrElse { GuardedVisualizerPrefs(apply = current, held = GUARDED_SHARING_FIELDS) }

/** Every field the core guard can hold, in its order: the fail-closed report. */
internal val GUARDED_SHARING_FIELDS = listOf(
    "privacy", "includeNotes", "autoUpload", "includeProfile", "autoSync",
    "shotsDirection", "beansDirection", "roastersDirection",
)

/** The restore-notice suffix for the fields a restore kept, or null when none were held. */
internal fun heldSharingNotice(held: List<String>): String? {
    val names = held.mapNotNull {
        when (it) {
            "privacy" -> "upload privacy"
            "includeNotes" -> "tasting notes in uploads"
            "autoUpload" -> "auto-upload"
            "includeProfile" -> "profile in uploads"
            "autoSync" -> "background sync"
            "shotsDirection" -> "shot sync direction"
            "beansDirection" -> "bean sync direction"
            "roastersDirection" -> "roaster sync direction"
            else -> null
        }
    }.distinct()
    if (names.isEmpty()) return null
    val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
    return "Kept this device's Visualizer $list; change them in Settings › Sharing if you want the backup's."
}

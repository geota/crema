package coffee.crema.ui

import coffee.crema.history.StoredShot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The shot menu's single "Upload" entry across every cloud destination
 * (Visualizer, the Decent account, …) — the Android twin of the web's
 * `$lib/history/upload-targets`.
 *
 * Each destination the user can turn on in Settings → Sharing is an
 * [UploadDestination]; per shot it becomes an [UploadTarget]: whether it can
 * take the shot right now, whether this shot is already on it, and where to
 * view / share it. [uploadMenuEntry] folds the list into ONE menu item —
 * "Upload to Visualizer + Decent", "Upload to Decent" (the ones still missing
 * it), or "Re-upload to …" once the shot is everywhere — so adding a
 * destination never adds a menu row. Pure Kotlin (no Android types):
 * unit-tested.
 */

/** A cloud destination. The serial names are the persisted log / notice keys. */
@Serializable
enum class UploadTargetId(val displayName: String) {
    @SerialName("visualizer")
    Visualizer("Visualizer"),

    @SerialName("decent")
    Decent("Decent"),
}

data class UploadTarget(
    val id: UploadTargetId,
    /** Short display name ("Visualizer", "Decent"). */
    val name: String,
    /** Turned on + usable in Settings → Sharing right now, and able to take this shot. */
    val enabled: Boolean,
    /** This shot already has a copy there. */
    val uploaded: Boolean,
    /** Where the uploaded copy can be opened ("View on X"), when it can. */
    val viewUrl: String?,
    /**
     * A public link to the uploaded copy, safe to hand to someone else — null
     * when the destination only has an owner-only page for it (a Decent upload
     * whose server id never came back).
     */
    val shareUrl: String? = viewUrl,
    /**
     * Whether this destination counts toward the shot's cloud status (the row
     * pip, the "Upload N" backlog). False for a shot the destination never
     * takes automatically — a sub-5 s flush on Decent — even though a manual
     * push is still [enabled].
     */
    val counted: Boolean = enabled,
) {
    /** A public link exists: offer "Share link" (web `shareable`); otherwise only "View on X". */
    val shareable: Boolean get() = uploaded && shareUrl != null
}

data class UploadMenuEntry(
    val title: String,
    val sub: String,
    /** False when no destination is enabled — the row stays visible but dimmed. */
    val enabled: Boolean,
    /** The destinations a tap pushes to: the missing ones, else (re-upload) every enabled one. */
    val targets: List<UploadTarget>,
    /** True when the tap is a re-upload (the shot is already on every enabled destination). */
    val reupload: Boolean,
)

private fun names(ts: List<UploadTarget>) = ts.joinToString(" + ") { it.name }

fun uploadMenuEntry(all: List<UploadTarget>): UploadMenuEntry {
    val enabled = all.filter { it.enabled }
    if (enabled.isEmpty()) {
        return UploadMenuEntry(
            title = "Upload shot",
            sub = "Connect Visualizer or your Decent account in Settings → Sharing.",
            enabled = false,
            targets = emptyList(),
            reupload = false,
        )
    }
    val missing = enabled.filter { !it.uploaded }
    if (missing.isNotEmpty()) {
        return UploadMenuEntry("Upload to ${names(missing)}", "Push this shot to ${names(missing)}.", true, missing, reupload = false)
    }
    return UploadMenuEntry("Re-upload to ${names(enabled)}", "Refreshes the copy on ${names(enabled)}.", true, enabled, reupload = true)
}

/** The "View on X" rows — one per destination that holds the shot. */
fun viewableTargets(all: List<UploadTarget>): List<UploadTarget> = all.filter { it.uploaded && it.viewUrl != null }

/** The "Share link" rows — only destinations with a public link to the shot. */
fun shareableTargets(all: List<UploadTarget>): List<UploadTarget> = all.filter { it.shareable }

/** The destinations for one shot — one row per service the user can enable in Settings → Sharing. */
fun uploadTargetsFor(shot: StoredShot, destinations: List<UploadDestination>): List<UploadTarget> =
    destinations.map { d ->
        val uploaded = d.isUploaded(shot)
        UploadTarget(
            id = d.id,
            name = d.id.displayName,
            enabled = d.enabled && d.canUpload(shot),
            uploaded = uploaded,
            viewUrl = if (uploaded) d.viewUrl(shot) else null,
            shareUrl = if (uploaded) d.shareUrl(shot) else null,
            counted = d.enabled && (uploaded || d.inBacklog(shot)),
        )
    }

/** Distinct shots missing from at least one enabled destination — the "Upload N" count. */
fun missingUploadTotal(shots: List<StoredShot>, destinations: List<UploadDestination>): Int {
    val on = destinations.filter { it.enabled }
    if (on.isEmpty()) return 0
    return shots.count { s -> on.any { it.inBacklog(s) } }
}

/** Shots missing from each enabled destination (only non-zero entries). */
fun missingUploadCounts(shots: List<StoredShot>, destinations: List<UploadDestination>): Map<UploadTargetId, Int> =
    destinations.filter { it.enabled }
        .associate { d -> d.id to shots.count { d.inBacklog(it) } }
        .filterValues { it > 0 }

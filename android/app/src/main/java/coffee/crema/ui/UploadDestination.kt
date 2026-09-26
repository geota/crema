package coffee.crema.ui

import coffee.crema.history.StoredShot
import coffee.crema.history.isBrewLog

/*
 * One cloud destination for shots (Visualizer, the Decent account). Both sync
 * controllers implement it so [SharingController] iterates destinations
 * instead of switching on names: the History menu targets, the "Upload N"
 * backlog, the catch-up offer, the per-shot completion notice.
 *
 * State reads ([enabled], [autoUpload], [inBacklog]) come from the
 * destination's own persisted state, not the UI mirror, so a caller acting
 * right after a sign-in or a toggle sees the new value.
 */
interface UploadDestination {
    val id: UploadTargetId

    /** Signed in / linked and able to take a manual push right now. */
    val enabled: Boolean

    /** Each finished shot is pushed automatically. */
    val autoUpload: Boolean

    /** Where every per-shot outcome is reported (the cross-destination notice). */
    var onUploadOutcome: ((shotId: String, outcome: UploadOutcome) -> Unit)?

    fun isUploaded(shot: StoredShot): Boolean

    /** Where to open the uploaded copy. */
    fun viewUrl(shot: StoredShot): String?

    /** A public link to the uploaded copy, or null when there is none. */
    fun shareUrl(shot: StoredShot): String? = viewUrl(shot)

    /** Can a manual push send this shot here at all? */
    fun canUpload(shot: StoredShot): Boolean = true

    /** Does this shot belong to the backlog (not here yet, and eligible to be)? */
    fun inBacklog(shot: StoredShot): Boolean

    /** The backlog. Brew Log rows (issue #10) are local-only and never in it. */
    fun unsent(shots: List<StoredShot>): List<StoredShot> = shots.filter { !it.isBrewLog && inBacklog(it) }

    /** The capture-time push. Returns true when an upload actually started (it will report an outcome). */
    fun maybeAutoUpload(shot: StoredShot, fullSamples: List<TelemetrySample>? = null): Boolean

    /**
     * A one-off manual push (the History menu). Returns true when an upload
     * started; false when it could not (not signed in, already in flight).
     */
    fun pushShot(shot: StoredShot, replace: Boolean): Boolean

    /** Drain the backlog, awaiting the whole pass. */
    suspend fun uploadUnsentNow(shots: List<StoredShot>): DrainResult

    /** Persist the auto-upload toggle; returns once it is stored. */
    suspend fun setAutoUploadNow(enabled: Boolean)
}

/** One backlog pass. [stopped] names why the pass ended early, if it did. */
data class DrainResult(
    val uploaded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val stopped: String? = null,
) {
    val attempted: Int get() = uploaded + failed + skipped
}

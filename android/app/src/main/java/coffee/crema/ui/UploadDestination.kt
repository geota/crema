package coffee.crema.ui

import coffee.crema.history.ShotRow
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

    fun isUploaded(shot: ShotRow): Boolean

    /** Where to open the uploaded copy. */
    fun viewUrl(shot: ShotRow): String?

    /** A public link to the uploaded copy, or null when there is none. */
    fun shareUrl(shot: ShotRow): String? = viewUrl(shot)

    /** Can a manual push send this shot here at all? */
    fun canUpload(shot: ShotRow): Boolean = true

    /** Does this shot belong to the backlog (not here yet, and eligible to be)? */
    fun inBacklog(shot: ShotRow): Boolean

    /** The backlog. Brew Log rows (issue #10) are local-only and never in it. */
    fun unsent(shots: List<ShotRow>): List<ShotRow> = shots.filter { !it.isBrewLog && inBacklog(it) }

    /** The capture-time push. Returns true when an upload actually started (it will report an outcome).
     *  The destination reads the shot whole just before it goes (#310 P4a). */
    fun maybeAutoUpload(shot: ShotRow): Boolean

    /**
     * A one-off manual push (the History menu). Returns true when an upload
     * started; false when it could not (not signed in, already in flight).
     */
    fun pushShot(shot: ShotRow, replace: Boolean): Boolean

    /** Drain the backlog, awaiting the whole pass. */
    suspend fun uploadUnsentNow(shots: List<ShotRow>): DrainResult

    /** Persist the auto-upload toggle; returns once it is stored. */
    suspend fun setAutoUploadNow(enabled: Boolean)
}

/**
 * One backlog pass. [stopped] names why the pass ended early, if it did;
 * [note] is anything else the user should read once (Decent: shots held back
 * because their DE1 is not on the account).
 */
data class DrainResult(
    val uploaded: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
    val stopped: String? = null,
    val note: String? = null,
) {
    val attempted: Int get() = uploaded + failed + skipped
}

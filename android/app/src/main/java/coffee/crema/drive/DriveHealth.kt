package coffee.crema.drive

import coffee.crema.core.AttentionReason
import coffee.crema.core.RunOutcome
import coffee.crema.core.SyncFailure
import coffee.crema.core.SyncFailureRejectedInner
import coffee.crema.core.SyncHealth
import coffee.crema.core.WaitReason
import coffee.crema.core.classifySyncFailure
import coffee.crema.core.syncHealthAfterRun
import kotlinx.serialization.json.Json
import java.io.IOException

/*
 * What a failed Drive call means and how Settings words it. The rules are the
 * core's (`classify_sync_failure`, `sync_health_after_run`); this reads them and
 * holds the copy.
 */

/** The core's JSON: its enums carry their variant under `kind`. */
private val coreJson = Json {
    classDiscriminator = "kind"
    ignoreUnknownKeys = true
}

/** The notice and banner when Google ends the sign-in. */
const val DRIVE_RECONNECT_LINE = "Google ended the Drive sign-in, so backups have stopped. Reconnect to resume them."

/** The line when Drive refuses a sign-in Google hasn't ended. */
const val DRIVE_REFUSED_LINE = "Google Drive refused this sign-in. Disconnect and connect again if it keeps happening."

/** What a failed Drive call means. A reply goes to the core; a transport failure is [SyncFailure.Offline]. */
internal fun driveFailure(e: Throwable, operation: String, nowMs: Long): SyncFailure = when (e) {
    is DriveHttpError -> coreJson.decodeFromString(
        SyncFailure.serializer(),
        classifySyncFailure("drive", operation, e.status.takeIf { it in 0..0xFFFF }?.toUShort(), e.body, null, nowMs),
    )
    is IOException -> SyncFailure.Offline
    else -> SyncFailure.Rejected(SyncFailureRejectedInner(0u))
}

/** Whether time alone can fix [failure] (core `SyncFailure::is_transient`). */
internal fun isTransient(failure: SyncFailure): Boolean =
    failure == SyncFailure.Offline || failure is SyncFailure.RateLimited || failure is SyncFailure.Unavailable

/** The core `SyncHealth` JSON a run leaves. */
internal fun driveHealthAfter(outcome: RunOutcome, nowMs: Long): String =
    syncHealthAfterRun(coreJson.encodeToString(RunOutcome.serializer(), outcome), nowMs)

/** The words for [failure]: its own, else the reply's [message], else a refusal. */
internal fun driveFailureLine(failure: SyncFailure, message: String?): String = when (failure) {
    SyncFailure.Offline -> "couldn’t reach Google Drive"
    is SyncFailure.RateLimited -> "Google Drive asked Crema to slow down"
    is SyncFailure.Unavailable -> "Google Drive isn’t answering properly"
    SyncFailure.StorageFull -> "your Google Drive is full"
    SyncFailure.SessionEnded -> DRIVE_RECONNECT_LINE
    else -> message ?: "Google Drive refused the request"
}

/** The Settings line for a stored `SyncHealth`, or null when there's nothing to say. */
internal fun driveHealthLine(healthJson: String?): String? {
    val health = healthJson?.let { runCatching { coreJson.decodeFromString(SyncHealth.serializer(), it) }.getOrNull() }
    return when (health) {
        is SyncHealth.Waiting -> when (health.details.why) {
            WaitReason.Offline -> "Waiting for a connection. Crema will back up on its own."
            WaitReason.RateLimited -> "Google Drive asked Crema to wait. It will try again on its own."
        }
        is SyncHealth.NeedsAttention -> when (health.details.why) {
            AttentionReason.StorageFull -> "Your Google Drive is full. Free up space to keep backing up."
            AttentionReason.ServerTrouble -> "Google Drive isn’t answering properly. Crema will try again next launch."
            AttentionReason.Reconnect -> DRIVE_RECONNECT_LINE
            else -> "Google Drive refused the last backup."
        }
        else -> null
    }
}

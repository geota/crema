package coffee.crema.ui

import coffee.crema.core.CremaBridge
import coffee.crema.core.NotificationSource
import coffee.crema.core.cremaProfileFromWire

/** An Idle machine and a settled connection (the connect sweep ran): the
 *  core's profile sync uploads a desired profile at once. */
internal fun CremaBridge.settleForUploads(nowMs: ULong = 0uL) {
    onNotification(NotificationSource.DE1_STATE, byteArrayOf(2, 0), nowMs)
    connectSweep("{}", nowMs)
}

/** How long the core lets a new desired profile rest before it uploads. */
internal const val DESIRED_SETTLE_MS = 500uL

/**
 * Make a wire `Profile` fixture (adapted to a library `CremaProfile` by the
 * core, as the app's library does) the desired profile through the shell's
 * only entry point, `setDesiredProfile`, with the Quick-Controls overrides
 * [qcJson] (none by default; a title alone does not change what is uploaded).
 * The core uploads once the profile has rested [DESIRED_SETTLE_MS]; this ticks
 * it then and returns that tick's `CoreOutput` JSON: on a settled, idle
 * connection, the upload the core's profile sync started.
 */
internal fun CremaBridge.uploadWire(wireJson: String, nowMs: ULong, qcJson: String? = null): String {
    setDesiredProfile(cremaProfileFromWire(wireJson), qcJson, nowMs)
    return onTick(nowMs + DESIRED_SETTLE_MS)
}

package coffee.crema.firmware

import coffee.crema.core.DecentFirmwareChannel
import coffee.crema.core.FirmwareImageCheck
import coffee.crema.core.FirmwareImageError
import coffee.crema.core.FlashBlocker
import coffee.crema.core.FlashFailure
import coffee.crema.core.FlashPhase
import coffee.crema.core.FlashStatus

// English copy for the firmware-update flow — web parity with
// `$lib/firmware/flash-copy.ts` and `source.ts` (`imageProblem`).

/** The alpha warning, one point per line. */
val ALPHA_POINTS: List<String> = listOf(
    "Firmware updates in Crema are alpha and have not yet been tried on a real DE1. Updating takes about 15 to 25 minutes.",
    "Crema writes only the DE1’s spare memory bank. Decent’s apps report that the DE1 switches to it only after checking every byte, so a failed update leaves the current firmware in place.",
    "Keep this device charging and near the DE1, and keep Crema open until it finishes.",
    "You’ll need to switch the DE1 off and on at the end.",
)

/** Under every failure before the verify request. */
const val STILL_SAFE =
    "Decent’s apps report that the DE1 stays on its previous firmware when an update stops before it is checked. The DE1 was left asleep. You can retry; the update starts again from the beginning."

/** When the verify outcome was lost. */
const val UNCONFIRMED =
    "The connection dropped or the DE1 didn’t answer while it was checking the firmware. The DE1 may be running either build. Switch it off and on and Crema will read which."

/** While an interrupted erase may still be running. */
const val ERASE_SETTLING =
    "The DE1 may still be erasing. Crema keeps other commands away from it until it finishes (up to a minute)."

/** The exit confirmation on the power-cycle step. */
const val EXIT_QUESTION =
    "Exit without confirming the build? Switch the DE1 off and on before using it, and don’t start another update until it has restarted."

/** The cancel confirmation. */
fun cancelQuestion(installedBuild: UInt?): String =
    if (installedBuild == null) {
        "Stop the update? Your DE1 stays on its current build."
    } else {
        "Stop the update? Your DE1 stays on build $installedBuild."
    }

/** Under the power-cycle steps: what Crema sees right now. */
fun waitingText(s: FlashStatus): String {
    val old = s.restartedOnBuild
    val now = when {
        s.foreignMachine != null ->
            "A different DE1 is connected. Crema only accepts the build from the DE1 it updated: connect that one after switching it off and on, or exit."
        old != null && s.verifyUnconfirmed ->
            "The DE1 reports build $old, not ${s.targetBuild}. If it still says $old after you switch it off and on, the update didn’t take: your DE1 is fine on build $old, and you can exit and try again."
        old != null ->
            "The DE1 reconnected but reports build $old, not ${s.targetBuild}. Switch it off at the back, wait 10 seconds and switch it on again."
        s.machineRestarted && s.linkUp -> "The DE1 is connected. Reading its build…"
        s.machineRestarted -> "The DE1 is offline. Waiting for it to come back and report its build…"
        else -> "Waiting for the DE1 to be switched off…"
    }
    return "$now Crema keeps other commands away from the DE1 until then."
}

/** The app-wide notice while a hidden flash holds the DE1. */
fun heldBannerText(s: FlashStatus?): String = when {
    s?.foreignMachine != null -> "Firmware update waiting for the DE1 it updated — a different DE1 is connected; DE1 control paused"
    s?.phase == FlashPhase.AwaitingPowerCycle -> "Firmware update waiting for the DE1 to restart — DE1 control paused"
    s?.eraseSettling == true -> "Firmware update stopped; the DE1 may still be erasing — DE1 control paused"
    else -> "Firmware update in progress — DE1 control paused"
}

fun channelLabel(channel: DecentFirmwareChannel): String = when (channel) {
    DecentFirmwareChannel.Stable -> "Stable"
    DecentFirmwareChannel.Beta -> "Beta"
    DecentFirmwareChannel.Nightly -> "Nightly (early access)"
}

fun phaseLabel(phase: FlashPhase): String = when (phase) {
    FlashPhase.Preparing -> "Putting the DE1 to sleep"
    FlashPhase.Erasing -> "Erasing the spare memory bank"
    FlashPhase.Uploading -> "Sending firmware"
    FlashPhase.Verifying -> "DE1 is checking the firmware"
    FlashPhase.AwaitingPowerCycle -> "Switch the DE1 off and on"
    FlashPhase.Succeeded -> "Firmware updated"
    FlashPhase.Failed -> "Update stopped"
    FlashPhase.Cancelled -> "Update cancelled"
}

/** "About 14 min left" / "40 s left". */
fun etaText(status: FlashStatus): String {
    val s = status.etaSeconds?.toInt() ?: return ""
    val about = if (status.etaIsEstimate) "About " else ""
    return if (s >= 90) "$about${Math.round(s / 60.0)} min left" else "$about${maxOf(1, s)} s left"
}

fun blockerText(b: FlashBlocker): String = when (b) {
    FlashBlocker.NotConnected -> "Connect your DE1."
    FlashBlocker.NotReady -> "Wait for the DE1 to finish connecting."
    FlashBlocker.ReadOnlyMirror -> "This device mirrors another Crema. Update from the primary device."
    FlashBlocker.FlashInProgress -> "A firmware update is already running."
    FlashBlocker.BuildUnknown -> "The DE1 hasn’t reported its firmware build yet."
    FlashBlocker.ModelUnknown -> "The DE1 hasn’t reported its model yet."
    is FlashBlocker.UnsupportedModel ->
        if (b.content.model >= 128u) {
            "Bengle firmware can’t be updated from Crema yet."
        } else {
            "Crema can’t update this DE1 model yet (it follows Decent’s list: DE1PRO, DE1XL, DE1XXL, DE1XXXL)."
        }
    FlashBlocker.StateUnknown -> "The DE1 hasn’t reported its state yet."
    is FlashBlocker.MachineBusy -> "Wait until the DE1 is idle or asleep."
    FlashBlocker.Refilling -> "Refill the water tank first."
    FlashBlocker.MachineError -> "The DE1 reports an error. Clear it first."
    FlashBlocker.ShotInProgress -> "Wait for the shot, steam or hot water to finish."
    FlashBlocker.GuidedBrewActive -> "Finish the guided brew first."
    FlashBlocker.ProfileUploading -> "Wait for the profile upload to finish."
    FlashBlocker.MaintenanceRunning -> "Wait for the cleaning or descaling cycle to finish."
    FlashBlocker.LowPower -> "Plug this device in, or charge it above 50 %."
}

fun failureText(f: FlashFailure): String = when (f) {
    is FlashFailure.LinkLost -> "The DE1 disconnected during the update."
    is FlashFailure.WriteFailed -> "A Bluetooth write failed (${f.content.message})."
    is FlashFailure.AckTimeout -> "The DE1 stopped answering."
    FlashFailure.EraseTimeout -> "The DE1 didn’t finish erasing within a minute."
    is FlashFailure.VerifyFailed ->
        "The DE1 found a damaged block at 0x${f.content.first_error.toString(16).padStart(6, '0')} and kept its current firmware."
}

/** Plain-English reason a firmware file was refused. */
fun imageProblem(check: FirmwareImageCheck): String = when (val e = check.error) {
    is FirmwareImageError.TooShort -> "This file is too small to be DE1 firmware."
    FirmwareImageError.BengleImage -> "This is Bengle firmware. Crema only updates classic DE1 machines."
    is FirmwareImageError.NotADe1Image -> "This file isn’t DE1 firmware."
    is FirmwareImageError.NotInManifest ->
        "Decent is serving DE1 build ${e.content.build}, but not a copy Crema has verified (it may be newer, or damaged). Crema only installs builds it knows byte for byte; update Crema to get it."
    is FirmwareImageError.Malformed -> "This file failed a firmware check (${e.content.reason})."
    null -> "This file could not be checked."
}

/** The power rule: charging, or battery at 50 % or more. */
fun powerOk(charging: Boolean, levelPercent: Int?): Boolean =
    charging || levelPercent == null || levelPercent >= 50

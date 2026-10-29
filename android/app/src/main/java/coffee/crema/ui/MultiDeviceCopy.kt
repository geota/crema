package coffee.crema.ui

/*
 * The words and enablement of the multi-device surfaces (hub, remote,
 * normal), kept pure so both shells say the same thing and the tests can pin
 * them without composing a screen. Users see "hub" and "remote"; the code's
 * roles stay `primary` and `secondary`.
 */

/** The remote's status line: "Connected through <hub>", "Reconnecting to
 *  <hub>…" while its link redials, or "<hub> isn't connected to the machine"
 *  while the link is up but the hub's roster has no DE1. */
internal fun remoteStatusLine(hub: String, reconnecting: Boolean, hubHasMachine: Boolean, viewOnly: Boolean = false): String =
    when {
        reconnecting -> "Reconnecting to $hub…"
        !hubHasMachine -> "$hub isn't connected to the machine"
        viewOnly -> "Connected through $hub · view-only"
        else -> "Connected through $hub"
    }

/** The hub this remote connects through, by name: from its Welcome (then its
 *  snapshots), else the address this device dials. */
internal fun MainUiState.remoteHubLabel(): String =
    mirroringPrimaryName.ifBlank { proxyPrimaryHost.ifBlank { "the hub" } }

/** The status line while this device is a remote, else null. */
internal fun MainUiState.remoteStatus(): String? =
    if (proxyRole != "secondary") null
    else remoteStatusLine(remoteHubLabel(), mirrorReconnecting, remoteHubHasMachine, mirrorViewOnly)

/** A remote may connect the machine itself when its hub is unreachable or no
 *  longer holds the DE1. */
internal fun MainUiState.canConnectDirectly(): Boolean =
    proxyRole == "secondary" && (mirrorReconnecting || !remoteHubHasMachine)

/** Why Hub mode can't be turned on here, or null. Another hub holds the DE1:
 *  this device's hub (it is a remote whose hub has the machine), or one NSD
 *  lists (a second hub without the machine would only confuse remotes). */
internal fun MainUiState.hubModeBlockedReason(): String? = when {
    proxyRole == "secondary" && !mirrorReconnecting && remoteHubHasMachine -> "Turn off hub mode on ${remoteHubLabel()} first"
    proxyRole == "normal" && hubHoldingMachine != null -> "Turn off hub mode on ${hubHoldingMachine.name} first"
    else -> null
}

/** The Hub mode switch's subtitle. [device] is "tablet" or "phone". */
internal fun MainUiState.hubModeSubtitle(device: String): String {
    hubModeBlockedReason()?.let { return it }
    return if (proxyRole == "primary") {
        "This $device will disconnect from the machine so another device can connect."
    } else {
        "Hold the machine on this $device so other devices can connect through it."
    }
}

/** The DE1 connect surfaces on a normal device while another hub holds the
 *  machine. */
internal fun turnOffHubToConnect(hub: String): String = "Turn off hub mode on $hub to connect this device"

/** The action that makes this device a remote of [hub]. */
internal fun connectThroughLabel(hub: String): String = "Connect through $hub"

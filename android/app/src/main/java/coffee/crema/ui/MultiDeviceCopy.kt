package coffee.crema.ui

import coffee.crema.ble.proxy.Peer

/*
 * The words and enablement of the multi-device surfaces (hub, remote,
 * normal), kept pure so both shells say the same thing and the tests can pin
 * them without composing a screen. Users see "hub" and "remote"; the code's
 * roles stay `primary` and `secondary`.
 */

/** The remote's status line: "Connected through <hub>", "Reconnecting to
 *  <hub>…" while its link redials, or "<hub> isn't connected to the machine"
 *  while the link is up but the hub's roster has no DE1. While redialing,
 *  "Couldn't verify <hub>" once the re-find gave up ([refindGaveUp]), and
 *  "<hub> isn't a hub any more" when NSD lists its address as a device that
 *  turned Hub mode off ([hubStoppedHosting]). */
internal fun remoteStatusLine(
    hub: String,
    reconnecting: Boolean,
    hubHasMachine: Boolean,
    viewOnly: Boolean = false,
    refindGaveUp: Boolean = false,
    hubStoppedHosting: Boolean = false,
): String =
    when {
        reconnecting && refindGaveUp -> "Couldn't verify $hub"
        reconnecting && hubStoppedHosting -> "$hub isn't a hub any more"
        reconnecting -> "Reconnecting to $hub…"
        !hubHasMachine -> "$hub isn't connected to the machine"
        viewOnly -> "Connected through $hub · view-only"
        else -> "Connected through $hub"
    }

/** The hub this remote connects through, by name: from its Welcome (then its
 *  snapshots), else the address this device dials. */
internal fun MainUiState.remoteHubLabel(): String =
    mirroringPrimaryName.ifBlank { proxyPrimaryHost.ifBlank { "the hub" } }

/** The status line while this device is a remote, else null. Relay v4: a
 *  remote whose keys couldn't be read, or that must pair again after the
 *  upgrade, doesn't dial and says why (spec §6.3, §6.4). */
internal fun MainUiState.remoteStatus(): String? = when {
    proxyRole != "secondary" -> null
    relayKeysUnavailable -> KEYS_UNAVAILABLE_COPY
    relayNeedsRepair -> "Crema updated how devices connect. Pair again with ${remoteHubLabel()}."
    else -> remoteStatusLine(remoteHubLabel(), mirrorReconnecting, remoteHubHasMachine, mirrorViewOnly, refindGaveUp, hubStoppedHosting)
}

/** The phone Brew card's cue beside its readiness badge while this device is
 *  a remote: the same words as the status line, so it never says "Connected
 *  through" while the banner says the hub has no machine. */
internal fun MainUiState.remoteBrewCue(): String? = remoteStatus()?.let { "· $it" }

/** A remote may connect the machine itself when its hub is unreachable, no
 *  longer holds the DE1, or couldn't be verified. */
internal fun MainUiState.canConnectDirectly(): Boolean =
    proxyRole == "secondary" && (mirrorReconnecting || !remoteHubHasMachine || refindGaveUp)

/** Why Hub mode can't be turned on here, or null: this device is a remote
 *  and its hub, which proved the pairing this device holds, has the DE1. */
internal fun MainUiState.hubModeBlockedReason(): String? =
    if (proxyRole == "secondary" && !mirrorReconnecting && remoteHubHasMachine) {
        "Turn off hub mode on ${remoteHubLabel()} first"
    } else {
        null
    }

/**
 * Whether [peer] is a hub this device has paired with and pinned. NSD
 * advertisements are unauthenticated and carry no host id, so a peer counts
 * only at the address of this device's pinned target ([MainUiState.proxyPrimaryHost])
 * while it holds a secret for the pinned host. Anything else is a device that
 * merely says it's a hub.
 *
 * The binding is by address only, and the name shown is the advertiser's own.
 * A device that later gets the old hub's address (DHCP reuse), or one at an
 * address typed into the manual Hub host field while a pin for another host
 * remains, is named too. That is accepted because this only words advice:
 * nothing is gated on it, and "Connect through" is the user's pick, which
 * pairs on the hub before anything is trusted.
 */
internal fun MainUiState.isVerifiedHub(peer: Peer): Boolean =
    pairedHubPinned && proxyPrimaryHost.isNotBlank() && peer.host == proxyPrimaryHost

/**
 * The advice on a normal device while a discovered hub says it holds the DE1,
 * or null. Advisory only: an advertisement can't block Hub mode, since any
 * device on the LAN can send one. A verified hub ([isVerifiedHub]) is named;
 * any other is "a device on this network".
 */
internal fun MainUiState.discoveredHubWarning(): String? {
    if (proxyRole != "normal") return null
    val hub = hubHoldingMachine ?: return null
    val who = if (isVerifiedHub(hub)) "${hub.name} is already the hub for this machine" else UNVERIFIED_HUB
    return "$who — turning on Hub mode here will fail to connect while it holds the machine"
}

/** The Hub mode switch's subtitle. [device] is "tablet" or "phone". */
internal fun MainUiState.hubModeSubtitle(device: String): String {
    hubModeBlockedReason()?.let { return it }
    discoveredHubWarning()?.let { return it }
    return if (proxyRole == "primary") {
        "This $device will disconnect from the machine so another device can connect."
    } else {
        "Hold the machine on this $device so other devices can connect through it."
    }
}

/** The DE1 connect surfaces on a normal device while a discovered hub says it
 *  holds the machine: named for a verified hub, unverified otherwise. */
internal fun turnOffHubToConnect(hub: Peer, verified: Boolean): String =
    if (verified) {
        "Turn off hub mode on ${hub.name} to connect this device"
    } else {
        "$UNVERIFIED_HUB (“${hub.name}”, unverified) — turn off hub mode there to connect this device"
    }

/** [turnOffHubToConnect] for this state's [MainUiState.hubHoldingMachine], or null. */
internal fun MainUiState.turnOffHubCopy(): String? = hubHoldingMachine?.let { turnOffHubToConnect(it, isVerifiedHub(it)) }

/** The action that makes this device a remote of [hub]. An unverified hub
 *  isn't named: connecting to it asks to pair first. */
internal fun connectThroughLabel(hub: Peer, verified: Boolean): String =
    if (verified) "Connect through ${hub.name}" else "Connect through it"

private const val UNVERIFIED_HUB = "A device on this network says it's the hub"

package coffee.crema.ui

import coffee.crema.ble.proxy.Peer

/*
 * The words and enablement of the multi-device surfaces (hub, remote,
 * normal), kept pure so both shells say the same thing and the tests can pin
 * them without composing a screen. Users see "hub" and "remote"; the code's
 * roles stay `primary` and `secondary`.
 */

/** The remote's status line: "Connecting through <hub>…" until the first
 *  Welcome since the pick ([connecting]), "Connected through <hub>",
 *  "Reconnecting to <hub>…" while its link redials, or "<hub> isn't
 *  connected to the machine" while the link is up but the hub's roster has no
 *  DE1. While redialing, "Couldn't verify <hub>…" once the re-find gave up
 *  ([refindGaveUp]), and "<hub> isn't a hub any more" when NSD lists its
 *  address as a device that turned Hub mode off ([hubStoppedHosting]). */
internal fun remoteStatusLine(
    hub: String,
    reconnecting: Boolean,
    hubHasMachine: Boolean,
    viewOnly: Boolean = false,
    refindGaveUp: Boolean = false,
    hubStoppedHosting: Boolean = false,
    connecting: Boolean = false,
): String =
    when {
        reconnecting && refindGaveUp -> hubUnverified(hub)
        reconnecting && hubStoppedHosting -> "$hub isn't a hub any more"
        connecting -> "Connecting through $hub…"
        reconnecting -> "Reconnecting to $hub…"
        !hubHasMachine -> "$hub isn't connected to the machine"
        viewOnly -> "Connected through $hub · view-only"
        else -> "Connected through $hub"
    }

/** The hub this remote connects through, by name: from its Welcome (then its
 *  snapshots), else the name it had when this device paired with the hub at
 *  the address it dials, else that address. */
internal fun MainUiState.remoteHubLabel(): String =
    mirroringPrimaryName.ifBlank {
        pairedHubs.firstOrNull { it.host == proxyPrimaryHost }?.name ?: proxyPrimaryHost.ifBlank { "the hub" }
    }

/** The status line while this device is a remote, else null. Relay v4: a
 *  remote whose keys couldn't be read, or that must pair again after the
 *  upgrade, doesn't dial and says why (spec §6.3, §6.4). */
internal fun MainUiState.remoteStatus(): String? = when {
    proxyRole != "secondary" -> null
    relayKeysUnavailable -> KEYS_UNAVAILABLE_COPY
    relayNeedsRepair -> upgradeRemote(remoteHubLabel())
    else -> remoteStatusLine(remoteHubLabel(), mirrorReconnecting, remoteHubHasMachine, mirrorViewOnly, refindGaveUp, hubStoppedHosting, remoteConnecting)
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
 * Whether [peer] is a hub this device has paired with: its advertised `kid`
 * starts one of this device's hub pins' ids, or, with no `kid`, its address
 * is a pinned hub's. NSD advertisements are unauthenticated, so this only
 * words the picker and the advice ("Paired", the hub's name). Nothing is
 * trusted on it: a pick dials with the pin's key, and only that hub's
 * handshake gets through (spec §6.1).
 */
internal fun MainUiState.isVerifiedHub(peer: Peer): Boolean {
    val kid = peer.kid
    return pairedHubs.any { pin -> if (kid != null) pin.id.startsWith(kid) else pin.host == peer.host }
}

/** A hub's picker line: "Paired", or unverified, and "Needs a Crema update"
 *  for one on the old protocol, which can't be picked (spec §4.8, §6.1). */
internal fun MainUiState.pickerLine(peer: Peer): String = when {
    !peer.upToDate -> "Needs a Crema update · ${peer.host}"
    isVerifiedHub(peer) -> "Paired · holds the DE1 · ${peer.host}"
    else -> "Says it's a hub · unverified · ${peer.host}"
}

/** A remote offers Pair again when it must pair before it dials: after the
 *  upgrade, or once its hub couldn't be verified (spec §6.3, §6.4). */
internal fun MainUiState.canPairAgain(): Boolean =
    proxyRole == "secondary" && !relayKeysUnavailable && (relayNeedsRepair || (mirrorReconnecting && refindGaveUp))

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

// ── Relay v4 pairing and trust (spec §6). Every pairing string both shells
// show lives here, pinned by MultiDeviceCopyTest. ‹hub› and ‹remote› are the
// paired or advertised names. ─────────────────────────────────────────────

/** The core's refusal names the controller acts on (PROTOCOL.md §10). */
internal const val REFUSED_REVOKED = "Revoked"
internal const val REFUSED_NOT_PAIRED = "NotPaired"
internal const val REFUSED_HUB_STOPPED = "HubStopped"

/** §6.1: how many automatic attempts one tap runs. */
internal const val PAIR_ATTEMPTS = coffee.crema.ble.proxy.PairingDialer.MAX_PAIR_ATTEMPTS

/** What a remote shows when its hub refuses pairing or the session (spec
 *  §6.1's advisory table, §6.3), from the core's reason. */
internal fun refusalMessage(reason: String, hub: String): String {
    val name = hub.ifBlank { "The hub" }
    return when (reason) {
        REFUSED_REVOKED, REFUSED_NOT_PAIRED -> "$name removed this device. Pair again to reconnect."
        REFUSED_HUB_STOPPED -> "$name turned off Hub mode."
        "HubUnverified", "HostMismatch" -> hubUnverified(name)
        "HubOutdated" -> olderCrema(name)
        "Version", "VERSION" -> "Update Crema on both devices to connect."
        "PairingClosed", "PAIRING_CLOSED" ->
            "$name isn't accepting new devices. On $name, open Settings ▸ Advanced ▸ Paired devices ▸ Pair a device, then try again."
        "Busy", "BUSY" -> "$name is pairing another device. Try again in a moment."
        "LockedOut", "LOCKED_OUT" -> "$name paused pairing after too many wrong codes. Try again in a minute."
        "Cancelled", "CANCELLED" -> "Pairing was cancelled on $name."
        "Timeout", "TIMEOUT" -> "The code expired. Try again."
        coffee.crema.ble.proxy.PairingDialer.UNREACHABLE -> "Couldn't reach $name. Check that it's on and on this network, then try again."
        else -> "Reconnecting to $name…"
    }
}

/** §6.3: a hub that couldn't prove the pinned key (after the re-find gave up). */
internal fun hubUnverified(hub: String): String =
    "Couldn't verify $hub. If Crema on $hub was reset or reinstalled, pair again."

/** §6.1, §6.3: a peer on the old protocol. */
internal fun olderCrema(name: String): String = "$name runs an older Crema — update it to connect."

/** §6.3: the keys couldn't be read this start. */
internal const val KEYS_UNAVAILABLE_COPY = "Pairing keys couldn't be read. Restart Crema to try again."

/** §6.3: the key was lost for good and replaced (shown once). */
internal const val KEY_RESET_COPY = "This device's pairing key was reset (for example after a restore). Pair it with your devices again."

/** §6.3: the hub's key changed since the last pairing, and this device paired again. */
internal fun hubKeyChanged(hub: String): String = "$hub's pairing key changed since you last paired. It's paired again now."

/** §6.4: the upgrade, on a remote (no dial until it pairs again). */
internal fun upgradeRemote(saved: String): String = "Crema updated how devices connect. Pair again with $saved."

/** §6.4: the upgrade, on a hub (a one-time banner in Paired devices). */
internal const val UPGRADE_HUB_COPY = "Crema updated how devices connect. Pair your phones again: Pair a device."

// §6.1, the remote's sheet.

internal fun pairingWaitingTitle(hub: String): String = "Pairing with $hub…"
internal fun pairingCodeTitle(hub: String): String = "Enter this code on $hub"
internal fun pairingRetryLine(hub: String): String = "That code didn't work. Here's a new one: enter it on $hub."
internal fun pairingFailedLine(hub: String): String =
    "Pairing with $hub didn't work. Make sure you're entering the code on $hub itself, then try again."
internal fun pairingAttemptLine(attempt: Int): String = "Attempt $attempt of $PAIR_ATTEMPTS"
internal fun pairedWith(hub: String): String = "Paired with $hub"

/** "Expires in 1:52". */
internal fun expiresIn(seconds: Long): String = "Expires in ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** The code as the sheet shows it: `482 913`. */
internal fun groupedCode(code: String): String = code.take(3) + " " + code.drop(3)

/** What TalkBack reads for the code: digit by digit, in its two groups ("4 8 2, 9 1 3"). */
internal fun spokenCode(code: String): String =
    code.take(3).toList().joinToString(" ") + ", " + code.drop(3).toList().joinToString(" ")

// §6.2, the hub's window and dialog.

internal fun pairRemoteTitle(remote: String): String = "Pair $remote?"
internal fun enterCodeLine(remote: String): String = "Enter the 6-digit code shown on $remote."
internal fun wrongCodeLine(remote: String, nextAttempt: Int): String =
    "That code didn't match. $remote is showing a new code — enter that one. ($nextAttempt of $PAIR_ATTEMPTS)"
internal const val LOCKED_OUT_COPY = "Too many wrong codes. Pairing is paused for a minute."
internal const val NOT_SAVED_COPY = "Pairing couldn't be saved on this device. Try again."
internal fun pairedRemote(remote: String, canControl: Boolean): String = "Paired $remote (${scopeLabel(canControl)})"
internal fun scopeLabel(canControl: Boolean): String = if (canControl) "View and control" else "View only"

/** "1:58". */
internal fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** The Pair a device row's title: closed, open with its countdown, or paused after three wrong codes. */
internal fun pairWindowTitle(openSeconds: Int, lockedSeconds: Int): String = when {
    lockedSeconds > 0 -> "Pairing paused · ${clock(lockedSeconds)}"
    openSeconds > 0 -> "Pairing open · ${clock(openSeconds)}"
    else -> "Pair a device"
}

/** The Pair a device row's line. [thisHub] is this device's name. */
internal fun pairWindowLine(openSeconds: Int, lockedSeconds: Int, thisHub: String): String = when {
    lockedSeconds > 0 -> LOCKED_OUT_COPY
    openSeconds > 0 -> "On your phone, open Other devices and choose $thisHub."
    else -> "Lets a new phone or tablet pair for the next 2 minutes."
}

// §6.5, forgetting and the key.

internal fun forgetRemoteTitle(remote: String): String = "Forget $remote?"
internal const val FORGET_REMOTE_BODY = "It will be disconnected now and has to pair again."
internal fun forgetHubTitle(hub: String): String = "Forget $hub?"
internal const val FORGET_HUB_BODY = "This device will stop connecting through it until you pair again."
internal const val RESET_KEY_TITLE = "Reset this device's pairing key?"
internal const val RESET_KEY_BODY = "Every paired device has to pair with it again."

/** "Last connected 5 minutes ago"; "Never connected" before the first session. */
internal fun lastConnected(lastSeenMs: Long, nowMs: Long): String {
    if (lastSeenMs <= 0L) return "Never connected"
    val minutes = ((nowMs - lastSeenMs).coerceAtLeast(0L) / 60_000L)
    val ago = when {
        minutes < 1 -> "just now"
        minutes < 60 -> if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        minutes < 60 * 24 -> (minutes / 60).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
        else -> (minutes / (60 * 24)).let { if (it == 1L) "yesterday" else "$it days ago" }
    }
    return "Last connected $ago"
}

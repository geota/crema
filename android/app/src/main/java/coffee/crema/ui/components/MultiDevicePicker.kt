package coffee.crema.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.proxy.Peer
import coffee.crema.ui.MainUiState
import coffee.crema.ui.connectThroughLabel
import coffee.crema.ui.turnOffHubCopy
import coffee.crema.ui.canConnectDirectly
import coffee.crema.ui.discoveredHubWarning
import coffee.crema.ui.hubModeBlockedReason
import coffee.crema.ui.canPairAgain
import coffee.crema.ui.isVerifiedHub
import coffee.crema.ui.pickerLine
import coffee.crema.ui.hubModeSubtitle
import coffee.crema.ui.remoteHubLabel
import coffee.crema.ui.remoteStatus

/* ── Multi-device: connect through a hub on the LAN (M2 / issue 13) ─────────
 * Shared, role-agnostic picker used by BOTH shells — the phone's Devices sheet
 * (PhoneDevicesSheet) and the tablet's Settings. On a normal device it lists
 * NSD-discovered hubs that hold the DE1 (plus the manual peer) → "Connect
 * through"; on a remote it shows the status line with Stop, and Connect
 * directly once the hub is unreachable or no longer holds the DE1; on the hub
 * it lists the connected remotes, read-only. The live switch is no-restart
 * (the caller wires switchToSecondary / switchToNormal / connectDirectly). */

@Composable
fun MultiDeviceSection(
    ui: MainUiState,
    onMirrorFrom: (host: String, port: Int) -> Unit,
    onStopMirroring: () -> Unit,
    onConnectDirectly: () -> Unit,
    /** A remote's Pair again (spec §6.3, §6.4): pairs with the saved target. */
    onPairAgain: () -> Unit,
    // The phone embeds this at the bottom of its Devices sheet, so it draws its own
    // "Other devices" header; the tablet hosts it under a Settings group title that
    // already serves as the header, so it suppresses this one (issue 13).
    showHeader: Boolean = true,
) {
    if (showHeader) {
        Spacer(Modifier.height(18.dp))
        Text(
            "Other devices",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
    }

    // Hub and remotes are still experimental and unvalidated on real
    // hardware — flag it alpha wherever these controls surface (Settings + the
    // Devices sheet, phone + tablet), shown regardless of [showHeader].
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CremaSettingsPill("Alpha")
        Text(
            "Experimental — still being validated on hardware.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))

    // Hub: we hold the DE1 → list the remotes connected through us, read-only.
    // Pairing and Forget live in Settings ▸ Advanced ▸ Paired devices.
    if (ui.proxyRole == "primary") {
        if (ui.remotes.isEmpty()) {
            Text(
                "No remotes are connected. To add one, open Settings ▸ Advanced ▸ Paired devices ▸ Pair a device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "Remotes connected",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.remotes.forEach { remote ->
                RemoteRow(name = remote.clientName, viewOnly = !remote.canControl)
            }
        }
        return
    }

    // A remote: the status line, Stop, and Connect directly when the hub
    // can't give us the machine.
    if (ui.proxyRole == "secondary") {
        MirrorRow(
            title = ui.remoteStatus().orEmpty(),
            sub = if (ui.canConnectDirectly()) {
                "Connect directly to use the machine from this device."
            } else {
                "Showing the machine's live shots from ${ui.remoteHubLabel()}."
            },
            action = "Stop",
            onClick = onStopMirroring,
        )
        if (ui.canPairAgain()) {
            MirrorRow(
                title = "Pair again",
                sub = "Pair this device with ${ui.remoteHubLabel()} again: you'll type a code on ${ui.remoteHubLabel()}.",
                action = "Pair again",
                onClick = onPairAgain,
            )
        }
        if (ui.canConnectDirectly()) {
            MirrorRow(
                title = "Connect directly",
                sub = "Stop using ${ui.remoteHubLabel()} and connect the machine here.",
                action = "Connect",
                onClick = onConnectDirectly,
            )
        }
        return
    }

    // NSD-discovered hubs holding the DE1, plus the manual peer (for
    // emulators / when NSD can't run). A hub on the old protocol is listed
    // but can't be picked: it needs a Crema update.
    val sources = ui.peers.filter { it.role == "primary" && it.holdsDe1 && it.port > 0 }
    val manualHost = ui.proxyPrimaryHost.takeIf { it.isNotBlank() }
    if (sources.isEmpty() && manualHost == null) {
        Text(
            "No other Crema devices found on the network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    sources.forEach { peer ->
        // An advertisement is unauthenticated: only a paired hub is called
        // one. Connecting to any other asks to pair first.
        MirrorRow(
            title = peer.name,
            sub = ui.pickerLine(peer),
            action = "Connect through",
            onClick = { onMirrorFrom(peer.host, peer.port) },
            enabled = peer.upToDate,
        )
    }
    manualHost?.let { host ->
        MirrorRow(
            title = "Manual hub",
            sub = "$host:${ui.proxyPrimaryPort}",
            action = "Connect through",
            onClick = { onMirrorFrom(host, ui.proxyPrimaryPort) },
        )
    }
}

/**
 * The Hub mode switch (Settings ▸ Multi-device, both shells), applied live.
 * Off frees the machine for another device. Disabled on a remote whose hub
 * holds the DE1. While a discovered hub says it holds the machine, turning
 * it on asks first, with "Turn on anyway": an advertisement can't block it.
 * [device] names this device in the subtitle ("tablet" / "phone").
 * [onHubMode] gets (on, anyway).
 */
@Composable
fun HubModeRow(ui: MainUiState, device: String, onHubMode: (on: Boolean, anyway: Boolean) -> Unit, last: Boolean = false) {
    val blocked = ui.hubModeBlockedReason()
    val warning = ui.discoveredHubWarning()
    var confirming by remember { mutableStateOf(false) }
    CremaSettingsRow("Hub mode", ui.hubModeSubtitle(device), last = last) {
        CremaSwitch(
            checked = ui.proxyRole == "primary",
            onCheckedChange = { on -> if (on && warning != null) confirming = true else onHubMode(on, false) },
            enabled = blocked == null,
        )
    }
    if (confirming && warning != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Turn on Hub mode?") },
            text = { Text(warning) },
            confirmButton = {
                TextButton(onClick = { confirming = false; onHubMode(true, true) }) { Text("Turn on anyway") }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

/**
 * On a normal device while a discovered hub says it holds the DE1
 * ([MainUiState.hubHoldingMachine]): why this device may not get the
 * machine, and "Connect through" to use it from here anyway. Named only for
 * a paired, pinned hub ([isVerifiedHub]). The DE1 scan keeps running, so this
 * device connects once that hub turns hub mode off.
 */
@Composable
fun HubHoldsMachineNotice(ui: MainUiState, onConnectThrough: (host: String, port: Int) -> Unit, modifier: Modifier = Modifier) {
    val hub = ui.hubHoldingMachine ?: return
    val verified = ui.isVerifiedHub(hub)
    Row(
        modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            ui.turnOffHubCopy().orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = { onConnectThrough(hub.host, hub.port) }, shape = RoundedCornerShape(999.dp)) {
            Text(
                connectThroughLabel(hub, verified),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/**
 * The tablet's app-wide remote status (above every screen while this device
 * is a remote): the same line as the phone's banner, as a chip. [onClick]
 * opens Settings ▸ Multi-device.
 */
@Composable
fun RemoteStatusChip(status: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(999.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhIcon("bluetooth", sizeDp = 15)
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                PhIcon("caret-right", sizeDp = 14)
            }
        }
    }
}

/** One remote in the hub's read-only list: its name, and a badge when it
 *  may only view. */
@Composable
private fun RemoteRow(name: String, viewOnly: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhIcon("bluetooth", sizeDp = 20)
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
        )
        if (viewOnly) CremaSettingsPill("View only")
    }
}

@Composable
private fun MirrorRow(title: String, sub: String, action: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhIcon("bluetooth", sizeDp = 20)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(999.dp)) {
            Text(action, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

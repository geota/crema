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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.MainUiState

/* ── Multi-device: mirror another Crema on the LAN (M2 / issue 13) ──────────
 * Shared, role-agnostic picker used by BOTH shells — the phone's Devices sheet
 * (PhoneDevicesSheet) and the tablet's Settings. Lists NSD-discovered hosts that
 * hold the DE1 (plus a debug manual peer) → "Mirror"; once mirroring, offers
 * Stop + (idle-only) Take over. The live switch is no-restart (the caller wires
 * switchToSecondary / switchToNormal / requestHandoff). */

@Composable
fun MultiDeviceSection(
    ui: MainUiState,
    onMirrorFrom: (host: String, port: Int) -> Unit,
    onStopMirroring: () -> Unit,
    onTakeOver: () -> Unit,
    // Primary side (issue 07): push the machine to a specific mirroring secondary.
    onHandOff: (clientId: String) -> Unit = {},
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

    // LAN mirror / hand-off is still experimental and unvalidated on real
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

    // Primary: we hold the DE1 → offer to hand it off to a mirroring device (issue 07).
    if (ui.proxyRole == "primary") {
        if (ui.mirrorClients.isEmpty()) {
            Text(
                "No devices are mirroring this machine.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            ui.mirrorClients.forEach { client ->
                MirrorRow(
                    title = client.clientName,
                    sub = "Mirroring this machine",
                    action = "Hand off",
                    onClick = { onHandOff(client.clientId) },
                )
            }
        }
        return
    }

    // If we're already a mirror, the only action is to stop.
    if (ui.proxyRole == "secondary") {
        MirrorRow(
            title = "Mirroring",
            sub = ui.mirroringPrimaryName.ifBlank { ui.proxyPrimaryHost.ifBlank { "a primary on the LAN" } },
            action = "Stop",
            onClick = onStopMirroring,
        )
        // M3 handoff: pull the DE1 onto this device. Idle-only — the primary
        // refuses mid-shot, so a running extraction is never interrupted.
        MirrorRow(
            title = "Take over the DE1",
            sub = "Hold the machine here (idle only)",
            action = "Take over",
            onClick = onTakeOver,
        )
        return
    }

    // NSD-discovered hosts holding the DE1, plus the debug manual peer (for
    // emulators / when NSD can't run).
    val sources = ui.peers.filter { it.isMirrorSource }
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
        MirrorRow(
            title = peer.name,
            sub = "Holds the DE1 · ${peer.host}",
            action = "Mirror",
            onClick = { onMirrorFrom(peer.host, peer.port) },
        )
    }
    manualHost?.let { host ->
        MirrorRow(
            title = "Debug primary",
            sub = "$host:${ui.proxyPrimaryPort}",
            action = "Mirror",
            onClick = { onMirrorFrom(host, ui.proxyPrimaryPort) },
        )
    }
}

@Composable
private fun MirrorRow(title: String, sub: String, action: String, onClick: () -> Unit) {
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
        OutlinedButton(onClick = onClick, shape = RoundedCornerShape(999.dp)) {
            Text(action, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
        }
    }
}

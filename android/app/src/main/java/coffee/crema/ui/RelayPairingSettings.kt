package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.CremaSettingsRow
import kotlinx.coroutines.delay

/** What the Paired devices rows can do (the view model's relay calls). */
internal class RelayPairingActions(
    val openWindow: () -> Unit,
    val closeWindow: () -> Unit,
    val forgetRemote: (id: String) -> Unit,
    val forgetHub: (id: String) -> Unit,
    /** Connect through a superseded hub pin: the user's explicit pick (§2.10). */
    val connectHub: (id: String) -> Unit,
    val resetKey: () -> Unit,
    val dismissUpgradeBanner: () -> Unit,
)

/**
 * Settings ▸ Advanced ▸ Paired devices, the same rows in both shells (spec
 * §6.2, §6.4, §6.5):
 * - while the keys couldn't be read, the line and the way out (Reset);
 * - on the hub, the one-time upgrade banner, Pair a device with its window
 *   and lockout countdowns, and each paired remote with its scope, last
 *   connection and Forget;
 * - the hubs this device has paired with (on a remote, or a device that was
 *   one), each with its last connection and Forget;
 * - Reset pairing key at the bottom, in every role.
 * Forget and Reset ask first.
 */
@Composable
internal fun RelayPairingRows(ui: MainUiState, actions: RelayPairingActions, stacked: Boolean = false) {
    // "Last connected" moves on while the screen is open.
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000L)
            value = System.currentTimeMillis()
        }
    }
    var forgetRemote by remember { mutableStateOf<PairedDevice?>(null) }
    var forgetHub by remember { mutableStateOf<coffee.crema.ble.proxy.RelayHubPin?>(null) }
    var resetKey by remember { mutableStateOf(false) }

    if (ui.relayKeysUnavailable) {
        CremaSettingsRow(
            "Pairing keys couldn't be read",
            "Restart Crema to try again. If this keeps happening, reset this device's pairing key: every paired device has to pair with it again.",
            last = true,
            stacked = stacked,
        ) {
            CremaButton(onClick = { resetKey = true }, variant = CremaButtonVariant.Text, label = "Reset")
        }
    } else {
        val hub = ui.proxyRole == "primary"
        if (hub && ui.relayUpgradeNotice) {
            CremaSettingsRow("Pair your phones again", UPGRADE_HUB_COPY, stacked = stacked) {
                CremaButton(onClick = actions.dismissUpgradeBanner, variant = CremaButtonVariant.Text, label = "Dismiss")
            }
        }
        if (hub) {
            val open = ui.pairingSecondsLeft
            val locked = ui.pairingLockedSecondsLeft
            CremaSettingsRow(pairWindowTitle(open, locked), pairWindowLine(open, locked, deviceLabel()), stacked = stacked) {
                CremaButton(
                    onClick = { if (open > 0) actions.closeWindow() else actions.openWindow() },
                    variant = CremaButtonVariant.Text,
                    enabled = locked == 0,
                    label = if (open > 0) "Stop" else "Pair",
                )
            }
            if (ui.pairedDevices.isEmpty()) {
                CremaSettingsRow("No paired devices", "Phones and tablets you pair with this hub appear here.", stacked = stacked)
            }
            ui.pairedDevices.forEach { d ->
                CremaSettingsRow(d.name, "${scopeLabel(d.canControl)} · ${lastConnected(d.lastSeenMs, nowMs)}", stacked = stacked) {
                    CremaButton(onClick = { forgetRemote = d }, variant = CremaButtonVariant.Text, label = "Forget")
                }
            }
        }
        ui.pairedHubs.forEach { h ->
            val line = if (h.superseded) lastSeenReplaced(h.lastSeenMs, nowMs) else "Paired hub · ${lastConnected(h.lastSeenMs, nowMs)}"
            CremaSettingsRow(h.name, line, stacked = stacked) {
                androidx.compose.foundation.layout.Row {
                    if (h.superseded) CremaButton(onClick = { actions.connectHub(h.id) }, variant = CremaButtonVariant.Text, label = "Connect")
                    CremaButton(onClick = { forgetHub = h }, variant = CremaButtonVariant.Text, label = "Forget")
                }
            }
        }
        if (!hub && ui.pairedHubs.isEmpty()) {
            CremaSettingsRow("No paired hubs", "Hubs this device connects through appear here once paired.", stacked = stacked)
        }
        CremaSettingsRow("Reset pairing key", RESET_KEY_BODY, last = true, stacked = stacked) {
            CremaButton(onClick = { resetKey = true }, variant = CremaButtonVariant.Text, danger = true, label = "Reset")
        }
    }

    forgetRemote?.let { d ->
        CremaConfirmDialog(
            title = forgetRemoteTitle(d.name),
            body = FORGET_REMOTE_BODY,
            confirmLabel = "Forget",
            danger = true,
            onConfirm = { forgetRemote = null; actions.forgetRemote(d.id) },
            onDismiss = { forgetRemote = null },
        )
    }
    forgetHub?.let { h ->
        CremaConfirmDialog(
            title = forgetHubTitle(h.name),
            body = FORGET_HUB_BODY,
            confirmLabel = "Forget",
            danger = true,
            onConfirm = { forgetHub = null; actions.forgetHub(h.id) },
            onDismiss = { forgetHub = null },
        )
    }
    if (resetKey) {
        CremaConfirmDialog(
            title = RESET_KEY_TITLE,
            body = RESET_KEY_BODY,
            confirmLabel = "Reset",
            danger = true,
            onConfirm = { resetKey = false; actions.resetKey() },
            onDismiss = { resetKey = false },
        )
    }
}

package coffee.crema.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.core.GrinderReading
import coffee.crema.core.GrinderState
import coffee.crema.core.PresetSlot
import coffee.crema.ui.GrinderLinkAction
import coffee.crema.ui.GrinderLinkStatus
import coffee.crema.ui.LiveReadouts
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaSettingsPill
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.grinderLinkAction
import coffee.crema.ui.grinderModelName
import coffee.crema.ui.linkedGrinder

/*
 * Devices → Grinder → "Connected grinder" (SPEC grinder-ble §7): Connect /
 * Disconnect / Forget, the link status, the live readout (gap, speeds, state,
 * humidity, lifetime grinds), firmware, and which owned grinder it is
 * ("Linked to …", or a choice, or "Add … to your grinders"). Marked Beta: no
 * one has run it against a real Motto 80 yet. Read-only: nothing here writes
 * to the grinder.
 */

/** The card's one-line status. */
internal fun grinderStatusText(status: GrinderLinkStatus, modelName: String): String = when (status) {
    GrinderLinkStatus.OFF -> "Not connected"
    GrinderLinkStatus.SCANNING -> "Looking for your $modelName…"
    GrinderLinkStatus.CONNECTING -> "Connecting…"
    GrinderLinkStatus.WAITING -> "Connected · waiting for a reading"
    GrinderLinkStatus.LIVE -> "Live"
    GrinderLinkStatus.RECONNECTING -> "Reconnecting…"
    GrinderLinkStatus.NOT_FOUND -> "Not found"
    GrinderLinkStatus.NO_ANSWER -> "Not answering"
    GrinderLinkStatus.NO_DATA -> "Not sending data"
    GrinderLinkStatus.UPDATE_FIRMWARE -> "Update firmware"
    GrinderLinkStatus.MISMATCH -> "Not a supported grinder"
}

/** A reading's state, in words. */
internal fun grinderStateText(state: GrinderState): String = when (state) {
    GrinderState.Idle -> "Idle"
    GrinderState.Grinding -> "Grinding"
    GrinderState.Purging -> "Purging"
    GrinderState.Busy -> "Busy"
    GrinderState.Updating -> "Updating firmware…"
    GrinderState.Warning -> "Warning"
    GrinderState.Unknown -> "Unknown"
}

/** The readout lines for a reading ("480 μm", "700 rpm" …), for display. */
internal fun grinderReadout(r: GrinderReading, unit: String?): List<Pair<String, String>> = buildList {
    add("State" to grinderStateText(r.state))
    r.setting?.let { add("Setting" to listOfNotNull(it.value, unit?.takeIf { u -> u.isNotEmpty() }).joinToString(" ")) }
    r.grind_rpm?.let { add("Speed" to "$it rpm") }
    r.feed_rpm?.let { add("Feeder" to "$it rpm") }
    r.preset?.let { add("Mode" to if (it is PresetSlot.Slot) "Preset ${it.content.index.toInt() + 1}" else "Manual") }
    r.humidity_pct?.let { add("Humidity" to "$it%") }
    r.total_grinds?.let { add("Lifetime grinds" to "$it") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConnectedGrinderCard(vm: MainViewModel, ui: MainUiState, modifier: Modifier = Modifier) {
    val g = ui.connectedGrinder
    val modelName = grinderModelName(g.model ?: coffee.crema.ui.GrinderController.GRINDER_MODEL_FALLBACK)
    val remote = ui.proxyRole == "secondary"
    val action = grinderLinkAction(g.status, ui.connectedGrinderAddress)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Connected grinder", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                CremaSettingsPill("Beta")
            }
            if (remote) {
                Text(
                    "The hub connects the grinder. Connect it there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                "Not tested on a real grinder yet. Have a Motto 80? Please open an issue on GitHub; a debug capture from it would help.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                grinderStatusText(g.status, modelName) +
                    (g.firmware?.let { " · firmware $it" } ?: "") +
                    (g.serialTail?.let { " · serial …$it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
            )
            g.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (g.status == GrinderLinkStatus.LIVE) {
                LiveReadouts(vm.live) { live ->
                    live.grinderReading?.let { r ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            grinderReadout(r, g.unit).forEach { (label, value) ->
                                Row {
                                    Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                                    Text(value, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            g.lastGrind?.let { s ->
                Text(
                    "Last grind: %.1f s".format(s.duration_ms.toLong() / 1000.0) +
                        (s.setting?.let { " at " + listOfNotNull(it.value, g.unit?.takeIf { u -> u.isNotEmpty() }).joinToString(" ") } ?: "") +
                        (s.grind_rpm?.let { " · $it rpm" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Which owned grinder this is (§6.1).
            val linked = ui.linkedGrinder()
            if (g.model != null) {
                when {
                    linked != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Linked to ${linked.name}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        CremaButton(onClick = { vm.linkConnectedGrinder(null) }, variant = CremaButtonVariant.Text, label = "Unlink")
                    }
                    g.linkChoices.isNotEmpty() -> {
                        Eyebrow("Which grinder is this?")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            g.linkChoices.forEach { id ->
                                val name = ui.grinders.firstOrNull { it.id == id }?.name ?: id
                                CremaButton(onClick = { vm.linkConnectedGrinder(id) }, variant = CremaButtonVariant.Outlined, label = name)
                            }
                        }
                    }
                    g.offerAdd -> CremaButton(onClick = { vm.addConnectedGrinder() }, variant = CremaButtonVariant.Tonal, icon = "plus", label = "Add $modelName to your grinders")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (action) {
                    GrinderLinkAction.DISCONNECT -> {
                        CremaButton(onClick = vm::disconnectGrinder, variant = CremaButtonVariant.Text, danger = true, label = "Disconnect")
                        if (g.status == GrinderLinkStatus.RECONNECTING) {
                            CremaButton(onClick = vm::retryGrinder, variant = CremaButtonVariant.Text, label = "Retry now")
                        }
                    }
                    GrinderLinkAction.RECONNECT ->
                        CremaButton(onClick = vm::reconnectGrinder, variant = CremaButtonVariant.Tonal, icon = "bluetooth", label = "Reconnect")
                    GrinderLinkAction.CONNECT ->
                        CremaButton(onClick = vm::connectGrinder, variant = CremaButtonVariant.Tonal, icon = "bluetooth", label = "Connect")
                }
                if (ui.rememberedGrinderAddress != null) {
                    CremaButton(onClick = vm::forgetGrinder, variant = CremaButtonVariant.Text, label = "Forget")
                }
            }
        }
    }
}

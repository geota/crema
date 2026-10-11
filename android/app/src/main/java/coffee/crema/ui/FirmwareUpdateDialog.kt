package coffee.crema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.core.FlashPhase
import coffee.crema.firmware.ALPHA_POINTS
import coffee.crema.firmware.FirmwareFlowUi
import coffee.crema.firmware.FirmwareUpdateController
import coffee.crema.firmware.FlowStep
import coffee.crema.firmware.STILL_SAFE
import coffee.crema.firmware.blockerText
import coffee.crema.firmware.channelLabel
import coffee.crema.firmware.etaText
import coffee.crema.firmware.failureText
import coffee.crema.firmware.phaseLabel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon

/**
 * Settings → Machine → "Update firmware…" (alpha), phone and tablet — web
 * parity with `FirmwareUpdateFlow.svelte`. Alpha warning (click-through) →
 * choose a build → download from Decent and verify → type UPDATE → progress
 * (phase, percent, ETA, Cancel) → power-cycle instructions → the read-back
 * result, or a failure with Retry. State lives in [FirmwareUpdateController];
 * the flash is the core's state machine. Deliberately plain until a design
 * pass. While a flash runs the dialog can't be dismissed.
 */
@Composable
fun FirmwareUpdateHost(flow: FirmwareUpdateController, installedBuild: UInt?) {
    val ui by flow.ui.collectAsStateWithLifecycle()
    if (ui.step == FlowStep.Closed) return
    Dialog(
        onDismissRequest = { flow.close() },
        properties = DialogProperties(
            dismissOnBackPress = !ui.running,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(16.dp).widthIn(max = 560.dp).fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(24.dp).heightIn(max = 640.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Eyebrow("Firmware · alpha", color = MaterialTheme.colorScheme.primary)
                Text(
                    if (ui.step == FlowStep.Flash && ui.status != null) phaseLabel(ui.status!!.phase) else "Update DE1 firmware",
                    style = MaterialTheme.typography.headlineSmall,
                )
                when (ui.step) {
                    FlowStep.Warning -> WarningStep(ui, flow)
                    FlowStep.Choose -> ChooseStep(ui, flow, installedBuild)
                    FlowStep.Source -> SourceStep(ui, flow)
                    FlowStep.Confirm -> ConfirmStep(ui, flow, installedBuild)
                    FlowStep.Flash -> FlashStep(ui, flow)
                    FlowStep.Closed -> Unit
                }
            }
        }
    }
}

@Composable
private fun Banner(text: String, warn: Boolean = true) {
    val color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhIcon(if (warn) "warning" else "info", sizeDp = 18, tint = color)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) { content() }
}

@Composable
private fun ColumnScope.WarningStep(ui: FirmwareFlowUi, flow: FirmwareUpdateController) {
    Banner(ALPHA_POINTS.joinToString("\n\n"))
    Row(
        Modifier.clickable { flow.setAcknowledged(!ui.acknowledged) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ui.acknowledged, onCheckedChange = { flow.setAcknowledged(it) })
        Text("I understand", style = MaterialTheme.typography.titleSmall)
    }
    Actions {
        CremaButton(onClick = { flow.close() }, variant = CremaButtonVariant.Text, label = "Cancel")
        CremaButton(onClick = { flow.acceptWarning() }, enabled = ui.acknowledged, label = "Continue")
    }
}

@Composable
private fun ColumnScope.ChooseStep(ui: FirmwareFlowUi, flow: FirmwareUpdateController, installed: UInt?) {
    Text(
        "Installed: ${installed?.let { "build $it" } ?: "not read yet"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ui.artifacts.forEach { a ->
        val chosen = ui.selectedId == a.id
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .border(
                    1.dp,
                    if (chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    RoundedCornerShape(10.dp),
                )
                .clickable { flow.select(a.id) }
                .padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            RadioButton(selected = chosen, onClick = { flow.select(a.id) })
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Build ${a.build} · ${channelLabel(a.channel)}", style = MaterialTheme.typography.titleSmall)
                installed?.let { i ->
                    Text(
                        when {
                            a.build > i -> "Newer than installed"
                            a.build == i -> "Reinstalls the installed build"
                            else -> "Replaces the installed build"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(a.releaseNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Actions {
        CremaButton(onClick = { flow.close() }, variant = CremaButtonVariant.Text, label = "Cancel")
        CremaButton(onClick = { flow.download() }, enabled = ui.selected != null, label = "Download and verify")
    }
}

@Composable
private fun ColumnScope.SourceStep(ui: FirmwareFlowUi, flow: FirmwareUpdateController) {
    val a = ui.selected ?: return
    if (ui.downloading) {
        Text("Downloading build ${a.build} from Decent…", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { ui.downloadProgress }, modifier = Modifier.fillMaxWidth())
    }
    ui.sourceProblem?.let { Banner(it) }
    Actions {
        CremaButton(onClick = { flow.back() }, variant = CremaButtonVariant.Text, label = "Back")
        if (!ui.downloading && ui.sourceProblem != null) {
            CremaButton(onClick = { flow.download() }, label = "Try again")
        }
    }
}

@Composable
private fun ColumnScope.ConfirmStep(ui: FirmwareFlowUi, flow: FirmwareUpdateController, installed: UInt?) {
    val a = ui.selected ?: return
    var typed by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Kv("Installed", installed?.toString() ?: "—")
        Kv("Installing", "${a.build} · ${channelLabel(a.channel)}")
        Kv("Verified", "SHA-256 ${a.sha256.take(12)}…")
        val lo = Math.round(a.chunkCount.toDouble() * 0.03 / 60)
        Kv("Takes about", "$lo–${lo + 10} min")
    }
    if (ui.blockers.isNotEmpty()) Banner(ui.blockers.joinToString("\n") { "• " + blockerText(it) })
    ui.startProblem?.let { Banner(it) }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        singleLine = true,
        label = { Text("Type “UPDATE” to start") },
        modifier = Modifier.fillMaxWidth(),
    )
    Actions {
        if (ui.blockers.isNotEmpty()) {
            CremaButton(onClick = { flow.refreshBlockers() }, variant = CremaButtonVariant.Text, label = "Check again")
        }
        CremaButton(onClick = { flow.close() }, variant = CremaButtonVariant.Text, label = "Cancel")
        CremaButton(
            onClick = { flow.start() },
            enabled = typed.trim().uppercase() == "UPDATE" && ui.blockers.isEmpty(),
            label = "Update firmware",
        )
    }
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ColumnScope.FlashStep(ui: FirmwareFlowUi, flow: FirmwareUpdateController) {
    val s = ui.status ?: return
    when {
        ui.running -> {
            LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${s.percent.toInt()} %", style = MaterialTheme.typography.bodyMedium)
                Text(etaText(s), style = MaterialTheme.typography.bodyMedium)
            }
            if (s.phase == FlashPhase.Uploading) {
                Text(
                    "${s.chunksAcked} of ${s.chunksTotal} blocks · build ${s.targetBuild}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Banner("Keep Crema open, this device charging and the DE1 nearby. If the update stops, the DE1 keeps its current firmware.", warn = false)
            Actions {
                CremaButton(
                    onClick = { flow.cancel() },
                    variant = CremaButtonVariant.Tonal,
                    enabled = s.canCancel,
                    label = if (s.canCancel) "Cancel" else "Finishing…",
                )
            }
        }
        s.phase == FlashPhase.AwaitingPowerCycle -> {
            Text(
                if (s.verifyUnconfirmed) {
                    "The connection dropped while the DE1 was checking the firmware. Switching it off and on shows which build it runs."
                } else {
                    "The DE1 checked build ${s.targetBuild} and will run it after a restart."
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "1. Switch the DE1 off with the switch on the back.\n2. Wait 10 seconds.\n3. Switch it on. Crema reconnects and reads the build.",
                style = MaterialTheme.typography.bodyMedium,
            )
            val old = s.restartedOnBuild
            if (old != null) {
                Banner("The DE1 restarted but still reports build $old. Switch it off and on once more. If it still reports $old, retry the update.")
            } else if (s.machineRestarted) {
                Text("Waiting for the DE1 to reconnect…", style = MaterialTheme.typography.bodySmall)
            }
            Actions { CremaButton(onClick = { flow.close() }, label = "Close") }
        }
        s.phase == FlashPhase.Succeeded -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhIcon("check-circle", sizeDp = 22, tint = MaterialTheme.colorScheme.primary)
                Text("Your DE1 now runs build ${s.targetBuild}.", style = MaterialTheme.typography.bodyLarge)
            }
            Actions { CremaButton(onClick = { flow.close() }, label = "Done") }
        }
        else -> {
            s.failure?.let { Text(failureText(it), style = MaterialTheme.typography.bodyMedium) }
            Banner(STILL_SAFE, warn = false)
            Actions {
                CremaButton(onClick = { flow.close() }, variant = CremaButtonVariant.Text, label = "Close")
                CremaButton(onClick = { flow.retry() }, label = "Retry")
            }
        }
    }
}

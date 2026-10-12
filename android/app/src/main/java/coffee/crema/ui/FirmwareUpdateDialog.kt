package coffee.crema.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.core.FirmwareBuildStatus
import coffee.crema.core.FlashPhase
import coffee.crema.core.FlashStatus
import coffee.crema.firmware.ALPHA_POINTS
import coffee.crema.firmware.ERASE_SETTLING
import coffee.crema.firmware.EXIT_QUESTION
import coffee.crema.firmware.FirmwareFlowUi
import coffee.crema.firmware.FirmwareUpdateController
import coffee.crema.firmware.FlowStep
import coffee.crema.firmware.STILL_SAFE
import coffee.crema.firmware.UNCONFIRMED
import coffee.crema.firmware.blockerText
import coffee.crema.firmware.cancelQuestion
import coffee.crema.firmware.channelLabel
import coffee.crema.firmware.etaText
import coffee.crema.firmware.failureText
import coffee.crema.firmware.heldBannerText
import coffee.crema.firmware.waitingText
import coffee.crema.firmware.phaseLabel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon

/**
 * Settings → Machine → "Update firmware…" (alpha), phone and tablet — web
 * parity with `FirmwareUpdateFlow.svelte`. Alpha warning (click-through) →
 * choose a build → download from Decent and verify → type UPDATE → progress
 * (phase, percent, ETA, a confirmed Cancel until verify) → switch the DE1 off
 * and on (waiting / reconnect states, Hide, a confirmed Exit) → the read-back
 * result, or a failure with Retry. Every step has one footer row: the way back
 * on the left, the actions on the right. State lives in
 * [FirmwareUpdateController]; the flash is the core's state machine.
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
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                when (ui.step) {
                    FlowStep.Warning -> WarningStep(ui, flow)
                    FlowStep.Choose -> ChooseStep(ui, flow, installedBuild)
                    FlowStep.Source -> SourceStep(ui, flow)
                    FlowStep.Confirm -> ConfirmStep(ui, flow, installedBuild)
                    FlowStep.Flash -> FlashStep(ui, flow, installedBuild)
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

/** One footer row: [left] (the way back) at the start, [right] (the actions) at the end. */
@Composable
private fun Footer(left: @Composable RowScope.() -> Unit = {}, right: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = left)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = right)
    }
}

@Composable
private fun ColumnScope.WarningStep(ui: FirmwareFlowUi, flow: FirmwareUpdateController) {
    Banner(ALPHA_POINTS.joinToString("\n\n"))
    Row(
        Modifier.toggleable(
            value = ui.acknowledged,
            role = Role.Checkbox,
            onValueChange = { flow.setAcknowledged(it) },
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = ui.acknowledged, onCheckedChange = null)
        Text("I understand", style = MaterialTheme.typography.titleSmall)
    }
    Footer(
        left = { CremaButton(onClick = { flow.close() }, variant = CremaButtonVariant.Text, label = "Cancel") },
        right = { CremaButton(onClick = { flow.acceptWarning() }, enabled = ui.acknowledged, label = "Continue") },
    )
}

/** How a pinned build relates to the installed one; `warn` = a downgrade. */
private fun relation(build: UInt, installed: UInt?): Pair<String, Boolean>? {
    installed ?: return null
    val rolledBack = firmwareBuildReport(installed)?.status == FirmwareBuildStatus.RolledBack
    return when {
        rolledBack -> "Replaces the withdrawn installed build" to false
        build > installed -> "Newer than installed" to false
        build == installed -> "Reinstalls the installed build" to false
        else -> "Older than installed build $installed (downgrade)" to true
    }
}

@Composable
private fun ColumnScope.ChooseStep(ui: FirmwareFlowUi, flow: FirmwareUpdateController, installed: UInt?) {
    Text(
        "Installed: ${installed?.let { "build $it" } ?: "not read yet"}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    .selectable(selected = chosen, role = Role.RadioButton, onClick = { flow.select(a.id) })
                    .padding(10.dp),
                verticalAlignment = Alignment.Top,
            ) {
                RadioButton(selected = chosen, onClick = null)
                Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Build ${a.build} · ${channelLabel(a.channel)}", style = MaterialTheme.typography.titleSmall)
                    relation(a.build, installed)?.let { (text, warn) ->
                        Text(
                            text,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(a.releaseNotes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    Footer(
        left = { CremaButton(onClick = { flow.close() }, variant = CremaButtonVariant.Text, label = "Cancel") },
        right = { CremaButton(onClick = { flow.download() }, enabled = ui.selected != null, label = "Download and verify") },
    )
}

@Composable
private fun ColumnScope.SourceStep(ui: FirmwareFlowUi, flow: FirmwareUpdateController) {
    val a = ui.selected ?: return
    if (ui.downloading) {
        Text("Downloading build ${a.build} from Decent…", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { ui.downloadProgress }, modifier = Modifier.fillMaxWidth())
    }
    ui.sourceProblem?.let { Banner(it) }
    Footer(
        left = { CremaButton(onClick = { flow.back() }, variant = CremaButtonVariant.Text, label = "Back") },
        right = {
            if (!ui.downloading && ui.sourceProblem != null) {
                CremaButton(onClick = { flow.download() }, label = "Try again")
            }
        },
    )
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
    if (relation(a.build, installed)?.second == true) Banner("This rolls the DE1 back to an older build.")
    if (ui.blockers.isNotEmpty()) Banner(ui.blockers.joinToString("\n") { "• " + blockerText(it) })
    ui.startProblem?.let { Banner(it) }
    OutlinedTextField(
        value = typed,
        onValueChange = { typed = it },
        singleLine = true,
        label = { Text("Type “UPDATE” to start") },
        modifier = Modifier.fillMaxWidth(),
    )
    Footer(
        left = { CremaButton(onClick = { flow.close() }, variant = CremaButtonVariant.Text, label = "Cancel") },
        right = {
            if (ui.blockers.isNotEmpty()) {
                CremaButton(onClick = { flow.refreshBlockers() }, variant = CremaButtonVariant.Text, label = "Check again")
            }
            CremaButton(
                onClick = { flow.start() },
                enabled = typed.trim().uppercase() == "UPDATE" && ui.blockers.isEmpty(),
                label = "Update firmware",
            )
        },
    )
}

@Composable
private fun Kv(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(v, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ColumnScope.FlashStep(ui: FirmwareFlowUi, flow: FirmwareUpdateController, installed: UInt?) {
    val s = ui.status ?: return
    when {
        ui.running -> {
            LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
            Row(
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
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
            if (ui.confirmingCancel) {
                Banner(cancelQuestion(installed))
            } else {
                Banner("Keep Crema open, this device charging and the DE1 nearby.", warn = false)
            }
            Footer(
                right = {
                    if (ui.confirmingCancel) {
                        CremaButton(onClick = { flow.keepUpdating() }, variant = CremaButtonVariant.Tonal, label = "Keep updating")
                        CremaButton(onClick = { flow.confirmCancel() }, danger = true, variant = CremaButtonVariant.Text, label = "Stop")
                    } else {
                        CremaButton(
                            onClick = { flow.requestCancel() },
                            variant = CremaButtonVariant.Tonal,
                            enabled = s.canCancel,
                            label = if (s.canCancel) "Cancel" else "Checking — can’t stop now",
                        )
                    }
                },
            )
        }
        s.phase == FlashPhase.AwaitingPowerCycle -> {
            Text(
                if (s.verifyUnconfirmed) UNCONFIRMED else "The DE1 checked build ${s.targetBuild} and runs it after a restart.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "1. Switch the DE1 off with the switch on the back.\n2. Wait 10 seconds.\n3. Switch it on. Crema reconnects and reads the build.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Banner(waitingText(s), warn = false)
            if (ui.confirmingExit) Banner(EXIT_QUESTION)
            Footer(
                left = {
                    if (ui.confirmingExit) {
                        CremaButton(onClick = { flow.keepWaiting() }, variant = CremaButtonVariant.Tonal, label = "Keep waiting")
                    } else {
                        CremaButton(onClick = { flow.requestExit() }, variant = CremaButtonVariant.Text, label = "Exit…")
                    }
                },
                right = {
                    if (ui.confirmingExit) {
                        CremaButton(onClick = { flow.confirmExit() }, danger = true, variant = CremaButtonVariant.Text, label = "Exit")
                    } else {
                        CremaButton(onClick = { flow.close() }, label = "Hide")
                    }
                },
            )
        }
        s.phase == FlashPhase.Succeeded -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhIcon("check-circle", sizeDp = 22, tint = MaterialTheme.colorScheme.primary)
                Text("Your DE1 now runs build ${s.targetBuild}.", style = MaterialTheme.typography.bodyLarge)
            }
            Footer(right = { CremaButton(onClick = { flow.close() }, label = "Done") })
        }
        else -> {
            s.failure?.let { Text(failureText(it), style = MaterialTheme.typography.bodyMedium) }
            if (s.eraseSettling) Banner(ERASE_SETTLING)
            Banner(STILL_SAFE, warn = false)
            Footer(
                left = {
                    CremaButton(
                        onClick = { flow.close() },
                        variant = CremaButtonVariant.Text,
                        label = if (s.locksWrites) "Hide" else "Close",
                    )
                },
                right = { CremaButton(onClick = { flow.retry() }, enabled = !s.locksWrites, label = "Retry") },
            )
        }
    }
}

/** The app-wide notice while a hidden flash holds the DE1 (see [heldBannerText]). */
@Composable
fun FirmwareHeldBanner(status: FlashStatus?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shadowElevation = 6.dp,
        modifier = modifier
            .widthIn(max = 640.dp)
            .heightIn(min = 48.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhIcon("arrows-clockwise", sizeDp = 18)
            Text(heldBannerText(status), style = MaterialTheme.typography.labelLarge)
            Text("View", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

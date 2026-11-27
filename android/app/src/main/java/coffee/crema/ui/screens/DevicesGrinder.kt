package coffee.crema.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.beans.grinderCatalogue
import coffee.crema.beans.savedGrind
import coffee.crema.beans.formatGrind
import coffee.crema.beans.scaleRefusal
import coffee.crema.core.Grinder
import coffee.crema.ui.GrinderPick
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.activeGrinder
import coffee.crema.ui.archivedGrinders
import coffee.crema.ui.brewGrindView
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.GrinderAddBox
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.liveGrinders
import coffee.crema.ui.linkedGrinder
import coffee.crema.ui.hasBluetoothGrinder
import coffee.crema.beans.newestShotGrind
import coffee.crema.ui.presetLabel
import coffee.crema.ui.remoteAutoSaveNote
import coffee.crema.ui.remoteBagGrindsNote
import coffee.crema.ui.remoteGrindersNote
import coffee.crema.ui.scaleFieldNumber
import coffee.crema.ui.scaleNumber
import coffee.crema.ui.scaleRangeText
import coffee.crema.ui.scaleStepField

/*
 * Devices → Grinder (#488, boards WideDevicesGrinder, WideDevicesAutoSave,
 * PhoneDevicesGrinder): the user's grinders (In use on the active one), Add
 * grinder (the type-or-pick box), the auto-save switch, and for the selected
 * grinder its scale (Lowest / Highest / Step / Unit, Reset to preset), a
 * preview of a value as Brew shows it, its saved grind per bag, Rename,
 * Change preset and Remove (archive; an "Archived" list restores). Shared by
 * the tablet (two columns) and the phone (one).
 */

/** Shots stamped with [g], by id, or by name on rows from before grinder ids. */
private fun shotCount(vm: MainViewModel, g: Grinder): Int = vm.historyRows().count { s ->
    s.grinderId == g.id || (s.grinderId == null && s.grinderModel?.trim()?.equals(g.name.trim(), ignoreCase = true) == true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DevicesGrinderContent(vm: MainViewModel, ui: MainUiState, wide: Boolean, modifier: Modifier = Modifier) {
    val live = ui.liveGrinders
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = live.firstOrNull { it.id == selectedId } ?: ui.activeGrinder()?.takeIf { g -> live.any { it.id == g.id } } ?: live.firstOrNull()
    var adding by remember { mutableStateOf(false) }
    val counts = remember(ui.history.version, live) { live.associate { it.id to shotCount(vm, it) } }

    val list: @Composable (Modifier) -> Unit = { m ->
        Column(m, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectedGrinderCard(vm, ui)
            // "Show in Devices and the rail": only for someone whose grinder
            // connects over Bluetooth; off, the Devices sheet and rail stay as they were.
            if (ui.hasBluetoothGrinder) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Show in Devices and the rail", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Adds a grinder row to the Devices sheet and a grinder dot on the tablet rail. The grinder still connects and fills in the grind either way.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CremaSwitch(ui.grinderInDevices, vm::setGrinderInDevices)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your grinders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                CremaButton(onClick = { adding = !adding }, variant = CremaButtonVariant.Tonal, icon = "plus", label = "Add grinder")
            }
            if (adding) {
                GrinderAddBox(
                    grinders = ui.grinders,
                    excludeIds = live.map { it.id },
                    listWhenBlank = true,
                    onPick = { pick ->
                        vm.resolveGrinderPick(pick)?.let { selectedId = it.id }
                        adding = false
                    },
                )
            }
            // A remote's grinders are its own; Brew reads the hub's (N4).
            ui.remoteGrindersNote()?.let { RemoteNote(it) }
            if (live.isEmpty()) {
                Text(
                    "No grinders yet. Add yours to give Brew its grind range and unit; with none, Brew uses 0–20 in steps of 0.1.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            live.forEach { g ->
                val sel = g.id == selected?.id
                Surface(
                    onClick = { selectedId = g.id },
                    shape = RoundedCornerShape(12.dp),
                    color = if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val n = counts[g.id] ?: 0
                            Text("${scaleRangeText(g.scale)} · $n shot${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (g.id == ui.activeGrinder()?.id) {
                            Text(
                                "IN USE",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            // "Save each shot's grind to its bag" (board WideDevicesAutoSave): personal, per device.
            // A remote never auto-saves: the hub's setting decides, so it's off here (N4).
            val autoSaveRemote = ui.remoteAutoSaveNote()
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Save each shot's grind to its bag", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        autoSaveRemote ?: "Off: grinds are saved only when you tap Save. On: every kept shot also saves its grind for that bag and grinder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CremaSwitch(ui.grindAutoSave == true, vm::setGrindAutoSave, enabled = autoSaveRemote == null)
            }
            val archived = ui.archivedGrinders
            if (archived.isNotEmpty()) {
                Eyebrow("Archived", Modifier.padding(top = 8.dp))
                archived.forEach { g ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Its saved grinds stay on your bags", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        CremaButton(onClick = { vm.restoreGrinder(g.id) }, variant = CremaButtonVariant.Text, label = "Restore")
                    }
                }
            }
        }
    }

    if (wide) {
        Row(modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(380.dp).fillMaxHeight()) {
                list(Modifier.verticalScroll(rememberScrollState()).padding(20.dp))
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp)) {
                    if (selected != null) GrinderSettings(vm, ui, selected, wide = true) else Text("Add a grinder to set its scale.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (selected != null) {
                Column {
                    Text(selected.name, style = MaterialTheme.typography.titleMedium)
                    Text(if (ui.linkedGrinder()?.id == selected.id) "Linked to the connected grinder" else "Set by hand", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            list(Modifier)
            if (selected != null) GrinderSettings(vm, ui, selected, wide = false)
        }
    }
}

/** The selected grinder's scale, preview, saved grinds per bag, and actions. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GrinderSettings(vm: MainViewModel, ui: MainUiState, g: Grinder, wide: Boolean) {
    val entry = remember(g.presetId) { grinderCatalogue.firstOrNull { it.id == g.presetId } }
    // The form, re-seeded when the stored grinder changes.
    var lo by remember(g.id, g.scale) { mutableStateOf(scaleNumber(g.scale.min)) }
    var hi by remember(g.id, g.scale) { mutableStateOf(scaleNumber(g.scale.max)) }
    var step by remember(g.id, g.scale) { mutableStateOf(scaleStepField(g.scale)) }
    var unit by remember(g.id, g.scale) { mutableStateOf(g.scale.unit) }
    var renaming by remember { mutableStateOf(false) }
    var changingPreset by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    fun num(t: String) = scaleFieldNumber(t)
    val refusal = scaleRefusal(num(lo), num(hi), num(step), unit.trim())
    val changed = num(lo) != g.scale.min || num(hi) != g.scale.max || num(step) != g.scale.step || unit.trim() != g.scale.unit
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Grind scale", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(
                when {
                    entry?.scale != null -> presetLabel(entry)
                    else -> "Range not set: check your grinder's dial"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val presetScale = entry?.scale
            if (presetScale != null && presetScale != g.scale) {
                CremaButton(onClick = { vm.saveGrinderEdit(g, g.copy(scale = presetScale)) }, variant = CremaButtonVariant.Text, label = "Reset to preset")
            }
        }
        val fields = listOf(
            Triple("Lowest", lo) { v: String -> lo = v },
            Triple("Highest", hi) { v: String -> hi = v },
            Triple("Step", step) { v: String -> step = v },
        )
        val fieldRow: @Composable (Modifier, String, String, (String) -> Unit, Boolean) -> Unit = { m, label, value, set, numeric ->
            OutlinedTextField(
                value = value,
                onValueChange = set,
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = coffee.crema.ui.theme.JetBrainsMono),
                modifier = m,
            )
        }
        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                fields.forEach { (label, value, set) -> fieldRow(Modifier.weight(1f), label, value, set, true) }
                fieldRow(Modifier.weight(1f), "Unit", unit, { unit = it }, false)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                fieldRow(Modifier.weight(1f), "Lowest", lo, { lo = it }, true)
                fieldRow(Modifier.weight(1f), "Highest", hi, { hi = it }, true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                fieldRow(Modifier.weight(1f), "Step", step, { step = it }, true)
                fieldRow(Modifier.weight(1f), "Unit", unit, { unit = it }, false)
            }
        }
        if (changed) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // On a remote Brew reads the hub's scale, so saving this one doesn't change Brew (N4).
                Text(refusal ?: ui.remoteGrindersNote() ?: "Changes apply to Brew once saved.", style = MaterialTheme.typography.bodySmall, color = if (refusal != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                CremaButton(onClick = { lo = scaleNumber(g.scale.min); hi = scaleNumber(g.scale.max); step = scaleStepField(g.scale); unit = g.scale.unit }, variant = CremaButtonVariant.Text, label = "Cancel")
                CremaButton(
                    onClick = { vm.saveGrinderEdit(g, g.copy(scale = g.scale.copy(min = num(lo)!!, max = num(hi)!!, step = num(step)!!, unit = unit.trim()))) },
                    enabled = refusal == null,
                    label = "Save scale",
                )
            }
        }
        // A preview of a value as Brew shows it.
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("On the Brew screen")
                    // Brew's grind on this grinder when it's the active one and set,
                    // else where Brew's first step from blank goes (the core's rule).
                    val view = ui.brewGrindView()
                    val brewValue = view.value?.takeIf { ui.activeGrinder()?.id == g.id }
                    val shown = brewValue ?: remember(g.scale) { coffee.crema.beans.grindText(coffee.crema.beans.grindStartFromBlank(g.scale), g.scale) }
                    val number = brewValue == null || view.dial != null
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(shown, style = MaterialTheme.typography.displaySmall.copy(fontFamily = coffee.crema.ui.theme.JetBrainsMono))
                        if (number && g.scale.unit.isNotBlank()) Text(g.scale.unit, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = coffee.crema.ui.theme.JetBrainsMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Steps of ${coffee.crema.beans.grindStepText(g.scale)} · tap the number to type it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (wide) Box(Modifier.weight(1f)) { SavedOnBags(vm, ui, g) }
        }
        if (!wide) SavedOnBags(vm, ui, g)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CremaButton(onClick = { renaming = true }, variant = CremaButtonVariant.Outlined, label = "Rename")
            CremaButton(onClick = { changingPreset = true }, variant = CremaButtonVariant.Outlined, label = "Change preset")
            CremaButton(onClick = { removing = true }, variant = CremaButtonVariant.Text, danger = true, label = "Remove grinder")
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(g.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename grinder") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { if (vm.saveGrinderEdit(g, g.copy(name = name.trim()))) renaming = false }, enabled = name.isNotBlank()) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (changingPreset) {
        AlertDialog(
            onDismissRequest = { changingPreset = false },
            title = { Text("Change preset") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Pick the grinder this is. Its verified scale, if any, replaces this one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GrinderAddBox(
                        grinders = emptyList(),
                        allowCreate = false,
                        placeholder = "Search the well-known grinders",
                        onPick = { pick ->
                            if (pick is GrinderPick.Catalogue) {
                                val e = grinderCatalogue.firstOrNull { it.id == pick.id }
                                if (e != null) vm.saveGrinderEdit(g, g.copy(presetId = e.id, scale = e.scale ?: g.scale))
                            }
                            changingPreset = false
                        },
                    )
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { changingPreset = false }) { Text("Cancel") } },
        )
    }
    if (removing) {
        CremaConfirmDialog(
            title = "Remove ${g.name}?",
            body = "It leaves Devices and the pickers. Shots and bags keep it, and you can restore it from Archived.",
            confirmLabel = "Remove",
            danger = true,
            onConfirm = { removing = false; vm.archiveGrinder(g.id) },
            onDismiss = { removing = false },
        )
    }
}

/** "Saved on bags": each bag's saved grind for [g], then bags with only a last shot. */
@Composable
private fun SavedOnBags(vm: MainViewModel, ui: MainUiState, g: Grinder) {
    val bags = ui.beans.filter { it.deletedAt == null }
    val saved = bags.mapNotNull { b -> b.savedGrind(g.id)?.let { b to it } }
    val shotOnly = remember(ui.history.version, bags, g) {
        bags.filter { b -> b.savedGrind(g.id) == null }
            .mapNotNull { b -> newestShotGrind(vm.lastGrindsForBag(b.id), g)?.let { b to it } }
            .take(3)
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Saved on bags")
            ui.remoteBagGrindsNote()?.let { RemoteNote(it) }
            if (saved.isEmpty() && shotOnly.isEmpty()) {
                Text("No grinds saved for it yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            saved.forEach { (b, row) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(b.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.starred) PhIcon("star-fill", sizeDp = 12, tint = MaterialTheme.colorScheme.primary, contentDescription = "this bag's grinder")
                    Text(formatGrind(row.grind, g.scale), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = coffee.crema.ui.theme.JetBrainsMono))
                }
            }
            shotOnly.forEach { (b, last) ->
                Row {
                    Text(b.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("last shot ${formatGrind(last.grind, g.scale)}", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = coffee.crema.ui.theme.JetBrainsMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** What a remote's own-library control doesn't drive (N4): Brew uses the hub's. */
@Composable
internal fun RemoteNote(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
}

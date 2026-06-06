package coffee.crema.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.core.Bean
import coffee.crema.profiles.CremaProfile
import coffee.crema.ui.BrewParams
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon

/*
 * Quick Controls — the Brew header's bottom sheet, modeled on the web shell's
 * QuickSheet. Three parts (top→bottom):
 *  • Favorites strip — pinned profiles + favourite beans, quick-pick the active
 *    one (vm.setActiveProfile / setActiveBean — the web FavoritesStrip).
 *  • Brew params — dose / yield / brew-temp as a TRANSIENT override (web's
 *    BrewParamState): edits don't touch the profile; they're baked into the next
 *    shot's uploaded profile (MainViewModel.startShot) and Reset snaps back.
 *    Save preset clones a NEW custom profile with these values (web savePreset).
 *  • Shot-behaviour + chart-channel toggles (Settings-backed).
 *
 * Deferred (OPTIONAL, not core-blocked): the live mid-shot SAW dial + the
 * steam/water/flush param cards (Android has no per-mode param store; the mode
 * chips run fixed params, like an espresso-first client). The yield override is
 * already covered by the upload-bake above, so no core/FFI work is required.
 */
private val CHART_CHANNELS = listOf(
    "pressure" to "Pressure",
    "flow" to "Flow",
    "headTemp" to "Coffee temp",
    "mixTemp" to "Water temp",
    "weight" to "Weight",
    "weightFlow" to "Weight flow",
    "dispensedVolume" to "Volume",
    "resistance" to "Resistance",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickControlsSheet(
    active: CremaProfile?,
    brewParams: BrewParams?,
    pinnedProfiles: List<CremaProfile>,
    favBeans: List<Bean>,
    activeProfileId: String?,
    activeBeanId: String?,
    autoTare: Boolean,
    stopOnWeight: Boolean,
    steamEco: Boolean,
    channels: Set<String>,
    onSelectProfile: (String) -> Unit,
    onSelectBean: (String) -> Unit,
    onAdjustBrew: (dose: Double, yieldOut: Double, brewTemp: Double) -> Unit,
    onResetBrew: () -> Unit,
    onSavePreset: (String) -> Unit,
    onAutoTare: (Boolean) -> Unit,
    onStopOnWeight: (Boolean) -> Unit,
    onSteamEco: (Boolean) -> Unit,
    onToggleChannel: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Effective values: the transient override if set, else the active profile.
    val dose = brewParams?.dose ?: (active?.dose?.toDouble() ?: 18.0)
    val yieldOut = brewParams?.yieldOut ?: (active?.yieldOut?.toDouble() ?: 36.0)
    val brewTemp = brewParams?.brewTemp ?: (active?.brewTemp?.toDouble() ?: 93.0)

    var showSave by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    // Steam / hot-water / flush params are local (Android has no per-mode store;
    // the mode chips run fixed params today) — the design's 6-up grid still shows them.
    var steamTemp by remember { mutableStateOf(148.0) }
    var hotWaterTemp by remember { mutableStateOf(90.0) }
    var flushTemp by remember { mutableStateOf(91.0) }
    var preFlush by remember { mutableStateOf(false) }
    var steamPurge by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ── Header ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Quick Controls", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (active != null) {
                        CremaButton(onClick = { showSave = true }, variant = CremaButtonVariant.Text, icon = "bookmark-simple", label = "Save preset")
                    }
                    if (brewParams != null) {
                        CremaButton(onClick = onResetBrew, variant = CremaButtonVariant.Text, icon = "arrow-counter-clockwise", label = "Reset")
                    }
                    CremaIconButton(icon = "x", onClick = onDismiss)
                }
            }
            Text(
                "Tweaks apply to the next shot — your saved profile is never changed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ── Favorites strip ──────────────────────────────────────────────
            if (pinnedProfiles.isNotEmpty() || favBeans.isNotEmpty()) {
                Eyebrow("Favorites", Modifier.padding(top = 4.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pinnedProfiles, key = { "p:${it.id}" }) { p ->
                        FilterChip(
                            selected = p.id == activeProfileId,
                            onClick = { onSelectProfile(p.id) },
                            label = { Text(p.name, maxLines = 1) },
                            leadingIcon = { PhIcon("coffee", sizeDp = 16) },
                        )
                    }
                    items(favBeans, key = { "b:${it.id}" }) { b ->
                        FilterChip(
                            selected = b.id == activeBeanId,
                            onClick = { onSelectBean(b.id) },
                            label = { Text(b.name, maxLines = 1) },
                            leadingIcon = { PhIcon("coffee-bean", sizeDp = 16) },
                        )
                    }
                }
            }

            // ── Params — a 6-up grid: brew override (baked into the next shot)
            //    on top, steam / hot-water / flush (local) below. ─────────────
            Eyebrow("Brew · applies to the next shot", Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { CremaStepper(label = "Dose", value = dose, unit = "g", onChange = { onAdjustBrew(it, yieldOut, brewTemp) }, step = 0.1, min = 5.0, max = 30.0) }
                Box(Modifier.weight(1f)) { CremaStepper(label = "Yield", value = yieldOut, unit = "g", onChange = { onAdjustBrew(dose, it, brewTemp) }, step = 0.5, min = 10.0, max = 80.0) }
                Box(Modifier.weight(1f)) { CremaStepper(label = "Brew temp", value = brewTemp, unit = "°C", onChange = { onAdjustBrew(dose, yieldOut, it) }, step = 0.5, min = 80.0, max = 100.0) }
            }
            Eyebrow("Steam · water · flush", Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { CremaStepper(label = "Steam", value = steamTemp, unit = "°C", onChange = { steamTemp = it }, step = 1.0, min = 100.0, max = 160.0, fmt = { "%.0f".format(it) }) }
                Box(Modifier.weight(1f)) { CremaStepper(label = "Hot water", value = hotWaterTemp, unit = "°C", onChange = { hotWaterTemp = it }, step = 1.0, min = 60.0, max = 100.0, fmt = { "%.0f".format(it) }) }
                Box(Modifier.weight(1f)) { CremaStepper(label = "Flush", value = flushTemp, unit = "°C", onChange = { flushTemp = it }, step = 1.0, min = 80.0, max = 100.0, fmt = { "%.0f".format(it) }) }
            }

            // ── Shot behaviour (2-up dense toggles) ──────────────────────────
            Eyebrow("Shot behaviour", Modifier.padding(top = 8.dp))
            TwoCol(
                { ToggleRow("Stop on weight", stopOnWeight, onStopOnWeight) },
                { ToggleRow("Auto-tare", autoTare, onAutoTare) },
            )
            TwoCol(
                { ToggleRow("Pre-flush", preFlush) { preFlush = it } },
                { ToggleRow("Steam purge", steamPurge) { steamPurge = it } },
            )
            TwoCol(
                { ToggleRow("Steam eco", steamEco, onSteamEco) },
                { },
            )

            // ── Chart channels (2-up dense toggles) ──────────────────────────
            Eyebrow("Chart channels", Modifier.padding(top = 12.dp))
            CHART_CHANNELS.chunked(2).forEach { pair ->
                TwoCol(
                    { val (k, l) = pair[0]; ToggleRow(l, k in channels) { onToggleChannel(k, it) } },
                    { if (pair.size > 1) { val (k, l) = pair[1]; ToggleRow(l, k in channels) { onToggleChannel(k, it) } } },
                )
            }
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save as preset") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Preset name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onSavePreset(presetName); showSave = false; presetName = "" },
                    enabled = presetName.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }
}

// Two equal columns — packs the toggle rows two-up instead of a long stack.
@Composable
private fun TwoCol(a: @Composable () -> Unit, b: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { a() }
        Box(Modifier.weight(1f)) { b() }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        CremaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

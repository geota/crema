package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import coffee.crema.ui.components.CremaValueUnit
import coffee.crema.ui.theme.CremaTheme
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
import androidx.compose.ui.unit.sp
import coffee.crema.core.Bean
import coffee.crema.profiles.CremaProfile
import coffee.crema.ui.BrewParams
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaIconButton
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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

            // ── Param steppers — compact cards (PWA qsheet-g-grid). 3-up × 2 rows
            //    (the modal sheet is narrower than the PWA's full-width dock). ────
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QcStepperCard("Dose", "%.1f".format(dose), "g", { onAdjustBrew((dose - 0.1).coerceAtLeast(5.0), yieldOut, brewTemp) }, { onAdjustBrew((dose + 0.1).coerceAtMost(30.0), yieldOut, brewTemp) }, Modifier.weight(1f))
                QcStepperCard("Yield", "%.1f".format(yieldOut), "g", { onAdjustBrew(dose, (yieldOut - 0.5).coerceAtLeast(10.0), brewTemp) }, { onAdjustBrew(dose, (yieldOut + 0.5).coerceAtMost(80.0), brewTemp) }, Modifier.weight(1f))
                QcStepperCard("Brew temp", "%.1f".format(brewTemp), "°C", { onAdjustBrew(dose, yieldOut, (brewTemp - 0.5).coerceAtLeast(80.0)) }, { onAdjustBrew(dose, yieldOut, (brewTemp + 0.5).coerceAtMost(100.0)) }, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QcStepperCard("Steam", "%.0f".format(steamTemp), "°C", { steamTemp = (steamTemp - 1).coerceAtLeast(100.0) }, { steamTemp = (steamTemp + 1).coerceAtMost(160.0) }, Modifier.weight(1f))
                QcStepperCard("Hot water", "%.0f".format(hotWaterTemp), "°C", { hotWaterTemp = (hotWaterTemp - 1).coerceAtLeast(60.0) }, { hotWaterTemp = (hotWaterTemp + 1).coerceAtMost(100.0) }, Modifier.weight(1f))
                QcStepperCard("Flush", "%.0f".format(flushTemp), "°C", { flushTemp = (flushTemp - 1).coerceAtLeast(80.0) }, { flushTemp = (flushTemp + 1).coerceAtMost(100.0) }, Modifier.weight(1f))
            }

            // ── Chart strip — channel groups (icon + primary/secondary mini-toggles,
            //    divider-split), then brew-behaviour mini-toggles. (PWA qsheet-foot.) ─
            val tel = CremaTheme.telemetry
            val groups = listOf(
                ChannelGroup("gauge", tel.pressure, "pressure" to "Pressure", "resistance" to "Resistance"),
                ChannelGroup("drop", tel.flow, "flow" to "Flow", "dispensedVolume" to "Volume"),
                ChannelGroup("thermometer", tel.temp, "headTemp" to "Coffee", "mixTemp" to "Water"),
                ChannelGroup("scales", tel.weight, "weight" to "Weight", "weightFlow" to "Flow"),
            )
            Eyebrow("Chart", Modifier.padding(top = 12.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                groups.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhIcon(g.icon, sizeDp = 14, tint = g.color)
                        QcMiniToggle(g.primary.second, g.primary.first in channels, { onToggleChannel(g.primary.first, it) }, g.color)
                        QcMiniToggle(g.secondary.second, g.secondary.first in channels, { onToggleChannel(g.secondary.first, it) }, g.color)
                    }
                }
            }
            Eyebrow("Shot behaviour", Modifier.padding(top = 8.dp))
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QcMiniToggle("Stop on weight", stopOnWeight, onStopOnWeight)
                QcMiniToggle("Auto-tare", autoTare, onAutoTare)
                QcMiniToggle("Pre-flush", preFlush, { preFlush = it })
                QcMiniToggle("Steam purge", steamPurge, { steamPurge = it })
                QcMiniToggle("Steam eco", steamEco, onSteamEco)
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

// A chart-strip channel group: an icon + two mini-toggles (primary/secondary).
private data class ChannelGroup(
    val icon: String,
    val color: Color,
    val primary: Pair<String, String>,   // key to label
    val secondary: Pair<String, String>,
)

// Compact stepper card (PWA qcs-card): uppercase label over a [− value+unit +]
// row, in a rounded surfaceContainerHigh tile — far lighter than CremaStepper.
@Composable
private fun QcStepperCard(label: String, value: String, unit: String?, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow(label)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            QcStepBtn("minus", onMinus)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { CremaValueUnit(value, unit, valueSize = 18.sp) }
            QcStepBtn("plus", onPlus)
        }
    }
}

@Composable
private fun QcStepBtn(icon: String, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { PhIcon(icon, sizeDp = 14) }
}

// Mini pill toggle (PWA qmini-tog): a tiny switch + label, far lighter than the
// full M3 Switch. `onColor` tints the track when on (channel color, else copper).
@Composable
private fun QcMiniToggle(label: String, on: Boolean, onToggle: (Boolean) -> Unit, onColor: Color = MaterialTheme.colorScheme.primary) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).clickable { onToggle(!on) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier.width(26.dp).height(15.dp).clip(RoundedCornerShape(999.dp))
                .background(if (on) onColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(horizontal = 2.dp).size(11.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

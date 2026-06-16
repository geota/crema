package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.horizontalScroll
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
import coffee.crema.ui.formatRatio
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
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
import androidx.compose.ui.draw.alpha
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaSearchPill
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaStepperStyle
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaDotToggle
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaSplitLabel
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SplitOption

/*
 * Quick Controls — the Brew header's bottom sheet, modeled on the web shell's
 * full-width docked QuickSheet (variant G — one row of six steppers):
 *  • Favorites strip — pinned profiles + favourite beans.
 *  • Six steppers, full width. Each has a header that's either a plain label or a
 *    CremaSplitLabel whose selectable options swap which value the single stepper +
 *    chip row edit (Dose|Grind, Brew temp|pre-infuse, Steam time|flow|temp, Hot
 *    water temp|volume, Flush time|temp). A 5-chip quick-select row sits under each.
 *  • Full-width toggle buttons (shot behaviour) + the chart-channel toggles.
 *
 * dose / yield / brew-temp are a TRANSIENT override (BrewParams via onAdjustBrew);
 * grind / pre-infuse / steam / water / flush are local (Android has no per-mode
 * param store yet — the chips/steppers drive the next shot's fixed params).
 */
private val niceFmt: (Double) -> String = { v -> if (v == kotlin.math.floor(v)) "%.0f".format(v) else "%.1f".format(v) }

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
    preFlush: Boolean,
    steamPurge: Boolean,
    channels: Set<String>,
    // Persisted QC steam / hot-water / flush values (issue 14). These stick
    // across opens and drive the machine on change (steam temp/time + hot-water
    // temp/vol via RMW; steam flow + flush temp/time standalone).
    qcSteamTimeS: Double,
    qcSteamFlowMlS: Double,
    qcSteamTempC: Double,
    qcHotWaterTempC: Double,
    qcHotWaterVolumeMl: Double,
    qcFlushTimeS: Double,
    qcFlushTempC: Double,
    onSelectProfile: (String) -> Unit,
    onSelectBean: (String) -> Unit,
    onAdjustBrew: (dose: Double, yieldOut: Double, brewTemp: Double, preinf: Double?) -> Unit,
    onResetBrew: () -> Unit,
    onSavePreset: (String) -> Unit,
    onAutoTare: (Boolean) -> Unit,
    onStopOnWeight: (Boolean) -> Unit,
    onSteamEco: (Boolean) -> Unit,
    onPreFlush: (Boolean) -> Unit,
    onSteamPurge: (Boolean) -> Unit,
    onSteamTime: (Double) -> Unit,
    onSteamFlow: (Double) -> Unit,
    onSteamTemp: (Double) -> Unit,
    onHotWaterTemp: (Double) -> Unit,
    onHotWaterVolume: (Double) -> Unit,
    onFlushTime: (Double) -> Unit,
    onFlushTemp: (Double) -> Unit,
    // Persisted QC grind click + pre-infuse transient override (issue 15).
    qcGrind: Double?,
    onGrind: (Double) -> Unit,
    onToggleChannel: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Effective brew values: the transient override if set, else the active profile.
    val dose = brewParams?.dose ?: (active?.dose?.toDouble() ?: 18.0)
    val yieldOut = brewParams?.yieldOut ?: (active?.yieldOut?.toDouble() ?: 36.0)
    val brewTemp = brewParams?.brewTemp ?: (active?.brewTemp?.toDouble() ?: 93.0)
    // Pre-infusion is the leading segment's time (no separate machine setting); a
    // transient override caps it, else show the active profile's own (issue 15).
    val preinf = brewParams?.preinf ?: (active?.preinfuseSeconds?.toDouble() ?: 8.0)
    // The current pre-infuse override state (null = not overridden) — preserved
    // when other brew steppers fire so a dose tweak doesn't drop a preinf override.
    val preinfOverride = brewParams?.preinf
    // Grind: persisted QC click (issue 15); show the saved value, else the seed.
    val grind = qcGrind ?: 4.2

    var showSave by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    // Per-stepper mode — which sub-value each combined stepper edits (local view state).
    var doseGrindMode by remember { mutableStateOf("dose") }
    var brewMode by remember { mutableStateOf("temp") }
    // Steam / water / flush *values* are persisted props (issue 14); only which
    // sub-value each stepper edits ("mode") is local view state.
    var steamMode by remember { mutableStateOf("time") }
    var waterMode by remember { mutableStateOf("volume") }
    var flushMode by remember { mutableStateOf("time") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Open fully expanded — the footer toggles sit below a half-height fold.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = 1400.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ── Header ───────────────────────────────────────────────────────
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("Quick Controls", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        "Tweaks apply to the next shot — your saved profile is never changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (active != null) {
                        CremaButton(onClick = { showSave = true }, variant = CremaButtonVariant.Text, icon = "bookmark-simple", label = "Save preset")
                    }
                    // Always visible like the web QuickSheet; enabled only once a
                    // tweak exists so it never reads as a dead control.
                    CremaButton(onClick = onResetBrew, variant = CremaButtonVariant.Text, icon = "arrow-counter-clockwise", label = "Reset", enabled = brewParams != null)
                    CremaIconButton(icon = "x", onClick = onDismiss)
                }
            }

            // ── Favorites strip ──────────────────────────────────────────────
            // Web FavoritesStrip: a type-to-filter search box ahead of the pinned
            // profiles + favourite beans chips.
            if (pinnedProfiles.isNotEmpty() || favBeans.isNotEmpty()) {
                var favQuery by remember { mutableStateOf("") }
                val shownProfiles = pinnedProfiles.filter { favQuery.isBlank() || it.name.contains(favQuery, ignoreCase = true) }
                val shownBeans = favBeans.filter { favQuery.isBlank() || it.name.contains(favQuery, ignoreCase = true) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CremaSearchPill(
                        query = favQuery,
                        onQueryChange = { favQuery = it },
                        placeholder = "Search profiles + beans",
                        modifier = Modifier.width(220.dp),
                        compact = true,
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(shownProfiles, key = { "p:${it.id}" }) { p ->
                            FilterChip(
                                selected = p.id == activeProfileId,
                                onClick = { onSelectProfile(p.id) },
                                label = { Text(p.name, maxLines = 1) },
                                leadingIcon = { PhIcon("coffee", sizeDp = 16) },
                            )
                        }
                        items(shownBeans, key = { "b:${it.id}" }) { b ->
                            FilterChip(
                                selected = b.id == activeBeanId,
                                onClick = { onSelectBean(b.id) },
                                label = { Text(b.name, maxLines = 1) },
                                leadingIcon = { PhIcon("coffee-bean", sizeDp = 16) },
                            )
                        }
                    }
                }
            }

            // ── Six steppers — one full-width row (PWA qsheet-g-grid is-six). ──
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // 1 — Dose | Grind (no prefix).
                CremaStepper(
                    modifier = Modifier.weight(1f),
                    value = if (doseGrindMode == "dose") dose else grind,
                    unit = if (doseGrindMode == "dose") "g" else null,
                    min = if (doseGrindMode == "dose") 5.0 else 0.0,
                    max = if (doseGrindMode == "dose") 30.0 else 20.0,
                    step = 0.1,
                    fmt = niceFmt,
                    chips = if (doseGrindMode == "dose") listOf(16.0, 17.0, 18.0, 19.0, 20.0) else listOf(3.8, 4.0, 4.2, 4.4, 4.6),
                    onChange = { if (doseGrindMode == "dose") onAdjustBrew(it, yieldOut, brewTemp, preinfOverride) else onGrind(it) },
                    style = CremaStepperStyle.Boxed,
                    header = {
                        CremaSplitLabel(prefix = "", options = listOf(SplitOption("dose", "Dose"), SplitOption("grind", "Grind")), value = doseGrindMode, onChange = { doseGrindMode = it })
                    },
                )
                // 2 — Yield (+ live ratio). The dot toggles the weight target
                // (stop-on-weight); the value resolves Quick Controls override →
                // profile (whichever is set). Greys out when the target is off.
                CremaStepper(
                    modifier = Modifier.weight(1f),
                    value = yieldOut, unit = "g", min = 10.0, max = 80.0, step = 0.5,
                    fmt = niceFmt,
                    chips = listOf(28.0, 32.0, 36.0, 40.0, 45.0),
                    enabled = stopOnWeight,
                    onChange = { onAdjustBrew(dose, it, brewTemp, preinfOverride) },
                    style = CremaStepperStyle.Boxed,
                    header = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            CremaDotToggle(stopOnWeight, { onStopOnWeight(!stopOnWeight) })
                            Eyebrow("Yield", Modifier.weight(1f))
                            Text(formatRatio(dose, yieldOut), style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.primary)
                        }
                    },
                )
                // 3 — Brew temp | pre-infuse.
                CremaStepper(
                    modifier = Modifier.weight(1f),
                    value = if (brewMode == "temp") brewTemp else preinf,
                    unit = if (brewMode == "temp") "°C" else "s",
                    min = if (brewMode == "temp") 80.0 else 0.0,
                    max = if (brewMode == "temp") 100.0 else 30.0,
                    step = if (brewMode == "temp") 0.5 else 1.0,
                    fmt = niceFmt,
                    chips = if (brewMode == "temp") listOf(88.0, 91.0, 93.0, 95.0, 97.0) else listOf(0.0, 4.0, 8.0, 12.0, 16.0),
                    onChange = { if (brewMode == "temp") onAdjustBrew(dose, yieldOut, it, preinfOverride) else onAdjustBrew(dose, yieldOut, brewTemp, it) },
                    style = CremaStepperStyle.Boxed,
                    header = {
                        CremaSplitLabel(prefix = "Brew", options = listOf(SplitOption("temp", "Temp"), SplitOption("preinf", "Pre-infuse")), value = brewMode, onChange = { brewMode = it })
                    },
                )
                // 4 — Steam time | flow | temp.
                CremaStepper(
                    modifier = Modifier.weight(1f),
                    value = when (steamMode) { "flow" -> qcSteamFlowMlS; "temp" -> qcSteamTempC; else -> qcSteamTimeS },
                    unit = when (steamMode) { "flow" -> "ml/s"; "temp" -> "°C"; else -> "s" },
                    min = when (steamMode) { "flow" -> 0.2; "temp" -> 120.0; else -> 1.0 },
                    max = when (steamMode) { "flow" -> 3.0; "temp" -> 170.0; else -> 60.0 },
                    step = when (steamMode) { "flow" -> 0.1; "temp" -> 0.5; else -> 1.0 },
                    fmt = niceFmt,
                    chips = when (steamMode) { "flow" -> listOf(0.6, 0.9, 1.2, 1.6, 2.0); "temp" -> listOf(140.0, 145.0, 148.0, 150.0, 155.0); else -> listOf(5.0, 10.0, 15.0, 20.0, 30.0) },
                    onChange = { when (steamMode) { "flow" -> onSteamFlow(it); "temp" -> onSteamTemp(it); else -> onSteamTime(it) } },
                    style = CremaStepperStyle.Boxed,
                    header = {
                        CremaSplitLabel(prefix = "Steam", options = listOf(SplitOption("time", "Time"), SplitOption("flow", "Flow"), SplitOption("temp", "Temp")), value = steamMode, onChange = { steamMode = it })
                    },
                )
                // 5 — Hot water temp | volume.
                CremaStepper(
                    modifier = Modifier.weight(1f),
                    value = if (waterMode == "temp") qcHotWaterTempC else qcHotWaterVolumeMl,
                    unit = if (waterMode == "temp") "°C" else "ml",
                    min = if (waterMode == "temp") 40.0 else 20.0,
                    max = if (waterMode == "temp") 98.0 else 500.0,
                    step = if (waterMode == "temp") 1.0 else 10.0,
                    fmt = niceFmt,
                    chips = if (waterMode == "temp") listOf(60.0, 75.0, 85.0, 92.0, 96.0) else listOf(60.0, 120.0, 180.0, 250.0, 350.0),
                    onChange = { if (waterMode == "temp") onHotWaterTemp(it) else onHotWaterVolume(it) },
                    style = CremaStepperStyle.Boxed,
                    header = {
                        CremaSplitLabel(prefix = "Hot water", options = listOf(SplitOption("temp", "Temp"), SplitOption("volume", "Volume")), value = waterMode, onChange = { waterMode = it })
                    },
                )
                // 6 — Flush time | temp.
                CremaStepper(
                    modifier = Modifier.weight(1f),
                    value = if (flushMode == "time") qcFlushTimeS else qcFlushTempC,
                    unit = if (flushMode == "time") "s" else "°C",
                    min = if (flushMode == "time") 1.0 else 60.0,
                    max = if (flushMode == "time") 20.0 else 100.0,
                    step = if (flushMode == "time") 1.0 else 0.5,
                    fmt = niceFmt,
                    chips = if (flushMode == "time") listOf(2.0, 4.0, 6.0, 8.0, 10.0) else listOf(88.0, 92.0, 95.0, 97.0, 99.0),
                    onChange = { if (flushMode == "time") onFlushTime(it) else onFlushTemp(it) },
                    style = CremaStepperStyle.Boxed,
                    header = {
                        CremaSplitLabel(prefix = "Flush", options = listOf(SplitOption("time", "Time"), SplitOption("temp", "Temp")), value = flushMode, onChange = { flushMode = it })
                    },
                )
            }

            // ── Footer — Chart channel toggles (left) and Shot-behaviour toggles
            //    (right), both small mini-toggles, split by a vertical divider. ──
            val tel = CremaTheme.telemetry
            val groups = listOf(
                ChannelGroup("gauge", tel.pressure, "pressure" to "Pressure", "resistance" to "Resistance"),
                ChannelGroup("drop", tel.flow, "flow" to "Flow", "dispensedVolume" to "Volume"),
                ChannelGroup("thermometer", tel.temp, "headTemp" to "Coffee", "mixTemp" to "Water"),
                ChannelGroup("scales", tel.weight, "weight" to "Weight", "weightFlow" to "Flow"),
            )
            // Chart channels + Shot behaviour, all on one line, divided in the
            // middle (scrolls horizontally only if it can't quite fit).
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Chart")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        groups.forEach { g ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                PhIcon(g.icon, sizeDp = 14, tint = g.color)
                                QcMiniToggle(g.primary.second, g.primary.first in channels, { onToggleChannel(g.primary.first, it) }, g.color)
                                QcMiniToggle(g.secondary.second, g.secondary.first in channels, { onToggleChannel(g.secondary.first, it) }, g.color)
                            }
                        }
                    }
                }
                Box(Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Eyebrow("Behavior")
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                        QcMiniToggle("Stop on weight", stopOnWeight, onStopOnWeight)
                        QcMiniToggle("Auto-tare", autoTare, onAutoTare)
                        QcMiniToggle("Pre-flush", preFlush, onPreFlush)
                        QcMiniToggle("Steam purge", steamPurge, onSteamPurge)
                        QcMiniToggle("Steam eco", steamEco, onSteamEco)
                    }
                }
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

// (QcStepper / QcChip / QcStepBtn removed — routed through CremaStepper with its
// header slot + quick-select chips; see CremaStepperStyle.Boxed.)

// Mini pill toggle (PWA qmini-tog) — a tiny switch + label, for the chart strip.
@Composable
private fun QcMiniToggle(label: String, on: Boolean, onToggle: (Boolean) -> Unit, onColor: Color = MaterialTheme.colorScheme.primary) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).clickable { onToggle(!on) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Box(
            Modifier.width(24.dp).height(14.dp).clip(RoundedCornerShape(999.dp))
                .background(if (on) onColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(horizontal = 2.dp).size(10.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

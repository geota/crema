package coffee.crema.ui.screens

import coffee.crema.ui.fmt
import coffee.crema.settings.Setting
import coffee.crema.ui.components.GateNotes
import coffee.crema.ui.components.RowGate
import coffee.crema.ui.components.gated
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import coffee.crema.ui.BrewDial
import coffee.crema.ui.BrewMode
import coffee.crema.ui.DoseGrindMode
import coffee.crema.ui.FlushMode
import coffee.crema.ui.QcBounds
import coffee.crema.ui.QcSteam
import coffee.crema.ui.SteamMode
import coffee.crema.ui.WaterMode
import coffee.crema.ui.HotWaterHeader
import coffee.crema.ui.hotWaterStepper
import coffee.crema.ui.hotWaterSubLine
import coffee.crema.core.HotWaterDose
import coffee.crema.core.HotWaterTarget
import coffee.crema.ui.formatRatio
import coffee.crema.ui.UNKNOWN_VALUE
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
import coffee.crema.ui.effectiveBrew
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
private val niceFmt: (Double) -> String = { v -> if (v == kotlin.math.floor(v)) fmt("%.0f", v) else fmt("%.1f", v) }

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
    /** The hot-water dose (Time · ml · Ratio) and the core's resolved target
     *  for its readout (the hub's on a remote); the units it reads in. */
    hotWaterDose: HotWaterDose,
    hotWaterTarget: HotWaterTarget?,
    weightUnit: String,
    volumeUnit: String,
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
    onHotWaterDose: (HotWaterDose) -> Unit,
    onFlushTime: (Double) -> Unit,
    onFlushTemp: (Double) -> Unit,
    // Persisted QC grind click + pre-infuse transient override (issue 15).
    qcGrind: Double?,
    /** Dial seed when no QC value is set — the active bean's saved grind
     *  (issue #16: "tweak slightly from last time" must START at last time),
     *  else a generic mid-range default. */
    qcGrindSeed: Double = 4.2,
    onGrind: (Double) -> Unit,
    /** The bean half of the scope-aware Save (issue #16): write the grind
     *  dial back to the active bean; returns true when it wrote. */
    onSaveGrindToBean: () -> Boolean = { false },
    onToggleChannel: (String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** Each machine or session control's gate (spec §7.6): on a remote the
     *  steam, hot-water and flush steppers are read-only. */
    gate: (Setting) -> RowGate = { RowGate.Open },
    /** The active profile's brew-temperature dial from the core (range, the
     *  profile's own brew temp, its pre-infusion seconds); null = none. */
    brewDial: BrewDial? = null,
) {
    // Effective brew values: the transient override if set, else the active
    // profile (its brew temp as the core anchors it).
    val (dose, yieldOut, profileBrewTemp) = effectiveBrew(brewParams, active)
    val brewTemp = brewParams?.brewTemp ?: brewDial?.anchor ?: profileBrewTemp
    // Pre-infusion is the total of the profile's leading pre-infusion segments
    // (the core's seed); a transient override rescales them (issue 15).
    val preinf = brewParams?.preinf ?: brewDial?.preinfS ?: (active?.preinfuseSeconds?.toDouble() ?: 8.0)
    // The current pre-infuse override state (null = not overridden) — preserved
    // when other brew steppers fire so a dose tweak doesn't drop a preinf override.
    val preinfOverride = brewParams?.preinf
    // Grind: persisted QC click (issue 15); show the saved value, else the
    // active bean's own setting (issue #16), else the generic seed.
    val grind = qcGrind ?: qcGrindSeed

    var showSave by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }
    // Per-stepper mode — which sub-value each combined stepper edits (local view state).
    var doseGrindMode by remember { mutableStateOf(DoseGrindMode.Dose) }
    var brewMode by remember { mutableStateOf(BrewMode.Temp) }
    // Steam / water / flush *values* are persisted props (issue 14); only which
    // sub-value each stepper edits ("mode") is local view state.
    var steamMode by remember { mutableStateOf(SteamMode.Time) }
    var waterMode by remember { mutableStateOf(WaterMode.Amount) }
    var flushMode by remember { mutableStateOf(FlushMode.Time) }
    val steamGate = gate(when (steamMode) { SteamMode.Flow -> Setting.QC_STEAM_FLOW; SteamMode.Temp -> Setting.QC_STEAM_TEMP; SteamMode.Time -> Setting.QC_STEAM_TIME })
    // The card's note: the temperature, or the dose (its mode stands for it).
    val waterGate = gate(if (waterMode == WaterMode.Temp) Setting.QC_HOT_WATER_TEMP else Setting.QC_HOT_WATER_MODE)
    val flushGate = gate(if (flushMode == FlushMode.Time) Setting.QC_FLUSH_TIME else Setting.QC_FLUSH_TEMP)
    val brewGate = gate(Setting.BREW_PARAMS)
    val doseGrindGate = if (doseGrindMode == DoseGrindMode.Dose) brewGate else gate(Setting.QC_GRIND)

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
                        // Scope-aware Save (issue #16): the grind dial writes
                        // back to the active bean, then the brew params to
                        // the profile — custom updates in place; read-only
                        // built-in → name + copy.
                        CremaButton(
                            onClick = {
                                onSaveGrindToBean()
                                if (active.source == "custom") onSavePreset(active.name) else showSave = true
                            },
                            variant = CremaButtonVariant.Text,
                            icon = "bookmark-simple",
                            label = "Save",
                        )
                    }
                    // Always visible like the web QuickSheet; enabled only once a
                    // tweak exists so it never reads as a dead control.
                    CremaButton(onClick = onResetBrew, variant = CremaButtonVariant.Text, icon = "arrow-counter-clockwise", label = "Reset", enabled = brewParams != null && brewGate.editable)
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
                                modifier = Modifier.gated(gate(Setting.ACTIVE_PROFILE)),
                                selected = p.id == activeProfileId,
                                onClick = { onSelectProfile(p.id) },
                                label = { Text(p.name, maxLines = 1) },
                                leadingIcon = { PhIcon("coffee", sizeDp = 16) },
                            )
                        }
                        items(shownBeans, key = { "b:${it.id}" }) { b ->
                            FilterChip(
                                modifier = Modifier.gated(gate(Setting.ACTIVE_BEAN)),
                                selected = b.id == activeBeanId,
                                onClick = { onSelectBean(b.id) },
                                label = { Text(b.name, maxLines = 1) },
                                leadingIcon = { PhIcon("coffee-bean", sizeDp = 16) },
                            )
                        }
                    }
                }
            }

            // ── Six steppers. One row on a wide tablet (10"); wraps to 3-per-row
            //    on a 7" landscape and 2-per-row on 7" portrait / phone, so each
            //    stepper keeps enough width for its split-label header + chips. ──
            val steppers = listOf<@Composable (Modifier) -> Unit>(
                // 1 — Dose | Grind (no prefix).
                { m ->
                    CremaStepper(
                        modifier = m,
                        value = if (doseGrindMode == DoseGrindMode.Dose) dose else grind,
                        unit = if (doseGrindMode == DoseGrindMode.Dose) "g" else null,
                        min = if (doseGrindMode == DoseGrindMode.Dose) QcBounds.DOSE_MIN_G else QcBounds.GRIND_MIN,
                        max = if (doseGrindMode == DoseGrindMode.Dose) QcBounds.DOSE_MAX_G else QcBounds.GRIND_MAX,
                        step = 0.1,
                        fmt = niceFmt,
                        chips = if (doseGrindMode == DoseGrindMode.Dose) listOf(16.0, 17.0, 18.0, 19.0, 20.0) else listOf(3.8, 4.0, 4.2, 4.4, 4.6),
                        onChange = { if (doseGrindMode == DoseGrindMode.Dose) onAdjustBrew(it, yieldOut, brewTemp, preinfOverride) else onGrind(it) },
                        style = CremaStepperStyle.Boxed,
                        gate = doseGrindGate,
                        header = {
                            CremaSplitLabel(prefix = "", options = listOf(SplitOption("dose", "Dose"), SplitOption("grind", "Grind")), value = doseGrindMode.value, onChange = { doseGrindMode = DoseGrindMode.of(it) })
                        },
                    )
                },
                // 2 — Yield (+ live ratio). The dot toggles the weight target
                // (stop-on-weight); the value resolves Quick Controls override →
                // profile (whichever is set). Greys out when the target is off.
                { m ->
                    CremaStepper(
                        modifier = m,
                        value = yieldOut, unit = "g", min = QcBounds.YIELD_MIN_G, max = QcBounds.YIELD_MAX_G, step = 0.5,
                        fmt = niceFmt,
                        chips = listOf(28.0, 32.0, 36.0, 40.0, 45.0),
                        enabled = stopOnWeight,
                        onChange = { onAdjustBrew(dose, it, brewTemp, preinfOverride) },
                        style = CremaStepperStyle.Boxed,
                        gate = brewGate,
                        header = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                CremaDotToggle(stopOnWeight, { onStopOnWeight(!stopOnWeight) }, Modifier.gated(gate(Setting.STOP_ON_WEIGHT)))
                                Eyebrow("Yield", Modifier.weight(1f))
                                Text(if (brewGate.unknown) UNKNOWN_VALUE else formatRatio(dose, yieldOut), style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.primary)
                            }
                        },
                    )
                },
                // 3 — Brew temp | pre-infuse. The temperature is absolute, inside the
                // core's range for the profile (every step shifts with it); off the
                // profile's own value it says how far, with a Reset.
                { m ->
                    val tempMin = brewDial?.min ?: QcBounds.BREW_TEMP_MIN_C
                    val tempMax = brewDial?.max ?: QcBounds.BREW_TEMP_MAX_C
                    // The core's pre-infusion range holds the profile's own value;
                    // a profile with no pre-infusion step has nothing to adjust.
                    val preinfMax = brewDial?.preinfMax ?: QcBounds.PREINF_MAX_S
                    val preinfOff = brewMode == BrewMode.Preinf && brewDial?.preinfAdjustable == false
                    Column(m) {
                        CremaStepper(
                            modifier = Modifier.fillMaxWidth(),
                            value = if (brewMode == BrewMode.Temp) brewTemp else preinf,
                            unit = if (brewMode == BrewMode.Temp) "°C" else "s",
                            min = if (brewMode == BrewMode.Temp) tempMin else QcBounds.PREINF_MIN_S,
                            max = if (brewMode == BrewMode.Temp) tempMax else preinfMax,
                            step = if (brewMode == BrewMode.Temp) 0.5 else 1.0,
                            fmt = niceFmt,
                            enabled = !preinfOff,
                            chips = if (brewMode == BrewMode.Temp) listOf(88.0, 91.0, 93.0, 95.0, 97.0).filter { it in tempMin..tempMax } else listOf(0.0, 4.0, 8.0, 12.0, 16.0).filter { it <= preinfMax },
                            onChange = { if (brewMode == BrewMode.Temp) onAdjustBrew(dose, yieldOut, it, preinfOverride) else onAdjustBrew(dose, yieldOut, brewTemp, it) },
                            style = CremaStepperStyle.Boxed,
                            gate = brewGate,
                            header = {
                                CremaSplitLabel(prefix = "Brew", options = listOf(SplitOption("temp", "Temp"), SplitOption("preinf", "Pre-infuse")), value = brewMode.value, onChange = { brewMode = BrewMode.of(it) })
                            },
                        )
                        if (preinfOff) PreinfusionNotAdjustableNote()
                        val offset = if (brewMode == BrewMode.Temp && brewParams != null) brewDial?.offsetLabel(brewTemp) else null
                        if (offset != null && brewDial != null) {
                            BrewTempOffsetRow(offset, enabled = brewGate.editable) {
                                onAdjustBrew(dose, yieldOut, brewDial.anchor, preinfOverride)
                            }
                        }
                    }
                },
                // 4 — Steam time | flow | temp.
                { m ->
                    CremaStepper(
                        modifier = m,
                        value = when (steamMode) { SteamMode.Flow -> qcSteamFlowMlS; SteamMode.Temp -> qcSteamTempC; SteamMode.Time -> qcSteamTimeS },
                        unit = when (steamMode) { SteamMode.Flow -> "ml/s"; SteamMode.Temp -> "°C"; SteamMode.Time -> "s" },
                        min = when (steamMode) { SteamMode.Flow -> QcSteam.MIN_FLOW_ML_S; SteamMode.Temp -> QcSteam.MIN_TEMP_C; SteamMode.Time -> QcSteam.MIN_TIME_S },
                        max = when (steamMode) { SteamMode.Flow -> QcSteam.MAX_FLOW_ML_S; SteamMode.Temp -> QcSteam.MAX_TEMP_C; SteamMode.Time -> QcSteam.MAX_TIME_S },
                        step = when (steamMode) { SteamMode.Flow -> 0.1; SteamMode.Temp -> 0.5; SteamMode.Time -> 1.0 },
                        fmt = niceFmt,
                        chips = when (steamMode) { SteamMode.Flow -> listOf(0.6, 0.9, 1.2, 1.6, 2.0); SteamMode.Temp -> listOf(140.0, 145.0, 148.0, 150.0, 155.0); SteamMode.Time -> listOf(5.0, 10.0, 15.0, 20.0, 30.0) },
                        onChange = { when (steamMode) { SteamMode.Flow -> onSteamFlow(it); SteamMode.Temp -> onSteamTemp(it); SteamMode.Time -> onSteamTime(it) } },
                        style = CremaStepperStyle.Boxed,
                        // Steam temp 0 = heater off (DE1 firmware snaps <135 → 0). The dot on
                        // the Steam label arms/disarms it and greys the bar; the 135 floor
                        // keeps the user out of the silent 120–134 snap-to-off band.
                        enabled = !(steamMode == SteamMode.Temp && qcSteamTempC <= 0.0),
                        gate = steamGate,
                        header = {
                            CremaSplitLabel(
                                prefix = "Steam",
                                options = listOf(SplitOption("time", "Time"), SplitOption("flow", "Flow"), SplitOption("temp", "Temp")),
                                value = steamMode.value, onChange = { steamMode = SteamMode.of(it) },
                                dot = steamMode == SteamMode.Temp,
                                dotOn = qcSteamTempC > 0,
                                onDot = { onSteamTemp(if (qcSteamTempC > 0) 0.0 else QcSteam.DEFAULT_TEMP_C) },
                                dotGate = gate(Setting.QC_STEAM_TEMP),
                            )
                        },
                    )
                },
                // 5 — Hot water: temp | ‹mode› ▾ (spec §2.7). The mode lives in
                // the label (a menu), the amount in the stepper's secondary line,
                // so the card keeps its siblings' footprint in every mode.
                { m ->
                    val waterHeader: @Composable () -> Unit = {
                        HotWaterHeader(
                            waterMode = waterMode,
                            onWaterMode = { waterMode = it },
                            mode = hotWaterDose.mode,
                            onMode = { onHotWaterDose(hotWaterDose.copy(mode = it)) },
                            gate = gate(Setting.QC_HOT_WATER_MODE),
                        )
                    }
                    if (waterMode == WaterMode.Temp) {
                        CremaStepper(
                            modifier = m,
                            value = qcHotWaterTempC,
                            unit = "°C",
                            min = QcBounds.WATER_TEMP_MIN_C,
                            max = QcBounds.WATER_TEMP_MAX_C,
                            step = 1.0,
                            fmt = niceFmt,
                            chips = listOf(60.0, 75.0, 85.0, 92.0, 96.0),
                            onChange = onHotWaterTemp,
                            style = CremaStepperStyle.Boxed,
                            gate = waterGate,
                            header = waterHeader,
                        )
                    } else {
                        val hw = hotWaterStepper(hotWaterDose, onHotWaterDose)
                        // Six to a row (~181 dp) gets the short form; a wider card
                        // the full sentence (web switches at 260 px).
                        BoxWithConstraints(m) {
                            CremaStepper(
                                value = hw.value,
                                unit = hw.unit,
                                min = hw.min,
                                max = hw.max,
                                step = hw.step,
                                fmt = hw.fmt,
                                chips = hw.chips,
                                chipFmt = hw.chipFmt,
                                chipActive = hw.chipActive,
                                extraChip = hw.extraChip,
                                onChange = hw.onChange,
                                style = CremaStepperStyle.Boxed,
                                gate = gate(hw.setting),
                                header = waterHeader,
                                sub = hotWaterSubLine(hotWaterTarget, weightUnit, volumeUnit, hotWaterDose.ratio, long = maxWidth >= 260.dp),
                            )
                        }
                    }
                },
                // 6 — Flush time | temp.
                { m ->
                    CremaStepper(
                        modifier = m,
                        value = if (flushMode == FlushMode.Time) qcFlushTimeS else qcFlushTempC,
                        unit = if (flushMode == FlushMode.Time) "s" else "°C",
                        min = if (flushMode == FlushMode.Time) QcBounds.FLUSH_TIME_MIN_S else QcBounds.FLUSH_TEMP_MIN_C,
                        max = if (flushMode == FlushMode.Time) QcBounds.FLUSH_TIME_MAX_S else QcBounds.FLUSH_TEMP_MAX_C,
                        step = if (flushMode == FlushMode.Time) 1.0 else 0.5,
                        fmt = niceFmt,
                        chips = if (flushMode == FlushMode.Time) listOf(2.0, 4.0, 6.0, 8.0, 10.0) else listOf(88.0, 92.0, 95.0, 97.0, 99.0),
                        onChange = { if (flushMode == FlushMode.Time) onFlushTime(it) else onFlushTemp(it) },
                        style = CremaStepperStyle.Boxed,
                        gate = flushGate,
                        header = {
                            CremaSplitLabel(prefix = "Flush", options = listOf(SplitOption("time", "Time"), SplitOption("temp", "Temp")), value = flushMode.value, onChange = { flushMode = FlushMode.of(it) })
                        },
                    )
                },
            )
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val perRow = when {
                    maxWidth >= 1140.dp -> 6   // 10" tablet: original single row
                    maxWidth >= 600.dp -> 3    // 7" landscape: two rows of three
                    else -> 2                  // 7" portrait / phone: three rows of two
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    steppers.chunked(perRow).forEach { rowItems ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowItems.forEach { it(Modifier.weight(1f)) }
                        }
                    }
                    GateNotes(listOf("Dose and grind" to doseGrindGate, "Brew" to brewGate, "Steam" to steamGate, "Hot water" to waterGate, "Flush" to flushGate))
                }
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
                        QcMiniToggle("Stop on weight", stopOnWeight, onStopOnWeight, gate = gate(Setting.STOP_ON_WEIGHT))
                        QcMiniToggle("Auto-tare", autoTare, onAutoTare, gate = gate(Setting.AUTO_TARE))
                        QcMiniToggle("Pre-flush", preFlush, onPreFlush, gate = gate(Setting.PRE_FLUSH))
                        QcMiniToggle("Steam purge", steamPurge, onSteamPurge, gate = gate(Setting.STEAM_PURGE))
                        QcMiniToggle("Steam eco", steamEco, onSteamEco, gate = gate(Setting.STEAM_ECO))
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
private fun QcMiniToggle(label: String, on: Boolean, onToggle: (Boolean) -> Unit, onColor: Color = MaterialTheme.colorScheme.primary, gate: RowGate = RowGate.Open) {
    Row(
        Modifier.gated(gate).clip(RoundedCornerShape(999.dp)).clickable { onToggle(!on) }.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // An unknown value (a remote before the hub's snapshot) shows "—".
        if (gate.unknown) Text("—", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Box(
            Modifier.width(24.dp).height(14.dp).clip(RoundedCornerShape(999.dp))
                .background(if (on) onColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(Modifier.padding(horizontal = 2.dp).size(10.dp).clip(CircleShape).background(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant))
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "+x.x °C from profile" under the brew-temperature dial, with a Reset that
 *  puts the dial back on the profile's own brew temperature. */
@Composable
internal fun BrewTempOffsetRow(label: String, enabled: Boolean, onReset: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        CremaButton(onClick = onReset, variant = CremaButtonVariant.Text, label = "Reset", enabled = enabled)
    }
}

/** Under a disabled pre-infusion dial: the profile has no pre-infusion step. */
@Composable
internal fun PreinfusionNotAdjustableNote() {
    Text(
        "This profile has no pre-infusion step, so there is nothing to adjust.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

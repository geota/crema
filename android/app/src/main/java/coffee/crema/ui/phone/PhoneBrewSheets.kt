package coffee.crema.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.profiles.CremaProfile
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.effectiveBrew
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.CremaEdge
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono

/*
 * The Brew screen's two bottom sheets (proto QuickSheet + DevicesSheet),
 * phone-tabbed re-flows of the tablet QuickControlsSheet with the SAME
 * ViewModel seams:
 *  • dose / yield / brew-temp → the transient BrewParams override
 *    (vm.quickAdjustBrew); Reset clears it; Save preset persists a profile.
 *  • grind / pre-inf / steam / water / flush → local stepper state (no
 *    per-mode param store on Android yet — tablet parity).
 *  • Chart tab → vm.toggleChartChannel; Shot tab → the five behaviour setters.
 */

private val niceFmt: (Double) -> String = { v -> if (v == kotlin.math.floor(v)) "%.0f".format(v) else "%.1f".format(v) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneQuickSheet(
    ui: MainUiState,
    active: CremaProfile?,
    vm: MainViewModel,
    onDismiss: () -> Unit,
) {
    val (dose, yieldOut, brewTemp) = ui.effectiveBrew()

    var tab by remember { mutableStateOf("dial") }
    // Split-stepper modes + local (no-VM-seam) values — tablet QC parity.
    var doseGrindMode by remember { mutableStateOf("dose") }
    var grind by remember { mutableStateOf(4.2) }
    var yieldRatioMode by remember { mutableStateOf("yield") }
    var piFlushMode by remember { mutableStateOf("preinf") }
    var preinf by remember { mutableStateOf(8.0) }
    var flushTime by remember { mutableStateOf(4.0) }
    // Steam / hot-water modes are local UI; the values they edit are the VM's
    // persisted QC state (vm.setQcSteam* / vm.setQcHotWater*), shared with the
    // tablet — no local stub.
    var steamMode by remember { mutableStateOf("time") }
    var waterMode by remember { mutableStateOf("volume") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CremaEdge)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Head.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Quick Controls", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 21.sp))
                    Text(
                        "Applies to the next shot — your saved profile is never changed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Tabs (proto .pf-qc-tabs).
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("dial" to "Dial-in", "chart" to "Chart", "shot" to "Shot").forEach { (id, label) ->
                    val on = tab == id
                    Box(
                        Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { tab = id },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            when (tab) {
                "dial" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Dose | Grind.
                            QcCell(
                                modifier = Modifier.weight(1f),
                                header = {
                                    CremaSplitLabel(
                                        prefix = "",
                                        options = listOf(SplitOption("dose", "Dose"), SplitOption("grind", "Grind")),
                                        value = doseGrindMode, onChange = { doseGrindMode = it },
                                    )
                                },
                                value = if (doseGrindMode == "dose") dose else grind,
                                unit = if (doseGrindMode == "dose") "g" else "",
                                step = 0.1,
                                min = if (doseGrindMode == "dose") 5.0 else 0.0,
                                max = if (doseGrindMode == "dose") 30.0 else 20.0,
                                presets = if (doseGrindMode == "dose") listOf(16.0, 18.0, 19.0, 20.0) else listOf(3.5, 4.0, 4.5, 5.0),
                                fmt = { "%.1f".format(it) },
                                onChange = { if (doseGrindMode == "dose") vm.quickAdjustBrew(it, yieldOut, brewTemp) else grind = it },
                            )
                            // Yield | Ratio.
                            QcCell(
                                modifier = Modifier.weight(1f),
                                header = {
                                    CremaSplitLabel(
                                        prefix = "",
                                        options = listOf(SplitOption("yield", "Yield"), SplitOption("ratio", "Ratio")),
                                        value = yieldRatioMode, onChange = { yieldRatioMode = it },
                                    )
                                },
                                value = if (yieldRatioMode == "yield") yieldOut else (if (dose > 0) yieldOut / dose else 2.0),
                                unit = if (yieldRatioMode == "yield") "g" else "",
                                step = if (yieldRatioMode == "yield") 0.5 else 0.1,
                                min = if (yieldRatioMode == "yield") 10.0 else 1.0,
                                max = if (yieldRatioMode == "yield") 80.0 else 5.0,
                                presets = if (yieldRatioMode == "yield") listOf(32.0, 36.0, 40.0, 45.0) else listOf(1.5, 2.0, 2.5, 3.0),
                                fmt = { if (yieldRatioMode == "yield") niceFmt(it) else "1:%.1f".format(it) },
                                onChange = {
                                    if (yieldRatioMode == "yield") vm.quickAdjustBrew(dose, it, brewTemp)
                                    else vm.quickAdjustBrew(dose, dose * it, brewTemp)
                                },
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            QcCell(
                                modifier = Modifier.weight(1f),
                                header = { Eyebrow("Brew temp") },
                                value = brewTemp, unit = "°C", step = 0.5, min = 80.0, max = 100.0,
                                presets = listOf(90.0, 93.0, 95.0, 97.0),
                                fmt = { "%.1f".format(it) },
                                onChange = { vm.quickAdjustBrew(dose, yieldOut, it) },
                            )
                            // Pre-inf | Flush (local — drives the next shot's fixed params).
                            QcCell(
                                modifier = Modifier.weight(1f),
                                header = {
                                    CremaSplitLabel(
                                        prefix = "",
                                        options = listOf(SplitOption("preinf", "Pre-inf"), SplitOption("flush", "Flush")),
                                        value = piFlushMode, onChange = { piFlushMode = it },
                                    )
                                },
                                value = if (piFlushMode == "preinf") preinf else flushTime,
                                unit = "s", step = 1.0,
                                min = 0.0, max = if (piFlushMode == "preinf") 30.0 else 20.0,
                                presets = if (piFlushMode == "preinf") listOf(0.0, 4.0, 8.0, 12.0) else listOf(2.0, 4.0, 6.0, 8.0),
                                fmt = { "%.0f".format(it) },
                                onChange = { if (piFlushMode == "preinf") preinf = it else flushTime = it },
                            )
                        }
                        // Steam — Time | Flow | Temp, wired to the same persisted QC
                        // state the tablet uses (vm.setQcSteam*). Temp 0 = heater off:
                        // the dot arms/disarms it and greys the bar; the 135 floor keeps
                        // the user out of the firmware's silent <135 → 0 snap band.
                        // Full-width so the 3-way label + dot never crowd (sheet scrolls).
                        QcCell(
                            modifier = Modifier.fillMaxWidth(),
                            header = {
                                CremaSplitLabel(
                                    prefix = "Steam",
                                    options = listOf(SplitOption("time", "Time"), SplitOption("flow", "Flow"), SplitOption("temp", "Temp")),
                                    value = steamMode, onChange = { steamMode = it },
                                    dot = steamMode == "temp",
                                    dotOn = ui.qcSteamTempC > 0,
                                    onDot = { vm.setQcSteamTemp(if (ui.qcSteamTempC > 0) 0f else 148f) },
                                )
                            },
                            value = when (steamMode) { "flow" -> ui.qcSteamFlowMlS.toDouble(); "temp" -> ui.qcSteamTempC.toDouble(); else -> ui.qcSteamTimeS.toDouble() },
                            unit = when (steamMode) { "flow" -> "ml/s"; "temp" -> "°C"; else -> "s" },
                            step = when (steamMode) { "flow" -> 0.1; "temp" -> 0.5; else -> 1.0 },
                            min = when (steamMode) { "flow" -> 0.2; "temp" -> 135.0; else -> 1.0 },
                            max = when (steamMode) { "flow" -> 3.0; "temp" -> 170.0; else -> 60.0 },
                            presets = when (steamMode) { "flow" -> listOf(0.6, 0.9, 1.2, 1.6, 2.0); "temp" -> listOf(140.0, 145.0, 148.0, 150.0, 155.0); else -> listOf(5.0, 10.0, 15.0, 20.0, 30.0) },
                            fmt = { if (it % 1.0 == 0.0) "%.0f".format(it) else "%.1f".format(it) },
                            enabled = !(steamMode == "temp" && ui.qcSteamTempC <= 0f),
                            onChange = { when (steamMode) { "flow" -> vm.setQcSteamFlow(it.toFloat()); "temp" -> vm.setQcSteamTemp(it.toFloat()); else -> vm.setQcSteamTime(it.toFloat()) } },
                        )
                        // Hot water — Temp | Volume, wired to the VM (tablet parity). No
                        // disable dot: 0 isn't a clean "off" here (the de1app/Decenza
                        // cross-check), so it stays a plain mode switch.
                        QcCell(
                            modifier = Modifier.fillMaxWidth(),
                            header = {
                                CremaSplitLabel(
                                    prefix = "Hot water",
                                    options = listOf(SplitOption("temp", "Temp"), SplitOption("volume", "Volume")),
                                    value = waterMode, onChange = { waterMode = it },
                                )
                            },
                            value = if (waterMode == "temp") ui.qcHotWaterTempC.toDouble() else ui.qcHotWaterVolumeMl.toDouble(),
                            unit = if (waterMode == "temp") "°C" else "ml",
                            step = if (waterMode == "temp") 1.0 else 10.0,
                            min = if (waterMode == "temp") 40.0 else 20.0,
                            max = if (waterMode == "temp") 98.0 else 500.0,
                            presets = if (waterMode == "temp") listOf(60.0, 75.0, 85.0, 92.0, 96.0) else listOf(60.0, 120.0, 180.0, 250.0, 350.0),
                            fmt = { "%.0f".format(it) },
                            onChange = { if (waterMode == "temp") vm.setQcHotWaterTemp(it.toFloat()) else vm.setQcHotWaterVolume(it.toFloat()) },
                        )
                    }
                }
                "chart" -> {
                    val tel = CremaTheme.telemetry
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Which channels draw on the live chart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        ChannelGroupRow("gauge", tel.pressure, "pressure" to "Pressure", "resistance" to "Resistance", tel.pressure2, ui, vm)
                        ChannelGroupRow("drop", tel.flow, "flow" to "Flow", "dispensedVolume" to "Volume", tel.flow2, ui, vm)
                        ChannelGroupRow("thermometer", tel.temp, "headTemp" to "Coffee temp", "mixTemp" to "Water temp", tel.temp2, ui, vm)
                        ChannelGroupRow("scales", tel.weight, "weight" to "Weight", "weightFlow" to "Weight flow", tel.weight2, ui, vm)
                    }
                }
                "shot" -> {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Behaviour armed for the next shot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                        ShotToggleRow("Stop on weight", ui.stopOnWeight, vm::setStopOnWeight)
                        ShotToggleRow("Auto-tare on start", ui.autoTare, vm::setAutoTare)
                        ShotToggleRow("Pre-flush before shot", ui.preFlush, vm::setPreFlush)
                        ShotToggleRow("Steam purge after", ui.steamPurge, vm::setSteamPurge)
                        ShotToggleRow("Steam eco mode", ui.steamEco, vm::setSteamEco)
                    }
                }
            }

            // Foot: Reset + Save profile. The dial is a per-shot override (baked
            // into the next shot) that never touches the saved profile — Reset
            // clears it. Save profile persists the dialled dose/yield/temp: it
            // updates the profile in place when it's user-defined, or saves a copy
            // when it's a read-only built-in.
            Row(
                Modifier.fillMaxWidth().padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CremaButton(
                    onClick = vm::resetBrewParams,
                    variant = CremaButtonVariant.Tonal,
                    icon = "arrow-counter-clockwise",
                    enabled = ui.brewParams != null,
                    label = "Reset",
                )
                Spacer(Modifier.weight(1f))
                CremaButton(
                    onClick = {
                        active?.let { a ->
                            if (a.source == "custom") vm.saveQuickPreset(a.name)
                            else vm.saveQuickPreset("${a.name} (copy)")
                        }
                    },
                    variant = CremaButtonVariant.Filled,
                    icon = "bookmark-simple",
                    enabled = active != null,
                    label = "Save profile",
                )
            }
        }
    }

}

// One Quick-Controls stepper cell (proto .pf-qc-step): header, − value +, presets.
@Composable
private fun QcCell(
    modifier: Modifier,
    header: @Composable () -> Unit,
    value: Double,
    unit: String,
    step: Double,
    min: Double,
    max: Double,
    presets: List<Double>,
    fmt: (Double) -> String,
    onChange: (Double) -> Unit,
    enabled: Boolean = true,
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            header()
            Row(
                Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QcRoundBtn("minus") { onChange((value - step).coerceIn(min, max)) }
                Row(verticalAlignment = Alignment.Bottom) {
                    TapToEditValue(
                        value = value,
                        min = min,
                        max = max,
                        onCommit = onChange,
                        editStyle = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 23.sp),
                        enabled = enabled,
                    ) {
                        Text(
                            fmt(value),
                            style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 23.sp, fontFeatureSettings = "tnum"),
                        )
                    }
                    if (unit.isNotEmpty()) Text(
                        unit,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                    )
                }
                QcRoundBtn("plus") { onChange((value + step).coerceIn(min, max)) }
            }
            Row(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.4f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presets.forEach { p ->
                    val on = kotlin.math.abs(p - value) < 0.001
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { onChange(p) }
                            .padding(vertical = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            fmt(p),
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, fontFeatureSettings = "tnum"),
                            color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QcRoundBtn(icon: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(34.dp)) {
        Box(contentAlignment = Alignment.Center) { PhIcon(icon, sizeDp = 15) }
    }
}

// Chart tab: one channel family — icon + primary/secondary mini toggles.
@Composable
private fun ChannelGroupRow(
    icon: String,
    color: Color,
    primary: Pair<String, String>,
    secondary: Pair<String, String>,
    secondaryColor: Color,
    ui: MainUiState,
    vm: MainViewModel,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PhIcon(icon, sizeDp = 18, tint = color)
            MiniToggle(primary.second, primary.first in ui.chartChannels, color, Modifier.weight(1f)) {
                vm.toggleChartChannel(primary.first, it)
            }
            MiniToggle(secondary.second, secondary.first in ui.chartChannels, secondaryColor, Modifier.weight(1f)) {
                vm.toggleChartChannel(secondary.first, it)
            }
        }
    }
}

// Shot tab: one full-width behaviour toggle row.
@Composable
private fun ShotToggleRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MiniPill(on, MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }
}

// Mini pill toggle (proto .qc-mini) + label.
@Composable
private fun MiniToggle(label: String, on: Boolean, color: Color, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onChange(!on) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MiniPill(on, color)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Medium),
            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

@Composable
private fun MiniPill(on: Boolean, color: Color) {
    Box(
        Modifier
            .size(width = 38.dp, height = 22.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) color else MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(
                1.dp,
                if (on) Color.Transparent else MaterialTheme.colorScheme.outline,
                RoundedCornerShape(999.dp),
            ),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 3.dp)
                .size(if (on) 16.dp else 12.dp)
                .clip(CircleShape)
                .background(if (on) Color.White else MaterialTheme.colorScheme.outline),
        )
    }
}

/* ── Devices sheet (proto DevicesSheet) ─────────────────────────────────── */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneDevicesSheet(
    ui: MainUiState,
    connected: Boolean,
    scaleConnected: Boolean,
    onConnect: (String) -> Unit,
    onDe1AutoConnect: (Boolean) -> Unit,
    onScaleAutoConnect: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = CremaEdge).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Text("Devices", style = MaterialTheme.typography.headlineSmall.copy(fontSize = 21.sp))
                Text(
                    "Bluetooth connections",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DeviceRow(
                kind = "Machine",
                name = if (connected) "Decent DE1" else "Decent DE1",
                icon = "sliders-horizontal",
                stat = if (connected) {
                    listOfNotNull("Connected", ui.de1Firmware).joinToString(" · ")
                } else {
                    when (ui.bleState) {
                        De1BleManager.State.SCANNING -> "Scanning…"
                        De1BleManager.State.CONNECTING, De1BleManager.State.DISCOVERING, De1BleManager.State.SUBSCRIBING -> "Connecting…"
                        else -> "Not connected"
                    }
                },
                on = connected,
                onAction = { onConnect("machine") },
                autoConnect = ui.rememberedDe1Address != null,
                autoConnectEnabled = connected || ui.rememberedDe1Address != null,
                onAutoConnect = onDe1AutoConnect,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            DeviceRow(
                kind = "Scale",
                name = ui.scaleName ?: "Scale",
                icon = "scales",
                stat = if (scaleConnected) {
                    buildList {
                        add("Connected")
                        ui.scaleBatteryPercent?.let { add("$it%") }
                        ui.scaleFirmware?.let { add("FW $it") }
                    }.joinToString(" · ")
                } else {
                    when (ui.scaleState) {
                        ScaleBleManager.State.SCANNING -> "Scanning…"
                        ScaleBleManager.State.CONNECTING, ScaleBleManager.State.DISCOVERING, ScaleBleManager.State.SUBSCRIBING -> "Connecting…"
                        else -> "Not paired"
                    }
                },
                on = scaleConnected,
                onAction = { onConnect("scale") },
                autoConnect = ui.rememberedScaleAddress != null,
                autoConnectEnabled = scaleConnected || ui.rememberedScaleAddress != null,
                onAutoConnect = onScaleAutoConnect,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = {
                    if (!connected) onConnect("machine")
                    if (!scaleConnected) onConnect("scale")
                },
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                PhIcon("bluetooth", sizeDp = 19)
                Spacer(Modifier.width(9.dp))
                Text("Scan for devices", style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold))
            }
            Text(
                "Acaia, Bookoo, Decent, Felicita and more pair automatically.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DeviceRow(
    kind: String,
    name: String,
    icon: String,
    stat: String,
    on: Boolean,
    onAction: () -> Unit,
    /** Per-device "Auto-connect" state + whether it's interactive. Greyed out
     *  (disabled) when there's no device to control — neither connected nor
     *  remembered — rather than hidden. */
    autoConnect: Boolean = false,
    autoConnectEnabled: Boolean = false,
    onAutoConnect: (Boolean) -> Unit = {},
) {
    val tel = CremaTheme.telemetry
    Row(
        Modifier.fillMaxWidth().padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (on) tel.success.copy(alpha = 0.18f).compositeOver(MaterialTheme.colorScheme.surfaceContainerHighest)
            else MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                PhIcon(icon, sizeDp = 22, tint = if (on) tel.success else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                kind.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(name, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(if (on) tel.success else MaterialTheme.colorScheme.outline),
                )
                Text(
                    stat,
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Per-device Auto-connect — a compact label-over-switch BESIDE the Pair
        // pill (ON remembers the device; OFF forgets it). Always shown; greyed out
        // when there's no device to control (neither connected nor remembered).
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "Auto-connect",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 0.2.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (autoConnectEnabled) 1f else 0.5f),
                maxLines = 1,
            )
            CremaSwitch(autoConnect, onAutoConnect, enabled = autoConnectEnabled)
        }
        Surface(
            onClick = onAction,
            shape = RoundedCornerShape(999.dp),
            color = if (on) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary,
            modifier = Modifier.height(36.dp),
        ) {
            Box(Modifier.padding(horizontal = 15.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (on) "Disconnect" else "Pair",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold),
                    color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

private fun Color.compositeOver(base: Color): Color = Color(
    red = red * alpha + base.red * (1 - alpha),
    green = green * alpha + base.green * (1 - alpha),
    blue = blue * alpha + base.blue * (1 - alpha),
    alpha = 1f,
)

package coffee.crema.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.refillSoon
import coffee.crema.ui.formatTemp
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.*
import coffee.crema.ui.screens.cleanRow
import coffee.crema.ui.screens.SettingsConfirmDialogs
import coffee.crema.ui.screens.cpuBoardLabel
import coffee.crema.ui.screens.rememberSettingsConfirmState
import coffee.crema.ui.screens.descaleRow
import coffee.crema.ui.screens.filterRow
import coffee.crema.ui.screens.cupWarmerTempValue
import coffee.crema.ui.screens.flowMultiplierValue
import coffee.crema.ui.screens.ghcModeOn
import coffee.crema.ui.screens.ghcPresent
import coffee.crema.ui.screens.hasCupWarmerPlate
import coffee.crema.ui.screens.firmwareLabel
import coffee.crema.ui.screens.heaterVoltageLabel
import coffee.crema.ui.screens.heaterVoltageValue
import coffee.crema.ui.screens.machineModelLabel
import coffee.crema.ui.screens.serialLabel
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono

/*
 * PhoneSettingsScreen — the handset Settings (port of
 * prototype/phone/phone-settings.jsx), wired to LIVE state.
 *
 * The tablet two-pane collapses to a section LIST (machine hero + grouped push
 * rows) that pushes a detail view as INTERNAL state — the bottom bar stays, the
 * back arrow (and system back) return to the list. Every section's content is
 * the tablet's, rebuilt from the phone SettingsGroup/SettingsRow primitives
 * with identical ViewModel wiring.
 */

private val PHONE_SECTIONS = listOf(
    "Device" to listOf(
        Triple("machine", "sliders-horizontal", "Machine"),
        Triple("peripherals", "plugs-connected", "Peripherals"),
    ),
    "Brewing" to listOf(
        Triple("brew", "coffee", "Brew defaults"),
        Triple("water", "drop", "Water & maintenance"),
    ),
    "App" to listOf(
        Triple("display", "monitor", "Display & units"),
        Triple("sharing", "share-network", "Sharing"),
    ),
    "Advanced" to listOf(
        Triple("calibration", "gauge", "Calibration"),
        Triple("advanced", "wrench", "Advanced"),
        Triple("about", "info", "About"),
    ),
)

@Composable
fun PhoneSettingsScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    var section by rememberSaveable { mutableStateOf<String?>(null) }

    // Staged destructive-action confirms + SAF export — shared with the tablet
    // shell (issue 27). Rows flip confirm.* flags; SettingsConfirmDialogs renders.
    val confirm = rememberSettingsConfirmState(vm)

    SettingsConfirmDialogs(confirm, vm, ui)

    // Local design-faithful prefs (pilled rows keep local state only).
    var density by rememberSaveable { mutableStateOf("comfortable") }
    var smoothPressure by rememberSaveable { mutableStateOf(true) }
    var tempOffset by rememberSaveable { mutableStateOf(0.0) }
    var screensaver by rememberSaveable { mutableStateOf(false) }
    var waterSource by rememberSaveable { mutableStateOf("filtered") }
    var hardnessPpm by rememberSaveable { mutableStateOf(68) }
    var tdsPpm by rememberSaveable { mutableStateOf(110) }

    val sectionTitle = mapOf(
        "machine" to "Machine", "peripherals" to "Peripherals", "brew" to "Brew defaults",
        "water" to "Water & maintenance", "display" to "Display & units", "sharing" to "Sharing",
        "calibration" to "Calibration", "advanced" to "Advanced", "about" to "About",
    )

    val current = section
    if (current != null) {
        BackHandler { section = null }
        Scaffold(
            topBar = { CremaPhoneBackBar(title = sectionTitle[current] ?: "Settings", onBack = { section = null }) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { inner ->
            Column(
                Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 24.dp),
            ) {
                // Phone settings render in the dense row layout; the tablet keeps
                // the default (roomy) — one CremaSettingsRow serves both (issue 26).
                CompositionLocalProvider(LocalSettingsRowDense provides true) {
                    when (current) {
                        "machine" -> MachineSection(vm, ui.let { it }, connected, onConnect)
                        "peripherals" -> PeripheralsSection(vm, scaleConnected, ui.scaleName, ui.grinderModel, onConnect, onOpenScale = { onNav("scale") })
                        "brew" -> BrewDefaultsSection(vm, ui.let { it })
                        "water" -> WaterSection(
                            vm, ui.let { it }, connected,
                            onRunCycle = { confirm.pendingCycle = it },
                            waterSource = waterSource, onWaterSource = { waterSource = it },
                            hardnessPpm = hardnessPpm, onHardness = { hardnessPpm = it },
                            tdsPpm = tdsPpm, onTds = { tdsPpm = it },
                        )
                        "display" -> DisplaySection(
                            vm, ui.themeMode, ui.keepScreenOnBrew,
                            density, { density = it },
                            screensaver, { screensaver = it },
                            tempUnit = ui.tempUnit,
                            weightUnit = ui.weightUnit,
                            pressureUnit = ui.pressureUnit,
                            volumeUnit = ui.volumeUnit,
                        )
                        "sharing" -> SharingSection(vm, ui.let { it }, confirm.launchSave, onResync = { confirm.pendingResync = true })
                        "calibration" -> CalibrationSection(
                            vm, ui.let { it }, connected,
                            tempOffset, { tempOffset = it },
                            onApplyFlow = { confirm.pendingFlowMultiplier = it },
                        )
                        "advanced" -> AdvancedSection(
                            vm, ui.let { it }, connected, onNav,
                            smoothPressure, { smoothPressure = it },
                            onStageLineFreq = { confirm.pendingLineFreq = it },
                            onStageHeaterVoltage = { confirm.pendingHeaterVoltage = it },
                            onResetPrefs = { confirm.confirmResetPrefs = true },
                            onErase = { confirm.confirmErase = true },
                        )
                        "about" -> AboutSection()
                    }
                }
            }
        }
        return
    }

    // ── Section list ─────────────────────────────────────────────────────────
    Scaffold(
        topBar = { CremaPhoneTopBar(title = "Settings") },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CremaEdge)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Machine hero — pushes the Machine section.
            Surface(
                onClick = { section = "machine" },
                shape = RoundedCornerShape(CremaCardSpec.phoneRadius),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(56.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            PhIcon("sliders-horizontal", sizeDp = 26, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Decent DE1", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (connected) listOfNotNull(firmwareLabel(ui.de1MachineInfo, ui.de1Firmware).takeIf { it != "—" }?.let { "Firmware $it" }, ui.headTemp?.let { formatTemp(it, ui.tempUnit) }).joinToString(" · ").ifEmpty { "Connected" }
                            else "Not connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CremaStatusDot(connected)
                            Text(
                                if (connected) "Connected" else "Tap to connect",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (connected) CremaTheme.telemetry.success else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    PhIcon("caret-right", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            PHONE_SECTIONS.forEach { (group, items) ->
                Text(
                    group.uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.7.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 10.dp),
                )
                items.forEach { (id, icon, title) ->
                    val sub = when (id) {
                        "machine" -> if (connected) listOfNotNull("DE1", "connected", firmwareLabel(ui.de1MachineInfo, ui.de1Firmware).takeIf { it != "—" }?.let { "FW $it" }).joinToString(" · ") else "Not connected"
                        "peripherals" -> if (scaleConnected) (ui.scaleName ?: "Scale connected") else "Scale, grinder"
                        "brew" -> "Targets, shot behaviour"
                        "water" -> "Tank, cycles, reminders"
                        "display" -> "Theme, density, units"
                        "sharing" -> if (ui.visualizer.signedIn) "Visualizer · ${ui.visualizer.account?.name ?: "signed in"}" else "Visualizer account, sync"
                        "calibration" -> "Sensor offsets"
                        "advanced" -> "Telemetry, diagnostics, resets"
                        else -> "Version, licenses"
                    }
                    CremaPushRow(icon = icon, title = title, sub = sub, onClick = { section = id })
                }
            }
        }
    }
}

/* ───────────────────────── section bodies ──────────────────────────────── */

@Composable
private fun MachineSection(
    vm: MainViewModel,
    ui: coffee.crema.ui.MainUiState,
    connected: Boolean,
    onConnect: (String) -> Unit,
) {
    // Machine hero (proto .pst-mhero): icon + state + identity key-values.
    Column(Modifier.padding(horizontal = CremaEdge, vertical = 8.dp)) {
        Surface(shape = RoundedCornerShape(CremaCardSpec.phoneRadius), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CremaStatusDot(connected)
                    Text(
                        if (connected) "Connected · ${ui.machineState ?: "ready"}" else "Not connected",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (connected) CremaTheme.telemetry.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("DE1 · Crema", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeroKV("Firmware", if (connected) firmwareLabel(ui.de1MachineInfo, ui.de1Firmware) else "—")
                    HeroKV("Model", if (connected) machineModelLabel(ui.de1MachineInfo) else "—")
                    HeroKV("Board", if (connected) cpuBoardLabel(ui.de1MachineInfo) else "—")
                    HeroKV("BLE", if (connected) (ui.de1BluetoothAddress ?: "Paired") else "—")
                }
                CremaButton(
                    onClick = { onConnect("machine") },
                    variant = if (connected) CremaButtonVariant.Tonal else CremaButtonVariant.Filled,
                    icon = if (connected) "bluetooth-slash" else "bluetooth",
                    label = if (connected) "Disconnect" else "Connect DE1",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
    SettingsGroup("Connection") {
        CremaSettingsRow("Telemetry rate", "How often the chart samples live data.", notImplemented = true) { CremaSettingsSelect("50 Hz") }
        CremaSettingsRow("Keep DE1 awake while Crema is open", "Re-arms the DE1's sleep timer every minute so the machine stays ready.") {
            CremaSwitch(ui.suppressDe1Sleep, vm::setSuppressDe1Sleep)
        }
        CremaSettingsRow("Auto-connect", "Reconnect to this DE1 automatically after a dropout, and on launch. Turning it off forgets the device.") {
            CremaSwitch(ui.rememberedDe1Address != null, vm::setDe1AutoConnect, enabled = connected || ui.rememberedDe1Address != null)
        }
        val ghcOn = ghcModeOn(ui.de1MachineInfo) ?: false
        val ghcAvailable = connected && (ghcPresent(ui.de1MachineInfo) == true)
        CremaSettingsRow("Group Head Controller (GHC)", "Start shots from the machine's touch panel.", last = true, needsConnection = !connected) {
            CremaSwitch(ghcOn, { vm.setGhcMode(it) }, enabled = ghcAvailable)
        }
    }
    SettingsGroup("Identity") {
        CremaSettingsRow("Model") { CremaMonoReadout(machineModelLabel(ui.de1MachineInfo), strong = true) }
        CremaSettingsRow("Serial number") { CremaMonoReadout(serialLabel(ui.de1MachineInfo), strong = true) }
        CremaSettingsRow("CPU board") { CremaMonoReadout(cpuBoardLabel(ui.de1MachineInfo), strong = true) }
        CremaSettingsRow("Firmware") { CremaMonoReadout(firmwareLabel(ui.de1MachineInfo, ui.de1Firmware), strong = true) }
        CremaSettingsRow("Heater voltage", last = true) { CremaMonoReadout(heaterVoltageLabel(ui.de1MachineInfo), strong = true) }
    }
    SettingsGroup("Diagnostics") {
        CremaSettingsRow("Connection state") { CremaMonoReadout(if (connected) "Ready" else "Disconnected", strong = true) }
        CremaSettingsRow("GATT verified") { CremaStatusDot(connected) }
        CremaSettingsRow("Machine state", last = ui.machineError == null) { CremaMonoReadout(ui.machineState ?: "—", strong = true) }
        // Readable error copy (core `subStateErrorMessage`, web parity), only while erroring.
        ui.machineError?.let { err ->
            CremaSettingsRow("Machine error", last = true) { CremaMonoReadout(err, strong = true, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (hasCupWarmerPlate(ui.de1MachineInfo)) {
        SettingsGroup("Cup warmer") {
            val plate = cupWarmerTempValue(ui.de1MachineInfo) ?: 25
            CremaSettingsRow(
                "Plate temperature", "The Bengle warming plate's target.", last = true,
                needsConnection = !connected,
                dot = true, dotOn = plate > 0,
                onDot = { if (connected) vm.setCupWarmerTemp(if (plate > 0) 0 else 55) },
            ) {
                CremaStepper(
                    value = plate.toDouble(), unit = "°C", step = 1.0, min = 0.0, max = 80.0,
                    fmt = { "%.0f".format(it) }, style = CremaStepperStyle.BareCompact,
                    onChange = { if (connected) vm.setCupWarmerTemp(it.toInt()) },
                )
            }
        }
    }
}

@Composable
private fun PeripheralsSection(
    vm: MainViewModel,
    scaleConnected: Boolean,
    scaleName: String?,
    grinderModel: String,
    onConnect: (String) -> Unit,
    onOpenScale: () -> Unit,
) {
    SettingsGroup("Connected devices") {
        CremaSettingsRow(
            "Scale",
            if (scaleConnected) "${scaleName ?: "Connected"} · manage on the Scale page." else "Stop-on-weight & auto-tare need a scale.",
            last = true,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CremaStatusDot(scaleConnected)
                if (scaleConnected) {
                    CremaButton(onClick = onOpenScale, variant = CremaButtonVariant.Outlined, label = "Open")
                } else {
                    CremaButton(onClick = { onConnect("scale") }, variant = CremaButtonVariant.Outlined, icon = "bluetooth", label = "Pair")
                }
            }
        }
    }
    SettingsGroup("Grinder") {
        CremaSettingsRow("Grinder model", "Logged with shots & Visualizer uploads. Pairing isn't supported yet.", last = true) {
            CremaTextField(
                value = grinderModel,
                onValueChange = vm::setGrinderModel,
                placeholder = "e.g. Niche Zero",
                singleLine = true,
                modifier = Modifier.width(160.dp),
            )
        }
    }
}

@Composable
private fun BrewDefaultsSection(vm: MainViewModel, ui: coffee.crema.ui.MainUiState) {
    SettingsGroup("Targets") {
        val setDefs = { d: Float, r: Float, t: Float, p: Float -> vm.setBrewDefaults(d, r, t, p) }
        val dD = ui.defaultDoseG; val dR = ui.defaultRatio; val dT = ui.defaultBrewTempC; val dP = ui.defaultPreinfuseS
        CremaSettingsRow("Default dose", "Starting dose for a fresh shot.") {
            CremaStepper(value = dD.toDouble(), unit = "g", step = 0.1, min = 5.0, max = 30.0, fmt = { "%.1f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { setDefs(it.toFloat(), dR, dT, dP) })
        }
        CremaSettingsRow("Default ratio", "Target brew ratio (yield ÷ dose).") {
            CremaStepper(value = dR.toDouble(), unit = null, step = 0.1, min = 1.0, max = 5.0, fmt = { "1:%.1f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { setDefs(dD, it.toFloat(), dT, dP) })
        }
        CremaSettingsRow("Default brew temp", "Starting group temperature.") {
            CremaStepper(value = dT.toDouble(), unit = "°C", step = 0.5, min = 80.0, max = 100.0, fmt = { "%.1f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { setDefs(dD, dR, it.toFloat(), dP) })
        }
        CremaSettingsRow("Default pre-infusion", "Low-pressure soak before the main shot.", last = true, dot = true, dotOn = dP > 0f, onDot = { setDefs(dD, dR, dT, if (dP > 0f) 0f else 8f) }) {
            CremaStepper(value = dP.toDouble(), unit = "s", step = 1.0, min = 0.0, max = 60.0, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { setDefs(dD, dR, dT, it.toFloat()) })
        }
    }
    SettingsGroup("Shot behaviour") {
        CremaSettingsRow("Auto-tare on shot start", "Zero the scale automatically when extraction begins.") { CremaSwitch(ui.autoTare, vm::setAutoTare) }
        CremaSettingsRow("Stop on weight", "End the shot once the target yield is reached.") { CremaSwitch(ui.stopOnWeight, vm::setStopOnWeight) }
        CremaSettingsRow(
            "Max shot duration", "Hard time cap — also a Brew stop condition.",
            dot = true, dotOn = ui.maxShotDurationS > 0f,
            onDot = { vm.setMaxShotDuration(if (ui.maxShotDurationS > 0f) 0f else 60f) },
        ) {
            val maxDur = ui.maxShotDurationS.toInt()
            CremaStepper(
                value = maxDur.toDouble(), unit = "s", step = 5.0, min = 0.0, max = 300.0,
                fmt = { "%.0f".format(it) }, style = CremaStepperStyle.BareCompact,
                onChange = { vm.setMaxShotDuration(it.toFloat()) },
            )
        }
        CremaSettingsRow("Group flush before each shot", "Stabilise the group temperature with a short flush.") { CremaSwitch(ui.preFlush, vm::setPreFlush) }
        CremaSettingsRow("Auto-purge after steam", "Clear the steam wand automatically after steaming.") { CremaSwitch(ui.steamPurge, vm::setSteamPurge) }
        CremaSettingsRow("Steam eco", "Idle the steam boiler cooler between sessions to save power.", last = true) { CremaSwitch(ui.steamEco, vm::setSteamEco) }
    }
}

@Composable
private fun WaterSection(
    vm: MainViewModel,
    ui: coffee.crema.ui.MainUiState,
    connected: Boolean,
    onRunCycle: (String) -> Unit,
    waterSource: String, onWaterSource: (String) -> Unit,
    hardnessPpm: Int, onHardness: (Int) -> Unit,
    tdsPpm: Int, onTds: (Int) -> Unit,
) {
    SettingsGroup("Tank") {
        val mm = ui.waterLevelMm
        val low = ui.refillSoon()
        CremaSettingsRow(
            "Water tank",
            when {
                mm == null -> "Connect the DE1 to read the tank level."
                low -> "Low — refill soon."
                else -> "Tank level looks good."
            },
            last = true,
        ) {
            CremaMonoReadout(if (mm != null) "${mm.toInt()} mm" else "—", color = if (low) Color(0xFFDBA764) else MaterialTheme.colorScheme.onSurface)
        }
    }
    SettingsGroup("Cycles") {
        val machineIdle = ui.machineState?.startsWith("Idle") == true
        val cycleReady = connected && machineIdle
        val cycleSub = if (connected && !machineIdle) " Machine must be idle." else ""
        CremaSettingsRow("Descale", "Run the DE1's descale cycle.$cycleSub", needsConnection = !connected) {
            CremaButton(onClick = { onRunCycle("descale") }, variant = CremaButtonVariant.Outlined, enabled = cycleReady, icon = "play", label = "Run")
        }
        CremaSettingsRow("Group clean", "Run the DE1's cleaning cycle.$cycleSub", needsConnection = !connected) {
            CremaButton(onClick = { onRunCycle("clean") }, variant = CremaButtonVariant.Outlined, enabled = cycleReady, icon = "play", label = "Run")
        }
        CremaSettingsRow("Steam rinse", "Flush the steam wand.$cycleSub", last = true, needsConnection = !connected) {
            CremaButton(onClick = { onRunCycle("steam-rinse") }, variant = CremaButtonVariant.Outlined, enabled = cycleReady, icon = "play", label = "Run")
        }
    }
    SettingsGroup("Reminders") {
        val ro = ui.maintenanceReadout
        val m = ui.maintenance
        val filter = ro?.filterRow(m)
        PMaintRow(
            title = "Water filter",
            note = filter?.note ?: "Awaiting data.",
            value = filter?.let { "${it.value}${it.unit}" } ?: "—",
            pct = filter?.pct ?: 0f,
            due = filter?.due ?: false,
            onMarkDone = { vm.markFilterCleaned() },
        )
        val descale = ro?.descaleRow(m)
        PMaintRow(
            title = "Descale",
            note = descale?.note ?: "Awaiting data.",
            value = descale?.let { "${it.value} ${it.unit}" } ?: "—",
            pct = descale?.pct ?: 0f,
            due = descale?.due ?: false,
            onMarkDone = { vm.markDescaled() },
        )
        val clean = ro?.cleanRow(m)
        PMaintRow(
            title = "Group clean",
            note = clean?.note ?: "Awaiting data.",
            value = clean?.let { "${it.value} ${it.unit}" } ?: "—",
            pct = clean?.pct ?: 0f,
            due = clean?.due ?: false,
            onMarkDone = { vm.markCleaned() },
            last = true,
        )
    }
    SettingsGroup("Maintenance intervals") {
        val m2 = ui.maintenance
        CremaSettingsRow("Filter capacity", "Litres before a filter change is due.") {
            CremaStepper(value = m2.filterCapacityLitres, unit = "L", step = 5.0, min = 5.0, max = 500.0, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { vm.setFilterCapacity(it) })
        }
        CremaSettingsRow("Descale interval", "Litres of brew water between descales.") {
            CremaStepper(value = m2.descaleIntervalLitres, unit = "L", step = 10.0, min = 10.0, max = 1000.0, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { vm.setDescaleInterval(it) })
        }
        CremaSettingsRow("Clean cycle interval", "Hours of machine-on time between cleans.", last = true) {
            CremaStepper(value = m2.cleanIntervalHours, unit = "h", step = 1.0, min = 1.0, max = 500.0, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { vm.setCleanInterval(it) })
        }
    }
    SettingsGroup("Water chemistry") {
        CremaSettingsRow("Water source", "Tunes descale intervals.", notImplemented = true, stacked = true) {
            CremaSegmentedButton(
                options = listOf(SegOption("tap", "Tap"), SegOption("filtered", "Filtered"), SegOption("bottled", "Bottled"), SegOption("rpavlis", "RPavlis")),
                value = waterSource,
                onChange = onWaterSource,
                fillWidth = true,
            )
        }
        CremaSettingsRow("Hardness", "General hardness (GH).", notImplemented = true) {
            CremaStepper(value = hardnessPpm.toDouble(), unit = "ppm", step = 1.0, min = 0.0, max = 500.0, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { onHardness(it.toInt()) })
        }
        CremaSettingsRow("Total dissolved solids", "Measured TDS of your water.", last = true, notImplemented = true) {
            CremaStepper(value = tdsPpm.toDouble(), unit = "ppm", step = 1.0, min = 0.0, max = 1000.0, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { onTds(it.toInt()) })
        }
    }
}

@Composable
private fun DisplaySection(
    vm: MainViewModel,
    themeMode: String,
    keepScreenOnBrew: Boolean,
    density: String, onDensity: (String) -> Unit,
    screensaver: Boolean, onScreensaver: (Boolean) -> Unit,
    tempUnit: String,
    weightUnit: String,
    pressureUnit: String,
    volumeUnit: String,
) {
    SettingsGroup("Appearance") {
        CremaSettingsRow("Theme", "Crema defaults to dark — the machine app is dark-skinned.") {
            CremaSegmentedButton(
                options = listOf(SegOption("light", "Light"), SegOption("dark", "Dark"), SegOption("system", "Auto")),
                value = if (themeMode in setOf("light", "dark", "system")) themeMode else "dark",
                onChange = vm::setThemeMode,
                uniform = true,
            )
        }
        CremaSettingsRow("Density", "Card padding and control sizing.", notImplemented = true) {
            CremaSegmentedButton(
                options = listOf(SegOption("compact", "Compact"), SegOption("comfortable", "Cozy")),
                value = density,
                onChange = onDensity,
                uniform = true,
            )
        }
        CremaSettingsRow("Screensaver", "Dim the display after a period idle.", notImplemented = true) { CremaSwitch(screensaver, onScreensaver) }
        CremaSettingsRow("Keep screen on while brewing", "Hold the display awake during a shot.", last = true) { CremaSwitch(keepScreenOnBrew, vm::setKeepScreenOnBrew) }
    }
    SettingsGroup("Units") {
        CremaSettingsRow("Temperature", "Units for every temperature readout.") {
            CremaSegmentedButton(
                options = listOf(SegOption("C", "°C"), SegOption("F", "°F")),
                value = tempUnit,
                onChange = vm::setTempUnit,
                groupWidth = 144.dp,
            )
        }
        CremaSettingsRow("Weight", "Units for dose and yield.") {
            CremaSegmentedButton(
                options = listOf(SegOption("g", "g"), SegOption("oz", "oz")),
                value = weightUnit,
                onChange = vm::setWeightUnit,
                groupWidth = 144.dp,
            )
        }
        CremaSettingsRow("Pressure", "Units for the pressure channel.") {
            CremaSegmentedButton(
                options = listOf(SegOption("bar", "bar"), SegOption("psi", "psi")),
                value = pressureUnit,
                onChange = vm::setPressureUnit,
                groupWidth = 144.dp,
            )
        }
        CremaSettingsRow("Volume", "Units for water and yield volume.", last = true) {
            CremaSegmentedButton(
                options = listOf(SegOption("ml", "ml"), SegOption("floz", "fl oz")),
                value = volumeUnit,
                onChange = vm::setVolumeUnit,
                groupWidth = 144.dp,
            )
        }
    }
}

@Composable
private fun SharingSection(
    vm: MainViewModel,
    ui: coffee.crema.ui.MainUiState,
    launchSave: (String, String?) -> Unit,
    onResync: () -> Unit,
) {
    val vz = ui.visualizer
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.visualizer.refreshAccount() }
    val openUrl: (String) -> Unit = { url ->
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
    SettingsGroup("Visualizer") {
        when {
            !vz.configured -> {
                CremaSettingsRow("Account", "Visualizer isn't configured in this build (no client id).", last = true) {
                    CremaButton(onClick = { openUrl("https://visualizer.coffee") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Visit")
                }
            }
            vz.signedIn -> {
                CremaSettingsRow("Account", vz.account?.name ?: "Signed in") {
                    CremaButton(onClick = { vm.visualizer.signOut() }, variant = CremaButtonVariant.Outlined, label = "Sign out")
                }
                CremaSettingsRow("Test connection", "Round-trips your token against the API.", last = true) {
                    CremaButton(onClick = { vm.visualizer.testConnection() }, variant = CremaButtonVariant.Tonal, icon = "plugs-connected", enabled = !vz.busy, label = "Test")
                }
            }
            else -> {
                CremaSettingsRow("Account", "Back up, share and compare shots on visualizer.coffee.", last = true) {
                    CremaButton(onClick = { vm.visualizer.beginSignIn(openUrl) }, variant = CremaButtonVariant.Filled, icon = "sign-in", enabled = !vz.busy, label = "Sign in")
                }
            }
        }
    }
    if (vz.signedIn) {
        SettingsGroup("Sync") {
            val unsyncedCount = ui.history.count { it.visualizerId == null }
            val lastSyncLabel = vz.lastShotSyncAt?.let {
                android.text.format.DateUtils.getRelativeTimeSpanString(it).toString()
            } ?: "never"
            CremaSettingsRow("Shots", "${ui.history.size} shot(s)" + (if (unsyncedCount > 0) " · $unsyncedCount unsynced" else "") + ". Last sync: $lastSyncLabel.") {
                CremaSegmentedButton(
                    options = listOf(SegOption("off", "Off"), SegOption("backup", "Push"), SegOption("pull", "Pull"), SegOption("two-way", "Both")),
                    value = vz.shotsDirection,
                    onChange = vm.visualizer::setShotsDirection,
                    uniform = true,
                )
            }
            CremaSettingsRow("Auto-sync new shots", "Upload each shot as it finishes (needs a pushing direction).") {
                CremaSwitch(vz.autoSync, vm.visualizer::setAutoSync, enabled = vz.shotsDirection == "backup" || vz.shotsDirection == "two-way")
            }
            CremaSettingsRow(
                "Sync now",
                if (unsyncedCount == 0) "All ${ui.history.size} local shots are on Visualizer." else "$unsyncedCount local shot(s) not uploaded yet.",
            ) {
                CremaButton(
                    onClick = { vm.visualizer.syncNow(ui.history) },
                    variant = CremaButtonVariant.Outlined,
                    icon = if (vz.shotsDirection == "pull") "cloud-arrow-down" else "cloud-arrow-up",
                    enabled = vz.shotsDirection != "off" && !vz.busy && !vz.syncing,
                    label = if (vz.syncing) "Syncing…" else "Sync",
                )
            }
            CremaSettingsRow("Re-sync shots", "Re-pull everything from Visualizer, de-duplicated.", last = true) {
                CremaButton(onClick = onResync, variant = CremaButtonVariant.Outlined, icon = "clock-counter-clockwise", enabled = !vz.busy && !vz.syncing, label = "Re-sync")
            }
        }
        SettingsGroup("Upload options") {
            CremaSettingsRow("Default privacy", "Public = community feed; unlisted = link only; private = just you. Shots can override this in History.") {
                CremaSegmentedButton(
                    options = listOf(SegOption("public", "Public"), SegOption("unlisted", "Unlisted"), SegOption("private", "Private")),
                    value = vz.privacy,
                    onChange = vm.visualizer::setPrivacy,
                    uniform = true,
                )
            }
            CremaSettingsRow("Include profile", "Attach the full recipe (every segment) to uploads.") { CremaSwitch(vz.includeProfile, vm.visualizer::setIncludeProfile) }
            CremaSettingsRow("Include tasting notes", "Attach your journal text to uploads. Ratings always ride along.", last = true) { CremaSwitch(vz.includeNotes, vm.visualizer::setIncludeNotes) }
        }
        var showLog by remember { mutableStateOf(false) }
        SettingsGroup("Recent activity") {
            CremaSettingsRow(
                "Sync log",
                if (vz.log.isEmpty()) "No sync activity yet." else "${vz.log.size} recent event(s).",
                last = !showLog || vz.log.isEmpty(),
            ) {
                CremaButton(onClick = { showLog = !showLog }, variant = CremaButtonVariant.Text, label = if (showLog) "Hide" else "Show", enabled = vz.log.isNotEmpty())
            }
            if (showLog) {
                vz.log.forEachIndexed { i, entry ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PhIcon(
                            when (entry.direction) { "push" -> "cloud-arrow-up"; "pull" -> "cloud-arrow-down"; "delete" -> "trash"; else -> "cloud" },
                            sizeDp = 15,
                            tint = if (entry.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            entry.error?.let {
                                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Text(
                            android.text.format.DateUtils.getRelativeTimeSpanString(entry.at).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (i != vz.log.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    SettingsGroup("Local export") {
        CremaSettingsRow("History export", "Your entire shot history as JSON — for spreadsheets and other tools.", last = true) {
            CremaButton(onClick = { launchSave("crema-history.json", vm.shotsJson(null)) }, variant = CremaButtonVariant.Outlined, icon = "download-simple", label = "Export")
        }
    }
}

@Composable
private fun CalibrationSection(
    vm: MainViewModel,
    ui: coffee.crema.ui.MainUiState,
    connected: Boolean,
    tempOffset: Double, onTempOffset: (Double) -> Unit,
    onApplyFlow: (Float) -> Unit,
) {
    SettingsGroup("Sensor calibration") {
        CremaSettingsRow("Temperature", "Shift every temperature reading.", notImplemented = true) {
            CremaStepper(value = tempOffset, unit = "°C", step = 0.1, min = -5.0, max = 5.0, fmt = { "%+.1f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = onTempOffset)
        }
        CremaSettingsRow("Pressure", "Re-zero the pressure sensor at idle.", notImplemented = true) { CremaMonoReadout("0.0 bar", strong = true) }
        val mult = flowMultiplierValue(ui.de1MachineInfo) ?: 1.0
        var flowDraft by remember(mult) { mutableStateOf(mult) }
        val flowDirty = kotlin.math.abs(flowDraft - mult) > 0.0001
        CremaSettingsRow("Flow", "Scale the flow-meter reading. Apply commits after a typed confirm.", needsConnection = !connected) {
            CremaStepper(value = flowDraft, unit = "×", step = 0.01, min = 0.13, max = 2.0, fmt = { "%.2f".format(it) }, style = CremaStepperStyle.BareCompact, onChange = { flowDraft = it })
        }
        CremaSettingsRow("", null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CremaButton(onClick = { onApplyFlow(flowDraft.toFloat()) }, variant = CremaButtonVariant.Filled, enabled = connected && flowDirty, label = "Apply")
                CremaButton(onClick = { onApplyFlow(1.0f) }, variant = CremaButtonVariant.Outlined, enabled = connected && kotlin.math.abs(mult - 1.0) > 0.0001, label = "Reset")
            }
        }
        CremaSettingsRow("Last read", "Flow multiplier reported by the DE1.", last = true) {
            CremaMonoReadout(flowMultiplierValue(ui.de1MachineInfo)?.let { String.format("×%.2f", it) } ?: "—", strong = true)
        }
    }
}

@Composable
private fun AdvancedSection(
    vm: MainViewModel,
    ui: coffee.crema.ui.MainUiState,
    connected: Boolean,
    onNav: (String) -> Unit,
    smoothPressure: Boolean, onSmoothPressure: (Boolean) -> Unit,
    onStageLineFreq: (String) -> Unit,
    onStageHeaterVoltage: (String) -> Unit,
    onResetPrefs: () -> Unit,
    onErase: () -> Unit,
) {
    SettingsGroup("Telemetry") {
        // The toggle reflects the user's OVERRIDE (auto/50/60), not the resolved
        // value — so it stays on "Auto" once the detector locks a frequency.
        val freqValue = when (ui.lineFreqOverride) {
            50.0f -> "50"
            60.0f -> "60"
            else -> "auto"
        }
        // On Auto, poll the resolved Hz (~1s; the DE1 locks ~1s into the first
        // shot) so the hint shows it live — mirrors the web's auto-detect hint.
        if (connected && ui.lineFreqOverride == 0.0f) {
            LaunchedEffect(Unit) { while (true) { vm.refreshLineFrequency(); delay(1000) } }
        }
        val detectedHz = ui.lineFreqHz?.takeIf { it > 0f }?.toInt()
        val freqSub = if (ui.lineFreqOverride == 0.0f) {
            "Auto-detected from the DE1's sample stream. " +
                (detectedHz?.let { "Currently locked at $it Hz." } ?: "Locks ~1s into the first shot.")
        } else {
            "Pinned at ${ui.lineFreqOverride.toInt()} Hz — switch to Auto to let the detector run."
        }
        CremaSettingsRow("AC mains frequency", freqSub, needsConnection = !connected) {
            CremaSegmentedButton(
                options = listOf(SegOption("auto", "Auto"), SegOption("50", "50"), SegOption("60", "60")),
                value = freqValue,
                onChange = { if (it == "auto") vm.setLineFrequency(0.0f) else onStageLineFreq(it) },
                enabled = connected,
                uniform = true,
            )
        }
        CremaSettingsRow("Smooth pressure curve", "Filter chart noise on the live readout.", last = true, notImplemented = true) { CremaSwitch(smoothPressure, onSmoothPressure) }
    }
    SettingsGroup("Diagnostics") {
        CremaSettingsRow("Show debug / event-log panel", "Surfaces the raw BLE event log below.", last = !ui.showDebugPanel) {
            CremaSwitch(ui.showDebugPanel, vm::setShowDebugPanel)
        }
        if (ui.showDebugPanel) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth()
                        .height(220.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val lines = ui.eventLog.takeLast(100)
                    if (lines.isEmpty()) {
                        Text("No events yet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        lines.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                CremaButton(onClick = { onNav("debug") }, variant = CremaButtonVariant.Text, icon = "bug", label = "Open full console")
            }
        }
    }
    SettingsGroup("Service-grade") {
        // null until the connected DE1 reports its mains voltage (raw HeaterVoltage MMR).
        val hv = heaterVoltageValue(ui.de1MachineInfo)
        CremaSettingsRow("Mains heater voltage", "Wrong voltage damages the heater — service only.", last = true, needsConnection = !connected) {
            CremaSegmentedButton(
                options = listOf(SegOption("120", "120 V"), SegOption("230", "230 V")),
                // No segment selected until the machine reports — matches the "—" in the
                // Machine readout above, rather than misleadingly defaulting to 230 V.
                value = hv ?: "",
                onChange = { if (it != hv) onStageHeaterVoltage(it) },
                enabled = connected,
                uniform = true,
            )
        }
    }
    SettingsGroup("Reset") {
        CremaSettingsRow("Reset preferences", "Restore Crema's settings to defaults.") {
            CremaButton(onClick = onResetPrefs, variant = CremaButtonVariant.Text, label = "Reset")
        }
        CremaSettingsRow("Erase all data", "Delete every profile, bean and shot.", last = true) {
            CremaButton(onClick = onErase, variant = CremaButtonVariant.Text, danger = true, label = "Erase")
        }
    }
}

@Composable
private fun AboutSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val openLink: (String) -> Unit = { url ->
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
    SettingsGroup("Version") {
        val appVersion = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev"
        }
        CremaSettingsRow("App") { CremaMonoReadout(appVersion, strong = true) }
        val coreVer = remember { runCatching { coffee.crema.core.coreVersion() }.getOrNull() ?: "—" }
        CremaSettingsRow("Core") { CremaMonoReadout("de1-core v$coreVer · UniFFI", strong = true) }
        CremaSettingsRow("Machine", last = true) { CremaMonoReadout("Decent DE1", strong = true) }
    }
    SettingsGroup("Project") {
        CremaSettingsRow("Project repository", "Source, issues, and release notes.") {
            CremaButton(onClick = { openLink("https://github.com/geota/crema") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Open")
        }
        CremaSettingsRow("Decent Espresso", "The folks who make the DE1.") {
            CremaButton(onClick = { openLink("https://decentespresso.com") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Visit")
        }
        CremaSettingsRow("Visualizer", "Optional cloud sync for shots and beans.", last = true) {
            CremaButton(onClick = { openLink("https://visualizer.coffee") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Visit")
        }
    }
    SettingsGroup("Open source libraries") {
        val libs = listOf(
            "Kotlin & Jetpack Compose" to "Language + UI toolkit",
            "kotlinx.serialization / coroutines" to "JSON + structured concurrency",
            "Nordic Kotlin-BLE-Library" to "The BLE stack under the DE1 link",
            "UniFFI & JNA" to "Rust core bindings + FFI dispatch",
            "Rust, serde, thiserror" to "The shared de1 core",
            "phosphor-icon" to "Icon set (Compose ImageVectors)",
            "OkHttp" to "Visualizer HTTP client",
            "Decent de1app & reaprime" to "Protocol documentation lineage",
        )
        libs.forEachIndexed { i, (name, desc) ->
            CremaSettingsRow(name, desc, last = i == libs.lastIndex) {}
        }
    }
    SettingsGroup("Legal") {
        CremaSettingsRow("Terms of Service", "License, hardware-risk disclaimer, limitation of liability.", notImplemented = true) { CremaMonoReadout("Ships with 1.0") }
        CremaSettingsRow("Privacy Policy", "Crema is local-only. No analytics, no tracking.", last = true, notImplemented = true) { CremaMonoReadout("Ships with 1.0") }
    }
}

/* ───────────────────────── local primitives ────────────────────────────── */

@Composable
private fun HeroKV(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CremaMonoReadout(value, strong = true)
    }
}

// Maintenance burn-down row (proto .pst-burn).
@Composable
private fun PMaintRow(
    title: String,
    note: String,
    value: String,
    pct: Float,
    due: Boolean,
    onMarkDone: () -> Unit,
    last: Boolean = false,
) {
    val amber = Color(0xFFDBA764)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                if (due) "$value · due" else value,
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, fontFeatureSettings = "tnum"),
                color = if (due) amber else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(999.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).fillMaxHeight()
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (due) amber else MaterialTheme.colorScheme.primary),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            CremaButton(onClick = onMarkDone, variant = CremaButtonVariant.Text, label = "Mark done")
        }
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

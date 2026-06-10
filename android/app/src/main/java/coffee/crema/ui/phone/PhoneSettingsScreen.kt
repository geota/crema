package coffee.crema.ui.phone

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.*
import coffee.crema.ui.screens.cpuBoardLabel
import coffee.crema.ui.screens.cupWarmerTempValue
import coffee.crema.ui.screens.flowMultiplierValue
import coffee.crema.ui.screens.ghcModeOn
import coffee.crema.ui.screens.ghcPresent
import coffee.crema.ui.screens.hasCupWarmerPlate
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

    // ── Staged destructive-action confirms (tablet parity) ──────────────────
    var confirmResetPrefs by rememberSaveable { mutableStateOf(false) }
    var confirmErase by rememberSaveable { mutableStateOf(false) }
    var pendingHeaterVoltage by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLineFreq by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFlowMultiplier by rememberSaveable { mutableStateOf<Float?>(null) }
    var pendingCycle by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingResync by rememberSaveable { mutableStateOf(false) }

    if (confirmResetPrefs) CremaConfirmDialog(
        title = "Reset preferences?",
        body = "Crema's settings return to their defaults. Your beans, profiles and shots are kept.",
        confirmLabel = "Reset",
        onConfirm = { vm.resetPreferences(); confirmResetPrefs = false },
        onDismiss = { confirmResetPrefs = false },
    )
    if (confirmErase) CremaConfirmDialog(
        title = "Erase all data?",
        body = "Every bean, roaster, shot and custom profile will be permanently deleted. Built-in profiles remain. This can't be undone.",
        confirmLabel = "Erase everything",
        icon = "trash",
        danger = true,
        requireTyped = "ERASE",
        onConfirm = { vm.eraseAll(); confirmErase = false },
        onDismiss = { confirmErase = false },
    )
    pendingHeaterVoltage?.let { volts ->
        CremaConfirmDialog(
            title = "Confirm machine voltage",
            body = "Wrong voltage on your mains can permanently damage your heater. Verify your wall outlet matches before proceeding, then type $volts to confirm.",
            confirmLabel = "Set $volts V",
            danger = true,
            requireTyped = volts,
            onConfirm = {
                volts.toIntOrNull()?.let { vm.setHeaterVoltage(it) }
                pendingHeaterVoltage = null
            },
            onDismiss = { pendingHeaterVoltage = null },
        )
    }
    pendingLineFreq?.let { hz ->
        CremaConfirmDialog(
            title = "Confirm AC mains frequency",
            body = "Wrong AC frequency mis-calibrates the volume integrator (no hardware damage, but shot volumes drift up to 5 %). Verify your mains frequency, then type $hz to confirm.",
            confirmLabel = "Set $hz Hz",
            danger = true,
            requireTyped = hz,
            onConfirm = {
                hz.toFloatOrNull()?.let { vm.setLineFrequency(it) }
                pendingLineFreq = null
            },
            onDismiss = { pendingLineFreq = null },
        )
    }
    pendingFlowMultiplier?.let { mult ->
        CremaConfirmDialog(
            title = "Apply flow calibration?",
            body = "Every flow and volume estimate scales by this multiplier (×${String.format("%.2f", mult)}). Calibrate only against a known reference. Type flow to confirm.",
            confirmLabel = "Apply ×${String.format("%.2f", mult)}",
            requireTyped = "flow",
            onConfirm = { vm.setFlowMultiplier(mult); pendingFlowMultiplier = null },
            onDismiss = { pendingFlowMultiplier = null },
        )
    }
    pendingCycle?.let { cycle ->
        val label = when (cycle) { "descale" -> "descale"; "clean" -> "cleaning"; else -> "steam rinse" }
        CremaConfirmDialog(
            title = "Start the $label cycle?",
            body = when (cycle) {
                "descale" -> "Have descaling solution in the tank and a container under the group. The cycle takes several minutes."
                "clean" -> "Fit the blind basket with cleaning tablet before starting. The cycle takes several minutes."
                else -> "Runs a short steam-wand rinse. Keep the wand over the drip tray."
            },
            confirmLabel = "Start",
            onConfirm = {
                when (cycle) {
                    "descale" -> vm.startDescale()
                    "clean" -> vm.startClean()
                    else -> vm.startSteamRinse()
                }
                pendingCycle = null
            },
            onDismiss = { pendingCycle = null },
        )
    }
    if (pendingResync) CremaConfirmDialog(
        title = "Re-pull every shot?",
        body = "Re-pulls your entire Visualizer history from the beginning. Existing shots are de-duplicated against what you already have.",
        confirmLabel = "Re-pull",
        onConfirm = { vm.visualizer.resyncAllShots(ui.history); pendingResync = false },
        onDismiss = { pendingResync = false },
    )

    // SAF export plumbing.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = pendingExport; pendingExport = null
        if (uri != null && t != null) vm.writeTextToUri(uri, t)
    }
    val launchSave: (String, String?) -> Unit = { name, content ->
        if (content != null) { pendingExport = content; saveLauncher.launch(name) }
    }

    // Local design-faithful prefs (pilled rows keep local state only).
    var density by rememberSaveable { mutableStateOf("comfortable") }
    var unitTemp by rememberSaveable { mutableStateOf("c") }
    var unitWeight by rememberSaveable { mutableStateOf("g") }
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
                when (current) {
                    "machine" -> MachineSection(vm, ui.let { it }, connected, onConnect)
                    "peripherals" -> PeripheralsSection(vm, scaleConnected, ui.scaleName, ui.grinderModel, onConnect, onOpenScale = { onNav("scale") })
                    "brew" -> BrewDefaultsSection(vm, ui.let { it })
                    "water" -> WaterSection(
                        vm, ui.let { it }, connected,
                        onRunCycle = { pendingCycle = it },
                        waterSource = waterSource, onWaterSource = { waterSource = it },
                        hardnessPpm = hardnessPpm, onHardness = { hardnessPpm = it },
                        tdsPpm = tdsPpm, onTds = { tdsPpm = it },
                    )
                    "display" -> DisplaySection(
                        vm, ui.themeMode, ui.keepScreenOnBrew,
                        density, { density = it },
                        screensaver, { screensaver = it },
                        unitTemp, { unitTemp = it },
                        unitWeight, { unitWeight = it },
                    )
                    "sharing" -> SharingSection(vm, ui.let { it }, launchSave, onResync = { pendingResync = true })
                    "calibration" -> CalibrationSection(
                        vm, ui.let { it }, connected,
                        tempOffset, { tempOffset = it },
                        onApplyFlow = { pendingFlowMultiplier = it },
                    )
                    "advanced" -> AdvancedSection(
                        vm, ui.let { it }, connected, onNav,
                        smoothPressure, { smoothPressure = it },
                        onStageLineFreq = { pendingLineFreq = it },
                        onStageHeaterVoltage = { pendingHeaterVoltage = it },
                        onResetPrefs = { confirmResetPrefs = true },
                        onErase = { confirmErase = true },
                    )
                    "about" -> AboutSection()
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
                shape = RoundedCornerShape(18.dp),
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
                            if (connected) listOfNotNull(ui.de1Firmware?.let { "Firmware $it" }, ui.headTemp?.let { "%.1f °C".format(it) }).joinToString(" · ").ifEmpty { "Connected" }
                            else "Not connected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PStatusDot(connected)
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
                        "machine" -> if (connected) listOfNotNull("DE1", "connected", ui.de1Firmware?.let { "FW $it" }).joinToString(" · ") else "Not connected"
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
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PStatusDot(connected)
                    Text(
                        if (connected) "Connected · ${ui.machineState ?: "ready"}" else "Not connected",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (connected) CremaTheme.telemetry.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("DE1 · Crema", style = MaterialTheme.typography.titleMedium)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeroKV("Firmware", if (connected) (ui.de1Firmware ?: "—") else "—")
                    HeroKV("Model", if (connected) machineModelLabel(ui.de1MachineInfo) else "—")
                    HeroKV("Board", if (connected) cpuBoardLabel(ui.de1MachineInfo) else "—")
                    HeroKV("BLE", if (connected) "Paired" else "—")
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
        PRow("Telemetry rate", "How often the chart samples live data.", notImplemented = true) { PSelect("50 Hz") }
        PRow("Keep DE1 awake while Crema is open", "Re-arms the DE1's sleep timer every minute so the machine stays ready.") {
            CremaSwitch(ui.suppressDe1Sleep, vm::setSuppressDe1Sleep)
        }
        val ghcOn = ghcModeOn(ui.de1MachineInfo) ?: false
        val ghcAvailable = connected && (ghcPresent(ui.de1MachineInfo) == true)
        PRow("Group Head Controller (GHC)", "Start shots from the machine's touch panel.", last = true, needsConnection = !connected) {
            CremaSwitch(ghcOn, { vm.setGhcMode(it) }, enabled = ghcAvailable)
        }
    }
    SettingsGroup("Identity") {
        PRow("Model") { PMono(machineModelLabel(ui.de1MachineInfo), strong = true) }
        PRow("Serial number") { PMono(serialLabel(ui.de1MachineInfo), strong = true) }
        PRow("CPU board") { PMono(cpuBoardLabel(ui.de1MachineInfo), strong = true) }
        PRow("Firmware") { PMono(ui.de1Firmware ?: "—", strong = true) }
        PRow("Heater voltage", last = true) { PMono(heaterVoltageLabel(ui.de1MachineInfo), strong = true) }
    }
    SettingsGroup("Diagnostics") {
        PRow("Connection state") { PMono(if (connected) "Ready" else "Disconnected", strong = true) }
        PRow("GATT verified") { PStatusDot(connected) }
        PRow("Machine state", last = true) { PMono(ui.machineState ?: "—", strong = true) }
    }
    if (hasCupWarmerPlate(ui.de1MachineInfo)) {
        SettingsGroup("Cup warmer") {
            val plate = cupWarmerTempValue(ui.de1MachineInfo) ?: 25
            PRow("Plate temperature", "The Bengle warming plate's target.", last = true, needsConnection = !connected) {
                PStepper(
                    "$plate", "°C",
                    { if (connected) vm.setCupWarmerTemp((plate - 1).coerceAtLeast(0)) },
                    { if (connected) vm.setCupWarmerTemp((plate + 1).coerceAtMost(80)) },
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
        PRow(
            "Scale",
            if (scaleConnected) "${scaleName ?: "Connected"} · manage on the Scale page." else "Stop-on-weight & auto-tare need a scale.",
            last = true,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PStatusDot(scaleConnected)
                if (scaleConnected) {
                    CremaButton(onClick = onOpenScale, variant = CremaButtonVariant.Outlined, label = "Open")
                } else {
                    CremaButton(onClick = { onConnect("scale") }, variant = CremaButtonVariant.Outlined, icon = "bluetooth", label = "Pair")
                }
            }
        }
    }
    SettingsGroup("Grinder") {
        PRow("Grinder model", "Logged with shots & Visualizer uploads. Pairing isn't supported yet.", last = true) {
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
        PRow("Default dose", "Starting dose for a fresh shot.") {
            PStepper(String.format("%.1f", dD), "g", { setDefs((dD - 0.1f).coerceAtLeast(5f), dR, dT, dP) }, { setDefs((dD + 0.1f).coerceAtMost(30f), dR, dT, dP) })
        }
        PRow("Default ratio", "Target brew ratio (yield ÷ dose).") {
            PStepper("1:" + String.format("%.1f", dR), null, { setDefs(dD, (dR - 0.1f).coerceAtLeast(1f), dT, dP) }, { setDefs(dD, (dR + 0.1f).coerceAtMost(5f), dT, dP) })
        }
        PRow("Default brew temp", "Starting group temperature.") {
            PStepper(String.format("%.1f", dT), "°C", { setDefs(dD, dR, (dT - 0.5f).coerceAtLeast(80f), dP) }, { setDefs(dD, dR, (dT + 0.5f).coerceAtMost(100f), dP) })
        }
        PRow("Default pre-infusion", "Low-pressure soak before the main shot.", last = true) {
            PStepper("${dP.toInt()}", "s", { setDefs(dD, dR, dT, (dP - 1f).coerceAtLeast(0f)) }, { setDefs(dD, dR, dT, (dP + 1f).coerceAtMost(60f)) })
        }
    }
    SettingsGroup("Shot behaviour") {
        PRow("Auto-tare on shot start", "Zero the scale automatically when extraction begins.") { CremaSwitch(ui.autoTare, vm::setAutoTare) }
        PRow("Stop on weight", "End the shot once the target yield is reached.") { CremaSwitch(ui.stopOnWeight, vm::setStopOnWeight) }
        PRow("Max shot duration", "Hard time cap — also a Brew stop condition.") {
            val maxDur = ui.maxShotDurationS.toInt()
            PStepper(
                "$maxDur", "s",
                { vm.setMaxShotDuration((maxDur - 5).coerceAtLeast(0).toFloat()) },
                { vm.setMaxShotDuration((maxDur + 5).coerceAtMost(300).toFloat()) },
            )
        }
        PRow("Group flush before each shot", "Stabilise the group temperature with a short flush.", notImplemented = true) { CremaSwitch(ui.preFlush, vm::setPreFlush) }
        PRow("Auto-purge after steam", "Clear the steam wand automatically after steaming.", notImplemented = true) { CremaSwitch(ui.steamPurge, vm::setSteamPurge) }
        PRow("Steam eco", "Idle the steam boiler cooler between sessions to save power.", last = true) { CremaSwitch(ui.steamEco, vm::setSteamEco) }
    }
}

private const val LOW_TANK_MM_PHONE = 20f

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
        val low = mm != null && mm < LOW_TANK_MM_PHONE
        PRow(
            "Water tank",
            when {
                mm == null -> "Connect the DE1 to read the tank level."
                low -> "Low — refill soon."
                else -> "Tank level looks good."
            },
            last = true,
        ) {
            PMono(if (mm != null) "${mm.toInt()} mm" else "—", color = if (low) Color(0xFFDBA764) else MaterialTheme.colorScheme.onSurface)
        }
    }
    SettingsGroup("Cycles") {
        val machineIdle = ui.machineState?.startsWith("Idle") == true
        val cycleReady = connected && machineIdle
        val cycleSub = if (connected && !machineIdle) " Machine must be idle." else ""
        PRow("Descale", "Run the DE1's descale cycle.$cycleSub", needsConnection = !connected) {
            CremaButton(onClick = { onRunCycle("descale") }, variant = CremaButtonVariant.Outlined, enabled = cycleReady, icon = "play", label = "Run")
        }
        PRow("Group clean", "Run the DE1's cleaning cycle.$cycleSub", needsConnection = !connected) {
            CremaButton(onClick = { onRunCycle("clean") }, variant = CremaButtonVariant.Outlined, enabled = cycleReady, icon = "play", label = "Run")
        }
        PRow("Steam rinse", "Flush the steam wand.$cycleSub", last = true, needsConnection = !connected) {
            CremaButton(onClick = { onRunCycle("steam-rinse") }, variant = CremaButtonVariant.Outlined, enabled = cycleReady, icon = "play", label = "Run")
        }
    }
    SettingsGroup("Reminders") {
        val ro = ui.maintenanceReadout
        val m = ui.maintenance
        PMaintRow(
            title = "Water filter",
            note = ro?.let { "${String.format("%.1f", it.filterUsedLitres)} L of ${m.filterCapacityLitres.toInt()} L used" } ?: "Awaiting data.",
            value = ro?.let { "${it.filterPercent.toInt()}%" } ?: "—",
            pct = ro?.let { (it.filterPercent / 100.0).toFloat() } ?: 0f,
            due = ro?.let { !it.filterOk } ?: false,
            onMarkDone = { vm.markFilterCleaned() },
        )
        PMaintRow(
            title = "Descale",
            note = ro?.let { "${String.format("%.0f", it.descaleSinceLitres)} L since last descale · every ${m.descaleIntervalLitres.toInt()} L" } ?: "Awaiting data.",
            value = ro?.let { "${String.format("%.0f", it.descaleSinceLitres)} L" } ?: "—",
            pct = ro?.let { if (m.descaleIntervalLitres > 0.0) (it.descaleSinceLitres / m.descaleIntervalLitres).toFloat() else 0f } ?: 0f,
            due = ro?.let { !it.descaleOk } ?: false,
            onMarkDone = { vm.markDescaled() },
        )
        PMaintRow(
            title = "Group clean",
            note = ro?.let { "${it.cleanSinceHours} h since last clean · every ${m.cleanIntervalHours.toInt()} h" } ?: "Awaiting data.",
            value = ro?.let { "${it.cleanSinceHours} h" } ?: "—",
            pct = ro?.let { if (m.cleanIntervalHours > 0.0) (it.cleanSinceHours.toDouble() / m.cleanIntervalHours).toFloat() else 0f } ?: 0f,
            due = ro?.let { !it.cleanOk } ?: false,
            onMarkDone = { vm.markCleaned() },
            last = true,
        )
    }
    SettingsGroup("Maintenance intervals") {
        val m2 = ui.maintenance
        PRow("Filter capacity", "Litres before a filter change is due.") {
            PStepper("${m2.filterCapacityLitres.toInt()}", "L", { vm.setFilterCapacity(m2.filterCapacityLitres - 5.0) }, { vm.setFilterCapacity(m2.filterCapacityLitres + 5.0) })
        }
        PRow("Descale interval", "Litres of brew water between descales.") {
            PStepper("${m2.descaleIntervalLitres.toInt()}", "L", { vm.setDescaleInterval(m2.descaleIntervalLitres - 10.0) }, { vm.setDescaleInterval(m2.descaleIntervalLitres + 10.0) })
        }
        PRow("Clean cycle interval", "Hours of machine-on time between cleans.", last = true) {
            PStepper("${m2.cleanIntervalHours.toInt()}", "h", { vm.setCleanInterval(m2.cleanIntervalHours - 1.0) }, { vm.setCleanInterval(m2.cleanIntervalHours + 1.0) })
        }
    }
    SettingsGroup("Water chemistry") {
        PRow("Water source", "Your feed water — tunes descale intervals.", notImplemented = true) {
            CremaSegmentedButton(
                options = listOf(SegOption("tap", "Tap"), SegOption("filtered", "Filtered"), SegOption("bottled", "Bottled")),
                value = waterSource,
                onChange = onWaterSource,
            )
        }
        PRow("Hardness", "General hardness (GH).", notImplemented = true) {
            PStepper("$hardnessPpm", "ppm", { onHardness((hardnessPpm - 1).coerceAtLeast(0)) }, { onHardness((hardnessPpm + 1).coerceAtMost(500)) })
        }
        PRow("Total dissolved solids", "Measured TDS of your water.", last = true, notImplemented = true) {
            PStepper("$tdsPpm", "ppm", { onTds((tdsPpm - 1).coerceAtLeast(0)) }, { onTds((tdsPpm + 1).coerceAtMost(1000)) })
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
    unitTemp: String, onUnitTemp: (String) -> Unit,
    unitWeight: String, onUnitWeight: (String) -> Unit,
) {
    SettingsGroup("Appearance") {
        PRow("Theme", "Crema defaults to dark — the machine app is dark-skinned.") {
            CremaSegmentedButton(
                options = listOf(SegOption("light", "Light"), SegOption("dark", "Dark"), SegOption("system", "Auto")),
                value = if (themeMode in setOf("light", "dark", "system")) themeMode else "dark",
                onChange = vm::setThemeMode,
            )
        }
        PRow("Density", "Card padding and control sizing.", notImplemented = true) {
            CremaSegmentedButton(
                options = listOf(SegOption("compact", "Compact"), SegOption("comfortable", "Cozy")),
                value = density,
                onChange = onDensity,
            )
        }
        PRow("Screensaver", "Dim the display after a period idle.", notImplemented = true) { CremaSwitch(screensaver, onScreensaver) }
        PRow("Keep screen on while brewing", "Hold the display awake during a shot.", last = true) { CremaSwitch(keepScreenOnBrew, vm::setKeepScreenOnBrew) }
    }
    SettingsGroup("Units") {
        PRow("Temperature", "Units for every temperature readout.", notImplemented = true) {
            CremaSegmentedButton(
                options = listOf(SegOption("c", "°C"), SegOption("f", "°F")),
                value = unitTemp,
                onChange = onUnitTemp,
            )
        }
        PRow("Weight", "Units for dose and yield.", notImplemented = true) {
            CremaSegmentedButton(
                options = listOf(SegOption("g", "g"), SegOption("oz", "oz")),
                value = unitWeight,
                onChange = onUnitWeight,
            )
        }
        PRow("Pressure", "Units for the pressure channel.", notImplemented = true) { PSelect("bar") }
        PRow("Volume", "Units for water and yield volume.", last = true, notImplemented = true) { PSelect("ml") }
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
                PRow("Account", "Visualizer isn't configured in this build (no client id).", last = true) {
                    CremaButton(onClick = { openUrl("https://visualizer.coffee") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Visit")
                }
            }
            vz.signedIn -> {
                PRow("Account", vz.account?.name ?: "Signed in") {
                    CremaButton(onClick = { vm.visualizer.signOut() }, variant = CremaButtonVariant.Outlined, label = "Sign out")
                }
                PRow("Test connection", "Round-trips your token against the API.", last = true) {
                    CremaButton(onClick = { vm.visualizer.testConnection() }, variant = CremaButtonVariant.Tonal, icon = "plugs-connected", enabled = !vz.busy, label = "Test")
                }
            }
            else -> {
                PRow("Account", "Back up, share and compare shots on visualizer.coffee.", last = true) {
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
            PRow("Shots", "${ui.history.size} shot(s)" + (if (unsyncedCount > 0) " · $unsyncedCount unsynced" else "") + ". Last sync: $lastSyncLabel.") {
                CremaSegmentedButton(
                    options = listOf(SegOption("off", "Off"), SegOption("backup", "Push"), SegOption("pull", "Pull"), SegOption("two-way", "Both")),
                    value = vz.shotsDirection,
                    onChange = vm.visualizer::setShotsDirection,
                )
            }
            PRow("Auto-sync new shots", "Upload each shot as it finishes (needs a pushing direction).") {
                CremaSwitch(vz.autoSync, vm.visualizer::setAutoSync, enabled = vz.shotsDirection == "backup" || vz.shotsDirection == "two-way")
            }
            PRow(
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
            PRow("Re-sync shots", "Re-pull everything from Visualizer, de-duplicated.", last = true) {
                CremaButton(onClick = onResync, variant = CremaButtonVariant.Outlined, icon = "clock-counter-clockwise", enabled = !vz.busy && !vz.syncing, label = "Re-sync")
            }
        }
        SettingsGroup("Upload options") {
            PRow("Default privacy", "Public = community feed; unlisted = link only; private = just you. Shots can override this in History.") {
                CremaSegmentedButton(
                    options = listOf(SegOption("public", "Public"), SegOption("unlisted", "Unlisted"), SegOption("private", "Private")),
                    value = vz.privacy,
                    onChange = vm.visualizer::setPrivacy,
                )
            }
            PRow("Include profile", "Attach the full recipe (every segment) to uploads.") { CremaSwitch(vz.includeProfile, vm.visualizer::setIncludeProfile) }
            PRow("Include tasting notes", "Attach your journal text to uploads. Ratings always ride along.", last = true) { CremaSwitch(vz.includeNotes, vm.visualizer::setIncludeNotes) }
        }
        var showLog by remember { mutableStateOf(false) }
        SettingsGroup("Recent activity") {
            PRow(
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
        PRow("History export", "Your entire shot history as JSON — for spreadsheets and other tools.", last = true) {
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
        PRow("Temperature", "Shift every temperature reading.", notImplemented = true) {
            PStepper(String.format("%+.1f", tempOffset), "°C", { onTempOffset((tempOffset - 0.1).coerceAtLeast(-5.0)) }, { onTempOffset((tempOffset + 0.1).coerceAtMost(5.0)) })
        }
        PRow("Pressure", "Re-zero the pressure sensor at idle.", notImplemented = true) { PMono("0.0 bar", strong = true) }
        val mult = flowMultiplierValue(ui.de1MachineInfo) ?: 1.0
        var flowDraft by remember(mult) { mutableStateOf(mult) }
        val flowDirty = kotlin.math.abs(flowDraft - mult) > 0.0001
        PRow("Flow", "Scale the flow-meter reading. Apply commits after a typed confirm.", needsConnection = !connected) {
            PStepper(String.format("%.2f", flowDraft), "×", { flowDraft = (flowDraft - 0.01).coerceAtLeast(0.5) }, { flowDraft = (flowDraft + 0.01).coerceAtMost(1.5) })
        }
        PRow("", null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CremaButton(onClick = { onApplyFlow(flowDraft.toFloat()) }, variant = CremaButtonVariant.Filled, enabled = connected && flowDirty, label = "Apply")
                CremaButton(onClick = { onApplyFlow(1.0f) }, variant = CremaButtonVariant.Outlined, enabled = connected && kotlin.math.abs(mult - 1.0) > 0.0001, label = "Reset")
            }
        }
        PRow("Last read", "Flow multiplier reported by the DE1.", last = true) {
            PMono(flowMultiplierValue(ui.de1MachineInfo)?.let { String.format("×%.2f", it) } ?: "—", strong = true)
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
        val freqValue = when (ui.lineFreqHz) {
            50.0f -> "50"
            60.0f -> "60"
            else -> "auto"
        }
        PRow("AC mains frequency", "Match your wall power for clean temperature control.", needsConnection = !connected) {
            CremaSegmentedButton(
                options = listOf(SegOption("auto", "Auto"), SegOption("50", "50"), SegOption("60", "60")),
                value = freqValue,
                onChange = { if (it == "auto") vm.setLineFrequency(0.0f) else onStageLineFreq(it) },
                enabled = connected,
            )
        }
        PRow("Smooth pressure curve", "Filter chart noise on the live readout.", last = true, notImplemented = true) { CremaSwitch(smoothPressure, onSmoothPressure) }
    }
    SettingsGroup("Diagnostics") {
        PRow("Show debug / event-log panel", "Surfaces the raw BLE event log below.", last = !ui.showDebugPanel) {
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
        val hv = heaterVoltageValue(ui.de1MachineInfo) ?: "230"
        PRow("Mains heater voltage", "Wrong voltage damages the heater — service only.", last = true, needsConnection = !connected) {
            CremaSegmentedButton(
                options = listOf(SegOption("120", "120 V"), SegOption("230", "230 V")),
                value = hv,
                onChange = { if (it != hv) onStageHeaterVoltage(it) },
                enabled = connected,
            )
        }
    }
    SettingsGroup("Reset") {
        PRow("Reset preferences", "Restore Crema's settings to defaults.") {
            CremaButton(onClick = onResetPrefs, variant = CremaButtonVariant.Text, label = "Reset")
        }
        PRow("Erase all data", "Delete every profile, bean and shot.", last = true) {
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
        PRow("App") { PMono(appVersion, strong = true) }
        val coreVer = remember { runCatching { coffee.crema.core.coreVersion() }.getOrNull() ?: "—" }
        PRow("Core") { PMono("de1-core v$coreVer · UniFFI", strong = true) }
        PRow("Machine", last = true) { PMono("Decent DE1", strong = true) }
    }
    SettingsGroup("Project") {
        PRow("Project repository", "Source, issues, and release notes.") {
            CremaButton(onClick = { openLink("https://github.com/geota/crema") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Open")
        }
        PRow("Decent Espresso", "The folks who make the DE1.") {
            CremaButton(onClick = { openLink("https://decentespresso.com") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Visit")
        }
        PRow("Visualizer", "Optional cloud sync for shots and beans.", last = true) {
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
            PRow(name, desc, last = i == libs.lastIndex) {}
        }
    }
    SettingsGroup("Legal") {
        PRow("Terms of Service", "License, hardware-risk disclaimer, limitation of liability.", notImplemented = true) {}
        PRow("Privacy Policy", "Crema is local-only. No analytics, no tracking.", last = true, notImplemented = true) {}
    }
}

/* ───────────────────────── local primitives ────────────────────────────── */

// Settings row with the tablet's pill semantics, phone paddings + hairline.
@Composable
private fun PRow(
    title: String,
    sub: String? = null,
    last: Boolean = false,
    notImplemented: Boolean = false,
    needsConnection: Boolean = false,
    control: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.bodyLarge)
                if (notImplemented) PPill("Soon")
                else if (needsConnection) PPill("Connect DE1", copper = true)
            }
            if (sub != null) Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Box(Modifier.alpha(if (notImplemented) 0.5f else 1f)) { control() }
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun PPill(text: String, copper: Boolean = false) {
    val fg = if (copper) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val bg = if (copper) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val ring = if (copper) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp),
        color = fg,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, ring, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun PSelect(value: String, onClick: () -> Unit = {}) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.height(38.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, style = MaterialTheme.typography.bodyMedium)
            PhIcon("caret-down", sizeDp = 14, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PStepper(value: String, unit: String?, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PStepBtn("minus", onMinus)
        Box(Modifier.widthIn(min = 56.dp), contentAlignment = Alignment.Center) {
            CremaValueUnit(value, unit, valueSize = 15.sp)
        }
        PStepBtn("plus", onPlus)
    }
}

@Composable
private fun PStepBtn(icon: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(34.dp)) {
        Box(contentAlignment = Alignment.Center) { PhIcon(icon, sizeDp = 14) }
    }
}

@Composable
private fun PStatusDot(on: Boolean) {
    val success = CremaTheme.telemetry.success
    if (on) Box(Modifier.size(10.dp).clip(CircleShape).background(success))
    else Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
}

@Composable
private fun PMono(text: String, strong: Boolean = false, color: Color? = null) {
    Text(
        text,
        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, fontFeatureSettings = "tnum"),
        color = color ?: if (strong) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun HeroKV(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PMono(value, strong = true)
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

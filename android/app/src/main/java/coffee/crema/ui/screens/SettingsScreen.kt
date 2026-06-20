package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.MmrRegister
import coffee.crema.core.hasCupWarmer
import coffee.crema.core.machineModelName
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.refillSoon
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaStatusDot
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaStepperStyle
import coffee.crema.ui.components.CremaTextField
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.CremaValueUnit
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.CremaSettingsRow
import coffee.crema.ui.components.CremaSettingsSelect
import coffee.crema.ui.components.CremaMonoReadout
import coffee.crema.ui.components.MultiDeviceSection
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SegOption
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono

/*
 * Settings — a port of tablet/settings-screen.jsx: a two-pane shell (248dp
 * section-nav rail + a scrolling content pane, max 880) over 8 sections.
 *
 * Controls are wired to the VM where the state exists today (Machine/Scale
 * connect, the three brew-behaviour toggles, Theme, the debug route). Where the
 * design shows a setting that has no source of truth yet (units, water-chemistry,
 * webhooks, sharing) the row renders faithfully with local/optimistic state —
 * persistence behind a SettingsStore is a deferred follow-up, exactly the
 * "render the structure, wire the source later" policy the PWA uses.
 */

private val SETTINGS_SECTIONS = listOf(
    Triple("machine", "sliders-horizontal", "Machine"),
    Triple("brew", "coffee", "Brew defaults"),
    Triple("water", "drop", "Water & maintenance"),
    Triple("display", "monitor", "Display & units"),
    Triple("sharing", "share-network", "Sharing"),
    Triple("calibration", "gauge", "Calibration"),
    Triple("advanced", "wrench", "Advanced"),
    Triple("about", "info", "About"),
)

@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    var active by rememberSaveable { mutableStateOf("machine") }
    // Staged destructive-action confirms + SAF export — shared with the phone
    // shell (issue 27). Rows flip confirm.* flags; SettingsConfirmDialogs renders.
    val confirm = rememberSettingsConfirmState(vm)
    SettingsConfirmDialogs(confirm, vm, ui)

    // ── Local design-faithful prefs (persistence deferred to a SettingsStore) ──
    // Pilled placeholder rows keep local state only (their pills mark them
    // not-implemented); everything functional reads ui.* / AppPrefs.
    var density by rememberSaveable { mutableStateOf("comfortable") }
    var autoSync by rememberSaveable { mutableStateOf(false) }
    var smoothPressure by rememberSaveable { mutableStateOf(true) }
    var tempOffset by rememberSaveable { mutableStateOf(0.0) }
    var screensaver by rememberSaveable { mutableStateOf(false) }
    var waterSource by rememberSaveable { mutableStateOf("filtered") }
    var hardnessPpm by rememberSaveable { mutableStateOf(68) }
    var tdsPpm by rememberSaveable { mutableStateOf(110) }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "settings",
            onNav = onNav,
            machineConnected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Row(Modifier.weight(1f).fillMaxHeight()) {
            // ── Section nav (248dp) ──────────────────────────────────────────
            Column(
                Modifier.width(248.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.padding(start = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow("Preferences")
                    Text("Settings", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                SETTINGS_SECTIONS.forEach { (id, icon, label) ->
                    SetNavItem(icon, label, active == id) { active = id }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))

            // ── Content pane ─────────────────────────────────────────────────
            Column(
                Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).widthIn(max = 880.dp).padding(start = 32.dp, top = 28.dp, end = 32.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                when (active) {
                    "machine" -> {
                        SetHead("Hardware", "Machine", "Your espresso machine, scale, and grinder. Crema talks to these over Bluetooth.")
                        MachineHeroCard(
                            connected = connected,
                            stateLabel = if (connected) "Connected · ${ui.machineState ?: "ready"}" else "Not connected",
                            // BUG FIX: this row previously showed a hardcoded
                            // "v1352" (and even fell back to the SCALE firmware) —
                            // it must reflect the MACHINE firmware the DE1 reports.
                            firmware = if (connected) firmwareLabel(ui.de1MachineInfo, ui.de1Firmware).takeIf { it != "—" } else null,
                            model = if (connected) machineModelLabel(ui.de1MachineInfo).takeIf { it != "—" } else null,
                            board = if (connected) cpuBoardLabel(ui.de1MachineInfo).takeIf { it != "—" } else null,
                            ble = if (connected) (ui.de1BluetoothAddress ?: "Paired") else null,
                            onConnect = { onConnect("machine") },
                            onUpdateFirmware = null,
                        )
                        SetGroup("Connection") {
                            CremaSettingsRow("Telemetry rate", "How often the chart samples live data.", notImplemented = true) { CremaSettingsSelect("50 Hz") }
                            // Keep-awake is REAL: a 60 s UserPresent (MMR 0x803858)
                            // heartbeat in the VM resets the DE1's sleep timer while on.
                            CremaSettingsRow("Keep DE1 awake while Crema is open", "Re-arms the DE1's sleep timer every minute so the machine stays ready.") {
                                CremaSwitch(ui.suppressDe1Sleep, vm::setSuppressDe1Sleep)
                            }
                            CremaSettingsRow("Auto-connect", "Reconnect to this DE1 automatically after a dropout, and on launch. Turning it off forgets the device.") {
                                CremaSwitch(ui.rememberedDe1Address != null, vm::setDe1AutoConnect, enabled = connected || ui.rememberedDe1Address != null)
                            }
                            // GHC start-from-machine mode — driven by the live GhcMode
                            // register; the toggle writes via the VM. Disabled until the
                            // machine is connected and the GHC is reported present.
                            val ghcOn = ghcModeOn(ui.de1MachineInfo) ?: false
                            val ghcAvailable = connected && (ghcPresent(ui.de1MachineInfo) == true)
                            CremaSettingsRow("Group Head Controller (GHC)", "Start shots from the machine's touch panel.", last = true, needsConnection = !connected) {
                                CremaSwitch(ghcOn, { vm.setGhcMode(it) }, enabled = ghcAvailable)
                            }
                        }
                        SetGroup("Identity") {
                            CremaSettingsRow("Model") { CremaMonoReadout(machineModelLabel(ui.de1MachineInfo), color = MaterialTheme.colorScheme.onSurface) }
                            CremaSettingsRow("Serial number") { CremaMonoReadout(serialLabel(ui.de1MachineInfo), color = MaterialTheme.colorScheme.onSurface) }
                            CremaSettingsRow("CPU board") { CremaMonoReadout(cpuBoardLabel(ui.de1MachineInfo), color = MaterialTheme.colorScheme.onSurface) }
                            CremaSettingsRow("Firmware") { CremaMonoReadout(firmwareLabel(ui.de1MachineInfo, ui.de1Firmware), color = MaterialTheme.colorScheme.onSurface) }
                            CremaSettingsRow("Heater voltage", last = true) { CremaMonoReadout(heaterVoltageLabel(ui.de1MachineInfo), color = MaterialTheme.colorScheme.onSurface) }
                        }
                        SetGroup("Diagnostics") {
                            CremaSettingsRow("Connection state") { CremaMonoReadout(if (connected) "Ready" else "Disconnected", color = MaterialTheme.colorScheme.onSurface) }
                            CremaSettingsRow("GATT verified") { CremaStatusDot(connected) }
                            CremaSettingsRow("Machine state") { CremaMonoReadout(ui.machineState ?: "—", color = MaterialTheme.colorScheme.onSurface) }
                            // Readable error copy (core `subStateErrorMessage`, web parity),
                            // shown only while the substate is an error.
                            ui.machineError?.let { err ->
                                CremaSettingsRow("Machine error") { CremaMonoReadout(err, color = MaterialTheme.colorScheme.error) }
                            }
                            CremaSettingsRow("Notifications received", last = true) { CremaMonoReadout(if (connected) "—" else "0", color = MaterialTheme.colorScheme.onSurface) }
                        }
                        SetGroup("Peripherals") {
                            CremaSettingsRow("Scale", if (scaleConnected) (ui.scaleName ?: "Connected") else "Not paired") {
                                if (scaleConnected) CremaStatusDot(true)
                                else CremaButton(onClick = { onConnect("scale") }, variant = CremaButtonVariant.Outlined, label = "Pair")
                            }
                            CremaSettingsRow("Grinder", "No grinder support yet.") { CremaStatusDot(false) }
                            // Equipment-level grinder model (web `grinderModel`): free
                            // text, persisted, shown on Brew + sent to Visualizer.
                            CremaSettingsRow("Grinder model", "Stamped on shots and Visualizer uploads.", last = true) {
                                CremaTextField(
                                    value = ui.grinderModel,
                                    onValueChange = vm::setGrinderModel,
                                    placeholder = "e.g. Niche Zero",
                                    singleLine = true,
                                    modifier = Modifier.width(240.dp),
                                )
                            }
                        }
                        // Cup warmer — Bengle hardware only (models 4–7, the web's
                        // hasCupWarmer gate); hidden entirely on other machines.
                        if (hasCupWarmerPlate(ui.de1MachineInfo)) {
                            SetGroup("Cup warmer") {
                                val plate = cupWarmerTempValue(ui.de1MachineInfo) ?: 25
                                CremaSettingsRow(
                                    "Plate temperature", "The Bengle warming plate's target.", last = true,
                                    needsConnection = !connected,
                                    dot = true, dotOn = plate > 0,
                                    onDot = { if (connected) vm.setCupWarmerTemp(if (plate > 0) 0 else 55) },
                                ) {
                                    CremaStepper(
                                        value = plate.toDouble(), unit = "°C", step = 1.0, min = 0.0, max = 80.0,
                                        fmt = { "%.0f".format(it) }, style = CremaStepperStyle.Bare,
                                        onChange = { if (connected) vm.setCupWarmerTemp(it.toInt()) },
                                    )
                                }
                            }
                        }
                    }
                    "brew" -> {
                        SetHead("Defaults", "Brew defaults", "Seed values for a fresh shot. Per-profile recipes override these.")
                        SetGroup("Targets") {
                            // Persisted (AppPrefs) — these seed "New profile" via
                            // brewDefaultsJson, so the dialled numbers are real.
                            val setDefs = { d: Float, r: Float, t: Float, p: Float -> vm.setBrewDefaults(d, r, t, p) }
                            val dD = ui.defaultDoseG; val dR = ui.defaultRatio; val dT = ui.defaultBrewTempC; val dP = ui.defaultPreinfuseS
                            CremaSettingsRow("Default dose", "Starting dose for a fresh shot.") { CremaStepper(value = dD.toDouble(), unit = "g", step = 0.1, min = 5.0, max = 30.0, fmt = { "%.1f".format(it) }, style = CremaStepperStyle.Bare, onChange = { setDefs(it.toFloat(), dR, dT, dP) }) }
                            CremaSettingsRow("Default ratio", "Target brew ratio (yield ÷ dose).") { CremaStepper(value = dR.toDouble(), unit = null, step = 0.1, min = 1.0, max = 5.0, fmt = { "1:%.1f".format(it) }, style = CremaStepperStyle.Bare, onChange = { setDefs(dD, it.toFloat(), dT, dP) }) }
                            CremaSettingsRow("Default brew temp", "Starting group temperature.") { CremaStepper(value = dT.toDouble(), unit = "°C", step = 0.5, min = 80.0, max = 100.0, fmt = { "%.1f".format(it) }, style = CremaStepperStyle.Bare, onChange = { setDefs(dD, dR, it.toFloat(), dP) }) }
                            CremaSettingsRow("Default pre-infusion", "Low-pressure soak before the main shot.", last = true, dot = true, dotOn = dP > 0f, onDot = { setDefs(dD, dR, dT, if (dP > 0f) 0f else 8f) }) { CremaStepper(value = dP.toDouble(), unit = "s", step = 1.0, min = 0.0, max = 60.0, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.Bare, onChange = { setDefs(dD, dR, dT, it.toFloat()) }) }
                        }
                        SetGroup("Shot behaviour") {
                            CremaSettingsRow("Auto-tare on shot start", "Zero the scale automatically when extraction begins.") { CremaSwitch(ui.autoTare, vm::setAutoTare) }
                            CremaSettingsRow("Stop on weight", "End the shot once the target yield is reached.") { CremaSwitch(ui.stopOnWeight, vm::setStopOnWeight) }
                            // Max shot duration — persisted in AppPrefs + read from
                            // ui.maxShotDurationS so it survives + shows on Brew's stop
                            // conditions. The stepper pushes each change through the VM.
                            CremaSettingsRow(
                                "Max shot duration", "Hard time cap — also a Brew stop condition.",
                                dot = true, dotOn = ui.maxShotDurationS > 0f,
                                onDot = { vm.setMaxShotDuration(if (ui.maxShotDurationS > 0f) 0f else 60f) },
                            ) {
                                val maxDur = ui.maxShotDurationS.toInt()
                                CremaStepper(
                                    value = maxDur.toDouble(), unit = "s", step = 5.0, min = 0.0, max = 300.0,
                                    fmt = { "%.0f".format(it) }, style = CremaStepperStyle.Bare,
                                    onChange = { vm.setMaxShotDuration(it.toFloat()) },
                                )
                            }
                            CremaSettingsRow("Group flush before each shot", "Stabilise the group temperature with a short flush.") { CremaSwitch(ui.preFlush, vm::setPreFlush) }
                            CremaSettingsRow("Auto-purge after steam", "Clear the steam wand automatically after steaming.") { CremaSwitch(ui.steamPurge, vm::setSteamPurge) }
                            CremaSettingsRow("Steam eco", "Idle the steam boiler cooler between sessions to save power.", last = true) { CremaSwitch(ui.steamEco, vm::setSteamEco) }
                        }
                    }
                    "water" -> {
                        SetHead("Upkeep", "Water & maintenance", "Crema tracks water volume and shot counts to remind you when upkeep is due.")
                        // Live tank level (Event.WaterLevel → ui.waterLevelMm) — a low
                        // note appears below the threshold. The Reminders group below
                        // is now driven by the real persisted MaintenanceState (the
                        // shell integrates group flow over telemetry into a litre
                        // counter, then derives the readouts via the pure core FFI).
                        SetGroup("Tank") {
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
                                CremaMonoReadout(
                                    if (mm != null) "${mm.toInt()} mm" else "—",
                                    color = if (low) Color(0xFFDBA764) else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        SetGroup("Cycles") {
                            // "Run now" stages a confirm dialog before driving the DE1's
                            // cycles (web parity: a cycle takes minutes and needs the kit
                            // fitted). Gated on connected AND machine idle — firing
                            // Descale mid-shot is a real hazard the web also blocks.
                            val machineIdle = ui.machineState?.startsWith("Idle") == true
                            val cycleReady = connected && machineIdle
                            val cycleSub = if (connected && !machineIdle) " Machine must be idle." else ""
                            CremaSettingsRow("Descale", "Run the DE1's descale cycle.$cycleSub", needsConnection = !connected) {
                                CremaButton(onClick = { confirm.pendingCycle = "descale" }, variant = CremaButtonVariant.Outlined, enabled = cycleReady, icon = "play", label = "Run now")
                            }
                            CremaSettingsRow("Group clean", "Run the DE1's cleaning cycle.$cycleSub", needsConnection = !connected) {
                                CremaButton(onClick = { confirm.pendingCycle = "clean" }, variant = CremaButtonVariant.Outlined, enabled = cycleReady, icon = "play", label = "Run now")
                            }
                            CremaSettingsRow("Steam rinse", "Flush the steam wand.$cycleSub", last = true, needsConnection = !connected) {
                                CremaButton(onClick = { confirm.pendingCycle = "steam-rinse" }, variant = CremaButtonVariant.Outlined, enabled = cycleReady, icon = "play", label = "Run now")
                            }
                        }
                        SetGroup("Reminders") {
                            // Real maintenance readouts, driven by ui.maintenanceReadout:
                            // the DE1 has no cumulative water counter, so the shell
                            // integrates group flow over telemetry into a persisted
                            // MaintenanceState and derives these via the pure core FFI.
                            // Each row's "Mark done" rebaselines the matching counter
                            // (filter/descale) or resets its hour clock (clean). An amber
                            // treatment appears when a counter is past its interval.
                            val ro = ui.maintenanceReadout
                            val m = ui.maintenance
                            val filter = ro?.filterRow(m)
                            MaintenanceRow(
                                icon = "funnel",
                                title = "Water filter",
                                note = filter?.note ?: "Awaiting data.",
                                value = filter?.value ?: "—",
                                unit = filter?.unit,
                                pct = filter?.pct ?: 0f,
                                due = filter?.due ?: false,
                                onMarkDone = { vm.markFilterCleaned() },
                            )
                            val descale = ro?.descaleRow(m)
                            MaintenanceRow(
                                icon = "drop",
                                title = "Descale",
                                note = descale?.note ?: "Awaiting data.",
                                value = descale?.value ?: "—",
                                unit = descale?.unit,
                                pct = descale?.pct ?: 0f,
                                due = descale?.due ?: false,
                                onMarkDone = { vm.markDescaled() },
                            )
                            val clean = ro?.cleanRow(m)
                            MaintenanceRow(
                                icon = "wind",
                                title = "Group clean",
                                note = clean?.note ?: "Awaiting data.",
                                value = clean?.value ?: "—",
                                unit = clean?.unit,
                                pct = clean?.pct ?: 0f,
                                due = clean?.due ?: false,
                                onMarkDone = { vm.markCleaned() },
                                last = true,
                            )
                        }
                        // Maintenance intervals — REAL (persisted MaintenanceState;
                        // the Reminders readouts above recompute immediately). Web
                        // WaterSection's "Maintenance intervals" group, same ranges.
                        SetGroup("Maintenance intervals") {
                            val m2 = ui.maintenance
                            CremaSettingsRow("Filter capacity", "Litres before a filter change is due.") {
                                CremaStepper(
                                    value = m2.filterCapacityLitres, unit = "L", step = 5.0, min = 5.0, max = 500.0,
                                    fmt = { "%.0f".format(it) }, style = CremaStepperStyle.Bare,
                                    onChange = { vm.setFilterCapacity(it) },
                                )
                            }
                            CremaSettingsRow("Descale interval", "Litres of brew water between descales.") {
                                CremaStepper(
                                    value = m2.descaleIntervalLitres, unit = "L", step = 10.0, min = 10.0, max = 1000.0,
                                    fmt = { "%.0f".format(it) }, style = CremaStepperStyle.Bare,
                                    onChange = { vm.setDescaleInterval(it) },
                                )
                            }
                            CremaSettingsRow("Clean cycle interval", "Hours of machine-on time between cleans.", last = true) {
                                CremaStepper(
                                    value = m2.cleanIntervalHours, unit = "h", step = 1.0, min = 1.0, max = 500.0,
                                    fmt = { "%.0f".format(it) }, style = CremaStepperStyle.Bare,
                                    onChange = { vm.setCleanInterval(it) },
                                )
                            }
                        }
                        SetGroup("Water chemistry") {
                            // No core method for water hardness / TDS yet — the controls
                            // mirror the web's (segment + steppers), pilled until real.
                            CremaSettingsRow("Water source", "Your feed water — tunes descale intervals.", notImplemented = true) {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("tap", "Tap"), SegOption("filtered", "Filtered"), SegOption("bottled", "Bottled"), SegOption("rpavlis", "RPavlis")),
                                    value = waterSource,
                                    onChange = { waterSource = it },
                                    uniform = true,
                                )
                            }
                            CremaSettingsRow("Hardness", "General hardness (GH).", notImplemented = true) {
                                CremaStepper(value = hardnessPpm.toDouble(), unit = "ppm", step = 1.0, min = 0.0, max = 500.0, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.Bare, onChange = { hardnessPpm = it.toInt() })
                            }
                            CremaSettingsRow("Total dissolved solids", "Measured TDS of your water.", last = true, notImplemented = true) {
                                CremaStepper(value = tdsPpm.toDouble(), unit = "ppm", step = 1.0, min = 0.0, max = 1000.0, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.Bare, onChange = { tdsPpm = it.toInt() })
                            }
                        }
                    }
                    "display" -> {
                        SetHead("Appearance", "Display & units", "How Crema looks and the units it shows across every readout.")
                        SetGroup("Appearance") {
                            CremaSettingsRow("Theme", "Crema defaults to dark — the machine app is dark-skinned.") {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("light", "Light"), SegOption("dark", "Dark")),
                                    value = if (ui.themeMode == "light") "light" else "dark",
                                    onChange = vm::setThemeMode,
                                    uniform = true,
                                )
                            }
                            CremaSettingsRow("Density", "Card padding and control sizing.", notImplemented = true) {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("compact", "Compact"), SegOption("comfortable", "Comfortable"), SegOption("spacious", "Spacious")),
                                    value = density,
                                    onChange = { density = it },
                                    uniform = true,
                                )
                            }
                            CremaSettingsRow("Screensaver", "Dim the display after a period idle.", notImplemented = true) { CremaSwitch(screensaver, { screensaver = it }) }
                            CremaSettingsRow("Keep screen on while brewing", "Hold the display awake during a shot.", last = true) { CremaSwitch(ui.keepScreenOnBrew, vm::setKeepScreenOnBrew) }
                        }
                        SetGroup("Units") {
                            CremaSettingsRow("Temperature", "Units for every temperature readout.") {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("C", "°C"), SegOption("F", "°F")),
                                    value = ui.tempUnit,
                                    onChange = vm::setTempUnit,
                                    groupWidth = 172.dp,
                                )
                            }
                            CremaSettingsRow("Weight", "Units for dose and yield.") {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("g", "g"), SegOption("oz", "oz")),
                                    value = ui.weightUnit,
                                    onChange = vm::setWeightUnit,
                                    groupWidth = 172.dp,
                                )
                            }
                            CremaSettingsRow("Pressure", "Units for the pressure channel.") {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("bar", "bar"), SegOption("psi", "psi")),
                                    value = ui.pressureUnit,
                                    onChange = vm::setPressureUnit,
                                    groupWidth = 172.dp,
                                )
                            }
                            CremaSettingsRow("Volume", "Units for water and yield volume.", last = true) {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("ml", "ml"), SegOption("floz", "fl oz")),
                                    value = ui.volumeUnit,
                                    onChange = vm::setVolumeUnit,
                                    groupWidth = 172.dp,
                                )
                            }
                        }
                    }
                    "sharing" -> {
                        SetHead("Third-party", "Sharing", "Crema is local-only — there's no Crema account. Shots and profiles live on this device. Connect Visualizer if you want to back up, share, or compare shots online.")
                        val vz = ui.visualizer
                        val context = androidx.compose.ui.platform.LocalContext.current
                        // Re-validate the cached account whenever the section opens.
                        LaunchedEffect(Unit) { vm.visualizer.refreshAccount() }
                        val openUrl: (String) -> Unit = { url ->
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                            )
                        }
                        VisualizerHeroCard(
                            vz = vz,
                            onSignIn = { vm.visualizer.beginSignIn(openUrl) },
                            onSignOut = { vm.visualizer.signOut() },
                            onTest = { vm.visualizer.testConnection() },
                            onOpenSite = { openUrl("https://visualizer.coffee") },
                        )
                        // Connected-state sync controls (web shows BeanSyncSection here;
                        // Android v1 = the shots-push controls).
                        if (vz.signedIn) {
                            SetGroup("Sync") {
                                val unsyncedCount = ui.history.count { it.visualizerId == null }
                                val lastSyncLabel = vz.lastShotSyncAt?.let {
                                    android.text.format.DateUtils.getRelativeTimeSpanString(it).toString()
                                } ?: "never"
                                CremaSettingsRow(
                                    "Shots",
                                    "${ui.history.size} shot(s)" +
                                        (if (unsyncedCount > 0) " · $unsyncedCount unsynced" else "") +
                                        ". Last sync: $lastSyncLabel. Free.",
                                ) {
                                    CremaSegmentedButton(
                                        options = listOf(
                                            SegOption("off", "Off"),
                                            SegOption("backup", "Backup"),
                                            SegOption("pull", "Pull"),
                                            SegOption("two-way", "Two-way"),
                                        ),
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
                                    if (unsyncedCount == 0) "All ${ui.history.size} local shots are on Visualizer."
                                    else "$unsyncedCount local shot(s) not uploaded yet.",
                                ) {
                                    CremaButton(
                                        onClick = { vm.visualizer.syncNow(ui.history) },
                                        variant = CremaButtonVariant.Outlined,
                                        icon = if (vz.shotsDirection == "pull") "cloud-arrow-down" else "cloud-arrow-up",
                                        enabled = vz.shotsDirection != "off" && !vz.busy && !vz.syncing,
                                        label = if (vz.syncing) "Syncing…" else "Sync now",
                                    )
                                }
                                CremaSettingsRow("Re-sync shots", "Re-pull everything from Visualizer, de-duplicated.", last = true) {
                                    CremaButton(
                                        onClick = { confirm.pendingResync = true },
                                        variant = CremaButtonVariant.Outlined,
                                        icon = "clock-counter-clockwise",
                                        enabled = !vz.busy && !vz.syncing,
                                        label = "Re-sync all",
                                    )
                                }
                            }
                            // Upload options — defaults applied to every upload (web's
                            // separate group; per-shot privacy overrides live in History).
                            SetGroup("Upload options") {
                                CremaSettingsRow("Default privacy", "Public = community feed; unlisted = direct link only; private = just you. Shots can override this in History.") {
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
                        }
                        if (vz.signedIn) {
                            var showLog by remember { mutableStateOf(false) }
                            SetGroup("Recent activity") {
                                CremaSettingsRow(
                                    "Sync log",
                                    if (vz.log.isEmpty()) "No sync activity yet."
                                    else "${vz.log.size} recent event(s).",
                                    last = !showLog || vz.log.isEmpty(),
                                ) {
                                    CremaButton(
                                        onClick = { showLog = !showLog },
                                        variant = CremaButtonVariant.Text,
                                        label = if (showLog) "Hide log" else "Show log",
                                        enabled = vz.log.isNotEmpty(),
                                    )
                                }
                                if (showLog) {
                                    vz.log.forEachIndexed { i, entry ->
                                        SyncLogRow(entry, last = i == vz.log.lastIndex)
                                    }
                                }
                            }
                        }
                        SetGroup("Local export") {
                            CremaSettingsRow(
                                "History export",
                                "One-shot download of your entire shot history as JSON. Useful for spreadsheets and other tools.",
                                last = true,
                            ) {
                                CremaButton(onClick = { confirm.launchSave("crema-history.json", vm.shotsJson(null)) }, variant = CremaButtonVariant.Outlined, icon = "download-simple", label = "Export")
                            }
                        }
                        // Other integrations — the web's stub trio, disabled until real.
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Eyebrow("Other integrations")
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                OtherIntegrationCard(
                                    icon = "chats-circle",
                                    title = "DecentForum",
                                    sub = "Share a profile from the library straight to a forum post.",
                                    buttonIcon = "link",
                                    buttonLabel = "Connect",
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                OtherIntegrationCard(
                                    icon = "cube",
                                    title = "Insight",
                                    sub = "Decent's profile marketplace. Import and rate profiles.",
                                    buttonIcon = "link",
                                    buttonLabel = "Connect",
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                                OtherIntegrationCard(
                                    icon = "house",
                                    title = "Home Assistant",
                                    sub = "Expose shot events as MQTT topics.",
                                    buttonIcon = "gear-six",
                                    buttonLabel = "Configure in Advanced",
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                )
                            }
                        }
                    }
                    "calibration" -> {
                        SetHead("Accuracy", "Calibration", "Advanced — only adjust against a known reference.")
                        SetGroup("Sensor calibration") {
                            // Temperature offset / pressure zero have no core setter
                            // (calibration writes are read-only over FFI) — placeholders.
                            CremaSettingsRow("Temperature", "Shift every temperature reading.", notImplemented = true) { CremaStepper(value = tempOffset, unit = "°C", step = 0.1, min = -5.0, max = 5.0, fmt = { "%+.1f".format(it) }, style = CremaStepperStyle.Bare, onChange = { tempOffset = it }) }
                            CremaSettingsRow("Pressure", "Re-zero the pressure sensor at idle.", notImplemented = true) { CremaMonoReadout("0.0 bar", color = MaterialTheme.colorScheme.onSurface) }
                            // Flow multiplier — the stepper edits a STAGED draft; the
                            // write happens only through Apply's type-to-confirm dialog
                            // (web parity: stray taps must not silently re-calibrate).
                            val mult = flowMultiplierValue(ui.de1MachineInfo) ?: 1.0
                            var flowDraft by remember(mult) { mutableStateOf(mult) }
                            val flowDirty = kotlin.math.abs(flowDraft - mult) > 0.0001
                            CremaSettingsRow("Flow", "Scale the flow-meter reading. Apply commits after a typed confirm.", needsConnection = !connected) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CremaStepper(
                                        value = flowDraft, unit = "×", step = 0.01, min = 0.13, max = 2.0,
                                        fmt = { "%.2f".format(it) }, style = CremaStepperStyle.Bare,
                                        onChange = { flowDraft = it },
                                    )
                                    CremaButton(
                                        onClick = { confirm.pendingFlowMultiplier = flowDraft.toFloat() },
                                        variant = CremaButtonVariant.Filled,
                                        enabled = connected && flowDirty,
                                        label = "Apply",
                                    )
                                    CremaButton(
                                        onClick = { confirm.pendingFlowMultiplier = 1.0f },
                                        variant = CremaButtonVariant.Outlined,
                                        enabled = connected && kotlin.math.abs(mult - 1.0) > 0.0001,
                                        label = "Reset",
                                    )
                                }
                            }
                            CremaSettingsRow("Last read", "Flow multiplier reported by the DE1.", last = true) { CremaMonoReadout(flowMultiplierValue(ui.de1MachineInfo)?.let { String.format("×%.2f", it) } ?: "—", color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    "advanced" -> {
                        SetHead("Power user", "Advanced", "Telemetry tuning, developer tools, and destructive resets.")
                        SetGroup("Telemetry") {
                            // AC mains frequency override — the toggle reflects the
                            // user's OVERRIDE (auto/50/60), not the resolved value, so
                            // it stays on "Auto" once the detector locks a frequency.
                            val freqValue = when (ui.lineFreqOverride) {
                                50.0f -> "50"
                                60.0f -> "60"
                                else -> "auto"
                            }
                            // On Auto, poll the resolved Hz live for the hint (web parity).
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
                                    options = listOf(SegOption("auto", "Auto"), SegOption("50", "50 Hz"), SegOption("60", "60 Hz")),
                                    value = freqValue,
                                    // Auto commits directly; 50/60 stage a type-to-confirm
                                    // (web MainsConfirmModal parity).
                                    onChange = { if (it == "auto") vm.setLineFrequency(0.0f) else confirm.pendingLineFreq = it },
                                    enabled = connected,
                                    uniform = true,
                                )
                            }
                            CremaSettingsRow("Smooth pressure curve", "Filter chart noise on the live readout.", last = true, notImplemented = true) { CremaSwitch(smoothPressure, { smoothPressure = it }) }
                        }
                        SetGroup("Diagnostics") {
                            // Web parity: a persisted toggle revealing the event log
                            // INLINE (web DebugPanel) rather than a separate screen.
                            // The full Phase-0 console stays reachable from the panel.
                            CremaSettingsRow(
                                "Show debug / event-log panel",
                                "Surfaces the raw BLE event log below.",
                                last = !ui.showDebugPanel,
                            ) { CremaSwitch(ui.showDebugPanel, vm::setShowDebugPanel) }
                            if (ui.showDebugPanel) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(
                                        Modifier.fillMaxWidth()
                                            .height(280.dp)
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
                        // Live, no-restart picker (issue 13) — the same one the phone
                        // uses: Mirror from a primary on the LAN, then Stop / Take over.
                        // The tablet lives by the machine, so it's exactly the device
                        // that wants this. The role selector below stays as the manual
                        // (restart-to-apply) escape hatch + the way to host the DE1.
                        SetGroup("Multi-device") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                                MultiDeviceSection(
                                    ui = ui,
                                    onMirrorFrom = { host, port -> vm.switchToSecondary(host, port) },
                                    onStopMirroring = vm::switchToNormal,
                                    onTakeOver = vm::requestHandoff,
                                    showHeader = false,
                                )
                            }
                        }
                        SetGroup("Multi-device — manual override (debug)") {
                            // Restart-to-apply role override + the way to become a host.
                            // The live picker above is the everyday path; this is the
                            // escape hatch (and how to host until a live "Host" action).
                            val roleSub = when (ui.proxyRole) {
                                "primary" -> "Owns the DE1 and relays it to others. Restart to apply."
                                "secondary" -> "Mirrors a primary over the LAN. Restart to apply."
                                else -> "Off — normal single-device use."
                            }
                            CremaSettingsRow("Role", roleSub, last = ui.proxyRole != "secondary") {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("normal", "Off"), SegOption("primary", "Primary"), SegOption("secondary", "Secondary")),
                                    value = ui.proxyRole,
                                    onChange = vm::setProxyRole,
                                    enabled = true,
                                    uniform = true,
                                )
                            }
                            if (ui.proxyRole == "secondary") {
                                CremaSettingsRow("Primary host", "An IP, or 10.0.2.2 for an adb-forwarded emulator.") {
                                    CremaTextField(
                                        value = ui.proxyPrimaryHost,
                                        onValueChange = vm::setProxyPrimaryHost,
                                        placeholder = "10.0.2.2",
                                        singleLine = true,
                                        modifier = Modifier.width(200.dp),
                                    )
                                }
                                CremaSettingsRow("Primary port", "The relay port — see the primary's log line.", last = true) {
                                    CremaTextField(
                                        value = if (ui.proxyPrimaryPort > 0) ui.proxyPrimaryPort.toString() else "",
                                        onValueChange = { vm.setProxyPrimaryPort(it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) },
                                        placeholder = "8080",
                                        singleLine = true,
                                        modifier = Modifier.width(120.dp),
                                    )
                                }
                            }
                        }
                        SetGroup("Paired devices") {
                            // Devices this host has allowed to mirror it (issue 02).
                            // Forget → the peer is re-prompted on its next connect.
                            if (ui.pairedDevices.isEmpty()) {
                                CremaSettingsRow("No paired devices", "Devices you allow to mirror this machine appear here.", last = true) {}
                            } else {
                                ui.pairedDevices.forEachIndexed { i, d ->
                                    CremaSettingsRow(
                                        d.name,
                                        if (d.canControl) "Mirror + control" else "Mirror only",
                                        last = i == ui.pairedDevices.lastIndex,
                                    ) {
                                        CremaButton(onClick = { vm.forgetPairedDevice(d.id) }, variant = CremaButtonVariant.Text, label = "Forget")
                                    }
                                }
                            }
                        }
                        SetGroup("Service-grade") {
                            // Mains heater voltage — service-grade, gated behind a danger
                            // confirm (staged in pendingHeaterVoltage). Reflects the live
                            // HeaterVoltage register; the VM guards 120/230 again.
                            // null until the connected DE1 reports its mains voltage.
                            val hv = heaterVoltageValue(ui.de1MachineInfo)
                            CremaSettingsRow("Mains heater voltage", "Wrong voltage damages the heater — service only.", last = true, needsConnection = !connected) {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("120", "120 V"), SegOption("230", "230 V")),
                                    // No segment selected until the machine reports its voltage —
                                    // matches the "—" Heater-voltage readout, not a default of 230.
                                    value = hv ?: "",
                                    // Stages the type-to-confirm dialog; ignore re-taps on
                                    // the current value (web disables the matching button).
                                    onChange = { if (it != hv) confirm.pendingHeaterVoltage = it },
                                    enabled = connected,
                                    uniform = true,
                                )
                            }
                            // REMOVED: "Reset machine to factory" — there is no core /
                            // FFI method for a DE1 factory reset (verified), so a wired
                            // action is impossible; a dead button would be misleading.
                        }
                        SetGroup("Reset") {
                            CremaSettingsRow("Reset preferences", "Restore Crema's settings to defaults.") { CremaButton(onClick = { confirm.confirmResetPrefs = true }, variant = CremaButtonVariant.Text, label = "Reset") }
                            CremaSettingsRow("Erase all data", "Delete every profile, bean and shot.", last = true) { CremaButton(onClick = { confirm.confirmErase = true }, variant = CremaButtonVariant.Text, danger = true, label = "Erase") }
                        }
                    }
                    "about" -> {
                        SetHead("About", "Crema", "A fast, native control surface for the Decent DE1 espresso machine.")
                        SetGroup("Version") {
                            val appVersion = run {
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "dev" }
                            }
                            CremaSettingsRow("App") { CremaMonoReadout(appVersion, color = MaterialTheme.colorScheme.onSurface) }
                            val coreVer = remember { runCatching { coffee.crema.core.coreVersion() }.getOrNull() ?: "—" }
                            CremaSettingsRow("Core") { CremaMonoReadout("de1-core v$coreVer · UniFFI", color = MaterialTheme.colorScheme.onSurface) }
                            CremaSettingsRow("Machine", last = true) { CremaMonoReadout("Decent DE1", color = MaterialTheme.colorScheme.onSurface) }
                        }
                        val aboutContext = androidx.compose.ui.platform.LocalContext.current
                        val openLink: (String) -> Unit = { url ->
                            aboutContext.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)),
                            )
                        }
                        SetGroup("Project") {
                            CremaSettingsRow("Project repository", "Source, issues, and release notes.") {
                                CremaButton(onClick = { openLink("https://github.com/geota/crema") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Open")
                            }
                            CremaSettingsRow("Decent Espresso", "The folks who make the DE1.") {
                                CremaButton(onClick = { openLink("https://decentespresso.com") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Visit")
                            }
                            CremaSettingsRow("Visualizer", "Optional cloud sync for shots and beans.") {
                                CremaButton(onClick = { openLink("https://visualizer.coffee") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Visit")
                            }
                            CremaSettingsRow("pdf.maceiras.dev", "Also by the author — a browser-based PDF toolkit. Files never leave your device.", last = true) {
                                CremaButton(onClick = { openLink("https://pdf.maceiras.dev") }, variant = CremaButtonVariant.Outlined, icon = "arrow-square-out", label = "Visit")
                            }
                        }
                        // The Android shell's real dependency stack (web parity for the
                        // "Open source libraries" group, tablet-specific contents).
                        SetGroup("Open source libraries") {
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
                        SetGroup("Legal") {
                            // The web ships /terms + /privacy in-app; the tablet has no
                            // hosted equivalents yet — pilled until those pages exist.
                            CremaSettingsRow("Terms of Service", "License, hardware-risk disclaimer, limitation of liability.", notImplemented = true) { CremaSettingsSelect("Ships with 1.0") }
                            CremaSettingsRow("Privacy Policy", "Crema is local-only. No analytics, no tracking.", last = true, notImplemented = true) { CremaSettingsSelect("Ships with 1.0") }
                        }
                    }
                }
            }
        }
    }
}

// ── Section nav pill ─────────────────────────────────────────────────────────
@Composable
private fun SetNavItem(icon: String, label: String, active: Boolean, onClick: () -> Unit) {
    val fg = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PhIcon(icon, sizeDp = 20, tint = fg)
        Text(label, style = MaterialTheme.typography.titleSmall, color = fg)
    }
}

// ── Section header: eyebrow + serif title + sub ──────────────────────────────
@Composable
private fun SetHead(eyebrow: String, title: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Eyebrow(eyebrow)
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 560.dp))
    }
}

// ── A titled group card hosting a divider-separated row list ──────────────────
// Rows draw their own bottom divider unless they pass last=true (the proto's
// full-bleed dividers, not inter-row gaps).
@Composable
private fun SetGroup(title: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) Eyebrow(title, Modifier.padding(start = 4.dp))
        CremaCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    }
}

// ── Machine hero — artwork well + info inner card + firmware inner card ──────
// Web .st-machinecard: grid 120px | 1fr | 280px, gap 24, padding 20×22. The two
// info panels share one inner-card treatment (tinted fill, hairline, 14dp pad,
// eyebrow at top, action pushed to the bottom) — the connection card neutral,
// the firmware card copper.
@Composable
private fun MachineHeroCard(
    connected: Boolean,
    stateLabel: String,
    firmware: String?,
    model: String?,
    board: String?,
    ble: String?,
    onConnect: () -> Unit,
    /** Null = firmware updating isn't implemented yet — the tile drops the button. */
    onUpdateFirmware: (() -> Unit)?,
) {
    CremaCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Artwork well — page-dark fill, the stylised DE1 drawing centred.
            Box(
                Modifier.width(120.dp).fillMaxHeight()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f), MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) { De1Artwork(Modifier.size(width = 88.dp, height = 104.dp)) }

            // Machine info inner card — the neutral twin of the firmware card.
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), MaterialTheme.shapes.medium)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CremaStatusDot(connected)
                    Eyebrow(stateLabel, color = if (connected) CremaTheme.telemetry.success else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (connected) {
                    Text("Decent DE1", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    HeroMeta("Firmware", firmware ?: "—")
                    HeroMeta("Model", model ?: "—")
                    HeroMeta("Board", board ?: "—")
                    HeroMeta("BLE", ble ?: "—")
                }
                Spacer(Modifier.weight(1f))
                CremaButton(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    variant = if (connected) CremaButtonVariant.Tonal else CremaButtonVariant.Filled,
                    icon = if (connected) "link-break" else "bluetooth",
                    label = if (connected) "Disconnect" else "Connect",
                )
            }

            // Firmware inner card — copper-tinted variant of the same shape.
            Column(
                Modifier.width(280.dp).fillMaxHeight()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.06f))
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.30f), MaterialTheme.shapes.medium)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("Firmware", color = MaterialTheme.colorScheme.primary)
                    if (!connected) ConnectDe1Pill()
                }
                Text(if (connected) "Up to date" else "No update info", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (connected) "Your DE1 is running the latest firmware Crema knows about."
                    else "Connect your DE1 to compare its installed firmware against the latest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (onUpdateFirmware != null) {
                    CremaButton(onClick = onUpdateFirmware, variant = CremaButtonVariant.Filled, enabled = connected, icon = "arrow-circle-up", label = "Check for updates")
                } else {
                    Text("Update checks aren\u2019t implemented yet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** One sync-activity line (web BeanSyncSection log): direction glyph + name + ago + error. */
@Composable
private fun SyncLogRow(entry: coffee.crema.visualizer.SyncLogEntry, last: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhIcon(
                when (entry.direction) {
                    "pull" -> "cloud-arrow-down"
                    "push" -> "cloud-arrow-up"
                    "delete" -> "trash"
                    else -> "warning"
                },
                sizeDp = 14,
                tint = if (entry.error != null) Color(0xFFD26456) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                entry.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Text(
                "${entry.direction} · ${android.text.format.DateUtils.getRelativeTimeSpanString(entry.at)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        entry.error?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFD26456),
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

/** The "needs a connected DE1" pill on the firmware card head (web .fw-conn-pill). */
@Composable
private fun ConnectDe1Pill() {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            "CONNECT DE1",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The stylised DE1 drawing — a 1:1 Canvas port of the web MachineSection's
 * inline SVG (viewBox 110×130): body shell, dark screen with the copper
 * status LED, group head, portafilter, drop shadow.
 */
@Composable
private fun De1Artwork(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    val copper = MaterialTheme.colorScheme.primary
    androidx.compose.foundation.Canvas(modifier) {
        val sx = size.width / 110f
        val sy = size.height / 130f
        fun x(v: Float) = v * sx
        fun y(v: Float) = v * sy
        // Drop shadow first so the body sits on it.
        drawOval(
            color = Color.Black.copy(alpha = 0.4f),
            topLeft = androidx.compose.ui.geometry.Offset(x(55f - 32f), y(110f - 4f)),
            size = androidx.compose.ui.geometry.Size(x(64f), y(8f)),
        )
        // Body shell.
        drawRoundRect(
            color = Color(0xFF3A2A1D),
            topLeft = androidx.compose.ui.geometry.Offset(x(20f), y(20f)),
            size = androidx.compose.ui.geometry.Size(x(70f), y(80f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(6f)),
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.18f),
            topLeft = androidx.compose.ui.geometry.Offset(x(20f), y(20f)),
            size = androidx.compose.ui.geometry.Size(x(70f), y(80f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(6f)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        )
        // Screen.
        drawRoundRect(
            color = Color(0xFF0D0907),
            topLeft = androidx.compose.ui.geometry.Offset(x(28f), y(28f)),
            size = androidx.compose.ui.geometry.Size(x(54f), y(34f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(3f)),
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.12f),
            topLeft = androidx.compose.ui.geometry.Offset(x(28f), y(28f)),
            size = androidx.compose.ui.geometry.Size(x(54f), y(34f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(3f)),
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx()),
        )
        // Status LED.
        drawCircle(color = copper, radius = x(2f), center = androidx.compose.ui.geometry.Offset(x(55f), y(45f)))
        // Group head + portafilter.
        drawRoundRect(
            color = tint.copy(alpha = 0.18f),
            topLeft = androidx.compose.ui.geometry.Offset(x(42f), y(68f)),
            size = androidx.compose.ui.geometry.Size(x(26f), y(6f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(2f)),
        )
        drawRoundRect(
            color = tint.copy(alpha = 0.10f),
            topLeft = androidx.compose.ui.geometry.Offset(x(48f), y(74f)),
            size = androidx.compose.ui.geometry.Size(x(14f), y(20f)),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(x(2f)),
        )
    }
}

/** The Visualizer hero card (web .st-visualizer): glyph well | identity | actions. */
@Composable
private fun VisualizerHeroCard(
    vz: coffee.crema.visualizer.VisualizerSync.UiState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onTest: () -> Unit,
    onOpenSite: () -> Unit,
) {
    CremaCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Glyph well — the copper pulse-curve mark.
            Box(
                Modifier.size(64.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), MaterialTheme.shapes.medium),
                contentAlignment = Alignment.Center,
            ) { VisualizerGlyph(Modifier.size(40.dp)) }

            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val eyebrow = when {
                    !vz.configured -> "OAuth not configured"
                    !vz.signedIn -> "Not connected"
                    else -> buildString {
                        append("Connected")
                        vz.account?.let { a ->
                            append(" · ${a.name}")
                            append(if (a.public) " · public profile" else " · private")
                        }
                    }
                }
                Eyebrow(
                    eyebrow,
                    color = when {
                        vz.signedIn -> MaterialTheme.colorScheme.primary
                        vz.configured -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> Color(0xFFDBA764)
                    },
                )
                Text(
                    "visualizer.coffee",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    when {
                        !vz.configured -> "Build with -PvisualizerClientId=… (or put visualizerClientId in local.properties) to enable sync."
                        !vz.signedIn -> "Free community service for sharing and comparing espresso shots"
                        else -> "Signed in"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onOpenSite).padding(vertical = 4.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "visualizer.coffee",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    PhIcon("arrow-square-out", sizeDp = 11, tint = MaterialTheme.colorScheme.primary)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vz.signedIn) {
                    CremaButton(
                        onClick = onTest,
                        variant = CremaButtonVariant.Outlined,
                        icon = "plugs-connected",
                        enabled = !vz.busy,
                        label = if (vz.busy) "Testing…" else "Test",
                    )
                    CremaButton(onClick = onSignOut, variant = CremaButtonVariant.Text, danger = true, icon = "sign-out", label = "Disconnect")
                } else {
                    CremaButton(
                        onClick = onSignIn,
                        variant = CremaButtonVariant.Filled,
                        icon = "sign-in",
                        enabled = vz.configured && !vz.busy,
                        label = if (vz.busy) "Signing in…" else "Sign in",
                    )
                }
            }
        }
    }
}

/** The Visualizer pulse-curve glyph — a Canvas port of the web's inline SVG. */
@Composable
private fun VisualizerGlyph(modifier: Modifier = Modifier) {
    val copper = MaterialTheme.colorScheme.primary
    val tint = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(modifier) {
        val sc = size.width / 48f
        fun p(v: Float) = v * sc
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(p(4f), p(36f))
            quadraticTo(p(12f), p(8f), p(24f), p(24f))
            // The SVG's T (smooth quadratic): control = reflection of (12,8) about (24,24).
            quadraticTo(p(36f), p(40f), p(44f), p(12f))
        }
        drawPath(
            path,
            color = copper,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = p(2.5f),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            ),
        )
        drawCircle(color = copper, radius = p(3.5f), center = androidx.compose.ui.geometry.Offset(p(24f), p(24f)))
        drawCircle(color = tint.copy(alpha = 0.6f), radius = p(2.5f), center = androidx.compose.ui.geometry.Offset(p(44f), p(12f)))
        drawCircle(color = tint.copy(alpha = 0.6f), radius = p(2.5f), center = androidx.compose.ui.geometry.Offset(p(4f), p(36f)))
    }
}

/** One "Other integrations" stub card (web .st-otherint-card) — disabled until real. */
@Composable
private fun OtherIntegrationCard(
    icon: String,
    title: String,
    sub: String,
    buttonIcon: String,
    buttonLabel: String,
    modifier: Modifier = Modifier,
) {
    CremaCard(modifier) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PhIcon(icon, sizeDp = 22, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            CremaButton(onClick = {}, variant = CremaButtonVariant.Outlined, icon = buttonIcon, label = buttonLabel, enabled = false)
        }
    }
}

@Composable
private fun HeroMeta(key: String, value: String) {
    // Web .st-machinecard-info-row: 72px key column, values stacked + aligned;
    // placeholder dashes render lighter so the empty stack reads uniform.
    val placeholder = value == "—"
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.width(72.dp))
        if (placeholder) {
            Text("—", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        } else {
            CremaMonoReadout(value, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

// ── Maintenance row — icon + note + right metric + 6dp progress bar ──────────
// When [onMarkDone] is provided the row shows a trailing "Mark done" text button
// that rebaselines the matching counter; [due] flips the metric + bar to amber.
@Composable
private fun MaintenanceRow(
    icon: String,
    title: String,
    note: String,
    value: String,
    unit: String?,
    pct: Float,
    due: Boolean,
    last: Boolean = false,
    onMarkDone: (() -> Unit)? = null,
) {
    val accent = if (due) Color(0xFFDBA764) else MaterialTheme.colorScheme.primary
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PhIcon(icon, sizeDp = 20, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    if (due) {
                        Text(
                            "Due",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFDBA764),
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0xFFDBA764).copy(alpha = 0.16f))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CremaValueUnit(value, unit, valueSize = 15.sp, valueColor = if (due) accent else MaterialTheme.colorScheme.onSurface)
            if (onMarkDone != null) {
                CremaButton(onClick = onMarkDone, variant = CremaButtonVariant.Text, label = "Mark done")
            }
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxWidth(pct.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(999.dp)).background(accent))
        }
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ── MMR raw → display scaling (the core surfaces RAW register words) ─────────
// Each helper reads de1MachineInfo[reg] (a raw UInt) and applies the
// per-register scaling the protocol defines, returning "—" when the register
// has not been read yet (disconnected, or the connect-time sweep reply hasn't
// landed). Ints are interpolated (never passed to String.format("%f")).

/** Machine-model id → marketing name, via core `machineModelName` (the firmware
 *  enum table — `1 = DE1` … `7 = DE1XXXL`, `"model N"` past the table) so web and
 *  Android render the same string. `—` only when the register is unread. */
internal fun machineModelLabel(info: Map<MmrRegister, UInt>): String =
    info[MmrRegister.MachineModel]?.let { machineModelName(it) } ?: "—"

/** CPU-board revision — raw is `version × 1000` (raw 1300 → "PCB v1.3"). The
 *  "PCB " prefix matches the web's machine readout. */
internal fun cpuBoardLabel(info: Map<MmrRegister, UInt>): String {
    val raw = info[MmrRegister.CpuBoardVersion]?.toInt() ?: return "—"
    // raw/1000 = major, (raw%1000)/100 = minor — interpolate Ints, no %f.
    return "PCB v${raw / 1000}.${(raw % 1000) / 100}"
}

/**
 * DE1 firmware version as the web shows it — the MMR FirmwareVersion BUILD
 * NUMBER formatted "v<n>" (e.g. "v1352", web's `firmwareBuild`). Falls back to
 * the BLE Version-characteristic string ([fallback] = `ui.de1Firmware`, e.g.
 * "v0.0.598 (API 4)") until the build-number register lands, then "—".
 */
internal fun firmwareLabel(info: Map<MmrRegister, UInt>, fallback: String?): String =
    info[MmrRegister.FirmwareVersion]?.let { "v$it" } ?: fallback ?: "—"

/** Machine serial number — raw word, shown as-is. */
internal fun serialLabel(info: Map<MmrRegister, UInt>): String =
    info[MmrRegister.SerialNumber]?.let { "$it" } ?: "—"

/**
 * Decode the raw HeaterVoltage MMR word (0x803834) to actual mains volts. The
 * firmware stamps `+1000` on user-committed writes, but a raw read can come back
 * as the actual volts (e.g. 120, not 1120) — so only un-stamp words ≥ 1000.
 * Mirrors the web readout (`v >= 1000 ? v - 1000 : v`); blindly subtracting 1000
 * turned a 120 V read into "−880 V". 0 = firmware not told yet; null = unread.
 */
private fun heaterVoltageVolts(info: Map<MmrRegister, UInt>): Int? {
    val raw = info[MmrRegister.HeaterVoltage]?.toInt() ?: return null
    return if (raw >= 1000) raw - 1000 else raw
}

/** Heater mains voltage readout — "120 V" / "230 V", "Not set" (auto-detect), or "—" (unread). */
internal fun heaterVoltageLabel(info: Map<MmrRegister, UInt>): String =
    when (val v = heaterVoltageVolts(info)) {
        null -> "—"
        0 -> "Not set"
        else -> "$v V"
    }

/** The current heater-voltage selection ("120" / "230") for the segmented control; null when unread or auto-detect (0). */
internal fun heaterVoltageValue(info: Map<MmrRegister, UInt>): String? =
    heaterVoltageVolts(info)?.takeIf { it > 0 }?.toString()

/** Bengle cup-warmer plate present? Models 4–7 (core `has_cup_warmer`). */
internal fun hasCupWarmerPlate(info: Map<MmrRegister, UInt>): Boolean =
    info[MmrRegister.MachineModel]?.let { hasCupWarmer(it) } ?: false

/** Cup-warmer plate temperature (°C), or null when the register is unread. */
internal fun cupWarmerTempValue(info: Map<MmrRegister, UInt>): Int? =
    info[MmrRegister.CupWarmerTemp]?.toInt()

/** Flow-calibration multiplier — raw is `int(1000 × multiplier)` (raw 1000 → ×1.00). */
internal fun flowMultiplierValue(info: Map<MmrRegister, UInt>): Double? =
    info[MmrRegister.CalibrationFlowMultiplier]?.let { it.toInt() / 1000.0 }

/** Whether the GHC is present (GhcInfo bit 0). Null when the register is unread. */
internal fun ghcPresent(info: Map<MmrRegister, UInt>): Boolean? =
    info[MmrRegister.GhcInfo]?.let { (it.toInt() and 0x1) != 0 }

/** Whether GHC start-from-machine mode is on (GhcMode != 0). Null when unread. */
internal fun ghcModeOn(info: Map<MmrRegister, UInt>): Boolean? =
    info[MmrRegister.GhcMode]?.let { it.toInt() != 0 }

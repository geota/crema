package coffee.crema.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.MmrRegister
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.CremaValueUnit
import coffee.crema.ui.components.Eyebrow
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
/**
 * Tank level (mm) below which the Water section shows a "low — refill soon"
 * note. A coarse heuristic on the live `Event.WaterLevel` reading; the precise
 * low/refill thresholds belong to the deferred maintenance store.
 */
private const val LOW_TANK_MM = 5f

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
    // Destructive-action confirms (the dead stubs the delete-dialogs audit flagged).
    var confirmResetPrefs by rememberSaveable { mutableStateOf(false) }
    var confirmErase by rememberSaveable { mutableStateOf(false) }
    // Heater-voltage confirm gate: the segmented control stages the chosen
    // voltage here and shows a CremaConfirmDialog before the destructive write.
    var pendingHeaterVoltage by rememberSaveable { mutableStateOf<String?>(null) }
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
    // Heater voltage is service-grade: wrong voltage damages the heater, so the
    // segmented choice is gated behind a danger confirm before the FFI write.
    pendingHeaterVoltage?.let { volts ->
        CremaConfirmDialog(
            title = "Set heater voltage to $volts V?",
            body = "Setting the wrong mains voltage can damage the DE1's heater. Only change this if you are certain of your wall power.",
            confirmLabel = "Set $volts V",
            danger = true,
            onConfirm = {
                volts.toIntOrNull()?.let { vm.setHeaterVoltage(it) }
                pendingHeaterVoltage = null
            },
            onDismiss = { pendingHeaterVoltage = null },
        )
    }

    // SAF export-to-file plumbing (same pattern as History / Beans / Profiles).
    // Plain remember — a multi-MB history JSON in saved-instance-state would
    // blow the 1 MB Binder cap on background; losing it on process death is fine.
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = pendingExport; pendingExport = null
        if (uri != null && t != null) vm.writeTextToUri(uri, t)
    }
    val launchSave: (String, String?) -> Unit = { name, content ->
        if (content != null) { pendingExport = content; saveLauncher.launch(name) }
    }

    // ── Local design-faithful prefs (persistence deferred to a SettingsStore) ──
    // Pilled placeholder rows keep local state only (their pills mark them
    // not-implemented); everything functional reads ui.* / AppPrefs.
    var density by rememberSaveable { mutableStateOf("comfortable") }
    var unitTemp by rememberSaveable { mutableStateOf("c") }
    var unitWeight by rememberSaveable { mutableStateOf("g") }
    var keepAwake by rememberSaveable { mutableStateOf(true) }
    var autoSync by rememberSaveable { mutableStateOf(false) }
    var smoothPressure by rememberSaveable { mutableStateOf(true) }
    var tempOffset by rememberSaveable { mutableStateOf(0.0) }

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
                            firmware = if (connected) ui.de1Firmware else null,
                            model = if (connected) machineModelLabel(ui.de1MachineInfo).takeIf { it != "—" } else null,
                            board = if (connected) cpuBoardLabel(ui.de1MachineInfo).takeIf { it != "—" } else null,
                            ble = if (connected) "Paired" else null,
                            onConnect = { onConnect("machine") },
                            onUpdateFirmware = null,
                        )
                        SetGroup("Connection") {
                            SetRow("Telemetry rate", "How often the chart samples live data.", notImplemented = true) { SetSelect("50 Hz") }
                            SetRow("Keep DE1 awake", "Hold a keep-alive so the machine stays ready.", notImplemented = true) { CremaSwitch(keepAwake, { keepAwake = it }) }
                            // GHC start-from-machine mode — driven by the live GhcMode
                            // register; the toggle writes via the VM. Disabled until the
                            // machine is connected and the GHC is reported present.
                            val ghcOn = ghcModeOn(ui.de1MachineInfo) ?: false
                            val ghcAvailable = connected && (ghcPresent(ui.de1MachineInfo) == true)
                            SetRow("Group Head Controller (GHC)", "Start shots from the machine's touch panel.", last = true) {
                                CremaSwitch(ghcOn, { vm.setGhcMode(it) }, enabled = ghcAvailable)
                            }
                        }
                        SetGroup("Identity") {
                            SetRow("Model") { MonoReadout(machineModelLabel(ui.de1MachineInfo), color = MaterialTheme.colorScheme.onSurface) }
                            SetRow("Serial number") { MonoReadout(serialLabel(ui.de1MachineInfo), color = MaterialTheme.colorScheme.onSurface) }
                            SetRow("CPU board") { MonoReadout(cpuBoardLabel(ui.de1MachineInfo), color = MaterialTheme.colorScheme.onSurface) }
                            SetRow("Firmware") { MonoReadout(ui.de1Firmware ?: "—", color = MaterialTheme.colorScheme.onSurface) }
                            SetRow("Heater voltage", last = true) { MonoReadout(heaterVoltageLabel(ui.de1MachineInfo), color = MaterialTheme.colorScheme.onSurface) }
                        }
                        SetGroup("Diagnostics") {
                            SetRow("Connection state") { MonoReadout(if (connected) "Ready" else "Disconnected", color = MaterialTheme.colorScheme.onSurface) }
                            SetRow("GATT verified") { StatusDot(connected) }
                            SetRow("Machine state") { MonoReadout(ui.machineState ?: "—", color = MaterialTheme.colorScheme.onSurface) }
                            SetRow("Notifications received", last = true) { MonoReadout(if (connected) "—" else "0", color = MaterialTheme.colorScheme.onSurface) }
                        }
                        SetGroup("Peripherals") {
                            SetRow("Scale", if (scaleConnected) (ui.scaleName ?: "Connected") else "Not paired") {
                                if (scaleConnected) StatusDot(true) else CremaButton(onClick = { onConnect("scale") }, variant = CremaButtonVariant.Outlined, label = "Pair")
                            }
                            SetRow("Grinder", "No grinder support yet.", last = true) { StatusDot(false) }
                        }
                    }
                    "brew" -> {
                        SetHead("Defaults", "Brew defaults", "Seed values for a fresh shot. Per-profile recipes override these.")
                        SetGroup("Targets") {
                            // Persisted (AppPrefs) — these seed "New profile" via
                            // brewDefaultsJson, so the dialled numbers are real.
                            val setDefs = { d: Float, r: Float, t: Float, p: Float -> vm.setBrewDefaults(d, r, t, p) }
                            val dD = ui.defaultDoseG; val dR = ui.defaultRatio; val dT = ui.defaultBrewTempC; val dP = ui.defaultPreinfuseS
                            SetRow("Default dose", "Starting dose for a fresh shot.") { SetStepper(String.format("%.1f", dD), "g", { setDefs((dD - 0.1f).coerceAtLeast(5f), dR, dT, dP) }, { setDefs((dD + 0.1f).coerceAtMost(30f), dR, dT, dP) }) }
                            SetRow("Default ratio", "Target brew ratio (yield ÷ dose).") { SetStepper("1:" + String.format("%.1f", dR), null, { setDefs(dD, (dR - 0.1f).coerceAtLeast(1f), dT, dP) }, { setDefs(dD, (dR + 0.1f).coerceAtMost(4f), dT, dP) }) }
                            SetRow("Default brew temp", "Starting group temperature.") { SetStepper(String.format("%.1f", dT), "°C", { setDefs(dD, dR, (dT - 0.5f).coerceAtLeast(80f), dP) }, { setDefs(dD, dR, (dT + 0.5f).coerceAtMost(100f), dP) }) }
                            SetRow("Default pre-infusion", "Low-pressure soak before the main shot.", last = true) { SetStepper("${dP.toInt()}", "s", { setDefs(dD, dR, dT, (dP - 1f).coerceAtLeast(0f)) }, { setDefs(dD, dR, dT, (dP + 1f).coerceAtMost(30f)) }) }
                        }
                        SetGroup("Shot behaviour") {
                            SetRow("Auto-tare on shot start", "Zero the scale automatically when extraction begins.") { CremaSwitch(ui.autoTare, vm::setAutoTare) }
                            SetRow("Stop on weight", "End the shot once the target yield is reached.") { CremaSwitch(ui.stopOnWeight, vm::setStopOnWeight) }
                            // Max shot duration — persisted in AppPrefs + read from
                            // ui.maxShotDurationS so it survives + shows on Brew's stop
                            // conditions. The stepper pushes each change through the VM.
                            SetRow("Max shot duration", "Hard time cap — also a Brew stop condition.") {
                                val maxDur = ui.maxShotDurationS.toInt()
                                SetStepper(
                                    "$maxDur", "s",
                                    { vm.setMaxShotDuration((maxDur - 1).coerceAtLeast(20).toFloat()) },
                                    { vm.setMaxShotDuration((maxDur + 1).coerceAtMost(120).toFloat()) },
                                )
                            }
                            SetRow("Group flush before each shot", "Stabilise the group temperature with a short flush.", notImplemented = true) { CremaSwitch(ui.preFlush, vm::setPreFlush) }
                            SetRow("Auto-purge after steam", "Clear the steam wand automatically after steaming.", notImplemented = true) { CremaSwitch(ui.steamPurge, vm::setSteamPurge) }
                            SetRow("Steam eco", "Idle the steam boiler cooler between sessions to save power.", last = true) { CremaSwitch(ui.steamEco, vm::setSteamEco) }
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
                            val low = mm != null && mm < LOW_TANK_MM
                            SetRow(
                                "Water tank",
                                when {
                                    mm == null -> "Connect the DE1 to read the tank level."
                                    low -> "Low — refill soon."
                                    else -> "Tank level looks good."
                                },
                                last = true,
                            ) {
                                MonoReadout(
                                    if (mm != null) "${mm.toInt()} mm" else "—",
                                    color = if (low) Color(0xFFDBA764) else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        SetGroup("Cycles") {
                            // "Run now" drives the DE1's descale / clean cycles via the
                            // VM (MachineRequest.DESCALE / CLEAN). Disabled until ready.
                            SetRow("Descale", "Run the DE1's descale cycle.", needsConnection = !connected) {
                                CremaButton(onClick = { vm.startDescale() }, variant = CremaButtonVariant.Outlined, enabled = connected, icon = "play", label = "Run now")
                            }
                            SetRow("Group clean", "Run the DE1's cleaning cycle.", last = true, needsConnection = !connected) {
                                CremaButton(onClick = { vm.startClean() }, variant = CremaButtonVariant.Outlined, enabled = connected, icon = "play", label = "Run now")
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
                            MaintenanceRow(
                                icon = "funnel",
                                title = "Water filter",
                                note = ro?.let {
                                    "${String.format("%.1f", it.filterUsedLitres)} L of " +
                                        "${m.filterCapacityLitres.toInt()} L used"
                                } ?: "Awaiting data.",
                                value = ro?.let { "${it.filterPercent.toInt()}" } ?: "—",
                                unit = ro?.let { "%" },
                                pct = ro?.let { (it.filterPercent / 100.0).toFloat() } ?: 0f,
                                due = ro?.let { !it.filterOk } ?: false,
                                onMarkDone = { vm.markFilterCleaned() },
                            )
                            MaintenanceRow(
                                icon = "drop",
                                title = "Descale",
                                note = ro?.let {
                                    "${String.format("%.0f", it.descaleSinceLitres)} L since last descale " +
                                        "· every ${m.descaleIntervalLitres.toInt()} L"
                                } ?: "Awaiting data.",
                                value = ro?.let { String.format("%.0f", it.descaleSinceLitres) } ?: "—",
                                unit = ro?.let { "L" },
                                pct = ro?.let {
                                    if (m.descaleIntervalLitres > 0.0)
                                        (it.descaleSinceLitres / m.descaleIntervalLitres).toFloat()
                                    else 0f
                                } ?: 0f,
                                due = ro?.let { !it.descaleOk } ?: false,
                                onMarkDone = { vm.markDescaled() },
                            )
                            MaintenanceRow(
                                icon = "wind",
                                title = "Group clean",
                                note = ro?.let {
                                    // cleanSinceHours is a Long — interpolate, never %f.
                                    "${it.cleanSinceHours} h since last clean " +
                                        "· every ${m.cleanIntervalHours.toInt()} h"
                                } ?: "Awaiting data.",
                                value = ro?.let { "${it.cleanSinceHours}" } ?: "—",
                                unit = ro?.let { "h" },
                                pct = ro?.let {
                                    if (m.cleanIntervalHours > 0.0)
                                        (it.cleanSinceHours.toDouble() / m.cleanIntervalHours).toFloat()
                                    else 0f
                                } ?: 0f,
                                due = ro?.let { !it.cleanOk } ?: false,
                                onMarkDone = { vm.markCleaned() },
                                last = true,
                            )
                        }
                        SetGroup("Water chemistry") {
                            // No core method for water hardness / TDS — display only.
                            SetRow("Water source", "Your feed water — tunes descale intervals.", notImplemented = true) { SetSelect("Filtered tap") }
                            SetRow("Hardness", "General hardness (GH).", notImplemented = true) { MonoReadout("50 ppm", color = MaterialTheme.colorScheme.onSurface) }
                            SetRow("Total dissolved solids", "Measured TDS of your water.", last = true, notImplemented = true) { MonoReadout("110 ppm", color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    "display" -> {
                        SetHead("Appearance", "Display & units", "How Crema looks and the units it shows across every readout.")
                        SetGroup("Appearance") {
                            SetRow("Theme", "Crema defaults to dark — the machine app is dark-skinned.") {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("light", "Light"), SegOption("dark", "Dark")),
                                    value = if (ui.themeMode == "light") "light" else "dark",
                                    onChange = vm::setThemeMode,
                                )
                            }
                            SetRow("Density", "Comfortable spacing or a denser layout.", notImplemented = true) {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("comfortable", "Comfortable"), SegOption("compact", "Compact")),
                                    value = density,
                                    onChange = { density = it },
                                )
                            }
                            SetRow("Screensaver", "Dim the display after a period idle.", notImplemented = true) { SetSelect("After 10 min") }
                            SetRow("Keep screen on while brewing", "Hold the display awake during a shot.", last = true) { CremaSwitch(ui.keepScreenOnBrew, vm::setKeepScreenOnBrew) }
                        }
                        SetGroup("Units") {
                            SetRow("Temperature", "Units for every temperature readout.", notImplemented = true) {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("c", "°C"), SegOption("f", "°F")),
                                    value = unitTemp,
                                    onChange = { unitTemp = it },
                                )
                            }
                            SetRow("Weight", "Units for dose and yield.", notImplemented = true) {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("g", "Grams"), SegOption("oz", "Ounces")),
                                    value = unitWeight,
                                    onChange = { unitWeight = it },
                                )
                            }
                            SetRow("Pressure", "Units for the pressure channel.", notImplemented = true) { SetSelect("bar") }
                            SetRow("Volume", "Units for water and yield volume.", last = true, notImplemented = true) { SetSelect("ml") }
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
                                SetRow("Auto-sync new shots", "Upload each shot as it finishes.") {
                                    CremaSwitch(vz.autoSync, vm.visualizer::setAutoSync)
                                }
                                SetRow("Privacy", "Who can see shots you upload.") {
                                    CremaSegmentedButton(
                                        options = listOf(SegOption("public", "Public"), SegOption("unlisted", "Unlisted"), SegOption("private", "Private")),
                                        value = vz.privacy,
                                        onChange = vm.visualizer::setPrivacy,
                                    )
                                }
                                SetRow("Include profile", "Attach the full recipe to uploads.") { CremaSwitch(vz.includeProfile, vm.visualizer::setIncludeProfile) }
                                SetRow("Include tasting notes", "Attach your notes to uploads.") { CremaSwitch(vz.includeNotes, vm.visualizer::setIncludeNotes) }
                                val unsynced = ui.history.count { it.visualizerId == null }
                                SetRow(
                                    "Upload backlog",
                                    if (unsynced == 0) "All ${ui.history.size} shots are on Visualizer."
                                    else "$unsynced shot(s) not uploaded yet.",
                                ) {
                                    val uploading = vz.uploadingShotIds.isNotEmpty()
                                    CremaButton(
                                        onClick = { vm.visualizer.uploadAllUnsynced(ui.history) },
                                        variant = CremaButtonVariant.Outlined,
                                        icon = "cloud-arrow-up",
                                        enabled = unsynced > 0 && !vz.busy && !uploading,
                                        label = if (uploading) "Uploading…" else "Upload all",
                                    )
                                }
                                SetRow("Last sync", "Most recent successful shot upload.", last = true) {
                                    MonoReadout(
                                        vz.lastShotSyncAt?.let {
                                            android.text.format.DateUtils.getRelativeTimeSpanString(it).toString()
                                        } ?: "—",
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                        SetGroup("Local export") {
                            SetRow(
                                "History export",
                                "One-shot download of your entire shot history as JSON. Useful for spreadsheets and other tools.",
                                last = true,
                            ) {
                                CremaButton(onClick = { launchSave("crema-history.json", vm.shotsJson(null)) }, variant = CremaButtonVariant.Outlined, icon = "download-simple", label = "Export")
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
                            SetRow("Temperature", "Shift every temperature reading.", notImplemented = true) { SetStepper(String.format("%+.1f", tempOffset), "°C", { tempOffset = (tempOffset - 0.1).coerceAtLeast(-5.0) }, { tempOffset = (tempOffset + 0.1).coerceAtMost(5.0) }) }
                            SetRow("Pressure", "Re-zero the pressure sensor at idle.", notImplemented = true) { MonoReadout("0.0 bar", color = MaterialTheme.colorScheme.onSurface) }
                            // Flow multiplier — reads the live CalibrationFlowMultiplier
                            // register and writes via setCalibrationFlowMultiplier. The
                            // step nudges the current value (default ×1.00 when unread).
                            val mult = flowMultiplierValue(ui.de1MachineInfo) ?: 1.0
                            SetRow("Flow", "Scale the flow-meter reading.", needsConnection = !connected) {
                                SetStepper(
                                    String.format("%.2f", mult), "×",
                                    { vm.setFlowMultiplier((mult - 0.01).coerceAtLeast(0.5).toFloat()) },
                                    { vm.setFlowMultiplier((mult + 0.01).coerceAtMost(1.5).toFloat()) },
                                )
                            }
                            SetRow("Last read", "Flow multiplier reported by the DE1.", last = true) { MonoReadout(flowMultiplierValue(ui.de1MachineInfo)?.let { String.format("×%.2f", it) } ?: "—", color = MaterialTheme.colorScheme.onSurface) }
                        }
                    }
                    "advanced" -> {
                        SetHead("Power user", "Advanced", "Telemetry tuning, developer tools, and destructive resets.")
                        SetGroup("Telemetry") {
                            // AC mains frequency override — the core accepts 0 (auto),
                            // 50, or 60 Hz; the selection writes via the VM and the
                            // resolved value is read back into ui.lineFreqHz.
                            val freqValue = when (ui.lineFreqHz) {
                                50.0f -> "50"
                                60.0f -> "60"
                                0.0f -> "auto"
                                else -> "auto"
                            }
                            SetRow("AC mains frequency", "Match your wall power for clean temperature control.", needsConnection = !connected) {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("auto", "Auto"), SegOption("50", "50 Hz"), SegOption("60", "60 Hz")),
                                    value = freqValue,
                                    onChange = { vm.setLineFrequency(if (it == "auto") 0.0f else it.toFloat()) },
                                )
                            }
                            SetRow("Smooth pressure curve", "Filter chart noise on the live readout.", last = true, notImplemented = true) { CremaSwitch(smoothPressure, { smoothPressure = it }) }
                        }
                        SetGroup("Diagnostics") {
                            SetRow("Debug readout", "The live Phase-0 telemetry + event log.", last = true) { CremaButton(onClick = { onNav("debug") }, variant = CremaButtonVariant.Tonal, icon = "bug", label = "Open") }
                        }
                        SetGroup("Service-grade") {
                            // Mains heater voltage — service-grade, gated behind a danger
                            // confirm (staged in pendingHeaterVoltage). Reflects the live
                            // HeaterVoltage register; the VM guards 120/230 again.
                            val hv = heaterVoltageValue(ui.de1MachineInfo) ?: "230"
                            SetRow("Mains heater voltage", "Wrong voltage damages the heater — service only.", last = true, needsConnection = !connected) {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("120", "120 V"), SegOption("230", "230 V")),
                                    value = hv,
                                    onChange = { pendingHeaterVoltage = it },
                                )
                            }
                            // REMOVED: "Reset machine to factory" — there is no core /
                            // FFI method for a DE1 factory reset (verified), so a wired
                            // action is impossible; a dead button would be misleading.
                        }
                        SetGroup("Reset") {
                            SetRow("Reset preferences", "Restore Crema's settings to defaults.") { CremaButton(onClick = { confirmResetPrefs = true }, variant = CremaButtonVariant.Text, label = "Reset") }
                            SetRow("Erase all data", "Delete every profile, bean and shot.", last = true) { CremaButton(onClick = { confirmErase = true }, variant = CremaButtonVariant.Text, danger = true, label = "Erase") }
                        }
                    }
                    "about" -> {
                        SetHead("About", "Crema", "A fast, native control surface for the Decent DE1 espresso machine.")
                        SetGroup("Version") {
                            val appVersion = run {
                                val ctx = androidx.compose.ui.platform.LocalContext.current
                                remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "dev" }
                            }
                            SetRow("App") { MonoReadout(appVersion, color = MaterialTheme.colorScheme.onSurface) }
                            SetRow("Core") { MonoReadout("de1-core · UniFFI", color = MaterialTheme.colorScheme.onSurface) }
                            SetRow("Machine", last = true) { MonoReadout("Decent DE1", color = MaterialTheme.colorScheme.onSurface) }
                        }
                        SetGroup("Project") {
                            // No public repo / licenses surface to link yet — rows render
                            // as plain acknowledgements instead of dead buttons (the same
                            // call as the removed factory-reset row).
                            SetRow("Source", "Crema is open source.", notImplemented = true) { SetSelect("Link coming") }
                            SetRow("Licenses", "Built on uPlot, fflate, wasm-bindgen, the Decent de1app protocol docs and more.", last = true, notImplemented = true) { SetSelect("List coming") }
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

// ── Settings row — title + optional sub (left), trailing control, bottom rule ─
// `notImplemented` shows a "Not implemented yet" pill + dims the control (PWA
// StRow.notImplemented); `needsConnection` shows a copper "Connect DE1" pill.
@Composable
private fun SetRow(
    title: String,
    sub: String? = null,
    last: Boolean = false,
    notImplemented: Boolean = false,
    needsConnection: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (notImplemented) SetPill("Not implemented yet")
                else if (needsConnection) SetPill("Connect DE1", copper = true)
            }
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Box(Modifier.alpha(if (notImplemented) 0.5f else 1f)) { trailing() }
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ── Status pill on a settings row — neutral "Not implemented yet" or copper
// "Connect DE1" (PWA .st-row-pill). ──────────────────────────────────────────
@Composable
private fun SetPill(text: String, copper: Boolean = false) {
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

// ── Flat select pill (value + caret) — opens a menu (static for now) ─────────
@Composable
private fun SetSelect(value: String, onClick: () -> Unit = {}) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.height(40.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            PhIcon("caret-down", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Compact 36dp stepper (distinct from the 56dp telemetry CremaStepper) ─────
@Composable
private fun SetStepper(value: String, unit: String?, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StepBtn("minus", onMinus)
        Box(Modifier.widthIn(min = 64.dp), contentAlignment = Alignment.Center) {
            CremaValueUnit(value, unit, valueSize = 16.sp)
        }
        StepBtn("plus", onPlus)
    }
}

@Composable
private fun StepBtn(icon: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) { PhIcon(icon, sizeDp = 15) }
    }
}

// ── Status dot — 10dp; on = success fill + faint ring, off = hairline ring ───
@Composable
private fun StatusDot(on: Boolean) {
    val success = CremaTheme.telemetry.success
    if (on) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(success))
    } else {
        Box(Modifier.size(10.dp).clip(CircleShape).border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape))
    }
}

// ── Tiny mono readout (diagnostics / versions) ───────────────────────────────
@Composable
private fun MonoReadout(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, fontFeatureSettings = "tnum"), color = color)
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
                    StatusDot(connected)
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
            quadraticBezierTo(p(12f), p(8f), p(24f), p(24f))
            // The SVG's T (smooth quadratic): control = reflection of (12,8) about (24,24).
            quadraticBezierTo(p(36f), p(40f), p(44f), p(12f))
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
            MonoReadout(value, color = MaterialTheme.colorScheme.onSurface)
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

/** Machine-model id → marketing name (`0 = Unset`, `1 = DE1` … `7 = DE1XXXL`). */
private fun machineModelLabel(info: Map<MmrRegister, UInt>): String =
    when (info[MmrRegister.MachineModel]?.toInt()) {
        1 -> "DE1"
        2 -> "DE1+"
        3 -> "DE1PRO"
        4 -> "DE1XL"
        5 -> "DE1CAFE"
        6 -> "DE1XXL"
        7 -> "DE1XXXL"
        0 -> "Unknown"
        null -> "—"
        else -> "Model ${info[MmrRegister.MachineModel]?.toInt()}"
    }

/** CPU-board revision — raw is `version × 1000` (raw 1100 → "v1.1"). */
private fun cpuBoardLabel(info: Map<MmrRegister, UInt>): String {
    val raw = info[MmrRegister.CpuBoardVersion]?.toInt() ?: return "—"
    // raw/1000 = major, (raw%1000)/100 = minor — interpolate Ints, no %f.
    return "v${raw / 1000}.${(raw % 1000) / 100}"
}

/** Machine serial number — raw word, shown as-is. */
private fun serialLabel(info: Map<MmrRegister, UInt>): String =
    info[MmrRegister.SerialNumber]?.let { "$it" } ?: "—"

/** Heater mains voltage — wire value is `volts + 1000` (raw 1230 → 230 V). */
private fun heaterVoltageLabel(info: Map<MmrRegister, UInt>): String {
    val raw = info[MmrRegister.HeaterVoltage]?.toInt() ?: return "—"
    return "${raw - 1000} V"
}

/** The current heater-voltage selection ("120" / "230") for the segmented control, or null. */
private fun heaterVoltageValue(info: Map<MmrRegister, UInt>): String? =
    info[MmrRegister.HeaterVoltage]?.toInt()?.let { (it - 1000).toString() }

/** Flow-calibration multiplier — raw is `int(1000 × multiplier)` (raw 1000 → ×1.00). */
private fun flowMultiplierValue(info: Map<MmrRegister, UInt>): Double? =
    info[MmrRegister.CalibrationFlowMultiplier]?.let { it.toInt() / 1000.0 }

/** Whether the GHC is present (GhcInfo bit 0). Null when the register is unread. */
private fun ghcPresent(info: Map<MmrRegister, UInt>): Boolean? =
    info[MmrRegister.GhcInfo]?.let { (it.toInt() and 0x1) != 0 }

/** Whether GHC start-from-machine mode is on (GhcMode != 0). Null when unread. */
private fun ghcModeOn(info: Map<MmrRegister, UInt>): Boolean? =
    info[MmrRegister.GhcMode]?.let { it.toInt() != 0 }

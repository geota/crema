package coffee.crema.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.ModeInfo
import coffee.crema.core.RangeCapability
import coffee.crema.ui.screens.BrewScreen
import coffee.crema.ui.screens.ProfilesScreen
import coffee.crema.ui.screens.ScaleScreen
import coffee.crema.ui.theme.CremaTheme

/**
 * The app's current single screen: a Connect button and a live readout of the
 * events the Rust core decodes from the DE1's BLE notifications. This is the
 * Phase-0 FFI/BLE proof-of-concept screen; future screens re-flow by window
 * size class (see README "Structure").
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    /** Android-12 BLE runtime permissions. */
    private val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    /**
     * Action to run once BLE permissions are granted. Set by [withBlePermission]
     * before the launcher fires, so the same launcher serves both the DE1 and
     * the scale connect buttons.
     */
    private var pendingPermissionAction: (() -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            pendingPermissionAction?.invoke()
        }
        // If denied, the UI status simply stays "Idle"; the user can retry.
        pendingPermissionAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CremaTheme {
                val ui by viewModel.ui.collectAsState()
                val machineConnected = ui.bleState == De1BleManager.State.READY
                val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
                val onRailConnect: (String) -> Unit = { which ->
                    when (which) {
                        "machine" ->
                            if (machineConnected) viewModel.disconnect()
                            else withBlePermission(viewModel::connect)
                        "scale" ->
                            if (scaleConnected) viewModel.disconnectScale()
                            else withBlePermission(viewModel::connectScale)
                    }
                }
                AppNavHost(
                    machineConnected = machineConnected,
                    scaleConnected = scaleConnected,
                    onRailConnect = onRailConnect,
                    brewContent = { navTo ->
                        BrewScreen(viewModel, onNav = navTo, onConnect = onRailConnect)
                    },
                    profilesContent = { navTo ->
                        ProfilesScreen(viewModel, onNav = navTo, onConnect = onRailConnect)
                    },
                    scaleContent = { navTo ->
                        ScaleScreen(viewModel, onNav = navTo, onConnect = onRailConnect)
                    },
                    debugContent = {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            MainScreen(
                                viewModel = viewModel,
                                onConnect = { withBlePermission(viewModel::connect) },
                                onDisconnect = viewModel::disconnect,
                                onConnectScale = { withBlePermission(viewModel::connectScale) },
                                onDisconnectScale = viewModel::disconnectScale,
                                onTareScale = viewModel::tareScale,
                                onSetScaleVolume = viewModel::setScaleVolume,
                                onSetScaleStandbyMinutes = viewModel::setScaleStandbyMinutes,
                                onSetScaleFlowSmoothing = viewModel::setScaleFlowSmoothing,
                                onSetScaleAntiMistouch = viewModel::setScaleAntiMistouch,
                                onSetScaleMode = viewModel::setScaleMode,
                                onSetScaleAutoStop = viewModel::setScaleAutoStop,
                            )
                        }
                    },
                )
            }
        }
    }

    /** Request BLE permissions if needed, then run [action] (scan + connect). */
    private fun withBlePermission(action: () -> Unit) {
        val missing = blePermissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            pendingPermissionAction = action
            permissionLauncher.launch(blePermissions)
        } else {
            action()
        }
    }
}

@Composable
private fun MainScreen(
    viewModel: MainViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onConnectScale: () -> Unit,
    onDisconnectScale: () -> Unit,
    onTareScale: () -> Unit,
    onSetScaleVolume: (Int) -> Unit,
    onSetScaleStandbyMinutes: (Int) -> Unit,
    onSetScaleFlowSmoothing: (Boolean) -> Unit,
    onSetScaleAntiMistouch: (Boolean) -> Unit,
    onSetScaleMode: (Int) -> Unit,
    onSetScaleAutoStop: (Int) -> Unit,
) {
    val ui by viewModel.ui.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Crema", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Phase-0 FFI/BLE path: cargo-ndk → UniFFI → Compose → live DE1 BLE.",
            style = MaterialTheme.typography.bodySmall,
        )

        val connected = ui.bleState == De1BleManager.State.READY ||
            ui.bleState == De1BleManager.State.CONNECTING ||
            ui.bleState == De1BleManager.State.DISCOVERING ||
            ui.bleState == De1BleManager.State.SUBSCRIBING ||
            ui.bleState == De1BleManager.State.SCANNING

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onConnect,
                enabled = !connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect to DE1") }

            OutlinedButton(
                onClick = onDisconnect,
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect") }
        }

        // Machine control (AND5) — proves requestMachineState round-trips to a
        // real DE1. The Brew screen replaces these with the real controls in M1/M2.
        val de1Ready = ui.bleState == De1BleManager.State.READY
        Text("Machine control", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = viewModel::wake, enabled = de1Ready) { Text("Wake") }
            Button(onClick = viewModel::sleep, enabled = de1Ready) { Text("Sleep") }
            Button(onClick = viewModel::startEspresso, enabled = de1Ready) { Text("Espresso") }
            OutlinedButton(onClick = viewModel::stopShot, enabled = de1Ready) { Text("Stop") }
        }

        ScaleSection(
            scaleState = ui.scaleState,
            scaleName = ui.scaleName,
            scaleFirmware = ui.scaleFirmware,
            scaleSerial = ui.scaleSerial,
            scaleWeightG = ui.scaleWeightG,
            scaleFlowGPerS = ui.scaleFlowGPerS,
            scaleTimerMs = ui.scaleTimerMs,
            scaleBatteryPercent = ui.scaleBatteryPercent,
            volumeRange = ui.scaleCapabilities?.volume,
            scaleVolume = ui.scaleVolume,
            standbyRange = ui.scaleCapabilities?.standby,
            scaleStandbyMinutes = ui.scaleStandbyMinutes,
            flowSmoothingSupported = ui.scaleCapabilities?.flow_smoothing == true,
            scaleFlowSmoothing = ui.scaleFlowSmoothing,
            antiMistouchSupported = ui.scaleCapabilities?.anti_mistouch == true,
            scaleAntiMistouch = ui.scaleAntiMistouch,
            modes = ui.scaleCapabilities?.modes ?: emptyList(),
            scaleActiveMode = ui.scaleActiveMode,
            autoStopSupported = ui.scaleCapabilities?.auto_stop == true,
            scaleAutoStop = ui.scaleAutoStop,
            onConnectScale = onConnectScale,
            onDisconnectScale = onDisconnectScale,
            onTareScale = onTareScale,
            onSetScaleVolume = onSetScaleVolume,
            onSetScaleStandbyMinutes = onSetScaleStandbyMinutes,
            onSetScaleFlowSmoothing = onSetScaleFlowSmoothing,
            onSetScaleAntiMistouch = onSetScaleAntiMistouch,
            onSetScaleMode = onSetScaleMode,
            onSetScaleAutoStop = onSetScaleAutoStop,
        )

        ReadoutCard(
            bleState = ui.bleState.name,
            status = ui.status,
            machineState = ui.machineState,
            shotPhase = ui.shotPhase,
            telemetry = ui.telemetry,
        )

        Text("Decoded events", style = MaterialTheme.typography.titleMedium)
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(ui.eventLog) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * The scale section: a Connect button, a live weight readout, a Tare button,
 * and any capability-gated configuration controls. The scale connection is
 * independent of the DE1 — it works with or without a machine connected.
 *
 * `scaleFlowGPerS` / `scaleTimerMs` / `scaleBatteryPercent` are the scale's own
 * native readings — only populated for scales that report them (the Bookoo);
 * shown as raw data alongside the weight.
 *
 * Configuration controls are **capability-gated, never device-gated**: each
 * control renders only when its capability is present in the core's
 * `ScaleCapabilities` — the volume / standby steppers when their range is
 * non-null and over exactly that scale-reported `[min, max]` range, the flow-
 * smoothing / anti-mistouch toggles when their flag is set, the mode buttons
 * when the scale lists any modes, the auto-stop selector when `auto_stop` is
 * set. The UI never branches on the concrete scale model — a weight-only
 * scale simply shows no configuration controls.
 *
 * Every configuration control is **two-way**: `scaleVolume` /
 * `scaleStandbyMinutes` / `scaleFlowSmoothing` / `scaleAutoStop` follow the
 * scale's live values decoded from every Bookoo weight notification, while
 * `scaleAntiMistouch` and `scaleActiveMode` follow the scale's `ff12` command
 * channel (the `03 0c` / `03 0e` responses surfaced as `Event.ScaleConfig`).
 * So the controls reflect the real device state — the mode and auto-stop
 * selectors highlight the live current mode.
 */
@Composable
private fun ScaleSection(
    scaleState: ScaleBleManager.State,
    /**
     * The connected scale's BLE advertised name, or null when no scale is
     * connected — shown as the scale card's header so the card is titled with
     * the actual device.
     */
    scaleName: String?,
    /**
     * The connected scale's firmware version, pre-formatted `"M.m.p"`, or null
     * until the scale's serial response arrives — shown as a raw readout.
     */
    scaleFirmware: String?,
    /**
     * The connected scale's serial number, or null until the scale's serial
     * response arrives — shown as a raw readout.
     */
    scaleSerial: String?,
    scaleWeightG: Float?,
    scaleFlowGPerS: Float?,
    scaleTimerMs: Long?,
    /**
     * The scale's battery charge percentage, or null when it does not report
     * one — shown as a raw readout in the scale card.
     */
    scaleBatteryPercent: Int?,
    /**
     * The connected scale's settable beeper-volume bounds, or null when the
     * scale's volume is not settable — the volume control is gated on this.
     */
    volumeRange: RangeCapability?,
    /** The volume step the control shows; tracks the scale's live value. */
    scaleVolume: Int,
    /**
     * The connected scale's auto-standby-timeout bounds (minutes), or null
     * when the scale has no configurable auto-standby — the standby control is
     * gated on this.
     */
    standbyRange: RangeCapability?,
    /** The standby timeout (minutes) the control shows; tracks the live value. */
    scaleStandbyMinutes: Int,
    /** Whether the scale supports flow smoothing — gates the toggle. */
    flowSmoothingSupported: Boolean,
    /** The flow-smoothing toggle state currently shown; tracks the live value. */
    scaleFlowSmoothing: Boolean,
    /** Whether the scale supports anti-mistouch — gates the toggle. */
    antiMistouchSupported: Boolean,
    /** The anti-mistouch toggle state currently shown; tracks the live value. */
    scaleAntiMistouch: Boolean,
    /**
     * The scale's selectable display modes — empty when the scale has no
     * switchable modes; the mode buttons are gated on this being non-empty.
     */
    modes: List<ModeInfo>,
    /**
     * The active display-mode id currently selected on the scale, or null
     * until the first `ff12` settings response arrives; tracks the scale's
     * live value so the mode selector highlights the real current mode.
     */
    scaleActiveMode: Int?,
    /** Whether the scale supports an auto-stop-mode setting — gates the selector. */
    autoStopSupported: Boolean,
    /**
     * The auto-stop mode id currently selected on the scale (`0` = Flow-Stop,
     * `1` = Cup-Removal), or null until the first reading arrives; tracks the
     * scale's live value so the selector highlights the real current mode.
     */
    scaleAutoStop: Int?,
    onConnectScale: () -> Unit,
    onDisconnectScale: () -> Unit,
    onTareScale: () -> Unit,
    onSetScaleVolume: (Int) -> Unit,
    onSetScaleStandbyMinutes: (Int) -> Unit,
    onSetScaleFlowSmoothing: (Boolean) -> Unit,
    onSetScaleAntiMistouch: (Boolean) -> Unit,
    onSetScaleMode: (Int) -> Unit,
    onSetScaleAutoStop: (Int) -> Unit,
) {
    val scaleConnected = scaleState == ScaleBleManager.State.READY ||
        scaleState == ScaleBleManager.State.CONNECTING ||
        scaleState == ScaleBleManager.State.DISCOVERING ||
        scaleState == ScaleBleManager.State.SUBSCRIBING ||
        scaleState == ScaleBleManager.State.SCANNING
    val scaleReady = scaleState == ScaleBleManager.State.READY

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The card is titled with the connected scale's advertised name
            // once known, falling back to a plain "Scale" before connect.
            Text(scaleName ?: "Scale", style = MaterialTheme.typography.titleMedium)
            Field("Weight", scaleWeightG?.let { "%.1f g".format(it) } ?: "—")
            Field("Flow", scaleFlowGPerS?.let { "%.1f g/s".format(it) } ?: "—")
            Field("Timer", scaleTimerMs?.let { "%.1f s".format(it / 1000.0) } ?: "—")
            Field("Battery", scaleBatteryPercent?.let { "$it %" } ?: "—")
            // The scale's identity from its connect-time serial response.
            Field("Firmware", scaleFirmware ?: "—")
            Field("Serial", scaleSerial ?: "—")

            Button(
                onClick = onConnectScale,
                enabled = !scaleConnected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Connect scale") }

            OutlinedButton(
                onClick = onDisconnectScale,
                enabled = scaleConnected,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Disconnect scale") }

            OutlinedButton(
                onClick = onTareScale,
                enabled = scaleReady,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Tare") }

            // Capability-gated: a volume-capable scale (the Bookoo) shows the
            // beep-volume stepper over its own reported range; a weight-only
            // scale has no `volume` capability and shows nothing here.
            if (volumeRange != null) {
                ConfigStepper(
                    label = "Beep volume",
                    value = scaleVolume,
                    range = volumeRange.min.toInt()..volumeRange.max.toInt(),
                    enabled = scaleReady,
                    onSetValue = onSetScaleVolume,
                )
            }

            // Capability-gated: shown only for a scale that exposes a
            // configurable auto-standby timeout, over its reported range.
            if (standbyRange != null) {
                ConfigStepper(
                    label = "Standby (min)",
                    value = scaleStandbyMinutes,
                    range = standbyRange.min.toInt()..standbyRange.max.toInt(),
                    enabled = scaleReady,
                    onSetValue = onSetScaleStandbyMinutes,
                )
            }

            // Capability-gated: shown only for a scale that supports the
            // flow-smoothing toggle.
            if (flowSmoothingSupported) {
                ConfigToggle(
                    label = "Flow smoothing",
                    checked = scaleFlowSmoothing,
                    enabled = scaleReady,
                    onCheckedChange = onSetScaleFlowSmoothing,
                )
            }

            // Capability-gated: shown only for a scale that supports the
            // anti-mistouch toggle.
            if (antiMistouchSupported) {
                ConfigToggle(
                    label = "Anti-mistouch",
                    checked = scaleAntiMistouch,
                    enabled = scaleReady,
                    onCheckedChange = onSetScaleAntiMistouch,
                )
            }

            // Capability-gated: a row of mode buttons, one per scale-reported
            // mode; shown only when the scale lists any switchable modes.
            if (modes.isNotEmpty()) {
                ModeSelector(
                    modes = modes,
                    selectedMode = scaleActiveMode,
                    enabled = scaleReady,
                    onSetMode = onSetScaleMode,
                )
            }

            // Capability-gated: a two-option auto-stop-mode selector; shown
            // only for a scale that supports the auto-stop-mode setting.
            if (autoStopSupported) {
                AutoStopSelector(
                    enabled = scaleReady,
                    selectedMode = scaleAutoStop,
                    onSetMode = onSetScaleAutoStop,
                )
            }
        }
    }
}

/**
 * A `−` / `+` stepper for a whole-number scale setting (beep volume,
 * auto-standby timeout) over the scale-reported [range] (`min..max`).
 * Write-only for this slice: the scale's true current value is not read back
 * (that needs the `0x0f` settings decode), so [value] reflects only what the
 * user last set and starts at a sensible default.
 *
 * Each tap calls [onSetValue] with the new step, which the view model forwards
 * to the matching core setter; the `−` / `+` buttons disable at the range ends
 * so [onSetValue] is only ever called with an in-[range] value.
 */
@Composable
private fun ConfigStepper(
    label: String,
    value: Int,
    range: IntRange,
    enabled: Boolean,
    onSetValue: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = { onSetValue(value - 1) },
                enabled = enabled && value > range.first,
            ) { Text("−") }
            Text(
                "$value",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            OutlinedButton(
                onClick = { onSetValue(value + 1) },
                enabled = enabled && value < range.last,
            ) { Text("+") }
        }
    }
}

/**
 * A labelled on/off toggle for a boolean scale setting (flow smoothing,
 * anti-mistouch). Two-way: [checked] tracks the scale's live value (flow
 * smoothing from the weight stream, anti-mistouch from the `ff12` command
 * channel) and is updated optimistically on a user change. Each change calls
 * [onCheckedChange], which the view model forwards to the matching core setter.
 */
@Composable
private fun ConfigToggle(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

/**
 * A row of buttons, one per scale-reported display [modes] entry, each
 * labelled with the mode's name. Tapping a button calls [onSetMode] with the
 * mode's `id`, which the view model forwards to the core's `setScaleMode` —
 * which yields three ordered `WriteScale` commands.
 *
 * Two-way: the scale reports its active display mode on its `ff12` command
 * channel (the `03 0e` settings response), so [selectedMode] tracks that real
 * value and the matching button is highlighted (filled) while the others stay
 * outlined. A tap still issues the write; the live `ScaleConfig` stream then
 * confirms it.
 */
@Composable
private fun ModeSelector(
    modes: List<ModeInfo>,
    selectedMode: Int?,
    enabled: Boolean,
    onSetMode: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Mode", style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            modes.forEach { mode ->
                val id = mode.id.toInt()
                // The mode the scale reports as active is highlighted with a
                // filled button; the others stay outlined.
                if (id == selectedMode) {
                    Button(
                        onClick = { onSetMode(id) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(mode.name) }
                } else {
                    OutlinedButton(
                        onClick = { onSetMode(id) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(mode.name) }
                }
            }
        }
    }
}

/**
 * A two-option selector for the scale's auto-stop mode — Flow-Stop (`id = 0`)
 * or Cup-Removal (`id = 1`). Tapping a button calls [onSetMode] with that id,
 * which the view model forwards to the core's `setScaleAutoStop`.
 *
 * Two-way: the scale echoes its live auto-stop mode in every weight
 * notification, so [selectedMode] tracks that real value and the matching
 * button is highlighted (filled) while the others stay outlined. A tap still
 * issues the write; the live stream then confirms it.
 */
@Composable
private fun AutoStopSelector(
    enabled: Boolean,
    selectedMode: Int?,
    onSetMode: (Int) -> Unit,
) {
    // id -> label; the order matches the AutoStopMode discriminants.
    val options = listOf(0 to "Flow-Stop", 1 to "Cup-Removal")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Auto-stop", style = MaterialTheme.typography.labelSmall)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { (id, label) ->
                // The mode the scale reports as current is highlighted with a
                // filled button; the others stay outlined.
                if (id == selectedMode) {
                    Button(
                        onClick = { onSetMode(id) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                } else {
                    OutlinedButton(
                        onClick = { onSetMode(id) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun ReadoutCard(
    bleState: String,
    status: String,
    machineState: String?,
    shotPhase: String?,
    telemetry: String?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Field("BLE", bleState)
            Field("Status", status)
            Spacer(Modifier.height(4.dp))
            Field("Machine state", machineState ?: "—")
            Field("Shot phase", shotPhase ?: "—")
            Field("Telemetry", telemetry ?: "—")
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

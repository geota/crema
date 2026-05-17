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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager

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
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        viewModel = viewModel,
                        onConnect = { withBlePermission(viewModel::connect) },
                        onDisconnect = viewModel::disconnect,
                        onConnectScale = { withBlePermission(viewModel::connectScale) },
                        onDisconnectScale = viewModel::disconnectScale,
                        onTareScale = viewModel::tareScale,
                    )
                }
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

        ScaleSection(
            scaleState = ui.scaleState,
            scaleWeightG = ui.scaleWeightG,
            scaleFlowGPerS = ui.scaleFlowGPerS,
            scaleTimerMs = ui.scaleTimerMs,
            onConnectScale = onConnectScale,
            onDisconnectScale = onDisconnectScale,
            onTareScale = onTareScale,
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
 * The scale section: a Connect button, a live weight readout, and a Tare
 * button. The scale connection is independent of the DE1 — it works with or
 * without a machine connected.
 *
 * `scaleFlowGPerS` / `scaleTimerMs` are the scale's own native readings — only
 * populated for scales that report them (the Bookoo); shown as raw data
 * alongside the weight.
 */
@Composable
private fun ScaleSection(
    scaleState: ScaleBleManager.State,
    scaleWeightG: Float?,
    scaleFlowGPerS: Float?,
    scaleTimerMs: Long?,
    onConnectScale: () -> Unit,
    onDisconnectScale: () -> Unit,
    onTareScale: () -> Unit,
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
            Text("Scale", style = MaterialTheme.typography.titleMedium)
            Field("Weight", scaleWeightG?.let { "%.1f g".format(it) } ?: "—")
            Field("Flow", scaleFlowGPerS?.let { "%.1f g/s".format(it) } ?: "—")
            Field("Timer", scaleTimerMs?.let { "%.1f s".format(it / 1000.0) } ?: "—")

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

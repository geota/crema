package coffee.crema.spikeb

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
import coffee.crema.spikeb.ble.De1BleManager

/**
 * Spike B's single screen: a Connect button and a live readout of the events
 * the Rust core decodes from the DE1's BLE notifications.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: SpikeViewModel by viewModels()

    /** Android-12 BLE runtime permissions. */
    private val blePermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.connect()
        }
        // If denied, the UI status simply stays "Idle"; the user can retry.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen(
                        viewModel = viewModel,
                        onConnect = ::requestConnect,
                        onDisconnect = viewModel::disconnect,
                    )
                }
            }
        }
    }

    /** Request BLE permissions if needed, then scan + connect. */
    private fun requestConnect() {
        val missing = blePermissions.any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(blePermissions)
        } else {
            viewModel.connect()
        }
    }
}

@Composable
private fun SpikeScreen(
    viewModel: SpikeViewModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Crema · Spike B", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Phase-0 toolchain spike: cargo-ndk → UniFFI → Compose → live DE1 BLE.",
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

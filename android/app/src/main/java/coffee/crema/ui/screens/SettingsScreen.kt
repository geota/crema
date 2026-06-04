package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.SegOption

/*
 * Settings — M5 v1. The last rail destination. Sections: Display (theme),
 * Machine (connection), Shot behaviour (the same toggles as Quick Controls),
 * Advanced (debug readout), About.
 *
 * v1 wires the theme switch (persisted app pref applied by MainActivity).
 * Units, machine config (calibration / steam / water), Visualizer sharing, and
 * maintenance are later increments.
 */
@Composable
fun SettingsScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "settings",
            onNav = onNav,
            machineConnected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Settings",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            SectionCard("Display") {
                Text("Theme", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                CremaSegmentedButton(
                    options = listOf(
                        SegOption("system", "System"),
                        SegOption("light", "Light"),
                        SegOption("dark", "Dark"),
                    ),
                    value = ui.themeMode,
                    onChange = vm::setThemeMode,
                )
            }

            SectionCard("Machine") {
                Field("DE1", if (connected) (ui.machineState ?: "Connected") else "Disconnected")
                CremaButton(
                    onClick = { onConnect("machine") },
                    variant = CremaButtonVariant.Tonal,
                    label = if (connected) "Disconnect DE1" else "Connect DE1",
                )
                Field("Scale", if (scaleConnected) (ui.scaleName ?: "Connected") else "Disconnected")
                if (scaleConnected) Field("Firmware", ui.scaleFirmware ?: "—")
                CremaButton(
                    onClick = { onConnect("scale") },
                    variant = CremaButtonVariant.Tonal,
                    label = if (scaleConnected) "Disconnect scale" else "Connect scale",
                )
            }

            SectionCard("Shot behaviour") {
                ToggleRow("Auto-tare", ui.autoTare, vm::setAutoTare)
                ToggleRow("Stop on weight", ui.stopOnWeight, vm::setStopOnWeight)
                ToggleRow("Steam eco", ui.steamEco, vm::setSteamEco)
            }

            SectionCard("Advanced") {
                CremaButton(
                    onClick = { onNav("debug") },
                    variant = CremaButtonVariant.Tonal,
                    icon = "bug",
                    label = "Open debug readout",
                )
            }

            SectionCard("About") {
                Text("Crema", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    "Decent Espresso DE1 client",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow(title)
        CremaCard(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { content() }
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        CremaSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SegOption

/*
 * Settings — a port of tablet/settings-screen.jsx: a two-pane shell (248dp
 * section-nav rail + a scrolling content pane, max 880) over 8 sections. Real,
 * wired controls live where the VM supports them today (Machine connect, Brew
 * behaviour toggles, Theme, Advanced debug); sections that need core/VM work
 * not yet built (Water & maintenance, Sharing, Calibration) render an honest
 * "coming soon" note rather than fake-functional rows.
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
                        SetHead("Machine", "Connect and manage your DE1 and scale.")
                        SetGroup("Connection") {
                            SetRow("DE1", if (connected) (ui.machineState ?: "Connected") else "Disconnected") {
                                CremaButton(
                                    onClick = { onConnect("machine") },
                                    variant = CremaButtonVariant.Tonal,
                                    label = if (connected) "Disconnect" else "Connect",
                                )
                            }
                            SetRow("Scale", if (scaleConnected) (ui.scaleName ?: "Connected") else "Disconnected") {
                                CremaButton(
                                    onClick = { onConnect("scale") },
                                    variant = CremaButtonVariant.Tonal,
                                    label = if (scaleConnected) "Disconnect" else "Connect",
                                )
                            }
                            if (scaleConnected) SetRow("Scale firmware", ui.scaleFirmware ?: "—") {}
                        }
                    }
                    "brew" -> {
                        SetHead("Brew defaults", "Defaults applied to every shot. Per-profile values override these.")
                        SetGroup("Shot behaviour") {
                            SetRow("Auto-tare on shot start", "Zero the scale automatically when extraction begins.") {
                                CremaSwitch(ui.autoTare, vm::setAutoTare)
                            }
                            SetRow("Stop on weight", "End the shot once the target yield is reached.") {
                                CremaSwitch(ui.stopOnWeight, vm::setStopOnWeight)
                            }
                            SetRow("Steam eco", "Idle the steam boiler cooler between sessions to save power.") {
                                CremaSwitch(ui.steamEco, vm::setSteamEco)
                            }
                        }
                    }
                    "display" -> {
                        SetHead("Display & units", "How Crema looks on this device.")
                        SetGroup("Appearance") {
                            SetRow("Theme", "Crema defaults to dark — the machine app is dark-skinned.") {
                                CremaSegmentedButton(
                                    options = listOf(SegOption("system", "System"), SegOption("light", "Light"), SegOption("dark", "Dark")),
                                    value = ui.themeMode,
                                    onChange = vm::setThemeMode,
                                )
                            }
                        }
                    }
                    "advanced" -> {
                        SetHead("Advanced", "Diagnostics and developer tools.")
                        SetGroup("Diagnostics") {
                            SetRow("Debug readout", "The live Phase-0 telemetry + event log.") {
                                CremaButton(onClick = { onNav("debug") }, variant = CremaButtonVariant.Tonal, icon = "bug", label = "Open")
                            }
                        }
                    }
                    "about" -> {
                        SetHead("About", "Crema — a Decent Espresso DE1 client.")
                        SetGroup("App") {
                            SetRow("Version", "0.1") {}
                            SetRow("Machine", "Decent Espresso DE1") {}
                        }
                    }
                    "water" -> {
                        SetHead("Water & maintenance", "Descale, backflush, filter and water-chemistry tracking.")
                        ComingSoon("Maintenance tracking is coming in a later update.")
                    }
                    "sharing" -> {
                        SetHead("Sharing", "Sync shots and profiles to Visualizer.")
                        ComingSoon("Visualizer sync is coming in a later update.")
                    }
                    "calibration" -> {
                        SetHead("Calibration", "Pressure, flow and temperature sensor offsets.")
                        ComingSoon("Sensor calibration is coming in a later update.")
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

// ── Section header (serif title + sub) ───────────────────────────────────────
@Composable
private fun SetHead(title: String, sub: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.widthIn(max = 560.dp))
    }
}

// ── A titled group card (large radius) ───────────────────────────────────────
@Composable
private fun SetGroup(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(title, Modifier.padding(start = 4.dp))
        CremaCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content() }
        }
    }
}

// ── Settings row — title + optional value/subtitle (left) + trailing control ──
@Composable
private fun SetRow(title: String, sub: String? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (sub != null) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing()
    }
}

// ── Honest placeholder for not-yet-built sections ────────────────────────────
@Composable
private fun ComingSoon(text: String) {
    CremaCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PhIcon("info", sizeDp = 20, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

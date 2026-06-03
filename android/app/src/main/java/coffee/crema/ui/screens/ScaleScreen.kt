package coffee.crema.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coffee.crema.ui.components.*
import coffee.crema.ui.theme.CremaTheme
import kotlinx.coroutines.delay

/*
 * ScaleScreen — the calm, focused weighing view (/scale).
 *
 * The most important pattern in the app: this screen is CAPABILITY-DRIVEN.
 *   • The Scale-settings panel is HIDDEN until a scale connects, and then
 *     renders only the rows the connected scale actually supports (caps.*).
 *   • Pairing / disconnect are NOT in the header — Connect happens from the
 *     nav-rail Scale pip or the on-screen empty state; Disconnect is inlined as
 *     the last action inside the settings panel.
 *   • Brew-behaviour (auto-tare, stop-on-weight) is NOT a scale capability —
 *     it lives in Settings → Brew defaults. This panel is scale-state only.
 *
 * Layout (tablet, 1280×800): NavigationRail | content Column {
 *     header,
 *     Row [ left Column (readout hero row + dose helper + recent activity)
 *           : weight 1 ,
 *           right Column (scale-settings panel) : fixed ~372dp ]
 * }
 * Recent activity sits UNDER the readout (left column slack) so the right rail
 * is entirely the settings panel — room for ~5+ capability rows without scroll.
 */

// ── Capability model — mirrors core ScaleCapabilities (de1-scale/scale.rs). ─
// Null / false fields collapse the matching row. A Bookoo Themis exposes the
// full set; a plain Acaia would null most of these → the panel shows its
// "no adjustable settings" empty state instead of a blank card.
data class ScaleCapabilities(
    val name: String,
    val meta: String,                 // "Connected · 12 ms · 86% · FW 1.3.3"
    val flowSmoothing: Boolean = false,
    val antiMistouch: Boolean = false,
    val autoStop: Boolean = false,
    val standby: IntRange? = null,     // auto-sleep timeout (minutes)
    val volume: IntRange? = null,      // beeper volume steps
    val canSetUnit: Boolean = false,   // on-scale display unit (g / oz)
    val canBeep: Boolean = false,      // locate / test-tone action
)

private val SampleScaleCaps = ScaleCapabilities(
    name = "Sample scale",
    meta = "Connected · 12 ms · 86% · FW 1.3.3",
    flowSmoothing = true,
    antiMistouch = true,
    autoStop = true,
    standby = 0..30,
    volume = 0..3,
    canSetUnit = true,
    canBeep = true,
)

@Composable
fun ScaleScreen(onNav: (String) -> Unit) {
    val sp = CremaTheme.spacing
    var connected by remember { mutableStateOf(true) }
    val caps = if (connected) SampleScaleCaps else null

    // Live weight sim — only ticks while connected.
    var weight by remember { mutableStateOf(17.4) }
    LaunchedEffect(connected) {
        if (!connected) return@LaunchedEffect
        while (true) {
            delay(600)
            val noise = (Math.random() - 0.5) * 0.04
            val nudge = if (Math.random() < 0.3) 0.1 else 0.0
            weight = (weight + noise + nudge).coerceAtLeast(0.0)
        }
    }
    val target = 18.0

    Row(Modifier.fillMaxSize()) {
        CremaNavigationRail(
            active = "scale",
            onNav = onNav,
            scaleConnected = connected,
            onConnect = { which -> if (which == "scale") connected = !connected },
        )
        Column(
            Modifier.fillMaxSize().padding(start = sp.edge, top = sp.s4, end = sp.edge, bottom = sp.s5),
            verticalArrangement = Arrangement.spacedBy(sp.s5),
        ) {
            ScaleHeader(connected, caps)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(sp.s5)) {
                // Left column — readout, dose helper, recent activity
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(sp.s4)) {
                    ScaleHeroRow(connected, weight, target, onTare = { weight = 0.0 }, onConnect = { connected = true })
                    DoseHelper(connected, weight, target)
                    RecentActivity(connected, Modifier.weight(1f))
                }
                // Right column — capability-driven settings panel
                ScaleSettingsPanel(
                    caps = caps,
                    onDisconnect = { connected = false },
                    modifier = Modifier.width(372.dp).fillMaxHeight(),
                )
            }
        }
    }
}

@Composable
private fun ScaleHeader(connected: Boolean, caps: ScaleCapabilities?) {
    Column {
        Eyebrow(if (connected) "Connected scale" else "Scale")
        Text(
            if (connected) caps!!.name else "No scale connected",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (connected) caps!!.meta else "Pair a scale to weigh your dose and stop shots by weight.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScaleHeroRow(
    connected: Boolean, weight: Double, target: Double,
    onTare: () -> Unit, onConnect: () -> Unit,
) {
    val sp = CremaTheme.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(sp.s4)) {
        // Readout card — fills remaining width
        CremaCard(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().padding(sp.s5), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format("%.1f", if (connected) weight else 0.0),
                        style = CremaTheme.readout.readoutXl.copy(fontSize = androidx.compose.ui.unit.TextUnit(132f, androidx.compose.ui.unit.TextUnitType.Sp)),
                        color = if (connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                    Text("  grams", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        // Tare column (connected) OR Connect column (disconnected) — fixed width
        Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(sp.s3)) {
            if (connected) {
                // Big copper TARE — note the radius is LARGE (16dp) here; the
                // readout card is MEDIUM (12dp): the step-down rule in reverse
                // (the hero control may equal the card; secondary btns step down).
                Surface(
                    onClick = onTare,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = sp.s5, vertical = 22.dp), verticalArrangement = Arrangement.Center) {
                        Eyebrow("Tare", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                        Text("0.0 g", style = CremaTheme.readout.readoutLg, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                CremaButton(onClick = {}, variant = CremaButtonVariant.Tonal, icon = "arrow-counter-clockwise", label = "Reset peak")
                CremaButton(onClick = {}, variant = CremaButtonVariant.Tonal, icon = "timer", label = "Start timer")
            } else {
                Spacer(Modifier.weight(0.4f))
                CremaButton(onClick = onConnect, variant = CremaButtonVariant.Filled, icon = "bluetooth", label = "Connect scale")
                Text(
                    "Acaia, Bookoo, Decent, Felicita and more pair automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(0.6f))
            }
        }
    }
}

@Composable
private fun DoseHelper(connected: Boolean, weight: Double, target: Double) {
    val sp = CremaTheme.spacing
    CremaCard(Modifier.fillMaxWidth().alpha(if (connected) 1f else 0.6f)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = sp.s4), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (connected) "Dose helper · weighing for Rao 80% · target ${"%.1f".format(target)} g"
                    else "Dose helper · target ${"%.1f".format(target)} g",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (connected) CremaButton(onClick = {}, variant = CremaButtonVariant.Text, icon = "shuffle", label = "Switch profile")
            }
            // progress track
            val pct = if (connected) (weight / target).coerceIn(0.0, 1.0).toFloat() else 0f
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (connected) "Add 0.5 g" else "Connect a scale to weigh your dose",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Eyebrow("Target ${"%.1f".format(target)} g")
            }
        }
    }
}

@Composable
private fun RecentActivity(connected: Boolean, modifier: Modifier = Modifier) {
    val sp = CremaTheme.spacing
    val rows = listOf(
        Triple("14:38", "Tare · before dose", "0.0 g"),
        Triple("14:38", "Weigh · dose for Rao 80%", "17.9 g"),
        Triple("14:36", "Shot end · Rao 80%", "36.2 g"),
        Triple("14:35", "Tare · on shot start", "0.0 g"),
        Triple("13:58", "Connect · BLE handshake", "12 ms"),
    )
    CremaCard(modifier) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = sp.s4)) {
            Eyebrow("Recent activity")
            Spacer(Modifier.height(8.dp))
            if (!connected) {
                Text("No scale activity yet — connect a scale to begin.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    rows.forEach { (time, label, value) ->
                        // One compact line: {time}  {message} ............ {value}
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(time, style = CremaTheme.readout.readoutSm.copy(fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                            Text(value, style = CremaTheme.readout.readoutSm.copy(fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp)), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScaleSettingsPanel(caps: ScaleCapabilities?, onDisconnect: () -> Unit, modifier: Modifier = Modifier) {
    val sp = CremaTheme.spacing
    CremaCard(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = sp.s4)) {
            Eyebrow("Scale settings")
            if (caps == null) {
                // Empty state — explains WHY the panel is blank (never a bug).
                Column(
                    Modifier.fillMaxSize().padding(vertical = 28.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(60.dp)) {
                        Box(contentAlignment = Alignment.Center) { PhIcon("gear-six", sizeDp = 28, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("No settings yet", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Connect a scale to adjust its settings — they'll appear here automatically, based on what the scale supports.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    var flow by remember { mutableStateOf(true) }
                    var anti by remember { mutableStateOf(false) }
                    var stop by remember { mutableStateOf(true) }
                    var sleep by remember { mutableStateOf(true) }
                    var vol by remember { mutableStateOf(2) }
                    var unit by remember { mutableStateOf("g") }

                    if (caps.flowSmoothing) ToggleRow("Flow smoothing", "Smooths the live mass-flow readout. Calmer, slower to settle.", flow) { flow = it }
                    if (caps.antiMistouch) ToggleRow("Anti-mistouch", "Ignore accidental taps on the scale's buttons.", anti) { anti = it }
                    if (caps.autoStop) ToggleRow("Auto-stop on flow drop", "Stop the scale's built-in timer when outflow drops to zero.", stop) { stop = it }
                    if (caps.standby != null) ToggleRow("Auto-sleep", "Sleep after 5 min of no activity.", sleep) { sleep = it }
                    if (caps.volume != null) SegRow("Beeper volume", "Button & target tone loudness.") {
                        CremaSegmentedButton(
                            options = (caps.volume).map { SegOption(it.toString(), it.toString()) },
                            value = vol.toString(), onChange = { vol = it.toInt() },
                        )
                    }
                    if (caps.canSetUnit) SegRow("Display unit", "What the on-scale display reads in.") {
                        CremaSegmentedButton(
                            options = listOf(SegOption("g", "g"), SegOption("oz", "oz")),
                            value = unit, onChange = { unit = it },
                        )
                    }
                }
                // Action footer — pinned to the bottom of the panel.
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = sp.s3), verticalAlignment = Alignment.CenterVertically) {
                    if (caps.canBeep) CremaButton(onClick = {}, variant = CremaButtonVariant.Tonal, icon = "speaker-high", label = "Beep")
                    Spacer(Modifier.weight(1f))
                    CremaButton(onClick = onDisconnect, variant = CremaButtonVariant.Text, icon = "link-break", danger = true, label = "Disconnect")
                }
            }
        }
    }
}

// A capability-gated toggle row: title + sub on the left, switch on the right.
@Composable
private fun ToggleRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CremaSwitch(checked, onChange)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// A row whose control is a compact segmented button on the right.
@Composable
private fun SegRow(title: String, sub: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

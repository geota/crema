package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.*
import coffee.crema.ui.theme.CremaTheme

/*
 * ScaleScreen — the calm, focused weighing view (/scale), wired to LIVE state.
 *
 * The most important pattern in the app: this screen is CAPABILITY-DRIVEN.
 *   • The Scale-settings panel is HIDDEN until a scale connects, and then
 *     renders only the rows the connected scale actually supports — gated on the
 *     core's `ScaleCapabilities`, never on a concrete scale model.
 *   • Pairing / disconnect are NOT in the header — Connect happens from the
 *     nav-rail Scale pip or the on-screen empty state; Disconnect is inlined as
 *     the last action inside the settings panel.
 *   • Brew-behaviour (auto-tare, stop-on-weight) is NOT a scale capability —
 *     it lives in Settings → Brew defaults. This panel is scale-state only.
 *
 * Live state + actions come from [MainViewModel] (the same funnel the Phase-0
 * readout uses); when the CremaCoreClient / AppState seam lands this swaps to
 * AppState with no layout change.
 */

// ── The screen's view-model of a scale's settings, derived from the core's
// `ScaleCapabilities`. Null / false fields collapse the matching row; a
// weight-only scale maps to an empty settings panel, never a blank card.
data class ScaleCapabilities(
    val name: String,
    val meta: String,
    val flowSmoothing: Boolean = false,
    val antiMistouch: Boolean = false,
    val autoStop: Boolean = false,
    val standby: IntRange? = null, // auto-sleep timeout (minutes)
    val volume: IntRange? = null,  // beeper volume steps
    val canSetUnit: Boolean = false,
    val canBeep: Boolean = false,
)

/** Adapt the core's `ScaleCapabilities` into the screen's row-gating model. */
private fun mapCaps(core: coffee.crema.core.ScaleCapabilities, name: String, meta: String) =
    ScaleCapabilities(
        name = name,
        meta = meta,
        flowSmoothing = core.flow_smoothing,
        antiMistouch = core.anti_mistouch,
        autoStop = core.auto_stop,
        standby = core.standby?.let { it.min.toInt()..it.max.toInt() },
        volume = core.volume?.let { it.min.toInt()..it.max.toInt() },
        // On-scale display-unit (g / oz) control is a later pass — the core
        // models it as set-grams / toggle, not a clean g↔oz segmented yet.
        canSetUnit = false,
        canBeep = core.can_beep,
    )

/** The header meta line, built from the live scale identity. */
private fun scaleMeta(ui: MainUiState): String =
    buildList {
        add("Connected")
        ui.scaleBatteryPercent?.let { add("$it%") }
        ui.scaleFirmware?.let { add("FW $it") }
    }.joinToString(" · ")

@Composable
fun ScaleScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val sp = CremaTheme.spacing
    val ui by vm.ui.collectAsStateWithLifecycle()

    val connected = ui.scaleState == ScaleBleManager.State.READY
    val machineConnected = ui.bleState == De1BleManager.State.READY
    val coreCaps = ui.scaleCapabilities
    val caps: ScaleCapabilities? =
        if (connected && coreCaps != null) {
            mapCaps(coreCaps, name = ui.scaleName ?: "Scale", meta = scaleMeta(ui))
        } else {
            null
        }
    val weight = (ui.scaleWeightG ?: 0f).toDouble()
    // Dose target = the active profile's dose (what you're weighing to), not a
    // hardcoded 18 g — the dose helper now tracks the loaded recipe.
    val target = (ui.profiles.firstOrNull { it.id == ui.activeProfileId }?.dose ?: 18f).toDouble()

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "scale",
            onNav = onNav,
            machineConnected = machineConnected,
            scaleConnected = connected,
            onConnect = onConnect,
        )
        Column(
            Modifier.fillMaxSize().padding(start = sp.edge, top = sp.s4, end = sp.edge, bottom = sp.s5),
            verticalArrangement = Arrangement.spacedBy(sp.s5),
        ) {
            ScaleHeader(connected, caps)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(sp.s5)) {
                // Left column — readout, dose helper, recent activity
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(sp.s4)) {
                    ScaleHeroRow(
                        connected = connected,
                        weight = weight,
                        onTare = vm::tareScale,
                        onConnect = { onConnect("scale") },
                        onResetPeak = vm::resetScalePeaks,
                        onStartTimer = vm::startScaleTimer,
                    )
                    DoseHelper(connected, weight, target)
                    RecentActivity(connected, Modifier.weight(1f))
                }
                // Right column — capability-driven settings panel
                ScaleSettingsPanel(
                    caps = caps,
                    flowSmoothing = ui.scaleFlowSmoothing,
                    antiMistouch = ui.scaleAntiMistouch,
                    autoStopMode = ui.scaleAutoStop,
                    volume = ui.scaleVolume,
                    standbyMinutes = ui.scaleStandbyMinutes,
                    onFlowSmoothing = vm::setScaleFlowSmoothing,
                    onAntiMistouch = vm::setScaleAntiMistouch,
                    onAutoStop = vm::setScaleAutoStop,
                    onVolume = vm::setScaleVolume,
                    onStandby = vm::setScaleStandbyMinutes,
                    onBeep = vm::beepScale,
                    onDisconnect = vm::disconnectScale,
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
            if (connected && caps != null) caps.name else "No scale connected",
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            if (connected && caps != null) caps.meta else "Pair a scale to weigh your dose and stop shots by weight.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScaleHeroRow(
    connected: Boolean,
    weight: Double,
    onTare: () -> Unit,
    onConnect: () -> Unit,
    onResetPeak: () -> Unit,
    onStartTimer: () -> Unit,
) {
    val sp = CremaTheme.spacing
    Row(horizontalArrangement = Arrangement.spacedBy(sp.s4)) {
        // Readout card — fills remaining width
        CremaCard(Modifier.weight(1f)) {
            Box(Modifier.fillMaxWidth().padding(sp.s5), contentAlignment = Alignment.Center) {
                // Number + unit are inline baseline siblings (never absolute) so the
                // unit can't collide with a wide value — the bug the PWA avoids too.
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format("%.1f", if (connected) weight else 0.0),
                        style = CremaTheme.readout.readoutHero,
                        color = if (connected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        "grams",
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 28.sp, lineHeight = 32.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 24.dp),
                    )
                }
            }
        }
        // Tare column (connected) OR Connect column (disconnected) — fixed width
        Column(Modifier.width(280.dp), verticalArrangement = Arrangement.spacedBy(sp.s3)) {
            if (connected) {
                Surface(
                    onClick = onTare,
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = sp.s5, vertical = 22.dp), verticalArrangement = Arrangement.Center) {
                        Eyebrow("Tare", color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text("0.0", style = CremaTheme.readout.readoutLg, color = MaterialTheme.colorScheme.onPrimary)
                            Text(
                                " g",
                                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp),
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                            )
                        }
                    }
                }
                ScalePillButton(icon = "arrow-counter-clockwise", label = "Reset peak", onClick = onResetPeak)
                ScalePillButton(icon = "timer", label = "Start timer", onClick = onStartTimer)
            } else {
                // FIXED spacers — a weighted Spacer makes this Column expand to the
                // incoming max height, which inflates the whole hero Row and shoves
                // the Dose helper + Recent activity cards off screen.
                Spacer(Modifier.height(40.dp))
                CremaButton(onClick = onConnect, modifier = Modifier.height(52.dp), variant = CremaButtonVariant.Filled, icon = "bluetooth", label = "Connect scale")
                Text(
                    "Acaia, Bookoo, Decent, Felicita and more pair automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
                    "Dose helper · target ${"%.1f".format(target)} g",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            val pct = if (connected) (weight / target).coerceIn(0.0, 1.0).toFloat() else 0f
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (connected) "Weighing…" else "Connect a scale to weigh your dose",
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
    CremaCard(modifier) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = sp.s4)) {
            Eyebrow("Recent activity")
            Spacer(Modifier.height(8.dp))
            // A real per-scale activity log is a later feature; for now this is
            // an honest empty state rather than fabricated rows.
            Text(
                if (connected) "No scale activity yet this session."
                else "No scale activity yet — connect a scale to begin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScaleSettingsPanel(
    caps: ScaleCapabilities?,
    flowSmoothing: Boolean,
    antiMistouch: Boolean,
    autoStopMode: Int?,
    volume: Int,
    standbyMinutes: Int,
    onFlowSmoothing: (Boolean) -> Unit,
    onAntiMistouch: (Boolean) -> Unit,
    onAutoStop: (Int) -> Unit,
    onVolume: (Int) -> Unit,
    onStandby: (Int) -> Unit,
    onBeep: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                    if (caps.flowSmoothing) {
                        ToggleRow("Flow smoothing", "Smooths the live mass-flow readout. Calmer, slower to settle.", flowSmoothing, onFlowSmoothing)
                    }
                    if (caps.antiMistouch) {
                        ToggleRow("Anti-mistouch", "Ignore accidental taps on the scale's buttons.", antiMistouch, onAntiMistouch)
                    }
                    if (caps.autoStop) {
                        SegRow("Auto-stop", "How the scale's built-in timer ends.") {
                            CremaSegmentedButton(
                                options = listOf(SegOption("0", "Flow-stop"), SegOption("1", "Cup-removal")),
                                value = (autoStopMode ?: 0).toString(),
                                onChange = { onAutoStop(it.toInt()) },
                            )
                        }
                    }
                    val standby = caps.standby
                    if (standby != null) {
                        SegRow("Auto-sleep", "Minutes of inactivity before the scale sleeps.") {
                            CremaStepper(
                                value = standbyMinutes.toDouble(),
                                unit = "min",
                                onChange = { onStandby(it.toInt()) },
                                step = 1.0,
                                min = standby.first.toDouble(),
                                max = standby.last.toDouble(),
                                fmt = { String.format("%.0f", it) },
                            )
                        }
                    }
                    val vol = caps.volume
                    if (vol != null) {
                        SegRow("Beeper volume", "Button & target tone loudness.") {
                            CremaSegmentedButton(
                                options = vol.map { SegOption(it.toString(), it.toString()) },
                                value = volume.toString(),
                                onChange = { onVolume(it.toInt()) },
                            )
                        }
                    }
                }
                // Action footer — pinned to the bottom of the panel.
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = sp.s3), verticalAlignment = Alignment.CenterVertically) {
                    if (caps.canBeep) CremaButton(onClick = onBeep, variant = CremaButtonVariant.Tonal, icon = "speaker-high", label = "Beep")
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

// A row whose control sits on the right (segmented button or stepper).
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

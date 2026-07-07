package coffee.crema.ui.phone

import coffee.crema.ui.fmt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.ble.ScaleBleManager
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.activeProfile
import coffee.crema.ui.effectiveBrew
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.*
import coffee.crema.ui.screens.ScaleCapabilityRows
import coffee.crema.ui.screens.ScaleSettingsFooter
import coffee.crema.ui.screens.scaleMeta
import coffee.crema.ui.theme.CremaTheme
import kotlinx.coroutines.delay

/*
 * PhoneScaleScreen — the handset Scale screen (port of prototype/phone/phone-scale.jsx,
 * via the phone handoff's ScaleScreen.kt exemplar), wired to LIVE state.
 *
 * Same intent as the tablet ScaleScreen:
 *   • CAPABILITY-DRIVEN — the settings card renders only the rows the connected
 *     scale supports (core `ScaleCapabilities`); a weight-only scale shows fewer.
 *     Disconnected → an explanatory empty state, never a blank card.
 *   • No pairing chrome in the top bar. Connect is the disconnected empty-state
 *     button; Disconnect is the LAST action inside the settings card.
 *   • Brew behaviour (auto-tare / stop-on-weight) is NOT here — Settings → Brew
 *     defaults, mirrored in Quick Controls.
 *
 * Layout (whole body scrolls): header → weight hero + live pill → Tare/Peak/Timer
 * → dose helper → capability settings → recent activity.
 */

@Composable
fun PhoneScaleScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()

    val connected = ui.scaleState == ScaleBleManager.State.READY
    val caps = if (connected) ui.scaleCapabilities else null
    val weight = (ui.scaleWeightG ?: 0f).toDouble()
    val activeProfile = ui.activeProfile()
    // Dose target = the QC override when set, else the active profile's dose.
    val target = effectiveBrew(ui.brewParams, activeProfile).dose

    // Tare flashes the readout copper for ~500ms.
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(pulse) { if (pulse) { delay(500); pulse = false } }

    Scaffold(
        topBar = {
            CremaPhoneTopBar(
                title = "Scale",
                actions = buildList {
                    if (!connected) add(BarAction("arrows-clockwise") { onConnect("scale") })
                    add(BarAction("gear-six") { onNav("settings") })
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CremaEdge),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            // ── Header (readout-focused, no pairing buttons) ────────────────
            Column {
                Text(
                    if (connected) (ui.scaleName ?: "Scale") else "No scale connected",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (connected) scaleMeta(ui)
                    else "Pair a scale to weigh your dose and stop shots by weight.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Weight hero ─────────────────────────────────────────────────
            val heroColor =
                if (pulse) MaterialTheme.colorScheme.primary
                else if (connected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    fmt("%.1f",  if (connected) weight else 0.0),
                    style = CremaTheme.readout.readoutXl,
                    color = heroColor,
                )
                Text("grams", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                LivePill(connected)
            }

            if (connected) {
                ConnectedBody(
                    vm = vm,
                    ui = ui,
                    weight = weight,
                    target = target,
                    profileName = activeProfile?.name,
                    onTare = { pulse = true; vm.tareScale() },
                    onSwitchProfile = { onNav("profiles") },
                )
            } else {
                DisconnectedBody(onConnect = { onConnect("scale") })
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ── Live / off status pill ──────────────────────────────────────────────────
@Composable
private fun LivePill(connected: Boolean) {
    val tel = CremaTheme.telemetry
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) tel.success else MaterialTheme.colorScheme.outline),
        )
        Text(
            if (connected) "Live · 0.1 g resolution" else "No scale connected",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── Connected: tare row + dose helper + capability settings + activity ──────
@Composable
private fun ConnectedBody(
    vm: MainViewModel,
    ui: MainUiState,
    weight: Double,
    target: Double,
    profileName: String?,
    onTare: () -> Unit,
    onSwitchProfile: () -> Unit,
) {
    val caps = ui.scaleCapabilities ?: return

    // Tare (hero, copper, pill) + two secondary actions.
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = onTare,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.weight(1f).height(52.dp),
        ) { PhIcon("circle-half", sizeDp = 20); Spacer(Modifier.width(8.dp)); Text("Tare") }
        FilledTonalButton(
            onClick = vm::resetScalePeaks,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.height(52.dp),
        ) { PhIcon("arrow-counter-clockwise", sizeDp = 18); Spacer(Modifier.width(6.dp)); Text("Peak") }
        FilledTonalButton(
            onClick = vm::startScaleTimer,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.height(52.dp),
        ) { PhIcon("timer", sizeDp = 18); Spacer(Modifier.width(6.dp)); Text("Timer") }
    }

    // Dose helper — progress toward the loaded profile's dose target.
    val pct = (weight / target).coerceIn(0.0, 1.0).toFloat()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (profileName != null) "Weighing for $profileName" else "Dose helper",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSwitchProfile) {
                    PhIcon("shuffle", sizeDp = 16); Spacer(Modifier.width(4.dp)); Text("Switch")
                }
            }
            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                strokeCap = StrokeCap.Round,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Nudges the next shot's dose target by +0.5 g (a transient
                // Quick-Controls override, not a profile edit) — same seam QC uses.
                TextButton(onClick = {
                    val active = ui.activeProfile()
                    val base = effectiveBrew(ui.brewParams, active)
                    val dose = base.dose + 0.5
                    // Yield's last-ditch default tracks the *bumped* dose (dose * 2),
                    // whereas effectiveBrew's tracks the base dose — so compute it here.
                    val yieldOut = ui.brewParams?.yieldOut ?: active?.yieldOut?.toDouble() ?: (dose * 2)
                    val temp = base.brewTemp
                    vm.quickAdjustBrew(dose, yieldOut, temp)
                }) {
                    PhIcon("plus", sizeDp = 16); Spacer(Modifier.width(4.dp)); Text("Add 0.5 g")
                }
                Text(
                    fmt("Target %.1f g",  target),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Scale settings — capability-driven. Only render rows the scale supports.
    // Body shared with the tablet (issue 33); dense rows via LocalSettingsRowDense.
    Eyebrow("Scale settings")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CompositionLocalProvider(LocalSettingsRowDense provides true) {
            Column {
                ScaleCapabilityRows(vm, ui, caps)
                ScaleSettingsFooter(vm, caps.can_beep, Modifier.padding(16.dp))
            }
        }
    }

    // Recent activity — an honest empty state until a per-scale activity log
    // exists (tablet parity; no fabricated rows).
    Eyebrow("Recent activity")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "No scale activity yet this session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Disconnected: connect CTA + settings empty state ────────────────────────
@Composable
private fun DisconnectedBody(onConnect: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Button(
            onClick = onConnect,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { PhIcon("bluetooth", sizeDp = 20); Spacer(Modifier.width(8.dp)); Text("Connect scale") }
        Spacer(Modifier.height(8.dp))
        Text(
            "Acaia, Bookoo, Decent, Felicita and more pair automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }

    Eyebrow("Scale settings")
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        CremaEmptyState(
            icon = "gear-six",
            message = "No settings yet",
            description = "Connect a scale to adjust its settings — they'll appear here automatically, based on what the scale supports.",
            modifier = Modifier.fillMaxWidth().padding(28.dp),
        )
    }
}

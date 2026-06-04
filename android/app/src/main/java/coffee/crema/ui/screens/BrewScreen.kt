package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.beans.daysOffRoast
import coffee.crema.beans.roastBand
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.profiles.CremaProfile
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.theme.CremaTheme
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * Brew — the hero dashboard (M1, read-only telemetry + machine control foot).
 *
 * A faithful port of the tablet design (prototype/tablet/brew-screen.jsx + the
 * web BrewDashboard): rail | column { header twin-block, body grid[248dp | 1fr],
 * foot(split) }. Every value reads from the live `MainUiState` the core decodes;
 * the four channel cards, timer, ratio, phase, and limits are driven by real
 * telemetry, and the foot's Coffee / Stop / mode chips drive machine control.
 *
 * M1 scope (what is deliberately NOT here yet):
 *  • The live chart is a static placeholder (M2 — pairs with per-frame phase fill).
 *  • Coffee does a DIRECT espresso request, not the gated profile-upload start
 *    sequence (lazy sync → await ProfileUploadCompleted → pre-shot flush → 500ms
 *    guard). That sequence is M2; for now Coffee brews whatever profile the DE1
 *    already has loaded, and the header selection is display-only.
 *  • The bean block is an honest empty state — the bean library is M3.
 *  • Quick Controls opens nothing yet (the bottom sheet is M2).
 */
@Composable
fun BrewScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    val active = ui.profiles.firstOrNull { it.id == ui.activeProfileId }
    val activeBean = ui.beans.firstOrNull { it.id == ui.activeBeanId }
    val running = ui.shotInProgress
    val espressoActive = ui.machineStateName == "Espresso"
    var quickOpen by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "brew",
            onNav = onNav,
            machineConnected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            BrewHeader(
                active = active,
                profiles = ui.profiles,
                onSelectProfile = vm::setActiveProfile,
                uploading = ui.profileUploading,
                uploadProgress = ui.profileUploadProgress,
                activeBean = activeBean,
                beans = ui.beans,
                roasters = ui.roasters,
                onSelectBean = vm::setActiveBean,
                onOpenQuick = { quickOpen = true },
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Left column (248dp, scrolls).
                Column(
                    modifier = Modifier
                        .width(248.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TimerCard(running = running, elapsedMs = ui.shotElapsedMs, phase = ui.shotPhase)
                    RatioCard(active = active, weightG = ratioWeight(ui, running))
                    PhaseCard(active = active, running = running, frame = ui.shotFrame, phase = ui.shotPhase)
                    LimitsCard(active = active, ui = ui)
                    if (!running && ui.lastShot != null) {
                        LastShotCard(last = ui.lastShot!!, dose = active?.dose ?: 18f)
                    }
                }
                // Right column (fills remainder).
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    ChannelsRow(ui = ui, active = active)
                    // The live chart fills the remainder. Hosted in a Surface
                    // (not CremaCard) so the Canvas chart can fillMaxSize.
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        CanvasShotChart(
                            samples = ui.shotTelemetry,
                            enabledChannels = ui.chartChannels,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                        )
                    }
                }
            }
            BrewFoot(
                ui = ui,
                connected = connected,
                scaleConnected = scaleConnected,
                espressoActive = espressoActive,
                onPower = { if (ui.machineStateName == "Sleep") vm.wake() else vm.sleep() },
                onSteam = { if (ui.machineStateName == "Steam") vm.stopShot() else vm.steam() },
                onHotWater = { if (ui.machineStateName == "HotWater") vm.stopShot() else vm.hotWater() },
                onFlush = vm::flush,
                // Gated start: upload the active profile, await completion, guard,
                // then Espresso (vm.startShot). Stop is a direct Idle request.
                onCoffee = { if (espressoActive) vm.stopShot() else vm.startShot() },
            )
            if (quickOpen) {
                QuickControlsSheet(
                    autoTare = ui.autoTare,
                    stopOnWeight = ui.stopOnWeight,
                    steamEco = ui.steamEco,
                    channels = ui.chartChannels,
                    onAutoTare = vm::setAutoTare,
                    onStopOnWeight = vm::setStopOnWeight,
                    onSteamEco = vm::setSteamEco,
                    onToggleChannel = vm::toggleChartChannel,
                    onDismiss = { quickOpen = false },
                )
            }
        }
    }
}

// Which weight feeds the Ratio card: the live scale weight while running, else
// the last shot's yield held until the next shot (web parity).
private fun ratioWeight(ui: coffee.crema.ui.MainUiState, running: Boolean): Float? =
    if (!running) (ui.lastShot?.yieldG ?: ui.scaleWeightG) else ui.scaleWeightG

// ── Header twin-block ───────────────────────────────────────────────────────

@Composable
private fun BrewHeader(
    active: CremaProfile?,
    profiles: List<CremaProfile>,
    onSelectProfile: (String) -> Unit,
    uploading: Boolean,
    uploadProgress: String?,
    activeBean: Bean?,
    beans: List<Bean>,
    roasters: List<Roaster>,
    onSelectBean: (String) -> Unit,
    onOpenQuick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileBlock(
            active = active,
            profiles = profiles,
            onSelect = onSelectProfile,
            uploading = uploading,
            uploadProgress = uploadProgress,
        )
        Spacer(Modifier.weight(1f))
        Box(Modifier.width(1.dp).height(44.dp).background(MaterialTheme.colorScheme.outlineVariant))
        BeanBlock(activeBean = activeBean, beans = beans, roasters = roasters, onSelect = onSelectBean)
        QuickControlsPill(onClick = onOpenQuick)
    }
}

@Composable
private fun ProfileBlock(
    active: CremaProfile?,
    profiles: List<CremaProfile>,
    onSelect: (String) -> Unit,
    uploading: Boolean,
    uploadProgress: String?,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = profiles.isNotEmpty()) { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Profile")
                if (uploading) {
                    Text(
                        uploadProgress?.let { "Uploading… $it" } ?: "Uploading…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                active?.name ?: "No profile selected",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, lineHeight = 24.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (active != null) {
                Text(
                    "Pre-inf ${active.preinfuseSeconds}s · 1:%.2f · %.1f g · %.1f °C".format(
                        active.ratio, active.yieldOut, active.brewTemp,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val spec = listOfNotNull(
                    active.beverageType?.replaceFirstChar { it.uppercase() },
                    active.roast?.replaceFirstChar { it.uppercase() },
                    active.author.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (spec.isNotBlank()) {
                    Text(
                        spec,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // Active first, then pinned, then store order (web HeaderPicker rank).
            val sorted = profiles.sortedByDescending {
                (if (it.id == active?.id) 2 else 0) + (if (it.pinned) 1 else 0)
            }
            sorted.forEach { p ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(p.name, style = MaterialTheme.typography.bodyLarge)
                            val sub = p.author.takeIf { it.isNotBlank() }
                                ?: p.tags.filter { it.isNotBlank() && it != "Built-in" }.joinToString(" · ")
                            if (sub.isNotBlank()) {
                                Text(
                                    sub,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = { onSelect(p.id); open = false },
                    trailingIcon = if (p.id == active?.id) {
                        { PhIcon("check", sizeDp = 16, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun BeanBlock(
    activeBean: Bean?,
    beans: List<Bean>,
    roasters: List<Roaster>,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val roasterNameOf: (Bean) -> String? = { b ->
        b.roasterId?.let { rid -> roasters.firstOrNull { it.id == rid }?.name }
    }
    Box {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = beans.isNotEmpty()) { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Eyebrow("Bean")
            Text(
                activeBean?.let { listOfNotNull(roasterNameOf(it), it.name).joinToString(" · ") } ?: "No bean selected",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (activeBean != null) {
                val meta = listOfNotNull(
                    activeBean.origin.country,
                    activeBean.origin.processing,
                    roastBand(activeBean.roastLevel?.toInt()),
                    daysOffRoast(activeBean.roastedOn)?.let { "${it}d off roast" },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    "Add beans in the Beans tab",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            beans.forEach { b ->
                DropdownMenuItem(
                    text = {
                        Text(
                            listOfNotNull(roasterNameOf(b), b.name).joinToString(" · "),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    onClick = { onSelect(b.id); open = false },
                    trailingIcon = if (b.id == activeBean?.id) {
                        { PhIcon("check", sizeDp = 16, tint = MaterialTheme.colorScheme.primary) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun QuickControlsPill(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon("sliders-horizontal", sizeDp = 18, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        Text(
            "Quick Controls",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

// ── Left column cards ───────────────────────────────────────────────────────

@Composable
private fun TimerCard(running: Boolean, elapsedMs: Long, phase: String?) {
    val totalSec = elapsedMs / 1000.0
    val mm = (totalSec / 60).toInt()
    val ss = (totalSec % 60).toInt()
    val tenth = ((totalSec % 1) * 10).toInt()
    val step = if (!running) "Ready" else phaseStepLabel(phase)
    CremaCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = 85.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Eyebrow(step)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "%02d:%02d".format(mm, ss),
                    style = CremaTheme.readout.readoutMd.copy(fontSize = 44.sp, lineHeight = 48.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    ".$tenth",
                    style = CremaTheme.readout.readoutSm.copy(fontSize = 20.sp, lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RatioCard(active: CremaProfile?, weightG: Float?) {
    val dose = active?.dose ?: 18f
    val live = if (weightG != null && dose > 0f) "1:%.2f".format(weightG / dose) else "1:0.00"
    CremaCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Eyebrow("Ratio")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    live,
                    style = CremaTheme.readout.readoutSm.copy(fontSize = 24.sp, lineHeight = 28.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (active != null && active.ratio > 0f) {
                    Text(
                        "· target 1:%.2f".format(active.ratio),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhaseCard(active: CremaProfile?, running: Boolean, frame: Int, phase: String?) {
    val segments = active?.segments ?: emptyList()
    val activeIdx = if (running) frame.coerceIn(0, max(0, segments.lastIndex)) else -1
    CremaCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Phase")
                Text(
                    if (running) phaseStepLabel(phase) else "Idle",
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (segments.isEmpty()) {
                Text(
                    "No profile",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Proportional segment track (fill ∝ duration).
                Row(
                    Modifier.fillMaxWidth().height(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    segments.forEachIndexed { i, seg ->
                        val fillFrac = when {
                            i < activeIdx -> 1f
                            i == activeIdx -> 1f
                            else -> 0f
                        }
                        val fillColor = if (i == activeIdx) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        Box(
                            Modifier
                                .weight(max(0.01f, seg.time))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(fillFrac)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(fillColor),
                            )
                        }
                    }
                }
                // Per-segment rows (name | time | early-exit metric). No inner
                // scroll — the left column scrolls; per-frame partial fill +
                // auto-scroll are M2 (they pair with the live chart).
                segments.forEachIndexed { i, seg ->
                    PhaseRow(seg = seg, isActive = i == activeIdx, isPast = i < activeIdx)
                }
            }
        }
    }
}

@Composable
private fun PhaseRow(seg: coffee.crema.profiles.ProfileSegment, isActive: Boolean, isPast: Boolean) {
    val tel = CremaTheme.telemetry
    val border = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    else MaterialTheme.colorScheme.outlineVariant
    val bg = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isPast) 0.78f else 1f)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            seg.name.ifBlank { "Frame" },
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Early-exit metric icon (channel-coloured), when the frame can exit early.
        seg.exit?.metric?.let { metric ->
            val (icon, color) = when (metric) {
                "pressure" -> "gauge" to tel.pressure
                "flow", "volume" -> "drop" to tel.flow
                "weight" -> "scales" to tel.weight
                else -> "timer" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            PhIcon(icon, sizeDp = 14, tint = color)
        }
        Text(
            phaseTime(seg.time),
            style = CremaTheme.readout.readoutSm.copy(fontSize = 12.sp, lineHeight = 15.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun LimitsCard(active: CremaProfile?, ui: coffee.crema.ui.MainUiState) {
    val tel = CremaTheme.telemetry
    val weightG = ui.scaleWeightG
    val rows = buildList {
        if (active != null && active.yieldOut > 0f) {
            add(LimitRow("Yield", "scales", tel.weight, weightG, active.yieldOut, "g"))
        }
        if (active != null && active.maxTotalVolumeMl > 0) {
            add(LimitRow("Volume", "drop-half", tel.flow, ui.dispensedVolume, active.maxTotalVolumeMl.toFloat(), "ml"))
        }
        // Time limit needs the settings store (maxShotDurationS) — M3.
    }
    if (rows.isEmpty()) return
    CremaCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Eyebrow("Max · stop conditions")
            rows.forEach { LimitRowView(it) }
        }
    }
}

private data class LimitRow(
    val label: String,
    val icon: String,
    val color: Color,
    val live: Float?,
    val target: Float,
    val unit: String,
)

@Composable
private fun LimitRowView(row: LimitRow) {
    val frac = if (row.target > 0f && row.live != null) (row.live / row.target).coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PhIcon(row.icon, sizeDp = 16, tint = row.color)
                Text(row.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "%.1f / %.1f%s".format(row.live ?: 0f, row.target, row.unit),
                style = CremaTheme.readout.readoutSm.copy(fontSize = 15.sp, lineHeight = 19.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatBar(fraction = frac, color = row.color)
    }
}

@Composable
private fun LastShotCard(last: coffee.crema.ui.LastShot, dose: Float) {
    val yieldG = last.yieldG
    val ratio = if (yieldG != null && dose > 0f) "1:%.2f".format(yieldG / dose) else "—"
    CremaCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Eyebrow("Last shot")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LastShotStat("Yield", if (yieldG != null) "%.1f".format(yieldG) else "—", "g", Modifier.weight(1f))
                LastShotStat("Ratio", ratio, "", Modifier.weight(1f))
                LastShotStat("Time", "%.1f".format(last.durationMs / 1000.0), "s", Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LastShotStat(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = CremaTheme.readout.readoutSm.copy(fontSize = 15.sp, lineHeight = 19.sp), color = MaterialTheme.colorScheme.onSurface)
            if (unit.isNotBlank()) Text(" $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Right column ────────────────────────────────────────────────────────────

@Composable
private fun ChannelsRow(ui: coffee.crema.ui.MainUiState, active: CremaProfile?) {
    val tel = CremaTheme.telemetry
    val resist = ui.resistanceWeight ?: ui.resistance
    val resistUnit = if (ui.resistanceWeight != null) "bar·s²/g²" else "bar·s²/ml²"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        ChannelCard(
            modifier = Modifier.weight(1f),
            primLabel = "Pressure", primIcon = "gauge", primColor = tel.pressure,
            primValue = fmt(ui.pressure), primUnit = "bar",
            secLabel = "Resistance", secColor = tel.pressure2,
            secValue = fmt(resist, 2), secUnit = resistUnit,
        )
        ChannelCard(
            modifier = Modifier.weight(1f),
            primLabel = "Flow", primIcon = "drop", primColor = tel.flow,
            primValue = fmt(ui.flow), primUnit = "ml/s",
            secLabel = "Water", secColor = tel.flow2,
            secValue = fmt(ui.dispensedVolume), secUnit = "ml",
        )
        ChannelCard(
            modifier = Modifier.weight(1f),
            primLabel = "Coffee", primIcon = "thermometer", primColor = tel.temp,
            primValue = fmt(ui.headTemp), primUnit = "°C",
            secLabel = "Water", secColor = tel.temp2,
            secValue = fmt(ui.mixTemp), secUnit = "°C",
            target = active?.let { "target %.1f °C".format(it.brewTemp) },
        )
        ChannelCard(
            modifier = Modifier.weight(1f),
            primLabel = "Weight", primIcon = "scales", primColor = tel.weight,
            primValue = fmt(ui.scaleWeightG), primUnit = "g",
            secLabel = "Flow", secColor = tel.weight2,
            secValue = fmt(ui.scaleFlowGPerS), secUnit = "g/s",
            target = active?.let { "target %.1f g".format(it.yieldOut) },
        )
    }
}

@Composable
private fun ChannelCard(
    modifier: Modifier,
    primLabel: String, primIcon: String, primColor: Color, primValue: String, primUnit: String,
    secLabel: String, secColor: Color, secValue: String, secUnit: String,
    target: String? = null,
) {
    CremaCard(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 13.dp, vertical = 9.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Primary (left).
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhIcon(primIcon, sizeDp = 14, tint = primColor)
                    Eyebrow(primLabel, color = primColor)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(primValue, style = CremaTheme.readout.readoutSm.copy(fontSize = 19.sp, lineHeight = 23.sp), color = primColor)
                    Text(" $primUnit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (target != null) {
                    Text(target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Secondary (right).
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Eyebrow(secLabel, color = secColor)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(secValue, style = CremaTheme.readout.readoutSm.copy(fontSize = 19.sp, lineHeight = 23.sp), color = secColor)
                    Text(" $secUnit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Foot (split) ────────────────────────────────────────────────────────────

@Composable
private fun BrewFoot(
    ui: coffee.crema.ui.MainUiState,
    connected: Boolean,
    scaleConnected: Boolean,
    espressoActive: Boolean,
    onPower: () -> Unit,
    onSteam: () -> Unit,
    onHotWater: () -> Unit,
    onFlush: () -> Unit,
    onCoffee: () -> Unit,
) {
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Left meta cluster.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PowerButton(connected = connected, asleep = ui.machineStateName == "Sleep", onClick = onPower)
                FootMeta("Machine", ui.machineState ?: "—")
                FootDivider()
                FootMeta("Scale", if (scaleConnected) (ui.scaleName ?: "Scale") else "—")
                FootDivider()
                FootMeta("Steam", ui.steamTemp?.let { "%.0f °C".format(it) } ?: "—")
                FootMeta("Tank", ui.waterLevelMm?.let { "%.0f mm".format(it) } ?: "—")
            }
            // Right actions.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Steam", "148 °C · 90 s", "cloud", CremaTheme.telemetry.modeSteam, active = ui.machineStateName == "Steam", enabled = connected, onTap = onSteam)
                ModeChip("Hot water", "90 °C · 250 ml", "drop", CremaTheme.telemetry.modeWater, active = ui.machineStateName == "HotWater", enabled = connected, onTap = onHotWater)
                ModeChip("Flush", "91 °C · 4 s", "sparkle", CremaTheme.telemetry.modeFlush, active = false, enabled = connected, onTap = onFlush)
                CoffeeButton(running = espressoActive, uploading = ui.profileUploading, enabled = connected, onClick = onCoffee)
            }
        }
    }
}

@Composable
private fun RowScope.FootMeta(label: String, value: String) {
    Column(Modifier.padding(end = 2.dp)) {
        Eyebrow(label)
        Text(
            value,
            style = CremaTheme.readout.readoutSm.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FootDivider() {
    Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun PowerButton(connected: Boolean, asleep: Boolean, onClick: () -> Unit) {
    val tint = when {
        !connected -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        asleep -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> CremaTheme.telemetry.success
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = connected, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        PhIcon("power", sizeDp = 18, tint = tint)
    }
}

@Composable
private fun ModeChip(
    label: String,
    sub: String,
    icon: String,
    modeColor: Color,
    active: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
) {
    val border = if (active) modeColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val bg = modeColor.copy(alpha = if (active) 0.20f else 0.14f)
    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(999.dp))
            .clickable(enabled = enabled, onClick = onTap)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PhIcon(icon, sizeDp = 19, tint = modeColor)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = CremaTheme.readout.readoutSm.copy(fontSize = 10.5f.sp, lineHeight = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (active) {
                PhIcon("x", sizeDp = 10, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CoffeeButton(running: Boolean, uploading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val stopColor = Color(0xFFD26456)
    val bg = if (running) stopColor else MaterialTheme.colorScheme.primary
    val fg = if (running) Color(0xFF2A0B07) else MaterialTheme.colorScheme.onPrimary
    // Tappable to start (Coffee) or stop (Stop); inert while a profile uploads.
    val clickable = enabled && !uploading
    val label = when { running -> "Stop"; uploading -> "Uploading…"; else -> "Coffee" }
    val icon = when { running -> "stop"; uploading -> "arrows-clockwise"; else -> "coffee" }
    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(if (clickable || running) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = clickable, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhIcon(icon, sizeDp = 20, tint = fg)
        Text(
            label,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
            color = fg,
        )
    }
}

// ── Shared bits ─────────────────────────────────────────────────────────────

@Composable
private fun StatBar(fraction: Float, color: Color) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(999.dp))
                .background(color),
        )
    }
}

private fun fmt(v: Float?, digits: Int = 1): String =
    if (v == null) "—" else "%.${digits}f".format(v)

private fun phaseStepLabel(phase: String?): String = when (phase) {
    "Heating" -> "Heating"
    "Preinfusion" -> "Pre-infusion"
    "Pouring" -> "Extraction"
    "Ending" -> "Ending"
    else -> "Extraction"
}

private fun phaseTime(t: Float): String = when {
    t <= 0f -> "—"
    t < 1f -> "%.1fs".format(t)
    else -> "${t.roundToInt()}s"
}

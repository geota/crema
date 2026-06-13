package coffee.crema.ui.phone

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.beans.daysOffRoast
import coffee.crema.beans.isFrozen
import coffee.crema.ui.formatRatio
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.profiles.CremaProfile
import coffee.crema.ui.MainUiState
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.*
import coffee.crema.ui.screens.CanvasProfilePreview
import coffee.crema.ui.screens.CanvasShotChart
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono

/*
 * PhoneBrewScreen — the handset hero (port of prototype/phone/phone-brew.jsx
 * BrewIdle + BrewRefinedA2), wired to LIVE state.
 *
 * Two states, switched by the shot lifecycle:
 *  • RESTING — profile/bean strip, ready hero (plan curve + targets), machine
 *    readiness strip, last-shot peek, mode cluster + Coffee.
 *  • RUNNING — same strip, compact timer + yield bar, four dual-channel chips
 *    (they ARE the chart legend), the live chart, mode cluster + Stop.
 *
 * Top bar: sliders → Quick Controls sheet · bluetooth → Devices sheet · gear →
 * Settings · kebab → profile overflow. The strip opens the profile/bean swap
 * dropdown anchored beneath it.
 */
@Composable
fun PhoneBrewScreen(
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
    var devicesOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var swapOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CremaPhoneTopBar(
                title = "Brew",
                actions = listOf(
                    BarAction("sliders-horizontal") { quickOpen = true },
                    BarAction(if (connected) "bluetooth-connected" else "bluetooth", accent = connected) { devicesOpen = true },
                    BarAction("gear-six") { onNav("settings") },
                    BarAction("dots-three-vertical") { menuOpen = true },
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(horizontal = CremaEdge)
                .padding(top = 2.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            // ── Tappable profile / bean strip + anchored swap dropdown ───────
            Box {
                ProfileStrip(
                    active = active,
                    beanLine = beanLine(ui),
                    onClick = { swapOpen = true },
                )
                if (swapOpen) {
                    SwapDropdown(
                        ui = ui,
                        onSelectProfile = { vm.setActiveProfile(it); swapOpen = false },
                        onSelectBean = { vm.setActiveBean(it); swapOpen = false },
                        onAllProfiles = { swapOpen = false; onNav("profiles") },
                        onAllBeans = { swapOpen = false; onNav("beans") },
                        onDismiss = { swapOpen = false },
                    )
                }
            }

            if (running) {
                RunningBody(ui = ui, active = active, modifier = Modifier.weight(1f))
            } else {
                RestingBody(
                    ui = ui,
                    active = active,
                    connected = connected,
                    scaleConnected = scaleConnected,
                    onOpenLastShot = { id -> vm.openShotInHistory(id); onNav("history") },
                    modifier = Modifier.weight(1f),
                )
            }

            // ── Foot: mode cluster + Coffee/Stop ─────────────────────────────
            ModeCluster(
                ui = ui,
                connected = connected,
                onSteam = { if (ui.machineStateName == "Steam") vm.stopShot() else vm.steam() },
                onWater = { if (ui.machineStateName == "HotWater") vm.stopShot() else vm.hotWater() },
                onFlush = vm::flush,
            )
            CoffeeCta(
                running = espressoActive,
                uploading = ui.profileUploading,
                enabled = connected,
                onClick = { if (espressoActive) vm.stopShot() else vm.startShot() },
            )
        }
    }

    if (quickOpen) {
        PhoneQuickSheet(
            ui = ui,
            active = active,
            vm = vm,
            onDismiss = { quickOpen = false },
        )
    }
    if (devicesOpen) {
        PhoneDevicesSheet(
            ui = ui,
            connected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
            onDismiss = { devicesOpen = false },
        )
    }
    if (menuOpen) {
        CremaOverflowSheet(
            title = active?.name ?: "Brew",
            items = buildList {
                add(SheetItem("pencil-simple", "Edit profile") {
                    when {
                        active == null -> vm.startNewProfile()
                        active.source == "custom" -> vm.startEditProfile(active.id)
                        else -> vm.duplicateProfile(active.id)
                    }
                    onNav("profile-edit")
                })
                add(SheetItem("shapes", "Browse profile library") { onNav("profiles") })
                if (ui.brewParams != null) {
                    add(SheetItem("arrow-counter-clockwise", "Reset changes to saved", sub = "Clear the Quick-Controls override") { vm.resetBrewParams() })
                }
                add(SheetItem(divider = true))
                add(
                    SheetItem(
                        if (ui.machineStateName == "Sleep") "sun" else "moon",
                        if (ui.machineStateName == "Sleep") "Wake machine" else "Sleep machine",
                        sub = if (connected) null else "Connect the DE1 first",
                    ) { if (connected) { if (ui.machineStateName == "Sleep") vm.wake() else vm.sleep() } },
                )
            },
            onDismiss = { menuOpen = false },
        )
    }
}

/** "Roaster · Bean · Nd off roast" strip meta, or a quiet fallback. */
private fun beanLine(ui: MainUiState): String {
    val bean = ui.beans.firstOrNull { it.id == ui.activeBeanId } ?: return "No bean selected"
    val roaster = ui.roasters.firstOrNull { it.id == bean.roasterId }?.name
    val days = daysOffRoast(bean.roastedOn)
    return listOfNotNull(
        roaster,
        bean.name,
        when {
            bean.isFrozen -> "frozen"
            days != null -> "${days}d off roast"
            else -> null
        },
    ).joinToString(" · ")
}

// ── Profile strip (proto .pf-profilestrip) ───────────────────────────────────
@Composable
private fun ProfileStrip(active: CremaProfile?, beanLine: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    active?.name ?: "No profile loaded",
                    style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    beanLine,
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.5.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            PhIcon("caret-down", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Swap dropdown (proto .pf-dd) — profiles w/ sparks + beans w/ avatars ─────
@Composable
private fun SwapDropdown(
    ui: MainUiState,
    onSelectProfile: (String) -> Unit,
    onSelectBean: (String) -> Unit,
    onAllProfiles: () -> Unit,
    onAllBeans: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ddWidth = LocalConfiguration.current.screenWidthDp.dp - CremaEdge * 2
    CremaAnchoredPopup(expanded = true, onDismiss = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 18.dp,
            modifier = Modifier.width(ddWidth).heightIn(max = 560.dp),
        ) {
            Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                DdSectionHead("Profile", "All profiles →", onAllProfiles)
                // Pinned favourites lead; fall back to the first few profiles.
                val faves = ui.profiles.filter { it.pinned }.take(4)
                    .ifEmpty { ui.profiles.filter { it.id !in ui.hiddenProfileIds }.take(4) }
                faves.forEach { p ->
                    val activeRow = p.id == ui.activeProfileId
                    DdRow(active = activeRow, onClick = { onSelectProfile(p.id) }) {
                        Box(
                            Modifier
                                .size(width = 44.dp, height = 26.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                            contentAlignment = Alignment.Center,
                        ) {
                            MiniProfileSpark(
                                profile = p,
                                active = activeRow,
                                modifier = Modifier.fillMaxSize().padding(3.dp),
                            )
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${formatRatio(p.dose, p.yieldOut)} · %.0fg · %.0f°".format(p.dose, p.brewTemp),
                                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                                color = if (activeRow) LocalContentColor.current.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (activeRow) PhIcon("check-circle", sizeDp = 18, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DdSectionHead("Bean", "All beans →", onAllBeans)
                val beans = ui.beans.filter { it.archivedAt == null }.take(3)
                beans.forEach { b ->
                    val activeRow = b.id == ui.activeBeanId
                    val roaster = ui.roasters.firstOrNull { it.id == b.roasterId }?.name
                    val days = daysOffRoast(b.roastedOn)
                    DdRow(active = activeRow, onClick = { onSelectBean(b.id) }) {
                        RoasterMarkAvatar(name = roaster ?: b.name, sizeDp = 30, cornerDp = 8, fontSize = 13.sp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOfNotNull(roaster, b.name).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    days?.let { "${it}d off roast" },
                                    b.remaining.takeIf { b.bagSize > 0f }?.let { "%.0f g left".format(it) },
                                ).joinToString(" · ").ifEmpty { "—" },
                                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                                color = if (activeRow) LocalContentColor.current.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (activeRow) PhIcon("check-circle", sizeDp = 18, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                if (beans.isEmpty()) {
                    Text(
                        "No beans yet — add a bag in Beans.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DdSectionHead(label: String, link: String, onLink: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Eyebrow(label)
        Text(
            link,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onLink).padding(4.dp),
        )
    }
}

@Composable
private fun DdRow(active: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    val bg = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    val fg = if (active) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
    CompositionLocalProvider(LocalContentColor provides fg) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            content = content,
        )
    }
}

/**
 * Tiny silhouette of a profile's segment plan (proto MiniSpark), coloured like
 * the full card preview: pressure phases solid sage, flow phases dashed blue.
 * The active row tints everything primary (the proto's active treatment).
 */
@Composable
private fun MiniProfileSpark(profile: CremaProfile, active: Boolean, modifier: Modifier = Modifier) {
    val tel = CremaTheme.telemetry
    val primary = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val segs = profile.segments.filter { it.time > 0f }
        if (segs.isEmpty()) return@Canvas
        val total = segs.sumOf { it.time.toDouble() }.toFloat().takeIf { it > 0f } ?: return@Canvas
        val maxV = (segs.maxOf { it.target }).coerceAtLeast(1f)
        val stroke = 2.dp.toPx()
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
            floatArrayOf(3.dp.toPx(), 2.5.dp.toPx()),
        )
        fun yOf(v: Float) = (1f - (v / maxV)) * (size.height * 0.9f) + size.height * 0.05f
        var t = 0f
        var lastY = size.height * 0.95f
        segs.forEach { s ->
            val isFlow = s.mode == "flow"
            val x0 = (t / total) * size.width
            t += s.time
            val x1 = (t / total) * size.width
            val y = yOf(s.target)
            val path = Path()
            path.moveTo(x0, lastY)
            if (s.ramp == "fast") {
                path.lineTo(x0, y)
            } else {
                // Quick ease into the new level over the first ~20% of the phase.
                path.cubicTo(x0, lastY, x0, y, x0 + (x1 - x0) * 0.2f, y)
            }
            path.lineTo(x1, y)
            drawPath(
                path,
                color = when {
                    active -> primary
                    isFlow -> tel.flow
                    else -> tel.pressure
                },
                style = Stroke(
                    width = stroke,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                    pathEffect = if (isFlow) dash else null,
                ),
            )
            lastY = y
        }
    }
}

/* ────────────────────────── RUNNING (BrewRefinedA2) ─────────────────────── */

@Composable
private fun RunningBody(ui: MainUiState, active: CremaProfile?, modifier: Modifier = Modifier) {
    val tel = CremaTheme.telemetry
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // Compact timer + yield bar.
        val totalS = ui.shotElapsedMs / 1000.0
        val mm = (totalS.toInt() / 60).toString().padStart(2, '0')
        val ss = (totalS.toInt() % 60).toString().padStart(2, '0')
        val frac = ((totalS % 1) * 10).toInt().toString()
        val dose = (ui.brewParams?.dose ?: active?.dose?.toDouble() ?: 18.0).toFloat()
        val target = (ui.brewParams?.yieldOut ?: active?.yieldOut?.toDouble() ?: 36.0).toFloat()
        val weight = ui.scaleWeightG ?: 0f
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    phaseLabel(ui.shotPhase).uppercase(),
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$mm:$ss",
                        style = TextStyle(
                            fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium,
                            fontSize = 60.sp, lineHeight = 54.sp, letterSpacing = (-2).sp,
                            fontFeatureSettings = "tnum",
                        ),
                    )
                    Text(
                        ".$frac",
                        style = TextStyle(
                            fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium,
                            fontSize = 28.sp, lineHeight = 54.sp, letterSpacing = (-1).sp,
                            fontFeatureSettings = "tnum",
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Yield row + bar.
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("Yield · ${formatRatio(dose, weight)}")
                        Text(
                            "%.1f / %.1f g".format(weight, target),
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
                        )
                    }
                    Box(
                        Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(
                            Modifier.fillMaxWidth((weight / target).coerceIn(0f, 1f)).fillMaxHeight()
                                .clip(RoundedCornerShape(999.dp))
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                }
            }
        }

        // 2×2 dual-channel chips — these are the chart legend.
        val resist = ui.resistanceWeight ?: ui.resistance
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DualChip(
                Modifier.weight(1f),
                "PRESSURE", fmtF(ui.pressure), "bar", tel.pressure,
                "RESISTANCE", fmtF(resist, 2), "", tel.pressure2,
            )
            DualChip(
                Modifier.weight(1f),
                "FLOW", fmtF(ui.flow), "ml/s", tel.flow,
                "VOLUME", fmtF(ui.dispensedVolume, 0), "ml", tel.flow2,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DualChip(
                Modifier.weight(1f),
                "COFFEE", fmtF(ui.headTemp), "°C", tel.temp,
                "WATER", fmtF(ui.mixTemp), "°C", tel.temp2,
            )
            DualChip(
                Modifier.weight(1f),
                "WEIGHT", fmtF(ui.scaleWeightG), "g", tel.weight,
                "FLOW", fmtF(ui.scaleFlowGPerS), "g/s", tel.weight2,
            )
        }

        // Live chart (no legend — the chips above are the legend).
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            CanvasShotChart(
                samples = ui.shotTelemetry,
                enabledChannels = ui.chartChannels,
                modifier = Modifier.fillMaxSize().padding(start = 2.dp, end = 8.dp, top = 8.dp, bottom = 2.dp),
            )
        }
    }
}

private fun phaseLabel(phase: String?): String = when (phase) {
    "Heating" -> "Heating"
    "Preinfusion" -> "Pre-infusion"
    "Pouring" -> "Extraction"
    "Ending" -> "Ending"
    else -> "Extraction"
}

private fun fmtF(v: Float?, digits: Int = 1): String =
    if (v == null) "—" else "%.${digits}f".format(v)

// Dual-channel chip (proto .pf-dchip).
@Composable
private fun DualChip(
    modifier: Modifier,
    label: String, value: String, unit: String, color: Color,
    sLabel: String, sValue: String, sUnit: String, sColor: Color,
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(color))
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, letterSpacing = 0.7.sp, fontWeight = FontWeight.SemiBold),
                    color = color,
                )
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    value,
                    style = TextStyle(
                        fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium,
                        fontSize = 27.sp, lineHeight = 30.sp, letterSpacing = (-1).sp, fontFeatureSettings = "tnum",
                    ),
                    color = color,
                )
                if (unit.isNotEmpty()) Text(
                    unit,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 2.dp))
            Row(
                Modifier.fillMaxWidth().padding(top = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    sLabel,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold),
                    color = sColor,
                )
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        sValue,
                        style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 12.sp, fontFeatureSettings = "tnum"),
                        color = sColor,
                    )
                    if (sUnit.isNotEmpty()) Text(
                        sUnit,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/* ────────────────────────── RESTING (BrewIdle) ──────────────────────────── */

@Composable
private fun RestingBody(
    ui: MainUiState,
    active: CremaProfile?,
    connected: Boolean,
    scaleConnected: Boolean,
    onOpenLastShot: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tel = CremaTheme.telemetry
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // ── Ready hero ───────────────────────────────────────────────────────
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                // Badge + group temp.
                val asleep = ui.machineStateName == "Sleep"
                val heating = ui.machineStateName == "Heating" || ui.machineSubstate == "Heating"
                val (badge, badgeColor) = when {
                    !connected -> "Not connected" to MaterialTheme.colorScheme.onSurfaceVariant
                    asleep -> "Asleep — tap Coffee to wake" to MaterialTheme.colorScheme.onSurfaceVariant
                    heating -> "Heating…" to Color(0xFFDBA764)
                    else -> "Ready to brew" to tel.success
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(badgeColor))
                        Text(
                            badge,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                            color = badgeColor,
                        )
                    }
                    Text(
                        ui.headTemp?.let { "Group %.1f°C".format(it) } ?: "Group —",
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 12.sp, fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Target line: dose → yield, ratio.
                val dose = ui.brewParams?.dose ?: active?.dose?.toDouble() ?: 18.0
                val yieldOut = ui.brewParams?.yieldOut ?: active?.yieldOut?.toDouble() ?: 36.0
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TargetNumber(dose)
                    PhIcon(
                        "arrow-right", sizeDp = 18,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    TargetNumber(yieldOut)
                    Spacer(Modifier.weight(1f))
                    Text(
                        formatRatio(dose, yieldOut),
                        style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 20.sp, fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                // Plan curve — the full target profile, dimmed.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(108.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                ) {
                    if (active != null && active.segments.isNotEmpty()) {
                        CanvasProfilePreview(segments = active.segments, modifier = Modifier.fillMaxSize().alpha(0.75f))
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "No profile loaded — pick one above.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                // 4-up param strip.
                val peakBar = active?.segments?.filter { it.mode != "flow" }?.maxOfOrNull { it.target }
                val estTime = active?.segments?.sumOf { it.time.toDouble() }?.toInt()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ReadyParam("TEMP", "%.1f".format(ui.brewParams?.brewTemp ?: active?.brewTemp?.toDouble() ?: 93.0), "°C", Modifier.weight(1f))
                    ReadyParam("PRE-INF", "${active?.preinfuseSeconds ?: 0}", "s", Modifier.weight(1f))
                    ReadyParam("PRESSURE", peakBar?.let { "%.1f".format(it) } ?: "—", if (peakBar != null) "bar" else "", Modifier.weight(1f))
                    ReadyParam("EST. TIME", estTime?.let { "~$it" } ?: "—", if (estTime != null) "s" else "", Modifier.weight(1f))
                }
            }
        }

        // ── Machine readiness strip — group temp · steam temp · tank · scale ─
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val groupOk = connected && ui.headTemp != null &&
                active != null && ui.headTemp >= active.brewTemp - 2f
            MStat("Group", "thermometer", ui.headTemp?.let { "%.1f°".format(it) } ?: "—", groupOk, Modifier.weight(1f))
            MStat("Steam", "cloud", ui.steamTemp?.let { "%.0f°".format(it) } ?: "—", false, Modifier.weight(1f))
            MStat("Tank", "drop-half", ui.waterLevelMm?.let { "%.0fmm".format(it) } ?: "—", false, Modifier.weight(1f))
            MStat("Scale", "scales", if (scaleConnected) "%.1fg".format(ui.scaleWeightG ?: 0f) else "—", scaleConnected, Modifier.weight(1f))
        }

        // ── Last shot peek ───────────────────────────────────────────────────
        val last = ui.lastShot
        if (last != null) {
            val lastStored = ui.history.firstOrNull { it.id == last.id }
            Surface(
                onClick = { onOpenLastShot(last.id) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Eyebrow(
                            "Last shot · " + remember(last.completedAtMs) {
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                                    .format(java.util.Date(last.completedAtMs))
                            },
                        )
                        val r = lastStored?.rating ?: 0
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            (1..5).forEach { n ->
                                PhIcon(
                                    if (n <= r) "star-fill" else "star",
                                    sizeDp = 13,
                                    tint = if (n <= r) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                )
                            }
                        }
                        Text(
                            listOfNotNull(lastStored?.profileName, lastStored?.beanName).joinToString(" · ").ifEmpty { "Shot" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                last.yieldG?.let { "%.1f".format(it) } ?: "—",
                                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 20.sp, fontFeatureSettings = "tnum"),
                            )
                            Text("g", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        val ratio = lastStored?.let { s ->
                            val y = s.yieldG; val d = s.doseG
                            if (y != null && d != null && d > 0f) formatRatio(d, y) else null
                        }
                        Text(
                            listOfNotNull(ratio, "%.0fs".format(last.durationMs / 1000.0)).joinToString(" · "),
                            style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PhIcon("caret-right", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun TargetNumber(value: Double) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            "%.0f".format(value),
            style = TextStyle(
                fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium,
                fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-1.5).sp, fontFeatureSettings = "tnum",
            ),
        )
        Text(
            "g",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 1.dp, bottom = 5.dp),
        )
    }
}

@Composable
private fun ReadyParam(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                value,
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum"),
            )
            if (unit.isNotEmpty()) Text(
                unit,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Machine-readiness tile (proto .pf-mstat-i) — a labelled live machine stat
// (NOT a chart channel): group temp / steam temp / tank level / scale weight.
@Composable
private fun MStat(label: String, icon: String, value: String, ok: Boolean, modifier: Modifier = Modifier) {
    val tel = CremaTheme.telemetry
    Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(
            Modifier.padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 0.6.sp, fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhIcon(icon, sizeDp = 15, tint = if (ok) tel.success else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(5.dp))
                Text(
                    value,
                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

/* ────────────────────────── Foot ────────────────────────────────────────── */

@Composable
private fun ModeCluster(
    ui: MainUiState,
    connected: Boolean,
    onSteam: () -> Unit,
    onWater: () -> Unit,
    onFlush: () -> Unit,
) {
    val tel = CremaTheme.telemetry
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ModePill(
            "Steam", ui.steamTemp?.let { "%.0f° · 90s".format(it) } ?: "148° · 90s",
            "cloud", tel.modeSteam,
            active = ui.machineStateName == "Steam",
            enabled = connected, onTap = onSteam, modifier = Modifier.weight(1f),
        )
        ModePill(
            "Water", "250ml · 90°", "drop", tel.modeWater,
            active = ui.machineStateName == "HotWater",
            enabled = connected, onTap = onWater, modifier = Modifier.weight(1f),
        )
        ModePill(
            "Flush", "4s purge", "sparkle", tel.modeFlush,
            active = false,
            enabled = connected, onTap = onFlush, modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ModePill(
    label: String, sub: String, icon: String, color: Color,
    active: Boolean, enabled: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier,
) {
    val bg = color.copy(alpha = 0.16f).blendOverSurface(MaterialTheme.colorScheme.surfaceContainer)
    Surface(
        onClick = onTap,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = if (active) androidx.compose.foundation.BorderStroke(1.5.dp, color) else null,
        modifier = modifier.alpha(if (enabled) 1f else 0.55f),
    ) {
        Column(
            Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                PhIcon(icon, sizeDp = 17, tint = color)
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                sub,
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 10.sp, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun CoffeeCta(running: Boolean, uploading: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val stopColor = Color(0xFFD26456)
    val bg = if (running) stopColor else MaterialTheme.colorScheme.primary
    val fg = if (running) Color(0xFF2A0B07) else MaterialTheme.colorScheme.onPrimary
    val clickable = enabled && !uploading
    val label = when { running -> "Stop"; uploading -> "Uploading…"; else -> "Coffee" }
    val icon = when { running -> "stop"; uploading -> "arrows-clockwise"; else -> "coffee" }
    Surface(
        onClick = onClick,
        enabled = clickable,
        shape = RoundedCornerShape(999.dp),
        color = if (clickable || running) bg else bg.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth().height(58.dp),
    ) {
        Row(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhIcon(icon, sizeDp = 22, tint = fg)
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                color = fg,
            )
        }
    }
}

// CSS color-mix helper.
private fun Color.blendOverSurface(base: Color): Color = Color(
    red = red * alpha + base.red * (1 - alpha),
    green = green * alpha + base.green * (1 - alpha),
    blue = blue * alpha + base.blue * (1 - alpha),
    alpha = 1f,
)

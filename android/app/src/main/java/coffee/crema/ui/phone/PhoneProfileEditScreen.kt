package coffee.crema.ui.phone

import coffee.crema.ui.fmt
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.core.Compare
import coffee.crema.core.ExitMetric
import coffee.crema.core.Pump
import coffee.crema.core.Transition
import coffee.crema.profiles.ProfileBounds
import coffee.crema.profiles.SegmentEdit
import coffee.crema.profiles.SegmentExit
import coffee.crema.profiles.exitMetricFromWire
import coffee.crema.profiles.isPressure
import coffee.crema.profiles.pumpFromWire
import coffee.crema.profiles.transitionFromWire
import coffee.crema.profiles.limiterUnit
import coffee.crema.profiles.targetUnit
import coffee.crema.profiles.toEdit
import coffee.crema.ui.formatRatio
import coffee.crema.profiles.SegmentLimiter
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.CremaEdge
import coffee.crema.ui.phone.components.CremaPhoneBackBar
import coffee.crema.ui.screens.ProfileCurveChart
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono

/*
 * PhoneProfileEditScreen — the pushed profile editor (DESIGN §3.7; port of
 * prototype/phone/phone-editors.jsx ProfileEdit), wired to the SAME save seam
 * as the tablet editor (vm.saveProfile patches only edited fields into the
 * profile's complete JSON).
 *
 * Groups: Profile (name, notes, author, roast affinity, beverage) → Targets
 * (dose / yield / computed ratio / brew temp + whole-shot limits) → Pressure
 * curve (the shared draggable ProfileCurveChart + the PHASE ACCORDION — each
 * phase collapses to name + summary, expands to the full field set) → Tags &
 * options.
 */

/** Uniform width for the compact phase-editor segment pills so they line up. */
private val SegmentPillWidth = 176.dp

@Composable
fun PhoneProfileEditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val profile = ui.profiles.firstOrNull { it.id == ui.editingProfileId }
        ?: ui.draftProfile?.takeIf { it.id == ui.editingProfileId }

    val back: () -> Unit = { vm.cancelProfileEdit(); onBack() }
    BackHandler(onBack = back)

    if (profile == null) {
        Scaffold(
            topBar = { CremaPhoneBackBar(title = "Profile editor", onBack = back) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { inner ->
            Box(Modifier.padding(inner).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No profile selected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    val isNew = ui.draftProfile?.id == profile.id

    var name by remember(profile.id) { mutableStateOf(profile.name) }
    var roast by remember(profile.id) { mutableStateOf(profile.roast) }
    val tags = remember(profile.id) {
        mutableStateListOf<String>().apply { addAll(profile.tags.filterNot { it.equals("Built-in", ignoreCase = true) }) }
    }
    var pinned by remember(profile.id) { mutableStateOf(profile.pinned) }
    var notes by remember(profile.id) { mutableStateOf(profile.notes) }
    var author by remember(profile.id) { mutableStateOf(profile.author) }
    var beverage by remember(profile.id) { mutableStateOf(profile.beverageType) }
    var dose by remember(profile.id) { mutableStateOf(profile.dose.toDouble()) }
    var yieldG by remember(profile.id) { mutableStateOf(profile.yieldOut.toDouble()) }
    var brewTemp by remember(profile.id) { mutableStateOf(profile.brewTemp.toDouble()) }
    var maxVol by remember(profile.id) { mutableStateOf(profile.maxTotalVolumeMl.toDouble()) }
    var preinfuse by remember(profile.id) { mutableStateOf(profile.preinfuseStepCount.toDouble()) }
    var tankTemp by remember(profile.id) { mutableStateOf(profile.tankTemperatureC.toDouble()) }
    val segs = remember(profile.id) {
        mutableStateListOf<SegmentEdit>().apply {
            addAll(profile.segments.map { it.toEdit(profile.brewTemp) })
        }
    }
    var openPhase by remember(profile.id) { mutableStateOf(-1) }

    Scaffold(
        topBar = {
            CremaPhoneBackBarWithSave(
                breadcrumb = "Profiles",
                title = if (isNew) "New profile" else "Edit profile",
                saveEnabled = name.isNotBlank(),
                onCancel = back,
                onSave = {
                    vm.saveProfile(
                        id = profile.id,
                        name = name,
                        roast = roast,
                        tags = tags.toList(),
                        pinned = pinned,
                        notes = notes,
                        author = author,
                        beverageType = beverage,
                        dose = dose.toFloat(),
                        yieldOut = yieldG.toFloat(),
                        brewTemp = brewTemp.toFloat(),
                        maxTotalVolumeMl = maxVol.toInt(),
                        preinfuseStepCount = preinfuse.toInt(),
                        tankTemperatureC = tankTemp.toFloat(),
                        segments = segs.toList(),
                    )
                    onBack()
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
                .padding(horizontal = CremaEdge)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(2.dp))

            // ── Profile ─────────────────────────────────────────────────────
            EdGroup("Profile") {
                CremaTextField(value = name, onValueChange = { name = it }, label = "Name", placeholder = "Profile name")
                CremaTextField(value = notes, onValueChange = { notes = it }, label = "Notes", placeholder = "Tasting notes, recipe intent…", singleLine = false, minLines = 2)
                CremaTextField(value = author, onValueChange = { author = it }, label = "Author", placeholder = "Who designed this profile?")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Roast affinity", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CremaSegmentedButton(
                        options = listOf(SegOption("light", "Light"), SegOption("medium", "Medium"), SegOption("dark", "Dark")),
                        value = roast ?: "",
                        onChange = { roast = if (roast == it) null else it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Beverage type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CremaSegmentedButton(
                        options = listOf(SegOption("espresso", "Espresso"), SegOption("filter", "Filter"), SegOption("pourover", "Pour-over")),
                        value = beverage ?: "",
                        onChange = { beverage = if (beverage == it) null else it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── Targets ─────────────────────────────────────────────────────
            EdGroup("Targets") {
                EdRow("Dose") { CremaStepper(value = dose, unit = "g", step = 0.1, min = 5.0, max = 30.0, fmt = { fmt("%.1f", it) }, onChange = { dose = it }, style = CremaStepperStyle.BareCompact) }
                EdRow("Yield") { CremaStepper(value = yieldG, unit = "g", step = 0.5, min = 10.0, max = 120.0, fmt = { fmt("%.1f", it) }, onChange = { yieldG = it }, style = CremaStepperStyle.BareCompact) }
                EdRow("Ratio", sub = "Computed") {
                    Text(
                        formatRatio(dose, yieldG),
                        style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 18.sp, fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                EdRow("Brew temp") { CremaStepper(value = brewTemp, unit = "°C", step = 0.5, min = 80.0, max = ProfileBounds.INSTANCE.maxTemperatureC.toDouble(), fmt = { fmt("%.1f", it) }, onChange = { brewTemp = it }, style = CremaStepperStyle.BareCompact) }
                EdRow("Max total volume", sub = "Cap the whole shot by volume", dot = true, dotOn = maxVol > 0, onDot = { maxVol = if (maxVol > 0) 0.0 else 50.0 }) { CremaStepper(value = maxVol, unit = "ml", step = 5.0, min = 0.0, max = 500.0, fmt = { fmt("%.0f", it) }, onChange = { maxVol = it }, style = CremaStepperStyle.BareCompact) }
                EdRow("Pre-infuse phases", sub = "Leading phases counted as pre-infusion", dot = true, dotOn = preinfuse > 0, onDot = { preinfuse = if (preinfuse > 0) 0.0 else 1.0 }) { CremaStepper(value = preinfuse, unit = null, step = 1.0, min = 0.0, max = segs.size.toDouble(), fmt = { fmt("%.0f", it) }, onChange = { preinfuse = it }, style = CremaStepperStyle.BareCompact) }
                EdRow("Tank temp", sub = "Override the machine tank setpoint", dot = true, dotOn = tankTemp > 0, onDot = { tankTemp = if (tankTemp > 0) 0.0 else 92.0 }) { CremaStepper(value = tankTemp, unit = "°C", step = 1.0, min = 0.0, max = 60.0, fmt = { fmt("%.0f", it) }, onChange = { tankTemp = it }, style = CremaStepperStyle.BareCompact) }
            }

            // ── Pressure curve + phase accordion ────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Pressure curve", Modifier.padding(start = 4.dp))
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Drag points to shape",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                        ProfileCurveChart(
                            targets = segs.map { it.target },
                            times = segs.map { it.time },
                            modes = segs.map { it.mode },
                            ramps = segs.map { it.ramp },
                            temps = segs.map { it.temp },
                            modifier = Modifier.fillMaxWidth().height(170.dp),
                            onSegmentEdit = { i, target, time ->
                                segs[i] = segs[i].copy(
                                    target = (target * 10).toInt() / 10f,
                                    time = (time * 10).toInt() / 10f,
                                )
                            },
                        )
                    }
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column {
                        segs.forEachIndexed { i, seg ->
                            PhaseRow(
                                index = i,
                                seg = seg,
                                open = openPhase == i,
                                onToggle = { openPhase = if (openPhase == i) -1 else i },
                                onChange = { segs[i] = it },
                                onDelete = {
                                    segs.removeAt(i)
                                    if (openPhase == i) openPhase = -1
                                },
                                onDuplicate = { segs.add(i + 1, seg.copy(name = seg.name + " (copy)")) },
                                last = i == segs.lastIndex,
                            )
                        }
                    }
                }
                CremaButton(
                    onClick = {
                        segs.add(
                            SegmentEdit(
                                name = "Phase ${segs.size + 1}",
                                mode = Pump.Pressure,
                                ramp = Transition.Smooth,
                                target = 9.0f,
                                time = 10f,
                                temp = brewTemp.toFloat(),
                                tempSensor = "coffee",
                            ),
                        )
                        openPhase = segs.lastIndex
                    },
                    variant = CremaButtonVariant.Tonal,
                    icon = "plus",
                    label = "Add phase",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── Tags & options ──────────────────────────────────────────────
            EdGroup("Tags & options") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Tags", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PhoneTagChips(tags)
                }
                EdRow("Pin to favourites", sub = "Shows in the Brew swap dropdown") {
                    CremaSwitch(pinned, { pinned = it })
                }
            }
        }
    }
}

/* ── Phase accordion row ────────────────────────────────────────────────── */

@Composable
private fun PhaseRow(
    index: Int,
    seg: SegmentEdit,
    open: Boolean,
    onToggle: () -> Unit,
    onChange: (SegmentEdit) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    last: Boolean,
) {
    val tel = CremaTheme.telemetry
    val isPressure = seg.isPressure
    val dotColor = if (isPressure) tel.pressure else tel.flow
    val tUnit = seg.targetUnit()
    val bounds = ProfileBounds.INSTANCE
    val summary = buildString {
        append(if (isPressure) "Pressure" else "Flow")
        append(fmt(" · %.1f %s · %.0fs", seg.target, tUnit, seg.time))
        seg.exit?.metric?.let { m ->
            append(" · exit ${m.string} ${if (seg.exit?.compare == Compare.Under) "<" else ">"} ${fmt("%.1f", seg.exit?.threshold ?: 0f)}")
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Column(Modifier.weight(1f)) {
            Text(
                seg.name.ifBlank { "Phase ${index + 1}" },
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 10.5.sp, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        PhIcon(
            "caret-down", sizeDp = 16,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.rotate(if (open) 180f else 0f),
        )
    }
    AnimatedVisibility(visible = open) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CremaTextField(
                value = seg.name,
                onValueChange = { onChange(seg.copy(name = it)) },
                label = "Phase name",
                placeholder = "e.g. Pre-infusion",
            )
            FieldRow("Type") {
                CremaSegmentedButton(
                    options = listOf(SegOption("pressure", "Pressure"), SegOption("flow", "Flow")),
                    value = (seg.mode ?: Pump.Pressure).string,
                    onChange = { onChange(seg.copy(mode = pumpFromWire(it))) },
                    compact = true,
                    modifier = Modifier.width(SegmentPillWidth),
                )
            }
            FieldRow("Transition") {
                CremaSegmentedButton(
                    options = listOf(SegOption("smooth", "Smooth"), SegOption("fast", "Fast")),
                    value = (seg.ramp ?: Transition.Smooth).string,
                    onChange = { onChange(seg.copy(ramp = transitionFromWire(it))) },
                    compact = true,
                    modifier = Modifier.width(SegmentPillWidth),
                )
            }
            FieldRow("Target") {
                CremaStepper(value = seg.target.toDouble(), unit = tUnit, step = 0.1, min = 0.0, max = if (isPressure) bounds.maxPressureBar.toDouble() else bounds.maxFlowMlPerS.toDouble(), fmt = { fmt("%.1f", it) }, style = CremaStepperStyle.BareCompact, onChange = {
                    onChange(seg.copy(target = it.toFloat()))
                })
            }
            FieldRow("Duration") {
                CremaStepper(value = seg.time.toDouble(), unit = "s", step = 1.0, min = 0.0, max = bounds.maxFrameSeconds.toDouble(), fmt = { fmt("%.0f", it) }, style = CremaStepperStyle.BareCompact, onChange = {
                    onChange(seg.copy(time = it.toFloat()))
                })
            }
            // Temperature + sensor.
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Temperature", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CremaSegmentedButton(
                        options = listOf(SegOption("coffee", "Coffee"), SegOption("water", "Water")),
                        value = seg.tempSensor ?: "coffee",
                        onChange = { onChange(seg.copy(tempSensor = it)) },
                        compact = true,
                        modifier = Modifier.width(SegmentPillWidth),
                    )
                    CremaStepper(value = (seg.temp ?: 93f).toDouble(), unit = "°", step = 0.5, min = 70.0, max = bounds.maxTemperatureC.toDouble(), fmt = { fmt("%.1f", it) }, style = CremaStepperStyle.BareCompact, onChange = {
                        onChange(seg.copy(temp = it.toFloat()))
                    })
                }
            }
            // Volume limit (optional).
            OptionalField(
                label = "Volume limit",
                sub = "Cap water dispensed this phase",
                on = seg.volume != null,
                onToggle = { onChange(seg.copy(volume = if (seg.volume == null) 50f else null)) },
            ) {
                CremaStepper(value = (seg.volume ?: 50f).toDouble(), unit = "ml", step = 5.0, min = 0.0, max = 500.0, fmt = { fmt("%.0f", it) }, style = CremaStepperStyle.BareCompact, onChange = {
                    onChange(seg.copy(volume = it.toFloat()))
                })
            }
            // Exit early (optional).
            OptionalField(
                label = "Exit early",
                sub = "Move on when the condition is met",
                on = seg.exit != null,
                onToggle = {
                    onChange(
                        seg.copy(
                            exit = if (seg.exit == null) {
                                SegmentExit(metric = if (isPressure) ExitMetric.Flow else ExitMetric.Pressure, compare = Compare.Over, threshold = 1.5f)
                            } else null,
                        ),
                    )
                },
            ) {
                val exit = seg.exit ?: SegmentExit(ExitMetric.Flow, Compare.Over, 1.5f)
                // Over/under renders as a tappable >/< INSIDE the threshold field (issue 50 —
                // matching the tablet + PWA), so the metric selector + value share one row.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    CremaSegmentedButton(
                        options = listOf(SegOption("pressure", "Pressure"), SegOption("flow", "Flow")),
                        value = (exit.metric ?: ExitMetric.Flow).string,
                        onChange = { onChange(seg.copy(exit = exit.copy(metric = exitMetricFromWire(it)))) },
                        compact = true,
                        modifier = Modifier.width(SegmentPillWidth),
                    )
                    CremaStepper(
                        value = (exit.threshold ?: 1.5f).toDouble(), unit = null, step = 0.1, min = 0.0,
                        max = if (exit.metric == ExitMetric.Pressure) bounds.maxPressureBar.toDouble() else bounds.maxFlowMlPerS.toDouble(),
                        fmt = { fmt("%.1f", it) }, style = CremaStepperStyle.BareCompact,
                        compareSymbol = if ((exit.compare ?: Compare.Over) == Compare.Over) ">" else "<",
                        onCompare = { onChange(seg.copy(exit = exit.copy(compare = if ((exit.compare ?: Compare.Over) == Compare.Over) Compare.Under else Compare.Over))) },
                        onChange = { onChange(seg.copy(exit = exit.copy(threshold = it.toFloat()))) },
                    )
                }
            }
            // Max limiter (optional) — caps the non-priority quantity.
            OptionalField(
                label = "Max ${seg.limiterUnit()} limit",
                sub = "Safety ceiling on the other channel",
                on = seg.limiter != null,
                onToggle = {
                    onChange(seg.copy(limiter = if (seg.limiter == null) SegmentLimiter(value = if (isPressure) 2.5f else 9f) else null))
                },
            ) {
                val lim = seg.limiter ?: SegmentLimiter()
                CremaStepper(value = lim.value.toDouble(), unit = seg.limiterUnit(), step = 0.1, min = 0.0, max = if (isPressure) bounds.maxFlowMlPerS.toDouble() else bounds.maxPressureBar.toDouble(), fmt = { fmt("%.1f", it) }, style = CremaStepperStyle.BareCompact, onChange = {
                    onChange(seg.copy(limiter = lim.copy(value = it.toFloat())))
                })
            }
            // Phase foot: delete / duplicate.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                CremaButton(onClick = onDelete, variant = CremaButtonVariant.Text, danger = true, icon = "trash", label = "Delete phase")
                CremaButton(onClick = onDuplicate, variant = CremaButtonVariant.Text, icon = "copy", label = "Duplicate")
            }
        }
    }
    if (!last) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/* ── Local form primitives ──────────────────────────────────────────────── */

@Composable
internal fun EdGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(title, Modifier.padding(start = 4.dp))
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
internal fun EdRow(
    title: String,
    sub: String? = null,
    dot: Boolean = false,
    dotOn: Boolean = false,
    onDot: (() -> Unit)? = null,
    control: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Optional enable dot for a 0-sentinel target (Max volume / Tank temp /
        // Pre-infuse phases): toggles the feature and greys the stepper rather
        // than relying on a "0 = off" subtitle.
        if (dot) CremaDotToggle(dotOn, { onDot?.invoke() })
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.alpha(if (dot && !dotOn) 0.4f else 1f)) { control() }
    }
}

@Composable
internal fun FieldRow(label: String, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        control()
    }
}

@Composable
internal fun OptionalField(
    label: String,
    sub: String,
    on: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Leading enable dot (was a trailing switch) + label — matches the
            // tablet/PWA optional-config affordance so both shells read the same.
            CremaDotToggle(on, onToggle)
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // Always show the gated control, greyed when off (was hidden) — so the
        // disabled state reads at a glance instead of vanishing.
        Row(Modifier.fillMaxWidth().alpha(if (on) 1f else 0.4f), horizontalArrangement = Arrangement.End) { content() }
    }
}

// (EdStepper removed — phone editors route through CremaStepper / BareCompact.)

/** Input-chip tag row + inline add (phone editors). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PhoneTagChips(tags: androidx.compose.runtime.snapshots.SnapshotStateList<String>) {
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.forEach { tag ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
            ) {
                Row(
                    Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(tag, style = MaterialTheme.typography.labelMedium)
                    PhIcon(
                        "x", sizeDp = 12,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clip(CircleShape).clickable { tags.remove(tag) },
                    )
                }
            }
        }
        if (adding) {
            androidx.compose.foundation.text.BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                    draft.trim().takeIf { it.isNotEmpty() && it !in tags }?.let { tags.add(it) }
                    draft = ""; adding = false
                }),
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .widthIn(min = 60.dp, max = 140.dp),
            )
        } else {
            Surface(
                onClick = { adding = true },
                shape = RoundedCornerShape(999.dp),
                color = androidx.compose.ui.graphics.Color.Transparent,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    PhIcon("plus", sizeDp = 12, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Add", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

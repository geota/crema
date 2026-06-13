package coffee.crema.ui.phone

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.profiles.SegmentEdit
import coffee.crema.profiles.SegmentExit
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
            addAll(
                profile.segments.map { s ->
                    SegmentEdit(
                        name = s.name,
                        mode = s.mode ?: "pressure",
                        ramp = s.ramp ?: "smooth",
                        target = s.target,
                        time = s.time,
                        temp = s.temp ?: profile.brewTemp,
                        tempSensor = s.tempSensor ?: "coffee",
                        volume = s.volumeLimitMl,
                        exit = s.exit,
                        limiter = s.limiter,
                    )
                },
            )
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
                EdRow("Dose") { EdStepper(dose, "g", 0.1, 5.0, 30.0, { "%.1f".format(it) }) { dose = it } }
                EdRow("Yield") { EdStepper(yieldG, "g", 0.5, 10.0, 120.0, { "%.0f".format(it) }) { yieldG = it } }
                EdRow("Ratio", sub = "Computed") {
                    Text(
                        formatRatio(dose, yieldG),
                        style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 18.sp, fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                EdRow("Brew temp") { EdStepper(brewTemp, "°C", 0.5, 80.0, 100.0, { "%.1f".format(it) }) { brewTemp = it } }
                EdRow("Max total volume", sub = "0 = no limit") { EdStepper(maxVol, "ml", 5.0, 0.0, 500.0, { "%.0f".format(it) }) { maxVol = it } }
                EdRow("Pre-infuse phases", sub = "Leading phases counted as pre-infusion") { EdStepper(preinfuse, null, 1.0, 0.0, segs.size.toDouble(), { "%.0f".format(it) }) { preinfuse = it } }
                EdRow("Tank temp", sub = "0 = unset") { EdStepper(tankTemp, "°C", 1.0, 0.0, 60.0, { "%.0f".format(it) }) { tankTemp = it } }
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
                                mode = "pressure",
                                ramp = "smooth",
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
    val isPressure = seg.mode != "flow"
    val dotColor = if (isPressure) tel.pressure else tel.flow
    val tUnit = if (isPressure) "bar" else "ml/s"
    val summary = buildString {
        append(if (isPressure) "Pressure" else "Flow")
        append(" · %.1f %s · %.0fs".format(seg.target, tUnit, seg.time))
        seg.exit?.metric?.let { m ->
            append(" · exit $m ${if (seg.exit?.compare == "under") "<" else ">"} ${"%.1f".format(seg.exit?.threshold ?: 0f)}")
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
                    value = seg.mode ?: "pressure",
                    onChange = { onChange(seg.copy(mode = it)) },
                )
            }
            FieldRow("Transition") {
                CremaSegmentedButton(
                    options = listOf(SegOption("smooth", "Smooth"), SegOption("fast", "Fast")),
                    value = seg.ramp ?: "smooth",
                    onChange = { onChange(seg.copy(ramp = it)) },
                )
            }
            FieldRow("Target") {
                EdStepper(seg.target.toDouble(), tUnit, 0.1, 0.0, 12.0, { "%.1f".format(it) }) {
                    onChange(seg.copy(target = it.toFloat()))
                }
            }
            FieldRow("Duration") {
                EdStepper(seg.time.toDouble(), "s", 1.0, 0.0, 127.0, { "%.0f".format(it) }) {
                    onChange(seg.copy(time = it.toFloat()))
                }
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
                    )
                    EdStepper((seg.temp ?: 93f).toDouble(), "°", 0.5, 70.0, 105.0, { "%.1f".format(it) }) {
                        onChange(seg.copy(temp = it.toFloat()))
                    }
                }
            }
            // Volume limit (optional).
            OptionalField(
                label = "Volume limit",
                sub = "Cap water dispensed this phase",
                on = seg.volume != null,
                onToggle = { onChange(seg.copy(volume = if (seg.volume == null) 50f else null)) },
            ) {
                EdStepper((seg.volume ?: 50f).toDouble(), "ml", 5.0, 0.0, 500.0, { "%.0f".format(it) }) {
                    onChange(seg.copy(volume = it.toFloat()))
                }
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
                                SegmentExit(metric = if (isPressure) "flow" else "pressure", compare = "over", threshold = 1.5f)
                            } else null,
                        ),
                    )
                },
            ) {
                val exit = seg.exit ?: SegmentExit("flow", "over", 1.5f)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        CremaSegmentedButton(
                            options = listOf(SegOption("pressure", "Pressure"), SegOption("flow", "Flow")),
                            value = exit.metric ?: "flow",
                            onChange = { onChange(seg.copy(exit = exit.copy(metric = it))) },
                        )
                        CremaSegmentedButton(
                            options = listOf(SegOption("over", ">"), SegOption("under", "<")),
                            value = exit.compare ?: "over",
                            onChange = { onChange(seg.copy(exit = exit.copy(compare = it))) },
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        EdStepper((exit.threshold ?: 1.5f).toDouble(), null, 0.1, 0.0, 12.0, { "%.1f".format(it) }) {
                            onChange(seg.copy(exit = exit.copy(threshold = it.toFloat())))
                        }
                    }
                }
            }
            // Max limiter (optional) — caps the non-priority quantity.
            OptionalField(
                label = "Max ${if (isPressure) "ml/s" else "bar"} limit",
                sub = "Safety ceiling on the other channel",
                on = seg.limiter != null,
                onToggle = {
                    onChange(seg.copy(limiter = if (seg.limiter == null) SegmentLimiter(value = if (isPressure) 2.5f else 9f) else null))
                },
            ) {
                val lim = seg.limiter ?: SegmentLimiter()
                EdStepper(lim.value.toDouble(), if (isPressure) "ml/s" else "bar", 0.1, 0.0, 12.0, { "%.1f".format(it) }) {
                    onChange(seg.copy(limiter = lim.copy(value = it.toFloat())))
                }
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
internal fun EdRow(title: String, sub: String? = null, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        control()
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            CremaSwitch(on, { onToggle() })
        }
        if (on) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { content() }
    }
}

/** Compact − value + stepper for the editors (36dp buttons, mono value). */
@Composable
internal fun EdStepper(
    value: Double,
    unit: String?,
    step: Double,
    min: Double,
    max: Double,
    fmt: (Double) -> String,
    onChange: (Double) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = { onChange(((value - step) * 100).let { kotlin.math.round(it) / 100 }.coerceAtLeast(min)) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(34.dp),
        ) { Box(contentAlignment = Alignment.Center) { PhIcon("minus", sizeDp = 14) } }
        Box(Modifier.widthIn(min = 62.dp), contentAlignment = Alignment.Center) {
            CremaValueUnit(fmt(value), unit, valueSize = 15.sp)
        }
        Surface(
            onClick = { onChange(((value + step) * 100).let { kotlin.math.round(it) / 100 }.coerceAtMost(max)) },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.size(34.dp),
        ) { Box(contentAlignment = Alignment.Center) { PhIcon("plus", sizeDp = 14) } }
    }
}

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

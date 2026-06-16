package coffee.crema.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import coffee.crema.profiles.ProfileBounds
import coffee.crema.profiles.SegmentEdit
import coffee.crema.profiles.SegmentExit
import coffee.crema.ui.formatRatio
import coffee.crema.profiles.SegmentLimiter
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaStepperStyle
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaOptionalHeader
import coffee.crema.ui.components.CremaSplitLabel
import coffee.crema.ui.components.SplitOption
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import coffee.crema.ui.theme.JetBrainsMono
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SegOption

/*
 * Profile editor (the pushed `profile-edit` route) — M3, single full-width page.
 *
 * One vertical scroll of full-width numbered sections — 1 Details (name, notes,
 * pin), 2 Roast & tags, 3 Targets, 4 Limits (max volume / preinfuse steps / tank
 * temp), 5 Pressure profile (curve + segments). Save patches only these fields
 * into the profile's complete JSON (vm.saveProfile → patchCremaProfileJson) so
 * every wire-relevant field round-trips untouched to the DE1. Each segment is a
 * single horizontal row; the curve is a hand-rolled Canvas drag editor.
 */
private val TYPE_OPTIONS = listOf(SegOption("pressure", "Pressure"), SegOption("flow", "Flow"))
private val RAMP_OPTIONS = listOf(SegOption("smooth", "Smooth"), SegOption("fast", "Fast"))

@Composable
fun ProfileEditScreen(vm: MainViewModel, onBack: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // A new / duplicated profile lives in `draftProfile` (not yet in `profiles`);
    // an existing custom is found in the merged list. Both keyed by editingProfileId.
    val profile = ui.profiles.firstOrNull { it.id == ui.editingProfileId }
        ?: ui.draftProfile?.takeIf { it.id == ui.editingProfileId }

    // Any back-out discards an unsaved new / duplicated draft.
    val back: () -> Unit = { vm.cancelProfileEdit(); onBack() }
    BackHandler(onBack = back)

    if (profile == null) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CremaIconButton(icon = "arrow-left", onClick = back)
            Text(
                "No profile selected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val isNew = ui.draftProfile?.id == profile.id

    // Field state seeded from the profile; re-seeds if the edited profile changes.
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
    // Per-segment editable state (positional with the profile's segments).
    val segs = remember(profile.id) {
        mutableStateListOf<SegmentEdit>().apply {
            addAll(profile.segments.map { it.toEdit(profile.brewTemp) })
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CremaIconButton(icon = "arrow-left", onClick = back)
            Eyebrow(
                if (isNew) "Profiles › New profile" else "Profiles › Edit profile",
                Modifier.weight(1f),
            )
            CremaButton(onClick = back, variant = CremaButtonVariant.Text, label = "Discard changes")
            CremaButton(
                onClick = { vm.duplicateProfile(profile.id) },
                variant = CremaButtonVariant.Tonal,
                icon = "copy",
                label = "Duplicate",
            )
            CremaButton(
                onClick = {
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
                icon = "check",
                label = "Save profile",
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // One scroll of full-width numbered sections.
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // 1 — Details: the name (title), notes, and pin.
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                    Text("1", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.padding(top = 8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Title + favorite star (the pin toggle, copper when pinned).
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            BasicTextField(
                                value = name,
                                onValueChange = { name = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.weight(1f),
                                decorationBox = { inner ->
                                    Box {
                                        if (name.isEmpty()) Text("Profile name", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        inner()
                                    }
                                },
                            )
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).clickable { pinned = !pinned },
                                contentAlignment = Alignment.Center,
                            ) {
                                PhIcon(if (pinned) "star-fill" else "star", sizeDp = 24, tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // Notes — full width, compact height.
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow("Notes")
                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                placeholder = { Text("Tasting notes, recipe intent…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                textStyle = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 3,
                            )
                        }
                        // Author (web pe-author) shares a line with Tags.
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Eyebrow("Author")
                                OutlinedTextField(
                                    value = author,
                                    onValueChange = { author = it },
                                    placeholder = { Text("Who designed this profile?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                    textStyle = MaterialTheme.typography.bodyMedium,
                                    singleLine = true,
                                    modifier = Modifier.width(320.dp),
                                )
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Eyebrow("Tags")
                                TagChips(tags)
                            }
                        }
                        // Roast + Beverage type share a full-width line.
                        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Top) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Eyebrow("Roast")
                                RoastChips(roast) { roast = it }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Eyebrow("Beverage type")
                                BeverageChips(beverage) { beverage = it }
                            }
                        }
                    }
                }
            }

            // 2 — Target + Limits: two grouped cards (Target | Limit), each a faint
            // copper frame with its own eyebrow.
            NumberedSection("2", "Target + Limits", "Recipe targets & optional caps") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GroupCard("Target", 4f) {
                        LabeledStepper("Brew temp", brewTemp, "°C", Modifier.weight(1f), 0.5, 20.0, ProfileBounds.INSTANCE.maxTemperatureC.toDouble(), { brewTemp = it })
                        LabeledStepper("Dose", dose, "g", Modifier.weight(1f), 0.1, 1.0, 60.0, { dose = it })
                        LabeledStepper("Yield", yieldG, "g", Modifier.weight(1f), 0.5, 1.0, 200.0, { yieldG = it })
                        LabeledRatio(dose, yieldG, Modifier.weight(1f))
                    }
                    GroupCard("Limit", 3f) {
                        LimitTile("Max volume", maxVol, "ml", Modifier.weight(1f), maxVol > 0.0, { maxVol = if (maxVol > 0.0) 0.0 else 50.0 }, 10.0, ProfileBounds.INSTANCE.minTotalVolumeMl.toDouble(), ProfileBounds.INSTANCE.maxTotalVolumeMl.toDouble(), { maxVol = it })
                        LimitTile("Preinfuse steps", preinfuse, null, Modifier.weight(1f), preinfuse > 0.0, { preinfuse = if (preinfuse > 0.0) 0.0 else 1.0 }, 1.0, 0.0, 10.0, { preinfuse = it })
                        LimitTile("Tank temp", tankTemp, "°C", Modifier.weight(1f), tankTemp > 0.0, { tankTemp = if (tankTemp > 0.0) 0.0 else 92.0 }, 1.0, 0.0, 95.0, { tankTemp = it })
                    }
                }
            }

            // 3 — Pressure profile: the curve, then the segments. Reset rides the
            // title line; the total time sits in the graph's bottom-right (copper).
            val totalS = segs.sumOf { it.time.toDouble() }
            NumberedSection(
                "3", "Pressure profile",
                // Web pe-chart sub: "N segments · Xs total · drag the dots or edit the rows below".
                "${segs.size} segments · ${"%.0f".format(totalS)}s total · drag the dots or edit the segments below",
                trailing = {
                    CremaButton(
                        onClick = {
                            segs.clear()
                            segs.addAll(profile.segments.map { it.toEdit(profile.brewTemp) })
                        },
                        variant = CremaButtonVariant.Text,
                        icon = "arrow-counter-clockwise",
                        label = "Reset",
                    )
                },
            ) {
                Box(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp),
                ) {
                    ProfileCurveChart(
                        targets = segs.map { it.target },
                        times = segs.map { it.time },
                        modes = segs.map { it.mode },
                        ramps = segs.map { it.ramp },
                        temps = segs.map { it.temp },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        onSegmentEdit = { i, target, time ->
                            if (i in segs.indices) segs[i] = segs[i].copy(target = target, time = time)
                        },
                    )
                    Text(
                        "${segs.sumOf { it.time.toDouble() }.toInt()} s",
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 6.dp),
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 15.sp, fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("Segments", Modifier.weight(1f))
                    CremaButton(
                        onClick = {
                            val last = segs.lastOrNull()
                            segs.add(
                                SegmentEdit(
                                    name = "New segment",
                                    mode = last?.mode ?: "pressure",
                                    ramp = last?.ramp ?: "smooth",
                                    target = last?.target ?: 6f,
                                    time = 6f,
                                    temp = last?.temp ?: brewTemp.toFloat(),
                                    tempSensor = last?.tempSensor ?: "coffee",
                                    volume = last?.volume,
                                ),
                            )
                        },
                        icon = "plus",
                        label = "Add",
                    )
                }
                if (segs.isEmpty()) {
                    Text("This profile has no segments.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                segs.forEachIndexed { i, seg ->
                    SegmentRowFull(
                        index = i,
                        seg = seg,
                        deletable = segs.size > 1,
                        onEdit = { if (i in segs.indices) segs[i] = it },
                        onDelete = { if (i in segs.indices) segs.removeAt(i) },
                    )
                }
            }
        }
    }
}

// ── A full segment as ONE horizontal row (web .pe-seg). The name column carries
// the number (top-left), name, and labelled Type / Ramp pills; the field cells
// bottom-pad 6dp so every stepper bottom-aligns with the Max+Tolerance card. ───
@Composable
private fun SegmentRowFull(
    index: Int,
    seg: SegmentEdit,
    deletable: Boolean,
    onEdit: (SegmentEdit) -> Unit,
    onDelete: () -> Unit,
) {
    val isFlow = seg.mode == "flow"
    val exitOn = seg.exit != null
    val exView = seg.exit ?: SegmentExit("flow", "over", 4f)
    val limOn = seg.limiter != null
    val lmView = seg.limiter ?: SegmentLimiter(6f, 0.6f)
    val segVolOn = (seg.volume ?: 0f) > 0f
    val bounds = ProfileBounds.INSTANCE
    CremaCard(Modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Name column: number + name (top), then labelled Type / Ramp pills.
            Column(Modifier.width(178.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) }
                    BasicTextField(
                        value = seg.name,
                        onValueChange = { onEdit(seg.copy(name = it)) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            Box {
                                if (seg.name.isEmpty()) Text("Segment ${index + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                inner()
                            }
                        },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("Type", Modifier.width(34.dp))
                    Box(Modifier.weight(1f)) { MiniSegmented(TYPE_OPTIONS, seg.mode ?: "pressure") { onEdit(seg.copy(mode = it)) } }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("Ramp", Modifier.width(34.dp))
                    Box(Modifier.weight(1f)) { MiniSegmented(RAMP_OPTIONS, seg.ramp ?: "smooth") { onEdit(seg.copy(ramp = it)) } }
                }
            }

            SegCell(1f) {
                Eyebrow("Target")
                CremaStepper(value = seg.target.toDouble(), unit = if (isFlow) "ml/s" else "bar", step = 0.1, min = 0.0, max = if (isFlow) bounds.maxFlowMlPerS.toDouble() else bounds.maxPressureBar.toDouble(), onChange = { onEdit(seg.copy(target = it.toFloat())) }, style = CremaStepperStyle.BoxedDense)
            }
            SegCell(1f) {
                Eyebrow("Time")
                CremaStepper(value = seg.time.toDouble(), unit = "s", step = 0.5, min = 0.0, max = bounds.maxFrameSeconds.toDouble(), onChange = { onEdit(seg.copy(time = it.toFloat())) }, style = CremaStepperStyle.BoxedDense)
            }
            SegCell(1.15f) {
                CremaSplitLabel(prefix = "Temp", options = listOf(SplitOption("coffee", "Coffee"), SplitOption("water", "Water")), value = seg.tempSensor ?: "coffee", onChange = { onEdit(seg.copy(tempSensor = it)) })
                CremaStepper(value = (seg.temp ?: 93f).toDouble(), unit = "°C", step = 0.5, min = 20.0, max = bounds.maxTemperatureC.toDouble(), onChange = { onEdit(seg.copy(temp = it.toFloat())) }, style = CremaStepperStyle.BoxedDense)
            }
            SegCell(1f) {
                CremaOptionalHeader("Volume", segVolOn, { onEdit(seg.copy(volume = if (segVolOn) null else 50f)) })
                CremaStepper(value = (seg.volume ?: 0f).toDouble(), unit = "ml", step = 5.0, min = 0.0, max = 500.0, onChange = { onEdit(seg.copy(volume = it.toFloat().takeIf { v -> v > 0f })) }, fmt = { "%.0f".format(it) }, style = CremaStepperStyle.BoxedDense, enabled = segVolOn)
            }
            SegCell(1.5f) {
                CremaSplitLabel(
                    prefix = "Exit",
                    dot = true,
                    dotOn = exitOn,
                    onDot = { onEdit(seg.copy(exit = if (exitOn) null else SegmentExit("flow", "over", 4f))) },
                    options = listOf(SplitOption("pressure", "Pressure"), SplitOption("flow", "Flow")),
                    value = exView.metric ?: "flow",
                    onChange = { m -> if (exitOn) onEdit(seg.copy(exit = seg.exit?.copy(metric = m))) },
                )
                CremaStepper(
                    value = (exView.threshold ?: 4f).toDouble(),
                    unit = if (exView.metric == "pressure") "bar" else "ml/s",
                    step = 0.1, min = 0.0, max = if (exView.metric == "pressure") bounds.maxPressureBar.toDouble() else bounds.maxFlowMlPerS.toDouble(),
                    onChange = { v -> if (exitOn) onEdit(seg.copy(exit = seg.exit?.copy(threshold = v.toFloat()))) },
                    style = CremaStepperStyle.BoxedDense,
                    enabled = exitOn,
                    compareSymbol = if ((exView.compare ?: "over") == "over") ">" else "<",
                    onCompare = { if (exitOn) onEdit(seg.copy(exit = seg.exit?.copy(compare = if ((exView.compare ?: "over") == "over") "under" else "over"))) },
                )
            }
            // Max + Tolerance — a paired, gated group in a faint copper card.
            SegCellGroup(2f) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CremaOptionalHeader("Max", limOn, { onEdit(seg.copy(limiter = if (limOn) null else SegmentLimiter(6f, 0.6f))) })
                    CremaStepper(value = lmView.value.toDouble(), unit = if (seg.mode == "flow") "bar" else "ml/s", step = 0.1, min = 0.0, max = if (seg.mode == "flow") bounds.maxPressureBar.toDouble() else bounds.maxFlowMlPerS.toDouble(), onChange = { v -> if (limOn) onEdit(seg.copy(limiter = seg.limiter?.copy(value = v.toFloat()))) }, style = CremaStepperStyle.BoxedDense, enabled = limOn)
                }
                // Tolerance is gated by the Max dot — fade the whole cell (eyebrow
                // included) when Max is off.
                Column(Modifier.weight(1f).alpha(if (limOn) 1f else 0.4f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow("Tolerance")
                    CremaStepper(value = lmView.range.toDouble(), unit = null, step = 0.1, min = 0.0, max = 6.0, onChange = { v -> if (limOn) onEdit(seg.copy(limiter = seg.limiter?.copy(range = v.toFloat()))) }, style = CremaStepperStyle.BoxedDense)
                }
            }

            // Delete — subtle, faded (PWA .pe-seg-del).
            if (deletable) {
                Box(
                    Modifier.align(Alignment.CenterVertically).size(26.dp).clip(CircleShape).clickable(onClick = onDelete),
                    contentAlignment = Alignment.Center,
                ) { PhIcon("trash", sizeDp = 13, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)) }
            }
        }
    }
}

// A plain field cell: header + stepper, bottom-aligned within the row (6dp bottom
// pad matches the grouped frame's inset so all steppers sit on one line).
@Composable
private fun RowScope.SegCell(weight: Float, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(weight).padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

// A paired group of cells in a faint copper-tinted card (PWA .pe-seg-cell.is-grouped).
@Composable
private fun RowScope.SegCellGroup(weight: Float, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.weight(weight)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

/** Map a stored ProfileSegment to the editor's SegmentEdit (fills sensible defaults). */
private fun coffee.crema.profiles.ProfileSegment.toEdit(brewTemp: Float) = SegmentEdit(
    name = name,
    mode = mode ?: "pressure",
    ramp = ramp ?: "smooth",
    target = target,
    time = time,
    temp = temp ?: brewTemp,
    tempSensor = tempSensor ?: "coffee",
    volume = volumeLimitMl,
    exit = exit,
    limiter = limiter,
)

// ── A numbered settings section: hairline rule, mono number + serif title + sub,
// then body (matches the bean-creation BeBlock). ──────────────────────────────
@Composable
private fun NumberedSection(n: String, title: String, sub: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // Number · title · sub on one baseline (sub to the RIGHT of the title); an
        // optional trailing action (e.g. Reset) right-justifies on the same line.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(n, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.alignByBaseline())
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.alignByBaseline())
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alignByBaseline())
            }
            if (trailing != null) trailing()
        }
        content()
    }
}

// ── A labelled group card (Target / Limit) — a faint copper frame with an
// eyebrow header over a row of tiles (the PWA .pe-seg-cell.is-grouped look). ───
@Composable
private fun RowScope.GroupCard(label: String, weight: Float, content: @Composable RowScope.() -> Unit) {
    Column(
        Modifier.weight(weight)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Eyebrow(label)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

// ── Targets/Limits tiles — the eyebrow (or dot header) sits ABOVE a compact
// stepper box. ────────────────────────────────────────────────────────────────
@Composable
private fun LabeledStepper(label: String, value: Double, unit: String?, modifier: Modifier, step: Double, min: Double, max: Double, onChange: (Double) -> Unit, fmt: (Double) -> String = { String.format("%.1f", it) }) {
    CremaStepper(label = label, value = value, unit = unit, onChange = onChange, step = step, min = min, max = max, fmt = fmt, modifier = modifier, style = CremaStepperStyle.Boxed)
}

@Composable
private fun LimitTile(label: String, value: Double, unit: String?, modifier: Modifier, on: Boolean, onToggle: () -> Unit, step: Double, min: Double, max: Double, onChange: (Double) -> Unit) {
    CremaStepper(
        value = value, unit = unit, onChange = onChange, step = step, min = min, max = max,
        fmt = { String.format("%.0f", it) }, modifier = modifier, style = CremaStepperStyle.Boxed,
        enabled = on, header = { CremaOptionalHeader(label, on, onToggle) },
    )
}

@Composable
private fun LabeledRatio(dose: Double, yieldG: Double, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Eyebrow("Ratio", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
            Text("Computed", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.4.sp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 9.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(formatRatio(dose, yieldG), style = CremaTheme.readout.readoutSm.copy(fontSize = 18.sp), color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

// ── Deselectable roast chips — Light / Medium / Dark; tapping the active one
// clears to null (no explicit "None"). ───────────────────────────────────────
@Composable
private fun RoastChips(value: String?, onChange: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("light" to "Light", "medium" to "Medium", "dark" to "Dark").forEach { (id, label) ->
            val active = value == id
            Box(
                Modifier
                    .width(84.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onChange(if (active) null else id) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Beverage-type chips (web beverageTypeOptions); lowercase wire values match
// the web/core `beverageType` strings. NOT deselectable: the Rust
// CremaProfile.beverage_type is a non-optional enum (no "none" on the wire),
// so a cleared chip would silently revert to the stored value on save.
@Composable
private fun BeverageChips(value: String?, onChange: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            "espresso" to "Espresso",
            "pourover" to "Pourover",
            "manual" to "Manual",
            "cleaning" to "Cleaning",
            "calibrate" to "Calibrate",
        ).forEach { (id, label) ->
            val active = value == id
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onChange(id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Small segmented pill (Type / Ramp), for the segment row's name column. ────
@Composable
private fun MiniSegmented(options: List<SegOption>, value: String, onChange: (String) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(7.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { opt ->
            val active = value == opt.id
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { onChange(opt.id) }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(opt.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/**
 * The tag editor (PWA TagInput) — copper tag chips each with an ✕ to remove, then
 * a dashed faint "+ Add tag" chip that swaps to a copper-bordered inline input on
 * tap (autofocus, "New tag…"); Enter or blur commits and the chip returns.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: SnapshotStateList<String>) {
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var wasFocused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val copper = MaterialTheme.colorScheme.primary
    val tint = MaterialTheme.colorScheme.onSurface

    fun commit() {
        val t = draft.trim()
        if (t.isNotEmpty() && tags.none { it.equals(t, ignoreCase = true) }) tags.add(t)
        draft = ""
        adding = false
        wasFocused = false
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tags.toList().forEach { tag ->
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(copper.copy(alpha = 0.10f)).border(1.dp, copper.copy(alpha = 0.35f), RoundedCornerShape(999.dp)).padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(tag, style = MaterialTheme.typography.labelLarge, color = copper)
                Box(
                    Modifier.size(18.dp).clip(CircleShape).clickable { tags.remove(tag) },
                    contentAlignment = Alignment.Center,
                ) { PhIcon("x", sizeDp = 11, tint = copper.copy(alpha = 0.7f)) }
            }
        }
        if (adding) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.labelLarge.copy(color = tint),
                cursorBrush = SolidColor(copper),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit() }),
                modifier = Modifier
                    .width(132.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(tint.copy(alpha = 0.05f))
                    .border(1.dp, copper, RoundedCornerShape(999.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .focusRequester(focus)
                    .onFocusChanged { fs ->
                        if (fs.isFocused) wasFocused = true
                        else if (wasFocused) commit()
                    },
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (draft.isEmpty()) Text("New tag…", style = MaterialTheme.typography.labelLarge, color = tint.copy(alpha = 0.4f))
                        inner()
                    }
                },
            )
            LaunchedEffect(Unit) { focus.requestFocus() }
        } else {
            Row(
                Modifier.dashedBorder(tint.copy(alpha = 0.35f)).clip(RoundedCornerShape(999.dp)).clickable { adding = true }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                PhIcon("plus", sizeDp = 13, tint = tint.copy(alpha = 0.55f))
                Text("Add tag", style = MaterialTheme.typography.labelLarge, color = tint.copy(alpha = 0.55f))
            }
        }
    }
}

/** A dashed rounded-square outline (PWA .pe-tag-add, squared to match Roast chips). */
private fun Modifier.dashedBorder(color: Color) = this.drawBehind {
    val sw = 1.dp.toPx()
    val r = size.height / 2f // pill — follows the chip's rounded shape
    drawRoundRect(
        color = color,
        topLeft = Offset(sw / 2f, sw / 2f),
        size = Size(size.width - sw, size.height - sw),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = sw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f), 0f)),
    )
}

// (EditStepper / StepperBox / EditStepBtn removed — routed through CremaStepper.)

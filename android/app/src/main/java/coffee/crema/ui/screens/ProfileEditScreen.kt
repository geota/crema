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
import androidx.compose.material3.InputChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import coffee.crema.profiles.SegmentEdit
import coffee.crema.profiles.SegmentExit
import coffee.crema.profiles.SegmentLimiter
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.CremaOptionalHeader
import coffee.crema.ui.components.CremaSplitLabel
import coffee.crema.ui.components.SplitOption
import androidx.compose.ui.draw.alpha
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
 * Edits the profile opened via MainViewModel.startEditProfile (an existing custom)
 * or startNewProfile / duplicateProfile (a draft). Save patches only these fields
 * into the profile's complete JSON (vm.saveProfile → patchCremaProfileJson), so
 * every wire-relevant field round-trips untouched to the DE1.
 *
 * Layout: one vertical scroll of full-width numbered sections — 1 Details,
 * 2 Targets, 3 Limits, 4 Pressure profile (the curve + the segments). Each segment
 * is a single horizontal row (web .pe-seg). The curve is a hand-rolled Canvas drag
 * editor (ProfileCurveChart).
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
    var dose by remember(profile.id) { mutableStateOf(profile.dose.toDouble()) }
    var yieldG by remember(profile.id) { mutableStateOf(profile.yieldOut.toDouble()) }
    var brewTemp by remember(profile.id) { mutableStateOf(profile.brewTemp.toDouble()) }
    var maxVol by remember(profile.id) { mutableStateOf(profile.maxTotalVolumeMl.toDouble()) }
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
                        dose = dose.toFloat(),
                        yieldOut = yieldG.toFloat(),
                        brewTemp = brewTemp.toFloat(),
                        maxTotalVolumeMl = maxVol.toInt(),
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
            // Serif name H1.
            BasicTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.headlineMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (name.isEmpty()) Text("Profile name", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                },
            )

            // 1 — Details. Notes on the left, roast / tags / pin on the right.
            NumberedSection("1", "Details", "Roast, tags & tasting notes") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("Notes")
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            placeholder = { Text("Tasting notes, recipe intent…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 5,
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow("Roast")
                            RoastChips(roast) { roast = it }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Eyebrow("Tags")
                            TagChips(tags)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Pin to favorites strip in Quick Controls",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            CremaSwitch(checked = pinned, onCheckedChange = { pinned = it })
                        }
                    }
                }
            }

            // 2 — Targets. Dose · Yield · Brew temp · Ratio, four-up.
            NumberedSection("2", "Targets", "Dose, yield & temperature") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TargetTile("Dose", String.format("%.1f", dose), "g", { dose = (dose - 0.1).coerceAtLeast(1.0) }, { dose = (dose + 0.1).coerceAtMost(60.0) }, Modifier.weight(1f))
                    TargetTile("Yield", String.format("%.1f", yieldG), "g", { yieldG = (yieldG - 0.5).coerceAtLeast(1.0) }, { yieldG = (yieldG + 0.5).coerceAtMost(200.0) }, Modifier.weight(1f))
                    TargetTile("Brew temp", String.format("%.1f", brewTemp), "°C", { brewTemp = (brewTemp - 0.5).coerceAtLeast(20.0) }, { brewTemp = (brewTemp + 0.5).coerceAtMost(105.0) }, Modifier.weight(1f))
                    RatioTile(if (dose > 0.0) yieldG / dose else 0.0, Modifier.weight(1f))
                }
            }

            // 3 — Limits. The optional whole-shot volume cap (quarter width, aligned
            // with the Targets tiles).
            NumberedSection("3", "Limits", "Optional whole-shot volume cap") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(
                        Modifier.weight(1f).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val volOn = maxVol > 0.0
                        CremaOptionalHeader("Max volume", volOn, { maxVol = if (volOn) 0.0 else 50.0 })
                        Row(
                            Modifier.fillMaxWidth().alpha(if (volOn) 1f else 0.4f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            EditStepBtn("minus", { maxVol = (maxVol - 10).coerceAtLeast(0.0) })
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(String.format("%.0f", maxVol), style = CremaTheme.readout.readoutSm.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                                Text("ml", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
                            }
                            EditStepBtn("plus", { maxVol = (maxVol + 10).coerceAtMost(1023.0) })
                        }
                    }
                    Spacer(Modifier.weight(3f))
                }
            }

            // 4 — Pressure profile: the curve, then the segments.
            NumberedSection("4", "Pressure profile", "Drag the dots or edit the segments below") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${segs.size} segments · ${segs.sumOf { it.time.toDouble() }.toInt()}s total",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CremaButton(
                        onClick = {
                            segs.clear()
                            segs.addAll(profile.segments.map { it.toEdit(profile.brewTemp) })
                        },
                        variant = CremaButtonVariant.Text,
                        icon = "arrow-counter-clockwise",
                        label = "Reset",
                    )
                }
                Box(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp),
                ) {
                    ProfileCurveChart(
                        targets = segs.map { it.target },
                        times = segs.map { it.time },
                        modes = segs.map { it.mode },
                        modifier = Modifier.fillMaxWidth().height(300.dp),
                        onSegmentEdit = { i, target, time ->
                            if (i in segs.indices) segs[i] = segs[i].copy(target = target, time = time)
                        },
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

// ── A full segment as ONE horizontal row (web .pe-seg): number · name+mode ·
// Target · Time · Temp · Volume · Exit · Max+Tolerance · delete. Cells bottom-pad
// 6dp so every stepper bottom-aligns with the grouped Max+Tolerance frame. ─────
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
    CremaCard(Modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Number — vertically centred on the row.
            Box(
                Modifier.align(Alignment.CenterVertically).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer) }

            // Name + stacked Type / Ramp mini-pills (the tallest cell — sets row height).
            Column(Modifier.width(150.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                BasicTextField(
                    value = seg.name,
                    onValueChange = { onEdit(seg.copy(name = it)) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (seg.name.isEmpty()) Text("Segment ${index + 1}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            inner()
                        }
                    },
                )
                MiniSegmented(TYPE_OPTIONS, seg.mode ?: "pressure") { onEdit(seg.copy(mode = it)) }
                MiniSegmented(RAMP_OPTIONS, seg.ramp ?: "smooth") { onEdit(seg.copy(ramp = it)) }
            }

            SegCell(1f) {
                Eyebrow("Target")
                EditStepper(seg.target.toDouble(), if (isFlow) "ml/s" else "bar", 0.1, 0.0, if (isFlow) 10.0 else 12.0, { onEdit(seg.copy(target = it.toFloat())) })
            }
            SegCell(1f) {
                Eyebrow("Time")
                EditStepper(seg.time.toDouble(), "s", 0.5, 0.0, 120.0, { onEdit(seg.copy(time = it.toFloat())) })
            }
            SegCell(1.15f) {
                CremaSplitLabel(prefix = "Temp", options = listOf(SplitOption("coffee", "Coffee"), SplitOption("water", "Water")), value = seg.tempSensor ?: "coffee", onChange = { onEdit(seg.copy(tempSensor = it)) })
                EditStepper((seg.temp ?: 93f).toDouble(), "°C", 0.5, 20.0, 105.0, { onEdit(seg.copy(temp = it.toFloat())) })
            }
            SegCell(1f) {
                CremaOptionalHeader("Volume", segVolOn, { onEdit(seg.copy(volume = if (segVolOn) null else 50f)) })
                EditStepper((seg.volume ?: 0f).toDouble(), "ml", 5.0, 0.0, 500.0, { onEdit(seg.copy(volume = it.toFloat().takeIf { v -> v > 0f })) }, modifier = Modifier.alpha(if (segVolOn) 1f else 0.4f), fmt = { "%.0f".format(it) })
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
                EditStepper(
                    (exView.threshold ?: 4f).toDouble(),
                    if (exView.metric == "pressure") "bar" else "ml/s",
                    0.1, 0.0, 12.0,
                    { v -> if (exitOn) onEdit(seg.copy(exit = seg.exit?.copy(threshold = v.toFloat()))) },
                    modifier = Modifier.alpha(if (exitOn) 1f else 0.4f),
                    compareSymbol = if ((exView.compare ?: "over") == "over") ">" else "<",
                    onCompare = { if (exitOn) onEdit(seg.copy(exit = seg.exit?.copy(compare = if ((exView.compare ?: "over") == "over") "under" else "over"))) },
                )
            }
            // Max + Tolerance — a paired, gated group in a faint copper card.
            SegCellGroup(2f) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CremaOptionalHeader("Max", limOn, { onEdit(seg.copy(limiter = if (limOn) null else SegmentLimiter(6f, 0.6f))) })
                    EditStepper(lmView.value.toDouble(), if (seg.mode == "flow") "bar" else "ml/s", 0.1, 0.0, 12.0, { v -> if (limOn) onEdit(seg.copy(limiter = seg.limiter?.copy(value = v.toFloat()))) }, modifier = Modifier.alpha(if (limOn) 1f else 0.4f))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Eyebrow("Tolerance")
                    EditStepper(lmView.range.toDouble(), null, 0.1, 0.0, 6.0, { v -> if (limOn) onEdit(seg.copy(limiter = seg.limiter?.copy(range = v.toFloat()))) }, modifier = Modifier.alpha(if (limOn) 1f else 0.4f))
                }
            }

            // Delete.
            if (deletable) {
                Box(Modifier.align(Alignment.CenterVertically)) {
                    CremaIconButton(icon = "trash", onClick = onDelete)
                }
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
private fun NumberedSection(n: String, title: String, sub: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(n, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMono), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        content()
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onChange(if (active) null else id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
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
 * The tag editor — input chips (tap to remove) over an "Add tag" field that
 * commits on the keyboard's Done action. Mirrors the web `TagInput`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(tags: SnapshotStateList<String>) {
    var input by remember { mutableStateOf("") }
    if (tags.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tags.toList().forEach { tag ->
                InputChip(
                    selected = false,
                    onClick = { tags.remove(tag) },
                    label = { Text(tag) },
                    trailingIcon = { Text("✕", style = MaterialTheme.typography.labelMedium) },
                )
            }
        }
    }
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        label = { Text("Add tag") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                val t = input.trim()
                if (t.isNotEmpty() && tags.none { it.equals(t, ignoreCase = true) }) tags.add(t)
                input = ""
            },
        ),
    )
}

// ── Compact stepper — a filled −/value/+ bar (PWA QuickStepper). 28dp buttons,
// 15sp value, and an optional inline >/< compare symbol rendered INSIDE the value
// box (PWA .pe-seg-cmp), tappable to flip. ────────────────────────────────────
@Composable
private fun EditStepper(
    value: Double,
    unit: String?,
    step: Double,
    min: Double,
    max: Double,
    onChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    fmt: (Double) -> String = { String.format("%.1f", it) },
    compareSymbol: String? = null,
    onCompare: (() -> Unit)? = null,
) {
    Row(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditStepBtn("minus", { onChange((value - step).coerceIn(min, max)) }, size = 28)
        Row(Modifier.weight(1f).padding(horizontal = 1.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            if (compareSymbol != null) {
                Text(
                    compareSymbol,
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = (onCompare?.let { Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = it) } ?: Modifier).padding(end = 3.dp),
                )
            }
            Text(fmt(value), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 15.sp, fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
            if (unit != null) Text(unit, style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 1.dp, bottom = 2.dp))
        }
        EditStepBtn("plus", { onChange((value + step).coerceIn(min, max)) }, size = 28)
    }
}

// ── Target tile — compact metric tile with 32dp steppers (editor Targets) ────
@Composable
private fun TargetTile(label: String, value: String, unit: String?, onMinus: () -> Unit, onPlus: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow(label)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            EditStepBtn("minus", onMinus)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = CremaTheme.readout.readoutSm.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (unit != null) Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 3.dp))
            }
            EditStepBtn("plus", onPlus)
        }
    }
}

// Copper Ratio tile — computed, no stepper.
@Composable
private fun RatioTile(ratio: Double, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow("Ratio", color = MaterialTheme.colorScheme.primary)
        Text("1:%.2f".format(ratio), style = CremaTheme.readout.readoutSm.copy(fontSize = 22.sp), color = MaterialTheme.colorScheme.primary, maxLines = 1)
    }
}

@Composable
private fun EditStepBtn(icon: String, onClick: () -> Unit, size: Int = 32) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(size.dp)) {
        Box(contentAlignment = Alignment.Center) { PhIcon(icon, sizeDp = if (size <= 28) 12 else 14) }
    }
}

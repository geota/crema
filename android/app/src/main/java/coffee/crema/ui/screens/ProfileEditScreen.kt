package coffee.crema.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import coffee.crema.profiles.SegmentEdit
import coffee.crema.profiles.SegmentExit
import coffee.crema.profiles.SegmentLimiter
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.CremaDotToggle
import coffee.crema.ui.components.CremaOptionalHeader
import coffee.crema.ui.components.CremaSplitLabel
import coffee.crema.ui.components.SplitOption
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import coffee.crema.ui.theme.JetBrainsMono
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SegOption

/*
 * Profile editor (the pushed `profile-edit` route) — M3, v1 NON-curve.
 *
 * Edits the profile opened via MainViewModel.startEditProfile (an existing custom)
 * or startNewProfile / duplicateProfile (a draft): metadata (name, roast, tags,
 * pin), recipe targets (dose / yield / brew-temp / max-volume), and each existing
 * segment's target + time. Save patches only these fields into the profile's
 * complete JSON (vm.saveProfile → patchCremaProfileJson), so every wire-relevant
 * field round-trips untouched to the DE1; the editor never adds / removes segments.
 *
 * The curve is a hand-rolled Canvas drag editor (ProfileCurveChart) — drag a
 * handle to set a segment's (target, time), the same state these steppers write.
 * Deferred: segment add / remove.
 */
private val ROAST_OPTIONS = listOf(
    SegOption("none", "None"),
    SegOption("light", "Light"),
    SegOption("medium", "Medium"),
    SegOption("dark", "Dark"),
)
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
    var roast by remember(profile.id) { mutableStateOf(profile.roast ?: "none") }
    val tags = remember(profile.id) {
        mutableStateListOf<String>().apply { addAll(profile.tags.filterNot { it.equals("Built-in", ignoreCase = true) }) }
    }
    var pinned by remember(profile.id) { mutableStateOf(profile.pinned) }
    var notes by remember(profile.id) { mutableStateOf(profile.notes) }
    var dose by remember(profile.id) { mutableStateOf(profile.dose.toDouble()) }
    var yieldG by remember(profile.id) { mutableStateOf(profile.yieldOut.toDouble()) }
    var brewTemp by remember(profile.id) { mutableStateOf(profile.brewTemp.toDouble()) }
    var maxVol by remember(profile.id) { mutableStateOf(profile.maxTotalVolumeMl.toDouble()) }
    // Segment accordion: one expanded at a time (-1 = all collapsed).
    var expandedIdx by remember(profile.id) { mutableStateOf(0) }
    // Per-segment editable state (positional with the profile's segments; the
    // editor doesn't add / remove segments yet).
    val segs = remember(profile.id) {
        mutableStateListOf<SegmentEdit>().apply {
            addAll(
                profile.segments.map {
                    SegmentEdit(
                        name = it.name,
                        mode = it.mode ?: "pressure",
                        ramp = it.ramp ?: "smooth",
                        target = it.target,
                        time = it.time,
                        temp = it.temp ?: profile.brewTemp,
                        tempSensor = it.tempSensor ?: "coffee",
                        volume = it.volumeLimitMl,
                        exit = it.exit,
                        limiter = it.limiter,
                    )
                },
            )
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
                        roast = roast.takeIf { it != "none" },
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
        // Two-pane: metadata/targets on the left, the curve + segments on the
        // right (web ProfileEditor layout). Each pane scrolls independently.
        Row(Modifier.weight(1f).fillMaxWidth()) {
            Column(
                Modifier
                    .width(380.dp)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 10.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Flat form (design has no section card here) — leads with an
                // inline-editable serif title, then roast / tags / pin.
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("Notes")
                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            placeholder = {
                                Text(
                                    "Tasting notes, recipe intent…",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Eyebrow("Roast")
                        CremaSegmentedButton(options = ROAST_OPTIONS, value = roast, onChange = { roast = it })
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

                // Targets — a 2×2 grid of compact tiles (+ copper Ratio), the design's
                // TargetTile cluster, not full-width steppers.
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Eyebrow("Targets")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TargetTile("Dose", String.format("%.1f", dose), "g", { dose = (dose - 0.1).coerceAtLeast(1.0) }, { dose = (dose + 0.1).coerceAtMost(60.0) }, Modifier.weight(1f))
                        TargetTile("Yield", String.format("%.1f", yieldG), "g", { yieldG = (yieldG - 0.5).coerceAtLeast(1.0) }, { yieldG = (yieldG + 0.5).coerceAtMost(200.0) }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TargetTile("Brew temp", String.format("%.1f", brewTemp), "°C", { brewTemp = (brewTemp - 0.5).coerceAtLeast(20.0) }, { brewTemp = (brewTemp + 0.5).coerceAtMost(105.0) }, Modifier.weight(1f))
                        RatioTile(if (dose > 0.0) yieldG / dose else 0.0, Modifier.weight(1f))
                    }
                    // Max volume — optional whole-shot cap. Dot toggle (copper on /
                    // grey off) + the stepper greys out when off (PWA PeNumber dot).
                    Column(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp),
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
                }
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 20.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Flat curve header + the chart in a surfaceContainerLowest well.
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Pressure profile",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                "${segs.size} segments · ${segs.sumOf { it.time.toDouble() }.toInt()}s total · drag the dots or edit below",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        CremaButton(
                            onClick = {
                                segs.clear()
                                segs.addAll(
                                    profile.segments.map {
                                        SegmentEdit(it.name, it.mode ?: "pressure", it.ramp ?: "smooth", it.target, it.time, it.temp ?: profile.brewTemp)
                                    },
                                )
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
                            modifier = Modifier.fillMaxWidth().height(260.dp),
                            onSegmentEdit = { i, target, time ->
                                if (i in segs.indices) segs[i] = segs[i].copy(target = target, time = time)
                            },
                        )
                    }
                }

                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        volume = last?.volume,
                                    ),
                                )
                            },
                            icon = "plus",
                            label = "Add",
                        )
                    }
                    if (segs.isEmpty()) {
                        Text(
                            "This profile has no segments.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    segs.forEachIndexed { i, seg ->
                        val isFlow = seg.mode == "flow"
                        CremaCard(Modifier.fillMaxWidth(), container = MaterialTheme.colorScheme.surfaceContainerHigh) {
                          Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                Modifier.fillMaxWidth().clickable { expandedIdx = if (expandedIdx == i) -1 else i },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        seg.name.ifBlank { "Step ${i + 1}" },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                    )
                                    if (expandedIdx != i) {
                                        Text(
                                            "%.1f %s · %.0fs · %s".format(seg.target, if (isFlow) "ml/s" else "bar", seg.time, seg.ramp ?: "smooth"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                PhIcon("caret-down", modifier = Modifier.rotate(if (expandedIdx == i) 180f else 0f), sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (segs.size > 1) {
                                    CremaIconButton(icon = "trash", onClick = { if (i in segs.indices) segs.removeAt(i) })
                                }
                            }
                            if (expandedIdx == i) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.weight(1f)) {
                                    CremaSegmentedButton(options = TYPE_OPTIONS, value = seg.mode ?: "pressure", onChange = { if (i in segs.indices) segs[i] = segs[i].copy(mode = it) })
                                }
                                Box(Modifier.weight(1f)) {
                                    CremaSegmentedButton(options = RAMP_OPTIONS, value = seg.ramp ?: "smooth", onChange = { if (i in segs.indices) segs[i] = segs[i].copy(ramp = it) })
                                }
                            }
                            // Compact field grid (PWA cells): a small header over a filled
                            // stepper bar — Target · Time · Temp · Volume four-up.
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Eyebrow("Target")
                                    EditStepper(seg.target.toDouble(), if (isFlow) "ml/s" else "bar", 0.1, 0.0, if (isFlow) 10.0 else 12.0, { if (i in segs.indices) segs[i] = segs[i].copy(target = it.toFloat()) })
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Eyebrow("Time")
                                    EditStepper(seg.time.toDouble(), "s", 0.5, 0.0, 120.0, { if (i in segs.indices) segs[i] = segs[i].copy(time = it.toFloat()) })
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    CremaSplitLabel(prefix = "Temp", options = listOf(SplitOption("coffee", "Coffee"), SplitOption("water", "Water")), value = seg.tempSensor ?: "coffee", onChange = { s -> if (i in segs.indices) segs[i] = segs[i].copy(tempSensor = s) })
                                    EditStepper((seg.temp ?: 93f).toDouble(), "°C", 0.5, 20.0, 105.0, { if (i in segs.indices) segs[i] = segs[i].copy(temp = it.toFloat()) })
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val segVolOn = (seg.volume ?: 0f) > 0f
                                    CremaOptionalHeader("Volume", segVolOn, { if (i in segs.indices) segs[i] = segs[i].copy(volume = if (segVolOn) null else 50f) })
                                    EditStepper((seg.volume ?: 0f).toDouble(), "ml", 5.0, 0.0, 500.0, { if (i in segs.indices) segs[i] = segs[i].copy(volume = it.toFloat().takeIf { v -> v > 0f }) }, modifier = Modifier.alpha(if (segVolOn) 1f else 0.4f), fmt = { "%.0f".format(it) })
                                }
                            }
                            // Exit + Max — grouped optionals, two-up in faint copper frames.
                            // The >/< compare sits INSIDE the threshold stepper (PWA .pe-seg-cmp).
                            val exitOn = seg.exit != null
                            val exView = seg.exit ?: SegmentExit("flow", "over", 4f)
                            val limOn = seg.limiter != null
                            val lmView = seg.limiter ?: SegmentLimiter(6f, 0.6f)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SegmentGroup(Modifier.weight(1f)) {
                                    CremaSplitLabel(
                                        prefix = "Exit",
                                        dot = true,
                                        dotOn = exitOn,
                                        onDot = { if (i in segs.indices) segs[i] = segs[i].copy(exit = if (exitOn) null else SegmentExit("flow", "over", 4f)) },
                                        options = listOf(SplitOption("pressure", "Pressure"), SplitOption("flow", "Flow")),
                                        value = exView.metric ?: "flow",
                                        onChange = { m -> if (exitOn && i in segs.indices) segs[i] = segs[i].copy(exit = segs[i].exit?.copy(metric = m)) },
                                    )
                                    EditStepper(
                                        (exView.threshold ?: 4f).toDouble(),
                                        if (exView.metric == "pressure") "bar" else "ml/s",
                                        0.1, 0.0, 12.0,
                                        { v -> if (exitOn && i in segs.indices) segs[i] = segs[i].copy(exit = segs[i].exit?.copy(threshold = v.toFloat())) },
                                        modifier = Modifier.alpha(if (exitOn) 1f else 0.4f),
                                        compareSymbol = if ((exView.compare ?: "over") == "over") ">" else "<",
                                        onCompare = { if (exitOn && i in segs.indices) segs[i] = segs[i].copy(exit = segs[i].exit?.copy(compare = if ((exView.compare ?: "over") == "over") "under" else "over")) },
                                    )
                                }
                                SegmentGroup(Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            CremaOptionalHeader("Max", limOn, { if (i in segs.indices) segs[i] = segs[i].copy(limiter = if (limOn) null else SegmentLimiter(6f, 0.6f)) })
                                            EditStepper(lmView.value.toDouble(), if (seg.mode == "flow") "bar" else "ml/s", 0.1, 0.0, 12.0, { v -> if (limOn && i in segs.indices) segs[i] = segs[i].copy(limiter = segs[i].limiter?.copy(value = v.toFloat())) }, modifier = Modifier.alpha(if (limOn) 1f else 0.4f))
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Eyebrow("Tol")
                                            EditStepper(lmView.range.toDouble(), null, 0.1, 0.0, 6.0, { v -> if (limOn && i in segs.indices) segs[i] = segs[i].copy(limiter = segs[i].limiter?.copy(range = v.toFloat())) }, modifier = Modifier.alpha(if (limOn) 1f else 0.4f))
                                        }
                                    }
                                }
                            }
                            }
                          }
                        }
                    }
                }
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

// ── Grouped optional config — a faint copper-tinted frame around the controls
// that enable/disable together (PWA .pe-seg-cell.is-grouped). ─────────────────
@Composable
private fun SegmentGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

// ── Compact stepper — a filled −/value/+ bar (PWA QuickStepper). Far tighter
// than CremaStepper: 32dp buttons, 16sp value, and an optional inline >/< compare
// symbol rendered INSIDE the value box (PWA .pe-seg-cmp), tappable to flip. ─────
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
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditStepBtn("minus", { onChange((value - step).coerceIn(min, max)) })
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            if (compareSymbol != null) {
                Text(
                    compareSymbol,
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = (onCompare?.let { Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = it) } ?: Modifier).padding(end = 4.dp),
                )
            }
            Text(fmt(value), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 16.sp, fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (unit != null) Text(unit, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f), modifier = Modifier.padding(start = 1.dp, bottom = 2.dp))
        }
        EditStepBtn("plus", { onChange((value + step).coerceIn(min, max)) })
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
private fun EditStepBtn(icon: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(32.dp)) {
        Box(contentAlignment = Alignment.Center) { PhIcon(icon, sizeDp = 14) }
    }
}

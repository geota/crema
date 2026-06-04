package coffee.crema.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.foundation.layout.width
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaSwitch
import coffee.crema.ui.components.Eyebrow
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
    var dose by remember(profile.id) { mutableStateOf(profile.dose.toDouble()) }
    var yieldG by remember(profile.id) { mutableStateOf(profile.yieldOut.toDouble()) }
    var brewTemp by remember(profile.id) { mutableStateOf(profile.brewTemp.toDouble()) }
    var maxVol by remember(profile.id) { mutableStateOf(profile.maxTotalVolumeMl.toDouble()) }
    // Per-segment target / time as parallel snapshot lists (positional with the
    // profile's segments; the non-curve editor never changes the count).
    val segTargets = remember(profile.id) {
        mutableStateListOf<Float>().apply { addAll(profile.segments.map { it.target }) }
    }
    val segTimes = remember(profile.id) {
        mutableStateListOf<Float>().apply { addAll(profile.segments.map { it.time }) }
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
            CremaButton(
                onClick = {
                    vm.saveProfile(
                        id = profile.id,
                        name = name,
                        roast = roast.takeIf { it != "none" },
                        tags = tags.toList(),
                        pinned = pinned,
                        dose = dose.toFloat(),
                        yieldOut = yieldG.toFloat(),
                        brewTemp = brewTemp.toFloat(),
                        maxTotalVolumeMl = maxVol.toInt(),
                        segments = profile.segments.indices.map {
                            (segTargets.getOrNull(it) ?: 0f) to (segTimes.getOrNull(it) ?: 0f)
                        },
                    )
                    onBack()
                },
                icon = "check",
                label = "Save",
            )
        }
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
                EditorCard {
                    Eyebrow("Identity")
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Eyebrow("Roast")
                    CremaSegmentedButton(options = ROAST_OPTIONS, value = roast, onChange = { roast = it })
                    Eyebrow("Tags")
                    TagChips(tags)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Pinned to favorites",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        CremaSwitch(checked = pinned, onCheckedChange = { pinned = it })
                    }
                }

                EditorCard {
                    Eyebrow("Targets")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) {
                            CremaStepper(label = "Dose", value = dose, unit = "g", onChange = { dose = it }, step = 0.1, min = 1.0, max = 60.0)
                        }
                        Box(Modifier.weight(1f)) {
                            CremaStepper(label = "Yield", value = yieldG, unit = "g", onChange = { yieldG = it }, step = 0.5, min = 1.0, max = 200.0)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.weight(1f)) {
                            CremaStepper(label = "Brew temp", value = brewTemp, unit = "°C", onChange = { brewTemp = it }, step = 0.5, min = 20.0, max = 105.0)
                        }
                        Box(Modifier.weight(1f)) {
                            RatioReadout(if (dose > 0.0) yieldG / dose else 0.0)
                        }
                    }
                    CremaStepper(
                        label = "Max volume (0 = none)",
                        value = maxVol,
                        unit = "ml",
                        onChange = { maxVol = it },
                        step = 10.0,
                        min = 0.0,
                        max = 1023.0,
                        fmt = { "%.0f".format(it) },
                    )
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
                EditorCard {
                    Eyebrow("Curve · drag the points")
                    ProfileCurveChart(
                        targets = segTargets.toList(),
                        times = segTimes.toList(),
                        modes = profile.segments.map { it.mode },
                        modifier = Modifier.fillMaxWidth().height(260.dp),
                        onSegmentEdit = { i, target, time ->
                            if (i in segTargets.indices) segTargets[i] = target
                            if (i in segTimes.indices) segTimes[i] = time
                        },
                    )
                }

                EditorCard {
                    Eyebrow("Segments")
                    if (profile.segments.isEmpty()) {
                        Text(
                            "This profile has no segments.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    profile.segments.forEachIndexed { i, seg ->
                        val isFlow = seg.mode == "flow"
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "${i + 1}. ${seg.name.ifBlank { "Step ${i + 1}" }}  ·  ${if (isFlow) "Flow" else "Pressure"}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.weight(1f)) {
                                    CremaStepper(
                                        label = "Target",
                                        value = (segTargets.getOrNull(i) ?: 0f).toDouble(),
                                        unit = if (isFlow) "ml/s" else "bar",
                                        onChange = { if (i < segTargets.size) segTargets[i] = it.toFloat() },
                                        step = 0.1,
                                        min = 0.0,
                                        max = if (isFlow) 10.0 else 12.0,
                                    )
                                }
                                Box(Modifier.weight(1f)) {
                                    CremaStepper(
                                        label = "Time",
                                        value = (segTimes.getOrNull(i) ?: 0f).toDouble(),
                                        unit = "s",
                                        onChange = { if (i < segTimes.size) segTimes[i] = it.toFloat() },
                                        step = 0.5,
                                        min = 0.0,
                                        max = 120.0,
                                    )
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

/** A computed-ratio readout box — mono copper value, like the web Ratio card. */
@Composable
private fun RatioReadout(ratio: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("Ratio")
        Text(
            "1:%.2f".format(ratio),
            style = CremaTheme.readout.readoutSm,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** A form section card (header pad 16, 14 dp inter-row spacing). */
@Composable
private fun EditorCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    CremaCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            content = content,
        )
    }
}

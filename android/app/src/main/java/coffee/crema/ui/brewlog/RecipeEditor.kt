package coffee.crema.ui.brewlog

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.brew.BREW_METHOD_PRESETS
import coffee.crema.brew.methodIcon
import coffee.crema.brew.methodLabel
import coffee.crema.brew.stepKindLabel
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StepAdvance
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaFilterDropdown
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaStepperStyle
import coffee.crema.ui.components.CremaTextField
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SortKey
import coffee.crema.ui.fmt
import coffee.crema.ui.phone.components.CremaPhoneBackBar
import coffee.crema.ui.theme.CremaTheme
import kotlin.math.roundToInt

/*
 * The guided-brew recipe editor (issue #10 Phase 2) — a legible list, not a
 * curve editor: each step is one line (kind · to-g · secs · AUTO/TAP) with a
 * running "planned vs water" check. Twin of the web RecipeEditor.
 *
 * Two hosts over ONE VM-held draft ([MainViewModel.recipeEdit]), opened in
 * place from wherever the user is — Scale's Brew setup or Profiles' Brew
 * recipes section:
 *  • tablet: [RecipeEditorSheet], a right side sheet on the owning tab;
 *  • phone: [RecipeEditorScreen], the pushed `recipe-edit` route.
 * Save returns to the opener (the Scale setup comes back with the saved
 * recipe selected). A rotation across 840dp swaps hosts and keeps the edits.
 */

/** The editor body's width cap on big panes (step lists stay readable). */
private val EDITOR_MAX_WIDTH = 640.dp

/** The tablet side sheet's width (capped to the pane). */
private val EDITOR_SHEET_WIDTH = 560.dp

/** Below this body width a step splits over two lines. */
private val STEP_ONE_LINE_MIN = 520.dp

private val STEP_KINDS = listOf(
    BrewStepKind.Bloom, BrewStepKind.Pour, BrewStepKind.Wait, BrewStepKind.Steep,
    BrewStepKind.Stir, BrewStepKind.Press, BrewStepKind.Drawdown,
)

@Composable
private fun RecipeEditorBody(vm: MainViewModel, d: RecipeEditDraft, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val oneLine = maxWidth >= STEP_ONE_LINE_MIN
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // The method vocabulary — presets plus this recipe's own
                // free-text method when it isn't a curated one.
                val methodKeys = buildList {
                    BREW_METHOD_PRESETS.forEach { add(SortKey(it.id, it.label, it.icon)) }
                    if (none { it.id == d.method }) add(SortKey(d.method, methodLabel(d.method), methodIcon(d.method)))
                }
                CremaFilterDropdown(
                    icon = methodIcon(d.method),
                    keys = methodKeys,
                    selectedKey = d.method,
                    onKeyChange = vm::setRecipeEditMethod,
                )
                CremaTextField(
                    value = d.name,
                    onValueChange = { v -> vm.updateRecipeEdit { it.copy(name = v) } },
                    label = "Name",
                    modifier = Modifier.weight(1f).testTag("recipe-name"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val m = Modifier.weight(1f)
                CremaStepper(
                    label = "Dose", value = d.dose, unit = "g", step = 0.5, min = 0.0, max = 200.0,
                    onChange = { v -> vm.updateRecipeEdit { it.copy(dose = v) } },
                    modifier = m, style = CremaStepperStyle.Boxed,
                )
                CremaStepper(
                    label = "Water", value = d.water, unit = "g", step = 10.0, min = 0.0, max = 2000.0,
                    fmt = { fmt("%.0f", it) },
                    onChange = { v -> vm.updateRecipeEdit { it.copy(water = v) } },
                    modifier = m, style = CremaStepperStyle.Boxed,
                )
                CremaStepper(
                    label = "Temp", value = d.temp, unit = "°C", step = 1.0, min = 0.0, max = 100.0,
                    fmt = { fmt("%.0f", it) },
                    onChange = { v -> vm.updateRecipeEdit { it.copy(temp = v) } },
                    modifier = m, style = CremaStepperStyle.Boxed,
                )
            }
            Eyebrow("Steps")
            d.steps.forEachIndexed { i, step ->
                val kind: @Composable () -> Unit = {
                    CremaFilterDropdown(
                        icon = "list-bullets",
                        keys = STEP_KINDS.map { SortKey(it.string, stepKindLabel(it)) },
                        selectedKey = step.kind.string,
                        onKeyChange = { key ->
                            val k = STEP_KINDS.first { it.string == key }
                            vm.updateRecipeEdit { it.updateStep(i) { s -> s.copy(kind = k) } }
                        },
                    )
                }
                val grams: @Composable () -> Unit = {
                    CremaStepper(
                        value = (step.targetWaterG ?: 0f).toDouble(), unit = "g", step = 5.0, min = 0.0, max = 2000.0,
                        fmt = { fmt("%.0f", it) },
                        onChange = { v ->
                            vm.updateRecipeEdit { it.updateStep(i) { s -> s.copy(targetWaterG = v.toFloat().takeIf { f -> f > 0f }) } }
                        },
                    )
                }
                val secs: @Composable () -> Unit = {
                    CremaStepper(
                        value = (step.durationS ?: 0L).toDouble(), unit = "s", step = 5.0, min = 0.0, max = 900.0,
                        fmt = { fmt("%.0f", it) },
                        onChange = { v ->
                            vm.updateRecipeEdit { it.updateStep(i) { s -> s.copy(durationS = v.toLong().takeIf { l -> l > 0L }) } }
                        },
                    )
                }
                val advance: @Composable () -> Unit = {
                    CremaButton(
                        onClick = {
                            vm.updateRecipeEdit {
                                it.updateStep(i) { s ->
                                    s.copy(advance = if (s.advance == StepAdvance.Manual) StepAdvance.Auto else StepAdvance.Manual)
                                }
                            }
                        },
                        variant = CremaButtonVariant.Text,
                        label = if (step.advance == StepAdvance.Manual) "TAP" else "AUTO",
                    )
                }
                val remove: @Composable () -> Unit = {
                    IconButton(
                        onClick = { vm.updateRecipeEdit { it.removeStep(i) } },
                        modifier = Modifier.semantics { contentDescription = "Remove step ${i + 1}" },
                    ) {
                        PhIcon("trash", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val index: @Composable () -> Unit = {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(14.dp),
                    )
                }
                if (oneLine) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        index(); kind(); grams(); secs()
                        Spacer(Modifier.weight(1f))
                        advance(); remove()
                    }
                } else {
                    // Narrow pane: kind + AUTO/TAP + remove, then the two steppers.
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            index(); kind()
                            Spacer(Modifier.weight(1f))
                            advance(); remove()
                        }
                        Row(
                            Modifier.padding(start = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) { grams(); secs() }
                    }
                }
                if (i < d.steps.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CremaButton(
                    onClick = { vm.updateRecipeEdit { it.addStep() } },
                    variant = CremaButtonVariant.Text,
                    icon = "plus-circle",
                    label = "Add step",
                )
                Spacer(Modifier.weight(1f))
                if (d.plannedTotal > 0f) {
                    Text(
                        "${d.plannedTotal.roundToInt()} g planned" +
                            if (d.totalMatches) " · matches water ✓" else " · water is ${d.water.roundToInt()} g",
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = if (d.totalMatches) CremaTheme.telemetry.success else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

/** Cancel + Save — the sticky footer of both hosts. */
@Composable
private fun RecipeEditorFooter(onCancel: () -> Unit, onSave: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CremaButton(onClick = onCancel, variant = CremaButtonVariant.Outlined, label = "Cancel")
        CremaButton(onClick = onSave, icon = "check", label = "Save recipe", modifier = Modifier.testTag("recipe-save"))
    }
}

/**
 * Tablet host — a right side sheet over a scrim on the owning tab. Renders
 * nothing unless the open draft belongs to [owner].
 */
@Composable
fun RecipeEditorSheet(vm: MainViewModel, owner: String) {
    val draft by vm.recipeEdit.collectAsStateWithLifecycle()
    val d = draft ?: return
    if (d.owner != owner) return
    val dismiss = vm::closeRecipeEdit
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().clickable(onClick = dismiss), contentAlignment = Alignment.CenterEnd) {
            val sheetWidth = if (maxWidth < EDITOR_SHEET_WIDTH) maxWidth else EDITOR_SHEET_WIDTH
            Surface(
                modifier = Modifier
                    .width(sheetWidth)
                    .fillMaxHeight()
                    .imePadding()
                    .clickable(enabled = false, onClick = {})
                    .testTag("recipe-editor-sheet"),
                shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Recipe · ${methodLabel(d.method)}")
                            Text(d.heading, style = MaterialTheme.typography.titleLarge)
                        }
                        IconButton(onClick = dismiss, modifier = Modifier.semantics { contentDescription = "Close" }) {
                            PhIcon("x", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RecipeEditorBody(
                        vm = vm,
                        d = d,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    RecipeEditorFooter(onCancel = dismiss, onSave = vm::saveRecipeEdit)
                }
            }
        }
    }
}

/**
 * Phone host — the pushed full-screen `recipe-edit` route. Back / Cancel /
 * Save settle the draft; the route pops once, when the draft is gone.
 */
@Composable
fun RecipeEditorScreen(vm: MainViewModel, onBack: () -> Unit) {
    val draft by vm.recipeEdit.collectAsStateWithLifecycle()
    val close = vm::closeRecipeEdit
    BackHandler { close() }
    val d = draft
    if (d == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    Scaffold(
        topBar = { CremaPhoneBackBar(title = d.heading, subtitle = "Recipe · ${methodLabel(d.method)}", onBack = close) },
        bottomBar = {
            Column(Modifier.imePadding().navigationBarsPadding()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = EDITOR_MAX_WIDTH).fillMaxWidth()) {
                        RecipeEditorFooter(onCancel = close, onSave = vm::saveRecipeEdit)
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Box(
            Modifier.padding(inner).fillMaxSize().verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            RecipeEditorBody(
                vm = vm,
                d = d,
                modifier = Modifier
                    .widthIn(max = EDITOR_MAX_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp)
                    .testTag("recipe-editor-screen"),
            )
        }
    }
}

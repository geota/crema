package coffee.crema.ui.brewlog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coffee.crema.brew.BREW_METHOD_PRESETS
import coffee.crema.brew.defaultRecipeFor
import coffee.crema.brew.methodIcon
import coffee.crema.brew.methodLabel
import coffee.crema.brew.stepKindLabel
import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StepAdvance
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaFilterDropdown
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaTextField
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SortKey
import coffee.crema.ui.fmt
import kotlin.math.roundToInt

/**
 * Edit a guided-brew recipe (issue #10 Phase 2) — a legible list, not a
 * curve editor: each step is one line (kind · to-g · secs · AUTO/TAP)
 * with a running "planned vs water" check. Twin of the web RecipeEditor.
 */
@Composable
fun RecipeEditorDialog(
    recipe: BrewRecipe,
    onSave: (BrewRecipe) -> Unit,
    onDismiss: () -> Unit,
    heading: String = "Edit recipe",
    /** New-recipe mode: switching the method swaps in that method's classic
     *  template so "New recipe → AeroPress" starts from the AeroPress plan. */
    reseedOnMethodChange: Boolean = false,
) {
    var method by remember { mutableStateOf(recipe.method) }
    var name by remember { mutableStateOf(recipe.name) }
    var dose by remember { mutableStateOf(recipe.doseG.toDouble()) }
    var water by remember { mutableStateOf(recipe.waterG.toDouble()) }
    var temp by remember { mutableStateOf((recipe.tempC ?: 0f).toDouble()) }
    var steps by remember { mutableStateOf(recipe.steps.orEmpty()) }

    val kinds = listOf(
        BrewStepKind.Bloom, BrewStepKind.Pour, BrewStepKind.Wait, BrewStepKind.Steep,
        BrewStepKind.Stir, BrewStepKind.Press, BrewStepKind.Drawdown,
    )
    val plannedTotal = steps.mapNotNull { it.targetWaterG }.maxOrNull() ?: 0f
    val totalMatches = plannedTotal > 0f && kotlin.math.abs(plannedTotal - water.toFloat()) < 0.5f

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier
                    .padding(20.dp)
                    .heightIn(max = 640.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Eyebrow("Recipe")
                Text(heading, style = MaterialTheme.typography.titleLarge)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // The method vocabulary — presets plus this recipe's own
                    // free-text method when it isn't a curated one.
                    val methodKeys = buildList {
                        BREW_METHOD_PRESETS.forEach { add(SortKey(it.id, it.label, it.icon)) }
                        if (none { it.id == method }) add(SortKey(method, methodLabel(method), methodIcon(method)))
                    }
                    CremaFilterDropdown(
                        icon = methodIcon(method),
                        keys = methodKeys,
                        selectedKey = method,
                        onKeyChange = { key ->
                            method = key
                            if (reseedOnMethodChange) {
                                val seed = defaultRecipeFor(key, System.currentTimeMillis())
                                name = seed.name
                                dose = seed.doseG.toDouble()
                                water = seed.waterG.toDouble()
                                temp = (seed.tempC ?: 0f).toDouble()
                                steps = seed.steps.orEmpty()
                            }
                        },
                    )
                    CremaTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = "Name",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CremaStepper(label = "Dose", value = dose, unit = "g", onChange = { dose = it }, step = 0.5, min = 0.0, max = 200.0)
                    CremaStepper(label = "Water", value = water, unit = "g", onChange = { water = it }, step = 10.0, min = 0.0, max = 2000.0, fmt = { fmt("%.0f", it) })
                    CremaStepper(label = "Temp", value = temp, unit = "°C", onChange = { temp = it }, step = 1.0, min = 0.0, max = 100.0, fmt = { fmt("%.0f", it) })
                }
                steps.forEachIndexed { i, step ->
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(14.dp),
                        )
                        CremaFilterDropdown(
                            icon = "list-bullets",
                            keys = kinds.map { SortKey(it.string, stepKindLabel(it)) },
                            selectedKey = step.kind.string,
                            onKeyChange = { key ->
                                val kind = kinds.first { it.string == key }
                                steps = steps.mapIndexed { idx, s -> if (idx == i) s.copy(kind = kind) else s }
                            },
                        )
                        CremaStepper(
                            value = (step.targetWaterG ?: 0f).toDouble(),
                            unit = "g",
                            onChange = { v ->
                                steps = steps.mapIndexed { idx, s ->
                                    if (idx == i) s.copy(targetWaterG = v.toFloat().takeIf { it > 0f }) else s
                                }
                            },
                            step = 5.0,
                            min = 0.0,
                            max = 2000.0,
                            fmt = { fmt("%.0f", it) },
                        )
                        CremaStepper(
                            value = (step.durationS ?: 0L).toDouble(),
                            unit = "s",
                            onChange = { v ->
                                steps = steps.mapIndexed { idx, s ->
                                    if (idx == i) s.copy(durationS = v.toLong().takeIf { it > 0L }) else s
                                }
                            },
                            step = 5.0,
                            min = 0.0,
                            max = 900.0,
                            fmt = { fmt("%.0f", it) },
                        )
                        CremaButton(
                            onClick = {
                                steps = steps.mapIndexed { idx, s ->
                                    if (idx == i) {
                                        s.copy(advance = if (s.advance == StepAdvance.Manual) StepAdvance.Auto else StepAdvance.Manual)
                                    } else {
                                        s
                                    }
                                }
                            },
                            variant = CremaButtonVariant.Text,
                            label = if (step.advance == StepAdvance.Manual) "TAP" else "AUTO",
                        )
                        IconButton(onClick = { steps = steps.filterIndexed { idx, _ -> idx != i } }) {
                            PhIcon("trash", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CremaButton(
                        onClick = {
                            steps = steps + BrewStep(
                                kind = BrewStepKind.Pour,
                                targetWaterG = water.toFloat(),
                                advance = StepAdvance.Auto,
                            )
                        },
                        variant = CremaButtonVariant.Text,
                        icon = "plus-circle",
                        label = "Add step",
                    )
                    Spacer(Modifier.weight(1f))
                    if (plannedTotal > 0f) {
                        Text(
                            "${plannedTotal.roundToInt()} g planned" +
                                if (totalMatches) " · matches water ✓" else " · water is ${water.roundToInt()} g",
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                            color = if (totalMatches) coffee.crema.ui.theme.CremaTheme.telemetry.success else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    CremaButton(onClick = onDismiss, variant = CremaButtonVariant.Outlined, label = "Cancel")
                    CremaButton(
                        onClick = {
                            onSave(
                                recipe.copy(
                                    method = method,
                                    name = name.trim().ifBlank { recipe.name },
                                    doseG = dose.toFloat(),
                                    waterG = water.toFloat(),
                                    tempC = temp.toFloat().takeIf { it > 0f },
                                    steps = steps,
                                ),
                            )
                        },
                        icon = "check",
                        label = "Save recipe",
                    )
                }
            }
        }
    }
}

package coffee.crema.ui.brewlog

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.brew.formatClock
import coffee.crema.brew.methodIcon
import coffee.crema.brew.methodLabel
import coffee.crema.brew.stepKindLabel
import coffee.crema.core.BrewRecipe
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.CremaOverflowMenu
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.OverflowItem
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.theme.JetBrainsMono
import kotlin.math.roundToInt

/*
 * The Brew recipes library section (issue #10) — guided plans live on the
 * Profiles screen next to the machine profiles, but as a distinct section:
 * selecting a profile uploads it to the DE1; a recipe never touches the
 * machine (the Scale screen's Brew tab picks + runs it). Shared by the
 * tablet ProfilesScreen and PhoneProfilesScreen.
 */

/** Live recipes, method-grouped (label order) then most-recent, honoring the
 *  page search. */
fun visibleBrewRecipes(recipes: List<BrewRecipe>, query: String): List<BrewRecipe> {
    val q = query.trim().lowercase()
    return recipes
        .filter { it.deletedAt == null }
        .filter {
            q.isEmpty() || it.name.lowercase().contains(q) ||
                methodLabel(it.method).lowercase().contains(q) || it.method.contains(q)
        }
        .sortedWith(compareBy<BrewRecipe> { methodLabel(it.method) }.thenByDescending { it.updatedAt })
}

/** Nominal run time, ms — step durations, pours a notional 30 s each
 *  (twin of the web `nominalRecipeMs`). */
fun nominalRecipeMs(recipe: BrewRecipe): Long =
    recipe.steps.orEmpty().sumOf { s ->
        when {
            s.durationS != null -> s.durationS!! * 1000L
            s.targetWaterG != null -> 30_000L
            else -> 0L
        }
    }

/** "Bloom → Pour → Wait → Pour → Drawdown" — the card's plan line. */
fun recipeStepChain(recipe: BrewRecipe): String =
    recipe.steps.orEmpty().joinToString(" → ") { stepKindLabel(it.kind) }

@Composable
fun BrewRecipeCard(
    recipe: BrewRecipe,
    /** Whether this is what the Scale screen opens for its method. */
    isDefault: Boolean,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onMakeDefault: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    CremaCard(modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PhIcon(methodIcon(recipe.method), sizeDp = 13, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Eyebrow(methodLabel(recipe.method))
                }
                if (isDefault) {
                    Box(
                        Modifier
                            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            "DEFAULT",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Text(
                recipe.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append("${recipe.doseG.roundToInt()} g · ${recipe.waterG.roundToInt()} g water")
                    recipe.tempC?.let { append(" · ${it.roundToInt()} °C") }
                    append(" · ~${formatClock(nominalRecipeMs(recipe))}")
                },
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                recipeStepChain(recipe),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                CremaButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    variant = CremaButtonVariant.Tonal,
                    icon = "pencil-simple",
                    label = "Edit",
                )
                FilledTonalIconButton(onClick = onDuplicate) { PhIcon("copy", sizeDp = 18) }
                CremaOverflowMenu(
                    items = buildList {
                        if (!isDefault) add(OverflowItem("star", "Make default", onMakeDefault))
                        add(OverflowItem("trash", "Delete recipe", { confirmDelete = true }, danger = true))
                    },
                )
            }
        }
    }
    if (confirmDelete) {
        CremaConfirmDialog(
            title = "Delete recipe?",
            body = "“${recipe.name}” will be removed. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { onDelete(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

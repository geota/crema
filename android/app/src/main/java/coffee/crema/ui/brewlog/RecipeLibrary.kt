package coffee.crema.ui.brewlog

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.brew.formatClock
import coffee.crema.brew.methodIcon
import coffee.crema.brew.methodLabel
import coffee.crema.brew.CoreJson
import coffee.crema.brew.stepLabel
import coffee.crema.core.recipeNominalDurationMsJson
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

/**
 * The library list: the user's live recipes plus the built-ins (hidden ones
 * only when [showHidden]), method-grouped (label order), built-ins first in
 * catalogue order, then the user's most-recent first — honoring the page
 * search (name, method, credit).
 */
fun visibleBrewRecipes(
    recipes: List<BrewRecipe>,
    query: String,
    builtins: List<BrewRecipe> = emptyList(),
    hidden: Set<String> = emptySet(),
    showHidden: Boolean = false,
): List<BrewRecipe> {
    val q = query.trim().lowercase()
    val builtinOrder = builtins.withIndex().associate { (i, r) -> r.id to i }
    val user = recipes.filter { it.deletedAt == null && it.id !in builtinOrder }
    val shownBuiltins = builtins.filter { showHidden || it.id !in hidden }
    return (shownBuiltins + user)
        .filter {
            q.isEmpty() || it.name.lowercase().contains(q) ||
                methodLabel(it.method).lowercase().contains(q) || it.method.contains(q) ||
                it.credit.orEmpty().lowercase().contains(q)
        }
        .sortedWith(
            compareBy<BrewRecipe> { methodLabel(it.method) }
                .thenBy { if (it.id in builtinOrder) 0 else 1 }
                .thenBy { builtinOrder[it.id] ?: 0 }
                .thenByDescending { it.updatedAt },
        )
}

/** Whether [url] is safe to hand to ACTION_VIEW — http(s) only (a backup can
 *  carry any string). */
fun isOpenableSourceUrl(url: String?): Boolean {
    val u = url?.trim()?.lowercase() ?: return false
    return u.startsWith("https://") || u.startsWith("http://")
}

/**
 * The recipe's credit line — small secondary text, with a "Source" link that
 * opens [BrewRecipe.sourceUrl] externally (ACTION_VIEW). Attribution only:
 * no logos, no endorsement wording. Renders nothing without a credit.
 */
@Composable
fun RecipeCreditLine(recipe: BrewRecipe, modifier: Modifier = Modifier, maxLines: Int = 2) {
    val credit = recipe.credit?.trim()?.takeIf { it.isNotEmpty() } ?: return
    val context = LocalContext.current
    val url = recipe.sourceUrl?.takeIf { isOpenableSourceUrl(it) }
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            credit,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).testTag("recipe-credit"),
        )
        if (url != null) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            // No browser — nothing sensible to do.
                        }
                    }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .semantics { contentDescription = "Open recipe source" }
                    .testTag("recipe-source"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    "Source",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                PhIcon("arrow-square-out", sizeDp = 12, tint = MaterialTheme.colorScheme.primary, contentDescription = null)
            }
        }
    }
}

/** A small outlined pill — "DEFAULT", "BUILT-IN". */
@Composable
fun RecipeBadge(text: String, primary: Boolean) {
    val color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .border(1.dp, color, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp),
            color = color,
        )
    }
}

/** Nominal run time, ms — the core's `BrewRecipe::nominal_duration_ms`
 *  (step durations, pour-only steps a notional 30 s each; shared with the
 *  web). [core] is the FFI call, injectable for JVM unit tests. */
fun nominalRecipeMs(recipe: BrewRecipe, core: (String) -> Long = ::recipeNominalDurationMsJson): Long =
    core(CoreJson.encodeToString(BrewRecipe.serializer(), recipe))

/** "Bloom → Pour → Wait → Pour → Drawdown" — the card's plan line. */
fun recipeStepChain(recipe: BrewRecipe): String =
    recipe.steps.orEmpty().joinToString(" → ") { stepLabel(it) }

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
    /** A bundled, read-only built-in: "Duplicate to edit", Hide — no Edit/Delete. */
    isBuiltin: Boolean = false,
    /** A hidden built-in (shown under "Show hidden"). */
    isHidden: Boolean = false,
    onSetHidden: (Boolean) -> Unit = {},
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isBuiltin) RecipeBadge(if (isHidden) "HIDDEN" else "BUILT-IN", primary = false)
                    if (isDefault) RecipeBadge("DEFAULT", primary = true)
                }
            }
            Text(
                recipe.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RecipeCreditLine(recipe)
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
                if (isBuiltin) {
                    CremaButton(
                        onClick = onDuplicate,
                        modifier = Modifier.weight(1f).testTag("recipe-duplicate-to-edit"),
                        variant = CremaButtonVariant.Tonal,
                        icon = "copy",
                        label = "Duplicate to edit",
                    )
                } else {
                    CremaButton(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                        variant = CremaButtonVariant.Tonal,
                        icon = "pencil-simple",
                        label = "Edit",
                    )
                    FilledTonalIconButton(onClick = onDuplicate) { PhIcon("copy", sizeDp = 18) }
                }
                CremaOverflowMenu(
                    items = buildList {
                        if (!isDefault) add(OverflowItem("star", "Make default", onMakeDefault))
                        if (isBuiltin) {
                            if (isHidden) {
                                add(OverflowItem("arrow-counter-clockwise", "Unhide recipe", { onSetHidden(false) }))
                            } else {
                                add(OverflowItem("archive", "Hide recipe", { onSetHidden(true) }))
                            }
                        } else {
                            add(OverflowItem("trash", "Delete recipe", { confirmDelete = true }, danger = true))
                        }
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

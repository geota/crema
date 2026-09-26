package coffee.crema.ui.brewlog

import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StepAdvance
import kotlin.math.abs

/*
 * Guided-brew state that must outlive a layout (issue #10 Phase 2). Rotating
 * across the 840dp breakpoint swaps the phone and tablet nav hosts (#96), so
 * nothing here may live in a screen `remember`: MainViewModel holds these and
 * both hosts render them. Pure Kotlin (no Android, no FFI) — unit-tested.
 */

/** Effective cue settings: null = never chosen → the shared defaults
 *  (core `DEFAULT_BREW_CUE_SOUND` / `DEFAULT_BREW_CUE_HAPTICS`). Visual cues
 *  are always on and have no setting. */
object BrewCueDefaults {
    const val SOUND = false
    const val HAPTICS = true

    fun soundOn(explicit: Boolean?): Boolean = explicit ?: SOUND
    fun hapticsOn(explicit: Boolean?): Boolean = explicit ?: HAPTICS
}

/** The Scale screen's Weigh | Brew segment. */
object ScaleMode {
    const val WEIGH = "weigh"
    const val BREW = "brew"
}

/**
 * The Brew segment's setup selections. [recipe] is the chosen recipe itself —
 * a saved one, or the method's (unsaved) classic template — so an unsaved
 * default keeps one stable id across recompositions and rotations.
 */
data class GuidedBrewSetup(
    val scaleMode: String = ScaleMode.WEIGH,
    val method: String? = null,
    val recipe: BrewRecipe? = null,
    val startOnPour: Boolean = true,
)

object GuidedSetupRules {
    /** The method the setup opens on: the most recently pinned one, else pourover. */
    fun initialMethod(lastByMethod: Map<String, String>): String = lastByMethod.keys.firstOrNull() ?: "pourover"

    /** [method]'s last-used saved recipe, if it still exists. */
    fun savedFor(method: String, recipes: List<BrewRecipe>, lastByMethod: Map<String, String>): BrewRecipe? =
        lastByMethod[method]?.let { id -> recipes.firstOrNull { it.id == id && it.deletedAt == null } }

    /**
     * Fill a blank setup and keep a chosen saved recipe in step with the
     * library: an edited recipe replaces the stale copy, a deleted one falls
     * back to the method's default. [template] builds the classic recipe.
     */
    fun resolve(
        setup: GuidedBrewSetup,
        recipes: List<BrewRecipe>,
        lastByMethod: Map<String, String>,
        template: (String) -> BrewRecipe,
    ): GuidedBrewSetup {
        val method = setup.method ?: initialMethod(lastByMethod)
        val current = setup.recipe?.takeIf { it.method == method }
        val fresh = when {
            current == null -> savedFor(method, recipes, lastByMethod) ?: template(method)
            else -> {
                val stored = recipes.firstOrNull { it.id == current.id }
                when {
                    stored == null -> current // an unsaved template
                    stored.deletedAt != null -> savedFor(method, recipes, lastByMethod) ?: template(method)
                    else -> stored
                }
            }
        }
        return if (method == setup.method && fresh == setup.recipe) setup else setup.copy(method = method, recipe = fresh)
    }
}

/** Who opened the recipe editor — the tablet shows its side sheet on that tab. */
object RecipeEditOwner {
    const val SCALE = "scale"
    const val PROFILES = "profiles"
}

/** The phone's pushed recipe-editor route (see [coffee.crema.ui.NavRestore]). */
const val RECIPE_EDIT_ROUTE = "recipe-edit"

/**
 * The recipe editor's working copy, VM-held so edits survive rotation and the
 * phone-route ↔ tablet-sheet swap. [base] is the recipe being edited (its id,
 * timestamps and favourite flag carry through [toRecipe]).
 */
data class RecipeEditDraft(
    val owner: String,
    val base: BrewRecipe,
    /** New-recipe mode: a method switch re-seeds that method's classic plan. */
    val isNew: Boolean,
    val method: String,
    val name: String,
    val dose: Double,
    val water: Double,
    val temp: Double,
    val steps: List<BrewStep>,
) {
    val heading: String get() = if (isNew) "New recipe" else "Edit recipe"

    /** The cumulative water the steps plan to reach (their largest target). */
    val plannedTotal: Float get() = steps.mapNotNull { it.targetWaterG }.maxOrNull() ?: 0f

    val totalMatches: Boolean get() = plannedTotal > 0f && abs(plannedTotal - water.toFloat()) < 0.5f

    fun toRecipe(): BrewRecipe = base.copy(
        method = method,
        name = name.trim().ifBlank { base.name },
        doseG = dose.toFloat(),
        waterG = water.toFloat(),
        tempC = temp.toFloat().takeIf { it > 0f },
        steps = steps,
    )

    /** A method pick; in new-recipe mode [template] re-seeds the whole plan. */
    fun withMethod(key: String, template: (String) -> BrewRecipe): RecipeEditDraft {
        if (!isNew) return copy(method = key)
        val seed = template(key)
        return copy(
            method = key,
            name = seed.name,
            dose = seed.doseG.toDouble(),
            water = seed.waterG.toDouble(),
            temp = (seed.tempC ?: 0f).toDouble(),
            steps = seed.steps.orEmpty(),
        )
    }

    fun updateStep(index: Int, transform: (BrewStep) -> BrewStep): RecipeEditDraft =
        copy(steps = steps.mapIndexed { i, s -> if (i == index) transform(s) else s })

    fun removeStep(index: Int): RecipeEditDraft = copy(steps = steps.filterIndexed { i, _ -> i != index })

    fun addStep(): RecipeEditDraft =
        copy(steps = steps + BrewStep(kind = BrewStepKind.Pour, targetWaterG = water.toFloat(), advance = StepAdvance.Auto))

    companion object {
        fun of(owner: String, recipe: BrewRecipe, isNew: Boolean) = RecipeEditDraft(
            owner = owner,
            base = recipe,
            isNew = isNew,
            method = recipe.method,
            name = recipe.name,
            dose = recipe.doseG.toDouble(),
            water = recipe.waterG.toDouble(),
            temp = (recipe.tempC ?: 0f).toDouble(),
            steps = recipe.steps.orEmpty(),
        )
    }
}

/**
 * The live session's layout class, chosen from the pane it actually gets
 * (`BoxWithConstraints`), never the screen width — the rail and host chrome
 * eat space, and a phone in landscape lands on the tablet shell at ~400dp tall.
 */
enum class LivePane {
    /** < 600dp wide: one column, controls pinned at the bottom. */
    COMPACT,

    /** 600–840dp: one centred ≤460dp column; chart only when very tall. */
    MEDIUM,

    /** ≥ 840 wide, ≥ 600 tall: session column + live chart. */
    TWO_COLUMN,

    /** ≥ 840 wide, < 600 tall: clock + controls | step card + next; no chart. */
    COCKPIT,
    ;

    companion object {
        fun of(widthDp: Float, heightDp: Float): LivePane = when {
            widthDp < 600f -> COMPACT
            widthDp < 840f -> MEDIUM
            heightDp >= 600f -> TWO_COLUMN
            else -> COCKPIT
        }

        /** MEDIUM panes show the chart under the step card only from this height. */
        const val MEDIUM_CHART_MIN_HEIGHT = 900f

        /** The big clock scales with the height it gets, 48–96sp. */
        fun clockSp(heightDp: Float): Float = (heightDp * 0.11f).coerceIn(48f, 96f)
    }
}

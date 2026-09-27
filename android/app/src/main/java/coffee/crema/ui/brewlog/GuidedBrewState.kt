package coffee.crema.ui.brewlog

import coffee.crema.brew.CoreJson
import coffee.crema.core.Bean
import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewStep
import coffee.crema.core.BrewStepKind
import coffee.crema.core.StepAdvance
import coffee.crema.core.defaultBrewCueHaptics
import coffee.crema.core.defaultBrewCueSound
import coffee.crema.core.recipePlannedPourTotalGJson
import kotlin.math.abs

/*
 * Guided-brew state that must outlive a layout (issue #10 Phase 2). Rotating
 * across the 840dp breakpoint swaps the phone and tablet nav hosts (#96), so
 * nothing here may live in a screen `remember`: MainViewModel holds these and
 * both hosts render them. Pure Kotlin (no Android; the few core calls are
 * injectable FFI lambdas) — unit-tested.
 */

/** Effective cue settings: null = never chosen → the core's shared defaults
 *  (`DEFAULT_BREW_CUE_SOUND` / `DEFAULT_BREW_CUE_HAPTICS`, via the FFI —
 *  injectable for JVM unit tests). Visual cues are always on and have no
 *  setting. */
object BrewCueDefaults {
    fun soundOn(explicit: Boolean?, default: () -> Boolean = ::defaultBrewCueSound): Boolean =
        explicit ?: default()

    fun hapticsOn(explicit: Boolean?, default: () -> Boolean = ::defaultBrewCueHaptics): Boolean =
        explicit ?: default()
}

/** The Scale screen's Weigh | Brew segment. */
object ScaleMode {
    const val WEIGH = "weigh"
    const val BREW = "brew"
}

/**
 * The Brew segment's setup selections. [recipe] is the chosen recipe itself —
 * a saved one or a built-in — or null when the method has neither (espresso,
 * drip, free text: the setup offers "+ New recipe").
 */
data class GuidedBrewSetup(
    val scaleMode: String = ScaleMode.WEIGH,
    val method: String? = null,
    val recipe: BrewRecipe? = null,
    val startOnPour: Boolean = true,
    /** The user picked [method] this session (a chip, the dropdown, a saved
     *  edit). Until then the setup follows the most recent method, so the
     *  opening pick never freezes on whatever loaded first. */
    val methodChosen: Boolean = false,
    /**
     * The bag this brew debits (issue #10 feedback): null follows the active
     * bag, [GuidedBeanRules.NO_BEAN] is "No bean", else a bean id. Resolve it
     * with [GuidedBeanRules.resolve]. Choosing never changes the active bag,
     * and a method / recipe change keeps it.
     */
    val beanPick: String? = null,
)

/**
 * Which bag a guided brew uses — the Brew setup's bean picker, carried
 * through the summary into the Log-brew form (where it's still changeable).
 * Twin of the web `$lib/brew/bean-pick`.
 */
object GuidedBeanRules {
    /** [GuidedBrewSetup.beanPick] / the dropdown key for "No bean". */
    const val NO_BEAN = "none"

    /** A bag a brew can use: it exists and is neither archived nor deleted. */
    fun pickable(b: Bean?): Boolean = b != null && b.archivedAt == null && b.deletedAt == null

    /** The dropdown's bags, in library order — archived bags excluded (the log form's rule). */
    fun choices(beans: List<Bean>): List<Bean> = beans.filter(::pickable)

    /**
     * The bag [pick] resolves to: the chosen bag while it's still pickable;
     * if it was archived or deleted meanwhile (or nothing was chosen), the
     * active bag; else none. "No bean" stays none.
     */
    fun resolve(pick: String?, beans: List<Bean>, activeBeanId: String?): String? {
        if (pick == NO_BEAN) return null
        if (pick != null && pickable(beans.firstOrNull { it.id == pick })) return pick
        return activeBeanId?.takeIf { id -> pickable(beans.firstOrNull { it.id == id }) }
    }

    /**
     * The dose would take more than the bag has left (a bag with no
     * remaining weight recorded never warns) — the web log form's rule.
     */
    fun overdraws(bean: Bean?, doseG: Double): Boolean {
        val left = bean?.remaining ?: return false
        return doseG > 0.0 && left > 0f && doseG > left + 0.05
    }

    /** The quiet inline note, worded like the web log form. */
    fun overdrawNote(bean: Bean): String =
        "More than the ${kotlin.math.max(0, kotlin.math.round(bean.remaining ?: 0f).toInt())} g left in this bag — saving floors the bag at zero."
}

object GuidedSetupRules {
    /**
     * The method the setup opens on: the most recently used one ([lastMethod],
     * tracked explicitly — the method of the last saved brew log, the web's
     * `lastUsedMethod()`), else pourover (the core's `DEFAULT_LOG_METHOD`).
     * Never a map's first key: that's the first method ever pinned.
     */
    fun initialMethod(lastMethod: String?): String = lastMethod?.takeIf { it.isNotBlank() } ?: "pourover"

    /** Steps longer than this (seconds) don't hold the screen on. */
    const val LONG_STEP_S = 15 * 60L

    /**
     * Whether a live session holds the display on at [stepIndex]: yes, except
     * on a step timed longer than [LONG_STEP_S] (a cold brew's 12 h steep) —
     * nobody watches that countdown, and the session clock is timestamp-based
     * in the core, so a dark screen loses nothing; the step still ends on a tap.
     */
    fun holdsScreenOn(recipe: BrewRecipe?, stepIndex: Int): Boolean {
        val d = recipe?.steps?.getOrNull(stepIndex)?.durationS ?: return true
        return d <= LONG_STEP_S
    }

    /** [method]'s default-pointer recipe, if it still exists (user or built-in). */
    fun savedFor(method: String, recipes: List<BrewRecipe>, lastByMethod: Map<String, String>): BrewRecipe? =
        lastByMethod[method]?.let { id -> recipes.firstOrNull { it.id == id && it.deletedAt == null } }

    /**
     * Fill a blank setup and keep a chosen recipe in step with the library:
     * an edited recipe replaces the stale copy, a deleted one falls back to
     * the method's default. [recipes] is the user's recipes plus the
     * built-ins; [fallback] is the method's default when no pointer resolves
     * (its default built-in), or null for a method without one.
     */
    fun resolve(
        setup: GuidedBrewSetup,
        recipes: List<BrewRecipe>,
        lastByMethod: Map<String, String>,
        lastMethod: String?,
        fallback: (String) -> BrewRecipe?,
    ): GuidedBrewSetup {
        val method = setup.method?.takeIf { setup.methodChosen } ?: initialMethod(lastMethod)
        val current = setup.recipe?.takeIf { it.method == method }
        val fresh = when {
            current == null -> savedFor(method, recipes, lastByMethod) ?: fallback(method)
            else -> {
                val stored = recipes.firstOrNull { it.id == current.id }
                when {
                    stored == null || stored.deletedAt != null ->
                        savedFor(method, recipes, lastByMethod) ?: fallback(method)
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
    /** New-recipe mode: a method switch re-seeds that method's blank plan. */
    val isNew: Boolean,
    val method: String,
    val name: String,
    val dose: Double,
    val water: Double,
    val temp: Double,
    val steps: List<BrewStep>,
) {
    val heading: String get() = if (isNew) "New recipe" else "Edit recipe"

    /** The cumulative water the steps plan to reach — the core's
     *  `planned_pour_total_g` (largest finite target; 0 when none). [core]
     *  is the FFI call, injectable for JVM unit tests. */
    fun plannedTotal(core: (String) -> Float? = ::recipePlannedPourTotalGJson): Float =
        core(CoreJson.encodeToString(BrewRecipe.serializer(), toRecipe())) ?: 0f

    /** The editor's "planned · matches water" check. */
    fun totalMatches(planned: Float = plannedTotal()): Boolean =
        planned > 0f && abs(planned - water.toFloat()) < 0.5f

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
    /** < ~600dp window (a < 560dp pane): one column, controls pinned at the bottom. */
    COMPACT,

    /** 600–840dp wide and ≥ 600 tall: one centred ≤460dp column; chart only when very tall. */
    MEDIUM,

    /** ≥ 840 wide, ≥ 600 tall: session column + live chart. */
    TWO_COLUMN,

    /** ≥ 600 wide, < 600 tall: clock + controls | step card + next; no chart. */
    COCKPIT,
    ;

    companion object {
        /**
         * Below this pane width: COMPACT. A 600dp window (a 7" portrait on
         * the phone shell) leaves a ~568dp pane after the shell's 16dp
         * edges, and still gets the MEDIUM layout; the widest phones stay
         * under ~450dp.
         */
        const val COMPACT_MAX_WIDTH = 560f

        fun of(widthDp: Float, heightDp: Float): LivePane = when {
            widthDp < COMPACT_MAX_WIDTH -> COMPACT
            // Short panes go cockpit even below 840: a phone in landscape
            // lands on the tablet shell, and the rail leaves its pane
            // ~780dp wide and ~330dp tall.
            heightDp < 600f -> COCKPIT
            widthDp < 840f -> MEDIUM
            else -> TWO_COLUMN
        }

        /** MEDIUM panes show the chart under the step card only from this height. */
        const val MEDIUM_CHART_MIN_HEIGHT = 900f

        /** The big clock scales with the height it gets, 48–96sp. */
        fun clockSp(heightDp: Float): Float = (heightDp * 0.11f).coerceIn(48f, 96f)
    }
}

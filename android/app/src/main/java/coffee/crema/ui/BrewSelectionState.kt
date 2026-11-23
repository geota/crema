package coffee.crema.ui

import coffee.crema.brew.formatClock
import coffee.crema.core.BrewMode
import coffee.crema.core.BrewPickInput
import coffee.crema.core.BrewPickLock
import coffee.crema.core.BrewPickLockInput
import coffee.crema.core.BrewRecipe
import coffee.crema.core.BrewSelection
import coffee.crema.core.BrewSelectionInput
import coffee.crema.core.BrewSelectionKind
import coffee.crema.core.BrewSelectionView
import coffee.crema.core.HubProfileInput
import coffee.crema.core.RecipeSummary
import coffee.crema.core.StoredBrewSelection
import kotlin.math.roundToInt

/*
 * Brew's selection and the mode it decides (brew-modes spec §2, §4, §5), over
 * the core's `brew_selection` exports. The shell stores the pair the core
 * reads: [MainUiState.activeProfileId] (the key it always had) and
 * [MainUiState.activeRecipeId] beside it, both in `AppPrefs`. The recipe key
 * is per device: it never goes in the relayed `ConfigSnapshot`.
 */
object BrewSelectionRules {

    /** The recipe [id] is one this device can brew now: a built-in, or a custom recipe that isn't deleted. */
    fun recipeLive(id: String?, recipes: List<BrewRecipe>, builtins: List<BrewRecipe>): Boolean =
        id != null && (builtins.any { it.id == id } || recipes.any { it.id == id && it.deletedAt == null })

    /** The selection and its mode, from what's stored ([coffee.crema.core.brewSelectionViewJson]). */
    fun view(activeProfileId: String?, activeRecipeId: String?, recipeLive: Boolean): BrewSelectionView =
        coreJson.decodeFromString(
            coffee.crema.core.brewSelectionViewJson(
                coreJson.encodeToString(BrewSelectionInput.serializer(), BrewSelectionInput(activeProfileId, activeRecipeId, recipeLive)),
            ),
        )

    /** What to store after a pick on this device ([coffee.crema.core.pickBrewSelectionJson]). */
    fun pick(stored: StoredBrewSelection, pick: BrewSelection): StoredBrewSelection =
        coreJson.decodeFromString(
            coffee.crema.core.pickBrewSelectionJson(coreJson.encodeToString(BrewPickInput.serializer(), BrewPickInput(stored, pick))),
        )

    /** What a remote stores when the hub's profile reaches it ([coffee.crema.core.followHubProfileJson]): the recipe stays. */
    fun followHub(stored: StoredBrewSelection, hubProfileId: String?): StoredBrewSelection =
        coreJson.decodeFromString(
            coffee.crema.core.followHubProfileJson(coreJson.encodeToString(HubProfileInput.serializer(), HubProfileInput(stored, hubProfileId))),
        )

    /** Why the picker's rows are disabled now, or null ([coffee.crema.core.brewPickLockJson]). */
    fun lock(shotRunning: Boolean, guidedBrewRunning: Boolean): BrewPickLock? =
        coffee.crema.core.brewPickLockJson(
            coreJson.encodeToString(BrewPickLockInput.serializer(), BrewPickLockInput(shotRunning, guidedBrewRunning)),
        )?.let { coreJson.decodeFromString(BrewPickLock.serializer(), it) }

    /** [recipe]'s header / row summary ([coffee.crema.core.recipeSummaryJson]). */
    fun summary(recipe: BrewRecipe): RecipeSummary =
        coreJson.decodeFromString(
            coffee.crema.core.recipeSummaryJson(coreJson.encodeToString(BrewRecipe.serializer(), recipe)),
        )

    /** A guided brew holds this device: armed, running or paused (not done). */
    fun guidedBrewRunning(phase: String): Boolean = phase == "armed" || phase == "running" || phase == "paused"
}

/** One cached [BrewSelectionRules.view] per input, so a recomposition doesn't call the core again. */
private object BrewViewMemo {
    @Volatile var last: Pair<Triple<String?, String?, Boolean>, BrewSelectionView>? = null
}

/** Brew's selection and mode on this device. */
fun MainUiState.brewView(): BrewSelectionView {
    val key = Triple(activeProfileId, activeRecipeId, BrewSelectionRules.recipeLive(activeRecipeId, brewRecipes, builtinRecipes))
    BrewViewMemo.last?.let { (k, v) -> if (k == key) return v }
    return BrewSelectionRules.view(key.first, key.second, key.third).also { BrewViewMemo.last = key to it }
}

/** Brew is in Manual: a live recipe is selected. */
fun MainUiState.inManualMode(): Boolean = brewView().mode == BrewMode.Manual

/** The recipe Manual brews, or null in Espresso. */
fun MainUiState.selectedRecipe(): BrewRecipe? {
    val sel = brewView().selection?.takeIf { it.kind == BrewSelectionKind.Recipe } ?: return null
    return builtinRecipes.firstOrNull { it.id == sel.id } ?: brewRecipes.firstOrNull { it.id == sel.id && it.deletedAt == null }
}

/**
 * The recipe Brew's Manual page shows: the selected one, else a guided brew's
 * own while one exists (armed, running, paused or finished and unsaved). A
 * brew whose recipe was deleted mid-way, or a selection that changed under a
 * finished brew, keeps its page and its Finish / Save brew (REVIEW-532, as
 * web #531). Null = Espresso's page.
 */
fun MainUiState.manualRecipe(): BrewRecipe? =
    selectedRecipe() ?: guidedBrew.recipe?.takeIf { guidedBrew.phase != "idle" }

/** The stored pair the core reads and writes. */
fun MainUiState.storedBrewSelection(): StoredBrewSelection = StoredBrewSelection(activeProfileId, activeRecipeId)

/**
 * The picker lock (§4) on this device: a shot on the machine it shows (on a
 * remote, the hub's, from the relayed state) locks the profile rows; its own
 * guided brew locks every row.
 */
fun MainUiState.brewPickLock(): BrewPickLock? =
    BrewSelectionRules.lock(
        shotRunning = shotInProgress || shotStarting,
        guidedBrewRunning = BrewSelectionRules.guidedBrewRunning(guidedBrew.phase),
    )

/** "15 g · 250 g water · 100 °C · ~3:00", the header block's line. */
fun RecipeSummary.headerLine(): String = buildString {
    append("${doseG.roundToInt()} g · ${waterG.roundToInt()} g water")
    tempC?.let { append(" · ${it.roundToInt()} °C") }
    estimateLabel()?.let { append(" · ~$it") }
}

/** "3:00", or "2:45+" when open-ended; null = no time. */
fun RecipeSummary.estimateLabel(): String? = estimated?.let { formatClock(it.totalMs) + if (it.openEnded) "+" else "" }

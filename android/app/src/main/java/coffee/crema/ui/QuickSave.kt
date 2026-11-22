package coffee.crema.ui

import coffee.crema.core.BrewOverrides
import coffee.crema.core.QuickSaveInput
import coffee.crema.core.QuickSavePlan
import coffee.crema.core.QuickSaveProfile
import coffee.crema.core.QuickSaveProfileKind
import coffee.crema.core.QuickSaveProfileWrite
import coffee.crema.core.QuickSaveUndoInput
import coffee.crema.core.QuickSaveUndoPlan
import coffee.crema.core.quickSavePlanJson
import coffee.crema.core.quickSaveUndoJson
import kotlinx.serialization.json.Json

/*
 * What Quick Controls' **Save** writes (save-boundary review 2026-11-21, F1),
 * and its Undo: the core's rules (`quick_save_plan`, `quick_save_undo`,
 * REVIEW-CORE M2), so web and Android don't each write them. Each changed
 * part goes to its owner, and only when the user confirms:
 *  - the grind differs from the active bag's saved grind for the active
 *    grinder (#488) → the grind to that bag row;
 *  - the recipe dials differ from the profile (a Quick-Controls override) →
 *    a custom profile is updated in place (with Undo, and "Save as new…"),
 *    a built-in is copied under a name the user gives;
 *  - nothing differs → Save is off.
 *
 * On a remote the plan is read from the hub's values on screen, and Save is
 * sent to the hub, which saves to its own bag and profile (#488, review F8).
 * The name prompt, the profile store writes and the message wording stay here.
 */

private val quickSaveJson = Json { ignoreUnknownKeys = true }

/** Save is off: nothing to write (also what a failed core call answers). */
val NO_QUICK_SAVE = QuickSavePlan(
    profileKind = QuickSaveProfileKind.None,
    recipeChanged = false,
    profileWrite = QuickSaveProfileWrite.None,
    writesRecipe = false,
    enabled = false,
    needsName = false,
    offersSaveAsNew = false,
    includesGrind = false,
    copyNameSeed = "",
)

/** The Save button's label. */
val QuickSavePlan.label: String get() = "Save"

/** The plan's input for the state on screen (on a remote, the hub's). */
internal fun MainUiState.quickSaveInput(): QuickSaveInput {
    val profile = activeProfile()
    return QuickSaveInput(
        // On a remote, the hub's bag and its saved grind (S8); the hub saves.
        bag = grindBag(),
        grinder = activeGrinder(),
        brewGrind = qcGrind,
        profile = profile?.let {
            QuickSaveProfile(
                id = it.id,
                name = it.name,
                kind = if (it.source == "custom") QuickSaveProfileKind.Custom else QuickSaveProfileKind.Builtin,
            )
        },
        // The override is stored normalized: a dial back at the profile's own
        // value is dropped, and nothing left is no override.
        recipeOverrides = brewParams?.let {
            BrewOverrides(dose = it.dose.toFloat(), yield = it.yieldOut.toFloat(), brewTemp = it.brewTemp.toFloat(), preinf = it.preinf?.toFloat())
        } ?: BrewOverrides(),
    )
}

/** The last plan and what it was asked: Brew reads it on every recomposition. */
private class QuickSaveMemo(val input: QuickSaveInput, val plan: QuickSavePlan)

@Volatile private var quickSaveMemo: QuickSaveMemo? = null

/** The core's Save plan for [input] ([core] is its UniFFI call, swappable in tests). */
fun quickSavePlan(input: QuickSaveInput, core: (String) -> String = ::quickSavePlanJson): QuickSavePlan =
    quickSaveJson.decodeFromString(QuickSavePlan.serializer(), core(quickSaveJson.encodeToString(QuickSaveInput.serializer(), input)))

/** [QuickSavePlan] for the state on screen (on a remote, the hub's). */
fun MainUiState.quickSavePlan(): QuickSavePlan {
    val input = quickSaveInput()
    quickSaveMemo?.takeIf { it.input == input }?.let { return it.plan }
    // Never expected (the input is this app's own types): Save stays off.
    val plan = runCatching { quickSavePlan(input) }.getOrElse { NO_QUICK_SAVE }
    quickSaveMemo = QuickSaveMemo(input, plan)
    return plan
}

/** The core's guarded Undo of an in-place Save ([core] is its UniFFI call). */
fun quickSaveUndo(
    input: QuickSaveUndoInput,
    nowMs: Long,
    core: (String, Long) -> String = ::quickSaveUndoJson,
): QuickSaveUndoPlan =
    quickSaveJson.decodeFromString(QuickSaveUndoPlan.serializer(), core(quickSaveJson.encodeToString(QuickSaveUndoInput.serializer(), input), nowMs))

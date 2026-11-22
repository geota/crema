package coffee.crema.ui

import coffee.crema.beans.grindText

/** The kind of profile Quick Controls' Save would write to. */
enum class QuickSaveProfileKind { None, Custom, BuiltIn }

/**
 * What Quick Controls' **Save** writes (save-boundary review 2026-11-21, F1):
 * each changed part goes to its owner, and only when the user confirms.
 *  - the grind dial differs from the active bag's saved grind for the active
 *    grinder (#488) → the grind to that bag row;
 *  - the recipe dials differ from the profile (a Quick-Controls override) →
 *    a custom profile is updated in place (with Undo, and "Save as new…"),
 *    a built-in is copied under a name the user gives;
 *  - nothing differs → Save is off.
 *
 * On a remote the plan is read from the hub's values on screen, and Save is
 * sent to the hub, which saves to its own bag and profile (#488, review F8).
 */
data class QuickSavePlan(
    val beanId: String? = null,
    val beanName: String? = null,
    /** The dial as the bag stores it, when it differs from the bag's; else null. */
    val grind: String? = null,
    val profileId: String? = null,
    val profileName: String? = null,
    val profileKind: QuickSaveProfileKind = QuickSaveProfileKind.None,
    /** The recipe dials differ from the active profile's own. */
    val recipeChanged: Boolean = false,
) {
    /** Save writes the recipe to a profile (there is one to write to). */
    val writesRecipe: Boolean get() = recipeChanged && profileKind != QuickSaveProfileKind.None

    /** Save has something to write, here. */
    val enabled: Boolean get() = grind != null || writesRecipe

    /** Save asks for a name first: a built-in is read-only, so it's a copy. */
    val needsName: Boolean get() = enabled && writesRecipe && profileKind == QuickSaveProfileKind.BuiltIn

    /** "Save as new…" is offered: a custom profile would otherwise be updated in place. */
    val offersSaveAsNew: Boolean get() = enabled && writesRecipe && profileKind == QuickSaveProfileKind.Custom

    /** The Save button's label. */
    val label: String get() = "Save"

    /** Save includes the grind: the button carries the unsaved-grind dot. */
    val includesGrind: Boolean get() = grind != null

    /** The name the copy prompt starts from. */
    val copyNameSeed: String get() = profileName?.let { "$it (copy)" }.orEmpty()
}

/** [QuickSavePlan] for the state on screen (on a remote, the hub's). */
fun MainUiState.quickSavePlan(): QuickSavePlan {
    val bean = activeBag()
    // The dial against the bag's saved grind for the active grinder; none saved counts as different.
    val grind = qcGrind?.let(::grindText)
        ?.takeIf { bean != null && (savedGrindForBrew() == null || coffee.crema.beans.grindDiffers(it, savedGrindForBrew()?.grind)) }
    val profile = activeProfile()
    val kind = when {
        profile == null -> QuickSaveProfileKind.None
        profile.source == "custom" -> QuickSaveProfileKind.Custom
        else -> QuickSaveProfileKind.BuiltIn
    }
    return QuickSavePlan(
        beanId = bean?.id,
        beanName = bean?.name,
        grind = grind,
        profileId = profile?.id,
        profileName = profile?.name,
        profileKind = kind,
        // The override is stored normalized: a dial back at the profile's own
        // value is dropped, and nothing left is no override.
        recipeChanged = brewParams != null,
    )
}

package coffee.crema.ui

/** The kind of profile Quick Controls' Save would write to. */
enum class QuickSaveProfileKind { None, Custom, BuiltIn }

/**
 * What Quick Controls' **Save** writes (save-boundary review 2026-11-21, F1):
 * each changed part goes to its owner, and only when the user confirms.
 *  - the grind dial differs from the active bag's saved grind → the grind to the bag;
 *  - the recipe dials differ from the profile (a Quick-Controls override) →
 *    a custom profile is updated in place (with Undo, and "Save as new…"),
 *    a built-in is copied under a name the user gives;
 *  - nothing differs → Save is off.
 *
 * On a remote Save is off and says where to save ([hubLabel], F8 interim):
 * its library is its own copy, which the hub never reads.
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
    /** On a remote, the hub's name: Save is off there. */
    val hubLabel: String? = null,
) {
    /** Save writes the recipe to a profile (there is one to write to). */
    val writesRecipe: Boolean get() = recipeChanged && profileKind != QuickSaveProfileKind.None

    /** Save has something to write, here. */
    val enabled: Boolean get() = hubLabel == null && (grind != null || writesRecipe)

    /** Save asks for a name first: a built-in is read-only, so it's a copy. */
    val needsName: Boolean get() = enabled && writesRecipe && profileKind == QuickSaveProfileKind.BuiltIn

    /** "Save as new…" is offered: a custom profile would otherwise be updated in place. */
    val offersSaveAsNew: Boolean get() = enabled && writesRecipe && profileKind == QuickSaveProfileKind.Custom

    /** The Save button's label. */
    val label: String get() = hubLabel?.let { "Save on $it" } ?: "Save"

    /** The name the copy prompt starts from. */
    val copyNameSeed: String get() = profileName?.let { "$it (copy)" }.orEmpty()
}

/** The bag's form of the grind [dial]: whole numbers without a decimal. */
internal fun grindSettingOf(dial: Float): String =
    if (dial % 1f == 0f) fmt("%.0f", dial) else fmt("%.1f", dial)

/** [QuickSavePlan] for the state on screen (on a remote, the hub's). */
fun MainUiState.quickSavePlan(): QuickSavePlan {
    val bean = beans.firstOrNull { it.id == activeBeanId }
    val grind = qcGrind?.let(::grindSettingOf)?.takeIf { bean != null && it != bean.grinderSetting }
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
        hubLabel = if (proxyRole == "secondary") remoteHubLabel() else null,
    )
}

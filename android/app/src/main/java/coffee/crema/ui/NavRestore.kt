package coffee.crema.ui

/**
 * Route hand-off between the two nav hosts (phone [coffee.crema.ui.phone.PhoneNavHost]
 * and tablet [AppNavHost]). MainActivity swaps hosts when the window crosses the
 * 840dp breakpoint (e.g. rotating a phone to landscape); each host has its own
 * NavController starting on `brew`, so the incoming host replays the outgoing
 * host's route with the steps returned here.
 */
object NavRestore {
    /** Top-level destinations shared by both hosts. */
    val TAB_ROUTES: Set<String> = setOf("brew", "scale", "profiles", "beans", "history", "settings")

    /** Every route the phone host knows. */
    val PHONE_ROUTES: Set<String> = TAB_ROUTES + setOf("profile-edit", "bean-edit", "roaster-edit", "log-brew", "recipe-edit", "method-edit", "debug")

    /**
     * Every route the tablet host knows (roasters are edited in a dialog
     * there; the Brew Log form and the recipe editor are side sheets on their
     * owning tab).
     */
    val TABLET_ROUTES: Set<String> = TAB_ROUTES + setOf("profile-edit", "bean-edit", "debug")

    /** The tab each pushed route is opened from. */
    private val OWNER: Map<String, String> = mapOf(
        "profile-edit" to "profiles",
        "bean-edit" to "beans",
        "roaster-edit" to "beans",
        // The Brew Log form's owner is dynamic (History or Beans — wherever it
        // was opened); this is only the fallback. See [restoreRoute].
        "log-brew" to "history",
        // Likewise the recipe editor's (Brew's Manual page or Profiles).
        "recipe-edit" to "profiles",
        // And the brewing-method dialog's (the tab of whatever opened it).
        "method-edit" to "profiles",
        "debug" to "settings",
    )

    /**
     * The Profiles destination, with its optional `filter` argument (brew
     * modes polish): `profiles` is the whole library, as the tab restores it;
     * `profiles?filter=recipes` is the recipes only (Brew's "All recipes →");
     * `profiles?filter=all` the whole library afresh (Brew's "All profiles →").
     */
    const val PROFILES_PATTERN = "profiles?filter={filter}"

    /** [PROFILES_PATTERN]'s argument. */
    const val PROFILES_FILTER_ARG = "filter"

    /** Profiles on its recipes only. */
    const val PROFILES_RECIPES = "profiles?filter=recipes"

    /** Profiles on its whole library, never a recipes-only view left there. */
    const val PROFILES_ALL = "profiles?filter=all"

    /** The filter value that shows the recipes only. */
    const val FILTER_RECIPES = "recipes"

    /** Profiles' [filter] argument asks for the recipes only. */
    fun recipesOnly(filter: String?): Boolean = filter == FILTER_RECIPES

    /** [route] without its arguments: `profiles?filter=recipes` is `profiles`. */
    fun baseRoute(route: String): String = route.substringBefore('?')

    /**
     * Whether a tab navigation to [dest] restores the tab's saved state: a
     * plain tab does; one that names arguments opens fresh, so the arguments
     * decide the view rather than what the tab last showed.
     */
    fun restoresState(dest: String): Boolean = dest == baseRoute(dest)

    /**
     * The route to report for a back-stack entry: its destination's [pattern]
     * with the arguments filled in, so a host swap reopens the same view
     * (`profiles?filter=recipes`). Patterns without arguments come back as they are.
     */
    fun reportedRoute(pattern: String, filter: String?): String =
        if (pattern == PROFILES_PATTERN) {
            if (filter == FILTER_RECIPES) PROFILES_RECIPES else "profiles"
        } else {
            pattern
        }

    /** The phone's pushed Brew Log form (issue #10). */
    const val LOG_BREW = "log-brew"

    /** The phone's pushed guided-brew recipe editor (issue #10 Phase 2). */
    const val RECIPE_EDIT = "recipe-edit"

    /** The phone's pushed add / edit brewing-method dialog (issue #10 feedback). */
    const val METHOD_EDIT = "method-edit"

    /**
     * [owners] key naming the pushed route the method dialog sits on (the
     * Log-brew form or the recipe editor) — replayed between the tab and the
     * dialog so Back returns to that form.
     */
    const val METHOD_EDIT_PARENT = "method-edit/parent"

    /**
     * The navigations to perform, in order, on top of the `brew` start
     * destination so a host whose routes are [supported] lands on [route].
     * Tabs come back as one step, pushed editors as `[owningTab, editor]`
     * (Back then returns to the tab, as it would have), and an editor the
     * host lacks collapses to its owning tab. `brew` or an unknown route → none.
     */
    fun steps(route: String?, supported: Set<String>, owners: Map<String, String> = emptyMap()): List<String> {
        if (route == null || route == "brew") return emptyList()
        if (baseRoute(route) in TAB_ROUTES) return if (baseRoute(route) in supported) listOf(route) else emptyList()
        val owner = owners[route] ?: OWNER[route] ?: return emptyList()
        if (route == METHOD_EDIT && route in supported) {
            val parent = owners[METHOD_EDIT_PARENT]?.takeIf { it in supported }
            return listOfNotNull(owner, parent, route)
        }
        return if (route in supported) listOf(owner, route) else listOf(owner)
    }

    /**
     * The route the incoming host should restore, given the open Brew Log
     * form's owning tab ([logBrewOwner], null = no form open):
     *  • phone ← tablet: the tablet shows the form as a sheet ON its owning
     *    tab, so that tab comes back as the pushed `log-brew` route;
     *  • tablet ← phone: `log-brew` collapses to the owning tab (via [steps]
     *    with [owners]), where the tablet re-opens the sheet;
     *  • a stale `log-brew` with no form open falls back to History.
     * The recipe editor ([recipeEditOwner]: Scale or Profiles) follows the
     * same rules with `recipe-edit`; a stale one falls back to Profiles.
     * The brewing-method dialog ([methodEditOwner]) sits on top of both: on
     * the phone its tab (the tablet shows it as a sheet there) comes back as
     * `method-edit`; a stale one falls back to the form under it, else Profiles.
     */
    fun restoreRoute(
        route: String?,
        logBrewOwner: String?,
        phone: Boolean,
        recipeEditOwner: String? = null,
        methodEditOwner: String? = null,
    ): String? = restoreBaseRoute(route?.let(::baseRoute), logBrewOwner, phone, recipeEditOwner, methodEditOwner)
        // A tab that stays a tab keeps its arguments (`profiles?filter=recipes`).
        .let { restored -> if (route != null && restored == baseRoute(route)) route else restored }

    private fun restoreBaseRoute(
        route: String?,
        logBrewOwner: String?,
        phone: Boolean,
        recipeEditOwner: String?,
        methodEditOwner: String?,
    ): String? = when {
        route == METHOD_EDIT && methodEditOwner == null ->
            when {
                recipeEditOwner != null -> if (phone) RECIPE_EDIT else recipeEditOwner
                logBrewOwner != null -> if (phone) LOG_BREW else logBrewOwner
                else -> "profiles"
            }
        phone && methodEditOwner != null && route == methodEditOwner -> METHOD_EDIT
        route == LOG_BREW && logBrewOwner == null -> "history"
        route == RECIPE_EDIT && recipeEditOwner == null -> "profiles"
        phone && recipeEditOwner != null && route == recipeEditOwner -> RECIPE_EDIT
        phone && logBrewOwner != null && route == logBrewOwner -> LOG_BREW
        else -> route
    }

    /** [steps]' dynamic owners for the open form / editor. */
    fun owners(
        logBrewOwner: String?,
        recipeEditOwner: String? = null,
        methodEditOwner: String? = null,
        methodEditParent: String? = null,
    ): Map<String, String> = buildMap {
        if (logBrewOwner != null) put(LOG_BREW, logBrewOwner)
        if (recipeEditOwner != null) put(RECIPE_EDIT, recipeEditOwner)
        if (methodEditOwner != null) put(METHOD_EDIT, methodEditOwner)
        if (methodEditParent != null) put(METHOD_EDIT_PARENT, methodEditParent)
    }
}

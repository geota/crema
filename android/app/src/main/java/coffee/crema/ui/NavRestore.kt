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
    val PHONE_ROUTES: Set<String> = TAB_ROUTES + setOf("profile-edit", "bean-edit", "roaster-edit", "log-brew", "debug")

    /**
     * Every route the tablet host knows (roasters are edited in a dialog
     * there; the Brew Log form is a side sheet on its owning tab).
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
        "debug" to "settings",
    )

    /** The phone's pushed Brew Log form (issue #10). */
    const val LOG_BREW = "log-brew"

    /**
     * The navigations to perform, in order, on top of the `brew` start
     * destination so a host whose routes are [supported] lands on [route].
     * Tabs come back as one step, pushed editors as `[owningTab, editor]`
     * (Back then returns to the tab, as it would have), and an editor the
     * host lacks collapses to its owning tab. `brew` or an unknown route → none.
     */
    fun steps(route: String?, supported: Set<String>, owners: Map<String, String> = emptyMap()): List<String> {
        if (route == null || route == "brew") return emptyList()
        if (route in TAB_ROUTES) return if (route in supported) listOf(route) else emptyList()
        val owner = owners[route] ?: OWNER[route] ?: return emptyList()
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
     */
    fun restoreRoute(route: String?, logBrewOwner: String?, phone: Boolean): String? = when {
        route == LOG_BREW && logBrewOwner == null -> "history"
        phone && logBrewOwner != null && route == logBrewOwner -> LOG_BREW
        else -> route
    }

    /** [steps]' dynamic owners for the open form. */
    fun owners(logBrewOwner: String?): Map<String, String> =
        if (logBrewOwner != null) mapOf(LOG_BREW to logBrewOwner) else emptyMap()
}

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
    val PHONE_ROUTES: Set<String> = TAB_ROUTES + setOf("profile-edit", "bean-edit", "roaster-edit", "debug")

    /** Every route the tablet host knows (roasters are edited in a dialog there). */
    val TABLET_ROUTES: Set<String> = TAB_ROUTES + setOf("profile-edit", "bean-edit", "debug")

    /** The tab each pushed route is opened from. */
    private val OWNER: Map<String, String> = mapOf(
        "profile-edit" to "profiles",
        "bean-edit" to "beans",
        "roaster-edit" to "beans",
        "debug" to "settings",
    )

    /**
     * The navigations to perform, in order, on top of the `brew` start
     * destination so a host whose routes are [supported] lands on [route].
     * Tabs come back as one step, pushed editors as `[owningTab, editor]`
     * (Back then returns to the tab, as it would have), and an editor the
     * host lacks collapses to its owning tab. `brew` or an unknown route → none.
     */
    fun steps(route: String?, supported: Set<String>): List<String> {
        if (route == null || route == "brew") return emptyList()
        if (route in TAB_ROUTES) return if (route in supported) listOf(route) else emptyList()
        val owner = OWNER[route] ?: return emptyList()
        return if (route in supported) listOf(owner, route) else listOf(owner)
    }
}

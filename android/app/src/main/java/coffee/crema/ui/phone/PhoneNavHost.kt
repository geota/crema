package coffee.crema.ui.phone

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coffee.crema.ui.phone.components.CremaBottomNav

/**
 * The handset shell (compact window class): a Scaffold whose bottomBar is the
 * 5-destination [CremaBottomNav], hosting the same routes as the tablet's
 * AppNavHost. The caller (MainActivity) wires each slot to the ViewModel, the
 * same pattern as the tablet host, so this file owns routing + chrome only.
 *
 * Route classes (mirrors the phone handoff's CremaPhoneNav.kt):
 *  • TOP-LEVEL (bottom bar visible): brew · scale · profiles · beans · history
 *    · settings. Settings is NOT a bottom-bar item — every top bar's gear
 *    navigates to it (its tab row shows no active tab).
 *  • PUSHED full-screen (bottom bar HIDDEN): profile-edit · bean-edit ·
 *    roaster-edit · debug.
 *  • list → detail inside history / settings is INTERNAL screen state (the
 *    bottom bar stays put).
 */
@Composable
fun PhoneNavHost(
    brewContent: @Composable (onNav: (String) -> Unit) -> Unit,
    scaleContent: @Composable (onNav: (String) -> Unit) -> Unit,
    profilesContent: @Composable (onNav: (String) -> Unit) -> Unit,
    beansContent: @Composable (onNav: (String) -> Unit) -> Unit,
    historyContent: @Composable (onNav: (String) -> Unit) -> Unit,
    settingsContent: @Composable (onNav: (String) -> Unit) -> Unit,
    profileEditContent: @Composable (onBack: () -> Unit) -> Unit,
    beanEditContent: @Composable (onBack: () -> Unit) -> Unit,
    roasterEditContent: @Composable (onBack: () -> Unit) -> Unit,
    debugContent: @Composable () -> Unit,
) {
    val nav = rememberNavController()
    val tabRoutes = setOf("brew", "scale", "profiles", "beans", "history", "settings")

    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route ?: "brew"
    val showBottomBar = route in tabRoutes
    // Settings is a top-level destination but not a tab; nothing highlights.
    val activeTab = if (route == "settings") "" else route

    val onNav: (String) -> Unit = { dest ->
        nav.navigate(dest) {
            launchSingleTop = true
            if (dest in tabRoutes) {
                // Standard bottom-nav pattern: save the outgoing tab's state and
                // pop to the start so tabs don't stack on back.
                popUpTo(nav.graph.startDestinationId) { saveState = true }
                restoreState = true
            }
        }
    }
    val onBack: () -> Unit = { nav.popBackStack() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // Each screen's own TopAppBar consumes the status-bar inset; consuming
        // it here too would double the top spacing. The NavigationBar handles
        // the bottom system inset itself.
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            if (showBottomBar) {
                CremaBottomNav(active = activeTab, onNav = onNav)
            }
        },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = "brew",
            modifier = Modifier.padding(inner),
        ) {
            // ── Top-level destinations (bottom bar visible) ──────────────────
            composable("brew") { brewContent(onNav) }
            composable("scale") { scaleContent(onNav) }
            composable("profiles") { profilesContent(onNav) }
            composable("beans") { beansContent(onNav) }
            composable("history") { historyContent(onNav) }
            composable("settings") { settingsContent(onNav) }

            // ── Pushed editors (full-screen; bottom bar hidden) ──────────────
            composable("profile-edit") { profileEditContent(onBack) }
            composable("bean-edit") { beanEditContent(onBack) }
            composable("roaster-edit") { roasterEditContent(onBack) }
            composable("debug") { debugContent() }
        }
    }
}

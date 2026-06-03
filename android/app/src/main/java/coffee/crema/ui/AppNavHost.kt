package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import coffee.crema.ui.screens.BeanEditScreen
import coffee.crema.ui.screens.BeansScreen
import coffee.crema.ui.screens.HistoryScreen
import coffee.crema.ui.screens.ProfileEditScreen
import coffee.crema.ui.screens.ProfilesScreen
import coffee.crema.ui.screens.SettingsScreen

/**
 * The app shell: the six rail destinations + two pushed editors from the
 * prototype's nav graph (`prototype/tablet/prototype.jsx`), plus a `debug` route
 * that hosts the Phase-0 readout so the live BLE path stays reachable during M0.
 *
 * Each rail screen renders its own [CremaNavigationRail] (the prototype pattern),
 * so this host only owns routing — not chrome. Connection state + the
 * connect/disconnect action are threaded down so the rail's DE1 / Scale pips are
 * live on every screen.
 *
 * @param machineConnected whether the DE1 link is READY (drives the rail pip).
 * @param scaleConnected whether the scale link is READY (drives the rail pip).
 * @param onRailConnect tap on a rail pip: `"machine"` / `"scale"` → connect or
 *   disconnect that device (the caller is permission-wrapped).
 * @param debugContent the Phase-0 readout, hosted at the `debug` route.
 */
@Composable
fun AppNavHost(
    machineConnected: Boolean,
    scaleConnected: Boolean,
    onRailConnect: (String) -> Unit,
    /** The live Brew dashboard (the start destination). Receives the nav-rail
     *  `onNav` from this host while the caller binds the ViewModel + connect. */
    brewContent: @Composable (onNav: (String) -> Unit) -> Unit,
    /** The live Scale screen. Receives the nav-rail `onNav` from this host (it
     *  needs the nav controller) while the caller binds the ViewModel + connect. */
    scaleContent: @Composable (onNav: (String) -> Unit) -> Unit,
    debugContent: @Composable () -> Unit,
) {
    val nav = rememberNavController()
    // Rail destinations replace one another (single-top, no back-stack buildup);
    // editors + debug are pushed on top and pop back. Full tab state-saving is a
    // later polish item.
    val onNav: (String) -> Unit = { dest -> nav.navigate(dest) { launchSingleTop = true } }
    val onPush: (String) -> Unit = { route -> nav.navigate(route) }
    val onBack: () -> Unit = { nav.popBackStack() }

    NavHost(navController = nav, startDestination = "brew") {
        // The hero dashboard, wired to live VM telemetry by the caller.
        composable("brew") { brewContent(onNav) }
        composable("profiles") { ProfilesScreen(onNav, machineConnected, scaleConnected, onRailConnect) }
        composable("beans") { BeansScreen(onNav, machineConnected, scaleConnected, onRailConnect) }
        composable("history") { HistoryScreen(onNav, machineConnected, scaleConnected, onRailConnect) }
        // The fully-designed exemplar, wired to live VM state by the caller.
        composable("scale") { scaleContent(onNav) }
        composable("settings") {
            SettingsScreen(onNav, machineConnected, scaleConnected, onRailConnect, onOpenDebug = { onPush("debug") })
        }
        composable("profile-edit") { ProfileEditScreen(onBack = onBack) }
        composable("bean-edit") { BeanEditScreen(onBack = onBack) }
        composable("debug") { debugContent() }
    }
}

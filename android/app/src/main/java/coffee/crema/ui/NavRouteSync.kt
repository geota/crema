package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState

/**
 * Keeps a nav host in step with MainActivity's hoisted route so a phone↔tablet
 * host swap (rotating across the 840dp breakpoint) lands where the user was.
 *
 * On first composition it replays [initialRoute] on top of the `brew` start
 * destination via the host's own [onNav] (so tabs get the usual
 * save/restore-state options and editors are pushed over their owning tab —
 * see [NavRestore.steps]); from then on it reports every route change through
 * [onRouteChange]. Call it next to the host's NavHost (same composition) so the
 * graph is set before the replay runs.
 */
@Composable
internal fun SyncNavRoute(
    nav: NavHostController,
    initialRoute: String,
    supported: Set<String>,
    onNav: (String) -> Unit,
    onRouteChange: (String) -> Unit,
    owners: Map<String, String> = emptyMap(),
) {
    val replay = remember { NavRestore.steps(initialRoute, supported, owners) }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(nav) {
        replay.forEach(onNav)
        restored = true
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // Hold reports until the replay has run, or the transient `brew` start
    // would overwrite the route we are restoring.
    LaunchedEffect(route, restored) {
        if (restored && route != null) onRouteChange(route)
    }
}

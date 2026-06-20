package coffee.crema.ui.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.phone.components.CremaBottomNav

/**
 * The handset shell (compact window class): a Scaffold whose bottomBar is the
 * 5-destination [CremaBottomNav], hosting the same routes as the tablet's
 * AppNavHost. The caller (MainActivity) wires each slot to the ViewModel, the
 * same pattern as the tablet host, so this file owns routing + chrome only.
 *
 * It also owns the **app-wide mirror authority cue** (issue 10): when this device
 * is a secondary, a thin "Mirroring <primary>" banner sits above every tab and
 * opens the (hoisted) Devices sheet — Settings/Profiles/Beans no longer look
 * local. The banner lives here, not per-screen, so it can't be missed on a tab.
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
    vm: MainViewModel,
    onConnect: (String) -> Unit,
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

    val ui by vm.ui.collectAsStateWithLifecycle()
    val secondary = ui.proxyRole == "secondary"
    var devicesOpen by remember { mutableStateOf(false) }

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
        Column(Modifier.padding(inner).fillMaxSize()) {
            // App-wide authority banner (issue 10). It takes the status-bar inset
            // (so it sits below the system bar); the content Box below then
            // CONSUMES that inset so each screen's own TopAppBar doesn't re-pad
            // and leave a gap under the banner.
            if (secondary) {
                MirrorBanner(
                    primaryName = ui.mirroringPrimaryName,
                    reconnecting = ui.mirrorReconnecting,
                    viewOnly = ui.mirrorViewOnly,
                    onClick = { devicesOpen = true },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .then(if (secondary) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier),
            ) {
                NavHost(
                    navController = nav,
                    startDestination = "brew",
                ) {
                    // ── Top-level destinations (bottom bar visible) ──────────────
                    composable("brew") { brewContent(onNav) }
                    composable("scale") { scaleContent(onNav) }
                    composable("profiles") { profilesContent(onNav) }
                    composable("beans") { beansContent(onNav) }
                    composable("history") { historyContent(onNav) }
                    composable("settings") { settingsContent(onNav) }

                    // ── Pushed editors (full-screen; bottom bar hidden) ──────────
                    composable("profile-edit") { profileEditContent(onBack) }
                    composable("bean-edit") { beanEditContent(onBack) }
                    composable("roaster-edit") { roasterEditContent(onBack) }
                    composable("debug") { debugContent() }
                }
            }
        }
    }

    // One app-wide Devices sheet, opened by the banner from any tab (the Brew
    // screen keeps its own bluetooth-icon sheet). Take over / Stop live here too.
    if (devicesOpen) {
        PhoneDevicesSheet(
            ui = ui,
            connected = ui.bleState == De1BleManager.State.READY,
            scaleConnected = ui.scaleState == ScaleBleManager.State.READY,
            onConnect = onConnect,
            onDe1AutoConnect = vm::setDe1AutoConnect,
            onScaleAutoConnect = vm::setScaleAutoConnect,
            onMirrorFrom = { host, port -> vm.switchToSecondary(host, port); devicesOpen = false },
            onStopMirroring = { vm.switchToNormal(); devicesOpen = false },
            onTakeOver = { vm.requestHandoff(); devicesOpen = false },
            onHandOff = { id -> vm.offerHandoff(id); devicesOpen = false },
            onDismiss = { devicesOpen = false },
        )
    }
}

/**
 * The thin "Mirroring <primary>" bar shown on every tab while this device is a
 * secondary (issue 10) — brand-accent tinted so it reads as an authority state,
 * not an error. Switches to "Reconnecting to <primary>…" when the link drops, and
 * taps through to the Devices sheet (Stop / Take over).
 */
@Composable
private fun MirrorBanner(
    primaryName: String,
    reconnecting: Boolean,
    viewOnly: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val who = primaryName.ifBlank { "another device" }
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PhIcon("bluetooth", sizeDp = 15)
            Text(
                when {
                    reconnecting -> "Reconnecting to $who…"
                    viewOnly -> "Mirroring $who · view-only"
                    else -> "Mirroring $who"
                },
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Manage",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            PhIcon("caret-right", sizeDp = 14)
        }
    }
}

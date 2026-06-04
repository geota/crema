package coffee.crema.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.theme.CremaTheme

/*
 * M0 placeholder destinations.
 *
 * Each *rail* screen follows the prototype pattern — every screen owns its own
 * CremaNavigationRail — wired to the REAL DE1 / scale connection state and the
 * connect/disconnect action, plus a centered "coming soon" body. The pushed
 * *editor* screens render a back + breadcrumb header instead of the rail.
 *
 * These get replaced screen-by-screen in later milestones; see
 * android/IMPLEMENTATION-PLAN.md §2. Brew (BrewScreen.kt) and Scale
 * (ScaleScreen.kt) are now live, VM-driven screens; the rest follow.
 */

@Composable
private fun RailScaffold(
    active: String,
    onNav: (String) -> Unit,
    machineConnected: Boolean,
    scaleConnected: Boolean,
    onConnect: (String) -> Unit,
    body: @Composable () -> Unit,
) {
    Row(Modifier.fillMaxSize()) {
        CremaNavigationRail(
            active = active,
            onNav = onNav,
            machineConnected = machineConnected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Box(
            Modifier.fillMaxSize().padding(CremaTheme.spacing.edge),
            contentAlignment = Alignment.Center,
        ) { body() }
    }
}

@Composable
private fun ComingSoon(title: String, sub: String, extra: @Composable () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Eyebrow(title)
        Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra()
    }
}

@Composable
fun HistoryScreen(
    onNav: (String) -> Unit,
    machineConnected: Boolean,
    scaleConnected: Boolean,
    onConnect: (String) -> Unit,
) = RailScaffold("history", onNav, machineConnected, scaleConnected, onConnect) {
    ComingSoon("History", "Shot log + list-detail — milestone M4.")
}

@Composable
fun SettingsScreen(
    onNav: (String) -> Unit,
    machineConnected: Boolean,
    scaleConnected: Boolean,
    onConnect: (String) -> Unit,
    onOpenDebug: () -> Unit,
) = RailScaffold("settings", onNav, machineConnected, scaleConnected, onConnect) {
    ComingSoon("Settings", "Preferences, machine, sharing — milestone M5.") {
        CremaButton(
            onClick = onOpenDebug,
            variant = CremaButtonVariant.Tonal,
            icon = "bug",
            label = "Open debug readout",
        )
    }
}

// ── Pushed editors (no rail; back + breadcrumb) ─────────────────────────────

@Composable
fun ProfileEditScreen(onBack: () -> Unit) = EditorPlaceholder("Profiles", "Edit profile", onBack)

@Composable
fun BeanEditScreen(onBack: () -> Unit) = EditorPlaceholder("Beans", "Edit bean", onBack)

@Composable
private fun EditorPlaceholder(crumbRoot: String, crumbLeaf: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CremaIconButton(icon = "arrow-left", onClick = onBack)
            Eyebrow("$crumbRoot › $crumbLeaf")
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ComingSoon(crumbLeaf, "Full editor — milestone M3.")
        }
    }
}

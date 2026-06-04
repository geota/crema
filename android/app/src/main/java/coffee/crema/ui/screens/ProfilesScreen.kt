package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.profiles.CremaProfile
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.PhIcon

/*
 * Profiles (library) — M3 v1. A grid of profile cards over the core's built-in
 * profiles (ui.profiles), each with a pressure/flow curve preview, recipe
 * metrics, roast/tag pills, and Load-on-Brew (which sets the active profile the
 * Brew header + gated start use). The active profile gets a "Loaded" badge.
 *
 * v1 scope: built-ins only, load + browse. The profile editor (curve drag),
 * search/filter bar, and user (custom) profiles are later M3 increments.
 */
@Composable
fun ProfilesScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "profiles",
            onNav = onNav,
            machineConnected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Profiles",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val pinned = ui.profiles.count { it.pinned }
                    Text(
                        "${ui.profiles.size} profiles · $pinned pinned",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                CremaButton(
                    onClick = { vm.startNewProfile(); onNav("profile-edit") },
                    icon = "plus",
                    label = "New profile",
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 260.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 20.dp),
            ) {
                items(ui.profiles, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isActive = profile.id == ui.activeProfileId,
                        onLoad = { vm.setActiveProfile(profile.id) },
                        onEdit = { vm.startEditProfile(profile.id); onNav("profile-edit") },
                        onDuplicate = { vm.duplicateProfile(profile.id); onNav("profile-edit") },
                        onDelete = { vm.deleteProfile(profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: CremaProfile,
    isActive: Boolean,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val isCustom = profile.source == "custom"
    CremaCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CanvasProfilePreview(
                segments = profile.segments,
                modifier = Modifier.fillMaxWidth().height(76.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    profile.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 22.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Built-ins are read-only: customise via Duplicate. Customs
                    // also get Edit + Delete.
                    if (isCustom) {
                        FilledTonalIconButton(onClick = onEdit) { PhIcon("pencil-simple", sizeDp = 18) }
                    }
                    FilledTonalIconButton(onClick = onDuplicate) { PhIcon("copy", sizeDp = 18) }
                    if (isCustom) {
                        FilledTonalIconButton(onClick = onDelete) { PhIcon("trash", sizeDp = 18) }
                    }
                }
            }
            Text(
                "1:%.2f · %.1f g · %.0f °C".format(profile.ratio, profile.yieldOut, profile.brewTemp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val pills = listOfNotNull(profile.roast?.replaceFirstChar { it.uppercase() }) +
                profile.tags.filter { it.isNotBlank() && it != "Built-in" }
            if (pills.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pills.take(3).forEach { Pill(it) }
                }
            }
            if (isActive) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PhIcon("check", sizeDp = 16, tint = MaterialTheme.colorScheme.primary)
                    Text("Loaded", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                CremaButton(onClick = onLoad, variant = CremaButtonVariant.Tonal, icon = "coffee", label = "Load on Brew")
            }
        }
    }
}

@Composable
private fun Pill(text: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// The profile-card curve preview is now CanvasProfilePreview (a faithful Canvas
// port of the web ProfilePreview) — see CanvasProfilePreview.kt.

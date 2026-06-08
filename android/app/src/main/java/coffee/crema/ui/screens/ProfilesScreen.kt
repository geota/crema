package coffee.crema.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.CremaValueUnit
import coffee.crema.ui.components.CremaSortControl
import coffee.crema.ui.components.SortKey
import androidx.compose.ui.Alignment
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.profiles.CremaProfile
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaCardSpec
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.CremaOverflowMenu
import coffee.crema.ui.components.OverflowItem
import coffee.crema.ui.theme.JetBrainsMono

/*
 * Profiles (library) — M3 v1. A grid of profile cards over the core's built-in
 * profiles (ui.profiles), each with a pressure/flow curve preview, recipe
 * metrics, roast/tag pills, and Load-on-Brew (which sets the active profile the
 * Brew header + gated start use). The active profile gets a "Loaded" badge.
 *
 * v1 scope: built-ins only, load + browse. The profile editor (curve drag),
 * search/filter bar, and user (custom) profiles are later M3 increments.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("name") }
    var sortDesc by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importProfiles(uri)
    }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = pendingExport; pendingExport = null
        if (uri != null && t != null) vm.writeTextToUri(uri, t)
    }
    val launchSave: (String, String?) -> Unit = { name, content -> if (content != null) { pendingExport = content; saveLauncher.launch(name) } }
    // Fall back off the Hidden facet once nothing is archived (e.g. the last one
    // was just restored) so the grid never strands on an empty hidden view.
    val effectiveFilter = if (filter == "hidden" && ui.hiddenProfileIds.isEmpty()) "all" else filter
    val filtered = ui.profiles.filter { p ->
        val isHidden = p.id in ui.hiddenProfileIds
        (query.isBlank() ||
            p.name.contains(query, ignoreCase = true) ||
            p.tags.any { it.contains(query, ignoreCase = true) } ||
            (p.roast?.contains(query, ignoreCase = true) == true)) &&
            when (effectiveFilter) {
                // The Hidden facet draws only the archived built-ins; every other
                // facet excludes them from the active grid.
                "hidden" -> isHidden
                "pinned" -> !isHidden && p.pinned
                "all" -> !isHidden
                else -> !isHidden && p.roast?.equals(filter, ignoreCase = true) == true
            }
    }
    val roastOrder = mapOf("light" to 0, "medium" to 1, "dark" to 2)
    // Ascending base per key; the direction toggle reverses it.
    val sortedAsc = when (sort) {
        "roast" -> filtered.sortedBy { roastOrder[it.roast?.lowercase()] ?: 3 }
        "pinned" -> filtered.sortedBy { if (it.pinned) 0 else 1 }
        else -> filtered.sortedBy { it.name.lowercase() }
    }
    val sorted = if (sortDesc) sortedAsc.reversed() else sortedAsc

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
                    Eyebrow("Library")
                    Text(
                        "Profiles",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    val pinned = ui.profiles.count { it.pinned }
                    Text(
                        "${ui.profiles.size} saved · $pinned pinned to favorites",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Compact search pill — matched to the 40dp button height so the
                // command-bar row reads as one set (the stock 56dp OutlinedTextField
                // towered over Import / New profile).
                Box(
                    Modifier.width(240.dp).height(40.dp)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PhIcon("magnifying-glass", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text("Search profiles…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                CremaButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }, variant = CremaButtonVariant.Outlined, icon = "upload-simple", label = "Import")
                Spacer(Modifier.width(8.dp))
                CremaButton(onClick = { launchSave("crema-profiles.json", vm.allProfilesJson()) }, variant = CremaButtonVariant.Outlined, icon = "download-simple", label = "Export")
                Spacer(Modifier.width(8.dp))
                CremaButton(
                    onClick = { vm.startNewProfile(); onNav("profile-edit") },
                    icon = "plus",
                    label = "New profile",
                )
            }
            // Filter chips (left) + sort selector (right) on one polished row.
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val visible = ui.profiles.filter { it.id !in ui.hiddenProfileIds }
                    val chips = buildList {
                        add("all" to "All"); add("pinned" to "Pinned")
                        add("light" to "Light"); add("medium" to "Medium"); add("dark" to "Dark")
                        // Hidden facet only appears once there's something archived.
                        if (ui.hiddenProfileIds.isNotEmpty()) add("hidden" to "Hidden")
                    }
                    chips.forEach { (id, label) ->
                        val count = when (id) {
                            "all" -> visible.size
                            "pinned" -> visible.count { it.pinned }
                            "hidden" -> ui.hiddenProfileIds.size
                            else -> visible.count { it.roast?.equals(id, ignoreCase = true) == true }
                        }
                        CremaFilterChip(label = label, selected = effectiveFilter == id, count = count, onClick = { filter = id })
                    }
                }
                CremaSortControl(
                    keys = listOf(
                        SortKey("name", "Name", "sort-ascending"),
                        SortKey("roast", "Roast", "fire"),
                        SortKey("pinned", "Pinned", "star"),
                    ),
                    selectedKey = sort,
                    descending = sortDesc,
                    onKeyChange = { sort = it },
                    onToggleDirection = { sortDesc = !sortDesc },
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(CremaCardSpec.gridGap),
                verticalArrangement = Arrangement.spacedBy(CremaCardSpec.gridGap),
                contentPadding = CremaCardSpec.gridContentPadding,
            ) {
                items(sorted, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isActive = profile.id == ui.activeProfileId,
                        onLoad = { vm.setActiveProfile(profile.id) },
                        onTogglePin = { vm.togglePinProfile(profile.id) },
                        onEdit = {
                            // Custom → edit in place. Built-in → "edit a copy"
                            // (duplicateProfile mints a fresh id, so no list-key
                            // collision with the original built-in).
                            if (profile.source == "custom") vm.startEditProfile(profile.id) else vm.duplicateProfile(profile.id)
                            onNav("profile-edit")
                        },
                        onDuplicate = { vm.duplicateProfile(profile.id); onNav("profile-edit") },
                        onExport = { vm.exportProfile(profile.id) },
                        onDelete = { vm.deleteProfile(profile.id) },
                        isHidden = profile.id in ui.hiddenProfileIds,
                        onArchive = { vm.archiveBuiltinProfile(profile.id) },
                        onUnarchive = { vm.unarchiveBuiltinProfile(profile.id) },
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
    onTogglePin: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    isHidden: Boolean = false,
    onArchive: () -> Unit = {},
    onUnarchive: () -> Unit = {},
) {
    val isCustom = profile.source == "custom"
    var confirmDelete by remember { mutableStateOf(false) }
    CremaCard(
        modifier = Modifier.fillMaxWidth(),
        container = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(CremaCardSpec.radius),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(CremaCardSpec.pad),
            verticalArrangement = Arrangement.spacedBy(CremaCardSpec.gap),
        ) {
            // Head row — always present (reserves height) so cards in a grid row
            // stay equal regardless of active/pinned state: LOADED (left) + favorite star (right).
            Row(
                Modifier.fillMaxWidth().heightIn(min = CremaCardSpec.headMinHeight),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isActive) LoadedBadge() else Spacer(Modifier.size(1.dp))
                // Favorite star — always present, tappable: outline when off,
                // copper-filled when pinned (to the Quick Controls favorites strip).
                Box(
                    Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onTogglePin),
                    contentAlignment = Alignment.Center,
                ) {
                    PhIcon(
                        if (profile.pinned) "star-fill" else "star",
                        sizeDp = 18,
                        tint = if (profile.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Box(
                Modifier.fillMaxWidth().height(CremaCardSpec.previewHeight)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                CanvasProfilePreview(
                    segments = profile.segments,
                    modifier = Modifier.fillMaxSize(),
                )
                // Channel legend overlaid top-left (matches proto + PWA): a dot per
                // pressure/flow channel, a dashed marker for temp.
                ProfilePreviewLegend(
                    Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 6.dp),
                )
            }
            // Name + a guaranteed-present meta sub-line (anchors every card to the
            // same baseline block — the missing fixed line that made heights ragged).
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    profile.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (isCustom) "Custom profile" else "Built-in profile",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ProfileMetricsRow(profile)
            // Tag row — always present at a reserved min height so an untagged
            // card doesn't collapse shorter than its tagged neighbour.
            val tagPills = profile.tags.filter { it.isNotBlank() && it != "Built-in" }
            Row(
                Modifier.heightIn(min = CremaCardSpec.tagRowMinHeight),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                profile.roast?.let { Pill(it, roast = true) }
                // Source chip (PWA's "Built-in" tag) — always present so the row
                // never collapses and built-ins are clearly marked.
                Pill(if (isCustom) "Custom" else "Built-in")
                tagPills.take(2).forEach { Pill(it) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CremaButton(
                    onClick = onLoad,
                    modifier = Modifier.weight(1f),
                    variant = if (isActive) CremaButtonVariant.Outlined else CremaButtonVariant.Tonal,
                    icon = if (isActive) "check-circle" else "coffee",
                    label = if (isActive) "Loaded on Brew" else "Load on Brew",
                )
                FilledTonalIconButton(onClick = onDuplicate) { PhIcon("copy", sizeDp = 18) }
                FilledTonalIconButton(onClick = onEdit) { PhIcon("pencil-simple", sizeDp = 18) }
                CremaOverflowMenu(items = buildList {
                    add(OverflowItem("export", "Export .json", onExport))
                    when {
                        // Already archived (Hidden view) → restore.
                        isHidden -> add(OverflowItem("arrow-counter-clockwise", "Restore profile", onUnarchive))
                        // Custom → real delete. Built-in → archive (it can't be deleted),
                        // styled red so it reads as the delete affordance.
                        isCustom -> add(OverflowItem("trash", "Delete profile", { confirmDelete = true }, danger = true))
                        else -> add(OverflowItem("archive", "Archive profile", onArchive, danger = true))
                    }
                })
            }
        }
    }
    if (confirmDelete) {
        CremaConfirmDialog(
            title = "Delete profile?",
            body = "“${profile.name}” will be removed. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { onDelete(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

// Roast variant = uppercase copper-tinted (primary @12%); tags = neutral.
@Composable
private fun Pill(text: String, roast: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (roast) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            if (roast) text.uppercase() else text,
            style = MaterialTheme.typography.labelSmall,
            color = if (roast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// The 4-up recipe metrics grid (Ratio / Dose / Temp / Pre-inf), mono values
// between hairline rules — the web profile-card metrics row.
@Composable
private fun ProfileMetricsRow(profile: CremaProfile) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProfileMetric("Ratio", "1:%.1f".format(profile.ratio), "", Modifier.weight(1f))
            ProfileMetric("Dose", "%.1f".format(profile.dose), "g", Modifier.weight(1f))
            ProfileMetric("Temp", "%.0f".format(profile.brewTemp), "°C", Modifier.weight(1f))
            ProfileMetric("Pre-inf", "${profile.preinfuseSeconds}", "s", Modifier.weight(1f))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun ProfileMetric(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Eyebrow(label)
        CremaValueUnit(value, unit.ifBlank { null }, valueSize = 14.sp)
    }
}

// Channel legend overlay for the card preview — three tiny key items (dot +
// uppercase label) sitting top-left over the curve, the proto/PWA's
// `● PRESSURE  ● FLOW  ⚊ TEMP`. Telemetry colors are the shared brand palette.
@Composable
private fun ProfilePreviewLegend(modifier: Modifier = Modifier) {
    val tel = CremaTheme.telemetry
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("Pressure", tel.pressure, dashed = false)
        LegendItem("Flow", tel.flow, dashed = false)
        LegendItem("Temp", tel.temp, dashed = true)
    }
}

@Composable
private fun LegendItem(label: String, color: Color, dashed: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dashed) {
            // Short dashed segment — the temp channel's marker (it's a dashed line).
            Canvas(Modifier.size(width = 10.dp, height = 2.dp)) {
                drawLine(
                    color,
                    Offset(0f, size.height / 2f),
                    Offset(size.width, size.height / 2f),
                    strokeWidth = size.height,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                )
            }
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        }
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 8.5.sp,
                lineHeight = 8.5.sp,
                letterSpacing = 0.4.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Copper "LOADED" badge — the active-card head pill.
@Composable
private fun LoadedBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text("LOADED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
    }
}

// The profile-card curve preview is now CanvasProfilePreview (a faithful Canvas
// port of the web ProfilePreview) — see CanvasProfilePreview.kt.

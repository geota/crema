package coffee.crema.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import coffee.crema.ui.convertTemp
import coffee.crema.ui.convertWeight
import coffee.crema.ui.formatRatio
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
import coffee.crema.profiles.effectiveProfileFilter
import coffee.crema.profiles.filterAndSortProfiles
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaIconTone
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaCardSpec
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.CremaSearchPill
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
    // Hidden facet falls back to All when nothing is archived; both the chips and
    // the grid key off effectiveFilter (issue 28).
    val effectiveFilter = effectiveProfileFilter(filter, ui.hiddenProfileIds)
    val sorted = filterAndSortProfiles(ui.profiles, ui.hiddenProfileIds, query, filter, sort, sortDesc, ui.activeProfileId)

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "profiles",
            onNav = onNav,
            machineConnected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Command bar: title · search · actions in one row. On a narrow 7"/
            // portrait tablet (<840dp) the labelled buttons would crowd out the title
            // (collapsing it to a 1-char column), so they switch to icon-only and the
            // search flexes — everything still fits one row.
            val pinned = ui.profiles.count { it.pinned }
            val narrowBar = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 840
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
                        maxLines = 1,
                    )
                    Text(
                        "${ui.profiles.size} saved · $pinned pinned to favorites",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                CremaSearchPill(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search profiles…",
                    modifier = if (narrowBar) Modifier.weight(1f) else Modifier.width(240.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (narrowBar) {
                    CremaIconButton("upload-simple", { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }, tone = CremaIconTone.Tonal)
                    Spacer(Modifier.width(4.dp))
                    CremaIconButton("download-simple", { launchSave("crema-profiles.json", vm.allProfilesJson()) }, tone = CremaIconTone.Tonal)
                    Spacer(Modifier.width(4.dp))
                    CremaIconButton("plus", { vm.startNewProfile(); onNav("profile-edit") }, tone = CremaIconTone.Filled)
                } else {
                    CremaButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }, variant = CremaButtonVariant.Outlined, icon = "upload-simple", label = "Import")
                    Spacer(Modifier.width(8.dp))
                    CremaButton(onClick = { launchSave("crema-profiles.json", vm.allProfilesJson()) }, variant = CremaButtonVariant.Outlined, icon = "download-simple", label = "Export")
                    Spacer(Modifier.width(8.dp))
                    CremaButton(onClick = { vm.startNewProfile(); onNav("profile-edit") }, icon = "plus", label = "New profile")
                }
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
                // Adaptive so a narrow 7" tablet drops to 2 columns (wider cards →
                // the "Load on Brew" button keeps its label) while the 10" keeps 3.
                columns = GridCells.Adaptive(minSize = 320.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(CremaCardSpec.gridGap),
                verticalArrangement = Arrangement.spacedBy(CremaCardSpec.gridGap),
                contentPadding = CremaCardSpec.gridContentPadding,
            ) {
                items(sorted, key = { it.id }) { profile ->
                    ProfileCard(
                        profile = profile,
                        isActive = profile.id == ui.activeProfileId,
                        weightUnit = ui.weightUnit,
                        tempUnit = ui.tempUnit,
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
    weightUnit: String,
    tempUnit: String,
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
                if (isActive) ActiveBadge() else Spacer(Modifier.size(1.dp))
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
            ProfileMetricsRow(profile, weightUnit, tempUnit)
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
private fun ProfileMetricsRow(profile: CremaProfile, weightUnit: String, tempUnit: String) {
    val dose = convertWeight(profile.dose, weightUnit)
    val temp = convertTemp(profile.brewTemp, tempUnit)
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProfileMetric("Ratio", formatRatio(profile.dose, profile.yieldOut), "", Modifier.weight(1f))
            ProfileMetric("Dose", dose.value, dose.unit, Modifier.weight(1f))
            ProfileMetric("Temp", temp.value, temp.unit, Modifier.weight(1f))
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

// Copper "ACTIVE" badge — the active-card head pill (web .pp-active: outlined
// copper pill, not a solid fill; same term as the web shell).
@Composable
private fun ActiveBadge() {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

// The profile-card curve preview is now CanvasProfilePreview (a faithful Canvas
// port of the web ProfilePreview) — see CanvasProfilePreview.kt.

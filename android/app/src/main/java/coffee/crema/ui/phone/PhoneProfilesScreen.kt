package coffee.crema.ui.phone

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.profiles.CremaProfile
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.convertTemp
import coffee.crema.ui.convertWeight
import coffee.crema.ui.formatRatio
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.*
import coffee.crema.ui.screens.CanvasProfilePreview

/*
 * PhoneProfilesScreen — the handset Profiles library (port of
 * prototype/phone/phone-profiles.jsx), wired to LIVE state.
 *
 * The tablet's 3-column card grid collapses to a single-column list. The
 * pressure curve is each profile's identity, so it leads every card; the
 * loaded profile gets a copper border + "LOADED" badge. Card overflow moves
 * into a modal bottom sheet (CremaOverflowSheet); New becomes an extended FAB.
 */
@Composable
fun PhoneProfilesScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("name") }
    var sortDesc by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<CremaProfile?>(null) }
    var confirmDelete by remember { mutableStateOf<CremaProfile?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importProfiles(uri)
    }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = pendingExport; pendingExport = null
        if (uri != null && t != null) vm.writeTextToUri(uri, t)
    }

    // Same facet model as the tablet: Hidden appears only when something is
    // archived, and falls back to All once the last one is restored.
    val effectiveFilter = if (filter == "hidden" && ui.hiddenProfileIds.isEmpty()) "all" else filter
    val filtered = ui.profiles.filter { p ->
        val isHidden = p.id in ui.hiddenProfileIds
        (query.isBlank() ||
            p.name.contains(query, ignoreCase = true) ||
            p.tags.any { it.contains(query, ignoreCase = true) } ||
            p.notes.contains(query, ignoreCase = true) ||
            p.author.contains(query, ignoreCase = true) ||
            (p.roast?.contains(query, ignoreCase = true) == true)) &&
            when (effectiveFilter) {
                "hidden" -> isHidden
                "pinned" -> !isHidden && p.pinned
                "all" -> !isHidden
                else -> !isHidden && p.roast?.equals(effectiveFilter, ignoreCase = true) == true
            }
    }
    val roastOrder = mapOf("light" to 0, "medium" to 1, "dark" to 2)
    val sortedAsc = when (sort) {
        "roast" -> filtered.sortedBy { roastOrder[it.roast?.lowercase()] ?: 3 }
        "pinned" -> filtered.sortedBy { if (it.pinned) 0 else 1 }
        else -> filtered.sortedBy { it.name.lowercase() }
    }
    val sorted = if (sortDesc) sortedAsc.reversed() else sortedAsc

    Scaffold(
        topBar = {
            CremaPhoneTopBar(
                title = "Profiles",
                actions = listOf(
                    BarAction("upload-simple") { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                    BarAction("download-simple") {
                        vm.allProfilesJson()?.let { pendingExport = it; saveLauncher.launch("crema-profiles.json") }
                    },
                    BarAction("gear-six") { onNav("settings") },
                ),
            )
        },
        floatingActionButton = {
            CremaNewFab(label = "New") { vm.startNewProfile(); onNav("profile-edit") }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            val visible = ui.profiles.filter { it.id !in ui.hiddenProfileIds }
            CremaFilterChipRow(
                chips = buildList {
                    add(FilterChipSpec("all", "All", visible.size))
                    add(FilterChipSpec("pinned", "Pinned", visible.count { it.pinned }, icon = "star"))
                    add(FilterChipSpec("light", "Light", visible.count { it.roast.equals("light", true) }))
                    add(FilterChipSpec("medium", "Medium", visible.count { it.roast.equals("medium", true) }))
                    add(FilterChipSpec("dark", "Dark", visible.count { it.roast.equals("dark", true) }))
                    if (ui.hiddenProfileIds.isNotEmpty()) add(FilterChipSpec("hidden", "Hidden", ui.hiddenProfileIds.size))
                },
                selected = effectiveFilter,
                onSelect = { filter = it },
                trailing = {
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
                },
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = CremaEdge, end = CremaEdge, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    CremaPhoneSearch(query = query, onQueryChange = { query = it }, placeholder = "Search profiles")
                }
                items(sorted, key = { it.id }) { profile ->
                    PhoneProfileCard(
                        profile = profile,
                        isLoaded = profile.id == ui.activeProfileId,
                        weightUnit = ui.weightUnit,
                        tempUnit = ui.tempUnit,
                        onLoad = { vm.setActiveProfile(profile.id) },
                        onEdit = {
                            if (profile.source == "custom") vm.startEditProfile(profile.id) else vm.duplicateProfile(profile.id)
                            onNav("profile-edit")
                        },
                        onMenu = { menuFor = profile },
                    )
                }
                if (sorted.isEmpty()) {
                    item {
                        Text(
                            if (query.isBlank()) "No profiles in this view." else "No profiles match your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }
    }

    // Card overflow — modal bottom sheet (the tablet's kebab menu re-homed).
    menuFor?.let { p ->
        val isCustom = p.source == "custom"
        val isHidden = p.id in ui.hiddenProfileIds
        CremaOverflowSheet(
            title = p.name,
            items = buildList {
                add(SheetItem("star", if (p.pinned) "Unpin from favourites" else "Pin to favourites") { vm.togglePinProfile(p.id) })
                add(SheetItem("copy", "Duplicate") { vm.duplicateProfile(p.id); onNav("profile-edit") })
                add(SheetItem("download-simple", "Download", sub = "Export as .json profile") { vm.exportProfile(p.id) })
                add(SheetItem(divider = true))
                when {
                    isHidden -> add(SheetItem("arrow-counter-clockwise", "Restore profile") { vm.unarchiveBuiltinProfile(p.id) })
                    isCustom -> add(SheetItem("trash", "Delete profile", danger = true) { confirmDelete = p })
                    else -> add(SheetItem("archive", "Hide from library", sub = "Built-in profile", danger = true) { vm.archiveBuiltinProfile(p.id) })
                }
            },
            onDismiss = { menuFor = null },
        )
    }

    confirmDelete?.let { p ->
        CremaConfirmDialog(
            title = "Delete profile?",
            body = "“${p.name}” will be removed. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { vm.deleteProfile(p.id); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
}

// ── Profile card (proto .pp-card) ────────────────────────────────────────────
@Composable
private fun PhoneProfileCard(
    profile: CremaProfile,
    isLoaded: Boolean,
    weightUnit: String,
    tempUnit: String,
    onLoad: () -> Unit,
    onEdit: () -> Unit,
    onMenu: () -> Unit,
) {
    val loadedBg =
        if (isLoaded) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            .compositeOverSurface(MaterialTheme.colorScheme.surfaceContainer)
        else MaterialTheme.colorScheme.surfaceContainer
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = loadedBg,
        border = if (isLoaded) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            // Head: pin + name + sub, LOADED badge.
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        if (profile.pinned) PhIcon("star-fill", sizeDp = 14, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            profile.name,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Medium),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        if (profile.source == "custom") "Custom profile" else "Built-in profile",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (isLoaded) {
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = MaterialTheme.colorScheme.primary,
                    ) {
                        Row(
                            Modifier.height(24.dp).padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            PhIcon("check", sizeDp = 11, tint = MaterialTheme.colorScheme.onPrimary)
                            Text(
                                "LOADED",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.4.sp, fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }
            }

            // Curve banner — the profile's identity.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            ) {
                CanvasProfilePreview(segments = profile.segments, modifier = Modifier.fillMaxSize())
            }

            // Foot: roast pill + 4-up metric strip.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profile.roast?.let { RoastPill(it) }
                Spacer(Modifier.weight(1f))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val cardDose = convertWeight(profile.dose, weightUnit)
                    val cardTemp = convertTemp(profile.brewTemp, tempUnit)
                    CardMetric(formatRatio(profile.dose, profile.yieldOut), "RATIO")
                    CardMetric("${cardDose.value}${cardDose.unit}", "DOSE")
                    CardMetric("${cardTemp.value}${cardTemp.unit}", "TEMP")
                    CardMetric("${profile.preinfuseSeconds}s", "PRE")
                }
            }

            // Actions: Load (pill) + edit + overflow.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    onClick = onLoad,
                    shape = RoundedCornerShape(999.dp),
                    color = if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.weight(1f).height(40.dp),
                ) {
                    Row(
                        Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PhIcon(
                            if (isLoaded) "check-circle" else "arrow-line-down",
                            sizeDp = 16,
                            tint = if (isLoaded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(
                            if (isLoaded) "Loaded on Brew" else "Load on Brew",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                            color = if (isLoaded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                RoundIconButton("pencil-simple", onEdit)
                RoundIconButton("dots-three-vertical", onMenu)
            }
        }
    }
}

// 40dp round neutral icon button (proto .pm-iconbtn).
@Composable
internal fun RoundIconButton(icon: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.size(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            PhIcon(icon, sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// Roast pill — copper-wash (primary @12%), matching the tablet + web. No per-band
// tints (issue 42): a Light bean reads the same copper as every other roast, and
// the corner radius is fully rounded (999dp) like the tablet pill.
@Composable
internal fun RoastPill(roast: String) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .height(24.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            roast.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.3.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

// Mono value over a tiny tracked-out label (proto .pp-metric).
@Composable
internal fun CardMetric(value: String, label: String) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            value,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = coffee.crema.ui.theme.JetBrainsMono,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 15.sp,
                fontFeatureSettings = "tnum",
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

// Blend a translucent accent over an opaque surface (CSS color-mix equivalent).
private fun androidx.compose.ui.graphics.Color.compositeOverSurface(
    surface: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.graphics.Color =
    androidx.compose.ui.graphics.Color(
        red = red * alpha + surface.red * (1 - alpha),
        green = green * alpha + surface.green * (1 - alpha),
        blue = blue * alpha + surface.blue * (1 - alpha),
        alpha = 1f,
    )

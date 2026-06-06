package coffee.crema.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.window.Dialog
import coffee.crema.ui.components.CremaTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import coffee.crema.ui.theme.JetBrainsMono
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.beans.daysOffRoast
import coffee.crema.beans.roastBand
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.CremaOverflowMenu
import coffee.crema.ui.components.OverflowItem
import coffee.crema.ui.components.CremaSegmentedButton
import coffee.crema.ui.components.SegOption
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.CremaFilterDivider
import coffee.crema.ui.components.CremaFilterGroupLabel
import coffee.crema.ui.components.CremaValueUnit
import coffee.crema.ui.components.CremaSortControl
import coffee.crema.ui.components.SortKey
import androidx.compose.material3.IconButton

/*
 * Beans (library) — M3 v1. The bean bags the user has on hand, persisted via
 * LibraryStore. A grid of bean cards (roaster · name, roast band, days off
 * roast), each with set-active (→ the Brew bean block) and delete, plus an
 * "Add bean" dialog. days-off-roast is computed shell-side for v1; the freshness
 * band/colour belongs in the core (FFI follow-up).
 *
 * Later M3 increments: the full bean editor (origin, grind, tasting notes,
 * burn-down), Beanconqueror import (import_beanconqueror_json), and roasters.
 */
@Composable
fun BeansScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    var tab by remember { mutableStateOf("bags") }
    var roasterDialogOpen by remember { mutableStateOf(false) }
    var roasterEditing by remember { mutableStateOf<Roaster?>(null) }
    var query by remember { mutableStateOf("") }
    var beanFilter by remember { mutableStateOf("all") }
    var beanSort by remember { mutableStateOf("freshest") }
    var beanSortDesc by remember { mutableStateOf(false) }
    // Beanconqueror import — the system file picker hands back a Uri the VM reads
    // (single JSON or a .zip archive) and merges via the core importer.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBeanconquerorUri(uri)
    }

    val roasterNameOf: (Bean) -> String? = { b -> ui.roasters.firstOrNull { it.id == b.roasterId }?.name }
    // Bags — client-side search + filter + sort over the in-memory library.
    val visibleBeans = ui.beans.filter { b ->
        val matchesSearch = query.isBlank() ||
            b.name.contains(query, ignoreCase = true) ||
            (roasterNameOf(b)?.contains(query, ignoreCase = true) == true) ||
            (b.origin.country?.contains(query, ignoreCase = true) == true)
        // Archived bags are hidden from every view EXCEPT the Archived filter
        // (proto: "All" counts non-archived; "Archived" surfaces them).
        val matchesFilter = when (beanFilter) {
            "archived" -> b.archivedAt != null
            "active" -> b.archivedAt == null && b.id == ui.activeBeanId
            "favourite" -> b.archivedAt == null && b.favourite
            "frozen" -> b.archivedAt == null && b.frozenOn != null
            "light", "medium", "dark" -> b.archivedAt == null && roastBand(b.roastLevel?.toInt())?.equals(beanFilter, ignoreCase = true) == true
            else -> b.archivedAt == null
        }
        matchesSearch && matchesFilter
    }
    val beansAsc = when (beanSort) {
        "name" -> visibleBeans.sortedBy { it.name.lowercase() }
        "roast" -> visibleBeans.sortedBy { it.roastLevel?.toInt() ?: Int.MAX_VALUE }
        "remaining" -> visibleBeans.sortedBy { it.remaining }
        else -> visibleBeans.sortedBy { daysOffRoast(it.roastedOn) ?: Int.MAX_VALUE } // freshest first
    }
    val sortedBeans = if (beanSortDesc) beansAsc.reversed() else beansAsc
    val visibleRoasters = ui.roasters.filter {
        query.isBlank() || it.name.contains(query, ignoreCase = true) ||
            (it.city?.contains(query, ignoreCase = true) == true) ||
            (it.country?.contains(query, ignoreCase = true) == true)
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "beans",
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
                        "Beans",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${ui.beans.size} bags · ${ui.roasters.size} roasters · ${ui.beans.count { it.id == ui.activeBeanId }} active",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Compact search pill (matched to the 40dp button height), the
                // command-bar sibling of Profiles' search.
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
                                Text("Search beans, roasters…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                CremaButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    variant = CremaButtonVariant.Outlined,
                    icon = "upload-simple",
                    label = "Import",
                )
                Spacer(Modifier.width(8.dp))
                CremaButton(
                    onClick = { if (tab == "bags") { vm.startNewBean(); onNav("bean-edit") } else { roasterEditing = null; roasterDialogOpen = true } },
                    icon = "plus",
                    label = if (tab == "bags") "Add bean" else "Add roaster",
                )
            }
            // Bags / Roasters — a split (segmented) button on its own row, above the
            // filters: it picks WHAT you're viewing, distinct from how you filter bags.
            CremaSegmentedButton(
                options = listOf(
                    SegOption("bags", "Bags  ${ui.beans.size}"),
                    SegOption("roasters", "Roasters  ${ui.roasters.size}"),
                ),
                value = tab,
                onChange = { tab = it },
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            // Filter rail (Bags): STATUS group · full-height divider · ROAST group, sort
            // pinned right. IntrinsicSize.Min lets the divider stretch the row height
            // (PWA .bn-tabs-divider: align-self: stretch); group labels sit centered.
            if (tab == "bags") {
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val nonArchived = ui.beans.filter { it.archivedAt == null }
                    CremaFilterGroupLabel("Status")
                    listOf("all" to "All", "active" to "Active", "favourite" to "Favourite", "frozen" to "Frozen", "archived" to "Archived").forEach { (id, label) ->
                        val count = when (id) {
                            "all" -> nonArchived.size
                            "active" -> nonArchived.count { it.id == ui.activeBeanId }
                            "favourite" -> nonArchived.count { it.favourite }
                            "frozen" -> nonArchived.count { it.frozenOn != null }
                            else -> ui.beans.count { it.archivedAt != null }
                        }
                        CremaFilterChip(label = label, selected = beanFilter == id, count = count, onClick = { beanFilter = id })
                    }
                    CremaFilterDivider()
                    CremaFilterGroupLabel("Roast")
                    listOf("light" to "Light", "medium" to "Medium", "dark" to "Dark").forEach { (id, label) ->
                        val count = nonArchived.count { roastBand(it.roastLevel?.toInt())?.equals(id, ignoreCase = true) == true }
                        CremaFilterChip(label = label, selected = beanFilter == id, count = count, onClick = { beanFilter = id })
                    }
                    Spacer(Modifier.weight(1f))
                    CremaSortControl(
                        keys = listOf(
                            SortKey("freshest", "Freshest", "clock"),
                            SortKey("name", "Name", "sort-ascending"),
                            SortKey("roast", "Roast", "fire"),
                            SortKey("remaining", "Remaining", "gauge"),
                        ),
                        selectedKey = beanSort,
                        descending = beanSortDesc,
                        onKeyChange = { beanSort = it },
                        onToggleDirection = { beanSortDesc = !beanSortDesc },
                    )
                }
            }
            if (tab == "bags") {
                if (sortedBeans.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (ui.beans.isEmpty()) "No beans yet — add a bag to get started." else "No beans match your search or filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    ) {
                        items(sortedBeans, key = { it.id }) { bean ->
                            BeanCard(
                                bean = bean,
                                roasterName = ui.roasters.firstOrNull { it.id == bean.roasterId }?.name,
                                isActive = bean.id == ui.activeBeanId,
                                onSetActive = { vm.setActiveBean(bean.id) },
                                onEdit = { vm.startEditBean(bean.id); onNav("bean-edit") },
                                onDuplicate = { vm.duplicateBean(bean.id) },
                                onFreezeToggle = { if (bean.frozenOn != null) vm.defrostBean(bean.id) else vm.freezeBean(bean.id) },
                                onArchiveToggle = { if (bean.archivedAt != null) vm.unarchiveBean(bean.id) else vm.archiveBean(bean.id) },
                                onToggleFavourite = { vm.toggleBeanFavourite(bean.id) },
                                onDelete = { vm.deleteBean(bean.id) },
                            )
                        }
                    }
                }
            } else {
                if (visibleRoasters.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (ui.roasters.isEmpty()) "No roasters yet — add one to group your bags." else "No roasters match your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    ) {
                        items(visibleRoasters, key = { it.id }) { roaster ->
                            RoasterCard(
                                roaster = roaster,
                                bagCount = ui.beans.count { it.roasterId == roaster.id },
                                onEdit = { roasterEditing = roaster; roasterDialogOpen = true },
                                onVisit = { vm.visitRoasterWebsite(roaster.website) },
                                onDelete = { vm.deleteRoaster(roaster.id) },
                            )
                        }
                    }
                }
            }
        }
    }

    if (roasterDialogOpen) {
        RoasterDialog(
            initial = roasterEditing,
            onSave = { name, website, city, country, notes ->
                val editing = roasterEditing
                if (editing == null) vm.addRoaster(name, website, city, country, notes)
                else vm.updateRoaster(editing.id, name, website, city, country, notes)
            },
            onDismiss = { roasterDialogOpen = false },
        )
    }
}

@Composable
private fun BeanCard(
    bean: Bean,
    roasterName: String?,
    isActive: Boolean,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onFreezeToggle: () -> Unit,
    onArchiveToggle: () -> Unit,
    onToggleFavourite: () -> Unit,
    onDelete: () -> Unit,
) {
    val band = roastBand(bean.roastLevel?.toInt())
    val days = daysOffRoast(bean.roastedOn)
    val frozen = bean.frozenOn != null
    val tagList = bean.tags?.filter { it.isNotBlank() }.orEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    CremaCard(
        modifier = Modifier.fillMaxWidth(),
        container = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                BeanAvatar(roasterName ?: bean.name)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (roasterName != null) Eyebrow(roasterName)
                    Text(
                        bean.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    bean.origin.country?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onToggleFavourite, modifier = Modifier.size(32.dp)) {
                    PhIcon(if (bean.favourite) "star-fill" else "star", sizeDp = 18, tint = if (bean.favourite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val pills = buildList {
                band?.let { add(it to true) }
                if (frozen) add("Frozen" to false)
                if (bean.decaf) add("Decaf" to false)
                tagList.forEach { add(it to false) }
            }
            if (pills.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    pills.take(4).forEach { (t, isRoast) -> Pill(t, roast = isRoast) }
                }
            }
            // Stats row (PWA .bn-tile-stats): off-roast · opened/frozen · rating —
            // three cells, each a dot/icon + mono value + dimmed uppercase label,
            // with the 5-star rating pushed to the right.
            val openedDays = daysOffRoast(bean.openedOn)
            val frozenDays = daysOffRoast(bean.frozenOn)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BeanStat(
                    Modifier.weight(1f),
                    leading = { Box(Modifier.size(6.dp).clip(CircleShape).background(freshnessColor(frozen, days))) },
                    value = days?.let { "${it}d" } ?: "—",
                    label = "off roast",
                )
                Box(Modifier.weight(1f)) {
                    when {
                        frozen -> BeanStat(leading = { PhIcon("snowflake", sizeDp = 12, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, value = frozenDays?.let { "${it}d" } ?: "—", label = "frozen")
                        openedDays != null -> BeanStat(leading = { PhIcon("package", sizeDp = 12, tint = MaterialTheme.colorScheme.onSurfaceVariant) }, value = "${openedDays}d", label = "open")
                        else -> Text("—", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    val r = bean.rating.toInt()
                    (1..5).forEach { n ->
                        PhIcon(if (n <= r) "star-fill" else "star", sizeDp = 11, tint = if (n <= r) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                    }
                }
            }
            if (bean.bagSize > 0f) {
                val pct = (bean.remaining / bean.bagSize).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(Modifier.fillMaxWidth(pct).height(6.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primary))
                    }
                    Text(
                        "${bean.remaining.toInt()} / ${bean.bagSize.toInt()} g",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CremaButton(
                    onClick = onSetActive,
                    modifier = Modifier.weight(1f),
                    variant = if (isActive) CremaButtonVariant.Outlined else CremaButtonVariant.Tonal,
                    icon = if (isActive) "check-circle" else null,
                    label = if (isActive) "Active for brew" else "Set active",
                )
                FilledTonalIconButton(onClick = onDuplicate) { PhIcon("copy", sizeDp = 18) }
                FilledTonalIconButton(onClick = onEdit) { PhIcon("pencil-simple", sizeDp = 18) }
                CremaOverflowMenu(items = buildList {
                    add(OverflowItem(if (frozen) "drop" else "snowflake", if (frozen) "Defrost" else "Freeze bag", onFreezeToggle))
                    add(OverflowItem("archive", if (bean.archivedAt != null) "Unarchive" else "Archive", onArchiveToggle))
                    add(OverflowItem("trash", "Delete bean", { confirmDelete = true }, danger = true))
                })
            }
        }
    }
    if (confirmDelete) {
        CremaConfirmDialog(
            title = "Delete bean?",
            body = "“${bean.name}” will be removed. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { onDelete(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

// Roast variant = uppercase copper-tinted; everything else = neutral.
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

// One bean-card stat cell — leading dot/icon + a mono value over a dimmed
// uppercase label (PWA .bn-tile-stat: val on top, 9px caps label below).
@Composable
private fun BeanStat(modifier: Modifier = Modifier, leading: @Composable () -> Unit, value: String, label: String) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        leading()
        Column {
            Text(
                value,
                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum", lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp, lineHeight = 11.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
        }
    }
}

// Freshness status colour, keyed off days-off-roast (freeze pauses the counter).
// Resting (<4d) / aging (22–40d) = amber; peak (4–21d) = green; past-peak = muted
// red; frozen = icy blue; unknown = neutral. Semantic status hues (not theme tokens).
private fun freshnessColor(frozen: Boolean, days: Int?): Color = when {
    frozen -> Color(0xFF7FB0E0)
    days == null -> Color(0xFF8A8175)
    days < 4 -> Color(0xFFDBA764)
    days <= 21 -> Color(0xFF5FB87A)
    days <= 40 -> Color(0xFFDBA764)
    else -> Color(0xFFC58B8B)
}

// A 44dp roaster-mark avatar — a theme-color square keyed off the seed name.
@Composable
private fun BeanAvatar(seed: String) {
    val palette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
    )
    val color = palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            seed.take(1).uppercase(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

// A roaster directory card — avatar + name + "City · Country · N bags", with a
// kebab (Edit / Visit website / Delete). Proto's kebab-only roaster pattern.
@Composable
private fun RoasterCard(
    roaster: Roaster,
    bagCount: Int,
    onEdit: () -> Unit,
    onVisit: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    CremaCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                BeanAvatar(roaster.name)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        roaster.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val place = listOfNotNull(roaster.city, roaster.country).joinToString(" · ")
                    val sub = listOfNotNull(place.ifBlank { null }, "$bagCount ${if (bagCount == 1) "bag" else "bags"}").joinToString(" · ")
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CremaOverflowMenu(items = buildList {
                    add(OverflowItem("pencil-simple", "Edit roaster", onEdit))
                    if (!roaster.website.isNullOrBlank()) add(OverflowItem("arrow-square-out", "Visit website", onVisit))
                    add(OverflowItem("trash", "Delete roaster", { confirmDelete = true }, danger = true))
                })
            }
            roaster.website?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (confirmDelete) {
        CremaConfirmDialog(
            title = "Delete roaster?",
            body = "“${roaster.name}” will be removed. Its bags keep their data but lose the roaster link. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { onDelete(); confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun RoasterDialog(
    initial: Roaster?,
    onSave: (name: String, website: String, city: String, country: String, notes: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var website by remember { mutableStateOf(initial?.website ?: "") }
    var city by remember { mutableStateOf(initial?.city ?: "") }
    var country by remember { mutableStateOf(initial?.country ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 0.dp,
            shadowElevation = 12.dp,
            modifier = Modifier.widthIn(max = 460.dp),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    if (initial == null) "Add roaster" else "Edit roaster",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                CremaTextField(name, { name = it }, "Name", placeholder = "e.g. Onyx Coffee Lab")
                CremaTextField(website, { website = it }, "Website", placeholder = "https://…")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CremaTextField(city, { city = it }, "City", Modifier.weight(1f))
                    CremaTextField(country, { country = it }, "Country", Modifier.weight(1f))
                }
                CremaTextField(notes, { notes = it }, "Notes", placeholder = "Tasting style, subscription…", singleLine = false, minLines = 2)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    CremaButton(onClick = onDismiss, variant = CremaButtonVariant.Text, label = "Cancel")
                    CremaButton(
                        onClick = { onSave(name, website, city, country, notes); onDismiss() },
                        enabled = name.isNotBlank(),
                        label = if (initial == null) "Add roaster" else "Save",
                    )
                }
            }
        }
    }
}


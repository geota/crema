package coffee.crema.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.draw.alpha
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.beans.chipCounts
import coffee.crema.beans.filterBeanFacets
import coffee.crema.beans.includeArchivedApplies
import coffee.crema.beans.sortFilteredBeans
import coffee.crema.beans.beanDaysOffRoast
import coffee.crema.beans.daysOffRoast
import coffee.crema.beans.explanatory
import coffee.crema.beans.forField
import coffee.crema.beans.isFrozen
import coffee.crema.beans.roastBand
import coffee.crema.beans.roastBand5
import coffee.crema.beans.searchBeans
import coffee.crema.beans.searchRoasters
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.core.SearchField
import coffee.crema.core.SearchHit
import coffee.crema.ui.BeansViewState
import coffee.crema.ui.beansListKey
import coffee.crema.ui.rememberGridMemoryState
import coffee.crema.ui.rememberListBool
import coffee.crema.ui.rememberListString
import coffee.crema.ui.roastersListKey
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.rememberBeansViewState
import coffee.crema.ui.beans.linkedProfileNameFor
import coffee.crema.ui.beans.shotRowSummary
import coffee.crema.ui.freshnessColor
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaIconTone
import coffee.crema.ui.components.CremaStarRating
import coffee.crema.ui.components.CremaSplitButton
import coffee.crema.ui.components.SplitMenuItem
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.CremaSearchPill
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.SearchWhy
import coffee.crema.ui.components.UPPERCASE
import coffee.crema.ui.components.highlighted
import coffee.crema.ui.components.BeanAvatar
import coffee.crema.ui.components.RoasterMarkAvatar
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.CremaOverflowMenu
import coffee.crema.ui.components.OverflowItem
import coffee.crema.ui.components.CremaTabSwitch
import coffee.crema.ui.components.TabOption
import coffee.crema.ui.components.CremaEmptyState
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.CremaFilterDivider
import coffee.crema.ui.components.CremaFilterGroupLabel
import coffee.crema.ui.components.CremaValueUnit
import coffee.crema.ui.components.CremaSortControl
import coffee.crema.ui.components.SortKey
import androidx.compose.material3.IconButton
import coffee.crema.beans.roasterBagCountLabel

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
    beansState: BeansViewState = rememberBeansViewState(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    // Tab / filter / roaster scope live in the hoisted [BeansViewState]:
    // editing a bag navigates to its own destination, and rotating across the
    // 840dp breakpoint swaps in the phone host — local state was dropped either
    // way (a roaster's shelf, #86, came back as the unscoped library).
    var tab by beansState::tab
    var roasterDialogOpen by remember { mutableStateOf(false) }
    var roasterEditing by remember { mutableStateOf<Roaster?>(null) }
    // Search + sort live in the hoisted ListMemory (issue #123), shared with
    // the phone: the detail / editor round trips and a host swap keep them.
    // The bag facets stay in BeansViewState (#124, with its saved-filter
    // migration), which is hoisted the same way.
    var query by rememberListString("beans/query", "")
    // Roaster scope (#86): set by tapping a roaster card; the Bags tab then
    // shows that roaster's shelf, archived bags included. Cleared by its chip
    // or by Back.
    var roasterScopeId by beansState::roasterScopeId
    var beanSort by rememberListString("beans/sort", "freshest")
    var beanSortDesc by rememberListBool("beans/sortDesc", false)
    // The bag whose read-only detail is open (issue 61). Held as an id, not a
    // Bean, so the sheet re-renders live as the bag is favourited/archived
    // from inside it.
    // Hoisted into BeansViewState (#96) so the bag detail — and a Log-brew
    // form opened from it (issue #10) — survive the phone↔tablet host swap.
    var detailBeanId by beansState::detailBeanId
    // Beanconqueror import — the system file picker hands back a Uri the VM reads
    // (single JSON or a .zip archive) and merges via the core importer.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importBeanconquerorUri(uri)
    }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = pendingExport; pendingExport = null
        if (uri != null && t != null) vm.writeTextToUri(uri, t)
    }
    val launchSave: (String, String?) -> Unit = { name, content -> if (content != null) { pendingExport = content; saveLauncher.launch(name) } }

    // Bags — core-ranked search (issue 62) + facet filter + sort over the
    // in-memory library. The search itself is memoised on the library, so a
    // keystroke costs one FFI call rather than a re-serialisation.
    val beanHits = searchBeans(ui.beans, ui.roasters, query)
    val roasterHits = searchRoasters(ui.roasters, query)
    // The scope only applies while its roaster exists — deleting the roaster
    // (its card kebab) while scoped must not leave an empty, chip-less list.
    val scopeRoaster = roasterScopeId?.let { id -> ui.roasters.firstOrNull { it.id == id } }
    val scopeId = scopeRoaster?.id
    // Facets + chip counts from the core (#124): status, include-archived,
    // roast, tags, the shelf scope and the search compose.
    val facets = beansState.facets(scopeId)
    val facetResult = filterBeanFacets(ui.beans, facets, beanHits)
    val sortedBeans = sortFilteredBeans(ui.beans, facetResult.ids, beanHits, beanSort, beanSortDesc, ui.activeBeanId)
    // Back from a roaster's shelf returns to the Roasters directory.
    BackHandler(enabled = scopeId != null && tab == "bags") { beansState.closeShelf() }
    val visibleRoasters = coffee.crema.beans.directoryRoasters(ui.roasters, beansState.showDuplicates)
        .filter { roasterHits.matches(it.id) }
        .sortedByDescending { roasterHits.score(it.id) }
    // Merge suggestions (core rule) — the Roasters tab's "X looks like Y" banners.
    val duplicatePairs = remember(ui.roasters) { vm.roasterDuplicates(ui.roasters) }
    val mergeSuggestions = coffee.crema.beans.mergeSuggestions(duplicatePairs, ui.roasters, ui.beans, beansState.dismissedDuplicates)
    val hasTaggedDupes = ui.roasters.any { it.canonicalRoasterId != null }

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
                val narrowBar = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 840
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow("Library")
                    Text(
                        "Beans",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    // Web /beans sub-header: status counts, not totals.
                    Text(
                        run {
                            val act = ui.beans.count { it.archivedAt == null && !it.isFrozen }
                            val froz = ui.beans.count { it.archivedAt == null && it.isFrozen }
                            val arch = ui.beans.count { it.archivedAt != null }
                            "$act active · $froz frozen · $arch archived · ${ui.roasters.size} roasters"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                // Compact search pill (matched to the 40dp button height), the
                // command-bar sibling of Profiles' search.
                CremaSearchPill(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search beans, origin, notes…",
                    modifier = if (narrowBar) Modifier.weight(1f) else Modifier.width(240.dp),
                )
                Spacer(Modifier.width(8.dp))
                if (narrowBar) {
                    // 7"/portrait: icon-only actions so the title + search keep their
                    // width. The Export menu's secondary format (Beanconqueror) is a
                    // landscape-only affordance; the icon runs the primary Crema backup.
                    CremaIconButton("upload-simple", { importLauncher.launch(arrayOf("*/*")) }, tone = CremaIconTone.Tonal)
                    Spacer(Modifier.width(4.dp))
                    CremaIconButton("download-simple", { launchSave("crema-beans.json", vm.beansLibraryJson()) }, tone = CremaIconTone.Tonal)
                    Spacer(Modifier.width(4.dp))
                    CremaIconButton("plus", { if (tab == "bags") { vm.startNewBean(); onNav("bean-edit") } else { roasterEditing = null; roasterDialogOpen = true } }, tone = CremaIconTone.Filled)
                } else {
                    CremaButton(
                        onClick = { importLauncher.launch(arrayOf("*/*")) },
                        variant = CremaButtonVariant.Outlined,
                        icon = "upload-simple",
                        label = "Import",
                    )
                    Spacer(Modifier.width(8.dp))
                    CremaSplitButton(
                        icon = "download-simple",
                        label = "Export",
                        menuHead = "Export as",
                        onPrimary = { launchSave("crema-beans.json", vm.beansLibraryJson()) },
                        items = listOf(
                            SplitMenuItem("file-text", "Crema backup", "Lossless round-trip — beans and roasters. Re-importable in Crema.") { launchSave("crema-beans.json", vm.beansLibraryJson()) },
                            SplitMenuItem("file-zip", "Beanconqueror", "For sharing with Beanconqueror users. Crema-only fields like tags don't survive.") { launchSave("crema-to-beanconqueror.json", vm.beansBeanconquerorJson()) },
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    CremaButton(
                        onClick = { if (tab == "bags") { vm.startNewBean(); onNav("bean-edit") } else { roasterEditing = null; roasterDialogOpen = true } },
                        icon = "plus",
                        label = if (tab == "bags") "Add bean" else "Add roaster",
                    )
                }
            }
            // Bags / Roasters — a split (segmented) button on its own row, above the
            // filters: it picks WHAT you're viewing, distinct from how you filter bags.
            CremaTabSwitch(
                options = listOf(
                    TabOption("bags", "Bags", ui.beans.size),
                    TabOption("roasters", "Roasters", ui.roasters.size),
                ),
                value = tab,
                onChange = { tab = it },
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            // Roasters: "Show dupes" surfaces rows tagged as merged duplicates so
            // they can be inspected / un-merged (web, only when any exist).
            if (tab == "roasters" && hasTaggedDupes) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp)) {
                    CremaFilterChip(
                        label = if (beansState.showDuplicates) "Hide dupes" else "Show dupes",
                        selected = beansState.showDuplicates,
                        icon = "link",
                        onClick = { beansState.showDuplicates = !beansState.showDuplicates },
                    )
                }
            }
            // Filter rail (Bags): STATUS group · full-height divider · ROAST group, sort
            // pinned right. IntrinsicSize.Min lets the divider stretch the row height
            // (PWA .bn-tabs-divider: align-self: stretch); group labels sit centered.
            if (tab == "bags") {
                // Wide: one row, sort pinned right. Narrow 7"/portrait: the Status +
                // Roast chip groups + sort overflow, so the row scrolls horizontally
                // (the sort travels with it) instead of clipping the chip labels.
                if (scopeRoaster != null) {
                    // Scope chip: "<roaster> ✕" — tap to return to the full
                    // library. Its own line (as on the phone): inside the filter
                    // rail it pushed the Roast group off a ~950dp landscape row.
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 4.dp)) {
                        CremaFilterChip(
                            label = scopeRoaster.name,
                            selected = true,
                            icon = "storefront",
                            trailingIcon = "x",
                            modifier = Modifier.widthIn(max = 480.dp),
                            onClick = { roasterScopeId = null },
                        )
                    }
                }
                // The one-row rail (5 Status + 3 Roast chips + sort) needs ~1170dp of
                // screen. 7" and phone landscape (~950dp) already get this rail layout,
                // so below that it scrolls rather than clipping Dark and the sort away.
                // With the Include-archived and tag chips (#124) the groups can
                // outgrow even a 10" landscape row, so the chips scroll inside
                // their own weighted lane and the sort stays pinned right at
                // every width (a fixed row squeezed the last chip vertically).
                val filterScroll = rememberScrollState()
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                Row(
                    Modifier.weight(1f).height(IntrinsicSize.Min).horizontalScroll(filterScroll),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Status and Roast are independent groups (#124): Archived +
                    // Light means archived light roasts. Counts are faceted —
                    // each is what the list holds if that chip is picked.
                    val counts = facetResult.chipCounts()
                    CremaFilterGroupLabel("Status")
                    listOf("all" to "All", "active" to "Active", "favourite" to "Favourite", "frozen" to "Frozen", "archived" to "Archived").forEach { (id, label) ->
                        CremaFilterChip(label = label, selected = beansState.status == id, count = counts[id] ?: 0, onClick = { beansState.selectStatus(id) })
                    }
                    // "Include archived" — archived bags, dimmed, alongside the
                    // rest (off by default; only where it changes the list).
                    if (includeArchivedApplies(facets) && (beansState.includeArchived || facetResult.statusCounts.archived > 0u)) {
                        CremaFilterChip(
                            label = "Include archived",
                            selected = beansState.includeArchived,
                            icon = "archive",
                            count = if (beansState.includeArchived) null else facetResult.archivedHidden.toInt(),
                            onClick = { beansState.toggleIncludeArchived() },
                        )
                    }
                    CremaFilterDivider()
                    CremaFilterGroupLabel("Roast")
                    listOf("light" to "Light", "medium" to "Medium", "dark" to "Dark").forEach { (id, label) ->
                        CremaFilterChip(label = label, selected = beansState.roast == id, count = counts[id] ?: 0, onClick = { beansState.selectRoast(id) })
                    }
                    if (facetResult.tagCounts.isNotEmpty()) {
                        CremaFilterDivider()
                        CremaFilterGroupLabel("Tags")
                        facetResult.tagCounts.forEach { t ->
                            CremaFilterChip(label = t.tag, selected = t.tag in beansState.tags, count = t.count.toInt(), onClick = { beansState.toggleTag(t.tag) })
                        }
                    }
                    if (beansState.hasFilters) {
                        CremaFilterChip(label = "Clear", selected = false, icon = "x", onClick = { beansState.clearFilters() })
                    }
                }
                    Spacer(Modifier.width(12.dp))
                    CremaSortControl(
                        keys = listOf(
                            SortKey("freshest", "Freshest", "clock"),
                            SortKey("name", "Name", "sort-ascending"),
                            SortKey("roast", "Roast", "fire"),
                            SortKey("rating", "Rating", "star"),
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
                        CremaEmptyState(if (ui.beans.isEmpty()) "No beans yet — add a bag to get started." else "No beans match your search or filters.")
                    }
                } else {
                    // Scroll position per filter (issue #123), kept in ListMemory
                    // across the editor round trip, the tab switch and a host swap.
                    val gridState = rememberGridMemoryState(beansListKey(facets, beanSort, beanSortDesc), sortedBeans.size) { k ->
                        sortedBeans.indexOfFirst { it.id == k }.takeIf { it >= 0 }
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        // Adaptive: 2 columns on a narrow 7" tablet (wider cards →
                        // "Set active" keeps its label), 3 on the 10".
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    ) {
                        items(sortedBeans, key = { it.id }) { bean ->
                            BeanCard(
                                bean = bean,
                                roasterName = ui.roasters.firstOrNull { it.id == bean.roasterId }?.name,
                                hit = beanHits.hit(bean.id),
                                isActive = bean.id == ui.activeBeanId,
                                onOpen = { detailBeanId = bean.id },
                                onSetActive = { vm.setActiveBean(bean.id) },
                                onEdit = { vm.startEditBean(bean.id); onNav("bean-edit") },
                                onDuplicate = { vm.duplicateBean(bean.id) },
                                onFreezeToggle = { if (bean.isFrozen) vm.defrostBean(bean.id) else vm.freezeBean(bean.id) },
                                onArchiveToggle = { if (bean.archivedAt != null) vm.unarchiveBean(bean.id) else vm.archiveBean(bean.id) },
                                onToggleFavourite = { vm.toggleBeanFavourite(bean.id) },
                                remoteDeleteAvailable = vm.canDeleteOnVisualizer(bean.visualizerId),
                                onDelete = { remote -> vm.deleteBean(bean.id, remote) },
                            )
                        }
                    }
                }
            } else {
                if (visibleRoasters.isEmpty() && mergeSuggestions.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CremaEmptyState(if (ui.roasters.isEmpty()) "No roasters yet — add one to group your bags." else "No roasters match your search.")
                    }
                } else {
                    val gridState = rememberGridMemoryState(roastersListKey(beansState.showDuplicates), visibleRoasters.size) { k ->
                        visibleRoasters.indexOfFirst { it.id == k }.takeIf { it >= 0 }?.plus(mergeSuggestions.size)
                    }
                    LazyVerticalGrid(
                        state = gridState,
                        // Adaptive: 2 columns on a narrow 7" tablet (wider cards →
                        // "Set active" keeps its label), 3 on the 10".
                        columns = GridCells.Adaptive(minSize = 320.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                    ) {
                        // Merge banners span the grid, above the cards (web .bn-merge-banner).
                        items(mergeSuggestions, key = { "merge:" + it.dupe.id }, span = { GridItemSpan(maxLineSpan) }) { s ->
                            coffee.crema.ui.components.RoasterMergeBanner(
                                suggestion = s,
                                onKeepSeparate = { beansState.dismissedDuplicates = beansState.dismissedDuplicates + s.dupe.id },
                                onMerge = { vm.mergeRoaster(s.canonical.id, s.dupe.id) },
                            )
                        }
                        items(visibleRoasters, key = { it.id }) { roaster ->
                            RoasterCard(
                                roaster = roaster,
                                bagCountLabel = roasterBagCountLabel(ui.beans, roaster.id),
                                duplicateOf = roaster.canonicalRoasterId?.let { id -> ui.roasters.firstOrNull { it.id == id }?.name ?: "a removed roaster" },
                                onUnmerge = { vm.unmergeRoaster(roaster.id) },
                                linkedBagCount = ui.beans.count { it.roasterId == roaster.id },
                                remoteDeleteAvailable = { cascade -> vm.canDeleteRoasterOnVisualizer(roaster, cascade) },
                                onOpen = { beansState.openShelf(roaster.id) },
                                onEdit = { roasterEditing = roaster; roasterDialogOpen = true },
                                onVisit = { vm.visitRoasterWebsite(roaster.website) },
                                onDelete = { remote, cascade -> vm.deleteRoaster(roaster.id, remote, cascade) },
                            )
                        }
                    }
                }
            }
        }
    }

    // Read-only detail (issue 61) — resolved from the live library each frame
    // so mutations made inside the sheet are reflected immediately, and the
    // sheet closes by itself if the bag is deleted underneath it.
    detailBeanId?.let { id ->
        val bean = ui.beans.firstOrNull { it.id == id }
        if (bean == null) {
            detailBeanId = null
        } else {
            val shots = ui.history.filter { it.bean?.beanId == bean.id }
            BeanDetailSheet(
                bean = bean,
                roasterName = ui.roasters.firstOrNull { it.id == bean.roasterId }?.name,
                linkedProfileName = linkedProfileNameFor(bean, ui.profiles.map { it.id to it.name }),
                shotCount = shots.size,
                recentShots = shots.take(5).map { shotRowSummary(it) },
                recentDosesG = shots.map { it.doseG ?: 0f },
                onLogBrew = { vm.openLogBrew(coffee.crema.ui.brewlog.BrewLogOwner.BEANS, beanId = bean.id) },
                isActive = bean.id == ui.activeBeanId,
                onDismiss = { detailBeanId = null },
                onEdit = { detailBeanId = null; vm.startEditBean(bean.id); onNav("bean-edit") },
                onSetActive = { vm.setActiveBean(bean.id) },
                onToggleFavourite = { vm.toggleBeanFavourite(bean.id) },
                onToggleArchived = {
                    if (bean.archivedAt != null) vm.unarchiveBean(bean.id) else vm.archiveBean(bean.id)
                },
                onDelete = { remote -> vm.deleteBean(bean.id, remote) },
                remoteDeleteAvailable = vm.canDeleteOnVisualizer(bean.visualizerId),
                onOpenShot = { sid ->
                    detailBeanId = null
                    vm.openShotInHistory(sid)
                    onNav("history")
                },
                onSeeAllShots = {
                    detailBeanId = null
                    vm.openBeanShotsInHistory(bean.id)
                    onNav("history")
                },
            )
        }
    }

    // The Log-brew sheet (issue #10), opened from the bean detail footer with
    // that bag pre-selected — VM-held, so it survives the phone↔tablet swap.
    coffee.crema.ui.brewlog.LogBrewSheet(vm, owner = coffee.crema.ui.brewlog.BrewLogOwner.BEANS)

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
    hit: SearchHit?,
    isActive: Boolean,
    /** Tap the card BODY → the read-only detail sheet (issue 61). Deliberately
     *  not the whole card: the action row below owns its own taps. */
    onOpen: () -> Unit,
    onSetActive: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onFreezeToggle: () -> Unit,
    onArchiveToggle: () -> Unit,
    onToggleFavourite: () -> Unit,
    remoteDeleteAvailable: Boolean,
    onDelete: (alsoOnVisualizer: Boolean) -> Unit,
) {
    // Tile pill shows the finer 5-band display label (web roastBand5);
    // filters/freshness elsewhere stay on the canonical 3-band roastBand.
    val band = roastBand5(bean.roastLevel?.toInt())
    val days = beanDaysOffRoast(bean)
    val frozen = bean.isFrozen
    val tagList = bean.tags?.filter { it.isNotBlank() }.orEmpty()
    var confirmDelete by remember { mutableStateOf(false) }
    CremaCard(
        // Archived bags are dimmed (web parity) — a roaster's shelf mixes them
        // in with the live ones (#86).
        modifier = Modifier.fillMaxWidth().alpha(if (bean.archivedAt != null) 0.6f else 1f),
        container = if (isActive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(16.dp),
        border = if (isActive) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          // The card BODY opens the detail sheet; the action row below stays
          // outside this clickable so Set active / duplicate / edit / kebab do
          // not also open the sheet behind their own dialogs.
          Column(
              Modifier.fillMaxWidth().clickable(onClick = onOpen),
              verticalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                // The bag photo if one's set, else the roaster's mark ("?" when roasterless).
                BeanAvatar(
                    beanId = bean.id, imageRef = bean.imageRef, updatedAt = bean.updatedAt,
                    fallbackName = roasterName, sizeDp = 44, cornerDp = 12, fontSize = 16.sp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (roasterName != null) {
                        Eyebrow(
                            highlighted(
                                hit.forField(SearchField.Roaster)?.snippet,
                                roasterName,
                                transform = UPPERCASE,
                            ),
                        )
                    }
                    Text(
                        highlighted(hit.forField(SearchField.Name)?.snippet, bean.name),
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    bean.origin?.country?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Why this bag is in the results — only when the query landed
                    // somewhere the card does not already show.
                    hit.explanatory()?.let { why -> SearchWhy(why) }
                }
                IconButton(onClick = onToggleFavourite, modifier = Modifier.size(32.dp)) {
                    PhIcon(if (bean.favourite == true) "star-fill" else "star", sizeDp = 18, tint = if (bean.favourite == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val pills = buildList {
                band?.let { add(it to true) }
                if (frozen) add("Frozen" to false)
                if (bean.decaf == true) add("Decaf" to false)
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
                    leading = { Box(Modifier.size(6.dp).clip(CircleShape).background(freshnessColor(frozen, bean.roastLevel?.toInt(), days))) },
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
                CremaStarRating(
                    bean.rating?.toInt() ?: 0,
                    starDp = 11,
                    emptyTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                )
            }
            if ((bean.bagSize ?: 0f) > 0f) {
                val pct = ((bean.remaining ?: 0f) / (bean.bagSize ?: 0f)).coerceIn(0f, 1f)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(
                        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    ) {
                        Box(Modifier.fillMaxWidth(pct).height(6.dp).clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colorScheme.primary))
                    }
                    Text(
                        "${(bean.remaining ?: 0f).toInt()} / ${(bean.bagSize ?: 0f).toInt()} g",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if ((bean.remaining ?: 0f) > 0f) {
                // Tolerant of a missing bag size (imports): show the remaining
                // number alone — no burn bar, since there's no full/empty range.
                Text(
                    "${(bean.remaining ?: 0f).toInt()} g left",
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
        coffee.crema.ui.components.DeleteWithVisualizerDialog(
            title = "Delete bean?",
            body = "“${bean.name}” will be removed. This can’t be undone.",
            what = "bag",
            remoteAvailable = remoteDeleteAvailable,
            onConfirm = { remote -> onDelete(remote); confirmDelete = false },
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

// A roaster directory card — avatar + name, a "City · Country" line, then a
// meta row (website · "N bags · M archived"), with a kebab (Edit / Visit
// website / Delete). Tapping the card opens the roaster's shelf on the Bags
// tab (#86).
@Composable
private fun RoasterCard(
    roaster: Roaster,
    bagCountLabel: String,
    /** The canonical roaster's name when this row is a merged duplicate (badge + Un-merge). */
    duplicateOf: String?,
    onUnmerge: () -> Unit,
    linkedBagCount: Int,
    remoteDeleteAvailable: (cascade: Boolean) -> Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onVisit: () -> Unit,
    onDelete: (alsoOnVisualizer: Boolean, cascade: Boolean) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    CremaCard(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                RoasterMarkAvatar(roaster.name, sizeDp = 44, cornerDp = 12, fontSize = 16.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        roaster.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Location on its own line (web RoasterCard's row), so a long
                    // city/country ellipsizes itself rather than the bag counts.
                    val place = listOfNotNull(roaster.city, roaster.country)
                        .filter { it.isNotBlank() }
                        .joinToString(" · ")
                    if (place.isNotEmpty()) {
                        Text(place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Meta row: website (shrinks, ellipsized) · bag counts (never
                    // truncated — they are what the card is for at a glance).
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        roaster.website?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false).padding(end = 12.dp),
                            )
                        }
                        Text(
                            bagCountLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    duplicateOf?.let { name ->
                        coffee.crema.ui.components.DuplicateOfLabel(name, onUnmerge, Modifier.padding(top = 4.dp))
                    }
                }
                CremaOverflowMenu(items = buildList {
                    add(OverflowItem("pencil-simple", "Edit roaster", onEdit))
                    if (!roaster.website.isNullOrBlank()) add(OverflowItem("arrow-square-out", "Visit website", onVisit))
                    add(OverflowItem("trash", "Delete roaster", { confirmDelete = true }, danger = true))
                })
            }
        }
    }
    if (confirmDelete) {
        coffee.crema.ui.components.RoasterDeleteDialog(
            roasterName = roaster.name,
            linkedBagCount = linkedBagCount,
            remoteAvailable = remoteDeleteAvailable,
            onConfirm = { remote, cascade -> onDelete(remote, cascade); confirmDelete = false },
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


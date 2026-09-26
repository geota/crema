package coffee.crema.ui.phone

import coffee.crema.ui.fmt
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.history.StoredShot
import coffee.crema.beans.rankBeansForPicker
import coffee.crema.core.daysOffRoast as coreDaysOffRoast
import coffee.crema.ui.freshnessColor
import coffee.crema.beans.beanDaysOffRoast
import coffee.crema.brew.methodIcon
import coffee.crema.brew.methodLabel
import coffee.crema.history.beanLabel
import coffee.crema.history.brewHistoryStats
import coffee.crema.history.effectiveGrindSetting
import coffee.crema.history.formatShotDuration
import coffee.crema.history.grindLabel
import coffee.crema.history.filterAndSortShots
import coffee.crema.history.isManualLog
import coffee.crema.history.methodOf
import coffee.crema.history.methodRatioLabel
import coffee.crema.ui.brewlog.BrewSessionCanvas
import coffee.crema.ui.brewlog.BrewSparkChart
import coffee.crema.ui.phone.components.CremaNewFab
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.convertPressure
import coffee.crema.ui.convertTemp
import coffee.crema.ui.convertWeight
import coffee.crema.ui.formatRatio
import coffee.crema.ui.relativeAgo
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.*
import coffee.crema.ui.compare.ComparePhoneScreen
import coffee.crema.ui.compare.PhoneSelectBar
import coffee.crema.ui.compare.PhoneSelectHint
import coffee.crema.ui.compare.RowCheck
import coffee.crema.ui.compare.rememberCompareSelection
import coffee.crema.ui.screens.CanvasShotChart
import coffee.crema.ui.screens.EnlargeableChart
import coffee.crema.ui.screens.historySortKeys
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import coffee.crema.ui.UploadTarget
import coffee.crema.ui.uploadMenuEntry
import coffee.crema.ui.viewableTargets
import coffee.crema.ui.shareableTargets
import coffee.crema.ui.openUploadedCopy
import coffee.crema.history.isBrewLog
import coffee.crema.ui.UploadPip
import coffee.crema.ui.uploadPipFor
import coffee.crema.ui.shareUploadedLink

/*
 * PhoneHistoryScreen — the handset shot log (port of
 * prototype/phone/phone-history.jsx), wired to LIVE state.
 *
 * The tablet's list-detail pane collapses: a day-grouped shot list; tapping a
 * row PUSHES a full-screen detail (chart + metrics + rating + privacy + notes)
 * as INTERNAL state — the bottom bar stays, the back arrow (and system back)
 * return to the list.
 */
@Composable
fun PhoneHistoryScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Saveable: the pushed Log-brew route ("Log again") keeps this entry on the
    // back stack, and Cancel should land back on the brew's detail.
    var detailId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var profileFilter by remember { mutableStateOf<String?>(null) }
    // Bean filter — scope the log to one bag (bean-workflow-unify §B).
    var beanFilter by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf("all") }
    var sort by remember { mutableStateOf("date") }
    var sortDesc by remember { mutableStateOf(true) } // newest / highest first
    var exportSheet by remember { mutableStateOf(false) }
    // Brew Log (issue #10): the method filter + the Log-brew form.
    var methodFilter by remember { mutableStateOf<String?>(null) }
    // The form itself is the pushed `log-brew` route over a VM-held draft
    // (it survives rotation and the phone↔tablet swap).
    val openLogBrew: (StoredShot?) -> Unit = { prefill ->
        vm.openLogBrew(coffee.crema.ui.brewlog.BrewLogOwner.HISTORY, prefill = prefill)
        onNav(coffee.crema.ui.brewlog.LOG_BREW_ROUTE)
    }
    // Compare: select 2–5 shots, then push the full-screen overlay (HistoryCompareHooks).
    val sel = rememberCompareSelection()
    var compareOpen by remember { mutableStateOf(false) }

    // Brew's "Last shot" peek deep-links here: open that shot's detail.
    LaunchedEffect(ui.pendingHistoryShotId) {
        ui.pendingHistoryShotId?.let {
            detailId = it
            range = "all"
            profileFilter = null
            beanFilter = null
            vm.consumePendingHistoryShot()
        }
    }
    // The bean detail's "See all N shots" deep-links here: scope to that bag.
    LaunchedEffect(ui.pendingHistoryBeanId) {
        ui.pendingHistoryBeanId?.let {
            beanFilter = it
            range = "all"
            profileFilter = null
            vm.consumePendingHistoryBean()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importShots(uri)
    }
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val t = pendingExport; pendingExport = null
        if (uri != null && t != null) vm.writeTextToUri(uri, t)
    }
    val launchSave: (String, String?) -> Unit = { name, content ->
        if (content != null) { pendingExport = content; saveLauncher.launch(name) }
    }

    val now = System.currentTimeMillis()
    val dayMs = 24L * 60L * 60L * 1000L
    val startOfDay = remember(now / 60000L) {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    val filtered = filterAndSortShots(ui.history, query, range, profileFilter, sort, sortDesc, now, beanFilter, methodFilter)

    val detail = detailId?.let { id -> ui.history.firstOrNull { it.id == id } }

    if (detail != null) {
        BackHandler { detailId = null }
        PhoneShotDetail(
            vm = vm,
            shot = detail,
            uploadTargets = ui.sharing.uploadTargets[detail.id].orEmpty(),
            syncing = detail.id in ui.sharing.uploadingShotIds,
            defaultPrivacy = ui.visualizer.privacy,
            weightUnit = ui.weightUnit,
            tempUnit = ui.tempUnit,
            pressureUnit = ui.pressureUnit,
            onBack = { detailId = null },
            onDeleted = { detailId = null },
            onLoadOnBrew = { vm.loadProfileOnBrew(detail.profileName); onNav("brew") },
            onStartFromShot = { vm.startFromShot(detail.id); onNav("brew") },
            onExport = { launchSave("crema-shot.json", vm.shotsJson(listOf(detail.id))) },
            onLogAgain = { openLogBrew(detail) },
        )
        return
    }

    // Compare PUSHES full-screen (same idiom as detail); ComparePhoneScreen owns its
    // own back bar + channel selector + chart + legend cards.
    val compareShots = sel.picked.mapNotNull { id -> ui.history.firstOrNull { it.id == id } }
    if (compareOpen && compareShots.size >= 2) {
        BackHandler { compareOpen = false }
        Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
            Box(Modifier.padding(inner)) {
                ComparePhoneScreen(
                    shots = compareShots,
                    weightUnit = ui.weightUnit,
                    tempUnit = ui.tempUnit,
                    pressureUnit = ui.pressureUnit,
                    onBack = { compareOpen = false },
                )
            }
        }
        return
    }

    Scaffold(
        topBar = {
            if (sel.selecting) {
                CremaPhoneTopBar(title = "Select shots", actions = listOf(BarAction("x") { sel.cancel() }))
            } else {
                // Import / Export are separate bar actions — same pairing as the
                // Profiles and Beans top bars.
                CremaPhoneTopBar(
                    title = "History",
                    actions = listOf(
                        BarAction("magnifying-glass") { searchOpen = !searchOpen },
                        BarAction("upload-simple") { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        BarAction("download-simple") { exportSheet = true },
                        BarAction("gear-six") { onNav("settings") },
                    ),
                )
            }
        },
        bottomBar = { if (sel.selecting) PhoneSelectBar(sel, onCompare = { compareOpen = true }) },
        floatingActionButton = {
            // The Brew Log's manual-entry door (issue #10) — the same FAB
            // idiom as Beans and Profiles.
            if (!sel.selecting) {
                CremaNewFab("Log brew") { openLogBrew(null) }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (ui.history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CremaEmptyState(
                        message = "No shots yet — pull one on Brew.",
                        action = {
                            CremaButton(
                                onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                                variant = CremaButtonVariant.Outlined,
                                icon = "upload-simple",
                                label = "Import shots",
                            )
                        },
                    )
                }
                return@Column
            }

            // Stats strip (or, while selecting, the compare hint).
            if (sel.selecting) {
                PhoneSelectHint(Modifier.fillMaxWidth().padding(horizontal = CremaEdge, vertical = 6.dp))
            } else {
                PhoneStatsStrip(filtered, ui.weightUnit)
            }

            if (!sel.selecting) {
                // Filter chips: per-profile pills, then the date dropdown + sort;
                // a compare entry pins to the right (web parity, same arrows icon).
                val byProfile = ui.history.mapNotNull { it.profileName }
                    .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CremaFilterChipRow(
                        modifier = Modifier.weight(1f),
                        chips = buildList {
                            add(FilterChipSpec("all", "All", ui.history.size))
                            byProfile.take(6).forEach { (name, count) -> add(FilterChipSpec("p:$name", name, count)) }
                        },
                        selected = profileFilter?.let { "p:$it" } ?: "all",
                        onSelect = { id ->
                            when {
                                id == "all" -> profileFilter = null
                                id.startsWith("p:") -> profileFilter = id.removePrefix("p:")
                            }
                        },
                        trailing = {
                            // Method — the Brew Log filter (issue #10); only
                            // rendered once a second method exists.
                            val byMethod = ui.history
                                .groupingBy { it.methodOf ?: "espresso" }.eachCount()
                                .entries.sortedByDescending { it.value }
                            if (byMethod.size > 1) {
                                CremaFilterDropdown(
                                    icon = methodIcon(methodFilter),
                                    keys = buildList {
                                        add(SortKey("all", "All methods"))
                                        byMethod.forEach { (m, _) -> add(SortKey(m, methodLabel(m))) }
                                    },
                                    selectedKey = methodFilter ?: "all",
                                    onKeyChange = { methodFilter = if (it == "all") null else it },
                                )
                            }
                            // Bean — scope the log to one bag (tablet parity).
                            val byBean = ui.history.mapNotNull { it.bean?.beanId }
                                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                            if (byBean.isNotEmpty()) {
                                CremaFilterDropdown(
                                    icon = "coffee-bean",
                                    keys = buildList {
                                        add(SortKey("all", "All beans"))
                                        byBean.forEach { (bid, _) ->
                                            val label = ui.beans.firstOrNull { it.id == bid }?.name
                                                ?: ui.history.firstOrNull { it.bean?.beanId == bid }?.beanLabel
                                                ?: "Unnamed bag"
                                            add(SortKey(bid, label))
                                        }
                                    },
                                    selectedKey = beanFilter ?: "all",
                                    onKeyChange = { beanFilter = if (it == "all") null else it },
                                )
                            }
                            // Date range as a split dropdown (tablet parity) — the
                            // range pills crowded the profile chips out of the row.
                            CremaFilterDropdown(
                                icon = "calendar",
                                keys = listOf(
                                    SortKey("all", "All time"),
                                    SortKey("30d", "30 days"),
                                    SortKey("7d", "7 days"),
                                    SortKey("today", "Today"),
                                ),
                                selectedKey = range,
                                onKeyChange = { range = it },
                            )
                            CremaSortControl(
                                keys = historySortKeys,
                                selectedKey = sort,
                                descending = sortDesc,
                                onKeyChange = { key ->
                                    sort = key
                                    sortDesc = key !in setOf("profile", "bean")
                                },
                                onToggleDirection = { sortDesc = !sortDesc },
                            )
                        },
                    )
                    if (ui.history.size >= 2) {
                        IconButton(onClick = sel::enter) {
                            PhIcon("arrows-left-right", sizeDp = 20, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = searchOpen && !sel.selecting) {
                Box(Modifier.padding(horizontal = CremaEdge, vertical = 6.dp)) {
                    CremaPhoneSearch(query = query, onQueryChange = { query = it }, placeholder = "Search profile, bean, notes")
                }
            }

            // Catch-up strip (web "Upload N"): every shot missing from an enabled
            // destination, one tap. Hidden at zero.
            val missingUploads = ui.sharing.missingUploadTotal
            if (missingUploads > 0 && !sel.selecting) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = CremaEdge, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        "$missingUploads shot(s) not uploaded everywhere",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    CremaButton(
                        onClick = { vm.uploadMissing() },
                        variant = CremaButtonVariant.Tonal,
                        icon = "cloud-arrow-up",
                        enabled = !ui.sharing.catchUpBusy,
                        label = if (ui.sharing.catchUpBusy) "Uploading…" else "Upload $missingUploads",
                    )
                }
            }
            // Day-grouped shot list (headers only under the date sort —
            // rating/name orders interleave days, so groups would repeat).
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = CremaEdge, end = CremaEdge, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                var lastDay: String? = null
                filtered.forEach { shot ->
                    val day = dayLabel(shot.completedAtMs, startOfDay, dayMs)
                    if (day != lastDay && sort == "date") {
                        lastDay = day
                        item(key = "day-$day-${shot.id}") {
                            Text(
                                day.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp, letterSpacing = 0.7.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 2.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                    }
                    item(key = shot.id) {
                        PhoneShotRow(
                            shot = shot,
                            syncing = shot.id in ui.sharing.uploadingShotIds,
                            pip = uploadPipFor(ui.sharing.uploadTargets[shot.id].orEmpty()),
                            weightUnit = ui.weightUnit,
                            selecting = sel.selecting,
                            picked = sel.isPicked(shot.id),
                            dimmed = sel.atCap(shot.id),
                            onOpen = { if (sel.selecting) sel.toggle(shot.id) else detailId = shot.id },
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        CremaEmptyState("No shots match your filters.", modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp))
                    }
                }
            }
        }
    }

    if (exportSheet) {
        CremaOverflowSheet(
            title = "Export as",
            items = listOf(
                SheetItem("file-text", "All shots", sub = "Every shot as one Crema JSON file. Re-importable in Crema.") {
                    launchSave("crema-history.json", vm.shotsJson(null))
                },
                SheetItem("file-code", "Current filter", sub = "Only the ${filtered.size} shot(s) matching your search and filters.") {
                    launchSave("crema-history-filtered.json", vm.shotsJson(filtered.map { it.id }))
                },
            ),
            onDismiss = { exportSheet = false },
        )
    }
}

private fun dayLabel(ms: Long, startOfDay: Long, dayMs: Long): String = when {
    ms >= startOfDay -> "Today"
    ms >= startOfDay - dayMs -> "Yesterday"
    ms >= startOfDay - 6 * dayMs -> "This week"
    else -> "Earlier"
}

// ── Stats strip (proto .ph-stats — 3-up) ─────────────────────────────────────
@Composable
private fun PhoneStatsStrip(history: List<StoredShot>, weightUnit: String) {
    // Scoped to the filtered set (issue 48), method-aware (issue #10): a mixed
    // set scopes the weight / ratio / time averages to espresso rows — the
    // "esp" tag says so. The Brew Log adds the count and "Beans used" (Σ dose,
    // the inventory number), so six tiles wrap by the width the strip gets:
    // 3×2 under 600dp (every phone), one row from 600dp (7" portrait).
    val s = brewHistoryStats(history)
    fun scoped(label: String) = if (s.mixedMethods) "$label · esp" else label
    val nonEsp = history.any { it.methodOf != null && it.methodOf != "espresso" }
    val beansUsed = convertWeight(s.beansUsedG?.toFloat(), weightUnit)
    val avgWt = convertWeight(s.avgWeightG?.toFloat(), weightUnit)
    val tiles: List<Pair<String, String>> = listOf(
        (if (nonEsp) "Brews" else "Shots") to "${s.count}",
        "Beans used" to (s.beansUsedG?.let { "${beansUsed.value}${beansUsed.unit}" } ?: "—"),
        scoped("Avg weight") to (s.avgWeightG?.let { "${avgWt.value}${avgWt.unit}" } ?: "—"),
        scoped("Avg ratio") to (s.avgRatio?.let { fmt("1:%.1f", it) } ?: "—"),
        scoped("Avg time") to (s.avgTimeS?.let { formatShotDuration((it * 1000).toLong()) } ?: "—"),
        "Avg rating" to (s.avgRating?.let { fmt("%.1f★", it) } ?: "—"),
    )
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = CremaEdge, vertical = 4.dp)) {
        val perRow = if (maxWidth >= 600.dp) 6 else 3
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tiles.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (label, value) -> PhoneStatTile(label, value, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PhoneStatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 19.sp, fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Shot row (proto .ph-row) ─────────────────────────────────────────────────
@Composable
private fun PhoneShotRow(
    shot: StoredShot,
    syncing: Boolean,
    /** Cloud status across every enabled destination. */
    pip: UploadPip = UploadPip.Local,
    weightUnit: String,
    selecting: Boolean = false,
    picked: Boolean = false,
    dimmed: Boolean = false,
    onOpen: () -> Unit,
) {
    val tel = CremaTheme.telemetry
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (dimmed) Modifier.alpha(0.4f) else Modifier)
            .clip(MaterialTheme.shapes.medium)
            .background(if (picked) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (picked) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, MaterialTheme.shapes.medium) else Modifier)
            .clickable(enabled = !dimmed, onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (selecting) RowCheck(picked)
        // The sparkline slot: DE1 telemetry when recorded; a guided brew's
        // weight curve; else the method mark tile (issue #10).
        val rowSeries = shot.brewSeries
        val sparkModifier = Modifier
            .width(58.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
        when {
            shot.samples.isNotEmpty() -> CremaSparkChart(
                samples = shot.samples,
                insetDp = 3f,
                tempStroke = 1.0f,
                weightStroke = 1.2f,
                flowStroke = 1.2f,
                modifier = sparkModifier,
            )
            rowSeries != null && rowSeries.samples.isNotEmpty() ->
                BrewSparkChart(series = rowSeries, modifier = sparkModifier)
            else -> Box(sparkModifier, contentAlignment = Alignment.Center) {
                PhIcon(methodIcon(shot.methodOf), sizeDp = 16, tint = MaterialTheme.colorScheme.primary)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val rowName = shot.profileName
                    ?: shot.recipeName
                    ?: shot.methodOf?.let { methodLabel(it) }
                    ?: "Shot"
                Text(
                    if (shot.isManualLog) "$rowName · logged" else rowName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    // Compact-relative per-row time (issue 43), replacing the HH:mm
                    // clock — matches tablet/web vocabulary. Day-section headers
                    // (dayLabel) keep the absolute date context.
                    relativeAgo(shot.completedAtMs),
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                // Bean + the grind it was pulled at (issue #16).
                listOfNotNull(shot.beanLabel, shot.grindLabel).joinToString(" \u00b7 ").ifBlank { "\u2014" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            // One glance-able journal line: the forward-looking plan when set,
            // else the tasting notes. Absent \u2192 no extra line (tablet parity).
            val snippet = shot.nextPlan?.trim()?.takeIf { it.isNotEmpty() }
                ?: shot.notes?.trim()?.takeIf { it.isNotEmpty() }
            if (snippet != null) {
                Text(
                    snippet.replace(Regex("\\s+"), " "),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CremaStarRating(
                    shot.rating ?: 0,
                    starDp = 11,
                    emptyTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                )
                RowMono(shotRatioLabel(shot) ?: "—")
                // Filter brews speak water-in where espresso speaks yield
                // (issue #10) — never a dash next to a 1:16 ratio.
                val rowOut = if (shot.methodOf != null && shot.methodOf != "espresso") {
                    shot.waterG ?: shot.yieldG
                } else {
                    shot.yieldG
                }
                RowMono(rowOut?.let { convertWeight(it, weightUnit).let { m -> "${m.value}${m.unit}" } } ?: "—")
                RowMono(if (shot.durationMs > 0) formatShotDuration(shot.durationMs).replace(" ", "") else "—")
            }
        }
        when {
            // Brew-log rows never upload (issue #10), so they carry no cloud pip.
            shot.isBrewLog -> {}
            syncing -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
            pip == UploadPip.Uploaded -> PhIcon("cloud-check", sizeDp = 16, tint = tel.success)
            pip == UploadPip.Partial -> PhIcon("cloud-arrow-up", sizeDp = 16, tint = androidx.compose.ui.graphics.Color(0xFFDBA764))
            else -> PhIcon("device-mobile", sizeDp = 16, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun RowMono(text: String) {
    Text(
        text,
        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp, fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}


// ── Pushed detail (proto HistoryDetail) ──────────────────────────────────────
@Composable
private fun PhoneShotDetail(
    vm: MainViewModel,
    shot: StoredShot,
    /** Cloud destinations + their state for this shot — one Upload row via [uploadMenuEntry]. */
    uploadTargets: List<UploadTarget>,
    syncing: Boolean,
    defaultPrivacy: String,
    weightUnit: String,
    tempUnit: String,
    pressureUnit: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onLoadOnBrew: () -> Unit,
    /** Prefill Brew from this shot: profile + bag + grind + targets. */
    onStartFromShot: () -> Unit,
    onExport: () -> Unit,
    /** Brew rows (issue #10): re-open the Log-brew form seeded from this brew. */
    onLogAgain: (() -> Unit)? = null,
) {
    val tel = CremaTheme.telemetry
    // Brew Log rows (issue #10).
    val method = shot.methodOf
    val isBrew = method != null
    val manual = shot.isManualLog
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var changeBean by remember(shot.id) { mutableStateOf(false) }
    var rating by remember(shot.id) { mutableStateOf(shot.rating ?: 0) }
    var notes by remember(shot.id) { mutableStateOf(shot.notes ?: "") }
    var nextPlan by remember(shot.id) { mutableStateOf(shot.nextPlan ?: "") }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            // The bar carries only the pull timestamp (a shot is an archival
            // record) — profile + bean live in the Brew strip card below,
            // the SAME shared component (issue #16 round 4).
            CremaPhoneBackBar(
                title = remember(shot.completedAtMs) {
                    java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(shot.completedAtMs))
                },
                onBack = onBack,
                actions = {
                    IconButton(onClick = { menu = true }) { PhIcon("dots-three-vertical", sizeDp = 20) }
                },
            )
        },
        bottomBar = {
            // Foot actions sit above the app bottom nav (proto .ph-dactions).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CremaEdge, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isBrew) {
                    // Brew rows (issue #10): "Log again" is the dial-in loop's
                    // sibling — the machine prefill doesn't apply.
                    FilledTonalButton(
                        onClick = { onLogAgain?.invoke() },
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { PhIcon("arrow-counter-clockwise", sizeDp = 18); Spacer(Modifier.width(8.dp)); Text("Log again") }
                } else {
                    FilledTonalButton(
                        // The full dial-in leads (profile + bag + grind + targets);
                        // the profile-only reload moves to the overflow sheet.
                        onClick = onStartFromShot,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { PhIcon("coffee", sizeDp = 18); Spacer(Modifier.width(8.dp)); Text("Start from shot") }
                }
                Button(
                    onClick = onExport,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { PhIcon("download-simple", sizeDp = 18); Spacer(Modifier.width(8.dp)); Text("Export shot") }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(
            Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CremaEdge),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Profile / bean strip — Brew's card, verbatim (issue #16
            // round 4): title = the shot's profile (historical, not
            // switchable), meta = bean + the grind it was pulled at, and a
            // STATIC freshness pip (the bean's age at PULL time). Tapping
            // opens the beans-only swap dropdown to re-attribute.
            Box {
                val daysAtPull = remember(shot.id) {
                    coreDaysOffRoast(shot.bean?.roastedOn, null, null, shot.completedAtMs)?.toInt()
                }
                ProfileStrip(
                    title = when {
                        isBrew -> (shot.recipeName ?: methodLabel(method)) + if (manual) " · logged" else ""
                        else -> shot.profileName ?: "Shot"
                    },
                    beanLine = shot.beanLabel ?: "No bean",
                    // Bottom row: the grind this shot was pulled at + the
                    // grinder, fainter (tablet/web .bh-spec tier).
                    specLine = remember(shot.id, shot.grindSetting, shot.bean) {
                        listOfNotNull(
                            shot.grindLabel,
                            shot.bean?.grinder?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ").ifBlank { null }
                    },
                    freshLabel = daysAtPull?.let { "${it}d off roast" },
                    freshColor = freshnessColor(false, shot.bean?.roastLevel?.toInt(), daysAtPull),
                    onClick = { changeBean = true },
                )
                if (changeBean) {
                    val ui by vm.ui.collectAsStateWithLifecycle()
                    ShotBeanSwapDropdown(
                        ui = ui,
                        currentBeanId = shot.bean?.beanId,
                        onSelect = { vm.setShotBean(shot.id, it); changeBean = false },
                        onDismiss = { changeBean = false },
                    )
                }
            }

            // Chart card + legend. Brew rows (issue #10): a guided session
            // draws its weight curve with stage bands; a manual log shows no
            // chart card at all — the screen tightens up.
            val brewSeries = shot.brewSeries
            if (isBrew) {
                if (brewSeries != null && brewSeries.samples.isNotEmpty()) {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                        Column(Modifier.padding(start = 6.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)) {
                            Row(
                                Modifier.padding(start = 10.dp, bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                DetailLegend("WEIGHT", tel.weight)
                                DetailLegend("POUR RATE", tel.flow)
                                if (brewSeries.stageMarks.any { it.targetWaterG != null }) {
                                    PlannedDetailLegend()
                                }
                            }
                            BrewSessionCanvas(brewSeries, Modifier.fillMaxWidth().height(190.dp), showLegend = false)
                        }
                    }
                }
            } else {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(start = 6.dp, end = 10.dp, top = 10.dp, bottom = 6.dp)) {
                    Row(
                        Modifier.padding(start = 10.dp, bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        DetailLegend("PRESS", tel.pressure)
                        DetailLegend("FLOW", tel.flow)
                        DetailLegend("TEMP", tel.temp)
                        DetailLegend("WEIGHT", tel.weight)
                    }
                    if (shot.samples.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                            Text(
                                "No telemetry recorded for this shot.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        EnlargeableChart(Modifier.fillMaxWidth().height(190.dp)) { m ->
                            CanvasShotChart(
                                samples = shot.samples,
                                enabledChannels = setOf("pressure", "flow", "headTemp", "weight"),
                                live = false,
                                modifier = m,
                            )
                        }
                    }
                }
            }
            }

            // 4-up metric strip (proto .ph-dmetrics). Brew rows swap the
            // machine peaks for the method-shaped facts (issue #10).
            if (isBrew) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val out = if (method == "espresso") shot.yieldG else shot.waterG ?: shot.yieldG
                    val dOut = convertWeight(out, weightUnit)
                    DetailMetric(
                        if (shot.durationMs > 0) formatShotDuration(shot.durationMs) else "—",
                        "Time",
                        null,
                        Modifier.weight(1f),
                    )
                    DetailMetric(shot.doseG?.let { fmt("%.1fg", it.toDouble()) } ?: "—", "Dose", null, Modifier.weight(1f))
                    DetailMetric(out?.let { "${dOut.value}${dOut.unit}" } ?: "—", if (method == "espresso") "Yield" else "Water", tel.weight, Modifier.weight(1f))
                    DetailMetric(shotRatioLabel(shot) ?: "—", "Ratio", null, Modifier.weight(1f))
                }
            } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val dPeakP = convertPressure(shot.peakPressure, pressureUnit)
                val dPeakT = convertTemp(shot.peakTemp, tempUnit)
                val dYield = convertWeight(shot.yieldG, weightUnit)
                DetailMetric(fmt("%.0fs", shot.durationMs / 1000.0), "Time", null, Modifier.weight(1f))
                DetailMetric(shot.peakPressure?.let { dPeakP.value } ?: "—", "Peak ${dPeakP.unit}", tel.pressure, Modifier.weight(1f))
                DetailMetric(shot.peakTemp?.let { "${dPeakT.value}${dPeakT.unit}" } ?: "—", "Peak temp", tel.temp, Modifier.weight(1f))
                DetailMetric(shot.yieldG?.let { "${dYield.value}${dYield.unit}" } ?: "—", "Yield", tel.weight, Modifier.weight(1f))
            }
            }
            // Manual rows keep their user-entered facts editable (issue #10) —
            // dose re-settles the bag through the credit/debit pair; brew time
            // and temp are editable too (web parity). Two-up from 360dp.
            if (manual) {
                val esp = method == "espresso"
                val editors: List<@Composable (Modifier) -> Unit> = listOf(
                    { m ->
                        CremaStepper(
                            label = "Dose",
                            value = (shot.doseG ?: 0f).toDouble(),
                            unit = "g",
                            onChange = { vm.updateManualBrew(shot.id, doseG = it.toFloat().takeIf { v -> v > 0f }) },
                            step = 0.5, min = 0.0, max = 200.0,
                            modifier = m, style = CremaStepperStyle.Boxed,
                        )
                    },
                    { m ->
                        CremaStepper(
                            label = if (esp) "Yield" else "Water",
                            value = ((if (esp) shot.yieldG else shot.waterG) ?: 0f).toDouble(),
                            unit = "g",
                            onChange = {
                                val v = it.toFloat().takeIf { f -> f > 0f }
                                if (esp) vm.updateManualBrew(shot.id, yieldG = v) else vm.updateManualBrew(shot.id, waterG = v)
                            },
                            step = if (esp) 1.0 else 10.0, min = 0.0, max = 2000.0,
                            fmt = { fmt("%.0f", it) },
                            modifier = m, style = CremaStepperStyle.Boxed,
                        )
                    },
                    { m ->
                        CremaStepper(
                            label = "Time",
                            value = (shot.durationMs / 1000L).toDouble(),
                            onChange = { vm.updateManualBrew(shot.id, durationMs = (it * 1000).toLong().takeIf { v -> v > 0L }) },
                            step = 5.0, min = 0.0, max = 86_400.0,
                            fmt = { coffee.crema.brew.formatClock((it * 1000).toLong()) },
                            modifier = m, style = CremaStepperStyle.Boxed,
                        )
                    },
                    { m ->
                        CremaStepper(
                            label = "Temp",
                            value = (shot.brewTempC ?: 0f).toDouble(),
                            unit = "°C",
                            onChange = { vm.updateManualBrew(shot.id, brewTempC = it.toFloat().takeIf { v -> v > 0f }) },
                            step = 1.0, min = 0.0, max = 100.0,
                            fmt = { fmt("%.0f", it) },
                            modifier = m, style = CremaStepperStyle.Boxed,
                        )
                    },
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val cols = if (maxWidth >= 360.dp) 2 else 1
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        editors.chunked(cols).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { e -> e(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            // Shot quality — the core's analysis (Decenza port) over the stored
            // telemetry, below the chart + metrics it describes. Computed once per
            // shot; null (thin/legacy shot) renders nothing.
            val quality = remember(shot.id) { vm.analyzeShotQuality(shot) }
            if (quality != null) ShotQualityCard(quality)

            // Rating + privacy + notes.
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Eyebrow("Your rating")
                        CremaStarRating(
                            rating,
                            onChange = { rating = it; vm.updateShot(shot.id, rating, notes) },
                            touchDp = 34,
                        )
                    }
                    // Grind stepper — the shot's recorded grind, editable in
                    // place (issue #16 round 3). Each click persists; the
                    // Visualizer PATCH debounces behind it.
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Eyebrow("Grind")
                        CremaStepper(
                            value = shot.effectiveGrindSetting?.toDoubleOrNull() ?: 0.0,
                            onChange = { vm.setShotGrind(shot.id, it.toFloat()) },
                            step = 0.1,
                            min = 0.0,
                            max = 200.0,
                            fmt = { v -> if (v % 1.0 == 0.0) fmt("%.0f", v) else fmt("%.1f", v) },
                        )
                    }
                    CremaTextField(
                        value = notes,
                        onValueChange = { notes = it; vm.updateShot(shot.id, rating, it) },
                        label = "Tasting notes",
                        placeholder = "How did it taste?",
                        singleLine = false,
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // The forward-looking half of the journal: what to change
                    // on the NEXT shot. Surfaces on the Brew dial-in card when
                    // this bag is selected again (tablet parity).
                    CremaTextField(
                        value = nextPlan,
                        onValueChange = { nextPlan = it; vm.setShotNextPlan(shot.id, it) },
                        label = "Next time",
                        placeholder = if (shot.isBrewLog) "What will you change next time?" else "What will you change next shot?",
                        singleLine = false,
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // No override (privacy == null) highlights the chip matching
                    // the Settings → Sharing default — no duplicated "Default · x"
                    // chip. Tapping a chip pins this shot; tapping the pinned chip
                    // reverts to following the default.
                    // Brew Log rows never upload (issue #10), so no Visualizer privacy.
                    if (!isBrew) Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Eyebrow("Privacy")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("public" to "Public", "unlisted" to "Unlisted", "private" to "Private").forEach { (v, label) ->
                                PrivacyPill(
                                    label,
                                    on = shot.privacy == v || (shot.privacy == null && defaultPrivacy == v),
                                ) { vm.setShotPrivacy(shot.id, if (shot.privacy == v) null else v) }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }

    if (menu) {
        CremaOverflowSheet(
            title = (shot.profileName ?: shot.recipeName ?: methodLabel(shot.methodOf)) + " · " + remember(shot.completedAtMs) {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(shot.completedAtMs))
            },
            items = buildList {
                if (isBrew) {
                    // Brew rows (issue #10): the machine + Visualizer actions
                    // don't apply — logged brews are local-only.
                    add(SheetItem("download-simple", "Download brew", sub = "Crema JSON") { onExport() })
                    add(SheetItem(divider = true))
                    add(SheetItem("trash", "Delete brew", danger = true) { confirmDelete = true })
                    return@buildList
                }
                add(SheetItem("arrow-counter-clockwise", "Load profile only", sub = "Just this shot’s recipe, nothing else") { onLoadOnBrew() })
                add(SheetItem("download-simple", "Download shot", sub = "Crema JSON") { onExport() })
                // One "Upload" row over every cloud destination (Visualizer,
                // Decent), naming the ones still missing the shot — a re-upload
                // once it is everywhere (Visualizer de-dupes by telemetry SHA;
                // Decent replaces its copy). Nothing connected → the row stays
                // VISIBLE but disabled with the way in (a hidden action is
                // undiscoverable). Then a "View on X" per destination that has it.
                if (!syncing) {
                    val entry = uploadMenuEntry(uploadTargets)
                    add(SheetItem("cloud-arrow-up", entry.title, sub = entry.sub, disabled = !entry.enabled) { vm.uploadShotTo(shot, entry.targets) })
                }
                viewableTargets(uploadTargets).forEach { t ->
                    add(SheetItem("arrow-square-out", "View on ${t.name}") { openUploadedCopy(context, t) })
                }
                // Share = the PUBLIC link to an uploaded copy, via the system share
                // sheet — only for destinations that have one.
                val shareable = shareableTargets(uploadTargets)
                shareable.forEach { t ->
                    add(SheetItem("share-network", if (shareable.size == 1) "Share link" else "Share ${t.name} link", sub = "Anyone with the link can view the shot") {
                        shareUploadedLink(context, t)
                    })
                }
                add(SheetItem(divider = true))
                add(SheetItem("trash", "Delete shot", danger = true) { confirmDelete = true })
            },
            onDismiss = { menu = false },
        )
    }

    if (confirmDelete) {
        CremaConfirmDialog(
            title = "Delete shot?",
            body = "This shot will be removed from this device. This can’t be undone.",
            confirmLabel = "Delete",
            icon = "trash",
            danger = true,
            onConfirm = { vm.deleteShot(shot.id); confirmDelete = false; onDeleted() },
            onDismiss = { confirmDelete = false },
        )
    }

}

/**
 * The Brew strip's swap dropdown, beans only — anchored under the detail's
 * title block for re-attributing a shot (issue #16 round 3). Same visual
 * grammar as PhoneBrewScreen's SwapDropdown (DdRow, avatar, check-circle),
 * plus a "No bean" row to clear the attribution.
 */
@Composable
private fun ShotBeanSwapDropdown(
    ui: coffee.crema.ui.MainUiState,
    currentBeanId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val ddWidth = LocalConfiguration.current.screenWidthDp.dp - CremaEdge * 2
    CremaAnchoredPopup(expanded = true, onDismiss = onDismiss) {
        Surface(
            shape = RoundedCornerShape(CremaCardSpec.phoneRadius),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 18.dp,
            modifier = Modifier.width(ddWidth).heightIn(max = 560.dp),
        ) {
            Column(Modifier.padding(8.dp).verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
                    Eyebrow("Shot bean")
                }
                // Current bean leads, then favourites, then store order — the
                // Brew picker's shared rank, unarchived only.
                rankBeansForPicker(ui.beans.filter { it.archivedAt == null }, currentBeanId).forEach { b ->
                    val activeRow = b.id == currentBeanId
                    val roaster = ui.roasters.firstOrNull { it.id == b.roasterId }?.name
                    DdRow(active = activeRow, onClick = { onSelect(b.id) }) {
                        RoasterMarkAvatar(name = roaster ?: b.name, sizeDp = 30, cornerDp = 8, fontSize = 13.sp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOfNotNull(roaster, b.name).joinToString(" \u00b7 "),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            val days = beanDaysOffRoast(b)
                            Text(
                                listOfNotNull(
                                    days?.let { "${it}d off roast" },
                                    b.grinderSetting?.takeIf { it.isNotBlank() }?.let { "Grind $it" },
                                ).joinToString(" \u00b7 ").ifEmpty { "\u2014" },
                                style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                                color = if (activeRow) LocalContentColor.current.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (activeRow) PhIcon("check-circle", sizeDp = 18, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
                DdRow(active = currentBeanId == null, onClick = { onSelect(null) }) {
                    PhIcon("prohibit", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("No bean", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (currentBeanId == null) PhIcon("check-circle", sizeDp = 18, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** The dashed "PLANNED" entry beside WEIGHT / POUR RATE (issue #10). */
@Composable
private fun PlannedDetailLegend() {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(width = 10.dp, height = 3.dp)) {
            drawLine(
                color,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                strokeWidth = size.height * 0.6f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
            )
        }
        Text(
            "PLANNED",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DetailLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width = 8.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

@Composable
private fun DetailMetric(value: String, label: String, color: Color?, modifier: Modifier = Modifier) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(
            Modifier.padding(vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 16.sp, fontFeatureSettings = "tnum"),
                color = color ?: MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PrivacyPill(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            // A pill never wraps its label — narrow-width parity with the
            // tablet PrivacyChip.
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun shotRatioLabel(shot: StoredShot): String? =
    // Method-aware (issue #10): espresso speaks yield-out, filter methods
    // water-in — one core rule shared with the tablet + web.
    shot.methodRatioLabel()

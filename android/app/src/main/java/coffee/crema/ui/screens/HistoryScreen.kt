package coffee.crema.ui.screens

import coffee.crema.ui.fmt
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.ui.convertPressure
import coffee.crema.ui.convertTemp
import coffee.crema.ui.convertWeight
import coffee.crema.ui.formatRatio
import coffee.crema.ui.formatWeight
import coffee.crema.ui.relativeAgo
import coffee.crema.history.StoredShot
import coffee.crema.ui.compare.CompareDialog
import coffee.crema.ui.compare.RowCheck
import coffee.crema.ui.compare.TabletCompareBar
import coffee.crema.ui.compare.rememberCompareSelection
import coffee.crema.history.filterAndSortShots
import coffee.crema.beans.roastBand
import coffee.crema.core.daysOffRoast as coreDaysOffRoast
import coffee.crema.ui.freshnessColor
import coffee.crema.brew.methodIcon
import coffee.crema.brew.methodLabel
import coffee.crema.history.beanLabel
import coffee.crema.history.brewHistoryStats
import coffee.crema.history.effectiveGrindSetting
import coffee.crema.history.formatShotDuration
import coffee.crema.history.grindLabel
import coffee.crema.history.isManualLog
import coffee.crema.history.methodOf
import coffee.crema.history.methodRatioLabel
import coffee.crema.ui.brewlog.BrewSessionCanvas
import coffee.crema.ui.brewlog.BrewSparkChart
import coffee.crema.ui.brewlog.BrewLogOwner
import coffee.crema.ui.brewlog.LogBrewSheet
import coffee.crema.ui.brewlog.MethodMarkTile
import coffee.crema.ui.MainViewModel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import coffee.crema.ui.formatTemp
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaEmptyState
import coffee.crema.ui.components.CremaFreshnessChip
import coffee.crema.ui.components.CremaHeaderBlock
import coffee.crema.ui.components.CremaStarRating
import coffee.crema.ui.components.HeaderBlockLine
import coffee.crema.ui.components.HeaderLineStyle
import coffee.crema.ui.components.CremaStepper
import coffee.crema.ui.components.CremaSparkChart
import coffee.crema.ui.components.CremaTextField
import coffee.crema.ui.components.CremaValueUnit
import coffee.crema.ui.theme.HankenGrotesk
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.CremaFilterDropdown
import coffee.crema.ui.components.CremaSortControl
import coffee.crema.ui.components.SortKey
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.CremaSearchPill
import coffee.crema.ui.components.PhIcon
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
import coffee.crema.ui.components.CremaIconButton
import coffee.crema.ui.components.CremaIconTone
import coffee.crema.ui.components.CremaSplitButton
import coffee.crema.ui.components.SplitMenuItem
import coffee.crema.ui.components.CremaOverflowMenu
import coffee.crema.ui.components.OverflowItem
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.components.ShotQualityCard
import coffee.crema.core.ShotQualityReport
import coffee.crema.ui.theme.CremaTheme
import androidx.core.net.toUri
import coffee.crema.ui.UploadTarget
import coffee.crema.ui.uploadMenuEntry
import coffee.crema.ui.viewableTargets
import coffee.crema.ui.shareableTargets
import coffee.crema.ui.openUploadedCopy
import coffee.crema.ui.UploadPip
import coffee.crema.history.isBrewLog
import coffee.crema.ui.uploadPipFor
import androidx.compose.ui.graphics.Color
import coffee.crema.ui.shareUploadedLink

/*
 * History (shot log) — M4. A master-detail over the captured shots: the list on
 * the left, and a detail pane on the right with a metric strip + the shot's
 * static chart (ShotChart with live=false) over its stored telemetry slice.
 */
@OptIn(ExperimentalLayoutApi::class)
// Shot-log sort fields — icons match the web history SortPill; shared by the
// tablet sort control here and the phone history screen.
val historySortKeys = listOf(
    SortKey("date", "Date", "calendar"),
    SortKey("rating", "Rating", "star"),
    SortKey("profile", "Profile", "list-bullets"),
    SortKey("bean", "Bean", "coffee-bean"),
    SortKey("yield", "Yield", "scales"),
    SortKey("time", "Time", "timer"),
)

@Composable
fun HistoryScreen(
    vm: MainViewModel,
    onNav: (String) -> Unit,
    onConnect: (String) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val connected = ui.bleState == De1BleManager.State.READY
    val scaleConnected = ui.scaleState == ScaleBleManager.State.READY
    var selectedId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var range by remember { mutableStateOf("all") }
    // Web history: per-profile filter pills ahead of the range chips.
    var profileFilter by remember { mutableStateOf<String?>(null) }
    // Bean filter — scope the log to one bag (bean-workflow-unify §B).
    var beanFilter by remember { mutableStateOf<String?>(null) }
    // Brew Log method filter (issue #10) — rendered only once a second
    // method exists in history, so espresso-only users never see it.
    var methodFilter by remember { mutableStateOf<String?>(null) }
    // The Log-brew sheet — the Brew Log's manual-entry door. Its draft is
    // VM-held ([MainViewModel.logBrew]) so a rotation across 840dp hands it to
    // the phone's pushed route intact; this tab shows it when it owns it.
    var sort by remember { mutableStateOf("date") }
    var sortDesc by remember { mutableStateOf(true) } // newest / highest first
    // Compare: select 2–5 shots → overlay their curves in a modal (HistoryCompareHooks).
    val sel = rememberCompareSelection()
    // Shots missing from at least one enabled destination — the "Upload N" catch-up.
    val missingUploads = ui.sharing.missingUploadTotal
    var compareOpen by remember { mutableStateOf(false) }
    // Tapping Brew's "Last shot" card requests that shot here — select it + clear
    // any active range filter so it's guaranteed visible, then consume the request.
    LaunchedEffect(ui.pendingHistoryShotId) {
        ui.pendingHistoryShotId?.let {
            selectedId = it
            range = "all"
            profileFilter = null // an active profile pill must not hide the deep-linked shot
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
    val launchSave: (String, String?) -> Unit = { name, content -> if (content != null) { pendingExport = content; saveLauncher.launch(name) } }

    // Client-side search + time-range filter + sort over the shot log. The stat
    // strip is scoped to the same filtered set (issue 48) — it reflects what the
    // current filter / range shows, not all-time.
    val shots = filterAndSortShots(ui.history, query, range, profileFilter, sort, sortDesc, System.currentTimeMillis(), beanFilter, methodFilter)
    // Default the detail to the newest (top) shot until the user picks one.
    val selected = shots.firstOrNull { it.id == selectedId } ?: shots.firstOrNull()

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CremaNavigationRail(
            active = "history",
            onNav = onNav,
            machineConnected = connected,
            scaleConnected = scaleConnected,
            onConnect = onConnect,
        )
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // Short-window compaction (issue #35): on a landscape 8" tablet the
            // full eyebrow + headlineLarge + subtitle header ate ~100dp of a
            // ~700dp-tall window before the list. Below 800dp of height the
            // title drops to one compact line (the shot count lives in the
            // Shots stat tile anyway) and the stat tiles slim down.
            val shortWindow = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 800
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = if (shortWindow) 12.dp else 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Rows are "shots" only while the log is purely espresso —
                // once a pourover exists the page is honestly a brew log
                // (issue #10; web parity).
                val nonEsp = ui.history.any { it.methodOf != null && it.methodOf != "espresso" }
                val rowNoun = if (nonEsp) "brew" else "shot"
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (!shortWindow) Eyebrow("Library")
                    Text(
                        if (nonEsp) "Brew history" else "Shot history",
                        style = if (shortWindow) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!shortWindow) Text(
                        "${ui.history.size} ${rowNoun}s on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The header row gained a fourth action (Log brew) — collapse
                // to icon buttons whenever the window is narrow OR short
                // (the compact-header variant already trades chrome for the
                // list), so the serif title never wraps a letter at a time.
                val narrowBar = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 1000 || shortWindow
                if (ui.history.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    CremaSearchPill(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "Search profile, bean, notes…",
                        modifier = if (narrowBar) Modifier.weight(1f) else Modifier.width(260.dp),
                    )
                }
                if (narrowBar) {
                    // 7"/portrait: icon-only actions so the (long) title + search keep
                    // their width. Export's secondary "current filter" format stays a
                    // landscape affordance; the icon runs the primary all-shots export.
                    Spacer(Modifier.width(8.dp))
                    CremaIconButton("plus-circle", { vm.openLogBrew(BrewLogOwner.HISTORY) }, tone = CremaIconTone.Tonal)
                    Spacer(Modifier.width(4.dp))
                    CremaIconButton("upload-simple", { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }, tone = CremaIconTone.Tonal)
                    if (missingUploads > 0) {
                        Spacer(Modifier.width(4.dp))
                        CremaIconButton("cloud-arrow-up", { vm.uploadMissing() }, tone = CremaIconTone.Tonal)
                    }
                    if (ui.history.isNotEmpty()) {
                        Spacer(Modifier.width(4.dp))
                        CremaIconButton("download-simple", { launchSave("crema-history.json", vm.shotsJson(null)) }, tone = CremaIconTone.Tonal)
                        if (!sel.selecting && ui.history.size >= 2) {
                            Spacer(Modifier.width(4.dp))
                            CremaIconButton("arrows-left-right", sel::enter, tone = CremaIconTone.Tonal)
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp)) {
                        // Log brew — the Brew Log's manual-entry door (issue #10):
                        // pourover / AeroPress / French press made off the machine.
                        CremaButton(
                            onClick = { vm.openLogBrew(BrewLogOwner.HISTORY) },
                            variant = CremaButtonVariant.Outlined,
                            icon = "plus-circle",
                            label = "Log brew",
                        )
                        CremaButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            variant = CremaButtonVariant.Outlined,
                            icon = "upload-simple",
                            label = "Import",
                        )
                        // Catch-up: every shot missing from an enabled destination, in one
                        // tap (web "Upload N"). Hidden at zero.
                        if (missingUploads > 0) {
                            CremaButton(
                                onClick = { vm.uploadMissing() },
                                variant = CremaButtonVariant.Outlined,
                                icon = "cloud-arrow-up",
                                enabled = !ui.sharing.catchUpBusy,
                                label = if (ui.sharing.catchUpBusy) "Uploading…" else "Upload $missingUploads",
                            )
                        }
                        if (ui.history.isNotEmpty()) {
                            CremaSplitButton(
                                icon = "download-simple",
                                label = "Export",
                                menuHead = "Export as",
                                onPrimary = { launchSave("crema-history.json", vm.shotsJson(null)) },
                                items = listOf(
                                    SplitMenuItem("file-text", "All shots", "Every shot as one Crema JSON file. Re-importable in Crema.") { launchSave("crema-history.json", vm.shotsJson(null)) },
                                    SplitMenuItem("file-code", "Current filter", "Only the ${shots.size} shot(s) matching your search and date range.") { launchSave("crema-history-filtered.json", vm.shotsJson(shots.map { it.id })) },
                                ),
                            )
                            if (!sel.selecting) {
                                CremaButton(
                                    onClick = sel::enter,
                                    variant = CremaButtonVariant.Outlined,
                                    icon = "arrows-left-right",
                                    label = "Compare",
                                    enabled = ui.history.size >= 2,
                                )
                            }
                        }
                    }
                }
            }
            if (ui.history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CremaEmptyState(
                        message = "No shots yet — pull one on Brew.",
                        // Web hi-empty-page: an import CTA so a fresh install can
                        // bring an existing history straight in.
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
            } else {
                StatsStrip(shots, ui.weightUnit, compact = shortWindow)
                // Profile pills (left) + bean / method / range / sort controls
                // (right). The Brew Log's method dropdown made the control
                // group wide enough to crush the pills into a one-word column
                // on a short/narrow tablet pane (phone landscape, 7"), so:
                // pills always scroll on ONE line, and under 1000dp the
                // controls drop to their own line (sized by the pane).
                val pills: @Composable (Modifier) -> Unit = { m ->
                    Row(m.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Per-profile pills (web .hi-filter p: pills) — only profiles
                        // that actually have shots, ordered by shot count.
                        val byProfile = ui.history.mapNotNull { it.profileName }
                            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
                        CremaFilterChip(
                            label = "All",
                            selected = profileFilter == null,
                            count = ui.history.size,
                            onClick = { profileFilter = null },
                        )
                        byProfile.take(6).forEach { (name, count) ->
                            CremaFilterChip(
                                label = name,
                                selected = profileFilter == name,
                                count = count,
                                onClick = { profileFilter = if (profileFilter == name) null else name },
                            )
                        }
                    }
                }
                val controls: @Composable () -> Unit = {
                    // Bean — a split dropdown scoping the log to one bag (the
                    // bag as a unit of history, bean-workflow-unify §B). Only
                    // bags that actually have shots, ordered by shot count;
                    // labels prefer the live library name over the snapshot's.
                    run {
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
                    }
                    // Method — the Brew Log filter (issue #10). Only rendered
                    // once a second method exists, so espresso-only users
                    // never meet it.
                    run {
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
                    }
                    // Date range — a split dropdown (calendar glyph · value),
                    // not pills: it's a low-frequency pick beside the sort.
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
                            // A fresh field lands on its obvious direction (web
                            // DEFAULT_DIR): newest/highest first, names A→Z.
                            sortDesc = key !in setOf("profile", "bean")
                        },
                        onToggleDirection = { sortDesc = !sortDesc },
                    )
                }
                BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp)) {
                    if (maxWidth >= 1000.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pills(Modifier.weight(1f))
                            controls()
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            pills(Modifier.fillMaxWidth())
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                            ) { controls() }
                        }
                    }
                }
                if (sel.selecting) {
                    TabletCompareBar(
                        sel,
                        onCompare = { compareOpen = true },
                        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                    )
                }
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LazyColumn(
                        // Narrow 7" tablet: a slimmer shot list leaves the detail
                        // panel (7-metric row + chart) a usable width; 10" keeps 480.
                        modifier = Modifier
                            .width(if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 1100) 320.dp else 480.dp)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                    ) {
                        items(shots, key = { it.id }) { shot ->
                            ShotRow(
                                shot = shot,
                                selected = if (sel.selecting) sel.isPicked(shot.id) else shot.id == selected?.id,
                                syncing = shot.id in ui.sharing.uploadingShotIds,
                                pip = uploadPipFor(ui.sharing.uploadTargets[shot.id].orEmpty()),
                                weightUnit = ui.weightUnit,
                                selecting = sel.selecting,
                                picked = sel.isPicked(shot.id),
                                dimmed = sel.atCap(shot.id),
                                onClick = { if (sel.selecting) sel.toggle(shot.id) else selectedId = shot.id },
                            )
                        }
                    }
                    if (selected != null) {
                        val detailContext = androidx.compose.ui.platform.LocalContext.current
                        // Core shot-quality analysis (Decenza port) — computed once
                        // per selection; null (thin/malformed shot) renders nothing.
                        val quality = remember(selected.id) { vm.analyzeShotQuality(selected) }
                        ShotDetail(
                            shot = selected,
                            quality = quality,
                            // Fixed archival set (web detail chart): pressure + flow +
                            // coffee temp + weight — not the QC live-chart selection.
                            channels = setOf("pressure", "flow", "headTemp", "weight"),
                            weightUnit = ui.weightUnit,
                            tempUnit = ui.tempUnit,
                            pressureUnit = ui.pressureUnit,
                            onRate = { r, n -> vm.updateShot(selected.id, r, n) },
                            onNextPlanChange = { p -> vm.setShotNextPlan(selected.id, p) },
                            onStartFromShot = { vm.startFromShot(selected.id) },
                            onLoadOnBrew = { vm.loadProfileOnBrew(selected.profileName) },
                            onExport = { vm.exportShot(selected.id) },
                            onDelete = { vm.deleteShot(selected.id); selectedId = null },
                            // One Upload row over Visualizer + Decent (a re-upload for a
                            // destination that already has the shot: Visualizer de-dupes
                            // by telemetry SHA; Decent replaces its copy) — #44 follow-up.
                            uploadTargets = ui.sharing.uploadTargets[selected.id].orEmpty(),
                            syncing = selected.id in ui.sharing.uploadingShotIds,
                            onUpload = { targets -> vm.uploadShotTo(selected, targets) },
                            onViewUploaded = { t -> openUploadedCopy(detailContext, t) },
                            onShareUploaded = { t -> shareUploadedLink(detailContext, t) },
                            // Brew rows (issue #10): Log again re-opens the form
                            // seeded from this brew; manual rows keep their facts
                            // editable, re-settling the bag on a dose change.
                            onLogAgain = { vm.openLogBrew(BrewLogOwner.HISTORY, prefill = selected) },
                            onManualDoseChange = { vm.updateManualBrew(selected.id, doseG = it) },
                            onManualWaterChange = {
                                if (selected.methodOf == "espresso") vm.updateManualBrew(selected.id, yieldG = it)
                                else vm.updateManualBrew(selected.id, waterG = it)
                            },
                            onManualTempChange = { vm.updateManualBrew(selected.id, brewTempC = it) },
                            onManualTimeChange = { vm.updateManualBrew(selected.id, durationMs = it) },
                            defaultPrivacy = ui.visualizer.privacy,
                            onPrivacyChange = { p -> vm.setShotPrivacy(selected.id, p) },
                            // Post-hoc log fix-ups (issue #16): the dial-in loop spans
                            // shots, and the wrong bean is sometimes active at pull time.
                            beanChoices = remember(ui.beans, ui.roasters) {
                                ui.beans.map { b ->
                                    val roaster = ui.roasters.firstOrNull { it.id == b.roasterId }?.name
                                    b.id to listOfNotNull(roaster, b.name).joinToString(" \u00b7 ")
                                }
                            },
                            onGrindChange = { g -> vm.setShotGrind(selected.id, g) },
                            onBeanChange = { bid -> vm.setShotBean(selected.id, bid) },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }

    if (compareOpen) {
        val compareShots = sel.picked.mapNotNull { id -> ui.history.firstOrNull { it.id == id } }
        if (compareShots.size >= 2) {
            CompareDialog(
                shots = compareShots,
                weightUnit = ui.weightUnit,
                tempUnit = ui.tempUnit,
                pressureUnit = ui.pressureUnit,
                onDismiss = { compareOpen = false },
            )
        } else {
            compareOpen = false
        }
    }

    LogBrewSheet(vm, owner = BrewLogOwner.HISTORY)
}

// Aggregate stats strip — 6 tiles matching the prototype (.hist2-stats,
// repeat(6,1fr)): Today / This week / Total / Avg yield / Avg time / Avg rating.
// Counts interpolate ints directly ("$n", no %d/UByte crash); averages are
// Doubles, so %f is safe. Day/week boundaries are simple millis math off
// System.currentTimeMillis(); recomputed when `history` changes.
@Composable
private fun StatsStrip(history: List<StoredShot>, weightUnit: String, compact: Boolean = false) {
    // Stats over the passed (filter/range-scoped) list — issue 48, made
    // method-aware by the Brew Log (issue #10): count / beans-used / rating
    // span every row; the weight / ratio / time averages scope to espresso in
    // a mixed set (tagged "esp") or to the single method the set belongs to.
    // "Beans used" (Σ dose) replaces the beverage-weight tile — the inventory
    // number this feature exists for.
    val s = brewHistoryStats(history)
    val mixed = s.mixedMethods
    val nonEsp = history.any { it.methodOf != null && it.methodOf != "espresso" }
    val noun = (if (nonEsp) "brew" else "shot") + if (s.count == 1u) "" else "s"
    fun scoped(label: String) = if (mixed) "$label · esp" else label
    // Six tiles (the Brew Log added "Beans used"). They wrap by the width the
    // strip actually gets: one row from 600dp, 3×2 below — never a thin,
    // clipped tile and never a sideways scroll.
    val beansUsed = convertWeight(s.beansUsedG?.toFloat(), weightUnit)
    val avgWt = convertWeight(s.avgWeightG?.toFloat(), weightUnit)
    val tiles: List<@Composable (Modifier) -> Unit> = listOf(
        { m -> StatTile(if (nonEsp) "Brews" else "Shots", "${s.count}", noun, m, compact) },
        { m -> StatTile("Beans used", beansUsed.value, s.beansUsedG?.let { beansUsed.unit }, m, compact) },
        { m -> StatTile(scoped("Avg weight"), avgWt.value, s.avgWeightG?.let { avgWt.unit }, m, compact) },
        { m -> StatTile(scoped("Avg ratio"), s.avgRatio?.let { fmt("1:%.1f", it) } ?: "—", null, m, compact) },
        { m -> StatTile(scoped("Avg time"), s.avgTimeS?.let { formatShotDuration((it * 1000).toLong()) } ?: "—", null, m, compact) },
        { m -> StatTile("Avg rating", s.avgRating?.let { fmt("%.1f", it) } ?: "—", null, m, compact) },
    )
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        val perRow = if (maxWidth >= 600.dp) 6 else 3
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            tiles.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { tile -> tile(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    CremaCard(modifier, shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = if (compact) 8.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 4.dp),
        ) {
            Eyebrow(label)
            // PWA .hi-stat-val (22px mono) + em (11px SANS, half-size) — the unit must
            // be small + sans, not a 0.72x mono subscript (which read too big).
            // Compact (short-window) drops the value a step to trim the strip.
            CremaValueUnit(value, unit, valueSize = if (compact) 18.sp else 22.sp, unitSize = 11.sp, unitSans = true)
        }
    }
}

@Composable
private fun ShotRow(
    shot: StoredShot,
    selected: Boolean,
    syncing: Boolean,
    /** Cloud status across every enabled destination. */
    pip: UploadPip = UploadPip.Local,
    weightUnit: String,
    onClick: () -> Unit,
    selecting: Boolean = false,
    picked: Boolean = false,
    dimmed: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (dimmed) Modifier.alpha(0.4f) else Modifier)
            .clip(MaterialTheme.shapes.medium)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .clickable(enabled = !dimmed, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (selecting) RowCheck(picked)
        // Time column — wall-clock HH:mm over a compact relative (PWA .hi-row-time).
        val timeH = remember(shot.completedAtMs) {
            java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(shot.completedAtMs))
        }
        Column(Modifier.width(58.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CremaValueUnit(timeH, null, valueSize = 12.sp)
            Text(
                relativeAgo(shot.completedAtMs),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // The sparkline slot: DE1 telemetry when recorded; a guided brew's
        // weight curve; else the method mark tile (issue #10).
        val rowSeries = shot.brewSeries
        when {
            shot.samples.isNotEmpty() -> CremaSparkChart(
                samples = shot.samples,
                modifier = Modifier
                    .width(72.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            )
            rowSeries != null && rowSeries.samples.isNotEmpty() -> BrewSparkChart(
                series = rowSeries,
                modifier = Modifier
                    .width(72.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            )
            else -> MethodMarkTile(shot.methodOf)
        }
        // Main — profile name over the bean (PWA .hi-row-main). Brew rows show
        // the recipe / method name with a quiet "logged" provenance tag.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val rowName = shot.profileName
                ?: shot.recipeName
                ?: shot.methodOf?.let { methodLabel(it) }
                ?: "Shot"
            Text(
                if (shot.isManualLog) "$rowName · logged" else rowName,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Bean + the grind it was pulled at (issue #16) — the reporter
                // scans the list for "what did I grind last time".
                listOfNotNull(shot.beanLabel, shot.grindLabel).joinToString(" \u00b7 ").ifBlank { "\u2014" },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // One glance-able journal line: the forward-looking plan when
            // set (the most actionable thing a row can say), else the
            // tasting notes. Absent \u2192 no extra line, layout unchanged.
            val snippet = shot.nextPlan?.trim()?.takeIf { it.isNotEmpty() }
                ?: shot.notes?.trim()?.takeIf { it.isNotEmpty() }
            if (snippet != null) {
                Text(
                    snippet.replace(Regex("\\s+"), " "),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 11.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    ),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Ratio + Yield metrics, each value over a dimmed caps label (PWA
        // .hi-row-metric). Filter brews speak water-in (issue #10).
        RowMetric(shotRatio(shot) ?: "—", null, "Ratio")
        val isFilterBrew = shot.methodOf != null && shot.methodOf != "espresso" && (shot.waterG ?: 0f) > 0f
        if (isFilterBrew) {
            val rowWater = convertWeight(shot.waterG, weightUnit)
            RowMetric(rowWater.value, rowWater.unit, "Water")
        } else {
            val rowYield = convertWeight(shot.yieldG, weightUnit)
            RowMetric(rowYield.value, shot.yieldG?.let { rowYield.unit }, "Yield")
        }
        // Inline rating: compact stars, faint when unrated.
        CremaStarRating(
            shot.rating ?: 0,
            starDp = 11,
            emptyTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
        // Visualizer sync pip (web .hi-row pip): cloud-check once uploaded,
        // a spinner mid-upload, a faint cloud when local-only.
        when {
            // Brew-log rows never upload (issue #10), so they carry no cloud pip.
            shot.isBrewLog -> {}
            syncing -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            pip == UploadPip.Uploaded -> PhIcon("cloud-check", sizeDp = 13, tint = CremaTheme.telemetry.success)
            // On some enabled destinations, missing from another.
            pip == UploadPip.Partial -> PhIcon("cloud-arrow-up", sizeDp = 13, tint = Color(0xFFDBA764))
            else -> PhIcon("cloud", sizeDp = 13, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f))
        }
    }
}

// One right-aligned list-row metric: a mono value (+ small unit) over a dimmed
// uppercase label (PWA .hi-row-metric / .hi-row-metric-l).
@Composable
private fun RowMetric(value: String, unit: String?, label: String) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        CremaValueUnit(value, unit, valueSize = 13.sp, unitSize = 10.sp)
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.3.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShotDetail(
    shot: StoredShot,
    /** The core's shot-quality report, or null when unanalyzable (no block). */
    quality: ShotQualityReport?,
    channels: Set<String>,
    weightUnit: String,
    tempUnit: String,
    pressureUnit: String,
    modifier: Modifier,
    onRate: (Int, String) -> Unit,
    onLoadOnBrew: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    /**
     * The cloud destinations (Visualizer, Decent) with their enabled /
     * uploaded state for this shot — folded into ONE "Upload" menu row by
     * [uploadMenuEntry] plus a "View on X" per destination that holds the
     * shot, so adding a destination never adds a row.
     */
    uploadTargets: List<UploadTarget> = emptyList(),
    /** An upload of this shot is in flight — the Upload row hides until it lands. */
    syncing: Boolean = false,
    /** Push this shot to the given destinations (a re-upload for ones that already have it). */
    onUpload: (List<UploadTarget>) -> Unit = {},
    /** Open an uploaded copy ("View on X"). */
    onViewUploaded: (UploadTarget) -> Unit = {},
    /** Share the public link to an uploaded copy (system share sheet). */
    onShareUploaded: (UploadTarget) -> Unit = {},
    /** The Sharing default the "Default" privacy chip names. */
    defaultPrivacy: String = "unlisted",
    /** Per-shot privacy override edit; null reverts to the Sharing default. */
    onPrivacyChange: (String?) -> Unit = {},
    /** Library beans as id → "Roaster · Name" labels, for Change bean. */
    beanChoices: List<Pair<String, String>> = emptyList(),
    /** Edit the grind recorded on this shot; null clears it (issue #16). */
    onGrindChange: (Float?) -> Unit = {},
    /** Re-attribute this shot to another bean; null = no bean (issue #16). */
    onBeanChange: (String?) -> Unit = {},
    /** Edit the forward-looking "next time" plan (local-only). */
    onNextPlanChange: (String) -> Unit = {},
    /** Prefill Brew from this shot: profile + grind + yield target. */
    onStartFromShot: (() -> Unit)? = null,
    /** Brew rows (issue #10): re-open the Log-brew form seeded from this
     *  brew, dated now — the Brew Log's "Start from this shot" sibling. */
    onLogAgain: (() -> Unit)? = null,
    /** Manual brew rows: edit the user-entered facts (re-settles the bag
     *  on a dose change at the call site). Null = read-only. */
    onManualDoseChange: ((Float?) -> Unit)? = null,
    onManualWaterChange: ((Float?) -> Unit)? = null,
    onManualTempChange: ((Float?) -> Unit)? = null,
    /** Brew time, ms (web parity — the storyboard had deferred it on Android). */
    onManualTimeChange: ((Long?) -> Unit)? = null,
) {
    // Brew Log rows (issue #10).
    val method = shot.methodOf
    val isBrew = method != null
    val manual = shot.isManualLog
    // The pane scrolls: the chart keeps its full (pre-quality-card) height and
    // the quality card / rating / notes live below the fold instead of
    // squeezing the chart (user feedback 2026-07-07).
    Column(
        modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        var confirmDelete by remember(shot.id) { mutableStateOf(false) }
        var changeBean by remember(shot.id) { mutableStateOf(false) }
        // Detail head — the Brew header's PROFILE + BEAN blocks over the shot's
        // frozen snapshot (issue #16 round 4: the SAME shared components, not a
        // lookalike). The one deliberate difference from Brew: the pull
        // timestamp rides the profile block's eyebrow row — a shot is an
        // archival record.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            CremaHeaderBlock(
                eyebrow = if (isBrew) "Method" else "Profile",
                eyebrowTrailing = {
                    Text(
                        remember(shot.completedAtMs) {
                            java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(shot.completedAtMs))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                title = when {
                    isBrew -> (shot.recipeName ?: methodLabel(method)) + if (manual) " · logged" else ""
                    else -> shot.profileName ?: "Shot"
                },
                // A shot's profile is a historical fact — no picker, no caret.
                showCaret = false,
                modifier = Modifier.weight(1f),
                lines = if (isBrew) buildList {
                    // Brew rows: time · water (or yield) · ratio.
                    val meta = listOfNotNull(
                        shot.durationMs.takeIf { it > 0 }?.let { formatShotDuration(it) },
                        shot.waterG?.takeIf { it > 0f }?.let { "${it.toInt()} g water" }
                            ?: shot.yieldG?.let { formatWeight(it, weightUnit) },
                        shot.methodRatioLabel(),
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) add(HeaderBlockLine(meta))
                } else buildList {
                    // Brew's two profile lines, read from the shot's embedded
                    // profile snapshot — the JSON-v2 shape capture freezes
                    // (title/steps/target_weight, snake_case) — targets +
                    // character; the shot's ACTUALS live in the tiles below.
                    val wire = shot.profile
                    fun field(key: String) = (wire?.get(key) as? JsonPrimitive)?.contentOrNull
                    val dose = field("dose")?.toFloatOrNull() ?: shot.doseG
                    val yieldOut = field("target_weight")?.toFloatOrNull() ?: shot.yieldTargetG
                    // Brew temp = the first step's setpoint (v2 has no
                    // top-level brew temp).
                    val temp = ((wire?.get("steps") as? JsonArray)?.firstOrNull() as? JsonObject)
                        ?.let { (it["temperature_c"] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull() }
                    val meta = listOfNotNull(
                        if (dose != null && yieldOut != null) formatRatio(dose, yieldOut) else null,
                        yieldOut?.let { formatWeight(it, weightUnit) },
                        temp?.let { formatTemp(it, tempUnit) },
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) add(HeaderBlockLine(meta))
                    // .bh-spec — "{beverage} · {author}" (v2 carries no roast).
                    val spec = listOfNotNull(
                        field("beverage_type")?.replaceFirstChar { it.uppercase() },
                        field("author")?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (spec.isNotBlank()) add(HeaderBlockLine(spec, HeaderLineStyle.Spec))
                },
            )
            // Bean block — tap to re-attribute. The freshness chip is STATIC:
            // the bean's age at PULL time ("7d off roast" forever if it was
            // pulled at 7 days), not today's age. The grind moved here from the
            // profile meta (user direction) — the shot's recorded grind, else
            // the snapshot reference, exactly what History rows show.
            run {
                val daysAtPull = remember(shot.id) {
                    coreDaysOffRoast(shot.bean?.roastedOn, null, null, shot.completedAtMs)?.toInt()
                }
                CremaHeaderBlock(
                    eyebrow = "Bean",
                    eyebrowTrailing = if (daysAtPull != null) {
                        {
                            CremaFreshnessChip(
                                "${daysAtPull}d off roast",
                                freshnessColor(false, shot.bean?.roastLevel?.toInt(), daysAtPull),
                            )
                        }
                    } else {
                        null
                    },
                    title = shot.beanLabel ?: "No bean",
                    caretAtEnd = true,
                    onClick = { changeBean = true },
                    modifier = Modifier.width(264.dp),
                    lines = buildList {
                        val spec = listOfNotNull(
                            roastBand(shot.bean?.roastLevel?.toInt()),
                            shot.grindLabel,
                            shot.bean?.grinder?.takeIf { it.isNotBlank() },
                        ).joinToString(" · ")
                        if (spec.isNotBlank()) add(HeaderBlockLine(spec, HeaderLineStyle.Spec))
                        val tags = shot.bean?.tags?.filter { it.isNotBlank() }.orEmpty().joinToString(" · ")
                        if (tags.isNotBlank()) add(HeaderBlockLine(tags, HeaderLineStyle.Tags))
                    },
                )
            }
            CremaOverflowMenu(items = buildList {
                if (isBrew) {
                    // Brew rows (issue #10): the machine actions don't apply;
                    // "Log again" is the dial-in loop's sibling, and logged
                    // brews never touch Visualizer.
                    onLogAgain?.let { add(OverflowItem("arrow-counter-clockwise", "Log again", it)) }
                    add(OverflowItem("download-simple", "Export", onExport))
                    add(OverflowItem("trash", "Delete brew", { confirmDelete = true }, danger = true))
                    return@buildList
                }
                // The full dial-in leads; the profile-only reload stays for
                // users who want just the recipe (web SplitButton parity).
                onStartFromShot?.let { add(OverflowItem("coffee", "Start from this shot", it)) }
                add(OverflowItem("arrow-counter-clockwise", "Load profile only", onLoadOnBrew))
                add(OverflowItem("download-simple", "Export", onExport))
                // One "Upload" row for every cloud destination (Visualizer, Decent),
                // naming the ones still missing the shot; a "View on X" per
                // destination that holds it. Nothing connected → the row stays
                // VISIBLE but disabled — a hidden action is undiscoverable.
                // Hidden while an upload of this shot is in flight (no double POST).
                if (!syncing) {
                    val entry = uploadMenuEntry(uploadTargets)
                    add(OverflowItem("cloud-arrow-up", entry.title, { onUpload(entry.targets) }, enabled = entry.enabled))
                }
                viewableTargets(uploadTargets).forEach { t ->
                    add(OverflowItem("arrow-square-out", "View on ${t.name}", { onViewUploaded(t) }))
                }
                // Share = the PUBLIC link to an uploaded copy: one row when the shot
                // is shareable from one destination, one per destination otherwise.
                val shareable = shareableTargets(uploadTargets)
                shareable.forEach { t ->
                    add(OverflowItem("share-network", if (shareable.size == 1) "Share link" else "Share ${t.name} link", { onShareUploaded(t) }))
                }
                add(OverflowItem("trash", "Delete shot", { confirmDelete = true }, danger = true))
            })
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (confirmDelete) {
            CremaConfirmDialog(
                title = "Delete shot?",
                body = "This shot will be removed from this device. This can’t be undone.",
                confirmLabel = "Delete",
                icon = "trash",
                danger = true,
                onConfirm = { onDelete(); confirmDelete = false },
                onDismiss = { confirmDelete = false },
            )
        }
        if (changeBean) {
            ShotBeanDialog(
                choices = beanChoices,
                currentBeanId = shot.bean?.beanId,
                onSelect = { onBeanChange(it); changeBean = false },
                onDismiss = { changeBean = false },
            )
        }
        if (isBrew) {
            // Brew rows (issue #10): six method-shaped tiles — Method / Time /
            // Dose / Water|Yield / Ratio / Temp. No peak-anything: there is no
            // machine telemetry to peak.
            val espressoRow = method == "espresso"
            // FlowRow sized by the detail pane it gets: 3×2 on a narrow pane
            // (7" landscape ≈ 500dp), 6×1 when wide.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val perRow = if (maxWidth < 600.dp) 3 else 6
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    maxItemsInEachRow = perRow,
                ) {
                    val out = if (espressoRow) shot.yieldG else shot.waterG ?: shot.yieldG
                    val mOut = convertWeight(out, weightUnit)
                    MetricCard("Method", coffee.crema.brew.methodShortLabel(method), null, Modifier.weight(1f), isText = true)
                    MetricCard("Time", if (shot.durationMs > 0) formatShotDuration(shot.durationMs) else "—", null, Modifier.weight(1f))
                    MetricCard("Dose", shot.doseG?.let { fmt("%.1f", it.toDouble()) } ?: "—", shot.doseG?.let { "g" }, Modifier.weight(1f))
                    MetricCard(if (espressoRow) "Yield" else "Water", mOut.value, out?.let { mOut.unit }, Modifier.weight(1f))
                    MetricCard("Ratio", shotRatio(shot) ?: "—", null, Modifier.weight(1f), isText = true)
                    MetricCard("Temp", shot.brewTempC?.let { fmt("%.0f", it.toDouble()) } ?: "—", shot.brewTempC?.let { "°C" }, Modifier.weight(1f))
                }
            }
            // Manual rows keep their user-entered facts editable — steppers in
            // one wrapping row (the QC idiom), re-settling the bag on dose.
            if (manual && (onManualDoseChange != null || onManualWaterChange != null || onManualTempChange != null || onManualTimeChange != null)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    onManualDoseChange?.let { commit ->
                        CremaStepper(
                            label = "Dose",
                            value = (shot.doseG ?: 0f).toDouble(),
                            unit = "g",
                            onChange = { commit(it.toFloat().takeIf { v -> v > 0f }) },
                            step = 0.5,
                            min = 0.0,
                            max = 200.0,
                        )
                    }
                    onManualWaterChange?.let { commit ->
                        CremaStepper(
                            label = if (espressoRow) "Yield" else "Water",
                            value = ((if (espressoRow) shot.yieldG else shot.waterG) ?: 0f).toDouble(),
                            unit = "g",
                            onChange = { commit(it.toFloat().takeIf { v -> v > 0f }) },
                            step = if (espressoRow) 1.0 else 10.0,
                            min = 0.0,
                            max = 2000.0,
                            fmt = { fmt("%.0f", it) },
                        )
                    }
                    onManualTempChange?.let { commit ->
                        CremaStepper(
                            label = "Temp",
                            value = (shot.brewTempC ?: 0f).toDouble(),
                            unit = "°C",
                            onChange = { commit(it.toFloat().takeIf { v -> v > 0f }) },
                            step = 1.0,
                            min = 0.0,
                            max = 100.0,
                            fmt = { fmt("%.0f", it) },
                        )
                    }
                    onManualTimeChange?.let { commit ->
                        CremaStepper(
                            label = "Time",
                            value = (shot.durationMs / 1000L).toDouble(),
                            unit = null,
                            onChange = { commit((it * 1000).toLong().takeIf { v -> v > 0L }) },
                            step = 5.0,
                            min = 0.0,
                            max = 86_400.0,
                            fmt = { coffee.crema.brew.formatClock((it * 1000).toLong()) },
                        )
                    }
                }
            }
            // A guided session draws its weight curve with stage bands; a
            // manual log shows no chart region at all — the pane tightens up.
            val series = shot.brewSeries
            if (series != null && series.samples.isNotEmpty()) {
                Surface(
                    modifier = Modifier.height(300.dp).fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    BrewSessionCanvas(series, Modifier.fillMaxSize().padding(8.dp))
                }
            }
        } else {
        // Metric tiles — 7-up grid of recessed cards (PWA .hi-metrics / .hi-metric).
        val peakWt = shot.samples.mapNotNull { it.weight }.maxOrNull()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val mPeakP = convertPressure(shot.peakPressure, pressureUnit)
            val mPeakT = convertTemp(shot.peakTemp, tempUnit)
            val mPeakWt = convertWeight(peakWt, weightUnit)
            val mYield = convertWeight(shot.yieldG, weightUnit)
            MetricCard("Time", fmt("%.0f", shot.durationMs / 1000.0), "s", Modifier.weight(1f))
            MetricCard("Peak pressure", mPeakP.value, shot.peakPressure?.let { mPeakP.unit }, Modifier.weight(1f))
            MetricCard("Peak temp", mPeakT.value, shot.peakTemp?.let { mPeakT.unit }, Modifier.weight(1f))
            MetricCard("Peak wt", mPeakWt.value, peakWt?.let { mPeakWt.unit }, Modifier.weight(1f))
            MetricCard("Yield", mYield.value, shot.yieldG?.let { mYield.unit }, Modifier.weight(1f))
            MetricCard("Ratio", shotRatio(shot) ?: "—", null, Modifier.weight(1f), isText = true)
            MetricCard("Samples", "${shot.samples.size}", null, Modifier.weight(1f))
        }
        Surface(
            // Fixed height inside the scrolling pane — the chart's dominant,
            // pre-quality-card size (it was weight(1f) before the card ate it).
            modifier = Modifier.height(360.dp).fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            if (shot.samples.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No telemetry recorded for this shot.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                EnlargeableChart(Modifier.fillMaxSize()) { m ->
                    CanvasShotChart(
                        samples = shot.samples,
                        enabledChannels = channels,
                        live = false,
                        modifier = m.padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
            }
        }
        }
        // Shot quality — the core's analysis over the stored telemetry, right
        // below the chart it describes. Absent (null) for thin/legacy shots.
        if (quality != null) ShotQualityCard(quality)
        var rating by remember(shot.id) { mutableStateOf(shot.rating ?: 0) }
        var notes by remember(shot.id) { mutableStateOf(shot.notes ?: "") }
        var nextPlan by remember(shot.id) { mutableStateOf(shot.nextPlan ?: "") }
        // Rating (left) + the grind stepper (right) share one line (user
        // direction, issue #16 round 4) — a FlowRow so a narrow detail pane
        // (7-8" tablets) wraps the stepper onto its own line instead of
        // crushing it. Each stepper click persists; the Visualizer PATCH
        // debounces behind it.
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Rating")
                CremaStarRating(rating, onChange = { rating = it; onRate(it, notes) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("Grind")
                CremaStepper(
                    value = shot.effectiveGrindSetting?.toDoubleOrNull() ?: 0.0,
                    onChange = { onGrindChange(it.toFloat()) },
                    step = 0.1,
                    min = 0.0,
                    max = 200.0,
                    fmt = { v -> if (v % 1.0 == 0.0) fmt("%.0f", v) else fmt("%.1f", v) },
                )
            }
        }
        CremaTextField(
            value = notes,
            onValueChange = { notes = it; onRate(rating, it) },
            label = "Tasting notes",
            placeholder = "How did it taste?",
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        // The forward-looking half of the journal: what to change on the
        // NEXT shot. Surfaces on the Brew dial-in card when this bag is
        // selected again — the reason it is a separate field from notes.
        CremaTextField(
            value = nextPlan,
            onValueChange = { nextPlan = it; onNextPlanChange(it) },
            label = "Next time",
            placeholder = "What will you change next shot?",
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        // Privacy moves BELOW the notes with its label restored (user
        // direction) — the per-shot Visualizer visibility override: with no
        // override the chip matching the Settings → Sharing default is
        // highlighted; tapping a chip pins this shot; tapping the pinned chip
        // reverts to the default.
        // Brew Log rows never upload (issue #10), so no Visualizer privacy.
        if (!isBrew) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Privacy")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("public" to "Public", "unlisted" to "Unlisted", "private" to "Private").forEach { (v, label) ->
                    PrivacyChip(
                        label,
                        on = shot.privacy == v || (shot.privacy == null && defaultPrivacy == v),
                    ) { onPrivacyChange(if (shot.privacy == v) null else v) }
                }
            }
        }
    }
}

// One privacy pill (web .hi-privacy-chip): ghost border, copper wash when on.
@Composable
private fun PrivacyChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(
                1.dp,
                if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f),
                RoundedCornerShape(999.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            // A pill never wraps ("Priv/ate" on a squeezed 7" pane) — it either
            // fits or the FlowRow above moves the whole cluster down a line.
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun MetricCard(label: String, value: String, unit: String?, modifier: Modifier = Modifier, isText: Boolean = false) {
    // PWA .hi-metric tile: recessed card, 9px caps label, mono 15px value + 10px unit.
    // The Ratio renders as copper sans text (.hi-metric-v.is-text).
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isText) {
            Text(value, style = TextStyle(fontFamily = HankenGrotesk, fontSize = 13.sp), color = MaterialTheme.colorScheme.primary, maxLines = 1)
        } else {
            CremaValueUnit(value, unit, valueSize = 15.sp, unitSize = 10.sp)
        }
    }
}

private fun shotRatio(shot: StoredShot): String? =
    // Method-aware (issue #10): espresso speaks yield-out, filter methods
    // water-in — one core rule shared with the web.
    shot.methodRatioLabel()


/**
 * Re-attribute a stored shot to another library bean (issue #16) — a fresh
 * snapshot is frozen from the chosen bean; "No bean" clears the attribution.
 * Marked internal: the phone detail reuses it.
 */
@Composable
internal fun ShotBeanDialog(
    choices: List<Pair<String, String>>,
    currentBeanId: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change bean") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).heightIn(max = 400.dp),
            ) {
                (choices + (null to "No bean")).forEach { (id, label) ->
                    val selected = id == currentBeanId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(id) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PhIcon(
                            if (id == null) "prohibit" else "coffee-bean",
                            sizeDp = 16,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected) PhIcon("check", sizeDp = 15, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

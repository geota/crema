package coffee.crema.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import coffee.crema.history.beanLabel
import coffee.crema.history.historyStats
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaStarRating
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
import coffee.crema.ui.components.CremaSplitButton
import coffee.crema.ui.components.SplitMenuItem
import coffee.crema.ui.components.CremaOverflowMenu
import coffee.crema.ui.components.OverflowItem
import coffee.crema.ui.components.CremaConfirmDialog
import coffee.crema.ui.theme.CremaTheme

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
    var sort by remember { mutableStateOf("date") }
    var sortDesc by remember { mutableStateOf(true) } // newest / highest first
    // Tapping Brew's "Last shot" card requests that shot here — select it + clear
    // any active range filter so it's guaranteed visible, then consume the request.
    LaunchedEffect(ui.pendingHistoryShotId) {
        ui.pendingHistoryShotId?.let {
            selectedId = it
            range = "all"
            profileFilter = null // an active profile pill must not hide the deep-linked shot
            vm.consumePendingHistoryShot()
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
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60L * 60L * 1000L
    val startOfDay = run {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    val filtered = ui.history.filter { s ->
        val matchesSearch = query.isBlank() ||
            (s.profileName?.contains(query, ignoreCase = true) == true) ||
            (s.beanLabel?.contains(query, ignoreCase = true) == true) ||
            (s.notes?.contains(query, ignoreCase = true) == true)
        val matchesRange = when (range) {
            "today" -> s.completedAtMs >= startOfDay
            "7d" -> s.completedAtMs >= now - 7L * dayMs
            "30d" -> s.completedAtMs >= now - 30L * dayMs
            else -> true
        }
        val matchesProfile = profileFilter == null || s.profileName == profileFilter
        matchesSearch && matchesRange && matchesProfile
    }
    val sortedAsc = when (sort) {
        "rating" -> filtered.sortedBy { it.rating ?: 0 }
        "profile" -> filtered.sortedBy { it.profileName?.lowercase() ?: "" }
        "bean" -> filtered.sortedBy { it.beanLabel?.lowercase() ?: "" }
        "yield" -> filtered.sortedBy { it.yieldG ?: 0f }
        "time" -> filtered.sortedBy { it.durationMs }
        else -> filtered.sortedBy { it.completedAtMs }
    }
    val shots = if (sortDesc) sortedAsc.reversed() else sortedAsc
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
            Row(
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Eyebrow("Library")
                    Text(
                        "Shot history",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${ui.history.size} shots on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (ui.history.isNotEmpty()) {
                    CremaSearchPill(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "Search profile, bean, notes…",
                        modifier = Modifier.width(260.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 8.dp)) {
                    CremaButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                        variant = CremaButtonVariant.Outlined,
                        icon = "upload-simple",
                        label = "Import",
                    )
                    if (ui.history.isNotEmpty()) {
                        CremaSplitButton(
                            icon = "download-simple",
                            label = "Export",
                            menuHead = "Export as",
                            onPrimary = { launchSave("crema-history.json", vm.shotsJson(null)) },
                            items = listOf(
                                SplitMenuItem("file-text", "All shots", "Every shot as one Crema JSON file. Re-importable in Crema.") { launchSave("crema-history.json", vm.shotsJson(null)) },
                                SplitMenuItem("file-code", "Current filter", "Only the ${filtered.size} shot(s) matching your search and date range.") { launchSave("crema-history-filtered.json", vm.shotsJson(filtered.map { it.id })) },
                            ),
                        )
                    }
                }
            }
            if (ui.history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "No shots yet — pull one on Brew.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        // Web hi-empty-page: an import CTA so a fresh install can
                        // bring an existing history straight in.
                        CremaButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            variant = CremaButtonVariant.Outlined,
                            icon = "upload-simple",
                            label = "Import shots",
                        )
                    }
                }
            } else {
                StatsStrip(filtered, ui.weightUnit)
                // Range filter chips (left) + sort split-button (right).
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Row(
                    Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier.width(480.dp).fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
                    ) {
                        items(shots, key = { it.id }) { shot ->
                            ShotRow(
                                shot = shot,
                                selected = shot.id == selected?.id,
                                syncing = shot.id in ui.visualizer.uploadingShotIds,
                                weightUnit = ui.weightUnit,
                                onClick = { selectedId = shot.id },
                            )
                        }
                    }
                    if (selected != null) {
                        val detailContext = androidx.compose.ui.platform.LocalContext.current
                        ShotDetail(
                            shot = selected,
                            // Fixed archival set (web detail chart): pressure + flow +
                            // coffee temp + weight — not the QC live-chart selection.
                            channels = setOf("pressure", "flow", "headTemp", "weight"),
                            weightUnit = ui.weightUnit,
                            tempUnit = ui.tempUnit,
                            pressureUnit = ui.pressureUnit,
                            onRate = { r, n -> vm.updateShot(selected.id, r, n) },
                            onLoadOnBrew = { vm.loadProfileOnBrew(selected.profileName) },
                            onExport = { vm.exportShot(selected.id) },
                            onDelete = { vm.deleteShot(selected.id); selectedId = null },
                            onUploadVisualizer = if (ui.visualizer.signedIn && selected.visualizerId == null) {
                                { vm.visualizer.uploadShot(selected) }
                            } else {
                                null
                            },
                            defaultPrivacy = ui.visualizer.privacy,
                            onPrivacyChange = { p -> vm.setShotPrivacy(selected.id, p) },
                            onViewVisualizer = selected.visualizerId?.let { vid ->
                                {
                                    detailContext.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://visualizer.coffee/shots/$vid"),
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                }
            }
        }
    }
}

// Aggregate stats strip — 6 tiles matching the prototype (.hist2-stats,
// repeat(6,1fr)): Today / This week / Total / Avg yield / Avg time / Avg rating.
// Counts interpolate ints directly ("$n", no %d/UByte crash); averages are
// Doubles, so %f is safe. Day/week boundaries are simple millis math off
// System.currentTimeMillis(); recomputed when `history` changes.
@Composable
private fun StatsStrip(history: List<StoredShot>, weightUnit: String) {
    // Stats over the passed (filter/range-scoped) list — issue 48. Six tiles,
    // matching the web (PWA): count, total + average weight, then the three
    // averages (ratio / time / rating). The phone shows just the three averages.
    val s = historyStats(history)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val totalWt = convertWeight(s.totalWeightG?.toFloat(), weightUnit)
        val avgWt = convertWeight(s.avgWeightG?.toFloat(), weightUnit)
        StatTile("Shots", "${s.shots}", "shots", Modifier.weight(1f))
        StatTile("Weight", totalWt.value, s.totalWeightG?.let { totalWt.unit }, Modifier.weight(1f))
        StatTile("Avg weight", avgWt.value, s.avgWeightG?.let { avgWt.unit }, Modifier.weight(1f))
        StatTile("Avg ratio", s.avgRatio?.let { "1:%.1f".format(it) } ?: "—", null, Modifier.weight(1f))
        StatTile("Avg time", s.avgTimeS?.let { "%.0f".format(it) } ?: "—", s.avgTimeS?.let { "s" }, Modifier.weight(1f))
        StatTile("Avg rating", s.avgRating?.let { "%.1f".format(it) } ?: "—", null, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    CremaCard(modifier, shape = RoundedCornerShape(12.dp)) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Eyebrow(label)
            // PWA .hi-stat-val (22px mono) + em (11px SANS, half-size) — the unit must
            // be small + sans, not a 0.72x mono subscript (which read too big).
            CremaValueUnit(value, unit, valueSize = 22.sp, unitSize = 11.sp, unitSans = true)
        }
    }
}

@Composable
private fun ShotRow(shot: StoredShot, selected: Boolean, syncing: Boolean, weightUnit: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
        CremaSparkChart(
            samples = shot.samples,
            modifier = Modifier
                .width(72.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        )
        // Main — profile name over the bean (PWA .hi-row-main).
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                shot.profileName ?: "Shot",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                shot.beanLabel ?: "—",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Ratio + Yield metrics, each value over a dimmed caps label (PWA .hi-row-metric).
        RowMetric(shotRatio(shot) ?: "—", null, "Ratio")
        val rowYield = convertWeight(shot.yieldG, weightUnit)
        RowMetric(rowYield.value, shot.yieldG?.let { rowYield.unit }, "Yield")
        // Inline rating: compact stars, faint when unrated.
        CremaStarRating(
            shot.rating ?: 0,
            starDp = 11,
            emptyTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
        )
        // Visualizer sync pip (web .hi-row pip): cloud-check once uploaded,
        // a spinner mid-upload, a faint cloud when local-only.
        when {
            syncing -> CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            shot.visualizerId != null -> PhIcon("cloud-check", sizeDp = 13, tint = CremaTheme.telemetry.success)
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

@Composable
private fun ShotDetail(
    shot: StoredShot,
    channels: Set<String>,
    weightUnit: String,
    tempUnit: String,
    pressureUnit: String,
    modifier: Modifier,
    onRate: (Int, String) -> Unit,
    onLoadOnBrew: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
    /** Push this shot to Visualizer; null when signed out or already synced. */
    onUploadVisualizer: (() -> Unit)? = null,
    /** Open the shot on visualizer.coffee; null until it has been uploaded. */
    onViewVisualizer: (() -> Unit)? = null,
    /** The Sharing default the "Default" privacy chip names. */
    defaultPrivacy: String = "unlisted",
    /** Per-shot privacy override edit; null reverts to the Sharing default. */
    onPrivacyChange: (String?) -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var confirmDelete by remember(shot.id) { mutableStateOf(false) }
        // Detail head — title block (left) + actions (right), bottom-aligned with a
        // hairline rule (PWA .hi-detail-head: space-between, align-items: flex-end).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Web detail eyebrow: absolute "JUN 9 · 20:01" (a shot is an archival
                // record; relative time still rides on every list row).
                Eyebrow(
                    remember(shot.completedAtMs) {
                        java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(shot.completedAtMs))
                    },
                )
                Text(
                    shot.profileName ?: "Shot",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    shot.beanLabel?.let { add(it) }
                    val d = shot.doseG; val y = shot.yieldG
                    if (d != null && y != null) add("${formatWeight(d, weightUnit)} → ${formatWeight(y, weightUnit)}")
                    shotRatio(shot)?.let { add(it) }
                }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CremaButton(onClick = onLoadOnBrew, variant = CremaButtonVariant.Outlined, icon = "coffee", label = "Load on Brew")
                CremaButton(onClick = onExport, variant = CremaButtonVariant.Outlined, icon = "download-simple", label = "Export")
                CremaOverflowMenu(items = buildList {
                    onUploadVisualizer?.let { add(OverflowItem("cloud-arrow-up", "Upload to Visualizer", it)) }
                    onViewVisualizer?.let { add(OverflowItem("cloud-check", "View on Visualizer", it)) }
                    add(OverflowItem("trash", "Delete shot", { confirmDelete = true }, danger = true))
                })
            }
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
        // Metric tiles — 7-up grid of recessed cards (PWA .hi-metrics / .hi-metric).
        val peakWt = shot.samples.mapNotNull { it.weight }.maxOrNull()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val mPeakP = convertPressure(shot.peakPressure, pressureUnit)
            val mPeakT = convertTemp(shot.peakTemp, tempUnit)
            val mPeakWt = convertWeight(peakWt, weightUnit)
            val mYield = convertWeight(shot.yieldG, weightUnit)
            MetricCard("Time", "%.0f".format(shot.durationMs / 1000.0), "s", Modifier.weight(1f))
            MetricCard("Peak pressure", mPeakP.value, shot.peakPressure?.let { mPeakP.unit }, Modifier.weight(1f))
            MetricCard("Peak temp", mPeakT.value, shot.peakTemp?.let { mPeakT.unit }, Modifier.weight(1f))
            MetricCard("Peak wt", mPeakWt.value, peakWt?.let { mPeakWt.unit }, Modifier.weight(1f))
            MetricCard("Yield", mYield.value, shot.yieldG?.let { mYield.unit }, Modifier.weight(1f))
            MetricCard("Ratio", shotRatio(shot) ?: "—", null, Modifier.weight(1f), isText = true)
            MetricCard("Samples", "${shot.samples.size}", null, Modifier.weight(1f))
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
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
                CanvasShotChart(
                    samples = shot.samples,
                    enabledChannels = channels,
                    live = false,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 4.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
                )
            }
        }
        var rating by remember(shot.id) { mutableStateOf(shot.rating ?: 0) }
        var notes by remember(shot.id) { mutableStateOf(shot.notes ?: "") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Rating")
            CremaStarRating(rating, onChange = { rating = it; onRate(it, notes) })
        }
        // Privacy — the per-shot Visualizer visibility override (web .hi-privacy):
        // "Default" follows Settings → Sharing; a pinned chip overrides this shot.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow("Privacy")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PrivacyChip("Default · $defaultPrivacy", shot.privacy == null) { onPrivacyChange(null) }
                listOf("public" to "Public", "unlisted" to "Unlisted", "private" to "Private").forEach { (v, label) ->
                    PrivacyChip(label, shot.privacy == v) { onPrivacyChange(v) }
                }
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

private fun shotRatio(shot: StoredShot): String? {
    val y = shot.yieldG
    val d = shot.doseG
    return if (y != null && d != null && d > 0f) formatRatio(d, y) else null
}


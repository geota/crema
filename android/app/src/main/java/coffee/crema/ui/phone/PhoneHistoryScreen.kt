package coffee.crema.ui.phone

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coffee.crema.history.StoredShot
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.components.*
import coffee.crema.ui.phone.components.*
import coffee.crema.ui.screens.CanvasShotChart
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono

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
    var detailId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var profileFilter by remember { mutableStateOf<String?>(null) }
    var range by remember { mutableStateOf("all") }
    var exportSheet by remember { mutableStateOf(false) }

    // Brew's "Last shot" peek deep-links here: open that shot's detail.
    LaunchedEffect(ui.pendingHistoryShotId) {
        ui.pendingHistoryShotId?.let {
            detailId = it
            range = "all"
            profileFilter = null
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
    val filtered = ui.history.filter { s ->
        val matchesSearch = query.isBlank() ||
            (s.profileName?.contains(query, ignoreCase = true) == true) ||
            (s.beanName?.contains(query, ignoreCase = true) == true) ||
            (s.notes?.contains(query, ignoreCase = true) == true)
        val matchesRange = when (range) {
            "today" -> s.completedAtMs >= startOfDay
            "7d" -> s.completedAtMs >= now - 7L * dayMs
            "30d" -> s.completedAtMs >= now - 30L * dayMs
            else -> true
        }
        val matchesProfile = profileFilter == null || s.profileName == profileFilter
        matchesSearch && matchesRange && matchesProfile
    }.sortedByDescending { it.completedAtMs }

    val detail = detailId?.let { id -> ui.history.firstOrNull { it.id == id } }

    if (detail != null) {
        BackHandler { detailId = null }
        PhoneShotDetail(
            vm = vm,
            shot = detail,
            signedIn = ui.visualizer.signedIn,
            syncing = detail.id in ui.visualizer.uploadingShotIds,
            defaultPrivacy = ui.visualizer.privacy,
            onBack = { detailId = null },
            onDeleted = { detailId = null },
            onLoadOnBrew = { vm.loadProfileOnBrew(detail.profileName); onNav("brew") },
            onExport = { launchSave("crema-shot.json", vm.shotsJson(listOf(detail.id))) },
        )
        return
    }

    Scaffold(
        topBar = {
            CremaPhoneTopBar(
                title = "History",
                actions = listOf(
                    BarAction("magnifying-glass") { searchOpen = !searchOpen },
                    BarAction("export") { exportSheet = true },
                    BarAction("gear-six") { onNav("settings") },
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            if (ui.history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "No shots yet — pull one on Brew.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CremaButton(
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) },
                            variant = CremaButtonVariant.Outlined,
                            icon = "upload-simple",
                            label = "Import shots",
                        )
                    }
                }
                return@Column
            }

            // Stats strip — Today / Avg ratio / Avg rating over the FULL history.
            PhoneStatsStrip(ui.history, startOfDay)

            // Filter chips: per-profile pills, then time ranges.
            val byProfile = ui.history.mapNotNull { it.profileName }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            CremaFilterChipRow(
                chips = buildList {
                    add(FilterChipSpec("all", "All", ui.history.size))
                    byProfile.take(6).forEach { (name, count) -> add(FilterChipSpec("p:$name", name, count)) }
                    add(FilterChipSpec("r:today", "Today", ui.history.count { it.completedAtMs >= startOfDay }))
                    add(FilterChipSpec("r:7d", "7 days", ui.history.count { it.completedAtMs >= now - 7L * dayMs }))
                    add(FilterChipSpec("r:30d", "30 days", ui.history.count { it.completedAtMs >= now - 30L * dayMs }))
                },
                selected = profileFilter?.let { "p:$it" } ?: if (range != "all") "r:$range" else "all",
                onSelect = { id ->
                    when {
                        id == "all" -> { profileFilter = null; range = "all" }
                        id.startsWith("p:") -> { profileFilter = id.removePrefix("p:"); }
                        id.startsWith("r:") -> { profileFilter = null; range = id.removePrefix("r:") }
                    }
                },
            )

            AnimatedVisibility(visible = searchOpen) {
                Box(Modifier.padding(horizontal = CremaEdge, vertical = 6.dp)) {
                    CremaPhoneSearch(query = query, onQueryChange = { query = it }, placeholder = "Search profile, bean, notes")
                }
            }

            // Day-grouped shot list.
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = CremaEdge, end = CremaEdge, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                var lastDay: String? = null
                filtered.forEach { shot ->
                    val day = dayLabel(shot.completedAtMs, startOfDay, dayMs)
                    if (day != lastDay) {
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
                            syncing = shot.id in ui.visualizer.uploadingShotIds,
                            onOpen = { detailId = shot.id },
                        )
                    }
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No shots match your filters.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
            }
        }
    }

    if (exportSheet) {
        CremaOverflowSheet(
            title = "Shots",
            items = buildList {
                add(SheetItem("upload-simple", "Import shots", sub = "Crema JSON from another device") {
                    importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                })
                add(SheetItem(divider = true))
                add(SheetItem("file-text", "Export all shots", sub = "Every shot as one Crema JSON file") {
                    launchSave("crema-history.json", vm.shotsJson(null))
                })
                add(SheetItem("file-code", "Export current filter", sub = "Only the ${filtered.size} shot(s) matching your filters") {
                    launchSave("crema-history-filtered.json", vm.shotsJson(filtered.map { it.id }))
                })
            },
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
private fun PhoneStatsStrip(history: List<StoredShot>, startOfDay: Long) {
    val today = history.count { it.completedAtMs >= startOfDay }
    val ratios = history.mapNotNull { s ->
        val y = s.yieldG; val d = s.doseG
        if (y != null && d != null && d > 0f) y / d else null
    }
    val avgRatio = if (ratios.isNotEmpty()) ratios.average() else null
    val ratings = history.mapNotNull { it.rating?.takeIf { r -> r > 0 } }
    val avgRating = if (ratings.isNotEmpty()) ratings.average() else null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = CremaEdge, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PhoneStatTile("Today", "$today", Modifier.weight(1f))
        PhoneStatTile("Avg ratio", avgRatio?.let { "1:%.1f".format(it) } ?: "—", Modifier.weight(1f))
        PhoneStatTile("Avg rating", avgRating?.let { "%.1f★".format(it) } ?: "—", Modifier.weight(1f))
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
private fun PhoneShotRow(shot: StoredShot, syncing: Boolean, onOpen: () -> Unit) {
    val tel = CremaTheme.telemetry
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpen)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PhoneSpark(
            samples = shot.samples,
            modifier = Modifier
                .width(58.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    shot.profileName ?: "Shot",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    remember(shot.completedAtMs) {
                        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                            .format(java.util.Date(shot.completedAtMs))
                    },
                    style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                shot.beanName ?: "—",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val r = shot.rating ?: 0
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    (1..5).forEach { n ->
                        PhIcon(
                            if (n <= r) "star-fill" else "star",
                            sizeDp = 11,
                            tint = if (n <= r) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                        )
                    }
                }
                RowMono(shotRatioLabel(shot) ?: "—")
                RowMono(shot.yieldG?.let { "%.1fg".format(it) } ?: "—")
                RowMono("%.0fs".format(shot.durationMs / 1000.0))
            }
        }
        when {
            syncing -> CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.primary)
            shot.visualizerId != null -> PhIcon("cloud-check", sizeDp = 16, tint = tel.success)
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

// Tiny static pressure silhouette for a row (proto ShotSpark).
@Composable
private fun PhoneSpark(samples: List<TelemetrySample>, modifier: Modifier = Modifier) {
    val tel = CremaTheme.telemetry
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val firstT = samples.first().elapsedMs.toFloat()
        val span = (samples.last().elapsedMs.toFloat() - firstT).takeIf { it > 0f } ?: 1f
        val inset = 3.dp.toPx()
        val plotW = (size.width - inset * 2f).coerceAtLeast(1f)
        val plotH = (size.height - inset * 2f).coerceAtLeast(1f)
        var mn = Float.POSITIVE_INFINITY
        var mx = Float.NEGATIVE_INFINITY
        samples.forEach { s -> s.pressure?.let { if (it < mn) mn = it; if (it > mx) mx = it } }
        val vSpan = (mx - mn).takeIf { it > 0f } ?: return@Canvas
        val path = Path()
        var started = false
        samples.forEach { s ->
            val v = s.pressure ?: return@forEach
            val x = inset + ((s.elapsedMs.toFloat() - firstT) / span) * plotW
            val y = inset + (1f - (v - mn) / vSpan) * plotH
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        drawPath(path, color = tel.pressure, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

// ── Pushed detail (proto HistoryDetail) ──────────────────────────────────────
@Composable
private fun PhoneShotDetail(
    vm: MainViewModel,
    shot: StoredShot,
    signedIn: Boolean,
    syncing: Boolean,
    defaultPrivacy: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onLoadOnBrew: () -> Unit,
    onExport: () -> Unit,
) {
    val tel = CremaTheme.telemetry
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var rating by remember(shot.id) { mutableStateOf(shot.rating ?: 0) }
    var notes by remember(shot.id) { mutableStateOf(shot.notes ?: "") }
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            CremaPhoneBackBar(
                title = shot.profileName ?: "Shot",
                subtitle = buildList {
                    shot.beanName?.let { add(it) }
                    add(
                        remember(shot.completedAtMs) {
                            java.text.SimpleDateFormat("MMM d · HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(shot.completedAtMs))
                        },
                    )
                }.joinToString(" · "),
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
                FilledTonalButton(
                    onClick = onLoadOnBrew,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.weight(1f).height(48.dp),
                ) { PhIcon("arrow-line-down", sizeDp = 18); Spacer(Modifier.width(8.dp)); Text("Load on Brew") }
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
            // Chart card + legend.
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
                        CanvasShotChart(
                            samples = shot.samples,
                            enabledChannels = setOf("pressure", "flow", "headTemp", "weight"),
                            live = false,
                            modifier = Modifier.fillMaxWidth().height(190.dp),
                        )
                    }
                }
            }

            // 4-up metric strip (proto .ph-dmetrics).
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailMetric("%.0fs".format(shot.durationMs / 1000.0), "Time", null, Modifier.weight(1f))
                DetailMetric(shot.peakPressure?.let { "%.1f".format(it) } ?: "—", "Peak bar", tel.pressure, Modifier.weight(1f))
                DetailMetric(shot.peakTemp?.let { "%.1f°".format(it) } ?: "—", "Peak temp", tel.temp, Modifier.weight(1f))
                DetailMetric(shot.yieldG?.let { "%.1fg".format(it) } ?: "—", "Yield", tel.weight, Modifier.weight(1f))
            }

            // Rating + privacy + notes.
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Eyebrow("Your rating")
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            (1..5).forEach { n ->
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape).clickable {
                                        rating = if (n == rating) 0 else n
                                        vm.updateShot(shot.id, rating, notes)
                                    },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    PhIcon(
                                        if (n <= rating) "star-fill" else "star",
                                        sizeDp = 22,
                                        tint = if (n <= rating) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
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
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Eyebrow("Privacy")
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PrivacyPill("Default · $defaultPrivacy", shot.privacy == null) { vm.setShotPrivacy(shot.id, null) }
                            listOf("public" to "Public", "unlisted" to "Unlisted", "private" to "Private").forEach { (v, label) ->
                                PrivacyPill(label, shot.privacy == v) { vm.setShotPrivacy(shot.id, v) }
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
            title = (shot.profileName ?: "Shot") + " · " + remember(shot.completedAtMs) {
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(shot.completedAtMs))
            },
            items = buildList {
                add(SheetItem("download-simple", "Download shot", sub = "Crema JSON") { onExport() })
                if (signedIn && shot.visualizerId == null && !syncing) {
                    add(SheetItem("cloud-arrow-up", "Upload to Visualizer") { vm.visualizer.uploadShot(shot) })
                }
                if (shot.visualizerId != null) {
                    add(SheetItem("arrow-square-out", "View on Visualizer") {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse("https://visualizer.coffee/shots/${shot.visualizerId}"),
                            ),
                        )
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
        )
    }
}

private fun shotRatioLabel(shot: StoredShot): String? {
    val y = shot.yieldG
    val d = shot.doseG
    return if (y != null && d != null && d > 0f) "1:%.1f".format(y / d) else null
}

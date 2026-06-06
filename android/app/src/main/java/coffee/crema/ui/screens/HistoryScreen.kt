package coffee.crema.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import coffee.crema.history.StoredShot
import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.components.CremaCard
import coffee.crema.ui.components.CremaValueUnit
import coffee.crema.ui.theme.HankenGrotesk
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import coffee.crema.ui.components.CremaFilterChip
import coffee.crema.ui.components.CremaSortControl
import coffee.crema.ui.components.SortKey
import coffee.crema.ui.components.CremaNavigationRail
import coffee.crema.ui.components.Eyebrow
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.components.CremaButton
import coffee.crema.ui.components.CremaButtonVariant
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
    var sort by remember { mutableStateOf("date") }
    var sortDesc by remember { mutableStateOf(true) } // newest / highest first

    // Client-side search + time-range filter + sort over the shot log. The stat
    // strip stays over the FULL history (global metrics); only the list filters.
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
            (s.beanName?.contains(query, ignoreCase = true) == true) ||
            (s.notes?.contains(query, ignoreCase = true) == true)
        val matchesRange = when (range) {
            "today" -> s.completedAtMs >= startOfDay
            "7d" -> s.completedAtMs >= now - 7L * dayMs
            "30d" -> s.completedAtMs >= now - 30L * dayMs
            else -> true
        }
        matchesSearch && matchesRange
    }
    val sortedAsc = when (sort) {
        "rating" -> filtered.sortedBy { it.rating ?: 0 }
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
                    Box(
                        Modifier.width(260.dp).height(40.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PhIcon("magnifying-glass", sizeDp = 18, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text("Search profile, bean, notes…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
            }
            if (ui.history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No shots yet — pull one on Brew.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                StatsStrip(ui.history)
                // Range filter chips (left) + sort split-button (right).
                Row(
                    Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("all" to "All time", "30d" to "30 days", "7d" to "7 days", "today" to "Today").forEach { (id, label) ->
                            val count = when (id) {
                                "today" -> ui.history.count { it.completedAtMs >= startOfDay }
                                "7d" -> ui.history.count { it.completedAtMs >= now - 7L * dayMs }
                                "30d" -> ui.history.count { it.completedAtMs >= now - 30L * dayMs }
                                else -> ui.history.size
                            }
                            CremaFilterChip(label = label, selected = range == id, count = count, onClick = { range = id })
                        }
                    }
                    CremaSortControl(
                        keys = listOf(
                            SortKey("date", "Date"),
                            SortKey("rating", "Rating"),
                            SortKey("yield", "Yield"),
                            SortKey("time", "Time"),
                        ),
                        selectedKey = sort,
                        descending = sortDesc,
                        onKeyChange = { sort = it },
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
                                onClick = { selectedId = shot.id },
                            )
                        }
                    }
                    if (selected != null) {
                        ShotDetail(
                            shot = selected,
                            channels = ui.chartChannels,
                            onRate = { r, n -> vm.updateShot(selected.id, r, n) },
                            onLoadOnBrew = { vm.loadProfileOnBrew(selected.profileName) },
                            onExport = { vm.exportShot(selected.id) },
                            onDelete = { vm.deleteShot(selected.id); selectedId = null },
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
private fun StatsStrip(history: List<StoredShot>) {
    val now = System.currentTimeMillis()
    val dayMs = 24L * 60L * 60L * 1000L
    // Local midnight today: snap `now` to the device's day boundary so the
    // "Today" bucket follows the wall clock rather than a rolling 24h window.
    val startOfDay = run {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }
    val startOfWeek = now - 7L * dayMs

    val today = history.count { it.completedAtMs >= startOfDay }
    val thisWeek = history.count { it.completedAtMs >= startOfWeek }
    val total = history.size
    val yields = history.mapNotNull { it.yieldG }
    val avgYield = if (yields.isNotEmpty()) yields.average() else null
    val avgTime = if (history.isNotEmpty()) history.map { it.durationMs / 1000.0 }.average() else null
    val ratings = history.mapNotNull { it.rating?.takeIf { r -> r > 0 } }
    val avgRating = if (ratings.isNotEmpty()) ratings.average() else null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatTile("Today", "$today", "shots", Modifier.weight(1f))
        StatTile("This week", "$thisWeek", "shots", Modifier.weight(1f))
        StatTile("Total", "$total", "shots", Modifier.weight(1f))
        StatTile("Avg yield", avgYield?.let { "%.1f".format(it) } ?: "—", avgYield?.let { "g" }, Modifier.weight(1f))
        StatTile("Avg time", avgTime?.let { "%.0f".format(it) } ?: "—", avgTime?.let { "s" }, Modifier.weight(1f))
        StatTile("Avg rating", avgRating?.let { "%.1f".format(it) } ?: "—", null, Modifier.weight(1f))
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String?, modifier: Modifier = Modifier) {
    CremaCard(modifier, shape = RoundedCornerShape(16.dp)) {
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
private fun ShotRow(shot: StoredShot, selected: Boolean, onClick: () -> Unit) {
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
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(shot.profileName, shot.beanName).joinToString(" · ").ifEmpty { "Shot" },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                DateUtils.getRelativeTimeSpanString(shot.completedAtMs).toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SparkChart(
            samples = shot.samples,
            modifier = Modifier
                .width(72.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        )
        Text(
            shotRatio(shot) ?: "—",
            style = CremaTheme.readout.readoutSm.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            shot.yieldG?.let { "%.1f g".format(it) } ?: "—",
            style = CremaTheme.readout.readoutSm.copy(fontSize = 13.sp, lineHeight = 17.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Inline rating (proto): compact stars, faint when unrated.
        val r = shot.rating ?: 0
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            (1..5).forEach { n ->
                PhIcon(
                    if (n <= r) "star-fill" else "star",
                    sizeDp = 11,
                    tint = if (n <= r) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                )
            }
        }
    }
}

/*
 * SparkChart — a tiny static silhouette of a shot's PRESSURE curve for the list
 * rows. Single-channel mini-Canvas (proto `.hist2-row-spark`): pressure stroke
 * in `telemetry.pressure`, round-cap ~1.8dp, no axes/grid. Unlike CanvasShotChart
 * it stretches both axes to fill the 72×32 box (preserveAspectRatio: none) — x is
 * normalized over the sample span, y over the pressure min..max. Empty or single-
 * sample series draw nothing (the caller still sizes the box, so row heights stay
 * uniform).
 */
@Composable
private fun SparkChart(samples: List<TelemetrySample>, modifier: Modifier = Modifier) {
    val stroke = CremaTheme.telemetry.pressure
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val firstT = samples.first().elapsedMs.toFloat()
        val lastT = samples.last().elapsedMs.toFloat()
        val tSpan = (lastT - firstT).takeIf { it > 0f } ?: 1f
        var minP = Float.POSITIVE_INFINITY
        var maxP = Float.NEGATIVE_INFINITY
        samples.forEach { s ->
            if (s.pressure < minP) minP = s.pressure
            if (s.pressure > maxP) maxP = s.pressure
        }
        val pSpan = (maxP - minP).takeIf { it > 0f } ?: 1f
        val inset = 2.dp.toPx() // keep the round caps off the rounded corners
        val plotW = (w - inset * 2f).coerceAtLeast(1f)
        val plotH = (h - inset * 2f).coerceAtLeast(1f)
        val path = Path()
        samples.forEachIndexed { i, s ->
            val x = inset + ((s.elapsedMs.toFloat() - firstT) / tSpan) * plotW
            val y = inset + (1f - (s.pressure - minP) / pSpan) * plotH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            color = stroke,
            style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

@Composable
private fun ShotDetail(
    shot: StoredShot,
    channels: Set<String>,
    modifier: Modifier,
    onRate: (Int, String) -> Unit,
    onLoadOnBrew: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        var confirmDelete by remember(shot.id) { mutableStateOf(false) }
        // Detail head — title block (left) + actions (right), bottom-aligned with a
        // hairline rule (PWA .hi-detail-head: space-between, align-items: flex-end).
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Eyebrow(DateUtils.getRelativeTimeSpanString(shot.completedAtMs).toString())
                Text(
                    shot.profileName ?: "Shot",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildList {
                    shot.beanName?.let { add(it) }
                    val d = shot.doseG; val y = shot.yieldG
                    if (d != null && y != null) add("%.1f g → %.1f g".format(d, y))
                    shotRatio(shot)?.let { add(it) }
                }.joinToString(" · ")
                if (meta.isNotBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CremaButton(onClick = onLoadOnBrew, variant = CremaButtonVariant.Outlined, icon = "coffee", label = "Load on Brew")
                CremaButton(onClick = onExport, variant = CremaButtonVariant.Outlined, icon = "download-simple", label = "Export")
                CremaOverflowMenu(items = listOf(
                    OverflowItem("trash", "Delete shot", { confirmDelete = true }, danger = true),
                ))
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
            MetricCard("Time", "%.0f".format(shot.durationMs / 1000.0), "s", Modifier.weight(1f))
            MetricCard("Peak pressure", shot.peakPressure?.let { "%.1f".format(it) } ?: "—", shot.peakPressure?.let { "bar" }, Modifier.weight(1f))
            MetricCard("Peak temp", shot.peakTemp?.let { "%.0f".format(it) } ?: "—", shot.peakTemp?.let { "°C" }, Modifier.weight(1f))
            MetricCard("Peak wt", peakWt?.let { "%.1f".format(it) } ?: "—", peakWt?.let { "g" }, Modifier.weight(1f))
            MetricCard("Yield", shot.yieldG?.let { "%.1f".format(it) } ?: "—", shot.yieldG?.let { "g" }, Modifier.weight(1f))
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
            StarRating(rating) { rating = it; onRate(it, notes) }
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it; onRate(rating, it) },
            label = { Text("Tasting notes") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun StarRating(value: Int, onChange: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        (1..5).forEach { n ->
            IconButton(onClick = { onChange(if (n == value) 0 else n) }) {
                PhIcon(if (n <= value) "star-fill" else "star", sizeDp = 22, tint = if (n <= value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
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
    return if (y != null && d != null && d > 0f) "1:%.2f".format(y / d) else null
}

private fun shotMetrics(shot: StoredShot): String = listOfNotNull(
    shot.yieldG?.let { "%.1f g".format(it) },
    shotRatio(shot),
    "%.1f s".format(shot.durationMs / 1000.0),
).joinToString(" · ")

package coffee.crema.ui.brewlog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.brew.methodIcon
import coffee.crema.core.BrewSeries
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.theme.CremaTheme
import kotlin.math.max

/*
 * Brew Log UI primitives (issue #10) — the method mark tile that stands in
 * for a telemetry-less row's sparkline, the guided-brew weight sparkline,
 * and the detail-pane weight chart with stage boundaries. Twins of the web
 * MethodMark / MiniBrewChart / BrewSessionChart.
 */

/** The 32dp sunken method tile — the row's sparkline-slot stand-in. */
@Composable
fun MethodMarkTile(method: String?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(width = 72.dp, height = 32.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerLowest,
                RoundedCornerShape(6.dp),
            )
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        PhIcon(methodIcon(method), sizeDp = 16, tint = MaterialTheme.colorScheme.primary)
    }
}

/** Tiny guided-brew weight sparkline with hairline stage ticks. */
@Composable
fun BrewSparkChart(series: BrewSeries, modifier: Modifier = Modifier) {
    val weightColor = CremaTheme.telemetry.weight
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Canvas(modifier) {
        val samples = series.samples
        if (samples.size < 2) return@Canvas
        val span = max(1L, samples.last().elapsedMs).toFloat()
        val maxW = max(50f, samples.maxOf { it.weightG })
        val inset = 2.dp.toPx()
        val plotW = (size.width - inset * 2f).coerceAtLeast(1f)
        val plotH = (size.height - inset * 2f).coerceAtLeast(1f)
        series.stageMarks.filter { it.elapsedMs > 0 }.forEach { m ->
            val x = inset + (m.elapsedMs / span) * plotW
            drawLine(tickColor, start = androidx.compose.ui.geometry.Offset(x, inset), end = androidx.compose.ui.geometry.Offset(x, inset + plotH), strokeWidth = 1f)
        }
        val path = Path()
        samples.forEachIndexed { i, s ->
            val x = inset + (s.elapsedMs / span) * plotW
            val y = inset + (1f - (s.weightG / maxW).coerceIn(0f, 1f)) * plotH
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, weightColor, style = Stroke(width = 1.5.dp.toPx()))
    }
}

/**
 * The guided-brew detail chart: the weight curve (hero) + derived pour rate,
 * alternating stage bands at the recorded boundaries. The brew sibling of
 * `CanvasShotChart` for rows whose telemetry is a `BrewSeries`.
 *
 * The live session reuses it (issue #10 Phase 2): [minSpanMs] grows the
 * x-axis with the session clock (so bands draw before any weight arrives —
 * a scale-less session still shows its stages), [nowMs] draws the playhead,
 * and [targetG] the current step's cumulative pour target, dashed (only
 * when the marks carry no snapshotted targets — the staircase covers it).
 *
 * "Planned vs poured": when the stage marks carry the recipe's snapshotted
 * water targets, a dashed staircase ([plannedStaircase]) sits under the
 * solid weight curve, with a small in-chart legend unless [showLegend] is
 * false (a caller with its own legend row labels the dashed line there).
 */
@Composable
fun BrewSessionCanvas(
    series: BrewSeries,
    modifier: Modifier = Modifier,
    minSpanMs: Long? = null,
    nowMs: Long? = null,
    targetG: Float? = null,
    showLegend: Boolean = true,
) {
    val hasPlan = series.stageMarks.any { it.targetWaterG != null }
    Box(modifier) {
        BrewSessionPlot(series, Modifier.fillMaxSize(), minSpanMs, nowMs, targetG)
        if (hasPlan && showLegend) {
            Row(
                Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PlannedLegend()
                LegendEntry("poured", dashed = false, color = CremaTheme.telemetry.weight)
            }
        }
    }
}

/** The "planned" legend entry: a short dashed swatch in the staircase colour. */
@Composable
private fun PlannedLegend(label: String = "planned") {
    LegendEntry(label, dashed = true, color = plannedColor())
}

@Composable
private fun plannedColor() = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)

@Composable
private fun LegendEntry(label: String, dashed: Boolean, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(width = 16.dp, height = 6.dp)) {
            val y = size.height / 2f
            drawLine(
                color,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (dashed) 1.4.dp.toPx() else 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BrewSessionPlot(
    series: BrewSeries,
    modifier: Modifier,
    minSpanMs: Long?,
    nowMs: Long?,
    targetG: Float?,
) {
    val weightColor = CremaTheme.telemetry.weight
    val flowColor = CremaTheme.telemetry.flow
    val planColor = plannedColor()
    val bandColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val boundColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val cursorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
    Canvas(modifier) {
        val samples = series.samples
        val live = minSpanMs != null
        if (samples.size < 2 && !live) return@Canvas
        val span = max(1L, max(samples.lastOrNull()?.elapsedMs ?: 0L, minSpanMs ?: 0L)).toFloat()
        // Where the series ends: the live clock, else the last sample.
        val endMs = nowMs ?: samples.lastOrNull()?.elapsedMs ?: 0L
        val stairs = plannedStaircase(series.stageMarks, endMs)
        // Keep the planned line in view (with headroom) even on a short pour.
        val planMax = maxPlannedTarget(series.stageMarks) * 1.04f
        val maxW = max(50f, maxOf(samples.maxOfOrNull { it.weightG } ?: 0f, (targetG ?: 0f) * 1.1f, planMax))
        val maxF = max(4f, samples.mapNotNull { it.flowGS }.maxOrNull() ?: 0f)
        val inset = 8.dp.toPx()
        val plotW = (size.width - inset * 2f).coerceAtLeast(1f)
        val plotH = (size.height - inset * 2f).coerceAtLeast(1f)
        fun x(t: Long) = inset + (t / span).coerceIn(0f, 1f) * plotW
        fun yW(w: Float) = inset + (1f - (w / maxW).coerceIn(0f, 1f)) * plotH
        // Alternating stage bands + dashed boundaries.
        val marks = series.stageMarks.sortedBy { it.elapsedMs }
        marks.forEachIndexed { i, m ->
            val from = x(m.elapsedMs)
            val to = if (i + 1 < marks.size) x(marks[i + 1].elapsedMs) else if (nowMs != null) x(nowMs) else inset + plotW
            if (i % 2 == 1) {
                drawRect(
                    bandColor,
                    topLeft = androidx.compose.ui.geometry.Offset(from, inset),
                    size = androidx.compose.ui.geometry.Size((to - from).coerceAtLeast(0f), plotH),
                )
            }
            if (m.elapsedMs > 0) {
                drawLine(
                    boundColor,
                    start = androidx.compose.ui.geometry.Offset(from, inset),
                    end = androidx.compose.ui.geometry.Offset(from, inset + plotH),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                )
            }
        }
        // Horizontal weight gridlines at 50% and 100%.
        listOf(0.5f, 1f).forEach { frac ->
            val y = inset + (1f - frac) * plotH
            drawLine(gridColor, androidx.compose.ui.geometry.Offset(inset, y), androidx.compose.ui.geometry.Offset(inset + plotW, y), 1f)
        }
        // Planned water: the dashed staircase, under the weight curve.
        if (stairs.isNotEmpty()) {
            val path = Path()
            stairs.forEachIndexed { i, s ->
                val y = yW(s.targetG)
                if (i > 0 && stairs[i - 1].t1Ms == s.t0Ms) path.lineTo(x(s.t0Ms), y) else path.moveTo(x(s.t0Ms), y)
                path.lineTo(x(s.t1Ms), y)
            }
            drawPath(
                path,
                planColor,
                style = Stroke(
                    width = 1.4.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                ),
            )
        }
        // The current step's pour target (live, pre-snapshot fallback).
        if (stairs.isEmpty() && targetG != null && targetG > 0f) {
            val y = yW(targetG)
            drawLine(
                weightColor.copy(alpha = 0.45f),
                androidx.compose.ui.geometry.Offset(inset, y),
                androidx.compose.ui.geometry.Offset(inset + plotW, y),
                strokeWidth = 1.2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )
        }
        if (nowMs != null) {
            val px = x(nowMs)
            drawLine(cursorColor, androidx.compose.ui.geometry.Offset(px, inset), androidx.compose.ui.geometry.Offset(px, inset + plotH), 1.5f)
        }
        if (samples.size < 2) return@Canvas
        // Pour rate (secondary) beneath the weight curve.
        run {
            val path = Path()
            var started = false
            samples.forEach { s ->
                val f = s.flowGS ?: return@forEach
                val px = x(s.elapsedMs)
                val py = inset + (1f - (f / maxF).coerceIn(0f, 1f)) * plotH
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            }
            if (started) drawPath(path, flowColor.copy(alpha = 0.75f), style = Stroke(width = 1.2.dp.toPx()))
        }
        run {
            val path = Path()
            samples.forEachIndexed { i, s ->
                val px = x(s.elapsedMs)
                val py = yW(s.weightG)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, weightColor, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

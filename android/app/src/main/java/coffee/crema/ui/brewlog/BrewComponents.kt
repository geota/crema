package coffee.crema.ui.brewlog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
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
 */
@Composable
fun BrewSessionCanvas(series: BrewSeries, modifier: Modifier = Modifier) {
    val weightColor = CremaTheme.telemetry.weight
    val flowColor = CremaTheme.telemetry.flow
    val bandColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)
    val boundColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    Canvas(modifier) {
        val samples = series.samples
        if (samples.size < 2) return@Canvas
        val span = max(1L, samples.last().elapsedMs).toFloat()
        val maxW = max(50f, samples.maxOf { it.weightG })
        val maxF = max(4f, samples.mapNotNull { it.flowGS }.maxOrNull() ?: 0f)
        val inset = 8.dp.toPx()
        val plotW = (size.width - inset * 2f).coerceAtLeast(1f)
        val plotH = (size.height - inset * 2f).coerceAtLeast(1f)
        fun x(t: Long) = inset + (t / span) * plotW
        // Alternating stage bands + dashed boundaries.
        val marks = series.stageMarks.sortedBy { it.elapsedMs }
        marks.forEachIndexed { i, m ->
            val from = x(m.elapsedMs)
            val to = if (i + 1 < marks.size) x(marks[i + 1].elapsedMs) else inset + plotW
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
                val py = inset + (1f - (s.weightG / maxW).coerceIn(0f, 1f)) * plotH
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, weightColor, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

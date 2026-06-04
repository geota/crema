package coffee.crema.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.profiles.ProfileSegment
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import kotlin.math.max
import kotlin.math.min

/*
 * CanvasProfilePreview — the profile-card mini-chart, a Canvas port of the web
 * `components/profiles/ProfilePreview.svelte` (uPlot). Pressure curve (hero, soft
 * gradient fill), a damped "estimated flow" ghost (dashed, light fill), and a
 * stepped temperature line on a right °C axis — all computed from the profile's
 * segments via [sampleCurve] (the same sampler the web editor + LiveChart goal
 * line use). Replaces the old stepped `CurvePreview`.
 *
 * Two y-scales: pressure/flow on 0–12 bar, temperature on its own 80–105 °C scale
 * (right axis). `compact` strips all chrome to a bare pressure+flow silhouette
 * (Quick Controls favorites strip).
 */
private const val SMOOTH_STEPS = 24
private const val STEP_EPS = 1e-3f
private const val Y_MAX = 12f
private const val TEMP_MIN = 80f
private const val TEMP_MAX = 105f

/**
 * Densely sample a segment list into (time, value) arrays — a faithful port of the
 * web `curve.ts sampleCurve`. A `"fast"` ramp emits a near-vertical step; any other
 * ramp eases with a cubic whose control points sit at 40 % / 60 % of the span
 * (the same shape the SVG `curvePath` drew). `damp` rescales every target first
 * (the flow ghost passes `target → min(4, target·0.35 + 0.5)`).
 */
private fun sampleCurve(
    segments: List<ProfileSegment>,
    damp: ((Float) -> Float)? = null,
): Pair<FloatArray, FloatArray> {
    if (segments.isEmpty()) return FloatArray(0) to FloatArray(0)
    val time = ArrayList<Float>()
    val value = ArrayList<Float>()
    time.add(0f); value.add(0f)
    var t = 0f
    var prev = 0f
    for (s in segments) {
        val target = damp?.invoke(s.target) ?: s.target
        if (s.ramp == "fast") {
            time.add(t + STEP_EPS); value.add(target)
            time.add(t + s.time); value.add(target)
        } else {
            val dt = s.time
            val c0x = t + dt * 0.4f
            val c1x = t + dt * 0.6f
            for (i in 1..SMOOTH_STEPS) {
                val u = i.toFloat() / SMOOTH_STEPS
                val m = 1f - u
                val b0 = m * m * m
                val b1 = 3f * m * m * u
                val b2 = 3f * m * u * u
                val b3 = u * u * u
                time.add(b0 * t + b1 * c0x + b2 * c1x + b3 * (t + dt))
                value.add((b0 + b1) * prev + (b2 + b3) * target)
            }
        }
        t += s.time
        prev = target
    }
    return time.toFloatArray() to value.toFloatArray()
}

/** Per-segment (cumulative end time, temperature °C) for the stepped temp line. */
private fun tempSteps(segments: List<ProfileSegment>): List<Pair<Float, Float>> {
    val out = ArrayList<Pair<Float, Float>>(segments.size)
    var t = 0f
    for (s in segments) {
        t += s.time
        out.add(t to (s.temp ?: 93f))
    }
    return out
}

@Composable
fun CanvasProfilePreview(
    segments: List<ProfileSegment>,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val tel = CremaTheme.telemetry
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val pressure = remember(segments) { sampleCurve(segments) }
    val flow = remember(segments) { sampleCurve(segments) { t -> min(4f, t * 0.35f + 0.5f) } }
    val temps = remember(segments) { tempSteps(segments) }
    val total = remember(segments) { max(1f, segments.sumOf { it.time.toDouble() }.toFloat()) }
    val firstTemp = segments.firstOrNull()?.temp ?: 93f

    Canvas(modifier) {
        val padL = if (compact) 0f else 22.dp.toPx()
        val padR = if (compact) 0f else 22.dp.toPx()
        val padT = if (compact) 1.dp.toPx() else 4.dp.toPx()
        val padB = if (compact) 1.dp.toPx() else 12.dp.toPx()
        val plotL = padL
        val plotR = size.width - padR
        val plotT = padT
        val plotB = size.height - padB
        val plotW = (plotR - plotL).coerceAtLeast(1f)
        val plotH = (plotB - plotT).coerceAtLeast(1f)

        fun xPx(t: Float) = plotL + (t / total) * plotW
        fun yBar(v: Float) = plotB - (v.coerceIn(0f, Y_MAX) / Y_MAX) * plotH
        fun yTemp(c: Float) = plotB - ((c.coerceIn(TEMP_MIN, TEMP_MAX) - TEMP_MIN) / (TEMP_MAX - TEMP_MIN)) * plotH

        // Minimal card sparkline chrome: one faint mid gridline + a dashed
        // reference at 9 bar. The web ProfilePreview strips numeric axes on the
        // card (legend lives as an overlay instead) — full axes are the editor's.
        if (!compact) {
            drawLine(gridColor.copy(alpha = 0.5f), Offset(plotL, yBar(6f)), Offset(plotR, yBar(6f)), strokeWidth = 1f)
            drawLine(
                gridColor, Offset(plotL, yBar(9f)), Offset(plotR, yBar(9f)), strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx())),
            )
        }

        // Flow ghost (dashed, light fill) — drawn first so pressure sits on top.
        drawCurve(flow.first, flow.second, { xPx(it) }, { yBar(it) }, tel.flow.copy(alpha = 0.85f), 0.22f, true, if (compact) 1.2f else 1.4f, plotT, plotB)
        // Pressure (hero, gradient fill).
        drawCurve(pressure.first, pressure.second, { xPx(it) }, { yBar(it) }, tel.pressure, 0.30f, false, if (compact) 1.6f else 1.8f, plotT, plotB)

        // Temperature on the right °C scale (full mode only) — light + dashed.
        if (!compact && temps.isNotEmpty()) {
            val path = Path()
            path.moveTo(xPx(0f), yTemp(temps[0].second))
            path.lineTo(xPx(temps[0].first), yTemp(temps[0].second))
            for (i in 1 until temps.size) {
                val prevEnd = temps[i - 1].first
                val (cumEnd, temp) = temps[i]
                path.lineTo(xPx(prevEnd), yTemp(temp)) // vertical jump at the boundary
                path.lineTo(xPx(cumEnd), yTemp(temp))  // hold across the segment
            }
            drawPath(
                path, color = tel.temp.copy(alpha = 0.7f),
                style = Stroke(
                    width = 1.2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 2.dp.toPx())),
                ),
            )
        }
    }
}

/** One channel: a stroked line + an optional soft top-down gradient fill to baseline. */
private fun DrawScope.drawCurve(
    times: FloatArray,
    values: FloatArray,
    xPx: (Float) -> Float,
    yPx: (Float) -> Float,
    color: Color,
    fillAlpha: Float,
    dashed: Boolean,
    strokeDp: Float,
    plotT: Float,
    plotB: Float,
) {
    if (times.size < 2) return
    val line = Path()
    line.moveTo(xPx(times[0]), yPx(values[0]))
    for (i in 1 until times.size) line.lineTo(xPx(times[i]), yPx(values[i]))
    if (fillAlpha > 0f) {
        val fill = Path()
        fill.moveTo(xPx(times[0]), plotB)
        for (i in times.indices) fill.lineTo(xPx(times[i]), yPx(values[i]))
        fill.lineTo(xPx(times[times.size - 1]), plotB)
        fill.close()
        drawPath(
            fill,
            brush = Brush.verticalGradient(
                listOf(color.copy(alpha = fillAlpha), Color.Transparent),
                startY = plotT,
                endY = plotB,
            ),
        )
    }
    drawPath(
        line,
        color = color,
        style = Stroke(
            width = strokeDp.dp.toPx(),
            cap = StrokeCap.Round,
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null,
        ),
    )
}

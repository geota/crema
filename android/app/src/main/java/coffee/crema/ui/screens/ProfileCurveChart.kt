package coffee.crema.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import kotlin.math.max
import kotlin.math.roundToInt

/*
 * ProfileCurveChart — the recipe curve in the Profile editor, a hand-rolled
 * Canvas port of the web's uPlot drag editor (ProfileCurveEditor.svelte). The
 * curve AND the draggable handles are drawn in ONE Canvas pass over ONE
 * value→pixel transform, so a handle can never detach from the line — the
 * failure mode of the previous Vico overlay (whose handles read a 1-frame-lagged
 * copy of Vico's plot geometry). Removing the chart-library host from the editor
 * also fixes the editor's broken vertical scroll.
 *
 * Design (from the web editor): y 0–12 bar, x cumulative seconds; solid
 * horizontal grid + dashed vertical grid; a faint dashed 9-bar reference; one
 * point per segment END (cumulative time, target) + a fixed origin, the line
 * portion coloured by each segment's mode (pressure = sage, flow = blue).
 *
 * A handle at each segment end edits (target, time). The gesture consumes the
 * touch ONLY when a handle is grabbed, so dragging elsewhere falls through to
 * the page scroll.
 */
private const val CURVE_Y_MAX = 12f
private const val PAD_L = 26f
private const val PAD_R = 8f
private const val PAD_T = 8f
private const val PAD_B = 16f

@Composable
fun ProfileCurveChart(
    targets: List<Float>,
    times: List<Float>,
    modes: List<String?> = emptyList(),
    modifier: Modifier = Modifier,
    /** Non-null enables draggable handles: (segmentIndex, newTarget, newTime). */
    onSegmentEdit: ((index: Int, target: Float, time: Float) -> Unit)? = null,
) {
    val tel = CremaTheme.telemetry
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val refColor = tel.pressure.copy(alpha = 0.3f)
    val accent = MaterialTheme.colorScheme.primary
    val handleRing = MaterialTheme.colorScheme.surface
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = labelColor, fontSize = 10.sp, fontFamily = JetBrainsMono)

    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    // Fresh values for the long-lived (Unit-keyed) gesture coroutine.
    val targetsState = rememberUpdatedState(targets)
    val timesState = rememberUpdatedState(times)
    val editState = rememberUpdatedState(onSegmentEdit)

    val gesture = if (onSegmentEdit == null) {
        Modifier
    } else {
        Modifier.pointerInput(Unit) {
            val grab = 24.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val tms = timesState.value
                val tgts = targetsState.value
                val total = max(45f, tms.sum())
                val plotL = PAD_L.dp.toPx()
                val plotR = size.width.toFloat() - PAD_R.dp.toPx()
                val plotT = PAD_T.dp.toPx()
                val plotB = size.height.toFloat() - PAD_B.dp.toPx()
                val plotW = (plotR - plotL).coerceAtLeast(1f)
                val plotH = (plotB - plotT).coerceAtLeast(1f)
                // Hit-test the nearest segment-end handle within the grab radius.
                var grabbed: Int? = null
                var best = grab
                var cum = 0f
                for (i in tgts.indices) {
                    cum += tms.getOrElse(i) { 0f }
                    val hx = plotL + cum / total * plotW
                    val hy = plotB - (tgts[i].coerceIn(0f, CURVE_Y_MAX) / CURVE_Y_MAX) * plotH
                    val d = (Offset(hx, hy) - down.position).getDistance()
                    if (d <= best) {
                        best = d
                        grabbed = i
                    }
                }
                val idx = grabbed ?: return@awaitEachGesture // not on a handle → let the page scroll
                draggingIndex = idx
                down.consume()
                drag(down.id) { change ->
                    change.consume()
                    val tms2 = timesState.value
                    val total2 = max(45f, tms2.sum())
                    val cumTime = (change.position.x - plotL) / plotW * total2
                    val value = (plotB - change.position.y) / plotH * CURVE_Y_MAX
                    val startTime = (0 until idx).sumOf { tms2.getOrElse(it) { 0f }.toDouble() }.toFloat()
                    val timeRaw = (cumTime - startTime).coerceIn(0.5f, 120f)
                    val targetRaw = value.coerceIn(0f, CURVE_Y_MAX)
                    editState.value?.invoke(idx, (targetRaw * 10).roundToInt() / 10f, (timeRaw * 2).roundToInt() / 2f)
                }
                draggingIndex = null
            }
        }
    }

    Canvas(modifier.then(gesture)) {
        val plotL = PAD_L.dp.toPx()
        val plotR = size.width - PAD_R.dp.toPx()
        val plotT = PAD_T.dp.toPx()
        val plotB = size.height - PAD_B.dp.toPx()
        val plotW = (plotR - plotL).coerceAtLeast(1f)
        val plotH = (plotB - plotT).coerceAtLeast(1f)
        val total = max(45f, times.sum())

        fun xPx(t: Float) = plotL + t / total * plotW
        fun yPx(v: Float) = plotB - (v.coerceIn(0f, CURVE_Y_MAX) / CURVE_Y_MAX) * plotH

        // Horizontal grid + left bar labels.
        listOf(0, 3, 6, 9, 12).forEach { bar ->
            val y = yPx(bar.toFloat())
            drawLine(gridColor, Offset(plotL, y), Offset(plotR, y), strokeWidth = 1f)
            val lt = textMeasurer.measure("$bar", labelStyle)
            drawText(lt, topLeft = Offset(plotL - lt.size.width - 4.dp.toPx(), y - lt.size.height / 2f))
        }
        // Vertical dashed grid + time labels.
        val vDash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
        var tv = 10
        while (tv < total) {
            val x = xPx(tv.toFloat())
            drawLine(gridColor, Offset(x, plotT), Offset(x, plotB), strokeWidth = 1f, pathEffect = vDash)
            val lt = textMeasurer.measure("${tv}s", labelStyle)
            drawText(lt, topLeft = Offset(x - lt.size.width / 2f, plotB + 2.dp.toPx()))
            tv += 10
        }
        // Faint dashed 9-bar reference.
        val y9 = yPx(9f)
        drawLine(
            refColor,
            Offset(plotL, y9),
            Offset(plotR, y9),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
        )

        // Curve — origin at the first target, then a line per segment to its end
        // point, coloured by that segment's mode.
        if (targets.isNotEmpty()) {
            var prev = Offset(xPx(0f), yPx(targets[0]))
            var cum = 0f
            targets.forEachIndexed { i, target ->
                cum += times.getOrElse(i) { 0f }
                val p = Offset(xPx(cum), yPx(target))
                val color = if (modes.getOrNull(i) == "flow") tel.flow else tel.pressure
                drawLine(color, prev, p, strokeWidth = 2.6.dp.toPx(), cap = StrokeCap.Round)
                prev = p
            }
            // Draggable handles at each segment end.
            cum = 0f
            targets.forEachIndexed { i, target ->
                cum += times.getOrElse(i) { 0f }
                val c = Offset(xPx(cum), yPx(target))
                drawCircle(handleRing, radius = 7.dp.toPx(), center = c)
                val dot = when {
                    i == draggingIndex -> accent
                    modes.getOrNull(i) == "flow" -> tel.flow
                    else -> tel.pressure
                }
                drawCircle(dot, radius = 5.dp.toPx(), center = c)
            }
        }
    }
}

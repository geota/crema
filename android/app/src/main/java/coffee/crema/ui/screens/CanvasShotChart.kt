package coffee.crema.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import kotlin.math.ceil
import kotlin.math.max

/*
 * CanvasShotChart — the live espresso telemetry chart, a hand-rolled Canvas port
 * of the web shell's uPlot LiveChart (web/src/lib/components/brew/LiveChart.svelte).
 * Replaced Vico app-wide: Canvas owns the value→pixel transform, so the dual-axis
 * relabel, channel-coloured curves, and the playhead are all just arithmetic.
 *
 * THE DESIGN (from uPlot): ONE shared y-scale. pressure(bar)/flow(ml·s) plot raw
 * (≈0–10); temp(°C)/weight(g)/volume(ml) plot ÷10 (93 °C→9.3, 36 g→3.6). The
 * LEFT axis labels the scale as-is (bar / ml·s); the RIGHT axis labels the same
 * ticks ×10 (°C / g). y grows from 10 (+0.3 headroom), x from 60 s. Solid
 * horizontal grid (off the left axis) + dashed vertical grid; the dashed pressure
 * setpoint rides along when pressure is shown; a faded copper "now" line + a
 * per-channel end-dot when [live].
 *
 * Channels + transforms mirror the toggle set in Quick Controls (the same keys
 * the web uses). `available` gates the nullable scale-only channels so a missing
 * channel is dropped, never drawn as a flat zero.
 */
private class CanvasChannel(
    val key: String,
    val color: Color,
    val widthDp: Float,
    val dashed: Boolean = false,
    val alpha: Float = 1f,
    /** Drop the channel when no sample carries it (vs. drawing a flat zero). */
    val available: (List<TelemetrySample>) -> Boolean = { true },
    /** Applies the plot transform (raw, ÷10, clamp); null = no value at this sample. */
    val valueOf: (TelemetrySample) -> Float?,
)

@Composable
fun CanvasShotChart(
    samples: List<TelemetrySample>,
    enabledChannels: Set<String>,
    live: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tel = CremaTheme.telemetry
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val nowColor = MaterialTheme.colorScheme.primary
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember(labelColor) { TextStyle(color = labelColor, fontSize = 10.sp, fontFamily = JetBrainsMono) }

    // Fixed channel order + plot transforms (temp/weight/volume ride the ÷10 scale).
    // Remembered (keyed on the theme) so the 8 specs + their lambdas aren't
    // reallocated on every telemetry frame (~25 Hz during a shot).
    val channels = remember(tel) {
        listOf(
        CanvasChannel("pressure", tel.pressure, 2.6f) { it.pressure },
        CanvasChannel("flow", tel.flow, 2.2f) { it.flow },
        CanvasChannel("headTemp", tel.temp, 2.2f) { it.headTemp / 10f },
        CanvasChannel("mixTemp", tel.temp2, 1.5f) { it.mixTemp / 10f },
        CanvasChannel("weight", tel.weight, 2.2f, available = { s -> s.any { it.weight != null } }) { it.weight?.div(10f) },
        CanvasChannel("weightFlow", tel.weight2, 1.5f, available = { s -> s.any { it.weightFlow != null } }) { it.weightFlow },
        CanvasChannel("dispensedVolume", tel.flow2, 1.5f) { it.dispensedVolume / 10f },
        CanvasChannel(
            "resistance",
            tel.pressure2,
            1.5f,
            available = { s -> s.any { (it.resistanceWeight ?: it.resistance) != null } },
        ) { ((it.resistanceWeight ?: it.resistance) ?: 0f).coerceIn(0f, 10f) },
        )
    }
    // The dashed pressure setpoint rides along whenever pressure is shown (web parity).
    val setpoint = remember(tel) {
        CanvasChannel("setPressure", tel.pressure, 1.2f, dashed = true, alpha = 0.6f) { it.setGroupPressure }
    }
    val active = buildList {
        addAll(channels.filter { it.key in enabledChannels && it.available(samples) })
        if ("pressure" in enabledChannels) add(setpoint)
    }

    Canvas(modifier) {
        val padL = 30.dp.toPx()
        val padR = 34.dp.toPx()
        val padT = 8.dp.toPx()
        val padB = 16.dp.toPx()
        val plotL = padL
        val plotR = size.width - padR
        val plotT = padT
        val plotB = size.height - padB
        val plotW = (plotR - plotL).coerceAtLeast(1f)
        val plotH = (plotB - plotT).coerceAtLeast(1f)

        // Ranges (mirror uPlot: y floors at 10 + 0.3 headroom, x floors at 60 s).
        val lastSec = (samples.lastOrNull()?.elapsedMs ?: 0L) / 1000f
        val xMax = max(60f, ceil(lastSec))
        var dataMax = 0f
        samples.forEach { s -> active.forEach { ch -> ch.valueOf(s)?.let { if (it > dataMax) dataMax = it } } }
        val yMax = max(10f, ceil(dataMax + 0.3f))

        fun xPx(tSec: Float) = plotL + (tSec / xMax) * plotW
        fun yPx(v: Float) = plotB - (v / yMax) * plotH

        // Horizontal gridlines + left (raw) / right (×10) axis labels, step 2.
        var yv = 0
        while (yv <= yMax.toInt()) {
            val y = yPx(yv.toFloat())
            drawLine(gridColor, Offset(plotL, y), Offset(plotR, y), strokeWidth = 1f)
            val lt = textMeasurer.measure("$yv", labelStyle)
            drawText(lt, topLeft = Offset(plotL - lt.size.width - 4.dp.toPx(), y - lt.size.height / 2f))
            val rt = textMeasurer.measure("${yv * 10}", labelStyle)
            drawText(rt, topLeft = Offset(plotR + 4.dp.toPx(), y - rt.size.height / 2f))
            yv += 2
        }

        // Vertical dashed gridlines + elapsed-second labels.
        val step = if (xMax <= 90f) 10 else if (xMax <= 180f) 20 else 30
        val vDash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))
        var tv = step
        while (tv < xMax) {
            val x = xPx(tv.toFloat())
            drawLine(gridColor, Offset(x, plotT), Offset(x, plotB), strokeWidth = 1f, pathEffect = vDash)
            val ltxt = textMeasurer.measure("${tv}s", labelStyle)
            drawText(ltxt, topLeft = Offset(x - ltxt.size.width / 2f, plotB + 2.dp.toPx()))
            tv += step
        }

        // Channel curves — a polyline per channel; a null value breaks the line.
        val lineDash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        if (samples.size >= 2) {
            active.forEach { ch ->
                val path = Path()
                var started = false
                samples.forEach { s ->
                    val v = ch.valueOf(s)
                    if (v == null) {
                        started = false
                        return@forEach
                    }
                    val x = xPx(s.elapsedMs / 1000f)
                    val y = yPx(v)
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(
                    path,
                    color = ch.color.copy(alpha = ch.alpha),
                    style = Stroke(
                        width = ch.widthDp.dp.toPx(),
                        pathEffect = if (ch.dashed) lineDash else null,
                    ),
                )
            }
        }

        // "Now" marker — faded copper vertical line at the latest sample + a
        // per-channel end-dot on each solid curve (uPlot's markerPlugin). Live only.
        if (live && samples.isNotEmpty()) {
            val last = samples.last()
            val nx = xPx(last.elapsedMs / 1000f)
            drawLine(
                nowColor.copy(alpha = 0.7f),
                Offset(nx, plotT),
                Offset(nx, plotB),
                strokeWidth = 1.5.dp.toPx(),
            )
            active.forEach { ch ->
                if (!ch.dashed) {
                    ch.valueOf(last)?.let { v ->
                        drawCircle(ch.color, radius = 3.5.dp.toPx(), center = Offset(nx, yPx(v)))
                    }
                }
            }
        }
    }
}

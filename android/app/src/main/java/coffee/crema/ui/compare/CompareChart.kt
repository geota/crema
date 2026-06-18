package coffee.crema.ui.compare

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.history.StoredShot
import coffee.crema.ui.theme.JetBrainsMono
import kotlin.math.roundToInt

/*
 * CompareChart — hand-rolled overlay chart: one Path per shot for the selected
 * channel, drawn through the shot's REAL recorded samples (each shot keeps its
 * own time series — no resampling onto a shared grid). Round-number x/y gridlines
 * + JetBrains-Mono tick labels. Ported from compare-handoff/kotlin/CompareChart.kt;
 * the prototype's synthetic sampler is replaced by [channelValue] over
 * [TelemetrySample].
 *
 * Fixed height via the caller's Modifier (tablet 320dp, phone 220dp); width is
 * whatever the parent gives.
 */
@Composable
fun CompareChart(
    shots: List<StoredShot>,
    channel: Channel,
    modifier: Modifier = Modifier,
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    val axisLabel = MaterialTheme.colorScheme.onSurfaceVariant
    val colors = shots.indices.map { compareColor(it) }
    val measurer = rememberTextMeasurer()
    val tickStyle = TextStyle(fontSize = 10.sp, color = axisLabel, fontFamily = JetBrainsMono)

    // Domain — max time (≥15s) and max value (channel floor, +10% headroom).
    fun secOf(s: StoredShot) = (s.samples.lastOrNull()?.elapsedMs ?: s.durationMs) / 1000f
    val maxTime = maxOf(15f, shots.maxOfOrNull { secOf(it) } ?: 15f)
    var maxY = axisFloor(channel)
    for (s in shots) for (sample in s.samples) channelValue(sample, channel)?.let { if (it > maxY) maxY = it }
    maxY *= 1.1f

    // Tick cadence — same rounding as the static shot chart.
    val xIncr = if (maxTime <= 20f) 5f else if (maxTime <= 45f) 10f else 20f

    Canvas(modifier) {
        val padL = 40.dp.toPx(); val padR = 16.dp.toPx()
        val padT = 14.dp.toPx(); val padB = 26.dp.toPx()
        val plotW = size.width - padL - padR
        val plotH = size.height - padT - padB
        fun xAt(t: Float) = padL + (t / maxTime) * plotW
        fun yAt(v: Float) = padT + plotH - (v / maxY) * plotH

        // Plot frame.
        drawRect(grid, topLeft = Offset(padL, padT), size = Size(plotW, plotH), style = Stroke(width = 1f))

        // Horizontal y grid + labels (4 splits).
        for (i in 1..4) {
            val v = maxY * i / 4f
            val y = yAt(v)
            drawLine(grid, Offset(padL, y), Offset(padL + plotW, y), strokeWidth = 1f)
            val tl = measurer.measure(v.roundToInt().toString(), tickStyle)
            drawText(tl, topLeft = Offset(padL - 6.dp.toPx() - tl.size.width, y - tl.size.height / 2f))
        }

        // Vertical x grid + labels (dashed).
        val dash = PathEffect.dashPathEffect(floatArrayOf(2f, 5f))
        var t = xIncr
        while (t < maxTime) {
            val x = xAt(t)
            drawLine(grid, Offset(x, padT), Offset(x, padT + plotH), strokeWidth = 1f, pathEffect = dash)
            val tl = measurer.measure("${t.roundToInt()}s", tickStyle)
            drawText(tl, topLeft = Offset(x - tl.size.width / 2f, padT + plotH + 4.dp.toPx()))
            t += xIncr
        }

        // One path per shot, through its real samples; a null value breaks the line.
        shots.forEachIndexed { idx, s ->
            val path = Path()
            var started = false
            for (sample in s.samples) {
                val v = channelValue(sample, channel)
                if (v == null) { started = false; continue }
                val px = xAt(sample.elapsedMs / 1000f); val py = yAt(v)
                if (!started) { path.moveTo(px, py); started = true } else path.lineTo(px, py)
            }
            drawPath(
                path = path,
                color = colors[idx],
                style = Stroke(width = 2.4f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

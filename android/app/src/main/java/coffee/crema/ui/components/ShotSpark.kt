package coffee.crema.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import coffee.crema.history.Spark
import coffee.crema.ui.theme.CremaTheme

/*
 * The History row chart drawn from a row's stored spark (#310 P4a, SPEC §4):
 * no series is read to draw a row. Machine shots draw weight and flow behind,
 * pressure on top, each on the core's fixed scale; a guided brew draws its
 * weight curve over hairline stage ticks. A gap is a break in the line.
 */

/** A row's spark: [Spark.machine] channels, or a brew's weight with its stage ticks. */
@Composable
fun ShotSparkChart(
    spark: Spark,
    modifier: Modifier = Modifier,
    insetDp: Float = 2f,
    weightStroke: Float = 1.3f,
    flowStroke: Float = 1.3f,
    pressureStroke: Float = 1.8f,
) {
    val tel = CremaTheme.telemetry
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Canvas(modifier) {
        val inset = insetDp.dp.toPx()
        val plotW = (size.width - inset * 2f).coerceAtLeast(1f)
        val plotH = (size.height - inset * 2f).coerceAtLeast(1f)
        val last = (spark.pressure.size - 1).coerceAtLeast(1)
        fun x(i: Int) = inset + i.toFloat() / last * plotW
        fun channel(points: List<Float?>, color: Color, widthDp: Float) {
            if (points.count { it != null } < 2) return
            val path = Path()
            var pen = false
            points.forEachIndexed { i, v ->
                if (v == null) {
                    pen = false
                    return@forEachIndexed
                }
                val y = inset + (1f - v.coerceIn(0f, 1f)) * plotH
                if (pen) path.lineTo(x(i), y) else path.moveTo(x(i), y)
                pen = true
            }
            drawPath(path, color = color, style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (spark.brew) {
            for (t in spark.ticks) {
                val tx = inset + t * plotW
                drawLine(tickColor, start = Offset(tx, inset), end = Offset(tx, inset + plotH), strokeWidth = 1f)
            }
            channel(spark.weight, tel.weight, 1.5f)
            return@Canvas
        }
        channel(spark.weight, tel.weight.copy(alpha = 0.9f), weightStroke)
        channel(spark.flow, tel.flow.copy(alpha = 0.9f), flowStroke)
        channel(spark.pressure, tel.pressure, pressureStroke)
    }
}

/**
 * A chart's place while its shot loads ([loading]), or when it couldn't be
 * read: never "no telemetry", which would be a claim about the shot.
 */
@Composable
fun ChartLoading(loading: Boolean, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            if (loading) "Loading…" else "This shot's telemetry couldn't be read.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

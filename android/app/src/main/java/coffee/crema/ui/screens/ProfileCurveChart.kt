package coffee.crema.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.data.ExtraStore

/*
 * ProfileCurveChart — the recipe's pressure / flow curve in the Profile editor,
 * a Vico line over the segment control points. Sibling of ShotChart (which keeps
 * all live-telemetry Vico in one file); this keeps all profile-curve Vico here.
 *
 * v1 (3a): STATIC render that updates live as the editor's per-segment steppers
 * change. One line through a fixed origin + each segment's (cumulative-end-time,
 * target) point — the same control-point layout the web editor's drag handles use
 * (ProfileCurveEditor.svelte). y is bar (0–12); x is cumulative seconds.
 *
 * Next (3b): a draggable-handle overlay on top of this surface — handles placed
 * via Vico's plot bounds (data→px) and dragged back to (target, time) (px→data),
 * writing the SAME segment state these steppers do. Mode-aware colour (flow
 * dashed) + a 9-bar reference line ride along with that work.
 */

/** y fixed to 0–12 bar; x follows the data (total time), min 30 s so a short
 *  recipe still reads. */
private val ProfileCurveRangeProvider =
    object : CartesianLayerRangeProvider {
        override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
        override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) =
            if (maxX <= 0.0) 30.0 else maxX
        override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = 0.0
        override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = 12.0
    }

@Composable
fun ProfileCurveChart(
    targets: List<Float>,
    times: List<Float>,
    modifier: Modifier = Modifier,
) {
    val tel = CremaTheme.telemetry
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val modelProducer = remember { CartesianChartModelProducer() }
    val curveLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.pressure)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.6.dp),
    )

    // Control points: a fixed origin at the first segment's target, then one point
    // per segment END (cumulative time, target). `targets` / `times` are passed as
    // immutable copies (List.toList()) so a stepper edit changes the LaunchedEffect
    // keys by content and re-pushes the series.
    LaunchedEffect(modelProducer, targets, times) {
        val points = buildList {
            add(0f to (targets.firstOrNull() ?: 0f))
            var t = 0f
            targets.forEachIndexed { i, target ->
                t += times.getOrElse(i) { 0f }
                add(t to target)
            }
        }
        // Vico requires ≥1 series with ≥2 points; a segment-less profile draws a
        // flat baseline rather than throwing.
        val safe = if (points.size >= 2) points else listOf(0f to 0f, 30f to 0f)
        modelProducer.runTransaction {
            lineModel {
                series(x = safe.map { it.first }, y = safe.map { it.second }, key = "curve")
            }
        }
    }

    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp, fontFamily = JetBrainsMono)
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(listOf(curveLine)),
            rangeProvider = ProfileCurveRangeProvider,
        ),
        startAxis = VerticalAxis.rememberStart(
            valueFormatter = CartesianValueFormatter { _, v, _ -> v.toInt().toString() },
            itemPlacer = remember { VerticalAxis.ItemPlacer.step({ 3.0 }) },
            label = rememberAxisLabelComponent(style = labelStyle),
            guideline = rememberAxisGuidelineComponent(shape = RectangleShape, fill = Fill(gridColor)),
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            valueFormatter = CartesianValueFormatter { _, v, _ -> "${v.toInt()}s" },
            itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned() },
            label = rememberAxisLabelComponent(style = labelStyle),
            guideline = rememberAxisGuidelineComponent(fill = Fill(gridColor)),
        ),
    )

    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        modifier = modifier,
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false),
        animationSpec = null,
        animateIn = false,
    )
}

package coffee.crema.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.TelemetrySample
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
 * ShotChart — the live espresso telemetry chart, a Vico port of the web PWA's
 * uPlot chart (web/src/lib/components/brew/LiveChart.svelte).
 *
 * THE KEY DESIGN (from uPlot): one shared 0–10 value scale, relabelled by two
 * axes. Pressure (bar) and flow (ml/s) plot at their raw value (≈0–10).
 * Temperature (°C) and weight (g) are DIVIDED BY 10 before plotting (93 °C →
 * 9.3; 36 g → 3.6) so they ride the same scale. The START (left) axis prints
 * the raw value (0,2,…,10 = bar / ml·s); the END (right) axis prints the same
 * tick positions ×10 (0,20,…,100 = °C / g) — a cosmetic relabel, not an
 * independent range. In Vico, a VerticalAxis with no layer bound to it falls
 * back to the shared range, so the end axis just needs a ×10 valueFormatter.
 *
 * Vico is isolated to this one file. All other code passes a plain
 * List<TelemetrySample>. Real-time: one runTransaction per new buffer; diff +
 * enter animations are off (the data is already ~25 Hz).
 *
 * M2 v1 scope: the curves + dual-labelled axes + auto-growing ranges. The
 * copper "now" playhead + per-channel end-dots (uPlot's markerPlugin) are a
 * Compose overlay in the next increment; channel-visibility toggles arrive with
 * the Quick Controls sheet. Defaults match the web (pressure, flow, weight) plus
 * head temp (so the right axis is always exercised) and the pressure setpoint.
 */

// Shared range: y pinned [0, max(10, dataMax + 0.3)]; x pinned [0, max(60, dataMax)].
// Mirrors the uPlot scale ranges (floor-then-grow, +0.3 y headroom for end-dots).
private val ShotRangeProvider =
    object : CartesianLayerRangeProvider {
        override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
        override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) = maxOf(60.0, maxX)
        override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = 0.0
        override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = maxOf(10.0, maxY + 0.3)
    }

// Left axis: raw bar / ml·s. Right axis: the same positions ×10 = °C / g.
private val LeftAxisFormatter = CartesianValueFormatter { _, v, _ -> v.toInt().toString() }
private val RightAxisFormatter = CartesianValueFormatter { _, v, _ -> (v * 10).toInt().toString() }
private val TimeAxisFormatter = CartesianValueFormatter { _, v, _ -> "${v.toInt()}s" }

@Composable
fun ShotChart(samples: List<TelemetrySample>, modifier: Modifier = Modifier) {
    val tel = CremaTheme.telemetry
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val modelProducer = remember { CartesianChartModelProducer() }
    // Weight rides the chart only when a scale is paired (it's the one nullable
    // channel). Both the line list and the series push key off this so their
    // index order always matches (Vico maps lines→series by position).
    val hasWeight = samples.any { it.weight != null }

    // Real-time feed. Empty buffer (resting) → a flat baseline so the axes/grid
    // still render the empty frame, matching uPlot's at-rest look.
    LaunchedEffect(modelProducer, samples, hasWeight) {
        if (samples.isEmpty()) {
            modelProducer.runTransaction {
                lineModel {
                    series(x = listOf(0f, 60f), y = listOf(0f, 0f), key = "pressure")
                    series(x = listOf(0f, 60f), y = listOf(0f, 0f), key = "flow")
                    series(x = listOf(0f, 60f), y = listOf(0f, 0f), key = "temp")
                    series(x = listOf(0f, 60f), y = listOf(0f, 0f), key = "setPressure")
                }
            }
            return@LaunchedEffect
        }
        val xs = samples.map { it.elapsedMs / 1000f }
        modelProducer.runTransaction {
            lineModel {
                series(x = xs, y = samples.map { it.pressure }, key = "pressure")
                series(x = xs, y = samples.map { it.flow }, key = "flow")
                series(x = xs, y = samples.map { it.headTemp / 10f }, key = "temp")
                if (hasWeight) {
                    series(x = xs, y = samples.map { (it.weight ?: 0f) / 10f }, key = "weight")
                }
                series(x = xs, y = samples.map { it.setGroupPressure }, key = "setPressure")
            }
        }
    }

    // All lines created unconditionally (stable remember slots); the weight line
    // is only added to the provider when a scale is present (see hasWeight).
    val pressureLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.pressure)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.6.dp),
    )
    val flowLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.flow)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.2.dp),
    )
    val tempLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.temp)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.2.dp),
    )
    val weightLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.weight)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.2.dp),
    )
    val setpointLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.pressure.copy(alpha = 0.6f))),
        stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 1.2.dp, dashLength = 3.dp, gapLength = 3.dp),
    )

    // Order MUST match the series() push order above.
    val lines = buildList {
        add(pressureLine)
        add(flowLine)
        add(tempLine)
        if (hasWeight) add(weightLine)
        add(setpointLine)
    }

    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp, fontFamily = JetBrainsMono)

    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(lines),
            rangeProvider = ShotRangeProvider,
            // verticalAxisPosition left null → one shared range read by both axes.
        ),
        startAxis = VerticalAxis.rememberStart(
            valueFormatter = LeftAxisFormatter,
            itemPlacer = remember { VerticalAxis.ItemPlacer.step({ 2.0 }) },
            label = rememberAxisLabelComponent(style = labelStyle),
            // Solid horizontal gridlines (uPlot draws these off the left axis).
            guideline = rememberAxisGuidelineComponent(shape = RectangleShape, fill = Fill(gridColor)),
        ),
        endAxis = VerticalAxis.rememberEnd(
            valueFormatter = RightAxisFormatter,
            itemPlacer = remember { VerticalAxis.ItemPlacer.step({ 2.0 }) },
            label = rememberAxisLabelComponent(style = labelStyle),
            guideline = null, // avoid a second set of horizontal lines
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            valueFormatter = TimeAxisFormatter,
            itemPlacer = remember { HorizontalAxis.ItemPlacer.aligned() },
            label = rememberAxisLabelComponent(style = labelStyle),
            guideline = rememberAxisGuidelineComponent(fill = Fill(gridColor)), // dashed (default)
        ),
        // Pin the x-step so "aligned" ticks land on whole 10-second marks
        // regardless of the (dense) sample spacing.
        getXStep = { _, _, _ -> 10.0 },
    )

    CartesianChartHost(
        chart = chart,
        modelProducer = modelProducer,
        modifier = modifier.fillMaxSize(),
        scrollState = rememberVicoScrollState(scrollEnabled = false),
        zoomState = rememberVicoZoomState(zoomEnabled = false),
        animationSpec = null, // instant updates (data is already high-rate)
        animateIn = false,
    )
}

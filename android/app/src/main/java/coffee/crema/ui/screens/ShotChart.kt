package coffee.crema.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
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
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.marker.LineCartesianLayerMarkerTarget
import com.patrykandpatrick.vico.compose.common.* // Fill, pixels, half (the .pixels/.half extensions live here)
import com.patrykandpatrick.vico.compose.common.component.Component
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
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
 * M2 scope: curves + dual-labelled axes + auto-growing ranges + the copper
 * "now" playhead with per-channel end-dots (uPlot's markerPlugin, via a custom
 * persistent CartesianMarker — see PlayheadMarker). Channels are chosen from
 * Quick Controls via [enabledChannels] (defaults match the web: pressure, flow,
 * weight); the dashed pressure setpoint rides along when pressure is shown.
 * Pin/drag is a later increment.
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

/**
 * One toggleable chart channel: its stable key, its pre-remembered Vico line,
 * an [available] predicate that gates nullable (scale-only / low-flow) channels
 * so we never plot an all-zero ghost, and [valueOf] which already applies the
 * channel's plot transform (raw, ÷10, clamp). The enabled + available subset is
 * the single source of order for both the line provider and the series push, so
 * Vico's line→series index mapping always lines up.
 */
private class ChartChannelSpec(
    val key: String,
    val line: LineCartesianLayer.Line,
    val available: (List<TelemetrySample>) -> Boolean,
    val valueOf: (TelemetrySample) -> Float,
)

/*
 * The copper "now" playhead — a custom CartesianMarker. (Vico's
 * DefaultCartesianMarker always draws a label bubble, which we don't want; a
 * custom marker draws only what we render and reserves no plot margin.) Mirrors
 * uPlot's markerPlugin: one vertical guideline at the marked x spanning the plot
 * height, plus a filled dot per line series at its current value in that series'
 * colour. Drawn over the curves and pinned at the latest sample via
 * persistentMarkers. The draw idiom (drawVertical / pixels.half / ShapeComponent
 * .draw) mirrors Vico's own DefaultCartesianMarker.
 */
private class PlayheadMarker(
    private val guideline: LineComponent,
    private val indicator: (Color) -> Component,
    private val indicatorSizeDp: Dp,
) : CartesianMarker {
    override fun drawOverLayers(
        context: CartesianDrawingContext,
        targets: List<CartesianMarker.Target>,
    ) {
        with(context) {
            targets.map { it.canvasX }.toSet().forEach { x ->
                guideline.drawVertical(this, x, layerBounds.top, layerBounds.bottom)
            }
            // .pixels (Dp→px) is public; Vico's Float.half is internal, so halve manually.
            val half = indicatorSizeDp.pixels / 2f
            targets.forEach { target ->
                if (target is LineCartesianLayerMarkerTarget) {
                    target.points.forEach { point ->
                        indicator(point.color).draw(
                            this,
                            target.canvasX - half,
                            point.canvasY - half,
                            target.canvasX + half,
                            point.canvasY + half,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberPlayheadMarker(lineColor: Color): CartesianMarker {
    val guideline = remember(lineColor) {
        LineComponent(fill = Fill(lineColor), thickness = 1.5.dp)
    }
    return remember(lineColor) {
        PlayheadMarker(
            guideline = guideline,
            indicator = { color -> ShapeComponent(fill = Fill(color), shape = CircleShape) },
            indicatorSizeDp = 7.dp,
        )
    }
}

@Composable
fun ShotChart(
    samples: List<TelemetrySample>,
    enabledChannels: Set<String>,
    live: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val tel = CremaTheme.telemetry
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant

    val modelProducer = remember { CartesianChartModelProducer() }
    // One line per channel, all created unconditionally (stable remember slots).
    // Colours/widths from the theme; temp/weight/volume ride the ÷10 scale.
    val pressureLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.pressure)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.6.dp),
    )
    val flowLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.flow)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.2.dp),
    )
    val headTempLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.temp)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.2.dp),
    )
    val mixTempLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.temp2)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
    )
    val weightLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.weight)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.2.dp),
    )
    val weightFlowLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.weight2)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
    )
    val volumeLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.flow2)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
    )
    val resistanceLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.pressure2)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 1.5.dp),
    )
    val setpointLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.pressure.copy(alpha = 0.6f))),
        stroke = LineCartesianLayer.LineStroke.Dashed(thickness = 1.2.dp, dashLength = 3.dp, gapLength = 3.dp),
    )

    // The toggleable channels in a fixed order. `available` gates the nullable
    // (scale-only / low-flow) channels so a disabled-by-data channel is dropped
    // rather than drawn as a flat zero.
    val specs = listOf(
        ChartChannelSpec("pressure", pressureLine, { true }) { it.pressure },
        ChartChannelSpec("flow", flowLine, { true }) { it.flow },
        ChartChannelSpec("headTemp", headTempLine, { true }) { it.headTemp / 10f },
        ChartChannelSpec("mixTemp", mixTempLine, { true }) { it.mixTemp / 10f },
        ChartChannelSpec("weight", weightLine, { s -> s.any { it.weight != null } }) { (it.weight ?: 0f) / 10f },
        ChartChannelSpec("weightFlow", weightFlowLine, { s -> s.any { it.weightFlow != null } }) { it.weightFlow ?: 0f },
        ChartChannelSpec("dispensedVolume", volumeLine, { true }) { it.dispensedVolume / 10f },
        ChartChannelSpec(
            "resistance",
            resistanceLine,
            { s -> s.any { (it.resistanceWeight ?: it.resistance) != null } },
        ) { ((it.resistanceWeight ?: it.resistance) ?: 0f).coerceIn(0f, 10f) },
    )
    val active = specs.filter { it.key in enabledChannels && it.available(samples) }
    // The dashed pressure setpoint rides along whenever pressure is shown (web parity).
    val setpointSpec = ChartChannelSpec("setPressure", setpointLine, { true }) { it.setGroupPressure }
    val withSetpoint = if ("pressure" in enabledChannels) active + setpointSpec else active
    // Always keep ≥1 series — Vico requires it, and it draws the resting frame.
    val effective = withSetpoint.ifEmpty { listOf(specs[0]) }
    val providerLines = effective.map { it.line }

    // Real-time feed: one transaction per buffer / channel-set change. Empty
    // buffer (resting) → a flat baseline so the axes/grid still render the frame,
    // matching uPlot's at-rest look. The push order matches [providerLines].
    LaunchedEffect(modelProducer, samples, enabledChannels) {
        modelProducer.runTransaction {
            lineModel {
                if (samples.isEmpty()) {
                    effective.forEach { spec ->
                        series(x = listOf(0f, 60f), y = listOf(0f, 0f), key = spec.key)
                    }
                } else {
                    val xs = samples.map { it.elapsedMs / 1000f }
                    effective.forEach { spec ->
                        series(x = xs, y = samples.map { spec.valueOf(it) }, key = spec.key)
                    }
                }
            }
        }
    }

    val labelStyle = TextStyle(color = labelColor, fontSize = 11.sp, fontFamily = JetBrainsMono)

    val playhead = rememberPlayheadMarker(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
    // Pin the playhead at the latest sample's x (data-space). Null while resting
    // (the synthetic baseline) → no playhead. Captured fresh each recomposition
    // so Vico rebuilds the marker as the shot advances (persistentMarkers diffs
    // by the lambda's hashCode).
    val playheadX: Float? = samples.lastOrNull()?.let { it.elapsedMs / 1000f }

    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = LineCartesianLayer.LineProvider.series(providerLines),
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
        // The "now" playhead: a persistent marker at the latest sample's x.
        // Live only — a static history chart shows the whole shot, no playhead.
        persistentMarkers = { _ ->
            if (live && playheadX != null) playhead at playheadX
        },
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

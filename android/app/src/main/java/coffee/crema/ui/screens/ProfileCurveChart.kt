package coffee.crema.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.data.lineModel
import com.patrykandpatrick.vico.compose.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.common.data.ExtraStore
import kotlin.math.roundToInt

/*
 * ProfileCurveChart — the recipe's curve in the Profile editor, a Vico line over
 * the segment control points, with optional DRAGGABLE handles. Sibling of
 * ShotChart (which keeps all live-telemetry Vico in one file); this keeps all
 * profile-curve Vico here.
 *
 * STATIC (onSegmentEdit == null): one line through a fixed origin + each
 * segment's (cumulative-end-time, target) point. y is bar (0–12); x is
 * cumulative seconds.
 *
 * INTERACTIVE (onSegmentEdit != null): a draggable handle at each segment's end
 * point. Dragging maps the pointer back to (target, time) and calls onSegmentEdit
 * — the SAME segment state the editor's steppers write, so the two input surfaces
 * stay in lockstep. This mirrors the web's uPlot drag editor
 * (ProfileCurveEditor.svelte): handles placed via data→pixel, dragged back via
 * pixel→data.
 *
 * HOW THE DRAG WORKS (Vico 3.2.1 exposes no public value↔pixel API):
 *  - A capture-only [Decoration] copies the plot rect (`layerBounds`) + axis
 *    ranges into Compose state each draw — [GeometryCaptureDecoration]. (Vico's
 *    `chart.layerBounds` is internal; a Decoration is the public hook, and unlike
 *    a marker it reserves no plot margin.)
 *  - The mapping is computed manually with formulas lifted verbatim from Vico's
 *    own `LineCartesianLayer.getDrawX/getDrawY` — exact for this config (no point
 *    markers, zero layer padding, scroll + zoom disabled, LTR).
 *  - The gesture surface uses `pointerInput(Unit)` + rememberUpdatedState (so a
 *    per-frame edit never restarts the gesture) and consumes the down ONLY when a
 *    handle is grabbed, so non-handle drags fall through to the page scroll.
 *
 * Deferred (polish): per-segment pressure/flow colour split, a 9-bar reference
 * line, ramp-accurate connectors (the thin segment model carries no ramp field),
 * and segment add/remove.
 */

private const val CURVE_Y_MAX = 12.0

/** Immutable snapshot of the plot geometry needed to map data↔pixel outside the
 *  draw phase. Scalars only — the live drawing context is mutated each frame. */
private data class PlotGeometry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
) {
    val width get() = right - left
    val height get() = bottom - top
}

/** A draw-nothing [Decoration] that copies `layerBounds` + ranges into Compose
 *  state each frame (guarded so it only recomposes when the geometry changes). */
private class GeometryCaptureDecoration(
    private val yAxisPosition: Axis.Position.Vertical?,
    private val onGeometry: (PlotGeometry) -> Unit,
) : Decoration {
    override fun drawOverLayers(context: CartesianDrawingContext) {
        with(context) {
            val b = layerBounds
            val y = ranges.getYRange(yAxisPosition)
            onGeometry(
                PlotGeometry(
                    left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                    minX = ranges.minX, maxX = ranges.maxX, minY = y.minY, maxY = y.maxY,
                ),
            )
        }
    }
}

// data→pixel (verbatim from Vico's getDrawX/getDrawY; exact for our config) ──────
private fun dataToPxX(g: PlotGeometry, v: Double) =
    g.left + ((v - g.minX) / (g.maxX - g.minX)).toFloat() * g.width

private fun dataToPxY(g: PlotGeometry, v: Double) =
    g.bottom - ((v - g.minY) / (g.maxY - g.minY)).toFloat() * g.height

/** Each segment's end control point in pixels: (cumulative end time, target). */
private fun handleCenters(g: PlotGeometry, targets: List<Float>, times: List<Float>): List<Offset> {
    val out = ArrayList<Offset>(targets.size)
    var t = 0f
    for (i in targets.indices) {
        t += times.getOrElse(i) { 0f }
        out.add(
            Offset(
                dataToPxX(g, t.toDouble()),
                dataToPxY(g, targets[i].toDouble().coerceIn(g.minY, g.maxY)),
            ),
        )
    }
    return out
}

/** Index of the nearest handle within [radiusPx] of [pos], or null. */
private fun nearestHandleIndex(
    g: PlotGeometry,
    targets: List<Float>,
    times: List<Float>,
    pos: Offset,
    radiusPx: Float,
): Int? = handleCenters(g, targets, times)
    .mapIndexed { i, c -> i to (c - pos).getDistance() }
    .filter { it.second <= radiusPx }
    .minByOrNull { it.second }
    ?.first

/** y fixed to 0–12 bar; x follows the data with a 45 s floor so a short recipe
 *  reads and a pure y-drag (the common case) doesn't rescale the x-axis. */
private val ProfileCurveRangeProvider =
    object : CartesianLayerRangeProvider {
        override fun getMinX(minX: Double, maxX: Double, extraStore: ExtraStore) = 0.0
        override fun getMaxX(minX: Double, maxX: Double, extraStore: ExtraStore) =
            maxOf(45.0, maxX)
        override fun getMinY(minY: Double, maxY: Double, extraStore: ExtraStore) = 0.0
        override fun getMaxY(minY: Double, maxY: Double, extraStore: ExtraStore) = CURVE_Y_MAX
    }

@Composable
fun ProfileCurveChart(
    targets: List<Float>,
    times: List<Float>,
    modifier: Modifier = Modifier,
    /** Non-null enables draggable handles: (segmentIndex, newTarget, newTime). */
    onSegmentEdit: ((index: Int, target: Float, time: Float) -> Unit)? = null,
) {
    val tel = CremaTheme.telemetry
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val handleRing = MaterialTheme.colorScheme.surface
    val handleDot = tel.pressure
    val handleActive = MaterialTheme.colorScheme.primary

    val modelProducer = remember { CartesianChartModelProducer() }
    val curveLine = LineCartesianLayer.rememberLine(
        fill = LineCartesianLayer.LineFill.single(Fill(tel.pressure)),
        stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.6.dp),
    )

    // Control points: a fixed origin at the first segment's target, then one point
    // per segment END (cumulative time, target). `targets` / `times` are passed as
    // immutable copies (List.toList()) so a stepper / drag edit changes the
    // LaunchedEffect keys by content and re-pushes the series.
    LaunchedEffect(modelProducer, targets, times) {
        val points = buildList {
            add(0f to (targets.firstOrNull() ?: 0f))
            var t = 0f
            targets.forEachIndexed { i, target ->
                t += times.getOrElse(i) { 0f }
                add(t to target)
            }
        }
        val safe = if (points.size >= 2) points else listOf(0f to 0f, 45f to 0f)
        modelProducer.runTransaction {
            lineModel {
                series(x = safe.map { it.first }, y = safe.map { it.second }, key = "curve")
            }
        }
    }

    // Plot geometry captured from the draw phase (null until the first draw).
    val geometry = remember { mutableStateOf<PlotGeometry?>(null) }
    val captureDeco = remember {
        GeometryCaptureDecoration(yAxisPosition = null) { g ->
            if (g != geometry.value) geometry.value = g
        }
    }
    // Stable list references (captureDeco is stable; emptyList() is a singleton) so
    // the chart isn't rebuilt each time the geometry-capture writes back state.
    val captureList = remember(captureDeco) { listOf(captureDeco) }

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
        // Capture geometry only when interactive (the static preview needs no overlay).
        decorations = if (onSegmentEdit != null) captureList else emptyList(),
    )

    Box(modifier) {
        CartesianChartHost(
            chart = chart,
            modelProducer = modelProducer,
            modifier = Modifier.matchParentSize(),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false),
            animationSpec = null,
            animateIn = false,
        )

        if (onSegmentEdit != null) {
            var draggingIndex by remember { mutableStateOf<Int?>(null) }
            // Fresh values for the long-lived (Unit-keyed) gesture coroutine, so a
            // per-frame edit never restarts it.
            val targetsState = rememberUpdatedState(targets)
            val timesState = rememberUpdatedState(times)
            val editState = rememberUpdatedState(onSegmentEdit)

            Canvas(
                Modifier
                    .matchParentSize()
                    .pointerInput(Unit) {
                        val grabPx = 24.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val g = geometry.value ?: return@awaitEachGesture
                            val idx = nearestHandleIndex(
                                g, targetsState.value, timesState.value, down.position, grabPx,
                            ) ?: return@awaitEachGesture // not on a handle → let the page scroll
                            draggingIndex = idx
                            down.consume()
                            drag(down.id) { change ->
                                change.consume()
                                val gg = geometry.value ?: return@drag
                                val tms = timesState.value
                                val cumTime = gg.minX + (change.position.x - gg.left) / gg.width *
                                    (gg.maxX - gg.minX)
                                val value = gg.minY + (gg.bottom - change.position.y) / gg.height *
                                    (gg.maxY - gg.minY)
                                val startTime = (0 until idx)
                                    .sumOf { tms.getOrElse(it) { 0f }.toDouble() }
                                val timeRaw = (cumTime - startTime).coerceIn(0.5, 120.0)
                                val targetRaw = value.coerceIn(0.0, CURVE_Y_MAX)
                                editState.value?.invoke(
                                    idx,
                                    (targetRaw * 10).roundToInt() / 10f,
                                    (timeRaw * 2).roundToInt() / 2f,
                                )
                            }
                            draggingIndex = null
                        }
                    },
            ) {
                val g = geometry.value ?: return@Canvas
                handleCenters(g, targets, times).forEachIndexed { i, c ->
                    drawCircle(color = handleRing, radius = 7.dp.toPx(), center = c)
                    drawCircle(
                        color = if (i == draggingIndex) handleActive else handleDot,
                        radius = 5.dp.toPx(),
                        center = c,
                    )
                }
            }
        }
    }
}

package coffee.crema.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.components.PhIcon
import coffee.crema.ui.theme.CremaTelemetryColors
import coffee.crema.ui.theme.CremaTheme
import coffee.crema.ui.theme.JetBrainsMono

/** The sample under the finger, or -1 at rest. Read it in the draw phase, or in
 *  one small scope, so a drag never recomposes the screen. An index rather than
 *  a pixel position, so it survives the expand rotation. */
@Stable
class ChartScrubState {
    var index by mutableIntStateOf(-1)
}

/** The expanded overlay's close action. Non-null means this chart is expanded. */
internal val LocalChartExpandClose = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * The History chart plus its readout bar (#298). The bar sits above the plot and
 * shows the time and one coloured value per drawn line for the sample under the
 * finger. Its cells stay laid out while idle, so scrubbing never moves the chart.
 *
 * Gestures. Inline: a horizontal drag scrubs, a tap falls through to
 * [EnlargeableChart], and a vertical drag stays with the page. Expanded: a tap or
 * drag scrubs, and the bar carries the close button. The last position stays on
 * release so it can be read, and clears when the shot changes.
 */
@Composable
fun InspectableShotChart(
    samples: List<TelemetrySample>,
    units: ChartUnits,
    modifier: Modifier = Modifier,
) {
    val scrub = remember(samples) { ChartScrubState() }
    val channels = remember(samples) { drawnChannels(samples) }
    val close = LocalChartExpandClose.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChartReadoutBar(scrub, samples, channels, units, expanded = close != null, Modifier.weight(1f))
            if (close != null) {
                IconButton(onClick = close, modifier = Modifier.size(40.dp)) {
                    PhIcon("x", sizeDp = 18, contentDescription = "Close expanded chart")
                }
            }
        }
        CanvasShotChart(
            samples = samples,
            enabledChannels = HISTORY_CHART_CHANNELS,
            live = false,
            scrub = scrub,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .chartScrubGesture(samples, scrub, expanded = close != null),
        )
    }
}

/** Reads the scrub index here, so a move recomposes this bar and nothing above it. */
@Composable
private fun ChartReadoutBar(
    scrub: ChartScrubState,
    samples: List<TelemetrySample>,
    channels: List<ReadoutChannel>,
    units: ChartUnits,
    expanded: Boolean,
    modifier: Modifier = Modifier,
) {
    val tel = CremaTheme.telemetry
    val timeColor = MaterialTheme.colorScheme.onSurface
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant
    val sample = samples.getOrNull(scrub.index)
    val readout = remember(sample, channels, units) { chartReadout(sample, channels, units) }
    val hint = if (expanded) "Tap or drag to read values" else "Drag sideways to read values"
    val spoken = if (sample == null) {
        hint
    } else {
        (listOf("Time ${readout.time}") + readout.fields.map { "${it.channel.label} ${it.text}" }).joinToString(", ")
    }
    val cellStyle = remember { TextStyle(fontFamily = JetBrainsMono, fontSize = 10.5.sp, lineHeight = 16.sp) }
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .padding(start = 4.dp, bottom = 4.dp)
            .clearAndSetSemantics { contentDescription = spoken },
    ) {
        // The cell count and the bar's width set the rows, never the values, so
        // the height can't change mid-scrub. The web's grid follows the same rule.
        val minCell = with(density) { 54.sp.toDp() }
        val maxCell = with(density) { 84.sp.toDp() }
        val columns = (maxWidth / minCell).toInt().coerceAtLeast(1)
        val cellWidth = (maxWidth / columns).coerceAtMost(maxCell)
        val cells = listOf(readout.time to timeColor) + readout.fields.map { it.text to it.channel.color(tel) }
        Column(Modifier.alpha(if (sample == null) 0f else 1f)) {
            cells.chunked(columns).forEach { row ->
                Row {
                    row.forEach { (text, color) ->
                        Text(text, style = cellStyle, color = color, maxLines = 1, softWrap = false, modifier = Modifier.width(cellWidth))
                    }
                }
            }
        }
        if (sample == null) {
            Text(hint, style = MaterialTheme.typography.labelSmall.copy(lineHeight = 16.sp), color = hintColor, maxLines = 1)
        }
    }
}

/**
 * Scrub gestures for the plot. Inline, nothing is consumed until
 * [classifyChartDrag] says scrub, so a tap still reaches the parent's clickable
 * and a vertical drag still reaches the page's scroll. Expanded, every press
 * scrubs straight away.
 */
private fun Modifier.chartScrubGesture(
    samples: List<TelemetrySample>,
    scrub: ChartScrubState,
    expanded: Boolean,
): Modifier = pointerInput(samples, expanded) {
    val xMaxSec = shotChartXMaxSec(samples, live = false)
    val padLeft = ChartPadLeft.toPx()
    val padRight = ChartPadRight.toPx()
    val scrubTo = { x: Float ->
        val plotWidth = (size.width - padLeft - padRight).coerceAtLeast(1f)
        scrub.index = nearestSampleIndex(samples, plotElapsedMsAt(x, padLeft, plotWidth, xMaxSec))
    }
    val onDrag = { change: PointerInputChange ->
        change.consume()
        scrubTo(change.position.x)
    }
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (expanded) {
            down.consume()
            scrubTo(down.position.x)
        } else {
            var moved = Offset.Zero
            do {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                moved += change.positionChange()
                val kind = classifyChartDrag(moved.x, moved.y, viewConfiguration.touchSlop)
                if (kind == ChartDrag.Scroll) return@awaitEachGesture
                if (kind == ChartDrag.Scrub) onDrag(change)
            } while (kind == ChartDrag.Pending)
        }
        drag(down.id) { onDrag(it) }
    }
}

/** A line's colour; the legend and the readout must agree. */
private fun ReadoutChannel.color(tel: CremaTelemetryColors): Color = when (this) {
    ReadoutChannel.Pressure -> tel.pressure
    ReadoutChannel.Flow -> tel.flow
    ReadoutChannel.Temp -> tel.temp
    ReadoutChannel.Weight -> tel.weight
    ReadoutChannel.ScaleFlow -> tel.weight2
}

/** The legend for [InspectableShotChart]: the lines this shot draws. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShotChartLegend(samples: List<TelemetrySample>, modifier: Modifier = Modifier) {
    val tel = CremaTheme.telemetry
    val channels = remember(samples) { drawnChannels(samples) }
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        channels.forEach { DetailLegend(it.legendLabel, it.color(tel)) }
    }
}

/** One legend entry: a short colour bar and an uppercase label. */
@Composable
fun DetailLegend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width = 8.dp, height = 3.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 0.5.sp, fontWeight = FontWeight.SemiBold),
            color = color,
        )
    }
}

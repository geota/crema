package coffee.crema.ui.screens

import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.convertPressure
import coffee.crema.ui.convertTemp
import coffee.crema.ui.convertWeight
import coffee.crema.ui.fmt
import kotlin.math.abs
import kotlin.math.max

/*
 * The display arithmetic behind the History chart's readout bar (#298): which
 * lines a shot draws, the sample nearest a point in time, and the formatted
 * values for that sample.
 *
 * The web has the same rules in web/src/lib/history/chart-readout.ts. Keep the
 * fields, their order and their formats in step between the two. The web also
 * reads out water and resistance, lines only its History chart draws. The
 * dashed goal lines are targets, not readings, so neither shell reads them out.
 */

/** The fixed History chart channels, as [CanvasShotChart] keys. */
val HISTORY_CHART_CHANNELS = setOf("pressure", "flow", "headTemp", "weight", "weightFlow")

/** A solid History line, in readout order. */
enum class ReadoutChannel(val label: String, val legendLabel: String) {
    Pressure("Pressure", "PRESS"),
    Flow("Flow", "FLOW"),
    Temp("Temp", "TEMP"),
    Weight("Weight", "WEIGHT"),
    ScaleFlow("Scale flow", "SCALE FLOW"),
}

/** The units the readout formats in, from the user's settings. */
data class ChartUnits(val weight: String, val temp: String, val pressure: String)

/** One coloured value in the bar. */
data class ReadoutField(val channel: ReadoutChannel, val value: String, val unit: String) {
    val text: String get() = if (unit.isEmpty()) value else "$value $unit"
}

/** The bar's content at one sample: time first, then a value per line. */
data class ChartReadout(val time: String, val fields: List<ReadoutField>)

/** The placeholder for a line with no value at this sample. */
private const val DASH = "—"

/**
 * The lines this shot draws, in readout order. A scale channel no sample
 * carries is left out, so a shot without a scale has no weight or scale-flow
 * entry. An empty shot draws nothing.
 */
fun drawnChannels(samples: List<TelemetrySample>): List<ReadoutChannel> {
    if (samples.isEmpty()) return emptyList()
    return ReadoutChannel.entries.filter { c ->
        when (c) {
            ReadoutChannel.Weight -> samples.any { it.weight != null }
            ReadoutChannel.ScaleFlow -> samples.any { it.weightFlow != null }
            else -> true
        }
    }
}

/**
 * Index of the sample nearest [elapsedMs], or -1 for an empty list. [samples]
 * is oldest first. A point exactly between two samples takes the earlier one.
 */
fun nearestSampleIndex(samples: List<TelemetrySample>, elapsedMs: Long): Int {
    if (samples.isEmpty()) return -1
    var lo = 0
    var hi = samples.lastIndex
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (samples[mid].elapsedMs < elapsedMs) lo = mid + 1 else hi = mid
    }
    if (lo > 0 && elapsedMs - samples[lo - 1].elapsedMs <= samples[lo].elapsedMs - elapsedMs) return lo - 1
    return lo
}

/** A rate with one decimal; rates have no user-selectable unit. */
private fun rate(v: Float?, unit: String): Pair<String, String> =
    (if (v != null && v.isFinite()) fmt("%.1f", v) else DASH) to unit

private fun measure(s: TelemetrySample, c: ReadoutChannel, units: ChartUnits): Pair<String, String> = when (c) {
    ReadoutChannel.Pressure -> convertPressure(s.pressure, units.pressure).let { it.value to it.unit }
    ReadoutChannel.Flow -> rate(s.flow, "ml/s")
    ReadoutChannel.Temp -> convertTemp(s.headTemp, units.temp).let { it.value to it.unit }
    ReadoutChannel.Weight -> convertWeight(s.weight, units.weight).let { it.value to it.unit }
    ReadoutChannel.ScaleFlow -> rate(s.weightFlow, "g/s")
}

/**
 * The bar's content at [sample]. With no sample, every value is the dash
 * placeholder, which lets the idle bar lay out the same cells as a scrubbing
 * one and keep its height.
 */
fun chartReadout(sample: TelemetrySample?, channels: List<ReadoutChannel>, units: ChartUnits): ChartReadout =
    ChartReadout(
        time = sample?.let { fmt("%.1fs", it.elapsedMs / 1000.0) } ?: DASH,
        fields = channels.map { c ->
            val (value, unit) = sample?.let { measure(it, c, units) } ?: (DASH to "")
            ReadoutField(c, value, unit)
        },
    )

/** What a moving press on the inline chart has turned out to be. */
enum class ChartDrag { Pending, Scrub, Scroll }

/**
 * The inline chart's gesture rule: past the slop, a mostly horizontal drag
 * scrubs and anything else belongs to the page's vertical scroll. The web has
 * the same rule in `classifyDrag`.
 */
fun classifyChartDrag(dx: Float, dy: Float, slop: Float): ChartDrag {
    val ax = abs(dx)
    val ay = abs(dy)
    return when {
        max(ax, ay) < slop -> ChartDrag.Pending
        ax > ay -> ChartDrag.Scrub
        else -> ChartDrag.Scroll
    }
}

/**
 * The shot time, in ms, under a pixel x. Clamped to the plot, so a drag past
 * either edge holds at that edge.
 */
fun plotElapsedMsAt(x: Float, plotLeft: Float, plotWidth: Float, xMaxSec: Float): Long {
    val fraction = ((x - plotLeft) / plotWidth).coerceIn(0f, 1f)
    return (fraction * xMaxSec * 1000f).toLong()
}

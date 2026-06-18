package coffee.crema.ui.compare

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import coffee.crema.ui.TelemetrySample

/*
 * CompareModel — the channel set, per-shot palette, and channel↔sample mapping
 * for the History "Compare" overlay (tablet [CompareDialog] + phone
 * [ComparePhoneScreen]). Ported from compare-handoff/kotlin/CompareModel.kt with
 * the prototype's *synthetic* curve sampler dropped: the chart reads each shot's
 * REAL recorded [TelemetrySample] series (see [channelValue] + CompareChart),
 * and the headline numbers come from [StoredShot] like every other shot readout.
 */

/** The 8 selectable channels — 4 primaries + their dashboard-paired secondaries. */
enum class Channel { Pressure, Resistance, Flow, Water, Temp, MixTemp, Weight, WeightFlow }

/**
 * Per-shot colours, by selection order. Copper first so a 2-shot compare reads
 * "current vs. reference"; then blue / purple / amber / sage (web SHOT_COLORS).
 */
@Composable
fun compareColor(index: Int): Color {
    val palette = listOf(
        MaterialTheme.colorScheme.primary, // copper
        Color(0xFF5E86C2), // blue
        Color(0xFFA98AD0), // soft purple
        Color(0xFFE5A958), // amber
        Color(0xFF7FA86E), // sage
    )
    return palette[index % palette.size]
}

/** Max shots overlaid before the lines stop being distinguishable. */
const val COMPARE_MAX = 5

data class ChannelOption(val channel: Channel, val label: String)
data class ChannelGroup(val icon: String, val options: List<ChannelOption>)

/** Selector layout: 4 families of 2, each sharing the brew-dashboard family icon. */
val CHANNEL_GROUPS = listOf(
    ChannelGroup("gauge", listOf(ChannelOption(Channel.Pressure, "Pressure"), ChannelOption(Channel.Resistance, "Resistance"))),
    ChannelGroup("drop", listOf(ChannelOption(Channel.Flow, "Flow"), ChannelOption(Channel.Water, "Water"))),
    ChannelGroup("thermometer", listOf(ChannelOption(Channel.Temp, "Coffee"), ChannelOption(Channel.MixTemp, "Water"))),
    ChannelGroup("scales", listOf(ChannelOption(Channel.Weight, "Weight"), ChannelOption(Channel.WeightFlow, "Flow"))),
)

/** Native chart-axis unit for a channel (the chart plots raw telemetry units). */
fun unitLabel(ch: Channel): String = when (ch) {
    Channel.Pressure -> "bar"
    Channel.Resistance -> "bar·s²/ml²"
    Channel.Flow -> "ml/s"
    Channel.Water -> "ml"
    Channel.Temp, Channel.MixTemp -> "°C"
    Channel.Weight -> "g"
    Channel.WeightFlow -> "g/s"
}

/** Y-axis floor per channel so a near-zero shot still renders sensible ticks. */
fun axisFloor(ch: Channel): Float = when (ch) {
    Channel.Pressure -> 10f
    Channel.Resistance -> 5f
    Channel.Flow -> 6f
    Channel.Water -> 60f
    Channel.Temp, Channel.MixTemp -> 100f
    Channel.Weight -> 60f
    Channel.WeightFlow -> 3f
}

/**
 * The channel's value for one recorded [TelemetrySample], in the channel's native
 * unit; null when that sample has no reading for the channel (breaks the line).
 * Replaces the prototype's analytic synthesizer — this is the real-data seam.
 */
fun channelValue(s: TelemetrySample, ch: Channel): Float? = when (ch) {
    Channel.Pressure -> s.pressure
    Channel.Resistance -> s.resistance
    Channel.Flow -> s.flow
    Channel.Water -> s.dispensedVolume
    Channel.Temp -> s.headTemp
    Channel.MixTemp -> s.mixTemp
    Channel.Weight -> s.weight
    Channel.WeightFlow -> s.weightFlow
}

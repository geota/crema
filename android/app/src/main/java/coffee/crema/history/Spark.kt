package coffee.crema.history

/*
 * The row chart's spark (#310 P4a, SPEC §4), read back from a row's summary.
 * The core writes it (`de1_domain::shot_summary`); the format is in that
 * module's docs: a version byte, flags, a tick count, then 32 points each of
 * pressure, flow and weight on fixed scales, each 0..254 or 255 for a gap, and
 * the stage ticks' places 0..255.
 */

/** A decoded spark: each channel's 32 points as 0..1 of its scale, null for a gap. */
data class Spark(
    val machine: Boolean,
    val brew: Boolean,
    val hasWeight: Boolean,
    val pressure: List<Float?>,
    val flow: List<Float?>,
    val weight: List<Float?>,
    /** Each stage tick's place in the brew, 0..1. */
    val ticks: List<Float>,
)

const val SPARK_POINTS = 32
private const val SPARK_VERSION = 1
private const val SPARK_GAP = 255
private const val FLAG_MACHINE = 1
private const val FLAG_WEIGHT = 2
private const val FLAG_BREW = 4

/** [encoded] read back, or null when it isn't a spark this build reads. */
fun decodeSpark(encoded: String?): Spark? {
    if (encoded.isNullOrEmpty()) return null
    val bytes = runCatching { java.util.Base64.getDecoder().decode(encoded) }.getOrNull() ?: return null
    if (bytes.size < 3 + 3 * SPARK_POINTS) return null
    fun u(i: Int) = bytes[i].toInt() and 0xFF
    if (u(0) != SPARK_VERSION) return null
    val flags = u(1)
    val tickCount = u(2)
    if (bytes.size < 3 + 3 * SPARK_POINTS + tickCount) return null
    fun channel(from: Int) = List(SPARK_POINTS) { i -> u(from + i).let { if (it == SPARK_GAP) null else it / 254f } }
    return Spark(
        machine = flags and FLAG_MACHINE != 0,
        brew = flags and FLAG_BREW != 0,
        hasWeight = flags and FLAG_WEIGHT != 0,
        pressure = channel(3),
        flow = channel(3 + SPARK_POINTS),
        weight = channel(3 + 2 * SPARK_POINTS),
        ticks = List(tickCount) { i -> u(3 + 3 * SPARK_POINTS + i) / 255f },
    )
}

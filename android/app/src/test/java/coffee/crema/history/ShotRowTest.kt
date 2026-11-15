package coffee.crema.history

import coffee.crema.ui.TelemetrySample
import coffee.crema.ui.coreJson
import org.junit.Test
import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #310 P4a: the History row type. A [ShotRow] is a [StoredShot] without its
 * series, profile and brew series, so the compiler finds every reader of a
 * curve. The two types must keep the same light fields, or a field added to
 * one would be dropped on the way through the other.
 */
class ShotRowTest {
    private val heavy = setOf("samples", "profile", "brewSeries")

    @Test
    fun `a row has every light field of a stored shot, and nothing heavy`() {
        val shot = StoredShot::class.memberProperties.map { it.name }.toSet()
        val row = ShotRow::class.memberProperties.map { it.name }.toSet()
        assertEquals(shot - heavy, row - "summary", "the same light fields")
        assertTrue(heavy.none { it in row }, "a row can't hold a curve, a profile or a brew series")
        assertEquals(ShotMeta::class.memberProperties.map { it.name }.toSet(), shot - heavy, "ShotMeta names them all")
    }

    private fun sample(n: Int) = TelemetrySample(
        elapsedMs = n * 250L, pressure = 9f, flow = 2f, headTemp = 93f, mixTemp = 92f, weight = n * 0.5f, weightFlow = 2f,
        dispensedVolume = 0f, resistance = null, resistanceWeight = null, setHeadTemp = 93f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 1,
    )

    @Test
    fun `a row and its heavy parts make the shot again`() {
        val shot = StoredShot(
            id = "s1", completedAtMs = 5, durationMs = 30_000, yieldG = 36f, doseG = 18f, rating = 4, notes = "n", tags = listOf("t"),
            pinned = true, visualizerId = "v", brewMethod = null, samples = List(10, ::sample),
            profile = HistoryFixtures.profile(1),
        )
        val row = shot.toRow(summaryOf(shot, coreJson))
        assertEquals(shot, row.joined(shot))
        assertEquals(shot.copy(samples = emptyList(), profile = null), row.light())
        // An edit on the row wins over the loaded copy's field.
        assertEquals(5, row.copy(rating = 5).joined(shot).rating)
    }

    /** SPEC §4: the four ways a row with no samples would go wrong quietly. */
    @Test
    fun `a measured row stays measured, by its summary, with no samples in memory`() {
        val measured = StoredShot(id = "m", completedAtMs = 1, durationMs = 1, brewMethod = "espresso", samples = List(10, ::sample)).let { it.toRow(summaryOf(it, coreJson)) }
        assertFalse(measured.isManualLog, "a measured row isn't editable")
        val unknown = measured.copy(summary = measured.summary.copy(sampleCount = null))
        assertFalse(unknown.isManualLog, "unknown is not manual")
        val manual = StoredShot(id = "x", completedAtMs = 1, durationMs = 1, brewMethod = "pourover").let { it.toRow(summaryOf(it, coreJson)) }
        assertTrue(manual.isManualLog)
        // The de-dup signature from the summary equals the one with the series.
        val full = StoredShot(id = "sig", completedAtMs = 1_700_000_000_000, durationMs = 28_000, profileName = "P", yieldG = 3f, samples = List(10, ::sample))
        assertEquals(signatureOfShot(full), rowSignature(full.toRow(summaryOf(full, coreJson))))
        assertEquals(4.5f, rowFinalWeight(full.toRow(summaryOf(full, coreJson))), "the last reading, not the yield")
    }

    @Test
    fun `a row's spark reads back from the core's summary`() {
        val shot = StoredShot(id = "s", completedAtMs = 1, durationMs = 30_000, samples = List(40, ::sample))
        val spark = assertNotNull(decodeSpark(summaryOf(shot, coreJson).spark))
        assertTrue(spark.machine && spark.hasWeight && !spark.brew)
        assertEquals(SPARK_POINTS, spark.pressure.size)
        assertEquals(0.9f, assertNotNull(spark.pressure[10]), 0.01f, "9 bar on the 10 bar scale")
        assertNull(decodeSpark(null))
        assertNull(decodeSpark("not base64!"))
        assertNull(decodeSpark(summaryOf(StoredShot(id = "e", completedAtMs = 1, durationMs = 1), coreJson).spark), "no series, no spark")
    }
}

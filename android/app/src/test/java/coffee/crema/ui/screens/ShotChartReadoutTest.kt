package coffee.crema.ui.screens

import coffee.crema.ui.TelemetrySample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the History readout's lookup, fields and gesture rule (#298). The web's
 *  `chart-readout.vitest.ts` pins the same cases. */
class ShotChartReadoutTest {
    private fun sample(elapsedMs: Long, weight: Float? = null, weightFlow: Float? = null) = TelemetrySample(
        elapsedMs = elapsedMs,
        pressure = 9f,
        flow = 2.14f,
        headTemp = 92.34f,
        mixTemp = 91f,
        weight = weight,
        weightFlow = weightFlow,
        dispensedVolume = 0f,
        resistance = null,
        resistanceWeight = null,
        setHeadTemp = 93f,
        setGroupPressure = 9f,
        setGroupFlow = 0f,
    )

    private val metric = ChartUnits(weight = "g", temp = "C", pressure = "bar")
    private val scaleLines = ReadoutChannel.entries.toList()

    @Test
    fun `an empty series has no nearest sample`() {
        assertEquals(-1, nearestSampleIndex(emptyList(), 100))
    }

    @Test
    fun `nearest clamps to the ends and takes the closer neighbour`() {
        val series = listOf(sample(0), sample(250), sample(500), sample(1000))
        assertEquals(0, nearestSampleIndex(series, -50))
        assertEquals(3, nearestSampleIndex(series, 9000))
        assertEquals(0, nearestSampleIndex(series, 100))
        assertEquals(1, nearestSampleIndex(series, 200))
        assertEquals(3, nearestSampleIndex(series, 800))
        assertEquals(2, nearestSampleIndex(series, 500))
    }

    @Test
    fun `nearest takes the earlier sample at the midpoint`() {
        val series = listOf(sample(0), sample(250), sample(500), sample(1000))
        assertEquals(2, nearestSampleIndex(series, 750))
    }

    @Test
    fun `a shot with no scale leaves the scale lines out`() {
        assertEquals(
            listOf(ReadoutChannel.Pressure, ReadoutChannel.Flow, ReadoutChannel.Temp),
            drawnChannels(listOf(sample(0))),
        )
    }

    @Test
    fun `any sample with scale data adds the scale lines`() {
        assertEquals(scaleLines, drawnChannels(listOf(sample(0), sample(250, 4f, 1.2f))))
    }

    @Test
    fun `an empty shot draws nothing`() {
        assertEquals(emptyList(), drawnChannels(emptyList()))
    }

    @Test
    fun `readout formats time and one value per line, in order`() {
        val r = chartReadout(sample(24_300, 18.44f, 1.87f), scaleLines, metric)
        assertEquals("24.3s", r.time)
        assertEquals(
            listOf("9.0 bar", "2.1 ml/s", "92.3 °C", "18.4 g", "1.9 g/s"),
            r.fields.map { it.text },
        )
    }

    @Test
    fun `readout shows a dash where a drawn line has a gap at this sample`() {
        val r = chartReadout(sample(1000, weight = 3f), listOf(ReadoutChannel.Weight, ReadoutChannel.ScaleFlow), metric)
        assertEquals(listOf("3.0", "—"), r.fields.map { it.value })
    }

    @Test
    fun `the idle readout lays out the same cells`() {
        val idle = chartReadout(null, scaleLines, metric)
        assertEquals("—", idle.time)
        assertEquals(scaleLines, idle.fields.map { it.channel })
        assertTrue(idle.fields.all { it.value == "—" })
    }

    @Test
    fun `labels match the web`() {
        assertEquals("Scale flow", ReadoutChannel.ScaleFlow.label)
        assertEquals("SCALE FLOW", ReadoutChannel.ScaleFlow.legendLabel)
    }

    @Test
    fun `a press inside the slop is undecided`() {
        assertEquals(ChartDrag.Pending, classifyChartDrag(3f, 3f, 8f))
    }

    @Test
    fun `a mostly horizontal drag scrubs, either way`() {
        assertEquals(ChartDrag.Scrub, classifyChartDrag(12f, 4f, 8f))
        assertEquals(ChartDrag.Scrub, classifyChartDrag(-12f, 4f, 8f))
    }

    @Test
    fun `a vertical or diagonal drag stays with the page`() {
        assertEquals(ChartDrag.Scroll, classifyChartDrag(3f, 12f, 8f))
        assertEquals(ChartDrag.Scroll, classifyChartDrag(3f, -12f, 8f))
        assertEquals(ChartDrag.Scroll, classifyChartDrag(10f, 10f, 8f))
    }

    @Test
    fun `a pixel maps to shot time, clamped to the plot`() {
        assertEquals(15_000, plotElapsedMsAt(130f, 30f, 200f, 30f))
        assertEquals(0, plotElapsedMsAt(0f, 30f, 200f, 30f))
        assertEquals(30_000, plotElapsedMsAt(500f, 30f, 200f, 30f))
    }
}

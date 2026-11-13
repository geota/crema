package coffee.crema.ui

import coffee.crema.core.CremaBridge
import coffee.crema.core.PourAverages
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pour's averages (#299): the core's numbers, shown as one line. */
class PourAveragesTest {
    private fun sample(ms: Int, pressure: Float, flow: Float) =
        """{"elapsed":$ms,"sample":{"sampleTime":0,"groupPressure":$pressure,"groupFlow":$flow,"headTemp":92,""" +
            """"mixTemp":92,"setMixTemp":0,"setHeadTemp":0,"setGroupPressure":0,"setGroupFlow":0,"frameNumber":0,"steamTemp":0}}"""

    private fun shotJson(samples: List<String>) =
        """{"formatVersion":3,"id":"shot:a","completedAt":0,"profileName":"P","metadata":{},""" +
            """"record":{"duration":20000,"samples":[${samples.joinToString(",")}]}}"""

    @Test
    fun theCoreAveragesThePourAndLeavesThePreheatOut() {
        val preheat = (0 until 40).map { sample(it * 250, 0f, 0f) }
        val pour = (0..40).map { sample(10_000 + it * 250, 9f, 2f) }
        val raw = CremaBridge().pourAveragesForShot(shotJson(preheat + pour))
        val a = Json.decodeFromString(PourAverages.serializer(), raw)
        assertEquals(PourAverages(pressure = 9f, flow = 2f, headTemp = 92f), a)
        assertEquals("null", CremaBridge().pourAveragesForShot(shotJson(emptyList())))
    }

    @Test
    fun theLineUsesTheChosenUnitsAndOmitsWhatIsMissing() {
        val a = PourAverages(pressure = 9f, flow = 2.04f, headTemp = 92f)
        assertEquals("9.0 bar · 2.0 ml/s · 92.0°C", pourAverageLine(a, "bar", "C"))
        assertEquals("131 psi · 2.0 ml/s · 197.6°F", pourAverageLine(a, "psi", "F"))
        assertEquals("9.0 bar · 2.0 ml/s", pourAverageLine(a.copy(headTemp = null), "bar", "C"))
        assertNull(pourAverageLine(null, "bar", "C"))
    }
}

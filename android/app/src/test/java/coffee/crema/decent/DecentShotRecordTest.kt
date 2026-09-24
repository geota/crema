package coffee.crema.decent

import coffee.crema.core.ShotBean
import coffee.crema.core.ShotMachine
import coffee.crema.history.StoredShot
import coffee.crema.ui.TelemetrySample
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The shell side of the Decent ShotRecord: what [decentShotRecordJson] hands
 * the core. The record's own contract (field mapping, numbers, timestamps) is
 * pinned by the core's golden fixture, not here.
 */
class DecentShotRecordTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val core = FakeDecentCore()

    private fun sample(elapsedMs: Long) = TelemetrySample(
        elapsedMs = elapsedMs, pressure = 8.5f, flow = 2.1f, headTemp = 92.4f, mixTemp = 93.1f,
        weight = 12.5f, weightFlow = 1.9f, dispensedVolume = 20f, resistance = null, resistanceWeight = null,
        setHeadTemp = 92f, setGroupPressure = 9f, setGroupFlow = 0f, frameNumber = 2,
    )

    private val shot = StoredShot(
        id = "shot:0192",
        completedAtMs = 1_790_259_030_000L,
        durationMs = 30_000L,
        yieldG = 36.2f,
        doseG = 18f,
        yieldTargetG = 36f,
        profileName = "Blooming Espresso",
        bean = ShotBean(beanId = "b1", name = "Monarch", roasterName = "Onyx", grinderSetting = "2.4", grinder = "Niche"),
        samples = listOf(sample(0), sample(500)),
        visualizerId = "v-1",
        decentId = "d-1",
    )

    @Test
    fun `sends the upload wire of the shot, with the grinder model and yield target`() {
        decentShotRecordJson(core, json, shot, ShotMachine("6262", "v1.43", "DE1PRO"), "0.0.7", grinderModel = "Niche Zero")
        val wire = json.parseToJsonElement(core.lastShotJson!!).jsonObject
        assertEquals("shot:0192", wire["id"]!!.jsonPrimitive.content)
        assertEquals("Niche Zero", wire["grinderModel"]!!.jsonPrimitive.content)
        assertEquals(36f, wire["yieldTarget"]!!.jsonPrimitive.content.toFloat())
        assertEquals("Niche", wire["bean"]!!.jsonObject["grinder"]!!.jsonPrimitive.content)
        assertEquals("2.4", wire["metadata"]!!.jsonObject["grinderSetting"]!!.jsonPrimitive.content)
        assertEquals(2, wire["record"]!!.jsonObject["samples"]!!.jsonArray.size)
        assertEquals(2, wire["record"]!!.jsonObject["samples"]!!.jsonArray[1].jsonObject["sample"]!!.jsonObject["frameNumber"]!!.jsonPrimitive.content.toInt())
        // The upload variant: local ids stay home.
        assertEquals("null", wire["visualizerId"].toString())
        assertNull(wire["decentId"])
        val machine = json.decodeFromString(ShotMachine.serializer(), core.lastMachineJson!!)
        assertEquals(ShotMachine("6262", "v1.43", "DE1PRO"), machine)
    }

    @Test
    fun `full-resolution samples replace the stored series`() {
        val full = (0 until 40).map { sample(it * 100L) }
        decentShotRecordJson(core, json, shot, ShotMachine("6262"), "x", fullSamples = full)
        val wire = json.parseToJsonElement(core.lastShotJson!!).jsonObject
        assertEquals(40, wire["record"]!!.jsonObject["samples"]!!.jsonArray.size)
        decentShotRecordJson(core, json, shot, ShotMachine("6262"), "x", fullSamples = emptyList())
        assertEquals(2, json.parseToJsonElement(core.lastShotJson!!).jsonObject["record"]!!.jsonObject["samples"]!!.jsonArray.size)
    }

    @Test
    fun `model names are null for zero and unknown registers`() {
        val table = { raw: UInt -> listOf("unknown", "DE1", "DE1+", "DE1PRO").getOrElse(raw.toInt()) { "model $raw" } }
        assertNull(decentModelName(null, table))
        assertNull(decentModelName(0u, table))
        assertNull(decentModelName(42u, table))
        assertEquals("DE1PRO", decentModelName(3u, table))
    }
}

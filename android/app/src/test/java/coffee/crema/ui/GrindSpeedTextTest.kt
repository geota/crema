package coffee.crema.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/** The shot editor's RPM edit (G3): a trailing speed in the grind text is
 *  replaced, or dropped when cleared; never a second one. */
class GrindSpeedTextTest {
    @Test
    fun `an edited speed replaces the one in the text`() {
        assertEquals("15 800rpm", grindWithSpeed("15 1200rpm", 800))
        assertEquals("800rpm", grindWithSpeed("1200rpm", 800))
        assertEquals("15", grindWithSpeed("15 1200rpm", null))
        assertEquals(null, grindWithSpeed("1200rpm", null))
        assertEquals("15 1200rpm", grindWithSpeed("15 1200rpm", 1200))
    }

    @Test
    fun `a grind without a speed is left alone`() {
        assertEquals("480", grindWithSpeed("480", 700))
        assertEquals("18 clicks", grindWithSpeed("18 clicks", 700))
        assertEquals(null, grindWithSpeed(null, 700))
    }

    @Test
    fun `the patch carries the speed and the pull reads it`() {
        val shot = coffee.crema.history.StoredShot(id = "shot:1", completedAtMs = 1, durationMs = 1, grindSetting = "480", grindRpm = 700)
        val inputs = coffee.crema.visualizer.shotPatchInputs(shot, includeNotes = false, defaultPrivacy = null)
        assertEquals(700u, inputs.grindRpm)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(
            coffee.crema.core.visualizerShotPatchJson(
                kotlinx.serialization.json.Json.encodeToString(coffee.crema.core.ShotPatchInputs.serializer(), inputs),
                true,
            )!!,
        ) as kotlinx.serialization.json.JsonObject
        assertEquals("\"480 700rpm\"", body["grinder_setting"].toString())
        val wire = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"v1","clock":1,"duration_ms":1,"bean":{"bean_type":"Guji","grinder_setting":"480 700rpm","grind_rpm":700}}""",
        ) as kotlinx.serialization.json.JsonObject
        val pulled = coffee.crema.visualizer.storedShotFromWire(wire, null, kotlinx.serialization.json.Json)!!
        assertEquals(700, pulled.grindRpm)
        assertEquals("480 700rpm", pulled.bean?.grinderSetting)
    }
}

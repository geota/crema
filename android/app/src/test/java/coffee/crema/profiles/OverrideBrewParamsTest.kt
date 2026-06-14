package coffee.crema.profiles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-JVM tests for the issue-15 Quick-Controls brew override. dose/yield/brew
 * always apply; a pre-infuse override caps the leading segment's time only (the
 * DE1 has no separate pre-infusion setting — it's just frame 0's duration).
 */
class OverrideBrewParamsTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** A minimal CremaProfile JSON with two segments (the first = pre-infuse). */
    private val base = """
        {
          "id": "p1", "source": "custom", "name": "Test",
          "dose": 18.0, "yieldOut": 36.0, "brewTemp": 93.0,
          "preinfuseStepCount": 1,
          "segments": [
            {"name": "preinfuse", "time": 8.0, "target": 3.0},
            {"name": "pour", "time": 25.0, "target": 9.0}
          ]
        }
    """.trimIndent()

    private fun seg(out: String, i: Int) =
        (json.parseToJsonElement(out).jsonObject["segments"] as JsonArray)[i]
            .jsonObject["time"]!!.jsonPrimitive.float

    @Test
    fun `dose, yield and brew-temp are always overridden`() {
        val out = overrideBrewParamsJson(base, dose = 20f, yieldOut = 40f, brewTemp = 95f, json = json)
        val root = json.parseToJsonElement(out).jsonObject
        assertEquals(20f, root["dose"]!!.jsonPrimitive.float)
        assertEquals(40f, root["yieldOut"]!!.jsonPrimitive.float)
        assertEquals(95f, root["brewTemp"]!!.jsonPrimitive.float)
    }

    @Test
    fun `null preinf leaves the segments untouched`() {
        val out = overrideBrewParamsJson(base, dose = 20f, yieldOut = 40f, brewTemp = 95f, preinf = null, json = json)
        assertEquals(8.0f, seg(out, 0))
        assertEquals(25.0f, seg(out, 1))
    }

    @Test
    fun `a preinf override caps only the leading segment's time`() {
        val out = overrideBrewParamsJson(base, dose = 18f, yieldOut = 36f, brewTemp = 93f, preinf = 5f, json = json)
        assertEquals(5.0f, seg(out, 0)) // leading pre-infuse segment recapped
        assertEquals(25.0f, seg(out, 1)) // the pour frame is untouched
    }

    @Test
    fun `a preinf override on a segmentless profile is a no-op, not a crash`() {
        val segmentless = """{"id":"p","dose":18.0,"yieldOut":36.0,"brewTemp":93.0,"segments":[]}"""
        val out = overrideBrewParamsJson(segmentless, dose = 18f, yieldOut = 36f, brewTemp = 93f, preinf = 5f, json = json)
        assertNull(json.parseToJsonElement(out).jsonObject["segments"]!!.jsonArray.firstOrNull())
    }
}

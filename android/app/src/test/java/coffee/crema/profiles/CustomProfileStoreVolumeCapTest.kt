package coffee.crema.profiles

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A user's shot ended at ~42g against a 50g target right after dialling the
 * yield up via Quick Controls on a duplicated profile — a stale
 * DE1-firmware-enforced volume/time cap left over from the profile's
 * original (smaller) yield cut the pour short before the app's own
 * weight-based stop ever got the chance to fire.
 *
 * The fix is deliberately NOT here: [overrideBrewParamsJson] / [quickPresetJson]
 * patch only what Quick Controls is actually editing (dose / yield /
 * brew-temp), full stop — no silent rewrite of `maxTotalVolumeMl` or a
 * segment's `volumeLimitMl`, even when one of them would now conflict with
 * the new target. Reasoning: editing one field should mean exactly that,
 * not "and whatever else the app decided needed reconciling." The real fix
 * is a visible signal instead — `Event::ShotCompleted.endedWithoutTriggeringStop`
 * (core/de1-app) — so a stale cap or profile frame timing that cuts a shot
 * short shows up in the log rather than being silently patched around.
 *
 * These tests pin the NON-mutation as a regression guard: it would be easy
 * to "helpfully" reintroduce a volume rewrite here later.
 */
class CustomProfileStoreVolumeCapTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** A 30ml-capped 18g→30g profile — smaller than the 50g target these
     *  tests dial up to, on purpose: the cap should stay exactly 30. */
    private val baseProfileJson = """
        {"id":"profile:test","source":"custom","name":"Gentle and sweet",
         "notes":"","roast":null,"tags":[],"pinned":false,"lastUsed":null,
         "dose":18.0,"yieldOut":30.0,"brewTemp":90.0,"stopOnWeight":true,
         "autoTare":true,"preinfuseStepCount":1,"maxTotalVolumeMl":30,
         "segments":[
           {"id":"s1","name":"Preinfuse","mode":"pressure","target":2.0,
            "ramp":"fast","time":6.0,"exit":null,"temp":90.0,
            "tempSensor":"coffee","volumeLimitMl":0,"limiter":null},
           {"id":"s2","name":"Pour","mode":"pressure","target":9.0,
            "ramp":"fast","time":30.0,"exit":null,"temp":90.0,
            "tempSensor":"coffee","volumeLimitMl":30,"limiter":null}
         ],"author":"","beverageType":"espresso","tankTemperatureC":0.0}
    """.trimIndent()

    @Test
    fun `override leaves a whole-shot volume cap untouched even when it now conflicts`() {
        val patched = overrideBrewParamsJson(baseProfileJson, dose = 18f, yieldOut = 50f, brewTemp = 90f, json = json)
        val root = json.parseToJsonElement(patched).jsonObject
        assertEquals(30, root["maxTotalVolumeMl"]!!.jsonPrimitive.content.toFloat().toInt())
        assertEquals(50f, root["yieldOut"]!!.jsonPrimitive.content.toFloat())
    }

    @Test
    fun `override leaves per-segment volume caps untouched even when they now conflict`() {
        val patched = overrideBrewParamsJson(baseProfileJson, dose = 18f, yieldOut = 50f, brewTemp = 90f, json = json)
        val segments = json.parseToJsonElement(patched).jsonObject["segments"]!!.jsonArray
        assertEquals(30, segments[1].jsonObject["volumeLimitMl"]!!.jsonPrimitive.content.toFloat().toInt())
        assertEquals(0, segments[0].jsonObject["volumeLimitMl"]!!.jsonPrimitive.content.toFloat().toInt())
    }

    @Test
    fun `quick preset save also leaves a conflicting volume cap untouched`() {
        val patched = quickPresetJson(baseProfileJson, "Gentle and sweet (copy)", dose = 18f, yieldOut = 50f, brewTemp = 90f, json = json)
        val root = json.parseToJsonElement(patched).jsonObject
        assertEquals(30, root["maxTotalVolumeMl"]!!.jsonPrimitive.content.toFloat().toInt())
        assertEquals(50f, root["yieldOut"]!!.jsonPrimitive.content.toFloat())
    }
}

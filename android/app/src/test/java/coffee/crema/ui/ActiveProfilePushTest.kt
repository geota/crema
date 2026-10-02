package coffee.crema.ui

import coffee.crema.profiles.SegmentEdit
import coffee.crema.profiles.overrideBrewParamsJson
import coffee.crema.profiles.patchCremaProfileJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM tests for issue 11's activation path: the core's per-step weight
 * exits follow the ACTIVE profile, so an app restart whose shot start skips the
 * upload on a fingerprint match still hands the core the weights.
 *
 * The native core isn't loadable here, so [toWire] stands in for
 * `cremaProfileToWire` with the one mapping that matters (segment `weight` →
 * step `weight`; the Rust side pins the real conversion in
 * `crema_profile::tests::step_weight_round_trips_through_the_segment`). What
 * this pins is the shell: the push happens with no upload, the weights survive
 * the shell's JSON patches (the Quick-Controls override on the skip path, the
 * editor save), and a cleared / unconvertible profile clears the core.
 */
class ActiveProfilePushTest {
    private val json = Json { ignoreUnknownKeys = true }

    /** A-Flow-like: the Infuse segment leaves at 3.6 g. */
    private val aflow = """
        {
          "id": "aflow", "source": "builtin", "name": "A-Flow", "beverageType": "espresso",
          "dose": 18.0, "yieldOut": 36.0, "brewTemp": 93.0, "preinfuseStepCount": 1,
          "segments": [
            {"id": "s1", "name": "Fill", "time": 8.0, "target": 3.0},
            {"id": "s2", "name": "Infuse", "time": 60.0, "target": 3.0, "weight": 3.6},
            {"id": "s3", "name": "Pour", "time": 30.0, "target": 9.0}
          ]
        }
    """.trimIndent()

    /** Stand-in for `cremaProfileToWire`: segments → steps, weight carried. */
    private fun toWire(cremaJson: String): String {
        val root = json.parseToJsonElement(cremaJson).jsonObject
        val steps = root["segments"]!!.jsonArray.map { seg ->
            buildJsonObject {
                put("name", seg.jsonObject["name"] ?: JsonPrimitive(""))
                put("weight", seg.jsonObject["weight"] ?: JsonNull)
            }
        }
        return JsonObject(mapOf("title" to root["name"]!!, "steps" to JsonArray(steps))).toString()
    }

    private fun weightsOf(wireJson: String?): List<Float?>? = wireJson?.let {
        json.parseToJsonElement(it).jsonObject["steps"]!!.jsonArray
            .map { s -> s.jsonObject["weight"]?.jsonPrimitive?.floatOrNull }
    }

    /** The core's activation input, recorded. */
    private class FakeCore {
        val activations = mutableListOf<String?>()
        var uploads = 0
    }

    @Test
    fun `the fingerprint-skip path still hands the core the step weights`() {
        // App restart: a fresh core, the DE1 still holds A-Flow.
        val core = FakeCore()
        // Startup restore / shot start: pushStopTargets → the active profile.
        pushActiveProfile(aflow, ::toWire, { core.activations += it })
        // The shot start's effective profile (a Quick-Controls dose/yield
        // override baked in) still matches what the DE1 holds → skip.
        val effective = overrideBrewParamsJson(aflow, 18f, 36f, 93f, json = json)
        val skipped = shouldSkipProfileUpload("fp", "fp", de1Ready = true)
        if (!skipped) core.uploads++
        assertTrue(skipped)
        assertEquals(0, core.uploads, "no upload on the skip path")
        assertEquals(listOf(null, 3.6f, null), weightsOf(core.activations.single()))
        // …and the override never drops the weight either.
        assertEquals(listOf(null, 3.6f, null), weightsOf(toWire(effective)))
    }

    @Test
    fun `an edited profile re-selected replaces the weights`() {
        val core = FakeCore()
        pushActiveProfile(aflow, ::toWire, { core.activations += it })
        // Editor save (keeps the per-segment weight it doesn't edit) then the
        // save re-selects the profile → a second activation.
        val segs = listOf(
            SegmentEdit("Fill", null, null, 3f, 8f, null),
            SegmentEdit("Infuse", null, null, 3f, 45f, null),
        )
        val edited = patchCremaProfileJson(
            baseJson = aflow, name = "A-Flow (mine)", roast = null, tags = emptyList(),
            pinned = false, notes = "", author = "", beverageType = null,
            dose = 18f, yieldOut = 36f, brewTemp = 93f, maxTotalVolumeMl = 0,
            preinfuseStepCount = 1, tankTemperatureC = 0f, segments = segs, json = json,
        )
        pushActiveProfile(edited, ::toWire, { core.activations += it })
        assertEquals(listOf(null, 3.6f), weightsOf(core.activations.last()))
    }

    @Test
    fun `no active profile clears the core`() {
        val core = FakeCore()
        pushActiveProfile(aflow, ::toWire, { core.activations += it })
        pushActiveProfile(null, ::toWire, { core.activations += it })
        assertNull(core.activations.last())
    }

    @Test
    fun `an unconvertible profile clears rather than keeps the previous weights`() {
        val core = FakeCore()
        val logs = mutableListOf<String>()
        pushActiveProfile(aflow, ::toWire, { core.activations += it })
        pushActiveProfile("{}", { error("bad profile") }, { core.activations += it }, { logs += it })
        assertNull(core.activations.last())
        assertTrue(logs.single().contains("bad profile"))
    }
}

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

    private fun patchedGrind(shot: coffee.crema.history.StoredShot): String {
        val inputs = coffee.crema.visualizer.shotPatchInputs(shot, includeNotes = false, defaultPrivacy = null)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(
            coffee.crema.core.visualizerShotPatchJson(
                kotlinx.serialization.json.Json.encodeToString(coffee.crema.core.ShotPatchInputs.serializer(), inputs),
                true,
            )!!,
        ) as kotlinx.serialization.json.JsonObject
        return (body["grinder_setting"] as kotlinx.serialization.json.JsonPrimitive).content
    }

    /** Review #555 M1 (a): a pulled shot's grind lives on its bean; an RPM
     *  edit replaces the speed in it, and Visualizer gets one speed. */
    @Test
    fun `an rpm edit on a pulled shot replaces the speed on its bean grind`() {
        val pulled = coffee.crema.history.StoredShot(
            id = "shot:remote:v1", completedAtMs = 1, durationMs = 1, grindRpm = 700,
            bean = coffee.crema.core.ShotBean(name = "Guji", grinderSetting = "480 700rpm"),
        )
        val edited = pulled.edit(800)
        assertEquals("480 800rpm", edited.grindSetting)
        assertEquals("480 800rpm", patchedGrind(edited))
        // Cleared: the dial stays, the speed goes.
        assertEquals("480", pulled.edit(null).grindSetting)
        assertEquals("480 700rpm", pulled.edit(null).bean?.grinderSetting, "a snapshot that names a dial is kept")
    }

    /** As web's `editShotRpm` (core `rpm_edit_grind`). */
    private fun coffee.crema.history.StoredShot.edit(rpm: Int?): coffee.crema.history.StoredShot {
        val e = rpmEditGrind(this, rpm)
        return copy(grindRpm = rpm, grindSetting = e.grinderSetting, bean = rpmEditBean(bean, e))
    }

    /** REVIEW-555 recheck: clearing the speed of a pulled shot whose grind is
     *  only "1200rpm" sticks; the snapshot's speed-only grind goes too. */
    @Test
    fun `clearing a speed-only grind drops the snapshot's grind too`() {
        val pulled = coffee.crema.history.StoredShot(
            id = "shot:remote:v1", completedAtMs = 1, durationMs = 1, grindRpm = 1200,
            bean = coffee.crema.core.ShotBean(name = "Guji", grinderSetting = "1200rpm"),
        )
        val cleared = pulled.edit(null)
        assertEquals(null, cleared.grindSetting)
        assertEquals(null, cleared.bean?.grinderSetting)
        assertEquals("Guji", cleared.bean?.name, "the rest of the snapshot stays")
        assertEquals(null, cleared.grindRpm)
        // Its own speed-only grind and the snapshot's go together.
        val both = pulled.copy(grindSetting = "1200rpm").edit(null)
        assertEquals(null, both.grindSetting)
        assertEquals(null, both.bean?.grinderSetting)
        // A new speed keeps the snapshot and sets the shot's own grind.
        val faster = pulled.edit(1300)
        assertEquals("1300rpm", faster.grindSetting)
        assertEquals("1200rpm", faster.bean?.grinderSetting)
    }

    /** Review #555 M1 (b): a grind retyped with a new speed carries it, so
     *  Visualizer never gets "15 1300rpm 1200rpm". */
    @Test
    fun `a retyped grind with a speed is the shot's speed`() {
        val imported = coffee.crema.history.StoredShot(id = "shot:1", completedAtMs = 1, durationMs = 1, grindSetting = "15 1200rpm", grindRpm = 1200)
        val edited = imported.copy(grindSetting = "15 1300rpm", grindRpm = grindEditSpeed("15 1300rpm", imported.grindRpm))
        assertEquals(1300, edited.grindRpm)
        assertEquals("15 1300rpm", patchedGrind(edited))
        // No speed in the new text: the shot's speed stays.
        assertEquals(1200, grindEditSpeed("15", 1200))
        // The core never sends two speeds, whatever the fields say (review #555 M1).
        assertEquals("15 1300rpm", patchedGrind(imported.copy(grindSetting = "15 1200rpm", grindRpm = 1300)))
    }
}


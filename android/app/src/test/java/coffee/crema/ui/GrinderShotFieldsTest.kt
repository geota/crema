package coffee.crema.ui

import coffee.crema.history.StoredShot
import coffee.crema.visualizer.storedShotFromBackupJson
import coffee.crema.visualizer.wireShotJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Review #554: a web backup's shot with the connected grinder's fields
 * (`grinderObserved`, `grindRpm`) restored here and saved again keeps them
 * whole, a newer build's extra fields included; a row without them reads as
 * none and writes none.
 */
class GrinderShotFieldsTest {
    private val observed = """{"source":"LastGrind","model":"bookoo-motto-80","firmware":"1.3.11.1020","setting":"480","grindRpm":700,"feedRpm":40,"grindMs":9800,"endedBeforeShotMs":30000,"preset":-1,"humidityPct":30,"fromTheFuture":{"x":1}}"""
    private val line = """{"formatVersion":3,"id":"shot:g1","completedAt":1700000000000,"metadata":{"grinderSetting":"480"},"record":{"duration":30000,"samples":[]},"grinderObserved":$observed,"grindRpm":700}"""

    private fun parse(s: String) = coreJson.parseToJsonElement(s).jsonObject

    @Test
    fun `a backup's grinder fields survive a restore and a re-save`() {
        val shot = storedShotFromBackupJson(parse(line), coreJson)!!
        assertEquals(700, shot.grindRpm)
        assertEquals(parse(observed), shot.grinderObserved, "kept whole, unknown fields included")
        val out = wireShotJson(shot, forBackup = true)
        assertEquals(parse(observed), out["grinderObserved"])
        assertEquals("700", out["grindRpm"].toString())
        // Through the history row as Android stores it.
        val row = coreJson.decodeFromString(StoredShot.serializer(), coreJson.encodeToString(StoredShot.serializer(), shot))
        assertEquals(shot.grinderObserved, row.grinderObserved)
        assertEquals(shot.grindRpm, row.grindRpm)
        // The upload carries the speed (Visualizer's "480 700rpm"), not the observation.
        val upload = wireShotJson(shot)
        assertEquals("700", upload["grindRpm"].toString())
        assertNull(upload["grinderObserved"])
    }

    @Test
    fun `a row without them reads and writes none`() {
        val shot = storedShotFromBackupJson(parse("""{"id":"shot:1","completedAt":1,"durationMs":1}"""), coreJson)!!
        assertNull(shot.grinderObserved)
        assertNull(shot.grindRpm)
        val out: JsonObject = wireShotJson(shot, forBackup = true)
        assertNull(out["grinderObserved"])
        assertNull(out["grindRpm"])
    }

}

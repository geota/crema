package coffee.crema.visualizer

import coffee.crema.core.ShotBean
import coffee.crema.history.StoredShot
import coffee.crema.ui.TelemetrySample
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-JVM round-trip for the whole-app backup shot path (issue 01): Android's
 * flat [StoredShot] -> core-shape backup JSON ([wireShotJson]) -> flat
 * [StoredShot] ([storedShotFromBackupJson]).
 *
 * Guards the data-loss bug it fixes: backup export used to serialize the FLAT
 * shape, which the core `shots:[StoredShot]` envelope parser rejects (no
 * formatVersion/completedAt/record) — failing the *entire* backup once any shot
 * existed — and restore decoded the core shape with the flat serializer, hitting
 * MissingFieldException and silently dropping every shot.
 */
class BackupShotRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true }

    // Runtime UByte: a literal `7.toUByte()` trips the Kotlin const-eval ICE
    // (the "UByte const-eval gotcha"); routing through a fn keeps it non-const.
    private fun ub(n: Int): UByte = n.toUByte()

    private fun sample(t: Long, p: Float, w: Float?) = TelemetrySample(
        elapsedMs = t, pressure = p, flow = 1.8f, headTemp = 92.4f, mixTemp = 93.1f,
        weight = w, weightFlow = null, dispensedVolume = 12f, resistance = null,
        resistanceWeight = null, setHeadTemp = 92f, setGroupPressure = 9f, setGroupFlow = 0f,
    )

    @Test
    fun `flat shot survives the core-shape backup round-trip`() {
        val original = StoredShot(
            id = "shot:1790000000000",
            completedAtMs = 1_790_000_000_000,
            durationMs = 28_500,
            yieldG = 36.2f,
            doseG = 18.0f,
            grindSetting = 4.5f,
            profileName = "Blooming espresso",
            bean = ShotBean(
                beanId = "bean:b3", name = "Monarch", roasterName = "Onyx",
                roastedOn = "2026-05-20", roastLevel = ub(7), tags = listOf("daily"),
            ),
            rating = 4,
            notes = "tasty",
            samples = listOf(sample(0, 2f, null), sample(1000, 9.1f, 12.3f), sample(2000, 8.4f, 30.5f)),
            visualizerId = "viz:abc",
        )

        // Export must emit the core StoredShot wire shape (formatVersion/completedAt/record).
        // forBackup=true is what backupBundleJson passes (preserves visualizerId + grind).
        val wire = wireShotJson(original, forBackup = true)
        assertEquals(3, wire["formatVersion"]!!.jsonPrimitive.int)
        assertTrue(wire["record"]!!.jsonObject["samples"]!!.jsonArray.isNotEmpty())

        val restored = storedShotFromBackupJson(wire, json)!!
        assertEquals(original.id, restored.id)
        assertEquals(original.completedAtMs, restored.completedAtMs)
        assertEquals(original.durationMs, restored.durationMs)
        assertEquals(original.yieldG!!, restored.yieldG!!, 1e-3f)
        assertEquals(original.doseG!!, restored.doseG!!, 1e-3f)
        assertEquals(original.grindSetting!!, restored.grindSetting!!, 1e-3f)
        assertEquals(original.profileName, restored.profileName)
        assertEquals(original.rating, restored.rating)
        assertEquals(original.notes, restored.notes)
        assertEquals(original.visualizerId, restored.visualizerId)
        val ob = original.bean!!
        val rb = restored.bean!!
        assertEquals(ob.name, rb.name)
        assertEquals(ob.roasterName, rb.roasterName)
        assertEquals(ob.roastLevel, rb.roastLevel)
        assertEquals(original.samples.size, restored.samples.size)
        assertEquals(30.5f, restored.samples.last().weight!!, 1e-3f)
        assertNull(restored.samples.first().weight)
        // peaks are re-derived from the samples on restore (the core stores none).
        assertEquals(9.1f, restored.peakPressure!!, 1e-3f)
    }

    @Test
    fun `null rating round-trips to unrated and a beanless shot stays beanless`() {
        val s = StoredShot(id = "shot:2", completedAtMs = 1, durationMs = 1, rating = null, bean = null)
        val restored = storedShotFromBackupJson(wireShotJson(s), json)!!
        assertNull(restored.rating)
        assertNull(restored.bean)
    }
}

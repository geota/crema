package coffee.crema.visualizer

import coffee.crema.core.ShotBean
import coffee.crema.core.ShotPatchInputs
import coffee.crema.history.StoredShot
import coffee.crema.history.effectiveGrindSetting
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
            grindSetting = "4.5",
            profileName = "Blooming espresso",
            bean = ShotBean(
                beanId = "bean:b3", name = "Monarch", roasterName = "Onyx",
                roastedOn = "2026-05-20", roastLevel = ub(7), tags = listOf("daily"),
            ),
            rating = 4,
            notes = "tasty",
            barista = "Ana",
            drinker = "Ben",
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
        assertEquals(original.grindSetting, restored.grindSetting)
        assertEquals(original.profileName, restored.profileName)
        assertEquals(original.rating, restored.rating)
        assertEquals(original.notes, restored.notes)
        assertEquals("Ana", restored.barista)
        assertEquals("Ben", restored.drinker)
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
    fun `a pin rides a backup, and an old backup reads as not pinned`() {
        val pinned = StoredShot(id = "shot:p", completedAtMs = 1, durationMs = 1, pinned = true)
        assertEquals(true, storedShotFromBackupJson(wireShotJson(pinned, forBackup = true), json)!!.pinned)
        val plain = StoredShot(id = "shot:q", completedAtMs = 1, durationMs = 1)
        assertEquals(null, wireShotJson(plain, forBackup = true)["pinned"], "an unpinned row's bytes don't change")
        assertEquals(false, storedShotFromBackupJson(wireShotJson(plain, forBackup = true), json)!!.pinned)
        assertEquals(null, wireShotJson(pinned)["pinned"], "never uploaded")
    }

    @Test
    fun `the Visualizer version survives a backup, through the core, and stays out of uploads`() {
        val s = StoredShot(id = "shot:v", completedAtMs = 1, durationMs = 1, visualizerUpdatedAt = 1_700_000_000)
        assertEquals(1_700_000_000L, storedShotFromBackupJson(wireShotJson(s, forBackup = true), json)!!.visualizerUpdatedAt)
        assertEquals(null, wireShotJson(s)["visualizerUpdatedAt"], "never uploaded")
    }

    @Test
    fun `a shot's own grinder model survives a backup round trip and wins over the Settings grinder`() {
        val s = StoredShot(id = "shot:g", completedAtMs = 1, durationMs = 1, grinderModel = "Niche Zero")
        val restored = storedShotFromBackupJson(wireShotJson(s, forBackup = true), json)!!
        assertEquals("Niche Zero", restored.grinderModel)
        assertEquals("Niche Zero", wireShotJson(s, grinderModel = "EK43")["grinderModel"]!!.jsonPrimitive.content)
        // A shot captured here carries none: the Settings grinder fills in.
        val local = StoredShot(id = "shot:h", completedAtMs = 1, durationMs = 1)
        assertEquals("EK43", wireShotJson(local, grinderModel = "EK43")["grinderModel"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the edit PATCH carries the barista only after a barista edit, empty once cleared (final review 06 #1)`() {
        val cleared = StoredShot(id = "shot:p", completedAtMs = 1, durationMs = 1, barista = null)
        // Any other edit leaves the remote barista alone (null = omitted by the core).
        assertNull(shotPatchInputs(cleared, includeNotes = true, defaultPrivacy = "unlisted", settingsGrinderModel = null).barista)
        // A barista edit that cleared it still reaches Visualizer as "".
        assertEquals(
            "",
            shotPatchInputs(cleared, includeNotes = true, defaultPrivacy = "unlisted", settingsGrinderModel = null, sendBarista = true).barista,
        )
        val set = cleared.copy(barista = " Ana ", grinderModel = "Niche Zero")
        val inputs = shotPatchInputs(set, includeNotes = false, defaultPrivacy = "unlisted", settingsGrinderModel = "EK43", sendBarista = true)
        assertEquals("Ana", inputs.barista)
        assertEquals("Niche Zero", inputs.grinderModel)
        assertNull(inputs.notes)
        assertNull(shotPatchInputs(set, includeNotes = false, defaultPrivacy = "unlisted", settingsGrinderModel = null).barista)
    }

    @Test
    fun `a barista-only PATCH leaves every journal field out`() {
        val shot = StoredShot(
            id = "shot:j", completedAtMs = 1, durationMs = 1, rating = 4, notes = "juicy", barista = "Ana", grinderModel = "Niche Zero",
        )
        val inputs = shotPatchInputs(shot, includeNotes = true, defaultPrivacy = "unlisted", settingsGrinderModel = "EK43", sendBarista = true, sendJournal = false)
        assertEquals(ShotPatchInputs(barista = "Ana"), inputs)
    }

    @Test
    fun `null rating round-trips to unrated and a beanless shot stays beanless`() {
        val s = StoredShot(id = "shot:2", completedAtMs = 1, durationMs = 1, rating = null, bean = null)
        val restored = storedShotFromBackupJson(wireShotJson(s), json)!!
        assertNull(restored.rating)
        assertNull(restored.bean)
    }

    @Test
    fun `decentId, machine and yield target survive the backup round trip`() {
        val original = StoredShot(
            id = "shot:3", completedAtMs = 5, durationMs = 30_000, yieldTargetG = 36f,
            decentId = "d-77", machineSerial = "6262", machineFirmware = "v1.43 build 1352", machineModel = "DE1PRO",
        )
        val wire = wireShotJson(original, forBackup = true)
        assertEquals("d-77", wire["decentId"]!!.jsonPrimitive.content)
        val machine = wire["machine"]!!.jsonObject
        assertEquals("6262", machine["serialNumber"]!!.jsonPrimitive.content)
        assertEquals("DE1PRO", machine["model"]!!.jsonPrimitive.content)
        val restored = storedShotFromBackupJson(wire, json)!!
        assertEquals(original.decentId, restored.decentId)
        assertEquals(original.machineSerial, restored.machineSerial)
        assertEquals(original.machineFirmware, restored.machineFirmware)
        assertEquals(original.machineModel, restored.machineModel)
        assertEquals(36f, restored.yieldTargetG!!, 1e-3f)
        // The whole row round-trips — nothing else changed on the way.
        assertEquals(original, restored)
    }

    @Test
    fun `the upload wire omits decentId but keeps the machine, and a machineless shot has none`() {
        val s = StoredShot(id = "shot:4", completedAtMs = 5, durationMs = 30_000, decentId = "d-1", machineSerial = "6262")
        val up = wireShotJson(s)
        assertNull(up["decentId"])
        assertEquals("6262", up["machine"]!!.jsonObject["serialNumber"]!!.jsonPrimitive.content)
        val bare = wireShotJson(StoredShot(id = "shot:5", completedAtMs = 5, durationMs = 1), forBackup = true)
        assertNull(bare["machine"])
        assertNull(bare["decentId"])
        assertNull(storedShotFromBackupJson(bare, json)!!.machineSerial)
    }

    /** REVIEW-FINAL M1: an older row's Float grind reads as its digits, text survives, and the backup writes text. */
    @Test
    fun `a shot's grind is text in the store, the backup and the restore`() {
        // An older history row (row_json / history.json): the grind was a Float.
        fun row(grind: String) = """{"id":"s","completedAtMs":1,"durationMs":1,"grindSetting":$grind}"""
        assertEquals("15", json.decodeFromString(StoredShot.serializer(), row("15.0")).grindSetting)
        assertEquals("4.2", json.decodeFromString(StoredShot.serializer(), row("4.2")).grindSetting)
        assertEquals("2.25", json.decodeFromString(StoredShot.serializer(), row("2.25")).grindSetting)
        assertNull(json.decodeFromString(StoredShot.serializer(), row("null")).grindSetting)
        for (grind in listOf("2.1.3", "18 clicks", "15", "0.05")) {
            val shot = StoredShot(id = "s", completedAtMs = 1, durationMs = 1, grindSetting = grind)
            // The store writes it as text and reads it back.
            val stored = json.encodeToString(StoredShot.serializer(), shot)
            assertTrue(stored.contains("\"grindSetting\":\"$grind\""), stored)
            assertEquals(grind, json.decodeFromString(StoredShot.serializer(), stored).grindSetting)
            // The backup writes it as text, and the restore (and capture) adapter keeps it.
            val wire = wireShotJson(shot, forBackup = true)
            assertEquals(grind, wire["metadata"]!!.jsonObject["grinderSetting"]!!.jsonPrimitive.content)
            assertTrue(wire["metadata"]!!.jsonObject["grinderSetting"]!!.jsonPrimitive.isString)
            assertEquals(grind, storedShotFromBackupJson(wire, json)!!.grindSetting)
            // What History shows and the upload sends.
            assertEquals(grind, shot.effectiveGrindSetting)
            assertEquals(grind, wireShotJson(shot)["metadata"]!!.jsonObject["grinderSetting"]!!.jsonPrimitive.content)
        }
        // A core-shape shot whose grind is a JSON number (an older writer): its digits.
        val numeric = wireShotJson(StoredShot(id = "n", completedAtMs = 1, durationMs = 1), forBackup = true).let { w ->
            kotlinx.serialization.json.JsonObject(w + ("metadata" to kotlinx.serialization.json.JsonObject(w["metadata"]!!.jsonObject + ("grinderSetting" to kotlinx.serialization.json.JsonPrimitive(15.0)))))
        }
        assertEquals("15", storedShotFromBackupJson(numeric, json)!!.grindSetting)
    }

    /** A crafted number grind (a backup or a pulled row) is kept as its literal, never expanded. */
    @Test
    fun `a huge-exponent grind number isn't expanded`() {
        fun row(grind: String) = """{"id":"s","completedAtMs":1,"durationMs":1,"grindSetting":$grind}"""
        assertEquals("1e999999999", json.decodeFromString(StoredShot.serializer(), row("1e999999999")).grindSetting)
        assertEquals("1E-999999999", json.decodeFromString(StoredShot.serializer(), row("1E-999999999")).grindSetting)
        val long = "1" + "0".repeat(40)
        assertEquals(long, json.decodeFromString(StoredShot.serializer(), row(long)).grindSetting, "too many digits: as written")
        assertEquals("1500", json.decodeFromString(StoredShot.serializer(), row("1.5e3")).grindSetting, "a sane exponent is spelled out")
        assertEquals("1e999999999", coffee.crema.beans.grindTextOf(kotlinx.serialization.json.JsonUnquotedLiteral("1e999999999")))
    }
}

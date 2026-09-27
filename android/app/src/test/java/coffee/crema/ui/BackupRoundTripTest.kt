package coffee.crema.ui

import coffee.crema.history.StoredShot
import coffee.crema.visualizer.wireShotJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit test for the pure backup line-parser [parseBackupRecords] (review #07).
 * Backup/restore is data-loss-adjacent: the parser dispatches each tagged JSONL
 * line to its record bucket, and a missed `when` branch or a thrown malformed
 * line would silently drop a whole section. This pins the dispatch + the
 * skip-don't-throw tolerance without spinning up the ViewModel (the
 * wipe/merge apply stays VM-side).
 */
class BackupRoundTripTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun line(kind: String, body: JsonObject = JsonObject(emptyMap())): String =
        json.encodeToString(JsonObject.serializer(), JsonObject(body + ("kind" to JsonPrimitive(kind))))

    /** A core-shape shot line, the way `backupBundleJson` emits it (issue 01). */
    private fun shotLine(id: String): String {
        val shot = StoredShot(id = id, completedAtMs = 1_700_000_000_000, durationMs = 28_000, rating = 4)
        val wire = wireShotJson(shot, forBackup = true)
        return json.encodeToString(JsonObject.serializer(), JsonObject(wire + ("kind" to JsonPrimitive("shot"))))
    }

    @Test
    fun `dispatches every tagged line to its record bucket`() {
        val bundle = listOf(
            line("crema-backup/v1"),
            line("settings", buildJsonObject { put("common", buildJsonObject { }); put("_shell", "android") }),
            line("profileMeta", buildJsonObject { put("pinned", buildJsonArray { }); put("hiddenBuiltins", buildJsonArray { }) }),
            line("maintenance"),
            line("visualizerPrefs"),
            line("profile", buildJsonObject { put("id", "p1"); put("name", "Custom") }),
            shotLine("shot:t1"),
        ).joinToString("\n")

        val parsed = parseBackupRecords(bundle, json, 0L)

        assertTrue(parsed.sawHeader)
        assertEquals(1, parsed.profiles.size)
        assertEquals(1, parsed.shots.size)
        assertEquals("shot:t1", parsed.shots.first().id)
        assertNotNull(parsed.common) // settings → common, filled over AppPrefs defaults
        assertNotNull(parsed.profileMeta)
        assertNotNull(parsed.maintenance)
        assertNotNull(parsed.visualizerPrefs)
    }

    @Test
    fun `skips a malformed JSONL line without dropping the rest`() {
        val bundle = listOf(
            line("crema-backup/v1"),
            "{ this is not valid json",
            line("profile", buildJsonObject { put("id", "p1") }),
            shotLine("shot:t2"),
        ).joinToString("\n")

        val parsed = parseBackupRecords(bundle, json, 0L)

        assertTrue(parsed.sawHeader)
        assertEquals(1, parsed.profiles.size)
        assertEquals(1, parsed.shots.size)
    }

    @Test
    fun `tolerates a malformed bean line by skipping it`() {
        val bundle = listOf(
            line("crema-backup/v1"),
            line("bean", buildJsonObject { put("id", "b1") }), // missing required fields → skipped, not thrown
            line("profile", buildJsonObject { put("id", "p1") }),
        ).joinToString("\n")

        val parsed = parseBackupRecords(bundle, json, 0L)

        assertEquals(0, parsed.beans.size)
        assertEquals(1, parsed.profiles.size)
    }

    @Test
    fun `reports no header for a non-Crema file`() {
        val parsed = parseBackupRecords(line("profile", buildJsonObject { put("id", "p1") }), json, 0L)
        assertFalse(parsed.sawHeader)
    }

    @Test
    fun `recipe lines restore with their credit and recipeMeta rides along`() {
        val recipe = buildJsonObject {
            put("id", "recipe:copy")
            put("name", "1 Cup V60 (copy)")
            put("method", "pourover")
            put("doseG", 15.0)
            put("waterG", 250.0)
            put("tempC", 100.0)
            put("steps", buildJsonArray { })
            put("notes", kotlinx.serialization.json.JsonNull)
            put("favourite", false)
            put("createdAt", 1)
            put("updatedAt", 1)
            put("deletedAt", kotlinx.serialization.json.JsonNull)
            put("credit", "Adapted from James Hoffmann — A Better 1 Cup V60 Technique (2022)")
            put("sourceUrl", "https://www.youtube.com/watch?v=1oB1oDrDkHM")
        }
        val bundle = listOf(
            line("crema-backup/v1"),
            line("recipe", recipe),
            line("recipeMeta", buildJsonObject {
                put("defaults", buildJsonObject { put("pourover", "recipe:copy") })
                put("hiddenBuiltins", buildJsonArray { })
            }),
        ).joinToString("\n")
        val parsed = parseBackupRecords(bundle, json, 0L)
        assertEquals(1, parsed.recipes.size)
        val r = parsed.recipes.first()
        assertEquals("Adapted from James Hoffmann — A Better 1 Cup V60 Technique (2022)", r.credit)
        assertEquals("https://www.youtube.com/watch?v=1oB1oDrDkHM", r.sourceUrl)
        assertNotNull(parsed.recipeMeta)
        // A bundle from before recipes were backed up restores none.
        assertTrue(parseBackupRecords(line("crema-backup/v1"), json, 0L).recipes.isEmpty())
    }

    @Test
    fun `brewMethod lines restore custom methods and shots keep the label snapshot`() {
        val orb = buildJsonObject {
            put("id", "custom:01920000-0000-7000-8000-00000000abcd")
            put("label", "ORB")
            put("style", "percolation")
            put("icon", "funnel")
            put("createdAt", 1)
            put("updatedAt", 2)
        }
        val gone = buildJsonObject {
            put("id", "custom:gone")
            put("label", "Old brewer")
            put("style", "immersion")
            put("createdAt", 1)
            put("updatedAt", 3)
            put("deletedAt", 3)
        }
        val spoof = buildJsonObject {
            put("id", "pourover")
            put("label", "Spoof")
            put("createdAt", 1)
            put("updatedAt", 1)
        }
        val shot = StoredShot(
            id = "shot:orb",
            completedAtMs = 1_700_000_000_000,
            durationMs = 0,
            doseG = 15f,
            brewMethod = "custom:gone",
            brewMethodLabel = "Old brewer",
            waterG = 250f,
            brewTempC = 94f,
            recipeName = "Mine",
        )
        val wire = wireShotJson(shot, forBackup = true)
        val bundle = listOf(
            line("crema-backup/v1"),
            line("brewMethod", orb),
            line("brewMethod", gone),
            line("brewMethod", spoof),
            json.encodeToString(JsonObject.serializer(), JsonObject(wire + ("kind" to JsonPrimitive("shot")))),
        ).joinToString("\n")
        val parsed = parseBackupRecords(bundle, json, 0L)
        assertEquals(listOf("ORB", "Old brewer"), parsed.customMethods.map { it.label })
        assertEquals(3L, parsed.customMethods[1].deletedAt)
        assertEquals(coffee.crema.core.BrewMethodStyle.Immersion, parsed.customMethods[1].style)
        val back = parsed.shots.single()
        assertEquals("custom:gone", back.brewMethod)
        assertEquals("Old brewer", back.brewMethodLabel)
        assertEquals(250f, back.waterG)
        assertEquals(94f, back.brewTempC)
        assertEquals("Mine", back.recipeName)
        // A bundle from before custom methods restores none.
        assertTrue(parseBackupRecords(line("crema-backup/v1"), json, 0L).customMethods.isEmpty())
    }
}

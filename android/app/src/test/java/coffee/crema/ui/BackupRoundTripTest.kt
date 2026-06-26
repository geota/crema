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
}

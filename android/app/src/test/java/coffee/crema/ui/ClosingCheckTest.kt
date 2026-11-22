package coffee.crema.ui

import coffee.crema.core.QuickSaveGrindUndo
import coffee.crema.core.ShotBagMoveInput
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The #488 closing check: the import menus say which rule applies, in the
 * web's words (PLAN 62), and the core's marks decode from JSON written
 * before them, an older build's or a backup's (PLAN 63).
 */
class ClosingCheckTest {
    /** PLAN 62: the same two sentences as web's `#lib/bean/import-rules.ts`. */
    @Test
    fun `the import rules are the web's words, and each menu says its own`() {
        val web = File("../../web/src/lib/bean/import-rules.ts").readText()
        // The web file splits each string over a line; compare its literals joined.
        val literals = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(web).map { it.groupValues[1] }.toList()
        assertTrue(MERGE_RULE in literals, "web: $literals")
        assertTrue(LIBRARY_IMPORT_RULE in literals, "web: $literals")
        assertTrue("only adds" in LIBRARY_IMPORT_RULE)
        assertTrue("even when the backup's copy is newer" in MERGE_RULE)
        assertTrue(BEANS_IMPORT_BODY.endsWith(LIBRARY_IMPORT_RULE), "Beans → Import says the library rule")
    }

    /** PLAN 63: JSON without the marks decodes, as false. */
    @Test
    fun `the core's marks decode from JSON written before them`() {
        val undo = coreJson.decodeFromString(QuickSaveGrindUndo.serializer(), """{"beanId":"bean:1","grinderId":"g"}""")
        assertNull(undo.grindsWereNone, "absent: false to the core")
        val move = coreJson.decodeFromString(ShotBagMoveInput.serializer(), """{"remote":false,"replay":false,"mirrorRow":false}""")
        assertNull(move.mirrorCopy)
        // A backup's shot line from before the mark reads as unmarked.
        val shot = coffee.crema.visualizer.storedShotFromBackupJson(
            coreJson.parseToJsonElement("""{"id":"shot:1","completedAt":1,"durationMs":1}""") as kotlinx.serialization.json.JsonObject,
            coreJson,
        )
        assertEquals(false, shot?.mirrorCopy)
    }
}

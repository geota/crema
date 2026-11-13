package coffee.crema.ui

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Saving a backup to a picked document (#391 review): only a document this
 * save opened may be deleted on a failure, and a provider that refuses "wt"
 * gets "w".
 */
class CopyBackupToDocumentTest {
    @get:Rule val tmp = TemporaryFolder()

    private val backupFile by lazy { tmp.newFile("b.crema.zip").apply { writeBytes(ByteArray(10_000) { (it % 251).toByte() }) } }
    private fun backup() = backupFile

    @Test
    fun `a whole copy is kept`() {
        val out = ByteArrayOutputStream()
        var deleted = false
        assertTrue(copyBackupToDocument(backup(), open = { out }, delete = { deleted = true }))
        assertContentEquals(backup().readBytes(), out.toByteArray())
        assertFalse(deleted)
    }

    @Test
    fun `a document that never opened is the user's, so it isn't deleted`() {
        var deleted = false
        assertFalse(copyBackupToDocument(backup(), open = { throw IOException("refused") }, delete = { deleted = true }))
        assertFalse(deleted, "nothing was written to it")
        assertFalse(copyBackupToDocument(backup(), open = { null }, delete = { deleted = true }))
        assertFalse(deleted, "a null stream wrote nothing either")
    }

    @Test
    fun `a provider that refuses wt gets w`() {
        val modes = mutableListOf<String>()
        val out = ByteArrayOutputStream()
        assertTrue(
            copyBackupToDocument(backup(), open = { mode -> modes += mode; if (mode == "wt") throw IllegalArgumentException("no wt") else out }, delete = {}),
        )
        assertEquals(listOf("wt", "w"), modes)
        assertContentEquals(backup().readBytes(), out.toByteArray())
    }

    @Test
    fun `a copy that fails part way is deleted`() {
        var deleted = false
        val failing = object : OutputStream() {
            var n = 0
            override fun write(b: Int) {
                if (++n > 100) throw IOException("disk full")
            }
        }
        assertFalse(copyBackupToDocument(backup(), open = { failing }, delete = { deleted = true }))
        assertTrue(deleted, "a half-written document isn't left under a failure notice")
    }
}

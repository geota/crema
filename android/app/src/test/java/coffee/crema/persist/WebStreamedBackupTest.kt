package coffee.crema.persist

import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A backup the web builds one line at a time (#310 P2w) restores here: every
 * streamed entry has a data descriptor, and `ZipInputStream` refuses one on a
 * stored entry, so the web deflates its photos too (#386 review). The fixture
 * is written by the web's `streamBackup` (`stream.vitest.ts`, with
 * `CREMA_WRITE_FIXTURE=1`), and read with the accept rule of the restore.
 */
class WebStreamedBackupTest {
    /** The web test's photo: random-looking bytes from a fixed seed (`stream.vitest.ts`). */
    private fun fixturePhoto(length: Int = 4096): ByteArray {
        var x = 20_261_108
        return ByteArray(length) {
            x = (x * 1_103_515_245 + 12_345) and 0x7fffffff
            ((x ushr 16) and 0xff).toByte()
        }
    }

    @Test
    fun aWebStreamedBackupWithAPhotoReadsWithTheRestoresReader() {
        val stream = javaClass.classLoader!!.getResourceAsStream("backup/web-streamed-photo.crema.zip")!!
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(stream).use { zin ->
            readZipCapped(
                zin,
                accept = { it == "backup.jsonl" || it == "crema.jsonl" || it.startsWith("images/") },
            ) { name, bytes -> entries[name] = bytes }
        }
        assertEquals(listOf("backup.jsonl", "images/bean-1"), entries.keys.toList())
        val jsonl = entries.getValue("backup.jsonl").decodeToString()
        assertTrue(jsonl.startsWith("""{"""") && "\"kind\":\"crema-backup/v1\"" in jsonl.lineSequence().first(), jsonl.take(200))
        assertTrue(jsonl.lines().any { "\"kind\":\"shot\"" in it })
        assertTrue(entries.getValue("images/bean-1").contentEquals(fixturePhoto()), "the photo, whole")
    }
}

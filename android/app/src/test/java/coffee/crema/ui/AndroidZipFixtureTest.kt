package coffee.crema.ui

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The cross-shell fixture `android-zipoutputstream.crema.zip`, which the web's
 * streaming restore reads (#394), comes from the app's own writer,
 * [zipBackupBytes], so it can't drift from what Android writes (REVIEW-394).
 * Each entry's name, method, flags, sizes, CRC and bytes are compared; times
 * aren't. Rewrite it with `CREMA_WRITE_FIXTURE=1`.
 */
class AndroidZipFixtureTest {
    private val dir = File("../../core/de1-domain/tests/fixtures/backup")
    private val fixture = File(dir, "android-zipoutputstream.crema.zip")

    /** xorshift bytes with all three zip signatures in them: deflate stores them as they are. */
    private fun photo(): ByteArray {
        val out = ByteArray(8192)
        var x = 0x2545F4914F6CDD1DL
        for (i in out.indices) {
            x = x xor (x shl 13); x = x xor (x ushr 7); x = x xor (x shl 17)
            out[i] = x.toByte()
        }
        listOf(byteArrayOf(0x50, 0x4b, 3, 4), byteArrayOf(0x50, 0x4b, 7, 8), byteArrayOf(0x50, 0x4b, 1, 2))
            .forEachIndexed { s, sig -> sig.copyInto(out, listOf(100, 2000, 5000)[s]) }
        return out
    }

    private fun written(): ByteArray =
        zipBackupBytes(File(dir, "android-backup.crema.jsonl").readText(), sequenceOf("bean:android-1" to photo()))

    private data class Entry(val name: String, val method: Int, val flags: Int, val compressed: Long, val size: Long, val crc: Long)

    /** The central directory's entries. */
    private fun entries(zip: ByteArray): List<Entry> {
        val b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = (zip.size - 22 downTo 0).first { b.getInt(it) == 0x06054b50 }
        var at = b.getInt(eocd + 16)
        return List(b.getShort(eocd + 10).toInt()) {
            check(b.getInt(at) == 0x02014b50)
            val nameLen = b.getShort(at + 28).toInt() and 0xffff
            val e = Entry(
                name = String(zip, at + 46, nameLen),
                method = b.getShort(at + 10).toInt() and 0xffff,
                flags = b.getShort(at + 8).toInt() and 0xffff,
                compressed = b.getInt(at + 20).toLong() and 0xffffffffL,
                size = b.getInt(at + 24).toLong() and 0xffffffffL,
                crc = b.getInt(at + 16).toLong() and 0xffffffffL,
            )
            at += 46 + nameLen + (b.getShort(at + 30).toInt() and 0xffff) + (b.getShort(at + 32).toInt() and 0xffff)
            e
        }
    }

    private fun contents(zip: ByteArray): Map<String, ByteArray> = buildMap {
        ZipInputStream(zip.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                put(e.name, z.readBytes())
            }
        }
    }

    @Test
    fun theFixtureIsWhatTheAppWrites() {
        val now = written()
        if (System.getenv("CREMA_WRITE_FIXTURE") == "1") fixture.writeBytes(now)
        val kept = fixture.readBytes()
        val hint = "rerun with CREMA_WRITE_FIXTURE=1 to rewrite ${fixture.name}"
        assertEquals(entries(now), entries(kept), hint)
        val a = contents(now)
        val b = contents(kept)
        assertEquals(a.keys, b.keys, hint)
        for (name in a.keys) assertContentEquals(a.getValue(name), b.getValue(name), "$name: $hint")
        // Every entry has a data descriptor, and the photo's signatures sit in its compressed bytes.
        assertTrue(entries(kept).all { it.flags and 0x08 != 0 && it.method == 8 }, "${entries(kept)}")
        val window = String(photo().copyOfRange(96, 5008), Charsets.ISO_8859_1)
        assertTrue(window in String(kept, Charsets.ISO_8859_1), "the photo's signatures are in its compressed bytes")
    }
}

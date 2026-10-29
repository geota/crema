package coffee.crema.persist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Review 05 P3-2: oversized files and zip bombs fail cleanly instead of OOM. */
class ImportLimitsTest {
    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun walk(zip: ByteArray, entryCap: Long = 1024, totalCap: Long = 2048, count: Int = 3): Map<String, ByteArray> {
        val got = LinkedHashMap<String, ByteArray>()
        readZipCapped(ZipInputStream(ByteArrayInputStream(zip)), { it.endsWith(".jsonl") || it.startsWith("images/") },
            entryCap, totalCap, count) { n, b -> got[n] = b }
        return got
    }

    @Test
    fun boundedReadReturnsSmallStreamsAndRefusesLargeOnes() {
        assertArrayEquals(byteArrayOf(1, 2, 3), ByteArrayInputStream(byteArrayOf(1, 2, 3)).readBytesCapped(3))
        val e = assertThrows(ImportTooLargeException::class.java) {
            ByteArrayInputStream(ByteArray(20_000)).readBytesCapped(10_000, "\"big.json\"")
        }
        assertEquals("\"big.json\" is larger than 1 MB, too large to import.", e.message)
    }

    @Test
    fun zipReadsOnlyWantedEntries() {
        val got = walk(zipOf("backup.jsonl" to "x".toByteArray(), "junk.bin" to ByteArray(50_000)))
        assertEquals(listOf("backup.jsonl"), got.keys.toList())
    }

    @Test
    fun zipBombEntryStopsAtTheCap() {
        // 1 MiB of zeros compresses to ~1 KiB — a tiny file that unpacks huge.
        val bomb = zipOf("backup.jsonl" to ByteArray(1024 * 1024))
        assertTrue(bomb.size < 4096)
        val e = assertThrows(ImportTooLargeException::class.java) { walk(bomb) }
        assertTrue(e.message!!.contains("backup.jsonl"))
    }

    @Test
    fun zipTotalAndCountCaps() {
        val heavy = zipOf("images/a" to ByteArray(1000), "images/b" to ByteArray(1000), "images/c" to ByteArray(1000))
        assertTrue(assertThrows(ImportTooLargeException::class.java) { walk(heavy) }.message!!.contains("unpacks to more than"))
        val many = zipOf(*(1..4).map { "images/$it" to ByteArray(1) }.toTypedArray())
        assertTrue(assertThrows(ImportTooLargeException::class.java) { walk(many) }.message!!.contains("more than 3 files"))
    }
}

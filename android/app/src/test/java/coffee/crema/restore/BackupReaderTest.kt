package coffee.crema.restore

import coffee.crema.core.BackupImporter
import coffee.crema.core.BackupScanner
import coffee.crema.core.backupMaxLineBytes
import coffee.crema.core.scanBackupBytes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The streaming restore's reader (#310 P2a-b, SPEC §7), the twin of the web's
 * `zip-reader.vitest.ts`: the three writers' zips read whole, signatures and
 * all; the CRC, the end record and the limits hold; lines split as the core
 * splits a whole file.
 */
class BackupReaderTest {
    private val fixtures = File("../../core/de1-domain/tests/fixtures/backup")
    private val jsonl = File(fixtures, "android-backup.crema.jsonl").readBytes()
    private val dir = Files.createTempDirectory("backup-reader").toFile()
    private val writers = listOf("android-zipoutputstream", "fflate-zipsync", "fflate-zipdeflate")
        .map { it to File(fixtures, "$it.crema.zip") }

    /** The photo every fixture holds: xorshift bytes with all three signatures in them. */
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

    private fun copy(bytes: ByteArray, name: String = "b.zip") = File(dir, name).apply { writeBytes(bytes) }

    private fun linesOf(p: PreparedBackup): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        p.forEachLine { if (it is BackupLinePiece.Line) out += it.bytes else error("oversized") }
        return out
    }

    private fun refusal(block: () -> Unit): String = assertFailsWith<BackupReadException> { block() }.message.orEmpty()

    @Test
    fun eachWritersZipReadsWholeSignaturesAndAll() {
        val want = String(jsonl).split('\n').map { it.toByteArray() }
        for ((name, zip) in writers) {
            val bytes = zip.readBytes()
            // Stored deflate blocks (or a stored entry): the photo's bytes, signatures and all, sit in the zip as they are.
            val window = String(photo().copyOfRange(96, 5008), Charsets.ISO_8859_1)
            assertTrue(window in String(bytes, Charsets.ISO_8859_1), "$name holds the three signatures in an entry's data")
            val p = prepareBackupFile(zip, name, File(dir, "stage-$name"))
            assertContentEquals(photo(), p.photos.getValue("bean:android-1").readBytes(), name)
            assertEquals(want.map { String(it) }, linesOf(p).map { String(it) }, name)
            val whole = Json.parseToJsonElement(scanBackupBytes(jsonl, listOf("bean:android-1"))).jsonObject
            assertEquals(whole, Json.parseToJsonElement(p.manifestJson).jsonObject, name)
        }
    }

    @Test
    fun aCorruptedByteFailsTheChecksum() {
        for ((name, zip) in writers) {
            val bytes = zip.readBytes()
            // Inside the photo's data, past its local header and any deflate
            // block header, wherever the writer put the photo's entry.
            val data = photoDataStart(bytes)
            bytes[data + 300] = (bytes[data + 300].toInt() xor 0x01).toByte()
            assertTrue("checksum" in refusal { prepareBackupFile(copy(bytes), name, File(dir, "s")) }, name)
        }
    }

    @Test
    fun aTruncatedZipIsRefusedWhereverItWasCut() {
        val bytes = File(fixtures, "fflate-zipdeflate.crema.zip").readBytes()
        // Where the second entry's local header starts: a cut there reads as complete to ZipInputStream.
        val boundary = (30 until bytes.size - 4).first { i ->
            i > 30 && bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte() &&
                String(bytes, i + 30, "backup.jsonl".length, Charsets.ISO_8859_1) == "backup.jsonl"
        }
        for (keep in listOf(10, 100, bytes.size / 2, boundary, bytes.size - 30, bytes.size - 1)) {
            refusal { prepareBackupFile(copy(bytes.copyOf(keep)), "cut", File(dir, "s")) }
        }
    }

    @Test
    fun aZipThatStopsEarlyInsideAnIntactFileIsRefused() {
        // A later local header that reads as the central directory: ZipInputStream
        // ends there quietly, and the photo after it would just be missing.
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("backup.jsonl")); z.write(jsonl); z.closeEntry()
            z.putNextEntry(ZipEntry("images/bean:android-1")); z.write(photo()); z.closeEntry()
        }
        val bytes = out.toByteArray()
        val photoLocal = (4 until bytes.size - 4).first { i ->
            bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte()
        }
        bytes[photoLocal + 2] = 1; bytes[photoLocal + 3] = 2
        assertTrue("cut short" in refusal { prepareBackupFile(copy(bytes), "early", File(dir, "s")) })
    }

    @Test
    fun theLimitsHold() {
        val zip = File(fixtures, "android-zipoutputstream.crema.zip")
        assertTrue("restores backups up to" in refusal { prepareBackupFile(zip, "z", File(dir, "s"), BackupReadLimits(maxFileBytes = 1000)) })
        assertTrue("more than 1 files" in refusal { prepareBackupFile(zip, "z", File(dir, "s"), BackupReadLimits(maxEntries = 1)) })
        assertTrue("unpacks to more than" in refusal { prepareBackupFile(zip, "z", File(dir, "s"), BackupReadLimits(maxPhotoBytes = 1000)) })
        assertTrue("too large to restore" in refusal { prepareBackupFile(zip, "z", File(dir, "s"), BackupReadLimits(maxInflatedBytes = 10_000)) })
    }

    @Test
    fun aZipBombStopsAtTheLimitWhateverItSaysItHolds() {
        // 64 MiB of zeros deflates to about 64 KiB; the limit stops the inflate long before the end.
        val out = java.io.ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("images/bean:1"))
            val zeros = ByteArray(1024 * 1024)
            repeat(64) { z.write(zeros) }
            z.closeEntry()
            z.putNextEntry(ZipEntry("backup.jsonl")); z.write(jsonl); z.closeEntry()
        }
        assertTrue(out.size() < 1024 * 1024)
        val limits = BackupReadLimits(maxPhotoBytes = 4L * 1024 * 1024)
        assertTrue("unpacks to more than 4 MB" in refusal { prepareBackupFile(copy(out.toByteArray()), "bomb", File(dir, "s"), limits) })
        // Skipped entries count too: a bomb under a name the restore ignores.
        val ignored = java.io.ByteArrayOutputStream()
        ZipOutputStream(ignored).use { z ->
            z.putNextEntry(ZipEntry("junk.bin"))
            val zeros = ByteArray(1024 * 1024)
            repeat(64) { z.write(zeros) }
            z.closeEntry()
            z.putNextEntry(ZipEntry("backup.jsonl")); z.write(jsonl); z.closeEntry()
        }
        refusal { prepareBackupFile(copy(ignored.toByteArray()), "bomb", File(dir, "s"), BackupReadLimits(maxInflatedBytes = 4L * 1024 * 1024)) }
    }

    @Test
    fun encryptionZip64AndRepeatsAreRefused() {
        val zip = File(fixtures, "fflate-zipsync.crema.zip").readBytes()
        val encrypted = zip.copyOf().also { it[6] = (it[6].toInt() or 1).toByte() }
        assertTrue("encrypted" in refusal { prepareBackupFile(copy(encrypted), "e", File(dir, "s")) })
        val zip64 = zip.copyOf().also { val eocd = it.size - 22; it[eocd + 10] = 0xff.toByte(); it[eocd + 11] = 0xff.toByte() }
        assertTrue("zip64" in refusal { prepareBackupFile(copy(zip64), "z", File(dir, "s")) })
        // ZipOutputStream won't write a name twice: patch a second entry's name instead.
        val two = java.io.ByteArrayOutputStream()
        ZipOutputStream(two).use { z ->
            z.putNextEntry(ZipEntry("backup.jsonl")); z.write(jsonl); z.closeEntry()
            z.putNextEntry(ZipEntry("backup.jsonX")); z.write(jsonl); z.closeEntry()
        }
        val raw = two.toByteArray()
        val s = String(raw, Charsets.ISO_8859_1).replace("backup.jsonX", "backup.jsonl").toByteArray(Charsets.ISO_8859_1)
        assertTrue("twice" in refusal { prepareBackupFile(copy(s), "t", File(dir, "s")) })
    }

    @Test
    fun aBareJsonlFileStreamsTheSameAsItsZip() {
        val plain = prepareBackupFile(copy(jsonl, "b.jsonl"), "plain", File(dir, "s"))
        val zipped = prepareBackupFile(File(fixtures, "fflate-zipdeflate.crema.zip"), "zip", File(dir, "s2"))
        val a = Json.parseToJsonElement(plain.manifestJson).jsonObject - "photos"
        val b = Json.parseToJsonElement(zipped.manifestJson).jsonObject - "photos"
        assertEquals(a, b)
    }

    // ── Lines ────────────────────────────────────────────────────────────────

    /** An input that gives at most [chunk] bytes per read. */
    private fun chunked(bytes: ByteArray, chunk: Int): InputStream = object : InputStream() {
        val inner = ByteArrayInputStream(bytes)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, chunk))
    }

    private fun lines(text: String, chunk: Int, max: Int = 1 shl 20): List<String> {
        val out = mutableListOf<String>()
        forEachBackupLine(chunked(text.toByteArray(), chunk), max) {
            out += if (it is BackupLinePiece.Line) String(it.bytes) else "<oversized>"
        }
        return out
    }

    @Test
    fun linesSplitAsTheCoreSplitsAWholeFileHoweverTheBytesArrive() {
        for (text in listOf("a\nbb\n\nccc", "a\nbb\n", "\n", "", "x", "\n\n\nlast")) {
            for (chunk in listOf(1, 2, 3, 1000)) assertEquals(text.split('\n'), lines(text, chunk), "$text by $chunk")
        }
        assertEquals(String(jsonl).split('\n'), lines(String(jsonl), 97))
    }

    @Test
    fun aLinePastTheLimitIsSkippedAndOneAtItKept() {
        val text = "${"a".repeat(10)}\n${"b".repeat(11)}\nshort\n${"c".repeat(25)}"
        for (chunk in listOf(1, 4, 100)) {
            assertEquals(listOf("a".repeat(10), "<oversized>", "short", "<oversized>"), lines(text, chunk, 10), "by $chunk")
        }
    }

    @Test
    fun aWhitespaceLinePastTheLimitIsBlankNotOversized() {
        val text = "x\n${" \t\r".repeat(10)}\n${"  ".repeat(4)}y${" ".repeat(10)}\nz"
        for (chunk in listOf(1, 3, 100)) assertEquals(listOf("x", "", "<oversized>", "z"), lines(text, chunk, 8), "by $chunk")
    }

    @Test
    fun aCopyStopsAtTheFileLimitAndLeavesNothing() {
        val dest = File(dir, "copy.crema")
        assertTrue("larger than" in refusal { copyBackupTo(ByteArrayInputStream(ByteArray(5000)), dest, BackupReadLimits(maxFileBytes = 4096)) })
        assertFalse(dest.exists())
        assertContentEquals(jsonl, copyBackupTo(ByteArrayInputStream(jsonl), dest).readBytes())
    }

    // ── The FFI pair (#393's line-at-a-time core, bound here) ───────────────

    @Test
    fun theScannerAndImporterOverFfiGiveTheWholeFilesAnswers() {
        val scanner = BackupScanner()
        val importer = BackupImporter()
        val records = mutableListOf<JsonObject>()
        for (line in String(jsonl).split('\n')) {
            scanner.pushLine(line.toByteArray())
            importer.pushLine(line.toByteArray())?.let { records += Json.parseToJsonElement(it).jsonObject }
        }
        assertEquals(
            Json.parseToJsonElement(scanBackupBytes(jsonl, emptyList())),
            Json.parseToJsonElement(scanner.finish(emptyList())),
        )
        assertTrue(importer.hasHeader())
        assertEquals(setOf("shot", "bean", "roaster", "recipe", "brewMethod", "profile", "singleton"), records.map { it["line"].toString().trim('"') }.toSet())
        assertFailsWith<Exception> { scanner.finish(emptyList()) }
        assertEquals(16 * 1024 * 1024, backupMaxLineBytes().toInt())
        scanner.destroy(); importer.destroy()
    }

    /** Where the photo entry's data starts: after its local header, its name and its extra field. */
    private fun photoDataStart(bytes: ByteArray): Int {
        val name = "images/bean:android-1"
        val at = (0 until bytes.size - 30).first { i ->
            bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == 3.toByte() && bytes[i + 3] == 4.toByte() &&
                String(bytes, i + 30, name.length, Charsets.ISO_8859_1) == name
        }
        fun u16(o: Int) = (bytes[o].toInt() and 0xff) or ((bytes[o + 1].toInt() and 0xff) shl 8)
        return at + 30 + u16(at + 26) + u16(at + 28)
    }

    /**
     * The web's real streamed backup, kept current by `stream.vitest.ts`, reads
     * through the new reader too, not only the old one (REVIEW-395).
     */
    @Test
    fun theRealStreamedBackupsReadThroughThisReader() {
        // Each is kept current by its writer's own test: web by stream.vitest.ts,
        // Android by BackupStreamTest.
        for (name in listOf("web-streamed-photo", "android-streamed-photo")) {
            val zip = File("src/test/resources/backup/$name.crema.zip")
            val p = prepareBackupFile(zip, name, File(dir, "stage-$name"))
            assertTrue(p.photos.isNotEmpty(), "$name: its photo is staged")
            assertTrue(linesOf(p).isNotEmpty(), "$name: its lines read")
        }
    }
}

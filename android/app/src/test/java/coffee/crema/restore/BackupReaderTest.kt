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
        // A later local header that reads as the central directory: a reader
        // that walks local headers ends there quietly, and the photo after it
        // would just be missing. Read by the directory, its header is damage.
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
        assertTrue("local header" in refusal { prepareBackupFile(copy(bytes), "early", File(dir, "s")) })
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

    // ── REVIEW-395's parity items (#396) ─────────────────────────────────────

    @Test
    fun theLinesReadAgainMustBeTheBytesScanned() {
        // Plain JSONL: the run's read is refused at its end when the file changed.
        val plainFile = copy(jsonl, "again.jsonl")
        val plain = prepareBackupFile(plainFile, "plain", File(dir, "s"))
        linesOf(plain) // the same bytes read again are fine
        plainFile.writeBytes(String(jsonl).replace("android-1", "android-2").toByteArray())
        assertTrue("changed since it was checked" in refusal { linesOf(plain) })
        assertTrue("changed since it was checked" in refusal {
            RestoreRecords.read(plain, Json { ignoreUnknownKeys = true }, 0L)
        })
        // A zip: its CRCs are the new file's own, so they can't tell; the scan's can.
        val zipFile = copy(File(fixtures, "android-zipoutputstream.crema.zip").readBytes(), "again.zip")
        val zipped = prepareBackupFile(zipFile, "zip", File(dir, "s2"))
        linesOf(zipped)
        val other = java.io.ByteArrayOutputStream()
        ZipOutputStream(other).use { z ->
            z.putNextEntry(ZipEntry("backup.jsonl")); z.write(String(jsonl).replace("android-1", "android-2").toByteArray()); z.closeEntry()
        }
        zipFile.writeBytes(other.toByteArray())
        assertTrue("changed since it was checked" in refusal { linesOf(zipped) })
        // A zip with no backup.jsonl at all now: no member to check, so empty
        // records unless the walk itself refuses (REVIEW-410 should-fix 1).
        for (members in listOf(listOf("images/bean:android-1" to photo()), listOf("crema.jsonl" to jsonl))) {
            val without = java.io.ByteArrayOutputStream()
            ZipOutputStream(without).use { z -> for ((n, b) in members) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
            zipFile.writeBytes(without.toByteArray())
            assertTrue("changed since it was checked" in refusal { linesOf(zipped) }, members.first().first)
        }
    }

    /**
     * A pre-#391 Android backup (`ZipOutputStream`) saved over a larger, older
     * one by a provider that doesn't truncate on "w": the new zip, then the old
     * zip's tail, whose end record ends the file (REVIEW-410 should-fix 3).
     */
    private fun savedOverALargerZip(newer: ByteArray): ByteArray {
        val old = java.io.ByteArrayOutputStream()
        ZipOutputStream(old).use { z ->
            z.putNextEntry(ZipEntry("backup.jsonl")); z.write(String(jsonl).replace("android-1", "android-old").toByteArray()); z.write(jsonl); z.closeEntry()
            z.putNextEntry(ZipEntry("images/bean:android-old")); repeat(40) { z.write(photo()) }; z.closeEntry()
        }
        val bytes = old.toByteArray()
        assertTrue(bytes.size > newer.size + 1000)
        newer.copyInto(bytes)
        return bytes
    }

    private val newerZips = listOf(
        "android-zipoutputstream" to File(fixtures, "android-zipoutputstream.crema.zip"),
        "android-streamed-photo" to File("../../web/src/lib/backup/fixtures/android-streamed-photo.crema.zip"),
    ) + writers.drop(1)

    @Test
    fun aBackupSavedOverALargerZipReadsAsTheNewBackupExactly() {
        for ((name, zip) in newerZips) {
            val newer = zip.readBytes()
            val bytes = savedOverALargerZip(newer)
            // The end record's directory is the old zip's, and doesn't read.
            assertFailsWith<BackupReadException>(name) { walkBackupZip(copy(bytes, "over.zip"), BackupReadLimits()) { _, _, _ -> } }
            val want = prepareBackupFile(copy(newer, "new.zip"), name, File(dir, "want-$name"))
            val over = copy(bytes, "over.zip")
            val got = prepareBackupFile(over, name, File(dir, "got-$name"))
            assertEquals(want.manifestJson, got.manifestJson, name)
            assertEquals(linesOf(want).map { String(it) }, linesOf(got).map { String(it) }, name)
            assertEquals(want.photos.keys, got.photos.keys, name)
            for ((id, f) in want.photos) assertContentEquals(f.readBytes(), got.photos.getValue(id).readBytes(), "$name: $id")
            assertEquals(got.photos.values.toSet(), File(dir, "got-$name").listFiles()!!.toSet(), "$name: only the new photos staged")
            // The run's read finds the same directory again, through RestoreRecords too.
            RestoreRecords.read(got, Json { ignoreUnknownKeys = true }, 0L)
            // And a file that changed since is refused, as for any zip.
            val other = java.io.ByteArrayOutputStream()
            ZipOutputStream(other).use { z ->
                z.putNextEntry(ZipEntry("backup.jsonl")); z.write(String(jsonl).replace("android-1", "android-2").toByteArray()); z.closeEntry()
            }
            over.writeBytes(savedOverALargerZip(other.toByteArray()))
            assertTrue("changed since it was checked" in refusal { linesOf(got) }, name)
        }
    }

    @Test
    fun aBackupSavedOverALargerZipWhoseNewPartDoesNotAgreeIsRefused() {
        val newer = File(fixtures, "android-zipoutputstream.crema.zip").readBytes()
        val cases = mapOf(
            // Its photo's bytes: the directory agrees, the walk fails the checksum.
            "a photo byte" to { b: ByteArray -> b[photoDataStart(b) + 300] = (b[photoDataStart(b) + 300].toInt() xor 1).toByte() },
            // A name in its directory: no longer the local header's.
            "a directory name" to { b: ByteArray -> b[photoNameAt(b, 1) + "images/b".length] = 'd'.code.toByte() },
            // Its directory's CRC for the photo: the header and descriptor disagree.
            "a directory CRC" to { b: ByteArray -> b[photoNameAt(b, 1) - 46 + 16] = (b[photoNameAt(b, 1) - 46 + 16].toInt() xor 1).toByte() },
            // Its end record no longer just after its directory.
            "the end record's offset" to { b: ByteArray -> b[newer.size - 22 + 16] = (b[newer.size - 22 + 16].toInt() xor 1).toByte() },
        )
        // Bytes between its last entry and its directory: a zip that reads on its
        // own, but not one whose records run exactly to its directory.
        val cdOffset = u32le(newer, newer.size - 22 + 16).toInt()
        val gapped = newer.copyOfRange(0, cdOffset) + ByteArray(16) + newer.copyOfRange(cdOffset, newer.size)
        putU32le(gapped, gapped.size - 22 + 16, cdOffset + 16L)
        prepareBackupFile(copy(gapped, "gapped.zip"), "gapped", File(dir, "gapped"))
        val inputs = cases.mapValues { (_, damage) -> savedOverALargerZip(newer).also(damage) } +
            ("a gap before its directory" to savedOverALargerZip(gapped))
        for ((what, bytes) in inputs) {
            val stage = File(dir, "s-$what")
            val why = refusal { prepareBackupFile(copy(bytes), what, stage) }
            // The end record's own refusal, as without the fallback, and nothing staged.
            assertEquals(refusal { walkBackupZip(copy(bytes), BackupReadLimits()) { _, _, _ -> } }, why, what)
            assertTrue(stage.listFiles().isNullOrEmpty(), "$what: nothing staged")
        }
    }

    private fun u32le(b: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { v, i -> v or ((b[at + i].toLong() and 0xff) shl (8 * i)) }

    private fun putU32le(b: ByteArray, at: Int, v: Long) {
        for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte()
    }

    /** Where the photo entry's name sits in its local header, and in the central directory. */
    private fun photoNameAt(bytes: ByteArray, header: Int): Int {
        val name = "images/bean:android-1"
        val sig = if (header == 3) 30 else 46
        val at = (0 until bytes.size - sig).first { i ->
            bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() && bytes[i + 2] == header.toByte() && bytes[i + 3] == (header + 1).toByte() &&
                String(bytes, i + sig, name.length, Charsets.ISO_8859_1) == name
        }
        return at + sig
    }

    @Test
    fun aNameTheLocalHeaderAndTheDirectoryDisagreeOnIsRefused() {
        // REVIEW-395's fuzz case: one flipped byte moved the photo to another bean.
        val fixture = File(fixtures, "android-zipoutputstream.crema.zip").readBytes()
        for (header in listOf(3, 1)) {
            val bytes = fixture.copyOf()
            val at = photoNameAt(bytes, header) + "images/b".length
            bytes[at] = 'd'.code.toByte() // images/bdan:android-1
            assertTrue("names disagree" in refusal { prepareBackupFile(copy(bytes), "renamed", File(dir, "s")) }, "header $header")
        }
    }

    @Test
    fun aZipWhoseFirstBytesAreDamagedIsReadAsAZipNotAsText() {
        for ((name, zip) in writers) {
            val bytes = zip.readBytes().also { for (i in 0 until 4) it[i] = 0 }
            refusal { prepareBackupFile(copy(bytes), name, File(dir, "s")) }
        }
    }

    @Test
    fun aFileNameThatIsNotUtf8IsDamagedNotARawError() {
        val bytes = File(fixtures, "android-zipoutputstream.crema.zip").readBytes()
        // The same bad byte in both names: they agree, and neither decodes.
        for (header in listOf(3, 1)) bytes[photoNameAt(bytes, header) + "images/b".length] = 0xff.toByte()
        assertTrue("a file name" in refusal { prepareBackupFile(copy(bytes), "bad name", File(dir, "s")) })
    }

    /** A zip of stored entries, each followed by a data descriptor, as no Crema writer makes but the web reads. */
    private fun storedWithDescriptors(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val central = java.io.ByteArrayOutputStream()
        fun u16(o: java.io.ByteArrayOutputStream, v: Int) { o.write(v and 0xff); o.write((v ushr 8) and 0xff) }
        fun u32(o: java.io.ByteArrayOutputStream, v: Long) { u16(o, (v and 0xffff).toInt()); u16(o, ((v ushr 16) and 0xffff).toInt()) }
        for ((name, data) in entries) {
            val offset = out.size().toLong()
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            val n = name.toByteArray()
            // Local header: flag 0x08, method 0, CRC and sizes left zero for the descriptor.
            u32(out, 0x04034b50); u16(out, 20); u16(out, 0x08); u16(out, 0); u16(out, 0); u16(out, 0)
            u32(out, 0); u32(out, 0); u32(out, 0); u16(out, n.size); u16(out, 0); out.write(n)
            out.write(data)
            u32(out, 0x08074b50); u32(out, crc); u32(out, data.size.toLong()); u32(out, data.size.toLong())
            u32(central, 0x02014b50); u16(central, 20); u16(central, 20); u16(central, 0x08); u16(central, 0); u16(central, 0); u16(central, 0)
            u32(central, crc); u32(central, data.size.toLong()); u32(central, data.size.toLong())
            u16(central, n.size); u16(central, 0); u16(central, 0); u16(central, 0); u16(central, 0); u32(central, 0); u32(central, offset)
            central.write(n)
        }
        val cdOffset = out.size().toLong()
        val cd = central.toByteArray()
        out.write(cd)
        u32(out, 0x06054b50); u16(out, 0); u16(out, 0); u16(out, entries.size); u16(out, entries.size)
        u32(out, cd.size.toLong()); u32(out, cdOffset); u16(out, 0)
        return out.toByteArray()
    }

    @Test
    fun aStoredEntryWithADataDescriptorReadsAsOnTheWeb() {
        val zip = copy(storedWithDescriptors(listOf("backup.jsonl" to jsonl, "images/bean:android-1" to photo())))
        val p = prepareBackupFile(zip, "descriptors", File(dir, "s"))
        assertContentEquals(photo(), p.photos.getValue("bean:android-1").readBytes())
        assertEquals(String(jsonl).split('\n'), linesOf(p).map { String(it) })
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
        for ((name, path) in listOf(
            "web-streamed-photo" to "src/test/resources/backup/web-streamed-photo.crema.zip",
            "android-streamed-photo" to "../../web/src/lib/backup/fixtures/android-streamed-photo.crema.zip",
        )) {
            val zip = File(path)
            val p = prepareBackupFile(zip, name, File(dir, "stage-$name"))
            // Read the same zip with the JDK's ZipFile, an independent reader:
            // every photo and the backup's lines must come out byte for byte.
            java.util.zip.ZipFile(zip).use { z ->
                val images = z.entries().toList().filter { it.name.startsWith("images/") }
                assertEquals(images.map { it.name.removePrefix("images/") }.toSet(), p.photos.keys, "$name: the photos staged")
                for (e in images) {
                    assertContentEquals(z.getInputStream(e).readBytes(), p.photos.getValue(e.name.removePrefix("images/")).readBytes(), "$name: ${e.name}")
                }
                val want = z.getInputStream(z.getEntry("backup.jsonl")).readBytes()
                val got = linesOf(p).joinToString("\n") { String(it) }
                assertEquals(String(want).trimEnd('\n'), got.trimEnd('\n'), "$name: its lines")
            }
            val header = Json.parseToJsonElement(p.manifestJson).jsonObject["header"]
            assertTrue(header != null && header.toString() != "null", "$name: its header read")
        }
    }
}

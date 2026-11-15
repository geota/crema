package coffee.crema.restore

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Read a backup from a file a piece at a time (#310 P2a-b, SPEC §7 "Restore,
 * Android"), so a restore holds a line, not the file. The twin of the web's
 * `#lib/backup/zip-reader`: the same limits, and lines split as the core
 * splits a whole file.
 *
 * A zip is read through its central directory, as on the web: each entry's
 * sizes, offset and CRC come from there, so a data descriptor, or a
 * signature inside an entry's bytes, changes nothing. The local header must
 * name the entry as the directory does. Every byte inflated is counted
 * against the limits, the entries a restore skips too, and an entry stops at
 * the size it declared.
 */
data class BackupReadLimits(
    /** Largest backup file read. */
    val maxFileBytes: Long = 2 * GIB,
    /** Most bytes the zip may inflate to, every entry counted. */
    val maxInflatedBytes: Long = 3 * GIB,
    /** Largest line, `0x0A` not included. */
    val maxLineBytes: Int = 16 * MIB.toInt(),
    /** Most entries a zip may hold. */
    val maxEntries: Int = 10_000,
    /** Largest photo entry. */
    val maxPhotoBytes: Long = 100 * MIB,
) {
    companion object {
        const val MIB = 1024L * 1024L
        const val GIB = 1024L * MIB
    }
}

/**
 * A backup that won't read: damaged, or past a limit. Nothing was written;
 * [message] is the user's. [damaged] is the first kind, which an earlier
 * directory may still read (REVIEW-410 should-fix 3).
 */
class BackupReadException(message: String, internal val damaged: Boolean = false) : IOException(message)

private fun mb(bytes: Long) = "${(bytes + BackupReadLimits.MIB - 1) / BackupReadLimits.MIB} MB"

private fun damaged(why: String) = BackupReadException("This backup is damaged ($why). Nothing was changed.", damaged = true)

/** A line, `0x0A` not included, or one too long to hold, which was skipped. */
sealed interface BackupLinePiece {
    class Line(val bytes: ByteArray) : BackupLinePiece
    data object Oversized : BackupLinePiece
}

private fun isBlank(b: Byte): Boolean = b == 0x20.toByte() || b == 0x09.toByte() || b == 0x0a.toByte() ||
    b == 0x0c.toByte() || b == 0x0d.toByte()

/**
 * [input] split on `0x0A`, as the core splits a whole file: the piece after
 * the last newline is a line too. A line longer than [maxLineBytes] isn't
 * held: its bytes are skipped and it comes out [BackupLinePiece.Oversized],
 * unless every byte of it was whitespace, which is a blank line however long
 * (an empty line).
 */
fun forEachBackupLine(input: InputStream, maxLineBytes: Int, onPiece: (BackupLinePiece) -> Unit) {
    val buf = ByteArray(64 * 1024)
    var line = java.io.ByteArrayOutputStream()
    var skipping = false
    var skippedBlank = true
    fun flush() {
        if (skipping) {
            onPiece(if (skippedBlank) BackupLinePiece.Line(ByteArray(0)) else BackupLinePiece.Oversized)
            skipping = false
            skippedBlank = true
        } else {
            onPiece(BackupLinePiece.Line(line.toByteArray()))
        }
        line = java.io.ByteArrayOutputStream()
    }
    fun add(from: Int, to: Int) {
        if (from == to) return
        if (skipping) {
            if (skippedBlank) for (i in from until to) if (!isBlank(buf[i])) { skippedBlank = false; break }
            return
        }
        if (line.size().toLong() + (to - from) > maxLineBytes) {
            skipping = true
            skippedBlank = line.toByteArray().all(::isBlank) && (from until to).all { isBlank(buf[it]) }
            line = java.io.ByteArrayOutputStream()
            return
        }
        line.write(buf, from, to - from)
    }
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        var from = 0
        for (i in 0 until n) {
            if (buf[i] == 0x0a.toByte()) {
                add(from, i)
                flush()
                from = i + 1
            }
        }
        add(from, n)
    }
    flush()
}

/** Counts what a zip inflates, against [budget]; past it, throws. */
private class CountingStream(private val inner: InputStream, private val over: (Long) -> Unit) : InputStream() {
    var count = 0L
        private set
    override fun read(): Int = inner.read().also { if (it >= 0) bump(1) }
    override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len).also { if (it > 0) bump(it.toLong()) }
    private fun bump(n: Long) {
        count += n
        over(count)
    }
}

/** Header id of the zip64 extended-information extra field. */
private const val ZIP64_EXTRA = 0x0001

private fun hasZip64(extra: ByteArray?): Boolean {
    if (extra == null) return false
    var at = 0
    while (at + 4 <= extra.size) {
        val id = (extra[at].toInt() and 0xff) or ((extra[at + 1].toInt() and 0xff) shl 8)
        val len = (extra[at + 2].toInt() and 0xff) or ((extra[at + 3].toInt() and 0xff) shl 8)
        if (id == ZIP64_EXTRA) return true
        at += 4 + len
    }
    return false
}

internal const val BACKUP_MEMBER = "backup.jsonl"
internal const val LEGACY_MEMBER = "crema.jsonl"

private fun u16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8)
private fun u32(b: ByteArray, at: Int): Long = u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)

private const val EOCD_SIG = 0x06054b50L
private const val CENTRAL_SIG = 0x02014b50L
private const val LOCAL_SIG = 0x04034b50L
private const val DESCRIPTOR_SIG = 0x08074b50L
private const val EOCD_BYTES = 22
private const val U32_MAX = 0xffffffffL

/**
 * Largest central directory read. A Crema backup's is about 100 bytes an
 * entry; this bounds what a zip that lies about it can make the reader hold.
 */
private const val MAX_DIRECTORY_BYTES = 16 * 1024 * 1024

/** One entry, as the central directory describes it. */
internal class ZipDirEntry(
    val name: String,
    /** [name] as stored, to compare with the local header's. */
    val nameBytes: ByteArray,
    /** 0 stored, 8 deflate. */
    val method: Int,
    /** The general-purpose flags. */
    val flags: Int,
    /** The DOS time and date, as one number. */
    val dosTime: Long,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    /** Where its local header starts. */
    val localOffset: Long,
)

/** A zip's entries, and where its directory and end record are. */
internal class ZipDirectory(val entries: List<ZipDirEntry>, val cdOffset: Long, val endAt: Long)

/**
 * The end-of-central-directory record, as `(offset, its 22 bytes)`: the last
 * one whose comment ends the file, or null when there is none (the file was
 * cut, or isn't a zip).
 */
private fun findEndRecord(raf: RandomAccessFile): Pair<Long, ByteArray>? {
    val size = raf.length()
    if (size < EOCD_BYTES) return null
    val tailStart = maxOf(0L, size - EOCD_BYTES - 0xffff)
    val tail = ByteArray((size - tailStart).toInt())
    raf.seek(tailStart)
    raf.readFully(tail)
    for (i in tail.size - EOCD_BYTES downTo 0) {
        if (u32(tail, i) == EOCD_SIG && i + EOCD_BYTES + u16(tail, i + 20) == tail.size) {
            return (tailStart + i) to tail.copyOfRange(i, i + EOCD_BYTES)
        }
    }
    return null
}

private fun decodeName(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
} catch (e: CharacterCodingException) {
    throw damaged("a file name")
}

/**
 * [file]'s entries, from the central directory its end record gives, within
 * [limits]. Throws [BackupReadException] for a zip that's cut, split,
 * encrypted, zip64, past the entry limit, or whose directory doesn't read.
 */
internal fun readCentralDirectory(file: File, limits: BackupReadLimits): ZipDirectory =
    RandomAccessFile(file, "r").use { raf ->
        val (eocdAt, end) = findEndRecord(raf) ?: throw damaged("it's cut short")
        parseDirectory(raf, eocdAt, end, limits, exact = false)
    }

/**
 * The directory the end record [end], at [eocdAt], gives. [exact] (an earlier
 * directory, REVIEW-410 should-fix 3) also asks that the directory end where
 * the end record starts and that its entries fill it.
 */
private fun parseDirectory(raf: RandomAccessFile, eocdAt: Long, end: ByteArray, limits: BackupReadLimits, exact: Boolean): ZipDirectory {
    val countHere = u16(end, 8)
    val count = u16(end, 10)
    val cdSize = u32(end, 12)
    val cdOffset = u32(end, 16)
    if (count == 0xffff || countHere == 0xffff || cdSize == U32_MAX || cdOffset == U32_MAX) {
        throw BackupReadException("This backup is too large to restore (zip64).")
    }
    if (u16(end, 4) != 0 || u16(end, 6) != 0 || count != countHere) throw damaged("a split archive")
    if (count > limits.maxEntries) throw BackupReadException("This backup holds more than ${limits.maxEntries} files.")
    if (count == 0) throw damaged("no files in the zip")
    if (cdOffset + cdSize > eocdAt) throw damaged("the central directory is out of place")
    if (exact && cdOffset + cdSize != eocdAt) throw damaged("the central directory is out of place")
    if (cdSize > MAX_DIRECTORY_BYTES) throw BackupReadException("This backup's list of files is too large to restore.")
    val cd = ByteArray(cdSize.toInt())
    raf.seek(cdOffset)
    raf.readFully(cd)
    val entries = ArrayList<ZipDirEntry>(count)
    var p = 0
    repeat(count) {
        if (p + 46 > cd.size || u32(cd, p) != CENTRAL_SIG) throw damaged("a central directory entry")
        val flags = u16(cd, p + 8)
        val nameLen = u16(cd, p + 28)
        val extraLen = u16(cd, p + 30)
        val commentLen = u16(cd, p + 32)
        if (p + 46 + nameLen + extraLen > cd.size) throw damaged("a central directory entry")
        val nameBytes = cd.copyOfRange(p + 46, p + 46 + nameLen)
        val entry = ZipDirEntry(
            name = decodeName(nameBytes),
            nameBytes = nameBytes,
            method = u16(cd, p + 10),
            flags = flags,
            dosTime = u32(cd, p + 12),
            crc = u32(cd, p + 16),
            compressedSize = u32(cd, p + 20),
            size = u32(cd, p + 24),
            localOffset = u32(cd, p + 42),
        )
        if (flags and 1 != 0) throw BackupReadException("This backup is encrypted, which Crema can't read.")
        // A zip64 extra here alone is fine, as on the web: the sizes above are what's read.
        if (entry.compressedSize == U32_MAX || entry.size == U32_MAX || entry.localOffset == U32_MAX) {
            throw BackupReadException("This backup is too large to restore (zip64).")
        }
        if (entry.localOffset + 30 + nameLen + entry.compressedSize > cdOffset) throw damaged("\"${entry.name}\" is out of place")
        entries += entry
        p += 46 + nameLen + extraLen + commentLen
    }
    if (exact && p != cd.size) throw damaged("a central directory entry")
    return ZipDirectory(entries, cdOffset, eocdAt)
}

/**
 * Most earlier end records [earlierEndRecords] gives: each costs a read of its
 * directory, and a real backup with an old zip's tail has one.
 */
private const val MAX_EARLIER_END_RECORDS = 64

/**
 * Where [file] may hold an earlier zip's end record, last first: each
 * signature before the end record that ends the file (or before the end, when
 * none does) whose record is whole, unsplit, and has its directory just before
 * it. Before #391 a backup saved over a larger file, by a provider that
 * didn't truncate on "w", ends with the old zip's tail (REVIEW-410 should-fix
 * 3): the new backup's end record is one of these.
 */
internal fun earlierEndRecords(file: File): List<Long> = RandomAccessFile(file, "r").use { raf ->
    val size = raf.length()
    val found = ArrayList<Long>()
    var top = findEndRecord(raf)?.first ?: size
    val window = 64 * 1024
    val buf = ByteArray(window + 3)
    val end = ByteArray(EOCD_BYTES)
    while (top > 0 && found.size < MAX_EARLIER_END_RECORDS) {
        val from = maxOf(0L, top - window)
        val len = (minOf(size, top + 3) - from).toInt()
        raf.seek(from)
        raf.readFully(buf, 0, len)
        for (i in (top - from - 1).toInt() downTo 0) {
            if (i + 4 > len || u32(buf, i) != EOCD_SIG) continue
            val at = from + i
            if (at + EOCD_BYTES > size) continue
            raf.seek(at)
            raf.readFully(end)
            val ok = u16(end, 4) == 0 && u16(end, 6) == 0 && u16(end, 8) == u16(end, 10) && u16(end, 10) > 0 &&
                u32(end, 16) + u32(end, 12) == at && at + EOCD_BYTES + u16(end, 20) <= size
            if (ok) {
                found += at
                if (found.size == MAX_EARLIER_END_RECORDS) break
            }
        }
        top = from
    }
    found
}

/**
 * The directory whose end record is at [endAt], only if it agrees exactly
 * with the local headers: its entries, in order, are the zip's records from
 * offset 0 to the directory with nothing between them, each local header
 * names its entry as the directory does, with the same flags, method, time,
 * and CRC and sizes (in its header, or its data descriptor), and it holds
 * `backup.jsonl`. Otherwise null. The walk then checks each entry's bytes.
 */
internal fun exactDirectoryAt(file: File, endAt: Long, limits: BackupReadLimits): ZipDirectory? =
    RandomAccessFile(file, "r").use { raf ->
        if (endAt < 0 || endAt + EOCD_BYTES > raf.length()) return@use null
        val end = ByteArray(EOCD_BYTES)
        raf.seek(endAt)
        raf.readFully(end)
        if (u32(end, 0) != EOCD_SIG) return@use null
        val d = try {
            parseDirectory(raf, endAt, end, limits, exact = true)
        } catch (e: BackupReadException) {
            return@use null
        }
        if (d.entries.none { it.name == BACKUP_MEMBER }) return@use null
        if (agreesExactly(raf, d)) d else null
    }

private fun agreesExactly(raf: RandomAccessFile, d: ZipDirectory): Boolean {
    val head = ByteArray(30)
    val desc = ByteArray(16)
    var at = 0L
    for (e in d.entries) {
        if (e.localOffset != at || at + 30 > d.cdOffset) return false
        raf.seek(at)
        raf.readFully(head)
        if (u32(head, 0) != LOCAL_SIG) return false
        val flags = u16(head, 6)
        if (flags != e.flags || u16(head, 8) != e.method || u32(head, 10) != e.dosTime) return false
        val nameLen = u16(head, 26)
        val extraLen = u16(head, 28)
        val dataEnd = at + 30 + nameLen + extraLen + e.compressedSize
        if (dataEnd > d.cdOffset) return false
        val name = ByteArray(nameLen).also { raf.readFully(it) }
        if (!name.contentEquals(e.nameBytes)) return false
        val crc = u32(head, 14)
        val compressed = u32(head, 18)
        val size = u32(head, 22)
        val described = flags and 8 != 0
        if (described) {
            // The header may give them as 0, for its data descriptor to give.
            if ((crc != 0L && crc != e.crc) || (compressed != 0L && compressed != e.compressedSize) || (size != 0L && size != e.size)) return false
            val n = minOf(16L, d.cdOffset - dataEnd).toInt()
            if (n < 12) return false
            raf.seek(dataEnd)
            raf.readFully(desc, 0, n)
            fun gives(o: Int) = u32(desc, o) == e.crc && u32(desc, o + 4) == e.compressedSize && u32(desc, o + 8) == e.size
            at = dataEnd + when {
                n == 16 && u32(desc, 0) == DESCRIPTOR_SIG && gives(4) -> 16
                gives(0) -> 12
                else -> return false
            }
        } else {
            if (crc != e.crc || compressed != e.compressedSize || size != e.size) return false
            at = dataEnd
        }
    }
    return at == d.cdOffset
}

/**
 * An entry's bytes, inflated from exactly the compressed bytes the directory
 * gives it. Past its declared size it stops at once; at its end its size and
 * CRC are checked. The local header must name the same entry as the
 * directory, or a photo could land on another bean.
 */
private class EntryStream(private val raf: RandomAccessFile, private val e: ZipDirEntry) : InputStream() {
    private var at: Long
    private val end: Long
    private val inflater = if (e.method == 8) Inflater(true) else null
    private val input = ByteArray(64 * 1024)
    private val crc = CRC32()
    private var size = 0L
    private var done = false
    /** Whether the one dummy byte raw inflate may want past the data was given, as `ZipFile` gives it. */
    private var padded = false

    init {
        if (e.method != 0 && e.method != 8) throw damaged("\"${e.name}\" is compressed a way Crema can't read")
        val head = ByteArray(30)
        raf.seek(e.localOffset)
        raf.readFully(head)
        if (u32(head, 0) != LOCAL_SIG) throw damaged("\"${e.name}\"'s local header")
        if (u16(head, 6) and 1 != 0) throw BackupReadException("This backup is encrypted, which Crema can't read.")
        val localName = ByteArray(u16(head, 26)).also { raf.readFully(it) }
        if (!localName.contentEquals(e.nameBytes)) throw damaged("\"${e.name}\"'s names disagree")
        val extra = ByteArray(u16(head, 28)).also { raf.readFully(it) }
        if (hasZip64(extra)) throw BackupReadException("This backup is too large to restore (zip64).")
        at = e.localOffset + 30 + localName.size + extra.size
        end = at + e.compressedSize
        if (end > raf.length()) throw damaged("\"${e.name}\" is cut short")
    }

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (done) return -1
        if (len == 0) return 0
        val n = if (inflater == null) readStored(b, off, len) else readInflated(inflater, b, off, len)
        if (n < 0) {
            finish()
            return -1
        }
        size += n
        if (size > e.size) throw damaged("\"${e.name}\" is larger than it says")
        crc.update(b, off, n)
        return n
    }

    private fun readStored(b: ByteArray, off: Int, len: Int): Int {
        val left = end - at
        if (left <= 0) return -1
        val n = minOf(len.toLong(), left).toInt()
        raf.seek(at)
        raf.readFully(b, off, n)
        at += n
        return n
    }

    private fun readInflated(inflater: Inflater, b: ByteArray, off: Int, len: Int): Int {
        while (true) {
            val n = try {
                inflater.inflate(b, off, len)
            } catch (x: DataFormatException) {
                throw damaged("\"${e.name}\" won't unpack")
            }
            if (n > 0) return n
            if (inflater.finished()) return -1
            if (!inflater.needsInput()) throw damaged("\"${e.name}\" won't unpack")
            val left = end - at
            if (left <= 0) {
                if (padded) throw damaged("\"${e.name}\" is cut short")
                padded = true
                inflater.setInput(ByteArray(1), 0, 1)
                continue
            }
            val k = minOf(input.size.toLong(), left).toInt()
            raf.seek(at)
            raf.readFully(input, 0, k)
            at += k
            inflater.setInput(input, 0, k)
        }
    }

    private fun finish() {
        done = true
        inflater?.end()
        if (size != e.size) throw damaged("\"${e.name}\" is cut short")
        if (crc.value != e.crc) throw damaged("\"${e.name}\" fails its checksum")
    }

    override fun close() {
        inflater?.end()
    }
}

/**
 * Walk the zip [file], within [limits], through its central directory, as
 * the web's reader does, or through [directory], an earlier one from
 * [exactDirectoryAt]: each entry the restore reads goes to [onEntry] as a
 * stream it must read to the end (so its CRC is checked); the rest are read
 * through and dropped, checked and counted all the same. Throws
 * [BackupReadException] for a damaged zip or one past a limit.
 */
internal fun walkBackupZip(
    file: File,
    limits: BackupReadLimits,
    directory: ZipDirectory? = null,
    onEntry: (name: String, kind: EntryKind, data: InputStream) -> Unit,
) {
    val entries = (directory ?: readCentralDirectory(file, limits)).entries
    var inflated = 0L
    val names = HashSet<String>()
    try {
        RandomAccessFile(file, "r").use { raf ->
            for (e in entries) {
                val name = e.name
                val kind = when {
                    name.endsWith("/") -> null
                    name == BACKUP_MEMBER || name == LEGACY_MEMBER -> EntryKind.BACKUP
                    name.startsWith("images/") -> EntryKind.PHOTO
                    else -> null
                }
                if (kind != null && !names.add(name)) throw damaged("\"$name\" twice")
                val start = inflated
                EntryStream(raf, e).use { data ->
                    val counted = CountingStream(data) { n ->
                        if (start + n > limits.maxInflatedBytes) {
                            throw BackupReadException("This backup unpacks to more than ${mb(limits.maxInflatedBytes)}, too large to restore.")
                        }
                        if (kind == EntryKind.PHOTO && n > limits.maxPhotoBytes) {
                            throw BackupReadException("\"$name\" in this backup unpacks to more than ${mb(limits.maxPhotoBytes)}.")
                        }
                    }
                    if (kind != null) onEntry(name, kind, counted)
                    // Read through to the end: the CRC is checked there, and every byte counts.
                    val sink = ByteArray(64 * 1024)
                    while (counted.read(sink) >= 0) Unit
                    inflated = start + counted.count
                }
            }
        }
    } catch (e: java.io.EOFException) {
        throw damaged("it's cut short")
    }
}

internal enum class EntryKind { BACKUP, PHOTO }

/** Whether [file] starts like a zip (a local header). */
internal fun isZipFile(file: File): Boolean {
    val head = ByteArray(4)
    val n = FileInputStream(file).use { it.read(head) }
    return n == 4 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte() && head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
}

/**
 * Whether [file] is a zip: it starts like one, or it ends like one, so a zip
 * whose first bytes are damaged is read, and refused, as a zip, not as text
 * (REVIEW-395; the web's `looksLikeZip`).
 */
internal fun looksLikeZip(file: File): Boolean =
    isZipFile(file) || RandomAccessFile(file, "r").use { findEndRecord(it) } != null

/** Refuse a file past [BackupReadLimits.maxFileBytes]. */
internal fun checkFileSize(file: File, limits: BackupReadLimits) {
    if (file.length() > limits.maxFileBytes) {
        throw BackupReadException("This backup is ${mb(file.length())}. Crema restores backups up to ${mb(limits.maxFileBytes)}.")
    }
}

/**
 * Copy a backup from [input] to [dest], a piece at a time, within
 * [BackupReadLimits.maxFileBytes]: a file the user picked or Drive sent, to
 * read again from disk. Returns [dest]; on a failure it's deleted.
 */
fun copyBackupTo(input: InputStream, dest: File, limits: BackupReadLimits = BackupReadLimits()): File {
    try {
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > limits.maxFileBytes) {
                    throw BackupReadException("This backup is larger than ${mb(limits.maxFileBytes)}. Crema restores backups up to ${mb(limits.maxFileBytes)}.")
                }
                out.write(buf, 0, n)
            }
        }
        return dest
    } catch (e: Throwable) {
        dest.delete()
        throw e
    }
}

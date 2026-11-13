package coffee.crema.restore

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Read a backup from a file a piece at a time (#310 P2a-b, SPEC §7 "Restore,
 * Android"), so a restore holds a line, not the file. The twin of the web's
 * `#lib/backup/zip-reader`: the same limits, and lines split as the core
 * splits a whole file.
 *
 * A zip goes through [ZipInputStream], which checks each entry's CRC and
 * reads data descriptors from the bytes it inflates rather than by scanning
 * for signatures. Every byte it inflates is counted against the limits, the
 * entries a restore skips too, so an entry that lies about its size still
 * stops at them.
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

/** A backup that won't read: damaged, or past a limit. Nothing was written; [message] is the user's. */
class BackupReadException(message: String) : IOException(message)

private fun mb(bytes: Long) = "${(bytes + BackupReadLimits.MIB - 1) / BackupReadLimits.MIB} MB"

private fun damaged(why: String) = BackupReadException("This backup is damaged ($why). Nothing was changed.")

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

/**
 * Walk the zip [file], within [limits]: each entry the restore reads goes to
 * [onEntry] as a stream it must read to the end (so its CRC is checked); the
 * rest are read through and dropped, counted all the same. Throws
 * [BackupReadException] for a damaged zip or one past a limit.
 */
internal fun walkBackupZip(
    file: File,
    limits: BackupReadLimits,
    onEntry: (name: String, kind: EntryKind, data: InputStream) -> Unit,
) {
    var inflated = 0L
    val names = HashSet<String>()
    var entries = 0
    try {
        ZipInputStream(BufferedInputStream(FileInputStream(file), 64 * 1024)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (++entries > limits.maxEntries) throw BackupReadException("This backup holds more than ${limits.maxEntries} files.")
                if (hasZip64(entry.extra)) throw BackupReadException("This backup is too large to restore (zip64).")
                val name = entry.name
                val kind = when {
                    entry.isDirectory -> null
                    name == BACKUP_MEMBER || name == LEGACY_MEMBER -> EntryKind.BACKUP
                    name.startsWith("images/") -> EntryKind.PHOTO
                    else -> null
                }
                if (kind != null && !names.add(name)) throw damaged("\"$name\" twice")
                val start = inflated
                val counted = CountingStream(zis) { n ->
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
    } catch (e: BackupReadException) {
        throw e
    } catch (e: ZipException) {
        val m = e.message.orEmpty()
        throw when {
            "encrypted" in m -> BackupReadException("This backup is encrypted, which Crema can't read.")
            "CRC" in m -> damaged("a file fails its checksum")
            else -> damaged(m.ifBlank { "the zip won't read" })
        }
    } catch (e: java.io.EOFException) {
        throw damaged("it's cut short")
    }
    if (entries == 0) throw damaged("no files in the zip")
    // ZipInputStream stops quietly at an entry boundary: a zip cut there reads
    // as complete. Its end record says how many entries there should be.
    val expected = endRecordEntries(file) ?: throw damaged("it's cut short")
    if (expected != entries) throw damaged("it's cut short")
}

/**
 * The entry count in [file]'s end-of-central-directory record, or null when
 * there is none where it must be (the file was cut). Zip64 counts are refused.
 */
internal fun endRecordEntries(file: File): Int? {
    val eocd = 22
    java.io.RandomAccessFile(file, "r").use { raf ->
        val size = raf.length()
        if (size < eocd) return null
        val tailStart = maxOf(0L, size - eocd - 0xffff)
        val tail = ByteArray((size - tailStart).toInt())
        raf.seek(tailStart)
        raf.readFully(tail)
        fun u16(at: Int) = (tail[at].toInt() and 0xff) or ((tail[at + 1].toInt() and 0xff) shl 8)
        for (i in tail.size - eocd downTo 0) {
            val sig = tail[i] == 0x50.toByte() && tail[i + 1] == 0x4b.toByte() &&
                tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
            if (sig && i + eocd + u16(i + 20) == tail.size) {
                val count = u16(i + 10)
                if (count == 0xffff) throw BackupReadException("This backup is too large to restore (zip64).")
                if (u16(i + 4) != 0 || u16(i + 6) != 0) throw damaged("a split archive")
                return count
            }
        }
        return null
    }
}

internal enum class EntryKind { BACKUP, PHOTO }

/** Whether [file] starts like a zip (a local header). */
internal fun isZipFile(file: File): Boolean {
    val head = ByteArray(4)
    val n = FileInputStream(file).use { it.read(head) }
    return n == 4 && head[0] == 0x50.toByte() && head[1] == 0x4b.toByte() && head[2] == 0x03.toByte() && head[3] == 0x04.toByte()
}

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

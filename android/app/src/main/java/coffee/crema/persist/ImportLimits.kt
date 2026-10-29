package coffee.crema.persist

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Size caps for files and zip archives the user imports (backup restore,
 * Beanconqueror import, profile / shot import) — review 05 P3-2. A crafted
 * file or zip bomb must fail with a clear message instead of an OOM that kills
 * the app. The numbers match the web shell (`#lib/utils/import-limits.js`).
 *
 * Reads are bounded by the bytes actually produced, not by sizes a zip
 * declares, so an entry that lies about its size still stops at the cap.
 */
object ImportLimits {
    private const val MIB = 1024L * 1024L

    /** Largest file any import reads. */
    const val MAX_FILE_BYTES = 200 * MIB

    /** Largest single zip entry that may be inflated. */
    const val MAX_ENTRY_BYTES = 100 * MIB

    /** Total inflated bytes across the accepted entries of one zip. */
    const val MAX_TOTAL_BYTES = 400 * MIB

    /** Most entries one zip may inflate. */
    const val MAX_ENTRIES = 10_000

    internal fun mb(bytes: Long): String = "${(bytes + MIB - 1) / MIB} MB"
}

/** An import refused for being larger than [ImportLimits] allow; [message] is user-facing. */
class ImportTooLargeException(message: String) : IOException(message)

/**
 * Read at most [max] bytes; throws [ImportTooLargeException] (naming [what])
 * as soon as the stream yields more. Never buffers more than `max + 8 KiB`.
 */
fun InputStream.readBytesCapped(max: Long, what: String = "That file"): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > max) {
            throw ImportTooLargeException("$what is larger than ${ImportLimits.mb(max)}, too large to import.")
        }
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/**
 * Walk a zip, handing [onEntry] the bytes of each entry [accept] wants, within
 * the per-entry, total and entry-count caps. Entries not accepted are skipped
 * without being inflated into memory.
 */
fun readZipCapped(
    zip: ZipInputStream,
    accept: (name: String) -> Boolean,
    maxEntryBytes: Long = ImportLimits.MAX_ENTRY_BYTES,
    maxTotalBytes: Long = ImportLimits.MAX_TOTAL_BYTES,
    maxEntries: Int = ImportLimits.MAX_ENTRIES,
    onEntry: (name: String, bytes: ByteArray) -> Unit,
) {
    var count = 0
    var total = 0L
    var entry = zip.nextEntry
    while (entry != null) {
        val name = entry.name
        if (!entry.isDirectory && accept(name)) {
            if (++count > maxEntries) {
                throw ImportTooLargeException("The archive has more than $maxEntries files to import.")
            }
            val remaining = maxTotalBytes - total
            val bytes = if (remaining < maxEntryBytes) {
                runCatching { zip.readBytesCapped(remaining, "\"$name\"") }.getOrElse {
                    if (it is ImportTooLargeException) {
                        throw ImportTooLargeException(
                            "The archive unpacks to more than ${ImportLimits.mb(maxTotalBytes)}, too large to import.",
                        )
                    }
                    throw it
                }
            } else {
                zip.readBytesCapped(maxEntryBytes, "\"$name\" in the archive")
            }
            total += bytes.size
            onEntry(name, bytes)
        }
        zip.closeEntry()
        entry = zip.nextEntry
    }
}

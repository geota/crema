package coffee.crema.history

import coffee.crema.persist.KeptAside
import coffee.crema.persist.KeptAsideNotices
import coffee.crema.persist.SafeFile
import java.io.File
import java.io.RandomAccessFile

/**
 * A SQLite `-wal` a kill left damaged (#578 follow-up). SQLite reads a WAL
 * whose header doesn't check out as empty, and its next write starts the file
 * over, so the shots committed only to it would go without a word. Before a
 * history database opens, a non-empty `-wal` with a bad header is copied (never
 * moved or deleted: SQLite still owns it) to `<name>-wal.corrupt-<ms>`, the
 * newest [SafeFile.MAX_KEPT] kept, and reported like a damaged store. A `-wal`
 * the newest copy already holds byte for byte (a second open in this process,
 * before SQLite has written to it) isn't copied or reported again. Anything
 * that goes wrong here is logged and the open goes ahead as before.
 */
internal object DamagedWal {
    /** Its name in the kept-aside notice. */
    const val WHAT = "latest shot history changes"

    private const val CORRUPT = ".corrupt-"
    private const val HEADER = 32

    /** Test seam: the copy, which may fail. */
    @androidx.annotation.VisibleForTesting
    @Volatile internal var copyFile: (File, File) -> Unit = { from, to -> from.copyTo(to) }

    /** Check [db]'s `-wal` before [db] opens; the copy made, or null. Never throws. */
    fun keepAsideIfDamaged(db: File, log: (String) -> Unit): File? {
        val wal = File(db.path + "-wal")
        var copy: File? = null
        return try {
            if (wal.length() == 0L || headerValid(readHeader(wal))) return null
            if (copies(wal).firstOrNull()?.let { sameBytes(it, wal) } == true) return null
            val dest = nextCopy(wal)
            copy = dest
            copyFile(wal, dest)
            runCatching { prune(wal) }
            log("${wal.name} has a damaged header: SQLite will discard it, so it was copied to ${dest.name}")
            KeptAsideNotices.report(KeptAside(WHAT, dest))
            dest
        } catch (e: Exception) {
            copy?.let { runCatching { it.delete() } }
            log("couldn't keep the damaged ${wal.name} aside (${e.javaClass.simpleName}); opening as before")
            null
        }
    }

    private fun readHeader(wal: File): ByteArray = RandomAccessFile(wal, "r").use { f ->
        ByteArray(minOf(HEADER.toLong(), f.length()).toInt()).also { f.readFully(it) }
    }

    /**
     * Whether SQLite would read [h] as a WAL header (`walIndexRecover`): the
     * magic, a power-of-two page size from 512 to 64 KiB, and the header's own
     * checksum, in the byte order the magic's low bit names.
     */
    internal fun headerValid(h: ByteArray): Boolean {
        if (h.size < HEADER) return false
        fun be(i: Int) = ((h[i].toInt() and 0xff) shl 24) or ((h[i + 1].toInt() and 0xff) shl 16) or
            ((h[i + 2].toInt() and 0xff) shl 8) or (h[i + 3].toInt() and 0xff)
        val magic = be(0)
        if (magic != MAGIC_LE && magic != MAGIC_BE) return false
        val page = be(8)
        if (page < 512 || page > 65_536 || page and (page - 1) != 0) return false
        val bigEndian = magic == MAGIC_BE
        fun word(i: Int) = if (bigEndian) be(i) else Integer.reverseBytes(be(i))
        var s1 = 0
        var s2 = 0
        for (i in 0 until 24 step 8) {
            s1 += word(i) + s2
            s2 += word(i + 4) + s1
        }
        return s1 == be(24) && s2 == be(28)
    }

    private fun nextCopy(wal: File): File {
        var ms = System.currentTimeMillis()
        var f: File
        do { f = File(wal.path + CORRUPT + ms); ms++ } while (f.exists())
        return f
    }

    /** [wal]'s copies, newest first. */
    private fun copies(wal: File): List<File> =
        (wal.parentFile?.listFiles { f -> f.name.startsWith(wal.name + CORRUPT) } ?: emptyArray())
            .sortedByDescending { it.name.substringAfterLast('-').toLongOrNull() ?: 0L }

    private fun prune(wal: File) = copies(wal).drop(SafeFile.MAX_KEPT).forEach { it.delete() }

    private fun sameBytes(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { x ->
            b.inputStream().buffered().use { y ->
                while (true) {
                    val c = x.read()
                    if (c != y.read()) return false
                    if (c < 0) return true
                }
            }
        }
    }

    private const val MAGIC_LE = 0x377f0682
    private const val MAGIC_BE = 0x377f0683
}

package coffee.crema.persist

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Collections

/**
 * Crash-safe file persistence for every local store (shots, beans, profiles,
 * settings, sign-ins, …).
 *
 * **Why.** The stores used to save with `File.writeText`, which truncates the
 * target and then writes in place, and to load with
 * `runCatching { decode }.getOrNull() ?: empty`. A kill or power loss mid-save
 * left a half-written file, the next launch silently loaded it as EMPTY, and
 * the next save overwrote it: the user's history was gone for good.
 *
 * **Save** ([write]): the bytes go to `<name>.tmp` in the same directory,
 * `fd.sync()` makes them durable, the current target is renamed to
 * `<name>.bak`, and the temp file is renamed over the target. Each rename is a
 * single atomic `rename(2)` on the same filesystem, so at every instant either
 * the old target, the backup, or the complete new file is on disk: a reader
 * never sees a partial target.
 *
 * Own helper rather than `androidx.core.util.AtomicFile`: same tmp + sync +
 * rename technique, but AtomicFile's read side recovers its backup without
 * telling the caller anything, has no notion of "this file is unreadable, keep
 * it aside", and logs through `android.util.Log`, which plain JVM tests can't
 * run. Here the whole policy (backup, quarantine, refusing to overwrite an
 * unquarantined corrupt file) is one place, and is unit-tested end to end.
 *
 * **Load** ([load]) has three explicit outcomes ([SafeLoad]): Absent, Loaded,
 * Corrupt. On a decode failure the bad file is moved aside to
 * `<name>.corrupt-<epochMs>` (newest [MAX_KEPT] per store), the backup or a
 * complete leftover temp file is tried in its place, and only when neither
 * decodes does the caller get Corrupt. If the bad file can't be moved aside,
 * [write] refuses to touch that target for the rest of the process, so an
 * empty state can never replace the only copy.
 */
object SafeFile {
    /** Corrupt copies kept per store; older ones are deleted. */
    const val MAX_KEPT = 3

    private const val TMP = ".tmp"
    private const val BAK = ".bak"
    private const val CORRUPT = ".corrupt-"

    /** Targets whose corrupt file couldn't be moved aside: saving would destroy it. */
    private val blocked: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Test seam: runs after the temp file is synced and before any rename. */
    internal var beforeRename: (File) -> Unit = {}

    /** The previous good version kept beside [target]. */
    fun backupOf(target: File) = File(target.parentFile, target.name + BAK)

    private fun tmpOf(target: File) = File(target.parentFile, target.name + TMP)

    /** The kept-aside corrupt copies of [target], newest first. */
    fun keptAside(target: File): List<File> =
        (target.parentFile?.listFiles { f -> f.name.startsWith(target.name + CORRUPT) } ?: emptyArray())
            .sortedByDescending { it.name.substringAfterLast(CORRUPT).toLongOrNull() ?: 0L }

    /** Atomically replace [target] with [text] (UTF-8). Throws on failure. */
    fun write(target: File, text: String, keepBackup: Boolean = true) =
        writeBytes(target, text.toByteArray(Charsets.UTF_8), keepBackup)

    /** Atomically replace [target] with [bytes]. Throws on failure; the old target stays intact. */
    fun writeBytes(target: File, bytes: ByteArray, keepBackup: Boolean = false) {
        if (target.path in blocked) {
            throw IOException("${target.name} is unreadable and couldn't be kept aside; not overwriting it")
        }
        target.parentFile?.mkdirs()
        val tmp = tmpOf(target)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            beforeRename(tmp)
            if (keepBackup && target.exists() && !target.renameTo(backupOf(target))) {
                throw IOException("couldn't move ${target.name} to its backup")
            }
            if (!tmp.renameTo(target)) throw IOException("couldn't rename ${tmp.name} over ${target.name}")
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /**
     * Read and [decode] [target]. A file that fails to read or decode is moved
     * aside (see [SafeLoad.Corrupt]); the backup and a complete leftover temp
     * file are tried before giving up. [log] receives one line per event.
     */
    fun <T : Any> load(target: File, log: (String) -> Unit = {}, decode: (String) -> T): SafeLoad<T> {
        val bak = backupOf(target)
        val tmp = tmpOf(target)
        fun attempt(f: File): Result<T>? =
            if (!f.isFile) null else runCatching { decode(f.readText(Charsets.UTF_8)) }

        val primary = attempt(target)
        primary?.getOrNull()?.let { return SafeLoad.Loaded(it) }

        var keptAt: File? = null
        val error = primary?.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        if (primary != null) {
            keptAt = quarantine(target)
            if (keptAt == null) {
                blocked += target.path
                log("${target.name} unreadable ($error); couldn't keep it aside, saving is disabled")
            } else {
                log("${target.name} unreadable ($error); kept aside as ${keptAt.name}")
            }
        }

        // The previous good version (crash between the two renames, or the
        // target went bad), then a temp file that was fully written but never
        // renamed (it only counts if it decodes).
        for (candidate in listOf(bak, tmp)) {
            val r = attempt(candidate) ?: continue
            val value = r.getOrNull()
            if (value != null) {
                log("${target.name}: recovered from ${candidate.name}")
                if (target.path !in blocked) runCatching { write(target, candidate.readText(Charsets.UTF_8)) }
                return SafeLoad.Loaded(value, recoveredFrom = candidate.name)
            }
        }
        if (tmp.exists()) tmp.delete() // a partial temp is never useful

        return if (primary == null) SafeLoad.Absent else SafeLoad.Corrupt(keptAt, error ?: "unreadable")
    }

    /** Move [target] to `<name>.corrupt-<epochMs>` and prune to [MAX_KEPT]; null on failure. */
    private fun quarantine(target: File): File? {
        val dest = run {
            var ms = System.currentTimeMillis()
            var f: File
            do { f = File(target.parentFile, target.name + CORRUPT + ms); ms++ } while (f.exists())
            f
        }
        if (!target.renameTo(dest)) return null
        keptAside(target).drop(MAX_KEPT).forEach { it.delete() }
        return dest
    }

    /** Test seam: forget blocked targets between tests. */
    internal fun resetForTest() {
        blocked.clear()
        beforeRename = {}
    }
}

/** The outcome of [SafeFile.load]. */
sealed interface SafeLoad<out T> {
    /** No file yet: a fresh install, or the store was never saved. */
    data object Absent : SafeLoad<Nothing>

    /** Decoded. [recoveredFrom] names the backup/temp file when the target itself was bad. */
    data class Loaded<T>(val value: T, val recoveredFrom: String? = null) : SafeLoad<T>

    /**
     * The file exists but can't be read or decoded, and no backup could stand
     * in. The bytes were moved to [keptAt] (null when that failed, in which case
     * the target is left in place and further saves to it are refused).
     */
    data class Corrupt(val keptAt: File?, val error: String) : SafeLoad<Nothing>

    /** The decoded value, or null when Absent/Corrupt. */
    fun valueOrNull(): T? = (this as? Loaded<T>)?.value
}

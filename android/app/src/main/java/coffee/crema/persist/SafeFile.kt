package coffee.crema.persist

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

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
 * **Load** ([load]) has four explicit outcomes ([SafeLoad]): Absent, Loaded,
 * Corrupt, Unavailable. On a decode failure the bad file is moved aside to
 * `<name>.corrupt-<epochMs>` (newest [MAX_KEPT] per store), the backup or a
 * complete leftover temp file is tried in its place, and only when neither
 * decodes does the caller get Corrupt. If the bad file can't be moved aside,
 * [write] refuses to touch that target until a load reads it, so an empty state
 * can never replace the only copy.
 *
 * Only a decode failure can quarantine. Anything else thrown while reading or
 * decoding, an `IOException` or an `OutOfMemoryError`, says nothing about the
 * file, which stays where it is: that's Unavailable, and saves are refused the
 * same way until a load reads it or [setAside] moves it.
 *
 * **The load floor.** A store's saves are whole snapshots, so one built before
 * the store has loaded holds defaults, and landing it would replace the user's
 * data. [load] marks its path, [saveStore] and [writeCredential] refuse a path
 * that isn't marked (see [SaveFloor]), and [startSession] unmarks them all for
 * a new ViewModel, as this object can outlive one. A store whose state lands
 * after its load returns loads with `open = false` and calls [openSaves] once
 * the state is in place. A reload of a store that's already open goes between
 * [closeSaves] and [openSaves], so nothing built from the state being replaced
 * can land over the file. [peek] reads without touching any of this.
 *
 * **Overlap.** Every operation on a target holds that target's lock, because
 * they share its `.tmp`: without it the loser of two saves moves the target to
 * `.bak` and finds its temp file gone, and a load can delete a temp file a save
 * is still writing. Nothing here suspends, and the lock is held only around the
 * file work (and a load's pure decode), so a caller's own lock can only ever be
 * taken before it, never after. A wait is one fsync at most, the same disk work
 * a caller on the main thread already does for itself.
 */
object SafeFile {
    /** Corrupt copies kept per store; older ones are deleted. */
    const val MAX_KEPT = 3

    private const val TMP = ".tmp"
    private const val BAK = ".bak"
    private const val CORRUPT = ".corrupt-"

    /** After [CORRUPT]: put there by [setAside], so [MAX_KEPT] leaves it alone. */
    private const val SET_ASIDE = "kept-"

    /** Targets that couldn't be read, or moved aside: saving would destroy them. */
    private val blocked: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** Targets loaded this session: only these can take a gated save. */
    private val loaded: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** The newest session's number. */
    @Volatile private var session = 0

    /** Targets being reloaded: refused, quietly, until [openSaves]. */
    private val held: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /** See [openAllForTest]. */
    @Volatile private var allOpenForTest = false

    /** One lock per target path. Bounded by the files the app owns. */
    private val locks = ConcurrentHashMap<String, Any>()

    private fun lockOf(target: File): Any = locks.getOrPut(target.path) { Any() }

    /** Test seam: runs after the temp file is synced and before any rename. */
    internal var beforeRename: (File) -> Unit = {}

    /** Test seam: runs when a load has read its file, before it can open saves. */
    internal var afterLoad: (File) -> Unit = {}

    /** Whether saves to [target] are being refused. */
    fun isBlocked(target: File): Boolean = target.path in blocked

    /** A new ViewModel starts from defaults, so nothing is loaded for it yet.
     *  Returns the session's number, for [isCurrentSession]. */
    fun startSession(): Int {
        loaded.clear()
        held.clear()
        allOpenForTest = false
        SaveFloor.forgetNotices()
        Stores.forgetHealth()
        session += 1
        return session
    }

    /** Whether [number] is the newest session: a ViewModel on its way out isn't. */
    fun isCurrentSession(number: Int): Boolean = number == session

    /** [target]'s state is in place: gated saves may run. */
    fun openSaves(target: File) {
        held -= target.path
        loaded += target.path
        SaveFloor.forgetNotice(target)
    }

    /** [openSaves], unless a newer session has started: it hasn't loaded [target]. */
    fun openSaves(target: File, session: Int) {
        if (isCurrentSession(session)) openSaves(target)
    }

    /**
     * Refuse saves to [target] until [openSaves], whatever a load does: it's
     * about to be reloaded, and a snapshot built before the reload lands holds
     * what the reload replaces. A refusal here is quiet, as the store is about
     * to say what it holds.
     */
    fun closeSaves(target: File) {
        loaded -= target.path
        held += target.path
    }

    /** Whether [target] is between [closeSaves] and [openSaves]. */
    fun savesHeld(target: File): Boolean = target.path in held

    /** Whether a load has finished for [target] this session. */
    fun savesOpen(target: File): Boolean = allOpenForTest || target.path in loaded

    /** The previous good version kept beside [target]. */
    fun backupOf(target: File) = File(target.parentFile, target.name + BAK)

    private fun tmpOf(target: File) = File(target.parentFile, target.name + TMP)

    /** The kept-aside corrupt copies of [target], newest first. */
    fun keptAside(target: File): List<File> =
        (target.parentFile?.listFiles { f -> f.name.startsWith(target.name + CORRUPT) } ?: emptyArray())
            .sortedByDescending { it.name.substringAfterLast('-').toLongOrNull() ?: 0L }

    /** Atomically replace [target] with [text] (UTF-8). Throws on failure. */
    fun write(target: File, text: String, keepBackup: Boolean = true) =
        writeBytes(target, text.toByteArray(Charsets.UTF_8), keepBackup)

    /**
     * Atomically replace a CREDENTIAL file (sealed sign-in tokens): no `.bak`
     * of the previous version is kept, and any leftover `.bak`, `.tmp` or
     * `.corrupt-*` copy is deleted afterwards. Old tokens must not linger in
     * copies the backup / device-transfer rules cannot name (review 05 P3-5):
     * the previous session after a sign-out, or the plaintext token right
     * after the SecretBox migration re-save. That includes a copy [setAside]
     * kept: a credential is the one thing it doesn't keep for good, and signing
     * in again replaces what it held. Refused before its store has
     * loaded, unless [gated] is off for a store that doesn't load through
     * [load]. [what] is the store's name in the notice.
     */
    fun writeCredential(target: File, text: String, what: String = target.name, gated: Boolean = true) {
        if (gated && !SaveFloor.admit(target, what)) {
            throw IOException("${target.name} hasn't loaded; not saving over it")
        }
        synchronized(lockOf(target)) {
            writeBytes(target, text.toByteArray(Charsets.UTF_8), keepBackup = false)
            purgeCopies(target, keptAside = true)
        }
    }

    /**
     * Delete [target]'s `.bak` and `.tmp` siblings, and with [keptAside] its
     * `.corrupt-*` copies too. For credential stores, called after loading
     * (`.bak` / `.tmp` only, so a just-reported corrupt copy is still there
     * when the notice names it) and by [writeCredential].
     */
    fun purgeCopies(target: File, keptAside: Boolean = false): Unit = synchronized(lockOf(target)) {
        backupOf(target).delete()
        tmpOf(target).delete()
        if (keptAside) keptAside(target).forEach { it.delete() }
    }

    /**
     * Delete [target] and EVERY copy of it: the `.bak` of the previous save, a
     * leftover `.tmp`, and all `.corrupt-*` copies kept aside. For Erase all
     * data (final review 05 N-1): saving an empty value instead would rename
     * the full old file to `.bak`, where [load] could even bring it back.
     * Also lifts a save block on [target] (the unreadable file is gone).
     * Returns true when nothing of [target] remains on disk.
     */
    fun erase(target: File): Boolean = synchronized(lockOf(target)) {
        val copies = listOf(target, backupOf(target), tmpOf(target)) + keptAside(target)
        copies.forEach { it.delete() }
        blocked -= target.path
        copies.none { it.exists() }
    }

    /**
     * Delete every `.bak`, `.tmp` and `.corrupt-*` copy directly inside [dir]
     * (not recursive). The last step of Erase all data: a store saved while
     * the erase ran (an in-flight write of the empty state) leaves no copy of
     * the old file behind. Returns the names it could not delete.
     */
    fun eraseCopiesIn(dir: File): List<String> =
        (dir.listFiles { f -> f.isFile && isCopyName(f.name) } ?: emptyArray())
            .filterNot { it.delete() }
            .map { it.name }

    private fun isCopyName(name: String) = name.endsWith(BAK) || name.endsWith(TMP) || CORRUPT in name

    /** Atomically replace [target] with [bytes]. Throws on failure; the old target stays intact. */
    fun writeBytes(target: File, bytes: ByteArray, keepBackup: Boolean = false): Unit = synchronized(lockOf(target)) {
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
     * Read and [decode] [target]. A file that fails to decode is moved aside
     * (see [SafeLoad.Corrupt]); the backup and a complete leftover temp file are
     * tried before giving up. A file that can't be read at all stays put (see
     * [SafeLoad.Unavailable]). Each load decides afresh whether saves to
     * [target] are refused. [log] receives one line per event. On every outcome
     * it opens [target]'s saves, unless [open] is off: a load whose state lands
     * after this returns.
     */
    fun <T : Any> load(
        target: File,
        log: (String) -> Unit = {},
        open: Boolean = true,
        decode: (String) -> T,
    ): SafeLoad<T> = synchronized(lockOf(target)) {
        loadLocked(target, log, decode).also {
            afterLoad(target)
            if (open && target.path !in held) openSaves(target)
        }
    }

    /**
     * What a load would find, with no side effects: nothing moved, recovered,
     * refused or opened, whatever the outcome. For a read that isn't the store's
     * load, and for checking a file reads before reloading a store from it.
     */
    fun <T : Any> peek(target: File, decode: (String) -> T): SafeLoad<T> = synchronized(lockOf(target)) {
        var corrupt: Throwable? = null
        for (file in listOf(target, tmpOf(target), backupOf(target))) {
            val read = attempt(file, decode) ?: continue
            read.getOrNull()?.let { value ->
                return SafeLoad.Loaded(value, recoveredFrom = file.name.takeIf { file != target })
            }
            val error = read.exceptionOrNull() ?: continue
            if (!isDecodeFailure(error)) return SafeLoad.Unavailable(describe(error))
            corrupt = error
        }
        corrupt?.let { SafeLoad.Corrupt(null, describe(it)) } ?: SafeLoad.Absent
    }

    /** [decode] of [f]; null only if it isn't there. A failure to tell whether it
     *  is says nothing about the store, so that's a failed read, not an absence. */
    private fun <T : Any> attempt(f: File, decode: (String) -> T): Result<T>? = try {
        Files.readAttributes(f.toPath(), BasicFileAttributes::class.java)
        runCatching { decode(f.readText(Charsets.UTF_8)) }
    } catch (_: NoSuchFileException) {
        null
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun <T : Any> loadLocked(target: File, log: (String) -> Unit, decode: (String) -> T): SafeLoad<T> {
        val bak = backupOf(target)
        val tmp = tmpOf(target)
        fun unavailable(f: File, cause: Throwable): SafeLoad<T> {
            val error = describe(cause)
            blocked += target.path
            log("${f.name} couldn't be read ($error); left in place, saving is disabled")
            return SafeLoad.Unavailable(error)
        }

        blocked -= target.path
        val primary = attempt(target, decode)
        primary?.getOrNull()?.let { return SafeLoad.Loaded(it) }
        primary?.exceptionOrNull()?.takeUnless(::isDecodeFailure)?.let { return unavailable(target, it) }

        var keptAt: File? = null
        val error = primary?.exceptionOrNull()?.let(::describe)
        if (primary != null) {
            keptAt = quarantine(target, target)
            if (keptAt == null) {
                blocked += target.path
                log("${target.name} unreadable ($error); couldn't keep it aside, saving is disabled")
            } else {
                log("${target.name} unreadable ($error); kept aside as ${keptAt.name}")
            }
        }

        // A temp file that was fully written and synced but never renamed (a
        // crash between the two renames: it is the newest save, and only counts
        // if it decodes), then the previous good version.
        for (candidate in listOf(tmp, bak)) {
            val r = attempt(candidate, decode) ?: continue
            r.exceptionOrNull()?.takeUnless(::isDecodeFailure)?.let { return unavailable(candidate, it) }
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

    /**
     * Move what a load can't read out of [target]'s way, for a store that would
     * otherwise refuse saves for good: the target, or once that has gone the
     * temp file or backup that took its place. It's a rename, so it needs no
     * read, and it stays with the other kept-aside copies, past [MAX_KEPT]. Only
     * a store that's refusing saves: anything else is a good file, and a double
     * tap mustn't move a good backup. Lifts the refusal and leaves the store
     * [closeSaves]d, for the caller to reload; a rename that fails changes
     * nothing. Returns where the file went, or null if nothing moved.
     */
    fun setAside(target: File): File? = synchronized(lockOf(target)) {
        if (target.path !in blocked) return null
        val file = listOf(target, tmpOf(target), backupOf(target)).firstOrNull { it.exists() } ?: return null
        val kept = quarantine(file, target, CORRUPT + SET_ASIDE) ?: return null
        closeSaves(target)
        blocked -= target.path
        kept
    }

    /** Whether [e], thrown by a load, shows the file's content is bad. A
     *  `SerializationException` is an `IllegalArgumentException`. */
    private fun isDecodeFailure(e: Throwable) = e is IllegalArgumentException

    private fun describe(e: Throwable) = e.message ?: e.javaClass.simpleName

    /** Move [file] to `<target>.corrupt-<epochMs>` and prune to [MAX_KEPT]; null on failure. */
    private fun quarantine(file: File, target: File, tag: String = CORRUPT): File? {
        val dest = run {
            // Numbered after the newest copy kept, never before it: several
            // quarantines in one millisecond bump the number, and once the
            // prune deleted the oldest, the wall clock alone could reuse its
            // (lower) number and the newest copy would sort, and be pruned, as
            // the oldest.
            val newest = keptAside(target).firstOrNull()?.name?.substringAfterLast('-')?.toLongOrNull()
            var ms = maxOf(System.currentTimeMillis(), (newest ?: Long.MIN_VALUE) + 1)
            var f: File
            do { f = File(target.parentFile, target.name + tag + ms); ms++ } while (f.exists())
            f
        }
        if (!file.renameTo(dest)) return null
        keptAside(target).filterNot { SET_ASIDE in it.name.substringAfterLast(CORRUPT) }
            .drop(MAX_KEPT).forEach { it.delete() }
        return dest
    }

    /** Test seam: every path is open until the next session, for a test of one
     *  store, or one that seeds files before it builds a ViewModel. */
    internal fun openAllForTest() {
        allOpenForTest = true
    }

    /** Test seam: forget blocked and loaded targets between tests. */
    internal fun resetForTest() {
        blocked.clear()
        loaded.clear()
        held.clear()
        allOpenForTest = false
        beforeRename = {}
        afterLoad = {}
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

    /**
     * Reading or decoding failed for a reason that says nothing about the file,
     * so it may well be fine: nothing was moved, and saves are refused.
     */
    data class Unavailable(val error: String) : SafeLoad<Nothing>
}

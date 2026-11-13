package coffee.crema.persist

import android.util.Log
import coffee.crema.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A store file that couldn't be read at load time. [what] is the user-facing
 * name ("shot history"); [keptAt] is the moved-aside copy, when there is one.
 * [olderCopy] says the store came back from its backup, so the newest save is
 * gone. [setAside] says the user moved it, from the banner.
 */
data class KeptAside(
    val what: String,
    val keptAt: File?,
    val olderCopy: Boolean = false,
    val setAside: Boolean = false,
)

/** How a store's load went. A store nothing has loaded yet is [LOADING]. */
enum class StoreHealth { LOADING, OK, ABSENT, DAMAGED, UNAVAILABLE }

/** A store whose saves are being refused, for the banner. */
data class RefusedStore(val what: String, val file: File)

/**
 * Each store's [StoreHealth], and the stores saves are being refused for.
 * Filled by [loadStore]: process-wide, like the refusals in [SafeFile].
 */
object Stores {
    private val health = ConcurrentHashMap<String, StoreHealth>()
    private val whats = ConcurrentHashMap<String, String>()
    private val _refused = MutableStateFlow<List<RefusedStore>>(emptyList())

    /** The stores whose saves are being refused: while it isn't empty, the banner stays. */
    val refused: StateFlow<List<RefusedStore>> = _refused

    fun health(file: File): StoreHealth = health[file.path] ?: StoreHealth.LOADING

    /** What a backup made now would be missing: the stores whose load came out
     *  damaged or unavailable this session, by name. */
    fun unhealthy(): List<String> =
        health.filterValues { it == StoreHealth.DAMAGED || it == StoreHealth.UNAVAILABLE }
            .keys.mapNotNull { whats[it] }.distinct().sorted()

    internal fun record(target: File, what: String, outcome: SafeLoad<*>) {
        whats[target.path] = what
        health[target.path] = when (outcome) {
            SafeLoad.Absent -> StoreHealth.ABSENT
            is SafeLoad.Loaded -> StoreHealth.OK
            is SafeLoad.Corrupt -> StoreHealth.DAMAGED
            is SafeLoad.Unavailable -> StoreHealth.UNAVAILABLE
        }
        val refused = SafeFile.isBlocked(target)
        _refused.update { list ->
            val others = list.filterNot { it.file == target }
            if (refused) others + RefusedStore(what, target) else others
        }
    }

    /** A new session: nothing has loaded for it yet. A refusal stays until a
     *  load settles it, as [SafeFile] still holds it. */
    internal fun forgetHealth() = health.clear()

    /** [target] has gone (Erase all data): nothing is left to refuse. */
    internal fun forget(target: File) {
        health.remove(target.path)
        whats.remove(target.path)
        _refused.update { list -> list.filterNot { it.file == target } }
    }

    /** Test seam: forget everything between tests. */
    internal fun resetForTest() {
        health.clear()
        whats.clear()
        _refused.value = emptyList()
    }
}

/**
 * Process-wide queue of unreadable stores, filled by [loadStore] and drained by
 * the ViewModel into a one-time snackbar. Each corrupt file is reported once:
 * it is moved aside as it is reported, so the next launch loads cleanly.
 */
object KeptAsideNotices {
    private val _pending = MutableStateFlow<List<KeptAside>>(emptyList())
    val pending: StateFlow<List<KeptAside>> = _pending

    fun report(item: KeptAside) = _pending.update { it + item }

    /** Take everything reported so far (the caller shows it). */
    fun drain(): List<KeptAside> {
        var taken = emptyList<KeptAside>()
        _pending.update { taken = it; emptyList() }
        return taken
    }

    /** The snackbar line for [items]: "Couldn't read your shot history and bean library; …". */
    fun message(items: List<KeptAside>): String {
        val (older, rest) = items.partition { it.olderCopy }
        val (aside, unreadable) = rest.partition { it.setAside }
        return listOfNotNull(
            unreadableLine(unreadable).takeIf { unreadable.isNotEmpty() || items.isEmpty() },
            olderLine(older).takeIf { older.isNotEmpty() },
            "Your ${names(aside)} file was set aside, and you can share it from here.".takeIf { aside.isNotEmpty() },
        ).joinToString(" ")
    }

    private fun names(items: List<KeptAside>): String {
        val names = items.map { it.what }.distinct()
        return when (names.size) {
            0 -> "some saved data"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
    }

    private fun olderLine(items: List<KeptAside>) =
        "Your last change to your ${names(items)} couldn't be read, so the version before it was restored."

    private fun unreadableLine(items: List<KeptAside>): String {
        val list = names(items)
        val kept = items.any { it.keptAt != null }
        val tail = when {
            !kept -> "it was left in place and won't be overwritten"
            items.size == 1 -> "the damaged file was kept aside"
            else -> "the damaged files were kept aside"
        }
        return "Couldn't read your $list; $tail."
    }
}

private const val TAG = "SafeFile"

private fun persistLog(line: String) {
    DiagLog.add("persist: $line")
    runCatching { Log.w(TAG, line) } // android.util.Log isn't available in JVM tests
}

/**
 * The load floor: no save to a store that hasn't loaded. Saves are whole
 * snapshots, so a save before the load carries defaults, and landing it later
 * would be the same loss, which is why a refused save isn't queued. The first
 * refusal per path logs and queues one notice, until the path opens or a new
 * session starts. [saveStore] checks on entry, and so does a store that takes a
 * lock of its own first: a save that waits while the store loads would otherwise
 * be let through once the load had opened it, still carrying its old snapshot.
 * Under unit tests
 * (the build's `crema.strictSaves`) a refusal throws, so it fails the test that
 * caused it. A store being reloaded ([SafeFile.closeSaves]) refuses quietly.
 */
object SaveFloor {
    private val noticed: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val strict = System.getProperty("crema.strictSaves") == "true"
    private val _pending = MutableStateFlow<List<String>>(emptyList())

    /** What a refused save was for, until [drain] takes it. */
    val pending: StateFlow<List<String>> = _pending

    /** Test seam: a test of refusal itself turns this off. */
    internal var throwOnRefusal: Boolean = strict

    /** Whether a save to [target] may run; refusing tells whoever should know. */
    fun admit(target: File, what: String): Boolean {
        if (SafeFile.savesHeld(target)) return false
        if (SafeFile.savesOpen(target)) return true
        if (throwOnRefusal) throw AssertionError("saved ${target.name} before it loaded")
        if (noticed.add(target.path)) {
            persistLog("refused a save to ${target.name}: it hasn't loaded")
            _pending.update { it + what }
        }
        return false
    }

    /** Take the refusals so far (the caller shows them). */
    fun drain(): List<String> {
        var taken = emptyList<String>()
        _pending.update { taken = it; emptyList() }
        return taken
    }

    /** A refusal of [target] is news again. */
    internal fun forgetNotice(target: File) {
        noticed -= target.path
    }

    /** A new session: every refusal is news again. */
    internal fun forgetNotices() = noticed.clear()

    /** The line for a refused save to [what]. A shot can't be made again. */
    fun message(what: String) =
        if (what == HISTORY) {
            "Crema was still loading your $what, so the latest change to it wasn't saved."
        } else {
            "Crema was still loading your $what, so that change wasn't saved. Please make it again."
        }

    private const val HISTORY = "shot history"

    /** Test seam: forget the notices between tests. */
    internal fun resetForTest() {
        noticed.clear()
        _pending.value = emptyList()
        throwOnRefusal = strict
    }
}

/**
 * [SafeFile.load] for a store: logs to logcat + the crash-report buffer, records
 * its [StoreHealth], and for a store with a user-facing [what] queues a
 * [KeptAsideNotices] entry when it's Corrupt, or came back from its backup.
 * Pass `what = null` for caches the app can rebuild silently (e.g. the
 * stolen-serials list). A store that refuses saves shows in [Stores.refused].
 * See [SafeFile.load] for [open].
 */
fun <T : Any> loadStore(target: File, what: String?, open: Boolean = true, decode: (String) -> T): SafeLoad<T> {
    val outcome = loadItem(target, what, open, decode)
    if (what != null) Stores.record(target, what, outcome)
    return outcome
}

/**
 * [loadStore] for one file of many (a shot's telemetry): the caller says what a
 * file it can't read means, so it's no store's health, and no banner. Saves to
 * a file that couldn't be read are still refused.
 */
fun <T : Any> loadItem(target: File, what: String?, open: Boolean, decode: (String) -> T): SafeLoad<T> {
    val outcome = SafeFile.load(target, ::persistLog, open, decode)
    if (what != null) {
        when {
            outcome is SafeLoad.Corrupt -> KeptAsideNotices.report(KeptAside(what, outcome.keptAt))
            outcome is SafeLoad.Loaded && outcome.recoveredFrom == SafeFile.backupOf(target).name ->
                KeptAsideNotices.report(
                    KeptAside(what, SafeFile.keptAside(target).firstOrNull(), olderCopy = true),
                )
        }
    }
    return outcome
}

/**
 * The one safe way to save a store: refused before it has loaded ([SaveFloor],
 * checked here, on entry, so a stale snapshot can't wait out a load), then in
 * call order ([inSaveOrder]), then [encode] and the write on IO. Encoding here
 * keeps a big store off the caller's thread. A save that has been asked for
 * always lands: a cancelled scope can't drop it while it waits its turn, or the
 * store's file would stay behind what the user saw. True when stored.
 */
suspend fun saveStore(target: File, what: String, keepBackup: Boolean = true, encode: () -> String): Boolean {
    if (!SaveFloor.admit(target, what)) return false
    return withContext(NonCancellable) {
        inSaveOrder(target) {
            runCatching(encode)
                .onFailure { persistLog("couldn't encode ${target.name}: ${it.message}") }
                .map { saveItem(target, it, keepBackup) }
                .getOrDefault(false)
        }
    }
}

/**
 * [SafeFile.write] for a file with no load to wait for, logging (not throwing)
 * a failure; true when stored. For one file of many, or one that's only read
 * now and then: `hot-water.json` is written on every save and read when the
 * mode is missing, so the floor would refuse it on every normal install.
 */
fun saveItem(target: File, text: String, keepBackup: Boolean = true): Boolean =
    runCatching { SafeFile.write(target, text, keepBackup) }
        .onFailure { persistLog("couldn't save ${target.name}: ${it.message}") }
        .isSuccess

/** One FIFO queue per store file. */
private val saveQueues = ConcurrentHashMap<String, Mutex>()

/**
 * Run [block] on IO, after every earlier call for [target], for a store saved as
 * whole snapshots. [SafeFile] keeps overlapping saves from colliding, but not in
 * order: two launched in a row hop to IO on their own, and the older snapshot
 * could land last. The queue is taken before the hop, so call order wins. A
 * caller on Main releases its turn on Main, so never block Main on this queue.
 */
suspend fun <T> inSaveOrder(target: File, block: () -> T): T =
    saveQueues.getOrPut(target.path) { Mutex() }.withLock { withContext(Dispatchers.IO) { block() } }

/** [SafeFile.erase], logging a file that could not be deleted; true when gone. */
fun eraseStore(target: File): Boolean =
    SafeFile.erase(target).also { gone ->
        if (gone) Stores.forget(target) else persistLog("couldn't erase every copy of ${target.name}")
    }

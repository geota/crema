package coffee.crema.persist

import android.util.Log
import coffee.crema.diag.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * A store file that couldn't be read at load time. [what] is the user-facing
 * name ("shot history"); [keptAt] is the moved-aside copy, when there is one.
 * [olderCopy] says the store came back from its backup, so the newest save is
 * gone.
 */
data class KeptAside(val what: String, val keptAt: File?, val olderCopy: Boolean = false)

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
    private val _refused = MutableStateFlow<List<RefusedStore>>(emptyList())

    /** The stores whose saves are being refused: while it isn't empty, the banner stays. */
    val refused: StateFlow<List<RefusedStore>> = _refused

    fun health(file: File): StoreHealth = health[file.path] ?: StoreHealth.LOADING

    internal fun record(target: File, what: String, outcome: SafeLoad<*>) {
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

    /** [target] has gone (Erase all data): nothing is left to refuse. */
    internal fun forget(target: File) {
        health.remove(target.path)
        _refused.update { list -> list.filterNot { it.file == target } }
    }

    /** Test seam: forget everything between tests. */
    internal fun resetForTest() {
        health.clear()
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
        val (older, unreadable) = items.partition { it.olderCopy }
        return listOfNotNull(
            unreadableLine(unreadable).takeIf { unreadable.isNotEmpty() || older.isEmpty() },
            olderLine(older).takeIf { older.isNotEmpty() },
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
 * [SafeFile.load] for a store: logs to logcat + the crash-report buffer, records
 * its [StoreHealth], and for a store with a user-facing [what] queues a
 * [KeptAsideNotices] entry when it's Corrupt, or came back from its backup.
 * Pass `what = null` for caches the app can rebuild silently (e.g. the
 * stolen-serials list). A store that refuses saves shows in [Stores.refused].
 */
fun <T : Any> loadStore(target: File, what: String?, decode: (String) -> T): SafeLoad<T> {
    val outcome = loadItem(target, what, decode)
    if (what != null) Stores.record(target, what, outcome)
    return outcome
}

/**
 * [loadStore] for one file of many (a shot's telemetry): the caller says what a
 * file it can't read means, so it's no store's health, and no banner. Saves to
 * a file that couldn't be read are still refused.
 */
fun <T : Any> loadItem(target: File, what: String?, decode: (String) -> T): SafeLoad<T> {
    val outcome = SafeFile.load(target, ::persistLog, decode)
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

/** [SafeFile.write], logging (not throwing) a failure; true when stored. */
fun saveStore(target: File, text: String, keepBackup: Boolean = true): Boolean =
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

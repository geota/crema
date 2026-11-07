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
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * A store file that couldn't be read at load time. [what] is the user-facing
 * name ("shot history"); [keptAt] is the moved-aside copy, when there is one.
 */
data class KeptAside(val what: String, val keptAt: File?)

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
        val names = items.map { it.what }.distinct()
        val list = when (names.size) {
            0 -> "some saved data"
            1 -> names[0]
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
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
 * refusal per path logs and queues one notice. Under unit tests (the build's
 * `crema.strictSaves`) a refusal throws, so it fails the test that caused it.
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

    /** The line for a refused save to [what]. */
    fun message(what: String) = "Crema was still loading your $what, so that change wasn't saved. Please make it again."

    /** Test seam: forget the notices between tests. */
    internal fun resetForTest() {
        noticed.clear()
        _pending.value = emptyList()
        throwOnRefusal = strict
    }
}

/**
 * [SafeFile.load] for a store: logs to logcat + the crash-report buffer, and a
 * Corrupt outcome for a store with a user-facing [what] queues a
 * [KeptAsideNotices] entry. Pass `what = null` for caches the app can rebuild
 * silently (e.g. the stolen-serials list). See [SafeFile.load] for [open].
 */
fun <T : Any> loadStore(target: File, what: String?, open: Boolean = true, decode: (String) -> T): SafeLoad<T> {
    val outcome = SafeFile.load(target, ::persistLog, open, decode)
    if (outcome is SafeLoad.Corrupt && what != null) KeptAsideNotices.report(KeptAside(what, outcome.keptAt))
    return outcome
}

/** [saveItem] for a store: refused before it has loaded (see [SaveFloor]). */
fun saveStore(target: File, what: String, text: String, keepBackup: Boolean = true): Boolean =
    SaveFloor.admit(target, what) && saveItem(target, text, keepBackup)

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
    SafeFile.erase(target).also { gone -> if (!gone) persistLog("couldn't erase every copy of ${target.name}") }

package coffee.crema.persist

import android.util.Log
import coffee.crema.diag.DiagLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

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
 * [SafeFile.load] for a store: logs to logcat + the crash-report buffer, and a
 * Corrupt outcome for a store with a user-facing [what] queues a
 * [KeptAsideNotices] entry. Pass `what = null` for caches the app can rebuild
 * silently (e.g. the stolen-serials list).
 */
fun <T : Any> loadStore(target: File, what: String?, decode: (String) -> T): SafeLoad<T> {
    val outcome = SafeFile.load(target, ::persistLog, decode)
    if (outcome is SafeLoad.Corrupt && what != null) KeptAsideNotices.report(KeptAside(what, outcome.keptAt))
    return outcome
}

/** [SafeFile.write], logging (not throwing) a failure; true when stored. */
fun saveStore(target: File, text: String, keepBackup: Boolean = true): Boolean =
    runCatching { SafeFile.write(target, text, keepBackup) }
        .onFailure { persistLog("couldn't save ${target.name}: ${it.message}") }
        .isSuccess

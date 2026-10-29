package coffee.crema

import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A process-lifetime coroutine scope for short, best-effort work that must
 * outlive a ViewModel: `viewModelScope` is already cancelled by the time
 * `onCleared()` runs (lifecycle 2.x closes it first), so anything launched
 * from teardown has to live here. Never cancelled; the process ending is
 * what ends it. Keep work on it bounded (a timeout) so nothing lingers.
 *
 * Lazy so JVM unit tests that never touch it don't need a main looper.
 */
object ProcessScope {
    val main: CoroutineScope by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.Main.immediate +
                CoroutineExceptionHandler { _, e -> Log.e("ProcessScope", "Uncaught in process scope", e) },
        )
    }
}

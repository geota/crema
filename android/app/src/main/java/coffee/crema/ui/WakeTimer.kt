package coffee.crema.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The core's wake hint (`Event.WakeAt`, F16): the core asks to be ticked at a
 * time it is waiting for (the end of the profile-download guard, …), between
 * [CoreTicker]'s once-a-second ticks. One one-shot timer: each hint replaces
 * the pending one, and it is cancelled when the DE1 link goes (the core
 * resets) or the app is backgrounded (the 1 s tick still covers the deadline
 * while it runs). Web twin: `lib/state/wake-timer.ts`.
 */
internal class WakeTimer(
    private val scope: CoroutineScope,
    /** The clock the core's times are in (`SystemClock.elapsedRealtime`). */
    private val now: () -> Long,
    /** Tick the core at the given time. */
    private val tick: suspend (Long) -> Unit,
    /** Where the wait runs (a test dispatcher makes it virtual time). */
    private val context: CoroutineContext = EmptyCoroutineContext,
) {
    private var job: Job? = null

    /** A wake is pending. */
    val pending: Boolean @Synchronized get() = job?.isActive == true

    /** Tick at [atMs], replacing any pending wake. */
    @Synchronized
    fun arm(atMs: Long) {
        job?.cancel()
        job = scope.launch(context) {
            delay((atMs - now()).coerceAtLeast(0))
            tick(maxOf(atMs, now()))
        }
    }

    /** Drop the pending wake, if any. */
    @Synchronized
    fun cancel() {
        job?.cancel()
        job = null
    }
}

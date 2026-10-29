package coffee.crema.ui

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sleep-on-quit: put the DE1 to sleep when the user QUITS Crema (swipes the
 * task out of recents) — and only then. Backgrounding must never sleep the
 * machine by itself: the idle-sleep timer ("Sleep after", core
 * `CremaCore::idle_sleep`) keeps counting in the background and sleeps it
 * after the user's idle timeout while the process lives.
 *
 * Two quit signals feed one latched handler, because neither alone is
 * reliable (verified on an API-36 emulator):
 *
 *  1. **[MainViewModel.onCleared]** — a swipe destroys the activity
 *     gracefully ~400 ms before the OS kills the process, so the VM's
 *     teardown is the PRIMARY hook. `viewModelScope` is ALREADY cancelled
 *     when `onCleared` runs (lifecycle 2.x closes it first), so the Sleep
 *     write goes out on [coffee.crema.ProcessScope] instead, bounded by
 *     [QUIT_WRITE_BUDGET_MS], and the VM defers its BLE teardown until the
 *     write settles ([teardownAfterQuitWrite]). (Until the 2026-10-25 cohort
 *     review this path launched on the dead scope and never wrote anything.)
 *  2. **[QuitSleepService.onTaskRemoved]** — the fallback for devices that
 *     kill the task without destroying the activity. On the emulator the
 *     process died 4 ms after this callback, so it is strictly a backup;
 *     [MainActivity] re-starts the service on every foreground entry
 *     because the system stops idle background services.
 *
 * Best-effort by design: a force-stop or OOM kill delivers nothing
 * anywhere, and a swipe long after backgrounding may miss both signals. A
 * stock DE1 has no sleep timer of its own to fall back on (with the presence
 * flag set it only drops to UserNotPresent), so once the process is gone
 * nothing sleeps it until it's next connected; a Bengle does fall back, on
 * the firmware inactivity timeout the core sets to the same minutes. The
 * idle timer and this path never double-send: the core adopts this Sleep
 * as the idle episode's, and this path skips while an idle Sleep is in
 * flight (`quitSleepAllowed`).
 */
object QuitSleep {
    /** One VM's installed handler. The handler returns the in-flight Sleep
     *  write (true = it went out), or null when a gate declined — a
     *  deliberate "don't sleep" that stays latched. */
    private class Registration(val handler: () -> Deferred<Boolean>?)

    /** The installed registration; a newer VM's replaces an older one's. */
    private var current: Registration? = null

    /** One quit = one fire, whichever signal lands first — but only a fire
     *  that SENT (or deliberately declined) holds the latch: a write that
     *  failed or never went out re-arms it so the other signal can retry. */
    private var fired = false

    /** The write the latched fire started, while it runs: a later [fire]
     *  hands it out so `onCleared` waits for a write the service started. */
    private var inFlight: Deferred<Boolean>? = null

    /** Install the quit handler — called once from [MainViewModel]'s init.
     *  The handler re-checks every gate (pref, machine idle, primary role)
     *  at fire time, so installation is unconditional. Returns the
     *  registration to hand back to [clear]. */
    @Synchronized
    fun install(handler: () -> Deferred<Boolean>?): Any {
        val registration = Registration(handler)
        current = registration
        fired = false
        inFlight = null
        return registration
    }

    /** Drop [registration]'s handler — from [MainViewModel.onCleared]'s
     *  deferred teardown, once its own write settled. A compare-and-clear:
     *  that teardown runs up to ~1.75 s late, and a VM built in the meantime
     *  (a relaunch right after the swipe, same process) has installed its
     *  own handler, which must survive (final cohort review 2026-10-28, 04
     *  P3-1). After this a late task-removal is a no-op. */
    @Synchronized
    fun clear(registration: Any?) {
        if (registration != null && current === registration) current = null
    }

    /** Test-only: forget every registration and the latch. */
    @Synchronized
    internal fun reset() {
        current = null
        fired = false
        inFlight = null
    }

    /** Invoke the installed handler at most once per successful send. Main
     *  thread. Returns the in-flight write — including, when the latch is
     *  already held, the one the other signal started and that is still
     *  running, so `onCleared` never tears the link down under it — or null
     *  when nothing is in flight (no handler installed — which does NOT
     *  latch — a settled fire, or a gate declined). */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun fire(): Deferred<Boolean>? {
        val registration: Registration
        synchronized(this) {
            registration = current ?: return null
            if (fired) return inFlight?.takeUnless { it.isCompleted }
            fired = true
        }
        val write = registration.handler() ?: return null
        synchronized(this) {
            if (current === registration) inFlight = write
        }
        write.invokeOnCompletion { cause ->
            val sent = cause == null && write.getCompleted()
            synchronized(this) {
                if (inFlight === write) inFlight = null
                // Re-arm only this registration's latch: a successor VM's
                // must not be released by its predecessor's failed write.
                if (!sent && current === registration) fired = false
            }
        }
        return write
    }
}

/** Ceiling on the quit Sleep write (connect-interval + one retry fit easily);
 *  the VM's BLE teardown waits at most this (plus slack) for it. */
const val QUIT_WRITE_BUDGET_MS = 1_500L

/**
 * Send the quit Sleep [writes] in order within [budgetMs]. True only when
 * every write completed; a throw, a timeout or an empty list is false (the
 * latch then re-arms). Cancellation of the caller still propagates.
 */
internal suspend fun sendQuitWrites(writes: List<suspend () -> Unit>, budgetMs: Long): Boolean {
    if (writes.isEmpty()) return false
    return try {
        withTimeoutOrNull(budgetMs) {
            writes.forEach { it() }
            true
        } ?: false
    } catch (t: TimeoutCancellationException) {
        false // a write's own timeout, not ours
    } catch (c: CancellationException) {
        throw c
    } catch (e: Exception) {
        false
    }
}

/**
 * Run [teardown] once [pending] settles or [budgetMs] passes — on [scope],
 * which must outlive the caller (the VM's own scope is already cancelled in
 * `onCleared`). With nothing pending it runs synchronously, exactly as
 * before sleep-on-quit had to wait for anything.
 */
internal fun teardownAfterQuitWrite(
    scope: CoroutineScope,
    pending: Deferred<Boolean>?,
    budgetMs: Long,
    teardown: () -> Unit,
) {
    if (pending == null || pending.isCompleted) {
        teardown()
        return
    }
    scope.launch {
        try {
            withTimeoutOrNull(budgetMs) { pending.join() }
        } finally {
            teardown()
        }
    }
}

/**
 * The do-nothing started service whose sole purpose is receiving
 * [onTaskRemoved]. Not a foreground service on purpose: a persistent
 * notification (and surviving the swipe entirely) would contradict the
 * "quit means quit" semantics this feature exists for.
 */
class QuitSleepService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_NOT_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The in-app event log dies with the task — logcat is the only
        // place this path can leave a trace for field debugging.
        android.util.Log.i(TAG, "task removed — firing sleep-on-quit")
        QuitSleep.fire()
        // The sleep request is an async GATT write on ProcessScope, bounded
        // by QUIT_WRITE_BUDGET_MS; the process survives while this service
        // runs, so hold on that long for the write to flush before letting go.
        Handler(mainLooper).postDelayed({ stopSelf() }, FLUSH_MS)
    }

    private companion object {
        const val TAG = "QuitSleep"
        const val FLUSH_MS = QUIT_WRITE_BUDGET_MS
    }
}

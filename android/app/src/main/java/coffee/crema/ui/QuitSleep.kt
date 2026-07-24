package coffee.crema.ui

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sleep-on-quit: put the DE1 to sleep when the user QUITS Crema (swipes the
 * task out of recents) — and only then. Backgrounding must never sleep the
 * machine: the presence model already handles that case (the keep-awake
 * pokes pause and the DE1's own ~30 min timer takes over).
 *
 * Two quit signals feed one latched handler, because neither alone is
 * reliable (verified on an API-36 emulator):
 *
 *  1. **[MainViewModel.onCleared]** — a swipe destroys the activity
 *     gracefully ~400 ms before the OS kills the process, so the VM's
 *     teardown is the PRIMARY hook. It fires before `super.onCleared()`
 *     cancels `viewModelScope`: the scope's `Main.immediate` dispatch runs
 *     the sleep write synchronously to its first suspension, handing the
 *     GATT op to the system Bluetooth stack (which outlives our process).
 *  2. **[QuitSleepService.onTaskRemoved]** — the fallback for devices that
 *     kill the task without destroying the activity. On the emulator the
 *     process died 4 ms after this callback, so it is strictly a backup;
 *     [MainActivity] re-starts the service on every foreground entry
 *     because the system stops idle background services.
 *
 * Best-effort by design: a force-stop or OOM kill delivers nothing
 * anywhere, and a swipe long after backgrounding may miss both signals —
 * the DE1's own sleep timer (counting since the pokes stopped) still puts
 * the machine down in every fallback case.
 */
object QuitSleep {
    @Volatile
    private var handler: (() -> Unit)? = null

    /** One quit = one fire, whichever signal lands first. */
    private val fired = AtomicBoolean(false)

    /** Install the quit handler — called once from [MainViewModel]'s init.
     *  The handler re-checks every gate (pref, machine idle, primary role)
     *  at fire time, so installation is unconditional. */
    fun install(handler: () -> Unit) {
        this.handler = handler
        fired.set(false)
    }

    /** Drop the handler — [MainViewModel.onCleared], after its own fire.
     *  After this a late task-removal is a no-op. */
    fun clear() {
        handler = null
    }

    /** Invoke the installed handler at most once per install. Main thread. */
    fun fire() {
        if (fired.getAndSet(true)) return
        handler?.invoke()
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
        // The sleep request is an async GATT write on the BLE manager's
        // scope; the process survives while this service runs, so hold on
        // briefly for the write to flush before letting go.
        Handler(mainLooper).postDelayed({ stopSelf() }, FLUSH_MS)
    }

    private companion object {
        const val TAG = "QuitSleep"
        const val FLUSH_MS = 1_500L
    }
}

package coffee.crema.ui

import coffee.crema.ble.De1BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The steady core clock while the DE1 link is up or coming up (cohort review
 * 2026-10-28, #2 N1). Mirrors the web `CoreTicker`.
 *
 * `CremaCore::on_tick` runs the profile-upload ack watchdog, the wait for the
 * machine model, the repeat of an outstanding re-upload request, the lost-scale
 * watchdog and steam eco. Its only caller used to be the guided-brew tick, so
 * an upload whose last write failed never failed. The guided-brew 250 ms tick
 * keeps running beside this one; `on_tick` computes from timestamps, so extra
 * calls are harmless.
 */
internal class CoreTicker(
    private val scope: CoroutineScope,
    private val intervalMs: Long = CORE_TICK_MS,
    private val tick: suspend () -> Unit,
) {
    private var job: Job? = null

    /** Whether the clock is running. */
    val running: Boolean get() = job?.isActive == true

    /** Follow the DE1 link [state]: tick while it is up or coming up. */
    fun onLinkState(state: De1BleManager.State) {
        if (ticks(state)) start() else stop()
    }

    fun start() {
        if (running) return
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                runCatching { tick() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Tick cadence — the watchdogs work in whole seconds. */
        const val CORE_TICK_MS = 1_000L

        /** Link states in which the core ticks. */
        fun ticks(state: De1BleManager.State): Boolean = when (state) {
            De1BleManager.State.CONNECTING,
            De1BleManager.State.DISCOVERING,
            De1BleManager.State.SUBSCRIBING,
            De1BleManager.State.READY -> true
            De1BleManager.State.IDLE,
            De1BleManager.State.SCANNING,
            De1BleManager.State.DISCONNECTED -> false
        }
    }
}

package coffee.crema.ui

import coffee.crema.ble.De1BleManager
import coffee.crema.ble.ScaleBleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The steady core clock while the DE1 or the scale link is up or coming up
 * (cohort review 2026-10-28, #2 N1). Mirrors the web `CoreTicker`.
 *
 * A scale alone keeps it ticking: the lost-scale watchdog (`ScaleStale`,
 * `ScaleLinkDead`) runs on this clock, and a scale-only session otherwise
 * never noticed a dead scale link.
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
    private var de1Up = false
    private var scaleUp = false
    private var grinderUp = false

    /** Whether the clock is running. */
    val running: Boolean get() = job?.isActive == true

    /** Follow the DE1 link [state]: tick while it (or the scale's) is up or coming up. */
    fun onLinkState(state: De1BleManager.State) {
        de1Up = ticks(state)
        follow()
    }

    /** Follow the scale link [state]: tick while it (or the DE1's) is up or coming up. */
    fun onScaleState(state: ScaleBleManager.State) {
        scaleUp = ticks(state)
        follow()
    }

    /** Follow the grinder link [state]: its first-reading and silence
     *  deadlines run on the core's tick, so a grinder alone keeps it going. */
    fun onGrinderState(state: coffee.crema.ble.GrinderBleManager.State) {
        grinderUp = ticks(state)
        follow()
    }

    private fun follow() {
        if (de1Up || scaleUp || grinderUp) start() else stop()
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

        /** Grinder link states in which the core ticks (the same rule). */
        fun ticks(state: coffee.crema.ble.GrinderBleManager.State): Boolean = when (state) {
            coffee.crema.ble.GrinderBleManager.State.CONNECTING,
            coffee.crema.ble.GrinderBleManager.State.DISCOVERING,
            coffee.crema.ble.GrinderBleManager.State.SUBSCRIBING,
            coffee.crema.ble.GrinderBleManager.State.READY -> true
            coffee.crema.ble.GrinderBleManager.State.IDLE,
            coffee.crema.ble.GrinderBleManager.State.SCANNING,
            coffee.crema.ble.GrinderBleManager.State.DISCONNECTED -> false
        }

        /** Scale link states in which the core ticks (the same rule). */
        fun ticks(state: ScaleBleManager.State): Boolean = when (state) {
            ScaleBleManager.State.CONNECTING,
            ScaleBleManager.State.DISCOVERING,
            ScaleBleManager.State.SUBSCRIBING,
            ScaleBleManager.State.READY -> true
            ScaleBleManager.State.IDLE,
            ScaleBleManager.State.SCANNING,
            ScaleBleManager.State.DISCONNECTED -> false
        }
    }
}

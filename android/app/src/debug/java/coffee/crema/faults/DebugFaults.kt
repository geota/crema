package coffee.crema.faults

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What is armed. [stopsToDrop] counts down one per dropped `Stop` write;
 * [dropNextRequest] clears on the one plain requested-state write it drops;
 * the weight window runs until [weightUntilMs] on the
 * `SystemClock.elapsedRealtime` clock.
 */
data class FaultStatus(
    val stopsToDrop: Int = 0,
    val dropNextRequest: Boolean = false,
    val weightUntilMs: Long? = null,
) {
    /** Anything is armed at [nowMs]. */
    fun active(nowMs: Long): Boolean =
        stopsToDrop > 0 || dropNextRequest || (weightUntilMs != null && nowMs < weightUntilMs)
}

/**
 * The debug build's [FaultInjector] (DESIGN.md §7's three hooks). Arming is
 * the Debug screen's; each drop is logged through [log], so the run's log
 * shows exactly what was lost and when.
 */
class DebugFaults(
    private val now: () -> Long,
    private val log: (String) -> Unit,
) : FaultInjector {
    private val _status = MutableStateFlow(FaultStatus())
    val status: StateFlow<FaultStatus> = _status.asStateFlow()

    /** Weight frames dropped in the current window, for its closing line. */
    private var weightDropped = 0

    /** Drop the next [n] `Stop` writes (0 disarms). */
    fun armStops(n: Int) {
        val count = n.coerceAtLeast(0)
        _status.update { it.copy(stopsToDrop = count) }
        log(if (count > 0) "FAULT armed: drop the next $count Stop write(s)" else "FAULT disarmed: Stop writes")
    }

    /** Drop the next plain requested-state write. */
    fun armRequest(on: Boolean = true) {
        _status.update { it.copy(dropNextRequest = on) }
        log(if (on) "FAULT armed: drop the next requested-state write" else "FAULT disarmed: requested-state write")
    }

    /** Drop every weight frame for [seconds] from now (0 disarms). */
    fun armWeight(seconds: Int) {
        closeWeightWindow()
        if (seconds <= 0) {
            _status.update { it.copy(weightUntilMs = null) }
            log("FAULT disarmed: weight frames")
            return
        }
        _status.update { it.copy(weightUntilMs = now() + seconds * 1000L) }
        log("FAULT armed: drop weight frames for $seconds s")
    }

    /** Disarm everything. */
    fun disarmAll() {
        closeWeightWindow()
        _status.value = FaultStatus()
        log("FAULT disarmed: all")
    }

    @Synchronized
    override fun dropStop(): Boolean {
        val left = _status.value.stopsToDrop
        if (left <= 0) return false
        _status.update { it.copy(stopsToDrop = left - 1) }
        log("FAULT: dropped a Stop write (${left - 1} more to drop)")
        return true
    }

    @Synchronized
    override fun dropRequestedState(): Boolean {
        if (!_status.value.dropNextRequest) return false
        _status.update { it.copy(dropNextRequest = false) }
        log("FAULT: dropped a requested-state write")
        return true
    }

    @Synchronized
    override fun dropWeightFrame(nowMs: Long): Boolean {
        val until = _status.value.weightUntilMs ?: return false
        if (nowMs < until) {
            weightDropped++
            log("FAULT: dropped a weight frame ($weightDropped in this window)")
            return true
        }
        closeWeightWindow()
        _status.update { it.copy(weightUntilMs = null) }
        return false
    }

    /** End the weight window's log with how many frames it dropped. */
    @Synchronized
    private fun closeWeightWindow() {
        if (_status.value.weightUntilMs == null) return
        log("FAULT: weight window over, $weightDropped frame(s) dropped")
        weightDropped = 0
    }
}

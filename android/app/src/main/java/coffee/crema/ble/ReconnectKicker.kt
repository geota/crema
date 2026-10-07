package coffee.crema.ble

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The "try again NOW" handle on one device's [reconnectingSession].
 *
 * The supervisor waits out its backoff (500 ms doubling to 30 s) and then the
 * slow 60 s lurk tier with a plain timer — which is exactly what made coming
 * back to the app slow: a device that came back into range while the app was
 * away is only retried when the timer next fires. A [kick] interrupts that
 * wait: the supervisor resets its attempt counter to the fast burst and
 * attempts immediately.
 *
 * Rules (each pinned by `ReconnectKickerTest` / `ReconnectSupervisorTest`):
 *  - **Never two attempts per device.** A kick only interrupts a WAIT. A kick
 *    during an attempt does not start a second one — it just asks for the
 *    burst to be reset when that attempt ends ([Result.RESET_PENDING]).
 *  - **READY is untouchable.** A kick while connected is a no-op
 *    ([Result.IGNORED_CONNECTED]); so is one with no session ([Result.IGNORED_IDLE]).
 *  - **Debounced.** Accepted kicks are at least [debounceMs] apart, so rapid
 *    foreground/background toggles (or a foreground kick and a "Retry now"
 *    tap in the same second) collapse into one attempt.
 *
 * The respect-the-user-disconnect rule lives with the managers (they refuse
 * to kick a session the user ended); a user disconnect cancels the session
 * coroutine, which cancels any wait outright.
 */
class ReconnectKicker(
    private val nowMs: () -> Long,
    private val debounceMs: Long = KICK_DEBOUNCE_MS,
) {
    enum class Phase {
        /** No session. */
        IDLE,

        /** A connect attempt that did NOT follow a wait (a session's first). */
        ATTEMPTING,

        /** A connect attempt after a backoff / lurk wait — a RE-connect. */
        RETRYING,
        CONNECTED,

        /** Between attempts (backoff or the slow lurk interval). */
        WAITING,
    }

    enum class Result {
        /** The backoff/lurk wait was interrupted; an attempt starts now. */
        KICKED,

        /** An attempt is already running; the burst resets when it ends. */
        RESET_PENDING,
        IGNORED_CONNECTED,
        IGNORED_IDLE,
        DEBOUNCED,
    }

    private val _phase = MutableStateFlow(Phase.IDLE)

    /** The session's phase, observable (drives the UI's "Retry now"). */
    val phaseFlow: StateFlow<Phase> = _phase.asStateFlow()

    val phase: Phase get() = _phase.value

    /** Conflated: at most one pending kick; a second one is a duplicate. */
    private val kicks = Channel<ReconnectTrigger>(Channel.CONFLATED)

    private var lastAcceptedAtMs: Long? = null

    /** A kick that landed mid-attempt — reset the burst once it ends. */
    private var resetRequest: ReconnectTrigger? = null

    /** Ask the session to attempt now. See the class rules. */
    @Synchronized
    fun kick(trigger: ReconnectTrigger): Result {
        when (phase) {
            Phase.CONNECTED -> return Result.IGNORED_CONNECTED
            Phase.IDLE -> return Result.IGNORED_IDLE
            Phase.ATTEMPTING, Phase.RETRYING, Phase.WAITING -> Unit
        }
        val now = nowMs()
        lastAcceptedAtMs?.let { if (now - it < debounceMs) return Result.DEBOUNCED }
        lastAcceptedAtMs = now
        return if (phase == Phase.WAITING) {
            kicks.trySend(trigger)
            Result.KICKED
        } else {
            resetRequest = trigger
            Result.RESET_PENDING
        }
    }

    // ---- Supervisor side ---------------------------------------------------

    /** The session moved to [next]. Entering CONNECTED/IDLE drops stale kicks. */
    @Synchronized
    internal fun enter(next: Phase) {
        val effective = if (next == Phase.ATTEMPTING && _phase.value == Phase.WAITING) Phase.RETRYING else next
        _phase.value = effective
        when (effective) {
            Phase.CONNECTED, Phase.IDLE -> {
                kicks.tryReceive()
                resetRequest = null
            }
            Phase.ATTEMPTING, Phase.RETRYING -> {
                // A kick that raced the wait's own timeout: the attempt it wanted
                // is starting anyway, so keep only its burst reset.
                kicks.tryReceive().getOrNull()?.let { resetRequest = it }
            }
            Phase.WAITING -> Unit
        }
    }

    /** Consume a mid-attempt kick's burst-reset request, if any. */
    @Synchronized
    internal fun takeResetRequest(): ReconnectTrigger? = resetRequest.also { resetRequest = null }

    /**
     * Wait up to [waitMs] in the WAITING phase. Returns the kick's trigger if
     * one interrupted the wait, or null when the wait simply elapsed.
     */
    internal suspend fun awaitKickOrTimeout(waitMs: Long): ReconnectTrigger? {
        enter(Phase.WAITING)
        return withTimeoutOrNull(waitMs) { kicks.receive() }
    }

    companion object {
        /** Foreground toggles / taps closer than this collapse into one kick. */
        const val KICK_DEBOUNCE_MS = 1_500L
    }
}

/**
 * The same debounce for the controller-level kick paths that have no live
 * session to kick (a cold-start scan to restart, a direct connect to start).
 */
class KickDebouncer(
    private val nowMs: () -> Long,
    private val debounceMs: Long = ReconnectKicker.KICK_DEBOUNCE_MS,
) {
    private val last = HashMap<String, Long>()

    /** True (and records the time) if a kick for [key] may proceed now. */
    @Synchronized
    fun tryAcquire(key: String): Boolean {
        val now = nowMs()
        val prev = last[key]
        if (prev != null && now - prev < debounceMs) return false
        last[key] = now
        return true
    }
}

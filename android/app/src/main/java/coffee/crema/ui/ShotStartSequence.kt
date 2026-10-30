package coffee.crema.ui

import coffee.crema.core.MachineRequest
import coffee.crema.core.MachineState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** Ceiling on the pre-shot group flush before proceeding to Espresso
 *  anyway (issue 15) — the backstop if WaterSessionCompleted is missed.
 *  Matches the web shell's 30 s. */
const val PRESHOT_FLUSH_TIMEOUT_MS = 30_000L

/** How long a start stays pending after its Espresso request while the
 *  machine has not reported Espresso yet. Covers the gap before the state
 *  report, so a Start there is refused; a machine that never enters Espresso
 *  (a GHC-only machine refuses app starts) frees the button after this. */
const val ESPRESSO_ENTRY_TIMEOUT_MS = 5_000L

/** Shown when the machine ignored the Espresso request (web twin: the same text). */
const val NOT_STARTED_NOTICE = "The machine didn’t start the shot — start it on the machine"

/**
 * The one shot start in flight (cohort review 2026-10-29, 05 F2): Coffee →
 * profile upload → optional pre-shot flush → Espresso, held until the
 * machine reports Espresso. Web twin:
 * `lib/state/start-sequence.ts`, run by `CremaApp.startShot`.
 *
 * Only one start exists at a time: [begin] returns null while one is
 * pending, so a second Start (a double tap, a relayed retry, a second
 * controller) can't run a second chain whose late Espresso request lands
 * mid-shot, where the core used to bounce it through Idle and restart the
 * shot. A user's machine request, Stop above all, [cancel]s the start, so a
 * pre-flush ended by the user no longer releases the shot. The pre-flush
 * waiter belongs to the [Start], not to a field a second chain could
 * overwrite.
 *
 * Natural flush completion and a user stop can't be told apart from what
 * the DE1 reports (the flush ends in Idle either way, and
 * `WaterSessionCompleted` carries no reason). So the rule is: an app Stop
 * cancels the start before Idle goes out, and after the flush the shot
 * starts only if the machine reports Idle (a group-head button that moved it
 * to Steam or Sleep cancels).
 *
 * The upload stage stays in the VM, which owns the upload state on the core
 * lane: it calls [proceed] once the DE1 holds the profile, and [end] when the
 * upload fails. Safe to call from any thread (Main, the core lane, the
 * relay); the stages after [proceed] run on [scope].
 */
internal class ShotStartSequence(
    private val scope: CoroutineScope,
    /** The machine write itself: it must not cancel the start. */
    private val request: (MachineRequest) -> Unit,
    /** The state the DE1 last reported. */
    private val machineState: () -> MachineState?,
    /** Called whenever [active] may have changed. */
    private val onActiveChanged: () -> Unit,
    private val log: (String) -> Unit,
    private val notifyUser: (String) -> Unit,
    private val flushTimeoutMs: Long = PRESHOT_FLUSH_TIMEOUT_MS,
    private val espressoEntryTimeoutMs: Long = ESPRESSO_ENTRY_TIMEOUT_MS,
) {
    /** One start. Its identity is the sequence token. */
    class Start internal constructor() {
        @Volatile internal var cancelled = false
        @Volatile internal var job: Job? = null
        /** Set just before our pre-flush request goes out. */
        @Volatile internal var flushArmed = false
        /** A flush began after [flushArmed]: only its end may release us. */
        @Volatile internal var flushStarted = false
        @Volatile internal var espressoRequested = false
        internal val flushEnded = CompletableDeferred<Unit>()
        internal val espressoEntered = CompletableDeferred<Unit>()
    }

    private val current = AtomicReference<Start?>(null)

    /** A start is pending: from [begin] until the machine is in Espresso,
     *  the start fails, or it is cancelled. */
    val active: Boolean get() = current.get() != null

    /** Claim the sequence for a new start, or null when one is pending or a
     *  shot is already running. */
    fun begin(): Start? {
        if (machineState() == MachineState.Espresso) return null
        val start = Start()
        if (!current.compareAndSet(null, start)) return null
        onActiveChanged()
        return start
    }

    /** [start] is still the pending start and was not cancelled. */
    fun isLive(start: Start): Boolean = !start.cancelled && current.get() === start

    /** Cancel the pending start, if any. Returns it, so the caller can drop
     *  its own state for that start; null when nothing was pending. */
    fun cancel(): Start? {
        val start = current.get() ?: return null
        return if (cancelIfCurrent(start)) start else cancel()
    }

    /** The one cancel path: [start] stops being pending and its stages stop. */
    private fun cancelIfCurrent(start: Start): Boolean {
        if (!current.compareAndSet(start, null)) return false
        start.cancelled = true
        start.job?.cancel()
        onActiveChanged()
        return true
    }

    /** [start] is over without a cancel (its upload failed, or it reached
     *  Espresso). Returns whether it was still the live start, so a failure
     *  is told to the user only for a start nobody cancelled. */
    fun end(start: Start): Boolean {
        val live = isLive(start)
        if (current.compareAndSet(start, null)) onActiveChanged()
        return live
    }

    /** The DE1 holds the profile: run the optional pre-flush and
     *  the Espresso request for [start]. A no-op when it was cancelled. */
    fun proceed(start: Start, preFlush: Boolean) {
        if (!isLive(start)) return
        start.job = scope.launch {
            try {
                run(start, preFlush)
            } finally {
                end(start)
            }
        }
    }

    private suspend fun run(start: Start, preFlush: Boolean) {
        // The firmware's profile-download guard is the core's: it holds our
        // requests through an upload and the 500 ms after it (F16).
        if (preFlush) {
            start.flushArmed = true
            request(MachineRequest.FLUSH)
            // The WaterSessionCompleted(Flush) of our flush releases the
            // waiter; the ceiling is the backstop if that report is missed.
            val released = withTimeoutOrNull(flushTimeoutMs) { start.flushEnded.await() } != null
            if (!isLive(start)) return
            val state = machineState()
            if (released && state != MachineState.Idle) {
                log("Pre-shot flush ended in $state — shot not started")
                notifyUser("Shot not started — the machine left the flush")
                return
            }
        }
        if (!isLive(start)) return
        start.espressoRequested = true
        request(MachineRequest.ESPRESSO)
        if (machineState() == MachineState.Espresso) return
        if (withTimeoutOrNull(espressoEntryTimeoutMs) { start.espressoEntered.await() } == null && isLive(start)) {
            // A machine that only starts from the group head (GHC required)
            // ignores the app's request.
            log("The machine did not enter Espresso within ${espressoEntryTimeoutMs / 1000} s of the request")
            notifyUser(NOT_STARTED_NOTICE)
        }
    }

    /** Feed every `MachineStateChanged`. Espresso after our request ends the
     *  wait; Espresso before it means the shot was started on the machine,
     *  so this start stands down instead of requesting a second one. */
    fun onMachineState(state: MachineState) {
        if (state != MachineState.Espresso) return
        val start = current.get() ?: return
        if (start.espressoRequested) {
            start.espressoEntered.complete(Unit)
        } else if (cancelIfCurrent(start)) {
            log("Shot started on the machine — app start dropped")
        }
    }

    /** Feed `WaterSessionStarted(Flush)`. */
    fun onFlushStarted() {
        val start = current.get() ?: return
        if (start.flushArmed) start.flushStarted = true
    }

    /** Feed `WaterSessionCompleted(Flush)`. */
    fun onFlushCompleted() {
        val start = current.get() ?: return
        if (start.flushStarted) start.flushEnded.complete(Unit)
    }
}

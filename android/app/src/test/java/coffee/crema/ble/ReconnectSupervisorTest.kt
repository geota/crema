package coffee.crema.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The reconnect supervisor's kick contract, on virtual time: a kick interrupts
 * the backoff / lurk wait and resets the ladder to the fast burst; READY is
 * untouchable; kicks are debounced and never stack a second attempt; a user
 * disconnect ends the session for good.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSupervisorTest {

    /** A scripted device: [connectOk] decides each attempt; attempt start times are logged. */
    private class Rig(val scope: TestScope) {
        val attemptsAt = mutableListOf<Long>()
        var connectOk: (attempt: Int) -> Boolean = { false }
        var userInitiated = false
        var autoReconnect = true
        var teardowns = 0
        var drop = CompletableDeferred<Unit>()
        /** When set, an attempt suspends until completed (to kick mid-attempt). */
        var attemptGate: CompletableDeferred<Unit>? = null
        val statuses = mutableListOf<String>()
        val kicker = ReconnectKicker(nowMs = { scope.currentTime })
        val timeline = ReconnectTimelineRecorder(nowMs = { scope.currentTime }, wallMs = { 0L })

        fun start(): Job = scope.launch {
            reconnectingSession(
                label = "DE1",
                logTag = "test",
                status = { statuses += it },
                isUserInitiated = { userInitiated },
                isAutoReconnectEnabled = { autoReconnect },
                establish = {
                    attemptsAt += scope.currentTime
                    attemptGate?.await()
                    if (!connectOk(attemptsAt.size)) error("not found")
                },
                awaitDrop = { drop.await() },
                onConnected = {},
                onConnecting = {},
                teardown = { teardowns++ },
                kicker = kicker,
                timeline = timeline,
            )
        }
    }

    /** The full fast burst: 0.5+1+2+4+8+16+30+30 s of backoff before the lurk. */
    private val burstMs = (1..MAX_RECONNECT_ATTEMPTS).sumOf { backoffMs(it) }

    @Test
    fun `a kick interrupts the lurk wait and resets the ladder to the fast burst`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        // First attempt + the 8-attempt burst, then into the 60 s lurk wait.
        advanceTimeBy(burstMs + 1)
        runCurrent()
        assertEquals(MAX_RECONNECT_ATTEMPTS + 1, rig.attemptsAt.size)
        assertEquals(ReconnectKicker.Phase.WAITING, rig.kicker.phase)

        // 10 s into the lurk: kick. The attempt happens NOW, not at +60 s.
        advanceTimeBy(10_000)
        val kickAt = currentTime
        assertEquals(ReconnectKicker.Result.KICKED, rig.kicker.kick(ReconnectTrigger.FOREGROUND))
        runCurrent()
        assertEquals(MAX_RECONNECT_ATTEMPTS + 2, rig.attemptsAt.size)
        assertEquals(kickAt, rig.attemptsAt.last())

        // ...and the ladder restarted: the next retry is 500 ms later, not 60 s.
        advanceTimeBy(backoffMs(1) + 1)
        assertEquals(MAX_RECONNECT_ATTEMPTS + 3, rig.attemptsAt.size)
        assertEquals(kickAt + backoffMs(1), rig.attemptsAt.last())
        job.cancel()
    }

    @Test
    fun `a kick interrupts a burst backoff too`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        runCurrent()
        // Attempts 1..5 have run; the session now waits out the 8 s backoff.
        advanceTimeBy(backoffMs(1) + backoffMs(2) + backoffMs(3) + backoffMs(4) + 1)
        val before = rig.attemptsAt.size
        assertEquals(ReconnectKicker.Result.KICKED, rig.kicker.kick(ReconnectTrigger.USER_RETRY))
        runCurrent()
        assertEquals(before + 1, rig.attemptsAt.size)
        assertEquals(currentTime, rig.attemptsAt.last())
        job.cancel()
    }

    @Test
    fun `a kick while READY is a no-op`() = runTest {
        val rig = Rig(this)
        rig.connectOk = { true }
        val job = rig.start()
        runCurrent()
        assertEquals(1, rig.attemptsAt.size)
        assertEquals(ReconnectKicker.Phase.CONNECTED, rig.kicker.phase)
        assertEquals(ReconnectKicker.Result.IGNORED_CONNECTED, rig.kicker.kick(ReconnectTrigger.FOREGROUND))
        advanceTimeBy(120_000)
        assertEquals(1, rig.attemptsAt.size)

        // A stale kick must not leak into the next drop: after the drop the
        // ladder starts at its normal 500 ms backoff.
        rig.connectOk = { false }
        val dropAt = currentTime
        rig.drop.complete(Unit)
        runCurrent()
        assertEquals(ReconnectKicker.Phase.WAITING, rig.kicker.phase)
        advanceTimeBy(backoffMs(1) - 1)
        assertEquals(1, rig.attemptsAt.size)
        advanceTimeBy(2)
        assertEquals(2, rig.attemptsAt.size)
        assertEquals(dropAt + backoffMs(1), rig.attemptsAt.last())
        job.cancel()
    }

    @Test
    fun `rapid kicks are debounced`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        runCurrent()
        val before = rig.attemptsAt.size

        assertEquals(ReconnectKicker.Result.KICKED, rig.kicker.kick(ReconnectTrigger.FOREGROUND))
        runCurrent()
        // The kicked attempt failed at once and the session is back in its
        // 500 ms backoff: a second kick inside the debounce window is refused.
        advanceTimeBy(200)
        assertEquals(ReconnectKicker.Result.DEBOUNCED, rig.kicker.kick(ReconnectTrigger.FOREGROUND))
        runCurrent()
        assertEquals(before + 1, rig.attemptsAt.size)

        // After the window a kick is accepted again.
        advanceTimeBy(ReconnectKicker.KICK_DEBOUNCE_MS)
        val n = rig.attemptsAt.size
        assertEquals(ReconnectKicker.Result.KICKED, rig.kicker.kick(ReconnectTrigger.FOREGROUND))
        runCurrent()
        assertEquals(n + 1, rig.attemptsAt.size)
        job.cancel()
    }

    @Test
    fun `a kick mid-attempt never starts a second attempt, but resets the burst`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        runCurrent()
        // Next lurk attempt is gated so we can kick while it is in flight.
        rig.attemptGate = CompletableDeferred()
        advanceTimeBy(LURK_INTERVAL_MS)
        runCurrent()
        val inFlight = rig.attemptsAt.size
        assertEquals(ReconnectKicker.Phase.RETRYING, rig.kicker.phase)
        assertEquals(ReconnectKicker.Result.RESET_PENDING, rig.kicker.kick(ReconnectTrigger.FOREGROUND))
        runCurrent()
        assertEquals(inFlight, rig.attemptsAt.size)

        // The attempt fails: the reset puts the next retry at 500 ms, not 60 s.
        rig.attemptGate!!.complete(Unit)
        rig.attemptGate = null
        runCurrent()
        val failedAt = currentTime
        advanceTimeBy(backoffMs(1) + 1)
        assertEquals(inFlight + 1, rig.attemptsAt.size)
        assertEquals(failedAt + backoffMs(1), rig.attemptsAt.last())
        job.cancel()
    }

    @Test
    fun `a user disconnect ends the session and later kicks do nothing`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        runCurrent()
        // The manager's disconnect(): mark intentional, then cancel the session.
        rig.userInitiated = true
        job.cancel()
        runCurrent()
        assertEquals(0, rig.teardowns) // the user path does its own teardown
        assertEquals(ReconnectKicker.Phase.IDLE, rig.kicker.phase)
        val n = rig.attemptsAt.size
        assertEquals(ReconnectKicker.Result.IGNORED_IDLE, rig.kicker.kick(ReconnectTrigger.FOREGROUND))
        advanceTimeBy(10 * LURK_INTERVAL_MS)
        assertEquals(n, rig.attemptsAt.size)
    }

    @Test
    fun `a drop then a foreground kick reads as its own timeline episode`() = runTest {
        val rig = Rig(this)
        rig.connectOk = { it == 1 || it == 4 } // connect, drop, fail twice, kicked attempt succeeds
        val job = rig.start()
        runCurrent()
        rig.drop.complete(Unit)
        rig.drop = CompletableDeferred()
        runCurrent()
        advanceTimeBy(backoffMs(1) + backoffMs(2) + 1) // attempts 2 and 3 fail
        runCurrent()
        assertEquals(3, rig.attemptsAt.size)
        assertEquals(ReconnectKicker.Result.KICKED, rig.kicker.kick(ReconnectTrigger.FOREGROUND))
        runCurrent()
        assertEquals(ReconnectKicker.Phase.CONNECTED, rig.kicker.phase)

        val (fg, drop) = rig.timeline.timelines.value
        assertEquals(ReconnectTrigger.FOREGROUND, fg.trigger)
        assertEquals(ReconnectOutcome.READY, fg.outcome)
        assertEquals(1, fg.attempts)
        assertEquals(0L, fg.timeToReadyMs)
        assertEquals(ReconnectTrigger.DROP, drop.trigger)
        assertEquals(ReconnectOutcome.SUPERSEDED, drop.outcome)
        assertEquals(2, drop.failures)
        assertTrue(drop.marks.any { it.phase == ReconnectPhase.BACKOFF })
        job.cancel()
    }

    @Test
    fun `auto-reconnect off ends the session with a teardown`() = runTest {
        val rig = Rig(this)
        rig.autoReconnect = false
        val job = rig.start()
        runCurrent()
        assertFalse(job.isActive)
        assertEquals(1, rig.teardowns)
        assertEquals(ReconnectKicker.Phase.IDLE, rig.kicker.phase)
    }
}

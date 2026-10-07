package coffee.crema.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lurk tier's fallback-path selection and the supervisor's behaviour in
 * each policy: a pending (autoConnect) connect replaces the 60 s scan cycle;
 * a kick withdraws it for the fast path; a failure (GATT 133) falls back to
 * scan-then-connect with backoff; companion "absent" idles with no attempts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LurkPolicyTest {

    @Test
    fun `policy - associated and away idles, otherwise a pending connect where supported`() {
        assertEquals(LurkPolicy.IDLE_UNTIL_KICK, chooseLurkPolicy(cdmUsable = true, associated = true, present = false, pendingSupported = true))
        assertEquals(LurkPolicy.IDLE_UNTIL_KICK, chooseLurkPolicy(cdmUsable = true, associated = true, present = false, pendingSupported = false))
        assertEquals(LurkPolicy.PENDING_CONNECT, chooseLurkPolicy(cdmUsable = true, associated = true, present = true, pendingSupported = true))
        // No report yet = maybe here.
        assertEquals(LurkPolicy.PENDING_CONNECT, chooseLurkPolicy(cdmUsable = true, associated = true, present = null, pendingSupported = true))
        // Not associated, or CDM unusable: the presence signal is ignored.
        assertEquals(LurkPolicy.PENDING_CONNECT, chooseLurkPolicy(cdmUsable = true, associated = false, present = false, pendingSupported = true))
        assertEquals(LurkPolicy.PENDING_CONNECT, chooseLurkPolicy(cdmUsable = false, associated = true, present = false, pendingSupported = true))
        // No radio to pend on (LAN proxy / replay): the original scan cycle.
        assertEquals(LurkPolicy.SCAN_INTERVAL, chooseLurkPolicy(cdmUsable = false, associated = false, present = null, pendingSupported = false))
    }

    private class Rig(val scope: TestScope) {
        val normalAt = mutableListOf<Long>()
        val pendingAt = mutableListOf<Long>()
        var policy = LurkPolicy.PENDING_CONNECT
        var normalOk = false
        /** What the next pending connect does. */
        var pendingBehaviour: suspend () -> Unit = { awaitCancellation() }
        var pendingCancelled = 0
        val drop = CompletableDeferred<Unit>()
        val kicker = ReconnectKicker(nowMs = { scope.currentTime })

        fun start(): Job = scope.launch {
            reconnectingSession(
                label = "DE1",
                logTag = "test",
                status = {},
                isUserInitiated = { false },
                isAutoReconnectEnabled = { true },
                establish = {
                    normalAt += scope.currentTime
                    if (!normalOk) error("not found")
                },
                awaitDrop = { drop.await() },
                onConnected = {},
                onConnecting = {},
                teardown = {},
                kicker = kicker,
                lurkPolicy = { policy },
                establishPending = {
                    pendingAt += scope.currentTime
                    try {
                        pendingBehaviour()
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        pendingCancelled++
                        throw c
                    }
                },
            )
        }
    }

    private val burstMs = (1..MAX_RECONNECT_ATTEMPTS).sumOf { backoffMs(it) }

    @Test
    fun `after the burst a single pending connect replaces the 60 s scan cycle`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        runCurrent()
        assertEquals(MAX_RECONNECT_ATTEMPTS + 1, rig.normalAt.size)
        assertEquals(listOf(burstMs), rig.pendingAt) // straight after the last burst attempt
        // It just waits — no scans, no further attempts, for hours.
        advanceTimeBy(6 * 60 * 60_000L)
        assertEquals(MAX_RECONNECT_ATTEMPTS + 1, rig.normalAt.size)
        assertEquals(1, rig.pendingAt.size)
        assertEquals(ReconnectKicker.Phase.WAITING, rig.kicker.phase)
        job.cancel()
    }

    @Test
    fun `a pending connect that links up is READY`() = runTest {
        val rig = Rig(this)
        val linked = CompletableDeferred<Unit>()
        rig.pendingBehaviour = { linked.await() }
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        advanceTimeBy(3 * 60_000L)
        linked.complete(Unit)
        runCurrent()
        assertEquals(ReconnectKicker.Phase.CONNECTED, rig.kicker.phase)
        job.cancel()
    }

    @Test
    fun `a kick withdraws the pending connect and takes the fast path now`() = runTest {
        val rig = Rig(this)
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        advanceTimeBy(20 * 60_000L)
        val n = rig.normalAt.size
        assertEquals(ReconnectKicker.Result.KICKED, rig.kicker.kick(ReconnectTrigger.FOREGROUND))
        runCurrent()
        assertEquals(1, rig.pendingCancelled)
        assertEquals(n + 1, rig.normalAt.size)
        assertEquals(currentTime, rig.normalAt.last())
        // ...and the ladder restarted: 500 ms to the next scan-then-connect.
        advanceTimeBy(backoffMs(1) + 1)
        assertEquals(n + 2, rig.normalAt.size)
        job.cancel()
    }

    @Test
    fun `a failed pending connect (GATT 133) falls back to scan-then-connect with backoff`() = runTest {
        val rig = Rig(this)
        rig.pendingBehaviour = { error("GATT 133") }
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        runCurrent()
        val failedAt = rig.pendingAt.single()
        val n = rig.normalAt.size
        // Not another pending connect straight away: the fast burst again.
        advanceTimeBy(backoffMs(1) - 1)
        assertEquals(n, rig.normalAt.size)
        advanceTimeBy(2)
        assertEquals(n + 1, rig.normalAt.size)
        assertEquals(failedAt + backoffMs(1), rig.normalAt.last())
        assertEquals(1, rig.pendingAt.size)
        // Once that burst is spent, it pends again.
        advanceTimeBy(burstMs)
        runCurrent()
        assertEquals(2, rig.pendingAt.size)
        job.cancel()
    }

    @Test
    fun `companion says away - the lurk idles with no attempts until a presence kick`() = runTest {
        val rig = Rig(this)
        rig.policy = LurkPolicy.IDLE_UNTIL_KICK
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        runCurrent()
        val n = rig.normalAt.size
        advanceTimeBy(IDLE_SAFETY_MS - 10_000)
        assertEquals(n, rig.normalAt.size)
        assertTrue(rig.pendingAt.isEmpty())
        // The device comes back: the presence kick attempts at once.
        rig.policy = LurkPolicy.PENDING_CONNECT
        assertEquals(ReconnectKicker.Result.KICKED, rig.kicker.kick(ReconnectTrigger.PRESENCE))
        runCurrent()
        assertEquals(n + 1, rig.normalAt.size)
        job.cancel()
    }

    @Test
    fun `a silent presence signal can't strand the device - one safety attempt per idle interval`() = runTest {
        val rig = Rig(this)
        rig.policy = LurkPolicy.IDLE_UNTIL_KICK
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        runCurrent()
        val n = rig.normalAt.size
        advanceTimeBy(IDLE_SAFETY_MS)
        assertEquals(n + 1, rig.normalAt.size)
        advanceTimeBy(IDLE_SAFETY_MS)
        assertEquals(n + 2, rig.normalAt.size)
        job.cancel()
    }

    @Test
    fun `presence going away mid-lurk switches the next round to idle`() = runTest {
        val rig = Rig(this)
        rig.pendingBehaviour = { error("GATT 133") } // so the session comes back round
        val job = rig.start()
        advanceTimeBy(burstMs + 1)
        runCurrent()
        assertEquals(1, rig.pendingAt.size)
        rig.policy = LurkPolicy.IDLE_UNTIL_KICK
        advanceTimeBy(burstMs + 1) // the fallback burst
        runCurrent()
        val n = rig.normalAt.size
        advanceTimeBy(IDLE_SAFETY_MS - 10)
        assertEquals(n, rig.normalAt.size)
        assertEquals(1, rig.pendingAt.size)
        job.cancel()
    }
}

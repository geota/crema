package coffee.crema.ui

import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.MachineRequest
import coffee.crema.core.MachineState
import coffee.crema.core.NotificationSource
import coffee.crema.core.WaterSessionKind
import coffee.crema.core.WriteTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one owned shot start (cohort review 2026-10-29, 05 F2), on virtual time
 * against the host build of the core. Every machine request goes through the
 * real `request_machine_state`, and the DE1's state reports go back through
 * the real core, whose events feed the sequence the way the VM does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShotStartSequenceTest {

    private class Rig(private val scope: TestScope) {
        val bridge = CremaBridge()
        /** Every DE1 RequestedState byte the core wrote, in order. */
        val requested = mutableListOf<Int>()
        var state: MachineState? = null
        val notes = mutableListOf<String>()
        /** What the Coffee button would show: Stop while this is true. */
        var shotStarting = false
        val seq: ShotStartSequence = ShotStartSequence(
            scope = scope.backgroundScope,
            request = { apply(bridge.requestMachineState(it)) },
            machineState = { state },
            onActiveChanged = { shotStarting = seq.active },
            log = {},
            notifyUser = { notes += it },
        )

        init {
            report(IDLE)
        }

        fun apply(raw: String) {
            val out = coreJson.decodeFromString(CoreOutput.serializer(), raw)
            out.commands.filterIsInstance<Command.WriteCharacteristic>()
                .filter { it.content.target == WriteTarget.De1RequestedState }
                .forEach { requested += it.content.data.single().toInt() }
            for (e in out.events) {
                when (e) {
                    is Event.MachineStateChanged -> {
                        state = e.content.state
                        seq.onMachineState(e.content.state)
                    }
                    is Event.WaterSessionStarted ->
                        if (e.content.kind == WaterSessionKind.Flush) seq.onFlushStarted()
                    is Event.WaterSessionCompleted ->
                        if (e.content.kind == WaterSessionKind.Flush) seq.onFlushCompleted()
                    else -> Unit
                }
            }
        }

        /** The DE1 reports [stateByte] / [substate]. */
        fun report(stateByte: Int, substate: Int = 0) = apply(
            bridge.onNotification(
                NotificationSource.DE1_STATE,
                byteArrayOf(stateByte.toByte(), substate.toByte()),
                scope.testScheduler.currentTime.toULong(),
            ),
        )

        /** The VM's Stop: cancel the pending start, then request Idle. */
        fun stop() {
            seq.cancel()
            apply(bridge.requestMachineState(MachineRequest.IDLE))
        }
    }

    @Test
    fun `a second start during the guard is refused and the shot is requested once`() = runTest {
        val rig = Rig(this)
        val first = assertNotNull(rig.seq.begin())
        rig.seq.proceed(first, preFlush = false)
        assertTrue(rig.shotStarting, "Coffee reads Stop while the start is pending")
        advanceTimeBy(200)
        assertNull(rig.seq.begin(), "a second start inside the guard is refused")
        advanceTimeBy(400)
        runCurrent()
        assertEquals(listOf(ESPRESSO), rig.requested, "one Espresso, no Idle bounce")
        assertTrue(rig.shotStarting, "held until the machine reports Espresso")
        rig.report(ESPRESSO, POURING)
        runCurrent()
        assertFalse(rig.shotStarting)
        // With the shot running a start is refused, and a stale Espresso
        // request (the old orphaned chain) writes nothing: no Idle bounce.
        assertNull(rig.seq.begin())
        rig.apply(rig.bridge.requestMachineState(MachineRequest.ESPRESSO))
        assertEquals(listOf(ESPRESSO), rig.requested)
    }

    @Test
    fun `a second start during the pre-flush is refused and the flush releases one shot`() = runTest {
        val rig = Rig(this)
        val first = assertNotNull(rig.seq.begin())
        rig.seq.proceed(first, preFlush = true)
        advanceTimeBy(PROFILE_DOWNLOAD_GUARD_MS + 1)
        assertEquals(listOf(FLUSH), rig.requested)
        rig.report(FLUSH)
        advanceTimeBy(2_000)
        assertNull(rig.seq.begin(), "a second start during the flush is refused")
        // The flush ends on its own: the shot is requested once.
        advanceTimeBy(2_000)
        rig.report(IDLE)
        runCurrent()
        assertEquals(listOf(FLUSH, ESPRESSO), rig.requested)
        rig.report(ESPRESSO, POURING)
        // Nothing is left to fire later (the old chain's 30 s ceiling).
        advanceTimeBy(PRESHOT_FLUSH_TIMEOUT_MS * 2)
        runCurrent()
        assertEquals(listOf(FLUSH, ESPRESSO), rig.requested, "no late Idle + Espresso mid-shot")
        assertFalse(rig.shotStarting)
    }

    @Test
    fun `Stop during the pre-flush cancels the start and no shot is requested`() = runTest {
        val rig = Rig(this)
        val start = assertNotNull(rig.seq.begin())
        rig.seq.proceed(start, preFlush = true)
        advanceTimeBy(PROFILE_DOWNLOAD_GUARD_MS + 1)
        rig.report(FLUSH)
        advanceTimeBy(1_000)
        rig.stop()
        assertFalse(rig.shotStarting, "Coffee is back at once")
        rig.report(IDLE) // the flush ends because of the Stop
        advanceTimeBy(PRESHOT_FLUSH_TIMEOUT_MS * 2)
        runCurrent()
        assertEquals(listOf(FLUSH, IDLE), rig.requested, "no Espresso after the user's Stop")
        assertNotNull(rig.seq.begin(), "a fresh start is allowed afterwards")
    }

    @Test
    fun `Stop during the guard cancels the start`() = runTest {
        val rig = Rig(this)
        val start = assertNotNull(rig.seq.begin())
        rig.seq.proceed(start, preFlush = false)
        advanceTimeBy(100)
        rig.stop()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(IDLE), rig.requested)
        assertFalse(rig.shotStarting)
    }

    @Test
    fun `a cancel before the upload finishes stops proceed from starting the shot`() = runTest {
        val rig = Rig(this)
        val start = assertNotNull(rig.seq.begin())
        rig.stop() // during the upload
        rig.seq.proceed(start, preFlush = false) // ProfileUploadCompleted arrives later
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(IDLE), rig.requested)
    }

    @Test
    fun `a flush that leaves for another state cancels the start`() = runTest {
        val rig = Rig(this)
        val start = assertNotNull(rig.seq.begin())
        rig.seq.proceed(start, preFlush = true)
        advanceTimeBy(PROFILE_DOWNLOAD_GUARD_MS + 1)
        rig.report(FLUSH)
        advanceTimeBy(1_000)
        rig.report(STEAM) // the group-head Steam button mid-flush
        runCurrent()
        assertEquals(listOf(FLUSH), rig.requested)
        assertFalse(rig.shotStarting)
        assertEquals(1, rig.notes.size)
    }

    @Test
    fun `a stray flush completion does not release the waiter`() = runTest {
        val rig = Rig(this)
        rig.report(FLUSH) // an auto-purge flush already running
        val start = assertNotNull(rig.seq.begin())
        rig.seq.proceed(start, preFlush = true)
        advanceTimeBy(PROFILE_DOWNLOAD_GUARD_MS + 1)
        // Our request bounces the running flush through Idle.
        assertEquals(listOf(IDLE, FLUSH), rig.requested)
        rig.report(IDLE) // the old flush ends: not ours
        runCurrent()
        assertEquals(listOf(IDLE, FLUSH), rig.requested)
        rig.report(FLUSH)
        advanceTimeBy(4_000)
        rig.report(IDLE) // ours ends
        runCurrent()
        assertEquals(listOf(IDLE, FLUSH, ESPRESSO), rig.requested)
    }

    @Test
    fun `the flush ceiling still starts the shot when the completion is missed`() = runTest {
        val rig = Rig(this)
        val start = assertNotNull(rig.seq.begin())
        rig.seq.proceed(start, preFlush = true)
        advanceTimeBy(PROFILE_DOWNLOAD_GUARD_MS + 1)
        rig.report(FLUSH)
        advanceTimeBy(PRESHOT_FLUSH_TIMEOUT_MS - 1_000)
        assertEquals(listOf(FLUSH), rig.requested)
        advanceTimeBy(1_001)
        runCurrent()
        // The machine still reads the flush, so the core bounces through Idle.
        assertEquals(listOf(FLUSH, IDLE, ESPRESSO), rig.requested)
    }

    @Test
    fun `a machine that never enters Espresso frees the button`() = runTest {
        val rig = Rig(this)
        val start = assertNotNull(rig.seq.begin())
        rig.seq.proceed(start, preFlush = false)
        advanceTimeBy(PROFILE_DOWNLOAD_GUARD_MS + 1)
        assertTrue(rig.shotStarting)
        advanceTimeBy(ESPRESSO_ENTRY_TIMEOUT_MS)
        runCurrent()
        assertFalse(rig.shotStarting)
        assertEquals(listOf(NOT_STARTED_NOTICE), rig.notes, "says to start it on the machine")
        assertNotNull(rig.seq.begin())
    }

    @Test
    fun `Stop while waiting for Espresso gives no not-started notice`() = runTest {
        val rig = Rig(this)
        val start = assertNotNull(rig.seq.begin())
        rig.seq.proceed(start, preFlush = false)
        advanceTimeBy(PROFILE_DOWNLOAD_GUARD_MS + 1_000)
        rig.stop()
        advanceTimeBy(ESPRESSO_ENTRY_TIMEOUT_MS * 2)
        runCurrent()
        assertEquals(emptyList(), rig.notes)
        assertEquals(listOf(ESPRESSO, IDLE), rig.requested)
    }

    @Test
    fun `end reports a live start only, so a cancelled one gets no failure toast`() = runTest {
        val rig = Rig(this)
        val stopped = assertNotNull(rig.seq.begin())
        rig.stop()
        assertFalse(rig.seq.end(stopped), "stopped by the user")
        val overtaken = assertNotNull(rig.seq.begin())
        rig.report(ESPRESSO, POURING) // a group-head shot during the upload
        assertFalse(rig.seq.end(overtaken), "overtaken by a group-head shot")
        rig.report(IDLE)
        val live = assertNotNull(rig.seq.begin())
        assertTrue(rig.seq.end(live))
        assertFalse(rig.shotStarting)
    }

    @Test
    fun `a shot started on the machine during the upload drops the app start`() = runTest {
        val rig = Rig(this)
        val start = assertNotNull(rig.seq.begin())
        rig.report(ESPRESSO, POURING) // group-head start
        assertFalse(rig.shotStarting)
        rig.report(IDLE) // and it finishes before the upload does
        rig.seq.proceed(start, preFlush = false)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(emptyList(), rig.requested, "no second shot")
    }

    @Test
    fun `an upload failure ends the start without a request`() = runTest {
        val rig = Rig(this)
        val start = assertNotNull(rig.seq.begin())
        rig.seq.end(start)
        assertFalse(rig.shotStarting)
        rig.seq.proceed(start, preFlush = false)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(emptyList(), rig.requested)
    }

    private companion object {
        // DE1 state bytes (de1_protocol::MachineState).
        const val IDLE = 2
        const val ESPRESSO = 4
        const val STEAM = 5
        const val FLUSH = 15 // HotWaterRinse
        const val POURING = 5
    }
}

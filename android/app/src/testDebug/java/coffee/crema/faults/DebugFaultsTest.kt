package coffee.crema.faults

import android.content.ContextWrapper
import coffee.crema.ble.BleSessionRecorder
import coffee.crema.ble.BleTransport
import coffee.crema.ble.ScaleBleManager
import coffee.crema.core.Command
import coffee.crema.core.CommandWriteCharacteristicInner
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.WriteTarget
import coffee.crema.ui.CoreLane
import coffee.crema.ui.coreJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The hardware run's three hooks (DESIGN.md §7) drop exactly what they say,
 * in the shell's lanes, and then stop: N Stop writes, one plain
 * requested-state write, weight frames for T seconds. Each drop is logged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DebugFaultsTest {
    private var clock = 1_000L
    private val log = mutableListOf<String>()
    private val faults = DebugFaults(now = { clock }, log = { log += it })
    private val drops get() = log.count { it.startsWith("FAULT: dropped") }

    @Test
    fun `N Stop drops, then the writes go`() {
        faults.armStops(2)
        assertEquals(listOf(true, true, false, false), List(4) { faults.dropStop() })
        assertEquals(2, drops)
        assertFalse(faults.status.value.active(clock))
    }

    @Test
    fun `one requested-state drop, then the writes go`() {
        faults.armRequest()
        assertEquals(listOf(true, false, false), List(3) { faults.dropRequestedState() })
        assertEquals(1, drops)
    }

    @Test
    fun `weight frames drop for T seconds, then flow`() {
        faults.armWeight(3)
        val dropped = (0 until 50).map { i -> faults.dropWeightFrame(1_000L + i * 100) }
        // 1000..3900 ms is inside the 3 s window, 4000 on is not.
        assertEquals(List(30) { true } + List(20) { false }, dropped)
        assertEquals(30, drops)
        assertTrue(log.any { it == "FAULT: weight window over, 30 frame(s) dropped" })
        clock = 5_000
        assertFalse(faults.status.value.active(clock))
    }

    @Test
    fun `nothing armed drops nothing`() {
        assertFalse(faults.dropStop())
        assertFalse(faults.dropRequestedState())
        assertFalse(faults.dropWeightFrame(clock))
        assertEquals(0, drops)
    }

    // ── In the lanes ──────────────────────────────────────────────────────

    private val de1 = mutableListOf<Pair<WriteTarget, Boolean>>()

    private fun TestScope.lane() = CoreLane(
        scope = this,
        json = coreJson,
        log = { log += it },
        writeDe1 = { target, _, urgent, _ -> de1 += target to urgent; true },
        writeScale = {},
        profileWriteFailed = { """{"events":[],"commands":[]}""" },
        faults = faults,
        dispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun stop() = CoreOutput(emptyList(), listOf(Command.Stop))

    private fun request(state: Int) = CoreOutput(
        emptyList(),
        listOf(Command.WriteCharacteristic(CommandWriteCharacteristicInner(WriteTarget.De1RequestedState, listOf(state.toUByte())))),
    )

    @Test
    fun `the lane drops the armed Stop writes and sends the next`() = runTest {
        val lane = lane()
        faults.armStops(2)
        repeat(3) {
            lane.applyOutput(stop())
            advanceUntilIdle()
        }
        assertEquals(listOf(WriteTarget.De1RequestedState to true), de1, "two dropped, the third written")
    }

    @Test
    fun `the lane drops one plain request, never a Stop, and sends the next`() = runTest {
        val lane = lane()
        faults.armRequest()
        lane.applyOutput(stop())
        advanceUntilIdle()
        lane.applyOutput(request(4))
        advanceUntilIdle()
        lane.applyOutput(request(4))
        advanceUntilIdle()
        assertEquals(
            listOf(WriteTarget.De1RequestedState to true, WriteTarget.De1RequestedState to false),
            de1,
            "the Stop goes, the first request is dropped, the second goes",
        )
    }

    private class NoTransport : BleTransport {
        override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> = emptyFlow()
        override suspend fun awaitAdvertisement(device: BleTransport.DeviceHandle, timeoutMs: Long) = true
        override suspend fun connect(device: BleTransport.DeviceHandle) = awaitCancellation()
        override suspend fun connect(device: BleTransport.DeviceHandle, onLinkUp: () -> Unit) = connect(device)
        override suspend fun connectWhenAvailable(device: BleTransport.DeviceHandle, onLinkUp: () -> Unit) = connect(device)
        override suspend fun disconnect(device: BleTransport.DeviceHandle) {}
        override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> =
            MutableStateFlow(BleTransport.ConnState.DISCONNECTED)
        override fun observe(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): Flow<BleTransport.Notification> = emptyFlow()
        override suspend fun write(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID, data: ByteArray, withoutResponse: Boolean) {}
        override suspend fun read(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): ByteArray = ByteArray(0)
    }

    @Test
    fun `the scale lane drops weight frames for the window, then feeds the core`() {
        var fed = 0
        val scale = ScaleBleManager(
            transport = NoTransport(),
            bridge = CremaBridge(),
            recorder = BleSessionRecorder(ContextWrapper(null)),
            onCoreOutput = { fed++ },
            onStatus = {},
            faults = faults,
        )
        val frame = ByteArray(20)
        faults.armWeight(2)
        for (i in 0 until 30) {
            scale.handleWeightNotification(BleTransport.Notification(UUID.randomUUID(), frame, 1_000L + i * 100))
        }
        assertEquals(10, fed, "frames at 1000..2900 ms dropped, 3000..3900 fed")
    }
}

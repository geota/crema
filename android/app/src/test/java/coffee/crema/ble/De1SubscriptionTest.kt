package coffee.crema.ble

import android.content.ContextWrapper
import coffee.crema.core.CremaBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The DE1 manager awaits every subscription in the core's table and a
 * required one that fails fails the connect (core/shell fixes F14, review
 * §2.1-1). Before, the observe flows were merged un-awaited: one failed
 * subscribe was logged, the merge cancelled every other stream, and READY
 * stayed up over a link with dead notifications. Web's connect program
 * already failed on it.
 */
class De1SubscriptionTest {

    private class Handle : BleTransport.DeviceHandle {
        override val name: String? = "DE1"
        override val address: String = "AA:BB:CC:DD:EE:10"
    }

    /** Links up at once; [failing] characteristics refuse their CCCD enable. */
    private class FakeTransport(private val failing: Set<UUID>) : BleTransport {
        val enabled: MutableList<UUID> = Collections.synchronizedList(mutableListOf())
        val tornDown = CountDownLatch(1)
        private val state = MutableStateFlow(BleTransport.ConnState.CONNECTED)
        override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> = emptyFlow()
        override suspend fun connect(device: BleTransport.DeviceHandle) {
            state.value = BleTransport.ConnState.CONNECTED
        }
        override suspend fun disconnect(device: BleTransport.DeviceHandle) {
            tornDown.countDown()
        }
        override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> = state
        override fun observe(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): Flow<BleTransport.Notification> =
            emptyFlow()
        override suspend fun enableNotifications(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID) {
            enabled += characteristic
            if (characteristic in failing) error("CCCD write failed")
        }
        override suspend fun write(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID, data: ByteArray, withoutResponse: Boolean) {}
        // StateInfo: Idle, Ready — the required seed read answers.
        override suspend fun read(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): ByteArray =
            byteArrayOf(2, 0)
    }

    private fun manager(transport: BleTransport, status: MutableList<String>) = De1BleManager(
        transport = transport,
        bridge = CremaBridge(), // the host build of the core (cargoBuildHostCore)
        recorder = BleSessionRecorder(ContextWrapper(null)),
        onCoreOutput = {},
        onStatus = { status += it },
    )

    private fun awaitState(m: De1BleManager, want: De1BleManager.State, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (m.state.value == want) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test
    fun `both shells subscribe the core's table, required first`() {
        val t = FakeTransport(failing = emptySet())
        val m = manager(t, Collections.synchronizedList(mutableListOf()))
        m.connect(Handle())
        assertTrue(awaitState(m, De1BleManager.State.READY, 5_000), "control: a healthy link reaches READY")
        assertEquals(De1Uuids.SUBSCRIPTIONS.map { UUID.fromString(it.characteristic) }, t.enabled.toList())
        assertEquals(5, De1Uuids.SUBSCRIPTIONS.count { it.required })
        m.close()
    }

    @Test
    fun `a failed required subscription fails the connect instead of sitting READY`() {
        val status = Collections.synchronizedList(mutableListOf<String>())
        val t = FakeTransport(failing = setOf(De1Uuids.SHOT_SAMPLE))
        val m = manager(t, status)
        m.connect(Handle())
        assertTrue(t.tornDown.await(5, TimeUnit.SECONDS), "the link is torn down")
        assertFalse(awaitState(m, De1BleManager.State.READY, 1_000), "never READY")
        assertTrue(status.any { "ShotSample A00D subscribe failed" in it }, status.joinToString("\n"))
        m.close()
    }

    @Test
    fun `a failed best-effort subscription is skipped`() {
        val status = Collections.synchronizedList(mutableListOf<String>())
        val calibration = De1Uuids.SUBSCRIPTIONS.single { !it.required }.characteristic
        val t = FakeTransport(failing = setOf(UUID.fromString(calibration)))
        val m = manager(t, status)
        m.connect(Handle())
        assertTrue(awaitState(m, De1BleManager.State.READY, 5_000))
        assertTrue(status.any { "Calibration A012 subscribe skipped" in it })
        m.close()
    }
}

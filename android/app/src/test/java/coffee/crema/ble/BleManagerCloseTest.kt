package coffee.crema.ble

import android.content.ContextWrapper
import coffee.crema.core.CremaBridge
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The BLE managers' scopes end with the ViewModel (cohort review 2026-10-25,
 * Android P3-3). Before close() existed nothing cancelled them, so session,
 * liveness and observation children outlived the VM. A closed manager must
 * not start any more transport work.
 */
class BleManagerCloseTest {

    private class Handle(override val address: String) : BleTransport.DeviceHandle {
        override val name: String? = "DE1"
    }

    /** Counts down on the first connect attempt a session makes. */
    private class RecordingTransport : BleTransport {
        val connectAttempted = CountDownLatch(1)
        override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> = emptyFlow()
        override suspend fun awaitAdvertisement(device: BleTransport.DeviceHandle, timeoutMs: Long): Boolean {
            connectAttempted.countDown()
            return true
        }
        override suspend fun connect(device: BleTransport.DeviceHandle) {
            connectAttempted.countDown()
            awaitCancellation()
        }
        override suspend fun connect(device: BleTransport.DeviceHandle, onLinkUp: () -> Unit) = connect(device)
        override suspend fun connectWhenAvailable(device: BleTransport.DeviceHandle, onLinkUp: () -> Unit) = connect(device)
        override suspend fun disconnect(device: BleTransport.DeviceHandle) {}
        override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> =
            MutableStateFlow(BleTransport.ConnState.DISCONNECTED)
        override fun observe(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): Flow<BleTransport.Notification> = emptyFlow()
        override suspend fun write(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID, data: ByteArray, withoutResponse: Boolean) {}
        override suspend fun read(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): ByteArray = ByteArray(0)
    }

    private fun de1(transport: BleTransport) = De1BleManager(
        transport = transport,
        bridge = CremaBridge(), // the host build of the core (cargoBuildHostCore)
        recorder = BleSessionRecorder(ContextWrapper(null)),
        onCoreOutput = {},
        onStatus = {},
    )

    private fun scale(transport: BleTransport) = ScaleBleManager(
        transport = transport,
        bridge = CremaBridge(),
        recorder = BleSessionRecorder(ContextWrapper(null)),
        onCoreOutput = {},
        onStatus = {},
    )

    @Test
    fun anOpenDe1ManagerStartsASession() {
        val t = RecordingTransport()
        de1(t).connect(Handle("AA:BB:CC:DD:EE:01"))
        assertTrue(t.connectAttempted.await(5, TimeUnit.SECONDS), "control: connect reaches the transport")
    }

    @Test
    fun aClosedDe1ManagerStartsNothing() {
        val t = RecordingTransport()
        val m = de1(t)
        m.close()
        m.connect(Handle("AA:BB:CC:DD:EE:02"))
        assertFalse(t.connectAttempted.await(500, TimeUnit.MILLISECONDS))
    }

    @Test
    fun aClosedScaleManagerStartsNothing() {
        val t = RecordingTransport()
        val m = scale(t)
        m.close()
        m.connect(Handle("AA:BB:CC:DD:EE:03"), "BOOKOO_SC")
        assertFalse(t.connectAttempted.await(500, TimeUnit.MILLISECONDS))
    }
}

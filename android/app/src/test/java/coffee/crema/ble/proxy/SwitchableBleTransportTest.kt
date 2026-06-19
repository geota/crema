package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The runtime-switch facade routes every call to the current delegate and
 *  re-routes after [SwitchableBleTransport.setDelegate]. */
class SwitchableBleTransportTest {

    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val char = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")

    /** A [BleTransport] that tags everything it returns, so the facade's routing is observable. */
    private class TaggedTransport(val tag: String) : BleTransport {
        override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> =
            flow { emit(BleTransport.ScanMatch(Handle(tag), tag)) }
        override suspend fun connect(device: BleTransport.DeviceHandle) {}
        override suspend fun disconnect(device: BleTransport.DeviceHandle) {}
        override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> =
            MutableStateFlow(BleTransport.ConnState.CONNECTED).asStateFlow()
        override fun observe(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID) =
            emptyFlow<BleTransport.Notification>()
        override suspend fun write(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID, data: ByteArray) {}
        override suspend fun read(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): ByteArray =
            tag.toByteArray()

        class Handle(override val name: String, override val address: String = name) : BleTransport.DeviceHandle
    }

    @Test
    fun `routes to the current delegate, and re-routes on swap`() = runBlocking {
        val a = TaggedTransport("A")
        val b = TaggedTransport("B")
        val handle = TaggedTransport.Handle("x")
        val switchable = SwitchableBleTransport(a)

        assertEquals("A", switchable.scan { true }.first().name)
        assertContentEquals("A".toByteArray(), switchable.read(handle, service, char))
        assertEquals(a, switchable.delegate)

        switchable.setDelegate(b)

        assertEquals("B", switchable.scan { true }.first().name)
        assertContentEquals("B".toByteArray(), switchable.read(handle, service, char))
        assertEquals(b, switchable.delegate)
    }
}

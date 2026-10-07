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
        val writes = mutableListOf<Boolean>()
        override suspend fun write(
            device: BleTransport.DeviceHandle,
            service: UUID,
            characteristic: UUID,
            data: ByteArray,
            withoutResponse: Boolean,
        ) {
            writes += withoutResponse
        }
        override suspend fun read(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID): ByteArray =
            tag.toByteArray()
        override fun resolveByAddress(address: String, name: String?): BleTransport.DeviceHandle =
            Handle("$tag:$address", address)
        val advertWaits = mutableListOf<Long>()
        override suspend fun awaitAdvertisement(device: BleTransport.DeviceHandle, timeoutMs: Long): Boolean {
            advertWaits += timeoutMs
            return false
        }
        override val supportsPendingConnect: Boolean get() = tag == "A"
        val pendingConnects = mutableListOf<String>()
        override suspend fun connectWhenAvailable(device: BleTransport.DeviceHandle, onLinkUp: () -> Unit) {
            pendingConnects += device.address
            onLinkUp()
        }
        val linkUps = mutableListOf<String>()
        override suspend fun connect(device: BleTransport.DeviceHandle, onLinkUp: () -> Unit) {
            linkUps += "before-discovery"
            onLinkUp()
            linkUps += "after-discovery"
        }

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

    @Test
    fun `passes the write type through to the delegate`() = runBlocking {
        val a = TaggedTransport("A")
        val handle = TaggedTransport.Handle("x")
        val switchable = SwitchableBleTransport(a)

        switchable.write(handle, service, char, byteArrayOf(0x10), withoutResponse = true)
        switchable.write(handle, service, char, byteArrayOf(0x10))

        assertEquals(listOf(true, false), a.writes)
    }

    /**
     * Regression: the optional methods used to fall through to the interface
     * defaults (resolveByAddress → null, awaitAdvertisement → true at once), so
     * through this facade the DE1 never actually scanned before a reconnect and
     * every "direct connect by address" quietly fell back to a scan.
     */
    @Test
    fun `forwards the optional reconnect methods to the delegate`() = runBlocking {
        val a = TaggedTransport("A")
        val switchable = SwitchableBleTransport(a)
        val handle = TaggedTransport.Handle("x")

        assertEquals("A:AA:BB", switchable.resolveByAddress("AA:BB", "DE1")?.name)
        assertEquals(false, switchable.awaitAdvertisement(handle, 12_000))
        assertEquals(listOf(12_000L), a.advertWaits)

        val seen = mutableListOf<String>()
        switchable.connect(handle) { seen += "link-up" }
        assertEquals(listOf("link-up"), seen)
        assertEquals(listOf("before-discovery", "after-discovery"), a.linkUps)
    }

    @Test
    fun `forwards the pending (autoConnect) connect to the delegate`() = runBlocking {
        val a = TaggedTransport("A")
        val b = TaggedTransport("B")
        val switchable = SwitchableBleTransport(a)
        val handle = TaggedTransport.Handle("x", "AA:BB")
        assertEquals(true, switchable.supportsPendingConnect)
        var linked = false
        switchable.connectWhenAvailable(handle) { linked = true }
        assertEquals(listOf("AA:BB"), a.pendingConnects)
        assertEquals(true, linked)
        switchable.setDelegate(b)
        assertEquals(false, switchable.supportsPendingConnect)
    }
}

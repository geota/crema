package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A remote never reaches the hub's grinder (grinder-ble §6.2): its `baseInfo`
 * holds the serial and the Wi-Fi name. A read of the grinder (any service),
 * or of the grinder's service on any device, is refused `not-relayed` without
 * touching the radio; an attach to it finds a disconnected device with no
 * snapshot and no state; its notifications and state changes reach nobody.
 * The DE1's reads, attach and stream are unchanged.
 */
class RelayGrinderPrivacyTest {
    private val de1 = "AA:BB:CC:DD:EE:FF"
    private val grinder = "11:22:33:44:55:66"
    private val de1Service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")
    private val grinderService = UUID.fromString("4d543830-0001-4b80-8f00-424f4f4b4f4f")
    private val grinderTx = UUID.fromString("4d543830-0003-4b80-8f00-424f4f4b4f4f")
    private val deviceInfo = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    private val serialChar = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")

    private class Handle(override val address: String, override val name: String) : BleTransport.DeviceHandle

    @Test
    fun `a remote can't read, attach or hear the grinder`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val reads = CopyOnWriteArrayList<String>()
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            val hub = RelayHub(
                primaryName = "Kitchen tablet",
                roster = { listOf(DeviceInfo(de1, "DE1", "de1", "CONNECTED")) },
                readSource = { a, s, c -> reads += "$a $s $c"; byteArrayOf(0x42) },
                isSnapshotChar = { _, _ -> true },
                isRelayedService = { it != grinderService },
                isRelayedDevice = { it != grinder },
            )
            scope.launch { hub.serve(serverEnd, TEST_PEER) }
            val proxy = ProxyTransport(clientEnd, scope)
            val de1Handle = withTimeout(2_000) { proxy.scan { it == "DE1" }.first() }.device
            withTimeout(2_000) { proxy.connect(de1Handle) }

            // The DE1 reads as before.
            assertContentEquals(byteArrayOf(0x42), withTimeout(2_000) { proxy.read(de1Handle, de1Service, stateChar) })
            assertEquals(1, reads.size)

            // The grinder, by address (any service) or by service (any address): refused.
            val g = Handle(grinder, "BOOKOO MT80 1a2b3c4d")
            for ((device, service, char) in listOf(
                Triple(g, deviceInfo, serialChar),
                Triple(g, grinderService, grinderTx),
                Triple(de1Handle, grinderService, grinderTx),
            )) {
                val e = assertFailsWith<IllegalStateException> { withTimeout(2_000) { proxy.read(device, service, char) } }
                assertTrue(e.message!!.contains(NOT_RELAYED), "${e.message}")
            }
            assertEquals(1, reads.size, "the radio was never asked")

            // Its traffic before and after an attach reaches nobody.
            hub.onInbound(grinder, grinderService, grinderTx, byteArrayOf(1, 2, 3), atMs = 1)
            hub.onInbound(grinder, deviceInfo, serialChar, byteArrayOf(4), atMs = 1)
            hub.onConnState(grinder, BleTransport.ConnState.CONNECTED.name)
            withTimeout(2_000) { proxy.connect(g) }
            assertEquals(BleTransport.ConnState.DISCONNECTED, proxy.connectionState(g).value, "an attach finds nothing")
            val heard = CopyOnWriteArrayList<BleTransport.Notification>()
            val obs = scope.launch { proxy.observe(g, deviceInfo, serialChar).collect { heard += it } }
            hub.onInbound(grinder, deviceInfo, serialChar, byteArrayOf(5), atMs = 2)
            hub.onConnState(grinder, BleTransport.ConnState.FAILED.name)
            // The DE1's stream still flows, so the grinder's silence is real.
            val de1Heard = CopyOnWriteArrayList<BleTransport.Notification>()
            val de1Obs = scope.launch { proxy.observe(de1Handle, de1Service, stateChar).collect { de1Heard += it } }
            delay(100)
            hub.onInbound(de1, de1Service, stateChar, byteArrayOf(9), atMs = 3)
            withTimeout(2_000) { while (de1Heard.none { it.data.contentEquals(byteArrayOf(9)) }) delay(5) }
            assertEquals(emptyList(), heard.toList())
            assertEquals(BleTransport.ConnState.DISCONNECTED, proxy.connectionState(g).value)
            obs.cancel()
            de1Obs.cancel()
        } finally {
            scope.cancel()
        }
    }

    /** The tap neither tees the grinder nor serves a read of it. */
    @Test
    fun `the tap keeps the grinder to itself`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val delegate = object : BleTransport {
                val state = MutableStateFlow(BleTransport.ConnState.CONNECTED)
                override fun scan(matches: (name: String) -> Boolean): Flow<BleTransport.ScanMatch> = emptyFlow()
                override suspend fun connect(device: BleTransport.DeviceHandle) {}
                override suspend fun disconnect(device: BleTransport.DeviceHandle) {}
                override fun connectionState(device: BleTransport.DeviceHandle): StateFlow<BleTransport.ConnState> = state
                override fun observe(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID) =
                    kotlinx.coroutines.flow.flowOf(BleTransport.Notification(characteristic, byteArrayOf(7), 1))
                override suspend fun write(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID, data: ByteArray, withoutResponse: Boolean) {}
                override suspend fun read(device: BleTransport.DeviceHandle, service: UUID, characteristic: UUID) = byteArrayOf(0x42)
            }
            val inbound = CopyOnWriteArrayList<String>()
            val hub = RelayHub(
                primaryName = "P",
                roster = { emptyList() },
                readSource = { _, _, _ -> ByteArray(0) },
                isSnapshotChar = { _, _ -> true },
            )
            val tap = TappingBleTransport(delegate, hub, scope, isRelayedDevice = { it != grinder }, isRelayedService = { it != grinderService })
            val g = Handle(grinder, "BOOKOO MT80 1a2b3c4d")
            tap.connect(g)
            tap.observe(g, grinderService, grinderTx).collect { inbound += "seen by the grinder manager" }
            assertEquals(1, inbound.size, "the manager above still gets its notifications")
            assertFailsWith<IllegalStateException> { tap.readByAddress(grinder, deviceInfo, serialChar) }
            val d = Handle(de1, "DE1")
            tap.connect(d)
            assertFailsWith<IllegalStateException> { tap.readByAddress(de1, grinderService, grinderTx) }
            assertContentEquals(byteArrayOf(0x42), tap.readByAddress(de1, de1Service, stateChar))
        } finally {
            scope.cancel()
        }
    }

    /** Review #552 S1: only roster devices are served, so a standard service
     *  (a Device Information serial, 0x2A25) on an unlisted device is refused
     *  even when nothing names it a grinder; a rostered scale that drops out
     *  for a while is still served. */
    @Test
    fun `only devices the roster listed are served`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val scale = "22:33:44:55:66:77"
            val roster = java.util.concurrent.atomic.AtomicReference(
                listOf(DeviceInfo(de1, "DE1", "de1", "CONNECTED"), DeviceInfo(scale, "BOOKOO_SC 1", "scale", "CONNECTED")),
            )
            val reads = CopyOnWriteArrayList<String>()
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            val hub = RelayHub(
                primaryName = "Kitchen tablet",
                roster = { roster.get() },
                readSource = { a, _, _ -> reads += a; byteArrayOf(0x42) },
                isSnapshotChar = { _, _ -> true },
            )
            scope.launch { hub.serve(serverEnd, TEST_PEER) }
            val proxy = ProxyTransport(clientEnd, scope)
            withTimeout(2_000) { proxy.scan { it == "DE1" }.first() }
            val unlisted = Handle(grinder, "BOOKOO MT80 1a2b3c4d")
            val e = assertFailsWith<IllegalStateException> { withTimeout(2_000) { proxy.read(unlisted, deviceInfo, serialChar) } }
            assertTrue(e.message!!.contains(NOT_RELAYED), "${e.message}")
            withTimeout(2_000) { proxy.connect(unlisted) }
            assertEquals(BleTransport.ConnState.DISCONNECTED, proxy.connectionState(unlisted).value)
            // An unknown device the hub IS connected to (a future device, or a
            // grinder it forgot): its state and traffic are known to the hub,
            // but it isn't on the roster, so nothing is served.
            val unknown = Handle("33:44:55:66:77:88", "Something new")
            hub.onConnState(unknown.address, BleTransport.ConnState.CONNECTED.name)
            hub.onInbound(unknown.address, deviceInfo, serialChar, byteArrayOf(1), atMs = 1)
            val e2 = assertFailsWith<IllegalStateException> { withTimeout(2_000) { proxy.read(unknown, deviceInfo, serialChar) } }
            assertTrue(e2.message!!.contains(NOT_RELAYED), "${e2.message}")
            withTimeout(2_000) { proxy.connect(unknown) }
            assertEquals(BleTransport.ConnState.DISCONNECTED, proxy.connectionState(unknown).value, "not the CONNECTED the hub saw")
            // The scale drops out of the roster and is still served.
            roster.set(listOf(DeviceInfo(de1, "DE1", "de1", "CONNECTED")))
            assertContentEquals(byteArrayOf(0x42), withTimeout(2_000) { proxy.read(Handle(scale, "BOOKOO_SC 1"), deviceInfo, serialChar) })
            assertEquals(listOf(scale), reads.toList())
        } finally {
            scope.cancel()
        }
    }
}

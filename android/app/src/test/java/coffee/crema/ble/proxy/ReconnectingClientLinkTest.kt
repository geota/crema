package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import coffee.crema.core.RelayKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * The secondary's [ReconnectingClientLink] driving a [ProxyTransport] against a
 * real [LanRelayServer] — proves the self-connecting link runs the relay v4
 * resume and carries the protocol (scan → connect → notify → read) end-to-end
 * over a socket.
 */
class ReconnectingClientLinkTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")
    private val sampleChar = UUID.fromString("0000a00d-0000-1000-8000-00805f9b34fb")

    @Test
    fun `self-connecting link mirrors over a real socket`() = runBlocking {
        val versionBytes = byteArrayOf(0x09, 0x00)
        val hub = RelayHub(
            primaryName = "Tablet",
            roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
            readSource = { a, s, c -> if (a == addr && s == service && c == stateChar) versionBytes else error("no read") },
            isSnapshotChar = { _, c -> c == stateChar },
        )
        val rig = RelayRig(hub).start()
        val remote = RelayKeys.generate()
        rig.trust.pin(remote)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = rig.link(remote)
            val proxy = ProxyTransport(link, scope)

            val match = withTimeout(5_000) { proxy.scan { it == "DE1" }.first() }
            assertEquals(addr, match.device.address)
            withTimeout(5_000) { proxy.connect(match.device) }

            val received = CopyOnWriteArrayList<BleTransport.Notification>()
            val obs = scope.launch { proxy.observe(match.device, service, sampleChar).collect { received.add(it) } }
            val n = 50
            repeat(n) { i -> hub.onInbound(addr, service, sampleChar, byteArrayOf(i.toByte()), atMs = 1_000L + i) }
            withTimeout(5_000) { while (received.size < n) delay(5) }
            assertEquals(n, received.size)
            assertEquals((0 until n).map { 1_000L + it }, received.map { it.atMs })

            val read = withTimeout(5_000) { proxy.read(match.device, service, stateChar) }
            assertContentEquals(versionBytes, read)

            obs.cancel()
            link.close()
        } finally {
            scope.cancel()
            rig.stop()
        }
    }
}

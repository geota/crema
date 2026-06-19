package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The M1 in-process loopback: a [ProxyTransport] talking to a [RelayHub] over an
 * [InMemoryFrameLink], proving the reads-path mirror end-to-end — handshake,
 * roster→scan, attach + snapshot-on-attach, **lossless** notify fan-out,
 * read round-trip, write-rejection, and connState propagation — with no socket,
 * no NSD, no emulator and no Bluetooth.
 */
class ProxyLoopbackTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb") // snapshot (latest-wins)
    private val sampleChar = UUID.fromString("0000a00d-0000-1000-8000-00805f9b34fb") // counted stream

    @Test
    fun `secondary mirrors the primary losslessly`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            val versionBytes = byteArrayOf(0x09, 0x00, 0x7f)
            val hub = RelayHub(
                primaryId = "primary-1",
                primaryName = "Kitchen tablet",
                roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { a, s, c ->
                    if (a == addr && s == service && c == stateChar) versionBytes else error("no such read")
                },
                isSnapshotChar = { _, c -> c == stateChar }, // sample stream is NOT snapshotted
            )
            scope.launch { hub.serve(serverEnd) }
            val proxy = ProxyTransport(clientEnd, scope, clientId = "phone-1", clientName = "Phone")

            // 1) Roster → scan surfaces the primary's DE1.
            val match = withTimeout(2_000) { proxy.scan { it == "DE1" }.first() }
            assertEquals(addr, match.device.address)

            // A state value cached on the primary BEFORE the secondary attaches —
            // it must arrive as the attach snapshot.
            hub.onInbound(addr, service, stateChar, byteArrayOf(0x01, 0x02), atMs = 100L)

            // 2) connect = attach; converges to CONNECTED.
            withTimeout(2_000) { proxy.connect(match.device) }
            assertEquals(BleTransport.ConnState.CONNECTED, proxy.connectionState(match.device).value)

            // 3) observe both characteristics on one merged collector (as the
            //    managers do), started AFTER connect — the snapshot buffered in the
            //    channel must still be delivered.
            val received = CopyOnWriteArrayList<BleTransport.Notification>()
            val obs = scope.launch {
                merge(
                    proxy.observe(match.device, service, stateChar),
                    proxy.observe(match.device, service, sampleChar),
                ).collect { received.add(it) }
            }

            // 4) Stream N counted samples; assert losslessness + order + timestamps.
            val n = 500
            repeat(n) { i -> hub.onInbound(addr, service, sampleChar, byteArrayOf(i.toByte()), atMs = 1_000L + i) }
            withTimeout(5_000) { while (received.count { it.characteristic == sampleChar } < n) delay(5) }

            val samples = received.filter { it.characteristic == sampleChar }
            assertEquals(n, samples.size, "every sample arrives — no loss, no conflation")
            assertEquals((0 until n).map { 1_000L + it }, samples.map { it.atMs }, "order + verbatim timestamps")

            // snapshot-on-attach delivered the cached state value.
            assertTrue(
                received.any { it.characteristic == stateChar && it.data.contentEquals(byteArrayOf(0x01, 0x02)) },
                "attach snapshot converges the secondary to current machine state",
            )

            // 5) read round-trips.
            val read = withTimeout(2_000) { proxy.read(match.device, service, stateChar) }
            assertContentEquals(versionBytes, read)

            // 6) write is rejected by the relay and swallowed (returns, no throw).
            withTimeout(2_000) { proxy.write(match.device, service, stateChar, byteArrayOf(0x01)) }

            // 7) a primary-side link drop propagates to the secondary's connState.
            hub.onConnState(addr, BleTransport.ConnState.FAILED.name)
            withTimeout(2_000) {
                proxy.connectionState(match.device).first { it == BleTransport.ConnState.FAILED }
            }

            obs.cancel()
        } finally {
            scope.cancel()
        }
    }
}

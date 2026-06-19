package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.close
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
 * The M1 reads-path mirror over a **real WebSocket** — the same [RelayHub] and
 * [ProxyTransport] as the in-process loopback, but bridged by [LanRelayServer]
 * (Ktor CIO server) and a Ktor WebSocket client, both behind [KtorWsFrameLink].
 * Proves the socket layer carries the frames correctly: handshake → roster/scan
 * → attach + snapshot → lossless notify fan-out → read → write-reject. (The
 * 500-sample losslessness stress lives in [ProxyLoopbackTest]; here a smaller
 * stream confirms the wire faithfully relays it.)
 */
class LanRelayWsTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")
    private val sampleChar = UUID.fromString("0000a00d-0000-1000-8000-00805f9b34fb")

    @Test
    fun `secondary mirrors the primary over a websocket`() = runBlocking {
        val versionBytes = byteArrayOf(0x09, 0x00)
        val hub = RelayHub(
            primaryId = "primary-1",
            primaryName = "Kitchen tablet",
            roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
            readSource = { a, s, c ->
                if (a == addr && s == service && c == stateChar) versionBytes else error("no such read")
            },
            isSnapshotChar = { _, c -> c == stateChar },
        )
        val server = LanRelayServer(hub, requestedPort = 0)
        val port = server.start()
        val client = HttpClient(CIO) { install(WebSockets) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val session = client.webSocketSession { url("ws://127.0.0.1:$port${LanRelayServer.PATH}") }
            val proxy = ProxyTransport(KtorWsFrameLink(session), scope, clientId = "phone-1", clientName = "Phone")

            val match = withTimeout(5_000) { proxy.scan { it == "DE1" }.first() }
            assertEquals(addr, match.device.address)

            hub.onInbound(addr, service, stateChar, byteArrayOf(0x01, 0x02), atMs = 100L)
            withTimeout(5_000) { proxy.connect(match.device) }
            assertEquals(BleTransport.ConnState.CONNECTED, proxy.connectionState(match.device).value)

            val received = CopyOnWriteArrayList<BleTransport.Notification>()
            val obs = scope.launch {
                merge(
                    proxy.observe(match.device, service, stateChar),
                    proxy.observe(match.device, service, sampleChar),
                ).collect { received.add(it) }
            }

            val n = 200
            repeat(n) { i -> hub.onInbound(addr, service, sampleChar, byteArrayOf(i.toByte()), atMs = 1_000L + i) }
            withTimeout(5_000) { while (received.count { it.characteristic == sampleChar } < n) delay(5) }

            val samples = received.filter { it.characteristic == sampleChar }
            assertEquals(n, samples.size, "every sample relayed over the wire — no loss")
            assertEquals((0 until n).map { 1_000L + it }, samples.map { it.atMs }, "order + verbatim timestamps")
            assertTrue(
                received.any { it.characteristic == stateChar && it.data.contentEquals(byteArrayOf(0x01, 0x02)) },
                "attach snapshot crossed the wire",
            )

            val read = withTimeout(5_000) { proxy.read(match.device, service, stateChar) }
            assertContentEquals(versionBytes, read)

            withTimeout(5_000) { proxy.write(match.device, service, stateChar, byteArrayOf(0x01)) }

            obs.cancel()
            session.close()
        } finally {
            client.close()
            scope.cancel()
            server.stop()
        }
    }
}

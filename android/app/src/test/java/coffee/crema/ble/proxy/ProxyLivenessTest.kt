package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import coffee.crema.net.HttpClients
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WebSocket liveness on both ends: a link that goes half-open (no FIN, no RST,
 * the bytes just stop) must end the session within ping + timeout. A
 * [FreezableTcpRelay] sits between the client and the relay server and, once
 * frozen, keeps both sockets open but forwards nothing, which is what a dozing
 * phone or a dead Wi-Fi link looks like to the other side.
 */
class ProxyLivenessTest {

    @Test
    fun `the production client engine pings`() {
        val ok = HttpClients.webSocketOkHttp(HttpClients.newBaseOkHttp())
        assertEquals(HttpClients.WS_PING_INTERVAL_MS.toInt(), ok.pingIntervalMillis)
        assertEquals(0, ok.readTimeoutMillis, "a quiet link must not be dropped by a read timeout")
    }

    @Test
    fun `the secondary detects a half-open link and redials`() = runBlocking {
        val rig = RelayRig(hub()).start()
        val remote = RelayKeys.generate()
        rig.trust.pin(remote)
        val relay = FreezableTcpRelay(rig.port)
        val engine = pingingEngine(pingIntervalMs = 200)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = rig.link(remote, at = "ws://127.0.0.1:${relay.port}${LanRelayServer.PATH}", client = HttpClients.webSocketClient(engine))
            val proxy = ProxyTransport(link, scope)
            assertTrue(withTimeout(5_000) { proxy.control("tareScale") }.isSuccess)

            relay.freeze()
            // OkHttp fails the socket once a pong is late; the link drops to
            // CONNECTING (and redials) instead of sitting CONNECTED and silent.
            withTimeout(3_000) { link.state.first { it == LinkState.CONNECTING } }
            link.close()
        } finally {
            scope.cancel()
            relay.close()
            rig.stop()
            engine.close()
        }
    }

    @Test
    fun `the primary sheds a half-open session`() = runBlocking {
        val hub = hub()
        val rig = RelayRig(hub, pingPeriodMs = 200, pingTimeoutMs = 300).start()
        val remote = RelayKeys.generate()
        rig.trust.pin(remote)
        val relay = FreezableTcpRelay(rig.port)
        // This client never pings, so only the server's ping can notice.
        val engine = pingingEngine(pingIntervalMs = 0)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = rig.link(remote, at = "ws://127.0.0.1:${relay.port}${LanRelayServer.PATH}", client = HttpClients.webSocketClient(engine))
            val proxy = ProxyTransport(link, scope)
            assertTrue(withTimeout(5_000) { proxy.control("tareScale") }.isSuccess)
            assertTrue(hub.hasClient(remote.deviceId()))

            relay.freeze()
            withTimeout(5_000) { while (hub.hasClient(remote.deviceId())) delay(20) }
            link.close()
        } finally {
            scope.cancel()
            relay.close()
            rig.stop()
            engine.close()
        }
    }

    private fun hub() = RelayHub(
        primaryName = "P",
        roster = { emptyList() },
        readSource = { _, _, _ -> error("unused") },
        isSnapshotChar = { _, _ -> false },
        controlHandler = { _, _, _, _ -> Result.success(Unit) },
    )

    private fun pingingEngine(pingIntervalMs: Long): HttpClientEngine =
        HttpClients.okHttpEngine(HttpClients.webSocketOkHttp(HttpClients.newBaseOkHttp(), pingIntervalMs))

    /** A loopback TCP forwarder to [targetPort] that can stop forwarding
     *  without closing either socket. */
    private class FreezableTcpRelay(targetPort: Int) : Closeable {
        private val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val sockets = CopyOnWriteArrayList<Socket>()
        @Volatile private var frozen = false
        val port: Int get() = listener.localPort

        init {
            thread(isDaemon = true, name = "relay-accept") {
                while (!listener.isClosed) {
                    val inbound = runCatching { listener.accept() }.getOrNull() ?: break
                    val outbound = Socket(InetAddress.getLoopbackAddress(), targetPort)
                    sockets += inbound
                    sockets += outbound
                    pump(inbound.getInputStream(), outbound.getOutputStream())
                    pump(outbound.getInputStream(), inbound.getOutputStream())
                }
            }
        }

        fun freeze() { frozen = true }

        private fun pump(from: InputStream, to: OutputStream) = thread(isDaemon = true, name = "relay-pump") {
            val buf = ByteArray(8192)
            runCatching {
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    // Frozen: swallow the bytes and never forward them.
                    if (!frozen) { to.write(buf, 0, n); to.flush() }
                }
            }
        }

        override fun close() {
            runCatching { listener.close() }
            sockets.forEach { runCatching { it.close() } }
        }
    }
}

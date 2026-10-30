package coffee.crema.ble.proxy

import coffee.crema.net.HttpClients
import io.ktor.websocket.close
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Only Crema's own client may reach the relay: any browser `Origin`, or a
 *  `Host` that isn't a LAN name, is refused before the WebSocket upgrade. */
class LanRelayOriginTest {

    @Test
    fun `the upgrade check`() {
        assertTrue(LanRelayServer.isAllowedUpgrade(null, "192.168.1.20:41234"))
        assertTrue(LanRelayServer.isAllowedUpgrade(null, "[fe80::1]:41234"))
        assertTrue(LanRelayServer.isAllowedUpgrade(null, "localhost:8080"))
        assertTrue(LanRelayServer.isAllowedUpgrade(null, "kitchen-tablet.local:8080"))
        assertTrue(LanRelayServer.isAllowedUpgrade(null, "tablet"))
        assertFalse(LanRelayServer.isAllowedUpgrade("http://192.168.1.5", "192.168.1.20:41234"), "a web page")
        assertFalse(LanRelayServer.isAllowedUpgrade("null", "192.168.1.20:41234"), "a sandboxed page")
        assertFalse(LanRelayServer.isAllowedUpgrade(null, "relay.example.com:41234"), "a public name")
        assertFalse(LanRelayServer.isAllowedUpgrade(null, null))
        assertFalse(LanRelayServer.isAllowedUpgrade(null, ""))
    }

    @Test
    fun `a request with an Origin is refused before the upgrade`() = runBlocking {
        // Only the upgrade's HTTP status matters here: the handler just closes.
        val server = LanRelayServer(handler = { ws, _ -> ws.close() })
        val port = server.start()
        val ok = HttpClients.newBaseOkHttp()
        try {
            /** Open a WebSocket the way OkHttp does; the HTTP status it ends with. */
            suspend fun upgrade(origin: String?): Int {
                val result = CompletableDeferred<Int>()
                val req = Request.Builder()
                    .url("ws://127.0.0.1:$port${LanRelayServer.PATH}")
                    .apply { if (origin != null) header("Origin", origin) }
                    .build()
                val ws = ok.newWebSocket(req, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { result.complete(response.code) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        result.complete(response?.code ?: -1)
                    }
                })
                return try { withTimeout(5_000) { result.await() } } finally { ws.cancel() }
            }
            assertEquals(403, upgrade("http://192.168.1.5"))
            assertEquals(101, upgrade(null), "Crema's client (no Origin) still upgrades")
        } finally {
            server.stop()
            ok.dispatcher.executorService.shutdown()
        }
    }
}

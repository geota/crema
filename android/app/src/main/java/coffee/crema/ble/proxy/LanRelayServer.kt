package coffee.crema.ble.proxy

import android.util.Log
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket

/**
 * The primary-side LAN WebSocket server: it accepts secondary connections and
 * hands each to a [RelayHub]. A thin shell — Ktor (CIO engine) accepts the
 * socket and upgrades it to a WebSocket; every connection is wrapped in a
 * [KtorWsFrameLink] and driven by [RelayHub.serve]. All protocol logic lives in
 * the (link-agnostic, already unit-proven) [RelayHub]; this only owns the socket.
 *
 * Runs **on the primary device** (the DE1 owner). [start] binds an ephemeral
 * port by default and returns the resolved port so the caller can advertise it
 * over NSD (`_crema._tcp`). [INTERNET] is the only permission needed.
 */
class LanRelayServer(
    private val hub: RelayHub,
    private val requestedPort: Int = 0,
    private val path: String = PATH,
) {
    private var server: EmbeddedServer<*, *>? = null

    /** Start listening and return the bound port. Suspends until the connector is
     *  resolved (the socket is accepting), so a caller can advertise the port
     *  immediately after this returns. */
    suspend fun start(): Int {
        val s = embeddedServer(CIO, port = requestedPort) {
            install(WebSockets)
            routing {
                webSocket(path) {
                    // `this` is a DefaultWebSocketServerSession (a WebSocketSession).
                    // serve() suspends for the life of this connection.
                    hub.serve(KtorWsFrameLink(this))
                }
            }
        }
        s.start(wait = false)
        server = s
        val port = s.engine.resolvedConnectors().first().port
        Log.i(TAG, "LAN relay listening on :$port$path")
        return port
    }

    /** Stop the server and drop all connections. Idempotent. */
    fun stop() {
        server?.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
        server = null
    }

    companion object {
        private const val TAG = "LanRelayServer"
        private const val STOP_TIMEOUT_MS = 500L

        /** The WebSocket path secondaries (and the future PWA) dial. */
        const val PATH = "/de1-bridge"
    }
}

package coffee.crema.ble.proxy

import android.util.Log
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import kotlin.time.Duration.Companion.milliseconds

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
    /** Liveness: ping every [pingPeriodMs], close a session whose pong is
     *  [pingTimeoutMs] late. Tests shorten both. */
    private val pingPeriodMs: Long = PING_PERIOD_MS,
    private val pingTimeoutMs: Long = PING_TIMEOUT_MS,
) {
    private val lock = Any()
    private var server: EmbeddedServer<*, *>? = null
    private var stopped = false

    /** Start listening and return the bound port. Suspends until the connector is
     *  resolved (the socket is accepting), so a caller can advertise the port
     *  immediately after this returns. Throws if [stop] ran first or while
     *  starting; nothing is left listening then. */
    suspend fun start(): Int {
        val s = embeddedServer(CIO, port = requestedPort) {
            install(WebSockets) {
                maxFrameSize = PROXY_MAX_FRAME_BYTES
                // Liveness: a TCP that dies without FIN (phone doze, wifi
                // power-save, AP roam) must not leave a "connected" zombie —
                // ping every 15 s, close after 10 s of silence, so a dead peer's
                // session ends and the hub sheds it instead of queueing forever.
                pingPeriod = pingPeriodMs.milliseconds
                timeout = pingTimeoutMs.milliseconds
            }
            // Only Crema's own client may open the relay: refuse anything a web
            // page could send (see [isAllowedUpgrade]) before the upgrade.
            intercept(ApplicationCallPipeline.Plugins) {
                val headers = call.request.headers
                if (!isAllowedUpgrade(headers[HttpHeaders.Origin], headers[HttpHeaders.Host])) {
                    Log.w(TAG, "Refused a relay connection (origin=${headers[HttpHeaders.Origin]}, host=${headers[HttpHeaders.Host]})")
                    call.respond(HttpStatusCode.Forbidden)
                    finish()
                }
            }
            routing {
                webSocket(path) {
                    // `this` is a DefaultWebSocketServerSession (a WebSocketSession).
                    // serve() suspends for the life of this connection.
                    hub.serve(KtorWsFrameLink(this))
                }
            }
        }
        synchronized(lock) { check(!stopped) { "relay stopped before it started" } }
        s.start(wait = false)
        // A stop() that ran while this was starting saw no server to stop:
        // stop the one just started rather than leave it listening.
        val keep = synchronized(lock) { if (stopped) false else { server = s; true } }
        if (!keep) {
            s.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
            error("relay stopped while starting")
        }
        val port = s.engine.resolvedConnectors().first().port
        Log.i(TAG, "LAN relay listening on :$port$path")
        return port
    }

    /** Stop the server and drop all connections — including a start that is
     *  still in flight. Idempotent; a stopped server can't be started again. */
    fun stop() {
        val s = synchronized(lock) {
            stopped = true
            server.also { server = null }
        }
        s?.stop(gracePeriodMillis = 0, timeoutMillis = STOP_TIMEOUT_MS)
    }

    companion object {
        private const val TAG = "LanRelayServer"
        private const val STOP_TIMEOUT_MS = 500L
        private const val PING_PERIOD_MS = 15_000L
        private const val PING_TIMEOUT_MS = 10_000L

        /** The WebSocket path secondaries (and the future PWA) dial. */
        const val PATH = "/de1-bridge"

        /**
         * Whether a connection request may reach the relay. Browsers always send
         * `Origin` on a WebSocket upgrade and Crema's client never does, so any
         * `Origin` (including `null`) is refused. `Host` must name this device
         * the way a LAN client does: an IP literal, `localhost`, a `.local` name
         * or a single-label name. A public DNS name means the request came by
         * way of some other site's name, so it is refused too.
         */
        fun isAllowedUpgrade(origin: String?, host: String?): Boolean {
            if (origin != null) return false
            val name = hostName(host ?: return false).lowercase()
            if (name.isEmpty()) return false
            return name == "localhost" || name.endsWith(".local") || '.' !in name || isIpLiteral(name)
        }

        /** [host] without its port (IPv6 literals keep their brackets off). */
        private fun hostName(host: String): String = when {
            host.startsWith("[") -> host.substringAfter('[').substringBefore(']')
            host.count { it == ':' } == 1 -> host.substringBefore(':')
            else -> host
        }

        private fun isIpLiteral(name: String): Boolean =
            ':' in name || name.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } }
    }
}

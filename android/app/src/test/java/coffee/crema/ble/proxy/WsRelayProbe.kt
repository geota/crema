package coffee.crema.ble.proxy

import coffee.crema.net.HttpClients
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The relay probe (spec AC-24/25/34, after validation P1b's probe E): a
 * WebSocket relay between a real remote and a real hub that forwards every
 * message verbatim by default, records them all ([captured]), and lets a test
 * tamper with the remote → hub direction ([tamper]), inject messages
 * ([injectToHub]) or stop forwarding hub records ([dropHubRecords]). It sits at
 * the application layer, so WebSocket pings stay per hop (each side's TCP
 * looks alive), which is what an on-path attacker can always do.
 */
internal class WsRelayProbe(private val hubPort: Int, private val bindHost: String = "127.0.0.1") {
    /** One relayed message: [toHub] is the direction; [text] for a text message. */
    data class Msg(val toHub: Boolean, val text: Boolean, val bytes: ByteArray)

    /** Every message seen, both directions, in order (before tampering). */
    val captured: MutableList<Msg> = CopyOnWriteArrayList()

    /** Rewrites what the remote sends (index counts the remote's messages on this connection). */
    @Volatile var tamper: (index: Int, msg: Msg) -> List<Msg> = { _, m -> listOf(m) }

    /** Stop forwarding the hub's binary messages (pings still flow per hop). */
    @Volatile var dropHubRecords = false

    /** The close code the hub sent on each relayed connection, in order. */
    val hubCloseCodes: MutableList<Short> = CopyOnWriteArrayList()

    /** The hub side of the current connection (for injection). */
    @Volatile private var toHub: WebSocketSession? = null

    val firstConnection = CompletableDeferred<Unit>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val client = HttpClients.webSocketClient()
    private var server: EmbeddedServer<*, *>? = null
    var port: Int = 0
        private set

    val url: String get() = "ws://$bindHost:$port${LanRelayServer.PATH}"

    fun start(): WsRelayProbe {
        val s = embeddedServer(CIO, port = 0, host = bindHost) {
            install(WebSockets)
            routing { webSocket(LanRelayServer.PATH) { relay(this) } }
        }
        s.start(wait = false)
        port = runBlocking { s.engine.resolvedConnectors().first().port }
        server = s
        return this
    }

    /** Send [msg] to the hub on the current connection, as if the remote had. */
    suspend fun injectToHub(msg: Msg) {
        toHub?.send(if (msg.text) WsFrame.Text(true, msg.bytes) else WsFrame.Binary(true, msg.bytes))
    }

    private suspend fun relay(remote: WebSocketSession) = coroutineScope {
        val hub = client.webSocketSession { url("ws://127.0.0.1:$hubPort${LanRelayServer.PATH}") }
        toHub = hub
        firstConnection.complete(Unit)
        val down = launch {
            try {
                for (f in hub.incoming) {
                    val msg = when (f) {
                        is WsFrame.Text -> Msg(false, true, f.data)
                        is WsFrame.Binary -> Msg(false, false, f.data)
                        else -> continue
                    }
                    captured += msg
                    if (dropHubRecords && !msg.text) continue
                    remote.send(if (msg.text) WsFrame.Text(true, msg.bytes) else WsFrame.Binary(true, msg.bytes))
                }
            } catch (_: ClosedReceiveChannelException) {
            }
            val reason = runCatching { hub.closeReason.await() }.getOrNull()
            reason?.let { hubCloseCodes += it.code }
            runCatching { remote.close(reason ?: CloseReason(CloseReason.Codes.NORMAL, "")) }
        }
        var index = 0
        try {
            for (f in remote.incoming) {
                val msg = when (f) {
                    is WsFrame.Text -> Msg(true, true, f.data)
                    is WsFrame.Binary -> Msg(true, false, f.data)
                    else -> continue
                }
                captured += msg
                for (out in tamper(index++, msg)) {
                    hub.send(if (out.text) WsFrame.Text(true, out.bytes) else WsFrame.Binary(true, out.bytes))
                }
            }
        } catch (_: Exception) {
        }
        runCatching { hub.close() }
        down.join()
    }

    fun stop() {
        server?.stop(0, 500)
        runCatching { client.close() }
        scope.cancel()
    }

    companion object {
        /** The relay envelope kind of a binary message (spec §4.3). */
        const val KIND_NOISE_HS: Byte = 0x30
        const val KIND_RECORD: Byte = 0x40
    }
}

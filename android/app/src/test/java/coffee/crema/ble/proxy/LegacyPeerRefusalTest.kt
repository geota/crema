package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import coffee.crema.net.HttpClients
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.readText
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-26, old peers end to end (spec §4.6): a v3 remote (the golden v3 hello
 * over a raw client) gets exactly the text denial, close 4426 and the hub's
 * notice; a v4 remote against a stub v3 hub that denies it reports an
 * advisory `HubOutdated` and sends nothing after its ClientHello; against a
 * stub that answers with a v3 `challenge` it closes with no further message.
 */
class LegacyPeerRefusalTest {

    @Test
    fun `a v3 remote gets the text denial and the hub's notice`() {
        val rig = RelayRig().start()
        val client = HttpClients.webSocketClient()
        try {
            val hello = javaClass.classLoader!!.getResource("proxy-golden/v3/frames/hello.json")!!.readText().trimEnd('\n')
            val (texts, binaries, code) = runBlocking {
                val ws = client.webSocketSession { url(rig.url) }
                ws.send(WsFrame.Text(hello))
                val texts = mutableListOf<String>()
                var binaries = 0
                withTimeout(5_000) {
                    for (f in ws.incoming) when (f) {
                        is WsFrame.Text -> texts += f.readText()
                        is WsFrame.Binary -> binaries++
                        else -> Unit
                    }
                }
                Triple(texts, binaries, ws.closeReason.await()?.code?.toInt())
            }
            assertEquals(listOf("""{"type":"denied","reason":"version mismatch"}"""), texts)
            assertEquals(0, binaries, "never binary to a legacy peer")
            assertEquals(4426, code)
            runBlocking { eventually(what = "the hub's notice") { rig.trust.legacy.isNotEmpty() } }
            assertEquals(listOf("Sam's phone"), rig.trust.legacy.toList())
        } finally {
            client.close()
            rig.stop()
        }
    }

    /** A stub v3 hub: answers the first text with [reply] and records everything it gets. */
    private class StubV3Hub(private val reply: String) {
        val received = CopyOnWriteArrayList<String>()
        val remoteClosed = CompletableDeferred<Unit>()
        private val server = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(WebSockets)
            routing {
                webSocket(LanRelayServer.PATH) {
                    try {
                        for (f in incoming) {
                            received += when (f) {
                                is WsFrame.Text -> f.readText()
                                is WsFrame.Binary -> "binary:${f.data.size}"
                                else -> continue
                            }
                            if (received.size == 1) send(WsFrame.Text(reply))
                        }
                    } finally {
                        remoteClosed.complete(Unit)
                    }
                }
            }
        }.also { it.start(wait = false) }
        val port = runBlocking { server.engine.resolvedConnectors().first().port }
        fun stop() = server.stop(0, 500)
    }

    @Test
    fun `a v4 remote against a v3 hub's denial reports an advisory HubOutdated and sends nothing more`() {
        val stub = StubV3Hub("""{"type":"denied","reason":"version mismatch"}""")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val refusals = CopyOnWriteArrayList<String>()
            val link = ReconnectingClientLink("ws://127.0.0.1:${stub.port}${LanRelayServer.PATH}", scope, RelayKeys.generate(), RelayKeys.generate().publicKey())
            ProxyTransport(link, scope, onRefused = { reason, auth, terminal -> refusals += "$reason/$auth/$terminal" })
            runBlocking { eventually(what = "the refusal") { refusals.isNotEmpty() } }
            assertEquals(listOf("HubOutdated/false/true"), refusals.toList(), "advisory: the caller keeps the role")
            runBlocking { delay(1_000) }
            assertEquals(1, stub.received.size, "only the ClientHello: ${stub.received}")
            assertTrue(stub.received.single().contains("\"vmax\":4"), stub.received.single())
        } finally {
            scope.cancel()
            stub.stop()
        }
    }

    @Test
    fun `a v4 remote against a v3 challenge closes with no further message`() {
        val stub = StubV3Hub("""{"type":"challenge","primaryId":"host-1","primaryName":"Old hub","nonce":"bm9uY2U"}""")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ReconnectingClientLink("ws://127.0.0.1:${stub.port}${LanRelayServer.PATH}", scope, RelayKeys.generate(), RelayKeys.generate().publicKey())
            ProxyTransport(link, scope)
            runBlocking { withTimeout(10_000) { stub.remoteClosed.await() } }
            assertEquals(1, stub.received.size, "only the ClientHello: ${stub.received}")
        } finally {
            scope.cancel()
            stub.stop()
        }
    }
}

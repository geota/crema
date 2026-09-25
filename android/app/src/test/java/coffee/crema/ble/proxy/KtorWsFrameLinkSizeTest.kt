package coffee.crema.ble.proxy

import coffee.crema.net.HttpClients
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.send
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The secondary's frame cap. The OkHttp client engine can't take
 * `WebSockets { maxFrameSize }`, so [KtorWsFrameLink] enforces
 * [PROXY_MAX_FRAME_BYTES] on incoming frames itself — as the relay server's
 * Ktor plugin does: a frame up to the cap is delivered, a bigger one is never
 * decoded, the socket closes with 1009 TOO_BIG and [KtorWsFrameLink.incoming]
 * completes (nothing after it is delivered).
 */
class KtorWsFrameLinkSizeTest {

    /** An Event frame whose encoded text is exactly [bytes] UTF-8 bytes. */
    private fun eventOfSize(bytes: Long): String {
        val overhead = FrameCodec.encode(Frame.Event("")).toByteArray().size
        return FrameCodec.encode(Frame.Event("a".repeat((bytes - overhead).toInt())))
            .also { check(it.toByteArray().size.toLong() == bytes) }
    }

    @Test
    fun `an oversize incoming frame closes the link with 1009`() = runBlocking {
        val closed = CompletableDeferred<CloseReason?>()
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets) // no cap here: the test server must be able to SEND a big frame
            routing {
                webSocket("/ws") {
                    send(FrameCodec.encode(Frame.Event("small")))
                    send(eventOfSize(PROXY_MAX_FRAME_BYTES)) // exactly at the cap: allowed
                    send(eventOfSize(PROXY_MAX_FRAME_BYTES + 1)) // one byte over
                    send(FrameCodec.encode(Frame.Event("after")))
                    closed.complete(closeReason.await())
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClients.webSocketClient()
        try {
            val session = client.webSocketSession { url("ws://127.0.0.1:$port/ws") }
            val link = KtorWsFrameLink(session, maxFrameBytes = PROXY_MAX_FRAME_BYTES)
            val got = withTimeout(10_000) { link.incoming().toList() }
            assertEquals(2, got.size)
            assertEquals(Frame.Event("small"), got[0])
            assertEquals(PROXY_MAX_FRAME_BYTES, FrameCodec.encode(got[1]).toByteArray().size.toLong())
            val reason = withTimeout(10_000) { closed.await() }
            assertEquals(CloseReason.Codes.TOO_BIG.code, reason?.code)
        } finally {
            client.close()
            server.stop(0, 500)
        }
    }

    @Test
    fun `without a cap the link delivers any size (the server end)`() = runBlocking {
        val server = embeddedServer(CIO, port = 0) {
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    send(eventOfSize(PROXY_MAX_FRAME_BYTES + 1))
                    closeReason.await()
                }
            }
        }.start(wait = false)
        val port = server.engine.resolvedConnectors().first().port
        val client = HttpClients.webSocketClient()
        try {
            val session = client.webSocketSession { url("ws://127.0.0.1:$port/ws") }
            val link = KtorWsFrameLink(session)
            val first = withTimeout(10_000) { link.incoming().first() }
            assertEquals(PROXY_MAX_FRAME_BYTES + 1, FrameCodec.encode(first).toByteArray().size.toLong())
            link.close()
        } finally {
            client.close()
            server.stop(0, 500)
        }
    }
}

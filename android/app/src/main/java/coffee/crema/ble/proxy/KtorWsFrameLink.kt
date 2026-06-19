package coffee.crema.ble.proxy

import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A [FrameLink] over a Ktor [WebSocketSession]. One class backs **both** ends of
 * the LAN proxy: the primary's [WebSocketSession] is a
 * `DefaultWebSocketServerSession`, the secondary's a `DefaultClientWebSocketSession`,
 * and both are `WebSocketSession` — so the relay and the proxy speak to the same
 * seam regardless of which side they run on.
 *
 * Frames ride as WebSocket **text** messages (the [FrameCodec] JSON), which keeps
 * them browser-readable for the M4 PWA client. Non-text frames (ping/pong, which
 * Ktor handles itself; close) are filtered out of [incoming]; a closed socket
 * completes the flow.
 */
class KtorWsFrameLink(private val session: WebSocketSession) : FrameLink {

    override suspend fun send(frame: Frame) {
        session.send(FrameCodec.encode(frame))
    }

    override fun incoming(): Flow<Frame> =
        session.incoming.receiveAsFlow()
            .filterIsInstance<WsFrame.Text>()
            .map { FrameCodec.decode(it.readText()) }

    override suspend fun close() {
        session.close()
    }
}

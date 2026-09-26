package coffee.crema.ble.proxy

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

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
 *
 * [maxFrameBytes] is the client side's frame cap. The relay server enforces
 * [PROXY_MAX_FRAME_BYTES] in Ktor's server WebSockets plugin, but the OkHttp
 * client engine doesn't support `maxFrameSize` (it throws), so the secondary's
 * link checks each text frame itself: an oversize one is never decoded — the
 * link closes the socket with 1009 TOO_BIG, as Ktor's server does, and
 * [incoming] completes (the reconnecting link then redials). OkHttp has
 * already buffered that message by then; the cap bounds what the protocol
 * layer accepts, and the peer is the primary this device chose to dial.
 */
class KtorWsFrameLink(
    private val session: WebSocketSession,
    private val maxFrameBytes: Long? = null,
) : FrameLink {

    override suspend fun send(frame: Frame) {
        session.send(FrameCodec.encode(frame))
    }

    override fun incoming(): Flow<Frame> = flow {
        for (frame in session.incoming) {
            if (frame !is WsFrame.Text) continue
            val max = maxFrameBytes
            if (max != null && frame.data.size > max) {
                runCatching {
                    session.close(CloseReason(CloseReason.Codes.TOO_BIG, "Frame is too big: ${frame.data.size}. Max size is $max"))
                }
                return@flow
            }
            emit(FrameCodec.decode(frame.readText()))
        }
    }

    override suspend fun close() {
        session.close()
    }

    /** Non-suspending teardown: cancel the session (and its socket). */
    fun cancel() {
        session.cancel()
    }
}

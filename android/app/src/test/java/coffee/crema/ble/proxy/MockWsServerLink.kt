package coffee.crema.ble.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The server end of one MockWebServer WebSocket as a [FrameLink], for a test
 * that plays the primary against a real secondary stack (OkHttp client,
 * [ReconnectingClientLink], [ProxyTransport]). [receivedText] logs every text
 * message the client sent, exactly as it arrived and before it is decoded:
 * the raw wire a real secondary produces. Serve it with [RelayHub.serve], or
 * read [incoming] directly.
 *
 * Enqueue it with `MockResponse.Builder().webSocketUpgrade(link).build()`.
 */
internal class MockWsServerLink : WebSocketListener(), FrameLink {

    /** Every text message from the client, in arrival order. */
    val receivedText: MutableList<String> = CopyOnWriteArrayList()

    /** Completes when the client's socket has gone (closed or failed). */
    val gone = CompletableDeferred<Unit>()

    private val inbox = Channel<String>(Channel.UNLIMITED)
    private val socket = CompletableDeferred<WebSocket>()

    override fun onOpen(webSocket: WebSocket, response: Response) {
        socket.complete(webSocket)
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        receivedText += text
        inbox.trySend(text)
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(code, null)
        end()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = end()

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = end()

    private fun end() {
        inbox.close()
        gone.complete(Unit)
    }

    override suspend fun send(frame: Frame) {
        socket.await().send(FrameCodec.encode(frame))
    }

    override fun incoming(): Flow<Frame> = inbox.receiveAsFlow().map { FrameCodec.decode(it) }

    override suspend fun close() {
        socket.await().close(1000, null)
    }
}

package coffee.crema.ble.proxy

import coffee.crema.core.RelayCloseReason
import coffee.crema.core.RelayOutgoing
import coffee.crema.core.RelayStep
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * AC-32, the deterministic half: [RelayWire] writes a Step's `send` before it
 * releases the connection's lock (spec §3.5), so a slow socket write holds the
 * next encryption back and the wire order is the encryption order. The real
 * socket test ([NoiseFrameLinkOrderTest]) can miss the window; this one holds
 * the first write open while a second call waits.
 */
class RelayWireLockTest {

    /** Numbers each encrypted message 1, 2, … (one byte, the message's `send`). */
    private class CountingConn : RelayConn {
        private var n = 0
        private fun step(vararg out: Int) =
            RelayStep(out.map { RelayOutgoing(false, byteArrayOf(it.toByte())) }, emptyList(), "[]", null, null)

        override fun onText(raw: ByteArray, nowMs: ULong) = step()
        override fun onBinary(bytes: ByteArray, nowMs: ULong) = step()
        override fun send(message: ByteArray, nowMs: ULong) = step(++n)
        override fun closeSession(reason: RelayCloseReason, nowMs: ULong) = step()
        override fun tick(nowMs: ULong) = step()
        override fun nextDeadlineMs(): ULong? = null
        override fun peerPublicKey(): ByteArray? = null
        override fun destroy() {}
    }

    /** A socket whose first write takes 200 ms; records what reached the wire. */
    private class SlowFirstWrite : WebSocketSession {
        val wire = CopyOnWriteArrayList<Int>()
        override val coroutineContext: CoroutineContext = Job()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming: ReceiveChannel<WsFrame> = Channel()
        override val outgoing: SendChannel<WsFrame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()

        override suspend fun send(frame: WsFrame) {
            val n = frame.data[0].toInt()
            if (n == 1) delay(200)
            wire += n
        }

        override suspend fun flush() {}

        @Suppress("OVERRIDE_DEPRECATION")
        override fun terminate() {}
    }

    @Test
    fun `a slow socket write holds the next encryption back`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ws = SlowFirstWrite()
            val wire = RelayWire(ws, CountingConn(), scope, object : RelayWire.Listener {})
            val first = scope.async { wire.call { send(byteArrayOf(), it) } }
            delay(50) // the first write is in flight
            val second = scope.async { wire.call { send(byteArrayOf(), it) } }
            first.await()
            second.await()
            assertEquals(listOf(1, 2), ws.wire.toList(), "encryption order is wire order")
        } finally {
            scope.cancel()
        }
    }
}

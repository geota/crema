package coffee.crema.ble.proxy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A duplex, ordered, reliable stream of [Frame]s — the transport-agnostic seam
 * the proxy protocol rides on. One [FrameLink] is one connection between a
 * secondary's [ProxyTransport] and the primary's [RelayHub].
 *
 * M1 ships two implementations: [InMemoryFrameLink] (an in-process loopback for
 * unit tests — no sockets, no NSD, no Bluetooth) and, at the WebSocket step, a
 * Ktor-backed link. Both preserve per-link frame order (WebSocket already does;
 * the in-memory pair uses ordered channels), which is what lets the protocol
 * lean on "frames arrive in send order" for losslessness.
 */
interface FrameLink {
    /** Send one frame to the peer. Suspends only if an underlying buffer is full. */
    suspend fun send(frame: Frame)

    /** The frames arriving from the peer, in order. Collected by exactly one consumer. */
    fun incoming(): Flow<Frame>

    /** Close this end. Idempotent. */
    suspend fun close()
}

/**
 * An in-process [FrameLink] pair wired end-to-end through two unbounded ordered
 * channels — what one end [send]s, the other [incoming]s. The loopback the M1
 * relay/proxy unit tests run on: it exercises the full protocol path
 * (handshake → attach → snapshot → notify fan-out → read → write-reject) with no
 * socket, no NSD and no emulator, so the protocol logic is validated before any
 * Ktor/WebSocket binding exists.
 */
class InMemoryFrameLink private constructor(
    private val outbound: Channel<Frame>,
    private val inbound: Channel<Frame>,
) : FrameLink {
    override suspend fun send(frame: Frame) {
        outbound.send(frame)
    }

    override fun incoming(): Flow<Frame> = inbound.receiveAsFlow()

    override suspend fun close() {
        outbound.close()
    }

    companion object {
        /** A connected `(clientEnd, serverEnd)` pair: the client's `send` is the
         *  server's `incoming` and vice-versa. */
        fun pair(): Pair<FrameLink, FrameLink> {
            val toServer = Channel<Frame>(Channel.UNLIMITED)
            val toClient = Channel<Frame>(Channel.UNLIMITED)
            return InMemoryFrameLink(outbound = toServer, inbound = toClient) to
                InMemoryFrameLink(outbound = toClient, inbound = toServer)
        }
    }
}

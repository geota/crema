package coffee.crema.ble.proxy

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow

/** What a [FrameLink] delivers to its one consumer, with session boundaries. */
sealed interface LinkEvent {
    /** A new session (connection) went live; every later [Received] frame
     *  belongs to it until the next [SessionStarted]. */
    data class SessionStarted(val epoch: Long) : LinkEvent

    /** A frame arrived on the current session. */
    data class Received(val frame: Frame) : LinkEvent
}

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

    /**
     * [incoming] with session boundaries — use one or the other, not both. A
     * link that redials ([ReconnectingClientLink]) starts each new connection
     * with [LinkEvent.SessionStarted]; the default is one session (epoch 1)
     * for the life of the link.
     */
    fun events(): Flow<LinkEvent> =
        incoming().map<Frame, LinkEvent> { LinkEvent.Received(it) }.onStart { emit(LinkEvent.SessionStarted(1L)) }

    /** Send [frame] on session [epoch] only: false (nothing sent) when that
     *  session is no longer the live one. The default link has one session. */
    suspend fun sendOn(epoch: Long, frame: Frame): Boolean {
        send(frame)
        return true
    }
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

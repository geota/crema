package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.core.RelayCloseReason
import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A [FrameLink] over one established relay v4 session (spec §8): each [Frame]
 * is the UTF-8 JSON payload of one application message, which the core
 * encrypts into `FRAME` records (fragmenting large ones). The [RelayWire]
 * holds the connection's one send lock, so frames sent from any number of
 * coroutines are encrypted and written in the same order (AC-32), and its
 * deadline timer sends the heartbeats and closes a silent session.
 *
 * Inbound messages arrive through [deliver] (the wire's listener) and are
 * decoded tolerantly: an unknown frame type is skipped, malformed JSON closes
 * the session with 4400 (spec §4.5). [incoming] completes when the session
 * ends.
 */
internal class NoiseFrameLink(private val wire: RelayWire) : FrameLink {
    private val inbox = Channel<Frame>(Channel.UNLIMITED)

    override suspend fun send(frame: Frame) {
        val bytes = FrameCodec.encode(frame).encodeToByteArray()
        if (!wire.call { send(bytes, it) }) throw IOException("relay session closed")
    }

    override fun incoming(): Flow<Frame> = inbox.receiveAsFlow()

    /** End the session normally: an authenticated `CLOSE(NORMAL)`, then the socket. */
    override suspend fun close() {
        closeWith(RelayCloseReason.NORMAL)
    }

    /** The hub forgot this device: an authenticated `CLOSE(REVOKED)` (spec §2.10). */
    override suspend fun revoke() {
        closeWith(RelayCloseReason.REVOKED)
    }

    /** Close the session with an authenticated [reason]; a socket that already ended is left alone. */
    suspend fun closeWith(reason: RelayCloseReason) {
        if (!wire.call { closeSession(reason, it) }) ended()
    }

    /** One decrypted message from the wire. */
    suspend fun deliver(message: ByteArray) {
        val frame = try {
            FrameCodec.decodeTolerant(message.decodeToString(throwOnInvalidSequence = true))
        } catch (e: Exception) {
            Log.w(TAG, "Malformed frame on a relay session: ${e.javaClass.simpleName} — closing")
            wire.abort(CLOSE_PROTOCOL, "malformed frame")
            return
        }
        if (frame != null) inbox.trySend(frame)
    }

    /** The session ended: [incoming] completes. */
    fun ended() {
        inbox.close()
    }

    private companion object {
        const val TAG = "NoiseFrameLink"

        /** WebSocket close code for a protocol error (spec §4.5). */
        const val CLOSE_PROTOCOL = 4400
    }
}

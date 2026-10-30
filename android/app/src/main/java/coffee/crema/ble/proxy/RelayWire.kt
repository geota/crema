package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.core.RelayCloseReason
import coffee.crema.core.RelayEvent
import coffee.crema.core.RelayFfiException
import coffee.crema.core.RelayHubConnection
import coffee.crema.core.RelayRemoteConnection
import coffee.crema.core.RelayStep
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One core relay connection (spec §3.5), behind the calls the shell makes. The
 * hub and remote UniFFI objects share no interface, so [HubConn] and
 * [RemoteConn] adapt them. Every call that returns a [RelayStep] runs inside
 * [RelayWire.call], under the connection's one lock.
 */
internal interface RelayConn {
    fun onText(raw: ByteArray, nowMs: ULong): RelayStep
    fun onBinary(bytes: ByteArray, nowMs: ULong): RelayStep

    /** Encrypt one application message. Throws [RelayFfiException] outside an established session. */
    fun send(message: ByteArray, nowMs: ULong): RelayStep
    fun closeSession(reason: RelayCloseReason, nowMs: ULong): RelayStep
    fun tick(nowMs: ULong): RelayStep
    fun nextDeadlineMs(): ULong?

    /** The peer's static key once it is trusted (PROTOCOL.md §12), else null. */
    fun peerPublicKey(): ByteArray?

    /** Free the native object. Called once, after the socket ended. */
    fun destroy()
}

/** The hub side of one WebSocket. */
internal class HubConn(val core: RelayHubConnection) : RelayConn {
    override fun onText(raw: ByteArray, nowMs: ULong) = core.onText(raw, nowMs)
    override fun onBinary(bytes: ByteArray, nowMs: ULong) = core.onBinary(bytes, nowMs)
    override fun send(message: ByteArray, nowMs: ULong) = core.send(message, nowMs)
    override fun closeSession(reason: RelayCloseReason, nowMs: ULong) = core.closeSession(reason, nowMs)
    override fun tick(nowMs: ULong) = core.tick(nowMs)
    override fun nextDeadlineMs() = core.nextDeadlineMs()
    override fun peerPublicKey() = core.peerPublicKey()
    override fun destroy() = core.destroy()
}

/** The remote side of one WebSocket (a resume or one pairing attempt). */
internal class RemoteConn(val core: RelayRemoteConnection) : RelayConn {
    override fun onText(raw: ByteArray, nowMs: ULong) = core.onText(raw, nowMs)
    override fun onBinary(bytes: ByteArray, nowMs: ULong) = core.onBinary(bytes, nowMs)
    override fun send(message: ByteArray, nowMs: ULong) = core.send(message, nowMs)
    override fun closeSession(reason: RelayCloseReason, nowMs: ULong) = core.closeSession(reason, nowMs)
    override fun tick(nowMs: ULong) = core.tick(nowMs)
    override fun nextDeadlineMs() = core.nextDeadlineMs()
    override fun peerPublicKey() = core.peerPublicKey()
    override fun destroy() = core.destroy()
}

/**
 * Drives one [RelayConn] over one WebSocket, following the Android usage
 * contract (spec §3.5):
 *
 * - every core call runs under one [Mutex] per connection, and the Step's
 *   `send` goes out, in order, before the lock is released, so encryption
 *   order equals wire order;
 * - after the lock, the Step is applied in order: deliver, events, close;
 * - one timer is re-armed for `next_deadline_ms()` after every call, and fires
 *   `tick`;
 * - when the socket ends, the native object is destroyed (never left to the GC).
 *
 * Nothing here logs `send` bytes, codes, keys, the psk or the channel binding:
 * only event names, close codes and error kinds.
 */
internal class RelayWire(
    private val ws: WebSocketSession,
    private val conn: RelayConn,
    /** Where the deadline timer runs: the socket's own scope. */
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the owner does with a Step's output. Called outside the connection's lock. */
    interface Listener {
        /** One decrypted application message, in order. */
        suspend fun onDeliver(message: ByteArray) {}

        /** The Step's events, in order. */
        suspend fun onEvents(events: List<RelayEvent>) {}

        /** The connection ended ([code] is the WebSocket close code when the core or the shell chose one). */
        fun onClosed(code: Int?) {}

        /** One inbound message was fed to the core and its Step applied. */
        suspend fun onInboundHandled() {}
    }

    private val lock = Mutex()

    /** The connection ended: every later call is a no-op. */
    @Volatile
    var closed: Boolean = false
        private set

    private var closeCode: Int? = null
    private var destroyed = false
    private var timer: Job? = null

    /**
     * Run one core call under the lock and send its output in order, then
     * apply the rest of the Step. Returns false when the connection had
     * already ended or the call failed (a [RelayFfiException], logged by kind).
     */
    suspend fun call(block: RelayConn.(nowMs: ULong) -> RelayStep): Boolean {
        val step = lock.withLock {
            if (closed) return false
            val step = try {
                conn.block(clock().toULong())
            } catch (e: RelayFfiException) {
                Log.w(TAG, "relay call refused: ${kindOf(e)}")
                return false
            }
            sendAll(step)
            if (step.closeCode != null) markClosed(step.closeCode!!.toInt())
            if (!closed) rearm()
            step
        }
        apply(step)
        return true
    }

    /**
     * A read that stays possible after the core closed the connection, until
     * the native object is freed: the peer key at `Paired` (the step that
     * reports it also closes 1000), the code at `ShowCode`.
     */
    suspend fun <T> peek(block: RelayConn.() -> T?): T? = lock.withLock {
        if (destroyed) null else runCatching { conn.block() }.getOrNull()
    }

    /**
     * [call] for a core call whose error the caller handles (`start`,
     * `enter_code`, `confirm_paired`): a [RelayFfiException] propagates and
     * nothing is sent. Otherwise the Step is sent under the lock and applied,
     * as in [call]. Returns false when the connection had already ended.
     */
    suspend fun callChecked(block: RelayConn.(nowMs: ULong) -> RelayStep): Boolean {
        val step = lock.withLock {
            if (closed) return false
            val step = conn.block(clock().toULong())
            sendAll(step)
            if (step.closeCode != null) markClosed(step.closeCode!!.toInt())
            if (!closed) rearm()
            step
        }
        apply(step)
        return true
    }

    /**
     * Send the Step's output in order (called under the lock). A socket that
     * is going away makes the send fail; the connection still reads what is
     * already in flight from the peer, which may be its authenticated refusal
     * (a hub sends `CLOSE(NOT_PAIRED)` and closes at once), so a failed send
     * doesn't end the connection: the socket's end does.
     */
    private suspend fun sendAll(step: RelayStep) {
        try {
            for (out in step.send) {
                ws.send(if (out.text) WsFrame.Text(true, out.bytes) else WsFrame.Binary(true, out.bytes))
            }
        } catch (e: Exception) {
            Log.i(TAG, "relay send failed: the socket is going away (${e.javaClass.simpleName})")
        }
    }

    private suspend fun apply(step: RelayStep) {
        for (message in step.deliver) listener.onDeliver(message)
        val events = decodeEvents(step.eventsJson)
        if (events.isNotEmpty()) listener.onEvents(events)
        val code = step.closeCode?.toInt()
        if (code != null) closeSocket(code, step.closeReason.orEmpty())
    }

    /**
     * Pump the socket until it ends: text frames go to `on_text` as their raw
     * bytes (the prologue binds the exact bytes, spec §2.4 step 6), binary
     * frames to `on_binary`. A binary message over [WS_MAX_MESSAGE] closes the
     * socket (1009) before the core sees it.
     */
    suspend fun pump() {
        try {
            for (frame in ws.incoming) {
                when (frame) {
                    is WsFrame.Text -> if (call { onText(frame.data, it) }) listener.onInboundHandled()
                    is WsFrame.Binary -> {
                        if (frame.data.size > WS_MAX_MESSAGE) {
                            abort(CloseReason.Codes.TOO_BIG.code.toInt(), "message too big")
                            break
                        }
                        if (call { onBinary(frame.data, it) }) listener.onInboundHandled()
                    }
                    else -> Unit
                }
                if (closed) break
            }
        } finally {
            shutdown()
        }
    }

    /** Arm the deadline timer before the first message (the hub's 5 s to get a ClientHello). */
    suspend fun arm() {
        lock.withLock { if (!closed) rearm() }
    }

    /** End the connection from the shell's side (eviction, teardown): close the socket with [code]. */
    suspend fun abort(code: Int, reason: String) {
        lock.withLock { markClosed(code) }
        closeSocket(code, reason)
    }

    /** The socket ended: stop the timer and free the native object, once. */
    suspend fun shutdown() {
        withContext(NonCancellable) {
            timer?.cancel()
            val first = lock.withLock {
                markClosed(closeCode)
                if (destroyed) false else {
                    destroyed = true
                    runCatching { conn.destroy() }
                    true
                }
            }
            if (first) listener.onClosed(closeCode)
        }
    }

    private fun markClosed(code: Int?) {
        if (!closed) {
            closed = true
            closeCode = code
            timer?.cancel()
        }
    }

    private suspend fun closeSocket(code: Int, reason: String) {
        withContext(NonCancellable) {
            withTimeoutOrNull(CLOSE_TIMEOUT_MS) {
                runCatching { ws.close(CloseReason(code.toShort(), reason.take(MAX_CLOSE_REASON))) }
            }
        }
    }

    /** Called under the lock: one timer for the core's next deadline. */
    private fun rearm() {
        timer?.cancel()
        val deadline = runCatching { conn.nextDeadlineMs() }.getOrNull()?.toLong() ?: return
        timer = scope.launch {
            delay((deadline - clock()).coerceAtLeast(0))
            call { tick(it) }
        }
    }

    companion object {
        private const val TAG = "RelayWire"
        private const val CLOSE_TIMEOUT_MS = 1_000L

        /** A WebSocket close reason is at most 123 bytes; the core's are short ASCII names. */
        private const val MAX_CLOSE_REASON = 120

        private val eventJson = Json { ignoreUnknownKeys = true }

        /** The Step's events; an undecodable list is logged and dropped (never the bytes). */
        fun decodeEvents(json: String): List<RelayEvent> =
            if (json.isEmpty() || json == "[]") emptyList() else runCatching {
                eventJson.decodeFromString(ListSerializer(RelayEvent.serializer()), json)
            }.getOrElse {
                Log.w(TAG, "couldn't decode relay events: ${it.javaClass.simpleName}")
                emptyList()
            }

        /** A [RelayFfiException]'s kind (`InvalidState`, `Timeout`, …), never its detail. */
        fun kindOf(e: RelayFfiException): String = (e as? RelayFfiException.Failed)?.kind ?: e.javaClass.simpleName

        /** The event's variant name, for logs. */
        fun nameOf(event: RelayEvent): String = event.javaClass.simpleName
    }
}

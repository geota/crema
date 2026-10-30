package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.core.RelayCloseReason
import coffee.crema.core.RelayEvent
import coffee.crema.core.RelayFfiException
import coffee.crema.core.RelayKeys
import coffee.crema.core.RelayRemoteConnection
import coffee.crema.net.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Coarse state of a [ReconnectingClientLink], for the mirror's UI: distinguish
 *  "session up" from "dialing" so a dropped primary doesn't read as a
 *  healthy-but-frozen mirror (issue 03). */
enum class LinkState { CONNECTING, CONNECTED }

/**
 * The secondary's [FrameLink] to a primary's [LanRelayServer]: a Ktor WebSocket
 * client that **keeps a live session up across drops**, and runs the relay v4
 * resume on every connection (spec §2.6): a [RelayRemoteConnection.resume] to
 * the hub whose static key this device pinned at pairing ([hubKey]).
 *
 * - [events] is one continuous stream spanning every session. A session counts
 *   only once the core reports it `Established` (the hub proved its pinned key
 *   and the client-finished record went out): only then does
 *   [LinkEvent.SessionStarted] (epoch 1, 2, …) reach the consumer, followed by
 *   that session's frames. Nothing from an unauthenticated connection reaches
 *   the [ProxyTransport].
 * - A refusal from the hub is a [LinkEvent.Refused] with whether it was
 *   authenticated (spec §2.9). `HubStopped`, `Normal`, `Protocol` and `Timeout`
 *   keep redialing with the back-off; anything else stops the link (no
 *   redial) and leaves the decision to the caller: an authenticated
 *   `Revoked`/`NotPaired` drops the pin, an advisory one keeps the role.
 * - [sendOn] sends on one session only, so a frame meant for a session that
 *   has ended is never delivered to the next one.
 * - [state] exposes CONNECTING/CONNECTED for the UI.
 *
 * The socket is Ktor's OkHttp engine ([HttpClients.webSocketClient]). OkHttp
 * can't cap the message size, so the [RelayWire] closes the socket (1009) on a
 * binary message over [WS_MAX_MESSAGE] before the core sees it. The engine is
 * shared and outlives this link, so teardown cancels the live session
 * explicitly.
 */
class ReconnectingClientLink(
    private val url: String,
    scope: CoroutineScope,
    /** This device's relay key. */
    private val keys: RelayKeys,
    /** The hub's static public key, pinned at pairing. */
    private val hubKey: ByteArray,
    private val client: HttpClient = HttpClients.webSocketClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : FrameLink {

    /** One continuous inbound stream across all sessions. */
    private val inbox = Channel<LinkEvent>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(LinkState.CONNECTING)
    /** CONNECTING while dialing/redialing, CONNECTED while an established session is live. */
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** One established session and its epoch. */
    private class Session(val epoch: Long, val wire: RelayWire)

    /** The current established session, or null while (re)connecting. */
    @Volatile
    private var live: Session? = null

    /** The socket being dialed or served, for teardown. */
    @Volatile
    private var socket: WebSocketSession? = null

    /** A refusal stopped the link: nothing redials. */
    @Volatile
    private var stopped = false

    private var epoch = 0L

    private val loop = scope.launch {
        var attempt = 0
        while (isActive && !stopped) {
            try {
                if (runSession()) attempt = 0
            } catch (e: Exception) {
                if (!isActive) break
                Log.i(TAG, "Primary not reachable at $url (attempt ${attempt + 1}): ${e.javaClass.simpleName}")
            } finally {
                live = null
                socket?.cancel()
                socket = null
                if (isActive && !stopped) _state.value = LinkState.CONNECTING
            }
            if (stopped) break
            attempt++
            if (isActive) delay(backoffMs(attempt))
        }
    }

    /** Dial once and run the resume and the session; true when it was established. */
    private suspend fun runSession(): Boolean = coroutineScope {
        val ws = client.webSocketSession { url(this@ReconnectingClientLink.url) }
        socket = ws
        val core = try {
            RelayRemoteConnection.resume(keys, hubKey, clock().toULong())
        } catch (e: RelayFfiException) {
            Log.w(TAG, "Couldn't start a resume: ${RelayWire.kindOf(e)}")
            ws.cancel()
            return@coroutineScope false
        }
        var established = false
        lateinit var wire: RelayWire
        val listener = object : RelayWire.Listener {
            override suspend fun onDeliver(message: ByteArray) {
                val frame = try {
                    FrameCodec.decodeTolerant(message.decodeToString(throwOnInvalidSequence = true))
                } catch (e: Exception) {
                    Log.w(TAG, "Malformed frame from the hub: ${e.javaClass.simpleName} — closing")
                    wire.abort(CLOSE_PROTOCOL, "malformed frame")
                    return
                }
                if (frame != null && live?.wire === wire) inbox.trySend(LinkEvent.Received(frame))
            }

            override suspend fun onEvents(events: List<RelayEvent>) {
                for (event in events) when (event) {
                    is RelayEvent.Established -> {
                        established = true
                        epoch++
                        // The session is live (sendOn works) BEFORE its boundary
                        // reaches the consumer, so a request sent on
                        // SessionStarted always finds it.
                        live = Session(epoch, wire)
                        inbox.trySend(LinkEvent.SessionStarted(epoch))
                        _state.value = LinkState.CONNECTED
                        Log.i(TAG, "Relay session $epoch established with the hub at $url")
                    }
                    is RelayEvent.Refused -> {
                        val reason = event.content.reason
                        val terminal = reason !in REDIAL_REASONS
                        if (terminal) stopped = true
                        Log.i(TAG, "Hub refused the session: $reason (authenticated=${event.content.authenticated}, terminal=$terminal)")
                        inbox.trySend(LinkEvent.Refused(reason, event.content.authenticated, terminal))
                    }
                    is RelayEvent.Failed -> Log.i(TAG, "Relay session failed: ${event.content.error}")
                    else -> Log.d(TAG, "relay event ${RelayWire.nameOf(event)}")
                }
            }
        }
        wire = RelayWire(ws, RemoteConn(core), this, listener, clock)
        wire.applyStep(core.start())
        wire.pump()
        if (established) Log.i(TAG, "Primary link dropped — will redial")
        established
    }

    /** Send on the live session, waiting for one while redialing. A frame sent
     *  during a blip may be lost with the socket. */
    override suspend fun send(frame: Frame) {
        val session = live ?: run { state.first { it == LinkState.CONNECTED }; live }
        session?.let { sendTo(it, frame) }
    }

    override suspend fun sendOn(epoch: Long, frame: Frame): Boolean {
        val session = live?.takeIf { it.epoch == epoch } ?: return false
        return sendTo(session, frame)
    }

    private suspend fun sendTo(session: Session, frame: Frame): Boolean {
        val bytes = FrameCodec.encode(frame).encodeToByteArray()
        return session.wire.call { send(bytes, it) }
    }

    override fun events(): Flow<LinkEvent> = inbox.receiveAsFlow()

    override fun incoming(): Flow<Frame> =
        inbox.receiveAsFlow().filterIsInstance<LinkEvent.Received>().map { it.frame }

    /** Leave: an authenticated `CLOSE(NORMAL)` on the live session, then tear down. */
    override suspend fun close() {
        stopped = true
        live?.wire?.call { closeSession(RelayCloseReason.NORMAL, it) }
        dispose()
    }

    /** Synchronous teardown for non-suspend call sites (e.g. `ViewModel.onCleared`).
     *  Cancels the live socket (the shared engine outlives the client). */
    fun dispose() {
        stopped = true
        loop.cancel()
        socket?.cancel()
        runCatching { client.close() }
        // Close the inbox so a consumer of incoming() (the ProxyTransport's dispatch
        // flow) completes instead of suspending forever on a dead link (issue 10).
        inbox.close()
    }

    private companion object {
        const val TAG = "ReconnectingClientLink"
        const val CLOSE_PROTOCOL = 4400

        /** Refusals that keep the role and redial with the back-off (spec §2.9). */
        val REDIAL_REASONS = setOf("HubStopped", "Normal", "Protocol", "Timeout")

        /** 500 ms doubling, capped at 5 s — tighter than the BLE managers' 30 s, since
         *  a primary restart on the LAN is usually back within a few seconds and a
         *  mirror shouldn't sit frozen waiting out a long backoff (issue 03). */
        fun backoffMs(attempt: Int): Long =
            (500L shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(5_000L)
    }
}

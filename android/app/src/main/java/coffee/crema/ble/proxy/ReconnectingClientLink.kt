package coffee.crema.ble.proxy

import android.util.Log
import coffee.crema.net.HttpClients
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
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
 *  "socket up" from "telemetry flowing" so a dropped primary doesn't read as a
 *  healthy-but-frozen mirror (issue 03). */
enum class LinkState { CONNECTING, CONNECTED }

/**
 * The secondary's [FrameLink] to a primary's [LanRelayServer]: a Ktor WebSocket
 * client that **keeps a live session up across drops** — it dials with backoff at
 * startup *and* redials whenever the session ends, so a secondary survives the
 * primary restarting (issue 03), not just a late-starting primary (M1).
 *
 * Reconnection has two parts and this class owns the **transport** half:
 * - [events] is one continuous stream spanning every session: each new socket
 *   starts with [LinkEvent.SessionStarted] (epoch 1, 2, …), then its frames, so
 *   the [ProxyTransport] collector never ends and knows which session every
 *   frame came from.
 * - [sendOn] sends on one session only, so a frame meant for a session that
 *   has ended is never delivered to the next one.
 * - [state] exposes CONNECTING/CONNECTED for the UI.
 *
 * The **protocol** half (one `Hello` per session, requests after that
 * session's `Welcome`, re-`Attach` → re-snapshot) lives in [ProxyTransport],
 * keyed by epoch. The link knows nothing about frames.
 *
 * The socket is Ktor's OkHttp engine ([HttpClients.webSocketClient]: no retry
 * plugin, no read timeout, a ping every [HttpClients.WS_PING_INTERVAL_MS], so a
 * half-open socket ends the session). OkHttp can't cap the frame size, so each
 * session's [KtorWsFrameLink] enforces [PROXY_MAX_FRAME_BYTES] itself. The
 * engine is shared and outlives this link, so teardown cancels the live
 * session explicitly rather than relying on closing the client.
 */
class ReconnectingClientLink(
    private val url: String,
    scope: CoroutineScope,
    private val client: HttpClient = HttpClients.webSocketClient(),
) : FrameLink {

    /** One continuous inbound stream across all sessions. */
    private val inbox = Channel<LinkEvent>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(LinkState.CONNECTING)
    /** CONNECTING while dialing/redialing, CONNECTED while a session is live. */
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** One live socket and its epoch. */
    private class Session(val epoch: Long, val link: KtorWsFrameLink)

    /** The current live session, or null while (re)connecting. */
    @Volatile
    private var live: Session? = null

    private val loop = scope.launch {
        var attempt = 0
        var epoch = 0L
        while (isActive) {
            try {
                val session = client.webSocketSession { url(this@ReconnectingClientLink.url) }
                val link = KtorWsFrameLink(session, maxFrameBytes = PROXY_MAX_FRAME_BYTES)
                epoch++
                // The session is live (sendOn works) BEFORE its boundary reaches
                // the consumer, so a Hello sent on SessionStarted always finds
                // it, whatever thread the consumer runs on. The boundary still
                // precedes every frame of the session: those are pumped below.
                live = Session(epoch, link)
                inbox.trySend(LinkEvent.SessionStarted(epoch))
                attempt = 0
                _state.value = LinkState.CONNECTED
                Log.i(TAG, "Connected to primary at $url (session $epoch)")
                // Pump this session until the socket ends, then fall through to redial.
                link.incoming().collect { inbox.trySend(LinkEvent.Received(it)) }
                Log.i(TAG, "Primary link dropped — will redial")
            } catch (e: Exception) {
                Log.i(TAG, "Primary not reachable at $url (attempt ${attempt + 1}): ${e.message}")
            } finally {
                // Also on cancellation: the session isn't a child of this loop.
                live?.link?.cancel()
                live = null
                if (isActive) _state.value = LinkState.CONNECTING
            }
            attempt++
            if (isActive) delay(backoffMs(attempt))
        }
    }

    /** Send on the live session, waiting for one while redialing. A frame sent
     *  during a blip may be lost with the socket. */
    override suspend fun send(frame: Frame) {
        val session = live ?: run { state.first { it == LinkState.CONNECTED }; live }
        session?.link?.send(frame)
    }

    override suspend fun sendOn(epoch: Long, frame: Frame): Boolean {
        val session = live?.takeIf { it.epoch == epoch } ?: return false
        return runCatching { session.link.send(frame) }.isSuccess
    }

    override fun events(): Flow<LinkEvent> = inbox.receiveAsFlow()

    override fun incoming(): Flow<Frame> =
        inbox.receiveAsFlow().filterIsInstance<LinkEvent.Received>().map { it.frame }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun close() {
        val session = live?.link
        loop.cancel()
        runCatching { session?.close() }
        session?.cancel()
        client.close()
        inbox.close()
    }

    /** Synchronous teardown for non-suspend call sites (e.g. `ViewModel.onCleared`).
     *  Cancels the live session (the shared engine outlives the client). */
    fun dispose() {
        val session = live?.link
        loop.cancel()
        session?.cancel()
        runCatching { client.close() }
        // Close the inbox so a consumer of incoming() (the ProxyTransport's dispatch
        // flow) completes instead of suspending forever on a dead link (issue 10).
        inbox.close()
    }

    private companion object {
        const val TAG = "ReconnectingClientLink"

        /** 500 ms doubling, capped at 5 s — tighter than the BLE managers' 30 s, since
         *  a primary restart on the LAN is usually back within a few seconds and a
         *  mirror shouldn't sit frozen waiting out a long backoff (issue 03). */
        fun backoffMs(attempt: Int): Long =
            (500L shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(5_000L)
    }
}

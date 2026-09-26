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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
 * - [incoming] is one continuous flow spanning every session (frames from each
 *   live socket are pumped into one channel), so the [ProxyTransport] collector
 *   never ends.
 * - [state] exposes CONNECTING/CONNECTED for the UI; [reconnects] fires once each
 *   time a *new* session goes live after the first.
 *
 * The **protocol** half (re-`Hello` → re-`Attach` → re-snapshot on a reconnect)
 * lives in [ProxyTransport], driven by [reconnects] — the link can't re-establish
 * protocol state alone.
 *
 * The socket is Ktor's OkHttp engine ([HttpClients.webSocketClient]: no retry
 * plugin, no read timeout, no ping). OkHttp can't cap the frame size, so each
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
    private val inbox = Channel<Frame>(Channel.UNLIMITED)

    private val _state = MutableStateFlow(LinkState.CONNECTING)
    /** CONNECTING while dialing/redialing, CONNECTED while a session is live. */
    val state: StateFlow<LinkState> = _state.asStateFlow()

    /** Fires once each time a session goes live **after the first** — the
     *  [ProxyTransport]'s cue to re-run the attach handshake. */
    private val _reconnects = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val reconnects: Flow<Unit> = _reconnects

    /** The current live session, or null while (re)connecting. */
    @Volatile
    private var live: KtorWsFrameLink? = null

    private val loop = scope.launch {
        var attempt = 0
        var first = true
        while (isActive) {
            try {
                val session = client.webSocketSession { url(this@ReconnectingClientLink.url) }
                val link = KtorWsFrameLink(session, maxFrameBytes = PROXY_MAX_FRAME_BYTES)
                live = link
                attempt = 0
                _state.value = LinkState.CONNECTED
                Log.i(TAG, "Connected to primary at $url")
                if (!first) _reconnects.emit(Unit) // re-attach trigger (not the first connect)
                first = false
                // Pump this session until the socket ends, then fall through to redial.
                link.incoming().collect { inbox.trySend(it) }
                Log.i(TAG, "Primary link dropped — will redial")
            } catch (e: Exception) {
                Log.i(TAG, "Primary not reachable at $url (attempt ${attempt + 1}): ${e.message}")
            } finally {
                // Also on cancellation: the session isn't a child of this loop.
                live?.cancel()
                live = null
                if (isActive) _state.value = LinkState.CONNECTING
            }
            attempt++
            if (isActive) delay(backoffMs(attempt))
        }
    }

    override suspend fun send(frame: Frame) {
        // Use the live session; if mid-redial, wait for it. A frame sent during a
        // blip may be dropped — the re-handshake resyncs, so this is best-effort.
        val link = live ?: run { state.first { it == LinkState.CONNECTED }; live }
        link?.send(frame)
    }

    override fun incoming(): Flow<Frame> = inbox.receiveAsFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun close() {
        val session = live
        loop.cancel()
        runCatching { session?.close() }
        session?.cancel()
        client.close()
        inbox.close()
    }

    /** Synchronous teardown for non-suspend call sites (e.g. `ViewModel.onCleared`).
     *  Cancels the live session (the shared engine outlives the client). */
    fun dispose() {
        val session = live
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

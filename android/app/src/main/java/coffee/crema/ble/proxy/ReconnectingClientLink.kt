package coffee.crema.ble.proxy

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The secondary's [FrameLink] to a primary's [LanRelayServer]: a Ktor WebSocket
 * client that **retries until the primary is reachable**, so the secondary can be
 * launched before the primary is up (it keeps dialing with backoff, then
 * connects). Once connected it delegates to a [KtorWsFrameLink] over the live
 * session. A [ProxyTransport] sits on this unchanged.
 *
 * Scope note (M1): this handles the **startup** retry — the common "secondary
 * opened first" case. Full *mid-session* reconnection (redial + re-`Hello` +
 * re-`Attach` + re-snapshot after a drop) is the documented M1 hardening
 * follow-up; it belongs in [ProxyTransport]'s connect loop (it needs protocol
 * re-establishment, which the link can't do alone). For the demo the primary
 * stays up, so the happy path is connect-once-with-startup-retry.
 */
class ReconnectingClientLink(
    private val url: String,
    scope: CoroutineScope,
) : FrameLink {

    private val client = HttpClient(CIO) { install(WebSockets) }

    /** Completes with the live link once the first connection succeeds. */
    private val ready = CompletableDeferred<KtorWsFrameLink>()

    init {
        scope.launch {
            var attempt = 0
            while (isActive && !ready.isCompleted) {
                try {
                    val session = client.webSocketSession { url(this@ReconnectingClientLink.url) }
                    Log.i(TAG, "Connected to primary at $url")
                    ready.complete(KtorWsFrameLink(session))
                } catch (e: Exception) {
                    attempt++
                    val wait = backoffMs(attempt)
                    Log.i(TAG, "Primary not reachable at $url (attempt $attempt) — retrying in ${wait}ms: ${e.message}")
                    delay(wait)
                }
            }
        }
    }

    override suspend fun send(frame: Frame) {
        ready.await().send(frame)
    }

    override fun incoming(): Flow<Frame> = flow {
        emitAll(ready.await().incoming())
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override suspend fun close() {
        runCatching { if (ready.isCompleted) ready.getCompleted().close() }
        client.close()
    }

    /** Synchronous teardown for non-suspend call sites (e.g. `ViewModel.onCleared`).
     *  Closing the Ktor client tears down the live session with it. */
    fun dispose() {
        runCatching { client.close() }
    }

    private companion object {
        const val TAG = "ReconnectingClientLink"

        /** 500 ms doubling, capped at 30 s — the same backoff the BLE managers use. */
        fun backoffMs(attempt: Int): Long =
            (500L shl (attempt - 1).coerceIn(0, 10)).coerceAtMost(30_000L)
    }
}

package coffee.crema.ble.proxy

import coffee.crema.core.RelayCloseReason
import coffee.crema.core.RelayFfiException
import coffee.crema.core.RelayKeys
import coffee.crema.core.RelayOutgoing
import coffee.crema.core.RelayStep
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame as WsFrame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readReason
import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * REVIEW-524 follow-ups 1–2 (PLAN 55–56): closing can't hang on a stuck
 * connection, and a close always ends the socket.
 *
 * - A hub whose write to one remote is stuck (the remote stopped reading, so
 *   the write holds the wire's lock and never returns) still finishes
 *   [SecureRelayAcceptor.closeAll], and [stopHosting] (the mode switch) still
 *   stops the server, within the bound.
 * - A failing core close still closes the socket.
 */
class RelayCloseBoundsTest {

    /**
     * The hub's side of one real loopback socket, whose writes stall once
     * [stall] is set: a stalled write never returns, cancelled or not, until
     * [release] (the test's cleanup). Everything else is the real session.
     */
    private class StallableWs(private val inner: WebSocketSession) : WebSocketSession {
        @Volatile var stall = false
        val stalled = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        override val coroutineContext: CoroutineContext get() = inner.coroutineContext
        override var masking: Boolean
            get() = inner.masking
            set(v) { inner.masking = v }
        override var maxFrameSize: Long
            get() = inner.maxFrameSize
            set(v) { inner.maxFrameSize = v }
        override val incoming: ReceiveChannel<WsFrame> get() = inner.incoming
        override val outgoing: SendChannel<WsFrame> get() = inner.outgoing
        override val extensions: List<WebSocketExtension<*>> get() = inner.extensions

        override suspend fun send(frame: WsFrame) {
            if (!stall) return inner.send(frame)
            stalled.incrementAndGet()
            withContext(NonCancellable) { release.await() }
            throw IOException("released")
        }

        override suspend fun flush() = inner.flush()

        @Suppress("OVERRIDE_DEPRECATION")
        override fun terminate() {}
    }

    /** A hub on the loopback whose one admitted session's writes are stuck. */
    private class StuckHub(closeTimeoutMs: Long) {
        val hubKeys = RelayKeys.generate()
        val remote = RelayKeys.generate()
        val trust = TestHubTrust(hubKeys).apply { pin(remote) }
        val sockets = CopyOnWriteArrayList<StallableWs>()
        val served = CompletableDeferred<FrameLink>()
        val acceptor = SecureRelayAcceptor(
            RelayRig.defaultHub(), trust, closeTimeoutMs = closeTimeoutMs,
            serve = { l, _ -> served.complete(l); awaitCancellation() },
        )
        val server = LanRelayServer(handler = { ws, address ->
            val s = StallableWs(ws)
            sockets += s
            acceptor.accept(s, address)
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var port = 0

        /** Admit one remote, then stall the hub's writes to it with one send in flight (holding the wire's lock). */
        fun start(): StallableWs = runBlocking {
            port = server.start()
            val link = ReconnectingClientLink("ws://127.0.0.1:$port${LanRelayServer.PATH}", scope, remote, hubKeys.publicKey())
            link.events().filterIsInstance<LinkEvent.SessionStarted>().first()
            val hubLink = served.await()
            val ws = sockets.single()
            ws.stall = true
            scope.launch { runCatching { hubLink.send(Frame.Event("never arrives")) } }
            eventually(what = "a stuck send") { ws.stalled.get() == 1 }
            ws
        }

        fun cleanup() {
            sockets.forEach { it.release.complete(Unit) }
            server.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a stuck socket can't hold closeAll past its bound`() {
        val hub = StuckHub(closeTimeoutMs = BOUND_MS)
        try {
            val ws = hub.start()
            val started = System.nanoTime()
            val done = runBlocking { withTimeoutOrNull(HANG_MS) { hub.acceptor.closeAll() } }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertNotNull(done, "closeAll hung behind the stuck connection")
            assertTrue(tookMs in BOUND_MS until BOUND_MS + SLACK_MS, "closeAll waited the bound, then gave up: $tookMs ms")
            assertTrue(ws.coroutineContext.job.isCancelled, "the stuck socket was cancelled without its lock")
        } finally {
            hub.cleanup()
        }
    }

    /** The mode switch's teardown: the server stop runs after the bounded close. */
    @Test
    fun `a stuck socket can't hold the mode switch's server stop`() {
        val hub = StuckHub(closeTimeoutMs = BOUND_MS)
        try {
            hub.start()
            val started = System.nanoTime()
            val done = runBlocking { withTimeoutOrNull(HANG_MS) { stopHosting(hub.acceptor, hub.server) } }
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertNotNull(done, "the mode switch hung behind the stuck connection")
            assertTrue(tookMs < BOUND_MS + SLACK_MS, "stopHosting took $tookMs ms")
            assertFailsWith<ConnectException>("the server stopped listening") { Socket("127.0.0.1", hub.port).close() }
        } finally {
            hub.cleanup()
        }
    }

    /** A connection whose core close throws; every other call is a no-op. */
    private class FailingCloseConn : RelayConn {
        private fun empty() = RelayStep(emptyList<RelayOutgoing>(), emptyList(), "[]", null, null)
        override fun onText(raw: ByteArray, nowMs: ULong) = empty()
        override fun onBinary(bytes: ByteArray, nowMs: ULong) = empty()
        override fun send(message: ByteArray, nowMs: ULong) = empty()
        override fun closeSession(reason: RelayCloseReason, nowMs: ULong): RelayStep =
            throw RelayFfiException.Failed("Poisoned", "injected")
        override fun tick(nowMs: ULong) = empty()
        override fun nextDeadlineMs(): ULong? = null
        override fun peerPublicKey(): ByteArray? = null
        override fun destroy() {}
    }

    /** A socket that records the frames written to it. */
    private class RecordingWs : WebSocketSession {
        val sent = CopyOnWriteArrayList<WsFrame>()
        override val coroutineContext: CoroutineContext = Job()
        override var masking = false
        override var maxFrameSize = Long.MAX_VALUE
        override val incoming: ReceiveChannel<WsFrame> = Channel()
        override val outgoing: SendChannel<WsFrame> = Channel(Channel.UNLIMITED)
        override val extensions: List<WebSocketExtension<*>> = emptyList()

        override suspend fun send(frame: WsFrame) {
            sent += frame
        }

        override suspend fun flush() {}

        @Suppress("OVERRIDE_DEPRECATION")
        override fun terminate() {}

        fun closes(): List<CloseReason?> = sent.filterIsInstance<WsFrame.Close>().map { it.readReason() }
    }

    @Test
    fun `a failing core close still closes the socket`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ws = RecordingWs()
            val wire = RelayWire(ws, FailingCloseConn(), scope, object : RelayWire.Listener {})
            val link = NoiseFrameLink(wire)
            link.revoke()
            assertEquals(listOf<Short?>(1000), ws.closes().map { it?.code }, "the socket got a close")
            assertTrue(wire.closed, "every later call is a no-op")
            assertEquals(emptyList<Frame>(), withTimeoutOrNull(1_000) { link.incoming().toList() }, "incoming completed")
        } finally {
            scope.cancel()
        }
    }

    /** Unchanged wire behaviour: a wire that already ended gets no second close from the link. */
    @Test
    fun `a close on an ended wire sends nothing more`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ws = RecordingWs()
            val wire = RelayWire(ws, FailingCloseConn(), scope, object : RelayWire.Listener {})
            wire.abort(4408, "evicted")
            NoiseFrameLink(wire).closeWith(RelayCloseReason.HUB_STOPPED)
            assertEquals(listOf<Short?>(4408), ws.closes().map { it?.code })
        } finally {
            scope.cancel()
        }
    }

    private companion object {
        const val BOUND_MS = 300L

        /** Generous for a loaded runner; the old code never returned at all. */
        const val SLACK_MS = 3_000L
        const val HANG_MS = 10_000L
    }
}

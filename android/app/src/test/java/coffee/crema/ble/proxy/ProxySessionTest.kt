package coffee.crema.ble.proxy

import coffee.crema.ble.BleTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sessions and the relay's per-session delivery: requests only on a welcomed
 * session, a re-attach after a redial, no redial after a terminal refusal, an
 * ordered snapshot/live stream, and a lag-drop that doesn't trickle out the
 * backlog.
 */
class ProxySessionTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")

    /** A link whose sessions the test starts by hand; records what was sent on which epoch. */
    private class ScriptedSessionLink : FrameLink {
        val events = Channel<LinkEvent>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<Pair<Long, Frame>>()
        @Volatile var liveEpoch = 0L
        @Volatile var closed = 0

        fun start(epoch: Long) { liveEpoch = epoch; events.trySend(LinkEvent.SessionStarted(epoch)) }
        fun drop() { liveEpoch = 0 }
        fun receive(frame: Frame) { events.trySend(LinkEvent.Received(frame)) }

        override fun events(): Flow<LinkEvent> = events.receiveAsFlow()
        override suspend fun sendOn(epoch: Long, frame: Frame): Boolean {
            if (epoch != liveEpoch) return false
            sent += epoch to frame
            return true
        }
        override suspend fun send(frame: Frame) { sendOn(liveEpoch, frame) }
        override fun incoming(): Flow<Frame> = error("the transport reads events()")
        override suspend fun close() { closed++ }

    }

    private fun welcome() = Frame.Welcome("P", "primary", listOf(DeviceInfo("AA:BB:CC:DD:EE:FF", "DE1", "de1", "CONNECTED")), SCOPE_CONTROL)

    /** The hub's side of session [epoch]: relay v4 authenticated it in the core, so it only welcomes. */
    private fun welcomeOn(link: ScriptedSessionLink) = link.receive(welcome())

    private suspend fun waitFor(check: () -> Boolean) = withTimeout(2_000) { while (!check()) delay(5) }

    @Test
    fun `requests only on the welcomed session, re-attach after a redial`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedSessionLink()
            val proxy = ProxyTransport(link, scope, requestTimeoutMs = 10_000)
            val device = object : BleTransport.DeviceHandle {
                override val name: String = "DE1"
                override val address: String = "AA:BB:CC:DD:EE:FF"
            }

            link.start(1)
            welcomeOn(link)
            val attach = scope.async { proxy.connect(device) }
            waitFor { link.sent.any { it.second is Frame.Attach } }
            val attachId = (link.sent.last().second as Frame.Attach).id
            link.receive(Frame.Attached(attachId, device.address, "CONNECTED"))
            attach.await()

            // Session 1 drops; session 2 starts but also drops before its
            // Welcome; session 3 comes up. A control sent meanwhile waits.
            link.drop()
            val control = scope.async { proxy.control("tareScale") }
            link.start(2)
            delay(50)
            link.drop()
            link.start(3)
            delay(100)
            assertTrue(link.sent.none { it.second is Frame.Control }, "nothing before session 3's Welcome")

            welcomeOn(link)
            waitFor { link.sent.any { it.second is Frame.Control } && link.sent.count { it.second is Frame.Attach } == 2 }
            assertEquals(3L, link.sent.first { it.second is Frame.Control }.first, "the control went on the welcomed session")
            assertEquals(3L, link.sent.last { it.second is Frame.Attach }.first, "re-attach on the new session")
            val controlId = (link.sent.first { it.second is Frame.Control }.second as Frame.Control).id
            link.receive(Frame.ControlOk(controlId))
            assertTrue(control.await().isSuccess)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a terminal refusal fails requests at once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedSessionLink()
            val refused = CompletableDeferred<String>()
            val proxy = ProxyTransport(link, scope, onRefused = { reason, _, _ -> refused.complete(reason) })
            link.start(1)
            link.events.trySend(LinkEvent.Refused("NotPaired", authenticated = true, terminal = true))

            assertEquals("NotPaired", refused.await())
            assertTrue(withTimeout(1_000) { proxy.control("tareScale") }.isFailure)
            // A session reported after the refusal is ignored: the refusal sticks.
            link.start(2)
            link.receive(welcome())
            assertTrue(withTimeout(1_000) { proxy.control("tareScale") }.isFailure)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an unpaired remote over a real socket is refused once and doesn't redial`() = runBlocking {
        val hub = RelayHub("P", roster = { emptyList() }, readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false })
        val hubKeys = coffee.crema.core.RelayKeys.generate()
        val trust = TestHubTrust(hubKeys) // pins nobody
        val acceptor = SecureRelayAcceptor(hub, trust)
        val dials = AtomicInteger(0)
        val server = LanRelayServer(handler = { ws, address -> dials.incrementAndGet(); acceptor.accept(ws, address) })
        val port = server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val refusals = CopyOnWriteArrayList<String>()
            val link = ReconnectingClientLink("ws://127.0.0.1:$port${LanRelayServer.PATH}", scope, coffee.crema.core.RelayKeys.generate(), hubKeys.publicKey())
            ProxyTransport(link, scope, onRefused = { reason, auth, terminal -> refusals += "$reason/$auth/$terminal" })
            waitFor { refusals.isNotEmpty() }
            delay(1_500) // three backoff periods
            assertEquals(listOf("NotPaired/true/true"), refusals.toList(), "an authenticated NOT_PAIRED")
            assertEquals(1, dials.get(), "the link stopped: no redial")
        } finally {
            scope.cancel()
            server.stop()
        }
    }

    @Test
    fun `snapshot and live delivery for one characteristic never go backwards`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = RelayHub(
                "P", roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, c -> c == stateChar },
                clientCapacity = Channel.UNLIMITED,
            )
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { hub.serve(server, TEST_PEER) }
            val values = CopyOnWriteArrayList<Int>()
            val rx = CopyOnWriteArrayList<Frame>()
            scope.launch {
                client.incoming().collect { f ->
                    rx += f
                    if (f is Frame.Notify) values += ByteBuffer.wrap(Hex.decode(f.hex)).int
                }
            }
            awaitWelcome(rx)
            client.send(Frame.Attach(1L, addr))
            waitFor { rx.any { it is Frame.Attached } }

            // Live values race repeated re-attaches (snapshot bursts).
            val writer = thread {
                for (i in 1..20_000) hub.onInbound(addr, service, stateChar, ByteBuffer.allocate(4).putInt(i).array(), i.toLong())
            }
            for (n in 2..200L) client.send(Frame.Attach(n, addr))
            writer.join()
            client.send(Frame.Detach(999L, addr))
            waitFor { rx.any { it is Frame.Detached } }

            val backwards = values.zipWithNext().firstOrNull { (a, b) -> b < a }
            assertEquals(null, backwards, "the mirror never steps back to an older value")
            assertEquals(20_000, values.last())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a lag-dropped client gets its link closed without the backlog`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, serverEnd) = InMemoryFrameLink.pair()
            val release = CompletableDeferred<Unit>()
            val stalled = CompletableDeferred<Unit>()
            var stallNext = false
            val server = object : FrameLink {
                override suspend fun send(frame: Frame) {
                    if (stallNext && frame is Frame.Notify) { stallNext = false; stalled.complete(Unit); release.await() }
                    serverEnd.send(frame)
                }
                override fun incoming() = serverEnd.incoming()
                override suspend fun close() = serverEnd.close()
            }
            val hub = RelayHub(
                "P", roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false }, clientCapacity = 50,
            )
            scope.launch { hub.serve(server, TEST_PEER) }
            val rx = CopyOnWriteArrayList<Frame>()
            val ended = CompletableDeferred<Unit>()
            scope.launch { client.incoming().collect { rx += it }; ended.complete(Unit) }
            awaitWelcome(rx)
            client.send(Frame.Attach(1L, addr))
            waitFor { rx.any { it is Frame.Attached } }

            stallNext = true
            hub.onInbound(addr, service, stateChar, byteArrayOf(0), 0L)
            stalled.await()
            repeat(100) { i -> hub.onInbound(addr, service, stateChar, byteArrayOf(i.toByte()), i + 1L) }
            assertTrue(hub.remotes().none { it.clientId == "phone-1" }, "a dropped client is not listed as a remote")
            release.complete(Unit)

            withTimeout(2_000) { ended.await() }
            assertTrue(rx.count { it is Frame.Notify } <= 1, "the queued backlog was not sent: ${rx.count { it is Frame.Notify }}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a terminal refusal wakes a request parked for the next session and fails it at once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedSessionLink()
            val proxy = ProxyTransport(link, scope, requestTimeoutMs = 10_000)
            link.start(1)
            welcomeOn(link)
            delay(100) // welcomed
            link.drop() // the request's send fails: it parks for the next session
            val control = scope.async { proxy.control("tareScale") }
            delay(100)
            link.events.trySend(LinkEvent.Refused("Revoked", authenticated = true, terminal = true))
            val result = withTimeout(1_000) { control.await() }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Revoked"), "${result.exceptionOrNull()}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a request sent on a session that ends fails at once instead of timing out`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedSessionLink()
            val proxy = ProxyTransport(link, scope, requestTimeoutMs = 10_000)
            link.start(1)
            welcomeOn(link)
            val control = scope.async { proxy.control("tareScale") }
            waitFor { link.sent.any { it.second is Frame.Control } }
            link.drop()
            link.start(2) // the reply to session 1 can no longer arrive
            val result = withTimeout(1_000) { control.await() }
            assertTrue(result.isFailure, "failed at once, not after the 10 s timeout")
            assertTrue(link.sent.count { it.second is Frame.Control } == 1, "not resent: a control may already have run")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the first request reaches a real relay on a multi-threaded dispatcher`() = runBlocking {
        val hub = RelayHub(
            "P", roster = { emptyList() }, readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false },
            controlHandler = { _, _, _, _ -> Result.success(Unit) },
        )
        val rig = RelayRig(hub).start()
        val remote = coffee.crema.core.RelayKeys.generate()
        rig.trust.pin(remote)
        try {
            repeat(20) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val link = ReconnectingClientLink(rig.url, scope, remote, rig.hubKeys.publicKey())
                    val proxy = ProxyTransport(link, scope, requestTimeoutMs = 3_000)
                    assertTrue(withTimeout(5_000) { proxy.control("tareScale") }.isSuccess, "connection $it")
                    link.close()
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            rig.stop()
        }
    }

    @Test
    fun `stopping the relay while it starts leaves nothing listening`() = runBlocking {
        val noop: suspend (io.ktor.websocket.WebSocketSession, String) -> Unit = { _, _ -> }
        val stoppedFirst = LanRelayServer(noop)
        stoppedFirst.stop()
        assertTrue(runCatching { stoppedFirst.start() }.isFailure, "a stopped relay doesn't start")
        repeat(20) {
            val port = java.net.ServerSocket(0).use { it.localPort }
            val server = LanRelayServer(noop, requestedPort = port)
            val starting = async(Dispatchers.Default) { runCatching { server.start() } }
            server.stop() // a fast primary -> normal switch
            starting.await()
            val accepted = runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess
            assertTrue(!accepted, "no listener left on :$port (round $it)")
        }
    }

    /** Validation D8: a view-only remote is listed too, marked as such. */
    @Test
    fun `a view-only remote is listed under Remotes connected`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = RelayHub(
                "P", roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false },
            )
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { hub.serve(server, RemotePeer("phone-1", "Phone", canControl = false)) }
            val rx = CopyOnWriteArrayList<Frame>()
            scope.launch { client.incoming().collect { rx += it } }
            awaitWelcome(rx)
            waitFor { hub.remotes().isNotEmpty() }
            assertEquals(listOf(RemoteClient("phone-1", "Phone", canControl = false)), hub.remotes())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the remotes list shows the client's live session, not a closed one`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = RelayHub(
                "P", roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false }, clientCapacity = 4,
            )
            // Session 1: lag-dropped, its pump stuck, so it lingers closed in the list.
            val (c1, s1end) = InMemoryFrameLink.pair()
            val stuck = CompletableDeferred<Unit>()
            var stall = false
            val s1 = object : FrameLink {
                override suspend fun send(frame: Frame) { if (stall) stuck.await(); s1end.send(frame) }
                override fun incoming() = s1end.incoming()
                override suspend fun close() = s1end.close()
            }
            scope.launch { hub.serve(s1, TEST_PEER) }
            val rx1 = CopyOnWriteArrayList<Frame>()
            scope.launch { c1.incoming().collect { rx1 += it } }
            awaitWelcome(rx1)
            c1.send(Frame.Attach(1L, addr))
            waitFor { rx1.any { it is Frame.Attached } }
            stall = true
            repeat(20) { i -> hub.onInbound(addr, service, stateChar, byteArrayOf(i.toByte()), i.toLong()) }

            // Session 2: the same client, redialed and live.
            val (c2, s2) = InMemoryFrameLink.pair()
            scope.launch { hub.serve(s2, TEST_PEER) }
            val rx2 = CopyOnWriteArrayList<Frame>()
            scope.launch { c2.incoming().collect { rx2 += it } }
            awaitWelcome(rx2)

            assertEquals(listOf(RemoteClient("phone-1", "Phone", canControl = true)), hub.remotes())
            stuck.complete(Unit)
        } finally {
            scope.cancel()
        }
    }
}

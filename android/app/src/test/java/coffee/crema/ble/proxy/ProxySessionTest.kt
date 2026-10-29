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
 * One handshake per session, and the relay's per-session delivery: exactly one
 * Hello per session epoch, requests only on a welcomed session, a re-attach
 * after a redial, no redial after a Denied, an ordered snapshot/live stream,
 * and a lag-drop that doesn't trickle out the backlog.
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

        fun hellos(epoch: Long) = sent.count { it.first == epoch && it.second is Frame.Hello }
    }

    private fun welcome() = Frame.Welcome(PROXY_PROTOCOL_VERSION, "p", "P", "primary", listOf(DeviceInfo("AA:BB:CC:DD:EE:FF", "DE1", "de1", "CONNECTED")))

    /** The secret the scripted host issued at the first pairing. */
    private var hostSecret: String? = null

    /** Play a real host's side of session [epoch]'s handshake: challenge (with
     *  a proof once it has paired this device), wait for the Auth, welcome
     *  (issuing a secret at the first pairing). */
    private suspend fun handshakeOn(link: ScriptedSessionLink, epoch: Long) {
        val hello = link.sent.last { it.first == epoch && it.second is Frame.Hello }.second as Frame.Hello
        val hn = PairingCrypto.newNonce()
        val proof = hostSecret?.let { PairingCrypto.hostProof(it, "p", "phone-1", hello.nonce!!, hn) }
        link.receive(Frame.Challenge("p", "P", hn, proof))
        waitFor { link.sent.any { it.first == epoch && it.second is Frame.Auth } }
        val issue = if (hostSecret == null) PairingCrypto.newSecret().also { hostSecret = it } else null
        link.receive(welcome().copy(pairingSecret = issue))
    }

    private suspend fun waitFor(check: () -> Boolean) = withTimeout(2_000) { while (!check()) delay(5) }

    @Test
    fun `one Hello per session, requests only on the welcomed session, re-attach after a redial`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedSessionLink()
            val held = java.util.concurrent.ConcurrentHashMap<String, String>()
            val proxy = ProxyTransport(
                link, scope, "phone-1", "Phone", requestTimeoutMs = 10_000,
                pairingSecretFor = { held[it] }, onPaired = { id, _, secret -> held[id] = secret },
            )
            val device = object : BleTransport.DeviceHandle {
                override val name: String = "DE1"
                override val address: String = "AA:BB:CC:DD:EE:FF"
            }

            link.start(1)
            waitFor { link.hellos(1) == 1 }
            handshakeOn(link, 1)
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
            waitFor { link.hellos(2) == 1 }
            link.drop()
            link.start(3)
            waitFor { link.hellos(3) == 1 }
            delay(100)
            assertTrue(link.sent.none { it.second is Frame.Control }, "nothing before session 3's Welcome")

            handshakeOn(link, 3)
            waitFor { link.sent.any { it.second is Frame.Control } && link.sent.count { it.second is Frame.Attach } == 2 }
            assertEquals(3L, link.sent.first { it.second is Frame.Control }.first, "the control went on the welcomed session")
            assertEquals(3L, link.sent.last { it.second is Frame.Attach }.first, "re-attach on the new session")
            val controlId = (link.sent.first { it.second is Frame.Control }.second as Frame.Control).id
            link.receive(Frame.ControlOk(controlId))
            assertTrue(control.await().isSuccess)
            assertEquals(listOf(1, 1, 1), listOf(1L, 2L, 3L).map { link.hellos(it) }, "exactly one Hello per session")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the user's pick covers one pairing only, a redial to an unverified host is refused`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedSessionLink()
            val held = java.util.concurrent.ConcurrentHashMap<String, String>()
            val denied = CompletableDeferred<String>()
            ProxyTransport(
                link, scope, "phone-1", "Phone",
                onDenied = { denied.complete(it) },
                pairingSecretFor = { held[it] },
                onPaired = { id, _, secret -> held[id] = secret },
                allowRepair = true, // the user picked this host
            )
            // Session 1: a first pairing.
            link.start(1)
            waitFor { link.hellos(1) == 1 }
            link.receive(Frame.Challenge("p", "P", PairingCrypto.newNonce()))
            waitFor { link.sent.any { it.first == 1L && it.second is Frame.Auth } }
            val secret = PairingCrypto.newSecret()
            link.receive(Frame.Welcome(PROXY_PROTOCOL_VERSION, "p", "P", "primary", emptyList(), "control", secret))
            waitFor { held["p"] == secret }

            // Session 2 (an automatic redial): a host this device has no secret
            // for. The user's pick doesn't carry over, so no pairing request.
            link.drop()
            link.start(2)
            waitFor { link.hellos(2) == 1 }
            link.receive(Frame.Challenge("p2", "P", PairingCrypto.newNonce(), hostProof = null))
            link.receive(Frame.Welcome(PROXY_PROTOCOL_VERSION, "p2", "P", "primary", emptyList(), "control", PairingCrypto.newSecret()))

            assertEquals(DenyReason.HOST_UNVERIFIED, withTimeout(2_000) { denied.await() })
            delay(100)
            assertTrue(link.sent.none { it.first == 2L && it.second is Frame.Auth }, "no pairing request on the redial")
            assertEquals(secret, held["p"], "the stored secret is unchanged")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a Denied fails requests at once and closes the link, so it doesn't redial`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedSessionLink()
            val denied = CompletableDeferred<String>()
            val proxy = ProxyTransport(link, scope, "phone-1", "Phone", onDenied = { denied.complete(it) })
            link.start(1)
            waitFor { link.hellos(1) == 1 }
            link.receive(Frame.Denied("not authorized"))

            assertEquals("not authorized", denied.await())
            assertTrue(withTimeout(1_000) { proxy.control("tareScale") }.isFailure)
            waitFor { link.closed == 1 }
            // Even if a session started again, no Hello is sent into the refusal.
            link.start(2)
            delay(100)
            assertEquals(0, link.hellos(2))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a denied secondary over a real socket doesn't redial into another prompt`() = runBlocking {
        val prompts = AtomicInteger(0)
        val hub = RelayHub(
            "p", "P", roster = { emptyList() }, readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false },
            authorize = { prompts.incrementAndGet(); PairingDecision.Denied("not authorized") },
        )
        val server = LanRelayServer(hub)
        val port = server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ReconnectingClientLink("ws://127.0.0.1:$port${LanRelayServer.PATH}", scope)
            ProxyTransport(link, scope, "phone-1", "Phone")
            waitFor { prompts.get() == 1 }
            delay(1_500) // three backoff periods
            assertEquals(1, prompts.get(), "the host was asked once")
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
                "p", "P", roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, c -> c == stateChar },
                clientCapacity = Channel.UNLIMITED,
            )
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { hub.serve(server) }
            val values = CopyOnWriteArrayList<Int>()
            val rx = CopyOnWriteArrayList<Frame>()
            scope.launch {
                client.incoming().collect { f ->
                    rx += f
                    if (f is Frame.Notify) values += ByteBuffer.wrap(Hex.decode(f.hex)).int
                }
            }
            handshake(client, rx)
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
                "p", "P", roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false }, clientCapacity = 50,
            )
            scope.launch { hub.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            val ended = CompletableDeferred<Unit>()
            scope.launch { client.incoming().collect { rx += it }; ended.complete(Unit) }
            handshake(client, rx)
            client.send(Frame.Attach(1L, addr))
            waitFor { rx.any { it is Frame.Attached } }

            stallNext = true
            hub.onInbound(addr, service, stateChar, byteArrayOf(0), 0L)
            stalled.await()
            repeat(100) { i -> hub.onInbound(addr, service, stateChar, byteArrayOf(i.toByte()), i + 1L) }
            assertTrue(hub.handoffTargets().none { it.clientId == "phone-1" }, "a dropped client is no handoff target")
            release.complete(Unit)

            withTimeout(2_000) { ended.await() }
            assertTrue(rx.count { it is Frame.Notify } <= 1, "the queued backlog was not sent: ${rx.count { it is Frame.Notify }}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a Denied wakes a request parked for the next session and fails it at once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedSessionLink()
            val proxy = ProxyTransport(link, scope, "phone-1", "Phone", requestTimeoutMs = 10_000)
            link.start(1)
            waitFor { link.hellos(1) == 1 }
            handshakeOn(link, 1)
            delay(100) // welcomed
            link.drop() // the request's send fails: it parks for the next session
            val control = scope.async { proxy.control("tareScale") }
            delay(100)
            link.receive(Frame.Denied("revoked"))
            val result = withTimeout(1_000) { control.await() }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("revoked"), "${result.exceptionOrNull()}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a request sent on a session that ends fails at once instead of timing out`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedSessionLink()
            val proxy = ProxyTransport(link, scope, "phone-1", "Phone", requestTimeoutMs = 10_000)
            link.start(1)
            waitFor { link.hellos(1) == 1 }
            handshakeOn(link, 1)
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
    fun `the first Hello reaches a real relay on a multi-threaded dispatcher`() = runBlocking {
        val hub = RelayHub(
            "p", "P", roster = { emptyList() }, readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false },
            controlHandler = { _, _, _, _ -> Result.success(Unit) },
        )
        val server = LanRelayServer(hub)
        val port = server.start()
        try {
            repeat(20) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val link = ReconnectingClientLink("ws://127.0.0.1:$port${LanRelayServer.PATH}", scope)
                    val proxy = ProxyTransport(link, scope, "phone-$it", "Phone", requestTimeoutMs = 3_000)
                    assertTrue(withTimeout(5_000) { proxy.control("tareScale") }.isSuccess, "connection $it")
                    link.close()
                } finally {
                    scope.cancel()
                }
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stopping the relay while it starts leaves nothing listening`() = runBlocking {
        val hub = RelayHub("p", "P", roster = { emptyList() }, readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false })
        val stoppedFirst = LanRelayServer(hub)
        stoppedFirst.stop()
        assertTrue(runCatching { stoppedFirst.start() }.isFailure, "a stopped relay doesn't start")
        repeat(20) {
            val port = java.net.ServerSocket(0).use { it.localPort }
            val server = LanRelayServer(hub, requestedPort = port)
            val starting = async(Dispatchers.Default) { runCatching { server.start() } }
            server.stop() // a fast primary -> normal switch
            starting.await()
            val accepted = runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess
            assertTrue(!accepted, "no listener left on :$port (round $it)")
        }
    }

    @Test
    fun `a hand-off offer goes to the client's live session, not a closed one`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val hub = RelayHub(
                "p", "P", roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
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
            scope.launch { hub.serve(s1) }
            val rx1 = CopyOnWriteArrayList<Frame>()
            scope.launch { c1.incoming().collect { rx1 += it } }
            handshake(c1, rx1)
            c1.send(Frame.Attach(1L, addr))
            waitFor { rx1.any { it is Frame.Attached } }
            stall = true
            repeat(20) { i -> hub.onInbound(addr, service, stateChar, byteArrayOf(i.toByte()), i.toLong()) }

            // Session 2: the same client, redialed and live.
            val (c2, s2) = InMemoryFrameLink.pair()
            scope.launch { hub.serve(s2) }
            val rx2 = CopyOnWriteArrayList<Frame>()
            scope.launch { c2.incoming().collect { rx2 += it } }
            handshake(c2, rx2)

            assertTrue(hub.offerHandoff("phone-1"))
            waitFor { rx2.any { it is Frame.HandoffOffer } }
            stuck.complete(Unit)
        } finally {
            scope.cancel()
        }
    }
}

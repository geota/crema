package coffee.crema.ble.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Handshake ordering: no request leaves a secondary before the current
 * session's [Frame.Welcome], and the relay answers every request in every
 * state. The gate test is deterministic: [HelloGate] holds the `Hello` back,
 * which is the race that used to make a control sent right after construction
 * hang until its timeout.
 */
class ProxyHandshakeTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")

    /** Holds [Frame.Hello] until [gate] opens; records every frame in send order
     *  and notes any non-handshake frame that goes out before a Welcome came back. */
    private class HelloGate(private val inner: FrameLink) : FrameLink {
        val gate = CompletableDeferred<Unit>()
        val sent = CopyOnWriteArrayList<Frame>()
        val earlyFrames = CopyOnWriteArrayList<Frame>()
        @Volatile private var welcomed = false

        override suspend fun send(frame: Frame) {
            if (frame is Frame.Hello) gate.await() else if (frame !is Frame.Auth && !welcomed) earlyFrames += frame
            sent += frame
            inner.send(frame)
        }

        override fun incoming(): Flow<Frame> = inner.incoming().onEach { if (it is Frame.Welcome) welcomed = true }
        override suspend fun close() = inner.close()
    }

    private fun hub(
        relayed: MutableList<String>,
        pairingSecretFor: (String) -> String? = { null },
        authorize: suspend (PairingRequest) -> PairingDecision = { PairingDecision.Allowed(true) },
    ) = RelayHub(
        primaryId = "p",
        primaryName = "P",
        roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
        readSource = { _, _, _ -> byteArrayOf(0x01) },
        isSnapshotChar = { _, c -> c == stateChar },
        controlHandler = { method, _, _, _ -> relayed += method; Result.success(Unit) },
        authorize = authorize,
        pairingSecretFor = pairingSecretFor,
    )

    @Test
    fun `a control sent before the Hello lands waits for the Welcome and then succeeds`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val relayed = CopyOnWriteArrayList<String>()
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            scope.launch { hub(relayed).serve(serverEnd) }
            val gated = HelloGate(clientEnd)
            val proxy = ProxyTransport(gated, scope, "phone-1", "Phone", requestTimeoutMs = 60_000)

            val pending = scope.async { proxy.control("machineState", "IDLE") }
            // The request is parked behind the handshake: nothing went out yet.
            delay(100)
            assertTrue(gated.sent.isEmpty(), "no frame may go out before the Hello, was ${gated.sent}")
            assertFalse(pending.isCompleted)

            gated.gate.complete(Unit)
            val result = withTimeout(2_000) { pending.await() }

            assertTrue(result.isSuccess, "the parked control is sent after the Welcome: $result")
            assertEquals(listOf("machineState"), relayed.toList())
            assertTrue(gated.sent.first() is Frame.Hello, "Hello is the session's first frame")
            assertTrue(gated.earlyFrames.isEmpty(), "nothing left before the Welcome: ${gated.earlyFrames}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reads and attaches also wait for the handshake`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            scope.launch { hub(CopyOnWriteArrayList()).serve(serverEnd) }
            val gated = HelloGate(clientEnd)
            val proxy = ProxyTransport(gated, scope, "phone-1", "Phone", requestTimeoutMs = 60_000)
            val device = object : coffee.crema.ble.BleTransport.DeviceHandle {
                override val name: String = "DE1"
                override val address: String = addr
            }

            val read = scope.async { proxy.read(device, service, stateChar) }
            val attach = scope.async { proxy.connect(device) }
            delay(100)
            assertTrue(gated.sent.isEmpty())

            gated.gate.complete(Unit)
            withTimeout(2_000) { attach.await() }
            assertEquals(1, withTimeout(2_000) { read.await() }.single().toInt())
            assertTrue(gated.earlyFrames.isEmpty(), "nothing left before the Welcome: ${gated.earlyFrames}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a denied handshake fails parked requests at once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            scope.launch { hub(CopyOnWriteArrayList()) { PairingDecision.Denied("no") }.serve(serverEnd) }
            val proxy = ProxyTransport(clientEnd, scope, "phone-1", "Phone", requestTimeoutMs = 60_000)

            val result = withTimeout(2_000) { proxy.control("tareScale") }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("denied"), "${result.exceptionOrNull()}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the relay answers every request from a peer without an approved Hello`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val relayed = CopyOnWriteArrayList<String>()
            val reads = AtomicInteger(0)
            val h = RelayHub(
                primaryId = "p",
                primaryName = "P",
                roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
                readSource = { _, _, _ -> reads.incrementAndGet(); byteArrayOf(0x01) },
                isSnapshotChar = { _, _ -> true },
                controlHandler = { method, _, _, _ -> relayed += method; Result.success(Unit) },
            )
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { h.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            scope.launch { client.incoming().collect { rx += it } }

            client.send(Frame.Attach(1L, addr))
            client.send(Frame.Read(2L, addr, service.toString(), stateChar.toString()))
            client.send(Frame.Write(3L, addr, service.toString(), stateChar.toString(), "00"))
            client.send(Frame.Control(4L, "machineState", "IDLE"))
            client.send(Frame.Detach(5L, addr))
            withTimeout(2_000) { while (rx.size < 5) delay(5) }

            assertEquals(Frame.ControlErr(1L, "not-paired"), rx[0])
            assertEquals(Frame.ReadErr(2L, "not-paired"), rx[1])
            assertEquals(Frame.WriteErr(3L, "not-paired"), rx[2])
            assertEquals(Frame.ControlErr(4L, "not-paired"), rx[3])
            assertEquals(Frame.Detached(5L, addr), rx[4])
            assertEquals(0, reads.get(), "nothing is read for an unapproved peer")
            assertTrue(relayed.isEmpty(), "nothing is dispatched for an unapproved peer")
        } finally {
            scope.cancel()
        }
    }

    /**
     * The reconnect case over a real socket: a control tapped while the primary
     * is down is parked, the primary comes back on the same port, and the
     * control lands on the new session after its (slow) Welcome. Before the
     * fix it went out ahead of the re-Hello and was dropped, so it failed at
     * the request timeout although the link was back in about half a second.
     */
    @Test
    fun `a control parked across a primary restart lands after the new session's Welcome`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val port = ServerSocket(0).use { it.localPort }
        val relayed = CopyOnWriteArrayList<String>()
        val hellos = AtomicInteger(0)
        // A real host: it pairs the device on first contact and proves the
        // secret on the redial. The second session's approval is slow, so its
        // Welcome comes well after the socket is back: anything sent on
        // CONNECTED alone would be early.
        val issued = java.util.concurrent.ConcurrentHashMap<String, String>()
        val held = java.util.concurrent.ConcurrentHashMap<String, String>()
        val h = hub(relayed, pairingSecretFor = { issued[it] }) { req ->
            if (hellos.incrementAndGet() > 1) delay(300)
            val known = issued[req.clientId]
            if (known != null && req.proves(known)) {
                PairingDecision.Allowed(true)
            } else {
                PairingDecision.Allowed(true, PairingCrypto.newSecret().also { issued[req.clientId] = it })
            }
        }
        var server = LanRelayServer(h, requestedPort = port)
        server.start()
        try {
            val link = ReconnectingClientLink("ws://127.0.0.1:$port${LanRelayServer.PATH}", scope)
            val proxy = ProxyTransport(
                link, scope, "phone-1", "Phone", requestTimeoutMs = 4_000,
                pairingSecretFor = { held[it] }, onPaired = { id, _, secret -> held[id] = secret },
            )
            assertTrue(withTimeout(5_000) { proxy.control("tareScale") }.isSuccess)

            server.stop()
            withTimeout(5_000) { link.state.first { it == LinkState.CONNECTING } }
            val pending = scope.async { proxy.control("machineState", "IDLE") }
            delay(200)
            server = LanRelayServer(h, requestedPort = port)
            server.start()

            val result = withTimeout(6_000) { pending.await() }
            assertTrue(result.isSuccess, "the parked control lands on the new session: $result")
            assertEquals(listOf("tareScale", "machineState"), relayed.toList())
            assertEquals(2, hellos.get(), "one Hello per session")
            link.close()
        } finally {
            scope.cancel()
            server.stop()
        }
    }
}

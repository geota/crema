package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Session ordering (relay v4): no request leaves a secondary before the
 * current session's [Frame.Welcome] (the core authenticated the session, the
 * Welcome readies it), a terminal refusal fails parked requests at once, and a
 * request parked across a hub restart lands on the next session. The gate test
 * is deterministic: [WelcomeGate] holds the hub's Welcome back on the client.
 */
class ProxyHandshakeTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")

    /** Holds the hub's [Frame.Welcome] until [gate] opens; records every frame
     *  the client sends and notes any that goes out before a Welcome came through. */
    private class WelcomeGate(private val inner: FrameLink) : FrameLink {
        val gate = CompletableDeferred<Unit>()
        val sent = CopyOnWriteArrayList<Frame>()
        val earlyFrames = CopyOnWriteArrayList<Frame>()
        @Volatile private var welcomed = false

        override suspend fun send(frame: Frame) {
            if (!welcomed) earlyFrames += frame
            sent += frame
            inner.send(frame)
        }

        override fun incoming(): Flow<Frame> = inner.incoming().onEach {
            if (it is Frame.Welcome) {
                gate.await()
                welcomed = true
            }
        }

        override suspend fun close() = inner.close()
    }

    private fun hub(relayed: MutableList<String>) = RelayHub(
        primaryName = "P",
        roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
        readSource = { _, _, _ -> byteArrayOf(0x01) },
        isSnapshotChar = { _, c -> c == stateChar },
        controlHandler = { method, _, _, _ -> relayed += method; Result.success(Unit) },
    )

    @Test
    fun `a control sent before the Welcome waits for it and then succeeds`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val relayed = CopyOnWriteArrayList<String>()
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            scope.launch { hub(relayed).serve(serverEnd, TEST_PEER) }
            val gated = WelcomeGate(clientEnd)
            val proxy = ProxyTransport(gated, scope, requestTimeoutMs = 60_000)

            val pending = scope.async { proxy.control("machineState", "IDLE") }
            // The request is parked behind the Welcome: nothing went out yet.
            delay(100)
            assertTrue(gated.sent.isEmpty(), "no frame may go out before the Welcome, was ${gated.sent}")
            assertFalse(pending.isCompleted)

            gated.gate.complete(Unit)
            val result = withTimeout(2_000) { pending.await() }

            assertTrue(result.isSuccess, "the parked control is sent after the Welcome: $result")
            assertEquals(listOf("machineState"), relayed.toList())
            assertTrue(gated.earlyFrames.isEmpty(), "nothing left before the Welcome: ${gated.earlyFrames}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reads and attaches also wait for the Welcome`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (clientEnd, serverEnd) = InMemoryFrameLink.pair()
            scope.launch { hub(CopyOnWriteArrayList()).serve(serverEnd, TEST_PEER) }
            val gated = WelcomeGate(clientEnd)
            val proxy = ProxyTransport(gated, scope, requestTimeoutMs = 60_000)
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

    /** A link that delivers only what the test pushes into [events]. */
    private class ScriptedLink : FrameLink {
        val events = Channel<LinkEvent>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<Frame>()
        override suspend fun send(frame: Frame) { sent += frame }
        override fun incoming(): Flow<Frame> = error("events() only")
        override fun events(): Flow<LinkEvent> = events.receiveAsFlow()
        override suspend fun close() { events.close() }
    }

    @Test
    fun `a terminal refusal fails parked requests at once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedLink()
            val refusals = CopyOnWriteArrayList<String>()
            val proxy = ProxyTransport(link, scope, requestTimeoutMs = 60_000, onRefused = { reason, auth, terminal -> refusals += "$reason/$auth/$terminal" })
            val pending = scope.async { proxy.control("tareScale") }
            delay(50)
            link.events.send(LinkEvent.Refused("Revoked", authenticated = true, terminal = true))
            val result = withTimeout(2_000) { pending.await() }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Revoked"), "${result.exceptionOrNull()}")
            assertEquals(listOf("Revoked/true/true"), refusals.toList())
            assertTrue(link.sent.isEmpty(), "nothing went out")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a non-terminal refusal keeps a parked request for the next session`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = ScriptedLink()
            val proxy = ProxyTransport(link, scope, requestTimeoutMs = 60_000)
            val pending = scope.async { proxy.control("tareScale") }
            delay(50)
            link.events.send(LinkEvent.Refused("HubStopped", authenticated = true, terminal = false))
            delay(50)
            assertFalse(pending.isCompleted, "the link redials: the request waits")
            link.events.send(LinkEvent.SessionStarted(1))
            link.events.send(LinkEvent.Received(Frame.Welcome("P", "primary", emptyList(), SCOPE_CONTROL)))
            withTimeout(2_000) { while (link.sent.isEmpty()) delay(5) }
            val control = link.sent.single() as Frame.Control
            link.events.send(LinkEvent.Received(Frame.ControlOk(control.id)))
            assertTrue(withTimeout(2_000) { pending.await() }.isSuccess)
        } finally {
            scope.cancel()
        }
    }

    /**
     * The reconnect case over a real socket: a control tapped while the hub is
     * down is parked, the hub comes back on the same port with the same key,
     * and the control lands on the new session after its Welcome.
     */
    @Test
    fun `a control parked across a hub restart lands after the new session's Welcome`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val relayed = CopyOnWriteArrayList<String>()
        val h = hub(relayed)
        val hubKeys = RelayKeys.generate()
        val remote = RelayKeys.generate()
        var rig = RelayRig(h, hubKeys).also { it.trust.pin(remote) }.start()
        val port = rig.port
        try {
            // The link outlives the hub's rigs: it runs in the test's scope.
            val link = ReconnectingClientLink(rig.url, scope, remote, hubKeys.publicKey())
            val proxy = ProxyTransport(link, scope, requestTimeoutMs = 4_000)
            assertTrue(withTimeout(5_000) { proxy.control("tareScale") }.isSuccess)

            rig.stop()
            withTimeout(5_000) { link.state.first { it == LinkState.CONNECTING } }
            val pending = scope.async { proxy.control("machineState", "IDLE") }
            delay(200)
            // The hub comes back on the port it had (retry the bind briefly
            // while the old listener's socket is being released).
            rig = restartOn(h, hubKeys, remote, port)

            val result = withTimeout(6_000) { pending.await() }
            assertTrue(result.isSuccess, "the parked control lands on the new session: $result")
            assertEquals(listOf("tareScale", "machineState"), relayed.toList())
            link.close()
        } finally {
            scope.cancel()
            rig.stop()
        }
    }

    /** A new rig for [hub] with [hubKeys] (and [remote] pinned) on [port], retrying the bind for up to two seconds. */
    private suspend fun restartOn(hub: RelayHub, hubKeys: RelayKeys, remote: RelayKeys, port: Int): RelayRig {
        var lastError: Throwable? = null
        repeat(40) {
            val next = runCatching { RelayRig(hub, hubKeys, requestedPort = port).also { it.trust.pin(remote) }.start() }
            val rig = next.getOrNull()
            if (rig != null && rig.port == port) return rig
            rig?.stop()
            lastError = next.exceptionOrNull()
            delay(50)
        }
        throw AssertionError("Couldn't bind :$port again", lastError)
    }
}

package coffee.crema.ble.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the proxy hardening pass:
 *
 * - a lag-dropped client's LINK is closed (not just its outbox), so the peer's
 *   `incoming()` completes and its reconnect machinery resyncs;
 * - the attach snapshot burst never regresses a characteristic to a value older
 *   than one already delivered live (per-client monotonic seq guard), and a
 *   re-attach still re-delivers the current snapshot;
 * - a duplicate `Hello` on an approved session does not re-run TOFU;
 * - an `Attach` for a device the hub has no state for reports DISCONNECTED,
 *   not an assumed CONNECTED;
 * - a granted handoff's `ControlOk` reaches the taker, and the release waits
 *   for the taker's link to close (ack-by-disconnect);
 * - the observe tripwire counts concurrent collectors per characteristic.
 */
class ProxyHardeningTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")

    private fun hub(
        capacity: Int = kotlinx.coroutines.channels.Channel.UNLIMITED,
        authorize: suspend (String, String) -> PairingDecision = { _, _ -> PairingDecision.Allowed(true) },
    ) = RelayHub(
        primaryId = "p",
        primaryName = "P",
        roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
        readSource = { _, _, _ -> error("unused") },
        isSnapshotChar = { _, c -> c == stateChar },
        clientCapacity = capacity,
        authorize = authorize,
    )

    /** A server-side link whose sends can be held, so a test can make the
     *  outbox fill deterministically instead of racing the pump. */
    private class StallableLink(private val inner: FrameLink) : FrameLink {
        @Volatile var stall: CompletableDeferred<Unit>? = null
        override suspend fun send(frame: Frame) { stall?.await(); inner.send(frame) }
        override fun incoming() = inner.incoming()
        override suspend fun close() = inner.close()
    }

    private suspend fun handshakeAndAttach(client: FrameLink, rx: MutableList<Frame>) {
        client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "phone-1", "Phone"))
        withTimeout(2_000) { while (rx.none { it is Frame.Welcome }) delay(10) }
        client.send(Frame.Attach(1L, addr))
        withTimeout(2_000) { while (rx.none { it is Frame.Attached }) delay(10) }
    }

    @Test
    fun `overflow-dropped client sees its link close and stream end`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, serverEnd) = InMemoryFrameLink.pair()
            val server = StallableLink(serverEnd)
            val h = hub(capacity = 2) // tiny outbox: easy to overflow
            scope.launch { h.serve(server) }

            val rx = CopyOnWriteArrayList<Frame>()
            val ended = CompletableDeferred<Unit>()
            scope.launch {
                client.incoming().collect { rx.add(it) }
                ended.complete(Unit) // flow COMPLETED — the server closed the link
            }
            handshakeAndAttach(client, rx)

            // The peer stops reading (the pump's send is held), then a flood far
            // past the capacity makes deliver() drop the client.
            val stall = CompletableDeferred<Unit>().also { server.stall = it }
            repeat(50) { i -> h.onInbound(addr, service, stateChar, byteArrayOf(i.toByte()), atMs = i.toLong()) }
            stall.complete(Unit)

            // The client must OBSERVE the drop: its incoming flow completes.
            withTimeout(3_000) { ended.await() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `snapshot replay never regresses past a newer live value and re-attach re-delivers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            val h = hub()
            scope.launch { h.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            scope.launch { client.incoming().collect { rx.add(it) } }

            // v1 cached before attach → the attach snapshot delivers it.
            h.onInbound(addr, service, stateChar, byteArrayOf(0x01), atMs = 1L)
            handshakeAndAttach(client, rx)
            withTimeout(2_000) { while (rx.none { it is Frame.Notify }) delay(10) }

            // v2 lands live.
            h.onInbound(addr, service, stateChar, byteArrayOf(0x02), atMs = 2L)
            withTimeout(2_000) { while (rx.count { it is Frame.Notify } < 2) delay(10) }

            // Re-attach (same session): the guard must not regress to v1, but the
            // re-attach must still re-deliver the CURRENT snapshot (v2) for a
            // freshly-converging core.
            client.send(Frame.Attach(2L, addr))
            withTimeout(2_000) { while (rx.count { it is Frame.Attached } < 2) delay(10) }
            withTimeout(2_000) { while (rx.count { it is Frame.Notify } < 3) delay(10) }

            val values = rx.filterIsInstance<Frame.Notify>().map { Hex.decode(it.hex)[0].toInt() }
            assertEquals(listOf(1, 2, 2), values, "v1 snapshot, v2 live, v2 re-snapshot — never v1 after v2")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `duplicate Hello does not re-run authorization`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            val authCalls = AtomicInteger(0)
            val h = hub { _, _ -> authCalls.incrementAndGet(); PairingDecision.Allowed(true) }
            scope.launch { h.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            scope.launch { client.incoming().collect { rx.add(it) } }

            client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "phone-1", "Phone"))
            withTimeout(2_000) { while (rx.none { it is Frame.Welcome }) delay(10) }
            client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "phone-1", "Phone"))
            delay(200)

            assertEquals(1, authCalls.get(), "an approved session's duplicate Hello is ignored")
            assertEquals(1, rx.count { it is Frame.Welcome }, "no second Welcome")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `attach for an unknown device reports DISCONNECTED not assumed CONNECTED`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            val h = hub()
            scope.launch { h.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            scope.launch { client.incoming().collect { rx.add(it) } }

            client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "phone-1", "Phone"))
            withTimeout(2_000) { while (rx.none { it is Frame.Welcome }) delay(10) }
            // No onConnState was ever fed for this address.
            client.send(Frame.Attach(1L, "11:22:33:44:55:66"))
            withTimeout(2_000) { while (rx.none { it is Frame.Attached }) delay(10) }

            val attached = rx.filterIsInstance<Frame.Attached>().first()
            assertTrue(attached.state == "DISCONNECTED", "unknown state must be honest, was ${attached.state}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `handoff grant reaches the taker and the release waits for its disconnect`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            lateinit var h: RelayHub
            val released = CompletableDeferred<Boolean>()
            h = RelayHub(
                primaryId = "p",
                primaryName = "P",
                roster = { emptyList() },
                readSource = { _, _, _ -> error("unused") },
                isSnapshotChar = { _, _ -> false },
                // What ProxyController's grant does: answer at once, release later.
                controlHandler = { _, _, originId, _ ->
                    scope.launch { released.complete(h.awaitClientGone(originId!!, 3_000)) }
                    Result.success(Unit)
                },
            )
            scope.launch { h.serve(server) }
            val proxy = ProxyTransport(client, scope, "phone-1", "Phone")

            assertTrue(withTimeout(2_000) { proxy.control("handoff") }.isSuccess, "the grant reaches the taker")
            delay(200)
            assertTrue(!released.isCompleted, "no release while the taker is still connected")

            client.close() // the taker's mode switch closes its link
            assertTrue(withTimeout(2_000) { released.await() }, "released on the disconnect, not the timeout")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `observe tripwire counts concurrent collectors`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { hub().serve(server) }
            val proxy = ProxyTransport(client, scope, "phone-1", "Phone")
            val device = object : coffee.crema.ble.BleTransport.DeviceHandle {
                override val name: String = "DE1"
                override val address: String = addr
            }
            val a = scope.launch { proxy.observe(device, service, stateChar).collect {} }
            val b = scope.launch { proxy.observe(device, service, stateChar).collect {} }
            withTimeout(2_000) { while (proxy.observerCount(addr, stateChar) < 2) delay(5) }
            a.cancel(); b.cancel()
            withTimeout(2_000) { while (proxy.observerCount(addr, stateChar) > 0) delay(5) }
            assertEquals(0, proxy.observerCount(addr, stateChar))
        } finally {
            scope.cancel()
        }
    }
}

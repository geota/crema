package coffee.crema.ble.proxy

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pre-pairing gate (issue 03): an unapproved peer — one that skips `Hello`, or whose
 * `Hello` is DENIED — must get nothing: no attach/snapshot, no read, and no
 * roster/config/event/notify fan-out. Before the fix only `handleControl` checked
 * approval; `Attach`/`Read`/`onInbound` and the push-fan-out methods served any socket.
 */
class RelayHubPairingGateTest {
    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")

    private fun hub(authorize: suspend (String, String) -> PairingDecision) = RelayHub(
        primaryId = "p", primaryName = "P",
        roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
        readSource = { _, _, _ -> error("read must not be served to an unapproved peer") },
        isSnapshotChar = { _, _ -> true },
        configSource = { "{\"x\":1}" },
        authorize = authorize,
    )

    @Test
    fun `peer that skips Hello gets nothing`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            val h = hub { _, _ -> PairingDecision.Allowed(canControl = true) }
            // A cached snapshot value an attach WOULD burst, were it allowed.
            h.onInbound(addr, service, stateChar, byteArrayOf(0x01), atMs = 1L)
            scope.launch { h.serve(server) }

            val rx = CopyOnWriteArrayList<Frame>()
            val obs = scope.launch { client.incoming().collect { rx.add(it) } }

            // No Hello — straight to Attach + Read.
            client.send(Frame.Attach(1L, addr))
            client.send(Frame.Read(2L, addr, service.toString(), stateChar.toString()))
            delay(150)
            // Every push fan-out — none must reach an unapproved peer.
            h.pushRoster(); h.pushConfig(); h.broadcastEvent("someone acted", exceptClientId = null)
            h.onInbound(addr, service, stateChar, byteArrayOf(0x02), atMs = 2L)
            delay(150)

            assertTrue(rx.none { it is Frame.Attached }, "no attach without an approved Hello")
            assertTrue(rx.none { it is Frame.ReadOk || it is Frame.ReadErr }, "no read served or attempted")
            assertTrue(rx.none { it is Frame.Notify }, "no snapshot / stream")
            assertTrue(rx.none { it is Frame.Roster || it is Frame.Config || it is Frame.Event }, "no push fan-out")
            obs.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `denied peer gets only Denied, never welcome or stream`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            val h = hub { _, _ -> PairingDecision.Denied("not on the allowlist") }
            scope.launch { h.serve(server) }

            val rx = CopyOnWriteArrayList<Frame>()
            val obs = scope.launch { client.incoming().collect { rx.add(it) } }

            client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "phone-1", "Phone"))
            delay(150)
            h.pushRoster(); h.pushConfig(); h.broadcastEvent("x", exceptClientId = null)
            h.onInbound(addr, service, stateChar, byteArrayOf(0x01), atMs = 1L)
            delay(150)

            assertTrue(rx.any { it is Frame.Denied }, "denied peer is told so")
            assertTrue(rx.none { it is Frame.Welcome }, "no welcome")
            assertTrue(
                rx.none { it is Frame.Roster || it is Frame.Config || it is Frame.Event || it is Frame.Notify },
                "no fan-out to a denied peer",
            )
            obs.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `approved peer attaches, streams, and receives fan-out`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            val h = hub { _, _ -> PairingDecision.Allowed(canControl = true) }
            scope.launch { h.serve(server) }

            val rx = CopyOnWriteArrayList<Frame>()
            val obs = scope.launch { client.incoming().collect { rx.add(it) } }

            client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "phone-1", "Phone"))
            delay(100)
            client.send(Frame.Attach(1L, addr))
            delay(150)
            assertTrue(rx.any { it is Frame.Welcome }, "approved peer is welcomed")
            assertTrue(rx.any { it is Frame.Attached }, "and can attach")

            rx.clear()
            h.pushRoster()
            delay(100)
            assertTrue(rx.any { it is Frame.Roster }, "approved peer receives push fan-out")
            obs.cancel()
        } finally {
            scope.cancel()
        }
    }
}

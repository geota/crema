package coffee.crema.ble.proxy

import coffee.crema.core.RelayKeys
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-33 and AC-55, the secure acceptor's limits (spec §2.5, §4.3): the server
 * refuses a WebSocket message over 65 536 bytes, including one split into
 * continuation frames, before any authentication; a ClientHello over 1 KiB is
 * refused; at most 4 unauthenticated sessions (2 per address) exist at once,
 * and a full cap evicts the oldest unauthenticated session (4408) rather than
 * refusing, so a real resume gets through a flood; a session waiting for code
 * entry is never evicted; a session silent for 5 s before its ClientHello is
 * closed; and after the hub user cancels a pairing request, that address gets
 * BUSY for `intent=pair` until the window ends.
 */
class SecureAcceptorLimitsTest {

    private fun withRig(maxUnauthPerAddress: Int = SecureRelayAcceptor.MAX_UNAUTH_PER_ADDRESS, block: (RelayRig) -> Unit) {
        val rig = RelayRig(maxUnauthPerAddress = maxUnauthPerAddress).start()
        try {
            block(rig)
        } finally {
            rig.stop()
        }
    }

    /** The server closed the connection for the size: a 1009 close, or (Linux) a reset,
     *  when it closes with the rest of the message still unread. Never a timeout. */
    private fun assertRefusedForSize(code: Int?) =
        assertTrue(code == 1009 || code == null, "closed for the size (1009 or a reset), was $code")

    @Test
    fun `a message over 64 KiB is refused before authentication`() = withRig { rig ->
        RawWsClient(rig.port).use { ws ->
            ws.frame(2, true, ByteArray(70_000))
            assertRefusedForSize(ws.awaitClose())
            assertEquals(0, rig.trust.admitted.size)
        }
    }

    @Test
    fun `a message over 64 KiB split into continuation frames is refused too`() = withRig { rig ->
        RawWsClient(rig.port).use { ws ->
            ws.frame(2, false, ByteArray(40_000))
            ws.frame(0, true, ByteArray(40_000))
            assertRefusedForSize(ws.awaitClose())
            assertEquals(0, rig.trust.admitted.size)
        }
    }

    /** The server's WebSocket cap acts on the frame header: a frame that
     *  declares 8 MiB is refused before its payload, never buffered. */
    @Test
    fun `a frame declaring more than 64 KiB is refused before its payload arrives`() = withRig { rig ->
        RawWsClient(rig.port).use { ws ->
            // Only the header: nothing is left unread on the server, so its
            // close frame always arrives (no reset). Without the cap the server
            // would wait for the payload until the 5 s hello deadline (4408).
            ws.partialFrame(declaredLength = 8L shl 20, sent = 0)
            assertEquals(1009, ws.awaitClose(timeoutMs = 15_000), "refused for its size, not timed out")
        }
    }

    @Test
    fun `a ClientHello over 1 KiB is refused`() = withRig { rig ->
        RawWsClient(rig.port).use { ws ->
            val pad = "x".repeat(2_000)
            ws.text("""{"type":"hello","v":4,"vmin":4,"vmax":4,"role":"secondary","clientId":"","clientName":"","intent":"resume","pad":"$pad"}""")
            val code = ws.awaitClose()
            assertEquals(4400, code)
            assertTrue(ws.binaries.none { it[0] == 0x10.toByte() }, "no ServerHello for an oversize hello")
        }
    }

    @Test
    fun `a session silent for 5 s before its ClientHello is closed`() = withRig { rig ->
        RawWsClient(rig.port).use { ws ->
            val from = System.nanoTime()
            assertEquals(4408, ws.awaitClose(timeoutMs = 10_000))
            val secs = (System.nanoTime() - from) / 1e9
            assertTrue(secs in 4.0..7.0, "after ~5 s, was $secs")
        }
    }

    @Test
    fun `at most 2 unauthenticated sessions per address, the oldest evicted`() = withRig { rig ->
        val first = RawWsClient(rig.port)
        runBlocking { eventually(what = "the first session held") { rig.acceptor.sessionCount == 1 } }
        val second = RawWsClient(rig.port)
        runBlocking { eventually(what = "both sessions held") { rig.acceptor.sessionCount == 2 } }
        RawWsClient(rig.port).use {
            assertEquals(4408, first.awaitClose(timeoutMs = 4_000), "the oldest is evicted")
        }
        first.close()
        second.close()
    }

    @Test
    fun `at most 4 unauthenticated sessions, the oldest evicted`() = withRig(maxUnauthPerAddress = 10) { rig ->
        val flood = (1..4).map { n ->
            RawWsClient(rig.port).also { runBlocking { eventually(what = "$n sessions held") { rig.acceptor.sessionCount == n } } }
        }
        RawWsClient(rig.port).use {
            assertEquals(4408, flood.first().awaitClose(timeoutMs = 4_000), "the oldest is evicted")
        }
        flood.forEach { it.close() }
    }

    @Test
    fun `a real resume gets through a sustained flood`() = withRig(maxUnauthPerAddress = 10) { rig ->
        val keys = RelayKeys.generate()
        rig.trust.pin(keys)
        val flood = (1..4).map { RawWsClient(rig.port) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val welcomed = AtomicBoolean(false)
            val link = ReconnectingClientLink(rig.url, scope, keys, rig.hubKeys.publicKey())
            ProxyTransport(link, scope, onWelcome = { _, _ -> welcomed.set(true) })
            runBlocking { eventually(what = "the resume through the flood") { welcomed.get() } }
        } finally {
            scope.cancel()
            flood.forEach { it.close() }
        }
    }

    @Test
    fun `a session waiting for code entry is never evicted`() = withRig(maxUnauthPerAddress = 10) { rig ->
        rig.trust.gate.open(System.currentTimeMillis().toULong())
        val dialer = PairingDialer(rig.url, "Kitchen tablet", RelayKeys.generate(), "Phone", rig.scope, storePin = { _, _, _ -> true })
        dialer.start()
        runBlocking { eventually(what = "the code request") { rig.trust.requests.isNotEmpty() && dialer.state.value is PairingState.ShowCode } }
        val shown = dialer.state.value as PairingState.ShowCode
        val flood = (1..6).map { RawWsClient(rig.port).also { Thread.sleep(50) } }
        Thread.sleep(300)
        // The same connection, not a retry after an eviction: the same code and still one request.
        assertEquals(shown.code, (dialer.state.value as? PairingState.ShowCode)?.code, "the pairing session is still waiting")
        assertEquals(1, rig.trust.requests.size, "no second request: ${rig.trust.requests.size}")
        // And it still pairs.
        val code = shown.code
        runBlocking {
            assertTrue(rig.trust.requests.last().enterCode(code, canControl = true))
            eventually(what = "paired") { dialer.state.value is PairingState.Paired }
        }
        flood.forEach { it.close() }
    }

    @Test
    fun `after the hub cancels a request, that address gets BUSY until the window ends`() = withRig { rig ->
        rig.trust.gate.open(System.currentTimeMillis().toULong())
        val first = PairingDialer(rig.url, "Kitchen tablet", RelayKeys.generate(), "Squatter", rig.scope, storePin = { _, _, _ -> true })
        first.start()
        runBlocking {
            eventually(what = "the request") { rig.trust.requests.isNotEmpty() }
            val request = rig.trust.requests.last()
            // What the controller does on Cancel: abort, and turn the address away.
            rig.trust.turnedAway += request.address
            request.cancel()
            eventually(what = "the squatter refused") { first.state.value is PairingState.Refused }
        }
        val second = PairingDialer(rig.url, "Kitchen tablet", RelayKeys.generate(), "Squatter again", rig.scope, storePin = { _, _, _ -> true })
        second.start()
        runBlocking { eventually(what = "the second outcome") { second.state.value is PairingState.Refused } }
        val reason = (second.state.value as PairingState.Refused).reason
        assertTrue(reason.equals("Busy", ignoreCase = true), "BUSY for the rest of the window: $reason")
        assertEquals(1, rig.trust.requests.size, "no new code dialog")
        val status = rig.trust.gate.status(System.currentTimeMillis().toULong())
        assertTrue("\"failures\":0" in status && "\"open\":true" in status, "the window is untouched: $status")
    }
}

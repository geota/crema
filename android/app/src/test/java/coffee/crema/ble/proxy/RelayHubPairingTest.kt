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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pairing handshake through [RelayHub] and [ProxyTransport]: Hello →
 * Challenge → Auth → Welcome/Denied, the secret issued once and proven after,
 * and the session limits around it (version check, handshake deadline,
 * session cap, revoke).
 */
class RelayHubPairingTest {

    private val addr = "AA:BB:CC:DD:EE:FF"
    private val service = UUID.fromString("0000a000-0000-1000-8000-00805f9b34fb")
    private val stateChar = UUID.fromString("0000a00e-0000-1000-8000-00805f9b34fb")

    private fun hub(
        authorize: suspend (PairingRequest) -> PairingDecision,
        maxSessions: Int = 8,
        handshakeDeadlineMs: Long = 5_000,
        onOutdatedPeer: (String) -> Unit = {},
        pairingSecretFor: (String) -> String? = { null },
    ) = RelayHub(
        primaryId = "host-1",
        primaryName = "Kitchen tablet",
        roster = { listOf(DeviceInfo(addr, "DE1", "de1", "CONNECTED")) },
        readSource = { _, _, _ -> error("unused") },
        isSnapshotChar = { _, _ -> true },
        controlHandler = { _, _, _, _ -> Result.success(Unit) },
        authorize = authorize,
        onOutdatedPeer = onOutdatedPeer,
        maxSessions = maxSessions,
        handshakeDeadlineMs = handshakeDeadlineMs,
        pairingSecretFor = pairingSecretFor,
    )

    /** A host like ProxyController's gate: a valid proof is admitted; anything
     *  else is paired on the spot with a new secret (as if the window were open
     *  and the user allowed it). */
    private class PairingHost {
        val issued = ConcurrentHashMap<String, String>()
        val decisions = CopyOnWriteArrayList<PairingDecision>()
        suspend fun authorize(request: PairingRequest): PairingDecision {
            val known = issued[request.clientId]
            val decision = if (known != null && request.proves(known)) {
                PairingDecision.Allowed(canControl = true)
            } else {
                val secret = PairingCrypto.newSecret().also { issued[request.clientId] = it }
                PairingDecision.Allowed(canControl = true, issuedSecret = secret)
            }
            decisions += decision
            return decision
        }
    }

    @Test
    fun `the first connect pairs and keeps the secret, the next one proves it`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val host = PairingHost()
            val h = hub({ host.authorize(it) }, pairingSecretFor = { host.issued[it] })
            val held = ConcurrentHashMap<String, String>()

            val (c1, s1) = InMemoryFrameLink.pair()
            scope.launch { h.serve(s1) }
            val first = ProxyTransport(
                c1, scope, "phone-1", "Phone",
                pairingSecretFor = { held[it] }, onPaired = { id, _, secret -> held[id] = secret },
            )
            assertTrue(withTimeout(2_000) { first.control("tareScale") }.isSuccess)
            assertEquals(host.issued["phone-1"], held["host-1"], "the issued secret is kept under the host id")
            c1.close()

            val (c2, s2) = InMemoryFrameLink.pair()
            scope.launch { h.serve(s2) }
            val second = ProxyTransport(c2, scope, "phone-1", "Phone", pairingSecretFor = { held[it] })
            assertTrue(withTimeout(2_000) { second.control("tareScale") }.isSuccess)
            assertEquals(2, host.decisions.size)
            assertNull((host.decisions[1] as PairingDecision.Allowed).issuedSecret, "proved, not paired again")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a wrong secret is refused`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val real = PairingCrypto.newSecret()
            val h = hub({ req ->
                if (req.proves(real)) PairingDecision.Allowed(true) else PairingDecision.Denied(DenyReason.PAIRING_CLOSED)
            })
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { h.serve(server) }
            val denied = CompletableDeferred<String>()
            val proxy = ProxyTransport(
                client, scope, "phone-1", "Phone",
                onDenied = { denied.complete(it) }, pairingSecretFor = { PairingCrypto.newSecret() },
            )
            assertTrue(withTimeout(2_000) { proxy.control("tareScale") }.isFailure)
            assertEquals(DenyReason.PAIRING_CLOSED, withTimeout(2_000) { denied.await() })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a peer on another protocol version is refused and reported`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val outdated = CopyOnWriteArrayList<String>()
            val h = hub({ PairingDecision.Allowed(true) }, onOutdatedPeer = { outdated += it })
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { h.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            val ended = CompletableDeferred<Unit>()
            scope.launch { client.incoming().collect { rx += it }; ended.complete(Unit) }

            client.send(Frame.Hello(2, "secondary", "phone-1", "Old phone"))
            withTimeout(2_000) { ended.await() }

            assertEquals(listOf<Frame>(Frame.Denied(DenyReason.VERSION_MISMATCH)), rx.toList())
            assertEquals(listOf("Old phone"), outdated.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a session that doesn't complete the handshake in time is closed`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val h = hub({ PairingDecision.Allowed(true) }, handshakeDeadlineMs = 200)
            // Silent peer.
            val (silent, s1) = InMemoryFrameLink.pair()
            scope.launch { h.serve(s1) }
            val silentEnded = CompletableDeferred<Unit>()
            scope.launch { silent.incoming().collect {}; silentEnded.complete(Unit) }
            // Hello but never Auth.
            val (halfway, s2) = InMemoryFrameLink.pair()
            scope.launch { h.serve(s2) }
            val halfwayEnded = CompletableDeferred<Unit>()
            scope.launch { halfway.incoming().collect {}; halfwayEnded.complete(Unit) }
            halfway.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "phone-1", "Phone"))

            withTimeout(2_000) { silentEnded.await() }
            withTimeout(2_000) { halfwayEnded.await() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the host's own decision time does not count against the deadline`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // The "user" takes longer than the deadline to answer.
            val h = hub({ delay(600); PairingDecision.Allowed(true) }, handshakeDeadlineMs = 200)
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { h.serve(server) }
            val proxy = ProxyTransport(client, scope, "phone-1", "Phone")
            assertTrue(withTimeout(3_000) { proxy.control("tareScale") }.isSuccess)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `connections beyond the session cap are refused`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val h = hub({ PairingDecision.Allowed(true) }, maxSessions = 2)
            repeat(2) { i ->
                val (c, s) = InMemoryFrameLink.pair()
                scope.launch { h.serve(s) }
                val p = ProxyTransport(c, scope, "phone-$i", "Phone $i")
                assertTrue(withTimeout(2_000) { p.control("tareScale") }.isSuccess)
            }
            val (third, s3) = InMemoryFrameLink.pair()
            scope.launch { h.serve(s3) }
            val rx = CopyOnWriteArrayList<Frame>()
            val ended = CompletableDeferred<Unit>()
            scope.launch { third.incoming().collect { rx += it }; ended.complete(Unit) }

            withTimeout(2_000) { ended.await() }
            assertEquals(listOf<Frame>(Frame.Denied(DenyReason.BUSY)), rx.toList())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `revoking a device ends its live session`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val h = hub({ PairingDecision.Allowed(true) })
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { h.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            val ended = CompletableDeferred<Unit>()
            scope.launch { client.incoming().collect { rx += it }; ended.complete(Unit) }
            handshake(client, rx)
            client.send(Frame.Attach(1L, addr))
            withTimeout(2_000) { while (rx.none { it is Frame.Attached }) delay(5) }

            h.revoke("phone-1")
            withTimeout(2_000) { ended.await() }
            h.onInbound(addr, service, stateChar, byteArrayOf(0x01), atMs = 1L)

            withTimeout(2_000) { while (h.hasClient("phone-1")) delay(5) }
            assertEquals(Frame.Denied(DenyReason.REVOKED), rx.last(), "told why, and nothing after")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `an Auth outside the handshake is ignored and a challenge is answered once`() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val calls = CopyOnWriteArrayList<PairingRequest>()
            val h = hub({ calls += it; PairingDecision.Allowed(true) })
            val (client, server) = InMemoryFrameLink.pair()
            scope.launch { h.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            scope.launch { client.incoming().collect { rx += it } }

            client.send(Frame.Auth("early")) // before any Hello
            handshake(client, rx)

            assertEquals(1, calls.size)
            assertTrue(!calls.single().hasProof, "the early Auth was not taken as the answer")
            assertNotNull(rx.filterIsInstance<Frame.Challenge>().single().nonce)
        } finally {
            scope.cancel()
        }
    }
}

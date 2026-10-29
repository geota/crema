package coffee.crema.ble.proxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The secondary's side of the pairing handshake against a scripted primary:
 * it proves its secret only to a host that proved it first, keeps a pairing
 * secret only when it asked to pair with the host that challenged it, and
 * refuses a Welcome from any other host.
 */
class ProxyPairingBindingTest {

    private val held = ConcurrentHashMap<String, String>()
    private val paired = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val denied = CompletableDeferred<String>()
    private val welcomed = CompletableDeferred<Unit>()
    private val confirmed = Channel<String>(Channel.UNLIMITED)
    private val changed = Channel<Pair<String, String>>(Channel.UNLIMITED)
    private val pushed = Channel<String>(Channel.UNLIMITED)

    private suspend fun <T> scripted(
        allowRepair: Boolean = true,
        pinned: String? = null,
        block: suspend (server: FrameLink, rx: Channel<Frame>, proxy: ProxyTransport) -> T,
    ): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            val rx = Channel<Frame>(Channel.UNLIMITED)
            scope.launch { server.incoming().collect { rx.send(it) } }
            val proxy = ProxyTransport(
                client, scope, "phone-1", "Phone",
                onDenied = { denied.complete(it) },
                onWelcome = { _, _ -> welcomed.complete(Unit) },
                pairingSecretFor = { held[it] },
                onPaired = { id, _, secret -> held[id] = secret; paired.trySend(id to secret) },
                pinnedHostId = { pinned },
                onHostConfirmed = { confirmed.trySend(it) },
                allowRepair = allowRepair,
                onHostChanged = { id, name -> changed.trySend(id to name) },
                onConfig = { pushed.trySend("config:$it") },
                onEvent = { pushed.trySend("event:$it") },
            )
            return withTimeout(5_000) { block(server, rx, proxy) }
        } finally {
            scope.cancel()
        }
    }

    private suspend inline fun <reified F : Frame> Channel<Frame>.next(): F {
        while (true) { val f = receive(); if (f is F) return f }
    }

    private fun welcome(primaryId: String, secret: String?) =
        Frame.Welcome(PROXY_PROTOCOL_VERSION, primaryId, "Tablet", "primary", emptyList(), "control", secret)

    @Test
    fun `a Welcome from another host than the one that challenged is refused`() = runBlocking {
        scripted { server, rx, proxy ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-1", "Tablet", PairingCrypto.newNonce()))
            assertNull(rx.next<Frame.Auth>().proof, "no secret held: a pairing request")
            server.send(welcome("host-2", PairingCrypto.newSecret()))

            assertEquals(DenyReason.HANDSHAKE_MISMATCH, denied.await())
            assertTrue(held.isEmpty(), "no secret kept from the wrong host")
            assertTrue(proxy.control("tareScale").isFailure)
        }
    }

    @Test
    fun `a secret on a Welcome after a proof is ignored and the held one kept`() = runBlocking {
        val secret = PairingCrypto.newSecret()
        held["host-1"] = secret
        scripted { server, rx, _ ->
            val hello = rx.next<Frame.Hello>()
            val hn = PairingCrypto.newNonce()
            server.send(Frame.Challenge("host-1", "Tablet", hn, PairingCrypto.hostProof(secret, "host-1", "phone-1", hello.nonce!!, hn)))
            val auth = rx.next<Frame.Auth>()
            assertTrue(PairingCrypto.verifyClient(secret, "host-1", "phone-1", hello.nonce!!, hn, auth.proof), "proved to a host that proved first")
            server.send(welcome("host-1", PairingCrypto.newSecret()))

            welcomed.await()
            assertEquals(secret, held["host-1"], "an unsolicited secret doesn't replace the held one")
            assertTrue(paired.tryReceive().isFailure)
        }
    }

    @Test
    fun `even on the user's pick, a host that can't prove the held secret is refused and the secret kept`() = runBlocking {
        val secret = PairingCrypto.newSecret()
        held["host-1"] = secret
        scripted(allowRepair = true) { server, rx, proxy ->
            rx.next<Frame.Hello>()
            // No host proof (or a wrong one): this host doesn't know our secret.
            server.send(Frame.Challenge("host-1", "Tablet", PairingCrypto.newNonce(), hostProof = "forged"))
            assertEquals(DenyReason.HOST_CHANGED, denied.await())
            assertEquals("host-1" to "Tablet", changed.receive(), "the user is offered to forget the host")
            assertTrue(rx.tryReceive().getOrNull() !is Frame.Auth, "no pairing request")
            assertEquals(secret, held["host-1"], "the held secret is not replaced")
            assertTrue(proxy.control("tareScale").isFailure)
        }
    }

    @Test
    fun `after the host is forgotten on this device, the user's pick may pair again`() = runBlocking {
        held["host-1"] = PairingCrypto.newSecret()
        held.remove("host-1") // Forget host
        scripted(allowRepair = true) { server, rx, _ ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-1", "Tablet", PairingCrypto.newNonce()))
            assertNull(rx.next<Frame.Auth>().proof, "a pairing request")
            val fresh = PairingCrypto.newSecret()
            server.send(welcome("host-1", fresh))
            assertEquals("host-1" to fresh, paired.receive())
            assertEquals(fresh, held["host-1"])
        }
    }

    @Test
    fun `an unattended session refuses a host that can't prove the held secret`() = runBlocking {
        val secret = PairingCrypto.newSecret()
        held["host-1"] = secret
        scripted(allowRepair = false) { server, rx, proxy ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-1", "Tablet", PairingCrypto.newNonce()))
            assertEquals(DenyReason.HOST_CHANGED, denied.await())
            assertTrue(proxy.control("tareScale").isFailure)
            assertTrue(rx.tryReceive().getOrNull() !is Frame.Auth, "no Auth at all: no pairing request either")
            assertEquals(secret, held["host-1"])
        }
    }

    @Test
    fun `a pinned target refuses a challenge from another host id`() = runBlocking {
        scripted(allowRepair = true, pinned = "host-1") { server, rx, _ ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-9", "Tablet", PairingCrypto.newNonce()))
            assertEquals(DenyReason.HOST_MISMATCH, denied.await())
            assertTrue(rx.tryReceive().getOrNull() !is Frame.Auth, "no Auth sent to another host")
            assertTrue(held.isEmpty())
        }
    }

    @Test
    fun `a completed session confirms the host it challenged with`() = runBlocking {
        scripted(pinned = "host-1") { server, rx, _ ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-1", "Tablet", PairingCrypto.newNonce()))
            rx.next<Frame.Auth>()
            server.send(welcome("host-1", null))
            assertEquals("host-1", confirmed.receive())
        }
    }

    @Test
    fun `a Welcome without a Challenge in this session is refused`() = runBlocking {
        scripted { server, rx, proxy ->
            rx.next<Frame.Hello>()
            server.send(welcome("host-1", PairingCrypto.newSecret()))
            assertEquals(DenyReason.HANDSHAKE_MISMATCH, denied.await())
            assertTrue(held.isEmpty(), "nothing stored")
            assertTrue(confirmed.tryReceive().isFailure, "no host confirmed")
            assertTrue(!welcomed.isCompleted, "not welcomed")
            assertTrue(proxy.control("tareScale").isFailure)
        }
    }

    @Test
    fun `a Welcome from a host other than the pinned one is refused`() = runBlocking {
        scripted(pinned = "host-1") { server, rx, proxy ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-1", "Tablet", PairingCrypto.newNonce()))
            rx.next<Frame.Auth>()
            server.send(welcome("host-2", PairingCrypto.newSecret()))
            assertEquals(DenyReason.HANDSHAKE_MISMATCH, denied.await())
            assertTrue(held.isEmpty(), "nothing stored")
            assertTrue(confirmed.tryReceive().isFailure, "no host confirmed")
            assertTrue(proxy.control("tareScale").isFailure)
        }
    }

    @Test
    fun `an unattended session refuses a host this device holds no secret for`() = runBlocking {
        scripted(allowRepair = false) { server, rx, proxy ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-7", "Tablet", PairingCrypto.newNonce()))
            assertEquals(DenyReason.HOST_UNVERIFIED, denied.await())
            assertTrue(rx.tryReceive().getOrNull() !is Frame.Auth, "no pairing request without the user")
            assertTrue(confirmed.tryReceive().isFailure, "pin unchanged")
            assertTrue(proxy.control("tareScale").isFailure)
        }
    }

    @Test
    fun `an unattended session refuses a host other than the pinned one, even one it holds a secret for`() = runBlocking {
        held["host-2"] = PairingCrypto.newSecret()
        scripted(allowRepair = false, pinned = "host-1") { server, rx, _ ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-2", "Tablet", PairingCrypto.newNonce()))
            assertEquals(DenyReason.HOST_MISMATCH, denied.await())
            assertTrue(rx.tryReceive().getOrNull() !is Frame.Auth)
            assertTrue(confirmed.tryReceive().isFailure, "pin unchanged")
        }
    }

    @Test
    fun `an unattended session connects to the pinned host when it proves the secret`() = runBlocking {
        val secret = PairingCrypto.newSecret()
        held["host-1"] = secret
        scripted(allowRepair = false, pinned = "host-1") { server, rx, _ ->
            val hello = rx.next<Frame.Hello>()
            val hn = PairingCrypto.newNonce()
            server.send(Frame.Challenge("host-1", "Tablet", hn, PairingCrypto.hostProof(secret, "host-1", "phone-1", hello.nonce!!, hn)))
            val auth = rx.next<Frame.Auth>()
            assertTrue(PairingCrypto.verifyClient(secret, "host-1", "phone-1", hello.nonce!!, hn, auth.proof))
            server.send(welcome("host-1", null))
            welcomed.await()
            assertEquals("host-1", confirmed.receive())
            assertTrue(!denied.isCompleted)
        }
    }

    @Test
    fun `nothing a host pushes before its Welcome is acted on`() = runBlocking {
        scripted { server, rx, _ ->
            rx.next<Frame.Hello>()
            // Before (and instead of) a completed handshake: config, an event,
            // a retired hand-off offer, telemetry.
            server.send(Frame.Config("""{"stopOnWeight":false}"""))
            server.send(Frame.Event("someone drove"))
            server.send(Frame.HandoffOffer("Fake host"))
            server.send(Frame.Notify("AA:BB", "s", "c", "00", 1L))
            server.send(Frame.Challenge("host-1", "Tablet", PairingCrypto.newNonce()))
            rx.next<Frame.Auth>()
            server.send(welcome("host-1", null))
            welcomed.await()
            // After the Welcome, a push is applied (a round trip orders it).
            server.send(Frame.Event("after"))
            assertEquals("event:after", pushed.receive(), "only the post-Welcome push reached the app")
            assertTrue(pushed.tryReceive().isFailure)
        }
    }

    /** AC-3: an older hub's "Hand off" pushes a retired [Frame.HandoffOffer].
     *  The remote decodes and ignores it: the session stays welcomed, nothing
     *  reaches the app, and the next push still lands. */
    @Test
    fun `a retired handoff offer is ignored`() = runBlocking {
        scripted { server, rx, proxy ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-1", "Tablet", PairingCrypto.newNonce()))
            rx.next<Frame.Auth>()
            server.send(welcome("host-1", null))
            welcomed.await()
            assertEquals("host-1", confirmed.receive())

            server.send(Frame.HandoffOffer("Old hub"))
            server.send(Frame.Event("after"))
            assertEquals("event:after", pushed.receive(), "the offer reached nothing; the next push did")
            assertTrue(pushed.tryReceive().isFailure)
            assertTrue(!denied.isCompleted, "the session wasn't refused")
            // Still welcomed: a control goes out on the same session and its reply lands.
            val control = async { proxy.sendControl("tareScale", "") }
            val sent = rx.next<Frame.Control>()
            server.send(Frame.ControlOk(sent.id))
            assertEquals(ControlReply.Ok, control.await())
        }
    }

    @Test
    fun `a first pairing keeps the issued secret`() = runBlocking {
        scripted { server, rx, _ ->
            rx.next<Frame.Hello>()
            server.send(Frame.Challenge("host-1", "Tablet", PairingCrypto.newNonce()))
            assertNull(rx.next<Frame.Auth>().proof)
            val issued = PairingCrypto.newSecret()
            server.send(welcome("host-1", issued))
            assertEquals("host-1" to issued, paired.receive())
        }
    }

    @Test
    fun `the relay proves the secret only to a device it paired`() = runBlocking {
        val secret = PairingCrypto.newSecret()
        val hub = RelayHub(
            primaryId = "host-1", primaryName = "Tablet", roster = { emptyList() },
            readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false },
            pairingSecretFor = { if (it == "phone-1") secret else null },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            for ((id, expectProof) in listOf("phone-1" to true, "phone-2" to false)) {
                val (client, server) = InMemoryFrameLink.pair()
                scope.launch { hub.serve(server) }
                val rx = Channel<Frame>(Channel.UNLIMITED)
                scope.launch { client.incoming().collect { rx.send(it) } }
                val cn = PairingCrypto.newNonce()
                client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", id, "Phone", nonce = cn))
                val challenge = withTimeout(2_000) { rx.next<Frame.Challenge>() }
                if (expectProof) {
                    assertNotNull(challenge.hostProof)
                    assertTrue(PairingCrypto.verifyHost(secret, "host-1", id, cn, challenge.nonce, challenge.hostProof))
                } else {
                    assertNull(challenge.hostProof)
                }
            }
        } finally {
            scope.cancel()
        }
    }
}

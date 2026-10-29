package coffee.crema.ble.proxy

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.REFIND_DELAY_MS
import coffee.crema.ui.VmHarness
import coffee.crema.ui.remoteStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Validation P1b's probes, kept as regressions. NSD advertisements are
 * unauthenticated and nothing binds a session to the endpoint it dialed, so
 * the re-find dials only new ports on the remote's saved host, bounds every
 * attempt with a handshake deadline, saves a port only once it is welcomed,
 * and never asks to pair. A real remote ([MainViewModel]) over real sockets,
 * NSD injected. "localhost" stands for another LAN address: the endpoints
 * listen on the loopback, so it is reachable, but it isn't the saved
 * `127.0.0.1`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RefindSecurityTest {

    private val store = ViewModelStore()
    private val endpoints = mutableListOf<TestHubServer>()
    private val servers = mutableListOf<MockWebServer>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val secret = PairingCrypto.newSecret()
    private val pairingFile get() = File(VmHarness.app().filesDir, "proxy-pairing.json")

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        endpoints.forEach { runCatching { it.kill() } }
        servers.forEach { runCatching { it.close() } }
        scopes.forEach { it.cancel() }
    }

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1)).also { scopes += it }

    /** Advance the main looper's clock by [ms] in steps, letting socket
     *  threads post back between them. */
    private fun stepped(ms: Long) {
        var left = ms
        while (left > 0) {
            VmHarness.advance(250)
            Thread.sleep(10)
            left -= 250
        }
    }

    /** A remote restored on 127.0.0.1:[port], paired with and pinned to
     *  host-1 ([held] false: pinned, but the secret is gone). */
    private fun restoredRemote(port: Int, held: Boolean = true): MainViewModel {
        val heldJson = if (held) """{"host-1":"$secret"}""" else "{}"
        pairingFile.writeText("""{"hostId":"this-device","held":$heldJson,"pinnedHost":"host-1"}""")
        VmHarness.persistRole("secondary", "127.0.0.1", port)
        val vm = VmHarness.build(store)
        VmHarness.idle()
        return vm
    }

    private fun savedPort(): Int =
        Json.parseToJsonElement(File(VmHarness.app().filesDir, "prefs.json").readText())
            .jsonObject["proxyPrimaryPort"]!!.jsonPrimitive.int

    /** A WebSocket endpoint that accepts [sockets] dials and never says anything. */
    private fun silent(sockets: Int = 5): MockWebServer {
        val s = MockWebServer()
        repeat(sockets) { s.enqueue(MockResponse.Builder().webSocketUpgrade(MockWsServerLink()).build()) }
        s.start(InetAddress.getLoopbackAddress(), 0)
        servers += s
        return s
    }

    /** A real hub (host-1, proving [secret]) behind one long-lived server
     *  that serves several sockets, so one can drop and another come in. */
    private class LiveHub(val hub: RelayHub, val scope: CoroutineScope, val controls: MutableList<String>)

    private fun liveHub(clientId: () -> String): LiveHub {
        val controls = CopyOnWriteArrayList<String>()
        val hub = RelayHub(
            primaryId = "host-1", primaryName = "Kitchen tablet",
            roster = { listOf(DeviceInfo("AA:BB", "DE1", "de1", "CONNECTED")) },
            readSource = { _, _, _ -> ByteArray(0) }, isSnapshotChar = { _, _ -> false },
            controlHandler = { method, _, origin, _ -> controls += "$method from $origin"; Result.success(Unit) },
            authorize = { r -> if (r.proves(secret)) PairingDecision.Allowed(true) else PairingDecision.Denied(DenyReason.NOT_AUTHORIZED) },
            pairingSecretFor = { id -> secret.takeIf { id == clientId() } },
        )
        return LiveHub(hub, scope(), controls)
    }

    private fun serve(live: LiveHub, port: Int = 0): MockWebServer {
        val server = MockWebServer().also { servers += it }
        repeat(4) {
            val link = MockWsServerLink()
            server.enqueue(MockResponse.Builder().webSocketUpgrade(link).build())
            live.scope.launch { live.hub.serve(link) }
        }
        server.start(InetAddress.getLoopbackAddress(), port)
        return server
    }

    /** S1's attacker: accepts a remote and pipes its frames to the hub at
     *  [hubPort] and back, so it could inject on the approved session. */
    private fun relayTo(hubPort: Int): MockWebServer {
        val victim = MockWsServerLink()
        val attacker = MockWebServer().also { servers += it }
        attacker.enqueue(MockResponse.Builder().webSocketUpgrade(victim).build())
        attacker.start(InetAddress.getLoopbackAddress(), 0)
        val scope = scope()
        scope.launch {
            var toHub: WebSocket? = null
            victim.incoming().collect { f ->
                val hub = toHub ?: OkHttpClient().newWebSocket(
                    Request.Builder().url("ws://127.0.0.1:$hubPort${LanRelayServer.PATH}").build(),
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            scope.launch { victim.send(FrameCodec.decode(text)) }
                        }
                    },
                ).also { toHub = it }
                hub.send(FrameCodec.encode(f))
                // Once the remote is in, drive the machine as the remote.
                if (f is Frame.Auth) hub.send(FrameCodec.encode(Frame.Control(9_999, "startShot", "")))
            }
        }
        return attacker
    }

    /** S1 (P0): an advertiser at another address that would relay the
     *  remote's handshake to the real hub is never dialed. The remote
     *  reattaches to the hub on its saved host directly, and nothing reaches
     *  the machine. */
    @Test
    fun `an advertiser at another address can't relay the handshake`() {
        lateinit var vm: MainViewModel
        val live = liveHub(clientId = { VmHarness.proxyController(vm).deviceId })
        val hubServer = serve(live)
        val attacker = relayTo(hubServer.port)
        vm = restoredRemote(1)
        val proxy = VmHarness.proxyController(vm)
        val peers = MutableStateFlow(listOf(Peer("nsd-attacker", "A Kitchen tablet", "primary", true, "localhost", attacker.port)))
        proxy.overridePeers(peers)

        stepped(REFIND_DELAY_MS * 6)
        assertEquals(0, attacker.requestCount, "the remote dialed the attacker")
        assertEquals(1, savedPort())
        assertEquals("Reconnecting to 127.0.0.1…", vm.ui.value.remoteStatus())

        // The hub advertises its new port on the saved host, next to the attacker.
        peers.value = peers.value + Peer("nsd-real", "Kitchen tablet", "primary", true, "127.0.0.1", hubServer.port)
        stepped(REFIND_DELAY_MS + 500)
        VmHarness.idleUntil("welcomed by the hub") { !vm.ui.value.mirrorReconnecting && live.hub.remotes().isNotEmpty() }
        assertEquals(0, attacker.requestCount, "the remote dialed the attacker")
        assertEquals(emptyList(), live.controls.toList(), "nothing reached the machine")
        assertEquals("Connected through Kitchen tablet", vm.ui.value.remoteStatus())
    }

    /** S2 (P1) / M3: a silent advertiser can't hold the re-find. At another
     *  address it is never dialed; on the saved host, each attempt ends at
     *  the handshake deadline, the re-find gives up after three, and the
     *  silent port is never saved. The hub's own new port then resumes it. */
    @Test
    fun `a silent advertiser can't hold the re-find or be saved`() {
        lateinit var vm: MainViewModel
        val live = liveHub(clientId = { VmHarness.proxyController(vm).deviceId })
        val elsewhere = silent()
        val quiet = silent()
        vm = restoredRemote(1)
        val proxy = VmHarness.proxyController(vm)
        val peers = MutableStateFlow(
            listOf(
                Peer("nsd-elsewhere", "A Kitchen tablet", "primary", true, "localhost", elsewhere.port),
                Peer("nsd-silent", "Kitchen tablet", "primary", true, "127.0.0.1", quiet.port),
            ),
        )
        proxy.overridePeers(peers)

        stepped(180_000)
        assertEquals(0, elsewhere.requestCount, "another address is never dialed")
        assertEquals(3, quiet.requestCount, "three bounded attempts")
        assertTrue(vm.ui.value.refindGaveUp)
        assertEquals("Couldn't verify 127.0.0.1", vm.ui.value.remoteStatus())
        assertEquals(1, vm.ui.value.proxyPrimaryPort, "the silent port isn't the target")
        assertEquals(1, savedPort(), "the silent port isn't saved")

        val hubServer = serve(live)
        peers.value = listOf(Peer("nsd-real", "Kitchen tablet", "primary", true, "127.0.0.1", hubServer.port))
        stepped(REFIND_DELAY_MS + 500)
        VmHarness.idleUntil("welcomed by the hub") { !vm.ui.value.mirrorReconnecting && vm.ui.value.proxyPrimaryPort == hubServer.port }
        VmHarness.idleUntil("the hub's port saved") { savedPort() == hubServer.port }
        assertEquals(3, quiet.requestCount)
    }

    /** S3 (P1): an impostor that copies the hub's NSD instance id, under a
     *  name that sorts first, can't hide the hub: instance ids no longer
     *  matter, and its address isn't the saved host. */
    @Test
    fun `an impostor reusing the hub's instance id can't hide it`() {
        lateinit var vm: MainViewModel
        val (real, realHub) = TestHubServer.relay("host-1", "Kitchen tablet", secret, clientId = { VmHarness.proxyController(vm).deviceId })
        val impostor = TestHubServer.scripted { Frame.Challenge("rogue-1", "Kitchen tablet", PairingCrypto.newNonce()) }
        endpoints += listOf(real, impostor)
        vm = restoredRemote(1)
        VmHarness.proxyController(vm).overridePeers(
            MutableStateFlow(
                listOf(
                    Peer("nsd-hub", "A Kitchen tablet", "primary", true, "localhost", impostor.port),
                    Peer("nsd-hub", "Kitchen tablet", "primary", true, "127.0.0.1", real.port),
                ),
            ),
        )

        stepped(REFIND_DELAY_MS + 500)
        VmHarness.idleUntil("welcomed by the hub") { !vm.ui.value.mirrorReconnecting && realHub.remotes().isNotEmpty() }
        assertEquals(emptyList(), impostor.received, "the impostor was never dialed")
        assertEquals(real.port, vm.ui.value.proxyPrimaryPort)
        VmHarness.idleUntil("the hub's port saved") { savedPort() == real.port }
    }

    /** M2: a re-find dial never asks to pair. Pinned to host-1 but holding
     *  no secret for it (lost, or never saved), the remote meets a host-1
     *  on a new port of the saved host that proves nothing: it refuses
     *  without any Auth, keeps the pin, and stores nothing. */
    @Test
    fun `a re-find never asks to pair`() {
        val unproven = TestHubServer.scripted { Frame.Challenge("host-1", "Kitchen tablet", PairingCrypto.newNonce()) }
        endpoints += unproven
        val vm = restoredRemote(1, held = false)
        val proxy = VmHarness.proxyController(vm)
        proxy.overridePeers(MutableStateFlow(listOf(Peer("nsd-hub", "Kitchen tablet", "primary", true, "127.0.0.1", unproven.port))))

        stepped(REFIND_DELAY_MS + 2_000)
        VmHarness.idleUntil("the Hello") { unproven.received.any { "\"type\":\"hello\"" in it } }
        stepped(2_000)
        assertEquals(emptyList(), unproven.auths, "no pairing request (no Auth at all)")
        assertEquals("host-1", proxy.pairingStore.pinnedHost)
        assertEquals(null, proxy.pairingStore.heldSecret("host-1"))
        assertEquals("secondary", proxy.state.value.role)
        assertEquals(1, savedPort())
    }

    /** S4 (P2): a hub the re-find found is the remote's target from then on,
     *  saved. When its relay drops for longer than the re-find delay and
     *  comes back on the same port, the remote reconnects there, not to the
     *  old dead address. */
    @Test
    fun `a re-found hub that blips is kept`() {
        lateinit var vm: MainViewModel
        val clientId = { VmHarness.proxyController(vm).deviceId }
        val (first, _) = TestHubServer.relay("host-1", "Kitchen tablet", secret, clientId = clientId)
        endpoints += first
        val oldPort = first.port
        vm = restoredRemote(oldPort)
        val proxy = VmHarness.proxyController(vm)
        val peers = MutableStateFlow<List<Peer>>(emptyList())
        proxy.overridePeers(peers)
        VmHarness.idleUntil("the first welcome") { !vm.ui.value.mirrorReconnecting }
        first.kill()
        VmHarness.idleUntil("reconnecting") { vm.ui.value.mirrorReconnecting }

        val live = liveHub(clientId)
        val server = serve(live)
        val newPort = server.port
        peers.value = listOf(Peer("nsd-second", "Kitchen tablet", "primary", true, "127.0.0.1", newPort))
        stepped(REFIND_DELAY_MS + 500)
        VmHarness.idleUntil("the re-found welcome") { !vm.ui.value.mirrorReconnecting && vm.ui.value.proxyPrimaryPort == newPort }
        VmHarness.idleUntil("the new port saved") { savedPort() == newPort }
        assertEquals("Connected through Kitchen tablet", vm.ui.value.remoteStatus(), "the hub's name survives the switch")

        // A Wi-Fi blip on the hub: its relay is gone for ~8 s, then back on
        // the same port.
        server.close()
        stepped(REFIND_DELAY_MS + 3_000)
        val back = serve(live, port = newPort)
        stepped(60_000)
        VmHarness.idleUntil("welcomed again") { !vm.ui.value.mirrorReconnecting }
        assertTrue(back.requestCount > 0, "the remote came back to the re-found port")
        assertEquals(newPort, vm.ui.value.proxyPrimaryPort)
        assertEquals(newPort, savedPort())
    }
}

package coffee.crema.ble.proxy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.security.FakeKeystore
import coffee.crema.ui.ProxyController
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The picker's `kid` and address are unauthenticated (NSD). A LAN device
 * that advertises a pinned hub's `kid` at its own address, with its own key,
 * is picked: the remote dials it with the pinned key, the handshake fails,
 * and nothing stored changes. The hub pin keeps its address, the saved hub
 * stays, and no prefs callback ever sees the spoofer's address. The remote
 * then goes back to the real hub.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SpoofedKidPickTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = CopyOnWriteArrayList<String>()
    private val savedTargets = CopyOnWriteArrayList<Pair<String, Int>>()
    private val controllers = mutableListOf<ProxyController>()
    private var spoofer: RelayRig? = null

    @After
    fun tearDown() {
        controllers.forEach { it.close() }
        spoofer?.stop()
        scope.cancel()
    }

    private fun controller(name: String): ProxyController = ProxyController(
        app = ApplicationProvider.getApplicationContext(),
        json = Json { ignoreUnknownKeys = true },
        scope = scope,
        setCoreReadOnly = {}, setRecorderEnabled = {},
        notify = { lines += "$name notify: $it" },
        appendLog = { lines += "$name log: $it" },
        connect = {}, disconnect = {}, disconnectDevices = {}, connectScale = {}, scaleAttachWanted = { false },
        de1Connected = { false }, machineStateName = { null }, machineActions = RecordingMachineActions(),
        snapshotConfig = { "" }, applyConfig = {},
        roster = { listOf(DeviceInfo("AA:BB", "DE1", "de1", "CONNECTED")) }, isSnapshotChar = { _, _ -> false },
        onModeApplied = { role, host, port -> if (name == "remote" && role == "secondary") savedTargets += host to port },
        onTargetMoved = { host, port -> if (name == "remote") savedTargets += host to port },
        relayStore = RelayKeyStore(Files.createTempDirectory(name).toFile(), RelayKeyStore.deviceBox(FakeKeystore())),
        commandLane = Dispatchers.Default,
        refindDelayMs = 60_000,
    ).also { controllers += it }

    @Test
    fun `a spoofed kid at another address changes no pin, saved hub or saved address`() {
        val hub = controller("hub")
        val remote = controller("remote")
        val peers = MutableStateFlow<List<Peer>>(emptyList())
        remote.overridePeers(peers)
        hub.setHubMode(true)
        runBlocking { eventually(what = "the hub listening") { lines.any { "LAN relay listening" in it } } }
        val port = lines.first { "LAN relay listening" in it }.substringAfterLast(":").toInt()
        hub.openPairingWindow()
        remote.pairWith("127.0.0.1", port, "Kitchen tablet")
        runBlocking { eventually(what = "the code") { remote.state.value.pairing is PairingState.ShowCode } }
        val code = (remote.state.value.pairing as PairingState.ShowCode).code
        runBlocking { eventually(what = "the hub's dialog") { hub.state.value.pendingPairing != null } }
        hub.submitPairingCode(code, canControl = true)
        runBlocking { eventually(what = "the resume") { remote.state.value.role == "secondary" && remote.state.value.hubName.isNotEmpty() } }
        val pinned = remote.relayStore.hubs.single()
        assertEquals(pinned.id, remote.relayStore.savedHub)
        assertEquals(port, pinned.port)

        // A spoofer: its own key, the real hub's kid, its own address.
        val rig = RelayRig().start().also { spoofer = it }
        peers.value = listOf(Peer("spoof", "Kitchen tablet", "primary", holdsDe1 = true, host = "127.0.0.1", port = rig.port, kid = pinned.id.take(8)))
        runBlocking { eventually(what = "the spoofer listed") { remote.state.value.peers.isNotEmpty() } }
        lines.clear()
        remote.switchToSecondary("127.0.0.1", rig.port)
        runBlocking { eventually(what = "the spoofer refused") { lines.any { "remote log: Hub refused the session" in it } } }

        val after = remote.relayStore.hubs.single()
        assertEquals("127.0.0.1" to port, after.host to after.port, "the pin keeps the welcomed address")
        assertEquals(pinned.id, remote.relayStore.savedHub, "the saved hub is unchanged")
        assertTrue(savedTargets.none { it.second == rig.port }, "the spoofer's address never reached the prefs: $savedTargets")
        assertEquals(0, rig.trust.admitted.size, "the spoofer admitted nothing")

        // A refused pick goes back to the last welcomed target: the real hub.
        runBlocking { eventually(ms = 15_000, what = "back on the real hub") { lines.any { "remote log: Multi-device: SECONDARY → ws://127.0.0.1:$port" in it } } }
        assertEquals("127.0.0.1" to port, remote.relayStore.hubs.single().let { it.host to it.port })
    }
}

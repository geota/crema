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
 * Two real [ProxyController]s (a hub and a remote, each with its own key
 * store) over a real socket: the hub user chooses View only with the code,
 * the hub pins the remote with that scope, and the remote's resumed session
 * is view-only. Then the hub forgets it: the live session ends with an
 * authenticated revoke, and the remote drops its pin and becomes normal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PairingFlowControllerTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = CopyOnWriteArrayList<String>()
    private val controllers = mutableListOf<ProxyController>()

    @After
    fun tearDown() {
        controllers.forEach { it.close() }
        scope.cancel()
    }

    private fun controller(
        name: String,
        store: RelayKeyStore = RelayKeyStore(Files.createTempDirectory(name).toFile(), RelayKeyStore.deviceBox(FakeKeystore())),
    ): ProxyController = ProxyController(
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
        onModeApplied = { _, _, _ -> },
        relayStore = store,
        commandLane = Dispatchers.Default,
        refindDelayMs = 60_000,
    ).also { controllers += it }

    @Test
    fun `a view-only pairing resumes view-only and a forget makes the remote normal`() {
        val hub = controller("hub")
        val remote = controller("remote")
        hub.setHubMode(true)
        runBlocking { eventually(what = "the hub listening") { lines.any { "LAN relay listening" in it } } }
        val port = lines.first { "LAN relay listening" in it }.substringAfterLast(":").toInt()
        hub.openPairingWindow()

        remote.pairWith("127.0.0.1", port, "Kitchen tablet")
        runBlocking { eventually(what = "the code") { remote.state.value.pairing is PairingState.ShowCode } }
        val code = (remote.state.value.pairing as PairingState.ShowCode).code
        runBlocking { eventually(what = "the hub's dialog") { hub.state.value.pendingPairing != null } }
        hub.submitPairingCode(code, canControl = false)
        try {
            runBlocking { eventually(what = "the resume") { remote.state.value.role == "secondary" && remote.state.value.hubName.isNotEmpty() } }
        } catch (e: AssertionError) {
            // Timed out once on CI: say where it stuck.
            throw AssertionError("${e.message}; remote ${remote.state.value.role}/${remote.state.value.hubName}/${remote.state.value.pairing}\n${lines.joinToString("\n")}", e)
        }

        assertEquals(SCOPE_MIRROR, hub.relayStore.remotes.single().scope, "pinned with the chosen scope")
        assertTrue(remote.state.value.mirrorViewOnly, "the resumed session is view-only")
        runBlocking { eventually(what = "listed") { hub.state.value.remotes.isNotEmpty() } }
        assertEquals(false, hub.state.value.remotes.single().canControl)

        hub.forgetRemote(hub.relayStore.remotes.single().id)
        runBlocking { eventually(what = "the remote demoted") { remote.state.value.role == "normal" } }
        assertTrue(remote.relayStore.hubs.isEmpty(), "the remote dropped the hub's pin")
        assertTrue(lines.any { "removed this device" in it }, "the remote says so")
        assertTrue(hub.relayStore.remotes.isEmpty())
    }

    /**
     * AC-28 through the controller (SR-3b P3-2): after the hub user forgets a
     * remote, its next resume gets `NOT_PAIRED`, not `REVOKED`. The key left
     * the live pin set, so the core refuses it at IK, before admission.
     */
    @Test
    fun `a resume after a forget is not paired`() {
        val store = RelayKeyStore(Files.createTempDirectory("hub").toFile(), RelayKeyStore.deviceBox(FakeKeystore()))
        val hubKeys = (store.load() as RelayKeyLoad.Ready).keys
        val remote = coffee.crema.core.RelayKeys.generate()
        store.pinRemote(RelayRemotePin(remote.deviceId(), RelayKeyStore.b64(remote.publicKey()), "Phone", SCOPE_CONTROL, pairedAtMs = 1))
        val hub = controller("hub", store)
        hub.setHubMode(true)
        runBlocking { eventually(what = "the hub listening") { lines.any { "LAN relay listening" in it } } }
        val url = "ws://127.0.0.1:${lines.first { "LAN relay listening" in it }.substringAfterLast(":").toInt()}${LanRelayServer.PATH}"

        val first = CopyOnWriteArrayList<String>()
        val welcomed = java.util.concurrent.atomic.AtomicBoolean(false)
        ProxyTransport(ReconnectingClientLink(url, scope, remote, hubKeys.publicKey()), scope,
            onRefused = { reason, auth, _ -> first += "$reason/$auth" }, onWelcome = { _, _ -> welcomed.set(true) })
        runBlocking { eventually(what = "welcomed") { welcomed.get() } }

        hub.forgetRemote(remote.deviceId())
        runBlocking { eventually(what = "the revoke") { first.isNotEmpty() } }
        assertEquals(listOf("Revoked/true"), first.toList())

        val later = CopyOnWriteArrayList<String>()
        ProxyTransport(ReconnectingClientLink(url, scope, remote, hubKeys.publicKey()), scope,
            onRefused = { reason, auth, _ -> later += "$reason/$auth" })
        runBlocking { eventually(what = "the later refusal") { later.isNotEmpty() } }
        assertEquals(listOf("NotPaired/true"), later.toList())
    }
}

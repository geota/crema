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
        onModeApplied = { _, _, _ -> },
        relayStore = RelayKeyStore(Files.createTempDirectory(name).toFile(), RelayKeyStore.deviceBox(FakeKeystore())),
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
        runBlocking { eventually(what = "the resume") { remote.state.value.role == "secondary" && remote.state.value.hubName.isNotEmpty() } }

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
}

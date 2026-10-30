package coffee.crema.ble.proxy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.RelayKeys
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.security.FakeKeystore
import coffee.crema.ui.ProxyController
import java.io.File
import java.nio.file.Files
import java.util.Base64
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
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AC-31: codes, keys, the psk and the channel binding never reach
 * `appendLog`, Logcat or notifications. Two real [ProxyController]s — a hub
 * and a remote with their own key stores — pair over a real socket (the hub
 * user types the code the remote shows) and the remote resumes; every log
 * line, Logcat entry and notice of both is captured, and none contains the
 * code or any key (raw, hex or base64).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class NoSecretsInLogsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = CopyOnWriteArrayList<String>()
    private val controllers = mutableListOf<ProxyController>()

    @After
    fun tearDown() {
        controllers.forEach { it.close() }
        scope.cancel()
    }

    private fun controller(name: String, store: RelayKeyStore): ProxyController = ProxyController(
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

    private fun store(dir: File) = RelayKeyStore(dir, RelayKeyStore.deviceBox(FakeKeystore()))

    @Test
    fun `a full pairing and resume log no code and no key`() {
        ShadowLog.clear()
        val hubDir = Files.createTempDirectory("hub").toFile()
        val remoteDir = Files.createTempDirectory("remote").toFile()
        val hub = controller("hub", store(hubDir))
        val remote = controller("remote", store(remoteDir))
        hub.setHubMode(true)
        runBlocking { eventually(what = "the hub listening") { lines.any { "LAN relay listening" in it } } }
        val port = lines.first { "LAN relay listening" in it }.substringAfterLast(":").toInt()
        hub.openPairingWindow()

        remote.pairWith("127.0.0.1", port, "Kitchen tablet")
        runBlocking { eventually(what = "the code") { remote.state.value.pairing is PairingState.ShowCode } }
        val code = (remote.state.value.pairing as PairingState.ShowCode).code
        runBlocking { eventually(what = "the hub's dialog") { hub.state.value.pendingPairing != null } }
        hub.submitPairingCode(code, canControl = true)
        runBlocking {
            eventually(what = "the remote role") { remote.state.value.role == "secondary" }
            eventually(what = "the resume") { remote.state.value.hubName.isNotEmpty() && !remote.state.value.mirrorReconnecting }
        }
        assertTrue(hub.relayStore.remotes.isNotEmpty() && remote.relayStore.hubs.isNotEmpty(), "both pinned")

        val secrets = mutableListOf(code, code.take(3) + " " + code.drop(3))
        val hubKey = RelayKeyStore.publicKeyOf(remote.relayStore.hubs.single().public)
        val remoteKey = RelayKeyStore.publicKeyOf(hub.relayStore.remotes.single().public)
        for (key in listOf(hubKey, remoteKey)) {
            secrets += key.joinToString("") { "%02x".format(it) }
            secrets += Base64.getEncoder().encodeToString(key)
        }
        val logcat = ShadowLog.getLogs().map { "${it.tag}: ${it.msg} ${it.throwable ?: ""}" }
        val everything = lines + logcat
        assertTrue(everything.any { "Paired" in it }, "the capture saw the pairing: ${everything.takeLast(20)}")
        for (s in secrets) for (line in everything) assertFalse(s in line, "a secret in \"$line\"")
    }
}

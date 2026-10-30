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

/**
 * SR-3b P2-1: an authenticated refusal changes trust only in the hub that
 * signed it. The remote's saved hub is A; the user picks pinned hub B, which
 * has forgotten this remote and answers with an authenticated `NOT_PAIRED`.
 * Only B's pin goes; A's pin and the saved hub stay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RefusalUnpinsDialedHubTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = CopyOnWriteArrayList<String>()
    private var hubB: RelayRig? = null
    private val controller = ProxyController(
        app = ApplicationProvider.getApplicationContext(),
        json = Json { ignoreUnknownKeys = true },
        scope = scope,
        setCoreReadOnly = {}, setRecorderEnabled = {},
        notify = { lines += "notify: $it" },
        appendLog = { lines += "log: $it" },
        connect = {}, disconnect = {}, disconnectDevices = {}, connectScale = {}, scaleAttachWanted = { false },
        de1Connected = { false }, machineStateName = { null }, machineActions = RecordingMachineActions(),
        snapshotConfig = { "" }, applyConfig = {},
        roster = { emptyList() }, isSnapshotChar = { _, _ -> false },
        onModeApplied = { _, _, _ -> },
        relayStore = RelayKeyStore(Files.createTempDirectory("remote").toFile(), RelayKeyStore.deviceBox(FakeKeystore())),
        commandLane = Dispatchers.Default,
        refindDelayMs = 60_000,
    )

    @After
    fun tearDown() {
        controller.close()
        hubB?.stop()
        scope.cancel()
    }

    @Test
    fun `an authenticated refusal from a picked hub unpins only that hub`() {
        val store = controller.relayStore
        val hubA = coffee.crema.core.RelayKeys.generate()
        store.pinHub(RelayHubPin(hubA.deviceId(), RelayKeyStore.b64(hubA.publicKey()), "Hub A", "10.0.0.9", 41234, pinnedAtMs = 1))
        store.setSavedHub(hubA.deviceId())
        // Hub B is pinned here but has forgotten this remote.
        val rig = RelayRig().start().also { hubB = it }
        store.pinHub(RelayHubPin(rig.hubKeys.deviceId(), RelayKeyStore.b64(rig.hubKeys.publicKey()), "Hub B", "127.0.0.1", rig.port, pinnedAtMs = 1))

        controller.switchToSecondary("127.0.0.1", rig.port)
        runBlocking { eventually(what = "the refusal") { lines.any { "Mirror refused by the hub: NotPaired" in it } } }
        runBlocking { eventually(what = "normal") { controller.state.value.role == "normal" } }

        assertEquals(listOf(hubA.deviceId()), store.hubs.map { it.id }, "only B's pin went")
        assertEquals(hubA.deviceId(), store.savedHub, "the saved hub is untouched")
    }
}

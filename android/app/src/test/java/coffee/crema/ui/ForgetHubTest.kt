package coffee.crema.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.ble.proxy.PairingState
import coffee.crema.ble.proxy.RelayHubPin
import coffee.crema.ble.proxy.RelayKeyStore
import coffee.crema.core.RelayKeys
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.security.FakeKeystore
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AC-29: Forget on the remote drops the hub's pin (and, for the saved hub,
 * the remote role); the next pick of that hub pairs, never resumes.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ForgetHubTest {
    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val log = CopyOnWriteArrayList<String>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val controller = ProxyController(
        app = app, json = Json { ignoreUnknownKeys = true }, scope = scope,
        setCoreReadOnly = {}, setRecorderEnabled = {}, notify = {}, appendLog = { log += it },
        connect = {}, disconnect = {}, disconnectDevices = {}, connectScale = {}, scaleAttachWanted = { false },
        de1Connected = { false }, machineStateName = { null }, machineActions = RecordingMachineActions(),
        snapshotConfig = { "" }, applyConfig = {}, roster = { emptyList() }, isSnapshotChar = { _, _ -> false },
        onModeApplied = { _, _, _ -> },
        relayStore = RelayKeyStore(app.filesDir, RelayKeyStore.deviceBox(FakeKeystore())),
    )

    @After
    fun tearDown() {
        controller.cancelRemotePairing()
        controller.close()
        scope.cancel()
    }

    private fun advance(ms: Long) {
        scheduler.advanceTimeBy(ms)
        scheduler.runCurrent()
    }

    @Test
    fun `a forgotten hub pairs again on the next pick`() {
        val store = controller.relayStore
        store.pinHub(RelayHubPin("hub-1", RelayKeyStore.b64(RelayKeys.generate().publicKey()), "Kitchen tablet", "127.0.0.1", 9, pinnedAtMs = 1))
        store.setSavedHub("hub-1")
        controller.switchToSecondary("127.0.0.1", 9)
        advance(1_000)
        assertEquals("secondary", controller.state.value.role)
        assertTrue(log.any { "SECONDARY → ws://127.0.0.1:9" in it }, "the pinned hub resumed")

        controller.forgetHub("hub-1")
        advance(1_000)
        assertEquals("normal", controller.state.value.role, "its saved hub forgotten, the remote is normal")
        assertTrue(store.hubs.isEmpty())
        assertNull(store.savedHub)

        log.clear()
        controller.switchToSecondary("127.0.0.1", 9)
        advance(200)
        assertTrue(controller.state.value.pairing !is PairingState.Idle, "the next pick pairs")
        assertTrue(log.none { "SECONDARY →" in it }, "never resumes: $log")
    }
}

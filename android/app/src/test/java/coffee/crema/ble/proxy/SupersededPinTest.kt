package coffee.crema.ble.proxy

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import coffee.crema.core.RelayKeys
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.security.FakeKeystore
import coffee.crema.ui.ProxyController
import java.io.File
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
 * Spec §2.10: a hub pin a newer pairing replaced at its address is kept for
 * the user to forget, but superseded. No automatic lookup uses it: a pick's
 * `kid` or address match, the restart dial, the saved-hub fallback. Only the
 * user's explicit Connect dials it, and that hub's Welcome makes it live again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SupersededPinTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lines = CopyOnWriteArrayList<String>()
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val store = RelayKeyStore(Files.createTempDirectory("remote").toFile(), RelayKeyStore.deviceBox(FakeKeystore()))
    private val remoteKeys = (store.load() as RelayKeyLoad.Ready).keys
    private var rig: RelayRig? = null
    private var controller: ProxyController? = null

    @After
    fun tearDown() {
        controller?.close()
        rig?.stop()
        scope.cancel()
    }

    private fun newController(): ProxyController = ProxyController(
        app = app,
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
        relayStore = store,
        commandLane = Dispatchers.Default,
        refindDelayMs = 60_000,
    ).also { controller = it }

    /** A hub that knows this remote, and this remote's superseded pin for it. */
    private fun supersededHub(): Pair<RelayRig, RelayHubPin> {
        val r = RelayRig().start().also { rig = it }
        r.trust.pin(remoteKeys)
        val pin = RelayHubPin(r.hubKeys.deviceId(), RelayKeyStore.b64(r.hubKeys.publicKey()), "Old hub", "127.0.0.1", r.port, pinnedAtMs = 1, superseded = true)
        store.pinHub(pin)
        return r to pin
    }

    @Test
    fun `a superseded pin is never matched by a pick's kid`() {
        val (r, pin) = supersededHub()
        val c = newController()
        c.overridePeers(MutableStateFlow(listOf(Peer("p", "Old hub", "primary", holdsDe1 = true, host = "127.0.0.1", port = r.port, kid = pin.id.take(8)))))
        runBlocking { eventually(what = "the peer listed") { c.state.value.peers.isNotEmpty() } }
        c.switchToSecondary("127.0.0.1", r.port)
        runBlocking { eventually(what = "pairing, not a resume") { c.state.value.pairing !is PairingState.Idle } }
        assertTrue(lines.none { "SECONDARY →" in it }, "never dialed with the superseded key: $lines")
        assertTrue(r.trust.admitted.isEmpty())
    }

    @Test
    fun `a superseded pin is never matched by a pick's address`() {
        val (r, _) = supersededHub()
        val c = newController()
        c.switchToSecondary("127.0.0.1", r.port)
        runBlocking { eventually(what = "pairing, not a resume") { c.state.value.pairing !is PairingState.Idle } }
        assertTrue(lines.none { "SECONDARY →" in it }, "$lines")
        assertTrue(r.trust.admitted.isEmpty())
    }

    @Test
    fun `a superseded saved hub is never dialed at start`() {
        val (r, pin) = supersededHub()
        store.setSavedHub(pin.id)
        File(app.filesDir, "prefs.json").writeText("""{"proxyRole":"secondary","proxyPrimaryHost":"127.0.0.1","proxyPrimaryPort":${r.port}}""")
        val c = newController()
        c.start()
        runBlocking { eventually(what = "the remote role") { c.state.value.role == "secondary" } }
        runBlocking { eventually(what = "not dialing") { lines.any { "Not dialing" in it } } }
        Thread.sleep(500)
        assertTrue(r.trust.admitted.isEmpty(), "the superseded hub got no session")
        assertEquals(0, r.acceptor.sessionCount, "and no connection")
    }

    @Test
    fun `an explicit connect dials a superseded pin and its welcome makes it live`() {
        val (r, pin) = supersededHub()
        val c = newController()
        c.connectToPinnedHub(pin.id)
        runBlocking { eventually(what = "welcomed") { c.state.value.role == "secondary" && c.state.value.hubName.isNotEmpty() } }
        runBlocking { eventually(what = "the flag cleared") { store.hubs.single().superseded.not() } }
        assertEquals(pin.id, store.savedHub, "the hub the user connected through is the saved one")
        assertEquals(listOf(remoteKeys.deviceId()), r.trust.admitted.toList())
    }
}

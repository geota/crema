package coffee.crema.ble.proxy

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.security.SecretBox
import coffee.crema.security.SecretCipher
import coffee.crema.settings.PairedDevice
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.ProxyController
import coffee.crema.ui.VmHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A remote reconnects to its hub while the hub restarts and its prefs (the
 * paired devices) load late. Validation P1b: a hub serving before then
 * couldn't prove the pairing, the remote refused it with HOST_CHANGED, and
 * offered "forget this host", which drops the real pairing. A real remote
 * ([MainViewModel]) against a real hub [ProxyController] with a held-back
 * prefs load, over a real socket. The two share the app's files dir, so one
 * pairing file holds both sides: the hub's id and the secret it issued, and
 * the secret the remote holds for it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HubRestartLoadingTest {

    private val store = ViewModelStore()
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var hub: ProxyController? = null
    private val secret = PairingCrypto.newSecret()
    private val paired = CopyOnWriteArrayList<PairedDevice>()
    private val loaded = CompletableDeferred<Unit>()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        hub?.close()
        hubScope.cancel()
    }

    /** Advance the main looper's clock in steps until [done] holds (or for
     *  [maxMs] when [done] never does), letting socket threads post back. */
    private fun stepped(maxMs: Long, done: () -> Boolean = { false }): Boolean {
        var spent = 0L
        while (spent < maxMs) {
            if (done()) return true
            VmHarness.advance(250)
            Thread.sleep(10)
            spent += 250
        }
        return done()
    }

    /** A remote restored on the hub's saved port, paired and pinned, with
     *  nothing listening yet: the hub is restarting. */
    private fun remote(port: Int): MainViewModel {
        val files = VmHarness.app().filesDir
        File(files, "deviceId").writeText("phone-1")
        File(files, "relayPort").writeText("$port")
        File(files, "proxy-pairing.json").writeText(
            """{"hostId":"host-1","issued":{"phone-1":"$secret"},"held":{"host-1":"$secret"},"pinnedHost":"host-1"}""",
        )
        VmHarness.persistRole("secondary", "127.0.0.1", port)
        val vm = VmHarness.build(store)
        VmHarness.idle()
        // The role and target reach the UI with the prefs load.
        VmHarness.awaitPrefsLoaded(vm)
        return vm
    }

    /** The hub's process comes back: Hub mode, its paired devices known only
     *  once [loaded] completes, and its relay waiting for that unless
     *  [servesWhileLoading] (the hub before the fix). */
    private fun startHub(servesWhileLoading: Boolean) {
        hub = ProxyController(
            app = VmHarness.app(), json = Json { ignoreUnknownKeys = true }, scope = hubScope,
            setCoreReadOnly = {}, setRecorderEnabled = {}, notify = {}, appendLog = {}, connect = {}, disconnect = {},
            disconnectDevices = {}, connectScale = {}, scaleAttachWanted = { false }, de1Connected = { false },
            machineStateName = { null }, machineActions = RecordingMachineActions(), snapshotConfig = { "" }, applyConfig = {},
            roster = { listOf(DeviceInfo("AA:BB", "DE1", "de1", "CONNECTED")) }, isSnapshotChar = { _, _ -> false },
            pairedDevices = { paired.toList() }, rememberPaired = { _, _, _ -> }, onModeApplied = { _, _, _ -> },
            secretBox = SecretBox(object : SecretCipher {
                override fun seal(plain: ByteArray) = ByteArray(12) + plain
                override fun open(sealed: ByteArray) = sealed.copyOfRange(12, sealed.size)
            }),
            prefsReady = { if (!servesWhileLoading) loaded.await() },
        ).also { it.setHubMode(true) }
    }

    /** Wait in real time for the hub's relay to accept connections. */
    private fun awaitListening(port: Int) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 200) } }.isFailure) {
            check(System.nanoTime() < deadline) { "the hub never listened on :$port" }
            Thread.sleep(20)
        }
    }

    private fun finishLoading() {
        paired += PairedDevice("phone-1", "Phone", canControl = true)
        loaded.complete(Unit)
    }

    private fun refusals(vm: MainViewModel) = VmHarness.log(vm).filter { "refused" in it }

    @Test
    fun `a remote reconnecting to a loading hub is admitted with nothing held against it`() {
        val port = ServerSocket(0).use { it.localPort }
        val vm = remote(port)
        startHub(servesWhileLoading = false)

        stepped(4_000)
        assertEquals(emptyList(), refusals(vm), "no refusal while the hub loads")
        assertNull(vm.ui.value.changedHost, "no forget prompt")
        assertEquals("secondary", VmHarness.proxyController(vm).state.value.role)

        finishLoading()
        awaitListening(port)
        assertTrue(stepped(30_000) { !vm.ui.value.mirrorReconnecting }, "admitted once the hub loaded")
        assertEquals(emptyList(), refusals(vm), "no refusal at any point")
        assertNull(vm.ui.value.changedHost)
        assertEquals("secondary", VmHarness.proxyController(vm).state.value.role)
        assertTrue(!vm.ui.value.refindGaveUp)
    }

    /** The remote side on its own: a hub that serves while loading can't
     *  prove the secret yet. An automatic reconnect refuses it but never
     *  offers to forget it, stays a remote, and is admitted after loading. */
    @Test
    fun `an automatic reconnect never offers to forget a hub that can't prove the secret yet`() {
        val port = ServerSocket(0).use { it.localPort }
        val vm = remote(port)
        startHub(servesWhileLoading = true)
        awaitListening(port)

        assertTrue(stepped(30_000) { refusals(vm).isNotEmpty() }, "the loading hub was refused")
        assertNull(vm.ui.value.changedHost, "no forget prompt from an automatic reconnect")
        assertEquals("secondary", VmHarness.proxyController(vm).state.value.role, "not demoted")

        finishLoading()
        assertTrue(stepped(60_000) { !vm.ui.value.mirrorReconnecting }, "admitted after the back-off")
        assertNull(vm.ui.value.changedHost)
        assertEquals("secondary", VmHarness.proxyController(vm).state.value.role)
    }
}

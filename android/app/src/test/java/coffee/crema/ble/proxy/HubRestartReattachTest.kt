package coffee.crema.ble.proxy

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ui.MainViewModel
import coffee.crema.ui.REFIND_DELAY_MS
import coffee.crema.ui.VmHarness
import coffee.crema.ui.remoteStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-10: a hub restarts on a new port while a remote is attached. The remote
 * re-finds it through NSD, unattended, and reattaches with a fresh attach
 * snapshot, without a role change. Two [RelayHub] lifetimes behind real
 * sockets, a real [MainViewModel] as the remote, NSD injected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HubRestartReattachTest {

    private val store = ViewModelStore()
    private val endpoints = mutableListOf<TestHubServer>()
    private val secret = PairingCrypto.newSecret()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        endpoints.forEach { runCatching { it.kill() } }
    }

    /** Advance the main looper's clock by [ms] in steps, letting socket
     *  threads post back between them. */
    private fun advanceStepped(ms: Long, step: Long = 250) {
        var left = ms
        while (left > 0) {
            VmHarness.advance(minOf(step, left))
            Thread.sleep(10)
            left -= step
        }
    }

    private fun lifetime(configSource: () -> String = { "" }, clientId: () -> String): Pair<TestHubServer, RelayHub> =
        TestHubServer.relay("host-1", "Kitchen tablet", secret, clientId = clientId, configSource = configSource)
            .also { endpoints += it.first }

    @Test
    fun `a restarted hub is re-found on its new port and the remote reattaches`() {
        lateinit var vm: MainViewModel
        val clientId = { VmHarness.proxyController(vm).deviceId }
        val (first, firstHub) = lifetime(clientId = clientId)
        // Restored as a remote of the first lifetime, paired and pinned (the
        // secret stored the way a pre-keystore build did: Robolectric has no
        // keystore, and the store still opens it).
        File(VmHarness.app().filesDir, "proxy-pairing.json").writeText(
            """{"hostId":"this-device","held":{"host-1":"$secret"},"pinnedHost":"host-1"}""",
        )
        VmHarness.persistRole("secondary", "127.0.0.1", first.port)
        vm = VmHarness.build(store)
        VmHarness.idle()
        // The role and target reach the UI with the prefs load.
        VmHarness.awaitPrefsLoaded(vm)
        val proxy = VmHarness.proxyController(vm)
        val peers = MutableStateFlow<List<Peer>>(emptyList())
        proxy.overridePeers(peers)
        VmHarness.idleUntil("the first lifetime's welcome") { firstHub.remotes().isNotEmpty() && !vm.ui.value.mirrorReconnecting }
        assertEquals("Connected through Kitchen tablet", vm.ui.value.remoteStatus())

        // The hub restarts: its relay comes back on another port.
        first.kill()
        VmHarness.idleUntil("the remote to notice") { vm.ui.value.mirrorReconnecting }
        assertEquals("Reconnecting to Kitchen tablet…", vm.ui.value.remoteStatus())
        val (second, secondHub) = lifetime(configSource = { """{"grinderModel":"After the restart","primaryName":"Kitchen tablet"}""" }, clientId = clientId)
        peers.value = listOf(Peer("nsd-second-lifetime", "Kitchen tablet", "primary", holdsDe1 = false, host = "127.0.0.1", port = second.port))

        advanceStepped(REFIND_DELAY_MS + 500)
        VmHarness.idleUntil("the second lifetime's welcome") { secondHub.remotes().isNotEmpty() && !vm.ui.value.mirrorReconnecting }
        assertEquals("secondary", proxy.state.value.role, "no role change")
        assertEquals(second.port, vm.ui.value.proxyPrimaryPort, "the new port is the target now")
        assertEquals("host-1", proxy.pairingStore.pinnedHost, "unattended: the pin is unchanged")
        assertTrue(second.auths.isNotEmpty() && second.auths.all { "\"proof\"" in it }, "proved the held secret, never asked to pair: ${second.auths}")
        assertEquals("Connected through Kitchen tablet", vm.ui.value.remoteStatus())

        // The fresh attach: the remote's DE1 attaches to the second lifetime,
        // whose attach snapshot carries its config.
        VmHarness.idleUntil("the reattach") { second.received.any { "\"type\":\"attach\"" in it } }
        VmHarness.idleUntil("the attach snapshot") { vm.ui.value.grinderModel == "After the restart" }
    }
}

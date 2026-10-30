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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    private val hubKeys = coffee.crema.core.RelayKeys.generate()

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

    /** One lifetime of the hub: the same key (and pin for [remote]) each time, a new port. */
    private fun lifetime(remote: coffee.crema.core.RelayKeys, configSource: () -> String = { "" }): Pair<TestHubServer, RelayHub> =
        TestHubServer.relay(remote, "Kitchen tablet", hubKeys, configSource = configSource)
            .also { endpoints += it.first }

    @Test
    fun `a restarted hub is re-found on its new port and the remote reattaches`() {
        val firstConfig = """{"grinderModel":"Before the restart","rev":9}"""
        // Restored as a remote of the first lifetime, paired: each side pinned the other's key.
        val (relayStore, remote) = VmHarness.relayStore()
        val (first, firstHub) = lifetime(remote, configSource = { firstConfig })
        VmHarness.pinHub(relayStore, hubKeys, first.port)
        VmHarness.persistRole("secondary", "127.0.0.1", first.port)
        val vm = VmHarness.build(store)
        VmHarness.idle()
        // The role and target reach the UI with the prefs load.
        VmHarness.awaitPrefsLoaded(vm)
        val proxy = VmHarness.proxyController(vm)
        val peers = MutableStateFlow<List<Peer>>(emptyList())
        proxy.overridePeers(peers)
        VmHarness.idleUntil("the first lifetime's welcome") { firstHub.remotes().isNotEmpty() && !vm.ui.value.mirrorReconnecting }
        firstHub.pushConfig(firstConfig)
        VmHarness.idleUntil("the first lifetime's snapshot") { vm.ui.value.grinderModel == "Before the restart" }
        vm.setQcGrind(14f) // one relayed edit: the slice's generation becomes 1
        VmHarness.idleUntil("the edit settled") { VmHarness.ownState(vm).mirror?.let { it.generation == 1L && it.pending.isEmpty() } == true }
        val slice = assertNotNull(VmHarness.ownState(vm).mirror)
        assertEquals("Connected through Kitchen tablet", vm.ui.value.remoteStatus())

        // The hub restarts: its relay comes back on another port.
        first.kill()
        VmHarness.idleUntil("the remote to notice") { vm.ui.value.mirrorReconnecting }
        // P2 (AC-10's slice clause, AC-26): the last values stay, marked reconnecting.
        assertEquals("Before the restart", vm.ui.value.grinderModel)
        assertTrue(VmHarness.ownState(vm).mirror!!.reconnecting)
        assertEquals("Reconnecting to Kitchen tablet…", vm.ui.value.remoteStatus())
        val (second, secondHub) = lifetime(remote, configSource = { """{"grinderModel":"After the restart","primaryName":"Kitchen tablet","rev":0}""" })
        peers.value = listOf(Peer("nsd-second-lifetime", "Kitchen tablet", "primary", holdsDe1 = false, host = "127.0.0.1", port = second.port))

        advanceStepped(REFIND_DELAY_MS + 500)
        VmHarness.idleUntil("the second lifetime's welcome") { secondHub.remotes().isNotEmpty() && !vm.ui.value.mirrorReconnecting }
        assertEquals("secondary", proxy.state.value.role, "no role change")
        assertEquals(second.port, vm.ui.value.proxyPrimaryPort, "the new port is the target now")
        assertEquals(hubKeys.deviceId(), proxy.relayStore.savedHub, "unattended: the same hub, resumed")
        assertEquals(second.port, proxy.relayStore.hubs.single().port, "the hub's pin follows it to its new port")
        assertTrue(second.trust.requests.isEmpty(), "a re-find never pairs")
        assertEquals("Connected through Kitchen tablet", vm.ui.value.remoteStatus())

        // The fresh attach: the remote's DE1 attaches to the second lifetime,
        // whose attach snapshot carries its config.
        VmHarness.idleUntil("the reattach") { second.received.any { "\"type\":\"attach\"" in it } }
        VmHarness.idleUntil("the attach snapshot") { vm.ui.value.grinderModel == "After the restart" }
        // The slice was kept across the re-find (a recreated one starts at
        // generation 0), and the new lifetime's rev 0, below the 9 applied
        // before the restart, still applied because its Welcome reset it.
        val kept = assertNotNull(VmHarness.ownState(vm).mirror)
        assertEquals(1L, slice.generation)
        assertEquals(slice.generation, kept.generation)
        assertTrue(!kept.reconnecting)
    }
}

package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ble.proxy.PairingState
import coffee.crema.ble.proxy.RelayRig
import coffee.crema.ble.proxy.WsRelayProbe
import coffee.crema.core.RelayKeys
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-39: the hub generated a new key (a reset or reinstall). The remote, still
 * pinned to the old one, stops at `HOST_MISMATCH` before any IK message, says
 * "Couldn't verify ‹hub›…", and after the re-find gives up offers Pair again.
 * It never starts pairing by itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class HubKeyChangedTest {

    private val store = ViewModelStore()
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var rig: RelayRig? = null
    private var probe: WsRelayProbe? = null

    @After
    fun tearDown() {
        watchScope.cancel()
        store.clear()
        VmHarness.idle()
        probe?.stop()
        rig?.stop()
    }

    @Test
    fun `a hub with a new key is never trusted and never paired by itself`() {
        // The hub now runs a new key (and still knows this remote: it doesn't matter).
        val r = RelayRig().start().also { rig = it }
        val p = WsRelayProbe(r.port).start().also { probe = it }
        val (relayStore, remoteKeys) = VmHarness.relayStore()
        r.trust.pin(remoteKeys)
        VmHarness.pinHub(relayStore, RelayKeys.generate(), p.port, name = "Kitchen tablet")
        VmHarness.persistRole("secondary", "127.0.0.1", p.port)
        val vm = VmHarness.build(store)
        val pairing = CopyOnWriteArrayList<PairingState>()
        watchScope.launch { vm.ui.collect { if (it.relayPairing != PairingState.Idle) pairing += it.relayPairing } }
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)

        val unverified = hubUnverified("Kitchen tablet")
        untilClock("the first refusal") { unverified in vm.ui.value.userMessages }
        untilClock("the re-find's give-up") { vm.ui.value.refindGaveUp }
        assertEquals(unverified, vm.ui.value.remoteStatus())
        assertTrue(vm.ui.value.canPairAgain(), "Pair again is offered")
        assertTrue(vm.ui.value.canConnectDirectly(), "and Connect directly")
        assertEquals("secondary", vm.ui.value.proxyRole, "an unverified hub keeps the role")

        val fromRemote = p.captured.filter { it.toHub }
        assertTrue(fromRemote.any { it.text }, "the remote dialed")
        assertTrue(fromRemote.none { !it.text && it.bytes[0] == WsRelayProbe.KIND_NOISE_HS }, "never an IK message: ${fromRemote.map { if (it.text) "text" else "%02x".format(it.bytes[0]) }}")
        assertTrue(r.trust.admitted.isEmpty())
        assertTrue(r.trust.requests.isEmpty(), "no pairing request")
        assertTrue(pairing.isEmpty(), "pairing never started: $pairing")
        assertEquals(listOf("Kitchen tablet"), vm.ui.value.pairedHubs.map { it.name }, "the old pin stays until the user acts")
    }

    private fun untilClock(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (!done()) {
            VmHarness.advance(250)
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(5)
        }
    }
}

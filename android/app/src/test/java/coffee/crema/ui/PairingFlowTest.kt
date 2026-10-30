package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ble.proxy.DeviceInfo
import coffee.crema.ble.proxy.PairingState
import coffee.crema.ble.proxy.RelayKeyStore
import coffee.crema.ble.proxy.RelayRig
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.security.FakeKeystore
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AC-35, AC-36, AC-56: pairing end to end over a real socket. The remote is a
 * real [MainViewModel] (what its code sheet and status line show); the hub is
 * a real [ProxyController] with its own key store (one Robolectric app holds
 * only one view model's files), hosting through its LAN relay.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class PairingFlowTest {

    private val store = ViewModelStore()
    private val hubScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val hubLines = CopyOnWriteArrayList<String>()
    private var hub: ProxyController? = null
    private var rig: RelayRig? = null

    @After
    fun tearDown() {
        watchScope.cancel()
        hub?.close()
        rig?.stop()
        store.clear()
        VmHarness.idle()
        hubScope.cancel()
    }

    private fun hubController(): ProxyController = ProxyController(
        app = VmHarness.app(),
        json = Json { ignoreUnknownKeys = true },
        scope = hubScope,
        setCoreReadOnly = {}, setRecorderEnabled = {},
        notify = { hubLines += "notify: $it" },
        appendLog = { hubLines += "log: $it" },
        connect = {}, disconnect = {}, disconnectDevices = {}, connectScale = {}, scaleAttachWanted = { false },
        de1Connected = { false }, machineStateName = { null }, machineActions = RecordingMachineActions(),
        snapshotConfig = { "" }, applyConfig = {},
        roster = { listOf(DeviceInfo("AA:BB", "DE1", "de1", "CONNECTED")) }, isSnapshotChar = { _, _ -> false },
        onModeApplied = { _, _, _ -> },
        relayStore = RelayKeyStore(Files.createTempDirectory("hub").toFile(), RelayKeyStore.deviceBox(FakeKeystore())),
        commandLane = Dispatchers.Default,
        refindDelayMs = 60_000,
    ).also { hub = it }

    /** A hub listening, its window open; returns its relay port. */
    private fun openHub(h: ProxyController): Int {
        h.setHubMode(true)
        waitFor("the hub listening") { hubLines.any { "LAN relay listening" in it } }
        h.openPairingWindow()
        return hubLines.first { "LAN relay listening" in it }.substringAfterLast(":").toInt()
    }

    private fun remote(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        return vm
    }

    /** Every pairing state and status line the remote shows, in order. */
    private class Seen {
        val states = CopyOnWriteArrayList<PairingState>()
        val statuses = CopyOnWriteArrayList<String>()
    }

    private fun watch(vm: MainViewModel): Seen {
        val seen = Seen()
        watchScope.launch {
            vm.ui.collect { ui ->
                if (seen.states.lastOrNull() != ui.relayPairing) seen.states += ui.relayPairing
                ui.remoteStatus()?.let { if (seen.statuses.lastOrNull() != it) seen.statuses += it }
            }
        }
        return seen
    }

    private fun shownCode(vm: MainViewModel, attempt: Int): PairingState.ShowCode {
        waitFor("code attempt $attempt") { (vm.ui.value.relayPairing as? PairingState.ShowCode)?.attempt == attempt }
        return vm.ui.value.relayPairing as PairingState.ShowCode
    }

    /** AC-35 and AC-56: no code before AWAITING_CODE, then the code with its
     *  countdown; the hub's dialog names the remote; the right code with View
     *  only pairs; both say so; the remote resumes and says "Connected
     *  through" only once the hub welcomed it. */
    @Test
    fun `the right code pairs with the chosen scope and the remote connects through the hub`() {
        val h = hubController()
        val port = openHub(h)
        val vm = remote()
        dump = remoteDump(vm)
        val seen = watch(vm)

        vm.switchToSecondary("127.0.0.1", port)
        val code = shownCode(vm, attempt = 1)
        val firstShown = seen.states.indexOfFirst { it is PairingState.ShowCode }
        assertTrue(seen.states.take(firstShown).any { it is PairingState.Waiting }, "Waiting (no code) before the code: ${seen.states}")
        assertEquals(6, code.code.length)
        val left = code.expiresAtMs - System.currentTimeMillis()
        assertTrue(left in 1..120_000, "a countdown of at most 2 minutes: $left")

        waitFor("the hub's code dialog") { h.state.value.pendingPairing != null }
        val prompt = h.state.value.pendingPairing!!
        assertEquals(deviceLabel(), prompt.clientName, "the dialog names the remote")
        assertTrue(!prompt.lastWasWrong)

        h.submitPairingCode(code.code, canControl = false)
        waitFor("the remote's success") { pairedWith(code.hubName) in vm.ui.value.userMessages }
        assertTrue(hubLines.any { it == "notify: ${pairedRemote(deviceLabel(), canControl = false)}" }, "$hubLines")
        untilClock("connected through the hub") { vm.ui.value.remoteStatus()?.startsWith("Connected through") == true }
        assertEquals("Connected through ${code.hubName} · view-only", vm.ui.value.remoteStatus())
        val connecting = seen.statuses.indexOfFirst { it.startsWith("Connecting through") }
        val connected = seen.statuses.indexOfFirst { it.startsWith("Connected through") }
        assertTrue(connecting in 0 until connected, "Connecting through before the Welcome, then Connected: ${seen.statuses}")
        assertEquals(PairingState.Idle, vm.ui.value.relayPairing)
        assertEquals(listOf(code.hubName), vm.ui.value.pairedHubs.map { it.name })
    }

    /** AC-36: a wrong code gives a new code on the remote and "(2 of 3)" on
     *  the hub; the third pauses pairing for a minute on the hub, and the
     *  remote stops with Try again. */
    @Test
    fun `three wrong codes pause pairing and the remote stops with try again`() {
        val h = hubController()
        val port = openHub(h)
        val vm = remote()
        dump = remoteDump(vm)
        vm.switchToSecondary("127.0.0.1", port)

        var code = shownCode(vm, attempt = 1)
        waitFor("the first request") { h.state.value.pendingPairing != null }
        h.submitPairingCode(wrong(code.code), canControl = true)

        code = shownCode(vm, attempt = 2)
        assertTrue(code.retryAfterWrongCode, "the remote says the code didn't work")
        waitFor("the hub's second request") { h.state.value.pendingPairing?.lastWasWrong == true }
        val second = h.state.value.pendingPairing!!
        assertTrue(wrongCodeLine(second.clientName, second.wrongCodes + 1).endsWith("(2 of 3)"), "$second")
        h.submitPairingCode(wrong(code.code), canControl = true)

        code = shownCode(vm, attempt = 3)
        waitFor("the hub's third request") { h.state.value.pendingPairing?.wrongCodes == 2 }
        h.submitPairingCode(wrong(code.code), canControl = true)

        waitFor("the lockout") { h.state.value.pairingLockedSecondsLeft > 0 }
        assertTrue(hubLines.any { it == "notify: $LOCKED_OUT_COPY" }, "$hubLines")
        assertTrue(h.state.value.pairingLockedSecondsLeft in 50..60, "${h.state.value.pairingLockedSecondsLeft}")
        assertTrue(pairWindowTitle(h.state.value.pairingSecondsLeft, h.state.value.pairingLockedSecondsLeft).startsWith("Pairing paused · "))
        waitFor("the remote's final failure") { vm.ui.value.relayPairing is PairingState.Failed }
        assertTrue(vm.ui.value.pairedHubs.isEmpty())
    }

    /** §6.3, §2.10: the hub's key changed (reset or reinstalled, or another
     *  hub at that address). Pair again pairs with the saved target and says
     *  the key changed; the new hub is the saved one, and the old pin stays
     *  as an entry the user can forget. */
    @Test
    fun `pair again after a key change keeps the old pin and says so`() {
        val h = hubController()
        val port = openHub(h)
        val (relayStore, _) = VmHarness.relayStore()
        VmHarness.pinHub(relayStore, coffee.crema.core.RelayKeys.generate(), port, name = deviceLabel())
        VmHarness.persistRole("secondary", "127.0.0.1", port)
        val vm = remote()
        dump = remoteDump(vm)
        val oldPin = vm.ui.value.pairedHubs.single().id
        // The hub doesn't hold the pinned key: the re-find gives up, and Pair
        // again is offered (it never pairs by itself).
        untilClock("the re-find's give-up") { vm.ui.value.canPairAgain() }

        vm.pairAgain()
        val code = shownCode(vm, attempt = 1)
        waitFor("the hub's code dialog") { h.state.value.pendingPairing != null }
        h.submitPairingCode(code.code, canControl = true)
        try {
            untilClock("the key-changed line") { hubKeyChanged(code.hubName) in vm.ui.value.userMessages }
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message}; messages ${vm.ui.value.userMessages.takeLast(5)}, pairing ${vm.ui.value.relayPairing}, hub ${hubLines.takeLast(5)}", e)
        }
        val pins = vm.ui.value.pairedHubs
        assertEquals(2, pins.size, "$pins")
        assertTrue(pins.any { it.id == oldPin }, "the old pin stays, to forget")
        val newPin = pins.single { it.id != oldPin }
        assertEquals(newPin.id, VmHarness.proxyController(vm).relayStore.savedHub, "the new hub is the saved one")
        untilClock("connected through the hub") { vm.ui.value.remoteStatus() == "Connected through ${code.hubName}" }
    }

    /** AC-56: a hub that answers IK #2 with `CLOSE(NOT_PAIRED)` never makes
     *  the remote say "Connected through"; it says the hub removed it. */
    @Test
    fun `a hub that doesn't know the remote never shows connected through`() {
        val r = RelayRig().start().also { rig = it }
        val (relayStore, _) = VmHarness.relayStore()
        VmHarness.pinHub(relayStore, r.hubKeys, r.port)
        VmHarness.persistRole("secondary", "127.0.0.1", r.port)
        val vm = remote()
        dump = remoteDump(vm)
        val seen = watch(vm)
        untilClock("the refusal") { vm.ui.value.userMessages.any { "removed this device" in it } }
        untilClock("normal") { vm.ui.value.proxyRole == "normal" }
        assertTrue(seen.statuses.none { it.startsWith("Connected through") }, "${seen.statuses}")
    }

    /** Wait on [done] while Main's virtual clock moves too: a mode switch
     *  waits out short delays (the radio settle) on it. */
    private fun untilClock(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!done()) {
            VmHarness.advance(50)
            check(System.nanoTime() < deadline) { "Timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    /** What [waitFor] prints when it times out: both sides' state and logs. */
    @Volatile private var dump: () -> String = { "" }

    /** [VmHarness.idleUntil], saying where it stuck. */
    private fun waitFor(what: String, done: () -> Boolean) {
        try {
            VmHarness.idleUntil(what, done = done)
        } catch (e: IllegalStateException) {
            throw IllegalStateException("${e.message}\n${dump()}\nhub:\n${hubLines.joinToString("\n")}", e)
        }
    }

    private fun remoteDump(vm: MainViewModel): () -> String = {
        val hubState = hub?.state?.value
        "remote pairing ${vm.ui.value.relayPairing}, role ${vm.ui.value.proxyRole}; hub prompt ${hubState?.pendingPairing}, " +
            "locked ${hubState?.pairingLockedSecondsLeft}\nremote log:\n${VmHarness.log(vm).take(30).joinToString("\n")}"
    }

    private fun wrong(code: String): String = code.map { c -> '0' + ((c - '0' + 1) % 10) }.joinToString("")
}

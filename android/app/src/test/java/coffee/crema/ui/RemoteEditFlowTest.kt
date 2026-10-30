package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ble.proxy.Frame
import coffee.crema.ble.proxy.Golden
import coffee.crema.ble.proxy.InMemoryFrameLink
import coffee.crema.ble.proxy.PROXY_PROTOCOL_VERSION
import coffee.crema.ble.proxy.PairingCrypto
import coffee.crema.ble.proxy.Peer
import coffee.crema.machine.LocalMachine
import coffee.crema.machine.CommandOrigin
import coffee.crema.machine.CommandOutcome
import coffee.crema.machine.RecordingMachineActions
import coffee.crema.machine.RelayedCommandDispatcher
import coffee.crema.settings.ConfigSnapshot
import coffee.crema.settings.OnRemote
import coffee.crema.settings.Setting
import coffee.crema.settings.SettingGroup
import coffee.crema.ui.RemoteFixture.Companion.P
import coffee.crema.ui.RemoteFixture.Companion.S
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberFunctions
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.valueParameters
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Edits on a remote (spec §7.3): what is relayed, what is read-only, and
 *  what a view-only remote does. A real remote against a real hub. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteEditFlowTest {

    private val store = ViewModelStore()
    private val fixture = RemoteFixture(store)

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        fixture.kill()
    }

    /** A sample argument for each read-only setter, chosen to pass its own
     *  validation, so a refusal can only come from the remote gate. */
    private val samples: Map<String, List<Any?>> = mapOf(
        "setSteamEco" to listOf(true), "setSteamTwoTap" to listOf(true), "setFanThreshold" to listOf(58f),
        "setWaterRefillPointMm" to listOf(8f), "setHeaterVoltage" to listOf(230), "setFlowMultiplier" to listOf(1.1f),
        "setLineFrequency" to listOf(50f), "setCupWarmerTemp" to listOf(60), "setScaleVolume" to listOf(3),
        "setScaleStandbyMinutes" to listOf(10), "setScaleFlowSmoothing" to listOf(true), "setScaleAntiMistouch" to listOf(true),
        "setScaleMode" to listOf(1), "setScaleAutoStop" to listOf(1), "setMaxShotDuration" to listOf(41f),
        "setVolumeStopWithScale" to listOf(true), "setRequireScale" to listOf(true), "setPreFlush" to listOf(true),
        "setSteamPurge" to listOf(true), "setIdleSleepMin" to listOf(60), "setGrinderModel" to listOf("Niche"),
        "setQcSteamTemp" to listOf(150f), "setQcSteamTime" to listOf(30f), "setQcSteamFlow" to listOf(1.6f),
        "setQcHotWaterTemp" to listOf(85f), "setQcHotWaterVolume" to listOf(200f), "setQcFlushTime" to listOf(6f),
        "setQcFlushTemp" to listOf(93f),
    )

    private fun call(vm: MainViewModel, signature: String) {
        val name = signature.substringAfter('.').substringBefore('(')
        val types = signature.substringAfter('(').substringBefore(')').split(", ").filter { it.isNotEmpty() }
        val f = (MainViewModel::class as KClass<*>).declaredMemberFunctions.single { fn ->
            fn.name == name && fn.valueParameters.map { p -> (p.type.classifier as KClass<*>).simpleName + if (p.type.isMarkedNullable) "?" else "" } == types
        }
        f.call(vm, *samples.getValue(name).toTypedArray())
    }

    /** AC-23: on a remote, every READ_ONLY machine and session setter (from
     *  the registry) changes neither prefs.json nor the screen, sends no
     *  control, and says where to change it. */
    @Test
    fun `read-only settings`() {
        val vm = fixture.start()
        fixture.push()
        val readOnly = Setting.entries.filter { it.group != SettingGroup.C_PERSONAL && it.onRemote == OnRemote.READ_ONLY }
        val prefsBefore = abOf(savedPrefs())
        for (setting in readOnly) for (setter in setting.setters) {
            val shown = vm.ui.value
            val messages = vm.ui.value.userMessages.size
            call(vm, setter)
            VmHarness.idle()
            assertEquals(settingsShown(shown), settingsShown(vm.ui.value), "$setter changed the screen")
            assertTrue(vm.ui.value.userMessages.drop(messages).any { it == "Change on Kitchen tablet" }, "$setter: ${vm.ui.value.userMessages.drop(messages)}")
        }
        vm.tareScale() // a control that is sent, so the wire has caught up
        VmHarness.idleUntil("the tare") { fixture.handled.isNotEmpty() }
        assertEquals(listOf("tareScale" to ""), fixture.handled.toList(), "no control for a read-only setting")
        assertEquals(prefsBefore, abOf(savedPrefs()))
        assertEquals(abOf(P), abOf(savedPrefs()))
    }

    /** Every machine and session setting as the screen shows it. */
    private fun settingsShown(ui: MainUiState): Map<String, Any?> {
        val props = MainUiState::class.memberProperties.associateBy { it.name }
        return Setting.entries.filter { it.group != SettingGroup.C_PERSONAL }.mapNotNull { it.uiField }.plus("lineFreqHz")
            .distinct().associateWith { props.getValue(it).get(ui) }
    }

    /** AC-23: a view-only remote sends nothing at all, Stop included. */
    @Test
    fun `view-only sends nothing`() {
        fixture.viewOnly = true
        val vm = fixture.start()
        VmHarness.idleUntil("view-only") { vm.ui.value.mirrorViewOnly }
        vm.stopShot()
        vm.setStopOnWeight(true)
        vm.setQcGrind(14f)
        vm.startShot()
        VmHarness.idle()
        Thread.sleep(200)
        VmHarness.idle()
        assertTrue(fixture.endpoint.received.none { "\"type\":\"control\"" in it }, "nothing on the wire")
        assertTrue(vm.ui.value.userMessages.count { it == "View only — Kitchen tablet hasn’t allowed changes from this device" } >= 4, "${vm.ui.value.userMessages}")
    }

    /** S with the relayed values the golden samples set changed away from them. */
    private val base = S.copy(stopOnWeight = false, autoTare = true, qcGrind = 15f, brewDoseG = 20.0, brewYieldG = 40.0, brewTempC = 94.0, brewPreinfuseS = 6.0, rev = 1)

    /** A relayed edit: its golden sample, its setting, and how the screen shows it. */
    private class Edit(val fixture: String, val setting: Setting, val edited: Any?, val shown: (MainUiState) -> Any?)

    private val edits = listOf(
        Edit("setStopOnWeight", Setting.STOP_ON_WEIGHT, true) { it.stopOnWeight },
        Edit("setAutoTare", Setting.AUTO_TARE, false) { it.autoTare },
        Edit("quickAdjustBrew", Setting.BREW_PARAMS, BrewParams(18.0, 36.0, 93.0, 8.0)) { it.brewParams },
        Edit("setQcGrind", Setting.QC_GRIND, 14.5f) { it.qcGrind },
    )

    private fun controls(): List<String> = fixture.endpoint.received.filter { "\"type\":\"control\"" in it }

    private fun availability(vm: MainViewModel, s: Setting) = vm.ui.value.availability(s)

    /** Advance the main looper's clock in steps, letting socket and IO
     *  threads post back, until [done] holds. */
    private fun untilClock(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            VmHarness.advance(250)
            Thread.sleep(10)
        }
    }

    /** AC-21: each accepted relayed edit puts its golden frame on the wire,
     *  shows PENDING with the edited value while the hub works, then VIA_HUB
     *  with the hub's value (pushed before the reply). prefs.json never changes. */
    @Test
    fun `accepted relayed edits`() {
        val vm = fixture.start()
        fixture.current = base
        fixture.push(base)
        fixture.echoEdits = true
        for (e in edits) {
            val sample = RELAYED_VERB_SAMPLES.single { it.fixture == e.fixture }
            val sent = controls().size
            val handled = fixture.handled.size
            fixture.holdReplies = true
            sample.send!!(vm)
            VmHarness.idleUntil("${e.fixture} at the hub") { fixture.handled.size > handled }
            assertEquals(sent + 1, controls().size)
            assertEquals(Golden.require("control/${e.fixture}.json"), controls().last().replaceFirst(Regex("\"id\":\\d+"), "\"id\":1"))
            assertEquals(SettingAvailability.PENDING, availability(vm, e.setting), e.fixture)
            assertEquals(e.edited, e.shown(vm.ui.value), "${e.fixture}: the edited value while pending")
            fixture.holdReplies = false
            VmHarness.idleUntil("${e.fixture} settled") { availability(vm, e.setting) == SettingAvailability.VIA_HUB }
            val slice = assertNotNull(VmHarness.ownState(vm).mirror)
            assertTrue(slice.pending.isEmpty() && slice.unconfirmed.isEmpty(), "${e.fixture}: nothing left pending")
            assertEquals(fixture.current, slice.snapshot, "${e.fixture}: the hub's snapshot, pushed before the reply")
            assertEquals(e.edited, e.shown(vm.ui.value), "${e.fixture}: the hub's value")
        }
        assertEquals(abOf(P), abOf(savedPrefs()), "prefs.json is the remote's own")
    }

    /** AC-22: a refused edit returns to the hub's value, with one message. */
    @Test
    fun `refused`() {
        val vm = fixture.start()
        fixture.push(base)
        fixture.refused += "setAutoTare"
        val messages = vm.ui.value.userMessages.size
        vm.setAutoTare(false)
        VmHarness.idleUntil("the refusal") { VmHarness.ownState(vm).mirror?.pending?.isEmpty() == true && vm.ui.value.userMessages.size > messages }
        VmHarness.idle()
        assertEquals(true, vm.ui.value.autoTare, "the hub's value again")
        assertEquals(SettingAvailability.VIA_HUB, availability(vm, Setting.AUTO_TARE))
        assertEquals(listOf("refused by the hub — change reverted"), vm.ui.value.userMessages.drop(messages))
        assertEquals(abOf(P), abOf(savedPrefs()))
    }

    /** AC-22: an edit the hub never answers keeps its value, UNCONFIRMED,
     *  until the next snapshot shows the hub's value. */
    @Test
    fun `unknown`() {
        val vm = fixture.start()
        fixture.push(base)
        fixture.holdReplies = true
        vm.setQcGrind(14.5f)
        VmHarness.idleUntil("the edit at the hub") { fixture.handled.isNotEmpty() }
        untilClock("the request to time out") { availability(vm, Setting.QC_GRIND) == SettingAvailability.UNCONFIRMED }
        assertEquals(14.5f, vm.ui.value.qcGrind, "kept on screen")
        assertEquals("Kitchen tablet didn’t answer — this may not have been applied", vm.ui.value.availabilityNote(Setting.QC_GRIND))
        fixture.holdReplies = false
        fixture.push(base.copy(qcGrind = 12f, rev = 2))
        assertEquals(12f, vm.ui.value.qcGrind, "the next snapshot shows the hub's value")
        assertEquals(SettingAvailability.VIA_HUB, availability(vm, Setting.QC_GRIND))
        assertEquals(1, fixture.handled.count { it.first == "setQcGrind" }, "never resent")
        assertEquals(abOf(P), abOf(savedPrefs()))
    }

    /** AC-22: the link drops with an edit pending. The edit turns unconfirmed
     *  (the row is offline while the link redials), is never resent to the
     *  restarted hub, and its snapshot shows the hub's value. */
    @Test
    fun `reconnect mid-edit`() {
        fixture.peers = MutableStateFlow(emptyList<Peer>())
        val vm = fixture.start()
        fixture.push(base)
        fixture.holdReplies = true
        vm.setStopOnWeight(true)
        VmHarness.idleUntil("the edit at the hub") { fixture.handled.isNotEmpty() }
        assertEquals(SettingAvailability.PENDING, availability(vm, Setting.STOP_ON_WEIGHT))
        fixture.holdReplies = false
        fixture.hubConfig = coreJson.encodeToString(ConfigSnapshot.serializer(), base.copy(rev = 0))
        fixture.restart()
        VmHarness.idleUntil("the remote to notice") { vm.ui.value.mirrorReconnecting }
        val slice = assertNotNull(VmHarness.ownState(vm).mirror)
        assertEquals(setOf(Setting.STOP_ON_WEIGHT), slice.unconfirmed, "pending became unconfirmed")
        assertEquals(true, vm.ui.value.stopOnWeight, "kept on screen")
        assertEquals(SettingAvailability.OFFLINE, availability(vm, Setting.STOP_ON_WEIGHT))
        untilClock("the reattach") { fixture.hub.remotes().isNotEmpty() && !vm.ui.value.mirrorReconnecting }
        VmHarness.idleUntil("the attach snapshot") { VmHarness.ownState(vm).mirror?.unconfirmed?.isEmpty() == true }
        assertEquals(false, vm.ui.value.stopOnWeight, "the restarted hub's value")
        assertEquals(SettingAvailability.VIA_HUB, availability(vm, Setting.STOP_ON_WEIGHT))
        assertTrue(fixture.endpoint.received.none { "\"type\":\"control\"" in it }, "never resent to the restarted hub")
        assertEquals(abOf(P), abOf(savedPrefs()))
    }

    /** AC-27: two remotes edit the same key at once. The final value is the
     *  last one the hub processed, on both, and neither keeps a pending value.
     *  The second remote is a raw session with its own client id. */
    @Test
    fun `two remotes`() {
        val vm = fixture.start()
        fixture.current = base
        fixture.push(base)
        fixture.echoEdits = true
        val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val (client, server) = InMemoryFrameLink.pair()
            io.launch { fixture.hub.serve(server) }
            val rx = CopyOnWriteArrayList<Frame>()
            io.launch { client.incoming().collect { rx += it } }
            runBlocking {
                val nonce = PairingCrypto.newNonce()
                client.send(Frame.Hello(PROXY_PROTOCOL_VERSION, "secondary", "remote-2", "Second phone", nonce = nonce))
                val challenge = withTimeout(5_000) { while (rx.none { it is Frame.Challenge }) delay(5); rx.first { it is Frame.Challenge } as Frame.Challenge }
                client.send(Frame.Auth(PairingCrypto.clientProof(fixture.secret, "host-1", "remote-2", nonce, challenge.nonce)))
                withTimeout(5_000) { while (rx.none { it is Frame.Welcome }) delay(5) }
            }
            fixture.holdReplies = true
            vm.setQcGrind(14.5f)
            runBlocking { client.send(Frame.Control(1, "setQcGrind", "16.0")) }
            VmHarness.idleUntil("both edits at the hub") { fixture.handled.size == 2 }
            fixture.holdReplies = false
            runBlocking { withTimeout(5_000) { while (rx.none { it is Frame.ControlOk }) delay(5) } }
            VmHarness.idleUntil("the remote's edit settled") { VmHarness.ownState(vm).mirror?.pending?.isEmpty() == true }
            VmHarness.idleUntil("the last push") { VmHarness.ownState(vm).mirror?.snapshot == fixture.current }
            assertEquals(2, fixture.echoed.size)
            val last = fixture.echoed.last().second.toFloat()
            assertEquals(last, fixture.current.qcGrind, "the hub's value is the last edit it processed")
            assertEquals(last, vm.ui.value.qcGrind, "this remote shows it")
            assertEquals(SettingAvailability.VIA_HUB, availability(vm, Setting.QC_GRIND), "no stale pending value")
            val secondSees = rx.filterIsInstance<Frame.Config>().last().json
            assertEquals(fixture.current, coreJson.decodeFromString(ConfigSnapshot.serializer(), secondSees), "the other remote shows it too")
        } finally {
            io.cancel()
        }
    }

    /** AC-27: a relayed setting edit that reaches a hub still loading its
     *  prefs is refused with "starting up"; the remote shows the hub's value. */
    @Test
    fun `hub starting`() {
        val actions = RecordingMachineActions().apply { configLoaded = false }
        val dispatcher = RelayedCommandDispatcher(
            machine = LocalMachine(actions, notify = {}, hostName = { "Kitchen tablet" }),
            hostName = { "Kitchen tablet" },
            machineIdle = { true },
            announce = { _, _ -> },
            log = {},
            lane = Dispatchers.Unconfined,
        )
        fixture.dispatch = { method, args ->
            when (val outcome = dispatcher.dispatch(method, args, CommandOrigin("remote", "Phone"))) {
                CommandOutcome.Accepted -> Result.success(Unit)
                is CommandOutcome.Refused -> Result.failure(IllegalStateException(outcome.reason))
                is CommandOutcome.Unknown -> Result.failure(IllegalStateException(outcome.detail))
            }
        }
        val vm = fixture.start()
        fixture.push(base)
        val messages = vm.ui.value.userMessages.size
        vm.setStopOnWeight(true)
        VmHarness.idleUntil("the refusal") { vm.ui.value.userMessages.size > messages }
        VmHarness.idleUntil("the edit dropped") { VmHarness.ownState(vm).mirror?.pending?.isEmpty() == true }
        assertEquals(false, vm.ui.value.stopOnWeight, "the hub's value")
        assertTrue(vm.ui.value.userMessages.drop(messages).single().contains("Kitchen tablet is starting up"), "${vm.ui.value.userMessages.drop(messages)}")
        assertTrue(actions.applied.isEmpty(), "the hub applied nothing")
    }

    /** AC-35: the seven QC steam, hot-water and flush settings are READ_ONLY
     *  on a control remote; their setters send nothing and change nothing. */
    @Test
    fun `QC steam, water and flush rows are read-only`() {
        val vm = fixture.start()
        fixture.push(base)
        val qc = listOf(
            Setting.QC_STEAM_TIME, Setting.QC_STEAM_FLOW, Setting.QC_STEAM_TEMP, Setting.QC_HOT_WATER_TEMP,
            Setting.QC_HOT_WATER_VOLUME, Setting.QC_FLUSH_TIME, Setting.QC_FLUSH_TEMP,
        )
        for (s in qc) {
            assertEquals(SettingAvailability.READ_ONLY, availability(vm, s), "$s")
            assertEquals(false, vm.ui.value.gate(s).editable, "$s locks its control")
            val shown = settingsShown(vm.ui.value)
            for (setter in s.setters) call(vm, setter)
            VmHarness.idle()
            assertEquals(shown, settingsShown(vm.ui.value), "$s changed the screen")
        }
        vm.tareScale()
        VmHarness.idleUntil("the tare") { fixture.handled.isNotEmpty() }
        assertEquals(listOf("tareScale" to ""), fixture.handled.toList(), "no control for a QC machine setting")
        assertEquals(abOf(P), abOf(savedPrefs()))
    }
}

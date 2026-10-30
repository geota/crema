package coffee.crema.ui

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import coffee.crema.ble.proxy.Peer
import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.ConfigSnapshot
import coffee.crema.ui.RemoteFixture.Companion.BEAN_S
import coffee.crema.ui.RemoteFixture.Companion.P
import coffee.crema.ui.RemoteFixture.Companion.S
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Remote → standalone (T6, spec §7.5): the confirmation, adoption and the
 *  serialized commit. A real remote against a real hub. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteToStandaloneTest {

    private val store = ViewModelStore()
    private val fixture = RemoteFixture(store)

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        fixture.kill()
    }

    /** Mode switches the controller ran, by role, and prefs.json as each began. */
    private val switches = CopyOnWriteArrayList<Pair<String, AppPrefs>>()

    /** Whether the custom profiles on disk held the hub's profile as each switch began. */
    private val customOnDisk = CopyOnWriteArrayList<Boolean>()

    private fun role(vm: MainViewModel) = VmHarness.proxyController(vm).state.value.role

    /** Wait for the role, moving the virtual clock (the switch's settle
     *  delay) while the commit's saves finish on their real threads; the
     *  VM's log goes in a failure. */
    private fun waitRole(vm: MainViewModel, expected: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (role(vm) != expected) {
            if (System.nanoTime() > deadline) {
                throw AssertionError("still ${role(vm)}; log: ${VmHarness.log(vm).take(25).joinToString(" | ")}")
            }
            VmHarness.advance(50)
            Thread.sleep(5)
        }
    }

    /** A remote with P that received S (with a custom profile) and has an
     *  edit still pending at the hub. */
    private fun remoteWithPendingEdit(): MainViewModel {
        val vm = fixture.start()
        val profile = vm.ui.value.profiles.first { it.id == RemoteFixture.PROFILE_S }
        // A full profile, as a hub sends it (every field, defaults included).
        val custom = kotlinx.serialization.json.Json(coreJson) { encodeDefaults = true }
            .encodeToString(coffee.crema.profiles.CremaProfile.serializer(), profile.copy(id = "custom-on-the-hub", name = "Hub custom", source = "custom"))
        fixture.push(S.copy(activeProfileId = "custom-on-the-hub", activeProfileJson = custom))
        fixture.holdReplies = true
        vm.setQcGrind(18f) // pending: never answered
        VmHarness.idleUntil("the edit at the hub") { fixture.handled.isNotEmpty() }
        assertEquals(18f, vm.ui.value.qcGrind)
        VmHarness.proxyController(vm).modeSwitchProbe = { r ->
            switches += r to savedPrefs()
            customOnDisk += File(VmHarness.app().filesDir, "customProfiles.json").let { it.exists() && "custom-on-the-hub" in it.readText() }
        }
        return vm
    }

    private fun assertAdoptedS(vm: MainViewModel, next: String) {
        val saved = savedPrefs()
        val s = S.copy(activeProfileId = "custom-on-the-hub")
        val expected = abOf(
            P.copy(
                maxShotDurationS = s.maxShotDurationS, autoTare = s.autoTare, stopOnWeight = s.stopOnWeight,
                volumeStopWithScale = s.volumeStopWithScale!!, requireScale = s.requireScale!!, steamEco = s.steamEco!!,
                steamTwoTap = s.steamTwoTap!!, fanThresholdC = s.fanThresholdC!!, preFlush = s.preFlush!!, steamPurge = s.steamPurge!!,
                idleSleepMin = s.idleSleepMin, grinderModel = s.grinderModel, qcSteamTimeS = s.qcSteamTimeS,
                qcSteamFlowMlS = s.qcSteamFlowMlS, qcSteamTempC = s.qcSteamTempC, qcHotWaterTempC = s.qcHotWaterTempC,
                qcHotWaterVolumeMl = s.qcHotWaterVolumeMl, qcFlushTimeS = s.qcFlushTimeS, qcFlushTempC = s.qcFlushTempC,
                qcGrind = s.qcGrind, activeProfileId = s.activeProfileId, waterRefillPointMm = s.waterRefillPointMm,
            ),
        )
        assertEquals(expected, abOf(saved), "own A1–A11 and B1–B13 are S's (the snapshot, not the pending 18)")
        val own = VmHarness.ownState(vm)
        assertEquals(BEAN_S, own.activeBeanId, "the bean is in this library")
        assertEquals(BrewParams(20.0, 40.0, 94.0, 6.0), own.brewParams)
        assertTrue(own.profiles.any { it.id == "custom-on-the-hub" }, "the hub's custom profile is in the library now")
        // The switch saw the adopted prefs on disk when it began (I3).
        val (switchedTo, atSwitch) = switches.single()
        assertEquals(next, switchedTo)
        assertEquals(expected, abOf(atSwitch))
        assertEquals(listOf(true), customOnDisk.toList(), "the adopted custom profile was on disk before the switch")
        // And the first sweep after it writes S's machine values (A only;
        // the tank temperature and battery are inputs, not settings).
        assertEquals(
            buildConnectSweepSettings(58f, true, 8f, 150f, 30f, 85f, 200f, 1.6f, 6f, 93f, true, own.usbChargingMode, 0f, null),
            own.connectSweepSettings(0f, null),
        )
    }

    /** AC-24: Stop adopts on confirm. */
    @Test
    fun `stop adopts the hub's settings on confirm`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        val prompt = assertNotNull(vm.ui.value.adoptPrompt)
        assertEquals("Kitchen tablet", prompt.hubName)
        assertTrue(VmHarness.ownState(vm).mirror!!.frozen)
        assertEquals(S.qcSteamTempC, vm.ui.value.qcSteamTempC, "the screen keeps showing S while it asks")
        vm.setThemeMode("dark") // a save launched just before the commit
        vm.resolveAdoption(useHub = true)
        VmHarness.advance(200)
        waitRole(vm, "normal")
        VmHarness.idle()
        assertAdoptedS(vm, "normal")
        assertEquals("dark", savedPrefs().themeMode, "the earlier save didn't land after the adoption save")
    }

    /** AC-24: Connect directly adopts on confirm. */
    @Test
    fun `connect directly adopts the hub's settings on confirm`() {
        val vm = remoteWithPendingEdit()
        vm.connectDirectly()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        vm.resolveAdoption(useHub = true)
        VmHarness.advance(200)
        waitRole(vm, "normal")
        VmHarness.idle()
        assertAdoptedS(vm, "normal")
    }

    /** AC-24: Hub mode on with the hub gone adopts on confirm, then becomes the hub. */
    @Test
    fun `hub mode on with the hub gone adopts the hub's settings on confirm`() {
        val vm = remoteWithPendingEdit()
        fixture.kill()
        VmHarness.idleUntil("the hub gone") { vm.ui.value.mirrorReconnecting }
        vm.setHubMode(true)
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        vm.resolveAdoption(useHub = true)
        VmHarness.advance(200)
        waitRole(vm, "primary")
        VmHarness.idle()
        assertAdoptedS(vm, "primary")
    }

    /** AC-25: Keep mine (and dismissing, which is the same call) leaves own == P. */
    @Test
    fun `keep mine leaves this device's own settings`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        vm.resolveAdoption(useHub = false)
        VmHarness.advance(200)
        waitRole(vm, "normal")
        assertEquals(abOf(P), abOf(savedPrefs()))
        assertEquals(1, switches.size)
    }

    /** AC-25: no confirmation without a snapshot, or when nothing differs. */
    @Test
    fun `no confirmation without a snapshot or a difference`() {
        val vm = fixture.start()
        vm.switchToNormal()
        VmHarness.idle()
        assertNull(vm.ui.value.adoptPrompt, "no snapshot arrived")
        VmHarness.advance(200)
        waitRole(vm, "normal")
        assertEquals(abOf(P), abOf(savedPrefs()))
    }

    @Test
    fun `no confirmation when the hub's values are this device's own`() {
        val vm = fixture.start()
        val own = VmHarness.ownState(vm)
        fixture.push(
            ConfigSnapshot(
                activeProfileId = own.activeProfileId, activeBeanId = own.activeBeanId, stopOnWeight = own.stopOnWeight, autoTare = own.autoTare,
                maxShotDurationS = own.maxShotDurationS, grinderModel = own.grinderModel, qcSteamTimeS = own.qcSteamTimeS,
                qcSteamFlowMlS = own.qcSteamFlowMlS, qcSteamTempC = own.qcSteamTempC, qcHotWaterTempC = own.qcHotWaterTempC,
                qcHotWaterVolumeMl = own.qcHotWaterVolumeMl, qcFlushTimeS = own.qcFlushTimeS, qcFlushTempC = own.qcFlushTempC,
                qcGrind = own.qcGrind,
            ),
        )
        vm.switchToNormal()
        VmHarness.idle()
        assertNull(vm.ui.value.adoptPrompt, "nothing differs")
    }

    /** AC-25: a Denied makes the device normal without asking; one arriving
     *  while the confirmation is open cancels it (no adoption, one switch). */
    @Test
    fun `a denied cancels the confirmation`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        fixture.revoke()
        waitRole(vm, "normal")
        VmHarness.advance(200)
        VmHarness.idle()
        assertNull(vm.ui.value.adoptPrompt)
        val pendingLeave = MainViewModel::class.java.getDeclaredField("pendingLeave").apply { isAccessible = true }
        assertNull(pendingLeave.get(vm), "the Denied's switch cancelled the leave itself, not only the prompt")
        vm.resolveAdoption(useHub = true) // too late: nothing to resolve
        VmHarness.advance(200)
        VmHarness.idle()
        assertEquals(abOf(P), abOf(savedPrefs()), "no adoption")
        assertEquals(listOf("normal"), switches.map { it.first }, "one switch")
    }

    /** AC-25: a Config frame arriving while the confirmation is open changes nothing. */
    @Test
    fun `a snapshot while the confirmation is open changes nothing`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        val shown = vm.ui.value.qcSteamTempC
        fixture.hubConfig = coreJson.encodeToString(ConfigSnapshot.serializer(), S.copy(qcSteamTempC = 120f))
        fixture.hub.pushConfig(fixture.hubConfig)
        Thread.sleep(200)
        VmHarness.idle()
        assertEquals(shown, vm.ui.value.qcSteamTempC)
        assertEquals(S.qcSteamTempC, VmHarness.ownState(vm).mirror!!.snapshot!!.qcSteamTempC, "the frozen slice")
    }

    /** AC-25: with a pre-P2 hub, only the keys it sent are adopted, and the
     *  confirmation names the rest. */
    @Test
    fun `with a legacy hub only known keys are adopted`() {
        val vm = fixture.start()
        val legacy = S.copy(
            settingsSchema = null, steamEco = null, steamTwoTap = null, fanThresholdC = null, waterRefillPointMm = null,
            heaterVoltage = null, flowMultiplier = null, lineFrequencyHz = null, lineFrequencyOverride = null, cupWarmerTempC = null,
            volumeStopWithScale = null, requireScale = null, preFlush = null, steamPurge = null, idleSleepMin = null,
        )
        fixture.push(legacy)
        vm.switchToNormal()
        VmHarness.idle()
        assertEquals(9, assertNotNull(vm.ui.value.adoptPrompt).legacyUnknown)
        vm.resolveAdoption(useHub = true)
        VmHarness.advance(200)
        waitRole(vm, "normal")
        VmHarness.idle()
        val saved = savedPrefs()
        assertEquals(S.qcSteamTempC, saved.qcSteamTempC, "a key the hub sent")
        assertEquals(P.steamEco, saved.steamEco, "a key it didn't")
        assertEquals(P.fanThresholdC, saved.fanThresholdC)
    }

    /** AC-36: a switch queued between the confirmation and the commit wins:
     *  the commit runs neither the adoption nor a second switch. */
    @Test
    fun `a switch during the prompt wins`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        fixture.revoke() // the Denied's switch, queued before the answer
        vm.resolveAdoption(useHub = true)
        VmHarness.advance(400)
        waitRole(vm, "normal")
        VmHarness.advance(400)
        VmHarness.idle()
        assertEquals(listOf("normal"), switches.map { it.first }, "one switch")
    }

    /** AC-36 / R1: a Denied that arrives while the commit holds the mode lock
     *  doesn't queue a second switch after it. */
    @Test
    fun `a denied during the commit doesn't switch again`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        vm.resolveAdoption(useHub = true) // the commit starts (its saves are awaited)
        fixture.revoke()
        VmHarness.advance(400)
        waitRole(vm, "normal")
        Thread.sleep(200)
        VmHarness.advance(400)
        VmHarness.idle()
        assertEquals(listOf("normal"), switches.map { it.first }, "one switch")
    }

    /** AC-36: a Denied that arrives during the commit for Hub mode on (while
     *  it awaits its saves) leaves the device a hub: the Denied's
     *  epoch-checked switch does nothing. */
    @Test
    fun `a Denied during the commit can't undo hub mode`() {
        fixture.hubHoldsDe1 = false // so this remote may turn hub mode on
        val vm = remoteWithPendingEdit()
        vm.setHubMode(true)
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        vm.resolveAdoption(useHub = true) // the commit is queued first
        fixture.revoke() // the Denied's switch queues behind it
        VmHarness.idleUntil("the Denied") { VmHarness.log(vm).any { "Mirror denied by host" in it } }
        waitRole(vm, "primary")
        Thread.sleep(200)
        VmHarness.advance(400)
        VmHarness.idle()
        assertEquals("primary", role(vm), "still the hub")
        assertEquals(listOf("primary"), switches.map { it.first }, "one switch")
        assertTrue(VmHarness.log(vm).any { "Mode switch to normal skipped" in it }, "the Denied's switch was stale")
    }

    /** AC-25: a re-find candidate (the hub restarted elsewhere) arriving while
     *  the confirmation is open changes nothing: no switch, the prompt and
     *  the frozen slice stay. */
    @Test
    fun `a re-find candidate while the confirmation is open changes nothing`() {
        fixture.peers = MutableStateFlow(emptyList<Peer>())
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        fixture.restart()
        // Past the re-find delay, letting the socket threads post back.
        repeat(((REFIND_DELAY_MS + 2_000) / 250).toInt()) {
            VmHarness.advance(250)
            Thread.sleep(5)
        }
        assertTrue(switches.isEmpty(), "no re-find switch: $switches")
        assertNotNull(vm.ui.value.adoptPrompt, "the confirmation stays")
        assertEquals(S.qcSteamTempC, vm.ui.value.qcSteamTempC)
        assertTrue(VmHarness.ownState(vm).mirror!!.frozen)
        assertTrue(fixture.endpoint.received.none { "\"type\":\"hello\"" in it }, "never dialed the candidate")
        vm.resolveAdoption(useHub = false)
        waitRole(vm, "normal")
        assertEquals(listOf("normal"), switches.map { it.first })
    }

    /** §7.5 step 2: while the confirmation is open the slice is frozen, so a
     *  relayed edit is refused here: nothing shown, nothing sent. */
    @Test
    fun `an edit while the confirmation is open is refused`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        val sent = fixture.handled.size
        val messages = vm.ui.value.userMessages.size
        vm.setStopOnWeight(false)
        Thread.sleep(200)
        VmHarness.idle()
        assertEquals(S.stopOnWeight, vm.ui.value.stopOnWeight, "nothing shown")
        assertEquals(sent, fixture.handled.size, "nothing sent")
        assertEquals(listOf("Leaving Kitchen tablet"), vm.ui.value.userMessages.drop(messages))
    }

    /** P2-1 (the P2b validation), #205's guard: a re-find attempt already in
     *  flight when the confirmation opens, then refused for an unproven
     *  reason, doesn't switch while the re-find is held. The confirmation
     *  stays usable, and its answer leaves. Started from the validator's probe. */
    @Test
    fun `a re-find attempt refused while the confirmation is open doesn't switch`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt, "the prompt is open")
        val pc = VmHarness.proxyController(vm)
        val epochBefore = pc.modeEpoch
        // The current session becomes a re-find attempt still waiting for its
        // Welcome (what the re-find dials for a new port on the saved host).
        val sessionField = ProxyController::class.java.getDeclaredField("session").apply { isAccessible = true }
        val current = sessionField.get(pc)!!
        val cls = current.javaClass
        val host = cls.getDeclaredMethod("getHost").invoke(current) as String
        val port = cls.getDeclaredMethod("getPort").invoke(current) as Int
        val ctor = cls.getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, ProxyController.MirrorTrust::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
        sessionField.set(pc, ctor.newInstance(host, port + 1, ProxyController.MirrorTrust.UNATTENDED, true))
        pc.refuseCurrentSessionForTest("host mismatch", byHost = false, hostProved = false)
        repeat(60) {
            VmHarness.advance(100)
            Thread.sleep(10)
        }
        VmHarness.idle()
        assertEquals(epochBefore, pc.modeEpoch, "no switch while held")
        assertTrue(switches.isEmpty(), "$switches")
        assertNotNull(vm.ui.value.adoptPrompt, "the confirmation is still open")
        vm.resolveAdoption(useHub = false)
        waitRole(vm, "normal")
        assertNull(vm.ui.value.adoptPrompt)
        assertEquals(listOf("normal"), switches.map { it.first })
        assertEquals(abOf(P), abOf(savedPrefs()), "nothing adopted")
    }

    /** A re-find switch queued while the confirmation is open is dropped
     *  without counting as a switch, so the answer still commits (the merge
     *  of #205's hold check with the T6 epoch). */
    @Test
    fun `a re-find switch queued while the confirmation is open is dropped`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        val pc = VmHarness.proxyController(vm)
        val epochBefore = pc.modeEpoch
        val session = ProxyController::class.java.getDeclaredField("session").apply { isAccessible = true }.get(pc)!!
        val host = session.javaClass.getDeclaredMethod("getHost").invoke(session) as String
        val port = session.javaClass.getDeclaredMethod("getPort").invoke(session) as Int
        ProxyController::class.java.declaredMethods.single { it.name == "requestRefindSwitch" }
            .apply { isAccessible = true }.invoke(pc, host, port + 1, true, session)
        repeat(10) {
            VmHarness.advance(100)
            Thread.sleep(10)
        }
        VmHarness.idle()
        assertEquals(epochBefore, pc.modeEpoch, "the dropped switch didn't count")
        assertTrue(VmHarness.log(vm).any { "Re-find: held — dropping a queued switch" in it })
        assertNotNull(vm.ui.value.adoptPrompt, "the confirmation is still open")
        vm.resolveAdoption(useHub = true)
        VmHarness.advance(200)
        waitRole(vm, "normal")
        VmHarness.idle()
        assertAdoptedS(vm, "normal")
    }

    /** A switch queued before the answer runs first: it cancels the leave
     *  (the slice was frozen, though the answer had already cleared the
     *  pending leave), the user is told once, and the stale commit adopts
     *  nothing. */
    @Test
    fun `a switch queued before the answer cancels the leave once`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        val pc = VmHarness.proxyController(vm)
        val modeLock = ProxyController::class.java.getDeclaredField("modeMutex").apply { isAccessible = true }.get(pc) as kotlinx.coroutines.sync.Mutex
        assertTrue(modeLock.tryLock())
        val messages = vm.ui.value.userMessages.size
        vm.switchToSecondary("127.0.0.1", fixture.endpoint.port) // queued first
        VmHarness.idle()
        vm.resolveAdoption(useHub = true) // queued behind it
        VmHarness.idle()
        modeLock.unlock()
        repeat(20) {
            VmHarness.advance(100)
            Thread.sleep(10)
        }
        VmHarness.idle()
        assertEquals("secondary", role(vm))
        assertNull(vm.ui.value.adoptPrompt)
        assertTrue(VmHarness.ownState(vm).mirror?.frozen == false)
        assertEquals(listOf("Couldn't leave Kitchen tablet — try again"), vm.ui.value.userMessages.drop(messages).filter { "leave" in it })
        assertEquals(listOf("secondary"), switches.map { it.first }, "only the queued switch ran")
        assertEquals(abOf(P), abOf(savedPrefs()), "the stale commit adopted nothing")
    }

    /** P2-1, the VM's guard: a switch that does run while the confirmation is
     *  open (here the user picks the hub again) cancels the leave. The dialog
     *  goes away, the slice unfreezes, the re-find is released, the user is
     *  told, and Stop works again. */
    @Test
    fun `a switch while the confirmation is open cancels it cleanly`() {
        val vm = fixture.start()
        fixture.push(S)
        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt, "the prompt is open")
        val pc = VmHarness.proxyController(vm)
        val epochBefore = pc.modeEpoch
        val messages = vm.ui.value.userMessages.size
        vm.switchToSecondary("127.0.0.1", fixture.endpoint.port)
        repeat(20) {
            VmHarness.advance(100)
            Thread.sleep(10)
        }
        VmHarness.idle()
        assertTrue(pc.modeEpoch > epochBefore, "a switch ran")
        assertEquals("secondary", role(vm))
        assertNull(vm.ui.value.adoptPrompt, "the dialog goes away")
        assertTrue(VmHarness.ownState(vm).mirror?.frozen == false, "the slice unfreezes")
        assertEquals(false, ProxyController::class.java.getDeclaredField("refindHeld").apply { isAccessible = true }.getBoolean(pc), "the re-find is released")
        assertTrue(vm.ui.value.userMessages.drop(messages).contains("Couldn't leave Kitchen tablet — try again"), "${vm.ui.value.userMessages.drop(messages)}")
        // Stop works again (the new session has no snapshot yet: it leaves at once).
        vm.switchToNormal()
        waitRole(vm, "normal")
        assertNull(vm.ui.value.adoptPrompt)
        assertEquals(abOf(P), abOf(savedPrefs()), "nothing adopted")
    }

    /** P2-1: an answer always closes the dialog, even when the leave it
     *  belonged to is already gone. */
    @Test
    fun `an answer always closes the dialog`() {
        val vm = remoteWithPendingEdit()
        vm.switchToNormal()
        VmHarness.idle()
        assertNotNull(vm.ui.value.adoptPrompt)
        MainViewModel::class.java.getDeclaredField("pendingLeave").apply { isAccessible = true }.set(vm, null)
        vm.resolveAdoption(useHub = false)
        VmHarness.idle()
        assertNull(vm.ui.value.adoptPrompt)
    }
}

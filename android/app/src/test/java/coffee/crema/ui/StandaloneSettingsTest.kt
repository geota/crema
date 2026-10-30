package coffee.crema.ui

import coffee.crema.ble.proxy.TEST_PEER
import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.settings.AppPrefs
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import coffee.crema.ble.proxy.CodecFrameLink
import coffee.crema.ble.proxy.Frame
import coffee.crema.ble.proxy.PROXY_PROTOCOL_VERSION
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Normal devices and hubs keep today's settings behaviour (spec §7.4, AC-16, AC-32). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class StandaloneSettingsTest {

    private val store = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @After
    fun tearDown() {
        scope.cancel()
        store.clear()
        VmHarness.idle()
    }

    private fun build(role: String): MainViewModel {
        writePrefs(AppPrefs(idleSleepMin = 30, proxyRole = role))
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        return vm
    }

    /** One A/B setter: what it sets, then where that shows on screen and on disk. */
    private class Edit<T>(val name: String, val set: (MainViewModel) -> Unit, val ui: (MainUiState) -> T, val saved: (AppPrefs) -> T, val value: T)

    private val edits: List<Edit<*>> = listOf(
        Edit("qcSteamTemp", { it.setQcSteamTemp(150f) }, { it.qcSteamTempC }, { it.qcSteamTempC }, 150f),
        Edit("qcSteamTime", { it.setQcSteamTime(30f) }, { it.qcSteamTimeS }, { it.qcSteamTimeS }, 30f),
        Edit("qcSteamFlow", { it.setQcSteamFlow(1.6f) }, { it.qcSteamFlowMlS }, { it.qcSteamFlowMlS }, 1.6f),
        Edit("qcHotWaterTemp", { it.setQcHotWaterTemp(85f) }, { it.qcHotWaterTempC }, { it.qcHotWaterTempC }, 85f),
        Edit("qcHotWaterVolume", { it.setQcHotWaterVolume(200f) }, { it.qcHotWaterVolumeMl }, { it.qcHotWaterVolumeMl }, 200f),
        Edit("qcFlushTime", { it.setQcFlushTime(6f) }, { it.qcFlushTimeS }, { it.qcFlushTimeS }, 6f),
        Edit("qcFlushTemp", { it.setQcFlushTemp(93f) }, { it.qcFlushTempC }, { it.qcFlushTempC }, 93f),
        Edit("steamEco", { it.setSteamEco(true) }, { it.steamEco }, { it.steamEco }, true),
        Edit("steamTwoTap", { it.setSteamTwoTap(true) }, { it.steamTwoTap }, { it.steamTwoTap }, true),
        Edit("fanThreshold", { it.setFanThreshold(58f) }, { it.fanThresholdC }, { it.fanThresholdC }, 58f),
        Edit("refillPoint", { it.setWaterRefillPointMm(8f) }, { it.waterRefillPointMm }, { it.waterRefillPointMm }, 8f),
        Edit("stopOnWeight", { it.setStopOnWeight(false) }, { it.stopOnWeight }, { it.stopOnWeight }, false),
        Edit("autoTare", { it.setAutoTare(false) }, { it.autoTare }, { it.autoTare }, false),
        Edit("maxShotDuration", { it.setMaxShotDuration(44f) }, { it.maxShotDurationS }, { it.maxShotDurationS }, 44f),
        Edit("volumeStopWithScale", { it.setVolumeStopWithScale(true) }, { it.volumeStopWithScale }, { it.volumeStopWithScale }, true),
        Edit("requireScale", { it.setRequireScale(true) }, { it.requireScale }, { it.requireScale }, true),
        Edit("preFlush", { it.setPreFlush(true) }, { it.preFlush }, { it.preFlush }, true),
        Edit("steamPurge", { it.setSteamPurge(true) }, { it.steamPurge }, { it.steamPurge }, true),
        Edit("idleSleep", { it.setIdleSleepMin(60) }, { it.idleSleepMin }, { it.idleSleepMin }, 60),
        Edit("grinderModel", { it.setGrinderModel("Niche") }, { it.grinderModel }, { it.grinderModel }, "Niche"),
        Edit("qcGrind", { it.setQcGrind(15f) }, { it.qcGrind }, { it.qcGrind }, 15f),
        Edit("activeProfile", { it.setActiveProfile(RemoteFixture.PROFILE_S) }, { it.activeProfileId }, { it.activeProfileId }, RemoteFixture.PROFILE_S),
    )

    /** AC-16: on a normal device and on a hub, every A/B setter changes the
     *  screen and prefs.json as before, the screen is the own state, and the
     *  connect sweep reads own values. */
    @Test
    fun `normal and hub settings are unchanged`() {
        for (role in listOf("normal", "primary")) {
            val vm = build(role)
            for (e in edits) {
                e.set(vm)
                VmHarness.idle()
                assertEquals(e.value, e.ui(vm.ui.value), "$role ${e.name} on screen")
                VmHarness.idleUntil("$role ${e.name} saved") { e.saved(savedPrefs()) == e.value }
            }
            VmHarness.idle()
            assertEquals(VmHarness.ownState(vm), vm.ui.value, "$role: the screen is the own state")
            assertEquals(VmHarness.ownState(vm).connectSweepSettings(90f, 50), vm.ui.value.connectSweepSettings(90f, 50))
            store.clear()
            VmHarness.idle()
        }
    }

    /** AC-20: a hub (and a normal device) drops a config frame: it has no
     *  mirror slice, so nothing it shows or saves changes. */
    @Test
    fun `a hub drops a config frame`() {
        for (role in listOf("primary", "normal")) {
            val vm = build(role)
            val shown = vm.ui.value
            val saved = savedPrefs()
            vm.deliverConfigForTest(coreJson.encodeToString(coffee.crema.settings.ConfigSnapshot.serializer(), RemoteFixture.S))
            VmHarness.idle()
            assertEquals(null, VmHarness.ownState(vm).mirror, "$role: no slice")
            assertEquals(abOf(saved), abOf(savedPrefs()), "$role: prefs.json unchanged")
            for (e in edits) assertEquals(e.ui(shown), e.ui(vm.ui.value), "$role ${e.name} unchanged")
            assertTrue(VmHarness.log(vm).any { "Config snapshot dropped: not a remote" in it }, role)
            store.clear()
            VmHarness.idle()
        }
    }

    /** AC-27, the hub VM's half: its machine actions report config ready
     *  only once the prefs load, so a relayed setting edit before then is
     *  refused ("starting up") and applied after. */
    @Test
    fun `a hub refuses relayed config edits until its prefs load`() = VmHarness.holdingStartupLoad { release ->
        writePrefs(AppPrefs(idleSleepMin = 30, proxyRole = "primary"))
        val vm = VmHarness.build(store)
        val pc = VmHarness.proxyController(vm)
        val early = runBlocking { pc.handleRelayedControl("setQcGrind", "14.5", "remote-1", "Phone") }
        assertTrue(early is coffee.crema.machine.CommandOutcome.Refused && early.reason.endsWith("is starting up"), "$early")
        release.complete(Unit)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        assertEquals(null, vm.ui.value.qcGrind, "the early edit wasn't applied")
        val late = runBlocking { pc.handleRelayedControl("setQcGrind", "14.5", "remote-1", "Phone") }
        assertEquals(coffee.crema.machine.CommandOutcome.Accepted, late)
        VmHarness.idle()
        assertEquals(14.5f, vm.ui.value.qcGrind)
    }

    /** AC-16: on a hub, a reset and a restore push a snapshot to its remotes. */
    @Test
    fun `reset and restore on a hub push a snapshot`() {
        val vm = build("primary")
        val hub = assertNotNull(VmHarness.proxyController(vm).relayHub)
        val (client, server) = CodecFrameLink.pair(scope)
        val rx = CopyOnWriteArrayList<Frame>()
        scope.launch { hub.serve(server, TEST_PEER) }
        scope.launch { client.incoming().collect { rx += it } }
        VmHarness.idleUntil("the Welcome") { rx.any { it is Frame.Welcome } }

        fun configs() = rx.filterIsInstance<Frame.Config>().map { coreJson.decodeFromString(coffee.crema.settings.ConfigSnapshot.serializer(), it.json) }
        fun pushedBy(what: String, action: () -> Unit): coffee.crema.settings.ConfigSnapshot {
            val before = configs().size
            action()
            VmHarness.idleUntil("a snapshot after $what") { configs().size > before }
            return configs().last()
        }
        pushedBy("the reset") { vm.resetPreferences() }
        val backup = assertNotNull(runBlocking { vm.buildBackupZipBytes() })
        pushedBy("the restore") { VmHarness.restore(vm, backup) }
        // The pushes §6.1 adds.
        val duration = pushedBy("a max shot duration") { vm.setMaxShotDuration(41f) }
        assertEquals(41f, duration.maxShotDurationS)
        val freq = pushedBy("a line frequency") { vm.setLineFrequency(50f) }
        assertEquals(50f, freq.lineFrequencyOverride)
        // A v4 snapshot, numbered, with the hub's own values.
        vm.setSteamEco(true)
        vm.setPreFlush(true)
        val last = pushedBy("an edit") { vm.setIdleSleepMin(60) }
        assertEquals(listOf(true, true, 60), listOf(last.steamEco, last.preFlush, last.idleSleepMin))
        assertEquals(VmHarness.ownState(vm).fanThresholdC, last.fanThresholdC)
        val revs = configs().map { it.rev }
        assertEquals(revs.sorted(), revs, "revs only go up")
        assertEquals(revs.size, revs.toSet().size)
    }

    /** AC-32 (review-v2 6): a reset restores the preferences, not the role,
     *  the hub target, the paired devices or the DE1 / scale bindings. */
    @Test
    fun `reset keeps role and pairing`() {
        // Relay v4: the pairing lives in relay-v4.json, never in the prefs.
        val (relayStore, _) = VmHarness.relayStore()
        val remote = coffee.crema.core.RelayKeys.generate()
        relayStore.pinRemote(coffee.crema.ble.proxy.RelayRemotePin(remote.deviceId(), coffee.crema.ble.proxy.RelayKeyStore.b64(remote.publicKey()), "Phone", "control", pairedAtMs = 1))
        writePrefs(
            AppPrefs(
                themeMode = "light", weightUnit = "oz", stopOnWeight = false, idleSleepMin = 30,
                proxyRole = "primary", proxyPrimaryHost = "192.168.1.20", proxyPrimaryPort = 8765,
                de1Address = "AA:BB:CC:DD:EE:01", scaleAddress = "AA:BB:CC:DD:EE:02", scaleName = "Bookoo",
            ),
        )
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitPrefsLoaded(vm)
        vm.resetPreferences()
        VmHarness.idleUntil("the reset saved") { savedPrefs().themeMode == AppPrefs().themeMode }
        val saved = savedPrefs()
        assertEquals(AppPrefs().weightUnit, saved.weightUnit)
        assertEquals(AppPrefs().stopOnWeight, saved.stopOnWeight)
        assertEquals("primary", saved.proxyRole)
        assertEquals("192.168.1.20", saved.proxyPrimaryHost)
        assertEquals(8765, saved.proxyPrimaryPort)
        assertTrue("pairedDevices" !in java.io.File(VmHarness.app().filesDir, "prefs.json").readText(), "the prefs carry no pairing")
        assertEquals(listOf(remote.deviceId()), VmHarness.proxyController(vm).relayStore.remotes.map { it.id }, "the pin survives the reset")
        assertEquals("AA:BB:CC:DD:EE:01", saved.de1Address)
        assertEquals("AA:BB:CC:DD:EE:02", saved.scaleAddress)
        assertEquals("Bookoo", saved.scaleName)
    }
}

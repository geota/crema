package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.ble.proxy.Peer
import coffee.crema.settings.OnRemote
import coffee.crema.settings.Setting
import kotlinx.coroutines.flow.MutableStateFlow
import coffee.crema.settings.SettingGroup
import coffee.crema.ui.RemoteFixture.Companion.BEAN_P
import coffee.crema.ui.RemoteFixture.Companion.BEAN_S
import coffee.crema.ui.RemoteFixture.Companion.P
import coffee.crema.ui.RemoteFixture.Companion.PROFILE_S
import coffee.crema.ui.RemoteFixture.Companion.S
import coffee.crema.ui.RemoteFixture.Companion.savedActiveBean
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A remote's settings (spec §7.2): the hub's machine and session values are
 * shown from the mirror slice and never reach this device's own settings,
 * library or backup. A real remote VM against a real hub ([RemoteFixture]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RemoteSettingsTest {

    private val store = ViewModelStore()
    private val fixture = RemoteFixture(store)

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
        fixture.kill()
    }

    /** Stop, answering the T6 confirmation (if any) with "Keep mine", and
     *  wait for the normal role. */
    private fun stopKeepingMine(vm: MainViewModel) {
        vm.switchToNormal()
        VmHarness.idle()
        if (vm.ui.value.adoptPrompt != null) vm.resolveAdoption(useHub = false)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (VmHarness.proxyController(vm).state.value.role != "normal") {
            check(System.nanoTime() < deadline) { "still a remote" }
            VmHarness.advance(50)
            Thread.sleep(5)
        }
    }

    private fun idleRelays(count: Int) = VmHarness.idleUntil("$count controls at the hub") { fixture.handled.size >= count }

    /** AC-17. */
    @Test
    fun `own settings survive a remote session`() {
        val vm = fixture.start()
        fixture.push()
        vm.setStopOnWeight(true) // relayed (P has it off)
        vm.quickAdjustBrew(18.0, 36.0, 92.0) // a brew-dial edit, relayed
        idleRelays(2)
        vm.setThemeMode("dark")
        vm.setWeightUnit("g")
        VmHarness.idleUntil("the theme saved") { savedPrefs().themeMode == "dark" && savedPrefs().weightUnit == "g" }
        assertEquals(abOf(P), abOf(savedPrefs()), "mid-session")

        stopKeepingMine(vm)
        VmHarness.idleUntil("the role saved") { savedPrefs().proxyRole == "normal" }
        val saved = savedPrefs()
        assertEquals(abOf(P), abOf(saved), "the remote's own machine and session settings")
        assertEquals("dark", saved.themeMode)
        assertEquals("g", saved.weightUnit)
    }

    /** AC-18. */
    @Test
    fun `a remote shows the hub's A and B and its own C`() {
        val vm = fixture.start()
        fixture.push()
        val ui = vm.ui.value
        assertEquals(S.stopOnWeight, ui.stopOnWeight)
        assertEquals(S.autoTare, ui.autoTare)
        assertEquals(S.maxShotDurationS, ui.maxShotDurationS)
        assertEquals(S.grinderModel, ui.grinderModel)
        assertEquals(listOf(S.qcSteamTimeS, S.qcSteamFlowMlS, S.qcSteamTempC, S.qcHotWaterTempC, S.qcHotWaterVolumeMl, S.qcFlushTimeS, S.qcFlushTempC), listOf(ui.qcSteamTimeS, ui.qcSteamFlowMlS, ui.qcSteamTempC, ui.qcHotWaterTempC, ui.qcHotWaterVolumeMl, ui.qcFlushTimeS, ui.qcFlushTempC))
        assertEquals(S.qcGrind, ui.qcGrind)
        assertEquals(PROFILE_S, ui.activeProfileId)
        assertEquals(BEAN_S, ui.activeBeanId)
        assertEquals(BrewParams(20.0, 40.0, 94.0, 6.0), ui.brewParams)
        // The display fields a schema-1 hub adds (§6.1).
        assertEquals(listOf(true, true, 58f, 8f, true, true, true, true, 60), listOf(ui.steamEco, ui.steamTwoTap, ui.fanThresholdC, ui.waterRefillPointMm, ui.volumeStopWithScale, ui.requireScale, ui.preFlush, ui.steamPurge, ui.idleSleepMin))
        assertEquals(230u, ui.de1MachineInfo[coffee.crema.core.MmrRegister.HeaterVoltage])
        assertEquals(1100u, ui.de1MachineInfo[coffee.crema.core.MmrRegister.CalibrationFlowMultiplier])
        assertEquals(60u, ui.de1MachineInfo[coffee.crema.core.MmrRegister.CupWarmerTemp])
        assertEquals(50f, ui.lineFreqHz)
        assertEquals(50f, ui.lineFreqOverride)
        // Personal: units and the rest stay this device's, whatever the hub sent.
        assertEquals("oz", ui.weightUnit)
        assertEquals("F", ui.tempUnit)
        assertEquals("light", ui.themeMode)
        // And the own state underneath is untouched.
        assertEquals(P.qcSteamTempC, VmHarness.ownState(vm).qcSteamTempC)
        assertEquals(BEAN_P, VmHarness.ownState(vm).activeBeanId)
    }

    /** AC-19. */
    @Test
    fun `mirror values never reach prefs, library or backup`() {
        val vm = fixture.start()
        fixture.push()
        vm.setThemeMode("dark") // a persistPrefs
        VmHarness.idleUntil("the theme saved") { savedPrefs().themeMode == "dark" }
        assertEquals(abOf(P), abOf(savedPrefs()), "theme edit")
        vm.setAutoTare(false) // a relayed ack
        idleRelays(1)
        VmHarness.idle()
        assertEquals(abOf(P), abOf(savedPrefs()), "relayed ack")
        vm.toggleBeanFavourite(BEAN_S) // a library save
        VmHarness.idleUntil("the library saved") {
            runCatching { java.io.File(VmHarness.app().filesDir, "beanLibrary.json").readText() }.getOrDefault("").contains("\"favourite\":true")
        }
        assertEquals(BEAN_P, savedActiveBean(), "the library keeps this device's active bean")
        // The backup carries this device's own settings.
        val zip = assertNotNull(runBlocking { vm.buildBackupZipBytes() })
        val settings = backupSettings(zip)
        assertEquals(P.qcSteamTempC.toString(), settings["qcSteamTempC"]?.jsonPrimitive?.content?.let { it.toFloat().toString() })
        assertEquals(P.stopOnWeight.toString(), settings["stopOnWeight"]?.jsonPrimitive?.content)
        assertEquals(P.grinderModel, settings["grinderModel"]?.jsonPrimitive?.content)
        // The mode switch.
        stopKeepingMine(vm)
        VmHarness.idleUntil("the role saved") { savedPrefs().proxyRole == "normal" }
        assertEquals(abOf(P), abOf(savedPrefs()), "mode switch")
    }

    /** AC-20: the slice exists before the first frame on a restart as a
     *  remote, every A/B key is unknown until the first snapshot, and the
     *  slice goes with the role (I6). */
    @Test
    fun `slice lifecycle`() {
        var beforeWelcome: MainUiState? = null
        val vm = fixture.start(onBuilt = { beforeWelcome = VmHarness.ownState(it) })
        assertNotNull(beforeWelcome?.mirror, "the slice exists before any frame")
        val st = VmHarness.ownState(vm)
        assertTrue(st.awaitingHubValues, "no snapshot yet")
        for (s in Setting.entries.filter { it.group != SettingGroup.C_PERSONAL }) {
            assertTrue(!st.knownOnRemote(s), "$s is unknown before the first snapshot")
        }
        fixture.push()
        assertTrue(!VmHarness.ownState(vm).awaitingHubValues)
        assertTrue(VmHarness.ownState(vm).knownOnRemote(Setting.QC_STEAM_TEMP))
        stopKeepingMine(vm)
        assertNull(VmHarness.ownState(vm).mirror, "no slice off the remote role")
    }

    /** AC-20: a config frame after the device left the remote role changes nothing. */
    @Test
    fun `late config frames are dropped`() {
        val vm = fixture.start()
        fixture.push()
        stopKeepingMine(vm)
        val before = vm.ui.value
        vm.deliverConfigForTest(coreJson.encodeToString(coffee.crema.settings.ConfigSnapshot.serializer(), S))
        VmHarness.idle()
        assertEquals(abOf(P), abOfUi(vm.ui.value))
        assertEquals(before.qcSteamTempC, vm.ui.value.qcSteamTempC)
    }

    /** §6.1: a snapshot at or below the last applied rev is dropped. */
    @Test
    fun `older snapshots are dropped`() {
        val vm = fixture.start()
        fixture.push(S.copy(rev = 5))
        fixture.hubConfig = coreJson.encodeToString(coffee.crema.settings.ConfigSnapshot.serializer(), S.copy(rev = 3, qcGrind = 3f))
        fixture.hub.pushConfig(fixture.hubConfig)
        fixture.push(S.copy(rev = 6, qcGrind = 6f)) // a later one lands, so the rev-3 one was handled first
        assertEquals(6f, vm.ui.value.qcGrind)
        assertTrue(VmHarness.log(vm).any { "Config snapshot 3 dropped" in it }, "the rev-3 snapshot was dropped")
    }

    /** AC-30, the behaviour half: on a remote, library actions act on what is
     *  on screen (the hub's), not on this device's own values. */
    @Test
    fun `library actions use the values on screen`() {
        val vm = fixture.start()
        fixture.push()
        assertTrue(vm.saveQuickGrindToBean())
        VmHarness.idle()
        assertEquals("15", vm.ui.value.beans.first { it.id == BEAN_S }.grinderSetting, "S's dial on S's bean")
        assertEquals("", vm.ui.value.beans.first { it.id == BEAN_P }.grinderSetting)

        // The startup load must not replace the saved copy (it loads the
        // custom profiles after the prefs).
        VmHarness.awaitStartupLoaded(vm)
        vm.saveQuickPreset("Hub dial")
        // Saving a built-in's copy makes it active, an edit relayed to the
        // hub. Until the hub answers, that pending edit is what the screen
        // shows (and what the next library action acts on), so let it settle.
        VmHarness.idleUntil("the hub's answer") { VmHarness.ownState(vm).mirror?.pending?.isEmpty() == true }
        val saved = vm.ui.value.profiles.firstOrNull { it.name == "Hub dial" }
        assertNotNull(saved, "a copy of S's built-in profile")
        assertEquals(20f, saved.dose, "with S's brew override")
        assertEquals(40f, saved.yieldOut)

        // A hub custom profile this library lacks: refused.
        val customJson = vm.ui.value.profiles.first { it.id == PROFILE_S }.let { coreJson.encodeToString(coffee.crema.profiles.CremaProfile.serializer(), it.copy(id = "custom-on-the-hub", source = "custom")) }
        fixture.push(S.copy(activeProfileId = "custom-on-the-hub", activeProfileJson = customJson))
        val count = vm.ui.value.profiles.size
        vm.saveQuickPreset("Nope")
        VmHarness.idle()
        assertEquals(count, vm.ui.value.profiles.size)
        assertTrue(vm.ui.value.userMessages.any { it == "That profile is only on Kitchen tablet" }, "${vm.ui.value.userMessages}")
    }

    /** AC-31: the remote's core (its stop-target projection) follows the
     *  hub's values, also after a restore or a reset, and this device's own
     *  once it stops being a remote. */
    @Test
    fun `the remote core follows the hub`() {
        val vm = fixture.start()
        fixture.push()
        // The core's projection: the weight target and the max-duration cap
        // (S: 40 g, 44 s; this device's own: no override, 33 s).
        fun core() = vm.ui.value.stopTargets?.let { it.weightConfiguredG to it.maxTimeS }
        VmHarness.idle()
        assertEquals(40f to 44f, core(), "after the snapshot")
        val backup = assertNotNull(runBlocking { vm.buildBackupZipBytes() })
        VmHarness.restore(vm, backup)
        assertEquals(40f to 44f, core(), "after a restore")
        vm.resetPreferences()
        VmHarness.idle()
        assertEquals(40f to 44f, core(), "after a reset")
        stopKeepingMine(vm)
        val ownYield = vm.ui.value.profiles.first { it.id == RemoteFixture.PROFILE_P }.yieldOut.takeIf { it > 0f }
        val ownCap = VmHarness.ownState(vm).maxShotDurationS.takeIf { it > 0f }
        assertEquals(ownYield to ownCap, core(), "this device's own values, no override")
    }

    /** AC-31's loadPrefs clause (the P2a validation's P3-1): a snapshot that
     *  lands before this device's prefs finish loading still drives the core
     *  once they have. */
    @Test
    fun `the remote core follows a snapshot that beat loadPrefs`() = VmHarness.holdingStartupLoad { release ->
        var early = false
        val vm = fixture.start(onBuilt = {
            early = !MainViewModel::class.java.getDeclaredField("prefsLoaded").apply { isAccessible = true }.getBoolean(it)
            it.deliverConfigForTest(coreJson.encodeToString(coffee.crema.settings.ConfigSnapshot.serializer(), S))
            release.complete(Unit)
        })
        assertTrue(early, "the snapshot landed before loadPrefs finished")
        VmHarness.idle()
        assertEquals(40f to 44f, vm.ui.value.stopTargets?.let { it.weightConfiguredG to it.maxTimeS })
    }

    /** The P2a validation's P3-2: a hub custom profile this library lacks
     *  still drives the remote core's stop targets (through the overlay). */
    @Test
    fun `a hub-only profile drives the remote core`() {
        val vm = fixture.start()
        fixture.push()
        val custom = vm.ui.value.profiles.first { it.id == RemoteFixture.PROFILE_S }.copy(id = "custom-on-the-hub", source = "custom", yieldOut = 37f)
        val customJson = coreJson.encodeToString(coffee.crema.profiles.CremaProfile.serializer(), custom)
        fixture.push(S.copy(activeProfileId = "custom-on-the-hub", activeProfileJson = customJson, brewDoseG = null, brewYieldG = null, brewTempC = null, brewPreinfuseS = null, rev = 2))
        VmHarness.idle()
        assertEquals("custom-on-the-hub", vm.ui.value.activeProfileId)
        assertEquals(37f, vm.ui.value.stopTargets?.weightConfiguredG, "the overlay's yield")
    }

    /** AC-32: restore, reset and erase on a remote change own values only;
     *  the screens keep showing the hub's. */
    @Test
    fun `own-state writers leave the mirror alone`() {
        val vm = fixture.start()
        fixture.push()
        val shown = abOfUi(vm.ui.value)
        val backup = assertNotNull(runBlocking { vm.buildBackupZipBytes() })
        VmHarness.restore(vm, backup)
        assertEquals(shown, abOfUi(vm.ui.value), "after a restore")
        vm.resetPreferences()
        VmHarness.idle()
        assertEquals(shown, abOfUi(vm.ui.value), "after a reset")
        assertEquals(coffee.crema.settings.AppPrefs().stopOnWeight, VmHarness.ownState(vm).stopOnWeight, "the own value was reset")
        vm.eraseAll()
        VmHarness.idle()
        assertEquals(shown, abOfUi(vm.ui.value), "after erase")
    }

    /** Advance the main looper's clock in steps, letting socket threads
     *  post back, until [done] holds (the re-find waits on that clock). */
    private fun untilClock(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (!done()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            VmHarness.advance(250)
            Thread.sleep(10)
        }
    }

    /** AC-26: the hub restarts with this remote attached. Until it reattaches
     *  the remote keeps the last values, marked reconnecting (relayed rows
     *  OFFLINE); its own prefs never change. The restarted hub's snapshot
     *  applies although its rev started over: the Welcome reset it. */
    @Test
    fun `hub restart keeps the last view until reattach`() {
        fixture.peers = MutableStateFlow(emptyList<Peer>())
        val vm = fixture.start()
        fixture.push(S.copy(rev = 5))
        val shown = abOfUi(vm.ui.value)
        val after = S.copy(stopOnWeight = false, qcGrind = 9f, grinderModel = "After the restart", rev = 0)
        fixture.hubConfig = coreJson.encodeToString(coffee.crema.settings.ConfigSnapshot.serializer(), after)
        fixture.restart()
        VmHarness.idleUntil("the remote to notice") { vm.ui.value.mirrorReconnecting }
        assertEquals(shown, abOfUi(vm.ui.value), "the last values stay")
        assertTrue(VmHarness.ownState(vm).mirror!!.reconnecting)
        for (setting in Setting.entries.filter { it.group != SettingGroup.C_PERSONAL && vm.ui.value.knownOnRemote(it) }) {
            val expected = if (setting.onRemote == OnRemote.RELAYED) SettingAvailability.OFFLINE else SettingAvailability.READ_ONLY
            assertEquals(expected, vm.ui.value.availability(setting), "$setting while the hub is down")
        }
        assertEquals(abOf(P), abOf(savedPrefs()))
        untilClock("the reattach") { fixture.hub.remotes().isNotEmpty() && !vm.ui.value.mirrorReconnecting }
        VmHarness.idleUntil("the restarted hub's snapshot") { vm.ui.value.grinderModel == "After the restart" }
        assertEquals(9f, vm.ui.value.qcGrind)
        assertEquals(false, vm.ui.value.stopOnWeight)
        assertEquals(SettingAvailability.VIA_HUB, vm.ui.value.availability(Setting.STOP_ON_WEIGHT))
        assertEquals(abOf(P), abOf(savedPrefs()))
    }

    /** AC-27: a remote that attaches while the hub's DE1 is mid-shot (its
     *  state stream says Espresso throughout) shows S from the attach
     *  snapshot. The JVM harness can't run a real shot (spec §8), so the
     *  shot is the hub's live state stream. */
    @Test
    fun `attach mid-shot`() {
        fixture.hubConfig = coreJson.encodeToString(coffee.crema.settings.ConfigSnapshot.serializer(), S)
        val streaming = java.util.concurrent.atomic.AtomicBoolean(true)
        val stream = Thread {
            var t = 0L
            while (streaming.get()) {
                // Espresso (4), pouring (5).
                fixture.hub.onInbound("AA:BB", coffee.crema.ble.De1Uuids.SERVICE, coffee.crema.ble.De1Uuids.STATE_INFO, byteArrayOf(4, 5), t++)
                Thread.sleep(20)
            }
        }
        try {
            val vm = fixture.start(onBuilt = { stream.start() })
            vm.connect() // the remote's DE1, through the hub
            untilClock("the attach") { fixture.endpoint.received.any { "\"type\":\"attach\"" in it } }
            VmHarness.idleUntil("the attach snapshot") { VmHarness.ownState(vm).mirror?.snapshot == S }
            assertTrue(stream.isAlive, "the shot was still streaming")
            assertEquals(S.qcGrind, vm.ui.value.qcGrind)
            assertEquals(S.stopOnWeight, vm.ui.value.stopOnWeight)
            assertEquals(S.activeProfileId, vm.ui.value.activeProfileId)
            assertEquals(SettingAvailability.VIA_HUB, vm.ui.value.availability(Setting.STOP_ON_WEIGHT))
            assertEquals(abOf(P), abOf(savedPrefs()))
        } finally {
            streaming.set(false)
            stream.join(1_000)
        }
    }

    private fun abOfUi(ui: MainUiState): Map<String, Any?> = abOf(
        P.copy(
            maxShotDurationS = ui.maxShotDurationS, autoTare = ui.autoTare, stopOnWeight = ui.stopOnWeight,
            volumeStopWithScale = ui.volumeStopWithScale, requireScale = ui.requireScale, steamEco = ui.steamEco,
            steamTwoTap = ui.steamTwoTap, fanThresholdC = ui.fanThresholdC, preFlush = ui.preFlush, steamPurge = ui.steamPurge,
            idleSleepMin = ui.idleSleepMin, grinderModel = ui.grinderModel, qcSteamTimeS = ui.qcSteamTimeS,
            qcSteamFlowMlS = ui.qcSteamFlowMlS, qcSteamTempC = ui.qcSteamTempC, qcHotWaterTempC = ui.qcHotWaterTempC,
            qcHotWaterVolumeMl = ui.qcHotWaterVolumeMl, qcFlushTimeS = ui.qcFlushTimeS, qcFlushTempC = ui.qcFlushTempC,
            qcGrind = ui.qcGrind, activeProfileId = ui.activeProfileId, waterRefillPointMm = ui.waterRefillPointMm,
        ),
    )

    /** The `settings` line of a backup zip's `backup.jsonl`. */
    private fun backupSettings(zip: ByteArray): kotlinx.serialization.json.JsonObject {
        ZipInputStream(ByteArrayInputStream(zip)).use { z ->
            while (true) {
                val entry = z.nextEntry ?: break
                if (entry.name != "backup.jsonl") continue
                val line = z.readBytes().decodeToString().lines().first { "\"settings\"" in it }
                val obj = coreJson.parseToJsonElement(line).jsonObject
                return (obj["data"] ?: obj["settings"] ?: obj).jsonObject.let { it["common"]?.jsonObject ?: it }
            }
        }
        error("no backup.jsonl in the zip")
    }
}

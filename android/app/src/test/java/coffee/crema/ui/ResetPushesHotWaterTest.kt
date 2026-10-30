package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.HotWaterMode
import coffee.crema.core.HotWaterTarget
import coffee.crema.core.NotificationSource
import coffee.crema.settings.AppPrefs
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * Restore defaults and Erase all data reset the hot-water dose to ratio 1:3
 * and push it (hot-water modes spec §4 writers, AC-HW25): the core's target
 * and the packet it writes follow at once, not at the next connect.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ResetPushesHotWaterTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    /** The machine's packet: water 90 °C / 200 ml / 45 s (see SteamHotWaterTargetsTest). */
    private val machinePacket = byteArrayOf(0x40, 160.toByte(), 30, 90, 200.toByte(), 45, 220.toByte(), 88, 128.toByte())

    private fun volume200(): MainViewModel {
        writePrefs(AppPrefs(idleSleepMin = 30, qcHotWaterMode = HotWaterMode.Volume, qcHotWaterVolumeMl = 210f, qcHotWaterTimeS = 40f, qcHotWaterTimeNoLimit = false, qcHotWaterRatio = 2f))
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        VmHarness.bridge(vm).onNotification(NotificationSource.DE1_SHOT_SETTINGS, machinePacket, 0uL)
        assertEquals(HotWaterMode.Volume, coreTarget(vm).mode)
        return vm
    }

    private fun coreTarget(vm: MainViewModel) = coreJson.decodeFromString(HotWaterTarget.serializer(), VmHarness.bridge(vm).hotWaterTargetJson())

    private fun assertResetAndPushed(vm: MainViewModel, what: String) {
        assertEquals(HotWaterUi.defaults, VmHarness.ownState(vm).hotWaterDose(), "$what: ratio 1:3")
        val target = coreTarget(vm)
        assertEquals(HotWaterMode.Ratio, target.mode, "$what: pushed to the core")
        assertEquals(target, vm.ui.value.hotWaterTarget, "$what: the readout's target")
        VmHarness.idleUntil("$what: the packet") { vm.ui.value.de1ShotSettings?.hot_water_volume == target.wireVolumeMl }
        VmHarness.idleUntil("$what: saved") { runCatching { savedPrefs().qcHotWaterMode }.getOrNull() == HotWaterMode.Ratio }
    }

    @Test
    fun `restore defaults resets the dose and pushes it`() {
        val vm = volume200()
        vm.resetPreferences()
        VmHarness.idle()
        assertResetAndPushed(vm, "reset")
    }

    @Test
    fun `erase all data resets the dose and pushes it`() {
        val vm = volume200()
        vm.eraseAll()
        VmHarness.idle()
        assertResetAndPushed(vm, "erase")
    }
}

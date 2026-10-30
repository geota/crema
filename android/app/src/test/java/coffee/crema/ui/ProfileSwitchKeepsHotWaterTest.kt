package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.HotWaterMode
import coffee.crema.settings.AppPrefs
import coffee.crema.ui.RemoteFixture.Companion.savedPrefs
import coffee.crema.ui.RemoteFixture.Companion.writePrefs
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A profile switch in ratio mode (hot-water modes spec §2.2, AC-HW25): the
 * core's target follows the new yield, and `prefs.json`'s hot-water fields
 * stay as they were — the amount is derived, never written back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ProfileSwitchKeepsHotWaterTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    private fun AppPrefs.hotWater() = listOf(qcHotWaterMode, qcHotWaterTimeS, qcHotWaterTimeNoLimit, qcHotWaterVolumeMl, qcHotWaterRatio)

    @Test
    fun `a profile switch in ratio mode doesn't touch the stored dose`() {
        writePrefs(AppPrefs(idleSleepMin = 30, qcHotWaterMode = HotWaterMode.Ratio, qcHotWaterTimeS = 30f, qcHotWaterTimeNoLimit = false, qcHotWaterVolumeMl = 180f, qcHotWaterRatio = 2.5f))
        val vm = VmHarness.build(store)
        VmHarness.awaitStartupLoaded(vm)
        val stored = savedPrefs().hotWater()
        val yields = vm.ui.value.profiles.groupBy { it.yieldOut }.filterKeys { it in 20f..90f }.values.map { it.first() }.take(3)
        assertTrue(yields.size >= 2, "built-ins with different yields")
        for (p in yields) {
            vm.setActiveProfile(p.id)
            VmHarness.idle()
            VmHarness.idleUntil("${p.name} saved") { savedPrefs().activeProfileId == p.id }
            assertEquals((2.5f * p.yieldOut).roundToInt().toFloat(), assertNotNull(vm.ui.value.hotWaterTarget).volumeMl, "${p.name}: 1:2.5 of ${p.yieldOut} g")
            assertEquals(stored, savedPrefs().hotWater(), "${p.name}: prefs.json's hot-water fields")
            assertEquals(180f, vm.ui.value.qcHotWaterVolumeMl, "the volume mode's value is kept")
        }
    }
}

package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.settings.Setting
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The scale session's UI projection (MainViewModel breakup step 4): the
 * settings a scale echoes fill its controls and a reading that leaves one out
 * keeps the last value, the config channel folds in whatever each response
 * carries, and a disconnect clears the session back to the defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ScaleSessionVmTest {
    private val store = ViewModelStore()

    @After fun tearDown() { store.clear(); VmHarness.idle() }

    private fun build(): MainViewModel {
        val vm = VmHarness.build(store)
        VmHarness.idle()
        VmHarness.awaitStartupLoaded(vm)
        return vm
    }

    private fun deliver(vm: MainViewModel, events: String) {
        VmHarness.deliverCoreOutput(vm, """{"events":[$events],"commands":[]}""")
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
    }

    private fun reading(extra: String = "") =
        """{"type":"ScaleReading","content":{"weight":12.5,"flow":1.5$extra}}"""

    @Test
    fun `echoed settings fill the controls and a reading without them keeps the last value`() {
        val vm = build()
        deliver(vm, reading(""","device_volume":4,"device_standby":10,"device_battery":80,"device_flow":2.0"""))
        var s = vm.ui.value
        assertEquals(12.5f, vm.live.value.scaleWeightG)
        assertEquals(2.0f, vm.live.value.scaleFlowGPerS, "the scale's own flow when it reports one")
        assertEquals(4, s.scaleVolume)
        assertEquals(10, s.scaleStandbyMinutes)
        assertEquals(80, s.scaleBatteryPercent)
        assertTrue(Setting.SCALE_VOLUME in s.scaleEchoed && Setting.SCALE_STANDBY in s.scaleEchoed)

        deliver(vm, reading())
        s = vm.ui.value
        assertEquals(1.5f, vm.live.value.scaleFlowGPerS, "else the core's estimate")
        assertEquals(4, s.scaleVolume)
        assertEquals(10, s.scaleStandbyMinutes)
        assertEquals(80, s.scaleBatteryPercent)
    }

    @Test
    fun `the config channel folds in what each response carries`() {
        val vm = build()
        deliver(vm, """{"type":"ScaleConfig","content":{"anti_mistouch":true,"serial":"S1","firmware":"F1"}}""")
        deliver(vm, """{"type":"ScaleConfig","content":{"active_mode":2,"enabled_modes":3}}""")
        val s = vm.ui.value
        assertEquals(true, s.scaleAntiMistouch)
        assertEquals(2, s.scaleActiveMode)
        assertEquals("S1", s.scaleSerial)
        assertEquals("F1", s.scaleFirmware)
    }

    @Test
    fun `a stale scale drops the weight, and a disconnect clears the session`() {
        val vm = build()
        deliver(vm, reading(""","device_volume":5,"device_battery":70"""))
        deliver(vm, """{"type":"ScaleStale"}""")
        assertNull(vm.live.value.scaleWeightG)

        deliver(vm, reading(""","device_volume":5,"device_battery":70"""))
        vm.disconnectScale()
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        val s = vm.ui.value
        assertNull(vm.live.value.scaleWeightG)
        assertNull(s.scaleBatteryPercent)
        assertEquals(DEFAULT_SCALE_VOLUME, s.scaleVolume)
        assertEquals(emptySet(), s.scaleEchoed)
    }
}

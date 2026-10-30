package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import coffee.crema.core.CoreOutput
import coffee.crema.core.Event
import coffee.crema.core.NotificationSource
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android applies the core's water rules (core/shell fixes F9), like web:
 * the counted-water deltas (`Telemetry.counted_ml` — espresso and hot water
 * only; Android used to count descale, clean, steam rinse and air purge too)
 * and the low-water monitor fed with the user's threshold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WaterRulesTest {

    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        VmHarness.idle()
    }

    /** The core's own test `ShotSample` (positive group flow). */
    private val sample = intArrayOf(
        0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5C, 0x80, 0x00, 0x58, 0x00, 0x5A, 0x00, 0x5D, 0x00,
        0x60, 0x40, 0x03, 0x96,
    ).map { it.toByte() }.toByteArray()

    /** Run [raw] through the VM's own core-output path (applied on the core lane). */
    private fun apply(vm: MainViewModel, raw: String) {
        MainViewModel::class.java.getDeclaredMethod("onCoreOutputJson", String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, raw)
        VmHarness.idle()
    }

    private fun countedLitres(vm: MainViewModel): Double =
        MainViewModel::class.java.getDeclaredField("maintenanceTotalLitres")
            .apply { isAccessible = true }
            .getDouble(vm)

    @Test
    fun `water is counted only for espresso and hot water`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        val bridge = VmHarness.bridge(vm)
        var clock = 1_000uL
        // Machine-state codes: Steam 5, Descale 10, SteamRinse 16, Clean 18,
        // AirPurge 20 | Espresso 4, HotWater 6, HotWaterRinse 15.
        for ((code, counts) in listOf(5 to false, 10 to false, 16 to false, 18 to false, 20 to false, 4 to true, 6 to true, 15 to true)) {
            bridge.reset()
            val before = countedLitres(vm)
            apply(vm, bridge.onNotification(NotificationSource.DE1_STATE, byteArrayOf(code.toByte(), 5), clock))
            apply(vm, bridge.onNotification(NotificationSource.DE1_SHOT_SAMPLE, sample, clock))
            apply(vm, bridge.onNotification(NotificationSource.DE1_SHOT_SAMPLE, sample, clock + 250uL))
            clock += 10_000uL
            if (counts) {
                VmHarness.idleUntil("state $code counts", 5_000) { countedLitres(vm) > before }
            } else {
                Thread.sleep(200) // the lane has applied it by now
                VmHarness.idle()
                assertEquals(before, countedLitres(vm), "state $code")
            }
        }
    }

    @Test
    fun `the low-water threshold reaches the core and its warning notifies once`() {
        val vm = VmHarness.build(store)
        VmHarness.awaitPrefsLoaded(vm)
        val bridge = VmHarness.bridge(vm)
        fun waterLow(raw: String) = coreJson.decodeFromString(CoreOutput.serializer(), raw).events.filterIsInstance<Event.WaterLow>()
        // Raw 0 mm → 5 mm depth → 124 ml: above the 110 ml default.
        assertTrue(waterLow(bridge.onNotification(NotificationSource.DE1_WATER_LEVELS, byteArrayOf(0, 0, 5, 0), 0uL)).isEmpty())
        vm.setWaterWarnMl(130f)
        // The push runs on the core lane: report the same level until the raised
        // threshold has reached the core and it warns (once per dip, so a report
        // before the push lands warns nothing and doesn't use the warning up).
        var raw = ""
        var at = 100uL
        VmHarness.idleUntil("the raised threshold to reach the core", 5_000) {
            raw = bridge.onNotification(NotificationSource.DE1_WATER_LEVELS, byteArrayOf(0, 0, 5, 0), at)
            at += 100uL
            waterLow(raw).isNotEmpty()
        }
        assertEquals(1, waterLow(raw).size)
        apply(vm, raw)
        VmHarness.idleUntil("the low-water notice", 5_000) { vm.ui.value.userMessages.any { it.startsWith("Water low") } }
    }
}

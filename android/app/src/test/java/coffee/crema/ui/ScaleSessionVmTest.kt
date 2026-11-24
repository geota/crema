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
    fun `a firmware report re-reads the capabilities it gates`() {
        val vm = build()
        val bridge = VmHarness.bridge(vm)
        bridge.connectScale("BOOKOO_SC", emptyList())
        VmHarness.scaleController(vm).refreshScaleCapabilities("BOOKOO_SC")
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        // Before the serial reply: no auto-stop, no keep-awake clock.
        var caps = vm.ui.value.scaleCapabilities
        assertEquals(false, caps?.auto_stop)
        assertNull(caps?.heartbeat_interval_ms)

        // A Bookoo Mini's `03 0c` reply reporting firmware 1.4.3 (0x008f).
        val serial = intArrayOf(
            0x03, 0x0c, 0x00, 0x8f, 0x53, 0x4e, 0x32, 0x34, 0x30, 0x30, 0x64, 0x36, 0x36, 0x61,
            0x38, 0x39, 0x65, 0x37, 0x01, 0x00,
        ).map { it.toByte() }.toByteArray()
        serial[19] = serial.take(19).fold(0) { a, b -> a xor b.toInt() }.toByte()
        val out = bridge.onNotification(coffee.crema.core.NotificationSource.SCALE_COMMAND, serial, 1_000uL)
        VmHarness.deliverCoreOutput(vm, out)
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        caps = vm.ui.value.scaleCapabilities
        assertEquals(true, caps?.auto_stop)
        assertEquals(60_000u, caps?.heartbeat_interval_ms)
        assertEquals(true, caps?.can_power_off)
        assertEquals("1.4.3", vm.ui.value.scaleFirmware)
    }

    /** A Bookoo `03 0c` serial reply for firmware [fw]. */
    private fun bookooSerial(fw: Int): ByteArray {
        val frame = ByteArray(20)
        frame[0] = 0x03; frame[1] = 0x0c
        frame[2] = (fw shr 8).toByte(); frame[3] = fw.toByte()
        "SN2400d66a89e7".toByteArray().copyInto(frame, 4)
        frame[19] = frame.take(19).fold(0) { a, b -> a xor b.toInt() }.toByte()
        return frame
    }

    @Test
    fun `an Ultra on firmware 4 sets auto-stop per mode, and shows what it set`() {
        val vm = build()
        val bridge = VmHarness.bridge(vm)
        bridge.connectScale("BOOKOO_SC", emptyList())
        val out = bridge.onNotification(coffee.crema.core.NotificationSource.SCALE_COMMAND, bookooSerial(403), 1_000uL)
        VmHarness.deliverCoreOutput(vm, out)
        VmHarness.scaleController(vm).refreshScaleCapabilities("BOOKOO_SC")
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        val caps = vm.ui.value.scaleCapabilities
        assertEquals(listOf(1, 2), caps?.auto_stop_targets?.map { it.id.toInt() })
        // V4's conditions, filter removed included, in display order.
        assertEquals(listOf(0, 2, 1), caps?.auto_stop_modes?.map { it.id.toInt() })
        assertTrue(caps?.modes.orEmpty().isEmpty(), "no Flow / Timer / Auto selector on an Ultra")
        vm.setScaleAutoStopFor(2, 1)
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        assertEquals(mapOf(2 to 1), vm.ui.value.scaleAutoStopFor)
        // An unknown target writes nothing and shows nothing.
        vm.setScaleAutoStopFor(5, 1)
        VmHarness.drainCoreLane(vm)
        VmHarness.idle()
        assertEquals(mapOf(2 to 1), vm.ui.value.scaleAutoStopFor)
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

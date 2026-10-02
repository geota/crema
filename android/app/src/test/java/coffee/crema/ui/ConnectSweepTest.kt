package coffee.crema.ui

import coffee.crema.core.UsbChargingMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The connect sweep, shell side (web `connect-sweep.vitest.ts` parity). */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectSweepTest {
    private fun snapshot(mode: String = "alwaysOn", battery: Int? = 77) = buildConnectSweepSettings(
        fanThresholdC = 48f, steamTwoTap = true, refillPointMm = 9f,
        steamTempC = 150f, steamTimeoutS = 90f, hotWaterTempC = 80f, hotWaterVolumeMl = 150f,
        steamFlowMlS = 1.2f, flushTimeoutS = 4f, flushTempC = 90f, steamEco = false,
        usbChargingMode = mode, tankTempC = 35f, batteryPercent = battery,
    )

    @Test
    fun snapshotMapsSettingsTankTargetAndBattery() {
        val s = snapshot()
        assertEquals(48f, s.fanThresholdC)
        assertEquals(true, s.steamTwoTap)
        assertEquals(9f, s.refillPointMm)
        assertEquals(150f, s.steamTempC)
        assertEquals(35f, s.tankTempC)
        assertEquals(77.toUByte(), s.batteryPercent)
        assertEquals(UsbChargingMode.AlwaysOn, s.usbCharging)
        // Heater tweaks with no setting stay null → the core's de1app defaults.
        assertNull(s.phase1FlowMlS)
        assertNull(s.hotWaterIdleTempC)
        assertNull(snapshot(battery = null).batteryPercent)
        assertEquals(100.toUByte(), snapshot(battery = 140).batteryPercent)
    }

    @Test
    fun unsetOrUnknownChargingModesReadAsAlwaysOn() {
        assertEquals(UsbChargingMode.AlwaysOn, usbChargingModeOf("?"))
        assertEquals(UsbChargingMode.AlwaysOn, usbChargingModeOf(""))
        assertEquals(UsbChargingMode.AlwaysOn, usbChargingModeOf(null))
        assertEquals(UsbChargingMode.Smart, usbChargingModeOf("smart"))
        assertEquals(UsbChargingMode.SmartHigh, usbChargingModeOf("smartHigh"))
    }

    @Test
    fun tabletChargingDefaultsToAlwaysOn() {
        assertEquals("alwaysOn", coffee.crema.settings.AppPrefs().usbChargingMode)
    }

    @Test
    fun theSweepRunsOnEachReadyAndTheChargerIsCheckedEveryMinute() = runTest {
        var sweeps = 0
        var ticks = 0
        val runner = ConnectSweepRunner(TestScope(testScheduler), sweep = { sweeps++ }, tick = { ticks++ })
        runner.onReady()
        assertEquals(1, sweeps, "sweep runs at once on connect")
        advanceTimeBy(USB_CHARGER_CHECK_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(1, ticks)
        advanceTimeBy(USB_CHARGER_CHECK_INTERVAL_MS)
        runCurrent()
        assertEquals(2, ticks)
        // A drop stops the minute check; a reconnect sweeps again.
        runner.onDropped()
        advanceTimeBy(5 * USB_CHARGER_CHECK_INTERVAL_MS)
        runCurrent()
        assertEquals(2, ticks)
        runner.onReady()
        assertEquals(2, sweeps)
        runner.onDropped()
    }
}

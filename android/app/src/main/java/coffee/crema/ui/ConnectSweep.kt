package coffee.crema.ui

import coffee.crema.core.ConnectSweepSettings
import coffee.crema.core.UsbChargingMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The DE1 connect sweep, shell side (web twin: `lib/state/connect-sweep.ts`).
 *
 * The list of writes is core-owned (`CremaCore::connect_sweep`,
 * `de1_domain::connect_sweep` — de1app `later_new_de1_connection_setup` +
 * `set_heater_tweaks`, Decenza `sendInitialSettings`): this file only builds
 * the settings snapshot the core needs and drives de1app's once-a-minute
 * USB-charger check while the DE1 stays connected.
 */

/** de1app `schedule_minute_task`: the charger decision is re-sent every 60 s. */
const val USB_CHARGER_CHECK_INTERVAL_MS = 60_000L

/** The persisted spelling of de1app's `smart_battery_charging` modes. */
internal fun usbChargingModeOf(pref: String): UsbChargingMode = when (pref) {
    "smartHigh" -> UsbChargingMode.SmartHigh
    "alwaysOn" -> UsbChargingMode.AlwaysOn
    else -> UsbChargingMode.Smart
}

/**
 * The core's sweep snapshot from the user's settings, the active profile's
 * tank-temperature target (0 = none) and the tablet battery (null =
 * unreadable). Heater tweaks Crema has no setting for stay null, so the core
 * asserts de1app's defaults. Pure, so it unit-tests without the VM.
 */
internal fun buildConnectSweepSettings(
    fanThresholdC: Float,
    steamTwoTap: Boolean,
    refillPointMm: Float,
    steamTempC: Float,
    steamTimeoutS: Float,
    hotWaterTempC: Float,
    hotWaterVolumeMl: Float,
    steamFlowMlS: Float,
    flushTimeoutS: Float,
    flushTempC: Float,
    steamEco: Boolean,
    usbChargingMode: String,
    tankTempC: Float,
    batteryPercent: Int?,
): ConnectSweepSettings = ConnectSweepSettings(
    fanThresholdC = fanThresholdC,
    steamTwoTap = steamTwoTap,
    refillPointMm = refillPointMm,
    steamTempC = steamTempC,
    steamTimeoutS = steamTimeoutS,
    hotWaterTempC = hotWaterTempC,
    hotWaterVolumeMl = hotWaterVolumeMl,
    steamFlowMlS = steamFlowMlS,
    flushTimeoutS = flushTimeoutS,
    flushTempC = flushTempC,
    steamEco = steamEco,
    tankTempC = if (tankTempC.isFinite()) tankTempC else 0f,
    usbCharging = usbChargingModeOf(usbChargingMode),
    batteryPercent = batteryPercent?.coerceIn(0, 100)?.toUByte(),
)

/** [buildConnectSweepSettings] from the UI state. */
internal fun MainUiState.connectSweepSettings(tankTempC: Float, batteryPercent: Int?): ConnectSweepSettings =
    buildConnectSweepSettings(
        fanThresholdC = fanThresholdC,
        steamTwoTap = steamTwoTap,
        refillPointMm = refillPointMm(),
        steamTempC = qcSteamTempC,
        steamTimeoutS = qcSteamTimeS,
        hotWaterTempC = qcHotWaterTempC,
        hotWaterVolumeMl = qcHotWaterVolumeMl,
        steamFlowMlS = qcSteamFlowMlS,
        flushTimeoutS = qcFlushTimeS,
        flushTempC = qcFlushTempC,
        steamEco = steamEco,
        usbChargingMode = usbChargingMode,
        tankTempC = tankTempC,
        batteryPercent = batteryPercent,
    )

/**
 * Runs the sweep on every transition into ready and the USB-charger check
 * every minute while ready; stops the check on any drop.
 */
internal class ConnectSweepRunner(
    private val scope: CoroutineScope,
    private val sweep: () -> Unit,
    private val tick: () -> Unit,
    private val intervalMs: Long = USB_CHARGER_CHECK_INTERVAL_MS,
) {
    private var job: Job? = null

    /** The DE1 link became ready: sweep now, then check the charger each minute. */
    fun onReady() {
        job?.cancel()
        sweep()
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                tick()
            }
        }
    }

    /** The DE1 link dropped or was closed: stop the minute check. */
    fun onDropped() {
        job?.cancel()
        job = null
    }
}

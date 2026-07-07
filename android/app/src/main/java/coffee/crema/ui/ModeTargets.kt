package coffee.crema.ui

import coffee.crema.core.MachineState
import coffee.crema.core.MmrRegister
import coffee.crema.core.celsiusToFahrenheit

// Service-mode (steam / hot water / flush) display targets — the values the
// Brew mode chips and the mode timers show. One shared derivation for the
// tablet BrewScreen and the phone PhoneBrewScreen, mirroring the web
// BrewDashboard's MODE_TARGET_SEC / MODE_TARGET_LABEL.

/** First positive finite value, else the fallback. A partial / pre-handshake
 *  ShotSettings payload can carry literal zeros, which are meaningless as
 *  targets — treat them as missing (web BrewDashboard `posOr` parity). */
fun posOr(v: Float?, fallback: Float): Float =
    if (v != null && v.isFinite() && v > 0f) v else fallback

/** The service-mode targets. Precedence per field: live machine value (the
 *  connect-time `ShotSettingsRead`, echoed on change) → the user's persisted
 *  Quick-Controls value → legacy de1app default. The QC value is the user's
 *  intent; the machine value is what the firmware currently has loaded. */
data class ModeTargets(
    val steamTempC: Float,
    val steamTimeoutS: Float,
    val hotWaterTempC: Float,
    val hotWaterVolumeMl: Float,
    val hotWaterTimeoutS: Float,
    val flushTempC: Float,
    val flushTimeS: Float,
)

fun modeTargets(ui: MainUiState): ModeTargets {
    val ss = ui.de1ShotSettings
    // The machine's stored flush setpoint (FlushTemp MMR, deci-°C) — read at
    // connect but previously discarded, so Android showed the QC value where
    // web showed the machine's (review #41 divergence).
    val machineFlushC = ui.de1MachineInfo[MmrRegister.FlushTemp]?.toFloat()?.div(10f)
    return ModeTargets(
        steamTempC = posOr(ss?.steam_temp, posOr(ui.qcSteamTempC, 148f)),
        steamTimeoutS = posOr(ss?.steam_timeout, posOr(ui.qcSteamTimeS, 90f)),
        hotWaterTempC = posOr(ss?.hot_water_temp, posOr(ui.qcHotWaterTempC, 92f)),
        hotWaterVolumeMl = posOr(ss?.hot_water_volume, posOr(ui.qcHotWaterVolumeMl, 250f)),
        hotWaterTimeoutS = posOr(ss?.hot_water_timeout, 30f),
        flushTempC = posOr(machineFlushC, posOr(ui.qcFlushTempC, 95f)),
        flushTimeS = posOr(ui.qcFlushTimeS, 4f),
    )
}

/** The active service mode's display label, or null when no mode is running. */
fun modeLabel(state: MachineState?): String? = when (state) {
    MachineState.Steam -> "Steaming"
    MachineState.HotWater -> "Hot water"
    MachineState.HotWaterRinse -> "Flushing"
    else -> null
}

/** The running mode's target ceiling, seconds — the timeout the firmware
 *  enforces (the cap, not the typical session length); null when idle. */
fun modeTargetSeconds(state: MachineState?, t: ModeTargets): Float? = when (state) {
    MachineState.Steam -> t.steamTimeoutS
    MachineState.HotWater -> t.hotWaterTimeoutS
    MachineState.HotWaterRinse -> t.flushTimeS
    else -> null
}

/** Live `elapsed / total s` counter for an active mode's chip sub-label. */
fun modeRunningSub(elapsedMs: Long, targetS: Float): String =
    fmt("%.1f / %.0f s", elapsedMs / 1000f, targetS)

/** Compact whole-degree temperature for the dense mode chips, e.g. "148 °C". */
fun formatTempCompact(celsius: Float, unit: String): String =
    if (unit == "F") fmt("%.0f °F", celsiusToFahrenheit(celsius))
    else fmt("%.0f °C", celsius)

package coffee.crema.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coffee.crema.core.MachineState
import coffee.crema.core.MmrRegister
import coffee.crema.core.ModeTargetInputs
import coffee.crema.core.ModeTargets
import coffee.crema.core.celsiusToFahrenheit
import coffee.crema.core.resolveModeTargets
import kotlinx.serialization.json.Json

// Service-mode (steam / hot water / flush) display targets — the values the
// Brew mode chips and the mode timers show. The resolution (machine
// ShotSettings/MMR value → the user's Quick-Controls value → legacy de1app
// default, with a partial payload's literal 0 treated as missing) lives in
// the core — `de1_domain::resolve_mode_targets`, review #41: this file and
// the web BrewDashboard's derivation had drifted. One shared call for the
// tablet BrewScreen and the phone PhoneBrewScreen.

/** Wire codec for the resolver FFI round-trip. */
private val modeJson = Json { ignoreUnknownKeys = true }

/** The legacy de1app defaults, should the bridge ever reject (never in
 *  practice) — matches the core's own fallback tier. */
private val MODE_TARGET_FALLBACK = ModeTargets(
    steamTempC = 148f, steamTimeoutS = 90f,
    hotWaterTempC = 92f, hotWaterVolumeMl = 250f, hotWaterTimeoutS = 30f,
    flushTempC = 95f, flushTimeS = 4f,
)

/** Resolve the service-mode targets from the UI snapshot via the core.
 *  The FlushTemp MMR word crosses raw (deci-°C) — the ÷10 lives core-side. */
fun modeTargets(ui: MainUiState): ModeTargets {
    val ss = ui.de1ShotSettings
    val inputs = ModeTargetInputs(
        machineSteamTempC = ss?.steam_temp,
        machineSteamTimeoutS = ss?.steam_timeout,
        machineHotWaterTempC = ss?.hot_water_temp,
        machineHotWaterVolumeMl = ss?.hot_water_volume,
        machineHotWaterTimeoutS = ss?.hot_water_timeout,
        machineFlushTempDeciC = ui.de1MachineInfo[MmrRegister.FlushTemp]?.toFloat(),
        qcSteamTempC = ui.qcSteamTempC,
        qcSteamTimeS = ui.qcSteamTimeS,
        qcHotWaterTempC = ui.qcHotWaterTempC,
        qcHotWaterVolumeMl = ui.qcHotWaterVolumeMl,
        qcFlushTempC = ui.qcFlushTempC,
        qcFlushTimeS = ui.qcFlushTimeS,
    )
    return runCatching {
        modeJson.decodeFromString(
            ModeTargets.serializer(),
            resolveModeTargets(modeJson.encodeToString(ModeTargetInputs.serializer(), inputs)),
        )
    }.getOrDefault(MODE_TARGET_FALLBACK)
}

/**
 * [modeTargets] memoized against its actual inputs, for composable call
 * sites — the Brew screens recompose on every telemetry tick, and the
 * resolver is an FFI + JSON round-trip that must not ride that hot path.
 */
@Composable
fun rememberModeTargets(ui: MainUiState): ModeTargets {
    val flushDeci = ui.de1MachineInfo[MmrRegister.FlushTemp]
    return remember(
        ui.de1ShotSettings, flushDeci, ui.qcSteamTempC, ui.qcSteamTimeS,
        ui.qcHotWaterTempC, ui.qcHotWaterVolumeMl, ui.qcFlushTempC, ui.qcFlushTimeS,
    ) { modeTargets(ui) }
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

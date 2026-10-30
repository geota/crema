package coffee.crema.ui

import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField
import coffee.crema.settings.ConfigSnapshot
import coffee.crema.settings.Setting
import coffee.crema.settings.carries

/*
 * Remote → standalone (T6, spec §7.5): when a remote leaves its hub it may
 * take the hub's machine and session values as its own. Only the last
 * applied snapshot counts (never a pending edit), and only the adoptable
 * settings it carries: A1–A11, A22–A25 and B1–B13.
 */

/** The confirmation a leaving remote shows: [changed] of its own values
 *  differ from the hub's. */
data class AdoptPrompt(val hubName: String, val changed: Int)

/** The hub's value of an adoptable [setting] in [cfg]. */
internal fun hubValue(cfg: ConfigSnapshot, setting: Setting): Any? = when (setting) {
    Setting.QC_STEAM_TEMP -> cfg.qcSteamTempC
    Setting.QC_STEAM_TIME -> cfg.qcSteamTimeS
    Setting.QC_STEAM_FLOW -> cfg.qcSteamFlowMlS
    Setting.QC_HOT_WATER_TEMP -> cfg.qcHotWaterTempC
    Setting.QC_HOT_WATER_VOLUME -> cfg.qcHotWaterVolumeMl
    Setting.QC_HOT_WATER_MODE -> cfg.qcHotWaterMode
    Setting.QC_HOT_WATER_TIME -> cfg.qcHotWaterTimeS
    Setting.QC_HOT_WATER_TIME_NO_LIMIT -> cfg.qcHotWaterTimeNoLimit
    Setting.QC_HOT_WATER_RATIO -> cfg.qcHotWaterRatio
    Setting.QC_FLUSH_TIME -> cfg.qcFlushTimeS
    Setting.QC_FLUSH_TEMP -> cfg.qcFlushTempC
    Setting.STEAM_ECO -> cfg.steamEco
    Setting.STEAM_TWO_TAP -> cfg.steamTwoTap
    Setting.FAN_THRESHOLD -> cfg.fanThresholdC
    Setting.WATER_REFILL_POINT -> cfg.waterRefillPointMm
    Setting.STOP_ON_WEIGHT -> cfg.stopOnWeight
    Setting.AUTO_TARE -> cfg.autoTare
    Setting.MAX_SHOT_DURATION -> cfg.maxShotDurationS
    Setting.VOLUME_STOP_WITH_SCALE -> cfg.volumeStopWithScale
    Setting.REQUIRE_SCALE -> cfg.requireScale
    Setting.PRE_FLUSH -> cfg.preFlush
    Setting.STEAM_PURGE -> cfg.steamPurge
    Setting.IDLE_SLEEP -> cfg.idleSleepMin
    Setting.GRINDER_MODEL -> cfg.grinderModel
    Setting.QC_GRIND -> cfg.qcGrind
    Setting.ACTIVE_PROFILE -> cfg.activeProfileId
    Setting.ACTIVE_BEAN -> cfg.activeBeanId
    Setting.BREW_PARAMS -> if (cfg.brewDoseG != null && cfg.brewYieldG != null && cfg.brewTempC != null) {
        BrewParams(cfg.brewDoseG, cfg.brewYieldG, cfg.brewTempC, cfg.brewPreinfuseS)
    } else {
        null
    }
    else -> null
}

/** This device's own value of an adoptable [setting]. */
internal fun ownValue(own: MainUiState, setting: Setting): Any? = when (setting) {
    Setting.QC_STEAM_TEMP -> own.qcSteamTempC
    Setting.QC_STEAM_TIME -> own.qcSteamTimeS
    Setting.QC_STEAM_FLOW -> own.qcSteamFlowMlS
    Setting.QC_HOT_WATER_TEMP -> own.qcHotWaterTempC
    Setting.QC_HOT_WATER_VOLUME -> own.qcHotWaterVolumeMl
    Setting.QC_HOT_WATER_MODE -> own.qcHotWaterMode
    Setting.QC_HOT_WATER_TIME -> own.qcHotWaterTimeS
    Setting.QC_HOT_WATER_TIME_NO_LIMIT -> own.qcHotWaterTimeNoLimit
    Setting.QC_HOT_WATER_RATIO -> own.qcHotWaterRatio
    Setting.QC_FLUSH_TIME -> own.qcFlushTimeS
    Setting.QC_FLUSH_TEMP -> own.qcFlushTempC
    Setting.STEAM_ECO -> own.steamEco
    Setting.STEAM_TWO_TAP -> own.steamTwoTap
    Setting.FAN_THRESHOLD -> own.fanThresholdC
    Setting.WATER_REFILL_POINT -> own.refillPointMm()
    Setting.STOP_ON_WEIGHT -> own.stopOnWeight
    Setting.AUTO_TARE -> own.autoTare
    Setting.MAX_SHOT_DURATION -> own.maxShotDurationS
    Setting.VOLUME_STOP_WITH_SCALE -> own.volumeStopWithScale
    Setting.REQUIRE_SCALE -> own.requireScale
    Setting.PRE_FLUSH -> own.preFlush
    Setting.STEAM_PURGE -> own.steamPurge
    Setting.IDLE_SLEEP -> own.idleSleepMin
    Setting.GRINDER_MODEL -> own.grinderModel
    Setting.QC_GRIND -> own.qcGrind
    Setting.ACTIVE_PROFILE -> own.activeProfileId
    Setting.ACTIVE_BEAN -> own.activeBeanId
    Setting.BREW_PARAMS -> own.brewParams
    else -> null
}

/** The adoptable settings [cfg] carries whose value differs from [own]'s. */
fun adoptableChanges(own: MainUiState, cfg: ConfigSnapshot): Set<Setting> =
    Setting.entries.filter { it.adoptable && cfg.carries(it) && hubValue(cfg, it) != ownValue(own, it) }.toSet()

/**
 * [own] with the hub's values for [changes] (pure). The active bean is taken
 * only when this library has it ([hasBean]), else cleared; the active
 * profile's id is taken as is (a custom one must already be adopted into the
 * library). Every value goes through the same range check a local edit does
 * (AC-57), before anything reaches this device's own DE1: a Quick Controls
 * value or a brew override the local command would refuse keeps this
 * device's own; the refill point and the fan threshold clamp; the idle sleep
 * snaps to a choice; a max shot duration that isn't a positive number keeps
 * this device's own.
 */
fun adopt(own: MainUiState, cfg: ConfigSnapshot, changes: Set<Setting>, hasBean: (String) -> Boolean): MainUiState {
    var s = own
    for (setting in changes) {
        s = when (setting) {
            Setting.QC_STEAM_TEMP -> s.qc(QcField.STEAM_TEMP, cfg.qcSteamTempC) { copy(qcSteamTempC = it) }
            Setting.QC_STEAM_TIME -> s.qc(QcField.STEAM_TIME, cfg.qcSteamTimeS) { copy(qcSteamTimeS = it) }
            Setting.QC_STEAM_FLOW -> s.qc(QcField.STEAM_FLOW, cfg.qcSteamFlowMlS) { copy(qcSteamFlowMlS = it) }
            Setting.QC_HOT_WATER_TEMP -> s.qc(QcField.HOT_WATER_TEMP, cfg.qcHotWaterTempC) { copy(qcHotWaterTempC = it) }
            Setting.QC_HOT_WATER_VOLUME -> s.qc(QcField.HOT_WATER_VOLUME, cfg.qcHotWaterVolumeMl) { copy(qcHotWaterVolumeMl = it) }
            // Carried ⟹ non-null (see `carries`); the core clamps the dose on push.
            Setting.QC_HOT_WATER_MODE -> cfg.qcHotWaterMode?.let { s.copy(qcHotWaterMode = it) } ?: s
            Setting.QC_HOT_WATER_TIME -> cfg.qcHotWaterTimeS?.let { s.copy(qcHotWaterTimeS = it) } ?: s
            Setting.QC_HOT_WATER_TIME_NO_LIMIT -> cfg.qcHotWaterTimeNoLimit?.let { s.copy(qcHotWaterTimeNoLimit = it) } ?: s
            Setting.QC_HOT_WATER_RATIO -> cfg.qcHotWaterRatio?.let { s.copy(qcHotWaterRatio = it) } ?: s
            Setting.QC_FLUSH_TIME -> s.qc(QcField.FLUSH_TIME, cfg.qcFlushTimeS) { copy(qcFlushTimeS = it) }
            Setting.QC_FLUSH_TEMP -> s.qc(QcField.FLUSH_TEMP, cfg.qcFlushTempC) { copy(qcFlushTempC = it) }
            Setting.STEAM_ECO -> s.copy(steamEco = cfg.steamEco)
            Setting.STEAM_TWO_TAP -> s.copy(steamTwoTap = cfg.steamTwoTap)
            // The machine write clamps to 0..60 (setFanThreshold).
            Setting.FAN_THRESHOLD -> s.copy(fanThresholdC = cfg.fanThresholdC.coerceIn(0f, 60f))
            Setting.WATER_REFILL_POINT -> s.copy(waterRefillPointMm = cfg.waterRefillPointMm.coerceIn(REFILL_POINT_MIN_MM, REFILL_POINT_MAX_MM))
            Setting.STOP_ON_WEIGHT -> s.copy(stopOnWeight = cfg.stopOnWeight)
            Setting.AUTO_TARE -> s.copy(autoTare = cfg.autoTare)
            Setting.MAX_SHOT_DURATION -> if (cfg.maxShotDurationS.isFinite() && cfg.maxShotDurationS > 0f) s.copy(maxShotDurationS = cfg.maxShotDurationS) else s
            Setting.VOLUME_STOP_WITH_SCALE -> s.copy(volumeStopWithScale = cfg.volumeStopWithScale)
            Setting.REQUIRE_SCALE -> s.copy(requireScale = cfg.requireScale)
            Setting.PRE_FLUSH -> s.copy(preFlush = cfg.preFlush)
            Setting.STEAM_PURGE -> s.copy(steamPurge = cfg.steamPurge)
            Setting.IDLE_SLEEP -> idleSleepChoices().let { c -> s.copy(idleSleepMin = c[idleSleepIndex(cfg.idleSleepMin, c)]) }
            Setting.GRINDER_MODEL -> s.copy(grinderModel = cfg.grinderModel)
            Setting.QC_GRIND -> cfg.qcGrind?.let { g -> s.qc(QcField.GRIND, g) { copy(qcGrind = it) } } ?: s.copy(qcGrind = null)
            Setting.ACTIVE_PROFILE -> cfg.activeProfileId?.let { s.copy(activeProfileId = it) } ?: s
            Setting.ACTIVE_BEAN -> s.copy(activeBeanId = cfg.activeBeanId?.takeIf(hasBean))
            Setting.BREW_PARAMS -> when (val p = hubValue(cfg, Setting.BREW_PARAMS) as BrewParams?) {
                null -> s.copy(brewParams = null)
                else -> if (MachineCommand.AdjustBrew(p.dose, p.yieldOut, p.brewTemp, p.preinf).refusal() == null) s.copy(brewParams = p) else s
            }
            else -> s
        }
    }
    return s
}

/** [set] with [value] when the local Quick Controls command would accept it, else this. */
private inline fun MainUiState.qc(field: QcField, value: Float, set: MainUiState.(Float) -> MainUiState): MainUiState =
    if (field.refusal(value) == null) set(value) else this

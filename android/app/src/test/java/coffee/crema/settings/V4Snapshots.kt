package coffee.crema.settings

import coffee.crema.ui.MainUiState
import coffee.crema.ui.refillPointMm

/**
 * A v4 hub's config snapshot holding [s]'s values: every required field is
 * set (relay v4 R3b), so a test builds on it with `copy`. The machine
 * registers a hub may not have read stay null.
 */
internal fun v4Snapshot(s: MainUiState = MainUiState(), rev: Long = 0): ConfigSnapshot = ConfigSnapshot(
    activeProfileId = s.activeProfileId,
    activeBeanId = s.activeBeanId,
    stopOnWeight = s.stopOnWeight,
    autoTare = s.autoTare,
    maxShotDurationS = s.maxShotDurationS,
    grinderModel = s.grinderModel,
    qcSteamTimeS = s.qcSteamTimeS,
    qcSteamFlowMlS = s.qcSteamFlowMlS,
    qcSteamTempC = s.qcSteamTempC,
    qcHotWaterTempC = s.qcHotWaterTempC,
    qcHotWaterVolumeMl = s.qcHotWaterVolumeMl,
    qcFlushTimeS = s.qcFlushTimeS,
    qcFlushTempC = s.qcFlushTempC,
    qcGrind = s.qcGrind,
    brewDoseG = s.brewParams?.dose,
    brewYieldG = s.brewParams?.yieldOut,
    brewTempC = s.brewParams?.brewTemp,
    brewPreinfuseS = s.brewParams?.preinf,
    rev = rev,
    steamEco = s.steamEco,
    steamTwoTap = s.steamTwoTap,
    fanThresholdC = s.fanThresholdC,
    waterRefillPointMm = s.refillPointMm(),
    lineFrequencyOverride = s.lineFreqOverride,
    volumeStopWithScale = s.volumeStopWithScale,
    requireScale = s.requireScale,
    preFlush = s.preFlush,
    steamPurge = s.steamPurge,
    idleSleepMin = s.idleSleepMin,
)

/** [v4Snapshot] as a hub's `config` frame payload, after [edit]. */
internal fun v4SnapshotJson(rev: Long = 0, edit: (ConfigSnapshot) -> ConfigSnapshot = { it }): String =
    coffee.crema.ui.coreJson.encodeToString(ConfigSnapshot.serializer(), edit(v4Snapshot(rev = rev)))

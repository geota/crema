package coffee.crema.ui

import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField
import coffee.crema.settings.ConfigSnapshot
import coffee.crema.settings.MirroredSettings
import coffee.crema.settings.carries

/*
 * The effective projection (spec §7.2): what the screens show. On a normal
 * device or a hub it is the device's own state, unchanged. On a remote the
 * mirror slice is laid over the own values for the machine and session
 * settings (groups A and B), in order: the hub's last snapshot, then edits
 * still awaiting the hub's answer, then the hub's custom profile. Personal
 * settings (group C) are never overlaid. Nothing here is persisted: the VM
 * persists from its own state.
 */

/** This state as the screens show it. The identity while [MainUiState.mirror]
 *  is null; memoised on the source instance otherwise. */
fun MainUiState.effective(): MainUiState {
    val m = mirror ?: return this
    EffectiveMemo.last?.let { (source, projected) -> if (source === this) return projected }
    val projected = project(m)
    EffectiveMemo.last = this to projected
    return projected
}

/** The last projection, by source identity (one entry is enough: the UI
 *  pipeline and the VM project the same latest state). */
private object EffectiveMemo {
    @Volatile var last: Pair<MainUiState, MainUiState>? = null
}

private fun MainUiState.project(m: MirroredSettings): MainUiState {
    var s = this
    m.snapshot?.let { s = s.withSnapshot(it, m) }
    for (edit in m.pending.values.sortedBy { it.generation }) s = edit.command.applyTo(s)
    return s
}

/** The hub's known values from [cfg]. Units are personal now (C) and stay
 *  this device's own. A profile id this device can't show keeps its own. */
private fun MainUiState.withSnapshot(cfg: ConfigSnapshot, m: MirroredSettings): MainUiState {
    val overlay = m.profileOverlay
    val profileId = cfg.activeProfileId?.takeIf { id -> overlay?.id == id || profiles.any { it.id == id } } ?: activeProfileId
    val brew = if (cfg.brewDoseG != null && cfg.brewYieldG != null && cfg.brewTempC != null) {
        BrewParams(cfg.brewDoseG, cfg.brewYieldG, cfg.brewTempC, cfg.brewPreinfuseS)
    } else {
        null
    }
    return copy(
        activeProfileId = profileId,
        mirroredProfile = overlay,
        mirroredProfileJson = m.profileJson,
        mirroredBeanSummary = m.beanSummary,
        brewParams = brew,
        activeBeanId = cfg.activeBeanId,
        stopOnWeight = cfg.stopOnWeight,
        autoTare = cfg.autoTare,
        maxShotDurationS = cfg.maxShotDurationS,
        grinderModel = cfg.grinderModel,
        qcSteamTimeS = cfg.qcSteamTimeS,
        qcSteamFlowMlS = cfg.qcSteamFlowMlS,
        qcSteamTempC = cfg.qcSteamTempC,
        qcHotWaterTempC = cfg.qcHotWaterTempC,
        qcHotWaterVolumeMl = cfg.qcHotWaterVolumeMl,
        qcFlushTimeS = cfg.qcFlushTimeS,
        qcFlushTempC = cfg.qcFlushTempC,
        qcGrind = cfg.qcGrind,
    )
}

/** What a config command shows, without its side effects: the #203
 *  `showConfig` body as a pure function. */
fun MachineCommand.Config.applyTo(s: MainUiState): MainUiState = when (this) {
    // Switching profiles clears any Quick-Controls override, and the hub's
    // custom profile no longer applies.
    is MachineCommand.SetActiveProfile ->
        s.copy(activeProfileId = id, brewParams = null, mirroredProfile = null, mirroredProfileJson = null)
    is MachineCommand.SetActiveBean -> s.copy(activeBeanId = id)
    is MachineCommand.SetStopOnWeight -> s.copy(stopOnWeight = on)
    is MachineCommand.SetAutoTare -> s.copy(autoTare = on)
    is MachineCommand.SetQc -> when (field) {
        QcField.STEAM_TIME -> s.copy(qcSteamTimeS = value)
        QcField.STEAM_FLOW -> s.copy(qcSteamFlowMlS = value)
        QcField.STEAM_TEMP -> s.copy(qcSteamTempC = value)
        QcField.HOT_WATER_TEMP -> s.copy(qcHotWaterTempC = value)
        QcField.HOT_WATER_VOLUME -> s.copy(qcHotWaterVolumeMl = value)
        QcField.FLUSH_TIME -> s.copy(qcFlushTimeS = value)
        QcField.FLUSH_TEMP -> s.copy(qcFlushTempC = value)
        QcField.GRIND -> s.copy(qcGrind = value)
    }
    is MachineCommand.AdjustBrew -> s.copy(brewParams = BrewParams(dose, yieldOut, brewTemp, preinf))
    MachineCommand.ResetBrew -> s.copy(brewParams = null)
}

/** A remote is waiting for its hub's first snapshot: derived readouts (mode
 *  targets, stop targets, the brew targets) show "—", not this device's own
 *  values as the hub's (spec §7.2, "Unknown keys"). */
val MainUiState.awaitingHubValues: Boolean get() = mirror != null && mirror.snapshot == null

/**
 * Whether this remote knows the hub's value of [setting]. Personal settings
 * are always this device's own; a snapshot setting is known once a snapshot
 * carries it; a setting no snapshot carries (the scale's) is not known here.
 * On a normal device or a hub, everything is known.
 */
fun MainUiState.knownOnRemote(setting: coffee.crema.settings.Setting): Boolean {
    val m = mirror ?: return true
    return when (setting.mirroredVia) {
        coffee.crema.settings.MirrorSource.NONE -> true
        coffee.crema.settings.MirrorSource.SNAPSHOT -> m.snapshot?.carries(setting) ?: false
        coffee.crema.settings.MirrorSource.SCALE_ECHO, coffee.crema.settings.MirrorSource.NONE_AVAILABLE -> false
    }
}

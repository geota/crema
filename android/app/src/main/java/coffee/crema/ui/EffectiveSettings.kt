package coffee.crema.ui

import coffee.crema.core.MmrRegister
import coffee.crema.machine.MachineCommand
import kotlin.math.roundToInt
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
        // Known only from a hub that sends them (§6.1); else this device's own.
        steamEco = cfg.steamEco ?: steamEco,
        steamTwoTap = cfg.steamTwoTap ?: steamTwoTap,
        fanThresholdC = cfg.fanThresholdC ?: fanThresholdC,
        waterRefillPointMm = cfg.waterRefillPointMm ?: waterRefillPointMm,
        volumeStopWithScale = cfg.volumeStopWithScale ?: volumeStopWithScale,
        requireScale = cfg.requireScale ?: requireScale,
        preFlush = cfg.preFlush ?: preFlush,
        steamPurge = cfg.steamPurge ?: steamPurge,
        idleSleepMin = cfg.idleSleepMin ?: idleSleepMin,
        // The hub's machine registers (A12, A13, A15) and its line frequency (A14).
        de1MachineInfo = de1MachineInfo + listOfNotNull(
            cfg.heaterVoltage?.let { MmrRegister.HeaterVoltage to it.toUInt() },
            cfg.flowMultiplier?.let { MmrRegister.CalibrationFlowMultiplier to (it * 1000f).roundToInt().toUInt() },
            cfg.cupWarmerTempC?.let { MmrRegister.CupWarmerTemp to it.toUInt() },
        ),
        lineFreqHz = cfg.lineFrequencyHz ?: lineFreqHz,
        lineFreqOverride = cfg.lineFrequencyOverride ?: lineFreqOverride,
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
        coffee.crema.settings.MirrorSource.SCALE_ECHO -> setting in scaleEchoed
        coffee.crema.settings.MirrorSource.NONE_AVAILABLE -> false
    }
}

/** How a setting's row behaves on this device (spec §7.6). */
enum class SettingAvailability { EDITABLE, VIA_HUB, PENDING, UNCONFIRMED, READ_ONLY, VIEW_ONLY, UNKNOWN, OFFLINE }

/**
 * The one function every settings row and Quick Controls row asks (§7.6):
 * on a normal device or a hub, and for personal settings, EDITABLE. On a
 * remote, a value it doesn't know is UNKNOWN; a relayed setting is VIA_HUB
 * (PENDING while an edit awaits the hub, UNCONFIRMED when the hub never
 * answered, OFFLINE while the link redials, VIEW_ONLY on a view-only
 * remote); every other machine or session setting is READ_ONLY.
 */
fun MainUiState.availability(setting: coffee.crema.settings.Setting): SettingAvailability {
    val m = mirror ?: return SettingAvailability.EDITABLE
    if (setting.group == coffee.crema.settings.SettingGroup.C_PERSONAL) return SettingAvailability.EDITABLE
    if (!knownOnRemote(setting)) return SettingAvailability.UNKNOWN
    return when (setting.onRemote) {
        coffee.crema.settings.OnRemote.RELAYED -> when {
            mirrorViewOnly -> SettingAvailability.VIEW_ONLY
            m.reconnecting || mirrorReconnecting -> SettingAvailability.OFFLINE
            setting in m.unconfirmed -> SettingAvailability.UNCONFIRMED
            setting in m.pending -> SettingAvailability.PENDING
            else -> SettingAvailability.VIA_HUB
        }
        coffee.crema.settings.OnRemote.READ_ONLY -> SettingAvailability.READ_ONLY
        coffee.crema.settings.OnRemote.LOCAL -> SettingAvailability.EDITABLE
    }
}

/** Whether the row's control takes input. */
val SettingAvailability.editable: Boolean
    get() = this == SettingAvailability.EDITABLE || this == SettingAvailability.VIA_HUB ||
        this == SettingAvailability.PENDING || this == SettingAvailability.UNCONFIRMED

/** The line a row shows under its title on a remote, or null. */
fun MainUiState.availabilityNote(setting: coffee.crema.settings.Setting): String? {
    val hub = remoteHubLabel()
    return when (availability(setting)) {
        SettingAvailability.EDITABLE, SettingAvailability.VIA_HUB -> null
        SettingAvailability.PENDING -> "Sending to $hub…"
        SettingAvailability.UNCONFIRMED -> "$hub didn’t answer — this may not have been applied"
        SettingAvailability.READ_ONLY -> "Change on $hub"
        SettingAvailability.VIEW_ONLY -> "View only — $hub hasn’t allowed changes from this device"
        SettingAvailability.OFFLINE -> "Reconnecting to $hub…"
        SettingAvailability.UNKNOWN -> when {
            setting.mirroredVia == coffee.crema.settings.MirrorSource.NONE_AVAILABLE -> "Change on $hub"
            mirror?.snapshot == null -> "Waiting for $hub"
            setting.mirroredVia == coffee.crema.settings.MirrorSource.SNAPSHOT -> "Update Crema on $hub to see this here"
            else -> "Waiting for $hub"
        }
    }
}

/** [this] plus the scale settings a weight-stream sample reported; the same
 *  set when nothing new was reported (the stream runs at several Hz). */
internal fun Set<coffee.crema.settings.Setting>.withEchoes(volume: Boolean, standby: Boolean, smoothing: Boolean, autoStop: Boolean): Set<coffee.crema.settings.Setting> {
    val reported = listOfNotNull(
        coffee.crema.settings.Setting.SCALE_VOLUME.takeIf { volume },
        coffee.crema.settings.Setting.SCALE_STANDBY.takeIf { standby },
        coffee.crema.settings.Setting.SCALE_FLOW_SMOOTHING.takeIf { smoothing },
        coffee.crema.settings.Setting.SCALE_AUTO_STOP.takeIf { autoStop },
    )
    return if (reported.all { it in this }) this else this + reported
}

/** The row gate for [setting] on this device (§7.6). */
fun MainUiState.gate(setting: coffee.crema.settings.Setting): coffee.crema.ui.components.RowGate {
    if (mirror == null) return coffee.crema.ui.components.RowGate.Open
    val a = availability(setting)
    return coffee.crema.ui.components.RowGate(editable = a.editable, unknown = a == SettingAvailability.UNKNOWN, note = availabilityNote(setting))
}

/** The Reset preferences row's title: a remote resets only its own (§7.6). */
fun resetPreferencesTitle(ui: MainUiState): String =
    if (ui.mirror != null) "Reset this device's preferences" else "Reset preferences"

/** What a readout shows for a value this device doesn't know (§7.2). */
const val UNKNOWN_VALUE = "—"

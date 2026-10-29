package coffee.crema.ui

/**
 * One relayed control as the Android UI sends it today. [fixture] names the
 * golden wire file `proxy-golden/v3/control/<fixture>.json`, which holds
 * `Frame.Control(id = 1, method, args)` as encoded; [send] is the
 * [MainViewModel] call that produces it on a secondary.
 *
 * The list is the characterisation of every method in
 * [coffee.crema.machine.MachineCommandCodec.METHODS] (every machine state the
 * UI requests, and both shapes where an argument is optional), shared by the
 * codec test ([ControlVerbGoldenTest]), the secondary's sender test
 * ([RelayedVerbSecondaryWireTest]) and the primary's dispatcher test
 * ([RelayedVerbPrimaryDispatchTest]).
 */
internal data class RelayedVerbSample(
    val fixture: String,
    val method: String,
    val args: String,
    val send: (MainViewModel) -> Unit,
)

/** A built-in profile (core/de1-domain/profiles/builtin.json), so the primary
 *  can actually select it. */
internal const val SAMPLE_PROFILE_ID = "019e5fb5-63b5-7333-a2ac-34b9f4f0e604"

internal val RELAYED_VERB_SAMPLES: List<RelayedVerbSample> = listOf(
    // Machine control (MachineCommand.Control): forwarded, nothing applied locally.
    RelayedVerbSample("machineState.SLEEP", "machineState", "SLEEP") { it.sleep() },
    RelayedVerbSample("machineState.IDLE", "machineState", "IDLE") { it.stopShot() },
    RelayedVerbSample("machineState.ESPRESSO", "machineState", "ESPRESSO") { it.startEspresso() },
    RelayedVerbSample("machineState.STEAM", "machineState", "STEAM") { it.steam() },
    RelayedVerbSample("machineState.HOT_WATER", "machineState", "HOT_WATER") { it.hotWater() },
    RelayedVerbSample("machineState.FLUSH", "machineState", "FLUSH") { it.flush() },
    RelayedVerbSample("machineState.STEAM_RINSE", "machineState", "STEAM_RINSE") { it.startSteamRinse() },
    RelayedVerbSample("machineState.DESCALE", "machineState", "DESCALE") { it.startDescale() },
    RelayedVerbSample("machineState.CLEAN", "machineState", "CLEAN") { it.startClean() },
    RelayedVerbSample("startShot", "startShot", "") { it.startShot() },
    RelayedVerbSample("cancelMaintenance", "cancelMaintenance", "") { it.cancelMaintenance() },
    RelayedVerbSample("tareScale", "tareScale", "") { it.tareScale() },
    // Sent by ProxyController.requestHandoff; granted by the controller's handoff release.
    RelayedVerbSample("handoff", "handoff", "") { it.requestHandoff() },
    // Session config (MachineCommand.Config): applied locally, reverted on refusal.
    RelayedVerbSample("setActiveProfile", "setActiveProfile", SAMPLE_PROFILE_ID) { it.setActiveProfile(SAMPLE_PROFILE_ID) },
    RelayedVerbSample("setActiveBean", "setActiveBean", "bean-1") { it.setActiveBean("bean-1") },
    RelayedVerbSample("setActiveBean.none", "setActiveBean", "") { it.setActiveBean(null) },
    RelayedVerbSample("setStopOnWeight", "setStopOnWeight", "true") { it.setStopOnWeight(true) },
    RelayedVerbSample("setAutoTare", "setAutoTare", "false") { it.setAutoTare(false) },
    RelayedVerbSample("setQcSteamTime", "setQcSteamTime", "30.0") { it.setQcSteamTime(30f) },
    RelayedVerbSample("setQcSteamFlow", "setQcSteamFlow", "1.2") { it.setQcSteamFlow(1.2f) },
    RelayedVerbSample("setQcSteamTemp", "setQcSteamTemp", "148.0") { it.setQcSteamTemp(148f) },
    RelayedVerbSample("setQcSteamTemp.off", "setQcSteamTemp", "0.0") { it.setQcSteamTemp(0f) },
    RelayedVerbSample("setQcHotWaterTemp", "setQcHotWaterTemp", "85.0") { it.setQcHotWaterTemp(85f) },
    RelayedVerbSample("setQcHotWaterVolume", "setQcHotWaterVolume", "250.0") { it.setQcHotWaterVolume(250f) },
    RelayedVerbSample("setQcFlushTime", "setQcFlushTime", "5.0") { it.setQcFlushTime(5f) },
    RelayedVerbSample("setQcFlushTemp", "setQcFlushTemp", "92.0") { it.setQcFlushTemp(92f) },
    RelayedVerbSample("setQcGrind", "setQcGrind", "14.5") { it.setQcGrind(14.5f) },
    RelayedVerbSample("quickAdjustBrew", "quickAdjustBrew", "18.0,36.0,93.0,8.0") { it.quickAdjustBrew(18.0, 36.0, 93.0, 8.0) },
    RelayedVerbSample("quickAdjustBrew.noPreinf", "quickAdjustBrew", "18.0,36.0,93.0,") { it.quickAdjustBrew(18.0, 36.0, 93.0) },
    RelayedVerbSample("resetBrew", "resetBrew", "") { it.resetBrewParams() },
)

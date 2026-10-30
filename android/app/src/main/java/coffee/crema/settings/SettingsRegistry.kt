package coffee.crema.settings

import coffee.crema.machine.MachineCommand
import coffee.crema.machine.QcField

/*
 * Settings ownership (spec `.scratch/settings-ownership/SPEC.md` §5, §7.1):
 * one registry tags every setting Crema stores or edits as machine (A),
 * session (B) or personal (C), and says what a remote does with it. Tests
 * fail when a stored field, a public setter or a config-snapshot field is
 * left untagged (SettingsRegistryTest).
 */

/** Whose setting it is. */
enum class SettingGroup {
    /** Machine: written to the DE1 (or the scale) by the device that holds it. */
    A_MACHINE,

    /** Session: drives the hub's shot, records and timers. */
    B_SESSION,

    /** Personal: this device's own look, habits and bindings. */
    C_PERSONAL,
}

/** What a remote does with a setting. */
enum class OnRemote {
    /** Shows the hub's value; an edit goes to the hub as its #203 command. */
    RELAYED,

    /** Shows the hub's value, labelled "Change on <hub>". */
    READ_ONLY,

    /** The device's own value. */
    LOCAL,
}

/** How a remote learns the hub's value. */
enum class MirrorSource {
    /** A field of the hub's config snapshot ([ConfigSnapshot]). */
    SNAPSHOT,

    /** The mirrored scale stream echoes it (Bookoo only): known once reported. */
    SCALE_ECHO,

    /** No scale echoes it: always unknown on a remote. */
    NONE_AVAILABLE,

    /** Never mirrored (personal settings). */
    NONE,
}

/** Where a setting lives on the device that owns it. */
enum class SettingStore { APP_PREFS, LIBRARY, MEMORY, MACHINE, SCALE, MAINTENANCE, VISUALIZER, DECENT }

private const val VM = "MainViewModel"

/**
 * Every setting, with its §5 row. [storeField] is the property in its store's
 * serializer (null for the library, memory, machine and scale); [snapshotField]
 * is set exactly when [mirroredVia] is [MirrorSource.SNAPSHOT], and
 * [snapshotExtras] names the snapshot fields that travel with it. [uiField] is
 * the [coffee.crema.ui.MainUiState] property that shows it. [setters] are the
 * public functions that change it, as `Owner.name(ParamTypes)`.
 */
enum class Setting(
    val group: SettingGroup,
    val onRemote: OnRemote,
    val store: SettingStore,
    val storeField: String?,
    val mirroredVia: MirrorSource,
    val snapshotField: String?,
    val adoptable: Boolean,
    val setters: List<String>,
    val uiField: String? = storeField,
    val snapshotExtras: List<String> = emptyList(),
) {
    // ── A: machine (21) ──────────────────────────────────────────────────────
    QC_STEAM_TEMP(A, RO, AP, "qcSteamTempC", SNAP, "qcSteamTempC", true, listOf("$VM.setQcSteamTemp(Float)")),
    QC_STEAM_TIME(A, RO, AP, "qcSteamTimeS", SNAP, "qcSteamTimeS", true, listOf("$VM.setQcSteamTime(Float)")),
    QC_STEAM_FLOW(A, RO, AP, "qcSteamFlowMlS", SNAP, "qcSteamFlowMlS", true, listOf("$VM.setQcSteamFlow(Float)")),
    QC_HOT_WATER_TEMP(A, RO, AP, "qcHotWaterTempC", SNAP, "qcHotWaterTempC", true, listOf("$VM.setQcHotWaterTemp(Float)")),
    QC_HOT_WATER_VOLUME(A, RO, AP, "qcHotWaterVolumeMl", SNAP, "qcHotWaterVolumeMl", true, listOf("$VM.setQcHotWaterVolume(Float)")),
    QC_FLUSH_TIME(A, RO, AP, "qcFlushTimeS", SNAP, "qcFlushTimeS", true, listOf("$VM.setQcFlushTime(Float)")),
    QC_FLUSH_TEMP(A, RO, AP, "qcFlushTempC", SNAP, "qcFlushTempC", true, listOf("$VM.setQcFlushTemp(Float)")),
    STEAM_ECO(A, RO, AP, "steamEco", SNAP, "steamEco", true, listOf("$VM.setSteamEco(Boolean)")),
    STEAM_TWO_TAP(A, RO, AP, "steamTwoTap", SNAP, "steamTwoTap", true, listOf("$VM.setSteamTwoTap(Boolean)")),
    FAN_THRESHOLD(A, RO, AP, "fanThresholdC", SNAP, "fanThresholdC", true, listOf("$VM.setFanThreshold(Float)")),
    WATER_REFILL_POINT(A, RO, AP, "waterRefillPointMm", SNAP, "waterRefillPointMm", true, listOf("$VM.setWaterRefillPointMm(Float)")),
    HEATER_VOLTAGE(A, RO, SettingStore.MACHINE, null, SNAP, "heaterVoltage", false, listOf("$VM.setHeaterVoltage(Int)"), uiField = "de1MachineInfo"),
    FLOW_MULTIPLIER(A, RO, SettingStore.MACHINE, null, SNAP, "flowMultiplier", false, listOf("$VM.setFlowMultiplier(Float)"), uiField = "de1MachineInfo"),
    LINE_FREQUENCY(
        A, RO, SettingStore.MEMORY, null, SNAP, "lineFrequencyOverride", false, listOf("$VM.setLineFrequency(Float)"),
        uiField = "lineFreqOverride", snapshotExtras = listOf("lineFrequencyHz"),
    ),
    CUP_WARMER_TEMP(A, RO, SettingStore.MACHINE, null, SNAP, "cupWarmerTempC", false, listOf("$VM.setCupWarmerTemp(Int)"), uiField = "de1MachineInfo"),
    SCALE_VOLUME(A, RO, SettingStore.SCALE, null, MirrorSource.SCALE_ECHO, null, false, listOf("$VM.setScaleVolume(Int)"), uiField = "scaleVolume"),
    SCALE_STANDBY(A, RO, SettingStore.SCALE, null, MirrorSource.SCALE_ECHO, null, false, listOf("$VM.setScaleStandbyMinutes(Int)"), uiField = "scaleStandbyMinutes"),
    SCALE_FLOW_SMOOTHING(A, RO, SettingStore.SCALE, null, MirrorSource.SCALE_ECHO, null, false, listOf("$VM.setScaleFlowSmoothing(Boolean)"), uiField = "scaleFlowSmoothing"),
    SCALE_ANTI_MISTOUCH(A, RO, SettingStore.SCALE, null, MirrorSource.NONE_AVAILABLE, null, false, listOf("$VM.setScaleAntiMistouch(Boolean)"), uiField = "scaleAntiMistouch"),
    SCALE_MODE(A, RO, SettingStore.SCALE, null, MirrorSource.NONE_AVAILABLE, null, false, listOf("$VM.setScaleMode(Int)"), uiField = "scaleActiveMode"),
    SCALE_AUTO_STOP(A, RO, SettingStore.SCALE, null, MirrorSource.SCALE_ECHO, null, false, listOf("$VM.setScaleAutoStop(Int)"), uiField = "scaleAutoStop"),

    // ── B: session (13) ──────────────────────────────────────────────────────
    STOP_ON_WEIGHT(B, REL, AP, "stopOnWeight", SNAP, "stopOnWeight", true, listOf("$VM.setStopOnWeight(Boolean)")),
    AUTO_TARE(B, REL, AP, "autoTare", SNAP, "autoTare", true, listOf("$VM.setAutoTare(Boolean)")),
    MAX_SHOT_DURATION(B, RO, AP, "maxShotDurationS", SNAP, "maxShotDurationS", true, listOf("$VM.setMaxShotDuration(Float?)")),
    VOLUME_STOP_WITH_SCALE(B, RO, AP, "volumeStopWithScale", SNAP, "volumeStopWithScale", true, listOf("$VM.setVolumeStopWithScale(Boolean)")),
    REQUIRE_SCALE(B, RO, AP, "requireScale", SNAP, "requireScale", true, listOf("$VM.setRequireScale(Boolean)")),
    PRE_FLUSH(B, RO, AP, "preFlush", SNAP, "preFlush", true, listOf("$VM.setPreFlush(Boolean)")),
    STEAM_PURGE(B, RO, AP, "steamPurge", SNAP, "steamPurge", true, listOf("$VM.setSteamPurge(Boolean)")),
    IDLE_SLEEP(B, RO, AP, "idleSleepMin", SNAP, "idleSleepMin", true, listOf("$VM.setIdleSleepMin(Int)")),
    GRINDER_MODEL(B, RO, AP, "grinderModel", SNAP, "grinderModel", true, listOf("$VM.setGrinderModel(String)")),
    QC_GRIND(B, REL, AP, "qcGrind", SNAP, "qcGrind", true, listOf("$VM.setQcGrind(Float)")),
    ACTIVE_PROFILE(B, REL, AP, "activeProfileId", SNAP, "activeProfileId", true, listOf("$VM.setActiveProfile(String)")),
    ACTIVE_BEAN(B, REL, SettingStore.LIBRARY, null, SNAP, "activeBeanId", true, listOf("$VM.setActiveBean(String?)"), uiField = "activeBeanId"),
    BREW_PARAMS(
        B, REL, SettingStore.MEMORY, null, SNAP, "brewDoseG", true,
        listOf("$VM.quickAdjustBrew(Double, Double, Double, Double?)", "$VM.resetBrewParams()"),
        uiField = "brewParams", snapshotExtras = listOf("brewYieldG", "brewTempC", "brewPreinfuseS"),
    ),

    // ── C: personal (53) ─────────────────────────────────────────────────────
    THEME_MODE(C, LOC, AP, "themeMode", NONE, null, false, listOf("$VM.setThemeMode(String)")),
    WEIGHT_UNIT(C, LOC, AP, "weightUnit", NONE, null, false, listOf("$VM.setWeightUnit(String)")),
    TEMP_UNIT(C, LOC, AP, "tempUnit", NONE, null, false, listOf("$VM.setTempUnit(String)")),
    PRESSURE_UNIT(C, LOC, AP, "pressureUnit", NONE, null, false, listOf("$VM.setPressureUnit(String)")),
    VOLUME_UNIT(C, LOC, AP, "volumeUnit", NONE, null, false, listOf("$VM.setVolumeUnit(String)")),
    WATER_LEVEL_UNIT(C, LOC, AP, "waterLevelUnit", NONE, null, false, listOf("$VM.setWaterLevelUnit(String)")),
    WATER_WARN(C, LOC, AP, "waterWarnMl", NONE, null, false, listOf("$VM.setWaterWarnMl(Float)")),
    CHART_CHANNELS(C, LOC, AP, "chartChannels", NONE, null, false, listOf("$VM.toggleChartChannel(String, Boolean)")),
    KEEP_SCREEN_ON_BREW(C, LOC, AP, "keepScreenOnBrew", NONE, null, false, listOf("$VM.setKeepScreenOnBrew(Boolean)")),
    BREW_CUE_SOUND(C, LOC, AP, "brewCueSound", NONE, null, false, listOf("$VM.setBrewCueSound(Boolean)")),
    BREW_CUE_HAPTICS(C, LOC, AP, "brewCueHaptics", NONE, null, false, listOf("$VM.setBrewCueHaptics(Boolean)")),
    SCREENSAVER_AFTER(C, LOC, AP, "screensaverAfterMin", NONE, null, false, listOf("$VM.setScreensaverAfterMin(Int)")),
    WAKE_MACHINE_WITH_SAVER(C, LOC, AP, "wakeMachineWithSaver", NONE, null, false, listOf("$VM.setWakeMachineWithSaver(Boolean)")),
    SLEEP_ON_QUIT(C, LOC, AP, "sleepOnQuit", NONE, null, false, listOf("$VM.setSleepOnQuit(Boolean)")),
    /** Legacy, read only to migrate into [IDLE_SLEEP]; no setter. */
    SLEEP_MACHINE_WITH_SAVER(C, LOC, AP, "sleepMachineWithSaver", NONE, null, false, emptyList()),
    KEEP_CONNECTED_SCREEN_OFF(C, LOC, AP, "keepConnectedScreenOff", NONE, null, false, listOf("$VM.setKeepConnectedScreenOff(Boolean)")),
    USB_CHARGING_MODE(C, LOC, AP, "usbChargingMode", NONE, null, false, listOf("$VM.setUsbChargingMode(String)")),
    SHOW_DEBUG_PANEL(C, LOC, AP, "showDebugPanel", NONE, null, false, listOf("$VM.setShowDebugPanel(Boolean)")),
    AUTO_DRIVE_BACKUP(C, LOC, AP, "autoDriveBackup", NONE, null, false, listOf("$VM.setAutoDriveBackup(Boolean)")),
    AUTO_UPDATE_CHECK(C, LOC, AP, "autoUpdateCheck", NONE, null, false, listOf("$VM.setAutoUpdateCheck(Boolean)")),
    LAST_UPDATE_CHECK_AT(C, LOC, AP, "lastUpdateCheckAtMs", NONE, null, false, emptyList()),
    LAST_SEEN_LATEST_VERSION(C, LOC, AP, "lastSeenLatestVersion", NONE, null, false, emptyList()),
    LAST_BREW_LOG_METHOD(C, LOC, AP, "lastBrewLogMethod", NONE, null, false, emptyList()),
    DEFAULT_DOSE(C, LOC, AP, "defaultDoseG", NONE, null, false, listOf(BREW_DEFAULTS_SETTER)),
    DEFAULT_RATIO(C, LOC, AP, "defaultRatio", NONE, null, false, listOf(BREW_DEFAULTS_SETTER)),
    DEFAULT_BREW_TEMP(C, LOC, AP, "defaultBrewTempC", NONE, null, false, listOf(BREW_DEFAULTS_SETTER)),
    DEFAULT_PREINFUSE(C, LOC, AP, "defaultPreinfuseS", NONE, null, false, listOf(BREW_DEFAULTS_SETTER)),
    BARISTA_NAME(C, LOC, AP, "baristaName", NONE, null, false, listOf("$VM.setBaristaName(String)")),
    DE1_ADDRESS(C, LOC, AP, "de1Address", NONE, null, false, listOf("ConnectionController.setDe1AutoConnect(Boolean)"), uiField = "rememberedDe1Address"),
    SCALE_ADDRESS(C, LOC, AP, "scaleAddress", NONE, null, false, listOf("ConnectionController.setScaleAutoConnect(Boolean)"), uiField = "rememberedScaleAddress"),
    SCALE_NAME(C, LOC, AP, "scaleName", NONE, null, false, emptyList(), uiField = "rememberedScaleName"),
    DE1_COMPANION_ID(C, LOC, AP, "de1CompanionId", NONE, null, false, emptyList(), uiField = null),
    SCALE_COMPANION_ID(C, LOC, AP, "scaleCompanionId", NONE, null, false, emptyList(), uiField = null),
    COMPANION_DECLINED(C, LOC, AP, "companionDeclined", NONE, null, false, emptyList()),
    PROXY_ROLE(C, LOC, AP, "proxyRole", NONE, null, false, listOf("$VM.setHubMode(Boolean, Boolean)", "ProxyController.setHubMode(Boolean)")),
    PROXY_HOST(C, LOC, AP, "proxyPrimaryHost", NONE, null, false, listOf("$VM.setProxyPrimaryHost(String)")),
    PROXY_PORT(C, LOC, AP, "proxyPrimaryPort", NONE, null, false, listOf("$VM.setProxyPrimaryPort(Int)")),
    REPLAY_PRIMARY(C, LOC, AP, "replayPrimary", NONE, null, false, listOf("$VM.setReplayPrimary(Boolean)")),
    FILTER_CAPACITY(C, LOC, SettingStore.MAINTENANCE, "filterCapacityLitres", NONE, null, false, listOf("$VM.setFilterCapacity(Double)"), uiField = "maintenance"),
    DESCALE_INTERVAL(C, LOC, SettingStore.MAINTENANCE, "descaleIntervalLitres", NONE, null, false, listOf("$VM.setDescaleInterval(Double)"), uiField = "maintenance"),
    CLEAN_INTERVAL(C, LOC, SettingStore.MAINTENANCE, "cleanIntervalHours", NONE, null, false, listOf("$VM.setCleanInterval(Double)"), uiField = "maintenance"),
    FILTER_REMINDER(C, LOC, SettingStore.MAINTENANCE, "filterEnabled", NONE, null, false, listOf("$VM.setFilterReminderEnabled(Boolean)"), uiField = "maintenance"),
    DESCALE_REMINDER(C, LOC, SettingStore.MAINTENANCE, "descaleEnabled", NONE, null, false, listOf("$VM.setDescaleReminderEnabled(Boolean)"), uiField = "maintenance"),
    CLEAN_REMINDER(C, LOC, SettingStore.MAINTENANCE, "cleanEnabled", NONE, null, false, listOf("$VM.setCleanReminderEnabled(Boolean)"), uiField = "maintenance"),
    VISUALIZER_AUTO_UPLOAD(
        C, LOC, SettingStore.VISUALIZER, "autoUpload", NONE, null, false,
        listOf(
            "$VM.setVisualizerAutoSync(Boolean)", "VisualizerSync.setAutoSync(Boolean)", "VisualizerSync.setAutoUploadNow(Boolean)",
            "SharingController.setAutoUpload(UploadTargetId, Boolean)",
        ),
        uiField = "visualizer",
    ),
    /** Persisted; Android doesn't act on it yet, and nothing sets it. */
    VISUALIZER_AUTO_SYNC(C, LOC, SettingStore.VISUALIZER, "autoSync", NONE, null, false, emptyList(), uiField = "visualizer"),
    VISUALIZER_PRIVACY(C, LOC, SettingStore.VISUALIZER, "privacy", NONE, null, false, listOf("VisualizerSync.setPrivacy(String)"), uiField = "visualizer"),
    VISUALIZER_INCLUDE_PROFILE(C, LOC, SettingStore.VISUALIZER, "includeProfile", NONE, null, false, listOf("VisualizerSync.setIncludeProfile(Boolean)"), uiField = "visualizer"),
    VISUALIZER_INCLUDE_NOTES(C, LOC, SettingStore.VISUALIZER, "includeNotes", NONE, null, false, listOf("VisualizerSync.setIncludeNotes(Boolean)"), uiField = "visualizer"),
    VISUALIZER_SHOTS_DIRECTION(C, LOC, SettingStore.VISUALIZER, "shotsDirection", NONE, null, false, listOf("VisualizerSync.setShotsDirection(String)"), uiField = "visualizer"),
    VISUALIZER_BEANS_DIRECTION(C, LOC, SettingStore.VISUALIZER, "beansDirection", NONE, null, false, listOf("VisualizerSync.setBeansDirection(String)"), uiField = "visualizer"),
    VISUALIZER_ROASTERS_DIRECTION(C, LOC, SettingStore.VISUALIZER, "roastersDirection", NONE, null, false, listOf("VisualizerSync.setRoastersDirection(String)"), uiField = "visualizer"),
    DECENT_AUTO_UPLOAD(
        C, LOC, SettingStore.DECENT, "autoUpload", NONE, null, false,
        listOf(
            "$VM.setDecentAutoUpload(Boolean)", "DecentSync.setAutoUpload(Boolean)", "DecentSync.setAutoUploadNow(Boolean)",
            "SharingController.setAutoUpload(UploadTargetId, Boolean)",
        ),
        uiField = "decent",
    ),
    ;

    /** Every snapshot field this setting travels in. */
    val snapshotFields: List<String> get() = listOfNotNull(snapshotField) + snapshotExtras
}

private const val BREW_DEFAULTS_SETTER = "$VM.setBrewDefaults(Float, Float, Float, Float)"

private val A = SettingGroup.A_MACHINE
private val B = SettingGroup.B_SESSION
private val C = SettingGroup.C_PERSONAL
private val REL = OnRemote.RELAYED
private val RO = OnRemote.READ_ONLY
private val LOC = OnRemote.LOCAL
private val AP = SettingStore.APP_PREFS
private val SNAP = MirrorSource.SNAPSHOT
private val NONE = MirrorSource.NONE

/**
 * Stored fields and public setters that are not settings, each with why.
 * Keys are `Store.field` (`AppPrefs`, `MaintenanceState`,
 * `VisualizerSyncPrefs`, `DecentState`) or `Owner.name(ParamTypes)`.
 */
val NOT_SETTINGS: Map<String, String> = mapOf(
    // The hot-water dose (hot-water modes spec §2.6): carried with the bundle
    // here; rows A22–A25 come with the Android hot-water card (HW-3).
    "AppPrefs.qcHotWaterMode" to "hot-water dose, tagged in HW-3",
    "AppPrefs.qcHotWaterTimeS" to "hot-water dose, tagged in HW-3",
    "AppPrefs.qcHotWaterTimeNoLimit" to "hot-water dose, tagged in HW-3",
    "AppPrefs.qcHotWaterRatio" to "hot-water dose, tagged in HW-3",
    // Maintenance counters and timestamps: measured, not chosen.
    "MaintenanceState.totalLitres" to "a counter",
    "MaintenanceState.filterBaselineLitres" to "a counter baseline",
    "MaintenanceState.descaleBaselineLitres" to "a counter baseline",
    "MaintenanceState.cleanAtMs" to "a timestamp",
    "MaintenanceState.filterAtMs" to "a timestamp",
    "MaintenanceState.descaleAtMs" to "a timestamp",
    // Decent account credentials and logs.
    "DecentState.email" to "account credential",
    "DecentState.token" to "account credential",
    "DecentState.serials" to "account data",
    "DecentState.needsReauth" to "account status",
    "DecentState.lastUpload" to "upload log",
    "DecentState.rejectedShotIds" to "upload log",
    "DecentState.log" to "upload log",
    // Setters of library data, drafts and screen state.
    "$VM.setBuiltinRecipeHidden(String, Boolean)" to "library data (recipe organisation)",
    "$VM.setDefaultBrewRecipe(BrewRecipe)" to "library pointer (lastRecipeByMethod)",
    "$VM.setScaleMode(String)" to "guided-setup selection",
    "$VM.setGuidedStartOnPour(Boolean)" to "guided-setup selection",
    "$VM.setRecipeEditMethod(String)" to "screen-local draft",
    "$VM.setMethodEditStyle(BrewMethodStyle)" to "screen-local draft",
    "$VM.togglePinProfile(String)" to "library data",
    "$VM.setBeanImageFromUri(String, Uri)" to "library data",
    "$VM.toggleBeanFavourite(String)" to "library data",
    "$VM.setShotPrivacy(String, String?)" to "library data (a shot)",
    "$VM.setShotGrind(String, Float?)" to "library data (a shot)",
    "$VM.setShotBean(String, String?)" to "library data (a shot)",
    "$VM.setShotNextPlan(String, String)" to "library data (a shot)",
    "$VM.setShotPerson(String, String, String?)" to "library data (a shot)",
    "$VM.setAppForeground(Boolean)" to "app lifecycle",
    "LibraryController.togglePinProfile(String)" to "library data",
    "LibraryController.setBeanImageFromUri(String, Uri)" to "library data",
    "LibraryController.toggleBeanFavourite(String)" to "library data",
    "LibraryController.setShotPrivacy(String, String?)" to "library data (a shot)",
    "LibraryController.setShotGrind(String, Float?)" to "library data (a shot)",
    "LibraryController.setShotBean(String, String?)" to "library data (a shot)",
    "LibraryController.setShotNextPlan(String, String)" to "library data (a shot)",
    "LibraryController.setShotPerson(String, String, String?)" to "library data (a shot)",
    "FirmwareUpdateController.setAcknowledged(Boolean)" to "screen-local flash dialog state",
    "CatalogueSearchController.setQuery(String)" to "screen-local search text",
    "BackgroundConnectionController.setEnabled(Boolean)" to "service gate input",
    "BackgroundConnectionController.setDe1Active(Boolean)" to "service gate input",
    "BackgroundConnectionController.setBrewSessionActive(Boolean)" to "service gate input",
    "BackgroundConnectionController.setFirmwareFlashActive(Boolean)" to "service gate input",
    "BackgroundConnectionController.setForeground(Boolean)" to "service gate input",
    "BackgroundConnectionController.setBackgroundStartAllowed(Boolean)" to "service gate input",
)

/** Snapshot fields that are not settings: identity and bookkeeping. */
val SNAPSHOT_META: Set<String> = setOf("activeProfileJson", "activeBeanSummary", "primaryName", "authority", "rev")

/** The setting a #203 [MachineCommand.Config] edits. Exhaustive, so a new
 *  Config subtype has to answer it. */
fun MachineCommand.Config.setting(): Setting = when (this) {
    is MachineCommand.SetActiveProfile -> Setting.ACTIVE_PROFILE
    is MachineCommand.SetActiveBean -> Setting.ACTIVE_BEAN
    is MachineCommand.SetStopOnWeight -> Setting.STOP_ON_WEIGHT
    is MachineCommand.SetAutoTare -> Setting.AUTO_TARE
    is MachineCommand.SetQc -> field.setting()
    is MachineCommand.AdjustBrew, MachineCommand.ResetBrew -> Setting.BREW_PARAMS
}

/** The setting a Quick-Controls field edits. */
fun QcField.setting(): Setting = when (this) {
    QcField.STEAM_TIME -> Setting.QC_STEAM_TIME
    QcField.STEAM_FLOW -> Setting.QC_STEAM_FLOW
    QcField.STEAM_TEMP -> Setting.QC_STEAM_TEMP
    QcField.HOT_WATER_TEMP -> Setting.QC_HOT_WATER_TEMP
    QcField.HOT_WATER_VOLUME -> Setting.QC_HOT_WATER_VOLUME
    QcField.FLUSH_TIME -> Setting.QC_FLUSH_TIME
    QcField.FLUSH_TEMP -> Setting.QC_FLUSH_TEMP
    QcField.GRIND -> Setting.QC_GRIND
}

package coffee.crema.ui

import coffee.crema.settings.AppPrefs
import coffee.crema.settings.SettingsStore
import coffee.crema.settings.hotWaterDose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * App preferences (MainViewModel breakup step 6): reading and hydrating them
 * at startup, the full snapshot every save writes, the numbered saves, a
 * backup's portable values, and the personal setters that only change a
 * preference (display units, theme, cues, the screensaver, the manual hub
 * address, …). Setters that also reach the core or the machine stay with
 * their owners and call [persistPrefs].
 */
internal class SettingsController(
    private val store: SettingsStore,
    private val scope: CoroutineScope,
    private val uiState: () -> MainUiState,
    private val updateUi: ((MainUiState) -> MainUiState) -> Unit,
    /** True on tablet-class displays (≥600dp smallest width) — the form-factor
     *  default for [MainUiState.keepConnectedScreenOff] (tablet on / phone off). */
    val isTablet: Boolean,
    /** The remembered addresses live on the connection controller. */
    private val connectionState: () -> ConnectionController.State,
    private val companionIdFor: (String?) -> Int?,
    /** Push the hub's config snapshot to its remotes (a no-op elsewhere). */
    private val pushConfig: () -> Unit,
) {
    /** True once [hydratePrefs] has filled the UI from disk and [markLoaded]
     *  ran — writes before that would snapshot half-initialised state and
     *  clobber the saved prefs (e.g. erase the restored activeProfileId
     *  during boot). */
    @Volatile
    var loaded = false
        private set

    /** Completes with [loaded] (or when the startup load ends without it):
     *  the hub's relay waits for it, so it never serves before the paired
     *  devices are known. */
    val ready = CompletableDeferred<Unit>()

    /** The last [persistPrefs] snapshot's number (Main only). */
    private var prefsGeneration = 0L

    /** The persisted prefs (migrated by the store). */
    suspend fun read(): AppPrefs = store.load()

    /** Fill the UI state from [p] at startup. */
    fun hydratePrefs(p: AppPrefs) {
        // The load stored the migrated dose; read it through the core's rule.
        val hwDose = p.hotWaterDose()
        updateUi { it.withHotWaterDose(hwDose).copy(
            themeMode = p.themeMode,
            maxShotDurationS = p.maxShotDurationS,
            autoTare = p.autoTare,
            stopOnWeight = p.stopOnWeight,
            volumeStopWithScale = p.volumeStopWithScale,
            requireScale = p.requireScale,
            steamEco = p.steamEco,
            steamTwoTap = p.steamTwoTap,
            fanThresholdC = p.fanThresholdC,
            usbChargingMode = p.usbChargingMode,
            preFlush = p.preFlush,
            steamPurge = p.steamPurge,
            chartChannels = p.chartChannels,
            keepScreenOnBrew = p.keepScreenOnBrew,
            brewCueSound = p.brewCueSound,
            brewCueHaptics = p.brewCueHaptics,
            idleSleepMin = p.idleSleepMin ?: DEFAULT_IDLE_SLEEP_MIN,
            screensaverAfterMin = p.screensaverAfterMin,
            wakeMachineWithSaver = p.wakeMachineWithSaver,
            sleepOnQuit = p.sleepOnQuit,
            // Resolve the nullable pref against the form-factor default (#65).
            keepConnectedScreenOff = p.keepConnectedScreenOff ?: isTablet,
            autoDriveBackup = p.autoDriveBackup,
            driveBackupWeekly = p.driveBackupWeekly,
            driveBackupWifiOnly = p.driveBackupWifiOnly,
            bleSessionsKeep = p.bleSessionsKeep,
            autoUpdateCheck = p.autoUpdateCheck,
            lastUpdateCheckAtMs = p.lastUpdateCheckAtMs,
            lastSeenLatestVersion = p.lastSeenLatestVersion,
            lastBrewLogMethod = p.lastBrewLogMethod,
            grinderModel = p.grinderModel,
            baristaName = p.baristaName,
            showDebugPanel = p.showDebugPanel,
            defaultDoseG = p.defaultDoseG,
            defaultRatio = p.defaultRatio,
            defaultBrewTempC = p.defaultBrewTempC,
            defaultPreinfuseS = p.defaultPreinfuseS,
            weightUnit = p.weightUnit,
            tempUnit = p.tempUnit,
            pressureUnit = p.pressureUnit,
            volumeUnit = p.volumeUnit,
            waterLevelUnit = p.waterLevelUnit,
            waterWarnMl = p.waterWarnMl,
            waterRefillPointMm = p.waterRefillPointMm,
            qcSteamTimeS = p.qcSteamTimeS,
            qcSteamFlowMlS = p.qcSteamFlowMlS,
            qcSteamTempC = p.qcSteamTempC,
            qcHotWaterTempC = p.qcHotWaterTempC,
            qcFlushTimeS = p.qcFlushTimeS,
            qcFlushTempC = p.qcFlushTempC,
            qcGrind = p.qcGrind,
            // Restore the last session's profile selection; refreshProfiles
            // self-heals if the id no longer resolves (e.g. deleted custom).
            activeProfileId = p.activeProfileId ?: it.activeProfileId,
            proxyRole = p.proxyRole,
            proxyPrimaryHost = p.proxyPrimaryHost,
            proxyPrimaryPort = p.proxyPrimaryPort,
            replayPrimary = p.replayPrimary,
            companionDeclined = p.companionDeclined,
        ) }
    }

    /** The load is done: saves may go out, and [ready] completes. */
    fun markLoaded() {
        loaded = true
        ready.complete(Unit)
    }

    /** Apply a restored backup's PORTABLE values to the UI state, keeping
     *  this device's per-device bindings (BLE addresses, LAN proxy, paired
     *  devices); activeProfileId is deliberately NOT restored, so a merge
     *  never hijacks the current selection. */
    fun restoreValues(p: AppPrefs) {
        // A backup with no hot-water mode goes through the core's rule — its
        // own fields, never this device's marker.
        val hwDose = p.hotWaterDose()
        updateUi { it.withHotWaterDose(hwDose).copy(
            idleSleepMin = p.idleSleepMin ?: it.idleSleepMin,
            themeMode = p.themeMode,
            maxShotDurationS = p.maxShotDurationS,
            autoTare = p.autoTare,
            stopOnWeight = p.stopOnWeight,
            volumeStopWithScale = p.volumeStopWithScale,
            requireScale = p.requireScale,
            steamEco = p.steamEco,
            steamTwoTap = p.steamTwoTap,
            fanThresholdC = p.fanThresholdC,
            usbChargingMode = p.usbChargingMode,
            preFlush = p.preFlush,
            steamPurge = p.steamPurge,
            chartChannels = p.chartChannels,
            keepScreenOnBrew = p.keepScreenOnBrew,
            brewCueSound = p.brewCueSound,
            brewCueHaptics = p.brewCueHaptics,
            grinderModel = p.grinderModel,
            baristaName = p.baristaName,
            showDebugPanel = p.showDebugPanel,
            defaultDoseG = p.defaultDoseG,
            defaultRatio = p.defaultRatio,
            defaultBrewTempC = p.defaultBrewTempC,
            defaultPreinfuseS = p.defaultPreinfuseS,
            weightUnit = p.weightUnit,
            tempUnit = p.tempUnit,
            pressureUnit = p.pressureUnit,
            volumeUnit = p.volumeUnit,
            waterLevelUnit = p.waterLevelUnit,
            waterWarnMl = p.waterWarnMl,
            waterRefillPointMm = p.waterRefillPointMm,
            qcSteamTimeS = p.qcSteamTimeS,
            qcSteamFlowMlS = p.qcSteamFlowMlS,
            qcSteamTempC = p.qcSteamTempC,
            qcHotWaterTempC = p.qcHotWaterTempC,
            qcFlushTimeS = p.qcFlushTimeS,
            qcFlushTempC = p.qcFlushTempC,
            qcGrind = p.qcGrind,
        ) }
    }

    /** Erase all data: the prefs file and its copies. */
    suspend fun erase() = store.erase()

    /** Build a full AppPrefs from the current UI state so each setter persists
     *  without clobbering the other fields. */
    fun currentPrefs() = AppPrefs(
        themeMode = uiState().themeMode,
        maxShotDurationS = uiState().maxShotDurationS,
        autoTare = uiState().autoTare,
        stopOnWeight = uiState().stopOnWeight,
        volumeStopWithScale = uiState().volumeStopWithScale,
        requireScale = uiState().requireScale,
        steamEco = uiState().steamEco,
        steamTwoTap = uiState().steamTwoTap,
        fanThresholdC = uiState().fanThresholdC,
        usbChargingMode = uiState().usbChargingMode,
        preFlush = uiState().preFlush,
        steamPurge = uiState().steamPurge,
        chartChannels = uiState().chartChannels,
        keepScreenOnBrew = uiState().keepScreenOnBrew,
        brewCueSound = uiState().brewCueSound,
        brewCueHaptics = uiState().brewCueHaptics,
        idleSleepMin = uiState().idleSleepMin,
        screensaverAfterMin = uiState().screensaverAfterMin,
        wakeMachineWithSaver = uiState().wakeMachineWithSaver,
        sleepOnQuit = uiState().sleepOnQuit,
        keepConnectedScreenOff = uiState().keepConnectedScreenOff,
        autoDriveBackup = uiState().autoDriveBackup,
        driveBackupWeekly = uiState().driveBackupWeekly,
        driveBackupWifiOnly = uiState().driveBackupWifiOnly,
        bleSessionsKeep = uiState().bleSessionsKeep,
        autoUpdateCheck = uiState().autoUpdateCheck,
        lastUpdateCheckAtMs = uiState().lastUpdateCheckAtMs,
        lastSeenLatestVersion = uiState().lastSeenLatestVersion,
        lastBrewLogMethod = uiState().lastBrewLogMethod,
        grinderModel = uiState().grinderModel,
        baristaName = uiState().baristaName,
        showDebugPanel = uiState().showDebugPanel,
        defaultDoseG = uiState().defaultDoseG,
        defaultRatio = uiState().defaultRatio,
        defaultBrewTempC = uiState().defaultBrewTempC,
        defaultPreinfuseS = uiState().defaultPreinfuseS,
        weightUnit = uiState().weightUnit,
        tempUnit = uiState().tempUnit,
        pressureUnit = uiState().pressureUnit,
        volumeUnit = uiState().volumeUnit,
        waterLevelUnit = uiState().waterLevelUnit,
        waterWarnMl = uiState().waterWarnMl,
        waterRefillPointMm = uiState().waterRefillPointMm,
        qcSteamTimeS = uiState().qcSteamTimeS,
        qcSteamFlowMlS = uiState().qcSteamFlowMlS,
        qcSteamTempC = uiState().qcSteamTempC,
        qcHotWaterTempC = uiState().qcHotWaterTempC,
        qcHotWaterVolumeMl = uiState().qcHotWaterVolumeMl,
        qcHotWaterMode = uiState().qcHotWaterMode,
        qcHotWaterTimeS = uiState().qcHotWaterTimeS,
        qcHotWaterTimeNoLimit = uiState().qcHotWaterTimeNoLimit,
        qcHotWaterRatio = uiState().qcHotWaterRatio,
        qcFlushTimeS = uiState().qcFlushTimeS,
        qcFlushTempC = uiState().qcFlushTempC,
        qcGrind = uiState().qcGrind,
        activeProfileId = uiState().activeProfileId,
        // The remembered addresses live on the connection controller — its
        // setters call persistPrefs AFTER their state update, so this read is
        // always fresh (the MainUiState copies are display mirrors).
        de1Address = connectionState().rememberedDe1Address,
        scaleAddress = connectionState().rememberedScaleAddress,
        scaleName = connectionState().rememberedScaleName,
        de1CompanionId = companionIdFor(connectionState().rememberedDe1Address),
        scaleCompanionId = companionIdFor(connectionState().rememberedScaleAddress),
        companionDeclined = uiState().companionDeclined,
        proxyRole = uiState().proxyRole,
        proxyPrimaryHost = uiState().proxyPrimaryHost,
        proxyPrimaryPort = uiState().proxyPrimaryPort,
        replayPrimary = uiState().replayPrimary,
    )

    /** [persistPrefs], awaited: the T6 commit saves the adopted values before
     *  the role switch starts (I3). Numbered like every save, so an earlier
     *  fire-and-forget one can never land after it. */
    suspend fun persistPrefsNow(): Boolean {
        // Like persistPrefs: never save a half-default AppPrefs before the load.
        if (!loaded) return false
        val snapshot = currentPrefs()
        val generation = ++prefsGeneration
        return store.save(snapshot, generation)
    }

    /** [persistPrefsNow] for a restore: the save awaited, and a hub's mirrors told, as [persistPrefs] tells them. */
    suspend fun persistRestoredPrefs(): Boolean {
        val saved = persistPrefsNow()
        pushConfig()
        return saved
    }

    /** Persist the current prefs snapshot (best-effort, off the main thread).
     *  No-op until the initial load completes ([markLoaded]). */
    fun persistPrefs() {
        if (!loaded) return
        val snapshot = currentPrefs()
        // Numbered on Main as the snapshot is built: the store skips one that
        // lands after a newer one.
        val generation = ++prefsGeneration
        scope.launch { store.save(snapshot, generation) }
        // Multi-device: if we're hosting, push the updated session config to every
        // mirror so a secondary tracks our config mid-session (the attach-time
        // snapshot only covers a fresh join). No-op off-primary.
        pushConfig()
    }

    /** Show/hide a live-chart channel (Quick Controls). Persisted display pref. */
    fun toggleChartChannel(key: String, enabled: Boolean) {
        val next = uiState().chartChannels.toMutableSet().apply {
            if (enabled) add(key) else remove(key)
        }
        updateUi { it.copy(chartChannels = next) }
        persistPrefs()
    }

    /** Temperature readout unit (`"C" | "F"`). Persisted display pref. */
    fun setTempUnit(unit: String) {
        updateUi { it.copy(tempUnit = unit) }
        persistPrefs()
    }

    /** Pressure readout unit (`"bar" | "psi"`). Persisted display pref. */
    fun setPressureUnit(unit: String) {
        updateUi { it.copy(pressureUnit = unit) }
        persistPrefs()
    }

    /** Volume readout unit (`"ml" | "floz"`). Persisted display pref. */
    fun setVolumeUnit(unit: String) {
        updateUi { it.copy(volumeUnit = unit) }
        persistPrefs()
    }

    /** Water-tank readout style (`"ml" | "percent"`). Persisted display pref. */
    fun setWaterLevelUnit(unit: String) {
        updateUi { it.copy(waterLevelUnit = unit) }
        persistPrefs()
    }

    /** Keep the screen awake while a shot pulls (Settings → Display). Persisted. */
    fun setKeepScreenOnBrew(enabled: Boolean) {
        updateUi { it.copy(keepScreenOnBrew = enabled) }
        persistPrefs()
    }

    /** Guided-brew cue sound (Settings → Display, the Brew setup bell). Persisted as an explicit choice. */
    fun setBrewCueSound(enabled: Boolean) {
        updateUi { it.copy(brewCueSound = enabled) }
        persistPrefs()
    }

    /** Guided-brew cue vibration (Settings → Display). Persisted as an explicit choice. */
    fun setBrewCueHaptics(enabled: Boolean) {
        updateUi { it.copy(brewCueHaptics = enabled) }
        persistPrefs()
    }

    /** Settings → Brew defaults: the dose / ratio / temp / pre-infusion a NEW
     *  profile seeds from ([startNewProfile] → brewDefaultsJson). Persisted. */
    fun setBrewDefaults(doseG: Float, ratio: Float, brewTempC: Float, preinfuseS: Float) {
        updateUi { it.copy(
            defaultDoseG = doseG,
            defaultRatio = ratio,
            defaultBrewTempC = brewTempC,
            defaultPreinfuseS = preinfuseS,
        ) }
        persistPrefs()
    }

    /** Default barista name (free text), stamped on each new shot and brew log. Persisted. */
    fun setBaristaName(name: String) {
        updateUi { it.copy(baristaName = name) }
        persistPrefs()
    }

    /** Sleep the machine when Crema is quit (swiped from recents) — the
     *  [QuitSleep] handler consumes this at fire time. */
    fun setSleepOnQuit(on: Boolean) {
        updateUi { it.copy(sleepOnQuit = on) }
        persistPrefs()
    }

    /** Set the screensaver idle timeout, minutes (0 = never). Persisted. */
    fun setScreensaverAfterMin(minutes: Int) {
        updateUi { it.copy(screensaverAfterMin = minutes.coerceIn(0, 120)) }
        persistPrefs()
    }

    /** Toggle the coupled machine wake on saver dismiss. Persisted. */
    fun setWakeMachineWithSaver(on: Boolean) {
        updateUi { it.copy(wakeMachineWithSaver = on) }
        persistPrefs()
    }

    /** Show/hide the inline debug panel (Settings → Advanced). Persisted. */
    fun setShowDebugPanel(on: Boolean) {
        updateUi { it.copy(showDebugPanel = on) }
        persistPrefs()
    }

    fun setProxyPrimaryHost(host: String) {
        updateUi { it.copy(proxyPrimaryHost = host.trim()) }
        persistPrefs()
    }

    fun setProxyPrimaryPort(port: Int) {
        updateUi { it.copy(proxyPrimaryPort = port) }
        persistPrefs()
    }

    /** Debug: replay a captured shot as a fake DE1 when primary (emulator demos).
     *  Restart-to-apply (read at primary-start). Off = a real primary uses live BT. */
    fun setReplayPrimary(enabled: Boolean) {
        updateUi { it.copy(replayPrimary = enabled) }
        persistPrefs()
    }

    /** Set the theme mode (`"system"` / `"light"` / `"dark"`) and persist. */
    fun setThemeMode(mode: String) {
        updateUi { it.copy(themeMode = mode) }
        persistPrefs()
    }
}

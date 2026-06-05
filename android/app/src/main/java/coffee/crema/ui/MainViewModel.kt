package coffee.crema.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import coffee.crema.core.Command
import coffee.crema.core.CoreOutput
import coffee.crema.core.CremaBridge
import coffee.crema.core.Event
import coffee.crema.core.MachineRequest
import coffee.crema.core.ScaleCapabilities
import coffee.crema.core.Bean
import coffee.crema.core.Roaster
import coffee.crema.core.blankCremaProfile
import coffee.crema.core.builtinCremaProfiles
import coffee.crema.core.cremaProfileToWire
import coffee.crema.beans.BeanLibrary
import coffee.crema.beans.LibraryStore
import coffee.crema.beans.newBean
import coffee.crema.beans.newRoaster
import coffee.crema.history.HistoryStore
import coffee.crema.history.StoredShot
import coffee.crema.history.downsampleForStorage
import coffee.crema.settings.AppPrefs
import coffee.crema.settings.SettingsStore
import coffee.crema.ble.BleScanner
import coffee.crema.ble.BleSessionRecorder
import coffee.crema.ble.De1BleManager
import coffee.crema.ble.NordicBleTransport
import coffee.crema.ble.ScaleBleManager
import coffee.crema.profiles.CremaProfile
import coffee.crema.profiles.CustomProfileStore
import coffee.crema.profiles.brewDefaultsJson
import coffee.crema.profiles.duplicatedCustomProfileJson
import coffee.crema.profiles.patchCremaProfileJson
import coffee.crema.profiles.profileIdOf
import coffee.crema.profiles.SegmentEdit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Default scale beeper-volume step shown before the first live reading.
 *
 * The scale's true volume is decoded live from every Bookoo weight
 * notification (`Event.ScaleReading.device_volume`) and projected into
 * [MainUiState.scaleVolume] as soon as it arrives — this default is only the
 * fallback step the control shows in the brief window before that first
 * reading. The control's range is taken from `ScaleCapabilities.volume`.
 */
const val DEFAULT_SCALE_VOLUME: Int = 3

/**
 * Default scale auto-standby timeout (minutes) shown before the first live
 * reading.
 *
 * Like [DEFAULT_SCALE_VOLUME], this is only the fallback shown until the first
 * `Event.ScaleReading.device_standby` arrives, after which the control
 * tracks the scale's real value. The range comes from
 * `ScaleCapabilities.standby_minutes`.
 */
const val DEFAULT_SCALE_STANDBY_MINUTES: Int = 15

/**
 * Summary of the most recently completed espresso shot — assembled from
 * `Event.ShotCompleted` for the Brew screen's "Last shot" card. Null until a
 * shot finishes; held until the next shot starts.
 */
data class LastShot(
    /** Total shot duration, milliseconds. */
    val durationMs: Long,
    /** Final scale weight before the shot ended (the yield), grams, or null. */
    val yieldG: Float?,
    /** Peak group pressure across the shot, bar, or null. */
    val peakPressure: Float?,
    /** Peak group-head temperature across the shot, °C, or null. */
    val peakTemp: Float?,
)

/** A flat snapshot of everything the current screen shows. */
data class MainUiState(
    val bleState: De1BleManager.State = De1BleManager.State.IDLE,
    /** Coarse state of the scale connection. */
    val scaleState: ScaleBleManager.State = ScaleBleManager.State.IDLE,
    /** Most recent status line (scan / connect / error transitions). */
    val status: String = "Idle",
    /** Latest decoded machine state + substate, or null before the first one. */
    val machineState: String? = null,
    /** Latest shot phase. */
    val shotPhase: String? = null,
    /** Latest telemetry sample, pre-formatted (debug readout). */
    val telemetry: String? = null,
    // ── Structured live telemetry (Brew dashboard) ───────────────────────────
    // Projected from `Event.Telemetry`; null until the first sample of a session.
    /** Group pressure, bar. */
    val pressure: Float? = null,
    /** Group flow, ml/s. */
    val flow: Float? = null,
    /** Group-head temperature, °C (the COFFEE channel). */
    val headTemp: Float? = null,
    /** Mix temperature, °C — the DE1's blended group water (COFFEE-card secondary). */
    val mixTemp: Float? = null,
    /** Steam-heater temperature, °C (foot "Steam" readout). */
    val steamTemp: Float? = null,
    /** Running shot volume dispensed so far, ml — integrated by the core. */
    val dispensedVolume: Float? = null,
    /** Puck resistance, `bar/(ml/s)²` — pump-flow derived, or null near zero flow. */
    val resistance: Float? = null,
    /** Scale-derived puck resistance, `bar/(g/s)²` — preferred when a scale is paired. */
    val resistanceWeight: Float? = null,
    /** Milliseconds since the shot began (`Event.Telemetry.elapsed`); resets each shot. */
    val shotElapsedMs: Long = 0,
    /** Zero-based active frame index (`Event.ShotFrameChanged`); resets each shot. */
    val shotFrame: Int = 0,
    /** True between `ShotStarted` and `ShotCompleted` — drives resting↔extracting UI. */
    val shotInProgress: Boolean = false,
    /**
     * Buffered per-sample telemetry for the live shot chart — appended on each
     * `Event.Telemetry` while [shotInProgress], reset on `ShotStarted`, capped at
     * [SHOT_TELEMETRY_CAP]. Empty when no shot is running. The scalar fields
     * above stay live outside a shot (for the resting channel cards); this series
     * only fills during extraction (when the chart draws curves).
     */
    val shotTelemetry: List<TelemetrySample> = emptyList(),
    /** Raw machine-state name (e.g. `"Espresso"`) for `==` comparisons, or null. */
    val machineStateName: String? = null,
    /** Raw machine-substate name (e.g. `"Pouring"`), or null. */
    val machineSubstate: String? = null,
    /** Latest tank level, mm (`Event.WaterLevel`), or null before the first report. */
    val waterLevelMm: Float? = null,
    /** Summary of the last completed shot (Last-shot card), or null. */
    val lastShot: LastShot? = null,
    /**
     * Built-in profiles from the core (`builtinCremaProfiles()`), loaded once at
     * startup. The Brew header picker chooses from these until the profile
     * library (M3) adds user profiles.
     */
    val profiles: List<CremaProfile> = emptyList(),
    /** The selected profile's id (the Brew header's active profile), or null. */
    val activeProfileId: String? = null,
    /**
     * True while a profile is being uploaded to the DE1 (between
     * `ProfileUploadStarted` and `ProfileUploadCompleted`/`Failed`) — drives the
     * header "Uploading…" hint and the Coffee button's syncing state during the
     * gated shot start.
     */
    val profileUploading: Boolean = false,
    /** Upload progress as `"received/total"` acks while uploading, or null. */
    val profileUploadProgress: String? = null,
    /**
     * Shot-behaviour toggles surfaced in Quick Controls. Shell-managed and
     * optimistic — the core applies them via its setters but does not echo them
     * back as events, so these hold the last user choice (the same pattern the
     * scale config toggles use).
     */
    val autoTare: Boolean = false,
    val stopOnWeight: Boolean = false,
    val steamEco: Boolean = false,
    /**
     * Which telemetry channels the live chart draws — keys: `pressure`, `flow`,
     * `headTemp`, `mixTemp`, `weight`, `weightFlow`, `dispensedVolume`,
     * `resistance`. A local display pref toggled from Quick Controls; defaults
     * match the web (pressure / flow / weight).
     */
    val chartChannels: Set<String> = setOf("pressure", "flow", "weight"),
    /** The bean library — user bean bags, persisted via [LibraryStore]. */
    val beans: List<Bean> = emptyList(),
    /** The roaster directory (FK target for [beans]). */
    val roasters: List<Roaster> = emptyList(),
    /** The active bean id — the Brew bean block + burn-down, or null. */
    val activeBeanId: String? = null,
    /** The bean currently open in the editor (`bean-edit` route), or null. */
    val editingBeanId: String? = null,
    /** The profile currently open in the editor (`profile-edit` route), or null. */
    val editingProfileId: String? = null,
    /**
     * A brand-new or duplicated profile being edited but not yet saved. The editor
     * seeds from this when [editingProfileId] names a profile not yet in [profiles]
     * (New / Duplicate); null when editing an existing saved custom profile. Cleared
     * on save or cancel.
     */
    val draftProfile: CremaProfile? = null,
    /** Completed-shot log (newest first), persisted via [HistoryStore]. */
    val history: List<StoredShot> = emptyList(),
    /** Theme mode — `"system" | "light" | "dark"` (Settings), persisted. */
    val themeMode: String = "dark",
    /** Latest scale weight in grams, or null before the first reading. */
    val scaleWeightG: Float? = null,
    /**
     * Latest scale-reported native mass-flow rate in g/s, or null — only
     * scales that report their own flow (the Bookoo) populate this; it is
     * distinct from the core-computed flow.
     */
    val scaleFlowGPerS: Float? = null,
    /**
     * Latest scale-reported built-in-timer reading in milliseconds, or null —
     * only scales with a built-in timer (the Bookoo) populate this.
     */
    val scaleTimerMs: Long? = null,
    /**
     * What the connected scale can do beyond reporting a bare weight, or null
     * before a scale is connected / identified. The UI gates configuration
     * controls on this descriptor — Crema is capability-driven, never
     * device-driven, so the UI never branches on the concrete scale model.
     */
    val scaleCapabilities: ScaleCapabilities? = null,
    /**
     * The scale beeper-volume step (0..5) the UI currently shows.
     *
     * Two-way: it follows the scale's live value, decoded from every Bookoo
     * weight notification (`Event.ScaleReading.device_volume`), and is also
     * updated optimistically the moment the user changes the control — the
     * stream then catches up. Starts at [DEFAULT_SCALE_VOLUME] until the first
     * reading arrives.
     */
    val scaleVolume: Int = DEFAULT_SCALE_VOLUME,
    /**
     * The scale auto-standby timeout (minutes) the UI currently shows.
     *
     * Two-way like [scaleVolume]: it follows the scale's live value
     * (`Event.ScaleReading.device_standby`) and is updated
     * optimistically on user change. Starts at [DEFAULT_SCALE_STANDBY_MINUTES].
     */
    val scaleStandbyMinutes: Int = DEFAULT_SCALE_STANDBY_MINUTES,
    /**
     * The scale's battery charge percentage, or null before the first reading
     * (or for a scale that does not report it). Read-only — only the Bookoo
     * reports a battery level, decoded from its weight notification.
     */
    val scaleBatteryPercent: Int? = null,
    /**
     * The connected scale's BLE advertised name (e.g. `"BOOKOO_SC 502158"`),
     * or null when no scale is connected. Captured on connect from the name
     * the scanner resolved off the peripheral's advertisement; cleared on
     * [MainViewModel.disconnectScale]. Read-only — it is the device's BLE
     * identity, not a configurable setting.
     */
    val scaleName: String? = null,
    /**
     * The connected scale's firmware version, pre-formatted `"M.m.p"` (e.g.
     * `"1.4.1"`), or null until the scale's `03 0c` serial response arrives
     * (or for a scale that does not report it). Read-only — decoded from
     * `Event.ScaleConfig.firmware_version`, the `u16` the Bookoo answers its
     * connect-time `0x0a` query with, encoded `major × 100 + minor × 10 +
     * patch`.
     */
    val scaleFirmware: String? = null,
    /**
     * The connected scale's serial number, or null until the scale's `03 0c`
     * serial response arrives (or for a scale that does not report one).
     * Read-only — taken straight from `Event.ScaleConfig.serial`.
     */
    val scaleSerial: String? = null,
    /**
     * Whether the scale's flow smoothing toggle is shown as on.
     *
     * Two-way like [scaleVolume]: the Bookoo echoes its real flow-smoothing
     * state in every weight notification (`Event.ScaleReading.device_flow_smoothing`),
     * so the toggle follows that live value; it is also updated optimistically
     * the moment the user flips the control, after which the stream confirms
     * it. Starts off until the first reading arrives.
     */
    val scaleFlowSmoothing: Boolean = false,
    /**
     * The scale's currently selected auto-stop mode id (`0` = Flow-Stop, `1` =
     * Cup-Removal), or `null` until the first reading arrives (or for a scale
     * that does not report it).
     *
     * Two-way like [scaleFlowSmoothing]: the Bookoo echoes its real auto-stop
     * mode in every weight notification (`Event.ScaleReading.device_auto_stop`),
     * so the selector highlights that live value; it is also updated
     * optimistically the moment the user picks a mode, after which the stream
     * confirms it.
     */
    val scaleAutoStop: Int? = null,
    /**
     * Whether the scale's anti-mistouch toggle is shown as on.
     *
     * Two-way like [scaleFlowSmoothing]: the Bookoo reports its real
     * anti-mistouch state on its `ff12` command channel — in the `03 0c` serial
     * response to a connect-time query and after every anti-mistouch write
     * (`Event.ScaleConfig.anti_mistouch`) — so the toggle follows that live
     * value; it is also updated optimistically the moment the user flips the
     * control, after which the response confirms it. Starts off until the
     * first `ScaleConfig` arrives.
     */
    val scaleAntiMistouch: Boolean = false,
    /**
     * The scale's active display-mode index (`0` = Flow-Rate, `1` = Timer,
     * `2` = Auto), or `null` until the first `ff12` settings response arrives.
     *
     * Two-way like [scaleAutoStop]: the Bookoo reports its active mode on its
     * `ff12` command channel in the `03 0e` settings response
     * (`Event.ScaleConfig.active_mode`), so the mode selector highlights that
     * live value; it is also updated optimistically the moment the user picks
     * a mode, after which the response confirms it.
     */
    val scaleActiveMode: Int? = null,
    /** A rolling event log, newest first. */
    val eventLog: List<String> = emptyList(),
)

/**
 * Owns the [CremaBridge] and the [De1BleManager], parses the JSON `CoreOutput`
 * the bridge returns, and projects decoded [Event]s into [MainUiState].
 *
 * Carried over from the Phase-0 proof-of-concept: connection, core ownership,
 * and per-screen state are still folded into one class so the end-to-end path
 * is readable top to bottom. Splitting these into proper layers is future work.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val json = Json { ignoreUnknownKeys = true }

    /** The Rust core, behind the UniFFI bridge. */
    private val bridge: CremaBridge = CremaBridge()

    private val _ui = MutableStateFlow(MainUiState())
    val ui: StateFlow<MainUiState> = _ui.asStateFlow()

    /**
     * Raw built-in profile JSON, keyed by id — the full `CremaProfile` shape, kept
     * for upload because [MainUiState.profiles]'s thin model drops fields the wire
     * conversion ([cremaProfileToWire]) needs. Populated in [loadBuiltinProfiles].
     */
    private var builtinProfileJson: Map<String, String> = emptyMap()

    /** Built-in profiles parsed into the thin display model, cached for [refreshProfiles]. */
    private var builtinProfiles: List<CremaProfile> = emptyList()

    /**
     * Custom (user) profiles as complete `CremaProfile` JSON, newest first — the
     * editor's save target, persisted via [CustomProfileStore]. Kept as full JSON
     * (not the thin model) so every wire-relevant field round-trips on upload; see
     * [coffee.crema.profiles.patchCremaProfileJson].
     */
    private var customProfilesJson: List<String> = emptyList()

    /**
     * Complete `CremaProfile` JSON by id — built-ins + customs — the lookup
     * [startShot] feeds to [cremaProfileToWire]. Rebuilt by [refreshProfiles].
     */
    private var profileJsonById: Map<String, String> = emptyMap()

    /**
     * A new / duplicated profile's complete base JSON, held between [startNewProfile]
     * / [duplicateProfile] and the editor's [saveProfile]; null otherwise.
     */
    private var draftProfileJson: String? = null

    /**
     * Set when [startShot] kicks off a profile upload that should be followed by an
     * Espresso request once the upload completes (the gated start). Cleared on
     * `ProfileUploadCompleted` (after firing Espresso), on `ProfileUploadFailed`,
     * on the upload timeout, and on disconnect.
     */
    private var pendingBrew = false

    /** Bean-library persistence — a JSON file in filesDir. */
    private val library = LibraryStore(app, json)

    /** Shot-history persistence — a JSON file in filesDir. */
    private val historyStore = HistoryStore(app, json)

    /** App-preferences persistence — a JSON file in filesDir. */
    private val settingsStore = SettingsStore(app, json)

    /** Custom-profile persistence — a JSON file in filesDir. */
    private val customProfileStore = CustomProfileStore(app, json)

    /**
     * The app-wide BLE transport — the one Nordic-backed [NordicBleTransport].
     * Created once here and [NordicBleTransport.close]d from [onCleared]; it
     * owns the Nordic `Environment` (a broadcast receiver that must be closed).
     * The scanner and both managers sit on top of it.
     */
    private val bleTransport = NordicBleTransport(app)

    /**
     * The one app-wide BLE scanner, shared by both managers. A single
     * unfiltered scan dispatches each result by advertised-name match to
     * whichever device currently wants to connect — see [connect] / [connectScale].
     */
    private val bleScanner = BleScanner(
        transport = bleTransport,
        onStatus = { line -> _ui.value = _ui.value.copy(status = line) },
    )

    /**
     * The one session-wide BLE recorder, shared by both managers. A connection
     * counter inside it opens a single capture file when the first device (DE1
     * or scale) connects and closes it when the last disconnects, so a session
     * with both devices produces ONE interleaved file that replays through a
     * single `CremaCore` — important for scale-aware behaviour like shot-start
     * auto-tare.
     */
    private val bleRecorder = BleSessionRecorder(app)

    private val ble = De1BleManager(
        transport = bleTransport,
        bridge = bridge,
        recorder = bleRecorder,
        onCoreOutput = ::onCoreOutputJson,
        onStatus = { line -> _ui.value = _ui.value.copy(status = line) },
    )

    /**
     * The Bookoo scale connection. Shares the one [bridge] with [ble]: the core
     * is internally `Mutex`-guarded, so both managers feeding it concurrently
     * is safe. Its `CoreOutput` goes through the same [onCoreOutputJson] path.
     * It also shares the one [bleRecorder] so its weight notifications and
     * tare/timer writes land in the same interleaved capture file as the DE1's.
     */
    private val scale = ScaleBleManager(
        transport = bleTransport,
        bridge = bridge,
        recorder = bleRecorder,
        onCoreOutput = ::onCoreOutputJson,
        onStatus = { line -> _ui.value = _ui.value.copy(status = line) },
        onScaleIdentified = ::refreshScaleCapabilities,
    )

    init {
        // Collect the managers' coarse connection-state flows so the UI
        // snapshot updates promptly when either advances — rather than only
        // when an unrelated event happens to call observeBleState().
        viewModelScope.launch {
            ble.state.collect { state ->
                _ui.value = _ui.value.copy(bleState = state)
            }
        }
        viewModelScope.launch {
            scale.state.collect { state ->
                _ui.value = _ui.value.copy(scaleState = state)
            }
        }
        loadBuiltinProfiles()
        // Load both persisted stores in one coroutine, sequentially, so their
        // _ui copies don't race (read-modify-write of the shared snapshot).
        viewModelScope.launch {
            loadLibrary()
            loadHistory()
            loadPrefs()
            loadCustomProfiles()
        }
    }

    /**
     * Load the core's built-in profiles once and seed the Brew header's active
     * selection. `builtinCremaProfiles()` is a sans-IO core call returning a
     * JSON array of `CremaProfile`; the adapter logic lives in the Rust core
     * (de1_domain::crema_profile) and every shell shares it. Failures are
     * swallowed — the header just shows "No profile selected".
     */
    private fun loadBuiltinProfiles() {
        val raw = runCatching { builtinCremaProfiles() }.getOrElse {
            appendLog("Load built-in profiles failed: ${it.message}")
            return
        }
        val list = runCatching {
            json.decodeFromString(ListSerializer(CremaProfile.serializer()), raw)
        }.getOrElse {
            appendLog("Parse built-in profiles failed: ${it.message}")
            return
        }
        // Retain the full per-profile JSON (keyed by id) for upload — the thin
        // CremaProfile model decoded above is for display only and drops fields
        // the wire conversion needs.
        builtinProfileJson = runCatching {
            json.parseToJsonElement(raw).jsonArray.associate { element ->
                val id = element.jsonObject["id"]?.jsonPrimitive?.content.orEmpty()
                id to element.toString()
            }
        }.getOrElse { emptyMap() }
        builtinProfiles = list
        refreshProfiles()
    }

    /**
     * Recompute the merged profile list (customs first, then built-ins) and the
     * id → full-JSON upload map from the two raw-JSON sources. Seeds the active
     * selection to the first profile when none is set. Called whenever the
     * built-in or custom sources change.
     */
    private fun refreshProfiles() {
        val customThin = customProfilesJson.mapNotNull { raw ->
            runCatching { json.decodeFromString(CremaProfile.serializer(), raw) }.getOrNull()
        }
        val all = customThin + builtinProfiles
        profileJsonById = buildMap {
            putAll(builtinProfileJson)
            customProfilesJson.forEach { raw ->
                profileIdOf(raw, json)?.takeIf { it.isNotEmpty() }?.let { put(it, raw) }
            }
        }
        _ui.value = _ui.value.copy(
            profiles = all,
            activeProfileId = _ui.value.activeProfileId ?: all.firstOrNull()?.id,
        )
    }

    /** Load the persisted custom profiles into the merged list at startup. */
    private suspend fun loadCustomProfiles() {
        customProfilesJson = customProfileStore.load()
        refreshProfiles()
    }

    /** Persist the current custom profiles (best-effort, off the main thread). */
    private fun persistCustomProfiles() {
        val snapshot = customProfilesJson
        viewModelScope.launch { customProfileStore.save(snapshot) }
    }

    /** Select the Brew header's active profile. (M1: display only — uploading the
     *  profile to the DE1 on Coffee is the M2 gated-start sequence.) */
    fun setActiveProfile(id: String) {
        _ui.value = _ui.value.copy(activeProfileId = id)
    }

    // ── Profile editor (the `profile-edit` route) ─────────────────────────────

    /** Open an existing saved (custom) profile in the editor. */
    fun startEditProfile(id: String) {
        draftProfileJson = null
        _ui.value = _ui.value.copy(editingProfileId = id, draftProfile = null)
    }

    /**
     * Begin a brand-new custom profile: the core mints a complete blank
     * `CremaProfile` (a fresh id, `source: "custom"`, the default segment list)
     * seeded from the user's brew defaults. Held as a draft until the editor saves.
     */
    fun startNewProfile() {
        val blank = runCatching { blankCremaProfile(brewDefaultsJson()) }.getOrElse {
            appendLog("New profile failed: ${it.message}")
            return
        }
        beginDraft(blank)
    }

    /**
     * Duplicate any profile (built-in or custom) into a fresh custom draft — the
     * full recipe (every segment, temp, limiter) is preserved verbatim so the copy
     * uploads identically; only id / source / name / pinned change. Opens the editor.
     */
    fun duplicateProfile(id: String) {
        val base = profileJsonById[id] ?: run {
            appendLog("Duplicate: profile $id not found")
            return
        }
        val copy = runCatching { duplicatedCustomProfileJson(base, json) }.getOrElse {
            appendLog("Duplicate failed: ${it.message}")
            return
        }
        beginDraft(copy)
    }

    /** Stash a complete profile JSON as the editor's draft and route the editor to it. */
    private fun beginDraft(fullJson: String) {
        val thin = runCatching {
            json.decodeFromString(CremaProfile.serializer(), fullJson)
        }.getOrElse {
            appendLog("Draft parse failed: ${it.message}")
            return
        }
        draftProfileJson = fullJson
        _ui.value = _ui.value.copy(editingProfileId = thin.id, draftProfile = thin)
    }

    /**
     * Save the editor's non-curve edits. Patches only the edited fields into the
     * profile's complete JSON base (the draft for a new / duplicated profile, else
     * the stored custom), so every wire-relevant field — per-segment temp / mode /
     * ramp, notes, beverage type — round-trips untouched to the DE1. Upserts into
     * the custom store (newest first for a new profile; in place for an edit),
     * refreshes the merged list, and persists.
     *
     * @param segments (target, time) per existing segment, in order — the non-curve
     *   editor never adds / removes segments, so this matches the base length.
     */
    fun saveProfile(
        id: String,
        name: String,
        roast: String?,
        tags: List<String>,
        pinned: Boolean,
        dose: Float,
        yieldOut: Float,
        brewTemp: Float,
        maxTotalVolumeMl: Int,
        segments: List<SegmentEdit>,
    ) {
        val isExisting = customProfilesJson.any { profileIdOf(it, json) == id }
        val base = when {
            draftProfileJson != null && _ui.value.editingProfileId == id -> draftProfileJson
            isExisting -> customProfilesJson.firstOrNull { profileIdOf(it, json) == id }
            else -> null
        }
        if (base == null) {
            appendLog("Save profile: base for $id not found")
            return
        }
        val patched = runCatching {
            patchCremaProfileJson(
                baseJson = base,
                name = name,
                roast = roast,
                tags = tags,
                pinned = pinned,
                dose = dose,
                yieldOut = yieldOut,
                brewTemp = brewTemp,
                maxTotalVolumeMl = maxTotalVolumeMl,
                segments = segments,
                json = json,
            )
        }.getOrElse {
            appendLog("Save profile failed: ${it.message}")
            return
        }
        customProfilesJson = if (isExisting) {
            customProfilesJson.map { if (profileIdOf(it, json) == id) patched else it }
        } else {
            listOf(patched) + customProfilesJson
        }
        // Clear the draft but KEEP editingProfileId: the saved profile is now in
        // customProfilesJson, so the editor still resolves it (no "no profile"
        // flash before the nav pops), and a double-tap Save updates in place via
        // the isExisting branch rather than appending a duplicate. The next
        // start*Profile / cancel resets editingProfileId.
        draftProfileJson = null
        _ui.value = _ui.value.copy(draftProfile = null)
        refreshProfiles()
        persistCustomProfiles()
    }

    /** Close the editor without saving (discards any new / duplicated draft). */
    fun cancelProfileEdit() {
        draftProfileJson = null
        _ui.value = _ui.value.copy(editingProfileId = null, draftProfile = null)
    }

    /**
     * Delete a custom profile (built-ins are not deletable — they have no store
     * entry, so this is a no-op for them). Clears the active selection if it was
     * the deleted one; [refreshProfiles] then reseeds it to the first remaining.
     */
    fun deleteProfile(id: String) {
        if (customProfilesJson.none { profileIdOf(it, json) == id }) return
        customProfilesJson = customProfilesJson.filterNot { profileIdOf(it, json) == id }
        if (_ui.value.activeProfileId == id) {
            _ui.value = _ui.value.copy(activeProfileId = null)
        }
        refreshProfiles()
        persistCustomProfiles()
    }

    /** Start steaming. */
    fun steam() = requestMachineState(MachineRequest.STEAM)

    /** Start hot water. */
    fun hotWater() = requestMachineState(MachineRequest.HOT_WATER)

    /** Start a group-head flush / rinse. */
    fun flush() = requestMachineState(MachineRequest.FLUSH)

    /**
     * Start an espresso shot the gated way (web parity): upload the active
     * profile to the DE1, wait for the upload to complete, observe the firmware's
     * profile-download guard window, then request the Espresso state. The Espresso
     * request itself fires from [applyEvent] on `ProfileUploadCompleted`.
     *
     * The active profile is converted from the editable `CremaProfile` shape to
     * the wire `Profile` the DE1 expects by the sans-IO core
     * ([cremaProfileToWire]); the upload's frame-write commands + the
     * `ProfileUploadStarted` event come back as a `CoreOutput` and run through the
     * shared command path.
     *
     * M2 v1 always uploads (no fingerprint-skip optimisation yet) and has no
     * pre-shot flush (no settings store on Android yet) — both are later refinements.
     */
    fun startShot() {
        val cremaJson = _ui.value.activeProfileId?.let { profileJsonById[it] }
        if (cremaJson == null) {
            appendLog("Can't start shot: no active profile")
            return
        }
        val wireJson = runCatching { cremaProfileToWire(cremaJson) }.getOrElse {
            appendLog("Profile convert failed: ${it.message}")
            return
        }
        val raw = runCatching {
            bridge.uploadProfile(wireJson, System.currentTimeMillis().toULong())
        }.getOrElse {
            appendLog("Upload profile failed: ${it.message}")
            return
        }
        pendingBrew = true
        _ui.value = _ui.value.copy(profileUploading = true, profileUploadProgress = null)
        onCoreOutputJson(raw)
        // Backstop: if no ProfileUploadCompleted arrives, clear the pending brew
        // so the UI doesn't hang in "uploading" forever.
        viewModelScope.launch {
            delay(PROFILE_UPLOAD_TIMEOUT_MS)
            if (pendingBrew) {
                pendingBrew = false
                _ui.value = _ui.value.copy(profileUploading = false, profileUploadProgress = null)
                appendLog("Profile upload timed out; shot not started")
            }
        }
    }

    /** Called from the UI once the BLE runtime permissions have been granted. */
    fun connect() {
        _ui.value = _ui.value.copy(eventLog = emptyList())
        // Show "scanning" on the connection-status UI while the shared scanner
        // hunts; the scanner's onFound hands the matched DE1 to ble.connect.
        ble.markScanning()
        bleScanner.scanFor(SCAN_LABEL_DE1, De1BleManager::isDe1Name) { device, _ ->
            ble.connect(device)
        }
        // The manager's state flow is collected in `init`; markScanning() above
        // already pushed the new state, so no manual reflection is needed here.
    }

    fun disconnect() {
        // Drop any outstanding scan want too — the user may disconnect mid-scan.
        bleScanner.cancel(SCAN_LABEL_DE1)
        ble.disconnect()
        pendingBrew = false
        _ui.value = _ui.value.copy(
            bleState = De1BleManager.State.DISCONNECTED,
            machineState = null,
            machineStateName = null,
            machineSubstate = null,
            shotPhase = null,
            telemetry = null,
            // Drop the live telemetry so the Brew cards clear to "—" for the
            // next connection rather than freezing on the last machine's values.
            pressure = null,
            flow = null,
            headTemp = null,
            mixTemp = null,
            steamTemp = null,
            dispensedVolume = null,
            resistance = null,
            resistanceWeight = null,
            shotElapsedMs = 0,
            shotFrame = 0,
            shotInProgress = false,
            shotTelemetry = emptyList(),
            waterLevelMm = null,
            profileUploading = false,
            profileUploadProgress = null,
        )
    }

    // ---- Machine control (AND5) -------------------------------------------

    /**
     * Ask the DE1 to enter [state]. The core returns a `CoreOutput` whose
     * `Command.WriteCharacteristic(De1RequestedState)` is dispatched through the
     * shared command path — the same path the scale writes use. The Brew screen
     * (M1/M2) builds its Coffee / Stop / mode controls on this.
     */
    fun requestMachineState(state: MachineRequest) {
        val raw = runCatching { bridge.requestMachineState(state) }.getOrElse {
            appendLog("requestMachineState failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /** Wake the DE1 / return to idle (also stops a running shot). */
    fun wake() = requestMachineState(MachineRequest.IDLE)

    /** Put the DE1 to sleep. */
    fun sleep() = requestMachineState(MachineRequest.SLEEP)

    /** Start an espresso shot. */
    fun startEspresso() = requestMachineState(MachineRequest.ESPRESSO)

    /** Stop a running shot (return to idle). */
    fun stopShot() = requestMachineState(MachineRequest.IDLE)

    /** Enable/disable auto-tare at shot start (Quick Controls). Optimistic. */
    fun setAutoTare(enabled: Boolean) {
        _ui.value = _ui.value.copy(autoTare = enabled)
        runCatching { bridge.setAutoTare(enabled) }.onFailure {
            appendLog("Set auto-tare failed: ${it.message}")
        }
    }

    /**
     * Enable/disable stop-on-weight (Quick Controls). The yield target comes from
     * the active profile; this just arms/disarms the behaviour. Optimistic.
     */
    fun setStopOnWeight(enabled: Boolean) {
        _ui.value = _ui.value.copy(stopOnWeight = enabled)
        runCatching { bridge.setStopOnWeight(enabled) }.onFailure {
            appendLog("Set stop-on-weight failed: ${it.message}")
        }
    }

    /**
     * Enable/disable steam eco mode (Quick Controls). Returns a `CoreOutput` (an
     * MMR write) routed through the shared command path. Optimistic.
     */
    fun setSteamEco(enabled: Boolean) {
        _ui.value = _ui.value.copy(steamEco = enabled)
        val raw = runCatching {
            bridge.enableSteamEcoMode(enabled, System.currentTimeMillis().toULong())
        }.getOrElse {
            appendLog("Set steam eco failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /** Show/hide a live-chart channel (Quick Controls). Local display pref only. */
    fun toggleChartChannel(key: String, enabled: Boolean) {
        val next = _ui.value.chartChannels.toMutableSet().apply {
            if (enabled) add(key) else remove(key)
        }
        _ui.value = _ui.value.copy(chartChannels = next)
    }

    // ── Bean library ────────────────────────────────────────────────────────

    /** Load the persisted bean library into the UI snapshot at startup. */
    private suspend fun loadLibrary() {
        val lib = library.load()
        _ui.value = _ui.value.copy(
            beans = lib.beans,
            roasters = lib.roasters,
            activeBeanId = lib.activeBeanId ?: lib.beans.firstOrNull()?.id,
        )
    }

    /** Persist the current bean library (beans + roasters + active selection). */
    private fun persistLibrary() {
        val s = _ui.value
        val lib = BeanLibrary(s.beans, s.roasters, s.activeBeanId)
        viewModelScope.launch { library.save(lib) }
    }

    /**
     * Add a bean bag. [roasterName] is matched case-insensitively against the
     * roaster directory; an unseen roaster is minted. The new bean becomes the
     * active selection. Persisted immediately.
     */
    fun addBean(name: String, roasterName: String, roastLevel: Int?, roastedOn: String?) {
        if (name.isBlank()) return
        val now = System.currentTimeMillis()
        val (roasterId, roasters) = resolveRoaster(roasterName, _ui.value.roasters, now)
        val bean = newBean(name.trim(), roasterId, roastLevel, roastedOn?.takeIf { it.isNotBlank() }, now)
        _ui.value = _ui.value.copy(
            beans = _ui.value.beans + bean,
            roasters = roasters,
            activeBeanId = bean.id,
        )
        persistLibrary()
    }

    /**
     * Find a roaster by name (case-insensitive) or mint one. Returns the roaster
     * id (null for a blank name) and the possibly-extended roaster directory.
     */
    private fun resolveRoaster(
        name: String,
        roasters: List<Roaster>,
        nowMs: Long,
    ): Pair<String?, List<Roaster>> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null to roasters
        val existing = roasters.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
        return if (existing != null) {
            existing.id to roasters
        } else {
            val r = newRoaster(trimmed, nowMs)
            r.id to (roasters + r)
        }
    }

    /** Open a bean in the editor (the `bean-edit` route reads this). */
    fun startEditBean(id: String) {
        _ui.value = _ui.value.copy(editingBeanId = id)
    }

    /**
     * Apply edits to an existing bean (the editor's Save). Resolves the roaster
     * by name (find-or-create), stamps `updatedAt`, and persists.
     */
    fun updateBean(id: String, roasterName: String, transform: (Bean) -> Bean) {
        val s = _ui.value
        val existing = s.beans.firstOrNull { it.id == id } ?: return
        val now = System.currentTimeMillis()
        val (roasterId, roasters) = resolveRoaster(roasterName, s.roasters, now)
        // The shell owns the full field mapping (the core Bean already models
        // every field); we only resolve the roaster + stamp updatedAt here.
        val updated = transform(existing).copy(roasterId = roasterId, updatedAt = now)
        _ui.value = _ui.value.copy(
            beans = s.beans.map { if (it.id == id) updated else it },
            roasters = roasters,
        )
        persistLibrary()
    }

    /** Set the active bean (the Brew bean block). Persisted. */
    fun setActiveBean(id: String) {
        _ui.value = _ui.value.copy(activeBeanId = id)
        persistLibrary()
    }

    /** Remove a bean bag; reselect the first remaining if it was active. Persisted. */
    fun deleteBean(id: String) {
        val remaining = _ui.value.beans.filterNot { it.id == id }
        val wasActive = _ui.value.activeBeanId == id
        _ui.value = _ui.value.copy(
            beans = remaining,
            activeBeanId = if (wasActive) remaining.firstOrNull()?.id else _ui.value.activeBeanId,
        )
        persistLibrary()
    }

    // ── Shot history ──────────────────────────────────────────────────────────

    /** Load the persisted shot log at startup. */
    private suspend fun loadHistory() {
        _ui.value = _ui.value.copy(history = historyStore.load())
    }

    /**
     * Capture a completed shot into the log (newest first, capped) and persist.
     * Profile / bean names come from the active selections at completion. v1
     * stores a summary only — the telemetry slice for the detail chart is a
     * later increment.
     */
    private fun captureCompletedShot(
        durationMs: Long,
        yieldG: Float?,
        peakPressure: Float?,
        peakTemp: Float?,
    ) {
        val s = _ui.value
        val profile = s.profiles.firstOrNull { it.id == s.activeProfileId }
        val bean = s.beans.firstOrNull { it.id == s.activeBeanId }
        val beanName = bean?.let { b ->
            val roaster = b.roasterId?.let { rid -> s.roasters.firstOrNull { it.id == rid }?.name }
            listOfNotNull(roaster, b.name).joinToString(" · ")
        }
        val now = System.currentTimeMillis()
        val shot = StoredShot(
            id = "shot:$now",
            completedAtMs = now,
            durationMs = durationMs,
            yieldG = yieldG,
            doseG = profile?.dose,
            peakPressure = peakPressure,
            peakTemp = peakTemp,
            profileName = profile?.name,
            beanName = beanName,
            // The buffer still holds the just-finished shot (cleared on the next
            // ShotStarted); downsample it for the detail chart.
            samples = downsampleForStorage(s.shotTelemetry),
        )
        val next = (listOf(shot) + s.history).take(HistoryStore.MAX_SHOTS)
        _ui.value = _ui.value.copy(history = next)
        viewModelScope.launch { historyStore.save(next) }
    }

    /** Apply a user rating / tasting-notes edit to a logged shot. Persisted. */
    fun updateShot(id: String, rating: Int, notes: String) {
        val s = _ui.value
        val next = s.history.map {
            if (it.id == id) it.copy(rating = rating.coerceIn(0, 5).takeIf { r -> r > 0 }, notes = notes.ifBlank { null }) else it
        }
        _ui.value = s.copy(history = next)
        viewModelScope.launch { historyStore.save(next) }
    }

    // ── App settings ──────────────────────────────────────────────────────────

    /** Load persisted app preferences at startup. */
    private suspend fun loadPrefs() {
        _ui.value = _ui.value.copy(themeMode = settingsStore.load().themeMode)
    }

    /** Set the theme mode (`"system"` / `"light"` / `"dark"`) and persist. */
    fun setThemeMode(mode: String) {
        _ui.value = _ui.value.copy(themeMode = mode)
        viewModelScope.launch { settingsStore.save(AppPrefs(mode)) }
    }

    /** Scan for and connect to a Bookoo scale. Independent of the DE1. */
    fun connectScale() {
        scale.markScanning()
        bleScanner.scanFor(SCAN_LABEL_SCALE, ScaleBleManager::isBookooName) { device, name ->
            scale.connect(device, name)
        }
    }

    fun disconnectScale() {
        bleScanner.cancel(SCAN_LABEL_SCALE)
        scale.disconnect()
        _ui.value = _ui.value.copy(
            scaleState = ScaleBleManager.State.DISCONNECTED,
            scaleWeightG = null,
            scaleFlowGPerS = null,
            scaleTimerMs = null,
            // Drop the capabilities so the config UI hides until the next scale.
            scaleCapabilities = null,
            scaleVolume = DEFAULT_SCALE_VOLUME,
            scaleStandbyMinutes = DEFAULT_SCALE_STANDBY_MINUTES,
            scaleFlowSmoothing = false,
            scaleAutoStop = null,
            scaleAntiMistouch = false,
            scaleActiveMode = null,
            // Drop the scale's identity so the card clears for the next scale.
            scaleBatteryPercent = null,
            scaleName = null,
            scaleFirmware = null,
            scaleSerial = null,
        )
    }

    /**
     * Read the connected scale's [ScaleCapabilities] from the core and fold
     * them into the UI snapshot. Called by [ScaleBleManager] once the core has
     * identified the scale; the UI gates configuration controls on the result.
     *
     * Capability-driven, never device-driven: the UI never branches on the
     * concrete scale model, only on the capability flags.
     *
     * [advertisedName] is the scale's BLE name the scanner resolved — captured
     * into [MainUiState.scaleName] here so the scale card shows the device's
     * identity immediately, before the core's `0x0a` connect query returns the
     * firmware / serial.
     *
     * Also fires a baseline settings query at connect time: the core emits a
     * `Command.WriteScale` carrying the scale's `0x0f` settings-query command
     * (capability-gated — empty for a weight-only scale), routed through the
     * shared command path so the scale's `03 0e` settings response lands in
     * the capture.
     */
    private fun refreshScaleCapabilities(advertisedName: String) {
        val capsJson = runCatching { bridge.scaleCapabilities() }.getOrNull()
        val caps = capsJson?.let {
            runCatching {
                json.decodeFromString(ScaleCapabilities.serializer(), it)
            }.getOrNull()
        }
        _ui.value = _ui.value.copy(
            scaleCapabilities = caps,
            scaleName = advertisedName,
        )

        val queryRaw = runCatching { bridge.queryScaleSettings() }.getOrElse {
            appendLog("Query scale settings failed: ${it.message}")
            return
        }
        onCoreOutputJson(queryRaw)
    }

    /**
     * Set the connected scale's beeper volume to [level]. Asks the core for the
     * command bytes, then routes the resulting `Command.WriteScale` through the
     * shared command path — the same path as Tare and the core's auto-tare.
     *
     * The requested level is clamped to the connected scale's
     * `ScaleCapabilities.volume` `[min, max]` bounds; the core clamps again, so
     * an out-of-range value is harmless either way. The core only emits a
     * command for a scale whose capabilities include a settable `volume`, so
     * this is capability-gated end to end. The UI's shown volume is updated
     * optimistically; the live `Event.ScaleReading.device_volume` stream then
     * catches up and confirms the scale's real value.
     */
    fun setScaleVolume(level: Int) {
        val range = _ui.value.scaleCapabilities?.volume
        val clamped = if (range != null) {
            level.coerceIn(range.min.toInt(), range.max.toInt())
        } else {
            level
        }
        _ui.value = _ui.value.copy(scaleVolume = clamped)
        val raw = runCatching {
            bridge.setScaleVolume(clamped.toUByte())
        }.getOrElse {
            appendLog("Set volume failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Set the connected scale's auto-standby timeout to [minutes]. Asks the
     * core for the command bytes, then routes the resulting `Command.WriteScale`
     * through the shared command path — the same path as volume and Tare.
     *
     * The requested value is clamped to the connected scale's
     * `ScaleCapabilities.standby_minutes` `[min, max]` bounds; the core clamps
     * again, so an out-of-range value is harmless. Capability-gated end to end:
     * the core emits a command only for a scale that exposes a configurable
     * auto-standby. The UI's shown value is updated optimistically; the live
     * `Event.ScaleReading.device_standby` stream then catches up.
     */
    fun setScaleStandbyMinutes(minutes: Int) {
        val range = _ui.value.scaleCapabilities?.standby
        val clamped = if (range != null) {
            minutes.coerceIn(range.min.toInt(), range.max.toInt())
        } else {
            minutes
        }
        _ui.value = _ui.value.copy(scaleStandbyMinutes = clamped)
        val raw = runCatching {
            bridge.setScaleStandby(clamped.toUByte())
        }.getOrElse {
            appendLog("Set standby failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Enable or disable the connected scale's flow smoothing. Asks the core for
     * the command bytes, then routes the resulting `Command.WriteScale` through
     * the shared command path. Capability-gated: the core emits a command only
     * for a scale that supports flow smoothing. The UI's shown toggle is
     * updated optimistically; the live `Event.ScaleReading.device_flow_smoothing`
     * stream then catches up and confirms the scale's real state.
     */
    fun setScaleFlowSmoothing(enabled: Boolean) {
        _ui.value = _ui.value.copy(scaleFlowSmoothing = enabled)
        val raw = runCatching {
            bridge.setScaleFlowSmoothing(enabled)
        }.getOrElse {
            appendLog("Set flow smoothing failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Enable or disable the connected scale's anti-mistouch. Asks the core for
     * the command bytes, then routes the resulting `Command.WriteScale` through
     * the shared command path. Capability-gated: the core emits a command only
     * for a scale that supports anti-mistouch. The UI's shown toggle is updated
     * optimistically (the value is not read back).
     */
    fun setScaleAntiMistouch(enabled: Boolean) {
        _ui.value = _ui.value.copy(scaleAntiMistouch = enabled)
        val raw = runCatching {
            bridge.setScaleAntiMistouch(enabled)
        }.getOrElse {
            appendLog("Set anti-mistouch failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Switch the connected scale to display mode [modeId]. Asks the core for
     * the command bytes, then routes the result through the shared command
     * path. Switching a mode is **three** `Command.WriteScale` entries; the
     * core emits them in order and [onCoreOutputJson]'s `commands.forEach`
     * loop preserves that order, so all three reach `ScaleBleManager` in
     * sequence. Capability-gated: the core emits commands only for a scale that
     * exposes switchable modes and only for a listed [modeId].
     *
     * The UI's shown active mode is updated optimistically; the live
     * `Event.ScaleConfig.active_mode` stream (the scale's `03 0e` settings
     * response) then catches up and confirms the scale's real mode.
     */
    fun setScaleMode(modeId: Int) {
        _ui.value = _ui.value.copy(scaleActiveMode = modeId)
        val raw = runCatching {
            bridge.setScaleMode(modeId.toUByte())
        }.getOrElse {
            appendLog("Set mode failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Select the connected scale's auto-stop mode ([modeId]: 0 = flow-stop,
     * 1 = cup-removal). Asks the core for the command bytes, then routes the
     * resulting `Command.WriteScale` through the shared command path.
     * Capability-gated end to end: the core emits a command only for a scale
     * that supports an auto-stop-mode setting and only for an in-range
     * [modeId]. The UI's shown selection is updated optimistically; the live
     * `Event.ScaleReading.device_auto_stop` stream then catches up and confirms
     * the scale's real mode.
     */
    fun setScaleAutoStop(modeId: Int) {
        _ui.value = _ui.value.copy(scaleAutoStop = modeId)
        val raw = runCatching {
            bridge.setScaleAutoStop(modeId.toUByte())
        }.getOrElse {
            appendLog("Set auto-stop failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Tare the connected scale. Asks the core for the tare bytes, then routes
     * the resulting `Command.WriteScale` through the shared command path so the
     * exact same code handles a manual tare and the core's auto-tare.
     */
    fun tareScale() {
        val raw = runCatching { bridge.tareScale() }.getOrElse {
            appendLog("Tare failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * Beep the connected scale (locate / test tone). Capability-gated — the
     * core only emits a command for a scale that supports it; it throws
     * `CremaException` for one that doesn't, which we surface and swallow.
     */
    fun beepScale() {
        val raw = runCatching { bridge.scaleBeep() }.getOrElse {
            appendLog("Beep failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /** Clear the scale's tracked peak + final weight (Scale page "Reset peak"). */
    fun resetScalePeaks() {
        runCatching { bridge.resetScalePeaks() }.onFailure {
            appendLog("Reset peak failed: ${it.message}")
        }
    }

    /**
     * Start the connected scale's built-in timer. Capability-gated like
     * [beepScale]; routed through the shared command path.
     */
    fun startScaleTimer() {
        val raw = runCatching { bridge.startTimer() }.getOrElse {
            appendLog("Start timer failed: ${it.message}")
            return
        }
        onCoreOutputJson(raw)
    }

    /**
     * The FFI/BLE seam: a JSON `CoreOutput` string came back from
     * [CremaBridge.onNotification]. Deserialize it and fold its [Event]s into
     * the UI state.
     *
     * Runs on a BLE binder thread; [MutableStateFlow] assignment is thread-safe
     * and Compose collects it on the main thread.
     */
    private fun onCoreOutputJson(raw: String) {
        val output: CoreOutput = runCatching {
            json.decodeFromString(CoreOutput.serializer(), raw)
        }.getOrElse {
            appendLog("JSON parse error: ${it.message}")
            return
        }
        output.events.forEach(::applyEvent)
        output.commands.forEach(::executeCommand)
    }

    /**
     * Centralised command execution: every `CoreOutput` — from the DE1 manager
     * AND the scale manager — funnels its `commands` here. This single path
     * makes both the manual Tare button and the core's automatic shot-start
     * auto-tare work, because both surface as a [Command.WriteScale].
     *
     * A scale write is dispatched on [viewModelScope]: `writeCommand` is a
     * `suspend fun`, so it awaits the BLE write on a coroutine rather than
     * blocking the BLE binder thread this may be called from.
     */
    private fun executeCommand(command: Command) {
        when (command) {
            is Command.WriteScale -> {
                // The core hands over the exact bytes as a List<UByte>.
                val bytes = command.content.data
                    .map { it.toByte() }
                    .toByteArray()
                viewModelScope.launch { scale.writeCommand(bytes) }
            }
            is Command.WriteCharacteristic -> {
                // The core hands the exact bytes + which DE1 characteristic to
                // write them to. The BLE manager maps the target to a UUID and
                // writes; a profile-frame write also gets a synthesized
                // De1FrameAck. This is the machine-control path (AND5).
                val bytes = command.content.data
                    .map { it.toByte() }
                    .toByteArray()
                viewModelScope.launch { ble.writeCharacteristic(command.content.target, bytes) }
            }
        }
    }

    private fun applyEvent(event: Event) {
        when (event) {
            is Event.MachineStateChanged -> {
                val c = event.content
                _ui.value = _ui.value.copy(
                    machineState = "${c.state.string} / ${c.substate.string}",
                    machineStateName = c.state.string,
                    machineSubstate = c.substate.string,
                )
                appendLog("MachineState -> ${c.state.string} / ${c.substate.string}")
            }
            is Event.ShotStarted -> {
                // Entering a shot: flip the resting↔extracting flag and zero the
                // per-shot counters so the Brew timer / phase / volume restart.
                _ui.value = _ui.value.copy(
                    shotInProgress = true,
                    shotFrame = 0,
                    shotElapsedMs = 0,
                    dispensedVolume = 0f,
                    shotTelemetry = emptyList(),
                )
                appendLog("Shot started")
            }
            is Event.ShotPhaseChanged -> {
                val phase = event.content.phase.string
                _ui.value = _ui.value.copy(shotPhase = phase)
                appendLog("Shot phase -> $phase")
            }
            is Event.ShotFrameChanged -> {
                _ui.value = _ui.value.copy(shotFrame = event.content.frame.toInt())
                appendLog("Shot frame -> ${event.content.frame}")
            }
            is Event.Telemetry -> {
                val t = event.content
                val prev = _ui.value
                val line = "t=%dms  P=%.1fbar  flow=%.1fmL/s  head=%.1f°C".format(
                    t.elapsed.toLong(), t.group_pressure, t.group_flow, t.head_temp,
                )
                // Append a chart sample while a shot is running (the buffer fills
                // only during extraction, like the web). Fold in the latest scale
                // weight/flow so the WEIGHT curves track the cup. FIFO-capped.
                val nextBuffer = if (prev.shotInProgress) {
                    val sample = TelemetrySample(
                        elapsedMs = t.elapsed.toLong(),
                        pressure = t.group_pressure,
                        flow = t.group_flow,
                        headTemp = t.head_temp,
                        mixTemp = t.mix_temp,
                        weight = prev.scaleWeightG,
                        weightFlow = prev.scaleFlowGPerS,
                        dispensedVolume = t.dispensed_volume,
                        resistance = t.resistance,
                        resistanceWeight = t.resistance_weight,
                        setHeadTemp = t.set_head_temp,
                        setGroupPressure = t.set_group_pressure,
                        setGroupFlow = t.set_group_flow,
                    )
                    val appended = prev.shotTelemetry + sample
                    if (appended.size > SHOT_TELEMETRY_CAP) {
                        appended.takeLast(SHOT_TELEMETRY_CAP)
                    } else {
                        appended
                    }
                } else {
                    prev.shotTelemetry
                }
                // Keep the debug string AND project the structured channels the
                // Brew dashboard reads. High-rate; no log line.
                _ui.value = prev.copy(
                    telemetry = line,
                    pressure = t.group_pressure,
                    flow = t.group_flow,
                    headTemp = t.head_temp,
                    mixTemp = t.mix_temp,
                    steamTemp = t.steam_temp,
                    dispensedVolume = t.dispensed_volume,
                    resistance = t.resistance,
                    resistanceWeight = t.resistance_weight,
                    shotElapsedMs = t.elapsed.toLong(),
                    shotTelemetry = nextBuffer,
                )
            }
            is Event.ScaleReading -> {
                val r = event.content
                // The Bookoo echoes its live settings in every weight
                // notification. Project them into the config controls so they
                // show the scale's real current state — `device_*` is null for
                // scales that do not report a setting, in which case the
                // control keeps its last value.
                _ui.value = _ui.value.copy(
                    scaleWeightG = r.weight,
                    scaleFlowGPerS = r.device_flow,
                    scaleTimerMs = r.device_timer?.toLong(),
                    scaleVolume = r.device_volume?.toInt() ?: _ui.value.scaleVolume,
                    scaleStandbyMinutes = r.device_standby?.toInt()
                        ?: _ui.value.scaleStandbyMinutes,
                    scaleBatteryPercent = r.device_battery?.toInt()
                        ?: _ui.value.scaleBatteryPercent,
                    scaleFlowSmoothing = r.device_flow_smoothing
                        ?: _ui.value.scaleFlowSmoothing,
                    scaleAutoStop = r.device_auto_stop?.toInt()
                        ?: _ui.value.scaleAutoStop,
                )
                // Weight is high-rate; do not flood the log with every reading.
            }
            is Event.WaterLevel -> {
                _ui.value = _ui.value.copy(waterLevelMm = event.content.level)
                appendLog("Water level: %.0fmm".format(event.content.level))
            }
            is Event.StopTriggered ->
                appendLog("Auto-stop: ${event.content.reason.string}")
            is Event.ShotCompleted -> {
                val c = event.content
                // Leaving a shot: clear the extracting flag and capture the
                // summary for the Brew "Last shot" card.
                _ui.value = _ui.value.copy(
                    shotInProgress = false,
                    lastShot = LastShot(
                        durationMs = c.duration.toLong(),
                        yieldG = c.final_weight,
                        peakPressure = c.peak_pressure,
                        peakTemp = c.peak_temp,
                    ),
                )
                appendLog("Shot completed: ${c.duration}ms, ${c.sample_count} samples")
                captureCompletedShot(c.duration.toLong(), c.final_weight, c.peak_pressure, c.peak_temp)
            }
            is Event.WaterSessionStarted ->
                appendLog("Water session started: ${event.content.kind.string}")
            is Event.WaterSessionCompleted ->
                appendLog("Water session completed: ${event.content.kind.string}")
            is Event.SteamSessionStarted -> appendLog("Steam session started")
            is Event.SteamSessionCompleted ->
                appendLog("Steam session completed: ${event.content.duration}ms")
            is Event.SteamClogSuspected ->
                appendLog("Steam clog suspected: ${event.content.reason.string}")
            is Event.SteamEcoModeChanged ->
                appendLog("Steam eco mode: ${event.content.eco}")
            is Event.ScaleStale -> {
                _ui.value = _ui.value.copy(scaleWeightG = null)
                appendLog("Scale stale")
            }
            is Event.ScaleConfig -> {
                val c = event.content
                // The Bookoo's `ff12` command channel reports its dynamic
                // config: a `03 0c` serial response carries anti_mistouch (plus
                // serial / firmware), a `03 0e` settings response carries
                // active_mode. Any one notification fills only one set, so the
                // other fields are null — fold in whatever is present and keep
                // the last value for the rest, the same two-way pattern the
                // weight-stream settings use.
                _ui.value = _ui.value.copy(
                    scaleAntiMistouch = c.anti_mistouch ?: _ui.value.scaleAntiMistouch,
                    scaleActiveMode = c.active_mode?.toInt() ?: _ui.value.scaleActiveMode,
                    // The `03 0c` serial response carries firmware + serial;
                    // the `03 0e` settings response leaves both null — fold in
                    // whatever is present and keep the last value otherwise.
                    scaleFirmware = c.firmware ?: _ui.value.scaleFirmware,
                    scaleSerial = c.serial ?: _ui.value.scaleSerial,
                )
                when {
                    c.anti_mistouch != null ->
                        appendLog(
                            "Scale config: anti-mistouch=${c.anti_mistouch}" +
                                (c.serial?.let { ", serial=$it" } ?: "") +
                                (c.firmware?.let { ", fw=$it" } ?: ""),
                        )
                    c.active_mode != null ->
                        appendLog(
                            "Scale config: active mode=${c.active_mode}, " +
                                "enabled modes=${c.enabled_modes}",
                        )
                }
            }
            is Event.DecodeError ->
                appendLog("Decode error: ${event.content.message}")
            // The version / MMR / calibration read paths have no UI yet —
            // log them so the data is visible until a screen consumes it.
            is Event.Firmware ->
                appendLog("Firmware: ${event.content.firmware_string}")
            is Event.MmrValue ->
                appendLog(
                    "MMR ${event.content.register}: ${event.content.value}",
                )
            is Event.Calibration ->
                appendLog(
                    "Calibration ${event.content.target} " +
                        "(${event.content.command})",
                )
            // v1 stub never fires this in practice.
            is Event.FirmwareLockoutHit ->
                appendLog(
                    "Write refused (firmware update in progress): " +
                        event.content.method,
                )
            is Event.ProfileUploadStarted -> {
                _ui.value = _ui.value.copy(profileUploading = true, profileUploadProgress = null)
                appendLog("Uploading profile: ${event.content.title}")
            }
            is Event.ProfileUploadProgress -> {
                val c = event.content
                // High-rate (one per frame ack); update the progress hint, no log.
                _ui.value = _ui.value.copy(
                    profileUploadProgress = "${c.acks_received}/${c.total_acks}",
                )
            }
            is Event.ProfileUploadCompleted -> {
                _ui.value = _ui.value.copy(profileUploading = false, profileUploadProgress = null)
                appendLog("Profile uploaded: ${event.content.title}")
                // Gated start: the profile is now on the DE1. Observe the
                // firmware's profile-download guard window, then request Espresso.
                if (pendingBrew) {
                    pendingBrew = false
                    viewModelScope.launch {
                        delay(PROFILE_DOWNLOAD_GUARD_MS)
                        requestMachineState(MachineRequest.ESPRESSO)
                    }
                }
            }
            is Event.ProfileUploadFailed -> {
                pendingBrew = false
                _ui.value = _ui.value.copy(profileUploading = false, profileUploadProgress = null)
                appendLog("Profile upload failed: ${event.content.reason}")
            }
            // Shot-settings-read events surface in the real screens (M5), not this
            // Phase-0 debug readout. An explicit else keeps the sealed `when`
            // exhaustive as `Event` grows.
            else -> Unit
        }
    }

    private fun appendLog(line: String) {
        val stamped = "%s  %s".format(
            android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()),
            line,
        )
        val log = (listOf(stamped) + _ui.value.eventLog).take(MAX_LOG_LINES)
        _ui.value = _ui.value.copy(eventLog = log)
    }

    override fun onCleared() {
        super.onCleared()
        bleScanner.cancel(SCAN_LABEL_DE1)
        bleScanner.cancel(SCAN_LABEL_SCALE)
        ble.disconnect()
        scale.disconnect()
        // Close the Nordic environment last — it unregisters the Bluetooth
        // broadcast receiver and tears down the central manager. The managers'
        // disconnect() calls above are fire-and-forget coroutines; closing the
        // transport also cancels its scope, so any in-flight disconnect ends.
        bleTransport.close()
    }

    private companion object {
        const val MAX_LOG_LINES = 200

        /**
         * Wait after `ProfileUploadCompleted` before requesting Espresso — the
         * firmware's profile-download guard window. A state request inside it is
         * aborted to HeaterDown (BC 9788201734); the web uses the same 500 ms.
         */
        const val PROFILE_DOWNLOAD_GUARD_MS = 500L

        /** Abort a pending gated-start brew if the upload never completes. */
        const val PROFILE_UPLOAD_TIMEOUT_MS = 15_000L

        /** [BleScanner] want labels — one per device the app discovers. */
        const val SCAN_LABEL_DE1 = "DE1"
        const val SCAN_LABEL_SCALE = "Scale"
    }
}

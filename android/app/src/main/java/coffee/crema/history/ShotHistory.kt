package coffee.crema.history

import coffee.crema.brew.methodLabel
import coffee.crema.core.BrewHistoryStats
import coffee.crema.core.BrewSeries
import coffee.crema.core.BrewStatInput
import coffee.crema.core.HistoryStats
import coffee.crema.core.ShotBean
import coffee.crema.core.ShotStatInput
import coffee.crema.core.brewHistoryStats as coreBrewHistoryStats
import coffee.crema.core.downsampleIndices
import coffee.crema.core.historyStats as coreHistoryStats
import coffee.crema.ui.TelemetrySample
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/*
 * Shot history — the log of completed shots.
 *
 * v1 stores a per-shot SUMMARY only (no telemetry), so the persisted file stays
 * small. The detail view's static chart (reusing ShotChart) comes next and will
 * add a downsampled telemetry slice to [StoredShot] — additive, so older records
 * deserialise cleanly. Same file-JSON persistence pattern as the bean library.
 */

/** One completed shot — a row in the History list. */
@Serializable
data class StoredShot(
    override val id: String,
    /** Unix epoch ms when the shot completed (drives "N min ago"). */
    override val completedAtMs: Long,
    /** Total shot duration, ms. */
    override val durationMs: Long,
    /** Final scale weight (yield), g, or null when no scale was paired. */
    override val yieldG: Float? = null,
    /** Dose at capture (from the active profile), g — for the ratio. */
    override val doseG: Float? = null,
    /**
     * Grams this row actually took from its bag, g: less than the dose when
     * the bag ran out, 0 when it was empty, untracked or never debited. What
     * moving the shot off the bag credits back. Null on a row from before the
     * field and on a row with no bag. Mirrors core `StoredShot.bag_debit`.
     */
    override val bagDebitG: Float? = null,
    /**
     * Brew's grind at capture (issue 15), or null when none was set: the grind
     * actually used for this shot, distinct from the bean's catalog
     * [ShotBean.grinderSetting] (the bag's reference setting). Text, as the
     * core and web keep it (REVIEW-FINAL M1): "15", "2.25", "2.1.3", "18
     * clicks" as typed, never rounded. An older row's number reads as its
     * digits (15.0 as "15"). Mirrors core `ShotMetadata.grinder_setting`.
     */
    @Serializable(with = coffee.crema.beans.GrindTextSerializer::class)
    override val grindSetting: String? = null,
    /**
     * The stop-at-weight target the user dialled for THIS shot, g, or null.
     * The shot-quality yield arms key off it (falling back to the profile's
     * target) — web has persisted it all along; the gap made Android's
     * quality analysis use the wrong target (review #39). Additive.
     */
    override val yieldTargetG: Float? = null,
    /** Peak group pressure across the shot, bar, or null. */
    override val peakPressure: Float? = null,
    /** Peak group-head temperature across the shot, °C, or null. */
    override val peakTemp: Float? = null,
    /**
     * Peak scale weight across the shot, g, or null when no scale was
     * paired. The stats' yield fallback when [yieldG] (the settled final
     * weight) is missing — a cup lifted before settle still counts
     * (review #41; the web has always applied this fallback). Stamped
     * from the ShotCompleted event's `peak_weight`; null on older rows.
     */
    override val peakWeightG: Float? = null,
    /** Active profile name at capture, or null. */
    override val profileName: String? = null,
    /**
     * The library id of the profile pulled (custom or built-in), next to the
     * [profileName] snapshot: lookups use the id, display the name (save
     * boundaries F4). Mirrors core `StoredShot.profile_id` (`profileId`);
     * null on older rows and for a profile another app loaded.
     */
    override val profileId: String? = null,
    /**
     * The active profile's *recipe* (the core wire `Profile` shape) snapshotted at
     * capture, or null. Embedded into the Visualizer upload (#12) so a profile
     * downloaded back imports as a real profile, not an empty stub — `profileName`
     * alone can't rebuild the steps. Additive; older records deserialise as null.
     */
    val profile: JsonObject? = null,
    /**
     * Full bean snapshot at capture (the core wire shape) — roaster, roast date,
     * and roast level frozen at shot time. The single source for both the
     * Visualizer wire and the shell's "Roaster · Name" display (derived via
     * [beanLabel]); null for a beanless shot.
     */
    override val bean: ShotBean? = null,
    /** User star rating 0..5; null = unrated. Edited from the History detail. */
    override val rating: Int? = null,
    /** User tasting notes for this shot; null = none. Edited from the detail. */
    override val notes: String? = null,
    /**
     * Forward-looking dial-in plan — what to change on the *next* shot
     * ("go finer, +2 drops"). Kept apart from [notes] (how this shot tasted)
     * so the Brew screen can surface it when the bag is next selected.
     * Local workflow state: never uploaded to Visualizer. Additive — older
     * records deserialise cleanly. Mirrors core `ShotMetadata.next_plan`.
     */
    override val nextPlan: String? = null,
    /**
     * Who made the shot (dye2 audit #1) — the Settings default barista at
     * capture, editable from the History detail. Exported as de1app's
     * `my_name` (Visualizer's `barista`) and decaid's `baristaName`.
     * Mirrors core `ShotMetadata.barista`. Additive.
     */
    override val barista: String? = null,
    /**
     * Who drank it — de1app's `drinker_name`, decaid's `drinkerName`
     * (Visualizer has no field). Mirrors core `ShotMetadata.drinker`.
     */
    override val drinker: String? = null,
    /**
     * The grinder this shot was pulled on — frozen at capture by the core
     * (F10: the bean's grinder, else the Settings grinder), or carried by an
     * imported / restored shot. Null only for shots captured before F10,
     * which fall back to the Settings grinder at upload time.
     */
    override val grinderModel: String? = null,
    /**
     * The grinder record's id at shot start (#488, review F4), next to the
     * [grinderModel] name snapshot: lookups use the id, display the name.
     * Mirrors core `StoredShot.grinder_id`; null on older rows (matched by
     * name) and for a shot with no grinder.
     */
    override val grinderId: String? = null,
    /**
     * The shot's telemetry — the core's full record since F10 (no 200-point
     * cut, so a re-upload reproduces Visualizer's telemetry hash). Older
     * records carry a downsampled slice. Persisted in a per-shot series file,
     * not in the history file ([HistoryStore]).
     */
    val samples: List<TelemetrySample> = emptyList(),
    /** Shot-level tags — the bean's tags copied at capture (F10). */
    override val tags: List<String> = emptyList(),
    /** The pre-infusion the shot ran, seconds (F10: the as-run value). */
    override val preinfuseTargetS: Float? = null,
    /** Whether stop-on-weight was armed (F10, from the core). */
    override val stopOnWeight: Boolean? = null,
    /** Whether auto-tare was armed (F10, from the core). */
    override val autoTare: Boolean? = null,
    /** Why the core stopped the shot — the core `StopReason` name (`"Weight"`,
     *  `"Volume"`, `"MaxTime"`, …) — or null when it ended on its own. */
    override val stopReason: String? = null,
    /** The `Weight` stop was the core's estimate, the scale lost mid-shot
     *  (core `StoredShot.stop_estimated`; older builds read it as `Weight`). */
    override val stopEstimated: Boolean = false,
    /**
     * Per-shot Visualizer privacy override — `"public" | "unlisted" |
     * "private"`, or null to inherit the Sharing default at upload/patch
     * time (mirrors the web StoredShot field).
     */
    override val privacy: String? = null,
    /**
     * Visualizer `shot.id` once uploaded; null until pushed (mirrors the web
     * StoredShot field — drives the History sync pip + "Upload all unsynced").
     */
    override val visualizerId: String? = null,
    /**
     * Decent account shot id once uploaded to decentespresso.com's shot history
     * (geota/crema#84); null until pushed.
     */
    override val decentId: String? = null,
    /** The Visualizer row's `updated_at` (unix s) when a pull last merged it
     *  (core `StoredShot::visualizer_updated_at`): a pull skips a row at this
     *  version. Kept by backups, never uploaded. */
    override val visualizerUpdatedAt: Long? = null,
    /** The user pinned this shot (#311): the BLE session holding it keeps its
     *  capture past the limit, and it is exempt from any cleanup. Core
     *  `StoredShot::pinned`; kept by backups, moves between the apps. */
    override val pinned: Boolean = false,
    /** A replay of a capture, not a live pull (owner decision, #488 batch 6):
     *  kept in History with a replay mark, never uploaded or debited. Core
     *  `StoredShot::replay`; kept by backups, omitted when false. */
    override val replay: Boolean = false,
    /**
     * The DE1 this shot was pulled on, stamped at record time (#84). The Decent
     * upload needs the serial (the server checks it belongs to the account);
     * shots recorded before these fields fall back to the connected machine.
     */
    override val machineSerial: String? = null,
    override val machineFirmware: String? = null,
    override val machineModel: String? = null,
    /**
     * Brew Log method (issue #10) — a normalized string (`"pourover"`,
     * `"aeropress"`, `"espresso"` for a manually logged machine-free
     * shot, …). Null = machine espresso, so every pre-Brew-Log record
     * is already a valid brew row. Mirrors core `StoredShot.brew_method`.
     */
    override val brewMethod: String? = null,
    /**
     * The method's display label, snapshotted when the brew was saved with a
     * user-defined method (`custom:<uuid>`), so the row keeps its name after
     * the method is deleted or lost. Mirrors core `StoredShot.brew_method_label`.
     */
    override val brewMethodLabel: String? = null,
    /** The guided-session recipe name, snapshot at completion. */
    override val recipeName: String? = null,
    /**
     * Water in, g — the pour total for filter / immersion brews.
     * Distinct from [yieldG] (beverage in the cup): a 15 g / 250 g V60
     * speaks its ratio in water-in (1:16.7). Mirrors core
     * `ShotMetadata.water_g`.
     */
    override val waterG: Float? = null,
    /**
     * Brew water temperature, °C: the logged value for brews, and for machine
     * shots the brew temperature the shot ran at (the core-clamped Quick
     * Controls dial, else the profile's own). Mirrors core
     * `StoredShot.brew_temp_target`.
     */
    override val brewTempC: Float? = null,
    /**
     * Weight-only guided-session telemetry (core `BrewSeries`,
     * typeshared). Null for machine shots (their telemetry is
     * [samples]) and manual logs (none at all).
     */
    val brewSeries: BrewSeries? = null,
) : ShotMeta

/** Id prefix of a shot materialised from a Visualizer pull (see `storedShotFromWire`). */
const val PULLED_SHOT_ID_PREFIX = "shot:remote:"

/**
 * True for a shot pulled from Visualizer rather than recorded here: it may
 * have been pulled on another DE1, so the connected machine's serial is never
 * stamped onto it, and it stays out of the Decent backlog.
 */
val ShotMeta.pulledFromVisualizer: Boolean get() = id.startsWith(PULLED_SHOT_ID_PREFIX)

/**
 * A Brew Log entry of any kind — `brewMethod` set, manual or guided (issue
 * #10). Brew rows are local-only: no upload path (Visualizer or the Decent
 * account — auto, manual or backlog) ever takes one, they get no upload menu
 * or cloud pip, and they carry no DE1 machine stamp. Mirrors core
 * `StoredShot::is_brew_log` and the web `isBrewLog`.
 */
val ShotMeta.isBrewLog: Boolean
    get() = brewMethod != null

/** Why an upload path skipped a [isBrewLog] row — shared by every destination. */
const val BREW_LOG_UPLOAD_SKIP = "Brew Log entries stay on this device"

/** The row's method for display: the stored method, null = machine espresso. */
val ShotMeta.methodOf: String?
    get() = brewMethod?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/** The title of a machine espresso with no profile name (web: "Untitled shot"). */
const val UNTITLED_SHOT = "Untitled shot"

/**
 * What a stored row is called wherever it is listed — the History rows, the
 * bean detail's "Shots" list. One rule for every brew shape (#165): the
 * profile name (a machine espresso), else the guided recipe's name, else the
 * method's label (a manual or custom-method brew). A machine espresso with
 * no profile name is [UNTITLED_SHOT]. Blank names count as absent. Mirrors the
 * web's `shotTitle`, wording included.
 */
fun shotTitle(shot: ShotMeta): String =
    shot.profileName?.trim()?.takeIf { it.isNotEmpty() }
        ?: shot.recipeName?.trim()?.takeIf { it.isNotEmpty() }
        ?: shot.methodOf?.let(::methodLabel)
        ?: UNTITLED_SHOT

/**
 * Manually logged: a method was declared but no telemetry of any kind was
 * captured. Manual rows keep dose / water / time / temp editable (they are
 * user-entered facts) and never enter a Visualizer upload queue. Mirrors
 * core `StoredShot::is_manual_log`.
 */
val StoredShot.isManualLog: Boolean
    get() = brewMethod != null && samples.isEmpty() && brewSeries == null

/**
 * The grind THIS shot was pulled at, as a raw setting string (issue #16): the
 * recorded grind as stored, else the bean snapshot's reference setting; null
 * when neither. ONE precedence shared by the history rows/detail
 * ([grindLabel]) and the Visualizer wire (upload + PATCH) — what the app
 * displays is exactly what syncs.
 */
val ShotMeta.effectiveGrindSetting: String?
    get() = grindSetting?.trim()?.takeIf { it.isNotEmpty() } ?: bean?.grinderSetting?.takeIf { it.isNotBlank() }

/** "Grind N" display form of [effectiveGrindSetting]. */
val ShotMeta.grindLabel: String?
    get() = effectiveGrindSetting?.let { "Grind $it" }

/**
 * The method-aware `1:N` ratio label (issue #10): espresso-family rows
 * divide yield by dose; filter methods divide water-in by dose (yield
 * fallback). One core rule (`de1_domain::ratio_for_method`) for every
 * shell. Null when undefined.
 */
fun ShotMeta.methodRatioLabel(): String? {
    val r = coffee.crema.core.ratioForMethod(methodOf, doseG, waterG, yieldG) ?: return null
    return "1:" + String.format(java.util.Locale.US, "%.1f", r)
}

/** "0:27" under 90 s reads as "27 s"; beyond, mm:ss — one duration format
 *  for shots and pourovers alike. */
fun formatShotDuration(ms: Long): String {
    val total = (ms / 1000).toInt()
    return if (total < 90) "$total s" else "%d:%02d".format(total / 60, total % 60)
}

/**
 * The flat "Roaster · Name" label for a shot's bean, derived from the structured
 * [StoredShot.bean] snapshot (the canonical model) rather than a stored string —
 * the History UX line and the wire's `metadata.beans`. Mirrors the web
 * `beanLabel`: roaster + name when both, else whichever is present, else null.
 */
val ShotMeta.beanLabel: String?
    get() {
        val b = bean ?: return null
        val roaster = b.roasterName?.trim().orEmpty()
        val type = b.name.trim()
        return when {
            roaster.isNotEmpty() && type.isNotEmpty() -> "$roaster · $type"
            roaster.isNotEmpty() -> roaster
            type.isNotEmpty() -> type
            else -> null
        }
    }

/**
 * Project a [StoredShot] onto the core's `de1_domain::StoredShot` JSON shape
 * (camelCase) — the input `analyzeStoredShotQuality` parses. Only what the
 * quality analysis reads is carried: the profile snapshot, journal metadata,
 * the dialled yield target, and the telemetry record. The core `ShotSample`
 * fields are non-optional, so the channels Android never persisted
 * (sampleTime / setMixTemp / steamTemp) are zeroed; `frameNumber` is zeroed on
 * records from before the field existed, which the core builder degrades to a
 * single-phase Start/End reconstruction. A null scale weight OMITS the key
 * (the core side is absence-tolerant via `serde(default)`).
 */
fun StoredShot.coreShotJson(): JsonObject = buildJsonObject {
    put("formatVersion", 3)
    put("id", id)
    put("completedAt", completedAtMs)
    profile?.let { put("profile", it) }
    putJsonObject("metadata") {
        put("dose", doseG)
        put("yieldOut", yieldG)
        put("rating", rating)
        put("notes", notes)
        waterG?.let { put("waterG", it) }
        barista?.let { put("barista", it) }
        drinker?.let { put("drinker", it) }
    }
    yieldTargetG?.let { put("yieldTarget", it) }
    // Brew Log fields (issue #10) — ride the core shape so the v2
    // exporter emits the crema_brew_method extension keys.
    brewMethod?.let { put("brewMethod", it) }
    brewMethodLabel?.let { put("brewMethodLabel", it) }
    recipeName?.let { put("recipeName", it) }
    brewTempC?.let { put("brewTempTarget", it) }
    putJsonObject("record") {
        put("duration", durationMs)
        putJsonArray("samples") {
            samples.forEach { s ->
                addJsonObject {
                    put("elapsed", s.elapsedMs)
                    putJsonObject("sample") {
                        put("sampleTime", 0)
                        put("groupPressure", s.pressure)
                        put("groupFlow", s.flow)
                        put("headTemp", s.headTemp)
                        put("mixTemp", s.mixTemp)
                        put("setMixTemp", 0)
                        put("setHeadTemp", s.setHeadTemp)
                        put("setGroupPressure", s.setGroupPressure)
                        put("setGroupFlow", s.setGroupFlow)
                        put("frameNumber", s.frameNumber ?: 0)
                        put("steamTemp", 0)
                    }
                    s.weight?.let { put("scaleWeight", it) }
                }
            }
        }
    }
}

/** Wire codec for the stats FFI round-trip. */
private val statsJson = Json { ignoreUnknownKeys = true }

/**
 * Summary metrics for a (filter/range-scoped) set of shots — issue 48,
 * shared by the tablet's six-tile strip and the phone's three-average
 * strip. The aggregation rules live in the core
 * (`de1_domain::history_stats`, review #41: this Kotlin port ignored the
 * peak-weight yield fallback the web applied, so a lifted-cup shot
 * dropped out of Android's averages) — a light 5-field projection per
 * row crosses the FFI. On a bridge failure (never in practice) the strip
 * degrades to count-only ("—" everywhere else).
 */
fun historyStats(shots: List<ShotMeta>): HistoryStats {
    val rows = shots.map {
        ShotStatInput(
            durationMs = it.durationMs,
            finalWeightG = it.yieldG,
            peakWeightG = it.peakWeightG,
            doseG = it.doseG,
            rating = it.rating?.toUByte(),
        )
    }
    return runCatching {
        statsJson.decodeFromString(
            HistoryStats.serializer(),
            coreHistoryStats(statsJson.encodeToString(ListSerializer(ShotStatInput.serializer()), rows)),
        )
    }.getOrElse { HistoryStats(count = shots.size.toUInt()) }
}

/**
 * The method-aware stat strip (issue #10) — [historyStats]'s Brew Log
 * sibling. Adds water-in + method per row so the core can apply the
 * mixed-set scoping rules (`de1_domain::brew_history_stats`): count /
 * beans-used / rating span every row; the weight / ratio / time averages
 * scope to espresso rows in a mixed set (the strip tags those tiles
 * "esp") or to the single method the whole set belongs to.
 */
fun brewHistoryStats(shots: List<ShotMeta>): BrewHistoryStats {
    val rows = shots.map {
        BrewStatInput(
            durationMs = it.durationMs,
            finalWeightG = it.yieldG,
            peakWeightG = it.peakWeightG,
            doseG = it.doseG,
            waterG = it.waterG,
            rating = it.rating?.toUByte(),
            brewMethod = it.brewMethod,
        )
    }
    return runCatching {
        statsJson.decodeFromString(
            BrewHistoryStats.serializer(),
            coreBrewHistoryStats(statsJson.encodeToString(ListSerializer(BrewStatInput.serializer()), rows)),
        )
    }.getOrElse {
        BrewHistoryStats(count = shots.size.toUInt(), mixedMethods = false)
    }
}

/** Max points a guided session's weight series keeps (the core's
 *  `STORAGE_SAMPLE_CAP`). Espresso telemetry is stored whole since F10. */
const val SHOT_SAMPLE_CAP: Int = 200

/**
 * Thin a guided session's weight series to ~[SHOT_SAMPLE_CAP] samples before
 * it rides the history file — the web's cap, with the same core picker
 * (`downsample_indices`: every Nth, first and last kept, so the chart's time
 * axis and the planned staircase's end still line up). Stage marks are tiny
 * and kept whole. [indices] is the FFI call, injectable for JVM unit tests.
 */
fun downsampleBrewSeries(
    series: BrewSeries,
    indices: (UInt, UInt) -> List<UInt> = ::downsampleIndices,
): BrewSeries {
    val n = series.samples.size
    if (n <= SHOT_SAMPLE_CAP) return series
    return series.copy(samples = indices(n.toUInt(), SHOT_SAMPLE_CAP.toUInt()).map { series.samples[it.toInt()] })
}


/**
 * The stat strip from the core's `BrewHistoryStats` JSON (#310 A2: the
 * index's `stats_json`, SPEC §7). Null when it doesn't read.
 */
fun decodeBrewHistoryStats(text: String): BrewHistoryStats? =
    runCatching { statsJson.decodeFromString(BrewHistoryStats.serializer(), text) }.getOrNull()

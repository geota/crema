package coffee.crema.history

import android.content.Context
import coffee.crema.core.HistoryStats
import coffee.crema.core.ShotBean
import coffee.crema.core.ShotStatInput
import coffee.crema.core.downsampleIndices
import coffee.crema.core.historyStats as coreHistoryStats
import coffee.crema.ui.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

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
    val id: String,
    /** Unix epoch ms when the shot completed (drives "N min ago"). */
    val completedAtMs: Long,
    /** Total shot duration, ms. */
    val durationMs: Long,
    /** Final scale weight (yield), g, or null when no scale was paired. */
    val yieldG: Float? = null,
    /** Dose at capture (from the active profile), g — for the ratio. */
    val doseG: Float? = null,
    /**
     * Grinder click dialed in Quick Controls at capture (issue 15), or null when
     * the user never set one. The grind actually used for this shot — distinct
     * from the bean's catalog [ShotBean.grinderSetting] (the bag's reference
     * setting). Additive — older records deserialise cleanly.
     */
    val grindSetting: Float? = null,
    /**
     * The stop-at-weight target the user dialled for THIS shot, g, or null.
     * The shot-quality yield arms key off it (falling back to the profile's
     * target) — web has persisted it all along; the gap made Android's
     * quality analysis use the wrong target (review #39). Additive.
     */
    val yieldTargetG: Float? = null,
    /** Peak group pressure across the shot, bar, or null. */
    val peakPressure: Float? = null,
    /** Peak group-head temperature across the shot, °C, or null. */
    val peakTemp: Float? = null,
    /**
     * Peak scale weight across the shot, g, or null when no scale was
     * paired. The stats' yield fallback when [yieldG] (the settled final
     * weight) is missing — a cup lifted before settle still counts
     * (review #41; the web has always applied this fallback). Stamped
     * from the ShotCompleted event's `peak_weight`; null on older rows.
     */
    val peakWeightG: Float? = null,
    /** Active profile name at capture, or null. */
    val profileName: String? = null,
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
    val bean: ShotBean? = null,
    /** User star rating 0..5; null = unrated. Edited from the History detail. */
    val rating: Int? = null,
    /** User tasting notes for this shot; null = none. Edited from the detail. */
    val notes: String? = null,
    /**
     * A downsampled telemetry slice for the History detail chart (≤
     * [SHOT_SAMPLE_CAP] points). Empty for shots captured before this field
     * existed (additive — older records deserialise cleanly).
     */
    val samples: List<TelemetrySample> = emptyList(),
    /**
     * Per-shot Visualizer privacy override — `"public" | "unlisted" |
     * "private"`, or null to inherit the Sharing default at upload/patch
     * time (mirrors the web StoredShot field).
     */
    val privacy: String? = null,
    /**
     * Visualizer `shot.id` once uploaded; null until pushed (mirrors the web
     * StoredShot field — drives the History sync pip + "Upload all unsynced").
     */
    val visualizerId: String? = null,
)

/**
 * The grind THIS shot was pulled at, as a raw setting string (issue #16): the
 * recorded QC-dial value (trailing .0 trimmed), else the bean snapshot's
 * reference setting; null when neither. ONE precedence shared by the history
 * rows/detail ([grindLabel]) and the Visualizer wire (upload + PATCH) — what
 * the app displays is exactly what syncs.
 */
val StoredShot.effectiveGrindSetting: String?
    get() = grindSetting?.let { g ->
        if (g % 1f == 0f) String.format(java.util.Locale.US, "%.0f", g)
        else String.format(java.util.Locale.US, "%.1f", g)
    } ?: bean?.grinderSetting?.takeIf { it.isNotBlank() }

/** "Grind N" display form of [effectiveGrindSetting]. */
val StoredShot.grindLabel: String?
    get() = effectiveGrindSetting?.let { "Grind $it" }

/**
 * The flat "Roaster · Name" label for a shot's bean, derived from the structured
 * [StoredShot.bean] snapshot (the canonical model) rather than a stored string —
 * the History UX line and the wire's `metadata.beans`. Mirrors the web
 * `beanLabel`: roaster + name when both, else whichever is present, else null.
 */
val StoredShot.beanLabel: String?
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
    }
    yieldTargetG?.let { put("yieldTarget", it) }
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
fun historyStats(shots: List<StoredShot>): HistoryStats {
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

/** Max telemetry points stored per shot — enough for a faithful detail
 *  chart (mirrors the core's `STORAGE_SAMPLE_CAP`). */
const val SHOT_SAMPLE_CAP: Int = 200

/**
 * Downsample a shot's telemetry to ≤ [SHOT_SAMPLE_CAP] points so the
 * persisted history file stays small while the detail chart still reads
 * faithfully. The selection policy (every Nth, always including the last)
 * lives in the core (`de1_domain::downsample_indices`, review #42) — the
 * indices come back and the shell applies them to its own sample type.
 */
fun downsampleForStorage(samples: List<TelemetrySample>): List<TelemetrySample> {
    if (samples.size <= SHOT_SAMPLE_CAP) return samples
    return downsampleIndices(samples.size.toUInt(), SHOT_SAMPLE_CAP.toUInt())
        .map { samples[it.toInt()] }
}

/** File-backed JSON persistence for the shot log (`filesDir/shots.json`). */
class HistoryStore(private val context: Context, private val json: Json) {
    private val file get() = File(context.filesDir, FILE_NAME)

    suspend fun load(): List<StoredShot> = withContext(Dispatchers.IO) {
        runCatching {
            file.takeIf { it.exists() }?.readText()
                ?.let { json.decodeFromString(ListSerializer(StoredShot.serializer()), it) }
        }.getOrNull() ?: emptyList()
    }

    suspend fun save(shots: List<StoredShot>) {
        withContext(Dispatchers.IO) {
            runCatching {
                file.writeText(json.encodeToString(ListSerializer(StoredShot.serializer()), shots))
            }
        }
    }

    companion object {
        /** Keep the most recent N shots (matches the web cap). */
        const val MAX_SHOTS = 300
        private const val FILE_NAME = "shots.json"
    }
}

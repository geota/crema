package coffee.crema.history

import android.content.Context
import coffee.crema.core.ShotBean
import coffee.crema.ui.TelemetrySample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * Summary metrics for a (filter/range-scoped) set of shots — issue 48. Computed
 * once here so the tablet's six-tile strip and the phone's three-average strip
 * stay consistent. `null` averages/weights mean "no data" (render as "—").
 */
data class HistoryStats(
    val shots: Int,
    val totalWeightG: Double?,
    val avgWeightG: Double?,
    val avgRatio: Double?,
    val avgTimeS: Double?,
    val avgRating: Double?,
)

/** Derive [HistoryStats] over [shots]. Yields drive total/avg weight; ratio is the
 *  mean of per-shot yield÷dose (where both are present); rating averages 1..5. */
fun historyStats(shots: List<StoredShot>): HistoryStats {
    val yields = shots.mapNotNull { it.yieldG }
    val ratios = shots.mapNotNull { s ->
        val y = s.yieldG
        val d = s.doseG
        if (y != null && d != null && d > 0f) y / d else null
    }
    val ratings = shots.mapNotNull { it.rating?.takeIf { r -> r > 0 } }
    return HistoryStats(
        shots = shots.size,
        totalWeightG = if (yields.isNotEmpty()) yields.sum().toDouble() else null,
        avgWeightG = if (yields.isNotEmpty()) yields.average() else null,
        avgRatio = if (ratios.isNotEmpty()) ratios.average() else null,
        avgTimeS = if (shots.isNotEmpty()) shots.map { it.durationMs / 1000.0 }.average() else null,
        avgRating = if (ratings.isNotEmpty()) ratings.average() else null,
    )
}

/** Max telemetry points stored per shot — enough for a faithful detail chart. */
const val SHOT_SAMPLE_CAP: Int = 200

/**
 * Downsample a shot's telemetry to ≤ [SHOT_SAMPLE_CAP] points (keep every Nth,
 * always including the last) so the persisted history stays small while the
 * detail chart still reads faithfully.
 */
fun downsampleForStorage(samples: List<TelemetrySample>): List<TelemetrySample> {
    if (samples.size <= SHOT_SAMPLE_CAP) return samples
    val step = samples.size / SHOT_SAMPLE_CAP
    val kept = samples.filterIndexed { i, _ -> i % step == 0 }
    return if (kept.last() === samples.last()) kept else kept + samples.last()
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

package coffee.crema.history

import coffee.crema.core.FrameExitSpec
import coffee.crema.core.PhaseMarker
import coffee.crema.core.SeriesPoint
import coffee.crema.core.ShotQualityInput
import coffee.crema.ui.TelemetrySample
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs

/*
 * StoredShot → ShotQualityInput — the Android feed into the core's
 * shot-quality analysis (the Decenza `ShotAnalysis::analyzeShot` port,
 * `core/de1-domain/src/shot_quality.rs`).
 *
 * The web shell records per-sample frame numbers; Android's TelemetrySample
 * carries none, so the phase markers the analysis needs are RECONSTRUCTED from
 * the recorded setpoint series (setGroupPressure / setGroupFlow): a profile
 * frame change moves the commanded target, so a debounced setpoint step is the
 * best observable proxy for a frame boundary. Everything else is a direct
 * projection of the stored series.
 */

/** Below this many samples the analysis has nothing to say (mirrors the core's
 *  insufficient-data early return) — the mapper bails to null and the detail
 *  view renders no quality block at all. */
private const val MIN_QUALITY_SAMPLES = 10

/** A setpoint step larger than this (bar or ml/s) in either component reads as
 *  a frame transition. Big enough to ignore encoder jitter, small enough to
 *  catch a 0.5-bar bloom step. */
private const val SETPOINT_STEP = 0.25f

/** Median-setpoint threshold for calling a frame flow-controlled: the DE1
 *  zeroes the inactive channel's goal, so "flow goal up, pressure goal ~0"
 *  identifies a flow frame. */
private const val MODE_FLOOR = 0.2

/**
 * Map a [StoredShot] onto the core's [ShotQualityInput], or null when the shot
 * carries too little telemetry to analyze ([MIN_QUALITY_SAMPLES]).
 *
 * Phase markers are derived from setpoint steps (see [frameStartIndices]) —
 * two known compromises versus the web shell's frame-numbered samples:
 * a smooth (interpolated) transition whose per-sample setpoint delta stays
 * under [SETPOINT_STEP] never fires, so slow ramps merge into the surrounding
 * frame; and back-to-back frames with identical targets are indistinguishable.
 * Both degrade gracefully — fewer markers only widens the analysis windows.
 *
 * Frame labels: with a profile snapshot, frames below `preinfuse_step_count`
 * are "Preinfusion" and the first frame at/after it is "Pour" (the core keys
 * its pour window off those substrings). Without a profile the FIRST detected
 * transition is labelled "Pour" — a best-effort anchor: most espresso profiles
 * open with exactly one preinfusion phase, so the first observable setpoint
 * step is most plausibly the preinfusion→pour boundary. Later frames are
 * "Frame N" (cosmetic; the core matches only infus/pour/end/start).
 */
fun qualityInputFromShot(shot: StoredShot): ShotQualityInput? {
    val samples = shot.samples
    if (samples.size < MIN_QUALITY_SAMPLES) return null

    val durationS = shot.durationMs / 1000.0
    fun t(s: TelemetrySample): Double = s.elapsedMs / 1000.0

    val hints = profileHints(shot.profile)
    val frameStarts = frameStartIndices(samples)
    // Per-frame control mode over the frame's own samples (Decenza reads the
    // frame flags; we infer from the medians so a transient zero doesn't flip it).
    val frameModes = List(frameStarts.size) { f ->
        val from = frameStarts[f]
        val until = frameStarts.getOrElse(f + 1) { samples.size }
        val window = samples.subList(from, until)
        median(window.map { it.setGroupFlow }) > MODE_FLOOR &&
            median(window.map { it.setGroupPressure }) < MODE_FLOOR
    }

    val markers = mutableListOf<PhaseMarker>()
    // Synthetic first marker — Decenza inserts "Start"/frame 0 at extraction
    // start (shotanalysis.cpp:667-672); the core expects it.
    markers += PhaseMarker(
        timeS = 0.0,
        label = "Start",
        frameNumber = 0,
        isFlowMode = frameModes.first(),
        transitionReason = "",
    )
    // A zero preinfuse count means the pour begins at extraction start: the
    // Start marker already anchors the window at t=0, so no transition gets
    // the "Pour" label (it would wrongly push pour_start later).
    var pourEmitted = hints.preinfuseStepCount?.let { it <= 0 } ?: false
    for (f in 1 until frameStarts.size) {
        val pre = hints.preinfuseStepCount
        val label = when {
            pre != null && f < pre -> "Preinfusion"
            !pourEmitted -> { pourEmitted = true; "Pour" }
            else -> "Frame ${f + 1}"
        }
        markers += PhaseMarker(
            timeS = t(samples[frameStarts[f]]),
            label = label,
            frameNumber = f,
            // Setpoint-derived markers carry no exit reason — "" is the core's
            // "unknown (old data)" value and falls through to duration checks.
            transitionReason = "",
            isFlowMode = frameModes[f],
        )
    }
    // End marker: frameNumber -1 (negative = unknown) so skip-first-frame
    // iteration never mistakes the shot end for a profile frame boundary.
    markers += PhaseMarker(
        timeS = durationS,
        label = "End",
        frameNumber = -1,
        isFlowMode = frameModes.last(),
        transitionReason = "",
    )

    return ShotQualityInput(
        pressure = samples.map { SeriesPoint(t(it), it.pressure.toDouble()) },
        flow = samples.map { SeriesPoint(t(it), it.flow.toDouble()) },
        weight = samples.mapNotNull { s -> s.weight?.let { SeriesPoint(t(s), it.toDouble()) } },
        pressureGoal = samples.map { SeriesPoint(t(it), it.setGroupPressure.toDouble()) },
        flowGoal = samples.map { SeriesPoint(t(it), it.setGroupFlow.toDouble()) },
        phases = markers,
        // From the profile snapshot (review #39): the espresso hardcode ran
        // puck-integrity detectors on filter/pourover/cleaning shots and
        // badged them falsely — the core skips those by type.
        beverageType = hints.beverageType,
        durationS = durationS,
        firstFrameConfiguredS = hints.firstFrameConfiguredS,
        frameExits = hints.frameExits,
        // The user's dialled stop target wins over the profile's (web
        // parity — review #39).
        targetWeightG = shot.yieldTargetG?.toDouble() ?: hints.targetWeightG,
        finalWeightG = shot.yieldG?.toDouble() ?: 0.0,
        expectedFrameCount = hints.expectedFrameCount,
        analysisFlags = emptyList(),
        profileKbResolved = hints.resolved,
    )
}

/**
 * Frame boundaries from setpoint steps: a new frame starts at sample `i` when
 * the (setGroupPressure, setGroupFlow) pair steps by more than [SETPOINT_STEP]
 * in either component from sample `i-1` AND the new pair holds for ≥ 2
 * consecutive samples (`i` and `i+1` within [SETPOINT_STEP] of each other) —
 * the debounce that keeps a one-sample glitch or a mid-ramp reading from
 * spawning a phantom frame. Frame 0 always starts at index 0.
 */
private fun frameStartIndices(samples: List<TelemetrySample>): List<Int> {
    val starts = mutableListOf(0)
    // The last sample has no successor to debounce against — a step there is
    // indistinguishable from noise, so it never opens a frame.
    for (i in 1 until samples.size - 1) {
        val prev = samples[i - 1]
        val cur = samples[i]
        val next = samples[i + 1]
        val stepped = abs(cur.setGroupPressure - prev.setGroupPressure) > SETPOINT_STEP ||
            abs(cur.setGroupFlow - prev.setGroupFlow) > SETPOINT_STEP
        val holds = abs(next.setGroupPressure - cur.setGroupPressure) <= SETPOINT_STEP &&
            abs(next.setGroupFlow - cur.setGroupFlow) <= SETPOINT_STEP
        // After a hit, the next iteration compares i+1 against i — inside the
        // hold band by construction — so the same edge can't double-fire.
        if (stepped && holds) starts += i
    }
    return starts
}

private fun median(values: List<Float>): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid].toDouble()
    else (sorted[mid - 1].toDouble() + sorted[mid].toDouble()) / 2.0
}

/** The profile-derived analysis hints, with the core's "unknown" sentinels as
 *  fallbacks (-1 durations/counts, 0 target weight). */
private data class ProfileHints(
    val preinfuseStepCount: Int?,
    val firstFrameConfiguredS: Double,
    val expectedFrameCount: Int,
    val targetWeightG: Double,
    val resolved: Boolean,

    val frameExits: List<FrameExitSpec> = emptyList(),

    val beverageType: String = "espresso",
)

private val UNKNOWN_PROFILE = ProfileHints(
    preinfuseStepCount = null,
    firstFrameConfiguredS = -1.0,
    expectedFrameCount = -1,
    targetWeightG = 0.0,
    resolved = false,
)

/**
 * Pull the analysis hints out of the stored wire-Profile snapshot
 * ([StoredShot.profile], snake_case core `Profile` JSON). Fully defensive:
 * a malformed / truncated snapshot — old exports, hand-edited imports —
 * degrades to [UNKNOWN_PROFILE] instead of throwing.
 */
private fun profileHints(profile: JsonObject?): ProfileHints {
    if (profile == null) return UNKNOWN_PROFILE
    return runCatching {
        val steps = profile["steps"]?.jsonArray
        ProfileHints(
            preinfuseStepCount = profile["preinfuse_step_count"]?.jsonPrimitive?.intOrNull,
            firstFrameConfiguredS = steps?.firstOrNull()?.jsonObject
                ?.get("duration_seconds")?.jsonPrimitive?.doubleOrNull ?: -1.0,
            expectedFrameCount = steps?.size ?: -1,
            targetWeightG = profile["target_weight"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            beverageType = profile["beverage_type"]?.jsonPrimitive?.contentOrNull ?: "espresso",
            // "KB resolved" (Decenza: the profile's shape is known well enough
            // to trust the flow goal): here, a real snapshot with actual steps.
            resolved = !steps.isNullOrEmpty(),
            // Per-frame exit specs so the core can infer the transitionReason
            // these reconstructed markers can't carry (Decenza #1421).
            frameExits = steps?.map { el ->
                val step = el.jsonObject
                val exit = step["exit"]?.let { if (it is JsonObject) it else null }
                FrameExitSpec(
                    metric = exit?.get("metric")?.jsonPrimitive?.contentOrNull ?: "",
                    exitOver = exit?.get("compare")?.jsonPrimitive?.contentOrNull == "over",
                    threshold = exit?.get("threshold")?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    maxDurationS = step["duration_seconds"]?.jsonPrimitive?.doubleOrNull ?: -1.0,
                )
            } ?: emptyList(),
        )
    }.getOrDefault(UNKNOWN_PROFILE)
}

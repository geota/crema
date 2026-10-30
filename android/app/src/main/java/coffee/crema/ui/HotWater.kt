package coffee.crema.ui

import coffee.crema.core.HotWaterBasis
import coffee.crema.core.HotWaterBounds
import coffee.crema.core.HotWaterClamp
import coffee.crema.core.HotWaterDose
import coffee.crema.core.HotWaterMode
import coffee.crema.core.HotWaterTarget
import coffee.crema.core.hotWaterBoundsJson
import coffee.crema.core.ratioLabel
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/*
 * The hot-water dose on Android (hot-water modes spec §2.6, §2.7). The core
 * owns every number: the steppers' ranges and chips (`hotWaterBoundsJson`),
 * the migration, and the resolved target (`HotWaterTarget`: what Crema pours,
 * what the packet holds, where the weight stop sits). This file only reads
 * those and turns a target into the readout line, the resting chip amount and
 * the running counter, in the user's units. Nothing here computes an amount.
 */

/** The core's hot-water bounds, parsed once (they are constants per build),
 *  as `ProfileBounds` is. */
object HotWaterUi {
    private val json = Json { ignoreUnknownKeys = true }

    /** Ranges, chips and the default dose. */
    val bounds: HotWaterBounds by lazy { json.decodeFromString(HotWaterBounds.serializer(), hotWaterBoundsJson()) }

    /** The default dose: ratio 1:3. */
    val defaults: HotWaterDose get() = bounds.defaults
}

/** The dose these settings hold. */
fun MainUiState.hotWaterDose(): HotWaterDose = HotWaterDose(
    mode = qcHotWaterMode,
    timeS = qcHotWaterTimeS,
    timeNoLimit = qcHotWaterTimeNoLimit,
    volumeMl = qcHotWaterVolumeMl,
    ratio = qcHotWaterRatio,
)

/** These settings holding [dose]. */
fun MainUiState.withHotWaterDose(dose: HotWaterDose): MainUiState = copy(
    qcHotWaterMode = dose.mode,
    qcHotWaterTimeS = dose.timeS,
    qcHotWaterTimeNoLimit = dose.timeNoLimit,
    qcHotWaterVolumeMl = dose.volumeMl,
    qcHotWaterRatio = dose.ratio,
)

/** Above this many seconds the time readout warns of a large pour. */
private const val LARGE_POUR_S = 120f

private fun seconds(s: Float): String = "${s.roundToInt()} s"

/** A shot weight in the user's unit, a trailing ".0" dropped: `50 g`. */
private fun shotWeight(grams: Float?, weightUnit: String): String {
    val m = convertWeight(grams, weightUnit)
    return "${m.value.removeSuffix(".0")} ${m.unit}"
}

private fun volume(ml: Float?, volumeUnit: String): String = convertVolume(ml, volumeUnit).let { "${it.value} ${it.unit}" }

/**
 * The readout line under the hot-water stepper (spec §2.7), or "" when there
 * is nothing to say (volume mode without an operational scale): ratio "1:3 →
 * 150 ml for a 50 g shot", no target "No yield target · 150 ml", the capped and
 * floored copy (§2.4); volume "Stops by weight"; time "Stops at 30 s", a
 * large-pour warning above 120 s, "or about 255 ml" when the firmware needs a
 * time-mode volume backstop, and No limit.
 */
fun hotWaterReadout(target: HotWaterTarget, weightUnit: String, volumeUnit: String, ratio: Float): String {
    if (target.mode == HotWaterMode.Time) {
        val t = target.timeS ?: return "Pours until you stop it, also from the group-head button"
        return when {
            target.wireVolumeMl > 0f -> "Stops at ${seconds(t)}, or about ${volume(target.wireVolumeMl, volumeUnit)}"
            t > LARGE_POUR_S -> "Stops at ${seconds(t)} — a large pour, also from the group-head button"
            else -> "Stops at ${seconds(t)}"
        }
    }
    val ml = volume(target.volumeMl, volumeUnit)
    if (target.mode == HotWaterMode.Volume) return if (target.stopWeightG != null) "Stops by weight" else ""
    if (target.basis == HotWaterBasis.NoYieldTarget) return "No yield target · $ml"
    val r = ratioLabel(ratio)
    return when (target.clamp) {
        HotWaterClamp.Capped ->
            "$r → $ml (capped; $r of ${shotWeight(target.basisG, weightUnit)} is ${volume(target.requestedMl, volumeUnit)})"
        HotWaterClamp.RaisedToMinimum -> "$r → $ml (raised to the $ml minimum)"
        null -> "$r → $ml for a ${shotWeight(target.basisG, weightUnit)} shot"
    }
}

/** The amount a resting chip shows: "150 ml", "30 s", or "no limit". */
fun hotWaterRestingAmount(target: HotWaterTarget, volumeUnit: String): String =
    if (target.mode == HotWaterMode.Time) target.timeS?.let(::seconds) ?: "no limit" else volume(target.volumeMl, volumeUnit)

/** The phone chip's compact amount: "150ml", "30s", or "no limit". */
fun hotWaterCompactAmount(target: HotWaterTarget): String = when {
    target.mode != HotWaterMode.Time -> "${(target.volumeMl ?: 0f).roundToInt()}ml"
    target.timeS == null -> "no limit"
    else -> "${target.timeS.roundToInt()}s"
}

/**
 * The running counter while hot water pours: "12.3 / 30.0 s" in time mode
 * ("12.3 s" for No limit), "12.3 s · 150 ml" in volume and ratio modes — the
 * 60 s backstop isn't the dose.
 */
fun hotWaterRunning(target: HotWaterTarget, elapsedMs: Long, volumeUnit: String): String {
    val elapsed = fmt("%.1f", elapsedMs / 1000f)
    if (target.mode == HotWaterMode.Time) {
        return target.timeS?.let { "$elapsed / ${fmt("%.1f", it)} s" } ?: "$elapsed s"
    }
    return "$elapsed s · ${volume(target.volumeMl, volumeUnit)}"
}

/** The seconds a pour runs for, for a progress bar: time mode only, else 0. */
fun hotWaterTargetSeconds(target: HotWaterTarget): Float =
    if (target.mode == HotWaterMode.Time) target.timeS ?: 0f else 0f

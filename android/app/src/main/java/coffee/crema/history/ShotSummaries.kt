package coffee.crema.history

import coffee.crema.core.BrewSeries
import coffee.crema.core.ShotSummary
import coffee.crema.core.signatureForShot
import coffee.crema.visualizer.shotFinalWeight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * A row's summary (SPEC §4): what a History row shows without its series,
 * from the core's `shot_summary`, written at save, on migration and on
 * restore. P3a stores it; from P4a the rows on screen read it.
 */

/** The core's summary of [shot] (its samples and brew series). The input keeps
 *  a 0 g scale reading, as [shotFinalWeight] does, so the summary's final
 *  weight is the same one the de-dup signature reads. If the core can't read
 *  the shot, the same peaks worked out here, with no spark. */
internal fun summaryOf(shot: StoredShot, json: Json): ShotSummary {
    val input = runCatching {
        val core = shot.copy(profile = null).coreShotJson()
        val brew = shot.brewSeries?.let { json.encodeToJsonElement(BrewSeries.serializer(), it) }
        if (brew == null) core else JsonObject(core + ("brewSeries" to brew))
    }.getOrNull()
    val fromCore = input?.let { i ->
        runCatching { json.decodeFromString(ShotSummary.serializer(), coffee.crema.core.shotSummary(i.toString())) }.getOrNull()
    }
    return fromCore ?: summaryHere(shot)
}

/** [summaryOf] without the core: the same counts and peaks, and no spark. */
internal fun summaryHere(shot: StoredShot): ShotSummary {
    var peakWeight: Float? = null
    var finalWeight: Float? = null
    var peakPressure = 0f
    var peakTemp = 0f
    for (s in shot.samples) {
        peakPressure = maxOf(peakPressure, s.pressure)
        peakTemp = maxOf(peakTemp, s.headTemp)
        s.weight?.let { w ->
            peakWeight = peakWeight?.let { maxOf(it, w) } ?: w
            finalWeight = w
        }
    }
    return ShotSummary(
        v = 1u,
        sampleCount = shot.samples.size.toUInt(),
        brewSampleCount = (shot.brewSeries?.samples?.size ?: 0).toUInt(),
        peakWeight = peakWeight,
        finalWeight = finalWeight,
        peakPressure = peakPressure,
        peakTemp = peakTemp,
        spark = null,
    )
}

/** The summary of a row whose series couldn't be read: not manual, not "no telemetry", unknown. */
internal fun unknownSeriesSummary(shot: StoredShot): ShotSummary =
    summaryHere(shot.copy(samples = emptyList())).copy(sampleCount = null)

/** The de-dup signature of [shot] with its series (Visualizer `metadata.crema.signature`). */
internal fun signatureOfShot(shot: StoredShot): String =
    signatureForShot(shot.completedAtMs, shot.durationMs, shot.profileName, shotFinalWeight(shot))

/** The same signature from a row and its summary alone (SPEC §4): the final
 *  weight, then the peak, then the yield. */
internal fun signatureOfRow(row: ShotMeta, summary: ShotSummary?): String =
    signatureForShot(row.completedAtMs, row.durationMs, row.profileName, summary?.finalWeight ?: summary?.peakWeight ?: row.yieldG)

/** Whether [summary] says the shot has a machine curve on disk. Unknown counts as none here, as it does today. */
internal fun ShotSummary?.hasCurve(): Boolean = (this?.sampleCount ?: 0u) > 0u

/** [row]'s de-dup signature from its summary alone (SPEC §4): the one a pull matches against. */
fun rowSignature(row: ShotRow): String = signatureOfRow(row, row.summary)

/** [row]'s final weight for the de-dup signature, from its summary: the last reading, then the peak, then the yield. */
fun rowFinalWeight(row: ShotRow): Float? = row.summary.finalWeight ?: row.summary.peakWeight ?: row.yieldG

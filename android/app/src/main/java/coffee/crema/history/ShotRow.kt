package coffee.crema.history

import coffee.crema.core.BrewSeries
import coffee.crema.core.ShotBean
import coffee.crema.core.ShotSummary
import coffee.crema.ui.TelemetrySample
import kotlinx.serialization.json.JsonObject

/*
 * A History row without its telemetry (#310 P4a, SPEC §4). The history store's
 * memory holds these (#310 A3b; the UI state holds none): every field of a [StoredShot] except `samples`, `profile` and
 * `brewSeries`, plus the row's [ShotSummary] (peaks, sample counts and the row
 * chart's spark). The heavy parts load per shot through
 * `HistoryStore.loadShot`, so no list holds a curve, and the compiler finds
 * every reader of one: a [ShotRow] has no `samples` to read as empty.
 *
 * The light fields are [ShotMeta], shared with [StoredShot], so the filters,
 * labels and stats read either. `ShotRowFieldsTest` checks the two types keep
 * the same light fields.
 */

/** What a [StoredShot] and a [ShotRow] share: everything but the heavy parts. */
interface ShotMeta {
    val id: String
    val completedAtMs: Long
    val durationMs: Long
    val yieldG: Float?
    val doseG: Float?
    val bagDebitG: Float?
    val grindSetting: Float?
    val yieldTargetG: Float?
    val peakPressure: Float?
    val peakTemp: Float?
    val peakWeightG: Float?
    val profileName: String?
    val profileId: String?
    val bean: ShotBean?
    val rating: Int?
    val notes: String?
    val nextPlan: String?
    val barista: String?
    val drinker: String?
    val grinderModel: String?
    val grinderId: String?
    val tags: List<String>
    val preinfuseTargetS: Float?
    val stopOnWeight: Boolean?
    val autoTare: Boolean?
    val stopReason: String?
    val stopEstimated: Boolean
    val privacy: String?
    val visualizerId: String?
    val decentId: String?
    val visualizerUpdatedAt: Long?
    val pinned: Boolean
    val machineSerial: String?
    val machineFirmware: String?
    val machineModel: String?
    val brewMethod: String?
    val brewMethodLabel: String?
    val recipeName: String?
    val waterG: Float?
    val brewTempC: Float?
}

/** One History row: a [StoredShot] without its series, profile and brew series, with its [summary]. */
data class ShotRow(
    override val id: String,
    override val completedAtMs: Long,
    override val durationMs: Long,
    override val yieldG: Float? = null,
    override val doseG: Float? = null,
    override val bagDebitG: Float? = null,
    override val grindSetting: Float? = null,
    override val yieldTargetG: Float? = null,
    override val peakPressure: Float? = null,
    override val peakTemp: Float? = null,
    override val peakWeightG: Float? = null,
    override val profileName: String? = null,
    override val profileId: String? = null,
    override val bean: ShotBean? = null,
    override val rating: Int? = null,
    override val notes: String? = null,
    override val nextPlan: String? = null,
    override val barista: String? = null,
    override val drinker: String? = null,
    override val grinderModel: String? = null,
    override val grinderId: String? = null,
    override val tags: List<String> = emptyList(),
    override val preinfuseTargetS: Float? = null,
    override val stopOnWeight: Boolean? = null,
    override val autoTare: Boolean? = null,
    override val stopReason: String? = null,
    override val stopEstimated: Boolean = false,
    override val privacy: String? = null,
    override val visualizerId: String? = null,
    override val decentId: String? = null,
    override val visualizerUpdatedAt: Long? = null,
    override val pinned: Boolean = false,
    override val machineSerial: String? = null,
    override val machineFirmware: String? = null,
    override val machineModel: String? = null,
    override val brewMethod: String? = null,
    override val brewMethodLabel: String? = null,
    override val recipeName: String? = null,
    override val waterG: Float? = null,
    override val brewTempC: Float? = null,
    /** Peaks, sample counts and the row chart, from the core when the series was last written. */
    val summary: ShotSummary,
) : ShotMeta

/** [this] as a row with [summary]: its series, profile and brew series left behind. */
fun StoredShot.toRow(summary: ShotSummary): ShotRow = ShotRow(
    id = id, completedAtMs = completedAtMs, durationMs = durationMs, yieldG = yieldG, doseG = doseG,
    bagDebitG = bagDebitG, grindSetting = grindSetting, yieldTargetG = yieldTargetG, peakPressure = peakPressure,
    peakTemp = peakTemp, peakWeightG = peakWeightG, profileName = profileName, profileId = profileId, bean = bean, rating = rating,
    notes = notes, nextPlan = nextPlan, barista = barista, drinker = drinker, grinderModel = grinderModel, grinderId = grinderId,
    tags = tags, preinfuseTargetS = preinfuseTargetS, stopOnWeight = stopOnWeight, autoTare = autoTare,
    stopReason = stopReason, stopEstimated = stopEstimated, privacy = privacy, visualizerId = visualizerId,
    decentId = decentId, visualizerUpdatedAt = visualizerUpdatedAt, pinned = pinned, machineSerial = machineSerial,
    machineFirmware = machineFirmware, machineModel = machineModel, brewMethod = brewMethod,
    brewMethodLabel = brewMethodLabel, recipeName = recipeName, waterG = waterG, brewTempC = brewTempC,
    summary = summary,
)

/**
 * The full shot: this row's fields with the heavy parts given. Only a shot
 * loaded whole (`loadShot`) has them; a shot made here without them is light,
 * and nothing outside the store should read it as having no curve.
 */
fun ShotRow.withHeavy(
    samples: List<TelemetrySample>,
    profile: JsonObject?,
    brewSeries: BrewSeries?,
): StoredShot = StoredShot(
    id = id, completedAtMs = completedAtMs, durationMs = durationMs, yieldG = yieldG, doseG = doseG,
    bagDebitG = bagDebitG, grindSetting = grindSetting, yieldTargetG = yieldTargetG, peakPressure = peakPressure,
    peakTemp = peakTemp, peakWeightG = peakWeightG, profileName = profileName, profileId = profileId, profile = profile, bean = bean,
    rating = rating, notes = notes, nextPlan = nextPlan, barista = barista, drinker = drinker,
    grinderModel = grinderModel, grinderId = grinderId, samples = samples, tags = tags, preinfuseTargetS = preinfuseTargetS,
    stopOnWeight = stopOnWeight, autoTare = autoTare, stopReason = stopReason, stopEstimated = stopEstimated,
    privacy = privacy, visualizerId = visualizerId, decentId = decentId, visualizerUpdatedAt = visualizerUpdatedAt,
    pinned = pinned, machineSerial = machineSerial, machineFirmware = machineFirmware, machineModel = machineModel,
    brewMethod = brewMethod, brewMethodLabel = brewMethodLabel, recipeName = recipeName, waterG = waterG,
    brewTempC = brewTempC, brewSeries = brewSeries,
)

/** [full]'s heavy parts under this row's fields: a loaded shot as the row now stands (an edit since the load wins). */
fun ShotRow.joined(full: StoredShot): StoredShot = withHeavy(full.samples, full.profile, full.brewSeries)

/** The row as the store keeps it in memory and in `row_json`: no heavy parts. */
internal fun ShotRow.light(): StoredShot = withHeavy(emptyList(), null, null)

/**
 * Manually logged: a method, and no telemetry of any kind. An unknown count
 * (the series couldn't be read) is not manual (SPEC §4): a measured row must
 * never turn editable. Mirrors [StoredShot.isManualLog].
 */
val ShotRow.isManualLog: Boolean
    get() = brewMethod != null && summary.sampleCount == 0u && summary.brewSampleCount == 0u

/** The summary says the shot has machine samples. Unknown is not a curve here; the store's `hasCurve` checks the file too. */
val ShotRow.summarisedCurve: Boolean get() = (summary.sampleCount ?: 0u) > 0u

/** The summary says the shot has a guided brew's scale series. */
val ShotRow.hasBrewSeries: Boolean get() = summary.brewSampleCount > 0u

/** Machine samples per the summary, null when unknown. */
val ShotRow.sampleCount: Int? get() = summary.sampleCount?.toInt()

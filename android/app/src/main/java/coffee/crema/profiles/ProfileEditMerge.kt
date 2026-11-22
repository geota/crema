package coffee.crema.profiles

import coffee.crema.core.mergeProfileEditJson
import kotlinx.serialization.json.Json

/*
 * The profile editor's Save, field by field (REVIEW-FINAL S6, REVIEW-FINAL-2
 * N2): what the user changed in the editor lands on the profile as stored now,
 * so a pin or an in-place Quick Save written while the editor was open stays.
 * The rule is the core's `merge_profile_edit`; this file only builds its three
 * inputs the same way, so a field the user left alone compares equal.
 */

/**
 * [opened] as the editor would save it had the user changed nothing: its own
 * values put back through [patchCremaProfileJson], seeded as both editor
 * screens seed their fields. It is the `base` the merge compares the editor's
 * Save against, so an untouched field never reads as an edit because the
 * patch spells it differently (a float, a segment's default temperature).
 */
fun profileEditorSeedJson(opened: String, json: Json): String {
    val p = json.decodeFromString(CremaProfile.serializer(), opened)
    val segments = p.segments.map { it.toEdit(p.brewTemp) }
    return patchCremaProfileJson(
        baseJson = opened,
        name = p.name,
        roast = p.roast,
        tags = p.tags.filterNot { it.equals("Built-in", ignoreCase = true) },
        pinned = p.pinned,
        notes = p.notes,
        author = p.author,
        beverageType = p.beverageType,
        dose = p.dose.toDouble().toFloat(),
        yieldOut = p.yieldOut.toDouble().toFloat(),
        brewTemp = (segments.firstOrNull()?.temp ?: p.brewTemp).toDouble().toFloat(),
        maxTotalVolumeMl = p.maxTotalVolumeMl.toDouble().toInt(),
        preinfuseStepCount = p.preinfuseStepCount.toDouble().toInt(),
        tankTemperatureC = p.tankTemperatureC.toDouble().toFloat(),
        segments = segments,
        json = json,
    )
}

/**
 * The profile a Save stores: the fields [edited] changed against [base] (both
 * already through [patchCremaProfileJson], see [profileEditorSeedJson]) put on
 * [current], the stored row now; the body (segments and preinfusion count) as
 * one piece, `brewTemp` derived again, `id` and `source` the stored row's.
 * [core] is the UniFFI `mergeProfileEditJson` (injectable for JVM tests).
 */
fun mergeProfileSave(
    base: String,
    edited: String,
    current: String,
    core: (baseJson: String, editedJson: String, currentJson: String) -> String = ::mergeProfileEditJson,
): String = core(base, edited, current)

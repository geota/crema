package coffee.crema.ui

import coffee.crema.core.BrewOverrides
import coffee.crema.core.BrewTempRange
import kotlinx.serialization.json.Json

/**
 * The Quick-Controls override as the core's `BrewOverrides` JSON
 * (`{dose, yield, brewTemp, preinf}`): the absolute values the core applies to
 * the active profile — brew temp shifts every step, pre-infusion rescales the
 * leading pre-infusion segments, dose / yield replace — and clamps
 * (`de1_domain::effective_profile`). Values equal to the profile's own are
 * dropped by the core, so passing every field is safe. `null` = no override.
 */
internal fun BrewParams?.overridesJson(json: Json): String? = this?.let {
    json.encodeToString(
        BrewOverrides.serializer(),
        BrewOverrides(
            dose = it.dose.toFloat(),
            yield = it.yieldOut.toFloat(),
            brewTemp = it.brewTemp.toFloat(),
            preinf = it.preinf?.toFloat(),
        ),
    )
}

/**
 * The override after the core's lifecycle rule (Decenza
 * `applyTemperatureOverride`), applied where the override is stored — the
 * device holding the machine, for its own dial and a remote's relayed one
 * alike: brew temp clamped into the active profile's range, values equal to
 * the profile's own dropped. Nothing left → `null` (the profile's recipe
 * applies; the Reset state). No active profile → [params] unchanged.
 *
 * [normalize] is the core's `normalizeBrewOverrides(profileJson, qcJson)` and
 * [range] its `brewTempRange(profileJson)`; injected so this stays pure.
 */
internal fun normalizedBrewParams(
    params: BrewParams?,
    activeCremaJson: String?,
    json: Json,
    normalize: (String, String?) -> String,
    range: (String) -> String,
): BrewParams? {
    if (params == null || activeCremaJson == null) return params
    return runCatching {
        val qc = json.decodeFromString(BrewOverrides.serializer(), normalize(activeCremaJson, params.overridesJson(json)))
        if (qc.dose == null && qc.yield == null && qc.brewTemp == null && qc.preinf == null) return null
        val anchor = json.decodeFromString(BrewTempRange.serializer(), range(activeCremaJson)).anchor
        BrewParams(
            // A dropped dose / yield equals the profile's own (within 0.05).
            dose = qc.dose?.toDouble() ?: params.dose,
            yieldOut = qc.yield?.toDouble() ?: params.yieldOut,
            brewTemp = (qc.brewTemp ?: anchor).toDouble(),
            preinf = qc.preinf?.toDouble(),
        )
    }.getOrDefault(params)
}

/**
 * The brew-temperature dial for the active profile, from the core: its range
 * (no step may leave the core's temperature bounds once every step is
 * shifted), the profile's own brew temperature it is measured from, and the
 * profile's pre-infusion seconds (the total of its leading pre-infusion
 * segments) — the pre-infusion dial's seed.
 */
data class BrewDial(val min: Double, val max: Double, val anchor: Double, val preinfS: Double) {
    /** How far [brewTemp] sits from the profile's own, °C: the
     *  "+x.x °C from profile" hint; `null` at the anchor. */
    fun offsetLabel(brewTemp: Double): String? {
        val delta = brewTemp - anchor
        if (kotlin.math.abs(delta) < 0.05) return null
        val sign = if (delta > 0) "+" else "−"
        return "$sign${"%.1f".format(java.util.Locale.ROOT, kotlin.math.abs(delta))} °C from profile"
    }
}

/** [BrewDial] for a `CremaProfile` JSON; `null` when there is none or the core
 *  can't read it. [range] / [preinfusion] are the core's `brewTempRange` /
 *  `preinfusionSeconds`. */
internal fun brewDialOf(
    cremaJson: String?,
    json: Json,
    range: (String) -> String,
    preinfusion: (String) -> Float,
): BrewDial? = cremaJson?.let { profile ->
    runCatching {
        val r = json.decodeFromString(BrewTempRange.serializer(), range(profile))
        BrewDial(r.min.toDouble(), r.max.toDouble(), r.anchor.toDouble(), preinfusion(profile).toDouble())
    }.getOrNull()
}

/** The DE1 holds the desired profile (the core's profile sync pushed it on
 *  this connection). */
internal fun coffee.crema.core.ProfileSyncStatus.holdsDesired(): Boolean =
    desired != null && desired == lastPushed

package coffee.crema.ui

import coffee.crema.profiles.CremaProfile

/**
 * Transient Quick-Controls brew override — the dose / yield / brew-temp the user
 * nudged in the Quick Controls sheet. NOT persisted to any profile (mirrors the
 * web shell's BrewParamState): it is baked into the NEXT shot's uploaded profile
 * at [MainViewModel.startShot] and cleared on profile switch / Reset. The yield
 * is also pushed to the core as the stop-at-weight target (web
 * `setShotTargetWeight`) by [MainViewModel.pushStopTargets], so SAW arms on it.
 */
/**
 * The transient Quick-Controls brew override baked into the next shot's upload
 * (the library profile is untouched). dose/yield/brewTemp always carry the
 * effective value; [preinf] is null until the user touches the pre-infuse
 * stepper — pre-infusion isn't a separate machine setting, so an override just
 * caps the leading pre-infuse segment's time (issue 15).
 */
data class BrewParams(
    val dose: Double,
    val yieldOut: Double,
    val brewTemp: Double,
    val preinf: Double? = null,
)

/**
 * The brew parameters actually in effect: the live Quick-Controls override
 * ([brewParams]) if present, else the active profile's recipe, else the seed
 * defaults — 18 g dose, 93 °C, and a 1:2 yield (`dose * 2`). One source for the
 * `brewParams ?: active ?: 18/dose*2/93` triple that was copied across the brew &
 * scale surfaces (issue 28). The yield seed is ratio-preserving rather than a flat
 * 36 g so it stays a 1:2 shot if the dose seed ever changes; at today's 18 g dose
 * it is exactly 36 g, so this is a no-op vs the old hardcoded value.
 * NB this is the 3-way override→profile→default fallback; it is deliberately
 * distinct from the 2-way profile-only recipe (`active?.dose ?: 18f`) and the
 * per-segment temperature default (`seg.temp ?: 93f`), which are not folded in.
 */
data class EffectiveBrew(val dose: Double, val yieldOut: Double, val brewTemp: Double)

fun effectiveBrew(brewParams: BrewParams?, active: CremaProfile?): EffectiveBrew {
    val dose = brewParams?.dose ?: active?.dose?.toDouble() ?: 18.0
    return EffectiveBrew(
        dose = dose,
        yieldOut = brewParams?.yieldOut ?: active?.yieldOut?.toDouble() ?: (dose * 2),
        brewTemp = brewParams?.brewTemp ?: active?.brewTemp?.toDouble() ?: 93.0,
    )
}

/**
 * The profile currently driving the Brew surface: the mirror overlay
 * ([MainUiState.mirroredProfile]) when this device is showing a primary's custom
 * profile it doesn't itself have (issue 05), else the library lookup by
 * [MainUiState.activeProfileId]. One source so the header, curve, targets, and
 * scale dose all agree on a secondary.
 */
fun MainUiState.activeProfile(): CremaProfile? =
    mirroredProfile ?: profiles.firstOrNull { it.id == activeProfileId }

fun MainUiState.effectiveBrew(): EffectiveBrew =
    effectiveBrew(brewParams, activeProfile())

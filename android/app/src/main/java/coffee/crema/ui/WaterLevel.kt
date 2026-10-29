package coffee.crema.ui

/**
 * Headroom (mm) above the DE1's refill threshold at or below which the "refill
 * soon" cue shows — the tank is close to, but not yet at, the machine's own
 * refill point. Mirrors the web shell's `REFILL_SOON_MARGIN_MM`
 * (`web/src/lib/state/ui-state.svelte.ts`) so every shell warns at the same level.
 */
const val REFILL_SOON_MARGIN_MM = 5f

/** Typical full tank, ml — mirrors core `TANK_FULL_ML` (de1app's 40 mm
 *  `water_level_full_point`). Converts the warn dial's % ↔ canonical ml. */
const val TANK_FULL_ML_UI = 1104f

/** Default low-water warning threshold, ml — ~10% of a typical 1104 ml full
 *  fill (#33 follow-up). Applied when [MainUiState.waterWarnMl] is unset. */
const val WATER_WARN_DEFAULT_ML = 110f

/** Hysteresis above the warning threshold before the once-per-dip warning
 *  re-arms — ~2 mm of tank so pump-driven level bounce can't re-fire it. */
const val WATER_WARN_REARM_ML = 55f

/** Refill-point dial bounds, raw sensor mm — de1app's own slider range
 *  (`de1_skin_settings.tcl`, `-from 3 -to 70`), which is the range the
 *  firmware accepts. */
const val REFILL_POINT_MIN_MM = 3f
const val REFILL_POINT_MAX_MM = 70f

/** The effective low-water warning threshold, ml — the user's dialled value,
 *  the default when unset, and `<= 0` = disabled. */
fun MainUiState.waterWarnThresholdMl(): Float = waterWarnMl ?: WATER_WARN_DEFAULT_ML

/**
 * The refill point (raw sensor mm) to push to the machine — the user's dialled
 * value, or core's 5 mm default when unset. This is the DE1's OWN threshold
 * (the `WaterLevels` `StartFillLevel`): below it the machine blinks for water
 * and refuses to pour, whatever the app shows. de1app and Decenza both write
 * it at connect; Crema didn't, so a stale value left in the firmware made the
 * DE1 demand a refill with a half-full tank (geota/crema#47).
 */
fun MainUiState.refillPointMm(): Float = waterRefillPointMm ?: coffee.crema.core.defaultRefillPointMm()

/** The tank's true water depth (mm), the number de1app and Decenza both
 *  display. `Event.WaterLevel.level` already IS this depth (core adds the 5 mm
 *  sensor offset once, at decode), so this is the level as-is — never run it
 *  through `waterTankDepthMm` again (that double offset read ~135 ml high).
 *  Null until the first report. */
fun MainUiState.tankDepthMm(): Float? = waterLevelMm

/** The DE1's reported refill threshold (RAW sensor mm on the wire) converted
 *  to depth mm via core's `waterTankDepthMm`, so it compares and reads in the
 *  same unit as [tankDepthMm]. Null before the first report. */
fun MainUiState.refillThresholdDepthMm(): Float? =
    waterRefillThresholdMm?.let { coffee.crema.core.waterTankDepthMm(it) }

/**
 * Whether the tank is low enough to warrant a "refill soon" cue: the live level
 * is within [REFILL_SOON_MARGIN_MM] of (or already below) the DE1's *reported*
 * refill threshold. Both readings arrive together in one `Event.WaterLevel`
 * packet, so the cue is naturally suppressed before the first report and while
 * disconnected (the level is null). Mirrors web `waterRefillSoon` — the shell
 * defers to the machine's own refill point rather than a hardcoded threshold,
 * reconciling the former phone 20 mm vs tablet 5 mm constants (issue 29).
 */
fun MainUiState.refillSoon(): Boolean {
    // Depth vs depth: the level is offset-corrected, the threshold is raw on
    // the wire, so it goes through [refillThresholdDepthMm] first.
    val level = tankDepthMm() ?: return false
    val threshold = refillThresholdDepthMm() ?: return false
    return level <= threshold + REFILL_SOON_MARGIN_MM
}

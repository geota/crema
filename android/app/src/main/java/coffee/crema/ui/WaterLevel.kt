package coffee.crema.ui

import coffee.crema.core.WaterConstants
import kotlinx.serialization.json.Json

/** The core's water constants (web's settings read the same ones). */
private val water: WaterConstants by lazy {
    Json.decodeFromString(WaterConstants.serializer(), coffee.crema.core.waterConstants())
}

/** Typical full tank, ml — core `TANK_FULL_ML` (de1app's 40 mm
 *  `water_level_full_point`). Converts the warn dial's % ↔ canonical ml. */
val TANK_FULL_ML_UI: Float get() = water.tankFullMl

/** Default low-water warning threshold, ml — core `WATER_WARN_DEFAULT_ML`,
 *  ~10% of a full fill (#33 follow-up). Applied when [MainUiState.waterWarnMl]
 *  is unset. */
val WATER_WARN_DEFAULT_ML: Float get() = water.warnDefaultMl

/** Refill-point dial bounds, raw sensor mm — de1app's own slider range
 *  (`-from 3 -to 70`), core `REFILL_POINT_MIN_MM` / `MAX`; the core clamps
 *  every refill-point write to it too. */
val REFILL_POINT_MIN_MM: Float get() = water.refillPointMinMm
val REFILL_POINT_MAX_MM: Float get() = water.refillPointMaxMm

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
 * Whether the tank is low enough to warrant a "refill soon" cue — the core's
 * `refill_soon` (shared with web): the live level DEPTH within 5 mm of, or
 * already below, the DE1's *reported* RAW refill threshold. Both readings
 * arrive together in one `Event.WaterLevel`, so the cue is naturally
 * suppressed before the first report and while disconnected.
 */
fun MainUiState.refillSoon(): Boolean = coffee.crema.core.refillSoon(tankDepthMm(), waterRefillThresholdMm)

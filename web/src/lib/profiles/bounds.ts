/**
 * `$lib/profiles/bounds` — wire-protocol bounds for DE1 profile fields.
 *
 * Read once at module load from `de1_wasm::profile_bounds_json` so the
 * web shell, the future Android shell, and any profile validator reach
 * for the same numbers. The constants below are the **hard** firmware
 * limits (10-bit volume, 32-step cap, u8p4 pressure/flow/temperature
 * encodings, …). Editorial / UX-friendly ranges (e.g. "warn above 95 °C
 * brew temp") stay in the shell components — these are reject-or-accept
 * truth.
 *
 * See `core/de1-domain/src/profile_bounds.rs` for the canonical
 * declarations.
 */

import { profile_bounds_json } from '$lib/wasm/de1_wasm';

interface RawProfileBounds {
	max_profile_steps: number;
	max_total_volume_ml: number;
	min_total_volume_ml: number;
	min_pressure_bar: number;
	max_pressure_bar: number;
	min_flow_ml_per_s: number;
	max_flow_ml_per_s: number;
	min_temperature_c: number;
	max_temperature_c: number;
	max_steam_temperature_c: number;
	min_frame_seconds: number;
	max_frame_seconds: number;
	max_preinfuse_steps: number;
}

const RAW = JSON.parse(profile_bounds_json()) as RawProfileBounds;

/** Maximum step count in a profile — DE1 firmware accepts `0..=32`. */
export const MAX_PROFILE_STEPS = RAW.max_profile_steps;
/** Maximum `max_total_volume` (mL) — 10-bit wire field saturates at 1023. */
export const MAX_TOTAL_VOLUME_ML = RAW.max_total_volume_ml;
/** Minimum total volume — `0` disables the volume-stop. */
export const MIN_TOTAL_VOLUME_ML = RAW.min_total_volume_ml;
/** Minimum pressure setpoint (bar). */
export const MIN_PRESSURE_BAR = RAW.min_pressure_bar;
/** Maximum pressure setpoint (bar) — `u8p4` wire ceiling. */
export const MAX_PRESSURE_BAR = RAW.max_pressure_bar;
/** Minimum flow setpoint (mL/s). */
export const MIN_FLOW_ML_PER_S = RAW.min_flow_ml_per_s;
/** Maximum flow setpoint (mL/s) — `u8p4` wire ceiling. */
export const MAX_FLOW_ML_PER_S = RAW.max_flow_ml_per_s;
/** Minimum temperature setpoint (°C). 0 ° = "no override". */
export const MIN_TEMPERATURE_C = RAW.min_temperature_c;
/** Maximum brew / tank temperature setpoint (°C) — conservative cap. */
export const MAX_TEMPERATURE_C = RAW.max_temperature_c;
/** Maximum steam temperature setpoint (°C) — boiler can exceed brew. */
export const MAX_STEAM_TEMPERATURE_C = RAW.max_steam_temperature_c;
/** Minimum frame duration (seconds). 0 is legal for instantaneous changes. */
export const MIN_FRAME_SECONDS = RAW.min_frame_seconds;
/** Maximum frame duration (seconds) — `u8p4` wire ceiling. */
export const MAX_FRAME_SECONDS = RAW.max_frame_seconds;
/** Maximum pre-infusion step count — capped at `MAX_PROFILE_STEPS`. */
export const MAX_PREINFUSE_STEPS = RAW.max_preinfuse_steps;

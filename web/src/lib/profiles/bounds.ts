/**
 * `$lib/profiles/bounds` — wire-protocol bounds for DE1 profile fields.
 *
 * Sourced from `de1_wasm::profile_bounds_json` so the web shell, the Android
 * shell, and any profile validator reach for the same numbers. These are the
 * **hard** firmware limits (10-bit volume, 32-step cap, u8p4 pressure / flow /
 * temperature encodings, …). Editorial / UX-friendly ranges (e.g. "warn above
 * 95 °C brew temp") stay in the shell components — these are reject-or-accept
 * truth.
 *
 * Resolved **lazily** (on first property read) and memoized — *never* at module
 * scope. The wasm export is only callable once `loadCore()` has run, yet this
 * module is imported by profile-editor components; calling `profile_bounds_json()`
 * at module-load time raced wasm init and crashed first paint with `Cannot read
 * properties of undefined (reading '__wbindgen_malloc')`. Every read here happens
 * at render time, long after the core has loaded — the same rule `de1-uuids.ts`
 * and `scale.ts` follow: "never at module scope".
 *
 * See `core/de1-domain/src/profile_bounds.rs` for the canonical declarations.
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

/** The core's bounds — parsed once on first access, then memoized (see header). */
let raw: RawProfileBounds | null = null;
const bounds = (): RawProfileBounds =>
	(raw ??= JSON.parse(profile_bounds_json()) as RawProfileBounds);

/**
 * Hard firmware bounds for profile fields, sourced from the Rust core. Each is a
 * lazy getter so the wasm call defers off the module-load path (see header).
 */
export const ProfileBounds = {
	/** Maximum step count in a profile — DE1 firmware accepts `0..=32`. */
	get MAX_PROFILE_STEPS() {
		return bounds().max_profile_steps;
	},
	/** Maximum `max_total_volume` (ml) — 10-bit wire field saturates at 1023. */
	get MAX_TOTAL_VOLUME_ML() {
		return bounds().max_total_volume_ml;
	},
	/** Minimum total volume — `0` disables the volume-stop. */
	get MIN_TOTAL_VOLUME_ML() {
		return bounds().min_total_volume_ml;
	},
	/** Minimum pressure setpoint (bar). */
	get MIN_PRESSURE_BAR() {
		return bounds().min_pressure_bar;
	},
	/** Maximum pressure setpoint (bar) — `u8p4` wire ceiling. */
	get MAX_PRESSURE_BAR() {
		return bounds().max_pressure_bar;
	},
	/** Minimum flow setpoint (ml/s). */
	get MIN_FLOW_ML_PER_S() {
		return bounds().min_flow_ml_per_s;
	},
	/** Maximum flow setpoint (ml/s) — `u8p4` wire ceiling. */
	get MAX_FLOW_ML_PER_S() {
		return bounds().max_flow_ml_per_s;
	},
	/** Minimum temperature setpoint (°C). 0 ° = "no override". */
	get MIN_TEMPERATURE_C() {
		return bounds().min_temperature_c;
	},
	/** Maximum brew / tank temperature setpoint (°C) — conservative cap. */
	get MAX_TEMPERATURE_C() {
		return bounds().max_temperature_c;
	},
	/** Maximum steam temperature setpoint (°C) — boiler can exceed brew. */
	get MAX_STEAM_TEMPERATURE_C() {
		return bounds().max_steam_temperature_c;
	},
	/** Minimum frame duration (seconds). 0 is legal for instantaneous changes. */
	get MIN_FRAME_SECONDS() {
		return bounds().min_frame_seconds;
	},
	/** Maximum frame duration (seconds) — `u8p4` wire ceiling. */
	get MAX_FRAME_SECONDS() {
		return bounds().max_frame_seconds;
	},
	/** Maximum pre-infusion step count — capped at `MAX_PROFILE_STEPS`. */
	get MAX_PREINFUSE_STEPS() {
		return bounds().max_preinfuse_steps;
	}
} as const;

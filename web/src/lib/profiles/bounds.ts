/**
 * `$lib/profiles/bounds` — wire-protocol bounds for DE1 profile fields.
 *
 * The numbers below mirror `core/de1-domain/src/profile_bounds.rs` — the
 * canonical reject-or-accept truth driven by the DE1's wire-format
 * encodings (10-bit volume, 32-step cap, u8p4 pressure / flow /
 * temperature precision-pairs, …). Editorial / UX-friendly ranges (e.g.
 * "warn above 95 °C brew temp") stay in each shell component; these
 * constants are the hard firmware limits.
 *
 * Previously these read from `de1_wasm::profile_bounds_json` at module
 * load. That caused a `__wbindgen_malloc undefined` crash on first
 * paint: `bounds.ts` is statically imported by routes that mount before
 * the wasm `init()` resolves, and the JSON read fires synchronously at
 * top level. The bounds never change at runtime, so the JSON detour is
 * pure cost — inlined verbatim from the Rust source.
 */

/** Maximum step count in a profile — DE1 firmware accepts `0..=32`. */
export const MAX_PROFILE_STEPS = 32;
/** Maximum `max_total_volume` (ml) — 10-bit wire field saturates at 1023. */
export const MAX_TOTAL_VOLUME_ML = 1023;
/** Minimum total volume — `0` disables the volume-stop. */
export const MIN_TOTAL_VOLUME_ML = 0;
/** Minimum pressure setpoint (bar). */
export const MIN_PRESSURE_BAR = 0.0;
/** Maximum pressure setpoint (bar) — `u8p4` wire ceiling. */
export const MAX_PRESSURE_BAR = 15.9375;
/** Minimum flow setpoint (ml/s). */
export const MIN_FLOW_ML_PER_S = 0.0;
/** Maximum flow setpoint (ml/s) — `u8p4` wire ceiling. */
export const MAX_FLOW_ML_PER_S = 15.9375;
/** Minimum temperature setpoint (°C). 0 ° = "no override". */
export const MIN_TEMPERATURE_C = 0.0;
/** Maximum brew / tank temperature setpoint (°C) — conservative cap. */
export const MAX_TEMPERATURE_C = 100.0;
/** Maximum steam temperature setpoint (°C) — boiler can exceed brew. */
export const MAX_STEAM_TEMPERATURE_C = 170.0;
/** Minimum frame duration (seconds). 0 is legal for instantaneous changes. */
export const MIN_FRAME_SECONDS = 0.0;
/** Maximum frame duration (seconds) — `u8p4` wire ceiling. */
export const MAX_FRAME_SECONDS = 25.5;
/** Maximum pre-infusion step count — capped at `MAX_PROFILE_STEPS`. */
export const MAX_PREINFUSE_STEPS = MAX_PROFILE_STEPS;

//! Wire-protocol bounds for DE1 profile fields.
//!
//! These are the **hard** limits the firmware will accept — driven by the
//! DE1's wire-format encodings (10-bit volume, the 20-frame limit, `U8P4`
//! pressure / flow, `U8P1` temperature, `F8_1_7` frame time). Editorial /
//! UX-friendly ranges (e.g. "warn above 95 °C brew temp") stay in each
//! shell; this module is the canonical reject-or-accept truth.
//!
//! Centralised in `de1-domain` so the web shell, Android shell, and any
//! profile validator all reach for the same numbers.

// ── Step count ──────────────────────────────────────────────────────────

/// Maximum step count in a profile: 20, "the maximum number of steps that
/// the espresso machine can handle" (de1app `vars.tcl` refuses a 21st step;
/// pyDE1 `_MAX_SHOT_FRAMES = 20`). `profile::MAX_STEPS` and the importer use
/// this value; both reject a longer profile rather than truncate it.
pub const MAX_PROFILE_STEPS: u8 = 20;

// ── Volume ──────────────────────────────────────────────────────────────

/// Maximum total volume (ml) a profile may carry. The limit is the 10-bit
/// `MaxTotalVolume` field of the profile's tail frame (`FrameWrite`), so
/// values above this saturate at 1023. Crema zeroes the field on the wire
/// (no firmware volume stop, like every reference app) and stops by volume
/// app-side; the value stays on the profile for SAV and export.
pub const MAX_TOTAL_VOLUME_ML: u16 = 1023;

/// Minimum volume — `0` disables the volume-stop.
pub const MIN_TOTAL_VOLUME_ML: u16 = 0;

// ── Pressure ────────────────────────────────────────────────────────────

/// Lower bound for any pressure setpoint (bar). The firmware accepts
/// `0..=15.9375` (the `u8p4` wire encoding); 0 bar is "no pressure
/// limit" for flow-controlled steps.
pub const MIN_PRESSURE_BAR: f32 = 0.0;

/// Upper bound for any pressure setpoint (bar). The DE1's pump can't
/// produce more than ~13 bar at the puck under any real load, but the
/// wire-format `u8p4` allows 15.9375; we accept up to the wire ceiling
/// and let the firmware physics handle the rest.
pub const MAX_PRESSURE_BAR: f32 = 15.9375;

// ── Flow ────────────────────────────────────────────────────────────────

/// Lower bound for any flow setpoint (ml/s).
pub const MIN_FLOW_ML_PER_S: f32 = 0.0;

/// Upper bound for any flow setpoint (ml/s) — same `u8p4` wire encoding
/// as pressure.
pub const MAX_FLOW_ML_PER_S: f32 = 15.9375;

// ── Temperature ─────────────────────────────────────────────────────────

/// Lower bound for any temperature setpoint (°C). The firmware doesn't
/// reject 0 °C; it just won't heat. Useful as a "no override" marker
/// (e.g. `tank_temperature_c == 0` means "keep current setpoint").
pub const MIN_TEMPERATURE_C: f32 = 0.0;

/// Upper bound for any temperature setpoint (°C). The DE1's heater
/// hardware tops out around 120 °C (Steam Eco) but the wire format
/// accepts up to 127.5 (`U8P1`, half-degree steps); we use the same conservative
/// ceiling the legacy de1app applies for brew + tank fields. Steam
/// targets above 100 °C are normal — see [`MAX_STEAM_TEMPERATURE_C`].
pub const MAX_TEMPERATURE_C: f32 = 100.0;

/// Upper bound for *steam* temperature setpoints — boiler-side, can
/// exceed brew temperature. Matches the de1app's editorial cap.
pub const MAX_STEAM_TEMPERATURE_C: f32 = 170.0;

// ── Time ────────────────────────────────────────────────────────────────

/// Maximum profile-frame duration (seconds). The wire encoding is `F8_1_7`
/// (tenths below 12.75 s, whole seconds with the high bit set above), whose
/// ceiling is 127 s (de1app `convert_float_to_F8_1_7`). Pour steps of
/// 30–60 s are common.
pub const MAX_FRAME_SECONDS: f32 = 127.0;

/// Minimum profile-frame duration. Zero-second frames are legal (used
/// for instantaneous setpoint changes) but a real frame is ≥ 0.1 s.
pub const MIN_FRAME_SECONDS: f32 = 0.0;

// ── Pre-infusion ────────────────────────────────────────────────────────

/// Maximum pre-infusion step count — must be ≤ the profile's own step
/// count, but the absolute wire ceiling is [`MAX_PROFILE_STEPS`].
pub const MAX_PREINFUSE_STEPS: u8 = MAX_PROFILE_STEPS;

// ── Serialised snapshot ───────────────────────────────────────────────────

/// Serialise every bound above into one JSON object, so each shell parses
/// the same numbers from a single source. The web shell reaches this via the
/// wasm `profile_bounds_json`, Android via the UniFFI export — both delegate
/// here, so the two shells can never drift. Keys mirror the constant names
/// (snake_case) to let a reader pattern-match either way. The shell parses
/// the result once at module load — these are constants, not live values.
///
/// Hand-rolled rather than serde-derived to keep this module free of a
/// serde-of-statics struct; the field list is small and stable.
pub fn profile_bounds_json() -> String {
    format!(
        "{{\"max_profile_steps\":{},\"max_total_volume_ml\":{},\
         \"min_total_volume_ml\":{},\"min_pressure_bar\":{},\
         \"max_pressure_bar\":{},\"min_flow_ml_per_s\":{},\
         \"max_flow_ml_per_s\":{},\"min_temperature_c\":{},\
         \"max_temperature_c\":{},\"max_steam_temperature_c\":{},\
         \"min_frame_seconds\":{},\"max_frame_seconds\":{},\
         \"max_preinfuse_steps\":{}}}",
        MAX_PROFILE_STEPS,
        MAX_TOTAL_VOLUME_ML,
        MIN_TOTAL_VOLUME_ML,
        MIN_PRESSURE_BAR,
        MAX_PRESSURE_BAR,
        MIN_FLOW_ML_PER_S,
        MAX_FLOW_ML_PER_S,
        MIN_TEMPERATURE_C,
        MAX_TEMPERATURE_C,
        MAX_STEAM_TEMPERATURE_C,
        MIN_FRAME_SECONDS,
        MAX_FRAME_SECONDS,
        MAX_PREINFUSE_STEPS
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounds_json_is_valid_and_carries_the_caps() {
        let v: serde_json::Value =
            serde_json::from_str(&profile_bounds_json()).expect("valid JSON");
        // The shells key off snake_case names; spot-check the caps that the
        // editors clamp against so a constant edit can't silently drift them.
        assert_eq!(v["max_profile_steps"], MAX_PROFILE_STEPS);
        assert_eq!(v["max_total_volume_ml"], MAX_TOTAL_VOLUME_ML);
        assert_eq!(v["max_pressure_bar"], MAX_PRESSURE_BAR);
        assert_eq!(v["max_flow_ml_per_s"], MAX_FLOW_ML_PER_S);
        assert_eq!(v["max_temperature_c"], MAX_TEMPERATURE_C);
        assert_eq!(v["max_frame_seconds"], MAX_FRAME_SECONDS);
        assert_eq!(v.as_object().map(|o| o.len()), Some(13));
    }

    #[test]
    fn the_caps_match_the_reference_apps() {
        // Cohort review 02 P1-1 / P2-4: de1app and pyDE1 cap a profile at 20
        // frames; F8_1_7 frame time reaches 127 s.
        assert_eq!(MAX_PROFILE_STEPS, 20);
        assert!((MAX_FRAME_SECONDS - 127.0).abs() < f32::EPSILON);
        // The frame-time ceiling round-trips through the wire codec.
        let encoded = de1_protocol::fixed_point::f8_1_7_encode(MAX_FRAME_SECONDS);
        assert_eq!(encoded, 0x80 | 127);
    }
}

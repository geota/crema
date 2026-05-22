//! Wire-protocol bounds for DE1 profile fields.
//!
//! These are the **hard** limits the firmware will accept — driven by the
//! DE1's wire-format encodings (10-bit volume, u8 step count, 4-bit
//! `u4p4` pressure / flow / temperature precision-pairs etc.). Editorial /
//! UX-friendly ranges (e.g. "warn above 95 °C brew temp") stay in each
//! shell; this module is the canonical reject-or-accept truth.
//!
//! Centralised in `de1-domain` so the web shell, Android shell, and any
//! profile validator all reach for the same numbers — push #11 from
//! `docs/26-shell-to-core-audit.md`.

// ── Step count ──────────────────────────────────────────────────────────

/// Maximum step count in a profile — the DE1 firmware accepts `0..=32`
/// frames. Mirrors `profile::MAX_STEPS`.
pub const MAX_PROFILE_STEPS: u8 = 32;

// ── Volume ──────────────────────────────────────────────────────────────

/// Maximum total volume (mL) the DE1 will dispense in one shot. The
/// limit is the 10-bit wire field used in `HeaderWrite` (`cuuid_0F`,
/// `max_total_volume`), so writes above this saturate at 1023.
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

/// Lower bound for any flow setpoint (mL/s).
pub const MIN_FLOW_ML_PER_S: f32 = 0.0;

/// Upper bound for any flow setpoint (mL/s) — same `u8p4` wire encoding
/// as pressure.
pub const MAX_FLOW_ML_PER_S: f32 = 15.9375;

// ── Temperature ─────────────────────────────────────────────────────────

/// Lower bound for any temperature setpoint (°C). The firmware doesn't
/// reject 0 °C; it just won't heat. Useful as a "no override" marker
/// (e.g. `tank_temperature_c == 0` means "keep current setpoint").
pub const MIN_TEMPERATURE_C: f32 = 0.0;

/// Upper bound for any temperature setpoint (°C). The DE1's heater
/// hardware tops out around 120 °C (Steam Eco) but the wire format
/// accepts up to 127.99 (`u8p4` signed); we use the same conservative
/// ceiling the legacy de1app applies for brew + tank fields. Steam
/// targets above 100 °C are normal — see [`MAX_STEAM_TEMPERATURE_C`].
pub const MAX_TEMPERATURE_C: f32 = 100.0;

/// Upper bound for *steam* temperature setpoints — boiler-side, can
/// exceed brew temperature. Matches the de1app's editorial cap.
pub const MAX_STEAM_TEMPERATURE_C: f32 = 170.0;

// ── Time ────────────────────────────────────────────────────────────────

/// Maximum profile-frame duration (seconds). Wire encoding is `u8p4`
/// (4-bit fractional), so 25.5 s is the actual ceiling — but a single
/// 25 s frame is already extreme; most frames are <10 s.
pub const MAX_FRAME_SECONDS: f32 = 25.5;

/// Minimum profile-frame duration. Zero-second frames are legal (used
/// for instantaneous setpoint changes) but a real frame is ≥ 0.1 s.
pub const MIN_FRAME_SECONDS: f32 = 0.0;

// ── Pre-infusion ────────────────────────────────────────────────────────

/// Maximum pre-infusion step count — must be ≤ the profile's own step
/// count, but the absolute wire ceiling is [`MAX_PROFILE_STEPS`].
pub const MAX_PREINFUSE_STEPS: u8 = MAX_PROFILE_STEPS;

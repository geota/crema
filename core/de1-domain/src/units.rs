//! Unit-conversion math for Crema's four user-facing measurement dimensions
//! — weight (g ↔ oz), temperature (°C ↔ °F), pressure (bar ↔ psi), and
//! volume (ml ↔ US fl oz).
//!
//! Each conversion is a pure `f32 → f32` with no UI, locale, or formatting
//! concerns. The shell owns the per-screen pieces — the unit label string,
//! the decimal precision, the `—` placeholder for a missing value — and
//! reaches here only when it needs the number. The same constants every
//! shell trusts (`1 oz = 28.3495 g`, `1 bar = 14.5038 psi`, `1 US fl oz
//! = 29.5735 ml`) live exactly once.
//!
//! Each conversion has a sibling inverse for editable-input round-trips
//! (a stepper showing oz commits canonical grams; a stepper showing °F
//! commits canonical °C). The pair is exact under f32 arithmetic for
//! reasonable inputs — see the round-trip tests — though one-way ULP
//! noise from the conversion factors is expected for extreme inputs.

use typeshare::typeshare;

// ─── Weight: grams ↔ ounces ────────────────────────────────────────────────

/// The user's chosen *display unit* for weight.
///
/// Canonical storage is always grams; this enum names the unit the shell
/// surfaces — and crucially, the unit the Decent Scale's on-scale LCD must
/// be told to render. The Decent Scale exposes two LCD-enable wire packets
/// (one with byte `[4] = 0x00` for grams, one with byte `[4] = 0x01` for
/// ounces); the core picks the right packet based on this enum so the
/// on-scale display matches the shell.
///
/// `#[typeshare]` + serde: the web shell already keeps a TypeScript
/// `WeightUnit` (`'g' | 'oz'`) in its settings store; carrying the same
/// shape through the bridge means the shell can pass its existing pref
/// straight through without a lookup table.
#[typeshare]
#[derive(
    Debug, Default, Clone, Copy, PartialEq, Eq, Hash, serde::Serialize, serde::Deserialize,
)]
#[serde(rename_all = "lowercase")]
pub enum WeightUnit {
    /// Grams — the canonical unit and the Decent Scale's default LCD mode.
    #[default]
    Grams,
    /// US avoirdupois ounces — the only imperial display alternative.
    Ounces,
}

impl WeightUnit {
    /// Parse a lowercase wire string (`"grams"` / `"ounces"`) — the same
    /// shape the serde representation uses. Returns `None` for an
    /// unrecognised input.
    ///
    /// Provided as a bridge-friendly entry point: the wasm and JNI shims
    /// receive a `&str` from the JS / Kotlin side and need a no-allocation
    /// way to recover the enum without depending on serde-json or
    /// reflection.
    #[must_use]
    pub fn from_str_lower(s: &str) -> Option<Self> {
        match s {
            "grams" => Some(Self::Grams),
            "ounces" => Some(Self::Ounces),
            _ => None,
        }
    }
}

/// Grams in one international avoirdupois ounce — the inverse of
/// [`GRAMS_PER_OZ`]'s reciprocal, kept as a separate constant so each
/// direction is a single multiply (no divide latency, no reciprocal
/// rounding asymmetry beyond the constants' own precision).
const GRAMS_PER_OZ: f32 = 28.3495;

/// Ounces in one gram. Reciprocal of [`GRAMS_PER_OZ`], pinned at the same
/// precision the shell historically used so existing readouts don't shift
/// in the last decimal.
const OZ_PER_GRAM: f32 = 0.035274;

/// Convert a weight in grams to ounces (`g × 0.035274`).
pub fn grams_to_oz(grams: f32) -> f32 {
    grams * OZ_PER_GRAM
}

/// Convert a weight in ounces to grams (`oz × 28.3495`).
pub fn oz_to_grams(oz: f32) -> f32 {
    oz * GRAMS_PER_OZ
}

// ─── Temperature: Celsius ↔ Fahrenheit ─────────────────────────────────────

/// Convert a temperature in Celsius to Fahrenheit (`°C × 9/5 + 32`).
pub fn celsius_to_fahrenheit(celsius: f32) -> f32 {
    celsius * 1.8 + 32.0
}

/// Convert a temperature in Fahrenheit to Celsius (`(°F − 32) × 5/9`).
pub fn fahrenheit_to_celsius(fahrenheit: f32) -> f32 {
    (fahrenheit - 32.0) / 1.8
}

// ─── Pressure: bar ↔ psi ───────────────────────────────────────────────────

/// psi in one bar. The DE1 reports pressure in bar; psi is a US-customary
/// display choice. Kept at the shell's historical precision.
const PSI_PER_BAR: f32 = 14.5038;

/// bar in one psi. Reciprocal of [`PSI_PER_BAR`].
const BAR_PER_PSI: f32 = 0.0689476;

/// Convert a pressure in bar to psi (`bar × 14.5038`).
pub fn bar_to_psi(bar: f32) -> f32 {
    bar * PSI_PER_BAR
}

/// Convert a pressure in psi to bar (`psi × 0.0689476`).
pub fn psi_to_bar(psi: f32) -> f32 {
    psi * BAR_PER_PSI
}

// ─── Volume: ml ↔ US fluid ounces ──────────────────────────────────────────

/// ml in one US fluid ounce. Crema's canonical volume unit is ml; US fl
/// oz is the only imperial alternative the shell offers.
const ML_PER_FL_OZ: f32 = 29.5735;

/// US fl oz in one ml. Reciprocal of [`ML_PER_FL_OZ`].
const FL_OZ_PER_ML: f32 = 0.033814;

/// Convert a volume in ml to US fluid ounces (`ml × 0.033814`).
pub fn ml_to_fl_oz(ml: f32) -> f32 {
    ml * FL_OZ_PER_ML
}

/// Convert a volume in US fluid ounces to ml (`fl oz × 29.5735`).
pub fn fl_oz_to_ml(fl_oz: f32) -> f32 {
    fl_oz * ML_PER_FL_OZ
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Allow a few ULPs of f32 slack on round-trip checks — the
    /// shell-historical constants are not exact reciprocals, so a strict
    /// equality would over-constrain the contract.
    const EPSILON: f32 = 1e-3;

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() <= EPSILON * a.abs().max(b.abs()).max(1.0)
    }

    // ── Weight ─────────────────────────────────────────────────────────────

    #[test]
    fn weight_standard_values() {
        // 1 oz = 28.3495 g; 18 g (typical dose) ≈ 0.6349 oz.
        assert!(close(oz_to_grams(1.0), 28.3495));
        assert!(close(grams_to_oz(28.3495), 1.0));
        assert!(close(grams_to_oz(18.0), 0.634932));
    }

    #[test]
    fn weight_round_trip() {
        for &g in &[0.0_f32, 1.0, 18.0, 36.0, 250.0] {
            assert!(close(oz_to_grams(grams_to_oz(g)), g));
        }
    }

    #[test]
    fn weight_zero_is_zero() {
        assert_eq!(grams_to_oz(0.0), 0.0);
        assert_eq!(oz_to_grams(0.0), 0.0);
    }

    #[test]
    fn weight_unit_serializes_to_lowercase_grams_or_ounces() {
        // The web shell already keeps its pref as the lowercase strings
        // `"grams"` / `"ounces"`; the wire-format must match so the shell
        // can pass its existing settings value straight through the bridge
        // without a lookup table.
        let g = serde_json::to_string(&WeightUnit::Grams).unwrap();
        let o = serde_json::to_string(&WeightUnit::Ounces).unwrap();
        assert_eq!(g, "\"grams\"");
        assert_eq!(o, "\"ounces\"");
    }

    #[test]
    fn weight_unit_defaults_to_grams() {
        // Grams is the canonical unit — every numeric channel in the core
        // stores grams — so the default is also Grams.
        assert_eq!(WeightUnit::default(), WeightUnit::Grams);
    }

    // ── Temperature ────────────────────────────────────────────────────────

    #[test]
    fn temp_standard_values() {
        assert!(close(celsius_to_fahrenheit(0.0), 32.0));
        assert!(close(celsius_to_fahrenheit(100.0), 212.0));
        assert!(close(celsius_to_fahrenheit(93.0), 199.4)); // typical brew temp
        assert!(close(fahrenheit_to_celsius(32.0), 0.0));
        assert!(close(fahrenheit_to_celsius(212.0), 100.0));
    }

    #[test]
    fn temp_round_trip() {
        for &c in &[-40.0_f32, 0.0, 20.0, 93.0, 100.0] {
            assert!(close(fahrenheit_to_celsius(celsius_to_fahrenheit(c)), c));
        }
    }

    #[test]
    fn temp_minus_40_is_fixed_point() {
        // The classic identity: −40 °C = −40 °F.
        assert!(close(celsius_to_fahrenheit(-40.0), -40.0));
        assert!(close(fahrenheit_to_celsius(-40.0), -40.0));
    }

    // ── Pressure ───────────────────────────────────────────────────────────

    #[test]
    fn pressure_standard_values() {
        assert!(close(bar_to_psi(1.0), 14.5038));
        assert!(close(bar_to_psi(9.0), 130.5342)); // typical espresso pressure
        assert!(close(psi_to_bar(14.5038), 1.0));
    }

    #[test]
    fn pressure_round_trip() {
        for &b in &[0.0_f32, 1.0, 6.0, 9.0, 12.0] {
            assert!(close(psi_to_bar(bar_to_psi(b)), b));
        }
    }

    #[test]
    fn pressure_zero_is_zero() {
        assert_eq!(bar_to_psi(0.0), 0.0);
        assert_eq!(psi_to_bar(0.0), 0.0);
    }

    // ── Volume ─────────────────────────────────────────────────────────────

    #[test]
    fn volume_standard_values() {
        assert!(close(fl_oz_to_ml(1.0), 29.5735));
        assert!(close(ml_to_fl_oz(29.5735), 1.0));
        // A 1.5 L water tank → 50.72 fl oz.
        assert!(close(ml_to_fl_oz(1500.0), 50.721));
    }

    #[test]
    fn volume_round_trip() {
        for &ml in &[0.0_f32, 30.0, 250.0, 1500.0, 2058.0] {
            assert!(close(fl_oz_to_ml(ml_to_fl_oz(ml)), ml));
        }
    }

    #[test]
    fn volume_zero_is_zero() {
        assert_eq!(ml_to_fl_oz(0.0), 0.0);
        assert_eq!(fl_oz_to_ml(0.0), 0.0);
    }
}

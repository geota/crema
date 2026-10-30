//! # bounds
//!
//! The editorial ranges both shells' steppers and editors use — Quick
//! Controls, Settings → Brew defaults, and the profile editor — in one table,
//! and the core's enforcement of them. Each entry is a [`StepRange`] (the
//! hot-water modes' type, [`crate::hot_water`]); chips stay empty here because
//! the per-layout chip arrays are a shell concern (tablet and phone differ on
//! purpose).
//!
//! Hot water (temperature and dose) is not here: [`crate::hot_water_bounds`]
//! owns it. The brew-temperature range is F6's ([`crate::brew_temp_range`]
//! per profile, [`MIN_BREW_TEMPERATURE_C`]..[`MAX_TEMPERATURE_C`] outside one).
//!
//! These sit inside the wire limits of [`crate::profile_bounds`]. The profile
//! editor's ranges apply when the user edits a field; a stored or imported
//! profile with a value outside them is never rewritten (only edits clamp).

use crate::effective_profile::PREINFUSION_DIAL_MAX_S;
use crate::hot_water::StepRange;
use crate::profile_bounds::{
    MAX_FRAME_SECONDS, MAX_STEAM_TEMPERATURE_C, MAX_TEMPERATURE_C, MAX_TOTAL_VOLUME_ML,
    MIN_BREW_TEMPERATURE_C,
};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// The lowest steam temperature the steppers offer, °C. The DE1 firmware
/// snaps anything below it to 0 (heater off); `0` itself is always allowed as
/// "steam off".
pub const STEAM_TEMP_MIN_C: f32 = 135.0;

const fn range(min: f32, max: f32, step: f32) -> StepRange {
    StepRange {
        min,
        max,
        step,
        chips: Vec::new(),
    }
}

impl StepRange {
    /// `value` clamped into `[min, max]`, or `fallback` when it isn't finite.
    #[must_use]
    pub fn clamp_or(&self, value: f32, fallback: f32) -> f32 {
        if value.is_finite() {
            value.clamp(self.min, self.max)
        } else {
            fallback
        }
    }

    /// Whether `value` is finite and inside `[min, max]`.
    #[must_use]
    pub fn contains(&self, value: f32) -> bool {
        value.is_finite() && value >= self.min && value <= self.max
    }
}

/// The Quick Controls steppers' ranges (hot water excepted).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuickControlBounds {
    /// Dose, g.
    pub dose_g: StepRange,
    /// Grind setting.
    pub grind: StepRange,
    /// Yield, g.
    pub yield_g: StepRange,
    /// Yield-to-dose ratio (the `x` in `1:x`).
    pub ratio: StepRange,
    /// Brew temperature, °C, outside any profile; with a profile the dial
    /// uses [`crate::brew_temp_range`].
    pub brew_temp_c: StepRange,
    /// Pre-infusion, s, outside any profile; with a profile the dial uses
    /// [`crate::preinfusion_dial`].
    pub preinfuse_s: StepRange,
    /// Steam duration, s.
    pub steam_time_s: StepRange,
    /// Steam flow, ml/s.
    pub steam_flow_ml_s: StepRange,
    /// Steam temperature, °C, when on; `0` (off) is always allowed.
    pub steam_temp_c: StepRange,
    /// Group-flush duration, s.
    pub flush_time_s: StepRange,
    /// Group-flush temperature, °C.
    pub flush_temp_c: StepRange,
    /// The yield a brew override may carry, g: the yield stepper's range
    /// widened to what the ratio stepper reaches (`dose × ratio`, Android's
    /// ratio mode), so an override the Quick Controls can produce is never
    /// cut. Not a stepper; the yield stepper keeps `yield_g`.
    pub override_yield_g: StepRange,
}

/// Settings → Brew defaults' ranges (the seeds for new profiles).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewDefaultsBounds {
    /// Default dose, g.
    pub dose_g: StepRange,
    /// Default ratio.
    pub ratio: StepRange,
    /// Default brew temperature, °C.
    pub brew_temp_c: StepRange,
    /// Default pre-infusion, s.
    pub preinfuse_s: StepRange,
}

/// The profile editor's ranges: applied when the user edits a field, never
/// to rewrite a stored or imported profile.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProfileEditorBounds {
    /// A step's temperature, °C.
    pub segment_temp_c: StepRange,
    /// A pressure target, limiter or exit, bar.
    pub pressure_bar: StepRange,
    /// A flow target, limiter or exit, ml/s.
    pub flow_ml_s: StepRange,
    /// A step's duration, s.
    pub segment_time_s: StepRange,
    /// A step's volume limit, ml (`0` = off).
    pub segment_volume_ml: StepRange,
    /// The limiter's range.
    pub limiter_range: StepRange,
    /// The profile's dose, g.
    pub dose_g: StepRange,
    /// The profile's yield, g.
    pub yield_g: StepRange,
    /// The profile's total-volume cap, ml (`0` = off).
    pub max_total_volume_ml: StepRange,
    /// The tank-temperature override, °C (`0` = keep the machine's).
    pub tank_temp_c: StepRange,
}

/// The Quick Controls table.
#[must_use]
pub fn quick_control_bounds() -> QuickControlBounds {
    let dose_g = range(5.0, 30.0, 0.1);
    let yield_g = range(10.0, 80.0, 0.5);
    let ratio = range(1.0, 5.0, 0.1);
    let override_yield_g = range(
        yield_g.min.min(dose_g.min * ratio.min),
        yield_g.max.max(dose_g.max * ratio.max),
        yield_g.step,
    );
    QuickControlBounds {
        dose_g,
        grind: range(0.0, 20.0, 0.1),
        yield_g,
        ratio,
        brew_temp_c: range(MIN_BREW_TEMPERATURE_C, MAX_TEMPERATURE_C, 0.5),
        preinfuse_s: range(0.0, PREINFUSION_DIAL_MAX_S, 1.0),
        steam_time_s: range(1.0, 60.0, 1.0),
        steam_flow_ml_s: range(0.2, 3.0, 0.1),
        steam_temp_c: range(STEAM_TEMP_MIN_C, MAX_STEAM_TEMPERATURE_C, 0.5),
        flush_time_s: range(1.0, 20.0, 1.0),
        flush_temp_c: range(60.0, 100.0, 0.5),
        override_yield_g,
    }
}

/// The Brew defaults table.
#[must_use]
pub fn brew_defaults_bounds() -> BrewDefaultsBounds {
    BrewDefaultsBounds {
        dose_g: range(5.0, 30.0, 0.1),
        ratio: range(1.0, 5.0, 0.1),
        brew_temp_c: range(MIN_BREW_TEMPERATURE_C, MAX_TEMPERATURE_C, 0.5),
        preinfuse_s: range(0.0, 60.0, 1.0),
    }
}

/// The profile editor table (de1app's editor values where it has one).
#[must_use]
pub fn profile_editor_bounds() -> ProfileEditorBounds {
    ProfileEditorBounds {
        segment_temp_c: range(0.0, MAX_TEMPERATURE_C, 0.5),
        pressure_bar: range(0.0, 12.0, 0.1),
        flow_ml_s: range(0.0, 12.0, 0.1),
        segment_time_s: range(0.0, MAX_FRAME_SECONDS, 0.5),
        segment_volume_ml: range(0.0, f32::from(MAX_TOTAL_VOLUME_ML), 5.0),
        limiter_range: range(0.0, 6.0, 0.1),
        dose_g: range(5.0, 30.0, 0.1),
        yield_g: range(0.0, 2000.0, 0.5),
        max_total_volume_ml: range(0.0, f32::from(MAX_TOTAL_VOLUME_ML), 5.0),
        tank_temp_c: range(0.0, 45.0, 1.0),
    }
}

/// A steam temperature as the stepper and the machine take it: `0` stays off,
/// `1..135` snaps to `0` (what the firmware does with it), above the range is
/// clamped to the top, and a non-finite value is `fallback`.
#[must_use]
pub fn clamp_steam_temp(value: f32, fallback: f32) -> f32 {
    let r = quick_control_bounds().steam_temp_c;
    if !value.is_finite() {
        fallback
    } else if value < r.min {
        0.0
    } else {
        value.min(r.max)
    }
}

/// [`quick_control_bounds`] as JSON.
#[must_use]
pub fn quick_control_bounds_json() -> String {
    serde_json::to_string(&quick_control_bounds()).unwrap_or_default()
}

/// [`brew_defaults_bounds`] as JSON.
#[must_use]
pub fn brew_defaults_bounds_json() -> String {
    serde_json::to_string(&brew_defaults_bounds()).unwrap_or_default()
}

/// [`profile_editor_bounds`] as JSON.
#[must_use]
pub fn profile_editor_bounds_json() -> String {
    serde_json::to_string(&profile_editor_bounds()).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profile_bounds::{MAX_FLOW_ML_PER_S, MAX_PRESSURE_BAR, MIN_TEMPERATURE_C};

    #[test]
    fn quick_control_bounds_match_the_documented_ranges() {
        let q = quick_control_bounds();
        let pairs = [
            (&q.dose_g, 5.0, 30.0),
            (&q.grind, 0.0, 20.0),
            (&q.yield_g, 10.0, 80.0),
            (&q.ratio, 1.0, 5.0),
            (&q.brew_temp_c, MIN_BREW_TEMPERATURE_C, MAX_TEMPERATURE_C),
            (&q.preinfuse_s, 0.0, PREINFUSION_DIAL_MAX_S),
            (&q.steam_time_s, 1.0, 60.0),
            (&q.steam_flow_ml_s, 0.2, 3.0),
            (&q.steam_temp_c, 135.0, 170.0),
            (&q.flush_time_s, 1.0, 20.0),
            (&q.flush_temp_c, 60.0, 100.0),
        ];
        for (r, min, max) in pairs {
            assert_eq!((r.min, r.max), (min, max));
            assert!(r.step > 0.0 && r.chips.is_empty());
        }
        let b = brew_defaults_bounds();
        assert_eq!((b.preinfuse_s.min, b.preinfuse_s.max), (0.0, 60.0));
        assert_eq!(b.brew_temp_c, q.brew_temp_c);
    }

    #[test]
    fn editor_bounds_sit_inside_the_wire_limits() {
        let e = profile_editor_bounds();
        let inside = |r: &StepRange, lo: f32, hi: f32| r.min >= lo && r.max <= hi && r.min < r.max;
        assert!(inside(
            &e.segment_temp_c,
            MIN_TEMPERATURE_C,
            MAX_TEMPERATURE_C
        ));
        assert!(inside(&e.pressure_bar, 0.0, MAX_PRESSURE_BAR));
        assert!(inside(&e.flow_ml_s, 0.0, MAX_FLOW_ML_PER_S));
        assert!(inside(&e.segment_time_s, 0.0, MAX_FRAME_SECONDS));
        assert!(inside(
            &e.segment_volume_ml,
            0.0,
            f32::from(MAX_TOTAL_VOLUME_ML)
        ));
        assert!(inside(
            &e.max_total_volume_ml,
            0.0,
            f32::from(MAX_TOTAL_VOLUME_ML)
        ));
        assert!(inside(&e.tank_temp_c, MIN_TEMPERATURE_C, MAX_TEMPERATURE_C));
        let q = quick_control_bounds();
        for r in [&q.brew_temp_c, &q.flush_temp_c] {
            assert!(inside(r, MIN_TEMPERATURE_C, MAX_TEMPERATURE_C));
        }
        assert!(inside(
            &q.steam_temp_c,
            MIN_TEMPERATURE_C,
            MAX_STEAM_TEMPERATURE_C
        ));
    }

    #[test]
    fn steam_temp_keeps_off_snaps_low_values_off_and_clamps_high() {
        assert_eq!(clamp_steam_temp(0.0, 148.0), 0.0);
        assert_eq!(clamp_steam_temp(120.0, 148.0), 0.0);
        assert_eq!(clamp_steam_temp(140.0, 148.0), 140.0);
        assert_eq!(clamp_steam_temp(200.0, 148.0), 170.0);
        assert_eq!(clamp_steam_temp(f32::NAN, 148.0), 148.0);
        assert_eq!(clamp_steam_temp(-5.0, 148.0), 0.0);
    }

    #[test]
    fn bounds_json_round_trips() {
        let q: QuickControlBounds = serde_json::from_str(&quick_control_bounds_json()).unwrap();
        assert_eq!(q, quick_control_bounds());
        let b: BrewDefaultsBounds = serde_json::from_str(&brew_defaults_bounds_json()).unwrap();
        assert_eq!(b, brew_defaults_bounds());
        let e: ProfileEditorBounds = serde_json::from_str(&profile_editor_bounds_json()).unwrap();
        assert_eq!(e, profile_editor_bounds());
    }

    /// The editor ranges apply only when a field is edited: a stored or
    /// imported profile outside them comes through loading, the effective
    /// profile and the wire untouched.
    #[test]
    fn loading_an_out_of_range_profile_changes_nothing() {
        let mut p = crate::blank_profile(&crate::BrewDefaults::default());
        p.yield_out = 2500.0; // above the editor's 2000
        p.tank_temperature_c = 60.0; // above the editor's 45
        p.segments[0].target = 14.0; // above the editor's 12, inside the wire's 15.94
        let json = serde_json::to_string(&p).unwrap();
        let loaded: crate::CremaProfile = serde_json::from_str(&json).unwrap();
        assert_eq!(loaded, p);
        let effective = crate::effective_crema_profile(&loaded, &crate::BrewOverrides::default());
        assert_eq!(effective, p);
        let e = profile_editor_bounds();
        assert!(!e.yield_g.contains(p.yield_out) && !e.tank_temp_c.contains(p.tank_temperature_c));
        assert!(!e.pressure_bar.contains(p.segments[0].target));
    }

    #[test]
    fn override_yield_covers_the_ratio_stepper() {
        let q = quick_control_bounds();
        let r = &q.override_yield_g;
        assert_eq!((r.min, r.max), (5.0, 150.0));
        assert_eq!(
            (q.yield_g.min, q.yield_g.max),
            (10.0, 80.0),
            "the stepper keeps its range"
        );
    }
}

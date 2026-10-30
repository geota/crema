//! Hot-water dose modes: time, volume, or a ratio of the shot.
//!
//! The user sets a **dose**: a mode plus one value per mode (switching modes
//! keeps each value). [`resolve_hot_water`] turns the dose and the shot
//! context into the [`HotWaterTarget`]: what Crema pours, what goes into the
//! DE1's steam / hot-water packet (cuuid_0B), and the weight to stop at. Both
//! shells use it through the core; neither computes a volume.
//!
//! | Mode | No operational scale: volume, timeout | Operational scale | Who stops it |
//! |---|---|---|---|
//! | Time | [`HotWaterFirmware::time_mode_wire_volume_ml`] / N s | the same | the DE1, at N s |
//! | Volume | V ml / 60 s | 250 ml / 60 s | the DE1 at V ml, or the core at V g |
//! | Ratio | R ml / 60 s | 250 ml / 60 s | as Volume, R = ratio × target yield |
//!
//! - **Ratio** is applied to the next shot's target yield (the Quick Controls
//!   override, else the profile's target weight), rounded half away from
//!   zero to whole ml like `u8p0_encode`, and clamped to 20–250 ml. With no
//!   target yield it pours the volume-mode value.
//! - **Volume and ratio** are capped at 250 ml: the wire field is a `u8`, and
//!   with a scale the DE1 is asked for the 250 ml backstop (de1app
//!   `binary.tcl:207-215`). The 60 s timeout is a backstop too (de1app
//!   `water_time_max`, Decenza's hard-coded 60).
//! - **Time** is the only stop in time mode: volume 0 means no volume stop
//!   (Decenza's weight mode sends 0; reaprime's slider starts at 0).
//! - 1 ml of water counts as 1 g, as in de1app, Decenza and reaprime.
//!
//! The hardware facts a DE1 run settles live in one injected
//! [`HotWaterFirmware`], so both branches stay unit-testable.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// Most ml Crema pours in volume and ratio modes (the `u8` wire field, and
/// the backstop it asks for with a scale).
pub const HOT_WATER_MAX_ML: f32 = 250.0;
/// Least ml Crema pours in volume and ratio modes.
pub const HOT_WATER_MIN_ML: f32 = 20.0;
/// The packet timeout in volume and ratio modes, s: a backstop (de1app
/// `water_time_max`, Decenza `de1device.cpp:2767`).
pub const HOT_WATER_BACKSTOP_TIMEOUT_S: f32 = 60.0;
/// Shortest time-mode pour, s.
pub const HOT_WATER_TIME_MIN_S: f32 = 5.0;
/// The old fixed hot-water volume default, ml. A stored 150 with no mode is
/// read as "never changed" ([`initial_hot_water_dose`]).
pub const HOT_WATER_OLD_DEFAULT_ML: f32 = 150.0;
/// Hot-water volume, ml, requested when an operational scale does the
/// stopping: the legacy app asks for far more water than wanted so the
/// scale's weight stop is what cuts the pour (`binary.tcl`
/// `return_de1_packed_steam_hotwater_settings`).
pub const HOT_WATER_STOP_ON_SCALE_ML: f32 = 250.0;
/// The default ratio: water per gram of shot (an americano at 1:3).
pub const HOT_WATER_DEFAULT_RATIO: f32 = 3.0;
/// The default volume-mode value, ml.
pub const HOT_WATER_DEFAULT_VOLUME_ML: f32 = 150.0;
/// Ratio range and step.
pub const HOT_WATER_RATIO_MIN: f32 = 0.5;
/// See [`HOT_WATER_RATIO_MIN`].
pub const HOT_WATER_RATIO_MAX: f32 = 6.0;
const RATIO_STEP: f32 = 0.5;
const VOLUME_STEP_ML: f32 = 10.0;
const TIME_STEP_S: f32 = 5.0;

/// The firmware facts the core passes. The hardware run edits this one
/// const: volume 0 failing → `{255, None, 25, 20}`; a timeout value that
/// pours until stopped → `no_limit_timeout: Some(v)`; a measured cap →
/// `time_max_s`.
pub const HOT_WATER_FIRMWARE: HotWaterFirmware = HotWaterFirmware {
    time_mode_wire_volume_ml: 0,
    no_limit_timeout: None,
    time_max_s: 255,
    time_default_s: 30,
};

/// How the hot-water amount is set.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum HotWaterMode {
    /// Pour for a number of seconds.
    Time,
    /// Pour a number of ml.
    Volume,
    /// Pour a multiple of the shot's target yield.
    Ratio,
}

/// What the user set: the mode plus every mode's value, so switching modes
/// brings each value back.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HotWaterDose {
    /// The persisted mode.
    pub mode: HotWaterMode,
    /// Time-mode seconds.
    pub time_s: f32,
    /// Time mode pours until stopped. Honoured only when
    /// [`HotWaterFirmware::no_limit_timeout`] is `Some`; `time_s` is kept so
    /// turning it off restores the last time.
    pub time_no_limit: bool,
    /// Volume-mode ml.
    pub volume_ml: f32,
    /// Ratio-mode water per gram of shot.
    pub ratio: f32,
}

impl HotWaterDose {
    /// The default dose: ratio 1:3, with the default time and 150 ml.
    #[must_use]
    pub fn defaults(fw: &HotWaterFirmware) -> HotWaterDose {
        HotWaterDose {
            mode: HotWaterMode::Ratio,
            time_s: f32::from(fw.time_default_s),
            time_no_limit: false,
            volume_ml: HOT_WATER_DEFAULT_VOLUME_ML,
            ratio: HOT_WATER_DEFAULT_RATIO,
        }
    }
}

impl Default for HotWaterDose {
    fn default() -> Self {
        HotWaterDose::defaults(&HOT_WATER_FIRMWARE)
    }
}

/// What the stored settings hold, every field optional: the input to the
/// migration ([`initial_hot_water_dose`]).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct StoredHotWater {
    /// The stored mode; `None` before the modes existed.
    pub mode: Option<HotWaterMode>,
    /// The stored volume, ml.
    pub volume_ml: Option<f32>,
    /// The stored time, s.
    pub time_s: Option<f32>,
    /// The stored No limit flag.
    pub time_no_limit: Option<bool>,
    /// The stored ratio.
    pub ratio: Option<f32>,
}

/// The hardware facts a DE1 run settles, injected so both branches are
/// unit-testable. The core passes [`HOT_WATER_FIRMWARE`].
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HotWaterFirmware {
    /// The packet volume in time mode: 0 = no volume stop; 255 if 0 doesn't
    /// disable it.
    pub time_mode_wire_volume_ml: u8,
    /// A timeout value that pours until stopped, if the firmware has one.
    pub no_limit_timeout: Option<u8>,
    /// The longest time-mode pour, s: 255 (the `u8` field), 25 when volume 0
    /// fails, or a measured firmware cap.
    pub time_max_s: u8,
    /// The default time, s: 30 (reaprime), 20 when volume 0 fails.
    pub time_default_s: u8,
}

/// The shot context the ratio and the stop depend on.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct HotWaterContext {
    /// The next shot's target yield, g (`None`, 0 or NaN = no target).
    pub target_yield_g: Option<f32>,
    /// A scale is connected and not stale (de1app `is_operational`).
    pub scale_operational: bool,
}

/// What a ratio-mode amount was applied to.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum HotWaterBasis {
    /// Time and volume modes.
    NotApplicable,
    /// The ratio applied to the target yield.
    TargetYield,
    /// No target yield: the volume-mode value is poured.
    NoYieldTarget,
}

/// Which bound changed the ml.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum HotWaterClamp {
    /// Lowered to [`HOT_WATER_MAX_ML`].
    Capped,
    /// Raised to [`HOT_WATER_MIN_ML`].
    RaisedToMinimum,
}

/// The resolved hot-water target: what the readout shows, what the packet
/// holds and where the weight stop sits.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HotWaterTarget {
    /// The dose's mode.
    pub mode: HotWaterMode,
    /// Time mode: the seconds poured; `None` for No limit.
    pub time_s: Option<f32>,
    /// Volume and ratio modes: what Crema pours, ml, after clamping.
    pub volume_ml: Option<f32>,
    /// The unclamped ml, only when a clamp changed it.
    pub requested_ml: Option<f32>,
    /// Which bound applied, if one did.
    pub clamp: Option<HotWaterClamp>,
    /// What a ratio applied to.
    pub basis: HotWaterBasis,
    /// Ratio mode: the yield the ratio applied to, g.
    pub basis_g: Option<f32>,
    /// The packet's `TargetHotWaterVol`, ml.
    pub wire_volume_ml: f32,
    /// The packet's `TargetHotWaterLength`, s.
    pub wire_timeout_s: f32,
    /// Volume and ratio modes with an operational scale: the weight to stop
    /// at, g.
    pub stop_weight_g: Option<f32>,
}

/// A stepper's range: `chips` lie inside `[min, max]`.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StepRange {
    /// Lowest value.
    pub min: f32,
    /// Highest value.
    pub max: f32,
    /// Step.
    pub step: f32,
    /// Preset chips.
    pub chips: Vec<f32>,
}

/// The hot-water steppers' ranges and the default dose, for both shells.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HotWaterBounds {
    /// Time mode, s.
    pub time_s: StepRange,
    /// Offer a "No limit" step above the time range.
    pub time_no_limit: bool,
    /// Volume mode, ml.
    pub volume_ml: StepRange,
    /// Ratio mode.
    pub ratio: StepRange,
    /// The default dose.
    pub defaults: HotWaterDose,
}

/// `value` clamped to `[min, max]`, or `fallback` when not finite.
fn clamp_or(value: f32, min: f32, max: f32, fallback: f32) -> f32 {
    if value.is_finite() {
        value.clamp(min, max)
    } else {
        fallback
    }
}

/// The dose with every value inside its range; a NaN or infinite value takes
/// the mode's default. A restored, adopted, imported or relayed dose goes
/// through this before it reaches the packet. The No limit flag is kept
/// (it ships dark until the firmware has a value for it).
#[must_use]
pub fn sanitize_hot_water_dose(dose: &HotWaterDose, fw: &HotWaterFirmware) -> HotWaterDose {
    let defaults = HotWaterDose::defaults(fw);
    HotWaterDose {
        mode: dose.mode,
        time_s: clamp_or(
            dose.time_s,
            HOT_WATER_TIME_MIN_S,
            f32::from(fw.time_max_s),
            defaults.time_s,
        ),
        time_no_limit: dose.time_no_limit,
        volume_ml: clamp_or(
            dose.volume_ml,
            HOT_WATER_MIN_ML,
            HOT_WATER_MAX_ML,
            defaults.volume_ml,
        ),
        ratio: clamp_or(
            dose.ratio,
            HOT_WATER_RATIO_MIN,
            HOT_WATER_RATIO_MAX,
            defaults.ratio,
        ),
    }
}

/// `requested` ml clamped to the volume range, with the bound that applied.
fn clamp_ml(requested: f32) -> (f32, Option<HotWaterClamp>) {
    if requested > HOT_WATER_MAX_ML {
        (HOT_WATER_MAX_ML, Some(HotWaterClamp::Capped))
    } else if requested < HOT_WATER_MIN_ML {
        (HOT_WATER_MIN_ML, Some(HotWaterClamp::RaisedToMinimum))
    } else {
        (requested, None)
    }
}

/// Turn the dose and the shot context into the target (see the module docs).
#[must_use]
pub fn resolve_hot_water(
    dose: &HotWaterDose,
    ctx: &HotWaterContext,
    fw: &HotWaterFirmware,
) -> HotWaterTarget {
    let clean = sanitize_hot_water_dose(dose, fw);
    if clean.mode == HotWaterMode::Time {
        let no_limit = fw.no_limit_timeout.filter(|_| clean.time_no_limit);
        return HotWaterTarget {
            mode: HotWaterMode::Time,
            time_s: if no_limit.is_some() {
                None
            } else {
                Some(clean.time_s)
            },
            volume_ml: None,
            requested_ml: None,
            clamp: None,
            basis: HotWaterBasis::NotApplicable,
            basis_g: None,
            wire_volume_ml: f32::from(fw.time_mode_wire_volume_ml),
            wire_timeout_s: no_limit.map_or(clean.time_s, f32::from),
            stop_weight_g: None,
        };
    }
    let target_yield = ctx.target_yield_g.filter(|g| g.is_finite() && *g > 0.0);
    let (requested, basis, basis_g) = match (clean.mode, target_yield) {
        (HotWaterMode::Ratio, Some(yield_g)) => (
            (clean.ratio * yield_g).round(),
            HotWaterBasis::TargetYield,
            Some(yield_g),
        ),
        (HotWaterMode::Ratio, None) => (clean.volume_ml, HotWaterBasis::NoYieldTarget, None),
        // Volume: the raw value, so a clamp is reported like a ratio's.
        _ => (
            if dose.volume_ml.is_finite() {
                dose.volume_ml
            } else {
                clean.volume_ml
            },
            HotWaterBasis::NotApplicable,
            None,
        ),
    };
    let (ml, clamp) = clamp_ml(requested);
    HotWaterTarget {
        mode: clean.mode,
        time_s: None,
        volume_ml: Some(ml),
        requested_ml: clamp.map(|_| requested),
        clamp,
        basis,
        basis_g,
        wire_volume_ml: if ctx.scale_operational {
            HOT_WATER_STOP_ON_SCALE_ML
        } else {
            ml
        },
        wire_timeout_s: HOT_WATER_BACKSTOP_TIMEOUT_S,
        stop_weight_g: ctx.scale_operational.then_some(ml),
    }
}

/// `values` that lie inside `[min, max]`.
fn chips_within(values: &[f32], min: f32, max: f32) -> Vec<f32> {
    values
        .iter()
        .copied()
        .filter(|v| (min..=max).contains(v))
        .collect()
}

/// The steppers' ranges, chips and the default dose for `fw`. Chips above a
/// lowered time cap drop out.
#[must_use]
pub fn hot_water_bounds(fw: &HotWaterFirmware) -> HotWaterBounds {
    let time_max = f32::from(fw.time_max_s);
    HotWaterBounds {
        time_s: StepRange {
            min: HOT_WATER_TIME_MIN_S,
            max: time_max,
            step: TIME_STEP_S,
            chips: chips_within(
                &[15.0, 30.0, 45.0, 60.0, 120.0],
                HOT_WATER_TIME_MIN_S,
                time_max,
            ),
        },
        time_no_limit: fw.no_limit_timeout.is_some(),
        volume_ml: StepRange {
            min: HOT_WATER_MIN_ML,
            max: HOT_WATER_MAX_ML,
            step: VOLUME_STEP_ML,
            chips: vec![60.0, 100.0, 150.0, 200.0, 250.0],
        },
        ratio: StepRange {
            min: HOT_WATER_RATIO_MIN,
            max: HOT_WATER_RATIO_MAX,
            step: RATIO_STEP,
            chips: vec![2.0, 2.5, 3.0, 4.0, 5.0],
        },
        defaults: HotWaterDose::defaults(fw),
    }
}

/// `stored`'s values over the defaults, in `mode`.
fn dose_from(stored: &StoredHotWater, mode: HotWaterMode, fw: &HotWaterFirmware) -> HotWaterDose {
    let defaults = HotWaterDose::defaults(fw);
    sanitize_hot_water_dose(
        &HotWaterDose {
            mode,
            time_s: stored.time_s.unwrap_or(defaults.time_s),
            time_no_limit: stored.time_no_limit.unwrap_or(false),
            volume_ml: stored.volume_ml.unwrap_or(defaults.volume_ml),
            ratio: stored.ratio.unwrap_or(defaults.ratio),
        },
        fw,
    )
}

/// The dose to start from, for settings that may predate the modes:
///
/// - a stored mode → that mode with the stored values;
/// - no mode but a `marker` with one (the device-local copy an older build
///   can't drop) → the marker's;
/// - no mode, no marker, and the volume missing or the old 150 ml default
///   (the only sign of "never changed" once any setting was saved) → ratio
///   1:3;
/// - no mode, no marker, and a volume of 0 — the old "no volume stop", a pour
///   the timeout ends (kept by the settings sanitize) → time mode, which is
///   that pour;
/// - no mode, no marker, any other volume → volume mode with that ml.
///
/// Values not stored take the defaults. Shells call this on load and persist
/// the result once, so the 150 ml rule runs a single time.
#[must_use]
pub fn initial_hot_water_dose(
    stored: &StoredHotWater,
    marker: Option<&StoredHotWater>,
    fw: &HotWaterFirmware,
) -> HotWaterDose {
    if let Some(mode) = stored.mode {
        return dose_from(stored, mode, fw);
    }
    if let Some(marker) = marker
        && let Some(mode) = marker.mode
    {
        return dose_from(marker, mode, fw);
    }
    let never_changed = stored
        .volume_ml
        .is_none_or(|ml| ml == HOT_WATER_OLD_DEFAULT_ML || !ml.is_finite());
    let mode = if never_changed {
        HotWaterMode::Ratio
    } else if stored.volume_ml == Some(0.0) {
        HotWaterMode::Time
    } else {
        HotWaterMode::Volume
    };
    dose_from(stored, mode, fw)
}

/// [`resolve_hot_water`] with the shipped firmware, JSON in and out: a
/// `HotWaterDose` and a `HotWaterContext`. For tests and previews; the
/// shells show the core's own target.
///
/// # Errors
///
/// The parse error, as a string.
pub fn resolve_hot_water_json(dose_json: &str, ctx_json: &str) -> Result<String, String> {
    let dose: HotWaterDose = serde_json::from_str(dose_json).map_err(|e| e.to_string())?;
    let ctx: HotWaterContext = serde_json::from_str(ctx_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&resolve_hot_water(&dose, &ctx, &HOT_WATER_FIRMWARE))
        .map_err(|e| e.to_string())
}

/// [`hot_water_bounds`] for the shipped firmware, as JSON.
#[must_use]
pub fn hot_water_bounds_json() -> String {
    serde_json::to_string(&hot_water_bounds(&HOT_WATER_FIRMWARE)).unwrap_or_default()
}

/// [`initial_hot_water_dose`] with the shipped firmware, JSON in and out: a
/// `StoredHotWater` and an optional marker `StoredHotWater`.
///
/// # Errors
///
/// The parse error, as a string.
pub fn initial_hot_water_dose_json(
    stored_json: &str,
    marker_json: Option<&str>,
) -> Result<String, String> {
    let stored: StoredHotWater = serde_json::from_str(stored_json).map_err(|e| e.to_string())?;
    let marker = marker_json
        .map(serde_json::from_str::<StoredHotWater>)
        .transpose()
        .map_err(|e| e.to_string())?;
    serde_json::to_string(&initial_hot_water_dose(
        &stored,
        marker.as_ref(),
        &HOT_WATER_FIRMWARE,
    ))
    .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const FW: HotWaterFirmware = HOT_WATER_FIRMWARE;
    /// Volume 0 doesn't disable the DE1's volume stop.
    const FALLBACK: HotWaterFirmware = HotWaterFirmware {
        time_mode_wire_volume_ml: 255,
        no_limit_timeout: None,
        time_max_s: 25,
        time_default_s: 20,
    };

    fn dose(mode: HotWaterMode) -> HotWaterDose {
        HotWaterDose {
            mode,
            ..HotWaterDose::default()
        }
    }

    fn ctx(target_yield_g: Option<f32>, scale_operational: bool) -> HotWaterContext {
        HotWaterContext {
            target_yield_g,
            scale_operational,
        }
    }

    fn ratio(r: f32) -> HotWaterDose {
        HotWaterDose {
            ratio: r,
            ..dose(HotWaterMode::Ratio)
        }
    }

    fn ml(t: &HotWaterTarget) -> f32 {
        t.volume_ml.expect("an ml target")
    }

    /// AC-HW1.
    #[test]
    fn ratio_of_target_yield() {
        let t = resolve_hot_water(&ratio(3.0), &ctx(Some(50.0), false), &FW);
        assert_eq!(ml(&t), 150.0);
        assert_eq!(t.basis, HotWaterBasis::TargetYield);
        assert_eq!(t.basis_g, Some(50.0));
        assert_eq!(t.requested_ml, None);
        assert_eq!(t.clamp, None);
        let t = resolve_hot_water(&ratio(3.0), &ctx(Some(36.0), false), &FW);
        assert_eq!(ml(&t), 108.0);
    }

    /// AC-HW1: half away from zero, like `u8p0_encode`.
    #[test]
    fn rounds_half_away_from_zero() {
        let t = resolve_hot_water(&ratio(2.5), &ctx(Some(18.5), false), &FW);
        assert_eq!(ml(&t), 46.0);
        let t = resolve_hot_water(&ratio(3.0), &ctx(Some(36.5), false), &FW);
        assert_eq!(ml(&t), 110.0);
    }

    /// AC-HW3.
    #[test]
    fn no_target_falls_back_to_volume_value() {
        for target in [None, Some(0.0), Some(f32::NAN)] {
            let d = HotWaterDose {
                volume_ml: 180.0,
                ..ratio(3.0)
            };
            let t = resolve_hot_water(&d, &ctx(target, false), &FW);
            assert_eq!(ml(&t), 180.0, "{target:?}");
            assert_eq!(t.basis, HotWaterBasis::NoYieldTarget);
            assert_eq!(t.basis_g, None);
        }
    }

    /// AC-HW4.
    #[test]
    fn clamps_each_mode() {
        let t = resolve_hot_water(&ratio(3.0), &ctx(Some(100.0), false), &FW);
        assert_eq!(
            (ml(&t), t.requested_ml, t.clamp),
            (250.0, Some(300.0), Some(HotWaterClamp::Capped))
        );
        let t = resolve_hot_water(&ratio(0.5), &ctx(Some(18.0), false), &FW);
        assert_eq!(
            (ml(&t), t.requested_ml, t.clamp),
            (20.0, Some(9.0), Some(HotWaterClamp::RaisedToMinimum))
        );
        let volume = |v: f32| HotWaterDose {
            volume_ml: v,
            ..dose(HotWaterMode::Volume)
        };
        let t = resolve_hot_water(&volume(500.0), &ctx(None, false), &FW);
        assert_eq!((ml(&t), t.clamp), (250.0, Some(HotWaterClamp::Capped)));
        let t = resolve_hot_water(&volume(5.0), &ctx(None, false), &FW);
        assert_eq!(
            (ml(&t), t.clamp),
            (20.0, Some(HotWaterClamp::RaisedToMinimum))
        );
        let t = resolve_hot_water(&volume(200.0), &ctx(None, false), &FW);
        assert_eq!((ml(&t), t.requested_ml, t.clamp), (200.0, None, None));
        let time = |s: f32| HotWaterDose {
            time_s: s,
            ..dose(HotWaterMode::Time)
        };
        for (set, got) in [(0.0, 5.0), (120.0, 120.0), (500.0, 255.0)] {
            let t = resolve_hot_water(&time(set), &ctx(None, false), &FW);
            assert_eq!(t.time_s, Some(got), "time {set}");
        }
        // No limit is ignored while the firmware has no value for it.
        let t = resolve_hot_water(
            &HotWaterDose {
                time_no_limit: true,
                ..time(45.0)
            },
            &ctx(None, false),
            &FW,
        );
        assert_eq!((t.time_s, t.wire_timeout_s), (Some(45.0), 45.0));
    }

    /// AC-HW4.
    #[test]
    fn sanitize_dose() {
        let raw = HotWaterDose {
            mode: HotWaterMode::Ratio,
            time_s: f32::NAN,
            time_no_limit: true,
            volume_ml: f32::INFINITY,
            ratio: 9.0,
        };
        let s = sanitize_hot_water_dose(&raw, &FW);
        assert_eq!(s.time_s, 30.0);
        assert_eq!(s.volume_ml, 150.0);
        assert_eq!(s.ratio, 6.0);
        assert!(s.time_no_limit, "kept: it ships dark");
        let s = sanitize_hot_water_dose(&HotWaterDose { ratio: 0.1, ..raw }, &FW);
        assert_eq!(s.ratio, 0.5);
        let s = sanitize_hot_water_dose(
            &HotWaterDose {
                ratio: f32::NAN,
                ..raw
            },
            &FW,
        );
        assert_eq!(s.ratio, 3.0);
    }

    /// AC-HW4: the fallback firmware caps time at 25 s, defaults to 20 s and
    /// drops the chips above the cap.
    #[test]
    fn fallback_firmware_bounds() {
        let b = hot_water_bounds(&FALLBACK);
        assert_eq!(b.time_s.max, 25.0);
        assert_eq!(b.time_s.chips, vec![15.0]);
        assert_eq!(b.defaults.time_s, 20.0);
        assert!(!b.time_no_limit);
        let t = resolve_hot_water(
            &HotWaterDose {
                time_s: 120.0,
                ..dose(HotWaterMode::Time)
            },
            &ctx(None, false),
            &FALLBACK,
        );
        assert_eq!(t.time_s, Some(25.0));
        let shipped = hot_water_bounds(&FW);
        assert_eq!(shipped.time_s.chips, vec![15.0, 30.0, 45.0, 60.0, 120.0]);
        assert_eq!(shipped.volume_ml.max, 250.0);
        assert_eq!(shipped.defaults, HotWaterDose::default());
        for r in [&shipped.time_s, &shipped.volume_ml, &shipped.ratio] {
            assert!(r.chips.iter().all(|c| (r.min..=r.max).contains(c)));
        }
    }

    /// AC-HW5.
    #[test]
    fn wire_without_scale() {
        for d in [
            HotWaterDose {
                volume_ml: 180.0,
                ..dose(HotWaterMode::Volume)
            },
            ratio(3.0),
        ] {
            let t = resolve_hot_water(&d, &ctx(Some(60.0), false), &FW);
            assert_eq!(t.wire_volume_ml, 180.0);
            assert_eq!(t.wire_timeout_s, 60.0);
            assert_eq!(t.stop_weight_g, None);
        }
    }

    /// AC-HW5: time mode per firmware branch.
    #[test]
    fn time_mode_wire_per_firmware() {
        let time = HotWaterDose {
            time_s: 120.0,
            ..dose(HotWaterMode::Time)
        };
        let t = resolve_hot_water(&time, &ctx(Some(36.0), false), &FW);
        assert_eq!((t.wire_volume_ml, t.wire_timeout_s), (0.0, 120.0));
        assert_eq!(t.stop_weight_g, None);
        let t = resolve_hot_water(&time, &ctx(None, false), &FALLBACK);
        assert_eq!((t.wire_volume_ml, t.wire_timeout_s), (255.0, 25.0));
        let until_stopped = HotWaterFirmware {
            no_limit_timeout: Some(0),
            ..FW
        };
        let no_limit = HotWaterDose {
            time_no_limit: true,
            ..time
        };
        let t = resolve_hot_water(&no_limit, &ctx(None, false), &until_stopped);
        assert_eq!((t.wire_volume_ml, t.wire_timeout_s), (0.0, 0.0));
        assert_eq!(t.time_s, None, "No limit");
        assert!(hot_water_bounds(&until_stopped).time_no_limit);
    }

    /// AC-HW6: the backstop and the weight stop with an operational scale;
    /// time mode exactly as without one.
    #[test]
    fn wire_with_scale() {
        let t = resolve_hot_water(&ratio(3.0), &ctx(Some(50.0), true), &FW);
        assert_eq!((t.wire_volume_ml, t.wire_timeout_s), (250.0, 60.0));
        assert_eq!(t.stop_weight_g, Some(150.0));
        let t = resolve_hot_water(&dose(HotWaterMode::Volume), &ctx(None, true), &FW);
        assert_eq!(t.wire_volume_ml, 250.0);
        assert_eq!(t.stop_weight_g, Some(150.0));
        let time = HotWaterDose {
            time_s: 45.0,
            ..dose(HotWaterMode::Time)
        };
        assert_eq!(
            resolve_hot_water(&time, &ctx(Some(50.0), true), &FW),
            resolve_hot_water(&time, &ctx(Some(50.0), false), &FW)
        );
    }

    /// AC-HW7.
    #[test]
    fn mode_switch_keeps_values() {
        let mut d = ratio(3.0);
        d.mode = HotWaterMode::Volume;
        d.volume_ml = 200.0;
        d.mode = HotWaterMode::Time;
        d.time_s = 45.0;
        d.mode = HotWaterMode::Ratio;
        let c = ctx(Some(50.0), false);
        assert_eq!(ml(&resolve_hot_water(&d, &c, &FW)), 150.0);
        d.mode = HotWaterMode::Volume;
        assert_eq!(ml(&resolve_hot_water(&d, &c, &FW)), 200.0);
        d.mode = HotWaterMode::Time;
        assert_eq!(resolve_hot_water(&d, &c, &FW).time_s, Some(45.0));
    }

    /// AC-HW8.
    #[test]
    fn initial_dose_table() {
        let stored = |mode: Option<HotWaterMode>, volume_ml: Option<f32>| StoredHotWater {
            mode,
            volume_ml,
            ..StoredHotWater::default()
        };
        let d = initial_hot_water_dose(&stored(None, None), None, &FW);
        assert_eq!(d, HotWaterDose::default());
        assert_eq!(
            (d.mode, d.ratio, d.time_s, d.volume_ml),
            (HotWaterMode::Ratio, 3.0, 30.0, 150.0)
        );
        let d = initial_hot_water_dose(&stored(None, Some(150.0)), None, &FW);
        assert_eq!(
            (d.mode, d.ratio, d.volume_ml),
            (HotWaterMode::Ratio, 3.0, 150.0)
        );
        for (set, got) in [(200.0, 200.0), (500.0, 250.0), (10.0, 20.0)] {
            let d = initial_hot_water_dose(&stored(None, Some(set)), None, &FW);
            assert_eq!((d.mode, d.volume_ml), (HotWaterMode::Volume, got), "{set}");
        }
        // A volume that isn't a number (a shell bug, never valid JSON) is
        // treated as never changed, not as a volume the user chose.
        let d = initial_hot_water_dose(&stored(None, Some(f32::NAN)), None, &FW);
        assert_eq!((d.mode, d.volume_ml), (HotWaterMode::Ratio, 150.0));
        // 0 ml was "no volume stop": a pour the timeout ends, so time mode.
        let d = initial_hot_water_dose(&stored(None, Some(0.0)), None, &FW);
        assert_eq!((d.mode, d.time_s), (HotWaterMode::Time, 30.0));
        for mode in [
            HotWaterMode::Ratio,
            HotWaterMode::Volume,
            HotWaterMode::Time,
        ] {
            let s = StoredHotWater {
                mode: Some(mode),
                volume_ml: Some(150.0),
                time_s: Some(45.0),
                time_no_limit: Some(true),
                ratio: Some(2.5),
            };
            let d = initial_hot_water_dose(&s, None, &FW);
            assert_eq!(
                d,
                HotWaterDose {
                    mode,
                    time_s: 45.0,
                    time_no_limit: true,
                    volume_ml: 150.0,
                    ratio: 2.5
                }
            );
        }
        // The downgrade marker: an older build dropped the mode.
        let marker = StoredHotWater {
            mode: Some(HotWaterMode::Volume),
            volume_ml: Some(150.0),
            ..StoredHotWater::default()
        };
        let d = initial_hot_water_dose(&stored(None, Some(150.0)), Some(&marker), &FW);
        assert_eq!((d.mode, d.volume_ml), (HotWaterMode::Volume, 150.0));
        let marker = StoredHotWater {
            mode: Some(HotWaterMode::Ratio),
            ratio: Some(2.5),
            ..StoredHotWater::default()
        };
        let d = initial_hot_water_dose(&stored(None, None), Some(&marker), &FW);
        assert_eq!((d.mode, d.ratio), (HotWaterMode::Ratio, 2.5));
        // A stored mode wins over the marker.
        let d = initial_hot_water_dose(&stored(Some(HotWaterMode::Time), None), Some(&marker), &FW);
        assert_eq!(d.mode, HotWaterMode::Time);
    }

    #[test]
    fn json_facades_round_trip() {
        let out = resolve_hot_water_json(
            r#"{"mode":"ratio","timeS":30,"timeNoLimit":false,"volumeMl":150,"ratio":3}"#,
            r#"{"targetYieldG":50,"scaleOperational":false}"#,
        )
        .unwrap();
        let t: HotWaterTarget = serde_json::from_str(&out).unwrap();
        assert_eq!(t.volume_ml, Some(150.0));
        let b: HotWaterBounds = serde_json::from_str(&hot_water_bounds_json()).unwrap();
        assert_eq!(b.volume_ml.max, 250.0);
        let d: HotWaterDose = serde_json::from_str(
            &initial_hot_water_dose_json(r#"{"volumeMl":200}"#, None).unwrap(),
        )
        .unwrap();
        assert_eq!(d.mode, HotWaterMode::Volume);
        assert!(resolve_hot_water_json("{", "{}").is_err());
    }
}

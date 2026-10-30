//! The **effective** profile: a library [`CremaProfile`] with the user's
//! per-shot Quick Controls overrides ([`BrewOverrides`]) applied — the one
//! profile a shell may upload to the DE1, and the one its fingerprint hashes.
//!
//! The overrides never edit the saved profile (Decenza keeps its temperature
//! override beside the profile the same way); they live in the shell's Quick
//! Controls state and are applied here, at upload time, by one rule both shells
//! share:
//!
//! - **Brew temperature** is an *absolute* target `T`. The anchor `A` is the
//!   first step's temperature ([`brew_temp_anchor`]; de1app's
//!   `espresso_temperature` for an advanced profile), never the free-standing
//!   top-level `brew_temp`, which old saves let drift from the steps. Every
//!   step's temperature moves by `T − A`, so the profile's temperature shape
//!   is kept (de1app `change_espresso_temperature`, reaprime
//!   `Profile.adjustTemperature`, Decenza `framesShiftedToTemperature`). `T` is
//!   clamped into [`brew_temp_range`], so no step leaves the core's
//!   temperature bounds or drops below [`STEP_FLOOR_C`]; a `T` of 0 or outside
//!   the bounds is refused. The top-level `brew_temp` is derived from the
//!   first step ([`with_derived_brew_temp`]) so every surface shows the anchor.
//! - **Pre-infusion** is the total time, seconds, of the profile's leading
//!   pre-infusion segments (the first `preinfuse_step_count`). An override
//!   rescales those segments' times proportionally to reach the new total —
//!   see [`apply_preinfusion`]. A profile with no pre-infusion segments has
//!   nothing to rescale, so the override is dropped.
//! - **Dose / yield** replace the profile's own values.
//!
//! [`normalize_brew_overrides`] is the override lifecycle: it clamps the brew
//! temperature and drops every value that equals the profile's own (an override
//! dialled back to the anchor is no override). Both shells store only the
//! normalized overrides, so "dialled back to the profile" and "never touched"
//! are the same state with the same fingerprint.

use crate::crema_profile::{CremaProfile, ProfileSegment};
use crate::profile_bounds::{
    MAX_FRAME_SECONDS, MAX_TEMPERATURE_C, MIN_BREW_TEMPERATURE_C, MIN_TEMPERATURE_C,
};
use typeshare::typeshare;

/// Two override values closer than this are the same value (half the 0.1
/// resolution every Quick Controls stepper uses).
const SAME_VALUE_EPSILON: f32 = 0.05;

/// The lowest temperature a shift may move a heated step to, °C: a step's
/// 0 °C means "heater off", so a brew-temperature change never turns a heated
/// step off (a profile authored with a lower step is not shifted down at all).
pub const STEP_FLOOR_C: f32 = 1.0;

/// The pre-infusion dial's default top, seconds — both shells' stepper
/// maximum. A profile whose own pre-infusion is longer raises it
/// ([`preinfusion_dial`]).
pub const PREINFUSION_DIAL_MAX_S: f32 = 30.0;

/// The user's per-shot Quick Controls overrides — absolute values, each
/// optional (absent = the profile's own value). The JSON shape the shells
/// already pass to the fingerprint (`{dose, yield, brewTemp, preinf}`).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewOverrides {
    /// Dose, grams.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub dose: Option<f32>,
    /// Yield target, grams.
    #[serde(rename = "yield", skip_serializing_if = "Option::is_none")]
    pub yield_g: Option<f32>,
    /// Absolute brew temperature, °C — every step is shifted by the
    /// difference from the profile's anchor ([`brew_temp_anchor`]).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub brew_temp: Option<f32>,
    /// Total pre-infusion time, seconds, across the leading pre-infusion
    /// segments ([`preinfusion_seconds`]).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub preinf: Option<f32>,
}

impl BrewOverrides {
    /// `true` when no field is set — the profile runs as saved.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.dose.is_none()
            && self.yield_g.is_none()
            && self.brew_temp.is_none()
            && self.preinf.is_none()
    }
}

/// The brew-temperature dial's range for one profile, °C (inclusive).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewTempRange {
    /// Lowest absolute brew temperature the dial may reach.
    pub min: f32,
    /// Highest absolute brew temperature the dial may reach.
    pub max: f32,
    /// The profile's own brew temperature (the anchor the shift is
    /// measured from) — the dial's "from profile" reference.
    pub anchor: f32,
}

/// The temperature an absolute brew-temp override is measured from: the
/// first step's temperature (de1app `espresso_temperature` for an advanced
/// shot, Decenza `espressoTemperature`). Never the top-level `brew_temp`: old
/// blank profiles carry 93 over 92 °C steps, and older editors and Save preset
/// changed it without moving the steps. A profile without steps (or a
/// non-finite first temperature) falls back to 92 °C — there is nothing to
/// shift.
#[must_use]
pub fn brew_temp_anchor(profile: &CremaProfile) -> f32 {
    profile
        .segments
        .first()
        .map(|s| s.temp)
        .filter(|t| t.is_finite())
        .unwrap_or(92.0)
}

/// `profile` with its top-level `brew_temp` set to the anchor (the first
/// step's temperature), so the cards, the dial and the editors all show the
/// temperature the profile actually starts at. Shells apply it on load and
/// save; [`crate::from_wire`] and [`effective_crema_profile`] produce it.
#[must_use]
pub fn with_derived_brew_temp(mut profile: CremaProfile) -> CremaProfile {
    profile.brew_temp = brew_temp_anchor(&profile);
    profile
}

/// The brew-temperature dial's range for `profile`: every absolute target in
/// it shifts every step by `T − anchor` without any step leaving
/// [`STEP_FLOOR_C`]..=[`MAX_TEMPERATURE_C`], with the low end no lower than
/// [`MIN_BREW_TEMPERATURE_C`] (the dial's minimum brew temperature) and the
/// high end inside the core's bounds. The anchor is always in the range (no
/// shift is always allowed, even for a profile authored outside the bounds).
#[must_use]
pub fn brew_temp_range(profile: &CremaProfile) -> BrewTempRange {
    let anchor = brew_temp_anchor(profile);
    let temps = profile
        .segments
        .iter()
        .map(|s| s.temp)
        .filter(|t| t.is_finite());
    let (lo, hi) = temps.fold((None::<f32>, None::<f32>), |(lo, hi), t| {
        (
            Some(lo.map_or(t, |l: f32| l.min(t))),
            Some(hi.map_or(t, |h: f32| h.max(t))),
        )
    });
    // No steps: nothing to push out of bounds, so the bounds themselves.
    let min = lo
        .map_or(STEP_FLOOR_C, |lo| anchor + (STEP_FLOOR_C - lo))
        .max(MIN_BREW_TEMPERATURE_C);
    let max = hi
        .map_or(MAX_TEMPERATURE_C, |hi| anchor + (MAX_TEMPERATURE_C - hi))
        .min(MAX_TEMPERATURE_C);
    BrewTempRange {
        min: min.min(anchor),
        max: max.max(anchor),
        anchor,
    }
}

/// How many leading segments are pre-infusion: the profile's
/// `preinfuse_step_count`, capped at its segment count.
fn preinfusion_segment_count(profile: &CremaProfile) -> usize {
    usize::from(profile.preinfuse_step_count).min(profile.segments.len())
}

/// The profile's pre-infusion time, seconds: the total time of its leading
/// pre-infusion segments (0 when it has none), to 0.1 s. The Quick Controls
/// pre-infusion dial's seed on both shells.
#[must_use]
pub fn preinfusion_seconds(profile: &CremaProfile) -> f32 {
    let n = preinfusion_segment_count(profile);
    let total: f32 = profile.segments[..n]
        .iter()
        .map(|s| finite_non_negative(s.time))
        .sum();
    // `+ 0.0` turns the empty sum's -0.0 into 0.0 (a "-0 s" on screen).
    round_tenth(total) + 0.0
}

/// The pre-infusion dial for one profile: its range, the profile's own
/// seconds ([`preinfusion_seconds`]) and whether it can be dialled at all.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PreinfusionDial {
    /// Lowest pre-infusion, seconds (0 = off).
    pub min: f32,
    /// Highest pre-infusion, seconds: the stepper's [`PREINFUSION_DIAL_MAX_S`],
    /// raised to the profile's own value when that is longer.
    pub max: f32,
    /// The profile's own pre-infusion seconds — the dial's seed.
    pub seconds: f32,
    /// `false` for a profile with no pre-infusion segment: there is nothing
    /// to rescale, so the shells disable the dial and say why.
    pub adjustable: bool,
}

/// The pre-infusion dial for `profile` — see [`PreinfusionDial`]. Its range
/// always contains the profile's own value.
#[must_use]
pub fn preinfusion_dial(profile: &CremaProfile) -> PreinfusionDial {
    let n = preinfusion_segment_count(profile);
    let seconds = preinfusion_seconds(profile);
    #[allow(clippy::cast_precision_loss)]
    let ceiling = MAX_FRAME_SECONDS * n as f32;
    PreinfusionDial {
        min: 0.0,
        max: PREINFUSION_DIAL_MAX_S.max(seconds.ceil()).min(ceiling),
        seconds,
        adjustable: n > 0,
    }
}

fn finite_non_negative(v: f32) -> f32 {
    if v.is_finite() { v.max(0.0) } else { 0.0 }
}

fn round_tenth(v: f32) -> f32 {
    (v * 10.0).round() / 10.0
}

fn same(a: f32, b: f32) -> bool {
    (a - b).abs() < SAME_VALUE_EPSILON
}

/// The override lifecycle rule (Decenza `applyTemperatureOverride`): clamp the
/// brew temperature into [`brew_temp_range`] and pre-infusion into what the
/// leading segments can hold, drop non-finite or non-positive values, and drop
/// every value equal to the profile's own (an override dialled back to the
/// anchor is cleared). The result is what a shell stores and passes on.
#[must_use]
pub fn normalize_brew_overrides(profile: &CremaProfile, qc: &BrewOverrides) -> BrewOverrides {
    let range = brew_temp_range(profile);
    // A target of 0 or outside the core's bounds is refused (it is no brew
    // temperature: a model default, a corrupt value), never clamped into a
    // shift.
    let brew_temp = qc
        .brew_temp
        .filter(|t| t.is_finite() && *t > MIN_TEMPERATURE_C && *t <= MAX_TEMPERATURE_C)
        .map(|t| t.clamp(range.min, range.max))
        .filter(|t| !same(*t, range.anchor));

    let n = preinfusion_segment_count(profile);
    #[allow(clippy::cast_precision_loss)]
    let preinf_max = MAX_FRAME_SECONDS * n as f32;
    let preinf = qc
        .preinf
        .filter(|p| n > 0 && p.is_finite())
        .map(|p| round_tenth(p.clamp(0.0, preinf_max)))
        .filter(|p| !same(*p, preinfusion_seconds(profile)));

    // Dose and yield land inside the Quick-Controls ranges (F8): the dose
    // stepper's, and for the yield what the yield or ratio stepper reaches;
    // a yield of 0 keeps its meaning (no weight target).
    let q = crate::bounds::quick_control_bounds();
    let yield_range = q.override_yield_g;
    let dose = qc
        .dose
        .filter(|d| d.is_finite() && *d > 0.0)
        .map(|d| q.dose_g.clamp_or(d, d))
        .filter(|d| !same(*d, profile.dose));
    let yield_g = qc
        .yield_g
        .filter(|y| y.is_finite() && *y >= 0.0)
        .map(|y| {
            if y == 0.0 {
                y
            } else {
                yield_range.clamp_or(y, y)
            }
        })
        .filter(|y| !same(*y, profile.yield_out));

    BrewOverrides {
        dose,
        yield_g,
        brew_temp,
        preinf,
    }
}

/// Rescale the leading `n` segments' times so they total `target` seconds:
/// proportionally to their current times (the shape of the pre-infusion is
/// kept), or split equally when they currently total 0. Times go to 0.1 s and
/// the rounding remainder lands on the last leading segment, so the total is
/// exact.
fn apply_preinfusion(segments: &mut [ProfileSegment], n: usize, target: f32) {
    let leading = &mut segments[..n];
    let Some(last) = leading.len().checked_sub(1) else {
        return;
    };
    let current: f32 = leading.iter().map(|s| finite_non_negative(s.time)).sum();
    let mut assigned = 0.0;
    for (i, seg) in leading.iter_mut().enumerate() {
        if i == last {
            seg.time = round_tenth((target - assigned).max(0.0));
        } else {
            #[allow(clippy::cast_precision_loss)]
            let share = if current > 0.0 {
                finite_non_negative(seg.time) / current
            } else {
                1.0 / n as f32
            };
            seg.time = round_tenth(target * share);
            assigned += seg.time;
        }
    }
}

/// The profile to upload: `profile` with the normalized `qc` applied (see the
/// module docs) and its `brew_temp` derived from the first step. With no
/// overrides it is `profile` with only that derivation, so its bytes match the
/// saved profile's. The editors' "Brew temp" field uses it too
/// (`{brewTemp}` only) — Decenza's `applyTemperatureToProfile`.
#[must_use]
pub fn effective_crema_profile(profile: &CremaProfile, qc: &BrewOverrides) -> CremaProfile {
    let qc = normalize_brew_overrides(profile, qc);
    let mut effective = profile.clone();
    if let Some(target) = qc.brew_temp {
        let delta = target - brew_temp_anchor(profile);
        for seg in &mut effective.segments {
            // Clamped after the shift: the range keeps every step inside the
            // bounds, and this removes the float noise at its ends.
            seg.temp = (seg.temp + delta).clamp(MIN_TEMPERATURE_C, MAX_TEMPERATURE_C);
        }
    }
    let mut effective = with_derived_brew_temp(effective);
    if let Some(preinf) = qc.preinf {
        let n = preinfusion_segment_count(profile);
        apply_preinfusion(&mut effective.segments, n, preinf);
    }
    if let Some(dose) = qc.dose {
        effective.dose = dose;
    }
    if let Some(yield_g) = qc.yield_g {
        effective.yield_out = yield_g;
    }
    effective
}

/// The fingerprint of the effective profile — the hash of what
/// [`effective_crema_profile`] uploads, so it matches the bytes by
/// construction. The one fingerprint both shells compare against what the
/// DE1 holds.
///
/// # Errors
///
/// The serialisation error string (unreachable for a well-formed profile).
pub fn effective_profile_fingerprint(
    profile: &CremaProfile,
    qc: &BrewOverrides,
) -> Result<String, String> {
    let effective = effective_crema_profile(profile, qc);
    let json = serde_json::to_string(&effective).map_err(|e| e.to_string())?;
    crate::profile_fingerprint(&json, None)
}

// ── JSON facades (the FFI / wasm boundary) ──────────────────────────────────

fn parse_profile(profile_json: &str) -> Result<CremaProfile, String> {
    serde_json::from_str(profile_json).map_err(|e| e.to_string())
}

/// Parse a [`BrewOverrides`] JSON; empty / `null` = no overrides.
///
/// # Errors
///
/// The JSON error string on malformed input.
pub fn parse_brew_overrides(qc_json: Option<&str>) -> Result<BrewOverrides, String> {
    match qc_json.map(str::trim) {
        None | Some("" | "null") => Ok(BrewOverrides::default()),
        Some(s) => serde_json::from_str(s).map_err(|e| e.to_string()),
    }
}

/// JSON facade for [`effective_crema_profile`]: a [`CremaProfile`] JSON and an
/// optional [`BrewOverrides`] JSON in, the effective [`CremaProfile`] JSON out.
///
/// # Errors
///
/// The JSON error string on malformed input.
pub fn effective_crema_profile_json(
    profile_json: &str,
    qc_json: Option<&str>,
) -> Result<String, String> {
    let p = parse_profile(profile_json)?;
    let qc = parse_brew_overrides(qc_json)?;
    serde_json::to_string(&effective_crema_profile(&p, &qc)).map_err(|e| e.to_string())
}

/// JSON facade for [`effective_profile_fingerprint`].
///
/// # Errors
///
/// The JSON error string on malformed input.
pub fn effective_profile_fingerprint_json(
    profile_json: &str,
    qc_json: Option<&str>,
) -> Result<String, String> {
    let p = parse_profile(profile_json)?;
    let qc = parse_brew_overrides(qc_json)?;
    effective_profile_fingerprint(&p, &qc)
}

/// JSON facade for [`normalize_brew_overrides`].
///
/// # Errors
///
/// The JSON error string on malformed input.
pub fn normalize_brew_overrides_json(
    profile_json: &str,
    qc_json: Option<&str>,
) -> Result<String, String> {
    let p = parse_profile(profile_json)?;
    let qc = parse_brew_overrides(qc_json)?;
    serde_json::to_string(&normalize_brew_overrides(&p, &qc)).map_err(|e| e.to_string())
}

/// JSON facade for [`brew_temp_range`]: a [`BrewTempRange`] JSON.
///
/// # Errors
///
/// The JSON error string on malformed input.
pub fn brew_temp_range_json(profile_json: &str) -> Result<String, String> {
    let p = parse_profile(profile_json)?;
    serde_json::to_string(&brew_temp_range(&p)).map_err(|e| e.to_string())
}

/// JSON facade for [`with_derived_brew_temp`]: a [`CremaProfile`] JSON in, the
/// same profile with its `brewTemp` set to the first step's temperature out.
///
/// # Errors
///
/// The JSON error string on malformed input.
pub fn with_derived_brew_temp_json(profile_json: &str) -> Result<String, String> {
    let p = parse_profile(profile_json)?;
    serde_json::to_string(&with_derived_brew_temp(p)).map_err(|e| e.to_string())
}

/// JSON facade for [`preinfusion_dial`]: a [`PreinfusionDial`] JSON.
///
/// # Errors
///
/// The JSON error string on malformed input.
pub fn preinfusion_dial_json(profile_json: &str) -> Result<String, String> {
    let p = parse_profile(profile_json)?;
    serde_json::to_string(&preinfusion_dial(&p)).map_err(|e| e.to_string())
}

/// JSON facade for [`preinfusion_seconds`].
///
/// # Errors
///
/// The JSON error string on malformed input.
pub fn preinfusion_seconds_json(profile_json: &str) -> Result<f32, String> {
    parse_profile(profile_json).map(|p| preinfusion_seconds(&p))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crema_profile::{BrewDefaults, blank_profile, default_segments, to_wire};

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-4
    }

    /// A three-step declining-temperature profile: 94 / 90 / 88 °C (anchored
    /// on its first step, 94), one 6 s pre-infusion segment.
    fn declining() -> CremaProfile {
        let mut p = blank_profile(&BrewDefaults::default());
        p.segments.truncate(3);
        for (seg, t) in p.segments.iter_mut().zip([94.0, 90.0, 88.0]) {
            seg.temp = t;
        }
        p.segments[0].time = 6.0;
        p.brew_temp = 94.0;
        p.preinfuse_step_count = 1;
        p
    }

    fn temps(p: &CremaProfile) -> Vec<f32> {
        p.segments.iter().map(|s| s.temp).collect()
    }

    /// AC-F6.1: the absolute target shifts every step by `T − A` — 94/90/88
    /// shown as 91, dialled to 93 (+2) → 96/92/90.
    #[test]
    fn f6_1_brew_temp_shifts_every_step_by_the_delta() {
        let p = declining();
        // 94/90/88, anchored on 94: dialled to 96 (+2) → 96/92/90.
        let qc = BrewOverrides {
            brew_temp: Some(96.0),
            ..BrewOverrides::default()
        };
        let e = effective_crema_profile(&p, &qc);
        assert_eq!(temps(&e), vec![96.0, 92.0, 90.0]);
        assert!(close(e.brew_temp, 96.0));
        // The wire steps carry the shift (the bytes the DE1 runs).
        let wire: Vec<f32> = to_wire(&e).steps.iter().map(|s| s.temperature_c).collect();
        assert_eq!(wire, vec![96.0, 92.0, 90.0]);
        // A downward dial moves them down by the same delta.
        let down = effective_crema_profile(
            &p,
            &BrewOverrides {
                brew_temp: Some(92.5),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(temps(&down), vec![92.5, 88.5, 86.5]);
    }

    /// AC-F6.2: the range keeps every step inside the core bounds, and the
    /// core clamps a value outside it at both ends.
    #[test]
    fn f6_2_brew_temp_range_is_enforced_at_both_ends() {
        let p = declining();
        let r = brew_temp_range(&p);
        // Highest step 94 may rise 6 to 100 → 94 + 6; the low end stops at the
        // dial's minimum brew temperature, 80.
        assert!(close(r.max, 100.0), "max {}", r.max);
        assert!(close(r.min, MIN_BREW_TEMPERATURE_C), "min {}", r.min);
        assert!(close(r.anchor, 94.0));

        let high = effective_crema_profile(
            &p,
            &BrewOverrides {
                brew_temp: Some(100.0),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(temps(&high), vec![100.0, 96.0, 94.0]);
        assert!(temps(&high).iter().all(|t| *t <= MAX_TEMPERATURE_C));

        // Inside the core's bounds but below the range: clamped.
        let low = effective_crema_profile(
            &p,
            &BrewOverrides {
                brew_temp: Some(3.0),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(temps(&low), vec![80.0, 76.0, 74.0]);
        assert!(temps(&low).iter().all(|t| *t >= STEP_FLOOR_C));
        let n = normalize_brew_overrides(
            &p,
            &BrewOverrides {
                brew_temp: Some(3.0),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(n.brew_temp, Some(80.0));
    }

    /// A brew temperature of 0 or outside the core's bounds is refused, never
    /// clamped into a shift: no step is ever moved to 0 °C.
    #[test]
    fn a_brew_temp_of_zero_or_out_of_bounds_is_refused() {
        let p = declining();
        for bad in [0.0, -20.0, 100.5, 150.0, f32::NAN, f32::INFINITY] {
            let qc = BrewOverrides {
                brew_temp: Some(bad),
                ..BrewOverrides::default()
            };
            assert_eq!(normalize_brew_overrides(&p, &qc).brew_temp, None, "{bad}");
            assert_eq!(
                temps(&effective_crema_profile(&p, &qc)),
                vec![94.0, 90.0, 88.0]
            );
        }
    }

    /// The range's ends stay inside the core's bounds: a profile with a 0 °C
    /// step (heater off, like "Steam only") does not offer 192 °C.
    #[test]
    fn the_range_ends_stay_inside_the_core_bounds() {
        let mut p = declining();
        for seg in &mut p.segments {
            seg.temp = 0.0;
        }
        p.segments[0].temp = 92.0;
        let r = brew_temp_range(&p);
        assert!(r.max <= MAX_TEMPERATURE_C, "{r:?}");
        assert!(r.min >= MIN_TEMPERATURE_C, "{r:?}");
        // The 0 °C steps can't go lower: the dial can't go down at all.
        assert!(close(r.min, 92.0), "{r:?}");
        // Every builtin's range is inside the bounds (or just the anchor).
        for wire in crate::builtin_profiles() {
            let p = crate::from_wire(wire);
            let r = brew_temp_range(&p);
            assert!(r.min <= r.anchor && r.anchor <= r.max, "{}: {r:?}", p.name);
            assert!(
                r.max <= MAX_TEMPERATURE_C.max(r.anchor),
                "{}: {r:?}",
                p.name
            );
            assert!(r.min >= MIN_TEMPERATURE_C, "{}: {r:?}", p.name);
            // Shifting to either end keeps every step in bounds, exactly (an
            // end at the anchor is no shift: the profile as authored).
            for t in [r.min, r.max].into_iter().filter(|t| !same(*t, r.anchor)) {
                let e = effective_crema_profile(
                    &p,
                    &BrewOverrides {
                        brew_temp: Some(t),
                        ..BrewOverrides::default()
                    },
                );
                assert!(
                    e.segments
                        .iter()
                        .all(|s| (MIN_TEMPERATURE_C..=MAX_TEMPERATURE_C).contains(&s.temp)),
                    "{}: {t}",
                    p.name
                );
            }
        }
    }

    #[test]
    fn the_range_always_contains_the_anchor() {
        // A profile authored above the bound: no upward room, the anchor stays
        // legal.
        let mut p = declining();
        p.segments[0].temp = 101.0;
        let r = brew_temp_range(&p);
        assert!(close(r.anchor, 101.0));
        assert!(close(r.max, 101.0));
        assert!(r.min <= 101.0);
    }

    /// P1: the anchor is the first step, never the free-standing top-level
    /// `brew_temp` (old blank profiles carry 93 over 92 °C steps).
    #[test]
    fn a_stale_top_level_brew_temp_is_ignored() {
        let mut p = declining();
        for seg in &mut p.segments {
            seg.temp = 92.0;
        }
        p.brew_temp = 93.0;
        assert!(close(brew_temp_anchor(&p), 92.0));
        assert!(close(brew_temp_range(&p).max, 100.0));
        assert!(close(with_derived_brew_temp(p.clone()).brew_temp, 92.0));
        // Dialling 95 brews at 95, not 94.
        let e = effective_crema_profile(
            &p,
            &BrewOverrides {
                brew_temp: Some(95.0),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(temps(&e), vec![95.0, 95.0, 95.0]);
        // And no override still derives the display value from the steps.
        assert!(close(
            effective_crema_profile(&p, &BrewOverrides::default()).brew_temp,
            92.0
        ));
    }

    /// The editors' "Brew temp" field shifts every step through the core
    /// (Decenza `applyTemperatureToProfile`), keeping the shape.
    #[test]
    fn an_editor_brew_temp_change_shifts_every_step() {
        let p = declining();
        let edited = effective_crema_profile(
            &p,
            &BrewOverrides {
                brew_temp: Some(91.0),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(temps(&edited), vec![91.0, 87.0, 85.0]);
        assert!(close(edited.brew_temp, 91.0));
        assert!(close(brew_temp_anchor(&edited), 91.0));
    }

    /// AC-F6.3 (core half): a pre-infusion override changes the uploaded
    /// frames, rescaling every leading pre-infusion segment.
    #[test]
    fn f6_3_preinfusion_rescales_the_leading_segments() {
        let mut p = declining();
        p.segments[0].time = 2.0;
        p.segments[1].time = 6.0;
        p.preinfuse_step_count = 2;
        assert!(close(preinfusion_seconds(&p), 8.0));
        let e = effective_crema_profile(
            &p,
            &BrewOverrides {
                preinf: Some(12.0),
                ..BrewOverrides::default()
            },
        );
        // 2:6 kept → 3 + 9; the third (pour) segment untouched.
        assert!(close(e.segments[0].time, 3.0));
        assert!(close(e.segments[1].time, 9.0));
        assert!(close(e.segments[2].time, p.segments[2].time));
        let wire = to_wire(&e);
        assert!(close(wire.steps[0].duration_seconds, 3.0));
        assert!(close(wire.steps[1].duration_seconds, 9.0));
        // Off: every leading segment to 0 s.
        let off = effective_crema_profile(
            &p,
            &BrewOverrides {
                preinf: Some(0.0),
                ..BrewOverrides::default()
            },
        );
        assert!(close(off.segments[0].time, 0.0) && close(off.segments[1].time, 0.0));
    }

    #[test]
    fn preinfusion_rounding_keeps_the_total_exact() {
        let mut p = declining();
        p.segments[0].time = 1.0;
        p.segments[1].time = 1.0;
        p.segments[2].time = 1.0;
        p.preinfuse_step_count = 3;
        let e = effective_crema_profile(
            &p,
            &BrewOverrides {
                preinf: Some(10.0),
                ..BrewOverrides::default()
            },
        );
        assert!(close(preinfusion_seconds(&e), 10.0));
        // Zero-length leading segments split the target equally.
        for s in &mut p.segments {
            s.time = 0.0;
        }
        let e = effective_crema_profile(
            &p,
            &BrewOverrides {
                preinf: Some(9.0),
                ..BrewOverrides::default()
            },
        );
        assert!(e.segments.iter().all(|s| close(s.time, 3.0)));
    }

    /// The pre-infusion dial's range always holds the profile's own value
    /// (37 / 45 / 80 s builtins), and a profile with no pre-infusion segment
    /// gets a disabled dial at 0 (not "-0").
    #[test]
    fn the_preinfusion_dial_holds_the_profiles_own_value() {
        for wire in crate::builtin_profiles() {
            let p = crate::from_wire(wire);
            let d = preinfusion_dial(&p);
            assert!(
                d.min <= d.seconds && d.seconds <= d.max,
                "{}: {d:?}",
                p.name
            );
            if d.adjustable {
                assert!(
                    d.max >= PREINFUSION_DIAL_MAX_S.min(d.max),
                    "{}: {d:?}",
                    p.name
                );
            } else {
                assert!(
                    d.seconds.to_bits() == 0.0_f32.to_bits(),
                    "{}: {d:?} is not +0",
                    p.name
                );
            }
        }
        let mut long = declining();
        long.segments[0].time = 45.0;
        let d = preinfusion_dial(&long);
        assert!(d.adjustable && close(d.max, 45.0) && close(d.seconds, 45.0));
        let mut none = declining();
        none.preinfuse_step_count = 0;
        let d = preinfusion_dial(&none);
        assert!(!d.adjustable);
        assert_eq!(d.seconds.to_bits(), 0.0_f32.to_bits());
    }

    #[test]
    fn a_profile_without_preinfusion_ignores_the_override() {
        let mut p = declining();
        p.preinfuse_step_count = 0;
        assert!(close(preinfusion_seconds(&p), 0.0));
        let qc = BrewOverrides {
            preinf: Some(10.0),
            ..BrewOverrides::default()
        };
        assert_eq!(normalize_brew_overrides(&p, &qc).preinf, None);
        assert_eq!(
            effective_crema_profile(&p, &qc),
            with_derived_brew_temp(p.clone())
        );
    }

    #[test]
    fn step_weights_survive_the_overrides() {
        let mut p = declining();
        p.segments[1].weight = Some(3.6);
        let e = effective_crema_profile(
            &p,
            &BrewOverrides {
                dose: Some(20.0),
                brew_temp: Some(93.0),
                preinf: Some(4.0),
                ..BrewOverrides::default()
            },
        );
        let weights: Vec<Option<f32>> = to_wire(&e).steps.iter().map(|s| s.weight).collect();
        assert_eq!(weights, vec![None, Some(3.6), None]);
    }

    #[test]
    fn dose_and_yield_replace_the_profile_values() {
        let p = declining();
        let e = effective_crema_profile(
            &p,
            &BrewOverrides {
                dose: Some(20.0),
                yield_g: Some(44.0),
                ..BrewOverrides::default()
            },
        );
        assert!(close(e.dose, 20.0) && close(e.yield_out, 44.0));
        assert_eq!(e.segments, p.segments);
    }

    /// AC-F6.5 (core half): an override equal to the profile's own value is
    /// cleared, so dialling back to the anchor is the same as never dialling.
    #[test]
    fn f6_5_overrides_at_the_anchor_are_cleared() {
        let p = declining();
        let at_anchor = BrewOverrides {
            dose: Some(p.dose),
            yield_g: Some(p.yield_out + 0.01),
            brew_temp: Some(94.0),
            preinf: Some(6.0),
        };
        let n = normalize_brew_overrides(&p, &at_anchor);
        assert!(n.is_empty(), "{n:?}");
        assert_eq!(
            effective_crema_profile(&p, &at_anchor),
            with_derived_brew_temp(p.clone())
        );
        assert_eq!(
            effective_profile_fingerprint(&p, &at_anchor).unwrap(),
            effective_profile_fingerprint(&p, &BrewOverrides::default()).unwrap()
        );
        // Non-finite values are no override either.
        let junk = BrewOverrides {
            dose: Some(f32::NAN),
            yield_g: Some(-1.0),
            brew_temp: Some(f32::INFINITY),
            preinf: Some(f32::NAN),
        };
        assert!(normalize_brew_overrides(&p, &junk).is_empty());
    }

    #[test]
    fn no_overrides_leave_the_profile_and_its_fingerprint_untouched() {
        let p = declining();
        assert_eq!(effective_crema_profile(&p, &BrewOverrides::default()), p);
        let json = serde_json::to_string(&p).unwrap();
        assert_eq!(
            effective_profile_fingerprint(&p, &BrewOverrides::default()).unwrap(),
            crate::profile_fingerprint(&json, None).unwrap()
        );
    }

    /// AC-F6.3 (fingerprint half): the fingerprint is the hash of the bytes
    /// uploaded — each override that changes the frames changes it, and two
    /// override sets producing the same profile share it.
    #[test]
    fn the_fingerprint_follows_the_effective_bytes() {
        let p = declining();
        let base = effective_profile_fingerprint(&p, &BrewOverrides::default()).unwrap();
        for qc in [
            BrewOverrides {
                brew_temp: Some(96.0),
                ..BrewOverrides::default()
            },
            BrewOverrides {
                preinf: Some(9.0),
                ..BrewOverrides::default()
            },
            BrewOverrides {
                dose: Some(19.0),
                ..BrewOverrides::default()
            },
        ] {
            let fp = effective_profile_fingerprint(&p, &qc).unwrap();
            assert_ne!(fp, base, "{qc:?}");
            let effective = effective_crema_profile(&p, &qc);
            let json = serde_json::to_string(&effective).unwrap();
            assert_eq!(fp, crate::profile_fingerprint(&json, None).unwrap());
        }
        // 3 °C (below the range, inside the bounds) and the range's 80 °C min
        // upload the same bytes.
        let over = BrewOverrides {
            brew_temp: Some(3.0),
            ..BrewOverrides::default()
        };
        let at_max = BrewOverrides {
            brew_temp: Some(80.0),
            ..BrewOverrides::default()
        };
        assert_eq!(
            effective_profile_fingerprint(&p, &over).unwrap(),
            effective_profile_fingerprint(&p, &at_max).unwrap()
        );
    }

    #[test]
    fn overrides_clamp_dose_and_yield() {
        let p = crate::blank_profile(&crate::BrewDefaults::default());
        let qc = BrewOverrides {
            dose: Some(45.0),
            yield_g: Some(400.0),
            ..BrewOverrides::default()
        };
        let n = normalize_brew_overrides(&p, &qc);
        // Yield: up to what the ratio stepper reaches (30 g × 5).
        assert_eq!((n.dose, n.yield_g), (Some(30.0), Some(150.0)));
        let ratio = BrewOverrides {
            yield_g: Some(100.0),
            ..BrewOverrides::default()
        };
        assert_eq!(normalize_brew_overrides(&p, &ratio).yield_g, Some(100.0));
        let low = BrewOverrides {
            dose: Some(2.0),
            yield_g: Some(0.0),
            ..BrewOverrides::default()
        };
        let n = normalize_brew_overrides(&p, &low);
        assert_eq!(n.dose, Some(5.0));
        assert_eq!(
            n.yield_g,
            Some(0.0),
            "a yield of 0 (no weight target) is kept"
        );
    }

    #[test]
    fn overrides_json_uses_the_shell_keys() {
        let qc = parse_brew_overrides(Some(r#"{"dose":19,"yield":40,"brewTemp":93.5,"preinf":5}"#))
            .unwrap();
        assert_eq!(qc.dose, Some(19.0));
        assert_eq!(qc.yield_g, Some(40.0));
        assert_eq!(qc.brew_temp, Some(93.5));
        assert_eq!(qc.preinf, Some(5.0));
        assert!(parse_brew_overrides(None).unwrap().is_empty());
        assert!(parse_brew_overrides(Some("{}")).unwrap().is_empty());
        assert!(parse_brew_overrides(Some("null")).unwrap().is_empty());
        // Absent fields stay absent on the way out.
        let out = serde_json::to_string(&BrewOverrides {
            brew_temp: Some(93.0),
            ..BrewOverrides::default()
        })
        .unwrap();
        assert_eq!(out, r#"{"brewTemp":93.0}"#);
    }

    #[test]
    fn json_facades_round_trip() {
        let p = declining();
        let pj = serde_json::to_string(&p).unwrap();
        let ej = effective_crema_profile_json(&pj, Some(r#"{"brewTemp":96}"#)).unwrap();
        let e: CremaProfile = serde_json::from_str(&ej).unwrap();
        assert_eq!(temps(&e), vec![96.0, 92.0, 90.0]);
        let r: BrewTempRange = serde_json::from_str(&brew_temp_range_json(&pj).unwrap()).unwrap();
        assert!(close(r.max, 100.0));
        let mut stale = p.clone();
        stale.brew_temp = 80.0;
        let derived: CremaProfile = serde_json::from_str(
            &with_derived_brew_temp_json(&serde_json::to_string(&stale).unwrap()).unwrap(),
        )
        .unwrap();
        assert!(close(derived.brew_temp, 94.0));
        let dial: PreinfusionDial =
            serde_json::from_str(&preinfusion_dial_json(&pj).unwrap()).unwrap();
        assert!(dial.adjustable && close(dial.seconds, 6.0));
        assert!(close(preinfusion_seconds_json(&pj).unwrap(), 6.0));
        let n: BrewOverrides = serde_json::from_str(
            &normalize_brew_overrides_json(&pj, Some(r#"{"brewTemp":94}"#)).unwrap(),
        )
        .unwrap();
        assert!(n.is_empty());
        assert_eq!(
            effective_profile_fingerprint_json(&pj, None).unwrap(),
            effective_profile_fingerprint(&p, &BrewOverrides::default()).unwrap()
        );
        assert!(effective_crema_profile_json("{", None).is_err());
    }

    #[test]
    fn the_default_segments_have_one_preinfusion_segment() {
        let p = blank_profile(&BrewDefaults::default());
        assert_eq!(p.preinfuse_step_count, 1);
        // The default 8 s pre-infusion lives on the first segment only.
        assert!(close(preinfusion_seconds(&p), 8.0));
        assert!(close(default_segments()[0].time, 8.0));
    }
}

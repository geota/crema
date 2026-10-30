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
//!   profile's displayed brew temperature ([`brew_temp_anchor`]); every step's
//!   temperature moves by `T − A`, so the profile's temperature shape is kept
//!   (de1app `change_espresso_temperature`, reaprime `Profile.adjustTemperature`,
//!   Decenza `framesShiftedToTemperature`). `T` is clamped into
//!   [`brew_temp_range`], so no step leaves the core's temperature bounds.
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
use crate::profile_bounds::{MAX_FRAME_SECONDS, MAX_TEMPERATURE_C, MIN_TEMPERATURE_C};
use typeshare::typeshare;

/// Two override values closer than this are the same value (half the 0.1
/// resolution every Quick Controls stepper uses).
const SAME_VALUE_EPSILON: f32 = 0.05;

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
/// profile's displayed `brew_temp`. A profile without one (absent / 0 / not
/// finite — an old save) falls back to the step mean rounded to the nearest
/// 0.5 °C, the same value [`crate::from_wire`] derives for an import; a
/// profile without positive step temperatures falls back to 92 °C.
#[must_use]
pub fn brew_temp_anchor(profile: &CremaProfile) -> f32 {
    if profile.brew_temp.is_finite() && profile.brew_temp > 0.0 {
        return profile.brew_temp;
    }
    let temps: Vec<f32> = profile
        .segments
        .iter()
        .map(|s| s.temp)
        .filter(|t| t.is_finite() && *t > 0.0)
        .collect();
    if temps.is_empty() {
        return 92.0;
    }
    #[allow(clippy::cast_precision_loss)]
    let mean = temps.iter().sum::<f32>() / temps.len() as f32;
    (mean * 2.0).round() / 2.0
}

/// The brew-temperature dial's range for `profile`: every absolute target in
/// it shifts every step by `T − anchor` without any step leaving
/// [`MIN_TEMPERATURE_C`]..=[`MAX_TEMPERATURE_C`]. The anchor is always in the
/// range (no shift is always allowed, even for a profile authored outside the
/// bounds).
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
    let min = lo.map_or(MIN_TEMPERATURE_C, |lo| anchor + (MIN_TEMPERATURE_C - lo));
    let max = hi.map_or(MAX_TEMPERATURE_C, |hi| anchor + (MAX_TEMPERATURE_C - hi));
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
    round_tenth(total)
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
    let brew_temp = qc
        .brew_temp
        .filter(|t| t.is_finite())
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

    let dose = qc
        .dose
        .filter(|d| d.is_finite() && *d > 0.0)
        .filter(|d| !same(*d, profile.dose));
    let yield_g = qc
        .yield_g
        .filter(|y| y.is_finite() && *y >= 0.0)
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
/// module docs). With no overrides it is `profile` unchanged, so its bytes and
/// fingerprint match the saved profile's.
#[must_use]
pub fn effective_crema_profile(profile: &CremaProfile, qc: &BrewOverrides) -> CremaProfile {
    let qc = normalize_brew_overrides(profile, qc);
    let mut effective = profile.clone();
    if let Some(target) = qc.brew_temp {
        let delta = target - brew_temp_anchor(profile);
        for seg in &mut effective.segments {
            seg.temp += delta;
        }
        effective.brew_temp = target;
    }
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

    /// A three-step declining-temperature profile: 94 / 90 / 88 °C, shown as
    /// 91 °C (its half-degree mean rounded), one 6 s pre-infusion segment.
    fn declining() -> CremaProfile {
        let mut p = blank_profile(&BrewDefaults::default());
        p.segments.truncate(3);
        for (seg, t) in p.segments.iter_mut().zip([94.0, 90.0, 88.0]) {
            seg.temp = t;
        }
        p.segments[0].time = 6.0;
        p.brew_temp = 91.0;
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
        let qc = BrewOverrides {
            brew_temp: Some(93.0),
            ..BrewOverrides::default()
        };
        let e = effective_crema_profile(&p, &qc);
        assert_eq!(temps(&e), vec![96.0, 92.0, 90.0]);
        assert!(close(e.brew_temp, 93.0));
        // The wire steps carry the shift (the bytes the DE1 runs).
        let wire: Vec<f32> = to_wire(&e).steps.iter().map(|s| s.temperature_c).collect();
        assert_eq!(wire, vec![96.0, 92.0, 90.0]);
        // A downward dial moves them down by the same delta.
        let down = effective_crema_profile(
            &p,
            &BrewOverrides {
                brew_temp: Some(89.5),
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
        // Highest step 94 may rise 6 to 100 → 91 + 6; lowest 88 may fall to 0.
        assert!(close(r.max, 97.0), "max {}", r.max);
        assert!(close(r.min, 3.0), "min {}", r.min);
        assert!(close(r.anchor, 91.0));

        let high = effective_crema_profile(
            &p,
            &BrewOverrides {
                brew_temp: Some(105.0),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(temps(&high), vec![100.0, 96.0, 94.0]);
        assert!(close(high.brew_temp, 97.0));
        assert!(temps(&high).iter().all(|t| *t <= MAX_TEMPERATURE_C));

        let low = effective_crema_profile(
            &p,
            &BrewOverrides {
                brew_temp: Some(-20.0),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(temps(&low), vec![6.0, 2.0, 0.0]);
        assert!(temps(&low).iter().all(|t| *t >= MIN_TEMPERATURE_C));

        // The normalized override reports the clamped value.
        let n = normalize_brew_overrides(
            &p,
            &BrewOverrides {
                brew_temp: Some(150.0),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(n.brew_temp, Some(97.0));
    }

    #[test]
    fn the_range_always_contains_the_anchor() {
        // A profile authored above the bound: no upward room, the anchor stays
        // legal.
        let mut p = declining();
        p.segments[0].temp = 101.0;
        let r = brew_temp_range(&p);
        assert!(close(r.max, 91.0));
        assert!(r.min <= 91.0);
    }

    #[test]
    fn an_absent_brew_temp_anchors_on_the_step_mean() {
        let mut p = declining();
        p.brew_temp = 0.0;
        // (94 + 90 + 88) / 3 = 90.67 → 90.5.
        assert!(close(brew_temp_anchor(&p), 90.5));
        let e = effective_crema_profile(
            &p,
            &BrewOverrides {
                brew_temp: Some(91.5),
                ..BrewOverrides::default()
            },
        );
        assert_eq!(temps(&e), vec![95.0, 91.0, 89.0]);
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
        assert_eq!(effective_crema_profile(&p, &qc), p);
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
            brew_temp: Some(91.0),
            preinf: Some(6.0),
        };
        let n = normalize_brew_overrides(&p, &at_anchor);
        assert!(n.is_empty(), "{n:?}");
        assert_eq!(effective_crema_profile(&p, &at_anchor), p);
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
                brew_temp: Some(93.0),
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
        // 150 °C and the range's max upload the same bytes.
        let over = BrewOverrides {
            brew_temp: Some(150.0),
            ..BrewOverrides::default()
        };
        let at_max = BrewOverrides {
            brew_temp: Some(97.0),
            ..BrewOverrides::default()
        };
        assert_eq!(
            effective_profile_fingerprint(&p, &over).unwrap(),
            effective_profile_fingerprint(&p, &at_max).unwrap()
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
        let ej = effective_crema_profile_json(&pj, Some(r#"{"brewTemp":93}"#)).unwrap();
        let e: CremaProfile = serde_json::from_str(&ej).unwrap();
        assert_eq!(temps(&e), vec![96.0, 92.0, 90.0]);
        let r: BrewTempRange = serde_json::from_str(&brew_temp_range_json(&pj).unwrap()).unwrap();
        assert!(close(r.max, 97.0));
        assert!(close(preinfusion_seconds_json(&pj).unwrap(), 6.0));
        let n: BrewOverrides = serde_json::from_str(
            &normalize_brew_overrides_json(&pj, Some(r#"{"brewTemp":91}"#)).unwrap(),
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

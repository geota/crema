//! The gate, `last`, the estimated leg, a returning scale and a disturbed one
//! (`.scratch/machine-control/DESIGN.md` §3.4, §3.5; PR 8).
#![allow(
    clippy::cast_possible_truncation,
    clippy::cast_precision_loss,
    clippy::cast_sign_loss
)]

use de1_domain::session::{Baseline, Blocked};

use crate::scale_trust_tests::{bookoo, hot_water_core, sample, stop_reasons};
use crate::*;

/// A shot with a `target` g weight target, auto-tare off, flowing from Idle
/// at 100 ms with no Preinfusion substate.
pub(super) fn espresso(target: f32) -> CremaCore {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_auto_tare(false);
    core.set_profile_target_weight(Some(target));
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 0);
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 50);
    core.on_notification(Source::De1State, &[4, 5], 100);
    core
}

/// A pour in progress: the clock, and the water that has really left the
/// group.
pub(super) struct Pour {
    pub t: u64,
    pub poured_ml: f32,
    /// The water the cup doesn't get: the headspace, then nothing.
    pub headspace_ml: f32,
}

impl Pour {
    pub(super) fn from(t: u64, headspace_ml: f32) -> Pour {
        Pour {
            t,
            poured_ml: 0.0,
            headspace_ml,
        }
    }

    /// The true weight in the cup, 1 g a ml past the headspace.
    pub(super) fn cup_g(&self) -> f32 {
        (self.poured_ml - self.headspace_ml).max(0.0)
    }
}

/// What reaches the core in one 100 ms step.
pub(super) struct Step {
    /// A DE1 sample arrives.
    pub sample: bool,
    /// The scale's reading, if one arrives.
    pub reading: Option<f32>,
}

/// Run the pour in 100 ms steps up to `until`: `step(t, cup)` says what
/// arrives. Each sample is 2.5 ml/s; while samples are missing the water
/// still flows, and the integrator catches up on the next. Returns the first
/// stop and the true cup weight then.
pub(super) fn run(
    core: &mut CremaCore,
    pour: &mut Pour,
    until: u64,
    mut step: impl FnMut(u64, f32) -> Step,
) -> Option<(StopReason, f32)> {
    while pour.t < until {
        pour.t += 100;
        let what = step(pour.t, pour.cup_g());
        let mut reasons = vec![];
        if what.sample {
            let o =
                core.on_notification(Source::De1ShotSample, &sample((pour.t / 8) as u16), pour.t);
            reasons.extend(stop_reasons(&o));
            pour.poured_ml = pour.poured_ml.max(core.pour_ml());
        } else {
            pour.poured_ml += 0.26;
        }
        if let Some(g) = what.reading {
            let o = core.on_notification(Source::ScaleWeight, &bookoo(g), pour.t + 1);
            reasons.extend(stop_reasons(&o));
        }
        let o = core.on_tick(pour.t + 2);
        reasons.extend(stop_reasons(&o));
        if let Some(reason) = reasons.first() {
            return Some((*reason, pour.cup_g()));
        }
    }
    None
}

/// Samples, and readings of the true cup only while `cup < lose_at` g.
fn lost_at(lose_at: f32) -> impl FnMut(u64, f32) -> Step {
    let mut lost = false;
    move |_, cup| {
        lost |= cup >= lose_at;
        Step {
            sample: true,
            reading: (!lost).then_some(cup),
        }
    }
}

fn near_target(stop: Option<(StopReason, f32)>, reason: StopReason) {
    let (got, cup) = stop.expect("a stop");
    assert_eq!(got, reason, "stopped at a true {cup} g");
    assert!((33.0..39.0).contains(&cup), "stopped at a true {cup} g");
}

/// The capture's "lost for good after the gate" rows: the estimate stops the
/// shot near 36 g.
#[test]
fn a_scale_lost_after_the_gate_stops_on_estimate_near_target() {
    for lose_at in [12.0, 20.0, 30.0] {
        let mut core = espresso(36.0);
        let mut pour = Pour::from(100, 10.0);
        let stop = run(&mut core, &mut pour, 90_000, lost_at(lose_at));
        near_target(stop, StopReason::EstimatedWeight);
    }
}

/// §6: while it estimates, the weight row says so and the shot stays armed.
#[test]
fn the_weight_row_reads_estimating_while_the_scale_is_lost() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 10.0);
    assert_eq!(run(&mut core, &mut pour, 12_000, lost_at(15.0)), None);
    let json: serde_json::Value =
        serde_json::from_str(&core.stop_targets_projection_json()).expect("json");
    assert_eq!(
        json["weightBlockedNote"],
        "Estimating, scale stopped reporting"
    );
    assert_eq!(json["armed"], true);
}

/// Mutation check 7: with the scale lost before water reached the cup there
/// is no estimate. The shot is `Blind` and the backstop bounds it.
#[test]
fn a_scale_lost_before_the_cup_had_weight_never_estimates() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 40.0);
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| Step {
        sample: true,
        reading: (t < 3_000).then_some(cup),
    });
    let (reason, _) = stop.expect("the backstop");
    assert_eq!(reason, StopReason::Volume);
    assert!(
        core.pour_ml() >= 114.0,
        "at the backstop: {} ml",
        core.pour_ml()
    );
}

/// Mutation check 10: a +3.5 g touch for 0.6 s, under the step band, can't
/// open the gate, so a scale lost afterwards leaves the shot `Blind`, not
/// estimating from a cup the water hasn't reached.
#[test]
fn a_bump_never_opens_the_gate() {
    let mut core = espresso(36.0);
    // 4 g of early drips, then the headspace keeps the rest out of the cup.
    let mut pour = Pour::from(100, 30.0);
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| Step {
        sample: true,
        reading: (t < 3_000).then(|| {
            cup + 4.0
                + if (1_500..2_100).contains(&t) {
                    3.5
                } else {
                    0.0
                }
        }),
    });
    let (reason, _) = stop.expect("the backstop");
    assert_eq!(reason, StopReason::Volume, "no estimate from an empty cup");
}

/// Mutation check 8: a passed limit doesn't rearm when the scale is lost
/// after the gate; the estimate stops the shot near its target.
#[test]
fn a_passed_limit_never_rearms_under_lost() {
    let mut core = espresso(36.0);
    core.set_profile_volume_limit(Some(36.0));
    let mut pour = Pour::from(100, 20.0);
    let stop = run(&mut core, &mut pour, 90_000, lost_at(20.0));
    near_target(stop, StopReason::EstimatedWeight);
}

/// A scale back within reach of `last` is adopted: weight stops the shot.
#[test]
fn a_returning_scale_within_reach_is_adopted() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 10.0);
    let mut gap = 0..0;
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| {
        if gap.is_empty() && cup >= 20.0 {
            gap = t..t + 1_500;
        }
        Step {
            sample: true,
            reading: (!gap.contains(&t)).then_some(cup),
        }
    });
    near_target(stop, StopReason::Weight);
}

/// P11, and mutation check 11: a returning scale reading far over what the
/// cup could hold (+250 g on return) is refused, and the estimate finishes.
#[test]
fn a_returning_scale_far_over_its_last_is_refused() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 10.0);
    let mut gap = 0..0;
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| {
        if gap.is_empty() && cup >= 20.0 {
            gap = t..t + 1_500;
        }
        let reading = if gap.contains(&t) {
            None
        } else if t >= gap.end && !gap.is_empty() {
            Some(cup + 250.0)
        } else {
            Some(cup)
        };
        Step {
            sample: true,
            reading,
        }
    });
    near_target(stop, StopReason::EstimatedWeight);
    let refused = core.session.map(|s| s.baseline);
    assert!(
        matches!(
            refused,
            Some(Baseline::Refused {
                why: Blocked::ScaleRezeroed,
                last: Some(_)
            })
        ),
        "{refused:?}"
    );
}

/// A scale that re-zeroed while away reads under `last − 3 g`: refused too.
#[test]
fn a_returning_scale_that_rezeroed_is_refused() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 10.0);
    let mut gap = 0..0;
    let mut zero = 0.0;
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| {
        if gap.is_empty() && cup >= 20.0 {
            gap = t..t + 1_500;
        }
        if t == gap.end {
            zero = cup;
        }
        Step {
            sample: true,
            reading: (!gap.contains(&t)).then_some(cup - zero),
        }
    });
    near_target(stop, StopReason::EstimatedWeight);
}

/// Mutation check 13, and the model's `Bump 18`: a hand on the cup after the
/// gate is a disturbance. The estimate finishes from `last`, near a true
/// 36 g, where the weight leg alone would have stopped at 18.
#[test]
fn a_hand_on_the_cup_after_the_gate_hands_over_to_the_estimate() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 10.0);
    let stop = run(&mut core, &mut pour, 90_000, |_, cup| Step {
        sample: true,
        reading: Some(if cup >= 20.0 { cup + 18.0 } else { cup }),
    });
    near_target(stop, StopReason::EstimatedWeight);
}

/// Q6: a physical tare at 20 g of 36 is a downward step: the estimate stops
/// the shot near target instead of 20 g over.
#[test]
fn q6_a_physical_tare_mid_shot_stops_near_target_on_estimate() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 10.0);
    let stop = run(&mut core, &mut pour, 90_000, |_, cup| Step {
        sample: true,
        reading: Some(if cup >= 20.0 { cup - 20.0 } else { cup }),
    });
    near_target(stop, StopReason::EstimatedWeight);
}

/// §3.5, before the gate: a spoon set on the scale while the cup is still
/// empty holds still, so the zero moves by the step and weight stays live. The
/// shot stops by weight at a true 36 g.
#[test]
fn a_load_at_rest_before_the_gate_moves_the_zero() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 30.0);
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| Step {
        sample: true,
        reading: Some(if t >= 2_000 { cup + 18.0 } else { cup }),
    });
    near_target(stop, StopReason::Weight);
}

/// §3.5, before the gate: a hand still moving after 2 s refuses the scale
/// as disturbed, which is `Blind`: the backstop bounds the shot at once.
#[test]
fn a_hand_still_moving_before_the_gate_leaves_the_shot_blind() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 60.0);
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| Step {
        sample: true,
        reading: Some(if t >= 2_000 {
            cup + 18.0 + ((t / 100) % 2) as f32 * 3.0
        } else {
            cup
        }),
    });
    let (reason, _) = stop.expect("the backstop");
    assert_eq!(reason, StopReason::Volume);
    assert!(core.pour_ml() >= 114.0);
    assert_eq!(
        core.weight_stop_blocked_by(),
        Some("scale-disturbed"),
        "the row asks for a hand"
    );
}

/// I18: `last` never pairs a reading with a sample over 1 s old. With samples
/// missing for 4.5 s while the scale stays live, then the scale lost, the
/// estimate counts from the last pairing, not from a fresh weight beside a
/// volume that jumps when samples return.
#[test]
fn last_pairs_a_reading_only_with_a_fresh_sample() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 10.0);
    let mut muted = 0..0;
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| {
        if muted.is_empty() && cup >= 8.0 {
            muted = t..t + 4_500;
        }
        Step {
            sample: !muted.contains(&t),
            reading: (muted.is_empty() || t < muted.end).then_some(cup),
        }
    });
    // Pairing allows a sample up to 1 s old, so `last` can sit up to a
    // second's pour (2.6 ml here) behind its weight: within I13's 6 g. Paired
    // with the stale volume instead, the estimate fires near 23 g.
    let (reason, cup) = stop.expect("a stop");
    assert_eq!(reason, StopReason::EstimatedWeight);
    assert!((30.0..39.0).contains(&cup), "stopped at a true {cup} g");
}

/// P2: hot water whose scale stalls mid-pour stops near its target on the
/// estimate, not at `V` by volume.
#[test]
fn p2_hot_water_whose_scale_stalls_stops_near_target_on_estimate() {
    let mut core = hot_water_core();
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 20);
    core.on_notification(Source::De1State, &[6, 5], 100);
    assert_eq!(core.hot_water_stop_target_g(), Some(120.0));
    let mut pour = Pour::from(100, 0.0);
    let (reason, cup) = run(&mut core, &mut pour, 90_000, lost_at(60.0)).expect("a stop");
    assert_eq!(reason, StopReason::EstimatedWeight);
    assert!((115.0..122.0).contains(&cup), "stopped at a true {cup} g");
}

/// §3.5: hot water disturbed before the gate is `Blind`, so `V` stands in.
#[test]
fn hot_water_disturbed_before_the_gate_stops_at_its_volume() {
    let mut core = hot_water_core();
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 20);
    core.on_notification(Source::De1State, &[6, 5], 100);
    let mut pour = Pour::from(100, 20.0);
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| Step {
        sample: true,
        reading: Some(if t >= 1_000 { cup + 30.0 } else { cup }),
    });
    let (reason, _) = stop.expect("a stop");
    assert_eq!(reason, StopReason::Volume);
    assert!(
        (119.0..125.0).contains(&core.pour_ml()),
        "{} ml",
        core.pour_ml()
    );
}

/// An estimated stop is stored as `Weight` with `stop_estimated`, and never
/// trains the drip model.
#[test]
fn an_estimated_stop_is_stored_as_weight_and_never_learned() {
    let mut core = espresso(36.0);
    let before = core.saw_model_json();
    let mut pour = Pour::from(100, 10.0);
    let stop = run(&mut core, &mut pour, 90_000, lost_at(20.0));
    assert_eq!(stop.map(|s| s.0), Some(StopReason::EstimatedWeight));
    core.on_notification(Source::De1State, &[2, 0], pour.t + 100);
    core.on_tick(pour.t + 10_000);
    let shot = core.pending_shot.as_ref().expect("a completed shot");
    assert_eq!(shot.stop_reason, Some(StopReason::EstimatedWeight));
    let stored = core
        .take_stored_shot(de1_domain::ShotCaptureContext::default())
        .expect("a stored shot");
    assert_eq!(stored.stop_reason, Some(StopReason::Weight));
    assert!(stored.stop_estimated);
    assert_eq!(core.saw_model_json(), before, "no drip learning");
}

/// A scale back from a gap before the gate with a hand on the cup: the hand
/// is adopted, since `Tared` has no returning check. When it lifts, the step
/// settles back into what an empty cup reads, so the zero stays where it was.
/// Moving it would carry the hand into every later reading, and the shot would
/// stop 7 g early.
#[test]
fn a_hand_lifting_off_an_empty_cup_leaves_the_zero_alone() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 40.0);
    let stop = run(&mut core, &mut pour, 90_000, |t, cup| Step {
        sample: true,
        reading: match t {
            600..6_600 => None,
            6_600..6_900 => Some(cup + 7.0),
            _ => Some(cup),
        },
    });
    near_target(stop, StopReason::Weight);
}

/// REVIEW-2 #6: a trusted reading under 3 g sends the session back to
/// `Tared`, so `last` can't ride back to an empty cup.
#[test]
fn a_trusted_cup_reading_under_3_g_falls_back_to_tared() {
    let mut core = espresso(36.0);
    let mut pour = Pour::from(100, 0.0);
    let mut down: Option<f32> = None;
    run(&mut core, &mut pour, 12_000, |_, cup| {
        if cup >= 10.0 {
            down = Some(down.map_or(cup, |g| g - 0.4));
        }
        Step {
            sample: true,
            reading: Some(down.map_or(cup, |g| g.max(1.0))),
        }
    });
    assert_eq!(core.session.map(|s| s.baseline), Some(Baseline::Tared));
}

/// The band never flags a pour: hot water climbing at up to 12 g/s, the
/// band's own rate, read at 5 Hz with one reading a second late, keeps its
/// weight stop and stops by weight at its 120 g.
#[test]
fn a_fast_hot_water_pour_is_never_a_disturbance() {
    for rate in [4.0f32, 8.0, 12.0] {
        let mut core = hot_water_core();
        core.on_notification(Source::De1State, &[2, 0], 10);
        core.on_notification(Source::ScaleWeight, &bookoo(0.0), 20);
        core.on_notification(Source::De1State, &[6, 5], 100);
        let mut stop = None;
        for t in (200..30_000u64).step_by(100) {
            let o = core.on_notification(Source::De1ShotSample, &sample((t / 8) as u16), t);
            stop = stop.or(stop_reasons(&o).first().copied());
            // 5 Hz, and nothing between 3 s and 4 s.
            if t % 200 == 0 && !(3_000..4_000).contains(&t) {
                let g = (t - 100) as f32 / 1_000.0 * rate;
                let o = core.on_notification(Source::ScaleWeight, &bookoo(g), t + 1);
                stop = stop.or(stop_reasons(&o).first().copied());
            }
            if stop.is_some() {
                break;
            }
        }
        let baseline = core.session.map(|s| s.baseline);
        assert!(
            !matches!(baseline, Some(Baseline::Refused { .. })),
            "{rate} g/s: {baseline:?}"
        );
        assert_eq!(stop, Some(StopReason::Weight), "{rate} g/s");
    }
}

/// A stop the machine ignored needed a retry: what landed after it is
/// overshoot, not drip, so it never trains the drip model. Otherwise the
/// next shot would stop short by the learned "drip".
#[test]
fn a_retried_stop_never_trains_the_drip_model() {
    let mut core = espresso(36.0);
    let before = core.saw_model_json();
    let mut pour = Pour::from(100, 10.0);
    let stop = run(&mut core, &mut pour, 90_000, |_, cup| Step {
        sample: true,
        reading: Some(cup),
    });
    assert_eq!(stop.map(|s| s.0), Some(StopReason::Weight));
    // The machine ignores the stop: it keeps pouring and the core retries.
    let until = pour.t + 3_000;
    run(&mut core, &mut pour, until, |_, cup| Step {
        sample: true,
        reading: Some(cup),
    });
    core.on_notification(Source::De1State, &[4, 6], pour.t + 100);
    core.on_notification(Source::De1State, &[2, 0], pour.t + 1_100);
    for t in (pour.t + 1_200..pour.t + 12_000).step_by(100) {
        core.on_notification(Source::ScaleWeight, &bookoo(pour.cup_g()), t);
        core.on_tick(t);
    }
    assert!(core.pending_shot.is_some() || core.post_shot_settle.is_none());
    assert_eq!(core.saw_model_json(), before, "no drip learning");
}

/// §3.5's known limit, auto-tare off on a loaded scale: the untared-cup guard
/// (PR 5) acts only over 50 g in the first 3 s of flow. Last shot's 35 g is
/// under it, so the session takes it as the user's zero and weight stops the
/// shot almost at once; there is no weight-stop guarantee, so tare first. A
/// 60 g cup is over it: the guard latches a virtual zero, and the shot stops
/// on target.
#[test]
fn a_loaded_scale_with_auto_tare_off_is_guarded_only_over_50_g() {
    for (load, early) in [(35.0f32, true), (60.0, false)] {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        core.set_auto_tare(false);
        core.set_profile_target_weight(Some(36.0));
        core.on_notification(Source::ScaleWeight, &bookoo(load), 0);
        core.on_notification(Source::De1State, &[2, 0], 10);
        core.on_notification(Source::ScaleWeight, &bookoo(load), 50);
        core.on_notification(Source::De1State, &[4, 5], 100);
        let mut pour = Pour::from(100, 10.0);
        let (reason, cup) = run(&mut core, &mut pour, 90_000, |_, cup| Step {
            sample: true,
            reading: Some(cup + load),
        })
        .expect("a stop");
        assert_eq!(reason, StopReason::Weight, "{load} g");
        assert_eq!(cup < 10.0, early, "{load} g: stopped at a true {cup} g");
    }
}

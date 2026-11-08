//! A session's auto-stop always exists while it flows, and hot water runs
//! through it (`.scratch/machine-control/DESIGN.md` §3.3; PR 7).
#![allow(clippy::cast_possible_truncation, clippy::cast_precision_loss)]

use de1_domain::session::ActiveMode;
use de1_protocol::MachineState;

use crate::scale_trust_tests::{bookoo, hot_water_core, sample, stop_reasons};
use crate::*;

/// The first stop from `from_ms` to `to_ms`, with a sample every 100 ms (each
/// 0.25 ml) and a reading of `grams(t)` after it. Returns the reason, the
/// time, and the water poured since `from_ms` by the samples' own count.
fn first_stop(
    core: &mut CremaCore,
    from_ms: u64,
    to_ms: u64,
    grams: impl Fn(u64) -> Option<f32>,
) -> Option<(StopReason, u64, f32)> {
    let mut samples = 0u32;
    for t in (from_ms..to_ms).step_by(100) {
        let o = core.on_notification(Source::De1ShotSample, &sample((t / 8) as u16), t);
        samples += 1;
        let ml = samples as f32 * 0.25;
        if let Some(reason) = stop_reasons(&o).first() {
            return Some((*reason, t, ml));
        }
        if let Some(g) = grams(t) {
            let o = core.on_notification(Source::ScaleWeight, &bookoo(g), t + 1);
            if let Some(reason) = stop_reasons(&o).first() {
                return Some((*reason, t, ml));
            }
        }
    }
    None
}

/// #316.1: Heating, then a substate this app can't name. That's first flow
/// (§3.3): the clock starts, the fallback tare goes out, and the shot stops by
/// weight at 36 g. Before, the shot never started flowing in the core and ran
/// with no stop.
#[test]
fn heating_then_an_unknown_substate_stops_at_the_target() {
    let mut core = CremaCore::new();
    core.connect_scale("BOOKOO_SC", &[]);
    core.set_profile_target_weight(Some(36.0));
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 0);
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::De1State, &[4, 1], 100);
    assert!(core.auto_stop_view().is_none(), "heating isn't flow");
    core.on_notification(Source::De1State, &[4, 21], 200);
    assert_eq!(core.flow_started, Some(Duration::from_millis(200)));
    let view = core.auto_stop_view().expect("an auto-stop at first flow");
    assert_eq!(view.mode, ActiveMode::Espresso);
    assert_eq!(view.started, Duration::from_millis(200));
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 300);
    // 2 g/s into the cup.
    let grams = |t: u64| Some(t.saturating_sub(400) as f32 * 0.002);
    let (reason, at, _) = first_stop(&mut core, 400, 60_000, grams).expect("a stop");
    assert_eq!(reason, StopReason::Weight);
    let g = grams(at).unwrap_or_default();
    assert!((34.0..37.0).contains(&g), "stopped at {g} g");
}

/// #316.1, for every leg: a shot with no leg at first flow still has its
/// auto-stop, so a max time set mid-shot stops it, counted from flow. It
/// isn't "armed" until it has a leg (§3.3).
#[test]
fn a_shot_with_no_leg_at_first_flow_takes_one_set_later() {
    let mut core = CremaCore::new();
    core.on_notification(Source::De1State, &[2, 0], 0);
    core.on_notification(Source::De1State, &[4, 1], 100);
    core.on_notification(Source::De1State, &[4, 5], 1_000);
    assert!(core.auto_stop_view().is_some(), "an auto-stop with no legs");
    assert!(!core.stop_targets_projection().0, "no leg: not armed");
    core.set_max_shot_duration(Some(10.0));
    assert!(core.stop_targets_projection().0, "armed with a leg");
    let (reason, at, _) = first_stop(&mut core, 1_100, 30_000, |_| None).expect("a stop");
    assert_eq!(reason, StopReason::MaxTime);
    assert_eq!(at, 11_000, "10 s from first flow");
}

/// Hot water's volume counts from its own start: `V` (120 ml) after an
/// espresso that poured 50 ml still stops at 120 ml of hot water. The scale
/// was fresh when the packet went out, so it holds the 250 ml backstop, and
/// stale when the pour began, so no weight stop is armed.
#[test]
fn hot_water_after_a_shot_counts_its_volume_from_its_start() {
    let mut core = hot_water_core();
    core.on_notification(Source::De1State, &[2, 0], 10);
    core.on_notification(Source::De1State, &[4, 5], 100);
    for t in (200..20_200u64).step_by(100) {
        core.on_notification(Source::De1ShotSample, &sample((t / 8) as u16), t);
        core.on_notification(Source::ScaleWeight, &bookoo(0.0), t + 1);
    }
    assert!(
        core.pour_ml() > 45.0,
        "the shot poured {} ml",
        core.pour_ml()
    );
    core.on_notification(Source::De1State, &[2, 0], 20_300);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 20_301);
    core.on_notification(Source::De1State, &[6, 5], 22_400);
    assert_eq!(core.pour_ml(), 0.0, "a new session's volume");
    assert_eq!(core.hot_water_stop_target_g(), None, "stale at the start");
    let (reason, _, ml) = first_stop(&mut core, 22_500, 90_000, |_| None).expect("a stop");
    assert_eq!(reason, StopReason::Volume);
    // The integrator locks to 60 Hz on these sample clocks and counts 0.26 ml
    // a sample, so 120 ml is 115.5 by this count. Counted from the shot's
    // 50 ml it would be about 70.
    assert!(
        (110.0..122.0).contains(&ml),
        "stopped at {ml} ml of hot water"
    );
}

/// A hot-water pour has its auto-stop from its start until it ends, and an
/// espresso that follows it on the next report gets its own, not the pour's.
#[test]
fn each_mode_runs_its_own_auto_stop() {
    let mut core = hot_water_core();
    let idle = MachineState::Idle as u8;
    core.on_notification(Source::De1State, &[idle, 0], 10);
    core.on_notification(Source::De1State, &[6, 1], 100);
    let view = core.auto_stop_view().expect("hot water's auto-stop");
    assert_eq!(view.mode, ActiveMode::HotWater);
    assert_eq!(view.started, Duration::from_millis(100));
    core.on_notification(Source::De1State, &[idle, 0], 200);
    assert!(core.auto_stop_view().is_none(), "the pour ended");
    core.on_notification(Source::De1State, &[6, 5], 300);
    core.on_notification(Source::De1State, &[4, 5], 400);
    let view = core.auto_stop_view().expect("the shot's auto-stop");
    assert_eq!(view.mode, ActiveMode::Espresso);
    assert_eq!(view.started, Duration::from_millis(400));
    core.on_notification(Source::De1State, &[idle, 0], 500);
    assert!(core.auto_stop_view().is_none());
}

/// §3.5: with a trusted weight stop armed, hot water's `V` leg stands down,
/// so the vessel's weight decides even when the flow meter reads high. Here
/// the cup gets 0.8 g per ml counted: weight stops it at 120 g, past 120 ml.
#[test]
fn hot_water_with_a_trusted_weight_stop_lets_weight_decide() {
    let mut core = hot_water_core();
    core.on_notification(Source::De1State, &[MachineState::Idle as u8, 0], 10);
    core.on_notification(Source::ScaleWeight, &bookoo(0.0), 20);
    core.on_notification(Source::De1State, &[6, 5], 100);
    assert_eq!(core.hot_water_stop_target_g(), Some(120.0), "armed");
    let mut counted = 0.0f32;
    let mut stop = None;
    for t in (200..90_000u64).step_by(100) {
        let o = core.on_notification(Source::De1ShotSample, &sample((t / 8) as u16), t);
        counted = core.pour_ml();
        if let Some(reason) = stop_reasons(&o).first() {
            stop = Some(*reason);
            break;
        }
        let o = core.on_notification(Source::ScaleWeight, &bookoo(counted * 0.8), t + 1);
        if let Some(reason) = stop_reasons(&o).first() {
            stop = Some(*reason);
            break;
        }
    }
    assert_eq!(stop, Some(StopReason::Weight));
    assert!(counted > 140.0, "stopped at {counted} ml counted");
}

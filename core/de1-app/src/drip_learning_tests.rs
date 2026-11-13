//! The drip model learns only from a settle that ran its course: a settle
//! cut short measured part of the drip, and would teach a short one
//! (REVIEW-399 S1).
use crate::scale_estimate_tests::{Pour, Step, espresso, run};
use crate::scale_trust_tests::bookoo;
use crate::{CremaCore, Source, StopReason};

/// A weight stop the machine honours, its Idle, and the first drips: the
/// shot is in its post-shot settle. Returns the clock and the cup's weight.
fn honoured_stop_into_settle(core: &mut CremaCore) -> (u64, f32) {
    let mut pour = Pour::from(100, 10.0);
    let stop = run(core, &mut pour, 90_000, |_, cup| Step {
        sample: true,
        reading: Some(cup),
    });
    assert_eq!(stop.map(|s| s.0), Some(StopReason::Weight));
    let (t, cup) = (pour.t, pour.cup_g());
    core.on_notification(Source::De1State, &[4, 6], t + 100);
    core.on_notification(Source::De1State, &[2, 0], t + 300);
    core.on_notification(Source::ScaleWeight, &bookoo(cup + 0.4), t + 400);
    core.on_notification(Source::ScaleWeight, &bookoo(cup + 0.8), t + 500);
    assert!(core.post_shot_settle.is_some());
    (t + 500, cup + 0.8)
}

/// A settle that runs its course trains the drip model.
#[test]
fn a_full_settle_trains_the_drip_model() {
    let mut core = espresso(36.0);
    let before = core.saw_model_json();
    let (t, cup) = honoured_stop_into_settle(&mut core);
    for at in (t + 100..t + 4_000).step_by(100) {
        core.on_notification(Source::ScaleWeight, &bookoo(cup + 1.0), at);
        core.on_tick(at);
    }
    assert!(core.post_shot_settle.is_none());
    assert_ne!(core.saw_model_json(), before, "a full settle learns");
}

/// A new shot starting inside the last one's settle completes it at once,
/// with the drip so far: it trains nothing.
#[test]
fn a_settle_cut_short_by_the_next_shot_never_trains_the_drip_model() {
    let mut core = espresso(36.0);
    let before = core.saw_model_json();
    let (t, _) = honoured_stop_into_settle(&mut core);
    core.on_notification(Source::De1State, &[4, 1], t + 100);
    assert!(core.post_shot_settle.is_none(), "the start completed it");
    assert!(core.shot_started.is_some());
    assert_eq!(core.saw_model_json(), before, "no drip learning");
}

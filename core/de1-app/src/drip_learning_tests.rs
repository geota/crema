//! The drip model learns only from a settle that ran its course: a settle
//! cut short measured part of the drip, and would teach a short one
//! (REVIEW-399 S1). The control, a full settle that learns, is
//! `scale_estimate_tests::a_settle_cut_short_by_a_reset_never_trains_the_drip_model`.
use crate::Source;
use crate::scale_estimate_tests::{espresso, honoured_stop_into_settle};
use crate::scale_trust_tests::bookoo;

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

/// The `final_weight` of the `ShotCompleted` in `events`, if one is there.
fn completed_weight(events: &[crate::Event]) -> Option<Option<f32>> {
    events.iter().find_map(|e| match e {
        crate::Event::ShotCompleted { final_weight, .. } => Some(*final_weight),
        _ => None,
    })
}

/// Hot water started while the drip still lands (Decenza `6f81941b`): the
/// settle ends at once with the last reading before the water, the water
/// never counts toward the shot, and the cut-short drip trains nothing.
#[test]
fn hot_water_during_the_settle_ends_it_before_the_water_lands() {
    let mut core = espresso(36.0);
    let before = core.saw_model_json();
    let (t, cup) = honoured_stop_into_settle(&mut core);
    let o = core.on_notification(Source::De1State, &[6, 1], t + 100);
    let w = completed_weight(&o.events)
        .expect("the hot water completed the shot")
        .expect("a weight");
    assert!(w <= cup + 0.5, "{w} g, the cup held {cup} g");
    assert!(core.post_shot_settle.is_none());
    // The hot water lands: nothing reopens the shot.
    for at in (t + 200..t + 4_000).step_by(100) {
        let o = core.on_notification(Source::ScaleWeight, &bookoo(cup + 60.0), at);
        assert!(completed_weight(&o.events).is_none());
        assert!(completed_weight(&core.on_tick(at).events).is_none());
    }
    assert_eq!(core.saw_model_json(), before, "no drip learning");
}

/// With a steady average seen during the settle, hot water saves that average
/// and the drip still trains the model.
#[test]
fn hot_water_after_a_steady_settle_saves_it_and_learns() {
    let mut core = espresso(36.0);
    let before = core.saw_model_json();
    let (t, cup) = honoured_stop_into_settle(&mut core);
    // The cup holds still until the settle sees a steady average.
    let began = core.post_shot_settle.as_ref().map(|s| s.began);
    let mut at = t;
    while core.shot_metrics.clean_at < began {
        at += 50;
        core.on_notification(Source::ScaleWeight, &bookoo(cup + 1.0), at);
        assert!(at < t + 3_000, "no steady average");
    }
    assert!(core.post_shot_settle.is_some(), "still settling");
    let o = core.on_notification(Source::De1State, &[6, 1], at + 20);
    let w = completed_weight(&o.events)
        .expect("the hot water completed the shot")
        .expect("a weight");
    // The settle's average of the smoothed weight, not the water to come.
    assert!(
        (w - (cup + 1.0)).abs() < 1.0,
        "{w} g, the cup held {}",
        cup + 1.0
    );
    assert_ne!(core.saw_model_json(), before, "a clean settle learns");
}

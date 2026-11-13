//! The drip model learns only from a settle that ran its course: a settle
//! cut short measured part of the drip, and would teach a short one
//! (REVIEW-399 S1). The control, a full settle that learns, is
//! `scale_estimate_tests::a_settle_cut_short_by_a_reset_never_trains_the_drip_model`.
use crate::Source;
use crate::scale_estimate_tests::{espresso, honoured_stop_into_settle};

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

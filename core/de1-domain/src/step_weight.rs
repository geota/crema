//! Per-step weight exits — the app-side half of a profile step's `weight`.
//!
//! A v2 / de1app profile step may carry a target `weight` (grams): "leave this
//! step once the cup holds this much" (A-Flow's Infuse: `weight 3.6`). The DE1
//! frame has no weight field, so the machine can't enforce it — the app has to
//! watch the scale and command `SkipToNext` itself, as decaid's
//! `ShotSequencer._handleStepWeightExit` does (c89cd451).
//!
//! [`StepWeightExit`] is the sans-IO decision: feed it the current frame and
//! the projected weight and it says when to send `SkipToNext`. The rules:
//!
//! - **Projection.** The caller passes the same sensor-lag projection SAW uses
//!   (robust weight + mass flow × the legacy lead), so a step leaves on target
//!   rather than one lag late.
//! - **One skip per frame.** Once a skip was sent for a frame, no second one is
//!   sent for it — a duplicate would land on the *next* frame and skip that too.
//! - **Retry on reject.** decaid retries a skip the transport rejected. Crema's
//!   core sees no write result, so "rejected" is observed the only way it can
//!   be: the DE1 is still on the same frame [`SKIP_RETRY_AFTER`] later. Then the
//!   skip is re-sent, up to [`SKIP_RETRY_MAX`] times.
//! - **Scale only.** No scale, no weight, no skip (the caller only feeds this
//!   from scale readings and passes `scale_connected`).
//! - **Last frame vs SAW.** Skipping the last frame ends the shot. decaid does
//!   that unconditionally; Crema does it only when no stop-at-weight target is
//!   armed, so a last-step weight can never cut a shot short of the SAW target
//!   (SAW owns the end of the shot, with its drip model).

use std::time::Duration;

use crate::profile::Profile;

/// Re-send a skip if the DE1 is still on the same frame this long after it
/// was sent (a DE1 reports its frame ~4–5 times a second).
pub const SKIP_RETRY_AFTER: Duration = Duration::from_millis(600);

/// Give up re-sending a frame's skip after this many retries.
pub const SKIP_RETRY_MAX: u32 = 5;

/// A skip sent and not yet seen to take effect.
#[derive(Debug, Clone, Copy, PartialEq)]
struct PendingSkip {
    frame: u8,
    last_push: Duration,
    retries: u32,
}

/// Decides when a step's weight target sends `SkipToNext`. See the module
/// docs for the rules.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct StepWeightExit {
    /// Per-frame weight target, grams (`None` = no weight exit).
    weights: Vec<Option<f32>>,
    /// The frame the DE1 last reported for this shot.
    frame: Option<u8>,
    /// The skip in flight, if any.
    pending: Option<PendingSkip>,
    /// Frames a skip has already been sent for this shot.
    skipped: Vec<u8>,
}

impl StepWeightExit {
    /// An arbiter for the given per-frame weight targets (non-positive values
    /// count as "no weight exit").
    #[must_use]
    pub fn new(weights: Vec<Option<f32>>) -> Self {
        let weights = weights
            .into_iter()
            .map(|w| w.filter(|g| g.is_finite() && *g > 0.0))
            .collect();
        Self {
            weights,
            ..Self::default()
        }
    }

    /// An arbiter for `profile`'s step weights.
    #[must_use]
    pub fn for_profile(profile: &Profile) -> Self {
        Self::new(profile.steps.iter().map(|s| s.weight).collect())
    }

    /// Whether any step carries a weight exit.
    #[must_use]
    pub fn has_weight_exits(&self) -> bool {
        self.weights.iter().any(Option::is_some)
    }

    /// Forget the previous shot's frame and skips. Call at every shot start.
    pub fn reset_shot(&mut self) {
        self.frame = None;
        self.pending = None;
        self.skipped.clear();
    }

    /// The DE1 reported `frame`. A frame change settles a pending skip (it
    /// took effect, or the machine moved on by itself).
    pub fn on_frame(&mut self, frame: u8) {
        self.frame = Some(frame);
        if self.pending.is_some_and(|p| p.frame != frame) {
            self.pending = None;
        }
    }

    /// Feed a projected weight. Returns `true` when the caller should send
    /// `SkipToNext` now — the first time the current frame's target is
    /// reached, or as a retry while the DE1 hasn't left the frame.
    ///
    /// `saw_armed`: a stop-at-weight target is armed for this shot (see the
    /// last-frame rule).
    pub fn on_weight(
        &mut self,
        projected_g: f32,
        now: Duration,
        scale_connected: bool,
        saw_armed: bool,
    ) -> bool {
        if !scale_connected {
            return false;
        }
        let Some(frame) = self.frame else {
            return false;
        };
        // Retry a skip the DE1 has not acted on.
        if let Some(pending) = self.pending.as_mut()
            && pending.frame == frame
        {
            if now.saturating_sub(pending.last_push) >= SKIP_RETRY_AFTER
                && pending.retries < SKIP_RETRY_MAX
            {
                pending.retries += 1;
                pending.last_push = now;
                return true;
            }
            return false;
        }
        if self.skipped.contains(&frame) {
            return false;
        }
        let Some(target) = self.weights.get(usize::from(frame)).copied().flatten() else {
            return false;
        };
        let is_last = usize::from(frame) + 1 >= self.weights.len();
        if is_last && saw_armed {
            return false;
        }
        if !(projected_g.is_finite() && projected_g >= target) {
            return false;
        }
        self.skipped.push(frame);
        self.pending = Some(PendingSkip {
            frame,
            last_push: now,
            retries: 0,
        });
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ms(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    /// Three frames; frame 1 exits at 3.6 g (A-Flow's Infuse).
    fn aflow_like() -> StepWeightExit {
        StepWeightExit::new(vec![None, Some(3.6), None])
    }

    #[test]
    fn no_skip_without_a_scale() {
        let mut a = aflow_like();
        a.on_frame(1);
        assert!(!a.on_weight(10.0, ms(1_000), false, false));
    }

    #[test]
    fn no_skip_on_a_frame_without_a_weight() {
        let mut a = aflow_like();
        a.on_frame(0);
        assert!(!a.on_weight(50.0, ms(1_000), true, false));
    }

    #[test]
    fn skips_once_the_projected_weight_reaches_the_target() {
        let mut a = aflow_like();
        a.on_frame(1);
        assert!(!a.on_weight(3.5, ms(1_000), true, false));
        assert!(a.on_weight(3.6, ms(1_100), true, false));
    }

    #[test]
    fn a_single_skip_per_frame() {
        let mut a = aflow_like();
        a.on_frame(1);
        assert!(a.on_weight(4.0, ms(1_000), true, false));
        // Readings keep arriving before the DE1 reports the new frame: no
        // duplicate (it would skip frame 2 as well).
        assert!(!a.on_weight(4.2, ms(1_100), true, false));
        assert!(!a.on_weight(4.4, ms(1_300), true, false));
        // The DE1 moves on; frame 2 has no weight.
        a.on_frame(2);
        assert!(!a.on_weight(9.0, ms(1_400), true, false));
    }

    #[test]
    fn retries_while_the_de1_stays_on_the_frame() {
        let mut a = aflow_like();
        a.on_frame(1);
        assert!(a.on_weight(4.0, ms(1_000), true, false));
        // Still frame 1 after SKIP_RETRY_AFTER: the skip was lost — resend.
        a.on_frame(1);
        assert!(!a.on_weight(4.1, ms(1_500), true, false));
        assert!(a.on_weight(4.2, ms(1_600), true, false));
        // The retry worked: the frame changes and nothing more is sent.
        a.on_frame(2);
        assert!(!a.on_weight(4.3, ms(2_400), true, false));
    }

    #[test]
    fn retries_are_bounded() {
        let mut a = aflow_like();
        a.on_frame(1);
        assert!(a.on_weight(4.0, ms(0), true, false));
        let mut sent = 0;
        for i in 1..=20u64 {
            if a.on_weight(4.0, ms(i * 600), true, false) {
                sent += 1;
            }
        }
        assert_eq!(sent, SKIP_RETRY_MAX);
    }

    #[test]
    fn the_last_frame_yields_to_an_armed_saw() {
        let mut a = StepWeightExit::new(vec![None, Some(30.0)]);
        a.on_frame(1);
        // SAW armed: skipping the last frame would end the shot before SAW.
        assert!(!a.on_weight(31.0, ms(1_000), true, true));
        // No SAW target: the last-step weight ends the shot (decaid).
        assert!(a.on_weight(31.0, ms(1_100), true, false));
    }

    #[test]
    fn an_earlier_frame_skips_even_with_saw_armed() {
        let mut a = aflow_like();
        a.on_frame(1);
        assert!(a.on_weight(3.6, ms(1_000), true, true));
    }

    #[test]
    fn reset_shot_rearms_every_frame() {
        let mut a = aflow_like();
        a.on_frame(1);
        assert!(a.on_weight(4.0, ms(1_000), true, false));
        a.reset_shot();
        assert!(!a.on_weight(4.0, ms(2_000), true, false), "no frame yet");
        a.on_frame(1);
        assert!(a.on_weight(4.0, ms(2_100), true, false));
    }

    #[test]
    fn non_positive_weights_are_ignored() {
        let a = StepWeightExit::new(vec![Some(0.0), Some(-1.0), None]);
        assert!(!a.has_weight_exits());
    }
}

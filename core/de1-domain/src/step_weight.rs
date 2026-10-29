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
//! - **Mixed frames defer to the firmware.** `SkipToNext` is relative: if the
//!   frame also has a firmware pressure/flow exit and the firmware advances on
//!   its own while the app's skip is in flight, the skip lands on the next
//!   frame and skips that one too. On such a frame the skip waits while the
//!   live sensor is near the firmware threshold and still moving toward it, for
//!   at most [`MAX_DEFERRAL_SAMPLES`] weight samples (Decenza `4e1dd359`,
//!   `StepExitArbiter`; reaprime `20f9cc8b` drops the app skip on such frames
//!   outright). Feed the live sensors with
//!   [`on_sensors`](StepWeightExit::on_sensors).

use std::time::Duration;

use crate::profile::{Compare, ExitMetric, Profile};

/// Decenza `StepExitArbiter::kMaxDeferralSamples`: on the third near-threshold
/// sample the skip fires regardless.
pub const MAX_DEFERRAL_SAMPLES: usize = 3;
/// Proximity window for a pressure exit: 20 % of the threshold, at least
/// 0.3 bar (Decenza `kPressureProximityFraction` / `kPressureProximityMinimum`).
const PRESSURE_PROXIMITY_FRACTION: f32 = 0.20;
const PRESSURE_PROXIMITY_MINIMUM: f32 = 0.3;
/// Proximity window for a flow exit: 25 % of the threshold, at least
/// 0.2 ml/s (Decenza `kFlowProximityFraction` / `kFlowProximityMinimum`).
const FLOW_PROXIMITY_FRACTION: f32 = 0.25;
const FLOW_PROXIMITY_MINIMUM: f32 = 0.2;

/// A frame's firmware-owned exit, as the DE1 evaluates it.
#[derive(Debug, Clone, Copy, PartialEq)]
struct FirmwareExit {
    metric: ExitMetric,
    compare: Compare,
    threshold: f32,
}

/// Near-threshold readings recorded while a mixed frame's skip is deferred.
#[derive(Debug, Clone, Default, PartialEq)]
struct Deferral {
    frame: u8,
    readings: Vec<f32>,
}

impl Deferral {
    /// Every recorded step moved toward the threshold (one reversal = not
    /// trending). A single reading counts as trending, as in Decenza.
    ///
    /// Two equal readings in a row count as not trending, which ends the
    /// deferral. That happens whenever two scale samples arrive between two
    /// DE1 shot samples (they carry the same cached pressure / flow). It is
    /// Decenza's timing too, checked at `9c3cd016` (cohort review 2026-10-28
    /// #6 #5): `WeightProcessor::processWeight` evaluates the arbiter on
    /// every scale sample with `m_currentPressure` / `m_currentFlow` cached by
    /// `setCurrentFrame` from the ~5 Hz shot-sample stream, and the arbiter's
    /// header states that cadence assumption. Kept line for line.
    fn trending(&self, compare: Compare) -> bool {
        self.readings.windows(2).all(|w| match compare {
            Compare::Over => w[1] > w[0],
            Compare::Under => w[1] < w[0],
        })
    }
}

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
    /// Per-frame firmware exit (`None` = none, or a non-actionable threshold).
    exits: Vec<Option<FirmwareExit>>,
    /// The latest group pressure (bar) and flow (ml/s) the DE1 reported.
    sensors: Option<(f32, f32)>,
    /// The mixed-frame skip being deferred, if any.
    deferral: Option<Deferral>,
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

    /// An arbiter for `profile`'s step weights and firmware exits.
    #[must_use]
    pub fn for_profile(profile: &Profile) -> Self {
        let mut arbiter = Self::new(profile.steps.iter().map(|s| s.weight).collect());
        arbiter.exits = profile
            .steps
            .iter()
            .map(|s| {
                s.exit
                    .filter(|e| e.threshold.is_finite() && e.threshold > 0.0)
                    .map(|e| FirmwareExit {
                        metric: e.metric,
                        compare: e.compare,
                        threshold: e.threshold,
                    })
            })
            .collect();
        arbiter
    }

    /// Adopt `other`'s per-frame targets, keeping this shot's frame / skip
    /// state. A re-activation mid-shot (the shell re-pushing the active
    /// profile on a Quick-Controls change) must not forget a skip already
    /// sent — that would send a second one and skip the next frame too.
    pub fn adopt_weights(&mut self, other: StepWeightExit) {
        self.weights = other.weights;
        self.exits = other.exits;
    }

    /// The DE1's latest group pressure (bar) and flow (ml/s), from each shot
    /// sample. Read by the mixed-frame deferral.
    pub fn on_sensors(&mut self, pressure_bar: f32, flow_ml_s: f32) {
        self.sensors = Some((pressure_bar, flow_ml_s));
    }

    /// The per-frame weight targets, grams (`None` = no weight exit).
    #[must_use]
    pub fn weights(&self) -> &[Option<f32>] {
        &self.weights
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
        self.deferral = None;
    }

    /// The DE1 reported `frame`. A frame change settles a pending skip (it
    /// took effect, or the machine moved on by itself).
    pub fn on_frame(&mut self, frame: u8) {
        self.frame = Some(frame);
        if self.pending.is_some_and(|p| p.frame != frame) {
            self.pending = None;
        }
        if self.deferral.as_ref().is_some_and(|d| d.frame != frame) {
            self.deferral = None;
        }
    }

    /// Whether a mixed frame's skip should wait for the firmware's own exit
    /// (Decenza `StepExitArbiter::evaluate`). Records the reading while near.
    fn defer_to_firmware(&mut self, frame: u8) -> bool {
        let Some(exit) = self.exits.get(usize::from(frame)).copied().flatten() else {
            return false;
        };
        // No live reading yet: nothing says the firmware is close.
        let Some((pressure, flow)) = self.sensors else {
            return false;
        };
        let (value, fraction, minimum) = match exit.metric {
            ExitMetric::Pressure => (
                pressure,
                PRESSURE_PROXIMITY_FRACTION,
                PRESSURE_PROXIMITY_MINIMUM,
            ),
            ExitMetric::Flow => (flow, FLOW_PROXIMITY_FRACTION, FLOW_PROXIMITY_MINIMUM),
        };
        let window = (fraction * exit.threshold).max(minimum);
        if !value.is_finite() || (exit.threshold - value).abs() > window {
            // Far from the firmware threshold: no race, fire now.
            self.deferral = None;
            return false;
        }
        let deferral = match self.deferral.as_mut() {
            Some(d) if d.frame == frame => d,
            _ => self.deferral.insert(Deferral {
                frame,
                readings: Vec::new(),
            }),
        };
        deferral.readings.push(value);
        if deferral.readings.len() >= MAX_DEFERRAL_SAMPLES {
            return false;
        }
        deferral.trending(exit.compare)
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
        // Mixed frame: let the firmware own the transition while it is about
        // to. Not sent and not marked sent, so the next sample re-evaluates;
        // if the firmware advances meanwhile the frame is never skipped.
        if self.defer_to_firmware(frame) {
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
    fn adopting_weights_keeps_the_shot_state() {
        let mut a = aflow_like();
        a.on_frame(1);
        assert!(a.on_weight(4.0, ms(1_000), true, false));
        // Same profile re-activated mid-shot: no second skip for frame 1.
        a.adopt_weights(aflow_like());
        assert!(!a.on_weight(4.1, ms(1_100), true, false));
        // A different profile's targets take effect from here.
        a.adopt_weights(StepWeightExit::new(vec![None, None, Some(8.0)]));
        assert_eq!(a.weights(), &[None, None, Some(8.0)]);
        a.on_frame(2);
        assert!(a.on_weight(8.0, ms(1_400), true, false));
    }

    #[test]
    fn non_positive_weights_are_ignored() {
        let a = StepWeightExit::new(vec![Some(0.0), Some(-1.0), None]);
        assert!(!a.has_weight_exits());
    }

    // ---- Mixed frames (report 08 M2, Decenza 4e1dd359) ------------------

    /// Decenza's field case: a Visualizer "soup" fill frame with
    /// `pressure_over 2.0` plus `weight 1.0`, then a main pour.
    fn soup_like() -> StepWeightExit {
        let mut a = StepWeightExit::new(vec![Some(1.0), None]);
        a.exits = vec![
            Some(FirmwareExit {
                metric: ExitMetric::Pressure,
                compare: Compare::Over,
                threshold: 2.0,
            }),
            None,
        ];
        a
    }

    #[test]
    fn a_mixed_frame_defers_while_the_firmware_exit_is_imminent() {
        let mut a = soup_like();
        a.on_frame(0);
        // 1 g reached as pressure climbs through 1.8 bar toward 2.0 bar.
        a.on_sensors(1.8, 2.0);
        assert!(
            !a.on_weight(1.2, ms(1_000), true, false),
            "defer: near and rising"
        );
        a.on_sensors(1.9, 2.0);
        assert!(
            !a.on_weight(1.3, ms(1_200), true, false),
            "defer: still rising"
        );
        // The firmware's own exit fires: frame 1. The weight skip never went
        // out, so the main pour is not skipped.
        a.on_frame(1);
        a.on_sensors(2.1, 2.0);
        assert!(!a.on_weight(1.5, ms(1_400), true, false));
        assert!(
            !a.on_weight(30.0, ms(1_600), true, false),
            "frame 1 has no weight exit"
        );
    }

    #[test]
    fn a_mixed_frame_fires_on_the_third_near_sample() {
        let mut a = soup_like();
        a.on_frame(0);
        a.on_sensors(1.7, 2.0);
        assert!(!a.on_weight(1.1, ms(1_000), true, false));
        a.on_sensors(1.8, 2.0);
        assert!(!a.on_weight(1.2, ms(1_100), true, false));
        a.on_sensors(1.85, 2.0);
        assert!(
            a.on_weight(1.3, ms(1_200), true, false),
            "cap of 3 deferrals"
        );
    }

    #[test]
    fn a_mixed_frame_fires_when_the_sensor_is_not_trending() {
        let mut a = soup_like();
        a.on_frame(0);
        a.on_sensors(1.8, 2.0);
        assert!(!a.on_weight(1.1, ms(1_000), true, false));
        a.on_sensors(1.75, 2.0); // falling away from pressure_over 2.0
        assert!(a.on_weight(1.2, ms(1_100), true, false));
    }

    #[test]
    fn two_scale_samples_on_one_shot_sample_end_the_deferral_as_in_decenza() {
        // Cohort review 2026-10-28 #6 #5: the second scale sample before the
        // next DE1 sample carries the same pressure, which reads as "not
        // trending" — Decenza's `isTrending` (strict step) on the same
        // cadence. Pinned so a divergence is a deliberate change.
        let mut a = soup_like();
        a.on_frame(0);
        a.on_sensors(1.8, 2.0);
        assert!(!a.on_weight(1.1, ms(1_000), true, false), "first: defer");
        assert!(
            a.on_weight(1.2, ms(1_100), true, false),
            "same reading: fire"
        );
    }

    #[test]
    fn a_mixed_frame_fires_at_once_when_far_from_the_threshold() {
        let mut a = soup_like();
        a.on_frame(0);
        a.on_sensors(0.5, 2.0); // window = max(0.2 × 2.0, 0.3) = 0.4 bar
        assert!(a.on_weight(1.1, ms(1_000), true, false));
    }

    #[test]
    fn a_flow_exit_uses_the_flow_window() {
        let mut a = StepWeightExit::new(vec![Some(1.0), None]);
        a.exits = vec![
            Some(FirmwareExit {
                metric: ExitMetric::Flow,
                compare: Compare::Under,
                threshold: 1.0,
            }),
            None,
        ];
        a.on_frame(0);
        // window = max(0.25 × 1.0, 0.2) = 0.25 ml/s; 1.2 is near, falling.
        a.on_sensors(9.0, 1.2);
        assert!(!a.on_weight(1.1, ms(1_000), true, false));
        a.on_sensors(9.0, 1.6); // rising away: fire
        a.on_frame(0);
        assert!(a.on_weight(1.2, ms(1_100), true, false));
    }

    #[test]
    fn for_profile_reads_the_firmware_exits() {
        use crate::profile::{ExitCondition, Pump, TempSensor, Transition};
        let step = |exit: Option<ExitCondition>, weight: Option<f32>| crate::profile::ProfileStep {
            name: "s".to_owned(),
            pump: Pump::Pressure,
            target: 9.0,
            temperature_c: 93.0,
            temp_sensor: TempSensor::Coffee,
            transition: Transition::Fast,
            duration_seconds: 10.0,
            exit,
            volume_limit_ml: 0,
            limiter: None,
            weight,
        };
        let profile = Profile {
            id: String::new(),
            title: "Soup".to_owned(),
            notes: String::new(),
            steps: vec![
                step(
                    Some(ExitCondition {
                        metric: ExitMetric::Pressure,
                        compare: Compare::Over,
                        threshold: 2.0,
                    }),
                    Some(1.0),
                ),
                step(
                    Some(ExitCondition {
                        metric: ExitMetric::Pressure,
                        compare: Compare::Over,
                        threshold: 0.0,
                    }),
                    None,
                ),
            ],
            preinfuse_step_count: 1,
            minimum_pressure: 0.0,
            maximum_flow: 6.0,
            max_total_volume_ml: 0,
            target_weight: 0.0,
            dose: 0.0,
            author: String::new(),
            beverage_type: crate::profile::BeverageType::Espresso,
            tank_temperature: 0.0,
            version: "2".to_owned(),
        };
        let a = StepWeightExit::for_profile(&profile);
        assert_eq!(
            a.exits,
            vec![
                Some(FirmwareExit {
                    metric: ExitMetric::Pressure,
                    compare: Compare::Over,
                    threshold: 2.0,
                }),
                None, // pressure_over 0 is not actionable
            ]
        );
    }
}

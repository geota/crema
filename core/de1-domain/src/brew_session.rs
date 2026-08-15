//! The guided-brew session engine — issue #10 Phase 2.
//!
//! [`BrewSessionMonitor`] runs one [`BrewRecipe`] as a live session:
//! a step clock with cues, optional start-on-first-pour, and a
//! weight-only telemetry recording. It is the non-machine sibling of
//! [`ShotMonitor`](crate::ShotMonitor): sans-IO, no clock of its own
//! (the caller supplies a monotonic `now`), and — deliberately — **no
//! DE1 anywhere**. Its cues terminate in shell-side sound/haptics,
//! never a machine write.
//!
//! Inputs are the two streams the app already has: scale weight
//! (`on_weight`, ~10 Hz while a scale is connected) and the shell tick
//! (`on_tick`, ~250 ms, run only while a session is active). Everything
//! a shell needs to render arrives as [`BrewSessionEvent`]s plus the
//! cheap state accessors.
//!
//! ## Step semantics
//!
//! - **Pour target only** — live progress against the cumulative weight
//!   target; auto-advances at target (scale present, `advance: Auto`),
//!   else holds with a [`BrewCue::Boundary`] cue.
//! - **Duration only** — a countdown; auto-advances at zero, or holds
//!   with a cue when `advance: Manual`.
//! - **Both** (bloom) — the pour target governs the *pour* (a Boundary
//!   cue says "stop pouring"), the duration governs the *step end*.
//! - **Neither** — open-ended (drawdown "until you tap"); only `skip`
//!   moves on.

use std::time::Duration;

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::brew::{
    BrewRecipe, BrewSample, BrewSeries, BrewStep, BrewStepKind, StageMark, StepAdvance,
};

/// Hard cap on recorded samples — ~18 minutes at the 4 Hz recording
/// rate, far beyond any guided brew; recording stops at the cap, the
/// session itself keeps running.
pub const MAX_BREW_SAMPLES: usize = 4_500;

/// Minimum spacing between recorded samples (~4 Hz from a ~10 Hz scale
/// stream).
pub const BREW_SAMPLE_MIN_INTERVAL: Duration = Duration::from_millis(200);

/// Net weight that counts as "the pour started" while armed.
pub const START_ON_POUR_THRESHOLD_G: f32 = 2.0;

/// How far ahead of a *timed* boundary the get-ready cue fires.
pub const APPROACH_LEAD: Duration = Duration::from_secs(3);

/// Timed steps shorter than this skip the approach cue (it would fire
/// nearly at the boundary anyway).
const APPROACH_MIN_STEP: Duration = Duration::from_secs(5);

/// The human stop-reaction allowance added to the scale's sensor lag
/// when leading a weight target.
const REACTION_ALLOWANCE_S: f32 = 0.8;

/// A cue the shell renders as sound/haptic.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BrewCue {
    /// A boundary is coming: ~3 s before a countdown ends, or a pour is
    /// within sensor-lag + reaction of its weight target. Once per step.
    Approach,
    /// The boundary itself: stop pouring / countdown hit zero on a step
    /// that holds for a tap. Steps that auto-advance signal the boundary
    /// via [`BrewSessionEvent::StepChanged`] instead. Once per step.
    Boundary,
}

/// What phase the session is in.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BrewSessionPhase {
    /// Waiting to start — for the tap, or for the first pour.
    Armed,
    Running,
    Paused,
    Done,
}

/// Everything the completed session hands back — the shell pre-fills
/// the log form from this and attaches the series to the stored row.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewSessionSummary {
    pub method: String,
    pub recipe_id: String,
    pub recipe_name: String,
    /// Total session time, milliseconds (pauses excluded).
    #[typeshare(serialized_as = "I64")]
    pub duration_ms: u64,
    /// Last net scale weight seen, grams — the measured water total.
    pub final_weight_g: Option<f32>,
    pub series: BrewSeries,
}

/// Something the session did that the UI reacts to.
#[derive(Debug, Clone, PartialEq)]
pub enum BrewSessionEvent {
    /// The session clock started (tap, or first pour detected).
    Started,
    /// A new step began. Fires for step 0 at start. Shells chime here
    /// for auto-advanced boundaries.
    StepChanged { step_index: usize },
    /// A cue is due for the *current* step.
    Cue { cue: BrewCue, step_index: usize },
    /// The session ended — last step finished, or an explicit finish.
    Completed(BrewSessionSummary),
}

/// The guided-brew session state machine. One session per monitor;
/// drop it (or [`finish`](Self::finish)) and build a new one for the
/// next brew.
#[derive(Debug)]
pub struct BrewSessionMonitor {
    recipe: BrewRecipe,
    phase: BrewSessionPhase,
    start_on_pour: bool,
    /// The scale's advertised settle lag — leads weight-target cues.
    sensor_lag: Duration,
    /// Monotonic `now` the clock started, if it has.
    started: Option<Duration>,
    paused_since: Option<Duration>,
    paused_accum: Duration,
    step_index: usize,
    /// Session-elapsed timestamp the current step began.
    step_started: Duration,
    approach_cued: bool,
    boundary_cued: bool,
    /// The current step's pour target has been met (bloom bookkeeping).
    target_met: bool,
    samples: Vec<BrewSample>,
    stage_marks: Vec<StageMark>,
    last_sample_at: Option<Duration>,
    last_weight: Option<f32>,
    /// Consecutive over-threshold readings while armed (spike guard).
    pour_hits: u8,
    /// A scale has reported at least once — pour targets are live.
    scale_seen: bool,
}

impl BrewSessionMonitor {
    /// Arm a session for `recipe`. `start_on_pour` starts the clock at
    /// the first sustained weight rise; `sensor_lag` is the connected
    /// scale's settle lag ([`Duration::ZERO`] when no scale).
    ///
    /// A recipe with no steps gets one implicit open pour step to its
    /// water target, so "just time it for me" recipes still run.
    #[must_use]
    pub fn new(mut recipe: BrewRecipe, start_on_pour: bool, sensor_lag: Duration) -> Self {
        if recipe.steps.is_empty() {
            recipe.steps.push(BrewStep {
                kind: BrewStepKind::Pour,
                target_water_g: (recipe.water_g > 0.0).then_some(recipe.water_g),
                ..BrewStep::default()
            });
        }
        BrewSessionMonitor {
            recipe,
            phase: BrewSessionPhase::Armed,
            start_on_pour,
            sensor_lag,
            started: None,
            paused_since: None,
            paused_accum: Duration::ZERO,
            step_index: 0,
            step_started: Duration::ZERO,
            approach_cued: false,
            boundary_cued: false,
            target_met: false,
            samples: Vec::new(),
            stage_marks: Vec::new(),
            last_sample_at: None,
            last_weight: None,
            pour_hits: 0,
            scale_seen: false,
        }
    }

    // ── State accessors ──────────────────────────────────────────

    #[must_use]
    pub fn phase(&self) -> BrewSessionPhase {
        self.phase
    }

    #[must_use]
    pub fn recipe(&self) -> &BrewRecipe {
        &self.recipe
    }

    #[must_use]
    pub fn step_index(&self) -> usize {
        self.step_index
    }

    /// Session time, pauses excluded. Zero before the clock starts.
    #[must_use]
    pub fn elapsed(&self, now: Duration) -> Duration {
        let Some(started) = self.started else {
            return Duration::ZERO;
        };
        let effective = self.paused_since.unwrap_or(now);
        effective
            .saturating_sub(started)
            .saturating_sub(self.paused_accum)
    }

    /// Time inside the current step.
    #[must_use]
    pub fn step_elapsed(&self, now: Duration) -> Duration {
        self.elapsed(now).saturating_sub(self.step_started)
    }

    // ── Commands ─────────────────────────────────────────────────

    /// Start the clock now (the Start tap). No-op unless armed.
    pub fn start(&mut self, now: Duration) -> Vec<BrewSessionEvent> {
        if self.phase != BrewSessionPhase::Armed {
            return Vec::new();
        }
        self.started = Some(now);
        self.phase = BrewSessionPhase::Running;
        self.stage_marks.push(StageMark {
            elapsed_ms: 0,
            step_index: 0,
        });
        vec![
            BrewSessionEvent::Started,
            BrewSessionEvent::StepChanged { step_index: 0 },
        ]
    }

    /// Freeze the session clock. The scale keeps streaming; recording
    /// resumes with the clock.
    pub fn pause(&mut self, now: Duration) {
        if self.phase == BrewSessionPhase::Running {
            self.phase = BrewSessionPhase::Paused;
            self.paused_since = Some(now);
        }
    }

    /// Resume a paused session.
    pub fn resume(&mut self, now: Duration) {
        if self.phase == BrewSessionPhase::Paused
            && let Some(since) = self.paused_since.take()
        {
            self.paused_accum += now.saturating_sub(since);
            self.phase = BrewSessionPhase::Running;
        }
    }

    /// Advance to the next step immediately (the Skip tap / the tap
    /// that ends an open or manual-hold step). Completes the session
    /// when the current step is the last.
    pub fn skip(&mut self, now: Duration) -> Vec<BrewSessionEvent> {
        if self.phase != BrewSessionPhase::Running {
            return Vec::new();
        }
        self.advance(now)
    }

    /// End the session now, from Running or Paused.
    pub fn finish(&mut self, now: Duration) -> Vec<BrewSessionEvent> {
        match self.phase {
            BrewSessionPhase::Running | BrewSessionPhase::Paused => vec![self.complete(now)],
            _ => Vec::new(),
        }
    }

    // ── Inputs ───────────────────────────────────────────────────

    /// Feed one (spike-gated, tared) scale reading.
    pub fn on_weight(
        &mut self,
        now: Duration,
        weight_g: f32,
        flow_g_s: Option<f32>,
    ) -> Vec<BrewSessionEvent> {
        self.scale_seen = true;
        let mut events = Vec::new();
        match self.phase {
            BrewSessionPhase::Armed if self.start_on_pour => {
                if weight_g >= START_ON_POUR_THRESHOLD_G {
                    self.pour_hits = self.pour_hits.saturating_add(1);
                    // Two consecutive readings over threshold: a real
                    // pour, not a bump the spike gate let through.
                    if self.pour_hits >= 2 {
                        events.extend(self.start(now));
                    }
                } else {
                    self.pour_hits = 0;
                }
            }
            BrewSessionPhase::Running => {
                self.record_sample(now, weight_g, flow_g_s);
                self.last_weight = Some(weight_g);
                events.extend(self.check_pour_target(now, weight_g, flow_g_s));
            }
            _ => {
                self.last_weight = Some(weight_g);
            }
        }
        events
    }

    /// The shell's periodic tick (~250 ms while a session is active).
    /// Drives countdowns; safe to call at any cadence — boundaries
    /// compute from timestamps, never tick counts.
    pub fn on_tick(&mut self, now: Duration) -> Vec<BrewSessionEvent> {
        if self.phase != BrewSessionPhase::Running {
            return Vec::new();
        }
        let mut events = Vec::new();
        let step = &self.recipe.steps[self.step_index];
        let Some(duration_s) = step.duration_s else {
            return events;
        };
        let step_len = Duration::from_secs(duration_s);
        let in_step = self.step_elapsed(now);
        // Approach only inside the lead window — a coarse tick that
        // lands past the deadline goes straight to the boundary.
        if !self.approach_cued
            && step_len >= APPROACH_MIN_STEP
            && in_step + APPROACH_LEAD >= step_len
            && in_step < step_len
        {
            self.approach_cued = true;
            events.push(BrewSessionEvent::Cue {
                cue: BrewCue::Approach,
                step_index: self.step_index,
            });
        }
        if in_step >= step_len {
            if step.advance == StepAdvance::Auto {
                events.extend(self.advance(now));
            } else if !self.boundary_cued {
                self.boundary_cued = true;
                events.push(BrewSessionEvent::Cue {
                    cue: BrewCue::Boundary,
                    step_index: self.step_index,
                });
            }
        }
        events
    }

    // ── Internals ────────────────────────────────────────────────

    fn record_sample(&mut self, now: Duration, weight_g: f32, flow_g_s: Option<f32>) {
        if self.samples.len() >= MAX_BREW_SAMPLES {
            return;
        }
        let elapsed = self.elapsed(now);
        if let Some(last) = self.last_sample_at
            && elapsed.saturating_sub(last) < BREW_SAMPLE_MIN_INTERVAL
        {
            return;
        }
        self.last_sample_at = Some(elapsed);
        self.samples.push(BrewSample {
            elapsed_ms: u64::try_from(elapsed.as_millis()).unwrap_or(u64::MAX),
            weight_g,
            flow_g_s,
        });
    }

    fn check_pour_target(
        &mut self,
        now: Duration,
        weight_g: f32,
        flow_g_s: Option<f32>,
    ) -> Vec<BrewSessionEvent> {
        let step = &self.recipe.steps[self.step_index];
        let Some(target) = step.target_water_g else {
            return Vec::new();
        };
        if self.target_met {
            return Vec::new();
        }
        let mut events = Vec::new();
        // Lead the target by what the scale hasn't reported yet plus a
        // human stop-reaction — same trick as espresso stop-at-weight.
        let lead_s = self.sensor_lag.as_secs_f32() + REACTION_ALLOWANCE_S;
        let margin = match flow_g_s {
            Some(f) if f > 0.2 => (f * lead_s).max(2.0),
            _ => 2.0,
        };
        if !self.approach_cued && weight_g >= target - margin && weight_g < target {
            self.approach_cued = true;
            events.push(BrewSessionEvent::Cue {
                cue: BrewCue::Approach,
                step_index: self.step_index,
            });
        }
        if weight_g >= target {
            self.target_met = true;
            let timed = step.duration_s.is_some();
            let auto = step.advance == StepAdvance::Auto;
            if !timed && auto {
                // Pure pour step: the target IS the boundary.
                events.extend(self.advance(now));
            } else if !self.boundary_cued {
                // Bloom ("stop pouring", the countdown continues) or a
                // manual-hold pour.
                self.boundary_cued = true;
                events.push(BrewSessionEvent::Cue {
                    cue: BrewCue::Boundary,
                    step_index: self.step_index,
                });
            }
        }
        events
    }

    fn advance(&mut self, now: Duration) -> Vec<BrewSessionEvent> {
        if self.step_index + 1 >= self.recipe.steps.len() {
            return vec![self.complete(now)];
        }
        self.step_index += 1;
        self.step_started = self.elapsed(now);
        self.approach_cued = false;
        self.boundary_cued = false;
        self.target_met = false;
        self.stage_marks.push(StageMark {
            elapsed_ms: u64::try_from(self.step_started.as_millis()).unwrap_or(u64::MAX),
            step_index: u64::try_from(self.step_index).unwrap_or(u64::MAX),
        });
        vec![BrewSessionEvent::StepChanged {
            step_index: self.step_index,
        }]
    }

    fn complete(&mut self, now: Duration) -> BrewSessionEvent {
        let duration = self.elapsed(now);
        self.phase = BrewSessionPhase::Done;
        BrewSessionEvent::Completed(BrewSessionSummary {
            method: self.recipe.method.clone(),
            recipe_id: self.recipe.id.clone(),
            recipe_name: self.recipe.name.clone(),
            duration_ms: u64::try_from(duration.as_millis()).unwrap_or(u64::MAX),
            final_weight_g: self.last_weight,
            series: BrewSeries {
                samples: std::mem::take(&mut self.samples),
                stage_marks: std::mem::take(&mut self.stage_marks),
            },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ms(v: u64) -> Duration {
        Duration::from_millis(v)
    }

    /// Morning V60: bloom to 45 g / 0:45, pour to 250 g, wait 0:30,
    /// open drawdown.
    fn recipe() -> BrewRecipe {
        BrewRecipe {
            id: "recipe:test".to_owned(),
            name: "Morning V60".to_owned(),
            method: "pourover".to_owned(),
            dose_g: 15.0,
            water_g: 250.0,
            temp_c: Some(96.0),
            steps: vec![
                BrewStep {
                    kind: BrewStepKind::Bloom,
                    target_water_g: Some(45.0),
                    duration_s: Some(45),
                    ..BrewStep::default()
                },
                BrewStep {
                    kind: BrewStepKind::Pour,
                    target_water_g: Some(250.0),
                    ..BrewStep::default()
                },
                BrewStep {
                    kind: BrewStepKind::Wait,
                    duration_s: Some(30),
                    ..BrewStep::default()
                },
                BrewStep {
                    kind: BrewStepKind::Drawdown,
                    advance: StepAdvance::Manual,
                    ..BrewStep::default()
                },
            ],
            notes: None,
            favourite: false,
            created_at: 0,
            updated_at: 0,
            deleted_at: None,
        }
    }

    fn lag() -> Duration {
        Duration::from_millis(380)
    }

    #[test]
    fn manual_start_stamps_step_zero() {
        let mut m = BrewSessionMonitor::new(recipe(), false, lag());
        assert_eq!(m.phase(), BrewSessionPhase::Armed);
        let ev = m.start(ms(1_000));
        assert_eq!(
            ev,
            vec![
                BrewSessionEvent::Started,
                BrewSessionEvent::StepChanged { step_index: 0 }
            ]
        );
        assert_eq!(m.phase(), BrewSessionPhase::Running);
        assert_eq!(m.elapsed(ms(3_500)), ms(2_500));
        // A second start is a no-op.
        assert!(m.start(ms(4_000)).is_empty());
    }

    #[test]
    fn start_on_pour_needs_two_sustained_readings() {
        let mut m = BrewSessionMonitor::new(recipe(), true, lag());
        // A single spike does not start the clock.
        assert!(m.on_weight(ms(100), 5.0, None).is_empty());
        assert!(m.on_weight(ms(200), 0.1, None).is_empty());
        assert_eq!(m.phase(), BrewSessionPhase::Armed);
        // Two consecutive over-threshold readings do.
        assert!(m.on_weight(ms(300), 2.5, Some(1.0)).is_empty());
        let ev = m.on_weight(ms(400), 4.0, Some(2.0));
        assert!(ev.contains(&BrewSessionEvent::Started));
        assert_eq!(m.phase(), BrewSessionPhase::Running);
        // The clock started at the detection instant.
        assert_eq!(m.elapsed(ms(1_400)), ms(1_000));
    }

    #[test]
    fn bloom_boundary_cue_fires_at_target_but_advance_waits_for_the_countdown() {
        let mut m = BrewSessionMonitor::new(recipe(), false, lag());
        m.start(ms(0));
        // Approach as the pour nears 45 g.
        let ev = m.on_weight(ms(5_000), 42.5, Some(4.0));
        assert_eq!(
            ev,
            vec![BrewSessionEvent::Cue {
                cue: BrewCue::Approach,
                step_index: 0
            }]
        );
        // Boundary at target — stop pouring — but the step holds.
        let ev = m.on_weight(ms(9_000), 45.5, Some(4.0));
        assert_eq!(
            ev,
            vec![BrewSessionEvent::Cue {
                cue: BrewCue::Boundary,
                step_index: 0
            }]
        );
        assert_eq!(m.step_index(), 0);
        // The countdown governs the step end: nothing at 30 s…
        assert!(m.on_tick(ms(30_000)).is_empty());
        // …the timed approach cue would have fired at 42 s but the
        // weight approach already used this step's cue…
        // …and the boundary advances the step at 45 s.
        let ev = m.on_tick(ms(45_100));
        assert_eq!(ev, vec![BrewSessionEvent::StepChanged { step_index: 1 }]);
    }

    #[test]
    fn pure_pour_step_auto_advances_at_target() {
        let mut m = BrewSessionMonitor::new(recipe(), false, lag());
        m.start(ms(0));
        m.on_weight(ms(9_000), 46.0, Some(4.0)); // bloom target met
        m.on_tick(ms(45_100)); // bloom countdown over → step 1
        assert_eq!(m.step_index(), 1);
        // Nearing 250 g: approach, then advance at target.
        let ev = m.on_weight(ms(80_000), 245.0, Some(4.5));
        assert_eq!(
            ev,
            vec![BrewSessionEvent::Cue {
                cue: BrewCue::Approach,
                step_index: 1
            }]
        );
        let ev = m.on_weight(ms(83_000), 250.2, Some(4.0));
        assert_eq!(ev, vec![BrewSessionEvent::StepChanged { step_index: 2 }]);
    }

    #[test]
    fn timed_wait_cues_approach_then_advances() {
        let mut m = BrewSessionMonitor::new(recipe(), false, lag());
        m.start(ms(0));
        m.on_weight(ms(9_000), 46.0, Some(4.0));
        m.on_tick(ms(45_100));
        m.on_weight(ms(83_000), 250.2, Some(4.0));
        assert_eq!(m.step_index(), 2); // Wait 0:30, started at 83 s
        assert!(m.on_tick(ms(100_000)).is_empty());
        let ev = m.on_tick(ms(110_500)); // 27.5 s in: approach window
        assert_eq!(
            ev,
            vec![BrewSessionEvent::Cue {
                cue: BrewCue::Approach,
                step_index: 2
            }]
        );
        let ev = m.on_tick(ms(113_200));
        assert_eq!(ev, vec![BrewSessionEvent::StepChanged { step_index: 3 }]);
    }

    #[test]
    fn open_drawdown_holds_until_skip_and_skip_on_last_step_completes() {
        let mut m = BrewSessionMonitor::new(recipe(), false, lag());
        m.start(ms(0));
        m.on_weight(ms(9_000), 46.0, Some(4.0));
        m.on_tick(ms(45_100));
        m.on_weight(ms(83_000), 250.2, Some(4.0));
        m.on_tick(ms(113_200));
        assert_eq!(m.step_index(), 3);
        // The drawdown has no duration and no target: ticks are quiet.
        assert!(m.on_tick(ms(150_000)).is_empty());
        m.on_weight(ms(160_000), 251.0, None);
        let ev = m.skip(ms(185_000));
        let BrewSessionEvent::Completed(summary) = &ev[0] else {
            panic!("expected completion, got {ev:?}");
        };
        assert_eq!(summary.duration_ms, 185_000);
        assert_eq!(summary.method, "pourover");
        assert_eq!(summary.recipe_name, "Morning V60");
        assert_eq!(summary.final_weight_g, Some(251.0));
        // Four stage marks: one per step actually entered.
        assert_eq!(summary.series.stage_marks.len(), 4);
        assert_eq!(summary.series.stage_marks[3].elapsed_ms, 113_200);
        assert_eq!(m.phase(), BrewSessionPhase::Done);
    }

    #[test]
    fn pause_freezes_the_clock_and_recording() {
        let mut m = BrewSessionMonitor::new(recipe(), false, lag());
        m.start(ms(0));
        m.on_weight(ms(1_000), 10.0, Some(2.0));
        m.pause(ms(2_000));
        assert_eq!(m.phase(), BrewSessionPhase::Paused);
        // The clock is frozen at 2 s…
        assert_eq!(m.elapsed(ms(9_000)), ms(2_000));
        // …and weight during the pause is not recorded as a sample
        // (probed via the summary below — samples are private).
        m.on_weight(ms(5_000), 20.0, Some(2.0));
        m.resume(ms(10_000));
        assert_eq!(m.phase(), BrewSessionPhase::Running);
        // …and after resuming, elapsed excludes the 8 s pause.
        assert_eq!(m.elapsed(ms(11_000)), ms(3_000));
        let ev = m.finish(ms(12_000));
        let BrewSessionEvent::Completed(summary) = &ev[0] else {
            panic!("expected completion");
        };
        assert_eq!(summary.duration_ms, 4_000);
        assert_eq!(
            summary.series.samples.len(),
            1,
            "paused weight not recorded"
        );
        // The pause-time weight still counts as the last-seen weight.
        assert_eq!(summary.final_weight_g, Some(20.0));
    }

    #[test]
    fn manual_hold_timed_step_cues_boundary_once_then_waits() {
        let mut recipe = recipe();
        recipe.steps[2].advance = StepAdvance::Manual; // Wait 0:30, manual
        let mut m = BrewSessionMonitor::new(recipe, false, lag());
        m.start(ms(0));
        m.on_weight(ms(9_000), 46.0, Some(4.0));
        m.on_tick(ms(45_100));
        m.on_weight(ms(83_000), 250.2, Some(4.0));
        assert_eq!(m.step_index(), 2);
        m.on_tick(ms(110_500)); // approach
        let ev = m.on_tick(ms(113_500));
        assert_eq!(
            ev,
            vec![BrewSessionEvent::Cue {
                cue: BrewCue::Boundary,
                step_index: 2
            }]
        );
        // Holds — and the cue does not repeat.
        assert!(m.on_tick(ms(120_000)).is_empty());
        let ev = m.skip(ms(125_000));
        assert_eq!(ev, vec![BrewSessionEvent::StepChanged { step_index: 3 }]);
    }

    #[test]
    fn sample_recording_is_rate_limited_and_capped() {
        let mut m = BrewSessionMonitor::new(recipe(), false, lag());
        m.start(ms(0));
        // 10 Hz stream for 2 s → at most ~10 samples at the 200 ms floor.
        for i in 0..20u64 {
            m.on_weight(ms(i * 100), f32::from(u8::try_from(i).unwrap()), Some(1.0));
        }
        let ev = m.finish(ms(2_000));
        let BrewSessionEvent::Completed(summary) = &ev[0] else {
            panic!("expected completion");
        };
        assert!(summary.series.samples.len() <= 11);
        assert!(summary.series.samples.len() >= 9);
    }

    #[test]
    fn a_stepless_recipe_runs_as_one_open_pour_to_the_water_target() {
        let mut r = recipe();
        r.steps.clear();
        let mut m = BrewSessionMonitor::new(r, false, lag());
        m.start(ms(0));
        // The implicit step targets 250 g and auto-advances there —
        // which, as the only step, completes the session.
        let ev = m.on_weight(ms(60_000), 250.5, Some(4.0));
        let BrewSessionEvent::Completed(summary) = ev.last().unwrap() else {
            panic!("expected completion, got {ev:?}");
        };
        assert_eq!(summary.duration_ms, 60_000);
    }

    #[test]
    fn scale_less_session_advances_on_time_and_taps_only() {
        let mut m = BrewSessionMonitor::new(recipe(), false, Duration::ZERO);
        m.start(ms(0));
        // Bloom advances on its countdown even though the 45 g target
        // was never observed (no scale).
        let ev = m.on_tick(ms(45_100));
        assert_eq!(ev, vec![BrewSessionEvent::StepChanged { step_index: 1 }]);
        // The pure pour step has no countdown: only a tap moves on.
        assert!(m.on_tick(ms(90_000)).is_empty());
        let ev = m.skip(ms(95_000));
        assert_eq!(ev, vec![BrewSessionEvent::StepChanged { step_index: 2 }]);
    }
}

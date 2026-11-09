//! Scale weight-spike gate — rejects physically impossible single-sample
//! jumps before they reach stop-at-weight.
//!
//! A corrupt BLE weight frame (Decenza issue #610 was a real Felicita
//! 1649 g read mid-shot) that reaches the stop logic ends the shot
//! instantly; one that reaches the chart blows the y-axis. Decenza's
//! `weightprocessor.cpp` guards its pipeline by rejecting >100 g
//! single-sample jumps during extraction, auto-accepting after three
//! consecutive rejections so a *genuine* step change (cup swapped
//! mid-shot) recovers. This is the crema port of that gate, applied at
//! the one core choke point every consumer shares (SAW, charts, metrics,
//! drip learning) — the shells never see a rejected sample.
//!
//! The gate only *rejects* while a shot is in progress: at rest, large
//! steps are legitimate (placing a full cup on the scale). Outside a
//! shot it still tracks the accepted baseline so the first in-shot
//! sample has something to compare against. Non-finite readings are
//! always rejected and never count toward recovery — a NaN plateau is
//! never a real cup.

use std::time::Duration;

/// Largest believable single-sample weight change during a shot, grams.
/// Espresso flow peaks ~4 g/s and scales report at 5–10 Hz, so a real
/// between-sample delta is <1 g; 100 g leaves two orders of magnitude of
/// headroom while still catching codec glitches (Decenza uses the same
/// figure).
pub const MAX_SAMPLE_JUMP_G: f32 = 100.0;

/// A step between consecutive readings over this, plus
/// [`DISTURB_RATE_G_S`] × the time between them, means something pressed on,
/// landed on or was lifted off the scale (`DESIGN.md` §3.5). Real steps are
/// far smaller: 0.31 g at most in the capture's 42 s of flow.
pub const DISTURB_STEP_G: f32 = 3.0;

/// The flow allowance on the disturbance step, g/s: covers a delayed reading
/// in a fast hot-water pour.
pub const DISTURB_RATE_G_S: f32 = 12.0;

/// What the gate makes of a reading.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WeightVerdict {
    /// A believable reading.
    Accept,
    /// A spike: drop it.
    Reject,
    /// Passed on, but the scale was disturbed: a step over the band, or an
    /// accepted plateau past [`MAX_SAMPLE_JUMP_G`].
    Disturbed,
}

/// Consecutive out-of-band samples that *agree with each other's plateau*
/// (i.e. keep arriving) before the gate accepts the new level — a real
/// step change recovers in ~3 samples (<1 s at 5 Hz), a lone spike never
/// does.
const RECOVERY_SAMPLES: u8 = 3;

/// The spike gate's rolling state. One per scale session; [`reset`] on
/// shot start and whenever the weight baseline legitimately steps (tare
/// landing, software re-zero).
///
/// [`reset`]: WeightSpikeGate::reset
#[derive(Debug, Default)]
pub struct WeightSpikeGate {
    /// The last weight that passed the gate, grams, and when.
    last_accepted_g: Option<f32>,
    last_accepted_at: Option<Duration>,
    /// Consecutive rejected samples since the last accepted one.
    rejected_run: u8,
}

impl WeightSpikeGate {
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Feed one net weight sample. Returns `true` when the sample should
    /// be DROPPED (no event, no estimator, no stop check). `gating` is
    /// whether a shot is in progress — when `false` the gate only tracks
    /// the baseline and rejects nothing but non-finite values.
    pub fn rejects(&mut self, weight_g: f32, gating: bool) -> bool {
        if !weight_g.is_finite() {
            // Never a valid baseline, never counts toward recovery.
            return true;
        }
        if !gating {
            self.last_accepted_g = Some(weight_g);
            self.rejected_run = 0;
            return false;
        }
        match self.last_accepted_g {
            None => {
                self.last_accepted_g = Some(weight_g);
                false
            }
            Some(last) if (weight_g - last).abs() <= MAX_SAMPLE_JUMP_G => {
                self.last_accepted_g = Some(weight_g);
                self.rejected_run = 0;
                false
            }
            Some(_) => {
                self.rejected_run += 1;
                if self.rejected_run >= RECOVERY_SAMPLES {
                    // Three in a row past the band: a real new plateau
                    // (cup swap), not a glitch. Accept and re-baseline.
                    self.last_accepted_g = Some(weight_g);
                    self.rejected_run = 0;
                    false
                } else {
                    true
                }
            }
        }
    }

    /// Feed one net weight sample at `now`. `gating`: a shot is in progress,
    /// so spikes are rejected. `watching`: a session is flowing, so a step
    /// over the band is a disturbance, in either direction.
    pub fn check(
        &mut self,
        weight_g: f32,
        now: Duration,
        gating: bool,
        watching: bool,
    ) -> WeightVerdict {
        let before = self.last_accepted_g.zip(self.last_accepted_at);
        let recovering = self.rejected_run > 0;
        if self.rejects(weight_g, gating) {
            return WeightVerdict::Reject;
        }
        self.last_accepted_at = Some(now);
        let Some((last_g, last_at)) = before else {
            return WeightVerdict::Accept;
        };
        let dt = now.saturating_sub(last_at).as_secs_f32();
        let step = (weight_g - last_g).abs();
        // An accepted plateau after rejected spikes compares against the
        // level before them.
        let plateau = recovering && step > MAX_SAMPLE_JUMP_G;
        if watching && (plateau || step > DISTURB_STEP_G + DISTURB_RATE_G_S * dt) {
            WeightVerdict::Disturbed
        } else {
            WeightVerdict::Accept
        }
    }

    /// The last weight that passed, grams.
    #[must_use]
    pub fn last_accepted(&self) -> Option<f32> {
        self.last_accepted_g
    }

    /// Forget the baseline — call when the weight legitimately steps
    /// (shot start, tare landed, software re-zero) so the next sample
    /// re-baselines instead of reading as a jump.
    pub fn reset(&mut self) {
        self.last_accepted_g = None;
        self.last_accepted_at = None;
        self.rejected_run = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ms(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    /// The band at a scale's 10 Hz: 3 g + 12 g/s × 0.1 s = 4.2 g.
    #[test]
    fn the_band_at_10_hz_is_4_2_g_either_way() {
        let mut gate = WeightSpikeGate::new();
        assert_eq!(gate.check(10.0, ms(0), true, true), WeightVerdict::Accept);
        assert_eq!(gate.check(14.1, ms(100), true, true), WeightVerdict::Accept);
        assert_eq!(
            gate.check(18.5, ms(200), true, true),
            WeightVerdict::Disturbed
        );
        assert_eq!(
            gate.check(14.2, ms(300), true, true),
            WeightVerdict::Disturbed
        );
        assert_eq!(gate.check(10.1, ms(400), true, true), WeightVerdict::Accept);
    }

    /// At 5 Hz the band widens with the time between readings: 5.4 g.
    #[test]
    fn the_band_at_5_hz_is_5_4_g() {
        let mut gate = WeightSpikeGate::new();
        assert_eq!(gate.check(10.0, ms(0), true, true), WeightVerdict::Accept);
        assert_eq!(gate.check(15.3, ms(200), true, true), WeightVerdict::Accept);
        assert_eq!(
            gate.check(20.9, ms(400), true, true),
            WeightVerdict::Disturbed
        );
        // A reading 2 s late in a 10 g/s pour is inside 3 + 24 g.
        assert_eq!(
            gate.check(40.9, ms(2_400), true, true),
            WeightVerdict::Accept
        );
    }

    #[test]
    fn a_step_is_only_a_disturbance_while_a_session_flows() {
        let mut gate = WeightSpikeGate::new();
        assert_eq!(gate.check(0.0, ms(0), false, false), WeightVerdict::Accept);
        assert_eq!(
            gate.check(300.0, ms(100), false, false),
            WeightVerdict::Accept
        );
        assert_eq!(
            gate.check(320.0, ms(200), false, true),
            WeightVerdict::Disturbed
        );
    }

    #[test]
    fn an_accepted_plateau_past_the_spike_limit_is_a_disturbance() {
        let mut gate = WeightSpikeGate::new();
        assert_eq!(gate.check(10.0, ms(0), true, true), WeightVerdict::Accept);
        assert_eq!(
            gate.check(320.0, ms(100), true, true),
            WeightVerdict::Reject
        );
        assert_eq!(
            gate.check(321.0, ms(200), true, true),
            WeightVerdict::Reject
        );
        assert_eq!(
            gate.check(322.0, ms(300), true, true),
            WeightVerdict::Disturbed
        );
    }

    #[test]
    fn a_reset_clears_the_comparison() {
        let mut gate = WeightSpikeGate::new();
        assert_eq!(gate.check(10.0, ms(0), true, true), WeightVerdict::Accept);
        gate.reset();
        assert_eq!(gate.check(40.0, ms(100), true, true), WeightVerdict::Accept);
    }

    #[test]
    fn passes_normal_shot_weights() {
        let mut gate = WeightSpikeGate::new();
        for (i, w) in [0.0, 0.4, 1.1, 2.0, 3.2, 4.9].iter().enumerate() {
            assert!(!gate.rejects(*w, true), "sample {i} ({w} g) must pass");
        }
    }

    #[test]
    fn rejects_a_lone_spike_and_recovers_the_true_level() {
        let mut gate = WeightSpikeGate::new();
        assert!(!gate.rejects(10.0, true));
        // The Decenza #610 shape: one corrupt 1649 g frame mid-shot.
        assert!(gate.rejects(1649.0, true));
        // The very next sane sample flows straight through.
        assert!(!gate.rejects(10.4, true));
        assert!(!gate.rejects(10.9, true));
    }

    #[test]
    fn accepts_a_genuine_step_after_three_agreeing_samples() {
        let mut gate = WeightSpikeGate::new();
        assert!(!gate.rejects(10.0, true));
        // Cup swapped mid-shot: the new level persists.
        assert!(gate.rejects(320.0, true));
        assert!(gate.rejects(321.0, true));
        assert!(
            !gate.rejects(322.0, true),
            "third sample accepts the plateau"
        );
        assert!(!gate.rejects(323.0, true));
    }

    #[test]
    fn never_rejects_at_rest_but_tracks_the_baseline() {
        let mut gate = WeightSpikeGate::new();
        assert!(!gate.rejects(0.0, false));
        // Placing a full 400 g cup at rest is legitimate.
        assert!(!gate.rejects(400.0, false));
        // The rest baseline carries into the shot: no false reject.
        assert!(!gate.rejects(401.0, true));
    }

    #[test]
    fn rejects_non_finite_without_burning_recovery() {
        let mut gate = WeightSpikeGate::new();
        assert!(!gate.rejects(10.0, true));
        assert!(gate.rejects(f32::NAN, true));
        assert!(gate.rejects(f32::INFINITY, true));
        assert!(!gate.rejects(10.2, true), "sane sample still passes");
    }

    #[test]
    fn reset_rebaselines() {
        let mut gate = WeightSpikeGate::new();
        assert!(!gate.rejects(300.0, true));
        gate.reset();
        // Post-tare: 0 g is not a jump from a forgotten 300 g.
        assert!(!gate.rejects(0.0, true));
    }
}

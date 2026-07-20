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

/// Largest believable single-sample weight change during a shot, grams.
/// Espresso flow peaks ~4 g/s and scales report at 5–10 Hz, so a real
/// between-sample delta is <1 g; 100 g leaves two orders of magnitude of
/// headroom while still catching codec glitches (Decenza uses the same
/// figure).
pub const MAX_SAMPLE_JUMP_G: f32 = 100.0;

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
    /// The last weight that passed the gate, grams.
    last_accepted_g: Option<f32>,
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

    /// Forget the baseline — call when the weight legitimately steps
    /// (shot start, tare landed, software re-zero) so the next sample
    /// re-baselines instead of reading as a jump.
    pub fn reset(&mut self) {
        self.last_accepted_g = None;
        self.rejected_run = 0;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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

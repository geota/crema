//! Auto-tare settle gate and pre-shot zero correction — Decenza `da2e5495`
//! ("gate the auto-tare on a settled reading, and correct the pre-shot zero").
//!
//! A tare zeroes the scale on whatever the load cell reads at that instant,
//! so taring a ringing cell bakes the transient in as the new zero and every
//! reading for the rest of the shot is off by it — silently. Decenza's field
//! case: the auto-tare fired 8 ms after the cell swung −6.8 → +8.3 g, the zero
//! drifted to −20.6 g, stop-at-weight chased a 36 g target that was really
//! 56.6 g and stopped 23 g late, and the grind advice blamed the puck.
//!
//! Two pieces, both ported with Decenza's numbers:
//!
//! 1. **Settle** ([`TareSettleWindow`]): tare only once the last
//!    [`AUTO_TARE_SETTLE_SAMPLES`] readings sit inside
//!    [`AUTO_TARE_SETTLE_BAND_G`] — the *spread* across the window, not a
//!    per-sample delta, so a slow ramp is rejected too. The pair is bound by
//!    `(N − 1) × drift > band` at the observed 0.47 g/sample drift: N = 3 gives
//!    0.94 g and would call that drift still; N = 4 gives 1.41 g and rejects
//!    it. Never edit one constant without the other.
//!
//! 2. **Zero held** ([`pre_shot_zero_offset`]): the post-tare zero creeps
//!    during preheat (−0.1 … −0.5 g across six of Decenza's shots). That is a
//!    bias, not noise — a zero 0.4 g low makes every reading 0.4 g low — so the
//!    zero the scale actually had when flow started is captured and subtracted
//!    from every reading of the shot. Adopted only when the tare was *seen* to
//!    land and only within ±[`MAX_PRE_SHOT_ZERO_OFFSET_G`], so a forgotten
//!    untared cup is never mistaken for an offset (that stays the untared-cup
//!    guard's job).
//!
//! Decenza's first iteration of this PR also re-tared on three samples below
//! −2 g with a −35 g floor; their review replaced it with the bounded
//! offset above before merging, so that is not ported.

/// Samples that must sit inside [`AUTO_TARE_SETTLE_BAND_G`] before a tare —
/// Decenza `kAutoTareSettleSamples`. One pair with the band; see module docs.
pub const AUTO_TARE_SETTLE_SAMPLES: usize = 4;

/// Largest max − min spread, grams, across the settle window that still
/// counts as a still reading — Decenza `kAutoTareSettleBandG`.
pub const AUTO_TARE_SETTLE_BAND_G: f32 = 1.0;

/// A reading above this, grams, after the shot-start tare has landed means a
/// cup was put down during preheat and needs its own tare — Decenza
/// `AUTO_TARE_THRESHOLD` (de1app uses 0.04 g; 2 g avoids noise).
pub const AUTO_TARE_THRESHOLD_G: f32 = 2.0;

/// Minimum gap between two auto-tares, ms — Decenza `AUTO_TARE_HOLDOFF_MS`
/// (matches de1app). It is what retries a tare whose BLE write was dropped.
pub const AUTO_TARE_HOLDOFF_MS: u64 = 1000;

/// Largest pre-shot zero, grams, treated as drift and subtracted — Decenza
/// `kMaxPreShotZeroOffsetG`. Far below any cup, so an untared cup can never
/// be erased as an "offset".
pub const MAX_PRE_SHOT_ZERO_OFFSET_G: f32 = 2.0;

/// The last [`AUTO_TARE_SETTLE_SAMPLES`] raw scale readings, for the settle
/// test. Order inside the window is irrelevant (only min and max are read),
/// so a wrapping index replaces shifting.
#[derive(Debug, Clone, Copy, Default, PartialEq)]
pub struct TareSettleWindow {
    samples: [f32; AUTO_TARE_SETTLE_SAMPLES],
    /// Total samples seen since the last reset (saturating).
    count: usize,
}

impl TareSettleWindow {
    /// An empty window.
    #[must_use]
    pub const fn new() -> TareSettleWindow {
        TareSettleWindow {
            samples: [0.0; AUTO_TARE_SETTLE_SAMPLES],
            count: 0,
        }
    }

    /// Record one reading. A non-finite reading empties the window — it
    /// cannot be part of a still stretch.
    pub fn push(&mut self, weight_g: f32) {
        if !weight_g.is_finite() {
            self.reset();
            return;
        }
        self.samples[self.count % AUTO_TARE_SETTLE_SAMPLES] = weight_g;
        self.count = self.count.saturating_add(1);
    }

    /// Spread (max − min) across the filled window, or `None` while it is
    /// still filling — an unmeasured reading is not a still one.
    #[must_use]
    pub fn spread(&self) -> Option<f32> {
        if self.count < AUTO_TARE_SETTLE_SAMPLES {
            return None;
        }
        let lo = self.samples.iter().copied().fold(f32::INFINITY, f32::min);
        let hi = self
            .samples
            .iter()
            .copied()
            .fold(f32::NEG_INFINITY, f32::max);
        Some(hi - lo)
    }

    /// Whether the window is full and its spread is within
    /// [`AUTO_TARE_SETTLE_BAND_G`].
    #[must_use]
    pub fn is_settled(&self) -> bool {
        self.spread().is_some_and(|s| s <= AUTO_TARE_SETTLE_BAND_G)
    }

    /// Empty the window — after a tare (the tare itself moves the reading)
    /// or when the pre-flow window closes.
    pub fn reset(&mut self) {
        self.count = 0;
    }
}

/// The pre-shot zero to subtract from every reading of the shot, captured at
/// first flow (Decenza `WeightProcessor::markExtractionStart`): the last raw
/// reading when the tare was observed to land and it is within
/// ±[`MAX_PRE_SHOT_ZERO_OFFSET_G`], else `0.0` (no correction — the safe
/// direction, and what the code did before the offset existed).
#[must_use]
pub fn pre_shot_zero_offset(tare_observed: bool, last_raw_g: Option<f32>) -> f32 {
    match last_raw_g {
        Some(raw)
            if tare_observed && raw.is_finite() && raw.abs() <= MAX_PRE_SHOT_ZERO_OFFSET_G =>
        {
            raw
        }
        _ => 0.0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn window(samples: &[f32]) -> TareSettleWindow {
        let mut w = TareSettleWindow::new();
        for &s in samples {
            w.push(s);
        }
        w
    }

    #[test]
    fn an_unfilled_window_is_not_settled() {
        assert!(!window(&[]).is_settled());
        assert!(!window(&[250.0, 250.0, 250.0]).is_settled());
        assert_eq!(window(&[1.0, 1.0, 1.0]).spread(), None);
    }

    #[test]
    fn a_still_cup_settles_regardless_of_its_mass() {
        assert!(window(&[312.4, 312.6, 312.5, 312.9]).is_settled());
        // Exactly the band is still.
        assert!(window(&[0.0, 1.0, 0.5, 0.2]).is_settled());
    }

    #[test]
    fn a_ringing_cell_is_not_settled() {
        // Decenza's field trace: -6.8 → +8.3 g swings.
        assert!(!window(&[-6.8, 8.3, -2.0, 4.1]).is_settled());
        // The window keeps only the last four: once the ringing dies, it settles.
        let mut w = window(&[-6.8, 8.3, -2.0, 4.1]);
        for s in [0.4, 0.2, 0.3, 0.1] {
            w.push(s);
        }
        assert!(w.is_settled());
    }

    #[test]
    fn a_slow_drift_is_rejected_by_the_spread() {
        // 0.47 g/sample: each step is inside the band, the window is not.
        assert!(!window(&[0.0, -0.47, -0.94, -1.41]).is_settled());
    }

    #[test]
    fn reset_and_non_finite_empty_the_window() {
        let mut w = window(&[1.0, 1.0, 1.0, 1.0]);
        assert!(w.is_settled());
        w.reset();
        assert!(!w.is_settled());
        let mut w = window(&[1.0, 1.0, 1.0]);
        w.push(f32::NAN);
        w.push(1.0);
        assert!(!w.is_settled());
    }

    #[test]
    fn the_pre_shot_zero_is_adopted_only_when_bounded_and_observed() {
        assert_eq!(pre_shot_zero_offset(true, Some(-0.4)), -0.4);
        assert_eq!(pre_shot_zero_offset(true, Some(2.0)), 2.0);
        // Out of bounds: an untared cup, not drift.
        assert_eq!(pre_shot_zero_offset(true, Some(-2.5)), 0.0);
        assert_eq!(pre_shot_zero_offset(true, Some(180.0)), 0.0);
        // The tare was never seen to land: the reading may be pre-tare.
        assert_eq!(pre_shot_zero_offset(false, Some(-0.4)), 0.0);
        assert_eq!(pre_shot_zero_offset(true, None), 0.0);
        assert_eq!(pre_shot_zero_offset(true, Some(f32::NAN)), 0.0);
    }
}

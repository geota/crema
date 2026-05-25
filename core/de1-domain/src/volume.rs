//! Dispensed-volume integration from the DE1's `ShotSample` stream.
//!
//! Two responsibilities:
//!
//! - [`VolumeIntegrator`] integrates `group_flow × dt` into a running
//!   `dispensed_ml`. The `dt` source is the DE1's own `sample_time` ticks
//!   converted via the AC mains frequency when known, otherwise it falls
//!   back to host-clock deltas (BLE-jitter-prone but no worse than the
//!   pre-refactor behaviour).
//! - [`LineFreqDetector`] watches the relationship between `sample_time`
//!   ticks and host-clock elapsed time over the first few samples of a
//!   shot, and locks on either 50 Hz or 60 Hz mains. Once locked it is
//!   final for the session (the answer doesn't change on a single
//!   machine).
//!
//! Both are pure data: no clocks, no I/O, no BLE. Legacy reference:
//! `de1app/de1plus/de1_de1.tcl:324-595` (line-frequency estimation,
//! `intersample_time` derivation, the `flow × dt` accumulation, and the
//! sanity clamps).

use std::time::Duration;

use de1_protocol::ShotSample;

/// The two AC mains frequencies any DE1 will be deployed against.
const SUPPORTED_FREQS_HZ: [f32; 2] = [50.0, 60.0];

/// A single-sample flow integration that gets clamped if it lands here.
/// Mirrors the legacy `de1_de1.tcl:587` "Excessive water volume dispensed,
/// setting to 0" guard — protects the running total from one corrupt
/// `sample_time` delta destroying the shot's reported volume.
const PER_SAMPLE_MAX_ML: f32 = 1000.0;

/// Running dispensed-volume integration from `ShotSample` events.
///
/// The integrator owns no clock and no settings — both come in on each
/// [`integrate`](Self::integrate) call. `line_freq_hz`:
///
/// - `Some(hz)` (50 / 60) → `dt_s = dhc / (2 × hz)` where `dhc` is the
///   `sample_time` delta with 16-bit unwrap. **Bit-exact with the DE1's
///   internal volume tracking** when the freq is right.
/// - `None` → host-clock fallback: `dt_s = (now - last_now).as_secs_f32()`.
///   Same behaviour Crema had pre-refactor. BLE jitter contaminates this
///   by tens of ms per sample, so the integrated volume drifts a few
///   percent across a shot.
///
/// [`reset`](Self::reset) clears the running volume and the timing state
/// — call it on every `Event::ShotStarted`.
#[derive(Debug, Default, Clone)]
pub struct VolumeIntegrator {
    dispensed_ml: f32,
    last_sample_time: Option<u16>,
    last_host_time: Option<Duration>,
}

impl VolumeIntegrator {
    /// A fresh integrator at zero.
    pub fn new() -> Self {
        Self::default()
    }

    /// The volume dispensed so far, ml.
    pub fn dispensed_ml(&self) -> f32 {
        self.dispensed_ml
    }

    /// Discard the running volume and the timing state. Call at the start
    /// of every shot so two consecutive shots don't share an accumulator.
    pub fn reset(&mut self) {
        *self = Self::new();
    }

    /// Integrate one telemetry sample.
    ///
    /// `now` is the host-side arrival time (used both for the fallback `dt`
    /// computation and to anchor the next sample). `line_freq_hz` is the
    /// effective AC mains frequency; `None` falls back to host-clock dt.
    ///
    /// Sanity clamps mirror the legacy: a negative single-sample increment
    /// or anything above [`PER_SAMPLE_MAX_ML`] is treated as 0 (corrupt
    /// `sample_time` or `group_flow` — drop it rather than poison the
    /// running total).
    pub fn integrate(&mut self, sample: &ShotSample, now: Duration, line_freq_hz: Option<f32>) {
        let dt_s = self.dt_seconds(sample.sample_time, now, line_freq_hz);
        let raw = sample.group_flow * dt_s;
        let inc = if raw.is_finite() && (0.0..=PER_SAMPLE_MAX_ML).contains(&raw) {
            raw
        } else {
            0.0
        };
        self.dispensed_ml += inc;
        self.last_sample_time = Some(sample.sample_time);
        self.last_host_time = Some(now);
    }

    fn dt_seconds(&self, sample_time: u16, now: Duration, line_freq_hz: Option<f32>) -> f32 {
        match (self.last_sample_time, line_freq_hz) {
            (Some(prev), Some(hz)) if hz > 0.0 => {
                // 16-bit unwrap. `wrapping_sub` gives the forward delta even
                // across the 65536 boundary (legacy `de1_de1.tcl:560`).
                let dhc = sample_time.wrapping_sub(prev);
                f32::from(dhc) / (2.0 * hz)
            }
            _ => match self.last_host_time {
                Some(then) if now >= then => (now - then).as_secs_f32(),
                _ => 0.0,
            },
        }
    }
}

/// Auto-detects the DE1's AC mains frequency by ratio-of-ticks-to-host-time
/// over a short warmup window.
///
/// `sample_time` increments at 2 × line_freq Hz on the DE1 firmware. The
/// host's notification-arrival timestamps drift by BLE jitter but **average
/// out** over enough samples. Watching `sum(sample_time_delta) /
/// sum(host_dt)` for a 1-second window collapses noise; whichever of
/// {50 Hz, 60 Hz} the resulting `freq` lands closest to is the answer.
///
/// Once [`observe`](Self::observe) returns `Some(hz)` the detector is
/// "locked" — subsequent calls return the same `Some(hz)` without
/// re-checking. The lock is per-session (no persistence here); the legacy
/// app doesn't persist either.
///
/// **Lock thresholds**: 5+ samples and 1+ host-second of elapsed time.
/// The legacy uses similar — sample count + a wall-clock floor — to avoid
/// locking from one or two jitter-spike samples (`de1_de1.tcl:324-391`).
#[derive(Debug, Default, Clone)]
pub struct LineFreqDetector {
    /// `Some(hz)` once locked; never written to again.
    locked: Option<f32>,
    /// First observed `(sample_time, host_time)`; anchors the rolling sums.
    anchor: Option<(u16, Duration)>,
    /// The previous sample's `sample_time`, for wrap-correct delta sums.
    last_sample_time: Option<u16>,
    /// Sum of `sample_time` deltas since the anchor (unwrapped).
    total_ticks: u32,
    /// Number of samples observed since the anchor (>= 1 to lock).
    samples_observed: u32,
}

impl LineFreqDetector {
    pub fn new() -> Self {
        Self::default()
    }

    /// `Some(hz)` once a lock has been made. `None` while still
    /// accumulating.
    pub fn locked_hz(&self) -> Option<f32> {
        self.locked
    }

    /// Clear the detector — call on disconnect / session reset.
    pub fn reset(&mut self) {
        *self = Self::new();
    }

    /// Feed one sample. Returns `Some(hz)` the first time the detector
    /// locks (and on every subsequent call once locked); returns `None`
    /// while still warming up.
    pub fn observe(&mut self, sample_time: u16, now: Duration) -> Option<f32> {
        if self.locked.is_some() {
            return self.locked;
        }
        match (self.anchor, self.last_sample_time) {
            (None, _) => {
                self.anchor = Some((sample_time, now));
                self.last_sample_time = Some(sample_time);
                self.samples_observed = 1;
                None
            }
            (Some((_anchor_st, anchor_now)), Some(prev_st)) => {
                let dhc = u32::from(sample_time.wrapping_sub(prev_st));
                self.total_ticks = self.total_ticks.saturating_add(dhc);
                self.last_sample_time = Some(sample_time);
                self.samples_observed = self.samples_observed.saturating_add(1);
                let elapsed_s = if now > anchor_now {
                    (now - anchor_now).as_secs_f32()
                } else {
                    0.0
                };
                if self.samples_observed >= 5 && elapsed_s >= 1.0 && self.total_ticks > 0 {
                    // total_ticks comfortably under 2^20 for any realistic
                    // 1+ second warmup (60 Hz × 2 × 1 s = 120 ticks);
                    // f32 has 24 bits of mantissa.
                    #[allow(clippy::cast_precision_loss)]
                    let observed_hz = self.total_ticks as f32 / elapsed_s / 2.0;
                    let pick = SUPPORTED_FREQS_HZ
                        .iter()
                        .copied()
                        .min_by(|a, b| {
                            (a - observed_hz)
                                .abs()
                                .partial_cmp(&(b - observed_hz).abs())
                                .unwrap_or(std::cmp::Ordering::Equal)
                        })
                        .unwrap_or(60.0);
                    self.locked = Some(pick);
                }
                self.locked
            }
            (Some(_), None) => {
                // Shouldn't happen given the anchor branch always sets
                // `last_sample_time`; defensive.
                self.last_sample_time = Some(sample_time);
                None
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use de1_protocol::ShotSample;

    fn sample(sample_time: u16, group_flow: f32) -> ShotSample {
        ShotSample {
            sample_time,
            group_pressure: 0.0,
            group_flow,
            head_temp: 0.0,
            mix_temp: 0.0,
            set_mix_temp: 0.0,
            set_head_temp: 0.0,
            set_group_pressure: 0.0,
            set_group_flow: 0.0,
            frame_number: 0,
            steam_temp: 0.0,
        }
    }

    mod volume_integrator {
        use super::*;

        #[test]
        fn first_sample_yields_zero_dt() {
            let mut v = VolumeIntegrator::new();
            v.integrate(&sample(100, 4.0), Duration::from_secs(0), Some(60.0));
            assert_eq!(v.dispensed_ml(), 0.0);
        }

        #[test]
        fn sample_time_dt_with_60hz_mains() {
            let mut v = VolumeIntegrator::new();
            // 60 Hz mains → 120 ticks/s → 12 ticks = 0.1 s. 4 ml/s × 0.1 s = 0.4 ml.
            v.integrate(&sample(0, 4.0), Duration::from_secs(0), Some(60.0));
            v.integrate(&sample(12, 4.0), Duration::from_millis(100), Some(60.0));
            let v_diff = (v.dispensed_ml() - 0.4).abs();
            assert!(v_diff < 1e-6, "got {}", v.dispensed_ml());
        }

        #[test]
        fn sample_time_dt_with_50hz_mains() {
            let mut v = VolumeIntegrator::new();
            // 50 Hz mains → 100 ticks/s → 10 ticks = 0.1 s. 4 ml/s × 0.1 s = 0.4 ml.
            v.integrate(&sample(0, 4.0), Duration::from_secs(0), Some(50.0));
            v.integrate(&sample(10, 4.0), Duration::from_millis(100), Some(50.0));
            let v_diff = (v.dispensed_ml() - 0.4).abs();
            assert!(v_diff < 1e-6, "got {}", v.dispensed_ml());
        }

        #[test]
        fn host_clock_fallback_when_no_freq_known() {
            let mut v = VolumeIntegrator::new();
            v.integrate(&sample(0, 4.0), Duration::from_secs(0), None);
            v.integrate(&sample(0, 4.0), Duration::from_millis(100), None);
            let v_diff = (v.dispensed_ml() - 0.4).abs();
            assert!(v_diff < 1e-6, "got {}", v.dispensed_ml());
        }

        #[test]
        fn sample_time_unwraps_across_the_16bit_boundary() {
            let mut v = VolumeIntegrator::new();
            // 60 Hz mains. Sample-time wraps from 65520 → 12 with a "real"
            // delta of 28 ticks = 233 ms. 4 ml/s × 0.2333 s ≈ 0.933 ml.
            v.integrate(&sample(65520, 4.0), Duration::from_secs(0), Some(60.0));
            v.integrate(&sample(12, 4.0), Duration::from_millis(233), Some(60.0));
            let expected = 4.0 * 28.0 / 120.0;
            let v_diff = (v.dispensed_ml() - expected).abs();
            assert!(v_diff < 1e-4, "got {}", v.dispensed_ml());
        }

        #[test]
        fn negative_flow_clamps_to_zero() {
            let mut v = VolumeIntegrator::new();
            v.integrate(&sample(0, 4.0), Duration::from_secs(0), Some(60.0));
            v.integrate(&sample(12, -4.0), Duration::from_millis(100), Some(60.0));
            assert_eq!(v.dispensed_ml(), 0.0);
        }

        #[test]
        fn enormous_increment_clamps_to_zero() {
            let mut v = VolumeIntegrator::new();
            // Implausible flow × Δt — drop it rather than corrupt the total.
            v.integrate(&sample(0, 5000.0), Duration::from_secs(0), Some(60.0));
            v.integrate(&sample(120, 5000.0), Duration::from_secs(1), Some(60.0));
            assert_eq!(v.dispensed_ml(), 0.0);
        }

        #[test]
        fn reset_clears_state() {
            let mut v = VolumeIntegrator::new();
            v.integrate(&sample(0, 4.0), Duration::from_secs(0), Some(60.0));
            v.integrate(&sample(120, 4.0), Duration::from_secs(1), Some(60.0));
            assert!(v.dispensed_ml() > 0.0);
            v.reset();
            assert_eq!(v.dispensed_ml(), 0.0);
        }
    }

    mod line_freq_detector {
        use super::*;

        #[test]
        fn returns_none_before_minimum_samples() {
            let mut d = LineFreqDetector::new();
            for i in 0..4u16 {
                let r = d.observe(i * 6, Duration::from_millis(u64::from(i) * 50));
                assert_eq!(r, None);
            }
        }

        #[test]
        fn locks_on_60hz_when_120_ticks_per_second() {
            let mut d = LineFreqDetector::new();
            // 120 ticks per second → 60 Hz.
            // 12 samples over 1.1 s, each 12 ticks apart, 100 ms apart.
            let mut last = None;
            for i in 0..12u16 {
                last = d.observe(i * 12, Duration::from_millis(u64::from(i) * 100));
            }
            assert_eq!(last, Some(60.0));
            assert_eq!(d.locked_hz(), Some(60.0));
        }

        #[test]
        fn locks_on_50hz_when_100_ticks_per_second() {
            let mut d = LineFreqDetector::new();
            // 100 ticks per second → 50 Hz.
            // 12 samples over 1.1 s, each 10 ticks apart, 100 ms apart.
            let mut last = None;
            for i in 0..12u16 {
                last = d.observe(i * 10, Duration::from_millis(u64::from(i) * 100));
            }
            assert_eq!(last, Some(50.0));
        }

        #[test]
        fn snaps_to_nearest_supported_when_noisy() {
            let mut d = LineFreqDetector::new();
            // Slight jitter — average around 118 ticks/s → snap to 60.
            // 12 samples: total 132 ticks over 1.1 s → 120/s → 60 Hz.
            let times: [u16; 12] = [0, 11, 23, 35, 47, 60, 72, 83, 95, 108, 120, 132];
            for (i, t) in times.iter().enumerate() {
                d.observe(
                    *t,
                    Duration::from_millis(u64::try_from(i).unwrap_or(0) * 100),
                );
            }
            assert_eq!(d.locked_hz(), Some(60.0));
        }

        #[test]
        fn reset_clears_lock() {
            let mut d = LineFreqDetector::new();
            for i in 0..12u16 {
                d.observe(i * 12, Duration::from_millis(u64::from(i) * 100));
            }
            assert!(d.locked_hz().is_some());
            d.reset();
            assert_eq!(d.locked_hz(), None);
        }
    }
}

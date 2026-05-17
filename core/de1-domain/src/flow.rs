//! Flow estimation — turning a noisy scale-weight stream into a robust weight
//! and mass-flow estimate for SAW (stop-at-weight).
//!
//! [`FlowEstimator`] keeps a sliding window of recent `(time, weight)` readings
//! and, on each [`update`](FlowEstimator::update), reports an [`Estimate`].
//! Two algorithms are available, chosen by [`FlowAlgorithm`]:
//!
//! - [`FlowAlgorithm::OffsetMedian`] — the legacy de1app algorithm.
//! - [`FlowAlgorithm::TheilSen`] — robust regression; the default.
//!
//! Both reject vibration outliers; Theil–Sen has lower noise and lower lag.
//! The algorithm is a runtime choice, so the shell can expose it as an admin
//! option and the two can be compared on identical input.

use std::collections::VecDeque;

use crate::filter::median;

/// Weight-median window width and flow baseline span, in samples — the legacy
/// `samples_for_estimate`. At ~10 Hz this is ~1 s of context.
const SAMPLES_FOR_ESTIMATE: usize = 11;
/// Width of each end window in the offset-median flow estimate — the legacy
/// `samples_for_median_ends`.
const SAMPLES_FOR_MEDIAN_ENDS: usize = 5;
/// The sliding window both algorithms keep — the legacy shift-register size.
const WINDOW: usize = SAMPLES_FOR_ESTIMATE + SAMPLES_FOR_MEDIAN_ENDS - 1;

/// A robust weight and mass-flow estimate from the scale stream.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Estimate {
    /// Robust current weight, grams.
    pub weight_g: f32,
    /// Robust mass-flow rate, grams per second.
    pub flow_g_per_s: f32,
}

/// Which algorithm a [`FlowEstimator`] uses.
///
/// Exposed so the shell can offer it as an admin option; the default is
/// [`FlowAlgorithm::TheilSen`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum FlowAlgorithm {
    /// The legacy de1app algorithm (`device_scale.tcl`'s `flow_median`): the
    /// difference of two windowed medians taken a fixed baseline apart.
    OffsetMedian,
    /// Theil–Sen robust regression — the median of every pairwise slope across
    /// the window, with the level extrapolated to the present. Lower noise and
    /// lower lag than [`Self::OffsetMedian`].
    #[default]
    TheilSen,
}

/// Estimates weight and mass-flow from a scale-weight stream, robust to
/// vibration. Feed every reading to [`update`](Self::update).
#[derive(Debug, Clone)]
pub struct FlowEstimator {
    algorithm: FlowAlgorithm,
    /// `(time_ms, weight_g)`, newest at the back, capped at [`WINDOW`].
    samples: VecDeque<(u64, f32)>,
}

impl FlowEstimator {
    /// Create an estimator using `algorithm`.
    pub fn new(algorithm: FlowAlgorithm) -> FlowEstimator {
        FlowEstimator {
            algorithm,
            samples: VecDeque::with_capacity(WINDOW),
        }
    }

    /// The algorithm in use.
    pub fn algorithm(&self) -> FlowAlgorithm {
        self.algorithm
    }

    /// Discard accumulated history — e.g. when re-arming for a new shot.
    pub fn reset(&mut self) {
        self.samples.clear();
    }

    /// Feed a scale-weight reading and return the current [`Estimate`].
    pub fn update(&mut self, weight_g: f32, now_ms: u64) -> Estimate {
        self.samples.push_back((now_ms, weight_g));
        while self.samples.len() > WINDOW {
            self.samples.pop_front();
        }
        match self.algorithm {
            FlowAlgorithm::OffsetMedian => self.offset_median_estimate(weight_g),
            FlowAlgorithm::TheilSen => self.theil_sen_estimate(weight_g),
        }
    }

    /// The sample `back` positions before the newest (`back == 0` is newest).
    fn at(&self, back: usize) -> (u64, f32) {
        self.samples[self.samples.len() - 1 - back]
    }

    /// Legacy algorithm: median weight, offset-median flow.
    fn offset_median_estimate(&self, latest: f32) -> Estimate {
        let recent = SAMPLES_FOR_ESTIMATE.min(self.samples.len());
        let weights: Vec<f32> = self
            .samples
            .iter()
            .rev()
            .take(recent)
            .map(|&(_, w)| w)
            .collect();
        Estimate {
            weight_g: median(&weights).unwrap_or(latest),
            flow_g_per_s: self.offset_median_flow().unwrap_or(0.0),
        }
    }

    /// `(new_median - old_median) / dt`, where the two `SAMPLES_FOR_MEDIAN_ENDS`
    /// windows sit at the ends of a `SAMPLES_FOR_ESTIMATE`-sample baseline.
    /// `None` until the window has filled.
    fn offset_median_flow(&self) -> Option<f32> {
        if self.samples.len() < WINDOW {
            return None;
        }
        let new_end = SAMPLES_FOR_MEDIAN_ENDS - 1;
        let old_start = SAMPLES_FOR_ESTIMATE - 1;
        let old_end = old_start + SAMPLES_FOR_MEDIAN_ENDS - 1;

        let new_weights: Vec<f32> = (0..=new_end).map(|b| self.at(b).1).collect();
        let old_weights: Vec<f32> = (old_start..=old_end).map(|b| self.at(b).1).collect();
        let new_median = median(&new_weights)?;
        let old_median = median(&old_weights)?;

        let new_centre = (self.at(0).0 as f64 + self.at(new_end).0 as f64) / 2.0;
        let old_centre = (self.at(old_start).0 as f64 + self.at(old_end).0 as f64) / 2.0;
        let dt_s = (new_centre - old_centre) / 1000.0;
        if dt_s <= 0.0 {
            return None;
        }
        Some((f64::from(new_median - old_median) / dt_s) as f32)
    }

    /// Theil–Sen: the median of every pairwise slope; the level is each sample
    /// projected to the present along that slope, then taken as a median.
    fn theil_sen_estimate(&self, latest: f32) -> Estimate {
        let n = self.samples.len();
        if n < 2 {
            return Estimate {
                weight_g: latest,
                flow_g_per_s: 0.0,
            };
        }
        let points: Vec<(f64, f32)> = self.samples.iter().map(|&(t, w)| (t as f64, w)).collect();

        let mut slopes: Vec<f32> = Vec::with_capacity(n * (n - 1) / 2);
        for i in 0..n {
            for j in (i + 1)..n {
                let dt_s = (points[j].0 - points[i].0) / 1000.0;
                if dt_s > 0.0 {
                    slopes.push((f64::from(points[j].1 - points[i].1) / dt_s) as f32);
                }
            }
        }
        let flow_g_per_s = median(&slopes).unwrap_or(0.0);

        // Project every sample forward to "now" along the slope, then median.
        let now = points[n - 1].0;
        let projected: Vec<f32> = points
            .iter()
            .map(|&(t, w)| w + flow_g_per_s * ((now - t) / 1000.0) as f32)
            .collect();
        Estimate {
            weight_g: median(&projected).unwrap_or(latest),
            flow_g_per_s,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Feed `count` samples of a clean ramp — `k` grams at `t = k·100 ms`, a
    /// 10 g/s line — and return the final estimate.
    fn feed_clean_ramp(estimator: &mut FlowEstimator, count: u64) -> Estimate {
        let mut estimate = Estimate {
            weight_g: 0.0,
            flow_g_per_s: 0.0,
        };
        for k in 0..count {
            estimate = estimator.update(k as f32, k * 100);
        }
        estimate
    }

    /// Feed a 15-sample 10 g/s ramp with a 1000 g spike at sample 12.
    fn feed_ramp_with_spike(estimator: &mut FlowEstimator) -> Estimate {
        let mut estimate = Estimate {
            weight_g: 0.0,
            flow_g_per_s: 0.0,
        };
        for k in 0..15u64 {
            let weight = if k == 12 { 1000.0 } else { k as f32 };
            estimate = estimator.update(weight, k * 100);
        }
        estimate
    }

    #[test]
    fn flow_algorithm_defaults_to_theil_sen() {
        assert_eq!(FlowAlgorithm::default(), FlowAlgorithm::TheilSen);
    }

    #[test]
    fn theil_sen_recovers_flow_from_a_clean_ramp() {
        let mut estimator = FlowEstimator::new(FlowAlgorithm::TheilSen);
        let estimate = feed_clean_ramp(&mut estimator, 15);
        assert!((estimate.flow_g_per_s - 10.0).abs() < 0.01);
    }

    #[test]
    fn offset_median_recovers_flow_from_a_clean_ramp() {
        let mut estimator = FlowEstimator::new(FlowAlgorithm::OffsetMedian);
        let estimate = feed_clean_ramp(&mut estimator, 15);
        assert!((estimate.flow_g_per_s - 10.0).abs() < 0.01);
    }

    #[test]
    fn the_two_algorithms_agree_on_a_clean_ramp() {
        let mut theil_sen = FlowEstimator::new(FlowAlgorithm::TheilSen);
        let mut offset_median = FlowEstimator::new(FlowAlgorithm::OffsetMedian);
        let a = feed_clean_ramp(&mut theil_sen, 15);
        let b = feed_clean_ramp(&mut offset_median, 15);
        assert!((a.flow_g_per_s - b.flow_g_per_s).abs() < 0.01);
    }

    #[test]
    fn offset_median_reports_no_flow_until_the_window_fills() {
        let mut estimator = FlowEstimator::new(FlowAlgorithm::OffsetMedian);
        assert_eq!(feed_clean_ramp(&mut estimator, 14).flow_g_per_s, 0.0);
        // The 15th sample completes the shift register.
        assert!(estimator.update(14.0, 1400).flow_g_per_s > 0.0);
    }

    #[test]
    fn theil_sen_reports_flow_before_the_window_fills() {
        let mut estimator = FlowEstimator::new(FlowAlgorithm::TheilSen);
        // Just three samples — Theil–Sen needs only two.
        let estimate = feed_clean_ramp(&mut estimator, 3);
        assert!((estimate.flow_g_per_s - 10.0).abs() < 0.01);
    }

    #[test]
    fn theil_sen_is_steadier_than_offset_median_under_a_spike() {
        let theil_sen =
            feed_ramp_with_spike(&mut FlowEstimator::new(FlowAlgorithm::TheilSen)).flow_g_per_s;
        let offset_median =
            feed_ramp_with_spike(&mut FlowEstimator::new(FlowAlgorithm::OffsetMedian)).flow_g_per_s;
        // Theil–Sen holds the true 10 g/s; the spike sits in the offset-median
        // recent window and shifts its result.
        assert!((theil_sen - 10.0).abs() < 1.0, "theil-sen flow {theil_sen}");
        assert!(
            (offset_median - theil_sen).abs() > 0.5,
            "offset-median flow {offset_median} should be more disturbed"
        );
    }

    #[test]
    fn reset_discards_history() {
        let mut estimator = FlowEstimator::new(FlowAlgorithm::OffsetMedian);
        feed_clean_ramp(&mut estimator, 15);
        estimator.reset();
        // Fresh again: the first reading after reset yields no flow.
        assert_eq!(estimator.update(5.0, 0).flow_g_per_s, 0.0);
    }
}

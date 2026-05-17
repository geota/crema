//! Automatic shot-stop logic — stop-at-weight (SAW) and stop-at-volume (SAV).
//!
//! [`AutoStop`] is sans-IO: feed it scale-weight readings and [`ShotSample`]
//! telemetry (each with a monotonic timestamp) and it decides, exactly once,
//! when the shot should be told to stop.
//!
//! SAW is **predictive** — it stops when the *projected* weight (the robust
//! current weight plus mass-flow times a look-ahead) reaches the target, so
//! the cup lands on target despite the lag between sending a stop and flow
//! ceasing. Weight and flow come from a [`FlowEstimator`] whose algorithm
//! ([`FlowAlgorithm`]) is part of [`StopConfig`] — feeding raw readings to
//! [`AutoStop::on_weight`] is enough; the estimator does the smoothing. SAV
//! integrates the telemetry group flow into a dispensed volume.

use de1_protocol::ShotSample;
use typeshare::typeshare;

use crate::flow::{FlowAlgorithm, FlowEstimator};

/// The targets that end a shot automatically. A `None` field disables that mode.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StopTargets {
    /// Stop when the cup reaches this weight, grams (SAW).
    pub weight_g: Option<f32>,
    /// Stop when this volume has been dispensed, mL (SAV).
    pub volume_ml: Option<f32>,
    /// Stop when the shot has run this long, seconds (legacy `espresso_max_time`,
    /// default 60 s).
    pub max_time: Option<f32>,
}

/// Tuning for the [`AutoStop`] predictor.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StopConfig {
    /// Seconds of mass-flow to look ahead when projecting weight, to compensate
    /// for the lag between sending a stop and flow ceasing.
    pub weight_lead_seconds: f32,
    /// Grams to stop short of the weight target (a constant fudge factor).
    pub weight_offset_g: f32,
    /// Suppress auto-stop for this long after the shot starts, letting flow
    /// settle before the predictor is trusted.
    pub arming_delay_ms: u64,
    /// Which algorithm estimates weight and mass-flow from the scale stream.
    pub flow_algorithm: FlowAlgorithm,
}

impl Default for StopConfig {
    /// Legacy-derived defaults: `weight_lead_seconds` is the legacy
    /// `stop_weight_before_seconds` setting default (0.15 s); `arming_delay_ms`
    /// is the legacy "ignore the first 5 s of flow" rule; the flow algorithm
    /// defaults to [`FlowAlgorithm::TheilSen`].
    fn default() -> StopConfig {
        StopConfig {
            weight_lead_seconds: 0.15,
            weight_offset_g: 0.0,
            arming_delay_ms: 5_000,
            flow_algorithm: FlowAlgorithm::TheilSen,
        }
    }
}

/// The DE1's own contribution to the SAW look-ahead — the legacy
/// `saw::_lag_time_de1` constant.
pub const DE1_LAG_SECONDS: f32 = 0.1;

/// The look-ahead the offset-median estimator adds: its windowed median lags
/// ~half a window (the legacy `saw::lag_time_estimation`). Theil–Sen
/// extrapolates to the present and adds none.
pub const OFFSET_MEDIAN_LAG_SECONDS: f32 = 0.5;

impl StopConfig {
    /// Build a config whose SAW look-ahead is the legacy app's four-term lead
    /// (`device_scale.tcl`): the user's `stop_weight_before_seconds` setting,
    /// the connected scale's sensor lag (`de1_scale::Scale::sensor_lag_seconds`),
    /// the fixed [`DE1_LAG_SECONDS`], and the chosen estimator's own lag —
    /// [`OFFSET_MEDIAN_LAG_SECONDS`] for [`FlowAlgorithm::OffsetMedian`], none
    /// for [`FlowAlgorithm::TheilSen`].
    pub fn with_legacy_lead(
        stop_weight_before_seconds: f32,
        scale_sensor_lag_seconds: f32,
        flow_algorithm: FlowAlgorithm,
    ) -> StopConfig {
        let estimator_lag = match flow_algorithm {
            FlowAlgorithm::OffsetMedian => OFFSET_MEDIAN_LAG_SECONDS,
            FlowAlgorithm::TheilSen => 0.0,
        };
        StopConfig {
            weight_lead_seconds: stop_weight_before_seconds
                + scale_sensor_lag_seconds
                + DE1_LAG_SECONDS
                + estimator_lag,
            flow_algorithm,
            ..StopConfig::default()
        }
    }
}

/// Why [`AutoStop`] decided to end the shot.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum StopReason {
    /// The weight target (SAW) was reached.
    Weight,
    /// The volume target (SAV) was reached.
    Volume,
    /// The maximum shot time was reached (legacy `espresso_max_time`).
    MaxTime,
}

/// Decides when to stop a shot, from the scale and telemetry streams.
///
/// Construct one when flow begins; feed it [`on_weight`](Self::on_weight) and
/// [`on_sample`](Self::on_sample). The first call that crosses a target returns
/// `Some(`[`StopReason`]`)`; every call after that returns `None`.
#[derive(Debug)]
pub struct AutoStop {
    targets: StopTargets,
    config: StopConfig,
    started_ms: u64,
    dispensed_volume_ml: f32,
    flow: FlowEstimator,
    last_sample_ms: Option<u64>,
    triggered: bool,
}

impl AutoStop {
    /// Arm an auto-stop for a shot that started flowing at `started_ms`.
    pub fn new(targets: StopTargets, config: StopConfig, started_ms: u64) -> AutoStop {
        AutoStop {
            targets,
            config,
            started_ms,
            dispensed_volume_ml: 0.0,
            flow: FlowEstimator::new(config.flow_algorithm),
            last_sample_ms: None,
            triggered: false,
        }
    }

    /// Whether a stop has already been decided.
    pub fn has_triggered(&self) -> bool {
        self.triggered
    }

    /// The volume dispensed so far, mL (integrated from telemetry flow).
    pub fn dispensed_volume_ml(&self) -> f32 {
        self.dispensed_volume_ml
    }

    /// Whether enough time has passed since the start for auto-stop to act.
    fn is_armed(&self, now_ms: u64) -> bool {
        now_ms.saturating_sub(self.started_ms) >= self.config.arming_delay_ms
    }

    /// Check the max-shot-time target against the elapsed time. Returns
    /// [`StopReason::MaxTime`] the first time the shot has run too long.
    ///
    /// Unlike SAW/SAV this is not gated by the arming delay — a `max_time`
    /// shorter than `arming_delay_ms` would otherwise never fire.
    fn check_max_time(&mut self, now_ms: u64) -> Option<StopReason> {
        let max_time_s = self.targets.max_time?;
        let elapsed_s = now_ms.saturating_sub(self.started_ms) as f32 / 1000.0;
        if elapsed_s >= max_time_s {
            self.triggered = true;
            return Some(StopReason::MaxTime);
        }
        None
    }

    /// Feed a raw scale-weight reading (grams). The reading passes through the
    /// flow estimator; SAW projects the robust weight forward along the flow
    /// and returns [`StopReason::Weight`] the first time it reaches the target.
    pub fn on_weight(&mut self, grams: f32, now_ms: u64) -> Option<StopReason> {
        if self.triggered {
            return None;
        }
        let estimate = self.flow.update(grams, now_ms);

        if let Some(reason) = self.check_max_time(now_ms) {
            return Some(reason);
        }

        let target = self.targets.weight_g?;
        if !self.is_armed(now_ms) {
            return None;
        }
        // Project the robust weight one look-ahead forward along the flow.
        let projected =
            estimate.weight_g + estimate.flow_g_per_s.max(0.0) * self.config.weight_lead_seconds;
        if projected >= target - self.config.weight_offset_g {
            self.triggered = true;
            return Some(StopReason::Weight);
        }
        None
    }

    /// Feed a telemetry sample. Integrates its flow into the dispensed volume
    /// and returns [`StopReason::Volume`] the first time it reaches the SAV
    /// target.
    pub fn on_sample(&mut self, sample: &ShotSample, now_ms: u64) -> Option<StopReason> {
        if self.triggered {
            return None;
        }
        // Integrate flow (mL/s) over the interval since the last sample.
        if let Some(then) = self.last_sample_ms
            && now_ms > then
        {
            let dt_s = (now_ms - then) as f32 / 1000.0;
            self.dispensed_volume_ml += sample.group_flow * dt_s;
        }
        self.last_sample_ms = Some(now_ms);

        if let Some(reason) = self.check_max_time(now_ms) {
            return Some(reason);
        }

        let target = self.targets.volume_ml?;
        if !self.is_armed(now_ms) {
            return None;
        }
        if self.dispensed_volume_ml >= target {
            self.triggered = true;
            return Some(StopReason::Volume);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Config with no arming delay and no look-ahead — for testing the bare
    /// threshold behaviour. Uses the default (Theil–Sen) flow algorithm.
    fn immediate_config() -> StopConfig {
        StopConfig {
            weight_lead_seconds: 0.0,
            weight_offset_g: 0.0,
            arming_delay_ms: 0,
            flow_algorithm: FlowAlgorithm::TheilSen,
        }
    }

    /// A telemetry sample carrying the given group flow; other fields zeroed.
    fn sample_with_flow(group_flow: f32) -> ShotSample {
        ShotSample {
            sample_time: 0,
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

    #[test]
    fn saw_stops_when_weight_reaches_the_target() {
        let targets = StopTargets {
            weight_g: Some(36.0),
            volume_ml: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 0);
        assert_eq!(stop.on_weight(35.0, 1000), None);
        assert_eq!(stop.on_weight(36.0, 2000), Some(StopReason::Weight));
    }

    #[test]
    fn saw_predicts_ahead_using_mass_flow() {
        let targets = StopTargets {
            weight_g: Some(36.0),
            volume_ml: None,
            max_time: None,
        };
        let config = StopConfig {
            weight_lead_seconds: 1.0,
            ..immediate_config()
        };
        let mut stop = AutoStop::new(targets, config, 0);
        // First reading: no flow estimate yet, well under target.
        assert_eq!(stop.on_weight(30.0, 1000), None);
        // +4 g in 1 s -> 4 g/s; projected 34 + 4*1.0 = 38 >= 36 -> stop early.
        assert_eq!(stop.on_weight(34.0, 2000), Some(StopReason::Weight));
    }

    #[test]
    fn saw_does_not_fire_before_the_arming_delay() {
        let targets = StopTargets {
            weight_g: Some(36.0),
            volume_ml: None,
            max_time: None,
        };
        let config = StopConfig {
            arming_delay_ms: 5_000,
            ..immediate_config()
        };
        let mut stop = AutoStop::new(targets, config, 0);
        assert_eq!(stop.on_weight(40.0, 1_000), None); // over target, but not armed
        assert_eq!(stop.on_weight(40.0, 6_000), Some(StopReason::Weight));
    }

    #[test]
    fn saw_ignores_a_weight_spike_via_the_estimator() {
        let targets = StopTargets {
            weight_g: Some(36.0),
            volume_ml: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 0);
        // A slow ramp whose trend sits near 32 g — comfortably under target.
        for (weight, now) in [
            (30.0, 0),
            (30.5, 100),
            (31.0, 200),
            (31.5, 300),
            (32.0, 400),
        ] {
            assert_eq!(stop.on_weight(weight, now), None);
        }
        // A 1000 g vibration spike must not trip SAW — the estimator rejects it.
        assert_eq!(stop.on_weight(1000.0, 500), None);
    }

    #[test]
    fn saw_works_with_the_offset_median_algorithm() {
        let targets = StopTargets {
            weight_g: Some(36.0),
            volume_ml: None,
            max_time: None,
        };
        let config = StopConfig {
            flow_algorithm: FlowAlgorithm::OffsetMedian,
            ..immediate_config()
        };
        let mut stop = AutoStop::new(targets, config, 0);
        // A ramp climbing past the target eventually trips SAW.
        let triggered = (0..20u64)
            .any(|k| stop.on_weight(30.0 + k as f32, k * 100) == Some(StopReason::Weight));
        assert!(triggered);
    }

    #[test]
    fn sav_stops_when_integrated_volume_reaches_the_target() {
        let targets = StopTargets {
            weight_g: None,
            volume_ml: Some(36.0),
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 0);
        // First sample establishes the integration baseline.
        assert_eq!(stop.on_sample(&sample_with_flow(4.0), 1_000), None);
        // 4 mL/s over the next 9 s -> 36 mL dispensed.
        assert_eq!(
            stop.on_sample(&sample_with_flow(4.0), 10_000),
            Some(StopReason::Volume)
        );
    }

    #[test]
    fn auto_stop_fires_only_once() {
        let targets = StopTargets {
            weight_g: Some(36.0),
            volume_ml: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 0);
        assert_eq!(stop.on_weight(40.0, 1000), Some(StopReason::Weight));
        assert_eq!(stop.on_weight(50.0, 2000), None);
        assert!(stop.has_triggered());
    }

    #[test]
    fn disabled_targets_never_trigger() {
        let targets = StopTargets {
            weight_g: None,
            volume_ml: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 0);
        assert_eq!(stop.on_weight(999.0, 1000), None);
        assert_eq!(stop.on_sample(&sample_with_flow(99.0), 2000), None);
        assert!(!stop.has_triggered());
    }

    #[test]
    fn volume_is_integrated_from_the_flow_stream() {
        let targets = StopTargets {
            weight_g: None,
            volume_ml: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 0);
        stop.on_sample(&sample_with_flow(2.0), 0);
        stop.on_sample(&sample_with_flow(2.0), 5_000); // 2 mL/s * 5 s
        assert_eq!(stop.dispensed_volume_ml(), 10.0);
    }

    #[test]
    fn with_legacy_lead_sums_the_four_terms() {
        // 0.15 setting + 0.50 sensor lag + 0.1 DE1 lag; Theil–Sen adds none.
        let config = StopConfig::with_legacy_lead(0.15, 0.50, FlowAlgorithm::TheilSen);
        assert!((config.weight_lead_seconds - 0.75).abs() < 1e-6);
        assert_eq!(config.flow_algorithm, FlowAlgorithm::TheilSen);
    }

    #[test]
    fn max_time_stops_the_shot_via_on_weight() {
        let targets = StopTargets {
            weight_g: None,
            volume_ml: None,
            max_time: Some(30.0),
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 0);
        assert_eq!(stop.on_weight(10.0, 29_000), None);
        assert_eq!(stop.on_weight(10.0, 30_000), Some(StopReason::MaxTime));
    }

    #[test]
    fn max_time_stops_the_shot_via_on_sample() {
        let targets = StopTargets {
            weight_g: None,
            volume_ml: None,
            max_time: Some(60.0),
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 1_000);
        assert_eq!(stop.on_sample(&sample_with_flow(2.0), 30_000), None);
        assert_eq!(
            stop.on_sample(&sample_with_flow(2.0), 61_000),
            Some(StopReason::MaxTime)
        );
    }

    #[test]
    fn max_time_ignores_the_arming_delay() {
        // A max_time shorter than the arming delay must still fire.
        let targets = StopTargets {
            weight_g: None,
            volume_ml: None,
            max_time: Some(2.0),
        };
        let config = StopConfig {
            arming_delay_ms: 5_000,
            ..immediate_config()
        };
        let mut stop = AutoStop::new(targets, config, 0);
        assert_eq!(stop.on_weight(10.0, 2_000), Some(StopReason::MaxTime));
    }

    #[test]
    fn max_time_fires_only_once() {
        let targets = StopTargets {
            weight_g: None,
            volume_ml: None,
            max_time: Some(10.0),
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 0);
        assert_eq!(stop.on_weight(10.0, 10_000), Some(StopReason::MaxTime));
        assert_eq!(stop.on_weight(10.0, 20_000), None);
        assert!(stop.has_triggered());
    }

    #[test]
    fn offset_median_adds_its_lag_to_the_lead() {
        let theil_sen = StopConfig::with_legacy_lead(0.15, 0.50, FlowAlgorithm::TheilSen);
        let offset_median = StopConfig::with_legacy_lead(0.15, 0.50, FlowAlgorithm::OffsetMedian);
        let added = offset_median.weight_lead_seconds - theil_sen.weight_lead_seconds;
        assert!((added - OFFSET_MEDIAN_LAG_SECONDS).abs() < 1e-6);
    }
}

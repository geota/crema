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

use std::time::Duration;

use de1_protocol::ShotSample;
use typeshare::typeshare;

use crate::flow::{FlowAlgorithm, FlowEstimator};

/// The targets that end a shot automatically. A `None` field disables that mode.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StopTargets {
    /// Stop when the cup reaches this weight, grams (SAW).
    pub weight: Option<f32>,
    /// Stop when this volume has been dispensed, ml (SAV).
    pub volume: Option<f32>,
    /// Stop when the shot has run this long, seconds (the legacy
    /// `espresso_max_time`). This type has no `Default`; the legacy app's
    /// `espresso_max_time` setting itself defaults to 60 s, but a `StopTargets`
    /// has no time limit unless one is set here, and `None` disables the mode.
    pub max_time: Option<f32>,
}

/// Tuning for the [`AutoStop`] predictor.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StopConfig {
    /// How far ahead to project weight along the mass flow, to compensate for
    /// the lag between sending a stop and flow ceasing.
    pub weight_lead: Duration,
    /// Grams to stop short of the weight target (a constant fudge factor).
    pub weight_offset: f32,
    /// Suppress auto-stop for this long after the shot starts, letting flow
    /// settle before the predictor is trusted.
    pub arming_delay: Duration,
    /// Which algorithm estimates weight and mass-flow from the scale stream.
    pub flow_algorithm: FlowAlgorithm,
}

impl Default for StopConfig {
    /// Legacy-derived defaults: `weight_lead` is the legacy
    /// `stop_weight_before_seconds` setting default (0.15 s); `arming_delay`
    /// is the legacy "ignore the first 5 s of flow" rule; the flow algorithm
    /// defaults to [`FlowAlgorithm::TheilSen`].
    fn default() -> StopConfig {
        StopConfig {
            weight_lead: STOP_WEIGHT_BEFORE,
            weight_offset: 0.0,
            arming_delay: Duration::from_secs(5),
            flow_algorithm: FlowAlgorithm::TheilSen,
        }
    }
}

/// The legacy `stop_weight_before_seconds` setting default — the user-tunable
/// part of the SAW look-ahead (the canonical home for this constant; the
/// `de1-app` facade references it rather than re-declaring its own).
pub const STOP_WEIGHT_BEFORE: Duration = Duration::from_millis(150);

/// The DE1's own contribution to the SAW look-ahead — the legacy
/// `saw::_lag_time_de1` constant.
pub const DE1_LAG: Duration = Duration::from_millis(100);

/// The look-ahead the offset-median estimator adds: its windowed median lags
/// ~half a window (the legacy `saw::lag_time_estimation`). Theil–Sen
/// extrapolates to the present and adds none.
pub const OFFSET_MEDIAN_LAG: Duration = Duration::from_millis(500);

impl StopConfig {
    /// Build a config whose SAW look-ahead is the legacy app's four-term lead
    /// (`device_scale.tcl`): the user's `stop_weight_before` setting, the
    /// connected scale's sensor lag (`de1_scale::Scale::sensor_lag_seconds`),
    /// the fixed [`DE1_LAG`], and the chosen estimator's own lag —
    /// [`OFFSET_MEDIAN_LAG`] for [`FlowAlgorithm::OffsetMedian`], none for
    /// [`FlowAlgorithm::TheilSen`].
    pub fn with_legacy_lead(
        stop_weight_before: Duration,
        scale_sensor_lag: Duration,
        flow_algorithm: FlowAlgorithm,
    ) -> StopConfig {
        let estimator_lag = match flow_algorithm {
            FlowAlgorithm::OffsetMedian => OFFSET_MEDIAN_LAG,
            FlowAlgorithm::TheilSen => Duration::ZERO,
        };
        StopConfig {
            weight_lead: stop_weight_before + scale_sensor_lag + DE1_LAG + estimator_lag,
            flow_algorithm,
            ..StopConfig::default()
        }
    }
}

/// Why [`AutoStop`] decided to end the shot.
///
/// `#[non_exhaustive]` so further stop reasons (e.g. a future puck-collapse
/// trigger) can be added without breaking the FFI surface — this enum is the
/// payload of `Event::StopTriggered`.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
#[non_exhaustive]
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
    /// Monotonic timestamp the shot started flowing — see [`AutoStop::new`].
    started: Duration,
    flow: FlowEstimator,
    triggered: bool,
}

impl AutoStop {
    /// Arm an auto-stop for a shot that started flowing at `started`.
    pub fn new(targets: StopTargets, config: StopConfig, started: Duration) -> AutoStop {
        AutoStop {
            targets,
            config,
            started,
            flow: FlowEstimator::new(config.flow_algorithm),
            triggered: false,
        }
    }

    /// Whether a stop has already been decided.
    pub fn has_triggered(&self) -> bool {
        self.triggered
    }

    /// Whether enough time has passed since the start for auto-stop to act.
    fn is_armed(&self, now: Duration) -> bool {
        now.saturating_sub(self.started) >= self.config.arming_delay
    }

    /// Check the max-shot-time target against the elapsed time. Returns
    /// [`StopReason::MaxTime`] the first time the shot has run too long.
    ///
    /// Unlike SAW/SAV this is not gated by the arming delay — a `max_time`
    /// shorter than `config.arming_delay` would otherwise never fire.
    fn check_max_time(&mut self, now: Duration) -> Option<StopReason> {
        let max_time_s = self.targets.max_time?;
        let elapsed_s = now.saturating_sub(self.started).as_secs_f32();
        if elapsed_s >= max_time_s {
            self.triggered = true;
            return Some(StopReason::MaxTime);
        }
        None
    }

    /// Feed a raw scale-weight reading (grams). The reading passes through the
    /// flow estimator; SAW projects the robust weight forward along the flow
    /// and returns [`StopReason::Weight`] the first time it reaches the target.
    pub fn on_weight(&mut self, grams: f32, now: Duration) -> Option<StopReason> {
        if self.triggered {
            return None;
        }
        let estimate = self.flow.update(grams, now);

        if let Some(reason) = self.check_max_time(now) {
            return Some(reason);
        }

        let target = self.targets.weight?;
        if !self.is_armed(now) {
            return None;
        }
        // Project the robust weight one look-ahead forward along the flow.
        let projected =
            estimate.weight + estimate.flow.max(0.0) * self.config.weight_lead.as_secs_f32();
        if projected >= target - self.config.weight_offset {
            self.triggered = true;
            return Some(StopReason::Weight);
        }
        None
    }

    /// Feed a telemetry sample. The volume integration lives outside
    /// AutoStop now ([`VolumeIntegrator`](crate::VolumeIntegrator) on the
    /// orchestrator) — `dispensed_ml` carries the current running volume,
    /// so AutoStop only does the SAV comparison without re-integrating.
    /// Returns [`StopReason::Volume`] the first time `dispensed_ml`
    /// reaches the SAV target.
    pub fn on_sample(
        &mut self,
        _sample: &ShotSample,
        now: Duration,
        dispensed_ml: f32,
    ) -> Option<StopReason> {
        if self.triggered {
            return None;
        }

        if let Some(reason) = self.check_max_time(now) {
            return Some(reason);
        }

        let target = self.targets.volume?;
        if !self.is_armed(now) {
            return None;
        }
        if dispensed_ml >= target {
            self.triggered = true;
            return Some(StopReason::Volume);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: a `Duration` of `ms` milliseconds.
    fn ms(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    /// Config with no arming delay and no look-ahead — for testing the bare
    /// threshold behaviour. Uses the default (Theil–Sen) flow algorithm.
    fn immediate_config() -> StopConfig {
        StopConfig {
            weight_lead: Duration::ZERO,
            weight_offset: 0.0,
            arming_delay: Duration::ZERO,
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
            weight: Some(36.0),
            volume: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), Duration::ZERO);
        assert_eq!(stop.on_weight(35.0, ms(1000)), None);
        assert_eq!(stop.on_weight(36.0, ms(2000)), Some(StopReason::Weight));
    }

    #[test]
    fn saw_predicts_ahead_using_mass_flow() {
        let targets = StopTargets {
            weight: Some(36.0),
            volume: None,
            max_time: None,
        };
        let config = StopConfig {
            weight_lead: Duration::from_secs(1),
            ..immediate_config()
        };
        let mut stop = AutoStop::new(targets, config, Duration::ZERO);
        // First reading: no flow estimate yet, well under target.
        assert_eq!(stop.on_weight(30.0, ms(1000)), None);
        // +4 g in 1 s -> 4 g/s; projected 34 + 4*1.0 = 38 >= 36 -> stop early.
        assert_eq!(stop.on_weight(34.0, ms(2000)), Some(StopReason::Weight));
    }

    #[test]
    fn saw_does_not_fire_before_the_arming_delay() {
        let targets = StopTargets {
            weight: Some(36.0),
            volume: None,
            max_time: None,
        };
        let config = StopConfig {
            arming_delay: ms(5_000),
            ..immediate_config()
        };
        let mut stop = AutoStop::new(targets, config, Duration::ZERO);
        assert_eq!(stop.on_weight(40.0, ms(1_000)), None); // over target, but not armed
        assert_eq!(stop.on_weight(40.0, ms(6_000)), Some(StopReason::Weight));
    }

    #[test]
    fn saw_ignores_a_weight_spike_via_the_estimator() {
        let targets = StopTargets {
            weight: Some(36.0),
            volume: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), Duration::ZERO);
        // A slow ramp whose trend sits near 32 g — comfortably under target.
        for (weight, now) in [
            (30.0, ms(0)),
            (30.5, ms(100)),
            (31.0, ms(200)),
            (31.5, ms(300)),
            (32.0, ms(400)),
        ] {
            assert_eq!(stop.on_weight(weight, now), None);
        }
        // A 1000 g vibration spike must not trip SAW — the estimator rejects it.
        assert_eq!(stop.on_weight(1000.0, ms(500)), None);
    }

    #[test]
    fn saw_works_with_the_offset_median_algorithm() {
        let targets = StopTargets {
            weight: Some(36.0),
            volume: None,
            max_time: None,
        };
        let config = StopConfig {
            flow_algorithm: FlowAlgorithm::OffsetMedian,
            ..immediate_config()
        };
        let mut stop = AutoStop::new(targets, config, Duration::ZERO);
        // A ramp climbing past the target eventually trips SAW.
        let triggered = (0..20u8).any(|k| {
            stop.on_weight(30.0 + f32::from(k), ms(u64::from(k) * 100)) == Some(StopReason::Weight)
        });
        assert!(triggered);
    }

    #[test]
    fn sav_stops_when_dispensed_volume_reaches_the_target() {
        let targets = StopTargets {
            weight: None,
            volume: Some(36.0),
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), Duration::ZERO);
        // Below the target → no stop.
        assert_eq!(stop.on_sample(&sample_with_flow(4.0), ms(1_000), 4.0), None);
        // At/past the target → SAV fires.
        assert_eq!(
            stop.on_sample(&sample_with_flow(4.0), ms(10_000), 36.0),
            Some(StopReason::Volume)
        );
    }

    #[test]
    fn auto_stop_fires_only_once() {
        let targets = StopTargets {
            weight: Some(36.0),
            volume: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), Duration::ZERO);
        assert_eq!(stop.on_weight(40.0, ms(1000)), Some(StopReason::Weight));
        assert_eq!(stop.on_weight(50.0, ms(2000)), None);
        assert!(stop.has_triggered());
    }

    #[test]
    fn disabled_targets_never_trigger() {
        let targets = StopTargets {
            weight: None,
            volume: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), Duration::ZERO);
        assert_eq!(stop.on_weight(999.0, ms(1000)), None);
        assert_eq!(stop.on_sample(&sample_with_flow(99.0), ms(2000), 0.0), None);
        assert!(!stop.has_triggered());
    }

    #[test]
    fn with_legacy_lead_sums_the_four_terms() {
        // 0.15 setting + 0.50 sensor lag + 0.1 DE1 lag; Theil–Sen adds none.
        let config = StopConfig::with_legacy_lead(
            Duration::from_millis(150),
            Duration::from_millis(500),
            FlowAlgorithm::TheilSen,
        );
        assert_eq!(config.weight_lead, Duration::from_millis(750));
        assert_eq!(config.flow_algorithm, FlowAlgorithm::TheilSen);
    }

    #[test]
    fn max_time_stops_the_shot_via_on_weight() {
        let targets = StopTargets {
            weight: None,
            volume: None,
            max_time: Some(30.0),
        };
        let mut stop = AutoStop::new(targets, immediate_config(), Duration::ZERO);
        assert_eq!(stop.on_weight(10.0, ms(29_000)), None);
        assert_eq!(stop.on_weight(10.0, ms(30_000)), Some(StopReason::MaxTime));
    }

    #[test]
    fn max_time_stops_the_shot_via_on_sample() {
        let targets = StopTargets {
            weight: None,
            volume: None,
            max_time: Some(60.0),
        };
        let mut stop = AutoStop::new(targets, immediate_config(), ms(1_000));
        assert_eq!(
            stop.on_sample(&sample_with_flow(2.0), ms(30_000), 0.0),
            None
        );
        assert_eq!(
            stop.on_sample(&sample_with_flow(2.0), ms(61_000), 0.0),
            Some(StopReason::MaxTime)
        );
    }

    #[test]
    fn max_time_ignores_the_arming_delay() {
        // A max_time shorter than the arming delay must still fire.
        let targets = StopTargets {
            weight: None,
            volume: None,
            max_time: Some(2.0),
        };
        let config = StopConfig {
            arming_delay: ms(5_000),
            ..immediate_config()
        };
        let mut stop = AutoStop::new(targets, config, Duration::ZERO);
        assert_eq!(stop.on_weight(10.0, ms(2_000)), Some(StopReason::MaxTime));
    }

    #[test]
    fn max_time_fires_only_once() {
        let targets = StopTargets {
            weight: None,
            volume: None,
            max_time: Some(10.0),
        };
        let mut stop = AutoStop::new(targets, immediate_config(), Duration::ZERO);
        assert_eq!(stop.on_weight(10.0, ms(10_000)), Some(StopReason::MaxTime));
        assert_eq!(stop.on_weight(10.0, ms(20_000)), None);
        assert!(stop.has_triggered());
    }

    #[test]
    fn offset_median_adds_its_lag_to_the_lead() {
        let stop_weight_before = Duration::from_millis(150);
        let scale_lag = Duration::from_millis(500);
        let theil_sen =
            StopConfig::with_legacy_lead(stop_weight_before, scale_lag, FlowAlgorithm::TheilSen);
        let offset_median = StopConfig::with_legacy_lead(
            stop_weight_before,
            scale_lag,
            FlowAlgorithm::OffsetMedian,
        );
        assert_eq!(
            offset_median.weight_lead - theil_sen.weight_lead,
            OFFSET_MEDIAN_LAG,
        );
    }
}

//! Automatic shot-stop logic — stop-at-weight (SAW) and stop-at-volume (SAV).
//!
//! [`AutoStop`] is sans-IO: feed it scale-weight readings and [`ShotSample`]
//! telemetry (each with a monotonic timestamp) and it decides, exactly once,
//! when the shot should be told to stop.
//!
//! SAW is **predictive** — it stops when the *projected* weight (current weight
//! plus the recent mass-flow times a look-ahead time) reaches the target, so
//! the cup lands on target despite the lag between sending a stop and flow
//! actually ceasing. SAV integrates the group flow into a dispensed volume.
//!
//! The SAW look-ahead ([`StopConfig::weight_lead_seconds`]) is the legacy
//! app's four-term lead — `stop_weight_before_seconds`, the scale's sensor
//! lag, a fixed DE1 lag, and the median-filter lag — composed by
//! [`StopConfig::with_legacy_lead`]. When the scale signal is vibration-noisy,
//! median-filter the weight with [`crate::filter::MedianFilter`] before
//! [`AutoStop::on_weight`] and build the config with `median_filtered = true`
//! so the look-ahead accounts for the filter's delay.

use de1_protocol::ShotSample;

/// The targets that end a shot automatically. A `None` field disables that mode.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StopTargets {
    /// Stop when the cup reaches this weight, grams (SAW).
    pub weight_g: Option<f32>,
    /// Stop when this volume has been dispensed, mL (SAV).
    pub volume_ml: Option<f32>,
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
}

impl Default for StopConfig {
    /// Legacy-derived defaults: `weight_lead_seconds` is the legacy
    /// `stop_weight_before_seconds` setting default (0.15 s); `arming_delay_ms`
    /// is the legacy "ignore the first 5 s of flow" rule.
    fn default() -> StopConfig {
        StopConfig {
            weight_lead_seconds: 0.15,
            weight_offset_g: 0.0,
            arming_delay_ms: 5_000,
        }
    }
}

/// The DE1's own contribution to the SAW look-ahead — the legacy
/// `saw::_lag_time_de1` constant.
pub const DE1_LAG_SECONDS: f32 = 0.1;

/// Extra look-ahead the median weight filter introduces — the legacy
/// `saw::lag_time_estimation` value when high-vibration filtering is enabled.
pub const MEDIAN_FILTER_LAG_SECONDS: f32 = 0.5;

impl StopConfig {
    /// Build a config whose SAW look-ahead is the legacy app's four-term lead
    /// (`device_scale.tcl`): the user's `stop_weight_before_seconds` setting,
    /// the connected scale's sensor lag (`de1_scale::Scale::sensor_lag_seconds`),
    /// the fixed [`DE1_LAG_SECONDS`], and — when the weight stream is
    /// median-filtered — [`MEDIAN_FILTER_LAG_SECONDS`].
    pub fn with_legacy_lead(
        stop_weight_before_seconds: f32,
        scale_sensor_lag_seconds: f32,
        median_filtered: bool,
    ) -> StopConfig {
        let filter_lag = if median_filtered {
            MEDIAN_FILTER_LAG_SECONDS
        } else {
            0.0
        };
        StopConfig {
            weight_lead_seconds: stop_weight_before_seconds
                + scale_sensor_lag_seconds
                + DE1_LAG_SECONDS
                + filter_lag,
            ..StopConfig::default()
        }
    }
}

/// Why [`AutoStop`] decided to end the shot.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopReason {
    /// The weight target (SAW) was reached.
    Weight,
    /// The volume target (SAV) was reached.
    Volume,
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
    last_weight: Option<(u64, f32)>,
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
            last_weight: None,
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

    /// Feed a scale-weight reading (grams). Returns [`StopReason::Weight`] the
    /// first time the projected weight reaches the SAW target.
    pub fn on_weight(&mut self, grams: f32, now_ms: u64) -> Option<StopReason> {
        if self.triggered {
            return None;
        }
        // Estimate mass flow (g/s) from the change since the last reading.
        let mass_flow = match self.last_weight {
            Some((then, prev)) if now_ms > then => {
                let dt_s = (now_ms - then) as f32 / 1000.0;
                (grams - prev) / dt_s
            }
            _ => 0.0,
        };
        self.last_weight = Some((now_ms, grams));

        let target = self.targets.weight_g?;
        if !self.is_armed(now_ms) {
            return None;
        }
        // Project where the weight will be one look-ahead from now.
        let projected = grams + mass_flow.max(0.0) * self.config.weight_lead_seconds;
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
        if let Some(then) = self.last_sample_ms {
            if now_ms > then {
                let dt_s = (now_ms - then) as f32 / 1000.0;
                self.dispensed_volume_ml += sample.group_flow * dt_s;
            }
        }
        self.last_sample_ms = Some(now_ms);

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
    /// threshold behaviour.
    fn immediate_config() -> StopConfig {
        StopConfig {
            weight_lead_seconds: 0.0,
            weight_offset_g: 0.0,
            arming_delay_ms: 0,
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
    fn sav_stops_when_integrated_volume_reaches_the_target() {
        let targets = StopTargets {
            weight_g: None,
            volume_ml: Some(36.0),
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
        };
        let mut stop = AutoStop::new(targets, immediate_config(), 0);
        stop.on_sample(&sample_with_flow(2.0), 0);
        stop.on_sample(&sample_with_flow(2.0), 5_000); // 2 mL/s * 5 s
        assert_eq!(stop.dispensed_volume_ml(), 10.0);
    }

    #[test]
    fn with_legacy_lead_sums_the_four_terms() {
        // 0.15 setting + 0.50 sensor lag + 0.1 DE1 lag, no median filter.
        let config = StopConfig::with_legacy_lead(0.15, 0.50, false);
        assert!((config.weight_lead_seconds - 0.75).abs() < 1e-6);
    }

    #[test]
    fn median_filtering_adds_its_lag_to_the_lead() {
        let plain = StopConfig::with_legacy_lead(0.15, 0.50, false);
        let filtered = StopConfig::with_legacy_lead(0.15, 0.50, true);
        let added = filtered.weight_lead_seconds - plain.weight_lead_seconds;
        assert!((added - MEDIAN_FILTER_LAG_SECONDS).abs() < 1e-6);
    }
}

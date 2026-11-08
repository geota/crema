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

use de1_protocol::{MachineState, ShotSample, SubState};
use typeshare::typeshare;

use crate::flow::{FlowAlgorithm, FlowEstimator};
use crate::saw_learning::{SawLearningModel, SawSnapshot};
use crate::scale_trust::WeightAuthority;
use crate::session::{Blocked, LastTrusted};

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

    /// The config for a hot-water pour (`DESIGN.md` §3.3): the weight leg
    /// projects one sensor lag ahead (reaprime `HotWaterSequencer`), with no
    /// arming delay, no offset and no drip model.
    pub fn hot_water(scale_sensor_lag: Duration) -> StopConfig {
        StopConfig {
            weight_lead: scale_sensor_lag,
            weight_offset: 0.0,
            arming_delay: Duration::ZERO,
            flow_algorithm: FlowAlgorithm::TheilSen,
        }
    }
}

/// What the user asked to end a session with, before trust is applied.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct StopIntent {
    /// The weight target, grams: set, on, and not dotted off.
    pub weight: Option<f32>,
    /// The profile's volume limit, ml.
    pub volume_limit: Option<f32>,
    /// The max shot time, seconds.
    pub max_time: Option<f32>,
    /// The user wants the volume limit live alongside the weight target.
    pub volume_stop_with_scale: bool,
    /// The pour the volume limit counts from, ml: 0, or the pour when a shot
    /// whose flow began on an unknown substate re-anchored (REVIEW-385). The
    /// backstop always counts every ml.
    pub limit_from_ml: f32,
}

/// The estimated leg (`DESIGN.md` §3.5): with the scale lost after flow
/// reached the cup, stop when `pour_ml ≥ last.pour_ml + (target − lead_g −
/// last.weight_g)`. Only the remainder is estimated, 1 ml to 1 g.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct EstimatedLeg {
    /// The weight target, grams.
    pub target_g: f32,
    /// The last trusted weight and its pour.
    pub last: LastTrusted,
}

impl EstimatedLeg {
    /// The pour at which the leg fires, given the weight leg's lead in grams.
    #[must_use]
    pub fn stop_at_ml(&self, lead_g: f32) -> f32 {
        self.last.pour_ml + (self.target_g - lead_g - self.last.weight_g)
    }
}

/// What can end the session now, and why the weight leg can't, if it can't.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ResolvedLegs {
    /// The weight leg, grams.
    pub weight: Option<f32>,
    /// The estimated leg, standing in for weight under `Lost`.
    pub estimated: Option<EstimatedLeg>,
    /// The volume leg, ml: the limit, or a backstop.
    pub volume: Option<f32>,
    /// The time leg, seconds.
    pub max_time: Option<f32>,
    /// Why a configured weight target isn't live.
    pub blocked: Option<Blocked>,
}

impl ResolvedLegs {
    /// The legs as [`StopTargets`], or `None` when nothing is live.
    #[must_use]
    pub fn targets(&self) -> Option<StopTargets> {
        (self.weight.is_some() || self.volume.is_some() || self.max_time.is_some()).then_some(
            StopTargets {
                weight: self.weight,
                volume: self.volume,
                max_time: self.max_time,
            },
        )
    }

    /// The weight row's note: what the estimate is doing in weight's place,
    /// or why weight is blocked.
    #[must_use]
    pub fn note(&self) -> Option<&'static str> {
        let blocked = self.blocked?;
        Some(if self.estimated.is_some() {
            blocked.estimating_note()
        } else {
            blocked.note()
        })
    }
}

/// The pour the espresso backstop stops at: `max(limit, 60 + 1.5 × target)`,
/// ml (114 ml at 36 g). Provisional, from one shot (`DESIGN.md` §3.5).
#[must_use]
pub fn espresso_backstop_ml(limit: Option<f32>, target_g: f32) -> f32 {
    let backstop = 60.0 + 1.5 * target_g;
    limit.map_or(backstop, |l| l.max(backstop))
}

/// Where a session stands, for [`resolve_legs`].
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LegContext {
    /// What the weight may do.
    pub authority: WeightAuthority,
    /// The scale link is gone for good (dead or absent), not on a blip.
    pub scale_gone: bool,
    /// Why the session refused its scale, if it did.
    pub refused: Option<Blocked>,
}

/// The espresso legs, re-derived on every input (`DESIGN.md` §3.3, §3.4,
/// §3.5).
///
/// - The weight leg is live only under [`WeightAuthority::Trusted`].
/// - Under [`WeightAuthority::Lost`] the estimated leg stands in for it.
/// - The volume limit is live when there is no weight target, when the user
///   races both, or while weight is pending or refused. It stays off under
///   `Lost` and [`WeightAuthority::Blind`]: a 36 ml limit would stop a
///   preinfusion-free shot at an empty cup.
/// - Blind with the link gone, or after a disturbance before the cup had
///   weight, a weight target gets the backstop instead. So does a session
///   first seen flowing with no limit and no max time, counted from attach.
#[must_use]
pub fn resolve_legs(intent: StopIntent, ctx: LegContext) -> ResolvedLegs {
    let trusted = ctx.authority == WeightAuthority::Trusted;
    let weight = intent.weight.filter(|_| trusted);
    let estimated = match (intent.weight, ctx.authority) {
        (Some(target_g), WeightAuthority::Lost(last)) => Some(EstimatedLeg { target_g, last }),
        _ => None,
    };
    let limit_live = intent.weight.is_none()
        || intent.volume_stop_with_scale
        || matches!(
            ctx.authority,
            WeightAuthority::Pending | WeightAuthority::Refused(_)
        );
    let limit = intent
        .volume_limit
        .filter(|_| limit_live)
        .map(|l| l + intent.limit_from_ml);
    let disturbed = ctx.refused == Some(Blocked::ScaleDisturbed);
    let backstop = intent.weight.and_then(|target| match ctx.authority {
        WeightAuthority::Blind if ctx.scale_gone || disturbed => {
            Some(espresso_backstop_ml(intent.volume_limit, target))
        }
        WeightAuthority::Refused(Blocked::BeganUnseen)
            if intent.volume_limit.is_none() && intent.max_time.is_none() =>
        {
            Some(espresso_backstop_ml(None, target))
        }
        _ => None,
    });
    let blocked = match (intent.weight, ctx.authority) {
        (Some(_), WeightAuthority::Refused(why)) => Some(why),
        (Some(_), WeightAuthority::Blind) if disturbed => Some(Blocked::ScaleDisturbed),
        // A blip shows nothing (`DESIGN.md` §6); a lost link asks for a hand.
        (Some(_), WeightAuthority::Blind) if ctx.scale_gone => Some(Blocked::ScaleLost),
        (Some(_), WeightAuthority::Lost(_)) => Some(ctx.refused.unwrap_or(Blocked::ScaleLost)),
        _ => None,
    };
    ResolvedLegs {
        weight,
        estimated,
        volume: limit.or(backstop),
        max_time: intent.max_time,
        blocked,
    }
}

/// Whether water dispensed in this machine state counts toward stop-at-volume.
///
/// SAV counts **pour** volume only, as de1app does (`de1_de1.tcl`
/// `update_de1_shotvalue`: an Espresso sample adds to `preinfusion_volume` in
/// the preinfusion substate and to `pour_volume` in the pouring substate, and
/// `sav::check_for_sav` compares `pour_volume` alone). The DE1 reports the
/// profile's first `preinfuse_step_count` frames as the preinfusion substate —
/// for a basic pressure profile that includes the forced-rise frames — so the
/// water that fills the headspace never trips SAV early. Espresso's other
/// substates (heating, stabilising, ending) count toward neither, and an
/// unknown substate counts as flowing. Outside Espresso (de1app also tracks
/// HotWater) every sample counts.
#[must_use]
pub fn sav_counts_volume(state: MachineState, substate: SubState) -> bool {
    match state {
        MachineState::Espresso => matches!(substate, SubState::Pouring | SubState::Unknown),
        _ => true,
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
    /// The scale was lost after flow reached the cup, and the pour reached
    /// the weight target by estimate (`DESIGN.md` §3.5). Never trains the drip
    /// model; a stored shot records it as `Weight` with `stop_estimated`.
    EstimatedWeight,
}

/// Why a session owes a stop.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopCause {
    /// A leg fired.
    Auto(StopReason),
    /// The user tapped Stop.
    User,
}

impl StopCause {
    /// The leg's reason, or `None` for the user's Stop.
    #[must_use]
    pub const fn reason(self) -> Option<StopReason> {
        match self {
            StopCause::Auto(reason) => Some(reason),
            StopCause::User => None,
        }
    }
}

/// Where a session's stop stands (`DESIGN.md` §3.3). An enum with data: the
/// attempts exist only while a stop is owed.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StopState {
    /// Nothing owed: the legs are live.
    Armed,
    /// A stop was sent and the machine hasn't wound down yet.
    Owed {
        /// Why.
        cause: StopCause,
        /// When the last `Stop` went out.
        last_write: Duration,
        /// Retries after the first write.
        attempts: u32,
    },
    /// The machine is winding down.
    Honoured {
        /// The stop it honoured, or `None` if it wound down on its own.
        cause: Option<StopCause>,
    },
}

/// Retries on the clock before a retry needs proof of flow.
pub const STOP_RETRY_CAP: u32 = 30;

/// Retries before [`StopState::Owed`] alerts the user and starts reading the
/// state back.
pub const STOP_ALERT_AFTER: u32 = 5;

/// Samples at or over [`FLOW_PROOF_ML_S`], newer than the last write, that
/// prove water still flows past [`STOP_RETRY_CAP`]. Idle has a brief tail
/// after `Ending`, so one isn't enough.
pub const FLOW_PROOF_SAMPLES: u32 = 2;

/// The group flow that proves water flows, ml/s. Both captures average
/// 0.03 ml/s at Idle and 1.6 ml/s pouring.
pub const FLOW_PROOF_ML_S: f32 = 0.3;

/// The retry interval: 1 s, 2 s in steam, where an extra Idle can end a
/// two-tap steam early.
#[must_use]
pub const fn stop_retry_interval(steam: bool) -> Duration {
    if steam {
        Duration::from_secs(2)
    } else {
        Duration::from_secs(1)
    }
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
    /// The learned-drip snapshot captured at arm time (Decenza's SAW
    /// learning, `weightprocessor.cpp:587-615`). When present, the weight
    /// leg stops at `target − expected_drip(flow)` instead of the fixed
    /// four-term lead projection. `None` = legacy behaviour.
    drip_snapshot: Option<SawSnapshot>,
    /// The connected scale's sensor lag, seconds — the no-model default's
    /// `min(flow · (lag + 0.1), 8)` term.
    sensor_lag_s: f64,
    /// Captured at the weight trigger, for post-shot drip learning — see
    /// [`StopCapture`]. `None` until SAW fires.
    stop_capture: Option<StopCapture>,
    /// The estimated leg, while the scale is lost after flow reached the cup.
    estimate: Option<EstimatedLeg>,
    /// The weight leg's lead at its last live reading, grams: what the
    /// estimate stops short of the target by.
    lead_g: f32,
}

/// The instant the weight leg fired, captured for post-shot drip learning
/// (the training sample's inputs — `drip = final − weight_g`,
/// `overshoot = final − target_g`, kernel flow = `flow_g_per_s`). A named
/// struct rather than a bare `(f32, f32, f32)`: three same-typed floats
/// are one silent field-swap away from corrupting the learned model.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StopCapture {
    /// The robust (estimator) weight when the stop fired, grams.
    pub weight_g: f32,
    /// The estimator mass-flow when the stop fired, g/s.
    pub flow_g_per_s: f32,
    /// The weight target in effect when the stop fired, grams.
    pub target_g: f32,
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
            drip_snapshot: None,
            sensor_lag_s: 0.38,
            stop_capture: None,
            estimate: None,
            lead_g: 0.0,
        }
    }

    /// Set or clear the estimated leg. Like [`set_targets`](Self::set_targets),
    /// re-derived on every input.
    pub fn set_estimate(&mut self, estimate: Option<EstimatedLeg>) {
        self.estimate = estimate;
    }

    /// The estimated leg, if live.
    #[must_use]
    pub fn estimate(&self) -> Option<EstimatedLeg> {
        self.estimate
    }

    /// The weight leg's lead at its last live reading, grams.
    #[must_use]
    pub fn lead_g(&self) -> f32 {
        self.lead_g
    }

    /// Attach a learned-drip snapshot (Decenza SAW learning) — the weight
    /// leg then predicts post-stop drip from past shots instead of the
    /// fixed lead. Also usable mid-shot (a scale registering after arming).
    pub fn set_drip_model(&mut self, snapshot: SawSnapshot, sensor_lag_s: f64) {
        self.drip_snapshot = Some(snapshot);
        self.sensor_lag_s = sensor_lag_s;
    }

    /// The [`StopCapture`] recorded the instant the weight leg fired — the
    /// post-shot drip-learning sample's inputs. `None` until SAW triggers
    /// (volume / max-time stops capture nothing).
    pub fn stop_capture(&self) -> Option<StopCapture> {
        self.stop_capture
    }

    /// Move the clock's start to `now`: a shot whose flow began on an
    /// unknown substate gets its real start once a named flowing one arrives.
    pub fn restart_clock(&mut self, now: Duration) {
        self.started = now;
    }

    /// When the session's clock started: first flow for espresso, the
    /// session's open for hot water.
    #[must_use]
    pub fn started(&self) -> Duration {
        self.started
    }

    /// Whether a stop has already been decided.
    pub fn has_triggered(&self) -> bool {
        self.triggered
    }

    /// Replace the stop targets mid-shot. The flow estimator and the arming
    /// clock keep their state — only the thresholds move. Lets the
    /// orchestrator pick up a target that resolved only after arming (e.g.
    /// SAW when the scale registers a beat after first flow) instead of
    /// keeping the arm-time snapshot for the whole shot.
    pub fn set_targets(&mut self, targets: StopTargets) {
        self.targets = targets;
    }

    /// The currently-armed targets — what will end the shot. Read by the
    /// orchestrator's arming-visibility event (issue #15: silent arming
    /// failures need to be loud in the shells' event logs).
    #[must_use]
    pub fn targets(&self) -> StopTargets {
        self.targets
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
        // How far short of the target the leg stops, kept for the estimate:
        // the drip it expects, or the legacy look-ahead along the flow.
        let crossed = if let Some(snapshot) = &self.drip_snapshot {
            // Learned-drip threshold (Decenza weightprocessor.cpp:283-308):
            // stop when the robust weight reaches `target − expected_drip`,
            // with the kernel's flow input capped at 12 ml/s (:293). The
            // no-model default inside expected_drip reproduces the legacy
            // lead's intent (`min(flow · (sensor_lag + 0.1), 8)`).
            let flow = f64::from(estimate.flow.max(0.0)).min(12.0);
            let expected = SawLearningModel::expected_drip(snapshot, flow, self.sensor_lag_s);
            #[allow(clippy::cast_possible_truncation)]
            {
                self.lead_g = expected as f32;
            }
            self.is_armed(now) && f64::from(estimate.weight) >= f64::from(target) - expected
        } else {
            // Legacy: project the robust weight one look-ahead along the flow.
            let ahead = estimate.flow.max(0.0) * self.config.weight_lead.as_secs_f32();
            self.lead_g = ahead + self.config.weight_offset;
            let projected = estimate.weight + ahead;
            self.is_armed(now) && projected >= target - self.config.weight_offset
        };
        if crossed {
            self.triggered = true;
            self.stop_capture = Some(StopCapture {
                weight_g: estimate.weight,
                flow_g_per_s: estimate.flow,
                target_g: target,
            });
            return Some(StopReason::Weight);
        }
        None
    }

    /// Feed a telemetry sample. The volume integration lives outside
    /// AutoStop now ([`VolumeIntegrator`](crate::VolumeIntegrator) on the
    /// orchestrator) — `pour_ml` carries the running **pour** volume
    /// ([`VolumeIntegrator::pour_ml`](crate::VolumeIntegrator::pour_ml),
    /// preinfusion excluded per [`sav_counts_volume`]), so AutoStop only does
    /// the SAV comparison without re-integrating. Returns
    /// [`StopReason::Volume`] the first time `pour_ml` reaches the SAV target.
    pub fn on_sample(
        &mut self,
        _sample: &ShotSample,
        now: Duration,
        pour_ml: f32,
    ) -> Option<StopReason> {
        if self.triggered {
            return None;
        }

        if let Some(reason) = self.check_max_time(now) {
            return Some(reason);
        }

        if !self.is_armed(now) {
            return None;
        }
        if self.targets.volume.is_some_and(|target| pour_ml >= target) {
            self.triggered = true;
            return Some(StopReason::Volume);
        }
        if self
            .estimate
            .is_some_and(|e| pour_ml >= e.stop_at_ml(self.lead_g))
        {
            self.triggered = true;
            return Some(StopReason::EstimatedWeight);
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
    fn set_targets_swaps_in_a_weight_target_mid_shot() {
        // Armed with max-time only (the always-set leg) — the issue #15
        // shape, where the scale registers after arming.
        let mut stop = AutoStop::new(
            StopTargets {
                weight: None,
                volume: None,
                max_time: Some(45.0),
            },
            immediate_config(),
            Duration::ZERO,
        );
        assert_eq!(stop.on_weight(40.0, ms(1_000)), None);
        stop.set_targets(StopTargets {
            weight: Some(36.0),
            volume: None,
            max_time: Some(45.0),
        });
        assert_eq!(stop.on_weight(40.0, ms(2_000)), Some(StopReason::Weight));
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

    #[test]
    fn sav_counts_espresso_pour_only() {
        // de1app: preinfusion substate -> preinfusion_volume, pouring ->
        // pour_volume; SAV compares pour_volume.
        assert!(!sav_counts_volume(
            MachineState::Espresso,
            SubState::Preinfusion
        ));
        assert!(sav_counts_volume(MachineState::Espresso, SubState::Pouring));
        assert!(
            sav_counts_volume(MachineState::Espresso, SubState::Unknown),
            "an unknown substate counts as flowing"
        );
        // Pre-flow and ending substates count toward neither.
        for sub in [
            SubState::Heating,
            SubState::FinalHeating,
            SubState::Stabilising,
            SubState::Ending,
        ] {
            assert!(!sav_counts_volume(MachineState::Espresso, sub), "{sub:?}");
        }
        // Outside Espresso (hot water) every sample counts.
        assert!(sav_counts_volume(MachineState::HotWater, SubState::Pouring));
    }

    #[test]
    fn sav_compares_the_pour_volume_it_is_given() {
        let targets = StopTargets {
            weight: None,
            volume: Some(36.0),
            max_time: None,
        };
        let mut stop = AutoStop::new(targets, immediate_config(), Duration::ZERO);
        let s = sample_with_flow(4.0);
        // 50 ml dispensed in total, but only 20 ml of it pour: no stop.
        assert_eq!(stop.on_sample(&s, ms(1_000), 20.0), None);
        assert_eq!(
            stop.on_sample(&s, ms(2_000), 36.0),
            Some(StopReason::Volume)
        );
    }

    fn intent(weight: Option<f32>, limit: Option<f32>, max_time: Option<f32>) -> StopIntent {
        StopIntent {
            weight,
            volume_limit: limit,
            max_time,
            volume_stop_with_scale: false,
            limit_from_ml: 0.0,
        }
    }

    fn legs(intent: StopIntent, authority: WeightAuthority, scale_gone: bool) -> ResolvedLegs {
        let refused = match authority {
            WeightAuthority::Refused(why) => Some(why),
            _ => None,
        };
        resolve_legs(
            intent,
            LegContext {
                authority,
                scale_gone,
                refused,
            },
        )
    }

    fn last(weight_g: f32, pour_ml: f32) -> LastTrusted {
        LastTrusted {
            weight_g,
            pour_ml,
            at: Duration::ZERO,
        }
    }

    /// §3.5: under `Lost` the estimate stands in for weight, and a limit
    /// stays down unless the user races both.
    #[test]
    fn lost_weight_is_estimated_and_keeps_the_limit_down() {
        let lost = WeightAuthority::Lost(last(20.0, 40.0));
        let i = intent(Some(36.0), Some(36.0), None);
        let l = legs(i, lost, false);
        assert_eq!(l.weight, None);
        assert_eq!(
            l.estimated,
            Some(EstimatedLeg {
                target_g: 36.0,
                last: last(20.0, 40.0)
            })
        );
        assert_eq!(l.volume, None, "no limit under Lost");
        assert_eq!(l.blocked, Some(Blocked::ScaleLost));
        assert_eq!(l.note(), Some("Estimating, scale stopped reporting"));
        let racing = StopIntent {
            volume_stop_with_scale: true,
            ..i
        };
        assert_eq!(legs(racing, lost, false).volume, Some(36.0));
        // A re-zeroed return keeps estimating, under its own reason.
        let rezeroed = resolve_legs(
            i,
            LegContext {
                authority: lost,
                scale_gone: false,
                refused: Some(Blocked::ScaleRezeroed),
            },
        );
        assert_eq!(rezeroed.note(), Some("Estimating, scale re-zeroed"));
        // No target, nothing to estimate.
        assert_eq!(legs(intent(None, None, None), lost, false).estimated, None);
    }

    /// §3.5: disturbed before the gate is `Blind`, and gets the backstop at
    /// once, not on a blip.
    #[test]
    fn a_disturbance_before_the_gate_gets_the_backstop() {
        let l = resolve_legs(
            intent(Some(36.0), None, None),
            LegContext {
                authority: WeightAuthority::Blind,
                scale_gone: false,
                refused: Some(Blocked::ScaleDisturbed),
            },
        );
        assert_eq!(l.volume, Some(114.0));
        assert_eq!(l.blocked, Some(Blocked::ScaleDisturbed));
        assert_eq!(l.note(), Some("Scale disturbed. Stop this shot by hand."));
    }

    /// The estimate fires at `last.pour + (target − lead − last.weight)`,
    /// with the lead the weight leg had at its last live reading, as
    /// `EstimatedWeight`, and records no drip-learning capture.
    #[test]
    fn the_estimate_finishes_the_remainder_short_by_the_lead() {
        let config = StopConfig {
            weight_lead: Duration::from_secs(1),
            arming_delay: Duration::ZERO,
            ..StopConfig::default()
        };
        let weight = StopTargets {
            weight: Some(36.0),
            volume: None,
            max_time: None,
        };
        let mut stop = AutoStop::new(weight, config, Duration::ZERO);
        // 2 g/s into the cup, up to 20 g.
        for i in 0..=100u64 {
            #[allow(clippy::cast_precision_loss)]
            let g = i as f32 * 0.2;
            assert_eq!(stop.on_weight(g, ms(i * 100)), None);
        }
        let lead = stop.lead_g();
        assert!((1.5..2.5).contains(&lead), "lead {lead} g");
        // The scale is lost: weight off, the estimate on.
        stop.set_targets(StopTargets {
            weight: None,
            ..weight
        });
        stop.set_estimate(Some(EstimatedLeg {
            target_g: 36.0,
            last: last(20.0, 40.0),
        }));
        let at = 40.0 + (36.0 - lead - 20.0);
        let sample = sample_with_flow(2.0);
        assert_eq!(stop.on_sample(&sample, ms(11_000), at - 0.1), None);
        assert_eq!(
            stop.on_sample(&sample, ms(11_100), at),
            Some(StopReason::EstimatedWeight)
        );
        assert_eq!(stop.stop_capture(), None, "never trains the drip model");
    }

    #[test]
    fn weight_is_live_only_when_trusted() {
        use WeightAuthority::{Blind, Pending, Refused, Trusted};
        let i = intent(Some(36.0), None, None);
        assert_eq!(legs(i, Trusted, false).weight, Some(36.0));
        for authority in [Pending, Blind, Refused(Blocked::NoScale)] {
            assert_eq!(legs(i, authority, false).weight, None, "{authority:?}");
        }
    }

    #[test]
    fn the_volume_limit_stands_down_behind_trusted_or_blind_weight() {
        use WeightAuthority::{Blind, Pending, Refused, Trusted};
        let i = intent(Some(36.0), Some(36.0), None);
        assert_eq!(legs(i, Trusted, false).volume, None);
        assert_eq!(legs(i, Blind, false).volume, None, "a blip never rearms it");
        assert_eq!(legs(i, Pending, false).volume, Some(36.0));
        assert_eq!(
            legs(i, Refused(Blocked::ScaleSilent), false).volume,
            Some(36.0)
        );
        // No weight target, or the user races both: the limit is live.
        assert_eq!(
            legs(intent(None, Some(36.0), None), Trusted, false).volume,
            Some(36.0)
        );
        let racing = StopIntent {
            volume_stop_with_scale: true,
            ..i
        };
        assert_eq!(legs(racing, Trusted, false).volume, Some(36.0));
    }

    #[test]
    fn blind_with_the_link_gone_stops_at_the_backstop() {
        let i = intent(Some(36.0), Some(36.0), None);
        assert_eq!(legs(i, WeightAuthority::Blind, true).volume, Some(114.0));
        assert_eq!(
            legs(i, WeightAuthority::Blind, true).blocked,
            Some(Blocked::ScaleLost)
        );
        // A blip: no backstop and no note.
        assert_eq!(legs(i, WeightAuthority::Blind, false).volume, None);
        assert_eq!(legs(i, WeightAuthority::Blind, false).blocked, None);
        // A limit over the backstop stands.
        let high = intent(Some(36.0), Some(150.0), None);
        assert_eq!(legs(high, WeightAuthority::Blind, true).volume, Some(150.0));
    }

    #[test]
    fn an_unseen_session_with_no_limits_gets_the_backstop() {
        let unseen = WeightAuthority::Refused(Blocked::BeganUnseen);
        assert_eq!(
            legs(intent(Some(36.0), None, None), unseen, false).volume,
            Some(114.0)
        );
        assert_eq!(
            legs(intent(Some(36.0), Some(40.0), None), unseen, false).volume,
            Some(40.0)
        );
        assert_eq!(
            legs(intent(Some(36.0), None, Some(30.0)), unseen, false).volume,
            None
        );
        assert_eq!(legs(intent(None, None, None), unseen, false).volume, None);
    }

    #[test]
    fn a_refusal_is_the_blocked_reason() {
        let l = legs(
            intent(Some(36.0), None, None),
            WeightAuthority::Refused(Blocked::UntaredCup),
            false,
        );
        assert_eq!(l.blocked, Some(Blocked::UntaredCup));
        assert_eq!(l.targets(), None);
    }
}

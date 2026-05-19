//! The espresso shot state machine — observing a shot and recording it.
//!
//! [`ShotMonitor`] is sans-IO: feed it the DE1's [`StateInfo`] and [`ShotSample`]
//! notifications (each with a monotonic timestamp from the shell) and it tracks
//! the shot's [`ShotPhase`], accumulates a [`ShotRecord`], and returns the
//! notable [`ShotEvent`]s. SAW / SAV (stop-at-weight / stop-at-volume) logic
//! will build on this.

// `u64` millisecond elapsed times are divided into `f32` seconds for the
// recorded shot metrics; that time-to-float conversion loses precision past
// the f32 mantissa — harmless for a shot's span — so the precision-loss lint
// is allowed module-wide here.
#![allow(clippy::cast_precision_loss)]

use std::time::Duration;

use de1_protocol::{MachineState, ShotSample, StateInfo, SubState};
use typeshare::typeshare;

use crate::session::SessionTimer;

/// Hard cap on the number of telemetry samples retained for one shot.
///
/// A shot ends when the DE1 leaves the `Espresso` state — an untrusted signal
/// from the peer — so without a cap a buggy or hostile DE1 could stream
/// samples indefinitely and grow the heap without bound. The cap is sized far
/// beyond any real session: ~1 hour of telemetry at 10 Hz. A real espresso
/// shot is at most a few minutes, so a genuine shot is never truncated; once
/// the cap is reached further samples are simply dropped (no panic, no
/// reallocation).
pub const MAX_SHOT_SAMPLES: usize = 36_000;

/// Where an espresso shot is in its lifecycle.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, serde::Serialize, serde::Deserialize)]
pub enum ShotPhase {
    /// No espresso shot in progress.
    #[default]
    Idle,
    /// Heating and stabilising, before flow begins.
    Heating,
    /// Preinfusion — wetting the puck at low pressure or flow.
    Preinfusion,
    /// The main pour.
    Pouring,
    /// Ending — flow has stopped and the group is draining.
    Ending,
}

impl ShotPhase {
    /// Classify the shot phase from a DE1 state / substate pair. Any machine
    /// state other than `Espresso` is [`ShotPhase::Idle`] for shot tracking.
    pub fn classify(state: MachineState, substate: SubState) -> ShotPhase {
        if state != MachineState::Espresso {
            return ShotPhase::Idle;
        }
        match substate {
            SubState::Heating | SubState::FinalHeating | SubState::Stabilising => {
                ShotPhase::Heating
            }
            SubState::Preinfusion => ShotPhase::Preinfusion,
            SubState::Pouring => ShotPhase::Pouring,
            SubState::Ending => ShotPhase::Ending,
            _ => ShotPhase::Idle,
        }
    }
}

/// A telemetry sample tagged with its time since the shot began.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct TimedSample {
    /// Milliseconds since the shot started.
    pub elapsed_ms: u64,
    /// The telemetry sample.
    pub sample: ShotSample,
}

/// A completed espresso shot — its duration and full telemetry series.
///
/// The samples span the whole `Espresso` machine state, including the heating
/// phase before flow; consumers can use the [`ShotEvent::PhaseChanged`] stream
/// to locate flow-start.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct ShotRecord {
    /// Total shot duration, milliseconds.
    pub duration_ms: u64,
    /// Every telemetry sample collected during the shot, in order.
    pub samples: Vec<TimedSample>,
}

/// Summary metrics derived from a [`ShotRecord`]'s recorded telemetry.
///
/// These are computed from the sample series alone — no scale or barista
/// input — so they describe what the machine measured during the shot.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct ShotMetrics {
    /// Highest group pressure observed, bar.
    pub peak_pressure_bar: f32,
    /// Highest group flow observed, mL/s.
    pub peak_flow_ml_per_s: f32,
    /// Total water dispensed, mL — the group flow integrated over time.
    pub total_water_ml: f32,
    /// Shot duration, seconds (from [`ShotRecord::duration_ms`]).
    pub duration_s: f32,
}

impl ShotRecord {
    /// Compute summary [`ShotMetrics`] from the recorded telemetry.
    ///
    /// Peak pressure and flow are the maxima over all samples; total water is
    /// the trapezoidal integral of group flow (mL/s) over the elapsed time of
    /// the sample series. A record with no samples yields all-zero metrics
    /// except [`duration_s`](ShotMetrics::duration_s).
    pub fn metrics(&self) -> ShotMetrics {
        let mut peak_pressure_bar = 0.0f32;
        let mut peak_flow_ml_per_s = 0.0f32;
        let mut total_water_ml = 0.0f32;
        let mut prev: Option<&TimedSample> = None;

        for timed in &self.samples {
            peak_pressure_bar = peak_pressure_bar.max(timed.sample.group_pressure);
            peak_flow_ml_per_s = peak_flow_ml_per_s.max(timed.sample.group_flow);
            if let Some(prev) = prev
                && timed.elapsed_ms > prev.elapsed_ms
            {
                // Trapezoidal rule: average flow over the interval × duration.
                let dt_s = (timed.elapsed_ms - prev.elapsed_ms) as f32 / 1000.0;
                let avg_flow = (prev.sample.group_flow + timed.sample.group_flow) / 2.0;
                total_water_ml += avg_flow * dt_s;
            }
            prev = Some(timed);
        }

        ShotMetrics {
            peak_pressure_bar,
            peak_flow_ml_per_s,
            total_water_ml,
            duration_s: self.duration_ms as f32 / 1000.0,
        }
    }
}

/// A notable change observed by a [`ShotMonitor`].
#[derive(Debug, Clone, PartialEq)]
pub enum ShotEvent {
    /// An espresso shot has begun (the machine entered the `Espresso` state).
    Started,
    /// The shot has moved to a new phase.
    PhaseChanged(ShotPhase),
    /// The DE1 has advanced to a new profile frame.
    FrameChanged(u8),
    /// The shot has finished; carries the completed record.
    Completed(ShotRecord),
}

/// In-progress shot accumulation. The session timing lives in a shared
/// [`SessionTimer`]; this holds only what is shot-specific.
#[derive(Debug, Default)]
struct ShotInProgress {
    timer: SessionTimer,
    last_frame: Option<u8>,
    samples: Vec<TimedSample>,
}

/// Observes the DE1's notification stream and tracks one espresso shot at a time.
///
/// Sans-IO: it holds no clock and performs no I/O. The shell supplies a
/// monotonic `now_ms` with each notification.
#[derive(Debug, Default)]
pub struct ShotMonitor {
    phase: ShotPhase,
    shot: Option<ShotInProgress>,
}

impl ShotMonitor {
    /// Create a monitor in the idle state.
    pub fn new() -> ShotMonitor {
        ShotMonitor::default()
    }

    /// The current shot phase.
    pub fn phase(&self) -> ShotPhase {
        self.phase
    }

    /// Whether an espresso shot is currently being recorded.
    pub fn is_shot_in_progress(&self) -> bool {
        self.shot.is_some()
    }

    /// Feed a [`StateInfo`] notification. `now` is a monotonic timestamp
    /// supplied by the shell (a [`Duration`] from a shell-chosen epoch).
    ///
    /// Returns the events this update produced — empty if nothing notable
    /// changed.
    pub fn on_state_info(&mut self, info: StateInfo, now: Duration) -> Vec<ShotEvent> {
        let mut events = Vec::new();
        let in_espresso = info.state == MachineState::Espresso;

        // A shot starts when the machine enters the Espresso state.
        if in_espresso && self.shot.is_none() {
            let mut shot = ShotInProgress::default();
            shot.timer.start(now);
            self.shot = Some(shot);
            events.push(ShotEvent::Started);
        }

        let new_phase = ShotPhase::classify(info.state, info.substate);
        if new_phase != self.phase {
            self.phase = new_phase;
            events.push(ShotEvent::PhaseChanged(new_phase));
        }

        // A shot ends when the machine leaves the Espresso state.
        if !in_espresso && let Some(mut shot) = self.shot.take() {
            // `duration_ms` is the on-disk JSON shape: convert the elapsed
            // `Duration` back to `u64` ms, saturating in the (impossible)
            // case of a >584-million-year session.
            let duration = shot.timer.finish(now).unwrap_or(Duration::ZERO);
            events.push(ShotEvent::Completed(ShotRecord {
                duration_ms: u64::try_from(duration.as_millis()).unwrap_or(u64::MAX),
                samples: shot.samples,
            }));
        }
        events
    }

    /// Feed a [`ShotSample`] telemetry notification. Samples are recorded only
    /// while a shot is in progress.
    ///
    /// Returns the events this sample produced — empty if nothing notable.
    pub fn on_sample(&mut self, sample: &ShotSample, now: Duration) -> Vec<ShotEvent> {
        let mut events = Vec::new();
        if let Some(shot) = &mut self.shot {
            if shot.last_frame != Some(sample.frame_number) {
                shot.last_frame = Some(sample.frame_number);
                events.push(ShotEvent::FrameChanged(sample.frame_number));
            }
            // Drop samples past the cap so an over-long stream cannot grow the
            // heap without bound; see [`MAX_SHOT_SAMPLES`].
            if shot.samples.len() < MAX_SHOT_SAMPLES {
                // `elapsed_ms` is part of the persisted JSON schema — convert
                // the `Duration` back to `u64` ms for the stored sample.
                let elapsed_ms = u64::try_from(shot.timer.elapsed(now).as_millis())
                    .unwrap_or(u64::MAX);
                shot.samples.push(TimedSample {
                    elapsed_ms,
                    sample: sample.clone(),
                });
            }
        }
        events
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: a `Duration` of `ms` milliseconds — the unit every test below
    /// thinks in.
    fn ms(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    fn state(state: MachineState, substate: SubState) -> StateInfo {
        StateInfo { state, substate }
    }

    /// A telemetry sample carrying the given frame number; other fields zeroed.
    fn sample(frame_number: u8) -> ShotSample {
        ShotSample {
            sample_time: 0,
            group_pressure: 0.0,
            group_flow: 0.0,
            head_temp: 0.0,
            mix_temp: 0.0,
            set_mix_temp: 0.0,
            set_head_temp: 0.0,
            set_group_pressure: 0.0,
            set_group_flow: 0.0,
            frame_number,
            steam_temp: 0.0,
        }
    }

    /// Extract the `ShotRecord` from a `Completed` event in `events`.
    fn completed_record(events: Vec<ShotEvent>) -> ShotRecord {
        events
            .into_iter()
            .find_map(|event| match event {
                ShotEvent::Completed(record) => Some(record),
                _ => None,
            })
            .expect("a Completed event")
    }

    #[test]
    fn classifies_the_phase_from_state_and_substate() {
        assert_eq!(
            ShotPhase::classify(MachineState::Espresso, SubState::Pouring),
            ShotPhase::Pouring
        );
        assert_eq!(
            ShotPhase::classify(MachineState::Espresso, SubState::FinalHeating),
            ShotPhase::Heating
        );
    }

    #[test]
    fn a_non_espresso_state_classifies_as_idle() {
        assert_eq!(
            ShotPhase::classify(MachineState::Steam, SubState::Steaming),
            ShotPhase::Idle
        );
    }

    #[test]
    fn entering_espresso_starts_a_shot() {
        let mut monitor = ShotMonitor::new();
        let events = monitor.on_state_info(state(MachineState::Espresso, SubState::Heating), ms(1000));
        assert!(events.contains(&ShotEvent::Started));
        assert!(monitor.is_shot_in_progress());
        assert_eq!(monitor.phase(), ShotPhase::Heating);
    }

    #[test]
    fn phase_transitions_are_reported() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(state(MachineState::Espresso, SubState::Preinfusion), ms(1000));
        let events = monitor.on_state_info(state(MachineState::Espresso, SubState::Pouring), ms(2000));
        assert_eq!(events, vec![ShotEvent::PhaseChanged(ShotPhase::Pouring)]);
    }

    #[test]
    fn samples_are_not_recorded_before_a_shot() {
        let mut monitor = ShotMonitor::new();
        let events = monitor.on_sample(&sample(0), ms(500));
        assert!(events.is_empty());
        assert!(!monitor.is_shot_in_progress());
    }

    #[test]
    fn a_new_frame_number_reports_a_frame_change() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(state(MachineState::Espresso, SubState::Pouring), ms(1000));
        monitor.on_sample(&sample(0), ms(1100));
        let events = monitor.on_sample(&sample(1), ms(1200));
        assert_eq!(events, vec![ShotEvent::FrameChanged(1)]);
    }

    #[test]
    fn a_full_shot_yields_a_record_with_samples_and_duration() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(state(MachineState::Espresso, SubState::Preinfusion), ms(1000));
        monitor.on_sample(&sample(0), ms(1200));
        monitor.on_sample(&sample(0), ms(3000));
        let done = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(5000));

        let record = completed_record(done);
        assert_eq!(record.duration_ms, 4000); // 5000 - 1000
        assert_eq!(record.samples.len(), 2);
        assert_eq!(record.samples[0].elapsed_ms, 200); // 1200 - 1000
        assert_eq!(record.samples[1].elapsed_ms, 2000); // 3000 - 1000
        assert!(!monitor.is_shot_in_progress());
    }

    /// A `TimedSample` at `elapsed_ms` carrying the given pressure and flow.
    fn timed(elapsed_ms: u64, group_pressure: f32, group_flow: f32) -> TimedSample {
        let mut sample = sample(0);
        sample.group_pressure = group_pressure;
        sample.group_flow = group_flow;
        TimedSample { elapsed_ms, sample }
    }

    #[test]
    fn metrics_report_peaks_water_and_duration() {
        let record = ShotRecord {
            duration_ms: 30_000,
            samples: vec![
                timed(0, 1.0, 0.0),
                timed(1_000, 9.0, 2.0),
                timed(2_000, 6.0, 2.0),
            ],
        };
        let metrics = record.metrics();
        assert_eq!(metrics.peak_pressure_bar, 9.0);
        assert_eq!(metrics.peak_flow_ml_per_s, 2.0);
        // Trapezoidal: 0..1 s avg 1.0 -> 1.0 mL; 1..2 s avg 2.0 -> 2.0 mL.
        assert_eq!(metrics.total_water_ml, 3.0);
        assert_eq!(metrics.duration_s, 30.0);
    }

    #[test]
    fn the_sample_buffer_is_capped() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(state(MachineState::Espresso, SubState::Pouring), ms(0));
        // Feed well past the cap: the excess samples are dropped, not retained.
        for _ in 0..MAX_SHOT_SAMPLES + 100 {
            monitor.on_sample(&sample(0), ms(1_000));
        }
        let done = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(2_000));
        let record = completed_record(done);
        assert_eq!(record.samples.len(), MAX_SHOT_SAMPLES);
    }

    #[test]
    fn metrics_of_an_empty_record_are_zero_except_duration() {
        let record = ShotRecord {
            duration_ms: 5_000,
            samples: vec![],
        };
        let metrics = record.metrics();
        assert_eq!(metrics.peak_pressure_bar, 0.0);
        assert_eq!(metrics.peak_flow_ml_per_s, 0.0);
        assert_eq!(metrics.total_water_ml, 0.0);
        assert_eq!(metrics.duration_s, 5.0);
    }
}

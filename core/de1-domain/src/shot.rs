//! The espresso shot state machine — observing a shot and recording it.
//!
//! [`ShotMonitor`] is sans-IO: feed it the DE1's [`StateInfo`] and [`ShotSample`]
//! notifications (each with a monotonic timestamp from the shell) and it tracks
//! the shot's [`ShotPhase`], accumulates a [`ShotRecord`], and returns the
//! notable [`ShotEvent`]s. SAW / SAV (stop-at-weight / stop-at-volume) logic
//! will build on this.

use de1_protocol::{MachineState, ShotSample, StateInfo, SubState};

/// Where an espresso shot is in its lifecycle.
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

/// In-progress shot accumulation.
#[derive(Debug)]
struct ShotInProgress {
    started_ms: u64,
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

    /// Feed a [`StateInfo`] notification. `now_ms` is a monotonic millisecond
    /// timestamp supplied by the shell.
    ///
    /// Returns the events this update produced — empty if nothing notable
    /// changed.
    pub fn on_state_info(&mut self, info: StateInfo, now_ms: u64) -> Vec<ShotEvent> {
        let mut events = Vec::new();
        let in_espresso = info.state == MachineState::Espresso;

        // A shot starts when the machine enters the Espresso state.
        if in_espresso && self.shot.is_none() {
            self.shot = Some(ShotInProgress {
                started_ms: now_ms,
                last_frame: None,
                samples: Vec::new(),
            });
            events.push(ShotEvent::Started);
        }

        let new_phase = ShotPhase::classify(info.state, info.substate);
        if new_phase != self.phase {
            self.phase = new_phase;
            events.push(ShotEvent::PhaseChanged(new_phase));
        }

        // A shot ends when the machine leaves the Espresso state.
        if !in_espresso {
            if let Some(shot) = self.shot.take() {
                events.push(ShotEvent::Completed(ShotRecord {
                    duration_ms: now_ms.saturating_sub(shot.started_ms),
                    samples: shot.samples,
                }));
            }
        }
        events
    }

    /// Feed a [`ShotSample`] telemetry notification. Samples are recorded only
    /// while a shot is in progress.
    ///
    /// Returns the events this sample produced — empty if nothing notable.
    pub fn on_sample(&mut self, sample: &ShotSample, now_ms: u64) -> Vec<ShotEvent> {
        let mut events = Vec::new();
        if let Some(shot) = &mut self.shot {
            if shot.last_frame != Some(sample.frame_number) {
                shot.last_frame = Some(sample.frame_number);
                events.push(ShotEvent::FrameChanged(sample.frame_number));
            }
            shot.samples.push(TimedSample {
                elapsed_ms: now_ms.saturating_sub(shot.started_ms),
                sample: sample.clone(),
            });
        }
        events
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
        let events = monitor.on_state_info(state(MachineState::Espresso, SubState::Heating), 1000);
        assert!(events.contains(&ShotEvent::Started));
        assert!(monitor.is_shot_in_progress());
        assert_eq!(monitor.phase(), ShotPhase::Heating);
    }

    #[test]
    fn phase_transitions_are_reported() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(state(MachineState::Espresso, SubState::Preinfusion), 1000);
        let events = monitor.on_state_info(state(MachineState::Espresso, SubState::Pouring), 2000);
        assert_eq!(events, vec![ShotEvent::PhaseChanged(ShotPhase::Pouring)]);
    }

    #[test]
    fn samples_are_not_recorded_before_a_shot() {
        let mut monitor = ShotMonitor::new();
        let events = monitor.on_sample(&sample(0), 500);
        assert!(events.is_empty());
        assert!(!monitor.is_shot_in_progress());
    }

    #[test]
    fn a_new_frame_number_reports_a_frame_change() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(state(MachineState::Espresso, SubState::Pouring), 1000);
        monitor.on_sample(&sample(0), 1100);
        let events = monitor.on_sample(&sample(1), 1200);
        assert_eq!(events, vec![ShotEvent::FrameChanged(1)]);
    }

    #[test]
    fn a_full_shot_yields_a_record_with_samples_and_duration() {
        let mut monitor = ShotMonitor::new();
        monitor.on_state_info(state(MachineState::Espresso, SubState::Preinfusion), 1000);
        monitor.on_sample(&sample(0), 1200);
        monitor.on_sample(&sample(0), 3000);
        let done = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), 5000);

        let record = completed_record(done);
        assert_eq!(record.duration_ms, 4000); // 5000 - 1000
        assert_eq!(record.samples.len(), 2);
        assert_eq!(record.samples[0].elapsed_ms, 200); // 1200 - 1000
        assert_eq!(record.samples[1].elapsed_ms, 2000); // 3000 - 1000
        assert!(!monitor.is_shot_in_progress());
    }
}

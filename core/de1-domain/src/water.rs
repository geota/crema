//! The hot-water and flush session state machine.
//!
//! [`WaterMonitor`] is the sibling of [`ShotMonitor`](crate::ShotMonitor): where
//! `ShotMonitor` tracks the DE1's `Espresso` state, `WaterMonitor` tracks the
//! `HotWater` and `HotWaterRinse` (flush) states. It is sans-IO — feed it the
//! DE1's [`StateInfo`] notifications (each with a monotonic timestamp from the
//! shell) and it reports when a hot-water or flush session starts and finishes,
//! carrying the session's duration.
//!
//! Hot water and flush carry no profile and no per-frame telemetry, so unlike
//! [`ShotMonitor`] there is no sample series — only the session boundaries and
//! duration, mirroring the legacy app's `start_hot_water` / `start_flush`
//! (`machine.tcl`), which simply time the operation.

use de1_protocol::{MachineState, StateInfo};
use typeshare::typeshare;

use crate::session::SessionTimer;

/// Which kind of water-dispensing session the DE1 is running.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum WaterSessionKind {
    /// A hot-water pour (DE1 `HotWater` state).
    HotWater,
    /// A group-head flush / rinse (DE1 `HotWaterRinse` state).
    Flush,
}

impl WaterSessionKind {
    /// Classify the water-session kind from a DE1 machine state, if any. Any
    /// state other than `HotWater` or `HotWaterRinse` yields `None`.
    pub fn classify(state: MachineState) -> Option<WaterSessionKind> {
        match state {
            MachineState::HotWater => Some(WaterSessionKind::HotWater),
            MachineState::HotWaterRinse => Some(WaterSessionKind::Flush),
            _ => None,
        }
    }
}

/// A completed hot-water or flush session — its kind and total duration.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct WaterRecord {
    /// Whether this was a hot-water pour or a flush.
    pub kind: WaterSessionKind,
    /// Total session duration, milliseconds.
    pub duration_ms: u64,
}

/// A notable change observed by a [`WaterMonitor`].
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum WaterEvent {
    /// A hot-water or flush session has begun.
    Started(WaterSessionKind),
    /// The session has finished; carries the completed record.
    Completed(WaterRecord),
}

/// In-progress hot-water / flush session accumulation. The session timing
/// lives in a shared [`SessionTimer`]; this holds only the session kind.
#[derive(Debug)]
struct WaterInProgress {
    kind: WaterSessionKind,
    timer: SessionTimer,
}

/// Observes the DE1's notification stream and tracks one hot-water or flush
/// session at a time.
///
/// Sans-IO: it holds no clock and performs no I/O. The shell supplies a
/// monotonic `now_ms` with each notification.
#[derive(Debug, Default)]
pub struct WaterMonitor {
    session: Option<WaterInProgress>,
}

impl WaterMonitor {
    /// Create a monitor in the idle state.
    pub fn new() -> WaterMonitor {
        WaterMonitor::default()
    }

    /// Whether a hot-water or flush session is currently in progress.
    pub fn is_session_in_progress(&self) -> bool {
        self.session.is_some()
    }

    /// The kind of session in progress, if any.
    pub fn session_kind(&self) -> Option<WaterSessionKind> {
        self.session.as_ref().map(|s| s.kind)
    }

    /// Feed a [`StateInfo`] notification. `now_ms` is a monotonic millisecond
    /// timestamp supplied by the shell.
    ///
    /// Returns the events this update produced — empty if nothing notable
    /// changed. A session ends not only when the DE1 leaves the water states
    /// but also when it switches directly between `HotWater` and
    /// `HotWaterRinse`: the first session completes and the second begins.
    pub fn on_state_info(&mut self, info: StateInfo, now_ms: u64) -> Vec<WaterEvent> {
        let mut events = Vec::new();
        let new_kind = WaterSessionKind::classify(info.state);

        // An in-progress session ends when the machine leaves its state — by
        // going idle, or by switching to the other water state.
        if let Some(session) = &self.session
            && new_kind != Some(session.kind)
        {
            let mut session = self.session.take().expect("checked Some above");
            events.push(WaterEvent::Completed(WaterRecord {
                kind: session.kind,
                duration_ms: session.timer.finish(now_ms).unwrap_or(0),
            }));
        }

        // A session starts when the machine enters a water state with none
        // already tracked.
        if let Some(kind) = new_kind
            && self.session.is_none()
        {
            let mut timer = SessionTimer::new();
            timer.start(now_ms);
            self.session = Some(WaterInProgress { kind, timer });
            events.push(WaterEvent::Started(kind));
        }
        events
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use de1_protocol::SubState;

    fn state(state: MachineState, substate: SubState) -> StateInfo {
        StateInfo { state, substate }
    }

    /// Extract the `WaterRecord` from a `Completed` event in `events`.
    fn completed_record(events: Vec<WaterEvent>) -> WaterRecord {
        events
            .into_iter()
            .find_map(|event| match event {
                WaterEvent::Completed(record) => Some(record),
                WaterEvent::Started(_) => None,
            })
            .expect("a Completed event")
    }

    #[test]
    fn classifies_the_session_kind_from_state() {
        assert_eq!(
            WaterSessionKind::classify(MachineState::HotWater),
            Some(WaterSessionKind::HotWater)
        );
        assert_eq!(
            WaterSessionKind::classify(MachineState::HotWaterRinse),
            Some(WaterSessionKind::Flush)
        );
        assert_eq!(WaterSessionKind::classify(MachineState::Espresso), None);
    }

    #[test]
    fn entering_hot_water_starts_a_session() {
        let mut monitor = WaterMonitor::new();
        let events = monitor.on_state_info(state(MachineState::HotWater, SubState::Pouring), 1_000);
        assert_eq!(
            events,
            vec![WaterEvent::Started(WaterSessionKind::HotWater)]
        );
        assert!(monitor.is_session_in_progress());
        assert_eq!(monitor.session_kind(), Some(WaterSessionKind::HotWater));
    }

    #[test]
    fn entering_hot_water_rinse_starts_a_flush_session() {
        let mut monitor = WaterMonitor::new();
        let events =
            monitor.on_state_info(state(MachineState::HotWaterRinse, SubState::Pouring), 500);
        assert_eq!(events, vec![WaterEvent::Started(WaterSessionKind::Flush)]);
        assert_eq!(monitor.session_kind(), Some(WaterSessionKind::Flush));
    }

    #[test]
    fn a_full_hot_water_session_yields_a_record_with_duration() {
        let mut monitor = WaterMonitor::new();
        monitor.on_state_info(state(MachineState::HotWater, SubState::Pouring), 1_000);
        let done = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), 9_500);
        let record = completed_record(done);
        assert_eq!(record.kind, WaterSessionKind::HotWater);
        assert_eq!(record.duration_ms, 8_500); // 9500 - 1000
        assert!(!monitor.is_session_in_progress());
    }

    #[test]
    fn a_repeated_state_does_not_re_start_the_session() {
        let mut monitor = WaterMonitor::new();
        monitor.on_state_info(state(MachineState::HotWater, SubState::Pouring), 1_000);
        let events = monitor.on_state_info(state(MachineState::HotWater, SubState::Pouring), 2_000);
        assert!(events.is_empty());
        assert!(monitor.is_session_in_progress());
    }

    #[test]
    fn switching_directly_between_water_states_completes_then_starts() {
        let mut monitor = WaterMonitor::new();
        monitor.on_state_info(state(MachineState::HotWater, SubState::Pouring), 1_000);
        let events =
            monitor.on_state_info(state(MachineState::HotWaterRinse, SubState::Pouring), 4_000);
        assert_eq!(
            events,
            vec![
                WaterEvent::Completed(WaterRecord {
                    kind: WaterSessionKind::HotWater,
                    duration_ms: 3_000,
                }),
                WaterEvent::Started(WaterSessionKind::Flush),
            ]
        );
        assert_eq!(monitor.session_kind(), Some(WaterSessionKind::Flush));
    }

    #[test]
    fn a_non_water_state_with_no_session_does_nothing() {
        let mut monitor = WaterMonitor::new();
        let events = monitor.on_state_info(state(MachineState::Espresso, SubState::Pouring), 1_000);
        assert!(events.is_empty());
        assert!(!monitor.is_session_in_progress());
    }
}

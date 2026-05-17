//! # de1-app
//!
//! The headless Crema application core — the FFI-agnostic facade the bridge
//! crates wrap (`de1-ffi` for Android via UniFFI, `de1-wasm` for the web via
//! wasm-bindgen).
//!
//! [`CremaCore`] decodes the DE1's BLE notifications, drives the
//! [`ShotMonitor`](de1_domain::ShotMonitor) state machine, and reports
//! [`Event`]s in a [`CoreOutput`] envelope. It is sans-IO and holds no clock —
//! every method takes a monotonic `now_ms` from the shell.
//!
//! See `docs/08-ffi-and-web-scope.md` for the bridge design. This is cut 1:
//! observation of a DE1 shot. Scale weight, auto-stop, and machine-driving
//! commands arrive in later increments.

pub mod error;
pub mod event;

pub use error::AppError;
pub use event::{CoreOutput, Event, Source};

use de1_domain::{ShotEvent, ShotMonitor};
use de1_protocol::{ShotSample, StateInfo};

/// The headless Crema application core.
///
/// One `CremaCore` tracks one machine session. The FFI bridges wrap it behind
/// a mutex; the type itself uses ordinary `&mut self` methods so it stays
/// plain, testable Rust.
#[derive(Debug, Default)]
pub struct CremaCore {
    monitor: ShotMonitor,
    last_state: Option<StateInfo>,
    /// Timestamp the in-progress shot began — used to stamp telemetry elapsed
    /// time. `None` when no shot is in progress.
    shot_started_ms: Option<u64>,
}

impl CremaCore {
    /// Create a core in the idle state.
    pub fn new() -> CremaCore {
        CremaCore::default()
    }

    /// Feed a raw GATT notification: `data` is the characteristic's bytes and
    /// `now_ms` a monotonic millisecond timestamp from the shell.
    pub fn on_notification(&mut self, source: Source, data: &[u8], now_ms: u64) -> CoreOutput {
        let mut events = Vec::new();
        match source {
            Source::De1State => self.handle_state(data, now_ms, &mut events),
            Source::De1ShotSample => self.handle_sample(data, now_ms, &mut events),
        }
        CoreOutput { events }
    }

    /// Feed a periodic clock tick. Reserved for time-driven logic such as the
    /// stop-at-weight lead-time countdown; currently a no-op.
    pub fn on_tick(&mut self, _now_ms: u64) -> CoreOutput {
        CoreOutput::default()
    }

    /// Discard all session state — e.g. on disconnect.
    pub fn reset(&mut self) {
        *self = CremaCore::new();
    }

    /// Decode and process a `StateInfo` notification.
    fn handle_state(&mut self, data: &[u8], now_ms: u64, events: &mut Vec<Event>) {
        let info = match StateInfo::parse(data) {
            Ok(info) => info,
            Err(e) => {
                events.push(Event::DecodeError {
                    message: e.to_string(),
                });
                return;
            }
        };
        if self.last_state != Some(info) {
            self.last_state = Some(info);
            events.push(Event::MachineStateChanged {
                state: info.state,
                substate: info.substate,
            });
        }
        for event in self.monitor.on_state_info(info, now_ms) {
            self.map_shot_event(event, now_ms, events);
        }
    }

    /// Decode and process a `ShotSample` telemetry notification.
    fn handle_sample(&mut self, data: &[u8], now_ms: u64, events: &mut Vec<Event>) {
        let sample = match ShotSample::parse(data) {
            Ok(sample) => sample,
            Err(e) => {
                events.push(Event::DecodeError {
                    message: e.to_string(),
                });
                return;
            }
        };
        for event in self.monitor.on_sample(&sample, now_ms) {
            self.map_shot_event(event, now_ms, events);
        }
        let elapsed_ms = self
            .shot_started_ms
            .map_or(0, |start| now_ms.saturating_sub(start));
        events.push(Event::Telemetry {
            elapsed_ms,
            group_pressure: sample.group_pressure,
            group_flow: sample.group_flow,
            head_temp: sample.head_temp,
        });
    }

    /// Translate a domain [`ShotEvent`] into FFI [`Event`]s, tracking the
    /// shot-start timestamp used to stamp telemetry elapsed time.
    fn map_shot_event(&mut self, event: ShotEvent, now_ms: u64, events: &mut Vec<Event>) {
        match event {
            ShotEvent::Started => {
                self.shot_started_ms = Some(now_ms);
                events.push(Event::ShotStarted);
            }
            ShotEvent::PhaseChanged(phase) => events.push(Event::ShotPhaseChanged { phase }),
            ShotEvent::FrameChanged(frame) => events.push(Event::ShotFrameChanged { frame }),
            ShotEvent::Completed(record) => {
                self.shot_started_ms = None;
                events.push(Event::ShotCompleted {
                    duration_ms: record.duration_ms,
                    sample_count: record.samples.len() as u32,
                });
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use de1_domain::ShotPhase;
    use de1_protocol::{MachineState, SubState};

    /// A valid 19-byte `ShotSample` packet (the `de1-protocol` doctest fixture).
    const SAMPLE: [u8; 19] = [
        0x12, 0x34, 0x90, 0x00, 0x28, 0x00, 0x5C, 0x80, 0x00, 0x58, 0x00, 0x5A, 0x00, 0x5D, 0x00,
        0x60, 0x40, 0x03, 0x96,
    ];

    #[test]
    fn entering_espresso_emits_state_change_and_shot_start() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::De1State, &[4, 5], 1_000);
        assert!(out.events.contains(&Event::MachineStateChanged {
            state: MachineState::Espresso,
            substate: SubState::Pouring,
        }));
        assert!(out.events.contains(&Event::ShotStarted));
        assert!(out.events.contains(&Event::ShotPhaseChanged {
            phase: ShotPhase::Pouring,
        }));
    }

    #[test]
    fn an_unchanged_state_is_not_re_emitted() {
        let mut core = CremaCore::new();
        core.on_notification(Source::De1State, &[2, 0], 1_000);
        let out = core.on_notification(Source::De1State, &[2, 0], 2_000);
        assert!(
            !out.events
                .iter()
                .any(|e| matches!(e, Event::MachineStateChanged { .. }))
        );
    }

    #[test]
    fn a_shot_sample_emits_telemetry_with_elapsed_time() {
        let mut core = CremaCore::new();
        core.on_notification(Source::De1State, &[4, 5], 1_000); // starts a shot
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 3_500);
        let telemetry = out
            .events
            .iter()
            .find(|e| matches!(e, Event::Telemetry { .. }))
            .expect("a Telemetry event");
        let Event::Telemetry { elapsed_ms, .. } = telemetry else {
            unreachable!("filtered for Telemetry");
        };
        assert_eq!(*elapsed_ms, 2_500);
    }

    #[test]
    fn telemetry_before_a_shot_has_zero_elapsed_time() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 9_000);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::Telemetry { elapsed_ms: 0, .. }))
        );
    }

    #[test]
    fn a_malformed_packet_yields_a_decode_error() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::De1State, &[], 1_000);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::DecodeError { .. }))
        );
    }

    #[test]
    fn on_tick_is_currently_a_no_op() {
        let mut core = CremaCore::new();
        assert!(core.on_tick(1_000).events.is_empty());
    }

    #[test]
    fn reset_clears_session_state() {
        let mut core = CremaCore::new();
        core.on_notification(Source::De1State, &[4, 5], 1_000);
        core.reset();
        // After a reset the same state is "new" again, so the shot re-starts.
        let out = core.on_notification(Source::De1State, &[4, 5], 5_000);
        assert!(out.events.contains(&Event::ShotStarted));
    }

    #[test]
    fn core_output_round_trips_through_json() {
        let output = CoreOutput {
            events: vec![
                Event::ShotStarted,
                Event::Telemetry {
                    elapsed_ms: 2_500,
                    group_pressure: 6.0,
                    group_flow: 2.0,
                    head_temp: 92.0,
                },
                Event::ShotFrameChanged { frame: 3 },
            ],
        };
        let json = output.to_json().unwrap();
        let parsed: CoreOutput = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, output);
    }
}

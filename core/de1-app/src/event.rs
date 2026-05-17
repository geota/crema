//! FFI value types: the input discriminator [`Source`] and the observed-output
//! envelope [`CoreOutput`] of [`Event`]s.

use de1_domain::ShotPhase;
use de1_protocol::{MachineState, SubState};
use serde::{Deserialize, Serialize};

use crate::error::AppError;

/// Which DE1 GATT characteristic an incoming notification came from.
///
/// The shell maps each subscribed characteristic UUID to a `Source` before
/// calling [`CremaCore::on_notification`](crate::CremaCore::on_notification).
/// `#[non_exhaustive]`: more sources (water level, scale weight, …) arrive in
/// later increments.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum Source {
    /// The machine state / substate characteristic (`cuuid_0E`).
    De1State,
    /// The shot-telemetry characteristic (`cuuid_0D`).
    De1ShotSample,
}

/// Something the core observed that the UI may want to react to.
///
/// Serialized with an internal `"type"` tag — a JSON event reads
/// `{"type":"ShotStarted"}` or `{"type":"Telemetry","elapsed_ms":…}`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type")]
#[non_exhaustive]
pub enum Event {
    /// The DE1's machine state or substate changed.
    MachineStateChanged {
        /// New top-level machine state.
        state: MachineState,
        /// New substate within `state`.
        substate: SubState,
    },
    /// An espresso shot began.
    ShotStarted,
    /// The shot moved to a new phase.
    ShotPhaseChanged {
        /// The phase now in effect.
        phase: ShotPhase,
    },
    /// The DE1 advanced to a new profile frame.
    ShotFrameChanged {
        /// Zero-based frame index.
        frame: u8,
    },
    /// A telemetry sample arrived from the DE1.
    Telemetry {
        /// Milliseconds since the shot began; `0` if no shot is in progress.
        elapsed_ms: u64,
        /// Group pressure, bar.
        group_pressure: f32,
        /// Group flow, mL/s.
        group_flow: f32,
        /// Group-head temperature, °C.
        head_temp: f32,
    },
    /// The espresso shot finished.
    ShotCompleted {
        /// Total shot duration, milliseconds.
        duration_ms: u64,
        /// Number of telemetry samples recorded.
        sample_count: u32,
    },
    /// An incoming notification could not be decoded.
    DecodeError {
        /// Human-readable description of the failure.
        message: String,
    },
}

/// The result of feeding the core one input: everything it observed.
///
/// A `commands` field — writes the shell should perform — will join this
/// envelope once the core can drive the machine (see
/// `docs/08-ffi-and-web-scope.md`). The struct is `#[non_exhaustive]` so that
/// addition is not a breaking change for the bridge crates.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[non_exhaustive]
pub struct CoreOutput {
    /// The events observed, in order.
    pub events: Vec<Event>,
}

impl CoreOutput {
    /// Serialize to JSON — the form the FFI bridges hand to the native shells.
    ///
    /// # Errors
    ///
    /// [`AppError::Serialization`] if serialization fails; it should not for a
    /// well-formed `CoreOutput`.
    pub fn to_json(&self) -> Result<String, AppError> {
        serde_json::to_string(self).map_err(|e| AppError::Serialization(e.to_string()))
    }
}

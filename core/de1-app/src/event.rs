//! FFI value types: the input discriminator [`Source`], the observed [`Event`]s
//! and [`Command`]s the core emits, and the [`CoreOutput`] envelope.

use de1_domain::{ShotPhase, StopReason};
use de1_protocol::{MachineState, SubState};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::error::AppError;

/// Which BLE characteristic an incoming notification came from.
///
/// The shell maps each subscribed characteristic UUID to a `Source` before
/// calling [`CremaCore::on_notification`](crate::CremaCore::on_notification).
/// `#[non_exhaustive]`: more sources (water level, …) arrive in later increments.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[non_exhaustive]
pub enum Source {
    /// The DE1 machine state / substate characteristic (`cuuid_0E`).
    De1State,
    /// The DE1 shot-telemetry characteristic (`cuuid_0D`).
    De1ShotSample,
    /// The connected scale's weight notification.
    ScaleWeight,
}

/// Something the core observed that the UI may want to react to.
///
/// Serialized adjacently tagged — a JSON event reads `{"type":"ShotStarted"}`
/// or `{"type":"Telemetry","content":{"elapsed_ms":…}}`.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
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
        elapsed_ms: u32,
        /// Group pressure, bar.
        group_pressure: f32,
        /// Group flow, mL/s.
        group_flow: f32,
        /// Group-head temperature, °C.
        head_temp: f32,
    },
    /// A weight reading arrived from the scale, smoothed by the flow estimator.
    ScaleReading {
        /// Robust current weight, grams.
        weight_g: f32,
        /// Robust mass-flow rate, grams per second.
        flow_g_per_s: f32,
    },
    /// Auto-stop decided the shot should end. The accompanying [`Command`]
    /// carries the actual stop write.
    StopTriggered {
        /// Why the shot was stopped.
        reason: StopReason,
    },
    /// The espresso shot finished.
    ShotCompleted {
        /// Total shot duration, milliseconds.
        duration_ms: u32,
        /// Number of telemetry samples recorded.
        sample_count: u32,
    },
    /// An incoming notification could not be decoded.
    DecodeError {
        /// Human-readable description of the failure.
        message: String,
    },
}

/// A writable DE1 GATT characteristic. The shell maps this to a UUID.
///
/// `#[non_exhaustive]`: profile-upload and MMR targets arrive later.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum WriteTarget {
    /// The DE1 `RequestedState` characteristic (`cuuid_02`).
    De1RequestedState,
}

/// A BLE write the shell should perform on the core's behalf.
///
/// The core owns the protocol, so a command carries the exact bytes; the shell
/// only needs a characteristic-UUID map, no protocol logic.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
#[non_exhaustive]
pub enum Command {
    /// Write `data` to a DE1 GATT characteristic.
    WriteCharacteristic {
        /// Which characteristic to write.
        target: WriteTarget,
        /// The bytes to write.
        data: Vec<u8>,
    },
    /// Write `data` to the connected scale's command characteristic.
    WriteScale {
        /// The bytes to write.
        data: Vec<u8>,
    },
}

/// The result of feeding the core one input: what it observed (`events`) and
/// what it wants the shell to do (`commands`).
///
/// `#[non_exhaustive]` so further fields can be added without breaking the
/// bridge crates.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[non_exhaustive]
pub struct CoreOutput {
    /// The events observed, in order.
    pub events: Vec<Event>,
    /// BLE writes the shell should perform, in order.
    pub commands: Vec<Command>,
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

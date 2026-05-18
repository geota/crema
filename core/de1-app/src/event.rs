//! FFI value types: the input discriminator [`Source`], the observed [`Event`]s
//! and [`Command`]s the core emits, and the [`CoreOutput`] envelope.

use de1_domain::{ShotPhase, SteamClogReason, StopReason, WaterSessionKind};
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
    /// The DE1 water-tank level characteristic (`cuuid_11`).
    De1WaterLevels,
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
        /// Robust mass-flow rate, grams per second — the core's own estimate
        /// from the weight series, available for every supported scale.
        flow_g_per_s: f32,
        /// The scale's own native mass-flow rate, grams per second, when the
        /// scale reports one (the Bookoo does); `None` otherwise. Distinct
        /// from the core-computed `flow_g_per_s` — surfaced as raw data.
        device_flow_g_per_s: Option<f32>,
        /// The scale's own built-in-timer reading, milliseconds, when the
        /// scale reports one (the Bookoo does); `None` otherwise.
        device_timer_ms: Option<u32>,
        /// The scale's live beeper-volume setting `0..=5`, when the scale
        /// echoes its settings in the weight notification (the Bookoo does);
        /// `None` otherwise. Lets a settings control display the real value.
        device_volume: Option<u8>,
        /// The scale's live auto-standby timeout, minutes, when the scale
        /// echoes its settings (the Bookoo does); `None` otherwise.
        device_standby_minutes: Option<u8>,
        /// The scale's live battery charge percentage, when the scale reports
        /// it (the Bookoo does); `None` otherwise.
        device_battery_percent: Option<u8>,
        /// Whether the scale's flow smoothing is on, when the scale echoes its
        /// settings in the weight notification (the Bookoo does); `None`
        /// otherwise. Lets a settings toggle reflect the real on/off state.
        device_flow_smoothing: Option<bool>,
        /// The scale's live auto-stop mode id (`0` = Flow-Stop, `1` =
        /// Cup-Removal), when the scale echoes its settings in the weight
        /// notification (the Bookoo does); `None` otherwise. Lets a settings
        /// selector reflect the real current mode.
        device_auto_stop: Option<u8>,
    },
    /// The DE1 reported its water-tank level.
    WaterLevel {
        /// Current tank level, mm — includes the legacy +5 mm sensor correction.
        level_mm: f32,
        /// Refill threshold, mm; a refill is wanted at or below it.
        refill_threshold_mm: f32,
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
    /// A hot-water or flush session began (the DE1 entered the `HotWater` or
    /// `HotWaterRinse` state).
    WaterSessionStarted {
        /// Whether this is a hot-water pour or a flush.
        kind: WaterSessionKind,
    },
    /// A hot-water or flush session finished.
    WaterSessionCompleted {
        /// Whether this was a hot-water pour or a flush.
        kind: WaterSessionKind,
        /// Total session duration, milliseconds.
        duration_ms: u32,
    },
    /// A steam session began (the DE1 entered the `Steam` state).
    SteamSessionStarted,
    /// A steam session finished.
    SteamSessionCompleted {
        /// Total session duration, milliseconds.
        duration_ms: u32,
        /// Number of steam telemetry samples recorded.
        sample_count: u32,
    },
    /// The just-finished steam session's telemetry suggests a clogged steam
    /// wand. Accompanies the [`Event::SteamSessionCompleted`] for the session.
    SteamClogSuspected {
        /// Which threshold the steam telemetry tripped.
        reason: SteamClogReason,
    },
    /// Steam eco mode engaged or disengaged. When it changes, an accompanying
    /// [`Command`] rewrites the DE1's steam target temperature.
    SteamEcoModeChanged {
        /// `true` when the steam target dropped to the lower eco temperature.
        eco: bool,
    },
    /// The connected scale stopped reporting weight — no reading has arrived
    /// for roughly a second. Emitted once per stale episode; a fresh reading
    /// re-arms it.
    ScaleStale,
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
    /// The DE1 steam / hot-water `ShotSettings` characteristic (`cuuid_0B`).
    De1ShotSettings,
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

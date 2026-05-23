//! FFI value types: the input discriminator [`Source`], the observed [`Event`]s
//! and [`Command`]s the core emits, and the [`CoreOutput`] envelope.

use de1_domain::{ShotPhase, SteamClogReason, StopReason, WaterSessionKind};
use de1_protocol::{CalCommand, CalTarget, MachineState, MmrRegister, SubState};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::error::AppError;

/// Which BLE characteristic an incoming notification came from.
///
/// The shell maps each subscribed characteristic UUID to a `Source` before
/// calling [`CremaCore::on_notification`](crate::CremaCore::on_notification).
/// `#[non_exhaustive]`: more sources (water level, …) arrive in later increments.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[non_exhaustive]
pub enum Source {
    /// The DE1 machine state / substate characteristic (`cuuid_0E`).
    De1State,
    /// The DE1 shot-telemetry characteristic (`cuuid_0D`).
    De1ShotSample,
    /// The connected scale's weight notification.
    ScaleWeight,
    /// The connected scale's *command* characteristic notification — the
    /// channel commands are written to, which on the Bookoo (`ff12`) also
    /// notifies: the scale pushes its serial / settings responses back on it.
    ScaleCommand,
    /// The DE1 water-tank level characteristic (`cuuid_11`).
    De1WaterLevels,
    /// The DE1 version characteristic (`cuuid_01`) — BLE + firmware versions.
    De1Version,
    /// The DE1 `ReadFromMMR` characteristic (`cuuid_05`) — the memory-mapped
    /// register window answers a read request with a notification here.
    De1MmrRead,
    /// The DE1 `Calibration` characteristic (`cuuid_12`) — sensor calibration
    /// reads (current vs. factory) answer here.
    De1Calibration,
    /// The DE1 `ShotSettings` characteristic (`cuuid_0B`) — steam, hot-water,
    /// and group-temp settings. The firmware notifies on this characteristic
    /// whenever the settings change (either from the on-machine UI or from a
    /// host Write), and a connect-time Read seeds the initial snapshot.
    /// Matches reaprime's `transport.shotSettings` notify stream + the
    /// legacy de1app's `de1_read_hotwater` (`bluetooth.tcl:1707`).
    De1ShotSettings,
    /// **DORMANT — see docs/16 §6.1 (snoop-verified 2026-05-21).** The DE1
    /// `HeaderWrite` characteristic (`cuuid_0F`) is nominally R/W per
    /// `docs/02-ble-protocol.md`, but the legacy de1app never Reads it and
    /// the firmware returns all-zero bytes when asked. The Crema BLE shell
    /// no longer triggers a Read of this characteristic (dropped in commit
    /// `49f0803`), so this `Source` arm is never dispatched and
    /// [`Event::ProfileHeaderRead`] is never emitted.
    ///
    /// Kept in the enum for forward-compat: if a future firmware exposes
    /// the loaded-profile buffer on the Read side, the wiring is one
    /// `device.readCharacteristic` call away. `#[non_exhaustive]` makes the
    /// variant additive; removing it later would be a breaking ABI change
    /// for any out-of-workspace consumer.
    De1ProfileHeader,
    /// The DE1 `FrameWrite` characteristic (`cuuid_10`) echo — during a
    /// profile upload the DE1 echoes each frame write back as a
    /// write-response / notification, carrying the same 8-byte payload it
    /// received. The orchestrator's ack matcher reads byte 0 (`FrameToWrite`)
    /// to confirm each frame applied. See `docs/16-profile-upload-plan.md` §5.
    De1FrameAck,
}

/// Something the core observed that the UI may want to react to.
///
/// Serialized adjacently tagged — a JSON event reads `{"type":"ShotStarted"}`
/// or `{"type":"Telemetry","content":{"elapsed":…}}`.
///
/// Numeric fields stay as flat scalars (`u32`, `f32`) so they cross the FFI
/// boundary as plain numbers — `Duration` does not transmit through typeshare
/// to TS / Kotlin. The unit each scalar carries is documented in its
/// doc-comment.
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
        elapsed: u32,
        /// Group pressure, bar.
        group_pressure: f32,
        /// Group flow, ml/s.
        group_flow: f32,
        /// Group-head temperature, °C.
        head_temp: f32,
        /// Mix temperature, °C — the DE1's blended "group" water temperature.
        mix_temp: f32,
        /// Steam-heater temperature, °C.
        steam_temp: f32,
        /// Running shot volume dispensed so far, ml — integrated from the
        /// telemetry flow stream against the DE1's `sample_time` ticks
        /// (BLE-jitter-immune when the line-frequency is known, host-clock
        /// fallback otherwise). Resets to 0 on every `ShotStarted`.
        dispensed_volume_ml: f32,
        /// Target group-head temperature, °C — the heater setpoint for the
        /// active frame. Legacy "Goal temperature" curve on the live shot
        /// chart (`de1_de1.tcl:540` maps `goal_temperature ← SetHeadTemp`).
        set_head_temp: f32,
        /// Target group pressure, bar — the pump pressure setpoint for the
        /// active frame. Legacy "Goal pressure" curve.
        set_group_pressure: f32,
        /// Target group flow, ml/s — the pump flow setpoint for the active
        /// frame. Legacy "Goal flow" curve.
        set_group_flow: f32,
        /// Puck resistance — `group_pressure / group_flow²`, the
        /// de1app/DSx derived "resistance" metric (the R4 read-path).
        /// `None` when group flow is too low to divide by meaningfully —
        /// the near-zero-flow region produces noisy spikes that have no
        /// useful interpretation. Units are `bar / (ml/s)²`. Surfaced on
        /// the event itself so every shell consumes the same value
        /// (previously each shell duplicated the formula + threshold).
        resistance: Option<f32>,
    },
    /// A weight reading arrived from the scale, smoothed by the flow estimator.
    ScaleReading {
        /// Robust current weight, grams.
        weight: f32,
        /// Robust mass-flow rate, grams per second — the core's own estimate
        /// from the weight series, available for every supported scale.
        flow: f32,
        /// The scale's own native mass-flow rate, grams per second, when the
        /// scale reports one (the Bookoo does); `None` otherwise. Distinct
        /// from the core-computed `flow` — surfaced as raw data.
        device_flow: Option<f32>,
        /// The scale's own built-in-timer reading, milliseconds, when the
        /// scale reports one (the Bookoo does); `None` otherwise.
        device_timer: Option<u32>,
        /// The scale's live beeper-volume setting `0..=5`, when the scale
        /// echoes its settings in the weight notification (the Bookoo does);
        /// `None` otherwise. Lets a settings control display the real value.
        device_volume: Option<u8>,
        /// The scale's live auto-standby timeout, minutes, when the scale
        /// echoes its settings (the Bookoo does); `None` otherwise.
        device_standby: Option<u8>,
        /// The scale's live battery charge percentage, when the scale reports
        /// it (the Bookoo does); `None` otherwise.
        device_battery: Option<u8>,
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
        level: f32,
        /// Refill threshold, mm; a refill is wanted at or below it.
        refill_threshold: f32,
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
        duration: u32,
        /// Number of telemetry samples recorded.
        sample_count: u32,
        /// Peak group pressure observed across every Telemetry sample of the
        /// shot, bar. `None` when no telemetry arrived. Computed once at the
        /// core boundary — previously each shell re-iterated the buffered
        /// series for it.
        peak_pressure: Option<f32>,
        /// Peak group-head temperature observed across every Telemetry
        /// sample of the shot, °C. `None` when no telemetry arrived.
        peak_temp: Option<f32>,
        /// Peak scale weight observed across every ScaleReading of the shot,
        /// grams. `None` when no scale was paired (no readings arrived).
        peak_weight: Option<f32>,
        /// The final scale weight observed before the shot ended, grams —
        /// the "shot yield". `None` when no scale was paired (no readings
        /// arrived).
        final_weight: Option<f32>,
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
        duration: u32,
    },
    /// A steam session began (the DE1 entered the `Steam` state).
    SteamSessionStarted,
    /// A steam session finished.
    SteamSessionCompleted {
        /// Total session duration, milliseconds.
        duration: u32,
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
    /// The connected scale reported its dynamic configuration on its command
    /// characteristic (the Bookoo's `ff12` channel).
    ///
    /// The scale answers a serial query (`0x0a`, also echoed after every
    /// anti-mistouch write) with the `anti_mistouch` state plus its `serial`
    /// and `firmware_version`; it answers a settings query (`0x0f`) with the
    /// `active_mode` index and `enabled_modes` bitmask. Any one notification
    /// carries only one response type, so the fields the other response would
    /// fill are `None` — the shell folds in whichever fields are present and
    /// keeps its last value for the rest, the two-way pattern the Bookoo's
    /// weight-stream settings already use.
    ScaleConfig {
        /// The scale's live anti-mistouch state — `Some` only for a `03 0c`
        /// serial response, `None` for a `03 0e` settings response.
        anti_mistouch: Option<bool>,
        /// The scale's active display-mode index (`0` = Flow-Rate, `1` =
        /// Timer, `2` = Auto) — `Some` only for a `03 0e` settings response.
        active_mode: Option<u8>,
        /// The scale's enabled-modes bitmask (bit `n` set when mode `n` is
        /// enabled) — `Some` only for a `03 0e` settings response.
        enabled_modes: Option<u8>,
        /// The scale's serial number — `Some` only for a `03 0c` serial
        /// response.
        serial: Option<String>,
        /// The scale's firmware version, encoded `major × 100 + minor × 10 +
        /// patch` (e.g. `141` is firmware 1.4.1) — `Some` only for a `03 0c`
        /// serial response.
        firmware_version: Option<u16>,
    },
    /// The DE1 reported its firmware version (the `Version` characteristic).
    /// Carries the **BLE-block** identity — the primary firmware identity per
    /// the legacy `de1_version_string` (`vars.tcl:3867`). The CPU/FW block is
    /// often all-zero on real DE1s and is not surfaced here; `firmware_string`
    /// appends its values only when the FW block carries a distinct, non-zero
    /// `Sha`.
    Firmware {
        /// The BLE-firmware release number.
        release: f32,
        /// The BLE-firmware commits-since-release count.
        commits: u16,
        /// The BLE-firmware API version.
        api_version: u8,
        /// A human-readable firmware label, e.g. `"v1.0.142 (API 4)"`.
        firmware_string: String,
    },
    /// A DE1 memory-mapped register was read back. Emitted when a
    /// `ReadFromMMR` reply decodes to a register Crema models; one event per
    /// register the reply carries.
    MmrValue {
        /// Which register this value is for.
        register: MmrRegister,
        /// The register's raw 32-bit value, little-endian. Some registers are
        /// scaled (e.g. `CalibrationFlowMultiplier` is `int(1000 ×
        /// multiplier)`); the raw word is surfaced and the reader scales it.
        value: u32,
    },
    /// A DE1 sensor calibration was read back from the `Calibration`
    /// characteristic — the current (in-use) or factory calibration for one
    /// sensor.
    Calibration {
        /// Which sensor the calibration applies to.
        target: CalTarget,
        /// Whether this is the current (in-use) or the factory calibration —
        /// [`CalCommand::ReadCurrent`] or [`CalCommand::ReadFactory`].
        command: CalCommand,
        /// The value the DE1's sensor reported at calibration time.
        de1_reported: f32,
        /// The externally-measured true value the DE1 was calibrated against.
        measured: f32,
    },
    /// A write was refused because a firmware upload is locking out other
    /// writes. v1 carries the lockout guard as a stub
    /// ([`firmware_locks_writes`](crate::CremaCore::firmware_locks_writes))
    /// that always returns `false`; v2 will return `true` for the
    /// `Erase..Verifying` phases of a firmware upload. The event names the
    /// refused method so the shell can show a transient toast.
    ///
    /// See `docs/17-firmware-update-plan.md` §3.4.
    FirmwareLockoutHit {
        /// Name of the [`CremaCore`](crate::CremaCore) method that was
        /// refused — e.g. `"set_refill_threshold"`.
        method: String,
    },
    /// **DORMANT — see [`Source::De1ProfileHeader`] and docs/16 §6.1.** This
    /// event would carry the DE1's reported `ShotHeader` if the firmware
    /// supported reading the loaded-profile buffer on `cuuid_0F`. It does
    /// not (snoop-verified 2026-05-21), so the BLE shell no longer triggers
    /// the Read and this variant is never emitted in the current code path.
    ///
    /// Kept for forward-compat — the decode path
    /// ([`CremaCore::handle_profile_header_read`](crate::CremaCore)) and
    /// every consumer (UI, tests) remain in place; flipping the BLE shell
    /// back to issuing a Read is one line in `web/src/lib/ble/de1.ts`.
    ProfileHeaderRead {
        /// Total number of frames in the loaded profile.
        frame_count: u8,
        /// How many leading frames count as preinfusion.
        preinfuse_frame_count: u8,
        /// Minimum pressure allowed in flow-priority frames, bar.
        minimum_pressure: f32,
        /// Maximum flow allowed in pressure-priority frames, ml/s.
        maximum_flow: f32,
    },
    /// The DE1's steam + hot-water + group-temp `ShotSettings` were read,
    /// either at connect-time or in response to a setting change. Mirrors
    /// the legacy de1app's `de1_read_hotwater` flow (`bluetooth.tcl:1707`)
    /// and reaprime's `shotSettings` notify stream.
    ShotSettingsRead {
        /// Target steam temperature, °C.
        steam_temp_c: f32,
        /// Steam timeout, seconds.
        steam_timeout_s: f32,
        /// Target hot-water temperature, °C.
        hot_water_temp_c: f32,
        /// Hot-water volume, ml.
        hot_water_volume_ml: f32,
        /// Hot-water timeout, seconds.
        hot_water_timeout_s: f32,
        /// Espresso target volume, ml.
        espresso_volume_ml: f32,
        /// Espresso group target temperature, °C.
        group_temp_c: f32,
    },
    /// A profile upload has begun. Carries the total number of acks the
    /// orchestrator expects (frames + extensions + tail; the header is
    /// not acked separately).
    ProfileUploadStarted {
        /// Title of the profile being uploaded — propagated for the
        /// shell's UI ("Uploading <title>…") and for the active-profile
        /// tracking that pairs with this event.
        title: String,
        /// Number of normal frames the profile has.
        frame_count: u8,
        /// Number of extension frames (one per step that has a limiter).
        extension_frame_count: u8,
    },
    /// One profile frame was acknowledged by the DE1.
    ProfileUploadProgress {
        /// The step index just acknowledged. For an extension-frame ack
        /// this is the step the extension extends (raw `FrameToWrite` byte
        /// minus 32); for the tail it equals `frame_count`.
        frame: u8,
        /// Whether the just-acked write was an extension frame.
        extension: bool,
        /// Total number of acks the upload expects
        /// (`frame_count + extension_count + 1`).
        total_acks: u16,
        /// Number of acks received so far, including this one.
        acks_received: u16,
    },
    /// The whole profile uploaded successfully — every expected ack
    /// arrived in order. Carries the title for the shell's
    /// active-profile bookkeeping.
    ProfileUploadCompleted {
        /// Title of the profile that finished uploading.
        title: String,
    },
    /// The profile upload failed. The core has discarded its in-progress
    /// state; the shell may retry by calling `upload_profile` again.
    ProfileUploadFailed {
        /// Why the upload failed.
        reason: ProfileUploadFailure,
    },
    /// An incoming notification could not be decoded.
    DecodeError {
        /// Human-readable description of the failure.
        message: String,
    },
}

/// Why a profile upload failed.
///
/// `#[non_exhaustive]` so additional categories (e.g. a firmware-side
/// "shot in progress" rejection signalled through some future cuuid_10
/// packet) can be added without breaking the FFI surface.
///
/// See `docs/16-profile-upload-plan.md` §4.3.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
#[non_exhaustive]
pub enum ProfileUploadFailure {
    /// The profile had no steps.
    Empty,
    /// The profile had more than 32 steps.
    TooManySteps {
        /// How many steps the rejected profile had.
        count: u32,
    },
    /// A frame ack arrived for a frame number the orchestrator did not
    /// expect — either out-of-order or for a step the profile does not have.
    UnexpectedAck {
        /// The `FrameToWrite` byte the core expected next, raw (`≥ 32` for
        /// an expected extension ack, `== frame_count` for the tail).
        expected: u8,
        /// The `FrameToWrite` byte the ack actually carried.
        got: u8,
    },
    /// No ack arrived within
    /// [`PROFILE_UPLOAD_ACK_TIMEOUT`](crate::PROFILE_UPLOAD_ACK_TIMEOUT) of
    /// the most recent ack (or upload start, for the first ack).
    AckTimeout {
        /// The `FrameToWrite` byte the core was waiting for when the
        /// timeout fired.
        awaiting: u8,
    },
    /// The shell called
    /// [`cancel_profile_upload`](crate::CremaCore::cancel_profile_upload)
    /// mid-upload.
    Aborted,
    /// An unexpected [`AppError`] variant — the bridge couldn't classify it.
    /// The `message` carries the `AppError`'s `Display` output so the shell
    /// can surface it in a toast / log without losing the underlying cause.
    ///
    /// Previously such variants were silently coerced to
    /// [`ProfileUploadFailure::Empty`], misclassifying real failures.
    Internal {
        /// Human-readable `Display` of the underlying error.
        message: String,
    },
}

/// A writable DE1 GATT characteristic. The shell maps this to a UUID.
///
/// `#[non_exhaustive]`: profile-upload targets arrive later.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[non_exhaustive]
pub enum WriteTarget {
    /// The DE1 `RequestedState` characteristic (`cuuid_02`).
    De1RequestedState,
    /// The DE1 steam / hot-water `ShotSettings` characteristic (`cuuid_0B`).
    De1ShotSettings,
    /// The DE1 `ReadFromMMR` characteristic (`cuuid_05`) — an MMR read request
    /// is *written* here; the DE1 answers on the same characteristic's notify.
    De1MmrRequest,
    /// The DE1 `WriteToMMR` characteristic (`cuuid_06`) — an MMR write packet
    /// is sent here. Sibling of [`De1MmrRequest`] on the read side; the two
    /// use distinct UUIDs even though they share a packet layout.
    De1MmrWrite,
    /// The DE1 `Calibration` characteristic (`cuuid_12`) — a calibration read
    /// request is *written* here; the DE1 answers on the same characteristic.
    De1Calibration,
    /// The DE1 `WaterLevels` characteristic (`cuuid_11`) — the same
    /// characteristic the DE1 notifies tank level on; a 4-byte
    /// `WaterLevels` packet is *written* here to set the refill threshold.
    De1WaterLevels,
    /// The DE1 `HeaderWrite` characteristic (`cuuid_0F`) — the 5-byte
    /// `ShotHeader` packet is *written* here at the start of a profile
    /// upload. See `docs/16-profile-upload-plan.md`.
    De1ProfileHeader,
    /// The DE1 `FrameWrite` characteristic (`cuuid_10`) — each 8-byte
    /// frame packet (normal frames, extension frames, and the tail) is
    /// *written* here in upload order during a profile upload. The DE1
    /// echoes each write back as a `Source::De1FrameAck` notification.
    De1ProfileFrame,
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

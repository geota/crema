//! # de1-app
//!
//! The headless Crema application core — the FFI-agnostic facade the bridge
//! crates wrap (`de1-ffi` for Android via UniFFI, `de1-wasm` for the web via
//! wasm-bindgen).
//!
//! [`CremaCore`] decodes the DE1's and the scale's BLE notifications, drives
//! the [`ShotMonitor`](de1_domain::ShotMonitor) state machine and the
//! [`AutoStop`](de1_domain::AutoStop) controller, and reports [`Event`]s and
//! [`Command`]s in a [`CoreOutput`] envelope. It is sans-IO and holds no clock
//! — every notification carries a monotonic `now_ms` from the shell.
//!
//! See `docs/08-ffi-and-web-scope.md` for the bridge design.

mod capture;
pub mod error;
pub mod event;
pub mod firmware_info;

pub use error::AppError;
pub use event::{Command, CoreOutput, Event, ProfileUploadFailure, Source, WriteTarget};
pub use firmware_info::{FirmwareUpdateStatus, LATEST_KNOWN_FIRMWARE_BUILD};

use capture::{CaptureRecorder, MetaSnapshot, slice_to_jsonl};

use std::time::Duration;

use de1_domain::{
    AutoStop, FlowAlgorithm, FlowEstimator, LineFreqDetector, Profile, STOP_WEIGHT_BEFORE,
    ShotEvent, ShotMonitor, ShotPhase, SteamEvent, SteamMonitor, StopConfig, StopReason,
    StopTargets, VolumeIntegrator, WaterEvent, WaterMonitor, WeightUnit,
};
use de1_protocol::{
    CalTarget, Calibration, EXTENSION_FRAME_INDEX_OFFSET, MachineState, MmrReadReply, MmrRegister,
    ShotHeader, ShotSample, ShotSettings, StateInfo, Version, WaterLevels, mmr, profile,
    requested_state,
};

/// Maximum gap between profile-upload acks before the orchestrator fails the
/// upload with [`ProfileUploadFailure::AckTimeout`]. Measured from the
/// upload start for the first ack, from each subsequent ack thereafter.
///
/// See `docs/16-profile-upload-plan.md` §5.3 for the rationale; a 5-second
/// margin is ~100× the typical real-DE1 round-trip and clears Web
/// Bluetooth's worst-case back-pressure window.
pub const PROFILE_UPLOAD_ACK_TIMEOUT: Duration = Duration::from_secs(5);
use de1_scale::{Scale, ScaleCapabilities, ScaleUuids, TimerCommand, decent_scale};
use de1_scale::DecentScaleFirmwareVersion;

/// Re-export of `de1_scale::bookoo` so the wasm + future Android bridges
/// can reach scale-side helpers (e.g. [`bookoo::format_firmware_version`])
/// without each one depending on `de1-scale` directly. `de1-app` is the
/// public-facing crate.
pub mod bookoo {
    pub use de1_scale::bookoo::*;
}

/// Re-export of `de1_scale::decent_scale` for the same reason `bookoo` is
/// re-exported above — the wasm + future Android bridges reach the
/// LCD-enable / LCD-disable / heartbeat constants through `de1-app`, the
/// public-facing crate.
pub mod decent_scale_protocol {
    pub use de1_scale::decent_scale::*;
}

/// Scale sensor lag assumed when no scale is connected — a representative
/// value across the supported scales (380 ms, the legacy default).
const DEFAULT_SCALE_LAG: Duration = Duration::from_millis(380);
/// Millimetres added to the DE1's reported tank level — the legacy
/// `water_level_mm_correction` (`machine.tcl`).
const WATER_LEVEL_MM_CORRECTION: f32 = 5.0;
/// How long the scale may go without reporting weight before it is considered
/// stale — the legacy app warns after roughly one second of silence.
const SCALE_STALE_TIMEOUT: Duration = Duration::from_secs(1);
/// Hot-water volume (ml) requested when a scale is connected: the legacy app
/// asks for far more water than wanted so the scale's weight-based stop is what
/// cuts off the pour (`binary.tcl` `return_de1_packed_steam_hotwater_settings`).
const HOT_WATER_STOP_ON_SCALE_ML: f32 = 250.0;
/// The legacy `steam_eco_temperature` default (°C): the lower steam target the
/// machine drops to after a long idle period (`machine.tcl:33`).
const STEAM_ECO_TEMPERATURE_C: f32 = 136.0;

/// Convert a shell-supplied `now_ms` (the FFI-friendly `u64` ms) to the
/// internal [`Duration`] every monitor and helper uses. Centralised here so
/// the conversion has one home, and so any rounding policy stays consistent.
fn ms_to_duration(now_ms: u64) -> Duration {
    Duration::from_millis(now_ms)
}

/// Saturating conversion of a [`Duration`] back to `u32` ms for an FFI event
/// field. A real session never approaches `u32::MAX` ms (~49 days); the
/// checked conversion saturates rather than wrapping if it somehow did.
fn duration_to_u32_ms(d: Duration) -> u32 {
    u32::try_from(d.as_millis()).unwrap_or(u32::MAX)
}

/// Minimum group flow (ml/s) below which puck-resistance is undefined.
/// Reproduces the legacy de1app / DSx threshold — at near-zero flow the
/// numeric value swings wildly (small denominator squared) and reads
/// noisy, so the metric is suppressed.
const RESISTANCE_FLOW_FLOOR_ML_PER_S: f32 = 0.1;

/// The de1app/DSx "puck resistance" metric — `group_pressure / group_flow²`.
/// Surfaced on every [`Event::Telemetry`] so each shell consumes the same
/// computation. Returns `None` when group flow is below
/// [`RESISTANCE_FLOW_FLOOR_ML_PER_S`] — near-zero-flow values aren't
/// meaningful and used to be masked at the readout layer in every shell.
/// Units: `bar / (ml/s)²`.
fn puck_resistance(group_pressure_bar: f32, group_flow_ml_per_s: f32) -> Option<f32> {
    if !group_pressure_bar.is_finite() || !group_flow_ml_per_s.is_finite() {
        return None;
    }
    if group_flow_ml_per_s < RESISTANCE_FLOW_FLOOR_ML_PER_S {
        return None;
    }
    let r = group_pressure_bar / (group_flow_ml_per_s * group_flow_ml_per_s);
    if r.is_finite() {
        Some(r)
    } else {
        None
    }
}

/// Running peak / final-weight accumulator for one shot.
///
/// Fed each Telemetry sample (DE1-side: pressure, temperature) and each
/// scale weight reading (scale-side: peak weight + final weight). Reset on
/// every [`Event::ShotStarted`]; drained on [`Event::ShotCompleted`] into
/// the metrics the event carries.
///
/// Surfacing the metrics on the event removes a three-way re-iteration of
/// the buffered series in the web shell (`history/store.svelte.ts` live +
/// imported paths and `state/ui-state.svelte.ts`'s `ShotCompleted` fold).
/// All four are `Option<f32>` because there's no sample to bound them:
/// `peak_pressure` / `peak_temp` are `None` when no Telemetry arrived;
/// `peak_weight` / `final_weight` are `None` when no scale was paired.
#[derive(Debug, Default, Clone, Copy)]
struct ShotMetricsAccumulator {
    peak_pressure: Option<f32>,
    peak_temp: Option<f32>,
    peak_weight: Option<f32>,
    final_weight: Option<f32>,
}

impl ShotMetricsAccumulator {
    /// Update the pressure / temperature peaks from a DE1 Telemetry sample.
    fn feed_telemetry(&mut self, group_pressure: f32, head_temp: f32) {
        if group_pressure.is_finite() {
            self.peak_pressure = Some(match self.peak_pressure {
                Some(p) => p.max(group_pressure),
                None => group_pressure,
            });
        }
        if head_temp.is_finite() {
            self.peak_temp = Some(match self.peak_temp {
                Some(t) => t.max(head_temp),
                None => head_temp,
            });
        }
    }

    /// Update the weight peak and the running final-weight from a scale
    /// reading. `final_weight` is the last non-`None` weight observed, so
    /// every reading updates it.
    fn feed_weight(&mut self, weight: f32) {
        if !weight.is_finite() {
            return;
        }
        self.peak_weight = Some(match self.peak_weight {
            Some(w) => w.max(weight),
            None => weight,
        });
        self.final_weight = Some(weight);
    }

    /// Pop the four metrics for an [`Event::ShotCompleted`] payload, leaving
    /// the accumulator zeroed for the next shot.
    fn drain(&mut self) -> (Option<f32>, Option<f32>, Option<f32>, Option<f32>) {
        let metrics = (
            self.peak_pressure,
            self.peak_temp,
            self.peak_weight,
            self.final_weight,
        );
        *self = ShotMetricsAccumulator::default();
        metrics
    }

    /// Wipe everything; used on [`Event::ShotStarted`] in case the previous
    /// shot ended in a path that didn't drain (defensive — `map_shot_event`
    /// always drains on `Completed`, but the reset keeps the invariant
    /// independent of call order).
    fn reset(&mut self) {
        *self = ShotMetricsAccumulator::default();
    }
}

/// The headless Crema application core.
///
/// One `CremaCore` tracks one machine session. The FFI bridges wrap it behind
/// a mutex; the type itself uses ordinary `&mut self` methods so it stays
/// plain, testable Rust.
#[derive(Debug)]
pub struct CremaCore {
    monitor: ShotMonitor,
    /// Tracks hot-water and flush sessions, the sibling of `monitor`.
    water: WaterMonitor,
    /// Tracks steam sessions, eco mode, and steam-clog detection.
    steam: SteamMonitor,
    /// The most recent steam / hot-water [`ShotSettings`] supplied by the
    /// shell, retained so eco-mode transitions can rewrite the steam target.
    /// `None` until [`set_steam_hotwater_settings`](Self::set_steam_hotwater_settings).
    steam_hotwater_settings: Option<ShotSettings>,
    /// The eco-mode steam target temperature, °C.
    steam_eco_temp: f32,
    last_state: Option<StateInfo>,
    /// The most recent `Version` characteristic reply the shell delivered.
    /// `None` until the DE1 connects and the BLE shell reads `cuuid_01`. The
    /// BLE block here drives the human-readable firmware label
    /// ([`Event::Firmware`]); the user-visible build number for the
    /// update-status check lives in [`last_firmware_build`](Self) instead.
    last_firmware: Option<de1_protocol::Version>,
    /// The most recent value read from MMR register `0x800010`
    /// ([`MmrRegister::FirmwareVersion`]) — Decent's user-visible firmware
    /// build number ("v1352"). `None` until that MMR has been read at least
    /// once. Powers [`firmware_update_status`](Self::firmware_update_status).
    last_firmware_build: Option<u16>,
    /// Timestamp the in-progress shot began — stamps telemetry elapsed time.
    /// `None` when no shot is in progress.
    shot_started: Option<Duration>,
    /// The connected scale's codec, once identified.
    scale: Option<Scale>,
    /// Smooths the scale stream into weight + flow for display.
    flow: FlowEstimator,
    /// Auto-stop targets armed by the shell; the [`AutoStop`] itself is built
    /// when the shot first reaches a flowing phase.
    auto_stop_targets: Option<StopTargets>,
    /// The live auto-stop controller, for the duration of one shot.
    auto_stop: Option<AutoStop>,
    /// Timestamp of the most recent scale-weight reading, for the lost-scale
    /// watchdog. `None` until the connected scale reports once.
    last_scale_weight: Option<Duration>,
    /// Whether an [`Event::ScaleStale`] has already been emitted for the
    /// current stale episode — cleared by the next fresh reading.
    scale_stale_reported: bool,
    /// Whether the one-shot connect-time scale-config queries (`0x0a` serial /
    /// `0x0f` settings) have already been issued for the connected scale.
    /// There is no scale-connected notification, so the queries are emitted on
    /// the first weight notification from a capability-bearing scale; this
    /// flag keeps that a single emission. Cleared by [`reset`](Self::reset)
    /// and by [`connect_scale`](Self::connect_scale).
    scale_config_queried: bool,
    /// In-flight profile-upload state. `Some` from
    /// [`upload_profile`](Self::upload_profile) until the tail is acked,
    /// the upload is cancelled, or a failure event is emitted. See
    /// [`ProfileUpload`].
    profile_upload: Option<ProfileUpload>,
    /// Title of the profile that most recently completed uploading — the
    /// "active profile on the DE1" identity the brew page surfaces. `None`
    /// at cold start; cleared by [`reset`](Self::reset) on disconnect.
    last_active_profile_title: Option<String>,
    /// The most recently observed `ShotHeader` from the `HeaderWrite`
    /// characteristic — the shape of whatever profile the DE1 has loaded.
    /// Populated by [`handle_profile_header_read`](Self::handle_profile_header_read).
    last_profile_header: Option<ShotHeader>,
    /// Running dispensed-volume integrator — integrates `flow × dt` from
    /// every `ShotSample`. The `dt` source is the DE1's `sample_time` when
    /// the line-frequency is known, else the host clock. Reset on every
    /// `ShotEvent::Started`.
    volume_integrator: VolumeIntegrator,
    /// Auto-detector for the DE1's AC mains frequency (50 vs 60 Hz). Locks
    /// once after a 1+ second warmup; never reconsidered for the rest of
    /// the session. Cleared by [`reset`](Self::reset).
    line_freq_detector: LineFreqDetector,
    /// User-supplied override for the AC mains frequency. `Some(50.0)` or
    /// `Some(60.0)` if the user pinned it in settings; `None` means
    /// auto-detect (the detector's locked value wins).
    line_freq_override: Option<f32>,
    /// Running peak / final-weight tracker for the in-progress shot. Reset
    /// on every `ShotEvent::Started`; drained into `Event::ShotCompleted`
    /// on every `ShotEvent::Completed`. See [`ShotMetricsAccumulator`].
    shot_metrics: ShotMetricsAccumulator,
    /// Rolling BLE-capture buffer + identity-keeper. Fed at the top of
    /// [`on_notification`](Self::on_notification) (gated on
    /// [`CaptureRecorder::is_suppressed`] so replays don't re-record
    /// themselves); sliced into JSONL via
    /// [`capture_slice_jsonl`](Self::capture_slice_jsonl). The shell used
    /// to own this — pushed into the core (docs/26 audit) so web + Android
    /// share one byte-for-byte implementation and the recorder side-effect
    /// piggybacks the existing wasm-boundary crossing.
    capture: CaptureRecorder,
    /// BLE advertised name of the most recently `connect_scale`'d scale, so
    /// the META prelude can carry it. `None` until a scale identifies; reset
    /// by [`reset`](Self::reset) and re-set on every [`connect_scale`](Self::connect_scale).
    last_scale_advertised_name: Option<String>,
    /// Most recent value read from MMR register `0x80000C`
    /// ([`MmrRegister::MachineModel`]). `None` until the MMR has been read at
    /// least once. Feeds the capture META prelude alongside
    /// [`last_firmware_build`](Self::last_firmware_build) and
    /// [`last_serial_number`](Self).
    last_machine_model: Option<u32>,
    /// Most recent value read from MMR register `0x803830`
    /// ([`MmrRegister::SerialNumber`]). `None` until the MMR has been read at
    /// least once. Feeds the capture META prelude.
    last_serial_number: Option<u32>,
    /// The user's chosen display unit for weight, mirrored from the shell's
    /// settings store via [`set_weight_unit_pref`](Self::set_weight_unit_pref).
    ///
    /// Read by the Decent-Scale LCD-enable path (both the public method
    /// and the reactive auto-policy in [`handle_state`](Self::handle_state))
    /// to choose between [`decent_scale::LCD_ENABLE_GRAMS`] and
    /// [`decent_scale::LCD_ENABLE_OUNCES`]. The pref lives in the shell —
    /// the core is sans-IO and never reads settings storage — so this
    /// field is a write-through cache the shell keeps current.
    /// Defaults to [`WeightUnit::Grams`] (matches the legacy app default
    /// and the canonical storage unit).
    weight_unit_pref: WeightUnit,
}

/// In-flight state of one profile upload. Owned by
/// [`CremaCore::profile_upload`].
///
/// The orchestrator does **not** queue or sequence the BLE writes — the
/// `upload_profile` call emits every write in one `CoreOutput`, and the
/// shell submits them serially. The orchestrator only walks
/// [`expected_acks`](Self::expected_acks) as `Source::De1FrameAck`
/// notifications arrive.
#[derive(Debug)]
struct ProfileUpload {
    /// Title of the profile being uploaded; propagated to the completion /
    /// failure events and into
    /// [`last_active_profile_title`](CremaCore::last_active_profile_title)
    /// on success.
    title: String,
    /// The full sequence of `FrameToWrite` bytes the orchestrator expects,
    /// in upload order: `[0, 1, …, frame_count-1, 32+ext0, 32+ext1, …,
    /// frame_count]`. The header is not acked separately.
    expected_acks: Vec<u8>,
    /// Index into [`expected_acks`](Self::expected_acks) of the next ack
    /// the orchestrator is waiting for. Starts at `0`; on the final ack,
    /// `expected_acks.len() - 1`.
    next_idx: usize,
    /// Wall-clock of the most recent progress (upload start or an ack)
    /// — anchors the [`PROFILE_UPLOAD_ACK_TIMEOUT`] watchdog in
    /// [`CremaCore::on_tick`].
    last_progress: Duration,
}

impl Default for CremaCore {
    fn default() -> CremaCore {
        CremaCore::new()
    }
}

impl CremaCore {
    /// Create a core in the idle state.
    pub fn new() -> CremaCore {
        CremaCore {
            monitor: ShotMonitor::new(),
            water: WaterMonitor::new(),
            steam: SteamMonitor::new(),
            steam_hotwater_settings: None,
            steam_eco_temp: STEAM_ECO_TEMPERATURE_C,
            last_state: None,
            last_firmware: None,
            last_firmware_build: None,
            shot_started: None,
            scale: None,
            flow: FlowEstimator::new(FlowAlgorithm::default()),
            auto_stop_targets: None,
            auto_stop: None,
            last_scale_weight: None,
            scale_stale_reported: false,
            scale_config_queried: false,
            profile_upload: None,
            last_active_profile_title: None,
            last_profile_header: None,
            volume_integrator: VolumeIntegrator::new(),
            line_freq_detector: LineFreqDetector::new(),
            line_freq_override: None,
            shot_metrics: ShotMetricsAccumulator::default(),
            capture: CaptureRecorder::default(),
            last_scale_advertised_name: None,
            last_machine_model: None,
            last_serial_number: None,
            weight_unit_pref: WeightUnit::default(),
        }
    }

    /// The effective AC mains frequency the volume integrator will use.
    /// Returns the user override if set, else the auto-detector's locked
    /// value (if any), else `None` — in which case the integrator falls
    /// back to host-clock `dt` (BLE-jitter contaminated, ~5% volume drift).
    pub fn line_frequency_hz(&self) -> Option<f32> {
        self.line_freq_override.or(self.line_freq_detector.locked_hz())
    }

    /// Pin the AC mains frequency. `Some(50.0)` / `Some(60.0)` overrides
    /// the auto-detector; `None` returns to auto. Persisting the setting
    /// is the shell's job — pass `Some(hz)` on `loadCore()` once read from
    /// localStorage, or here when the user changes it in Settings.
    pub fn set_line_frequency_override(&mut self, hz: Option<f32>) {
        self.line_freq_override = hz;
    }

    /// Volume dispensed in the current shot, ml. Resets to 0 on every
    /// `Event::ShotStarted`. Updated on every `ShotSample` notification.
    pub fn dispensed_volume(&self) -> f32 {
        self.volume_integrator.dispensed_ml()
    }

    /// Whether a firmware upload is currently locking out other writes.
    ///
    /// v1 always returns `false` — firmware update is a v2 feature
    /// (`docs/17-firmware-update-plan.md`). The stub is carried now so every
    /// write method added in v1 can include the one-line lockout guard
    /// ([`refuse_if_firmware_locked`](Self::refuse_if_firmware_locked)) at the
    /// time the write lands; retrofitting nine guards at v2 time is more work
    /// than carrying them piecemeal.
    ///
    /// v2 will return `true` for the `Erase..Verifying` phases of a firmware
    /// upload and `false` everywhere else; read-only methods bypass this check
    /// because they cannot disturb an in-flight upload.
    pub fn firmware_locks_writes(&self) -> bool {
        false
    }

    /// If [`firmware_locks_writes`](Self::firmware_locks_writes) is `true`,
    /// build a [`CoreOutput`] carrying one [`Event::FirmwareLockoutHit`] named
    /// after `method` and return it; otherwise return `None`.
    ///
    /// The intended use is:
    ///
    /// ```ignore
    /// pub fn set_refill_threshold(&self, mm: f32) -> CoreOutput {
    ///     if let Some(out) = self.refuse_if_firmware_locked("set_refill_threshold") {
    ///         return out;
    ///     }
    ///     // …normal write…
    /// }
    /// ```
    fn refuse_if_firmware_locked(&self, method: &str) -> Option<CoreOutput> {
        if self.firmware_locks_writes() {
            let mut out = CoreOutput::default();
            out.events.push(Event::FirmwareLockoutHit {
                method: method.to_owned(),
            });
            Some(out)
        } else {
            None
        }
    }

    /// Identify and connect a scale from its BLE advertised name. Returns the
    /// connected scale's display label (`Scale::label`), or `None` if the name
    /// matched no supported scale.
    pub fn connect_scale(&mut self, advertised_name: &str) -> Option<String> {
        self.scale = Scale::identify(advertised_name);
        // A newly connected scale has not yet been asked for its serial /
        // settings; re-arm the one-shot connect-time queries.
        self.scale_config_queried = false;
        // Remember the advertised name for the capture META prelude — the
        // replay tool reads it to call `connectScale(name)` BEFORE iterating
        // the bytes (so subsequent SCALE_WEIGHT entries can decode). Only
        // stamped when the name identifies a supported scale, since the
        // capture is only useful when there's a decoder.
        let label = self.scale.as_ref().map(|scale| scale.label().to_owned());
        if label.is_some() {
            self.last_scale_advertised_name = Some(advertised_name.to_owned());
        }
        label
    }

    /// What the connected scale can do beyond reporting a bare weight — see
    /// [`ScaleCapabilities`]. `None` when no scale is connected.
    ///
    /// The shell reads this to drive capability-gated configuration UI: Crema
    /// is capability-driven, never device-driven, so the shell never branches
    /// on the concrete scale model.
    pub fn scale_capabilities(&self) -> Option<ScaleCapabilities> {
        self.scale.as_ref().map(Scale::capabilities)
    }

    /// The connected scale's BLE service and characteristic UUIDs — see
    /// [`ScaleUuids`]. `None` when no scale is connected.
    ///
    /// The web shell reads this to know which Web Bluetooth characteristics
    /// to subscribe to for weight notifications and command writes.
    pub fn scale_uuids(&self) -> Option<ScaleUuids> {
        self.scale.as_ref().map(Scale::uuids)
    }

    /// Arm automatic shot-stop. A `None` target disables that mode; the
    /// [`AutoStop`] is constructed when the next shot starts flowing.
    ///
    /// `weight` is grams (SAW target), `volume` is millilitres (SAV target),
    /// and `max_time` is shot-duration seconds (the legacy `espresso_max_time`
    /// limit).
    pub fn arm_auto_stop(
        &mut self,
        weight: Option<f32>,
        volume: Option<f32>,
        max_time: Option<f32>,
    ) {
        self.auto_stop_targets = Some(StopTargets {
            weight,
            volume,
            max_time,
        });
    }

    /// Disarm automatic shot-stop, including any controller already running.
    pub fn disarm_auto_stop(&mut self) {
        self.auto_stop_targets = None;
        self.auto_stop = None;
    }

    /// The standard DE1 profiles Crema ships built in, as a JSON array string.
    ///
    /// Every profile is a [`Profile`](de1_domain::Profile) (already serde),
    /// imported once from the verbatim-vendored legacy `*.tcl` files; see
    /// [`de1_domain::builtin_profiles`]. The shell parses the JSON into its own
    /// profile type, consistent with the rest of the JSON-string FFI surface.
    ///
    /// This is a pure read of compile-time data: it touches no machine session
    /// state, so it does not return a [`CoreOutput`]. Serialization of a
    /// `Vec<Profile>` is plain data and infallible in practice.
    pub fn builtin_profiles_json(&self) -> String {
        serde_json::to_string(de1_domain::builtin_profiles())
            .expect("built-in profiles are plain data and always serialize")
    }

    /// Build a [`Command`] that asks the DE1 to enter `state` — e.g.
    /// [`MachineState::Espresso`] to start a shot, [`MachineState::Idle`] to
    /// stop one or wake from sleep.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits one
    /// [`Event::FirmwareLockoutHit`] and no command.
    pub fn request_machine_state(&self, state: MachineState) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("request_machine_state") {
            return out;
        }
        let mut out = CoreOutput::default();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1RequestedState,
            data: vec![requested_state(state)],
        });
        out
    }

    /// Build a [`CoreOutput`] whose command reads one DE1 memory-mapped
    /// register.
    ///
    /// MMR is request/reply: the command writes a one-word `ReadFromMMR`
    /// request to [`WriteTarget::De1MmrRequest`]; the DE1 answers with a
    /// notification on the [`Source::De1MmrRead`] characteristic, which
    /// [`handle_mmr_read`](Self::handle_mmr_read) turns into an
    /// [`Event::MmrValue`]. Shaped like
    /// [`query_scale_settings`](Self::query_scale_settings).
    pub fn read_mmr(&self, register: MmrRegister) -> CoreOutput {
        let mut out = CoreOutput::default();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1MmrRequest,
            data: mmr::read_request(register.address(), 1).to_vec(),
        });
        out
    }

    /// Build a [`CoreOutput`] whose command reads `target`'s current (in-use)
    /// sensor calibration.
    ///
    /// Request/reply, like [`read_mmr`](Self::read_mmr): the command writes a
    /// [`Calibration`] read request to [`WriteTarget::De1Calibration`]; the
    /// DE1 answers on the [`Source::De1Calibration`] characteristic, decoded
    /// by [`handle_calibration`](Self::handle_calibration) into an
    /// [`Event::Calibration`].
    pub fn read_calibration(&self, target: CalTarget) -> CoreOutput {
        let mut out = CoreOutput::default();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1Calibration,
            data: Calibration::read_request(target).encode().to_vec(),
        });
        out
    }

    /// Build a [`CoreOutput`] whose command reads `target`'s factory sensor
    /// calibration — the calibration the machine shipped with, distinct from
    /// the current (in-use) one [`read_calibration`](Self::read_calibration)
    /// returns. The reply is an [`Event::Calibration`] with
    /// [`CalCommand::ReadFactory`](de1_protocol::CalCommand::ReadFactory).
    pub fn read_factory_calibration(&self, target: CalTarget) -> CoreOutput {
        let mut out = CoreOutput::default();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1Calibration,
            data: Calibration::read_factory_request(target).encode().to_vec(),
        });
        out
    }

    /// Write a new sensor calibration: the DE1 reported `reported` while
    /// the externally-measured true value was `measured`. The firmware
    /// stores the pair as a `WriteKey 0xCAFEF00D` packet on `cuuid_12`
    /// and from then on applies `measured / reported` as a multiplier on
    /// that sensor. The caller is expected to follow up with
    /// [`read_calibration`](Self::read_calibration) to surface the value
    /// the DE1 now reports as current.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits
    /// one [`Event::FirmwareLockoutHit`] and no command.
    pub fn write_calibration(
        &self,
        target: CalTarget,
        reported: f32,
        measured: f32,
    ) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("write_calibration") {
            return out;
        }
        let mut out = CoreOutput::default();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1Calibration,
            data: Calibration::write(target, reported, measured).encode().to_vec(),
        });
        out
    }

    /// Reset `target` to its factory sensor calibration. Encodes a
    /// `WriteKey 0xCAFEF00D` `ResetToFactory` packet onto `cuuid_12`; the
    /// DE1 immediately starts applying the factory calibration for that
    /// sensor. The caller is expected to follow up with
    /// [`read_calibration`](Self::read_calibration) (and optionally
    /// [`read_factory_calibration`](Self::read_factory_calibration)) to
    /// confirm the in-use value.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits
    /// one [`Event::FirmwareLockoutHit`] and no command.
    pub fn reset_calibration_to_factory(&self, target: CalTarget) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("reset_calibration_to_factory") {
            return out;
        }
        let mut out = CoreOutput::default();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1Calibration,
            data: Calibration::reset_to_factory(target).encode().to_vec(),
        });
        out
    }

    /// Build a [`Command`] that writes the DE1's steam / hot-water
    /// [`ShotSettings`] (`cuuid_0B`).
    ///
    /// The caller supplies the user's configured settings. When a scale is
    /// connected the hot-water volume on the wire is overridden so the scale's
    /// weight-based stop cuts the pour off — see
    /// [`steam_settings_for_eco`](Self::steam_settings_for_eco).
    ///
    /// The supplied settings are retained *unmodified* so a later eco-mode
    /// transition can rewrite the steam target on the same baseline; the
    /// scale-connected hot-water override is applied only when building the
    /// wire packet (see [`steam_settings_for_eco`](Self::steam_settings_for_eco))
    /// so it does not stick after the scale disconnects.
    pub fn set_steam_hotwater_settings(&mut self, settings: ShotSettings) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_steam_hotwater_settings") {
            return out;
        }
        self.steam_hotwater_settings = Some(settings);
        let mut out = CoreOutput::default();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1ShotSettings,
            data: self
                .steam_settings_for_eco(self.steam.is_eco_mode())
                .encode()
                .to_vec(),
        });
        out
    }

    /// Enable or disable steam eco mode (the legacy `eco_steam` setting). When
    /// enabled, the steam target drops to the eco temperature after the
    /// machine has been idle for the eco delay; see
    /// [`SteamMonitor`](de1_domain::SteamMonitor). Disabling it while engaged
    /// restores the normal steam target immediately.
    pub fn enable_steam_eco_mode(&mut self, enabled: bool, now_ms: u64) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("enable_steam_eco_mode") {
            return out;
        }
        let now = ms_to_duration(now_ms);
        let mut out = CoreOutput::default();
        for event in self.steam.on_eco_enabled(enabled, now) {
            self.map_steam_event(event, &mut out);
        }
        out
    }

    /// Build a [`Command`] that writes a single DE1 memory-mapped register.
    ///
    /// `register` selects the address; `value` is the raw little-endian word
    /// the register expects, **already scaled** per the register's
    /// documentation (the typed helpers below apply each register's scale
    /// before calling this). `byte_len` is how many of the four LE bytes the
    /// register actually consumes — 1, 2, or 4 — which controls the wire
    /// `Len` byte of the `WriteToMMR` packet.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits
    /// one [`Event::FirmwareLockoutHit`] and no command.
    pub fn write_mmr(&self, register: MmrRegister, value: u32, byte_len: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("write_mmr") {
            return out;
        }
        mmr_write_command(register, value, byte_len)
    }

    /// Set the fan-on temperature threshold, in °C (legacy
    /// `set_fan_temperature_threshold`). MMR `0x803808`, 1-byte.
    pub fn set_fan_threshold(&self, temp_c: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_fan_threshold") {
            return out;
        }
        mmr_write_command(MmrRegister::FanThreshold, u32::from(temp_c), 1)
    }

    /// Set the tank desired water-temperature threshold, in °C (legacy
    /// `set_tank_temperature_threshold` — the immediate value only; the
    /// legacy "preheat with 60 °C for 4 s" dance is a shell-side concern, see
    /// `docs/14` §4.2). MMR `0x80380C`, 1-byte.
    pub fn set_tank_threshold(&self, temp_c: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_tank_threshold") {
            return out;
        }
        mmr_write_command(MmrRegister::TankTempThreshold, u32::from(temp_c), 1)
    }

    /// Set the steam flow rate. `ml_per_s` is scaled by ×100 per the
    /// legacy `de1_comms.tcl:1210` + reaprime `de1.models.dart`
    /// (`steamFlow` `writeScale: 100.0`); the stored wire value is
    /// `ml/s × 100` (e.g. 7.0 ml/s → raw 700). MMR `0x803828`, 4-byte.
    /// Pre-2026-05-22 builds wrote `×10` into a 1-byte slot, both
    /// wrong; the 1-byte clamp prevented valid steam targets from
    /// reaching the firmware. docs/22 §2.1.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_steam_flow(&self, ml_per_s: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_steam_flow") {
            return out;
        }
        let raw = (ml_per_s * 100.0).round().clamp(0.0, f32::from(u16::MAX)) as u32;
        mmr_write_command(MmrRegister::SteamFlow, raw, 4)
    }

    /// Set the seconds of high-flow steam at the start of a steam cycle
    /// (legacy `set_steam_highflow_start`). The wire value is
    /// `seconds × 100` (the firmware default `0.7s` stores as `70` —
    /// see legacy `machine.tcl:309 steam_highflow_start 70`). MMR
    /// `0x80382C`, 4-byte. Takes `f32` seconds so sub-second precision
    /// survives — pre-2026-05-22 builds took `Duration` and called
    /// `as_secs()`, truncating `0.7s` to `0`. docs/22 §2.2.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_steam_highflow_start(&self, seconds: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_steam_highflow_start") {
            return out;
        }
        let raw = (seconds * 100.0).round().clamp(0.0, f32::from(u16::MAX)) as u32;
        mmr_write_command(MmrRegister::SteamHighFlowStart, raw, 4)
    }

    /// Set the group-head-control mode (legacy `set_ghc_mode`). `mode` is the
    /// raw mode id (`0`–`3`). MMR `0x803820`, 1-byte.
    pub fn set_ghc_mode(&self, mode: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_ghc_mode") {
            return out;
        }
        mmr_write_command(MmrRegister::GhcMode, u32::from(mode), 1)
    }

    /// Set the hot-water flow rate. `ml_per_s` is scaled `int(10 × rate)`.
    /// MMR `0x80384C`, 2-byte.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_hot_water_flow_rate(&self, ml_per_s: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_hot_water_flow_rate") {
            return out;
        }
        let raw = (ml_per_s * 10.0).round().clamp(0.0, 65_535.0) as u32;
        mmr_write_command(MmrRegister::HotWaterFlowRate, raw, 2)
    }

    /// Set the flush flow rate. `ml_per_s` is scaled `int(10 × rate)`.
    /// MMR `0x803840`, 2-byte.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_flush_flow_rate(&self, ml_per_s: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_flush_flow_rate") {
            return out;
        }
        let raw = (ml_per_s * 10.0).round().clamp(0.0, 65_535.0) as u32;
        mmr_write_command(MmrRegister::FlushFlowRate, raw, 2)
    }

    /// Set the flush water target temperature, °C — the temperature the
    /// DE1 holds while a group-flush cycle runs. Wire value is `°C × 10`
    /// (so 95.0 °C → raw 950). MMR `0x803844`, 4-byte. Per reaprime
    /// (`de1.models.dart:flushTemp` `readScale: 0.1`); the legacy de1app
    /// has no equivalent. docs/22 §3.2.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_flush_temp(&self, temp_c: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_flush_temp") {
            return out;
        }
        let raw = (temp_c * 10.0).round().clamp(0.0, f32::from(u16::MAX)) as u32;
        mmr_write_command(MmrRegister::FlushTemp, raw, 4)
    }

    /// Set the flush timeout. `dur` is scaled `int(10 × seconds)`.
    /// MMR `0x803848`, 2-byte.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_flush_timeout(&self, dur: Duration) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_flush_timeout") {
            return out;
        }
        // Scale ms-resolution into deciseconds (the legacy `int(10 * s)` form).
        let raw = (dur.as_millis() / 100).min(65_535) as u32;
        mmr_write_command(MmrRegister::FlushTimeout, raw, 2)
    }

    /// Enable or disable the tablet's USB charging output. MMR `0x803854`,
    /// 1-byte (`0`/`1`).
    pub fn set_usb_charger_on(&self, enabled: bool) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_usb_charger_on") {
            return out;
        }
        mmr_write_command(MmrRegister::UsbChargerOn, u32::from(enabled), 1)
    }

    /// Tell the firmware whether the user is currently present at the machine
    /// (the legacy `set_user_present`; written `1` on screen activity, `0`
    /// when the app goes to sleep). MMR `0x803860`, 1-byte.
    ///
    /// **Distinct register** from [`set_feature_flags`](Self::set_feature_flags)
    /// (`0x803858`). The legacy `de1_comms.tcl` writes to both addresses
    /// independently; they have related semantics but separate wire targets.
    pub fn set_user_present(&self, present: bool) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_user_present") {
            return out;
        }
        mmr_write_command(MmrRegister::UserPresent, u32::from(present), 1)
    }

    /// Set the firmware feature-flag bitmask (legacy `set_feature_flags`).
    /// MMR `0x803858`, 2-byte. Bit semantics are firmware-defined; the caller
    /// supplies the raw 16-bit word.
    ///
    /// **Distinct register** from [`set_user_present`](Self::set_user_present)
    /// (`0x803860`) — see that method for the rationale.
    pub fn set_feature_flags(&self, flags: u16) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_feature_flags") {
            return out;
        }
        mmr_write_command(MmrRegister::FeatureFlags, u32::from(flags), 2)
    }

    /// Override the refill-kit presence flag (legacy `set_refill_kit_present`,
    /// `0`/`1`/`2` for off / on / auto). MMR `0x80385C`, 4-byte.
    pub fn set_refill_kit_present(&self, state: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_refill_kit_present") {
            return out;
        }
        mmr_write_command(MmrRegister::RefillKit, u32::from(state), 4)
    }

    /// Set the mains heater voltage (legacy `set_heater_voltage`).
    ///
    /// `volts` **must** be `120` or `230` — any other value is rejected with
    /// [`AppError::InvalidArg`]. MMR `0x803834`, 4-byte little-endian.
    ///
    /// The wire payload is `volts + 1000` — the firmware uses the `+1000`
    /// offset as a "user-committed" marker (so `120` / `230` read back as
    /// `1120` / `1230`); a raw value of `0` means the firmware has not yet
    /// been told and is detecting from the heater dB. We never write `0`
    /// ourselves — only `1120` or `1230`. See reaprime
    /// `de1.models.dart:301 heaterV` ("Nominal Heater Voltage (0, 120V or
    /// 230V). +1000 if it's a set value.") and `docs/27` row #56.
    ///
    /// **Damaging if mis-set** — wrong voltage on the wrong mains can
    /// permanently damage the heater. The shell is responsible for the
    /// type-to-confirm modal (`MainsConfirmModal.svelte`) that the legacy
    /// app uses (`docs/14` §4.13); this clamp is the last-line guard.
    ///
    /// # Errors
    ///
    /// [`AppError::InvalidArg`] if `volts` is not in `{120, 230}`.
    pub fn set_heater_voltage(&self, volts: u8) -> Result<CoreOutput, AppError> {
        if let Some(out) = self.refuse_if_firmware_locked("set_heater_voltage") {
            return Ok(out);
        }
        if volts != 120 && volts != 230 {
            return Err(AppError::InvalidArg {
                field: "voltage".to_owned(),
                value: volts.to_string(),
                reason: "must be 120 or 230 V".to_owned(),
            });
        }
        // +1000 user-committed marker; encoded as 4-byte LE on the wire.
        let wire = u32::from(volts) + 1000;
        Ok(mmr_write_command(MmrRegister::HeaterVoltage, wire, 4))
    }

    /// Reset 8 machine settings to factory baseline (legacy reaprime
    /// `DELETE /api/v1/machine/settings/reset`).
    ///
    /// Re-applies the documented baseline for: fan threshold, hot-water
    /// idle temp, heater phase 1/2 flows, espresso warmup timeout,
    /// refill kit auto mode, flow-calibration multiplier, and steam
    /// purge mode. Every value is the post-clamp, on-the-wire baseline
    /// that reaprime ends up writing — see the
    /// `RESET_*` consts below and `docs/27-write-side-gaps.md`
    /// appendix "settings-reset baseline values".
    ///
    /// Emits 8 [`Command::WriteCharacteristic`] entries in one
    /// [`CoreOutput`]; the shell submits them serially. Order matches
    /// reaprime's `Defaults.applySettingsDefaults`
    /// (`de1_controller.defaults.dart:39-51`) but is not functionally
    /// significant — the writes are independent.
    ///
    /// No rollback on partial failure (matches reaprime). Profiles,
    /// shot history, and app preferences are untouched: this only
    /// re-writes 8 MMR registers on the DE1 itself.
    ///
    /// **Fan threshold gotcha:** reaprime's source reads `55 °C` but
    /// MMR `fanThreshold` clamps `max: 50`, so `_writeMMRInt` silently
    /// emits `50` on the wire. Crema writes `50` directly so the
    /// firmware ends up with the same value any user sees via
    /// read-back. See the appendix's "Important gotcha" note.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) —
    /// emits one [`Event::FirmwareLockoutHit`] and no commands.
    ///
    /// # Errors
    ///
    /// Infallible today; the [`Result`] mirrors
    /// [`set_heater_voltage`](Self::set_heater_voltage) for forward
    /// symmetry with future per-field validation.
    pub fn reset_machine_defaults(&self) -> Result<CoreOutput, AppError> {
        // Baselines — match reaprime/lib/src/controllers/
        // de1_controller.defaults.dart:39-51 *post-clamp*. See
        // docs/27 appendix "settings-reset baseline values" for the
        // wire encoding of every value below.
        //
        // Fan: reaprime writes 55, gets clamped to 50 by `_writeMMRInt`
        // (MMRItem.fanThreshold max=50). Crema emits 50 directly.
        const RESET_FAN_THRESHOLD_C: u32 = 50;
        // Hot-water idle temp: 95 °C, wire `°C × 10` = 950, 4-byte LE.
        const RESET_HOT_WATER_IDLE_TEMP_RAW: u32 = 950;
        // Phase-1/2 flows: 2.0 / 4.0 ml/s, wire `ml/s × 10` = 20 / 40, 4-byte LE.
        const RESET_PHASE_1_FLOW_RAW: u32 = 20;
        const RESET_PHASE_2_FLOW_RAW: u32 = 40;
        // Espresso warmup timeout: 4.0 s, wire `s × 10` = 40, 4-byte LE.
        const RESET_ESPRESSO_WARMUP_TIMEOUT_RAW: u32 = 40;
        // Refill kit: 2 = auto, 4-byte LE.
        const RESET_REFILL_KIT_RAW: u32 = 2;
        // Cal-flow-est: 1.0, wire `× 1000` = 1000, 2-byte LE.
        const RESET_CAL_FLOW_EST_RAW: u32 = 1000;
        // Steam purge mode (SteamTwoTapStop register, 0x803850): 0, 4-byte LE bool.
        const RESET_STEAM_PURGE_MODE_RAW: u32 = 0;

        if let Some(out) = self.refuse_if_firmware_locked("reset_machine_defaults") {
            return Ok(out);
        }

        // Eight independent MMR writes, in reaprime's source order. Each
        // tuple is (register, value, byte_len).
        let writes: [(MmrRegister, u32, u8); 8] = [
            (MmrRegister::FanThreshold, RESET_FAN_THRESHOLD_C, 1),
            (
                MmrRegister::HotWaterIdleTemp,
                RESET_HOT_WATER_IDLE_TEMP_RAW,
                4,
            ),
            (MmrRegister::Phase1FlowRate, RESET_PHASE_1_FLOW_RAW, 4),
            (MmrRegister::Phase2FlowRate, RESET_PHASE_2_FLOW_RAW, 4),
            (
                MmrRegister::EspressoWarmupTimeout,
                RESET_ESPRESSO_WARMUP_TIMEOUT_RAW,
                4,
            ),
            (MmrRegister::RefillKit, RESET_REFILL_KIT_RAW, 4),
            (
                MmrRegister::CalibrationFlowMultiplier,
                RESET_CAL_FLOW_EST_RAW,
                2,
            ),
            (
                MmrRegister::SteamTwoTapStop,
                RESET_STEAM_PURGE_MODE_RAW,
                4,
            ),
        ];

        let mut out = CoreOutput::default();
        for (register, value, byte_len) in writes {
            let bytes = value.to_le_bytes();
            let len = usize::from(byte_len.clamp(1, 4));
            out.commands.push(Command::WriteCharacteristic {
                target: WriteTarget::De1MmrWrite,
                data: mmr::write_request(register.address(), &bytes[..len]).to_vec(),
            });
        }
        Ok(out)
    }

    /// Set the cup-warmer plate temperature, in °C (Bengle models only).
    /// MMR `0x803874`, 2-byte. The bridge layer is responsible for gating
    /// this on the model — the firmware accepts the write on any DE1, but it
    /// is a no-op outside Bengles.
    pub fn set_cup_warmer_temperature(&self, temp_c: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_cup_warmer_temperature") {
            return out;
        }
        mmr_write_command(MmrRegister::CupWarmerTemp, u32::from(temp_c), 2)
    }

    /// Set the flow-calibration multiplier (legacy
    /// `set_calibration_flow_multiplier`). `multiplier` is scaled
    /// `int(1000 × multiplier)`. MMR `0x80383C`, 2-byte.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_calibration_flow_multiplier(&self, multiplier: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_calibration_flow_multiplier") {
            return out;
        }
        let raw = (multiplier * 1000.0).round().clamp(0.0, 65_535.0) as u32;
        mmr_write_command(MmrRegister::CalibrationFlowMultiplier, raw, 2)
    }

    /// Apply the seven-MMR-register `heater_tweaks` write (legacy
    /// `set_heater_tweaks`), pushing the writes onto a single [`CoreOutput`]
    /// in legacy order. Used at connect time to push the user's saved values.
    ///
    /// The fields mirror the legacy proc's argument list verbatim
    /// (`de1_comms.tcl:1123`). All flow / timeout values use the same scales
    /// as the dedicated setters above. The shell may also send the implied
    /// `set_flush_timeout` / `set_flush_flow_rate` / `set_hot_water_flow_rate`
    /// separately when the user changes them individually — those three are
    /// re-emitted here for parity with the legacy procedure.
    ///
    /// Refused while a firmware upload is in progress.
    #[allow(
        clippy::too_many_arguments,
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss
    )]
    pub fn set_heater_tweaks(&self, tweaks: HeaterTweaks) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_heater_tweaks") {
            return out;
        }
        // Round/clamp helpers reused from the dedicated setters: the legacy
        // proc's value scales survive here too.
        let scale10 = |v: f32| (v * 10.0).round().clamp(0.0, 65_535.0) as u32;
        let mut out = CoreOutput::default();
        for (register, value, byte_len) in [
            (MmrRegister::Phase1FlowRate, scale10(tweaks.phase_1_flow_rate), 2),
            (MmrRegister::Phase2FlowRate, scale10(tweaks.phase_2_flow_rate), 2),
            (
                MmrRegister::HotWaterIdleTemp,
                u32::from(tweaks.hot_water_idle_temp),
                1,
            ),
            (
                MmrRegister::EspressoWarmupTimeout,
                tweaks.espresso_warmup_timeout.as_secs().min(255) as u32,
                1,
            ),
            (
                MmrRegister::SteamTwoTapStop,
                u32::from(tweaks.steam_two_tap_stop),
                1,
            ),
            (
                MmrRegister::FlushTimeout,
                (tweaks.flush_timeout.as_millis() / 100).min(65_535) as u32,
                2,
            ),
            (MmrRegister::FlushFlowRate, scale10(tweaks.flush_flow_rate), 2),
            (
                MmrRegister::HotWaterFlowRate,
                scale10(tweaks.hot_water_flow_rate),
                2,
            ),
        ] {
            let bytes = value.to_le_bytes();
            out.commands.push(Command::WriteCharacteristic {
                target: WriteTarget::De1MmrWrite,
                data: mmr::write_request(register.address(), &bytes[..byte_len]).to_vec(),
            });
        }
        out
    }

    /// Build a [`Command`] that writes the DE1's water-tank refill threshold
    /// (`WaterLevels` / `cuuid_11`).
    ///
    /// `threshold_mm` is the level at or below which the DE1 should ask for a
    /// refill. The current level on the wire is hard-zeroed to match the
    /// legacy `set_tank_temperature_threshold` write — `WaterLevels` packets
    /// are read-only as far as the current level goes, so a write of `0` is
    /// the documented way to set only the threshold.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits
    /// one [`Event::FirmwareLockoutHit`] and no command.
    pub fn set_refill_threshold(&self, threshold_mm: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_refill_threshold") {
            return out;
        }
        let mut out = CoreOutput::default();
        let packet = WaterLevels {
            current_mm: 0.0,
            refill_threshold_mm: threshold_mm,
        }
        .encode();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1WaterLevels,
            data: packet.to_vec(),
        });
        out
    }

    /// The steam / hot-water [`ShotSettings`] to write, with the steam target
    /// temperature set for the given eco state. Falls back to a representative
    /// default when no settings have been supplied yet.
    ///
    /// When a scale is connected the hot-water volume is overridden to
    /// [`HOT_WATER_STOP_ON_SCALE_ML`]: the legacy app asks for far more water
    /// than wanted so the scale's weight-based stop is what cuts the pour off,
    /// since a weight stop is more accurate than the DE1's volume estimate
    /// (`binary.tcl` `return_de1_packed_steam_hotwater_settings`). The override
    /// is applied here, on the wire packet, rather than to the stored user
    /// settings, so it lifts as soon as the scale disconnects.
    fn steam_settings_for_eco(&self, eco: bool) -> ShotSettings {
        let mut settings = self.steam_hotwater_settings.clone().unwrap_or_default();
        if eco {
            settings.steam_temp_c = self.steam_eco_temp;
        }
        if self.scale.is_some() {
            settings.hot_water_volume_ml = HOT_WATER_STOP_ON_SCALE_ML;
        }
        settings
    }

    /// Build a [`Command`] that tares the connected scale. Empty if no scale is
    /// connected or the scale does not support tare.
    pub fn tare_scale(&mut self) -> CoreOutput {
        let mut out = CoreOutput::default();
        self.push_tare_command(&mut out);
        out
    }

    /// Append a tare-scale [`Command`] to `out`, if a scale is connected and
    /// supports tare. Shared by [`tare_scale`](Self::tare_scale) and the
    /// automatic tare at shot start.
    fn push_tare_command(&mut self, out: &mut CoreOutput) {
        if let Some(scale) = &mut self.scale
            && let Some(data) = scale.tare()
        {
            out.commands.push(Command::WriteScale { data });
        }
    }

    /// Build a [`Command`] that starts the connected scale's built-in timer.
    ///
    /// Capability-gated: emitted only when the connected scale supports
    /// software timer commands (the Bookoo today; weight-only scales and
    /// timer-less scales like the Acaia get an empty [`CoreOutput`]).
    /// Refused while a firmware upload is in progress.
    pub fn start_timer(&self) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("start_timer") {
            return out;
        }
        let mut out = CoreOutput::default();
        Self::push_timer_command(&self.scale, TimerCommand::Start, &mut out);
        out
    }

    /// Build a [`Command`] that stops the connected scale's built-in timer.
    ///
    /// Capability-gated like [`start_timer`](Self::start_timer); refused
    /// while a firmware upload is in progress.
    pub fn stop_timer(&self) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("stop_timer") {
            return out;
        }
        let mut out = CoreOutput::default();
        Self::push_timer_command(&self.scale, TimerCommand::Stop, &mut out);
        out
    }

    /// Build a [`Command`] that resets the connected scale's built-in timer
    /// to zero.
    ///
    /// Capability-gated like [`start_timer`](Self::start_timer); refused
    /// while a firmware upload is in progress.
    pub fn reset_timer(&self) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("reset_timer") {
            return out;
        }
        let mut out = CoreOutput::default();
        Self::push_timer_command(&self.scale, TimerCommand::Reset, &mut out);
        out
    }

    /// Internal: append a scale-timer [`Command`] to `out` if a scale is
    /// connected and supports the given timer command.
    ///
    /// Shared by the three public timer methods and the auto-policy in
    /// [`map_shot_event`](Self::map_shot_event). Returns silently when the
    /// scale is `None` or its codec returns `None` for the command.
    fn push_timer_command(
        scale: &Option<Scale>,
        command: TimerCommand,
        out: &mut CoreOutput,
    ) {
        if let Some(scale) = scale
            && let Some(data) = scale.timer(command)
        {
            out.commands.push(Command::WriteScale { data });
        }
    }

    /// Build a [`Command`] that enables the connected scale's on-scale LCD,
    /// in the unit the user has chosen.
    ///
    /// Decent-Scale-only: the LCD enable/disable surface is unique to the
    /// Decent Scale, so the command is emitted only when
    /// [`Scale::is_decent_scale`] is `true`. Every other scale — and an
    /// unconnected core — returns an empty [`CoreOutput`].
    ///
    /// `unit` picks between the two wire packets — [`decent_scale::LCD_ENABLE_GRAMS`]
    /// for [`WeightUnit::Grams`] and [`decent_scale::LCD_ENABLE_OUNCES`] for
    /// [`WeightUnit::Ounces`]. Both packets set the "send heartbeat" flag,
    /// so callers must follow up with periodic
    /// [`decent_scale_heartbeat`](Self::decent_scale_heartbeat) writes —
    /// the shell schedules the clock; the core is sans-IO. The reactive
    /// auto-policy in [`handle_state`](Self::handle_state) wires this to
    /// the DE1's [`MachineState::Idle`] transition, using the cached
    /// [`weight_unit_pref`](Self) so an Idle entry picks the right variant.
    pub fn enable_decent_scale_lcd(&self, unit: WeightUnit) -> CoreOutput {
        let mut out = CoreOutput::default();
        Self::push_decent_scale_write(&self.scale, &Self::lcd_enable_bytes(unit), &mut out);
        out
    }

    /// Pick the wire packet for [`enable_decent_scale_lcd`] based on the
    /// user's chosen weight unit.
    fn lcd_enable_bytes(unit: WeightUnit) -> [u8; 7] {
        match unit {
            WeightUnit::Grams => decent_scale::LCD_ENABLE_GRAMS,
            WeightUnit::Ounces => decent_scale::LCD_ENABLE_OUNCES,
        }
    }

    /// Build a [`Command`] that disables the connected scale's on-scale LCD.
    ///
    /// Decent-Scale-only, mirroring [`enable_decent_scale_lcd`](Self::enable_decent_scale_lcd).
    /// The reactive auto-policy fires this on the DE1's
    /// [`MachineState::Sleep`] transition.
    pub fn disable_decent_scale_lcd(&self) -> CoreOutput {
        let mut out = CoreOutput::default();
        Self::push_decent_scale_write(&self.scale, &decent_scale::LCD_DISABLE, &mut out);
        out
    }

    /// Build a [`Command`] that emits one heartbeat write to a connected
    /// Decent Scale.
    ///
    /// The Decent Scale's LCD goes back to sleep after a few seconds of
    /// silence; the host has to send [`decent_scale::HEARTBEAT`] roughly every
    /// [`decent_scale::HEARTBEAT_INTERVAL_MS`] ms to keep the display awake.
    /// The shell schedules the clock — the core is sans-IO and only builds
    /// the one-shot command on demand.
    ///
    /// Decent-Scale-only; empty for every other scale.
    pub fn decent_scale_heartbeat(&self) -> CoreOutput {
        let mut out = CoreOutput::default();
        Self::push_decent_scale_write(&self.scale, &decent_scale::HEARTBEAT, &mut out);
        out
    }

    /// Build a [`Command`] that powers off the connected Decent Scale.
    ///
    /// Returns [`AppError::UnsupportedOnHardware`] when the connected
    /// scale's firmware version is not yet known or is v1.0 / v1.1 — the
    /// [`decent_scale::POWER_OFF`] byte is silently ignored by anything
    /// before v1.2 firmware, so the UI must know to fall back to the
    /// "long-press the physical button" instruction instead of sending a
    /// no-op. Returns the same error for a non-Decent scale or no scale
    /// connected at all, mirroring [`enable_decent_scale_lcd`]'s gating.
    ///
    /// The byte sequence comes from the legacy app
    /// (`de1plus/bluetooth.tcl:1289`); the firmware-version gate is
    /// derived from the Decent Scale protocol docs cited there.
    pub fn power_off_decent_scale(&self) -> Result<CoreOutput, AppError> {
        let Some(scale) = self.scale.as_ref() else {
            return Err(AppError::UnsupportedOnHardware {
                feature: "decent_scale_power_off".to_owned(),
                reason: "no scale is connected".to_owned(),
            });
        };
        if !scale.is_decent_scale() {
            return Err(AppError::UnsupportedOnHardware {
                feature: "decent_scale_power_off".to_owned(),
                reason: "connected scale is not a Decent Scale".to_owned(),
            });
        }
        if !scale.supports_decent_scale_power_off() {
            let reason = match scale.decent_scale_firmware_version() {
                Some(DecentScaleFirmwareVersion::V1_0 | DecentScaleFirmwareVersion::V1_1) => {
                    "Decent Scale firmware < v1.2 ignores remote power-off; long-press \
                     the physical button instead"
                        .to_owned()
                }
                Some(DecentScaleFirmwareVersion::Unknown) | None => {
                    "Decent Scale firmware version not yet known; the remote power-off \
                     byte is only safe on v1.2+"
                        .to_owned()
                }
                Some(DecentScaleFirmwareVersion::V1_2) => {
                    // Unreachable: V1_2 returns true from supports_power_off.
                    "internal inconsistency in supports_decent_scale_power_off".to_owned()
                }
            };
            return Err(AppError::UnsupportedOnHardware {
                feature: "decent_scale_power_off".to_owned(),
                reason,
            });
        }
        let mut out = CoreOutput::default();
        Self::push_decent_scale_write(&self.scale, &decent_scale::POWER_OFF, &mut out);
        Ok(out)
    }

    /// Cache the user's chosen weight unit for the LCD-enable auto-policy.
    ///
    /// The shell calls this on every settings change to `weightUnit`. The
    /// core is sans-IO and never reaches into the settings store; this
    /// setter is the one-way write-through that keeps the LCD-enable
    /// auto-policy (in [`handle_state`](Self::handle_state)) in sync with
    /// what the shell is showing.
    ///
    /// Note: this only updates the cache; it does *not* re-emit an
    /// LCD-enable write. The shell decides whether to re-emit after a
    /// pref change — call [`enable_decent_scale_lcd`] with the new unit
    /// if the on-scale display should switch immediately.
    pub fn set_weight_unit_pref(&mut self, unit: WeightUnit) {
        self.weight_unit_pref = unit;
    }

    /// The cached weight-unit pref — what the LCD-enable auto-policy uses.
    pub fn weight_unit_pref(&self) -> WeightUnit {
        self.weight_unit_pref
    }

    /// Internal: append a fixed Decent-Scale write to `out` if the connected
    /// scale is a Decent Scale.
    ///
    /// Shared by every Decent-Scale public method and by the reactive
    /// auto-policy in [`handle_state`](Self::handle_state). Returns silently
    /// when no scale is connected or the connected scale is anything else.
    ///
    /// Single write per command. Legacy de1app double-sends as a paranoia
    /// workaround for v1.0 firmware buffer drops
    /// (`de1plus/bluetooth.tcl:1327-1330`, repeated for every LCD / timer
    /// / tare proc); reaprime does not (uses a data-flow watchdog
    /// mitigation instead — see `reaprime/.../decent_scale/scale.dart`).
    /// Per the project's "defer to reaprime on inconsistent missing
    /// capabilities" rule, we trust the modern BLE stack to deliver
    /// writes reliably. If real-hardware testing shows drops, the
    /// reaprime-style watchdog retry is the right addition — not blind
    /// double-send.
    fn push_decent_scale_write(scale: &Option<Scale>, bytes: &[u8; 7], out: &mut CoreOutput) {
        if let Some(scale) = scale
            && scale.is_decent_scale()
        {
            out.commands.push(Command::WriteScale {
                data: bytes.to_vec(),
            });
        }
    }

    /// Append a settings-query [`Command`] (`bookoo::QUERY_SETTINGS`, command
    /// `0x0f`) to `out`. The scale answers with a `03 0e …` notification.
    ///
    /// Called after a config write so the query reflects the post-change
    /// state; the caller has already established that the connected scale
    /// accepts the config command, so the query is unconditionally appended.
    fn push_query_settings_command(out: &mut CoreOutput) {
        out.commands.push(Command::WriteScale {
            data: bookoo::QUERY_SETTINGS.to_vec(),
        });
    }

    /// Append a serial-query [`Command`] (`bookoo::QUERY_SERIAL`, command
    /// `0x0a`) to `out`. The scale answers with a `03 0c …` notification on its
    /// command characteristic, carrying the live anti-mistouch state.
    fn push_query_serial_command(out: &mut CoreOutput) {
        out.commands.push(Command::WriteScale {
            data: bookoo::QUERY_SERIAL.to_vec(),
        });
    }

    /// Emit the one-shot connect-time scale-config queries the first time a
    /// capability-bearing scale reports weight.
    ///
    /// There is no dedicated scale-connected notification, so the queries ride
    /// on the first weight notification: the `0x0a` serial query (anti-mistouch
    /// state) and the `0x0f` settings query (active / enabled modes) are pushed
    /// once, so the shell's anti-mistouch toggle and mode selector show live
    /// state immediately. Capability-gated like
    /// [`query_scale_settings`](Self::query_scale_settings) — a weight-only
    /// scale (no `volume` capability) issues nothing — and guarded by
    /// `scale_config_queried` so it fires exactly once per connection.
    fn push_connect_queries_once(&mut self, out: &mut CoreOutput) {
        if self.scale_config_queried {
            return;
        }
        if let Some(scale) = &self.scale
            && scale.capabilities().volume.is_some()
        {
            Self::push_query_serial_command(out);
            Self::push_query_settings_command(out);
        }
        // Mark the one-shot done even for a weight-only scale, so the
        // capability check is not repeated on every weight notification.
        self.scale_config_queried = true;
    }

    /// Build a [`Command`] that queries the connected scale's settings
    /// (`bookoo::QUERY_SETTINGS`, command `0x0f`).
    ///
    /// Capability-gated, not device-gated: the query is emitted only when the
    /// connected scale exposes a configurable setting (its
    /// [`ScaleCapabilities::volume`] is `Some` — the same gate the config
    /// methods use). The result is empty when no scale is connected or the
    /// scale is weight-only. The scale answers with a `03 0e …` notification.
    pub fn query_scale_settings(&self) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && scale.capabilities().volume.is_some()
        {
            Self::push_query_settings_command(&mut out);
        }
        out
    }

    /// Build a [`Command`] that sets the connected scale's beeper volume.
    ///
    /// `level` is the requested volume step; it is clamped to the connected
    /// scale's [`ScaleCapabilities::volume`] `[min, max]` bounds before the
    /// command is built. Capability-gated, not device-gated: the command is
    /// emitted only when the connected scale exposes a settable volume (its
    /// `volume` capability is `Some`). The result is empty when no scale is
    /// connected or the scale cannot set its volume. Modelled on
    /// [`tare_scale`](Self::tare_scale).
    pub fn set_scale_volume(&mut self, level: u8) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && let Some(range) = scale.capabilities().volume
        {
            let level = level.clamp(range.min, range.max);
            out.commands.push(Command::WriteScale {
                data: bookoo::set_volume(level).to_vec(),
            });
            Self::push_query_settings_command(&mut out);
        }
        out
    }

    /// Build a [`Command`] that sets the connected scale's auto-standby
    /// timeout, in minutes.
    ///
    /// `minutes` is clamped to the connected scale's
    /// [`ScaleCapabilities::standby`] `[min, max]` bounds before the
    /// command is built. Capability-gated, not device-gated: the command is
    /// emitted only when the connected scale exposes a configurable
    /// auto-standby (its `standby` capability is `Some`). The result is
    /// empty otherwise. Modelled on [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_standby(&mut self, minutes: u8) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && let Some(range) = scale.capabilities().standby
        {
            let minutes = minutes.clamp(range.min, range.max);
            out.commands.push(Command::WriteScale {
                data: bookoo::set_standby_minutes(minutes).to_vec(),
            });
            Self::push_query_settings_command(&mut out);
        }
        out
    }

    /// Build a [`Command`] that enables or disables the connected scale's
    /// flow smoothing.
    ///
    /// Capability-gated, not device-gated: the command is emitted only when
    /// the connected scale's [`ScaleCapabilities::flow_smoothing`] is `true`.
    /// The result is empty otherwise. Modelled on
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_flow_smoothing(&mut self, enabled: bool) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && scale.capabilities().flow_smoothing
        {
            out.commands.push(Command::WriteScale {
                data: bookoo::set_flow_smoothing(enabled).to_vec(),
            });
            Self::push_query_settings_command(&mut out);
        }
        out
    }

    /// Build a [`Command`] that enables or disables the connected scale's
    /// anti-mistouch.
    ///
    /// Capability-gated, not device-gated: the command is emitted only when
    /// the connected scale's [`ScaleCapabilities::anti_mistouch`] is `true`.
    /// The result is empty otherwise. Modelled on
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_anti_mistouch(&mut self, enabled: bool) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && scale.capabilities().anti_mistouch
        {
            out.commands.push(Command::WriteScale {
                data: bookoo::set_anti_mistouch(enabled).to_vec(),
            });
            Self::push_query_settings_command(&mut out);
        }
        out
    }

    /// Build the [`Command`]s that switch the connected scale to display mode
    /// `mode_id`.
    ///
    /// Capability-gated, not device-gated: the commands are emitted only when
    /// the connected scale exposes switchable modes (its
    /// [`ScaleCapabilities::modes`] is non-empty) **and** `mode_id` is one of
    /// the listed modes. The result is empty otherwise.
    ///
    /// Selecting a mode is three writes — the target mode is enabled first,
    /// then the other two are disabled, so a mode is always enabled mid-
    /// sequence (see [`bookoo::select_mode`]). All three are pushed onto
    /// `CoreOutput::commands` **in order**; the shell must perform them in that
    /// order.
    pub fn set_scale_mode(&mut self, mode_id: u8) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && scale.capabilities().modes.iter().any(|m| m.id == mode_id)
            && let Some(mode) = bookoo_mode_from_id(mode_id)
        {
            for command in bookoo::select_mode(mode) {
                out.commands.push(Command::WriteScale {
                    data: command.to_vec(),
                });
            }
            Self::push_query_settings_command(&mut out);
        }
        out
    }

    /// Build a [`Command`] that selects the connected scale's auto-stop mode.
    ///
    /// `mode_id` is the wire value of the desired mode (`0` = flow-stop,
    /// `1` = cup-removal). Capability-gated, not device-gated: the command is
    /// emitted only when the connected scale's [`ScaleCapabilities::auto_stop`]
    /// is `true` **and** `mode_id` maps to a known [`bookoo::AutoStopMode`].
    /// An unconnected scale, a scale without the capability, or an
    /// out-of-range `mode_id` all yield an empty [`CoreOutput`]. Modelled on
    /// [`set_scale_mode`](Self::set_scale_mode).
    pub fn set_scale_auto_stop(&mut self, mode_id: u8) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && scale.capabilities().auto_stop
            && let Some(mode) = bookoo_auto_stop_from_id(mode_id)
        {
            out.commands.push(Command::WriteScale {
                data: bookoo::set_auto_stop_mode(mode).to_vec(),
            });
            Self::push_query_settings_command(&mut out);
        }
        out
    }

    /// Feed a raw GATT notification: `data` is the characteristic's bytes and
    /// `now_ms` a monotonic millisecond timestamp from the shell.
    ///
    /// `now_ms` stays `u64` at the FFI surface — that is the type the JS /
    /// Kotlin shells naturally pass — and is converted to a [`Duration`] once
    /// here, then handed to the internal handlers and monitors that all speak
    /// `Duration`.
    pub fn on_notification(&mut self, source: Source, data: &[u8], now_ms: u64) -> CoreOutput {
        // Capture side-effect first — the recorder needs the raw notification
        // bytes regardless of whether the decode that follows succeeds, and
        // running it here (rather than at each shell's BLE-manager layer)
        // means the recorder lives in one place and rides the same wasm
        // boundary crossing as the decode. Suppressed during a capture
        // replay so a replay doesn't re-record itself; see
        // [`set_replay_mode`](Self::set_replay_mode).
        self.capture.record(source, data, now_ms);
        let now = ms_to_duration(now_ms);
        let mut out = CoreOutput::default();
        match source {
            Source::De1State => self.handle_state(data, now, &mut out),
            Source::De1ShotSample => self.handle_sample(data, now, &mut out),
            Source::ScaleWeight => self.handle_scale_weight(data, now, &mut out),
            Source::ScaleCommand => self.handle_scale_command(data, &mut out),
            Source::De1WaterLevels => self.handle_water_levels(data, &mut out),
            Source::De1Version => self.handle_version(data, &mut out),
            Source::De1MmrRead => self.handle_mmr_read(data, &mut out),
            Source::De1Calibration => Self::handle_calibration(data, &mut out),
            Source::De1ShotSettings => self.handle_shot_settings_read(data, &mut out),
            Source::De1ProfileHeader => self.handle_profile_header_read(data, &mut out),
            Source::De1FrameAck => self.handle_profile_frame_ack(data, now, &mut out),
        }
        out
    }

    /// Feed a periodic clock tick. Drives the lost-scale watchdog (if a scale
    /// is connected but has not reported weight for [`SCALE_STALE_TIMEOUT`],
    /// emit [`Event::ScaleStale`] once per stale episode) and the steam
    /// eco-mode transition.
    pub fn on_tick(&mut self, now_ms: u64) -> CoreOutput {
        let now = ms_to_duration(now_ms);
        let mut out = CoreOutput::default();
        if self.scale.is_some()
            && !self.scale_stale_reported
            && let Some(last) = self.last_scale_weight
            && now.saturating_sub(last) >= SCALE_STALE_TIMEOUT
        {
            self.scale_stale_reported = true;
            out.events.push(Event::ScaleStale);
        }
        for event in self.steam.on_tick(now) {
            self.map_steam_event(event, &mut out);
        }
        // Profile-upload ack timeout — see PROFILE_UPLOAD_ACK_TIMEOUT.
        if let Some(upload) = &self.profile_upload
            && now.saturating_sub(upload.last_progress) >= PROFILE_UPLOAD_ACK_TIMEOUT
        {
            let awaiting = upload.expected_acks[upload.next_idx];
            self.profile_upload = None;
            out.events.push(Event::ProfileUploadFailed {
                reason: ProfileUploadFailure::AckTimeout { awaiting },
            });
        }
        out
    }

    /// Slice the rolling BLE-capture buffer to JSONL covering `[from_ms,
    /// to_ms]`, with the connect-phase identity entries (latest DE1_VERSION /
    /// DE1_STATE / DE1_SHOT_SETTINGS / per-register DE1_MMR_READ + first
    /// SCALE_WEIGHT) prepended ahead of the window so a replay can decode
    /// the bytes that follow, and one META prelude line carrying the scale's
    /// advertised name + DE1 firmware / model / serial when each is known.
    ///
    /// The wire format matches the Android `BleSessionRecorder`, the Rust
    /// `examples/replay.rs` tool, and the web `lib/replay/capture` parser
    /// byte-for-byte: one
    /// `{"t":<ms>,"dir":"in","src":"<NAME>","hex":"<lower-hex>"}` object per
    /// line, with an optional `{"src":"META","meta":{…}}` first line. Empty
    /// when the recorder is empty.
    pub fn capture_slice_jsonl(&self, from_ms: u64, to_ms: u64) -> String {
        slice_to_jsonl(&self.capture, &self.meta_snapshot(), from_ms, to_ms)
    }

    /// Drop every captured entry — used by the shell on BLE disconnect, where
    /// the live session is gone but the rest of the core (settings, history,
    /// the active profile, …) must stay intact. Distinct from
    /// [`reset`](Self::reset), which wipes everything.
    pub fn capture_clear(&mut self) {
        self.capture.clear();
    }

    /// Silence the rolling capture buffer (during a replay, when the shell
    /// is feeding the core its own recorded notifications and re-recording
    /// them would double-write IndexedDB on the next ShotCompleted). The
    /// shell wraps `replayCapture` with `set_replay_mode(true)` /
    /// `set_replay_mode(false)` in a try/finally.
    pub fn set_replay_mode(&mut self, on: bool) {
        self.capture.set_suppressed(on);
    }

    /// Build the [`MetaSnapshot`] handed to the capture slicer. The fields
    /// are pulled from the live state the decoders maintain — same source
    /// of truth the shell's `de1MachineInfo` snapshot would read.
    fn meta_snapshot(&self) -> MetaSnapshot {
        MetaSnapshot {
            scale_name: self.last_scale_advertised_name.clone(),
            de1_firmware_version: self.last_firmware_build.map(u32::from),
            de1_machine_model: self.last_machine_model,
            de1_serial_number: self.last_serial_number,
        }
    }

    /// Discard all session state — e.g. on disconnect. The connected scale and
    /// armed auto-stop targets are cleared too, and the capture rolling
    /// buffer is wiped (a fresh [`CaptureRecorder`] is part of
    /// [`CremaCore::new`]).
    pub fn reset(&mut self) {
        *self = CremaCore::new();
    }

    /// Decode and process a `StateInfo` notification.
    fn handle_state(&mut self, data: &[u8], now: Duration, out: &mut CoreOutput) {
        let info = match StateInfo::parse(data) {
            Ok(info) => info,
            Err(e) => {
                out.events.push(Event::DecodeError {
                    message: e.to_string(),
                });
                return;
            }
        };
        if self.last_state != Some(info) {
            let prev_state = self.last_state.map(|s| s.state);
            self.last_state = Some(info);
            out.events.push(Event::MachineStateChanged {
                state: info.state,
                substate: info.substate,
            });
            // Reactive Decent-Scale LCD auto-policy: enable the on-scale LCD
            // when the DE1 enters Idle (wake-with-DE1) and disable it when the
            // DE1 enters Sleep (sleep-with-DE1). Both writes are gated by
            // `push_decent_scale_write` on the connected scale being a Decent
            // Scale, so every other scale stays silent. We fire on *entry*
            // into the state — not on every tick — by checking the previous
            // top-level state, mirroring the audit §7.10/§7.11 design.
            if prev_state != Some(info.state) {
                match info.state {
                    // The LCD-enable variant follows the user's chosen
                    // weight unit, cached on the core by the shell via
                    // `set_weight_unit_pref` — so an Idle entry picks
                    // grams or ounces to match what the shell is showing.
                    MachineState::Idle => Self::push_decent_scale_write(
                        &self.scale,
                        &Self::lcd_enable_bytes(self.weight_unit_pref),
                        out,
                    ),
                    MachineState::Sleep => Self::push_decent_scale_write(
                        &self.scale,
                        &decent_scale::LCD_DISABLE,
                        out,
                    ),
                    _ => {}
                }
            }
        }
        for event in self.monitor.on_state_info(info, now) {
            self.map_shot_event(event, now, out);
        }
        for event in self.water.on_state_info(info, now) {
            Self::map_water_event(event, out);
        }
        for event in self.steam.on_state_info(info, now) {
            self.map_steam_event(event, out);
        }
    }

    /// Decode and process a `ShotSample` telemetry notification.
    fn handle_sample(&mut self, data: &[u8], now: Duration, out: &mut CoreOutput) {
        let sample = match ShotSample::parse(data) {
            Ok(sample) => sample,
            Err(e) => {
                out.events.push(Event::DecodeError {
                    message: e.to_string(),
                });
                return;
            }
        };
        for event in self.monitor.on_sample(&sample, now) {
            self.map_shot_event(event, now, out);
        }
        // Record steam telemetry while a steam session is in progress.
        self.steam.on_sample(&sample, now);
        // Volume integration: observe the line-frequency detector first
        // (its lock is the input to the integrator's dt source), then
        // integrate the sample's flow into the running dispensed_ml.
        // Auto-detect runs even while a user override is set — cheap, and
        // surfaces the detector's view alongside the override for
        // diagnostics.
        self.line_freq_detector.observe(sample.sample_time, now);
        self.volume_integrator
            .integrate(&sample, now, self.line_frequency_hz());
        let dispensed_ml = self.volume_integrator.dispensed_ml();
        let reason = self
            .auto_stop
            .as_mut()
            .and_then(|stop| stop.on_sample(&sample, now, dispensed_ml));
        Self::push_stop(reason, out);
        // `Event::Telemetry.elapsed` is `u32` milliseconds — what the FFI
        // surface carries — so convert the `Duration` once at this boundary.
        let elapsed = duration_to_u32_ms(
            self.shot_started
                .map_or(Duration::ZERO, |start| now.saturating_sub(start)),
        );
        // Update the in-progress shot's running peaks. Only contributes
        // while a shot is in progress — the accumulator is reset on
        // `ShotStarted` and drained on `ShotCompleted`, so any telemetry
        // observed outside a shot is harmless (the next reset wipes it).
        if self.shot_started.is_some() {
            self.shot_metrics
                .feed_telemetry(sample.group_pressure, sample.head_temp);
        }
        out.events.push(Event::Telemetry {
            elapsed,
            group_pressure: sample.group_pressure,
            group_flow: sample.group_flow,
            head_temp: sample.head_temp,
            mix_temp: sample.mix_temp,
            steam_temp: sample.steam_temp,
            dispensed_volume: self.volume_integrator.dispensed_ml(),
            set_head_temp: sample.set_head_temp,
            set_group_pressure: sample.set_group_pressure,
            set_group_flow: sample.set_group_flow,
            resistance: puck_resistance(sample.group_pressure, sample.group_flow),
        });
    }

    /// Decode and process a scale weight notification.
    fn handle_scale_weight(&mut self, data: &[u8], now: Duration, out: &mut CoreOutput) {
        let Some(scale) = &mut self.scale else {
            return;
        };
        let Some(reading) = scale.parse_reading(data) else {
            // Not a weight packet — for the Decent Scale, this might be a
            // `0x0A` LCD / heartbeat reply carrying the firmware-version
            // sentinel. The legacy app extracts the firmware version from
            // the same frame (`de1plus/bluetooth.tcl:2738-2749`); we
            // mirror that here so a Decent Scale's `supports_power_off`
            // capability becomes accurate shortly after the first
            // LCD-enable write. A non-Decent scale (or any frame that
            // isn't a `0x0A` reply) silently returns from this path.
            if let Some(decent_scale::CommandResponse::LcdAck {
                firmware_version, ..
            }) = scale.parse_decent_scale_command_response(data)
            {
                scale.record_decent_scale_firmware_version(firmware_version);
            }
            return;
        };
        // A fresh reading re-arms the lost-scale watchdog.
        self.last_scale_weight = Some(now);
        self.scale_stale_reported = false;
        // The first weight notification stands in for a scale-connected hook:
        // fire the one-shot serial / settings queries so the anti-mistouch
        // state and active mode are fetched as soon as the scale is reporting.
        self.push_connect_queries_once(out);
        let estimate = self.flow.update(reading.weight_g, now);
        // Update the in-progress shot's running peak / final weight from
        // the smoothed scale weight — only while a shot is in progress so
        // post-shot trickle readings (cup-removal etc.) don't pollute the
        // next shot's metrics. The smoothed `estimate.weight` is the
        // value the shell sees on its TelemetrySample.weight channel, so
        // peak / final stay aligned with what the chart actually drew.
        if self.shot_started.is_some() {
            self.shot_metrics.feed_weight(estimate.weight);
        }
        out.events.push(Event::ScaleReading {
            weight: estimate.weight,
            flow: estimate.flow,
            device_flow: reading.flow_g_per_s,
            device_timer: reading.timer_ms,
            device_volume: reading.volume,
            device_standby: reading.standby_minutes,
            device_battery: reading.battery_percent,
            device_flow_smoothing: reading.flow_smoothing,
            device_auto_stop: reading.auto_stop,
        });
        let reason = self
            .auto_stop
            .as_mut()
            .and_then(|stop| stop.on_weight(reading.weight_g, now));
        Self::push_stop(reason, out);
    }

    /// Decode and process a notification from the scale's *command*
    /// characteristic (the Bookoo's `ff12` channel).
    ///
    /// The scale pushes its serial / settings responses back on the channel
    /// commands are written to; this decodes one such frame into an
    /// [`Event::ScaleConfig`]. A frame that is not a recognised command
    /// response — including every frame from a scale that models no command
    /// channel — is silently dropped (it is not a decode failure, just traffic
    /// the core does not model).
    fn handle_scale_command(&mut self, data: &[u8], out: &mut CoreOutput) {
        let Some(scale) = &self.scale else {
            return;
        };
        let Some(response) = scale.parse_command_response(data) else {
            return;
        };
        let event = match response {
            bookoo::CommandResponse::Serial {
                firmware_version,
                serial,
                anti_mistouch,
            } => Event::ScaleConfig {
                anti_mistouch: Some(anti_mistouch),
                active_mode: None,
                enabled_modes: None,
                serial: Some(serial),
                firmware_version: Some(firmware_version),
            },
            bookoo::CommandResponse::Settings {
                active_mode,
                enabled_modes,
            } => Event::ScaleConfig {
                anti_mistouch: None,
                active_mode: Some(active_mode),
                enabled_modes: Some(enabled_modes),
                serial: None,
                firmware_version: None,
            },
        };
        out.events.push(event);
    }

    /// Decode and process a `WaterLevels` notification, applying the legacy
    /// +5 mm sensor correction to the reported tank level.
    fn handle_water_levels(&self, data: &[u8], out: &mut CoreOutput) {
        match WaterLevels::decode(data) {
            Ok(levels) => out.events.push(Event::WaterLevel {
                level: levels.current_mm + WATER_LEVEL_MM_CORRECTION,
                refill_threshold: levels.refill_threshold_mm,
            }),
            Err(e) => out.events.push(Event::DecodeError {
                message: e.to_string(),
            }),
        }
    }

    /// Decode and process a `Version` notification — the DE1's BLE-interface
    /// and CPU-board firmware versions. Caches the decoded value on
    /// `self.last_firmware` so
    /// [`firmware_update_status`](Self::firmware_update_status) can compare it
    /// against [`LATEST_KNOWN_FIRMWARE`](firmware_info::LATEST_KNOWN_FIRMWARE).
    fn handle_version(&mut self, data: &[u8], out: &mut CoreOutput) {
        match Version::decode(data) {
            Ok(version) => {
                self.last_firmware = Some(version);
                out.events.push(Event::Firmware {
                    release: version.ble.release,
                    commits: version.ble.commits,
                    api_version: version.ble.api_version,
                    firmware_string: version.firmware_string(),
                });
            }
            Err(e) => out.events.push(Event::DecodeError {
                message: e.to_string(),
            }),
        }
    }

    /// Compare the most recently observed DE1 firmware build number against
    /// the latest build Crema was compiled against
    /// ([`LATEST_KNOWN_FIRMWARE_BUILD`](firmware_info::LATEST_KNOWN_FIRMWARE_BUILD)).
    ///
    /// The build number comes from MMR register `0x800010`
    /// ([`MmrRegister::FirmwareVersion`]) — the canonical "v1352" build number
    /// the legacy de1app's check uses (`vars.tcl:3787-3797`). The shell must
    /// have issued [`read_mmr(FirmwareVersion)`](Self::read_mmr) and routed
    /// the reply back through `on_notification` for this to return a
    /// comparison; otherwise it returns [`FirmwareUpdateStatus::Unknown`].
    ///
    /// The check is a pure read against cached state — no BLE traffic, no
    /// network.
    pub fn firmware_update_status(&self) -> FirmwareUpdateStatus {
        firmware_info::compare(self.last_firmware_build)
    }

    // ── Profile upload (write side) ───────────────────────────────────────
    //
    // Implements the design in `docs/16-profile-upload-plan.md`. The shape
    // is: `upload_profile` validates the profile, returns one `CoreOutput`
    // carrying every BLE write in upload order (header + N frames + M
    // extensions + tail), and arms the ack matcher. Each `Source::De1FrameAck`
    // notification advances the matcher one step; the tail ack emits
    // `ProfileUploadCompleted` and records the title as the active profile.

    /// Start uploading `profile` to the DE1.
    ///
    /// On success returns a `CoreOutput` whose `commands` are the full upload
    /// sequence — `WriteCharacteristic { De1ProfileHeader, … }` followed by
    /// one `WriteCharacteristic { De1ProfileFrame, … }` per normal frame, per
    /// extension frame, and finally the tail. The accompanying `events`
    /// carries one [`Event::ProfileUploadStarted`].
    ///
    /// If an upload is already in flight when this method is called, the
    /// prior upload is aborted (emitting
    /// [`ProfileUploadFailure::Aborted`]) and the new one starts cleanly.
    ///
    /// `now` stamps the upload start so the ack-timeout watchdog can fire
    /// from [`on_tick`](Self::on_tick) after [`PROFILE_UPLOAD_ACK_TIMEOUT`].
    ///
    /// # Errors
    ///
    /// [`AppError::ProfileUpload`] wrapping a
    /// [`DomainError`](de1_domain::DomainError) — `NoSteps` or
    /// `TooManySteps` — if the profile fails validation. The wire stays
    /// quiet on a rejected profile.
    pub fn upload_profile(
        &mut self,
        profile: &Profile,
        now: Duration,
    ) -> Result<CoreOutput, AppError> {
        let assembled = profile.assemble()?;
        let mut out = CoreOutput::default();

        // Abort any prior upload before arming the new one.
        if let Some(prior) = self.profile_upload.take() {
            let _ = prior; // value only matters for clearing the slot
            out.events.push(Event::ProfileUploadFailed {
                reason: ProfileUploadFailure::Aborted,
            });
        }

        // Build the expected-ack sequence: normal frames in order, then
        // extension frames (each carrying its step index + 32 on the wire),
        // then the tail (whose FrameToWrite == frame_count). The header is
        // not acked separately.
        let frame_count = assembled.header.frame_count;
        let extension_count =
            u8::try_from(assembled.extension_frames.len()).unwrap_or(u8::MAX);
        let mut expected_acks =
            Vec::with_capacity(usize::from(frame_count) + usize::from(extension_count) + 1);
        for i in 0..frame_count {
            expected_acks.push(i);
        }
        for ext in &assembled.extension_frames {
            expected_acks.push(ext.index.wrapping_add(EXTENSION_FRAME_INDEX_OFFSET));
        }
        expected_acks.push(assembled.tail.frame_count);

        // Emit the BLE writes in upload order: header, then every
        // `frame_packets()` entry (frames → extensions → tail).
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1ProfileHeader,
            data: assembled.header_packet().to_vec(),
        });
        for packet in assembled.frame_packets() {
            out.commands.push(Command::WriteCharacteristic {
                target: WriteTarget::De1ProfileFrame,
                data: packet.to_vec(),
            });
        }

        out.events.push(Event::ProfileUploadStarted {
            title: profile.title.clone(),
            frame_count,
            extension_frame_count: extension_count,
        });

        self.profile_upload = Some(ProfileUpload {
            title: profile.title.clone(),
            expected_acks,
            next_idx: 0,
            last_progress: now,
        });
        Ok(out)
    }

    /// Cancel an in-progress profile upload. Emits
    /// [`Event::ProfileUploadFailed`] with
    /// [`ProfileUploadFailure::Aborted`]. If no upload is in flight returns
    /// an empty [`CoreOutput`].
    pub fn cancel_profile_upload(&mut self) -> CoreOutput {
        let mut out = CoreOutput::default();
        if self.profile_upload.take().is_some() {
            out.events.push(Event::ProfileUploadFailed {
                reason: ProfileUploadFailure::Aborted,
            });
        }
        out
    }

    /// `true` from the moment [`upload_profile`](Self::upload_profile)
    /// returns `Ok` until the tail ack arrives, the upload is cancelled, or
    /// a failure event is emitted.
    pub fn profile_upload_in_progress(&self) -> bool {
        self.profile_upload.is_some()
    }

    /// Title of the profile most recently uploaded successfully — the
    /// "active profile on the DE1" identity the brew page surfaces.
    /// `None` until the first successful upload; cleared by
    /// [`reset`](Self::reset).
    pub fn active_profile_title(&self) -> Option<&str> {
        self.last_active_profile_title.as_deref()
    }

    /// Decode and process a `HeaderWrite` notification — the DE1's reply
    /// to a one-shot Read of `cuuid_0F`, carrying the currently-loaded
    /// profile's 5-byte `ShotHeader`. Caches the value on
    /// [`last_profile_header`](Self::last_profile_header) and emits one
    /// [`Event::ProfileHeaderRead`].
    ///
    /// **DORMANT** — the BLE shell does not currently Read `cuuid_0F`
    /// (docs/16 §6.1: legacy never Reads it, firmware returns zeros).
    /// This handler is reachable only via test fixtures and remains as
    /// forward-compat for a future firmware that exposes the buffer.
    fn handle_profile_header_read(&mut self, data: &[u8], out: &mut CoreOutput) {
        match ShotHeader::decode(data) {
            Ok(header) => {
                self.last_profile_header = Some(header);
                out.events.push(Event::ProfileHeaderRead {
                    frame_count: header.frame_count,
                    preinfuse_frame_count: header.preinfuse_frame_count,
                    minimum_pressure: header.minimum_pressure,
                    maximum_flow: header.maximum_flow,
                });
            }
            Err(e) => out.events.push(Event::DecodeError {
                message: e.to_string(),
            }),
        }
    }

    /// Decode and process a `FrameWrite` echo notification — the DE1's
    /// per-frame ack during a profile upload. Walks the expected-ack
    /// sequence; mismatches end the upload with
    /// [`ProfileUploadFailure::UnexpectedAck`], the final ack emits
    /// [`Event::ProfileUploadCompleted`] and records the title.
    ///
    /// If no upload is in flight (e.g. a stale notify from a prior session
    /// that arrives after a reconnect), the notification is silently
    /// dropped — the legacy app likewise ignored stray acks.
    fn handle_profile_frame_ack(&mut self, data: &[u8], now: Duration, out: &mut CoreOutput) {
        let Some(byte) = profile::ack_frame_byte(data) else {
            return;
        };
        let Some(upload) = self.profile_upload.as_mut() else {
            return;
        };

        let expected = upload.expected_acks[upload.next_idx];
        if byte != expected {
            self.profile_upload = None;
            out.events.push(Event::ProfileUploadFailed {
                reason: ProfileUploadFailure::UnexpectedAck { expected, got: byte },
            });
            return;
        }

        upload.next_idx += 1;
        upload.last_progress = now;
        let acks_received = u16::try_from(upload.next_idx).unwrap_or(u16::MAX);
        let total_acks = u16::try_from(upload.expected_acks.len()).unwrap_or(u16::MAX);
        let extension = byte >= EXTENSION_FRAME_INDEX_OFFSET;
        let frame = if extension {
            byte.wrapping_sub(EXTENSION_FRAME_INDEX_OFFSET)
        } else {
            byte
        };

        out.events.push(Event::ProfileUploadProgress {
            frame,
            extension,
            total_acks,
            acks_received,
        });

        if upload.next_idx == upload.expected_acks.len() {
            // Tail ack matched — the upload is complete.
            let title = std::mem::take(&mut upload.title);
            self.profile_upload = None;
            self.last_active_profile_title = Some(title.clone());
            out.events.push(Event::ProfileUploadCompleted { title });
        }
    }

    /// Decode and process a `ReadFromMMR` reply — the DE1's answer to an MMR
    /// read request issued by [`read_mmr`](Self::read_mmr).
    ///
    /// A reply carries up to four consecutive 32-bit words from the register
    /// window. Crema's read requests ask for one word at a known register, so
    /// only the first word is interpreted: it is mapped back to its
    /// [`MmrRegister`] by the echoed address and emitted as an
    /// [`Event::MmrValue`]. A reply for an address Crema does not model is
    /// dropped silently — not a decode failure, just an unmodelled register.
    fn handle_mmr_read(&mut self, data: &[u8], out: &mut CoreOutput) {
        let reply = match MmrReadReply::decode(data) {
            Ok(reply) => reply,
            Err(e) => {
                out.events.push(Event::DecodeError {
                    message: e.to_string(),
                });
                return;
            }
        };
        if let Some(register) = MmrRegister::from_address(reply.address)
            && let Some(value) = reply.word(0)
        {
            // Side-effect cache: the FirmwareVersion register feeds
            // `firmware_update_status`. The wire word's value fits a `u16`
            // for every shipped DE1 build (latest is ~1352, well below
            // 65535); saturate on the impossible overflow path so the
            // comparison still pins "newer than Crema knows" instead of
            // silently truncating.
            //
            // MachineModel + SerialNumber land in the capture META prelude
            // (so the replay tool can `connectScale(name)` and know which
            // DE1 model + serial the bytes came from); cached unscaled
            // because META carries the integers exactly as the shell wrote
            // them in the pre-core implementation.
            match register {
                MmrRegister::FirmwareVersion => {
                    self.last_firmware_build = Some(u16::try_from(value).unwrap_or(u16::MAX));
                }
                MmrRegister::MachineModel => {
                    self.last_machine_model = Some(value);
                }
                MmrRegister::SerialNumber => {
                    self.last_serial_number = Some(value);
                }
                _ => {}
            }
            out.events.push(Event::MmrValue { register, value });
        }
    }

    /// Decode and process a `Calibration` reply — the DE1's answer to a
    /// calibration read request issued by
    /// [`read_calibration`](Self::read_calibration).
    ///
    /// Only a read reply (`ReadCurrent` / `ReadFactory`) is surfaced as an
    /// [`Event::Calibration`]; a `Write` / `ResetToFactory` echo carries no
    /// new information and is dropped.
    fn handle_calibration(data: &[u8], out: &mut CoreOutput) {
        let cal = match Calibration::decode(data) {
            Ok(cal) => cal,
            Err(e) => {
                out.events.push(Event::DecodeError {
                    message: e.to_string(),
                });
                return;
            }
        };
        if matches!(
            cal.command,
            de1_protocol::CalCommand::ReadCurrent | de1_protocol::CalCommand::ReadFactory
        ) {
            out.events.push(Event::Calibration {
                target: cal.target,
                command: cal.command,
                de1_reported: cal.de1_reported,
                measured: cal.measured,
            });
        }
    }

    /// Decode and process a `ShotSettings` notification — fired by the
    /// firmware when the steam / hot-water / group-temp settings change
    /// (either from on-machine UI or from a host write), and as the reply
    /// to a connect-time Read of `cuuid_0B`. Caches the value on
    /// [`steam_hotwater_settings`](Self::steam_hotwater_settings) and
    /// emits one [`Event::ShotSettingsRead`].
    fn handle_shot_settings_read(&mut self, data: &[u8], out: &mut CoreOutput) {
        let settings = match ShotSettings::decode(data) {
            Ok(s) => s,
            Err(e) => {
                out.events.push(Event::DecodeError {
                    message: e.to_string(),
                });
                return;
            }
        };
        self.steam_hotwater_settings = Some(settings.clone());
        out.events.push(Event::ShotSettingsRead {
            steam_temp: settings.steam_temp_c,
            steam_timeout: settings.steam_timeout_s,
            hot_water_temp: settings.hot_water_temp_c,
            hot_water_volume: settings.hot_water_volume_ml,
            hot_water_timeout: settings.hot_water_timeout_s,
            espresso_volume: settings.espresso_volume_ml,
            group_temp: settings.group_temp_c,
        });
    }

    /// Translate a domain [`ShotEvent`] into FFI [`Event`]s, maintaining the
    /// shot-start timestamp and the auto-stop lifecycle.
    ///
    /// At shot start the connected scale is also auto-tared and its built-in
    /// timer is reset-then-started; at shot completion the timer is stopped.
    /// All three timer writes are capability-gated by [`Scale::timer`], so
    /// scales without software timer commands stay silent — matching the
    /// audit §7.2 reactive auto-policy from `docs/14`.
    fn map_shot_event(&mut self, event: ShotEvent, now: Duration, out: &mut CoreOutput) {
        match event {
            ShotEvent::Started => {
                self.shot_started = Some(now);
                self.flow.reset();
                // Fresh shot → fresh running volume. The integrator keeps
                // its `last_sample_time` / `last_host_time` so the first
                // sample of the new shot still has a valid `dt` reference.
                self.volume_integrator.reset();
                // Wipe the previous shot's running peaks so this one starts
                // from a clean slate. (`Completed` already drains, but the
                // explicit reset keeps the invariant independent of call
                // order.)
                self.shot_metrics.reset();
                // Auto-tare the connected scale so the cup starts from zero,
                // mirroring the legacy app's tare-at-shot-start behaviour.
                self.push_tare_command(out);
                // Reset the scale's built-in timer first, then start it. The
                // reset clears any residual from a prior shot; the start
                // begins counting from zero.
                Self::push_timer_command(&self.scale, TimerCommand::Reset, out);
                Self::push_timer_command(&self.scale, TimerCommand::Start, out);
                out.events.push(Event::ShotStarted);
            }
            ShotEvent::PhaseChanged(phase) => {
                self.arm_auto_stop_if_flowing(phase, now);
                out.events.push(Event::ShotPhaseChanged { phase });
            }
            ShotEvent::FrameChanged(frame) => out.events.push(Event::ShotFrameChanged { frame }),
            ShotEvent::Completed(record) => {
                self.shot_started = None;
                self.auto_stop = None;
                // Stop the scale's built-in timer alongside the auto-stop.
                Self::push_timer_command(&self.scale, TimerCommand::Stop, out);
                // `record.duration` is the domain `Duration`; narrow to the
                // u32 ms the FFI `ShotCompleted` event carries.
                let duration = u32::try_from(record.duration.as_millis()).unwrap_or(u32::MAX);
                // Drain the running peak / final-weight tracker into the
                // event — one computation at the core boundary that every
                // shell can consume directly.
                let (peak_pressure, peak_temp, peak_weight, final_weight) =
                    self.shot_metrics.drain();
                out.events.push(Event::ShotCompleted {
                    duration,
                    sample_count: u32::try_from(record.samples.len()).unwrap_or(u32::MAX),
                    peak_pressure,
                    peak_temp,
                    peak_weight,
                    final_weight,
                });
            }
        }
    }

    /// Translate a domain [`WaterEvent`] into FFI [`Event`]s.
    fn map_water_event(event: WaterEvent, out: &mut CoreOutput) {
        match event {
            WaterEvent::Started(kind) => {
                out.events.push(Event::WaterSessionStarted { kind });
            }
            WaterEvent::Completed(record) => {
                out.events.push(Event::WaterSessionCompleted {
                    kind: record.kind,
                    duration: duration_to_u32_ms(record.duration),
                });
            }
        }
    }

    /// Translate a domain [`SteamEvent`] into FFI [`Event`]s. An eco-mode
    /// change also emits the [`Command`] that rewrites the DE1's steam target
    /// temperature.
    fn map_steam_event(&mut self, event: SteamEvent, out: &mut CoreOutput) {
        match event {
            SteamEvent::Started => out.events.push(Event::SteamSessionStarted),
            SteamEvent::Completed(record) => {
                out.events.push(Event::SteamSessionCompleted {
                    duration: duration_to_u32_ms(record.duration),
                    sample_count: u32::try_from(record.samples.len()).unwrap_or(u32::MAX),
                });
            }
            SteamEvent::ClogSuspected(reason) => {
                out.events.push(Event::SteamClogSuspected { reason });
            }
            SteamEvent::EcoModeChanged(eco) => {
                out.events.push(Event::SteamEcoModeChanged { eco });
                out.commands.push(Command::WriteCharacteristic {
                    target: WriteTarget::De1ShotSettings,
                    data: self.steam_settings_for_eco(eco).encode().to_vec(),
                });
            }
        }
    }

    /// Construct the [`AutoStop`] the first time the shot reaches a flowing
    /// phase, so its arming delay counts from flow start, not heating start.
    fn arm_auto_stop_if_flowing(&mut self, phase: ShotPhase, now: Duration) {
        if self.auto_stop.is_some() {
            return;
        }
        if !matches!(phase, ShotPhase::Preinfusion | ShotPhase::Pouring) {
            return;
        }
        if let Some(targets) = self.auto_stop_targets {
            self.auto_stop = Some(AutoStop::new(targets, self.stop_config(), now));
        }
    }

    /// The [`StopConfig`] for a new [`AutoStop`] — the legacy four-term SAW
    /// lead, using the connected scale's sensor lag.
    fn stop_config(&self) -> StopConfig {
        let scale_lag = self
            .scale
            .as_ref()
            .map_or(DEFAULT_SCALE_LAG, Scale::sensor_lag);
        StopConfig::with_legacy_lead(STOP_WEIGHT_BEFORE, scale_lag, FlowAlgorithm::TheilSen)
    }

    /// Push a [`StopReason`], when one occurred, as a [`Event::StopTriggered`]
    /// plus the [`Command`] that actually stops the machine.
    fn push_stop(reason: Option<StopReason>, out: &mut CoreOutput) {
        if let Some(reason) = reason {
            out.events.push(Event::StopTriggered { reason });
            out.commands.push(Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                data: vec![requested_state(MachineState::Idle)],
            });
        }
    }
}

/// Composite arguments for [`CremaCore::set_heater_tweaks`], mirroring the
/// legacy `set_heater_tweaks` proc (`de1_comms.tcl:1123`).
///
/// Carries the seven settings the legacy app pushes at connect time. The
/// composite write reuses each register's individual scale (flow
/// `int(10 × rate)`, etc.) — see [`CremaCore::set_heater_tweaks`] for the
/// per-field units.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct HeaterTweaks {
    /// Hot-water phase-1 flow rate, ml/s.
    pub phase_1_flow_rate: f32,
    /// Hot-water phase-2 flow rate, ml/s.
    pub phase_2_flow_rate: f32,
    /// Hot-water idle temperature, °C.
    pub hot_water_idle_temp: u8,
    /// Espresso warmup timeout.
    pub espresso_warmup_timeout: Duration,
    /// Steam two-tap-stop register raw byte.
    pub steam_two_tap_stop: u8,
    /// Flush timeout.
    pub flush_timeout: Duration,
    /// Flush flow rate, ml/s.
    pub flush_flow_rate: f32,
    /// Hot-water flow rate, ml/s.
    pub hot_water_flow_rate: f32,
}

/// Internal: build a `WriteToMMR` [`CoreOutput`] for one register.
///
/// Used by the public [`CremaCore::write_mmr`] and every typed setter;
/// lockout-checking stays in the callers so a [`Event::FirmwareLockoutHit`]
/// names the actual setter, not this internal helper.
///
/// `byte_len` is clamped to `1..=4`; the value is encoded little-endian.
#[allow(clippy::cast_possible_truncation)]
fn mmr_write_command(register: MmrRegister, value: u32, byte_len: u8) -> CoreOutput {
    let mut out = CoreOutput::default();
    let bytes = value.to_le_bytes();
    let len = byte_len.clamp(1, 4) as usize;
    out.commands.push(Command::WriteCharacteristic {
        target: WriteTarget::De1MmrWrite,
        data: mmr::write_request(register.address(), &bytes[..len]).to_vec(),
    });
    out
}

/// Map a scale mode `id` (the wire value carried in [`ScaleCapabilities`]'
/// `modes`) to the [`bookoo::BookooMode`] the command builder expects.
///
/// Returns `None` for an unknown `id`. The capability list is the source of
/// truth for which ids are valid; this is the final guard that the id is one
/// the Bookoo protocol actually understands.
fn bookoo_mode_from_id(id: u8) -> Option<bookoo::BookooMode> {
    match id {
        0 => Some(bookoo::BookooMode::FlowRate),
        1 => Some(bookoo::BookooMode::Timer),
        2 => Some(bookoo::BookooMode::Auto),
        _ => None,
    }
}

/// Map an auto-stop-mode `id` to the [`bookoo::AutoStopMode`] the command
/// builder expects (`0` = flow-stop, `1` = cup-removal).
///
/// Returns `None` for an out-of-range `id`, the final guard that the id is one
/// the Bookoo protocol actually understands.
fn bookoo_auto_stop_from_id(id: u8) -> Option<bookoo::AutoStopMode> {
    match id {
        0 => Some(bookoo::AutoStopMode::FlowStop),
        1 => Some(bookoo::AutoStopMode::CupRemoval),
        _ => None,
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

    /// A Bookoo weight packet reporting `centigrams` hundredths of a gram.
    // The three `as u8` casts are a standard big-endian byte split — each
    // shifted byte is masked to 8 bits by the cast, which is the intent.
    #[allow(clippy::cast_possible_truncation)]
    fn bookoo_packet(centigrams: u32) -> [u8; 10] {
        [
            0,
            0,
            0,
            0,
            0,
            0,
            b'+',
            (centigrams >> 16) as u8,
            (centigrams >> 8) as u8,
            centigrams as u8,
        ]
    }

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
        let Event::Telemetry { elapsed, .. } = telemetry else {
            unreachable!("filtered for Telemetry");
        };
        assert_eq!(*elapsed, 2_500);
    }

    #[test]
    fn telemetry_before_a_shot_has_zero_elapsed_time() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 9_000);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::Telemetry { elapsed: 0, .. }))
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
    fn on_tick_does_nothing_without_a_connected_scale() {
        let mut core = CremaCore::new();
        let out = core.on_tick(1_000_000);
        assert!(out.events.is_empty() && out.commands.is_empty());
    }

    #[test]
    fn on_tick_does_nothing_before_the_scale_first_reports() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // No reading yet — the watchdog has nothing to time against.
        let out = core.on_tick(1_000_000);
        assert!(out.events.is_empty());
    }

    #[test]
    fn on_tick_emits_scale_stale_once_per_stale_episode() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        // Within the timeout: no warning yet.
        assert!(core.on_tick(1_500).events.is_empty());
        // Past the 1 s timeout: ScaleStale fires.
        assert!(core.on_tick(2_100).events.contains(&Event::ScaleStale));
        // Still stale on the next tick, but the warning is not repeated.
        assert!(!core.on_tick(3_000).events.contains(&Event::ScaleStale));
    }

    #[test]
    fn a_fresh_scale_reading_re_arms_the_watchdog() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        assert!(core.on_tick(2_500).events.contains(&Event::ScaleStale));
        // A new reading re-arms the watchdog; staleness can be reported again.
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_100), 3_000);
        assert!(core.on_tick(3_500).events.is_empty());
        assert!(core.on_tick(4_100).events.contains(&Event::ScaleStale));
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
    fn connect_scale_identifies_a_known_scale() {
        let mut core = CremaCore::new();
        // A matched scale returns its display label.
        assert_eq!(
            core.connect_scale("BOOKOO_SC 1234"),
            Some("Bookoo".to_owned())
        );
        // An unrecognised name returns None.
        assert_eq!(core.connect_scale("Some Random Device"), None);
    }

    #[test]
    fn a_scale_weight_notification_emits_a_reading() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::ScaleReading { .. }))
        );
    }

    #[test]
    fn a_scale_weight_with_no_scale_connected_is_ignored() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        assert!(out.events.is_empty());
    }

    /// Decode a lowercase-hex string with no separators into bytes.
    fn hex(s: &str) -> Vec<u8> {
        (0..s.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap())
            .collect()
    }

    #[test]
    fn a_scale_command_serial_response_emits_a_scale_config_event() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // A real 03 0c serial response: fw 1.4.1, anti-mistouch on.
        let frame = hex("030c008d534e32343030643636613839653701ce");
        let out = core.on_notification(Source::ScaleCommand, &frame, 1_000);
        assert!(out.events.contains(&Event::ScaleConfig {
            anti_mistouch: Some(true),
            active_mode: None,
            enabled_modes: None,
            serial: Some("SN2400d66a89e7".to_owned()),
            firmware_version: Some(141),
        }));
    }

    #[test]
    fn a_scale_command_settings_response_emits_a_scale_config_event() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // A real 03 0e settings response: Auto active, only Auto enabled.
        let frame = hex("030e02040000000000000000000000000000000b");
        let out = core.on_notification(Source::ScaleCommand, &frame, 1_000);
        assert!(out.events.contains(&Event::ScaleConfig {
            anti_mistouch: None,
            active_mode: Some(2),
            enabled_modes: Some(0b100),
            serial: None,
            firmware_version: None,
        }));
    }

    #[test]
    fn a_scale_command_notification_with_no_scale_connected_is_ignored() {
        let mut core = CremaCore::new();
        let frame = hex("030c008d534e32343030643636613839653701ce");
        let out = core.on_notification(Source::ScaleCommand, &frame, 1_000);
        assert!(out.events.is_empty());
    }

    #[test]
    fn an_unrecognised_scale_command_frame_is_dropped_silently() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // A 03 0b weight-notification frame is not a command response — it is
        // dropped without a DecodeError, as it is not modelled here.
        let frame = hex("030b000000012b0000002b0000640096010000fa");
        let out = core.on_notification(Source::ScaleCommand, &frame, 1_000);
        assert!(out.events.is_empty());
    }

    #[test]
    fn the_first_weight_notification_issues_the_connect_time_queries() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // The first weight notification stands in for a connect hook: it must
        // emit the 0x0a serial query and the 0x0f settings query, once.
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        let writes: Vec<&[u8]> = out
            .commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteScale { data } => Some(data.as_slice()),
                Command::WriteCharacteristic { .. } => None,
            })
            .collect();
        assert!(writes.contains(&bookoo::QUERY_SERIAL.as_slice()));
        assert!(writes.contains(&bookoo::QUERY_SETTINGS.as_slice()));
    }

    #[test]
    fn the_connect_time_queries_are_issued_only_once() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        // A second weight notification must not re-issue the connect queries.
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(2_100), 2_000);
        assert!(
            !out.commands
                .iter()
                .any(|c| matches!(c, Command::WriteScale { .. }))
        );
    }

    #[test]
    fn the_connect_time_queries_are_re_armed_by_a_fresh_connect() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        // Reconnecting a scale re-arms the one-shot queries.
        core.connect_scale("BOOKOO_SC");
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(2_100), 2_000);
        assert!(
            out.commands
                .iter()
                .any(|c| matches!(c, Command::WriteScale { .. }))
        );
    }

    #[test]
    fn a_weight_only_scale_issues_no_connect_time_queries() {
        let mut core = CremaCore::new();
        // A weight-only scale has no configurable settings — the connect-time
        // queries are capability-gated, so nothing is emitted.
        core.connect_scale("Decent Scale ABC");
        let frame = [0x03, 0xCE, 0x07, 0xD0, 0x00, 0x00, 0x00];
        let out = core.on_notification(Source::ScaleWeight, &frame, 1_000);
        assert!(
            !out.commands
                .iter()
                .any(|c| matches!(c, Command::WriteScale { .. }))
        );
    }

    // ----- Decent Scale LCD + heartbeat -----------------------------------

    /// Extract the bytes of the single `WriteScale` command in `out`, or
    /// panic if none was emitted. Helper for the LCD / heartbeat tests.
    fn the_only_scale_write(out: &CoreOutput) -> &[u8] {
        let writes: Vec<&[u8]> = out
            .commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteScale { data } => Some(data.as_slice()),
                Command::WriteCharacteristic { .. } => None,
            })
            .collect();
        assert_eq!(writes.len(), 1, "expected exactly one WriteScale command");
        writes[0]
    }

    #[test]
    fn enable_decent_scale_lcd_emits_the_known_wire_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        let out = core.enable_decent_scale_lcd(WeightUnit::Grams);
        // The single emitted command must carry exactly `LCD_ENABLE_GRAMS`.
        assert_eq!(the_only_scale_write(&out), decent_scale::LCD_ENABLE_GRAMS);
    }

    #[test]
    fn enable_decent_scale_lcd_in_ounces_emits_the_ounces_packet() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        let out = core.enable_decent_scale_lcd(WeightUnit::Ounces);
        // The ounces variant only differs from the grams variant in byte
        // [4] (the unit flag) and the trailing checksum.
        assert_eq!(the_only_scale_write(&out), decent_scale::LCD_ENABLE_OUNCES);
    }

    #[test]
    fn disable_decent_scale_lcd_emits_the_known_wire_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        let out = core.disable_decent_scale_lcd();
        assert_eq!(the_only_scale_write(&out), decent_scale::LCD_DISABLE);
    }

    #[test]
    fn decent_scale_heartbeat_emits_the_known_wire_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        let out = core.decent_scale_heartbeat();
        assert_eq!(the_only_scale_write(&out), decent_scale::HEARTBEAT);
    }

    #[test]
    fn the_decent_scale_writes_are_silent_with_no_scale_connected() {
        let core = CremaCore::new();
        assert!(core.enable_decent_scale_lcd(WeightUnit::Grams).commands.is_empty());
        assert!(core.disable_decent_scale_lcd().commands.is_empty());
        assert!(core.decent_scale_heartbeat().commands.is_empty());
    }

    #[test]
    fn the_decent_scale_writes_are_silent_for_a_non_decent_scale() {
        // Every Decent-Scale-specific write is gated by `is_decent_scale`,
        // so connecting a Bookoo (or any other) must yield nothing.
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        assert!(core.enable_decent_scale_lcd(WeightUnit::Grams).commands.is_empty());
        assert!(core.disable_decent_scale_lcd().commands.is_empty());
        assert!(core.decent_scale_heartbeat().commands.is_empty());
    }

    #[test]
    fn power_off_decent_scale_errors_when_no_scale_is_connected() {
        let core = CremaCore::new();
        let err = core.power_off_decent_scale().unwrap_err();
        assert!(matches!(err, AppError::UnsupportedOnHardware { .. }));
    }

    #[test]
    fn power_off_decent_scale_errors_for_a_non_decent_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let err = core.power_off_decent_scale().unwrap_err();
        assert!(matches!(err, AppError::UnsupportedOnHardware { .. }));
    }

    #[test]
    fn power_off_decent_scale_errors_when_firmware_version_is_unknown() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        // No firmware reply observed yet — refuse the write.
        let err = core.power_off_decent_scale().unwrap_err();
        match err {
            AppError::UnsupportedOnHardware { feature, .. } => {
                assert_eq!(feature, "decent_scale_power_off");
            }
            other => panic!("expected UnsupportedOnHardware, got {other:?}"),
        }
    }

    #[test]
    fn power_off_decent_scale_emits_the_known_wire_bytes_on_v1_2_firmware() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        // Forward a synthetic `0x0A` reply with the v1.2 sentinel byte
        // through the notification path so the scale state records the
        // firmware version the same way it would on real hardware.
        let firmware_reply = [0x03, 0x0A, 0x01, 0x01, 0x00, 0x4E, 0x12, 0x00];
        core.on_notification(Source::ScaleWeight, &firmware_reply, 1_000);
        let out = core.power_off_decent_scale().expect("v1.2 supports power-off");
        assert_eq!(the_only_scale_write(&out), decent_scale::POWER_OFF);
    }

    #[test]
    fn power_off_decent_scale_errors_on_v1_0_firmware() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        // A v1.0 firmware sentinel — power-off is silently ignored by
        // the scale on this firmware, so the core must refuse the write
        // (the shell needs to fall back to "long-press the button").
        let firmware_reply = [0x03, 0x0A, 0x01, 0x01, 0x00, 0x4E, 0x10, 0x00];
        core.on_notification(Source::ScaleWeight, &firmware_reply, 1_000);
        let err = core.power_off_decent_scale().unwrap_err();
        assert!(matches!(err, AppError::UnsupportedOnHardware { .. }));
    }

    #[test]
    fn set_weight_unit_pref_round_trips() {
        let mut core = CremaCore::new();
        assert_eq!(core.weight_unit_pref(), WeightUnit::Grams);
        core.set_weight_unit_pref(WeightUnit::Ounces);
        assert_eq!(core.weight_unit_pref(), WeightUnit::Ounces);
    }

    #[test]
    fn entering_idle_picks_the_ounces_lcd_packet_when_the_pref_is_ounces() {
        // The auto-policy in handle_state reads the cached pref so an Idle
        // entry matches the unit the shell is showing.
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        core.set_weight_unit_pref(WeightUnit::Ounces);
        core.on_notification(Source::De1State, &[MachineState::Sleep as u8, 0], 1_000);
        let out = core.on_notification(
            Source::De1State,
            &[MachineState::Idle as u8, 0],
            2_000,
        );
        let writes: Vec<&[u8]> = out
            .commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteScale { data } => Some(data.as_slice()),
                Command::WriteCharacteristic { .. } => None,
            })
            .collect();
        assert!(
            writes.contains(&decent_scale::LCD_ENABLE_OUNCES.as_slice()),
            "expected LCD_ENABLE_OUNCES on Idle entry with ounces pref, got {writes:?}"
        );
    }

    #[test]
    fn entering_idle_auto_enables_the_decent_scale_lcd() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        // Transition Sleep -> Idle. The first state notification doesn't
        // emit (no prior state) but the second one — entering Idle from
        // Sleep — must fire the LCD-enable write.
        core.on_notification(
            Source::De1State,
            &[MachineState::Sleep as u8, 0],
            1_000,
        );
        let out = core.on_notification(
            Source::De1State,
            &[MachineState::Idle as u8, 0],
            2_000,
        );
        let writes: Vec<&[u8]> = out
            .commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteScale { data } => Some(data.as_slice()),
                Command::WriteCharacteristic { .. } => None,
            })
            .collect();
        assert!(
            writes.contains(&decent_scale::LCD_ENABLE_GRAMS.as_slice()),
            "expected LCD_ENABLE_GRAMS on Idle entry, got {writes:?}"
        );
    }

    #[test]
    fn entering_sleep_auto_disables_the_decent_scale_lcd() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        // Transition Idle -> Sleep. The second notification — entering
        // Sleep from Idle — must fire the LCD-disable write.
        core.on_notification(
            Source::De1State,
            &[MachineState::Idle as u8, 0],
            1_000,
        );
        let out = core.on_notification(
            Source::De1State,
            &[MachineState::Sleep as u8, 0],
            2_000,
        );
        let writes: Vec<&[u8]> = out
            .commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteScale { data } => Some(data.as_slice()),
                Command::WriteCharacteristic { .. } => None,
            })
            .collect();
        assert!(
            writes.contains(&decent_scale::LCD_DISABLE.as_slice()),
            "expected LCD_DISABLE on Sleep entry, got {writes:?}"
        );
    }

    #[test]
    fn the_decent_scale_lcd_auto_policy_is_silent_for_a_non_decent_scale() {
        // The auto-policy is Decent-Scale-only; a Bookoo (or no scale)
        // sees no LCD writes on the same state transitions.
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        core.on_notification(
            Source::De1State,
            &[MachineState::Sleep as u8, 0],
            1_000,
        );
        let out = core.on_notification(
            Source::De1State,
            &[MachineState::Idle as u8, 0],
            2_000,
        );
        let lcd_writes: Vec<&[u8]> = out
            .commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteScale { data } => Some(data.as_slice()),
                Command::WriteCharacteristic { .. } => None,
            })
            .filter(|w| {
                *w == decent_scale::LCD_ENABLE_GRAMS.as_slice()
                    || *w == decent_scale::LCD_DISABLE.as_slice()
            })
            .collect();
        assert!(lcd_writes.is_empty(), "unexpected LCD writes: {lcd_writes:?}");
    }

    #[test]
    fn a_water_level_notification_applies_the_5mm_correction() {
        let mut core = CremaCore::new();
        // WaterLevels packet: current 100 mm, refill threshold 70 mm.
        let out = core.on_notification(Source::De1WaterLevels, &[0x64, 0x00, 0x46, 0x00], 0);
        assert!(out.events.contains(&Event::WaterLevel {
            level: 105.0,
            refill_threshold: 70.0,
        }));
    }

    #[test]
    fn a_version_notification_emits_a_firmware_event() {
        let mut core = CremaCore::new();
        // 18-byte Version packet: BLE then FW block, each
        // `APIVersion u8 | Release u8 | Commits u16 | Changes u8 | Sha u32`.
        let packet = [
            0x04, 0x0A, 0x00, 0x0A, 0x02, 0xDE, 0xAD, 0xBE, 0xEF, // BLE block
            0x04, 0x0A, 0x00, 0x8E, 0x05, 0x12, 0x34, 0x56, 0x78, // FW block
        ];
        let out = core.on_notification(Source::De1Version, &packet, 0);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::Firmware { commits: 10, .. }))
        );
    }

    #[test]
    fn a_truncated_version_notification_emits_a_decode_error() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::De1Version, &[0x04, 0x0A], 0);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::DecodeError { .. }))
        );
    }

    #[test]
    fn read_mmr_emits_a_read_request_write() {
        let core = CremaCore::new();
        let out = core.read_mmr(MmrRegister::SerialNumber);
        // The command writes a one-word ReadFromMMR request — Len byte 0, then
        // the big-endian register address — to the MMR-request characteristic.
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteCharacteristic {
                target: WriteTarget::De1MmrRequest,
                data,
            }) if data[0] == 0 && data[1..4] == [0x80, 0x38, 0x30]
        ));
    }

    #[test]
    fn an_mmr_reply_emits_an_mmr_value_event() {
        let mut core = CremaCore::new();
        // A ReadFromMMR reply for the firmware-version register (0x800010),
        // first word = 1352, little-endian.
        let mut packet = [0u8; de1_protocol::MMR_PACKET_LEN];
        packet[1..4].copy_from_slice(&[0x80, 0x00, 0x10]);
        packet[4..8].copy_from_slice(&1352u32.to_le_bytes());
        let out = core.on_notification(Source::De1MmrRead, &packet, 0);
        assert!(out.events.iter().any(|e| matches!(
            e,
            Event::MmrValue {
                register: MmrRegister::FirmwareVersion,
                value: 1352,
            }
        )));
    }

    #[test]
    fn firmware_version_mmr_reply_caches_last_firmware_build() {
        let mut core = CremaCore::new();
        // Before any MMR read, the status check returns Unknown.
        assert_eq!(core.firmware_update_status(), FirmwareUpdateStatus::Unknown);
        // Feed a ReadFromMMR reply for 0x800010 with value 1352.
        let mut packet = [0u8; de1_protocol::MMR_PACKET_LEN];
        packet[1..4].copy_from_slice(&[0x80, 0x00, 0x10]);
        packet[4..8].copy_from_slice(&1352u32.to_le_bytes());
        let _ = core.on_notification(Source::De1MmrRead, &packet, 0);
        // The cache is now populated and the status check finds UpToDate.
        assert_eq!(
            core.firmware_update_status(),
            FirmwareUpdateStatus::UpToDate { installed: 1352 }
        );
    }

    #[test]
    fn an_mmr_reply_for_an_unmodelled_register_is_dropped() {
        let mut core = CremaCore::new();
        // Address 0x000000 is not a register Crema models — no event, and it
        // is not a decode error either.
        let packet = [0u8; de1_protocol::MMR_PACKET_LEN];
        let out = core.on_notification(Source::De1MmrRead, &packet, 0);
        assert!(out.events.is_empty());
    }

    #[test]
    fn a_truncated_mmr_reply_emits_a_decode_error() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::De1MmrRead, &[0x00, 0x80], 0);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::DecodeError { .. }))
        );
    }

    #[test]
    fn read_calibration_emits_a_read_request_write() {
        let core = CremaCore::new();
        let out = core.read_calibration(CalTarget::Pressure);
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteCharacteristic {
                target: WriteTarget::De1Calibration,
                ..
            })
        ));
        let out = core.read_factory_calibration(CalTarget::Flow);
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteCharacteristic {
                target: WriteTarget::De1Calibration,
                ..
            })
        ));
    }

    #[test]
    fn write_calibration_emits_the_exact_wire_bytes() {
        // The codec is exercised in de1-protocol; this asserts the
        // command-level shape (target = De1Calibration) and that the
        // encoded packet matches a known-good fixture so a regression
        // in the wiring is caught here too. Fixture mirrors the
        // `write_encodes_to_the_exact_wire_bytes` test in
        // `core/de1-protocol/src/calibration.rs`: a temperature write,
        // de1_reported = 92.5, measured = 93.0.
        let core = CremaCore::new();
        let out = core.write_calibration(CalTarget::Temperature, 92.5, 93.0);
        let Some(Command::WriteCharacteristic { target, data }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic");
        };
        assert_eq!(*target, WriteTarget::De1Calibration);
        assert_eq!(
            data.as_slice(),
            &[
                // WriteKey magic 0xCAFEF00D, big-endian.
                0xCA, 0xFE, 0xF0, 0x0D, //
                0x01, // command = Write
                0x02, // target = Temperature
                // de1_reported 92.5 as S32P16: 92.5 * 65536 = 0x005C8000.
                0x00, 0x5C, 0x80, 0x00, //
                // measured 93.0 as S32P16: 93.0 * 65536 = 0x005D0000.
                0x00, 0x5D, 0x00, 0x00,
            ],
        );
    }

    #[test]
    fn reset_calibration_to_factory_emits_a_write_with_the_reset_packet() {
        // Same shape: command targets De1Calibration; the encoded packet
        // carries the WriteKey + ResetToFactory + target tuple with zero
        // value fields. Fixture: pressure sensor.
        let core = CremaCore::new();
        let out = core.reset_calibration_to_factory(CalTarget::Pressure);
        let Some(Command::WriteCharacteristic { target, data }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic");
        };
        assert_eq!(*target, WriteTarget::De1Calibration);
        assert_eq!(
            data.as_slice(),
            &[
                0xCA, 0xFE, 0xF0, 0x0D, // WriteKey = 0xCAFEF00D
                0x02, // command = ResetToFactory
                0x01, // target = Pressure
                0x00, 0x00, 0x00, 0x00, // de1_reported = 0.0
                0x00, 0x00, 0x00, 0x00, // measured = 0.0
            ],
        );
    }

    #[test]
    fn a_calibration_reply_emits_a_calibration_event() {
        let mut core = CremaCore::new();
        // A ReadCurrent reply for the temperature sensor: DE1 reported 92.5.
        let packet = Calibration {
            command: de1_protocol::CalCommand::ReadCurrent,
            target: CalTarget::Temperature,
            de1_reported: 92.5,
            measured: 0.0,
        }
        .encode();
        let out = core.on_notification(Source::De1Calibration, &packet, 0);
        assert!(out.events.iter().any(|e| matches!(
            e,
            Event::Calibration {
                target: CalTarget::Temperature,
                command: de1_protocol::CalCommand::ReadCurrent,
                de1_reported,
                ..
            } if (*de1_reported - 92.5).abs() < 1e-3
        )));
    }

    #[test]
    fn a_calibration_write_echo_emits_no_event() {
        let mut core = CremaCore::new();
        // A Write echo carries no new readable information — it is dropped.
        let packet = Calibration::write(CalTarget::Flow, 1.0, 1.05).encode();
        let out = core.on_notification(Source::De1Calibration, &packet, 0);
        assert!(out.events.is_empty());
    }

    #[test]
    fn a_truncated_calibration_notification_emits_a_decode_error() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::De1Calibration, &[0x00; 5], 0);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::DecodeError { .. }))
        );
    }

    #[test]
    fn request_machine_state_emits_a_write_command() {
        let core = CremaCore::new();
        let out = core.request_machine_state(MachineState::Idle);
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                ..
            })
        ));
    }

    #[test]
    fn tare_scale_emits_a_write_only_when_a_scale_is_connected() {
        let mut core = CremaCore::new();
        assert!(core.tare_scale().commands.is_empty());
        core.connect_scale("BOOKOO_SC");
        let out = core.tare_scale();
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn scale_capabilities_is_none_without_a_connected_scale() {
        let core = CremaCore::new();
        assert!(core.scale_capabilities().is_none());
    }

    #[test]
    fn scale_capabilities_reports_a_bookoo_as_first_class() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let caps = core.scale_capabilities().expect("a connected scale");
        assert!(caps.volume.is_some());
        assert!(caps.reports_flow);
        assert!(caps.reports_timer);
    }

    #[test]
    fn scale_capabilities_reports_a_weight_only_scale_as_having_none() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        let caps = core.scale_capabilities().expect("a connected scale");
        assert!(caps.volume.is_none());
        assert!(!caps.reports_flow);
        assert!(!caps.reports_timer);
    }

    #[test]
    fn set_scale_volume_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_volume(3);
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_volume_clamps_the_level_to_the_capability_range() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // An out-of-range request clamps to the Bookoo's volume max of 5
        // before the command bytes are built.
        let Some(Command::WriteScale { data: clamped }) =
            core.set_scale_volume(99).commands.into_iter().next()
        else {
            panic!("expected a WriteScale command");
        };
        let Some(Command::WriteScale { data: at_max }) =
            core.set_scale_volume(5).commands.into_iter().next()
        else {
            panic!("expected a WriteScale command");
        };
        assert_eq!(clamped, at_max);
    }

    #[test]
    fn set_scale_volume_emits_nothing_without_a_scale() {
        let mut core = CremaCore::new();
        assert!(core.set_scale_volume(3).commands.is_empty());
    }

    #[test]
    fn set_scale_volume_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        // A weight-only scale has no `volume` capability — the command is
        // capability-gated, so nothing is emitted.
        core.connect_scale("Decent Scale ABC");
        assert!(core.set_scale_volume(3).commands.is_empty());
    }

    #[test]
    fn set_scale_standby_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_standby(15);
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_standby_clamps_to_the_capability_range() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // An out-of-range request clamps to the Bookoo's 5..=30 minute range.
        let Some(Command::WriteScale { data: clamped }) =
            core.set_scale_standby(99).commands.into_iter().next()
        else {
            panic!("expected a WriteScale command");
        };
        let Some(Command::WriteScale { data: at_max }) =
            core.set_scale_standby(30).commands.into_iter().next()
        else {
            panic!("expected a WriteScale command");
        };
        assert_eq!(clamped, at_max);
    }

    #[test]
    fn set_scale_standby_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        assert!(core.set_scale_standby(15).commands.is_empty());
    }

    #[test]
    fn set_scale_flow_smoothing_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        assert!(matches!(
            core.set_scale_flow_smoothing(true).commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_flow_smoothing_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        assert!(core.set_scale_flow_smoothing(true).commands.is_empty());
        assert!(core.set_scale_flow_smoothing(true).commands.is_empty());
    }

    #[test]
    fn set_scale_anti_mistouch_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        assert!(matches!(
            core.set_scale_anti_mistouch(false).commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_anti_mistouch_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        assert!(core.set_scale_anti_mistouch(true).commands.is_empty());
    }

    #[test]
    fn set_scale_mode_emits_three_writes_in_order_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // Selecting a mode is three writes (target on, then the other two off),
        // followed by the appended 0x0f settings query.
        let out = core.set_scale_mode(1);
        assert_eq!(out.commands.len(), 4);
        let bytes: Vec<Vec<u8>> = out
            .commands
            .into_iter()
            .map(|c| {
                let Command::WriteScale { data } = c else {
                    panic!("expected a WriteScale command");
                };
                data
            })
            .collect();
        // First three must match bookoo::select_mode(Timer): Timer on,
        // FlowRate off, Auto off — then the settings query.
        let expected: Vec<Vec<u8>> = bookoo::select_mode(bookoo::BookooMode::Timer)
            .iter()
            .map(|c| c.to_vec())
            .chain(std::iter::once(bookoo::QUERY_SETTINGS.to_vec()))
            .collect();
        assert_eq!(bytes, expected);
    }

    #[test]
    fn set_scale_mode_emits_nothing_for_an_unknown_mode_id() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // 7 is not a listed Bookoo mode — capability-gated, so nothing emitted.
        assert!(core.set_scale_mode(7).commands.is_empty());
    }

    #[test]
    fn set_scale_mode_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        assert!(core.set_scale_mode(0).commands.is_empty());
    }

    #[test]
    fn set_scale_mode_emits_nothing_without_a_scale() {
        let mut core = CremaCore::new();
        assert!(core.set_scale_mode(0).commands.is_empty());
    }

    #[test]
    fn set_scale_auto_stop_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        assert!(matches!(
            core.set_scale_auto_stop(1).commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_auto_stop_maps_each_mode_id_to_its_command() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let Some(Command::WriteScale { data: flow_stop }) =
            core.set_scale_auto_stop(0).commands.into_iter().next()
        else {
            panic!("expected a WriteScale command");
        };
        assert_eq!(
            flow_stop,
            bookoo::set_auto_stop_mode(bookoo::AutoStopMode::FlowStop)
        );
        let Some(Command::WriteScale { data: cup_removal }) =
            core.set_scale_auto_stop(1).commands.into_iter().next()
        else {
            panic!("expected a WriteScale command");
        };
        assert_eq!(
            cup_removal,
            bookoo::set_auto_stop_mode(bookoo::AutoStopMode::CupRemoval)
        );
    }

    #[test]
    fn set_scale_auto_stop_emits_nothing_for_an_out_of_range_mode_id() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // 2 is not a valid auto-stop mode — rejected gracefully, empty output.
        assert!(core.set_scale_auto_stop(2).commands.is_empty());
    }

    #[test]
    fn set_scale_auto_stop_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        assert!(core.set_scale_auto_stop(0).commands.is_empty());
    }

    #[test]
    fn set_scale_auto_stop_emits_nothing_without_a_scale() {
        let mut core = CremaCore::new();
        assert!(core.set_scale_auto_stop(0).commands.is_empty());
    }

    /// The `WriteScale` data of the last command in `out`, or panics.
    fn last_write_scale(out: &CoreOutput) -> &[u8] {
        let Some(Command::WriteScale { data }) = out.commands.last() else {
            panic!("expected a trailing WriteScale command");
        };
        data
    }

    #[test]
    fn set_scale_volume_appends_a_settings_query_after_the_config_write() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_volume(3);
        // Config write, then the 0x0f settings query.
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_standby_appends_a_settings_query() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_standby(15);
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_flow_smoothing_appends_a_settings_query() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_flow_smoothing(true);
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_anti_mistouch_appends_a_settings_query() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_anti_mistouch(false);
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_auto_stop_appends_a_settings_query() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_auto_stop(1);
        // Config write, then the 0x0f settings query.
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_mode_appends_a_settings_query_after_the_three_writes() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_mode(1);
        // Three mode writes, then the 0x0f settings query.
        assert_eq!(out.commands.len(), 4);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_methods_append_no_query_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        // An unsupported scale emits nothing at all — not even the query.
        assert!(core.set_scale_volume(3).commands.is_empty());
        assert!(core.set_scale_standby(15).commands.is_empty());
        assert!(core.set_scale_flow_smoothing(true).commands.is_empty());
        assert!(core.set_scale_anti_mistouch(true).commands.is_empty());
        assert!(core.set_scale_mode(0).commands.is_empty());
        assert!(core.set_scale_auto_stop(0).commands.is_empty());
    }

    #[test]
    fn query_scale_settings_emits_the_query_for_a_bookoo() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.query_scale_settings();
        assert_eq!(out.commands.len(), 1);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn query_scale_settings_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        assert!(core.query_scale_settings().commands.is_empty());
    }

    #[test]
    fn query_scale_settings_emits_nothing_without_a_scale() {
        let core = CremaCore::new();
        assert!(core.query_scale_settings().commands.is_empty());
    }

    #[test]
    fn an_armed_weight_target_triggers_a_stop_and_command() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        core.arm_auto_stop(Some(30.0), None, None);
        // Start a shot already in a flowing phase: arms the AutoStop at t = 0.
        core.on_notification(Source::De1State, &[4, 5], 0);
        // A 35 g reading well past the 5 s arming delay crosses the 30 g target.
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 6_000);
        assert!(out.events.contains(&Event::StopTriggered {
            reason: StopReason::Weight,
        }));
        // The same first weight notification also rides the connect-time scale
        // queries, so the stop write is not necessarily commands[0] — assert it
        // is present rather than first.
        assert!(out.commands.iter().any(|c| matches!(
            c,
            Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                ..
            }
        )));
    }

    #[test]
    fn an_armed_max_time_target_stops_the_shot() {
        let mut core = CremaCore::new();
        core.arm_auto_stop(None, None, Some(30.0));
        // Start a shot in a flowing phase: arms the AutoStop at t = 0.
        core.on_notification(Source::De1State, &[4, 5], 0);
        // A telemetry sample past the 30 s limit trips the time-based stop.
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 31_000);
        assert!(out.events.contains(&Event::StopTriggered {
            reason: StopReason::MaxTime,
        }));
    }

    #[test]
    fn a_shot_start_auto_tares_a_connected_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.on_notification(Source::De1State, &[4, 5], 1_000);
        assert!(out.events.contains(&Event::ShotStarted));
        // The shot start emits a tare-scale command for the connected scale.
        assert!(
            out.commands
                .iter()
                .any(|c| matches!(c, Command::WriteScale { .. }))
        );
    }

    #[test]
    fn a_shot_start_without_a_scale_emits_no_tare() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::De1State, &[4, 5], 1_000);
        assert!(out.events.contains(&Event::ShotStarted));
        assert!(
            !out.commands
                .iter()
                .any(|c| matches!(c, Command::WriteScale { .. }))
        );
    }

    /// A `ShotSettings` with a placeholder hot-water volume; other fields are
    /// representative defaults.
    fn hotwater_settings(hot_water_volume_ml: f32) -> de1_protocol::ShotSettings {
        de1_protocol::ShotSettings {
            steam_flags: 0,
            steam_temp_c: 150.0,
            steam_timeout_s: 120.0,
            hot_water_temp_c: 85.0,
            hot_water_volume_ml,
            hot_water_timeout_s: 30.0,
            espresso_volume_ml: 36.0,
            group_temp_c: 92.0,
        }
    }

    #[test]
    fn entering_hot_water_emits_a_water_session_started() {
        let mut core = CremaCore::new();
        let out = core.on_notification(Source::De1State, &[6, 5], 1_000);
        assert!(out.events.contains(&Event::WaterSessionStarted {
            kind: de1_domain::WaterSessionKind::HotWater,
        }));
    }

    #[test]
    fn a_full_flush_session_emits_started_then_completed() {
        let mut core = CremaCore::new();
        // HotWaterRinse (15) — a flush.
        core.on_notification(Source::De1State, &[15, 5], 1_000);
        let out = core.on_notification(Source::De1State, &[2, 0], 5_000);
        assert!(out.events.contains(&Event::WaterSessionCompleted {
            kind: de1_domain::WaterSessionKind::Flush,
            duration: 4_000,
        }));
    }

    #[test]
    fn set_steam_hotwater_settings_writes_the_user_volume_without_a_scale() {
        let mut core = CremaCore::new();
        let out = core.set_steam_hotwater_settings(hotwater_settings(50.0));
        let Some(Command::WriteCharacteristic { target, data }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic command");
        };
        assert_eq!(*target, WriteTarget::De1ShotSettings);
        // Byte 4 is the hot-water volume (u8p0): the user's 50 ml is unchanged.
        assert_eq!(data[4], 50);
    }

    #[test]
    fn set_steam_hotwater_settings_requests_250ml_when_a_scale_is_connected() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_steam_hotwater_settings(hotwater_settings(50.0));
        let Some(Command::WriteCharacteristic { data, .. }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic command");
        };
        // With a scale connected the volume is bumped to 250 ml so the scale's
        // weight stop is what ends the pour.
        assert_eq!(data[4], 250);
    }

    #[test]
    fn steam_hotwater_volume_override_lifts_when_the_scale_disconnects() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // The user configures 50 ml while a scale is connected.
        core.set_steam_hotwater_settings(hotwater_settings(50.0));
        // The scale disconnects (an unrecognised name clears `scale`).
        assert_eq!(core.connect_scale("Some Random Device"), None);
        // An eco transition rewrites the settings; the hot-water volume must
        // now be the user's original 50 ml, not the 250 ml scale override.
        core.enable_steam_eco_mode(true, 0);
        let out = core.on_tick(600_000);
        let Some(Command::WriteCharacteristic { data, .. }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic command");
        };
        assert_eq!(data[4], 50);
    }

    #[test]
    fn entering_steam_emits_a_steam_session_started() {
        let mut core = CremaCore::new();
        // Steam (5) / Steaming (7).
        let out = core.on_notification(Source::De1State, &[5, 7], 1_000);
        assert!(out.events.contains(&Event::SteamSessionStarted));
    }

    #[test]
    fn a_full_steam_session_emits_started_then_completed() {
        let mut core = CremaCore::new();
        core.on_notification(Source::De1State, &[5, 7], 1_000);
        let out = core.on_notification(Source::De1State, &[2, 0], 6_000);
        assert!(out.events.contains(&Event::SteamSessionCompleted {
            duration: 5_000,
            sample_count: 0,
        }));
    }

    #[test]
    fn a_steam_session_records_telemetry_samples() {
        let mut core = CremaCore::new();
        core.on_notification(Source::De1State, &[5, 7], 0);
        // SAMPLE carries group_pressure 9.0 — the steam monitor records it.
        core.on_notification(Source::De1ShotSample, &SAMPLE, 100);
        core.on_notification(Source::De1ShotSample, &SAMPLE, 200);
        let out = core.on_notification(Source::De1State, &[2, 0], 3_000);
        assert!(out.events.contains(&Event::SteamSessionCompleted {
            duration: 3_000,
            sample_count: 2,
        }));
    }

    #[test]
    fn a_clogged_steam_session_emits_a_clog_suspected_event() {
        let mut core = CremaCore::new();
        core.on_notification(Source::De1State, &[5, 7], 0);
        // SAMPLE has group_pressure 9.0 (over the 8 bar threshold). Feed enough
        // samples that, after the 20-sample trim, the over-pressure count
        // exceeds the trigger of 10.
        for _ in 0..40 {
            core.on_notification(Source::De1ShotSample, &SAMPLE, 100);
        }
        let out = core.on_notification(Source::De1State, &[2, 0], 5_000);
        assert!(out.events.iter().any(|e| matches!(
            e,
            Event::SteamClogSuspected {
                reason: de1_domain::SteamClogReason::OverPressure,
            }
        )));
    }

    #[test]
    fn steam_eco_mode_engages_on_tick_and_writes_the_eco_temperature() {
        let mut core = CremaCore::new();
        core.set_steam_hotwater_settings(hotwater_settings(50.0));
        core.enable_steam_eco_mode(true, 0);
        // Before the 600 s idle delay: nothing.
        assert!(core.on_tick(599_000).events.is_empty());
        // At the delay: eco mode engages and rewrites the steam target.
        let out = core.on_tick(600_000);
        assert!(
            out.events
                .contains(&Event::SteamEcoModeChanged { eco: true })
        );
        let Some(Command::WriteCharacteristic { target, data }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic command");
        };
        assert_eq!(*target, WriteTarget::De1ShotSettings);
        // Byte 1 is the steam temperature (u8p0): the 136 °C eco target.
        assert_eq!(data[1], 136);
    }

    #[test]
    fn disabling_steam_eco_mode_restores_the_normal_target() {
        let mut core = CremaCore::new();
        core.set_steam_hotwater_settings(hotwater_settings(50.0));
        core.enable_steam_eco_mode(true, 0);
        core.on_tick(600_000); // engages eco mode
        let out = core.enable_steam_eco_mode(false, 700_000);
        assert!(
            out.events
                .contains(&Event::SteamEcoModeChanged { eco: false })
        );
        let Some(Command::WriteCharacteristic { data, .. }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic command");
        };
        // Back to the user's normal 150 °C steam target.
        assert_eq!(data[1], 150);
    }

    #[test]
    fn a_steam_session_disengages_eco_mode() {
        let mut core = CremaCore::new();
        core.set_steam_hotwater_settings(hotwater_settings(50.0));
        core.enable_steam_eco_mode(true, 0);
        core.on_tick(600_000); // engages eco mode
        // Steaming is activity: it disengages eco mode.
        let out = core.on_notification(Source::De1State, &[5, 7], 601_000);
        assert!(
            out.events
                .contains(&Event::SteamEcoModeChanged { eco: false })
        );
    }

    #[test]
    fn builtin_profiles_json_is_a_non_empty_profile_array() {
        let core = CremaCore::new();
        let json = core.builtin_profiles_json();
        // It parses back into the same Vec<Profile> the domain crate ships.
        let parsed: Vec<de1_domain::Profile> = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed.len(), de1_domain::BUILTIN_PROFILE_COUNT);
        assert!(!parsed.is_empty());
        // Every shipped profile is uploadable to a DE1.
        for profile in &parsed {
            assert!(profile.assemble().is_ok());
        }
    }

    #[test]
    fn a_shot_start_auto_resets_then_starts_a_capable_scale_timer() {
        // Audit §7.2 reactive auto-policy: on transition into Espresso the
        // scale's built-in timer is reset (clearing any residual from the
        // previous shot) then started.
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.on_notification(Source::De1State, &[4, 5], 1_000);
        let scale_writes: Vec<&[u8]> = out
            .commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteScale { data } => Some(data.as_slice()),
                Command::WriteCharacteristic { .. } => None,
            })
            .collect();
        // The first WriteScale is the auto-tare (TARE), then the two timer
        // writes — RESET before START so the new shot counts from zero.
        let reset_pos = scale_writes
            .iter()
            .position(|w| *w == bookoo::TIMER_RESET.as_slice())
            .expect("a TIMER_RESET write");
        let start_pos = scale_writes
            .iter()
            .position(|w| *w == bookoo::TIMER_START.as_slice())
            .expect("a TIMER_START write");
        assert!(reset_pos < start_pos, "RESET must precede START");
    }

    #[test]
    fn a_shot_completion_auto_stops_a_capable_scale_timer() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // Start an espresso shot.
        core.on_notification(Source::De1State, &[4, 5], 1_000);
        // Transition to Idle to complete the shot.
        let out = core.on_notification(Source::De1State, &[2, 0], 5_000);
        assert!(
            out.commands.iter().any(|c| matches!(
                c,
                Command::WriteScale { data } if data.as_slice() == bookoo::TIMER_STOP.as_slice()
            )),
            "expected a TIMER_STOP write on ShotCompleted"
        );
    }

    #[test]
    fn a_shot_with_no_scale_completes_with_pressure_and_temp_peaks_only() {
        let mut core = CremaCore::new();
        // Start an espresso shot.
        core.on_notification(Source::De1State, &[4, 5], 1_000);
        // Feed two telemetry samples — SAMPLE carries `group_pressure ≈ 9.0`
        // and a non-zero `head_temp` from its fixture bytes.
        core.on_notification(Source::De1ShotSample, &SAMPLE, 1_100);
        core.on_notification(Source::De1ShotSample, &SAMPLE, 1_200);
        // Transition to Idle to complete the shot.
        let out = core.on_notification(Source::De1State, &[2, 0], 5_000);
        let completed = out
            .events
            .iter()
            .find(|e| matches!(e, Event::ShotCompleted { .. }))
            .expect("a ShotCompleted event");
        let Event::ShotCompleted {
            peak_pressure,
            peak_temp,
            peak_weight,
            final_weight,
            ..
        } = completed
        else {
            unreachable!("filtered for ShotCompleted");
        };
        assert!(
            peak_pressure.is_some(),
            "telemetry arrived → peak_pressure is Some",
        );
        assert!(
            peak_temp.is_some(),
            "telemetry arrived → peak_temp is Some",
        );
        assert!(
            peak_weight.is_none(),
            "no scale paired → peak_weight stays None",
        );
        assert!(
            final_weight.is_none(),
            "no scale paired → final_weight stays None",
        );
    }

    #[test]
    fn a_shot_with_a_scale_completes_with_peak_and_final_weight() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // Start an espresso shot.
        core.on_notification(Source::De1State, &[4, 5], 1_000);
        // Weight rises to 32 g then settles at 30 g — peak ≥ 32, final = 30.
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_100);
        core.on_notification(Source::ScaleWeight, &bookoo_packet(3_200), 1_200);
        core.on_notification(Source::ScaleWeight, &bookoo_packet(3_000), 1_300);
        // Transition to Idle to complete the shot.
        let out = core.on_notification(Source::De1State, &[2, 0], 5_000);
        let completed = out
            .events
            .iter()
            .find(|e| matches!(e, Event::ShotCompleted { .. }))
            .expect("a ShotCompleted event");
        let Event::ShotCompleted {
            peak_weight,
            final_weight,
            ..
        } = completed
        else {
            unreachable!("filtered for ShotCompleted");
        };
        let peak = peak_weight.expect("scale readings arrived → peak_weight is Some");
        let last = final_weight.expect("scale readings arrived → final_weight is Some");
        assert!(
            peak >= last,
            "peak {peak} g should be >= the final {last} g — the running max never decreases",
        );
        // The smoothed weight tracks the inputs (20 g, 32 g, 30 g) but the
        // flow estimator filters them, so we only check ordering and a
        // plausible range rather than exact values.
        assert!(
            (15.0..=40.0).contains(&peak),
            "peak {peak} g should be in a plausible espresso range",
        );
        assert!(
            (15.0..=40.0).contains(&last),
            "final {last} g should be in a plausible espresso range",
        );
    }

    #[test]
    fn a_second_shot_starts_with_a_clean_peak_metric_slate() {
        let mut core = CremaCore::new();
        // Shot 1: build up a peak.
        core.on_notification(Source::De1State, &[4, 5], 1_000);
        core.on_notification(Source::De1ShotSample, &SAMPLE, 1_100);
        core.on_notification(Source::De1State, &[2, 0], 5_000);
        // Shot 2: ShotStarted should wipe the accumulator. End immediately,
        // without any telemetry, and the metrics should all be None.
        core.on_notification(Source::De1State, &[4, 5], 10_000);
        let out = core.on_notification(Source::De1State, &[2, 0], 11_000);
        let completed = out
            .events
            .iter()
            .find(|e| matches!(e, Event::ShotCompleted { .. }))
            .expect("a ShotCompleted event");
        let Event::ShotCompleted {
            peak_pressure,
            peak_temp,
            peak_weight,
            final_weight,
            ..
        } = completed
        else {
            unreachable!("filtered for ShotCompleted");
        };
        assert!(
            peak_pressure.is_none(),
            "shot 2 had no telemetry → peak_pressure must be None, not leftover from shot 1",
        );
        assert!(
            peak_temp.is_none(),
            "shot 2 had no telemetry → peak_temp must be None",
        );
        assert!(peak_weight.is_none());
        assert!(final_weight.is_none());
    }

    #[test]
    fn a_shot_start_without_a_timer_capable_scale_emits_no_timer_writes() {
        let mut core = CremaCore::new();
        // Acaia has tare but no software timer.
        core.connect_scale("ACAIA-X");
        let out = core.on_notification(Source::De1State, &[4, 5], 1_000);
        let scale_writes: Vec<&[u8]> = out
            .commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteScale { data } => Some(data.as_slice()),
                Command::WriteCharacteristic { .. } => None,
            })
            .collect();
        // A tare may have been emitted (Acaia supports tare), but no timer
        // bytes — bookoo::TIMER_* would not match the Acaia's tare bytes.
        for write in &scale_writes {
            assert_ne!(*write, bookoo::TIMER_RESET.as_slice());
            assert_ne!(*write, bookoo::TIMER_START.as_slice());
            assert_ne!(*write, bookoo::TIMER_STOP.as_slice());
        }
    }

    #[test]
    fn start_timer_emits_a_write_only_when_a_capable_scale_is_connected() {
        let mut core = CremaCore::new();
        // No scale: empty.
        assert!(core.start_timer().commands.is_empty());
        // Bookoo: a WriteScale command.
        core.connect_scale("BOOKOO_SC");
        let out = core.start_timer();
        let Some(Command::WriteScale { data }) = out.commands.first() else {
            panic!("expected a WriteScale command");
        };
        assert_eq!(data.as_slice(), bookoo::TIMER_START.as_slice());
    }

    #[test]
    fn stop_and_reset_timer_emit_their_writes_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let stop = core.stop_timer();
        let reset = core.reset_timer();
        let Some(Command::WriteScale { data: stop_data }) = stop.commands.first() else {
            panic!("expected stop WriteScale");
        };
        let Some(Command::WriteScale { data: reset_data }) = reset.commands.first() else {
            panic!("expected reset WriteScale");
        };
        assert_eq!(stop_data.as_slice(), bookoo::TIMER_STOP.as_slice());
        assert_eq!(reset_data.as_slice(), bookoo::TIMER_RESET.as_slice());
    }

    #[test]
    fn timer_methods_emit_nothing_for_a_timer_less_scale() {
        let mut core = CremaCore::new();
        // The Acaia is in `Scale::supports_timer`'s exclusion list — no
        // software timer commands; capability-gated to nothing.
        core.connect_scale("ACAIA-X");
        assert!(core.start_timer().commands.is_empty());
        assert!(core.stop_timer().commands.is_empty());
        assert!(core.reset_timer().commands.is_empty());
    }

    #[test]
    fn write_mmr_emits_a_write_to_mmr_command() {
        let core = CremaCore::new();
        let out = core.write_mmr(MmrRegister::FanThreshold, 45, 1);
        let Some(Command::WriteCharacteristic { target, data }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic");
        };
        assert_eq!(*target, WriteTarget::De1MmrWrite);
        // 20-byte WriteToMMR packet: Len = 1, address big-endian, value LE.
        assert_eq!(data.len(), 20);
        assert_eq!(data[0], 1);
        assert_eq!(&data[1..4], &[0x80, 0x38, 0x08]);
        assert_eq!(data[4], 45);
    }

    #[test]
    fn set_steam_flow_scales_by_one_hundred_into_a_four_byte_slot() {
        // docs/22 §2.1: TCL + reaprime agree on ×100 + 4-byte; the
        // pre-2026-05-22 `×10` + 1-byte encoding was a bug (the 1-byte
        // clamp prevented valid steam targets from reaching the
        // firmware). 7.0 ml/s × 100 = 700; little-endian 4-byte =
        // [0xBC, 0x02, 0x00, 0x00].
        let core = CremaCore::new();
        let out = core.set_steam_flow(7.0);
        let Some(Command::WriteCharacteristic { target, data }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic");
        };
        assert_eq!(*target, WriteTarget::De1MmrWrite);
        assert_eq!(data[0], 4, "4-byte register");
        // bytes 4..8 = the little-endian payload after the [len, addr]
        // header that mmr_write_command emits.
        assert_eq!(&data[4..8], &[0xBC, 0x02, 0x00, 0x00]);
    }

    #[test]
    fn set_steam_highflow_start_preserves_sub_second_precision() {
        // docs/22 §2.2: the wire value is seconds × 100. The default
        // 0.7s stores as 70 (matches legacy `machine.tcl:309
        // steam_highflow_start 70`). Pre-2026-05-22 builds took a
        // `Duration` and called `as_secs()`, truncating 0.7s → 0.
        let core = CremaCore::new();
        let out = core.set_steam_highflow_start(0.7);
        let Some(Command::WriteCharacteristic { data, .. }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic");
        };
        assert_eq!(data[0], 4, "4-byte register");
        // 0.7 × 100 = 70; little-endian = [0x46, 0x00, 0x00, 0x00].
        assert_eq!(&data[4..8], &[0x46, 0x00, 0x00, 0x00]);
    }

    #[test]
    fn set_user_present_and_set_feature_flags_target_distinct_registers() {
        // Confirmed by user: 0x803860 (UserPresent) and 0x803858 (FeatureFlags)
        // are distinct registers, not aliases.
        let core = CremaCore::new();
        let up = core.set_user_present(true);
        let ff = core.set_feature_flags(0x1234);
        let Some(Command::WriteCharacteristic { data: up_data, .. }) = up.commands.first() else {
            panic!("expected WriteCharacteristic");
        };
        let Some(Command::WriteCharacteristic { data: ff_data, .. }) = ff.commands.first() else {
            panic!("expected WriteCharacteristic");
        };
        assert_eq!(&up_data[1..4], &[0x80, 0x38, 0x60], "UserPresent address");
        assert_eq!(&ff_data[1..4], &[0x80, 0x38, 0x58], "FeatureFlags address");
        assert_eq!(up_data[0], 1, "UserPresent is 1-byte");
        assert_eq!(ff_data[0], 2, "FeatureFlags is 2-byte");
    }

    #[test]
    fn set_heater_voltage_rejects_values_outside_120_or_230() {
        // docs/27 row #56: the firmware accepts only 120 or 230 as
        // user-committed values. Anything else is a clamp failure at the
        // bridge layer; the shell pre-validates via MainsConfirmModal but
        // the core is the last-line guard.
        let core = CremaCore::new();
        for bad in [0u8, 1, 100, 110, 119, 121, 200, 229, 231, 240, 255] {
            match core.set_heater_voltage(bad) {
                Err(AppError::InvalidArg { field, value, .. }) => {
                    assert_eq!(field, "voltage");
                    assert_eq!(value, bad.to_string());
                }
                other => panic!("expected InvalidArg for {bad}, got {other:?}"),
            }
        }
    }

    #[test]
    fn set_heater_voltage_encodes_plus_1000_as_four_byte_le() {
        // docs/27 row #56 + reaprime de1.models.dart:301 heaterV "Nominal
        // Heater Voltage (0, 120V or 230V). +1000 if it's a set value." —
        // the user-committed marker is the +1000 offset, encoded as
        // 4-byte little-endian on the wire.
        //   120 + 1000 = 1120 = 0x460 → bytes [0x60, 0x04, 0x00, 0x00]
        //   230 + 1000 = 1230 = 0x4CE → bytes [0xCE, 0x04, 0x00, 0x00]
        let core = CremaCore::new();

        let out120 = core
            .set_heater_voltage(120)
            .expect("120 V should be accepted");
        let Some(Command::WriteCharacteristic { target, data }) = out120.commands.first() else {
            panic!("expected a WriteCharacteristic for 120 V");
        };
        assert_eq!(*target, WriteTarget::De1MmrWrite);
        assert_eq!(data[0], 4, "HeaterVoltage is a 4-byte register");
        assert_eq!(&data[1..4], &[0x80, 0x38, 0x34], "address 0x803834 LE");
        assert_eq!(
            &data[4..8],
            &[0x60, 0x04, 0x00, 0x00],
            "wire payload = 120 + 1000 = 1120, LE"
        );

        let out230 = core
            .set_heater_voltage(230)
            .expect("230 V should be accepted");
        let Some(Command::WriteCharacteristic { data, .. }) = out230.commands.first() else {
            panic!("expected a WriteCharacteristic for 230 V");
        };
        assert_eq!(data[0], 4, "HeaterVoltage is a 4-byte register");
        assert_eq!(
            &data[4..8],
            &[0xCE, 0x04, 0x00, 0x00],
            "wire payload = 230 + 1000 = 1230, LE"
        );
    }

    #[test]
    fn reset_machine_defaults_emits_8_writes_with_known_baselines() {
        // docs/27 row #53 — settings reset to factory baseline.
        // Pins the 8 MMR writes byte-for-byte against the documented
        // baseline (post-clamp wire values, in reaprime's source order).
        let core = CremaCore::new();
        let out = core
            .reset_machine_defaults()
            .expect("reset_machine_defaults is infallible without a firmware lockout");

        // Each tuple: (register address, byte-len byte, payload bytes).
        // Payload bytes are little-endian; addresses are big-endian on
        // the wire (mmr::write_request packs them at offsets 1..4).
        let expected: [(u32, u8, &[u8]); 8] = [
            // Fan: 50 °C (post-clamp; reaprime's source says 55).
            (0x0080_3808, 1, &[0x32]),
            // HotWaterIdleTemp: 95 °C × 10 = 950 = 0x03B6.
            (0x0080_3818, 4, &[0xB6, 0x03, 0x00, 0x00]),
            // Phase1FlowRate: 2.0 × 10 = 20 = 0x14.
            (0x0080_3810, 4, &[0x14, 0x00, 0x00, 0x00]),
            // Phase2FlowRate: 4.0 × 10 = 40 = 0x28.
            (0x0080_3814, 4, &[0x28, 0x00, 0x00, 0x00]),
            // EspressoWarmupTimeout: 4.0 s × 10 = 40 = 0x28.
            (0x0080_3838, 4, &[0x28, 0x00, 0x00, 0x00]),
            // RefillKit: 2 (auto).
            (0x0080_385C, 4, &[0x02, 0x00, 0x00, 0x00]),
            // CalFlowEst: 1.0 × 1000 = 1000 = 0x03E8.
            (0x0080_383C, 2, &[0xE8, 0x03]),
            // SteamPurgeMode (SteamTwoTapStop): 0.
            (0x0080_3850, 4, &[0x00, 0x00, 0x00, 0x00]),
        ];

        assert_eq!(out.commands.len(), expected.len());
        for (i, (cmd, (addr, byte_len, payload))) in
            out.commands.iter().zip(expected.iter()).enumerate()
        {
            let Command::WriteCharacteristic { target, data } = cmd else {
                panic!("write #{i}: expected WriteCharacteristic, got {cmd:?}");
            };
            assert_eq!(*target, WriteTarget::De1MmrWrite, "write #{i} target");
            // mmr::write_request packs: [byte_len, addr_be (3 bytes), payload, …].
            assert_eq!(data[0], *byte_len, "write #{i} byte-len byte");
            let addr_bytes = [
                ((addr >> 16) & 0xFF) as u8,
                ((addr >> 8) & 0xFF) as u8,
                (addr & 0xFF) as u8,
            ];
            assert_eq!(&data[1..4], &addr_bytes, "write #{i} address");
            assert_eq!(
                &data[4..4 + payload.len()],
                *payload,
                "write #{i} payload"
            );
        }
    }

    #[test]
    fn set_heater_tweaks_emits_eight_writes_in_legacy_order() {
        let core = CremaCore::new();
        let out = core.set_heater_tweaks(HeaterTweaks {
            phase_1_flow_rate: 1.0,
            phase_2_flow_rate: 2.0,
            hot_water_idle_temp: 85,
            espresso_warmup_timeout: Duration::from_secs(30),
            steam_two_tap_stop: 0,
            flush_timeout: Duration::from_secs(5),
            flush_flow_rate: 3.0,
            hot_water_flow_rate: 4.0,
        });
        assert_eq!(out.commands.len(), 8);
        // Each command should target De1MmrWrite.
        for cmd in &out.commands {
            let Command::WriteCharacteristic { target, .. } = cmd else {
                panic!("expected WriteCharacteristic");
            };
            assert_eq!(*target, WriteTarget::De1MmrWrite);
        }
    }

    #[test]
    fn set_refill_threshold_emits_a_water_levels_write() {
        let core = CremaCore::new();
        let out = core.set_refill_threshold(70.0);
        let Some(Command::WriteCharacteristic { target, data }) = out.commands.first() else {
            panic!("expected a WriteCharacteristic command");
        };
        assert_eq!(*target, WriteTarget::De1WaterLevels);
        // 4-byte WaterLevels: current = 0 mm, threshold = 70 mm.
        // u16p8 encode: integer part in the high byte (be).
        assert_eq!(data.len(), 4);
        assert_eq!(data[0], 0);
        assert_eq!(data[1], 0);
        // 70 mm → high byte 70.
        assert_eq!(data[2], 70);
        assert_eq!(data[3], 0);
    }

    #[test]
    fn firmware_locks_writes_is_false_in_v1() {
        // v1 carries the lockout guard as a stub that always returns `false`;
        // v2 will return `true` for the `Erase..Verifying` upload phases.
        // This test pins the stub so a future change is a conscious decision,
        // not an accident — see `docs/17-firmware-update-plan.md` §3.4.
        let core = CremaCore::new();
        assert!(!core.firmware_locks_writes());
    }

    #[test]
    fn firmware_lockout_event_round_trips_through_json() {
        // The new event variant has to serialise like the rest of the Event
        // family — adjacently tagged with the `method` field intact.
        let output = CoreOutput {
            events: vec![Event::FirmwareLockoutHit {
                method: "set_refill_threshold".to_owned(),
            }],
            commands: vec![],
        };
        let json = output.to_json().unwrap();
        let parsed: CoreOutput = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, output);
    }

    #[test]
    fn core_output_round_trips_through_json() {
        let output = CoreOutput {
            events: vec![Event::ShotStarted, Event::ShotFrameChanged { frame: 3 }],
            commands: vec![Command::WriteScale {
                data: vec![1, 2, 3],
            }],
        };
        let json = output.to_json().unwrap();
        let parsed: CoreOutput = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, output);
    }

    // ── Profile upload — docs/16 §7.3 ─────────────────────────────────────

    mod profile_upload {
        use super::*;
        use de1_domain::{
            BeverageType, Limiter, Profile, ProfileStep, Pump, TempSensor, Transition,
        };

        fn step(name: &str, pump: Pump, limiter: Option<Limiter>) -> ProfileStep {
            ProfileStep {
                name: name.to_string(),
                pump,
                target: 9.0,
                temperature_c: 92.0,
                temp_sensor: TempSensor::Coffee,
                transition: Transition::Fast,
                duration_seconds: 20.0,
                exit: None,
                volume_limit_ml: 0,
                limiter,
                weight: None,
            }
        }

        fn profile(title: &str, steps: Vec<ProfileStep>) -> Profile {
            Profile {
                title: title.to_string(),
                notes: String::new(),
                steps,
                preinfuse_step_count: 1,
                minimum_pressure: 0.0,
                maximum_flow: 6.0,
                max_total_volume_ml: 0,
                target_weight: 0.0,
                dose: 0.0,
                author: String::new(),
                beverage_type: BeverageType::Espresso,
                tank_temperature: 0.0,
                version: "2".to_string(),
            }
        }

        /// Helper: walk the writes-to-FrameWrite commands and feed each back
        /// as a `De1FrameAck` notification. Returns the events emitted for the
        /// last ack.
        fn ack_every_frame(core: &mut CremaCore, commands: &[Command], now_ms_step: u64) -> Vec<Event> {
            let mut last_events = Vec::new();
            let frames: Vec<&[u8]> = commands
                .iter()
                .filter_map(|c| match c {
                    Command::WriteCharacteristic {
                        target: WriteTarget::De1ProfileFrame,
                        data,
                    } => Some(data.as_slice()),
                    _ => None,
                })
                .collect();
            for (i, packet) in frames.iter().enumerate() {
                let now = (i as u64 + 1) * now_ms_step;
                let out = core.on_notification(Source::De1FrameAck, packet, now);
                last_events = out.events;
            }
            last_events
        }

        #[test]
        fn minimum_profile_emits_header_one_frame_and_tail() {
            let mut core = CremaCore::new();
            let p = profile("Minimum", vec![step("a", Pump::Pressure, None)]);
            let out = core.upload_profile(&p, Duration::ZERO).unwrap();
            // 1 header + 1 frame + 0 extensions + 1 tail = 3 writes.
            assert_eq!(out.commands.len(), 3);
            match &out.commands[0] {
                Command::WriteCharacteristic { target, data } => {
                    assert_eq!(*target, WriteTarget::De1ProfileHeader);
                    assert_eq!(data.len(), 5);
                }
                _ => panic!("first command must be the header write"),
            }
            for cmd in &out.commands[1..] {
                match cmd {
                    Command::WriteCharacteristic { target, data } => {
                        assert_eq!(*target, WriteTarget::De1ProfileFrame);
                        assert_eq!(data.len(), 8);
                    }
                    _ => panic!("remaining commands must be frame writes"),
                }
            }
            assert!(matches!(
                out.events[0],
                Event::ProfileUploadStarted {
                    frame_count: 1,
                    extension_frame_count: 0,
                    ..
                }
            ));
            assert!(core.profile_upload_in_progress());
        }

        #[test]
        fn three_step_no_limiter_progresses_then_completes() {
            let mut core = CremaCore::new();
            let p = profile(
                "Triple",
                vec![
                    step("a", Pump::Pressure, None),
                    step("b", Pump::Pressure, None),
                    step("c", Pump::Pressure, None),
                ],
            );
            let started = core.upload_profile(&p, Duration::ZERO).unwrap();
            // 1 header + 3 frames + 0 extensions + 1 tail = 5 writes.
            assert_eq!(started.commands.len(), 5);

            let last_events = ack_every_frame(&mut core, &started.commands, 10);
            assert!(last_events.iter().any(|e| matches!(
                e,
                Event::ProfileUploadCompleted { title } if title == "Triple"
            )));
            assert!(!core.profile_upload_in_progress());
            assert_eq!(core.active_profile_title(), Some("Triple"));
        }

        #[test]
        fn middle_step_limiter_places_extension_after_frames() {
            let mut core = CremaCore::new();
            let p = profile(
                "MidLimiter",
                vec![
                    step("a", Pump::Pressure, None),
                    step(
                        "b",
                        Pump::Pressure,
                        Some(Limiter {
                            value: 3.0,
                            range: 0.5,
                        }),
                    ),
                    step("c", Pump::Pressure, None),
                ],
            );
            let started = core.upload_profile(&p, Duration::ZERO).unwrap();
            // 1 header + 3 frames + 1 extension + 1 tail = 6 writes.
            assert_eq!(started.commands.len(), 6);
            // Extension frame's FrameToWrite is step index 1 + 32 = 33.
            let frame_writes: Vec<&Vec<u8>> = started
                .commands
                .iter()
                .filter_map(|c| match c {
                    Command::WriteCharacteristic {
                        target: WriteTarget::De1ProfileFrame,
                        data,
                    } => Some(data),
                    _ => None,
                })
                .collect();
            assert_eq!(frame_writes[0][0], 0, "first frame is step 0");
            assert_eq!(frame_writes[1][0], 1, "second frame is step 1");
            assert_eq!(frame_writes[2][0], 2, "third frame is step 2");
            assert_eq!(frame_writes[3][0], 33, "extension is step 1 + 32");
            assert_eq!(frame_writes[4][0], 3, "tail's FrameToWrite == frame_count");

            // Acking each in order completes cleanly.
            let last = ack_every_frame(&mut core, &started.commands, 10);
            assert!(last.iter().any(|e| matches!(e, Event::ProfileUploadCompleted { .. })));
        }

        #[test]
        fn every_step_has_a_limiter() {
            let mut core = CremaCore::new();
            let p = profile(
                "AllLimited",
                (0..4)
                    .map(|i| {
                        step(
                            &format!("s{i}"),
                            Pump::Pressure,
                            Some(Limiter {
                                value: 3.0,
                                range: 0.5,
                            }),
                        )
                    })
                    .collect(),
            );
            let started = core.upload_profile(&p, Duration::ZERO).unwrap();
            // 1 header + 4 frames + 4 extensions + 1 tail = 10 writes.
            assert_eq!(started.commands.len(), 10);
            let last = ack_every_frame(&mut core, &started.commands, 10);
            assert!(last.iter().any(|e| matches!(e, Event::ProfileUploadCompleted { .. })));
        }

        #[test]
        fn thirty_two_step_profile_is_at_the_boundary() {
            let mut core = CremaCore::new();
            let p = profile(
                "Max",
                (0..32).map(|i| step(&format!("s{i}"), Pump::Pressure, None)).collect(),
            );
            let started = core.upload_profile(&p, Duration::ZERO).unwrap();
            // 1 header + 32 frames + 0 extensions + 1 tail = 34 writes.
            assert_eq!(started.commands.len(), 34);
        }

        #[test]
        fn empty_profile_returns_no_steps_err() {
            let mut core = CremaCore::new();
            let p = profile("Empty", vec![]);
            let err = core.upload_profile(&p, Duration::ZERO).unwrap_err();
            assert!(matches!(err, AppError::ProfileUpload(_)));
            assert!(!core.profile_upload_in_progress());
        }

        #[test]
        fn thirty_three_step_profile_returns_too_many_err() {
            let mut core = CremaCore::new();
            let p = profile(
                "TooMany",
                (0..33).map(|i| step(&format!("s{i}"), Pump::Pressure, None)).collect(),
            );
            assert!(matches!(
                core.upload_profile(&p, Duration::ZERO),
                Err(AppError::ProfileUpload(_))
            ));
            assert!(!core.profile_upload_in_progress());
        }

        #[test]
        fn wrong_frame_to_write_byte_fails_with_unexpected_ack() {
            let mut core = CremaCore::new();
            let p = profile(
                "Two",
                vec![
                    step("a", Pump::Pressure, None),
                    step("b", Pump::Pressure, None),
                ],
            );
            let _ = core.upload_profile(&p, Duration::ZERO).unwrap();
            // First expected ack carries FrameToWrite = 0; feed back a 5 instead.
            let mut bogus = [0u8; 8];
            bogus[0] = 5;
            let out = core.on_notification(Source::De1FrameAck, &bogus, 10);
            assert!(out.events.iter().any(|e| matches!(
                e,
                Event::ProfileUploadFailed {
                    reason: ProfileUploadFailure::UnexpectedAck {
                        expected: 0,
                        got: 5,
                    }
                }
            )));
            assert!(!core.profile_upload_in_progress());
        }

        #[test]
        fn frame_ack_with_no_upload_in_flight_is_dropped() {
            let mut core = CremaCore::new();
            let mut bogus = [0u8; 8];
            bogus[0] = 0;
            let out = core.on_notification(Source::De1FrameAck, &bogus, 10);
            assert!(out.events.is_empty(), "late acks must be ignored");
        }

        #[test]
        fn cancel_mid_upload_emits_aborted() {
            let mut core = CremaCore::new();
            let p = profile("Two", vec![step("a", Pump::Pressure, None), step("b", Pump::Pressure, None)]);
            let _ = core.upload_profile(&p, Duration::ZERO).unwrap();
            let out = core.cancel_profile_upload();
            assert!(out.events.iter().any(|e| matches!(
                e,
                Event::ProfileUploadFailed {
                    reason: ProfileUploadFailure::Aborted
                }
            )));
            assert!(!core.profile_upload_in_progress());
        }

        #[test]
        fn cancel_without_upload_is_a_noop() {
            let mut core = CremaCore::new();
            let out = core.cancel_profile_upload();
            assert!(out.events.is_empty());
            assert!(out.commands.is_empty());
        }

        #[test]
        fn re_entry_aborts_prior_upload_then_starts_anew() {
            let mut core = CremaCore::new();
            let p = profile("First", vec![step("a", Pump::Pressure, None)]);
            let _ = core.upload_profile(&p, Duration::ZERO).unwrap();
            let q = profile("Second", vec![step("a", Pump::Pressure, None)]);
            let out = core.upload_profile(&q, Duration::from_millis(50)).unwrap();
            // First event should be Aborted (for the prior upload), then Started.
            assert!(matches!(
                out.events[0],
                Event::ProfileUploadFailed {
                    reason: ProfileUploadFailure::Aborted
                }
            ));
            assert!(matches!(
                out.events[1],
                Event::ProfileUploadStarted { .. }
            ));
            assert!(core.profile_upload_in_progress());
        }

        #[test]
        fn on_tick_after_timeout_fires_ack_timeout() {
            let mut core = CremaCore::new();
            let p = profile("Two", vec![step("a", Pump::Pressure, None), step("b", Pump::Pressure, None)]);
            let _ = core.upload_profile(&p, Duration::ZERO).unwrap();
            // Tick at 5 seconds (the timeout); no acks have arrived.
            let tick = core.on_tick(
                u64::try_from(PROFILE_UPLOAD_ACK_TIMEOUT.as_millis()).unwrap_or(u64::MAX),
            );
            assert!(tick.events.iter().any(|e| matches!(
                e,
                Event::ProfileUploadFailed {
                    reason: ProfileUploadFailure::AckTimeout { awaiting: 0 }
                }
            )));
            assert!(!core.profile_upload_in_progress());
        }

        #[test]
        fn timeout_resets_on_each_ack() {
            let mut core = CremaCore::new();
            let p = profile("Two", vec![step("a", Pump::Pressure, None), step("b", Pump::Pressure, None)]);
            let _ = core.upload_profile(&p, Duration::ZERO).unwrap();
            // Ack frame 0 at t=4s (just under the timeout).
            let mut ack0 = [0u8; 8];
            ack0[0] = 0;
            let _ = core.on_notification(Source::De1FrameAck, &ack0, 4_000);
            // Tick at t=8s: still under 5s past the last ack, so NO timeout.
            let tick = core.on_tick(8_000);
            assert!(
                !tick.events.iter().any(|e| matches!(
                    e,
                    Event::ProfileUploadFailed {
                        reason: ProfileUploadFailure::AckTimeout { .. }
                    }
                )),
                "the watchdog resets on each ack"
            );
            assert!(core.profile_upload_in_progress());
        }

        #[test]
        fn reset_mid_upload_clears_state() {
            let mut core = CremaCore::new();
            let p = profile("Two", vec![step("a", Pump::Pressure, None), step("b", Pump::Pressure, None)]);
            let _ = core.upload_profile(&p, Duration::ZERO).unwrap();
            assert!(core.profile_upload_in_progress());
            core.reset();
            assert!(!core.profile_upload_in_progress());
            // reset() also drops the last-active title.
            assert_eq!(core.active_profile_title(), None);
        }

        #[test]
        fn completed_upload_records_active_title() {
            let mut core = CremaCore::new();
            assert_eq!(core.active_profile_title(), None);
            let p = profile("Active!", vec![step("a", Pump::Pressure, None)]);
            let started = core.upload_profile(&p, Duration::ZERO).unwrap();
            let _ = ack_every_frame(&mut core, &started.commands, 10);
            assert_eq!(core.active_profile_title(), Some("Active!"));
        }

        #[test]
        fn header_read_emits_profile_header_read_event() {
            let mut core = CremaCore::new();
            // A 5-byte ShotHeader: version 1, frame_count 4, preinfuse 2,
            // min_pressure raw=0x10 (1.0 bar), max_flow raw=0x60 (6.0 ml/s).
            let packet = [0x01, 4, 2, 0x10, 0x60];
            let out = core.on_notification(Source::De1ProfileHeader, &packet, 0);
            assert!(out.events.iter().any(|e| matches!(
                e,
                Event::ProfileHeaderRead {
                    frame_count: 4,
                    preinfuse_frame_count: 2,
                    ..
                }
            )));
        }

        #[test]
        fn truncated_header_emits_decode_error() {
            let mut core = CremaCore::new();
            let out = core.on_notification(Source::De1ProfileHeader, &[0x01, 4], 0);
            assert!(
                out.events
                    .iter()
                    .any(|e| matches!(e, Event::DecodeError { .. }))
            );
        }
    }
}

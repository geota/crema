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

mod capture;
pub mod de1_uuids;
pub mod error;
pub mod event;
pub mod firmware_info;

pub use de1_uuids::{
    De1Uuids, de1_uuids, de1_uuids_json, de1_write_target_uuid, de1_write_target_uuid_by_name,
};
pub use error::AppError;
pub use event::{Command, CoreOutput, Event, ProfileUploadFailure, Source, WriteTarget};
pub use firmware_info::{FirmwareUpdateStatus, LATEST_KNOWN_FIRMWARE_BUILD};

use capture::{CaptureRecorder, slice_to_jsonl};

use std::time::Duration;

use de1_domain::{
    AutoStop, Estimate, FlowAlgorithm, FlowEstimator, LineFreqDetector, Profile,
    STOP_WEIGHT_BEFORE, ShotEvent, ShotMonitor, ShotPhase, SteamEvent, SteamMonitor, StopConfig,
    StopReason, StopTargets, VolumeIntegrator, WaterEvent, WaterMonitor, WeightUnit,
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
/// A 5-second margin is ~100× the typical real-DE1 round-trip and clears
/// Web Bluetooth's worst-case back-pressure window.
pub const PROFILE_UPLOAD_ACK_TIMEOUT: Duration = Duration::from_secs(5);
use de1_scale::{Scale, ScaleCapabilities, ScaleConfigUpdate, ScaleUuids, TimerCommand};
#[cfg(test)]
use de1_scale::{bookoo, decent_scale};

/// Re-export of `de1_scale::Scale` so the wasm + future Android bridges can
/// reach the stateless name / weight-packet identification helpers
/// ([`Scale::identify`], [`Scale::guess_from_first_weight_packet`])
/// without each bridge crate taking a direct dep on `de1-scale`.
pub use de1_scale::Scale as ScaleId;

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

/// Minimum scale-derived flow (g/s) below which the weight-based puck
/// resistance is undefined. Same shape and motivation as
/// [`RESISTANCE_FLOW_FLOOR_ML_PER_S`]: at near-zero flow the `P / F²`
/// denominator gets tiny and noisy. Reuses the same numeric floor — the
/// scale's g/s and the DE1's ml/s are both ~1:1 with espresso flow at
/// the relevant magnitudes, and the legacy TCL (`gui.tcl:3414-3416`)
/// gates on the same threshold for both signals.
const RESISTANCE_WEIGHT_FLOW_FLOOR_G_PER_S: f32 = RESISTANCE_FLOW_FLOOR_ML_PER_S;

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
    if r.is_finite() { Some(r) } else { None }
}

/// Puck resistance computed from the *scale's* mass-flow rate rather than
/// the DE1's group-flow flowmeter — `group_pressure / weight_flow²`.
///
/// Why a sibling metric: the DE1's flowmeter sees what the *pump* dispenses
/// upstream of the puck, which includes water retained by the basket /
/// dispersion screen on the way in. A connected scale measures what
/// actually exits the puck into the cup — the true espresso output. During
/// pre-infusion and the early ramp the two diverge sharply (the pump is
/// running, but no espresso is dripping yet); during the main pour they
/// converge. The weight-derived resistance is therefore the truer
/// extraction signal whenever a scale is paired. The legacy de1app TCL
/// computes both — see `de1plus/gui.tcl:3414-3416` for the `P /
/// scale_weight_rate²` formula behind the chart's overlay. Crema surfaces
/// both on every Telemetry event so the chart can prefer the
/// scale-derived value and fall back to the pump-derived one per sample.
///
/// Units: `bar / (g/s)²`. Returns `None` when the flow is below
/// [`RESISTANCE_WEIGHT_FLOW_FLOOR_G_PER_S`] or any input is non-finite —
/// same guard the DE1-flow sibling carries, for the same noisy-low-flow
/// reason.
fn puck_resistance_weight(group_pressure_bar: f32, weight_flow_g_per_s: f32) -> Option<f32> {
    if !group_pressure_bar.is_finite() || !weight_flow_g_per_s.is_finite() {
        return None;
    }
    if weight_flow_g_per_s < RESISTANCE_WEIGHT_FLOW_FLOOR_G_PER_S {
        return None;
    }
    let r = group_pressure_bar / (weight_flow_g_per_s * weight_flow_g_per_s);
    if r.is_finite() { Some(r) } else { None }
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

    /// Clear only the scale-derived running peaks (peak weight + final
    /// weight). Used by [`CremaCore::reset_scale_peaks`] so the user can
    /// drain the Scale-page peak display without disturbing the
    /// pressure / temperature peaks that the DE1 telemetry feeds.
    fn reset_scale(&mut self) {
        self.peak_weight = None;
        self.final_weight = None;
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
    /// Timestamp the in-progress shot began (Espresso-state entry). `None` when
    /// no shot is in progress. Gates shot-metrics + drives the auto-tare; the
    /// telemetry elapsed clock is `flow_started`, not this.
    shot_started: Option<Duration>,
    /// Timestamp the shot's FLOW began — the first preinfusion/pouring substate
    /// (pump on). `None` until flow starts. `Event::Telemetry.elapsed` runs from
    /// here, not `shot_started`, so the pre-pour heating/stabilising phase isn't
    /// counted in the displayed shot time — matching de1app and the recorded
    /// `ShotRecord.duration` (the `ShotMonitor` times the same window).
    flow_started: Option<Duration>,
    /// The connected scale's codec, once identified.
    scale: Option<Scale>,
    /// Smooths the scale stream into weight + flow for display.
    flow: FlowEstimator,
    /// Whether to tare the connected scale automatically on shot start.
    /// Latched flag, default `true`; the shell pushes the user pref via
    /// [`set_auto_tare`](Self::set_auto_tare). Consulted on
    /// `ShotEvent::Started` regardless of whether the shot was initiated
    /// via Crema or the DE1's on-machine touch (GHC) — the gating event is
    /// the same.
    auto_tare: bool,
    /// Tare-settling gate. `Some(t)` from the auto-tare at shot start (where
    /// `t` is when the tare command was sent) until the hardware tare lands;
    /// `None` otherwise. While set, [`handle_scale_weight`](Self::handle_scale_weight)
    /// reports weight as zero so the pre-tare pan/cup weight never reaches the
    /// live chart or the recorded shot. Cleared on the first near-zero reading
    /// (tare confirmed) or after a fallback timeout. Mirrors de1app's
    /// `on_tare_seen`, which zeroes its weight shift-registers when a sub-0.04 g
    /// reading arrives within 1 s of the tare request (`device_scale.tcl`).
    tare_gate: Option<Duration>,
    /// Whether stop-at-weight (SAW) is enabled. Latched flag, default
    /// `true`; the shell pushes the user pref via
    /// [`set_stop_on_weight`](Self::set_stop_on_weight). Even when `true`,
    /// SAW only fires if a scale is connected and an effective target
    /// weight resolves > 0 (see [`effective_target_weight`](Self::effective_target_weight)).
    stop_on_weight: bool,
    /// Per-shot SAW kill switch. Latched flag, default `false`; the shell
    /// pushes `true` when the user toggles the QC yield dot OFF, back to
    /// `false` when it turns ON. Independent of
    /// [`stop_on_weight`](Self::stop_on_weight): either flag suppresses
    /// SAW arming. Reseeded to `false` on every active-profile change so
    /// each profile load starts with the user's last persistent intent.
    weight_target_disabled: bool,
    /// Opt-in: arm the profile's volume limit (SAV) even while a scale is
    /// registered. Default `false` — the reference-app consensus treats
    /// volume as a NO-SCALE FALLBACK only (it would otherwise routinely
    /// pre-empt the weight target, because the integrated-flow estimate
    /// runs ahead of true yield). Mirrors Decenza's `ignoreVolumeWithScale`
    /// (inverted sense). Set via
    /// [`set_volume_stop_with_scale`](Self::set_volume_stop_with_scale).
    volume_stop_with_scale: bool,
    /// The active profile's recipe target weight, grams. `None` = no
    /// recipe target. Set via
    /// [`set_profile_target_weight`](Self::set_profile_target_weight) when
    /// the shell activates or edits a profile.
    profile_target_weight: Option<f32>,
    /// The per-shot dial override weight, grams. `None` = no override
    /// (fall back to the profile recipe). Set via
    /// [`set_shot_target_weight`](Self::set_shot_target_weight) when the
    /// QC stepper moves.
    shot_target_weight: Option<f32>,
    /// The active profile's volume limit, millilitres (SAV). `None` = no
    /// limit. Set via
    /// [`set_profile_volume_limit`](Self::set_profile_volume_limit).
    profile_volume_limit: Option<f32>,
    /// The global maximum shot duration, seconds. `None` = no max. Set
    /// via [`set_max_shot_duration`](Self::set_max_shot_duration); the
    /// shell binds this to a user-tunable Settings field (legacy default
    /// 60 s).
    max_shot_duration: Option<f32>,
    /// The live auto-stop controller, for the duration of one shot.
    auto_stop: Option<AutoStop>,
    /// Timestamp of the most recent scale-weight reading, for the lost-scale
    /// watchdog. `None` until the connected scale reports once.
    last_scale_weight: Option<Duration>,
    /// The most recent smoothed scale weight (g) and mass-flow rate (g/s)
    /// from the [`FlowEstimator`]. Both `None` until a scale has reported
    /// at least once. Fed by [`handle_scale_weight`](Self::handle_scale_weight);
    /// read by [`handle_shot_sample`](Self::handle_shot_sample) so each
    /// `Event::Telemetry` can carry the scale-derived weight + flow + the
    /// weight-based puck resistance ([`puck_resistance_weight`]) alongside
    /// the DE1-flow sibling. Cleared by [`reset`](Self::reset) so a
    /// fresh session starts blank. Kept here (rather than peeked off
    /// the `FlowEstimator`) because the estimator's public API only
    /// returns an `Estimate` *as a sample is fed* — we need the last
    /// returned value for the cross-event lookup.
    last_scale_estimate: Option<Estimate>,
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
    /// to own this — pushed into the core so web + Android
    /// share one byte-for-byte implementation and the recorder side-effect
    /// piggybacks the existing wasm-boundary crossing.
    /// Rolling BLE-capture buffer + identity-keeper. The identity-keeper is
    /// the single source of truth for the META prelude (firmware build,
    /// machine model, serial, scale name) — no parallel scalars on
    /// `CremaCore`. See [`CaptureRecorder::meta_snapshot`].
    capture: CaptureRecorder,
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
    /// Whether the connected scale should be powered off when the DE1
    /// enters [`MachineState::Sleep`]. Off by default; toggled by the
    /// shell via [`set_auto_off_scale_on_sleep`](Self::set_auto_off_scale_on_sleep).
    ///
    /// Capability-gated by the scale's [`Scale::power_off_command`] — the
    /// reactive auto-policy in [`handle_state`](Self::handle_state) asks
    /// the scale for its power-off bytes and silently no-ops when the
    /// scale lacks one. Today that covers Decent / Eureka / Solo.
    auto_off_scale_on_sleep: bool,
    /// Whether this core is a **read-only mirror** — a secondary device
    /// mirroring a primary's DE1 over the LAN (the multi-device M2 feature).
    /// When `true`, the autonomous machine/scale writes the core produces
    /// while decoding the mirrored notification stream (SAW auto-stop,
    /// frame-skip acks, steam-eco target rewrites, watchdog actions) are
    /// dropped before they leave [`on_notification`](Self::on_notification) /
    /// [`on_tick`](Self::on_tick) — the mirror still decodes telemetry and
    /// fires every `Event` for the UI, but never drives hardware. The primary
    /// owns control; the secondary relays user intent to it via Control
    /// frames. Off by default; the shell flips it via
    /// [`set_read_only`](Self::set_read_only) on entering secondary mode.
    read_only: bool,
    /// Test-only override that flips [`firmware_locks_writes`](Self::firmware_locks_writes)
    /// to `true` so unit tests can verify the per-setter lockout-attribution
    /// path. v1's lockout state is a stub (always `false`); v2 will replace
    /// this field with the real upload-phase state machine.
    #[cfg(test)]
    firmware_lock_override: bool,
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
            shot_started: None,
            flow_started: None,
            scale: None,
            flow: FlowEstimator::new(FlowAlgorithm::default()),
            auto_tare: true,
            tare_gate: None,
            stop_on_weight: true,
            weight_target_disabled: false,
            volume_stop_with_scale: false,
            profile_target_weight: None,
            shot_target_weight: None,
            profile_volume_limit: None,
            max_shot_duration: None,
            auto_stop: None,
            last_scale_weight: None,
            last_scale_estimate: None,
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
            weight_unit_pref: WeightUnit::default(),
            auto_off_scale_on_sleep: false,
            read_only: false,
            #[cfg(test)]
            firmware_lock_override: false,
        }
    }

    /// The effective AC mains frequency the volume integrator will use.
    /// Returns the user override if set, else the auto-detector's locked
    /// value (if any), else `None` — in which case the integrator falls
    /// back to host-clock `dt` (BLE-jitter contaminated, ~5% volume drift).
    pub fn line_frequency_hz(&self) -> Option<f32> {
        self.line_freq_override
            .or(self.line_freq_detector.locked_hz())
    }

    /// Pin the AC mains frequency. `Some(50.0)` / `Some(60.0)` overrides
    /// the auto-detector; `None` returns to auto. Persisting the setting
    /// is the shell's job — pass `Some(hz)` on `loadCore()` once read from
    /// localStorage, or here when the user changes it in Settings.
    pub fn set_line_frequency_override(&mut self, hz: Option<f32>) {
        self.line_freq_override = hz;
    }

    /// Whether a firmware upload is currently locking out other writes.
    ///
    /// v1 always returns `false` — firmware update is a v2 feature.
    /// The stub is carried now so every
    /// write method added in v1 can include the one-line lockout guard
    /// ([`refuse_if_firmware_locked`](Self::refuse_if_firmware_locked)) at the
    /// time the write lands; retrofitting nine guards at v2 time is more work
    /// than carrying them piecemeal.
    ///
    /// v2 will return `true` for the `Erase..Verifying` phases of a firmware
    /// upload and `false` everywhere else; read-only methods bypass this check
    /// because they cannot disturb an in-flight upload.
    pub fn firmware_locks_writes(&self) -> bool {
        // Test-only override lets unit tests exercise the lockout path so the
        // per-setter [`Event::FirmwareLockoutHit`] attribution is verifiable
        // before v2 lands the real upload-phase state machine.
        #[cfg(test)]
        {
            self.firmware_lock_override
        }
        #[cfg(not(test))]
        {
            false
        }
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
    ///
    /// The shell-supplied `advertised_name` is **not cached** anywhere — the
    /// capture META prelude derives its scale-name field from the first
    /// SCALE_WEIGHT packet's wire signature
    /// ([`Scale::guess_from_first_weight_packet`]), which a replay can do
    /// just as well. Round-trip preserves the canonical scale type, not a
    /// user-customised BLE name.
    /// `discovered_services` are the GATT service UUIDs the shell found on the
    /// connected device (empty is fine — falls back to a name-only match). A
    /// distinctive service identifies the scale even when the advertised name
    /// doesn't (Acaia generation, a rebrand, or mixed-case). See
    /// [`Scale::identify`].
    pub fn connect_scale(
        &mut self,
        advertised_name: &str,
        discovered_services: &[String],
    ) -> Option<String> {
        self.scale = Scale::identify(advertised_name, discovered_services);
        // A newly connected scale has not yet been asked for its serial /
        // settings; re-arm the one-shot connect-time queries.
        self.scale_config_queried = false;
        // A scale arriving mid-shot unlocks the SAW leg of an already-armed
        // AutoStop (issue #15 — the arm-once guard otherwise baked
        // `weight: None` in for the whole shot).
        self.refresh_auto_stop_targets();
        self.scale.as_ref().map(|scale| scale.label().to_owned())
    }

    /// Disconnect the scale: drop the identified codec and reset every
    /// scale-derived runtime reading back to the fresh state [`CremaCore::new`]
    /// seeds (the stream smoother, the last weight / estimate, the
    /// stale-watchdog flag, the one-shot connect-time query latch, and the
    /// scale-derived peaks). User prefs (auto-tare, stop-on-weight) and the
    /// shot / profile config are intentionally NOT touched — only the live
    /// scale connection + its readings.
    ///
    /// The shell calls this when the scale's BLE link drops, so a reconnect
    /// starts clean and a vanished scale can't leave a ghost weight on the next
    /// `Telemetry`. (The De1 sibling drops the whole machine link via
    /// [`reset`](Self::reset); this is the narrower scale-only equivalent —
    /// AND4 — and benefits every shell.)
    pub fn disconnect_scale(&mut self) {
        self.scale = None;
        self.flow = FlowEstimator::new(FlowAlgorithm::default());
        self.last_scale_weight = None;
        self.last_scale_estimate = None;
        self.scale_stale_reported = false;
        self.scale_config_queried = false;
        self.shot_metrics.reset_scale();
        // A scale vanishing mid-shot drops the SAW leg of an armed AutoStop
        // (its weight stream is gone; volume / max-time keep the shot bounded).
        self.refresh_auto_stop_targets();
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

    /// Enable or disable auto-tare on shot start (default `true`). The
    /// connected scale is tared on `ShotEvent::Started` only when this
    /// flag is `true` *and* a scale is connected. Consulted regardless of
    /// who initiated the shot (Crema-tap or GHC-tap on the machine).
    pub fn set_auto_tare(&mut self, enabled: bool) {
        self.auto_tare = enabled;
    }

    /// Enable or disable stop-at-weight (default `true`). When `false`,
    /// SAW never arms even if a target weight is configured. Volume and
    /// max-time stops remain independent.
    pub fn set_stop_on_weight(&mut self, enabled: bool) {
        self.stop_on_weight = enabled;
        self.refresh_auto_stop_targets();
    }

    /// Per-shot kill switch for the weight target — when `true`, the
    /// orchestrator skips weight-based auto-stop arming this shot even
    /// if a target is configured. Independent of
    /// [`set_stop_on_weight`](Self::set_stop_on_weight) (the user's
    /// persistent pref); either flag suppresses arming. The shell flips
    /// this when the user toggles the QC yield dot OFF, and resets to
    /// `false` on every active-profile change.
    pub fn set_weight_target_disabled(&mut self, disabled: bool) {
        self.weight_target_disabled = disabled;
        self.refresh_auto_stop_targets();
    }

    /// Opt-in: arm the profile's volume limit (SAV) even while a scale is
    /// registered — for users who want BOTH caps racing. Default `false`:
    /// volume is a no-scale fallback only (see
    /// [`effective_stop_targets`](Self::effective_stop_targets)). The
    /// firmware-side tail stop stays disarmed either way —
    /// [`upload_profile`](Self::upload_profile) always writes 0.
    pub fn set_volume_stop_with_scale(&mut self, enabled: bool) {
        self.volume_stop_with_scale = enabled;
        self.refresh_auto_stop_targets();
    }

    /// Clear the running scale-derived peaks (peak weight + final
    /// weight) without disturbing pressure / temperature peaks. The
    /// Scale page's "Reset peak" button calls this when the user wants
    /// a fresh measurement window — e.g. between dose-in and brew.
    pub fn reset_scale_peaks(&mut self) {
        self.shot_metrics.reset_scale();
    }

    /// Set the active profile's recipe target weight, in grams. `None` =
    /// no recipe target. Defensive: a finite value `<= 0` normalises to
    /// `None`, mirroring the legacy wire convention where `0` means
    /// "disabled."
    pub fn set_profile_target_weight(&mut self, grams: Option<f32>) {
        self.profile_target_weight = grams.filter(|g| g.is_finite() && *g > 0.0);
        self.refresh_auto_stop_targets();
    }

    /// Set the per-shot dial override weight, in grams. `None` clears the
    /// override and falls back to the profile recipe target. Same defensive
    /// `<= 0 → None` normalisation as
    /// [`set_profile_target_weight`](Self::set_profile_target_weight).
    pub fn set_shot_target_weight(&mut self, grams: Option<f32>) {
        self.shot_target_weight = grams.filter(|g| g.is_finite() && *g > 0.0);
        self.refresh_auto_stop_targets();
    }

    /// Set the active profile's volume limit (SAV), in millilitres.
    /// `None` = no limit. Defensive `<= 0 → None`.
    pub fn set_profile_volume_limit(&mut self, milliliters: Option<f32>) {
        self.profile_volume_limit = milliliters.filter(|v| v.is_finite() && *v > 0.0);
        self.refresh_auto_stop_targets();
    }

    /// Set the global maximum shot duration, in seconds. `None` = no max.
    /// Defensive `<= 0 → None`. The legacy app default is 60 s.
    pub fn set_max_shot_duration(&mut self, seconds: Option<f32>) {
        self.max_shot_duration = seconds.filter(|s| s.is_finite() && *s > 0.0);
        self.refresh_auto_stop_targets();
    }

    /// The target weight currently in effect for SAW. Priority:
    ///
    /// 1. Per-shot dial override ([`shot_target_weight`](Self::shot_target_weight))
    ///    wins when set.
    /// 2. Otherwise the active profile's recipe target
    ///    ([`profile_target_weight`](Self::profile_target_weight)).
    /// 3. If both are `None`, no weight target.
    ///
    /// Does not consider `stop_on_weight` or scale-connected — that's
    /// [`effective_stop_targets`](Self::effective_stop_targets)'s job.
    fn effective_target_weight(&self) -> Option<f32> {
        self.shot_target_weight.or(self.profile_target_weight)
    }

    /// Compose the [`StopTargets`] that should arm the [`AutoStop`] on the
    /// next shot's first flowing phase. Returns `None` when none of the
    /// three stop modes resolve to an active target.
    ///
    /// SAW is additionally gated on `stop_on_weight && scale.is_some()`;
    /// SAV and max-time are independent of the scale.
    fn effective_stop_targets(&self) -> Option<StopTargets> {
        let weight = if self.stop_on_weight && !self.weight_target_disabled && self.scale.is_some()
        {
            self.effective_target_weight()
        } else {
            None
        };
        // Volume (SAV) is a NO-SCALE FALLBACK by default, never a competitor
        // to SAW — the reference-app consensus (reaprime arms volume only
        // when the scale is absent/lost; de1app zeroes the volume target when
        // weight stopping is in use, "since it's not as accurate"; Decenza's
        // ignoreVolumeWithScale). With a scale registered, the profile's
        // volume limit would routinely pre-empt the weight target because the
        // integrated-flow estimate runs ahead of true yield.
        // `volume_stop_with_scale` opts back into both caps racing. A scale
        // lost mid-shot re-arms this leg via refresh_auto_stop_targets — the
        // same degrade-to-volume reaprime does.
        let volume = if self.scale.is_some() && !self.volume_stop_with_scale {
            None
        } else {
            self.profile_volume_limit
        };
        let max_time = self.max_shot_duration;
        if weight.is_none() && volume.is_none() && max_time.is_none() {
            None
        } else {
            Some(StopTargets {
                weight,
                volume,
                max_time,
            })
        }
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
    pub fn write_calibration(&self, target: CalTarget, reported: f32, measured: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("write_calibration") {
            return out;
        }
        let mut out = CoreOutput::default();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1Calibration,
            data: Calibration::write(target, reported, measured)
                .encode()
                .to_vec(),
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
    ///
    /// **Range clamps** (defense-in-depth — the web shell already steppers
    /// to similar ranges, but external callers / replays / future
    /// bridges could bypass that):
    ///
    /// - `steam_temp_c`: 0 (disabled) or 135–170 °C. Legacy default
    ///   skin slider runs 134–170; legacy `binary.tcl:184-186` forces
    ///   values `<135` to `0` (disable the steam heater entirely).
    ///   Values above 170 °C risk over-heating the steam boiler —
    ///   neither TCL nor reaprime intends them. The 135 °C floor
    ///   matches TCL exactly.
    /// - `hot_water_temp_c`: 0–100 °C. Above 100 °C boils → pressure
    ///   event. The legacy default is 85 °C; reaprime doesn't clamp.
    /// - `group_temp_c`: 0–105 °C (matches legacy `vars.tcl`
    ///   `range_check_shot_variables` espresso_temperature 0..105).
    ///   The group cannot usefully run above 100 °C; the +5 °C head-
    ///   room matches the TCL guard.
    ///
    /// The `*_timeout_s` and `*_volume_ml` fields are not clamped here
    /// — they encode to a `u8` (capped at 255 in the protocol layer
    /// via `u8p0_encode`) and a stale or zero value has no firmware-
    /// damaging effect (`0` is interpreted as "use machine default"
    /// per legacy `binary.tcl`).
    pub fn set_steam_hotwater_settings(&mut self, settings: ShotSettings) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_steam_hotwater_settings") {
            return out;
        }
        self.steam_hotwater_settings = Some(Self::clamp_shot_settings(settings));
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

    /// Apply the documented range clamps to a [`ShotSettings`] before it
    /// is retained or written. See [`set_steam_hotwater_settings`] for
    /// the full rationale.
    ///
    /// - `steam_temp_c`: 0 (disabled) or 135–170 °C — values in
    ///   `(0, 135)` are snapped to 0 to match legacy
    ///   `binary.tcl:184-186` (which forces `<135` to 0 so the heater
    ///   isn't held at a useless sub-steam target).
    /// - `hot_water_temp_c`: clamped 0..=100 °C.
    /// - `group_temp_c`: clamped 0..=105 °C — matches legacy
    ///   `vars.tcl:range_check_shot_variables`.
    fn clamp_shot_settings(mut settings: ShotSettings) -> ShotSettings {
        // Steam target: 0 (off) or 135..=170 °C. Match legacy
        // `binary.tcl:184-186`: any value below 135 (the lowest
        // useful steam temperature) is snapped to 0 = "heater off".
        if settings.steam_temp_c > 0.0 && settings.steam_temp_c < 135.0 {
            settings.steam_temp_c = 0.0;
        } else if settings.steam_temp_c > 170.0 {
            settings.steam_temp_c = 170.0;
        }
        // Hot-water temperature: 0..=100 °C (above 100 boils → pressure).
        settings.hot_water_temp_c = settings.hot_water_temp_c.clamp(0.0, 100.0);
        // Espresso group target: 0..=105 °C (TCL range_check_shot_variables).
        settings.group_temp_c = settings.group_temp_c.clamp(0.0, 105.0);
        settings
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
    /// before calling this). `byte_len` should be **4**: every DE1 MMR register
    /// is a 32-bit word, and a sub-word write is silently dropped by firmware
    /// (the GHC-toggle snap-back + the historical FanThreshold/SteamFlow
    /// short-packet bugs — `.scratch/register-audit/AUDIT.md` F1). It sets the
    /// wire `Len` byte of the `WriteToMMR` packet.
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
    /// `set_fan_temperature_threshold`). MMR `0x803808`, 4-byte LE
    /// (one MMR word). reaprime `de1.models.dart:247 fanThreshold`
    /// declares `MmrValueKind.int32` (4 bytes); matched here. The
    /// pre-fix `byte_len=1` was the same class of short-packet bug
    /// fixed for the 8 heater-tweak setters in `9ef8ee5` — fan_threshold
    /// was outside that struct and got missed. Raw °C, no scaling;
    /// high 3 bytes are zero-pad.
    ///
    /// Clamped to 0..=50 °C — matches reaprime `MMRItem.fanThreshold`
    /// (`min: 0, max: 50`); legacy TCL has no clamp.
    pub fn set_fan_threshold(&self, temp_c: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_fan_threshold") {
            return out;
        }
        // Range 0..=50 °C — matches reaprime `fanThreshold` min/max;
        // legacy TCL is unbounded.
        let clamped = temp_c.min(50);
        mmr_write_command(MmrRegister::FanThreshold, u32::from(clamped), 4)
    }

    /// Set the tank desired water-temperature threshold, in °C (legacy
    /// `set_tank_temperature_threshold` — the immediate value only; the
    /// legacy "preheat with 60 °C for 4 s" dance is a shell-side concern).
    /// MMR `0x80380C`, 1-byte.
    pub fn set_tank_threshold(&self, temp_c: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_tank_threshold") {
            return out;
        }
        // 4-byte word: every DE1 MMR write must carry a full 32-bit LE word; de1app
        // (Len="04") + reaprime (setUint32 LE) always do, and a sub-word write is dropped
        // by firmware (.scratch/register-audit/AUDIT.md F1). Value zero-extends, so 4 is safe.
        mmr_write_command(MmrRegister::TankTempThreshold, u32::from(temp_c), 4)
    }

    /// Set the steam flow rate. `ml_per_s` is scaled by ×100 per the
    /// legacy `de1_comms.tcl:1210` + reaprime `de1.models.dart`
    /// (`steamFlow` `writeScale: 100.0`); the stored wire value is
    /// `ml/s × 100` (e.g. 7.0 ml/s → raw 700). MMR `0x803828`, 4-byte.
    /// Pre-2026-05-22 builds wrote `×10` into a 1-byte slot, both
    /// wrong; the 1-byte clamp prevented valid steam targets from
    /// reaching the firmware.
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
    /// `as_secs()`, truncating `0.7s` to `0`.
    ///
    /// Clamped to 0.3..=4.0 s before scaling — reaprime
    /// `MMRItem.steamStartSecs` description: "Valid range 0.0 - 4.0.
    /// **0 may result in an overheated heater. Be careful.**" Legacy
    /// TCL has no clamp. Nobody legitimately wants 0 — it skips the
    /// initial high-flow blast that dissipates accumulated heater heat
    /// into the steam line. 0.3 s is the smallest blast that
    /// meaningfully clears the line; anything shorter is effectively
    /// the firmware's "overheated heater" failure mode.
    /// Crema-stricter than both references on the lower bound.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_steam_highflow_start(&self, seconds: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_steam_highflow_start") {
            return out;
        }
        // Range 0.3..=4.0 s — upper bound matches reaprime steamStartSecs
        // description; lower bound is the post-audit safe floor (the
        // firmware comment warns that 0 risks heater overheat).
        let clamped = seconds.clamp(0.3, 4.0);
        let raw = (clamped * 100.0).round().clamp(0.0, f32::from(u16::MAX)) as u32;
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
    /// MMR `0x80384C`, 4-byte LE (one MMR word). TCL `de1_comms.tcl:1173`
    /// and reaprime `de1.models.dart:hotWaterFlowRate writeScale: 10.0`
    /// both write a 4-byte payload with Len=4; matched here.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_hot_water_flow_rate(&self, ml_per_s: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_hot_water_flow_rate") {
            return out;
        }
        let raw = (ml_per_s * 10.0).round().clamp(0.0, 65_535.0) as u32;
        mmr_write_command(MmrRegister::HotWaterFlowRate, raw, 4)
    }

    /// Set the flush flow rate. `ml_per_s` is scaled `int(10 × rate)`.
    /// MMR `0x803840`, 4-byte LE (one MMR word). TCL `de1_comms.tcl:1192`
    /// and reaprime `de1.models.dart:flushFlowRate writeScale: 10.0`
    /// both write a 4-byte payload with Len=4; matched here.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_flush_flow_rate(&self, ml_per_s: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_flush_flow_rate") {
            return out;
        }
        let raw = (ml_per_s * 10.0).round().clamp(0.0, 65_535.0) as u32;
        mmr_write_command(MmrRegister::FlushFlowRate, raw, 4)
    }

    /// Set the flush water target temperature, °C — the temperature the
    /// DE1 holds while a group-flush cycle runs. Wire value is `°C × 10`
    /// (so 95.0 °C → raw 950). MMR `0x803844`, 4-byte. Per reaprime
    /// (`de1.models.dart:flushTemp` `readScale: 0.1`); the legacy de1app
    /// has no equivalent.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_flush_temp(&self, temp_c: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_flush_temp") {
            return out;
        }
        let raw = (temp_c * 10.0).round().clamp(0.0, f32::from(u16::MAX)) as u32;
        mmr_write_command(MmrRegister::FlushTemp, raw, 4)
    }

    /// Set the flush timeout. `dur` is scaled `int(10 × seconds)`.
    /// MMR `0x803848`, 4-byte LE (one MMR word). TCL `de1_comms.tcl:1199`
    /// and reaprime `de1.models.dart:flushTimeout writeScale: 10.0`
    /// both write a 4-byte payload with Len=4; matched here.
    pub fn set_flush_timeout(&self, dur: Duration) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_flush_timeout") {
            return out;
        }
        // Scale ms-resolution into deciseconds (the legacy `int(10 * s)` form).
        // RS6: `try_from` over `as` — the `.min(65_535)` already bounds it, so the
        // `unwrap_or(MAX)` is unreachable, but it drops the cast + the `#[allow]`.
        let raw = u32::try_from((dur.as_millis() / 100).min(65_535)).unwrap_or(u32::MAX);
        mmr_write_command(MmrRegister::FlushTimeout, raw, 4)
    }

    /// Enable or disable the tablet's USB charging output. MMR `0x803854`,
    /// 1-byte (`0`/`1`).
    pub fn set_usb_charger_on(&self, enabled: bool) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_usb_charger_on") {
            return out;
        }
        mmr_write_command(MmrRegister::UsbChargerOn, u32::from(enabled), 4)
    }

    /// Tell the firmware whether the user is currently present at the machine
    /// (the legacy `set_user_present`; written `1` on screen activity, `0`
    /// when the app goes to sleep). MMR `0x803860`, 4-byte LE.
    ///
    /// **Distinct register** from [`set_feature_flags`](Self::set_feature_flags)
    /// (`0x803858`). The legacy `de1_comms.tcl` writes to both addresses
    /// independently; they have related semantics but separate wire targets.
    ///
    /// Wire `Len=4` — matches TCL `de1_comms.tcl:1166` and reaprime
    /// `_writeMMRInt` (always 4 wire bytes). Normalising the wire-byte
    /// count to 4 aligns Crema with both references.
    pub fn set_user_present(&self, present: bool) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_user_present") {
            return out;
        }
        mmr_write_command(MmrRegister::UserPresent, u32::from(present), 4)
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
        mmr_write_command(MmrRegister::FeatureFlags, u32::from(flags), 4)
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
    /// 230V). +1000 if it's a set value.").
    ///
    /// **Damaging if mis-set** — wrong voltage on the wrong mains can
    /// permanently damage the heater. The shell is responsible for the
    /// type-to-confirm modal (`MainsConfirmModal.svelte`) that the legacy
    /// app uses; this clamp is the last-line guard.
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
    /// that reaprime ends up writing — see the `RESET_*` consts below.
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
    /// read-back.
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
        // de1_controller.defaults.dart:39-51 *post-clamp*. Each
        // RESET_* const documents its wire encoding inline.
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
        // Cal-flow-est: 1.0, wire `× 1000` = 1000, 4-byte LE.
        // byte_len normalised to 4 (matches TCL + reaprime).
        const RESET_CAL_FLOW_EST_RAW: u32 = 1000;
        // Steam purge mode (SteamTwoTapStop register, 0x803850): 0, 4-byte LE bool.
        const RESET_STEAM_PURGE_MODE_RAW: u32 = 0;

        if let Some(out) = self.refuse_if_firmware_locked("reset_machine_defaults") {
            return Ok(out);
        }

        // Eight independent MMR writes, in reaprime's source order. Each
        // tuple is (register, value, byte_len).
        let writes: [(MmrRegister, u32, u8); 8] = [
            (MmrRegister::FanThreshold, RESET_FAN_THRESHOLD_C, 4),
            (
                MmrRegister::HotWaterIdleTemp,
                RESET_HOT_WATER_IDLE_TEMP_RAW,
                4,
            ),
            (MmrRegister::Phase1FlowRate, RESET_PHASE_1_FLOW_RAW, 4),
            (MmrRegister::Phase2FlowRate, RESET_PHASE_2_FLOW_RAW, 4),
            (
                MmrRegister::HeaterUp2Timeout,
                RESET_ESPRESSO_WARMUP_TIMEOUT_RAW,
                4,
            ),
            (MmrRegister::RefillKit, RESET_REFILL_KIT_RAW, 4),
            (
                MmrRegister::CalibrationFlowMultiplier,
                RESET_CAL_FLOW_EST_RAW,
                4,
            ),
            (MmrRegister::SteamTwoTapStop, RESET_STEAM_PURGE_MODE_RAW, 4),
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
    ///
    /// Clamped to 0..=80 °C — matches the web shell's
    /// `MachineSection.svelte` stepper (`min=0, max=80`); neither legacy
    /// TCL nor reaprime clamps.
    ///
    /// **OPEN — wire encoding divergence vs reaprime.** Crema follows
    /// the legacy TCL convention: raw °C as a u8 (e.g., 60°C → wire
    /// byte 60). reaprime's `BengleMmr.matSetPoint` declares
    /// `MmrValueKind.scaledFloat readScale:0.1 writeScale:10.0
    /// min:0 max:800` — wire value = °C × 10 as a 4-byte LE int (e.g.,
    /// 60°C → wire bytes `[88, 02, 00, 00]` for 600). If reaprime is
    /// correct, Crema writes 10× too low: a 60°C target lands on the
    /// firmware as 6.0°C, never warm enough to do anything. Kept as-is
    /// because the wrong direction (10× too HIGH = 600°C target) would
    /// be worse if the legacy convention is the real spec. Resolution
    /// pending hardware test + upstream reaprime confirmation.
    pub fn set_cup_warmer_temperature(&self, temp_c: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_cup_warmer_temperature") {
            return out;
        }
        // Range 0..=80 °C — matches shell stepper bounds.
        let clamped = temp_c.min(80);
        mmr_write_command(MmrRegister::CupWarmerTemp, u32::from(clamped), 4)
    }

    /// Set the flow-calibration multiplier (legacy
    /// `set_calibration_flow_multiplier`). `multiplier` is scaled
    /// `int(1000 × multiplier)`. MMR `0x80383C`, 4-byte LE.
    ///
    /// Clamped to 0.13..=2.0 (raw 130..=2000) — matches reaprime
    /// `MMRItem.calFlowEst` (`min: 130, max: 2000`). A multiplier
    /// outside this range corrupts the flow-estimation algorithm to
    /// the point that profile-driven stops misfire (under-extracted or
    /// flooded). Legacy TCL has no clamp; reaprime is the canonical
    /// source.
    ///
    /// Wire `Len=4` — matches TCL `de1_comms.tcl:1336` and reaprime
    /// `_writeMMRInt` (always 4 wire bytes). The raw value fits in two
    /// bytes (max 2000), but normalising the wire-byte count to 4
    /// aligns Crema with both references.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_calibration_flow_multiplier(&self, multiplier: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_calibration_flow_multiplier") {
            return out;
        }
        // Range 0.13..=2.0 multiplier — matches reaprime calFlowEst.
        let clamped = multiplier.clamp(0.13, 2.0);
        let raw = (clamped * 1000.0).round().clamp(0.0, 65_535.0) as u32;
        mmr_write_command(MmrRegister::CalibrationFlowMultiplier, raw, 4)
    }

    /// Set the hot-water phase-1 flow rate (legacy `heater_tweaks`
    /// `phase_1_flow_rate`). `ml_per_s` is scaled `int(10 × rate)`.
    /// MMR `0x803810`, 4-byte LE (one MMR word). TCL `de1_comms.tcl:1127`
    /// and reaprime `de1.models.dart:heaterUp1Flow writeScale: 10.0`
    /// both write a 4-byte payload with Len=4; matched here.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits
    /// one [`Event::FirmwareLockoutHit`] and no command.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_phase_1_flow_rate(&self, rate_ml_per_s: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_phase_1_flow_rate") {
            return out;
        }
        let raw = (rate_ml_per_s * 10.0).round().clamp(0.0, 65_535.0) as u32;
        mmr_write_command(MmrRegister::Phase1FlowRate, raw, 4)
    }

    /// Set the hot-water phase-2 flow rate (legacy `heater_tweaks`
    /// `phase_2_flow_rate`). `ml_per_s` is scaled `int(10 × rate)`.
    /// MMR `0x803814`, 4-byte LE (one MMR word). TCL `de1_comms.tcl:1128`
    /// and reaprime `de1.models.dart:heaterUp2Flow writeScale: 10.0`
    /// both write a 4-byte payload with Len=4; matched here.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits
    /// one [`Event::FirmwareLockoutHit`] and no command.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_phase_2_flow_rate(&self, rate_ml_per_s: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_phase_2_flow_rate") {
            return out;
        }
        let raw = (rate_ml_per_s * 10.0).round().clamp(0.0, 65_535.0) as u32;
        mmr_write_command(MmrRegister::Phase2FlowRate, raw, 4)
    }

    /// Set the hot-water boiler idle target temperature, °C (legacy
    /// `heater_tweaks` `hot_water_idle_temp`). MMR `0x803818`, 4-byte LE.
    ///
    /// Wire value is `°C × 10` (so 95.0 °C → raw 950). Matches TCL
    /// `de1_comms.tcl:1129` (settings stores the pre-scaled raw, e.g.
    /// `hot_water_idle_temp 950` in `machine.tcl`; the UI displays
    /// `0.1 * raw`) and reaprime `de1.models.dart:waterHeaterIdleTemp
    /// writeScale: 10.0`. Both write a 4-byte payload with Len=4.
    ///
    /// The pre-fix encoding (`u8` raw °C, byte_len=1) wrote the wrong
    /// value (under-scaled by 10×) into the wrong number of wire bytes
    /// — same class of bug as the pre-2026-05-22 `set_steam_flow`
    /// regression (see `set_steam_flow` doc-comment).
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits
    /// one [`Event::FirmwareLockoutHit`] and no command.
    ///
    /// Clamped to 0..=99 °C — matches TCL's documented safe maximum
    /// (`machine.tcl:285 hot_water_idle_temp "990"` = 99.0 °C). 100 °C
    /// boils the water-boiler content, which causes a pressure event
    /// and forces a firmware safety stop; 99 °C stays one degree under
    /// the trigger. The legacy default is 95.0 °C (`machine.tcl
    /// :hot_water_idle_temp 950`); reaprime doesn't clamp this register.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    pub fn set_hot_water_idle_temp(&self, temp_c: f32) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_hot_water_idle_temp") {
            return out;
        }
        // Range 0..=99 °C — matches TCL's documented safe maximum
        // (`machine.tcl:285 hot_water_idle_temp "990"`). At 100 °C the
        // water-boiler boils → pressure event → firmware safety stop;
        // 99 °C stays one degree under. reaprime doesn't clamp this
        // register; the legacy default sits at 95 °C.
        let clamped = temp_c.clamp(0.0, 99.0);
        let raw = (clamped * 10.0).round().clamp(0.0, 65_535.0) as u32;
        mmr_write_command(MmrRegister::HotWaterIdleTemp, raw, 4)
    }

    /// Set the espresso group warmup timeout (legacy `heater_tweaks`
    /// `espresso_warmup_timeout`). MMR `0x803838`, 4-byte LE.
    ///
    /// Wire value is `seconds × 10` (so 3.0 s → raw 30). Matches TCL
    /// `de1_comms.tcl:1130` (settings stores the pre-scaled raw, e.g.
    /// `espresso_warmup_timeout 10` = 1.0 s in `machine.tcl`) and
    /// reaprime `de1.models.dart:heaterUp2Timeout writeScale: 10.0`.
    /// Both write a 4-byte payload with Len=4.
    ///
    /// `timeout` is rounded to the nearest 100 ms and clamped to a u16
    /// raw value (max ~6553.5 s) before writing.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits
    /// one [`Event::FirmwareLockoutHit`] and no command.
    pub fn set_espresso_warmup_timeout(&self, timeout: Duration) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_espresso_warmup_timeout") {
            return out;
        }
        // dur.as_millis() / 100 gives deciseconds — the legacy `int(10 × s)` form.
        // RS6: `try_from` over `as` (the `.min(65_535)` bounds it); drops the cast
        // + the `#[allow]`.
        let raw = u32::try_from((timeout.as_millis() / 100).min(65_535)).unwrap_or(u32::MAX);
        mmr_write_command(MmrRegister::HeaterUp2Timeout, raw, 4)
    }

    /// Set the steam two-tap-stop (legacy `heater_tweaks`
    /// `steam_two_tap_stop`; reaprime `steamPurgeMode`). MMR `0x803850`,
    /// 4-byte LE int (one MMR word). Use `0` to disable and `1` to enable
    /// the double-tap-to-stop steam UX.
    ///
    /// TCL `de1_comms.tcl:1133` + reaprime
    /// `de1.models.dart:steamPurgeMode (MmrValueKind.int32)` both write a
    /// 4-byte payload with Len=4; matched here.
    ///
    /// Refused while a firmware upload is in progress
    /// (see [`firmware_locks_writes`](Self::firmware_locks_writes)) — emits
    /// one [`Event::FirmwareLockoutHit`] and no command.
    pub fn set_steam_two_tap_stop(&self, value: u8) -> CoreOutput {
        if let Some(out) = self.refuse_if_firmware_locked("set_steam_two_tap_stop") {
            return out;
        }
        mmr_write_command(MmrRegister::SteamTwoTapStop, u32::from(value), 4)
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
    fn push_timer_command(scale: &Option<Scale>, command: TimerCommand, out: &mut CoreOutput) {
        if let Some(scale) = scale
            && let Some(data) = scale.timer(command)
        {
            out.commands.push(Command::WriteScale { data });
        }
    }

    /// Build a [`Command`] sequence that enables the connected scale's
    /// on-scale LCD in the unit the user has chosen — capability-driven,
    /// not device-typed.
    ///
    /// Returns [`AppError::UnsupportedOnHardware`] when no scale is
    /// connected or the connected scale has no LCD-enable command
    /// ([`Scale::lcd_enable_command`] returns `None`). The shell can
    /// pre-gate the UI off
    /// [`ScaleCapabilities::can_lcd`](de1_scale::ScaleCapabilities::can_lcd)
    /// to avoid the error path.
    ///
    /// The unit picks variants where the scale's codec exposes them
    /// (Decent has two LCD-enable packets; Skale only emits its trailing
    /// `ENABLE_GRAMS` byte for grams). The reactive auto-policy in
    /// [`handle_state`](Self::handle_state) wires this to the DE1's
    /// [`MachineState::Idle`] transition with the cached
    /// [`weight_unit_pref`](Self) so an Idle entry picks the right
    /// variant.
    ///
    /// For scales that need a periodic heartbeat to keep the LCD awake
    /// (the Decent Scale's [`Scale::heartbeat_command`] returns `Some`),
    /// callers must follow up with periodic
    /// [`scale_heartbeat`](Self::scale_heartbeat) writes — the shell
    /// schedules the clock; the core is sans-IO.
    pub fn enable_scale_lcd(&self, unit: WeightUnit) -> Result<CoreOutput, AppError> {
        let scale = Self::scale_for("scale_lcd_enable", self.scale.as_ref())?;
        let writes = scale
            .lcd_enable_command(matches!(unit, WeightUnit::Grams))
            .ok_or_else(|| Self::unsupported(scale, "scale_lcd_enable", "no LCD-enable command"))?;
        let mut out = CoreOutput::default();
        Self::push_scale_writes(&mut out, writes);
        Ok(out)
    }

    /// Build a [`Command`] sequence that disables the connected scale's
    /// on-scale LCD — capability-driven sibling of
    /// [`enable_scale_lcd`](Self::enable_scale_lcd). Returns
    /// [`AppError::UnsupportedOnHardware`] when no scale or no
    /// LCD-disable command. The reactive auto-policy fires this on
    /// [`MachineState::Sleep`] entry.
    pub fn disable_scale_lcd(&self) -> Result<CoreOutput, AppError> {
        let scale = Self::scale_for("scale_lcd_disable", self.scale.as_ref())?;
        let writes = scale.lcd_disable_command().ok_or_else(|| {
            Self::unsupported(scale, "scale_lcd_disable", "no LCD-disable command")
        })?;
        let mut out = CoreOutput::default();
        Self::push_scale_writes(&mut out, writes);
        Ok(out)
    }

    /// Build a [`Command`] sequence that emits one heartbeat write to
    /// the connected scale — the Decent Scale's LCD sleeps after a few
    /// seconds of host silence and needs a keep-alive every
    /// [`decent_scale::HEARTBEAT_INTERVAL_MS`] ms. The shell schedules
    /// the clock; the core is sans-IO and builds the one-shot command
    /// on demand. Returns [`AppError::UnsupportedOnHardware`] when no
    /// scale or the scale doesn't need a heartbeat
    /// ([`ScaleCapabilities::heartbeat_interval_ms`](de1_scale::ScaleCapabilities::heartbeat_interval_ms)
    /// is `None`).
    pub fn scale_heartbeat(&self) -> Result<CoreOutput, AppError> {
        let scale = Self::scale_for("scale_heartbeat", self.scale.as_ref())?;
        let writes = scale
            .heartbeat_command()
            .ok_or_else(|| Self::unsupported(scale, "scale_heartbeat", "no heartbeat command"))?;
        let mut out = CoreOutput::default();
        Self::push_scale_writes(&mut out, writes);
        Ok(out)
    }

    /// Build a [`Command`] sequence that powers off the connected scale.
    /// Returns [`AppError::UnsupportedOnHardware`] when no scale or the
    /// scale lacks a host-driven power-off (Decent / Eureka / Solo
    /// support it; everything else returns `None`).
    pub fn power_off_scale(&self) -> Result<CoreOutput, AppError> {
        let scale = Self::scale_for("scale_power_off", self.scale.as_ref())?;
        let writes = scale
            .power_off_command()
            .ok_or_else(|| Self::unsupported(scale, "scale_power_off", "no power-off command"))?;
        let mut out = CoreOutput::default();
        Self::push_scale_writes(&mut out, writes);
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

    /// Build a [`Command`] sequence that fires a beep on the connected
    /// scale. Returns [`AppError::UnsupportedOnHardware`] when no scale
    /// or the scale lacks a beep command (Eureka / Solo support it).
    pub fn scale_beep(&self) -> Result<CoreOutput, AppError> {
        let scale = Self::scale_for("scale_beep", self.scale.as_ref())?;
        let writes = scale
            .beep_command()
            .ok_or_else(|| Self::unsupported(scale, "scale_beep", "no beep command"))?;
        let mut out = CoreOutput::default();
        Self::push_scale_writes(&mut out, writes);
        Ok(out)
    }

    /// Build a [`Command`] sequence that explicitly sets the connected
    /// scale's display unit to grams. Eureka / Solo / Difluid expose
    /// this; scales whose unit lives in the LCD-enable bytes (Decent /
    /// Skale) return `None` and the caller gets
    /// [`AppError::UnsupportedOnHardware`]. For scales that only expose
    /// a toggle (Hiroia), call [`toggle_scale_unit`](Self::toggle_scale_unit)
    /// instead.
    pub fn set_scale_unit_grams(&self) -> Result<CoreOutput, AppError> {
        let scale = Self::scale_for("scale_set_unit_grams", self.scale.as_ref())?;
        let writes = scale.set_unit_grams_command().ok_or_else(|| {
            Self::unsupported(scale, "scale_set_unit_grams", "no set-unit-grams command")
        })?;
        let mut out = CoreOutput::default();
        Self::push_scale_writes(&mut out, writes);
        Ok(out)
    }

    /// Build a [`Command`] sequence that toggles the connected scale's
    /// display unit. Hiroia Jimmy is the only scale that exposes a
    /// toggle today; every other scale returns
    /// [`AppError::UnsupportedOnHardware`].
    pub fn toggle_scale_unit(&self) -> Result<CoreOutput, AppError> {
        let scale = Self::scale_for("scale_toggle_unit", self.scale.as_ref())?;
        let writes = scale.toggle_unit_command().ok_or_else(|| {
            Self::unsupported(scale, "scale_toggle_unit", "no toggle-unit command")
        })?;
        let mut out = CoreOutput::default();
        Self::push_scale_writes(&mut out, writes);
        Ok(out)
    }

    /// Toggle whether the connected scale should be powered off on
    /// [`MachineState::Sleep`] entry. Off by default. Behaviour-driven by
    /// the scale's [`Scale::power_off_command`] capability — scales
    /// without a power-off command silently skip this on Sleep even
    /// when the pref is on.
    ///
    /// Replaces the previous Eureka-specific
    /// `set_eureka_precisa_auto_off_on_sleep`; the pref now generalises
    /// to any scale that exposes a power-off command (Decent / Eureka /
    /// Solo today).
    pub fn set_auto_off_scale_on_sleep(&mut self, enabled: bool) {
        self.auto_off_scale_on_sleep = enabled;
    }

    /// Whether the connected scale is configured to power off on machine
    /// Sleep entry. See
    /// [`set_auto_off_scale_on_sleep`](Self::set_auto_off_scale_on_sleep).
    pub fn auto_off_scale_on_sleep(&self) -> bool {
        self.auto_off_scale_on_sleep
    }

    /// Resolve `&self.scale` for a capability-named feature, returning a
    /// shared `AppError` on a missing scale. Shared by every public
    /// scale-write method below so the "no scale connected" branch has
    /// one home.
    fn scale_for<'a>(feature: &str, scale: Option<&'a Scale>) -> Result<&'a Scale, AppError> {
        scale.ok_or_else(|| AppError::UnsupportedOnHardware {
            feature: feature.to_owned(),
            reason: "no scale is connected".to_owned(),
        })
    }

    /// Build an `AppError::UnsupportedOnHardware` naming the connected
    /// scale and the missing capability — used by the public methods
    /// when [`Scale`]'s capability accessor returns `None`.
    fn unsupported(scale: &Scale, feature: &str, what: &str) -> AppError {
        AppError::UnsupportedOnHardware {
            feature: feature.to_owned(),
            reason: format!("connected scale ({}) has {what}", scale.label()),
        }
    }

    /// Wrap each `&[u8]` in `writes` as a `Command::WriteScale` and push
    /// onto `out`. The capability-driven sibling of the five
    /// `push_X_write` helpers it replaces: callers ask the scale for the
    /// bytes (via `lcd_enable_command`, `power_off_command`, …) and
    /// hand the resulting `Vec` here.
    fn push_scale_writes(out: &mut CoreOutput, writes: Vec<&'static [u8]>) {
        for bytes in writes {
            out.commands.push(Command::WriteScale {
                data: bytes.to_vec(),
            });
        }
    }

    /// Append a [`Command::WriteScale`] for `bytes` to `out`.
    fn push_scale_write(out: &mut CoreOutput, bytes: Vec<u8>) {
        out.commands.push(Command::WriteScale { data: bytes });
    }

    /// Append the connected scale's settings-query bytes (capability-gated;
    /// no-op when the scale doesn't expose a query). Used after every
    /// config write to re-sync the shell view to post-change state.
    fn push_query_settings_command(scale: &Scale, out: &mut CoreOutput) {
        if let Some(bytes) = scale.query_settings_command() {
            Self::push_scale_write(out, bytes);
        }
    }

    /// Emit the one-shot connect-time scale-config queries the first time a
    /// capability-bearing scale reports weight.
    ///
    /// There is no dedicated scale-connected notification, so the queries ride
    /// on the first weight notification — the scale's [`Scale::query_serial_command`]
    /// (anti-mistouch + serial) and [`Scale::query_settings_command`]
    /// (active / enabled modes) are issued once, so the shell's anti-mistouch
    /// toggle and mode selector show live state immediately. Capability-gated
    /// by the methods themselves; guarded by `scale_config_queried` so the
    /// branch fires exactly once per connection.
    fn push_connect_queries_once(&mut self, out: &mut CoreOutput) {
        if self.scale_config_queried {
            return;
        }
        if let Some(scale) = &self.scale {
            if let Some(bytes) = scale.query_serial_command() {
                Self::push_scale_write(out, bytes);
            }
            if let Some(bytes) = scale.query_settings_command() {
                Self::push_scale_write(out, bytes);
            }
        }
        // Mark the one-shot done even for a weight-only scale, so the
        // capability check is not repeated on every weight notification.
        self.scale_config_queried = true;
    }

    /// The post-subscribe connect handshake the shells run once, right after
    /// subscribing: the scale's connect-init writes (e.g. the Difluid's
    /// [`Scale::connect_writes`] `ENABLE_NOTIFICATIONS` — it streams no weight
    /// until enabled) followed by its settings query. Empty for a weight-only
    /// scale that needs neither. (Kept named `query_scale_settings` for FFI /
    /// shell compatibility — it has always been the post-connect hook; the
    /// connect-init writes ride the same call.)
    pub fn query_scale_settings(&self) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale {
            Self::push_scale_writes(&mut out, scale.connect_writes());
            if let Some(bytes) = scale.query_settings_command() {
                Self::push_scale_write(&mut out, bytes);
            }
        }
        out
    }

    /// Build a [`Command`] that sets the connected scale's beeper volume,
    /// clamped to the scale's [`ScaleCapabilities::volume`] range. Empty
    /// [`CoreOutput`] when no scale is connected or the volume isn't
    /// settable. Routed through the per-scale [`Scale::set_volume_command`].
    pub fn set_scale_volume(&self, level: u8) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && let Some(bytes) = scale.set_volume_command(level)
        {
            Self::push_scale_write(&mut out, bytes);
            Self::push_query_settings_command(scale, &mut out);
        }
        out
    }

    /// Build a [`Command`] that sets the connected scale's auto-standby
    /// timeout. `minutes` is clamped to the connected scale's
    /// [`ScaleCapabilities::standby`] range. Empty [`CoreOutput`] when the
    /// scale exposes no configurable standby. Routed through
    /// [`Scale::set_standby_command`].
    pub fn set_scale_standby(&self, minutes: u8) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && let Some(bytes) = scale.set_standby_command(minutes)
        {
            Self::push_scale_write(&mut out, bytes);
            Self::push_query_settings_command(scale, &mut out);
        }
        out
    }

    /// Build a [`Command`] that toggles the connected scale's flow
    /// smoothing — empty when the capability is absent. Routed through
    /// [`Scale::set_flow_smoothing_command`].
    pub fn set_scale_flow_smoothing(&self, enabled: bool) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && let Some(bytes) = scale.set_flow_smoothing_command(enabled)
        {
            Self::push_scale_write(&mut out, bytes);
            Self::push_query_settings_command(scale, &mut out);
        }
        out
    }

    /// Build a [`Command`] that toggles the connected scale's
    /// anti-mistouch — empty when the capability is absent. Routed
    /// through [`Scale::set_anti_mistouch_command`].
    pub fn set_scale_anti_mistouch(&self, enabled: bool) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && let Some(bytes) = scale.set_anti_mistouch_command(enabled)
        {
            Self::push_scale_write(&mut out, bytes);
            Self::push_query_settings_command(scale, &mut out);
        }
        out
    }

    /// Build the [`Command`]s that switch the connected scale to display
    /// mode `mode_id`. Routed through [`Scale::select_mode_command`],
    /// which returns the three-write sequence the Bookoo requires (the
    /// target mode is enabled first, then the other two are disabled —
    /// at least one mode is always enabled mid-sequence). The shell must
    /// perform the returned writes in order.
    pub fn set_scale_mode(&self, mode_id: u8) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && let Some(writes) = scale.select_mode_command(mode_id)
        {
            for bytes in writes {
                Self::push_scale_write(&mut out, bytes);
            }
            Self::push_query_settings_command(scale, &mut out);
        }
        out
    }

    /// Build a [`Command`] that selects the connected scale's auto-stop
    /// mode (`0` = flow-stop, `1` = cup-removal). Routed through
    /// [`Scale::set_auto_stop_command`]; empty [`CoreOutput`] when the
    /// capability is absent or `mode_id` is out of range.
    pub fn set_scale_auto_stop(&self, mode_id: u8) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && let Some(bytes) = scale.set_auto_stop_command(mode_id)
        {
            Self::push_scale_write(&mut out, bytes);
            Self::push_query_settings_command(scale, &mut out);
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
    /// Mark this core as a read-only mirror (secondary) or restore it to a
    /// normal authoritative core. See [`read_only`](Self::read_only).
    pub fn set_read_only(&mut self, value: bool) {
        self.read_only = value;
    }

    /// Whether this core is a read-only mirror — see [`set_read_only`](Self::set_read_only).
    pub fn is_read_only(&self) -> bool {
        self.read_only
    }

    /// Drop every machine/scale write from `out` when this core is a read-only
    /// mirror, leaving its decoded events intact. The [`Command`] enum is
    /// writes-only, so clearing it suppresses exactly the hardware-driving
    /// effects — a secondary decodes the mirrored stream for its UI but never
    /// drives the DE1/scale. A no-op on a normal (authoritative) core.
    fn gate_read_only(&self, mut out: CoreOutput) -> CoreOutput {
        if self.read_only {
            out.commands.clear();
        }
        out
    }

    pub fn on_notification(&mut self, source: Source, data: &[u8], now_ms: u64) -> CoreOutput {
        // Capture side-effect first — the recorder needs the raw notification
        // bytes regardless of whether the decode that follows succeeds, and
        // running it here (rather than at each shell's BLE-manager layer)
        // means the recorder lives in one place and rides the same wasm
        // boundary crossing as the decode. Replay-driven bytes record too:
        // a replayed shot lands in history with its own faithful capture.
        // A read-only mirror (a secondary) does NOT record, though — the shot
        // belongs to the primary, which records the authoritative session; a
        // mirror recording every shot it watches just litters its captures dir
        // and feeds the replay-primary's "newest capture" loop (issue 14).
        if !self.read_only {
            self.capture.record(source, data, now_ms);
        }
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
        self.gate_read_only(out)
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
        self.gate_read_only(out)
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
        slice_to_jsonl(&self.capture, &self.capture.meta_snapshot(), from_ms, to_ms)
    }

    /// Drop every captured entry — used by the shell on BLE disconnect, where
    /// the live session is gone but the rest of the core (settings, history,
    /// the active profile, …) must stay intact. Distinct from
    /// [`reset`](Self::reset), which wipes everything.
    pub fn capture_clear(&mut self) {
        self.capture.clear();
    }

    /// Discard all session state — e.g. on disconnect. The connected scale and
    /// armed auto-stop targets are cleared too, and the capture rolling
    /// buffer is wiped (a fresh [`CaptureRecorder`] is part of
    /// [`CremaCore::new`]).
    pub fn reset(&mut self) {
        // `read_only` is a device-role config (this core is a secondary mirror),
        // not session/shot state — it must survive a reset, else a reconnect
        // (the BLE manager resets the core on every disconnect) would turn the
        // mirror back into an authoritative core mid-session and resume driving
        // hardware. Carry it across the rebuild.
        let read_only = self.read_only;
        // The scale registration is a *device* link the shell owns, not DE1
        // session state: the BLE managers reset the core on a DE1 drop while
        // the scale link stays up, and nothing re-identifies the scale until
        // its own reconnect — leaving weight decoding dead and SAW unable to
        // arm on every shot after a DE1 reconnect (issue #15). Carry the codec
        // (and its one-shot config-query latch) across; the scale's own drop
        // path ([`disconnect_scale`](Self::disconnect_scale)) still clears it.
        let scale = self.scale.take();
        let scale_config_queried = self.scale_config_queried;
        *self = CremaCore::new();
        self.read_only = read_only;
        self.scale = scale;
        self.scale_config_queried = scale_config_queried;
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
            // Reactive scale auto-policy on state-entry. Capability-driven:
            // for each transition we ask the connected scale for the bytes
            // (via `lcd_enable_command` / `lcd_disable_command` /
            // `set_unit_grams_command` / `power_off_command`); scales that
            // don't expose the capability stay silent. Fires on state
            // *entry* — not on every tick — via the prev-state guard.
            if prev_state != Some(info.state)
                && let Some(scale) = self.scale.as_ref()
            {
                let grams = matches!(self.weight_unit_pref, WeightUnit::Grams);
                match info.state {
                    // The LCD-enable bytes follow the user's chosen weight
                    // unit, cached on the core by the shell via
                    // `set_weight_unit_pref` — so an Idle entry picks the
                    // right variant to match what the shell is showing.
                    MachineState::Idle => {
                        if let Some(writes) = scale.lcd_enable_command(grams) {
                            Self::push_scale_writes(out, writes);
                        }
                        // Independent of LCD: when the pref is grams,
                        // explicit "set to grams" scales (Eureka / Solo /
                        // Difluid) get nudged so the on-scale display
                        // unit follows the shell's unit.
                        if grams && let Some(writes) = scale.set_unit_grams_command() {
                            Self::push_scale_writes(out, writes);
                        }
                    }
                    MachineState::Sleep => {
                        if let Some(writes) = scale.lcd_disable_command() {
                            Self::push_scale_writes(out, writes);
                        }
                        // Power-off-on-sleep is opt-in (default off).
                        // Fires for any scale whose `power_off_command`
                        // returns `Some` — generalised from the
                        // previous Eureka-only path.
                        if self.auto_off_scale_on_sleep
                            && let Some(writes) = scale.power_off_command()
                        {
                            Self::push_scale_writes(out, writes);
                        }
                    }
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
        // Elapsed runs from FLOW start (pump on), not shot start (which begins
        // in the heating phase) — so the displayed shot time excludes the
        // pre-pour preheat, matching de1app + the recorded shot duration.
        let elapsed = duration_to_u32_ms(
            self.flow_started
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
        // When a scale is reporting, compute the weight-derived puck
        // resistance from its last estimate too — this is the "truer"
        // signal during the pour because it measures what *exits* the
        // puck, not what the pump pushes in. `None` falls through when
        // no scale is paired (or the scale hasn't sampled yet) and the
        // chart silently uses the DE1-flow sibling.
        let resistance_weight = self
            .last_scale_estimate
            .and_then(|e| puck_resistance_weight(sample.group_pressure, e.flow));
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
            resistance_weight,
        });
    }

    /// Decode and process a scale weight notification.
    fn handle_scale_weight(&mut self, data: &[u8], now: Duration, out: &mut CoreOutput) {
        let Some(scale) = &mut self.scale else {
            return;
        };
        // Capability-driven auto-recovery from the weight-notification
        // path. The scale itself decides whether `data` reveals a
        // non-grams mode and which wire bytes nudge it back — see
        // `Scale::unit_recovery`. Drop / Continue tells us whether the
        // numeric weight is bogus (drop the frame) or only the display
        // unit is off (queue the nudge + keep parsing).
        if let Some(recovery) = scale.unit_recovery(data) {
            match recovery {
                de1_scale::UnitRecovery::Drop { bytes } => {
                    out.commands.push(Command::WriteScale {
                        data: bytes.to_vec(),
                    });
                    return;
                }
                de1_scale::UnitRecovery::Continue { bytes } => {
                    out.commands.push(Command::WriteScale {
                        data: bytes.to_vec(),
                    });
                }
            }
        }
        let Some(reading) = scale.parse_reading(data) else {
            // Not a weight packet — delegate to the per-device absorber.
            // The Decent variant uses this to snap the firmware version
            // from its `0x0A` LCD-ack frame (mirroring legacy
            // `de1plus/bluetooth.tcl:2738-2749`); other scales no-op.
            // The generic handler stops naming Decent — adding a future
            // "Bookoo emits firmware on connect" lands in the same shape.
            scale.absorb_unmatched_frame(data);
            return;
        };
        // A fresh reading re-arms the lost-scale watchdog.
        self.last_scale_weight = Some(now);
        self.scale_stale_reported = false;
        // The first weight notification stands in for a scale-connected hook:
        // fire the one-shot serial / settings queries so the anti-mistouch
        // state and active mode are fetched as soon as the scale is reporting.
        self.push_connect_queries_once(out);
        // Tare-settling gate. After the auto-tare fired at shot start, the
        // scale keeps reporting the pre-tare pan/cup weight for the ~50–500 ms
        // until the hardware tare physically lands; recording that would
        // persist a weight spike at the very start of the shot. Hold the
        // reported weight at zero until a near-zero reading confirms the tare
        // settled, with a timeout fallback so weight is never lost if the tare
        // fails or the scale is noisy. Mirrors de1app's `on_tare_seen`, which
        // zeroes its weight shift-registers when a sub-0.04 g reading arrives
        // within 1 s of the tare request (`device_scale.tcl`).
        if let Some(tare_sent) = self.tare_gate {
            // de1app uses 0.04 g; loosened for scale noise so the gate clears
            // promptly once the tare lands.
            const TARE_SETTLE_G: f32 = 0.3;
            // de1app's confirmation window is 1 s; allow a touch more before
            // giving up and resuming so a failed tare never zeroes forever.
            const TARE_SETTLE_TIMEOUT: Duration = Duration::from_millis(1500);
            if reading.weight_g.abs() <= TARE_SETTLE_G
                || now.saturating_sub(tare_sent) >= TARE_SETTLE_TIMEOUT
            {
                // Tare landed (or we gave up): start the post-tare series clean
                // and let this (~zero) reading flow through normally below.
                self.tare_gate = None;
                self.flow.reset();
            } else {
                // Still pre-tare — report zero so neither the live chart nor the
                // recorded shot shows the pan/cup weight. The device's own
                // passthrough channels ride through unchanged; skip the flow
                // estimator, shot metrics, and auto-stop so nothing is polluted.
                self.last_scale_estimate = Some(Estimate {
                    weight: 0.0,
                    flow: 0.0,
                });
                out.events.push(Event::ScaleReading {
                    weight: 0.0,
                    flow: 0.0,
                    device_flow: reading.flow_g_per_s,
                    device_timer: reading.timer_ms,
                    device_volume: reading.volume,
                    device_standby: reading.standby_minutes,
                    device_battery: reading.battery_percent,
                    device_flow_smoothing: reading.flow_smoothing,
                    device_auto_stop: reading.auto_stop,
                });
                return;
            }
        }
        let estimate = self.flow.update(reading.weight_g, now);
        // Cache the latest smoothed weight + flow so the DE1 telemetry
        // handler can fold them onto the next `Event::Telemetry` —
        // powers the weight-based puck-resistance metric (see
        // [`puck_resistance_weight`]) and rides through as raw
        // `scale_weight` / `scale_flow_weight` channels for the v2
        // export + Visualizer upload.
        self.last_scale_estimate = Some(estimate);
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
            ScaleConfigUpdate::SerialInfo {
                firmware,
                serial,
                anti_mistouch,
            } => Event::ScaleConfig {
                anti_mistouch: Some(anti_mistouch),
                active_mode: None,
                enabled_modes: None,
                serial: Some(serial),
                firmware: Some(firmware),
            },
            ScaleConfigUpdate::Settings {
                active_mode,
                enabled_modes,
            } => Event::ScaleConfig {
                anti_mistouch: None,
                active_mode: Some(active_mode),
                enabled_modes: Some(enabled_modes),
                serial: None,
                firmware: None,
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
    /// and CPU-board firmware versions.
    fn handle_version(&mut self, data: &[u8], out: &mut CoreOutput) {
        match Version::decode(data) {
            Ok(version) => {
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
        firmware_info::compare(self.capture.firmware_build())
    }

    // ── Profile upload (write side) ───────────────────────────────────────
    //
    // The shape is: `upload_profile` validates the profile, returns one
    // `CoreOutput` carrying every BLE write in upload order (header + N
    // frames + M extensions + tail), and arms the ack matcher. Each
    // `Source::De1FrameAck` notification advances the matcher one step;
    // the tail ack emits `ProfileUploadCompleted` and records the title
    // as the active profile.

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
        let mut assembled = profile.assemble()?;
        // NEVER arm the firmware-side dispensed-volume stop: the tail's
        // max_total_volume_ml drives the DE1's own (flow-integrated) volume
        // estimate, which mis-fires — reaprime: "not compatible with active
        // scale and breaks with high PI flows". All three reference apps
        // upload 0 = no limit (de1app binary.tcl:1001, reaprime
        // unified_de1.profile.dart:90, Decenza profile.cpp:1189) and stop by
        // volume app-side instead — our SAV, which is accurate, attributable
        // (`Event::StopTriggered`), and interceptable. The profile keeps its
        // authored value for SAV + export; only the wire tail is zeroed.
        assembled.tail.max_total_volume_ml = 0;
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
        let extension_count = u8::try_from(assembled.extension_frames.len()).unwrap_or(u8::MAX);
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
    /// (the legacy app never Reads it, and firmware returns zeros).
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
                reason: ProfileUploadFailure::UnexpectedAck {
                    expected,
                    got: byte,
                },
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
            // The FirmwareVersion / MachineModel / SerialNumber registers
            // used to be cached on `CremaCore` so `firmware_update_status`
            // and the capture META prelude could read them. They are now
            // recovered on demand from the recorder's identity-keeper —
            // see `CaptureRecorder::meta_snapshot` / `firmware_build` —
            // so this arm just emits the typed value for shell consumers.
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
        out.events.push(Event::ShotSettingsRead {
            steam_temp: settings.steam_temp_c,
            steam_timeout: settings.steam_timeout_s,
            hot_water_temp: settings.hot_water_temp_c,
            hot_water_volume: settings.hot_water_volume_ml,
            hot_water_timeout: settings.hot_water_timeout_s,
            espresso_volume: settings.espresso_volume_ml,
            group_temp: settings.group_temp_c,
        });
        // RS6: cache by move after the event has copied its scalar fields out,
        // rather than cloning up front.
        self.steam_hotwater_settings = Some(settings);
    }

    /// Translate a domain [`ShotEvent`] into FFI [`Event`]s, maintaining the
    /// shot-start timestamp and the auto-stop lifecycle.
    ///
    /// At shot start the connected scale is also auto-tared and its built-in
    /// timer is reset-then-started; at shot completion the timer is stopped.
    /// All three timer writes are capability-gated by [`Scale::timer`], so
    /// scales without software timer commands stay silent — matching the
    /// reactive auto-policy of the legacy app.
    fn map_shot_event(&mut self, event: ShotEvent, now: Duration, out: &mut CoreOutput) {
        match event {
            ShotEvent::Started => {
                self.shot_started = Some(now);
                // Flow hasn't begun yet (the shot opens in the heating phase) —
                // the elapsed clock is zeroed at the first preinfusion/pouring
                // phase below, so the preheat isn't counted. See `flow_started`.
                self.flow_started = None;
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
                // mirroring the legacy app's tare-at-shot-start behaviour —
                // gated on the latched user preference (default true). The
                // tare write is a no-op when no scale is connected, so the
                // explicit scale check inside `push_tare_command` carries.
                if self.auto_tare {
                    self.push_tare_command(out);
                    // Open the tare-settling gate so the pre-tare pan/cup weight
                    // is suppressed until the hardware tare lands — see
                    // `tare_gate` and `handle_scale_weight`.
                    self.tare_gate = Some(now);
                }
                // Reset the scale's built-in timer first, then start it. The
                // reset clears any residual from a prior shot; the start
                // begins counting from zero.
                Self::push_timer_command(&self.scale, TimerCommand::Reset, out);
                Self::push_timer_command(&self.scale, TimerCommand::Start, out);
                out.events.push(Event::ShotStarted);
            }
            ShotEvent::PhaseChanged(phase) => {
                // First flow (pump on) zeroes the elapsed clock; the pre-pour
                // heating/stabilising phases don't. Matches the recorded
                // `ShotRecord.duration` (the ShotMonitor times the same window).
                if self.flow_started.is_none()
                    && matches!(phase, ShotPhase::Preinfusion | ShotPhase::Pouring)
                {
                    self.flow_started = Some(now);
                }
                self.arm_auto_stop_if_flowing(phase, now);
                out.events.push(Event::ShotPhaseChanged { phase });
            }
            ShotEvent::FrameChanged(frame) => out.events.push(Event::ShotFrameChanged { frame }),
            ShotEvent::Completed(record) => {
                self.shot_started = None;
                self.flow_started = None;
                self.auto_stop = None;
                // Close any tare gate that never saw its confirmation reading.
                self.tare_gate = None;
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
    ///
    /// The targets are composed fresh from the latched user settings via
    /// [`effective_stop_targets`](Self::effective_stop_targets), so any
    /// setting changes between shots are picked up automatically without a
    /// cached-tuple staleness window.
    fn arm_auto_stop_if_flowing(&mut self, phase: ShotPhase, now: Duration) {
        if self.auto_stop.is_some() {
            return;
        }
        if !matches!(phase, ShotPhase::Preinfusion | ShotPhase::Pouring) {
            return;
        }
        if let Some(targets) = self.effective_stop_targets() {
            self.auto_stop = Some(AutoStop::new(targets, self.stop_config(), now));
        }
    }

    /// Re-compose an armed [`AutoStop`]'s targets after a mid-shot change to
    /// stop-relevant state (scale connect / disconnect, the stop toggles, the
    /// target setters). [`arm_auto_stop_if_flowing`](Self::arm_auto_stop_if_flowing)
    /// arms exactly once per shot — and since max-shot-time is almost always
    /// set, it arms even before the scale has registered, which used to bake
    /// `weight: None` in for the whole shot (issue #15's intermittent SAW
    /// failure). No-op between shots (no `AutoStop` armed).
    fn refresh_auto_stop_targets(&mut self) {
        if self.auto_stop.is_none() {
            return;
        }
        let targets = self.effective_stop_targets().unwrap_or(StopTargets {
            weight: None,
            volume: None,
            max_time: None,
        });
        if let Some(stop) = self.auto_stop.as_mut() {
            stop.set_targets(targets);
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

#[cfg(test)]
mod tests {
    use super::*;
    use de1_domain::ShotPhase;
    use de1_protocol::{MachineState, SubState};
    // Per-scale protocol modules used by the tests; the production lib
    // no longer reaches into these directly (the capability-driven
    // refactor consolidated everything through `Scale::*_command`).
    use de1_scale::{difluid, eureka_precisa, hiroia_jimmy, skale};

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
    fn puck_resistance_weight_matches_p_over_f_squared() {
        // 9 bar at 2 g/s → 9 / 4 = 2.25.
        let r = puck_resistance_weight(9.0, 2.0).expect("a finite resistance");
        assert!((r - 2.25).abs() < 1e-5);
    }

    #[test]
    fn puck_resistance_weight_rejects_sub_floor_flow() {
        // Below RESISTANCE_WEIGHT_FLOW_FLOOR_G_PER_S: noisy near-zero
        // region — suppressed, matches the DE1-flow sibling.
        assert!(puck_resistance_weight(9.0, 0.0).is_none());
        assert!(puck_resistance_weight(9.0, 0.05).is_none());
    }

    #[test]
    fn puck_resistance_weight_rejects_nonfinite_inputs() {
        assert!(puck_resistance_weight(f32::NAN, 2.0).is_none());
        assert!(puck_resistance_weight(9.0, f32::NAN).is_none());
        assert!(puck_resistance_weight(f32::INFINITY, 2.0).is_none());
        assert!(puck_resistance_weight(9.0, f32::INFINITY).is_none());
    }

    #[test]
    fn telemetry_carries_no_resistance_weight_without_a_scale_estimate() {
        // No scale weight has been fed — the telemetry handler can't
        // compute a weight-derived resistance, so the field is `None`
        // and the chart falls back to the DE1-flow sibling.
        let mut core = CremaCore::new();
        core.on_notification(Source::De1State, &[4, 5], 1_000);
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 3_500);
        let Some(Event::Telemetry {
            resistance_weight, ..
        }) = out
            .events
            .iter()
            .find(|e| matches!(e, Event::Telemetry { .. }))
            .cloned()
        else {
            panic!("expected a Telemetry event");
        };
        assert!(resistance_weight.is_none());
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
    fn telemetry_elapsed_excludes_the_preheat_phase() {
        let mut core = CremaCore::new();
        // Enter Espresso in the HEATING substate (pump off): a shot starts, but
        // the elapsed clock has not — telemetry here reads elapsed 0.
        core.on_notification(Source::De1State, &[4, 1], 1_000); // 4=Espresso, 1=Heating
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 5_000); // 4s into heating
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::Telemetry { elapsed: 0, .. })),
            "heating-phase telemetry reads elapsed 0"
        );
        // Flow begins (pouring) 10s after the shot started — the clock zeroes here.
        core.on_notification(Source::De1State, &[4, 5], 11_000); // 5=Pouring
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 13_000); // 2s into flow
        let Some(Event::Telemetry { elapsed, .. }) = out
            .events
            .iter()
            .find(|e| matches!(e, Event::Telemetry { .. }))
        else {
            panic!("a Telemetry event");
        };
        assert_eq!(
            *elapsed, 2_000,
            "elapsed runs from flow start, not shot start"
        );
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
        core.connect_scale("BOOKOO_SC", &[]);
        // No reading yet — the watchdog has nothing to time against.
        let out = core.on_tick(1_000_000);
        assert!(out.events.is_empty());
    }

    #[test]
    fn on_tick_emits_scale_stale_once_per_stale_episode() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        assert!(core.on_tick(2_500).events.contains(&Event::ScaleStale));
        // A new reading re-arms the watchdog; staleness can be reported again.
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_100), 3_000);
        assert!(core.on_tick(3_500).events.is_empty());
        assert!(core.on_tick(4_100).events.contains(&Event::ScaleStale));
    }

    #[test]
    fn disconnect_scale_clears_the_scale_slice_but_not_user_prefs() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        // The scale slice is populated.
        assert!(core.scale_capabilities().is_some());
        assert!(core.scale_uuids().is_some());
        assert!(core.last_scale_weight.is_some());
        // A user pref to confirm it survives the disconnect.
        core.set_auto_tare(false);

        core.disconnect_scale();

        // Scale identity + every scale-derived reading is cleared...
        assert!(core.scale_capabilities().is_none());
        assert!(core.scale_uuids().is_none());
        assert!(core.last_scale_weight.is_none());
        assert!(core.last_scale_estimate.is_none());
        assert!(!core.scale_config_queried);
        // ...the stale watchdog is disarmed (no scale → no stale episode)...
        assert!(core.on_tick(9_999).events.is_empty());
        // ...but the user pref is untouched.
        assert!(!core.auto_tare);
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
            core.connect_scale("BOOKOO_SC 1234", &[]),
            Some("Bookoo".to_owned())
        );
        // An unrecognised name returns None.
        assert_eq!(core.connect_scale("Some Random Device", &[]), None);
    }

    #[test]
    fn a_scale_weight_notification_emits_a_reading() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        assert!(
            out.events
                .iter()
                .any(|e| matches!(e, Event::ScaleReading { .. }))
        );
    }

    /// The weight carried by the first `Event::ScaleReading` in `out`, if any.
    fn scale_reading_weight(out: &CoreOutput) -> Option<f32> {
        out.events.iter().find_map(|e| match e {
            Event::ScaleReading { weight, .. } => Some(*weight),
            _ => None,
        })
    }

    #[test]
    fn auto_tare_suppresses_the_pre_tare_weight_spike() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        // Auto-tare is on by default; entering Espresso starts a shot, which
        // sends the tare and opens the settling gate.
        let out = core.on_notification(Source::De1State, &[4, 5], 1_000);
        assert!(out.events.contains(&Event::ShotStarted));
        // The pan/cup is still on the scale (35 g) while the hardware tare is in
        // flight — it must NOT be recorded as a spike.
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 1_100);
        assert_eq!(
            scale_reading_weight(&out),
            Some(0.0),
            "pre-tare weight is reported as zero, not the pan weight"
        );
        // The scale reports ~zero: the tare landed, so the gate clears.
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(0), 1_200);
        assert_eq!(scale_reading_weight(&out), Some(0.0));
        // Real pour weight now flows through. Feed a short steady series so the
        // estimate settles regardless of the smoothing algorithm.
        core.on_notification(Source::ScaleWeight, &bookoo_packet(500), 1_300);
        core.on_notification(Source::ScaleWeight, &bookoo_packet(500), 1_400);
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(500), 1_500);
        let w = scale_reading_weight(&out).expect("a reading");
        assert!((w - 5.0).abs() < 0.5, "post-tare weight recorded (got {w})");
    }

    #[test]
    fn tare_gate_times_out_so_weight_is_never_lost() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        core.on_notification(Source::De1State, &[4, 5], 1_000); // gate opens at t=1000
        // The scale never reaches zero (e.g. the tare failed). Before the
        // fallback timeout the weight is still suppressed...
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 1_400);
        assert_eq!(scale_reading_weight(&out), Some(0.0));
        // ...but after it (1000 + 1500 ms) weight resumes rather than sticking
        // at zero forever.
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 2_600);
        let w = scale_reading_weight(&out).expect("a reading");
        assert!(
            (w - 35.0).abs() < 0.5,
            "weight resumes after the timeout (got {w})"
        );
    }

    #[test]
    fn no_tare_gate_when_auto_tare_is_disabled() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        core.set_auto_tare(false);
        // No auto-tare → no gate: weight is recorded as-is from the first
        // reading (the user is expected to tare manually).
        core.on_notification(Source::De1State, &[4, 5], 1_000);
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 1_100);
        let w = scale_reading_weight(&out).expect("a reading");
        assert!(
            (w - 35.0).abs() < 0.5,
            "no gate when auto-tare is off (got {w})"
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
        core.connect_scale("BOOKOO_SC", &[]);
        // A real 03 0c serial response: fw 1.4.1, anti-mistouch on.
        let frame = hex("030c008d534e32343030643636613839653701ce");
        let out = core.on_notification(Source::ScaleCommand, &frame, 1_000);
        assert!(out.events.contains(&Event::ScaleConfig {
            anti_mistouch: Some(true),
            active_mode: None,
            enabled_modes: None,
            serial: Some("SN2400d66a89e7".to_owned()),
            firmware: Some("1.4.1".to_owned()),
        }));
    }

    #[test]
    fn a_scale_command_settings_response_emits_a_scale_config_event() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        // A real 03 0e settings response: Auto active, only Auto enabled.
        let frame = hex("030e02040000000000000000000000000000000b");
        let out = core.on_notification(Source::ScaleCommand, &frame, 1_000);
        assert!(out.events.contains(&Event::ScaleConfig {
            anti_mistouch: None,
            active_mode: Some(2),
            enabled_modes: Some(0b100),
            serial: None,
            firmware: None,
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
        core.connect_scale("BOOKOO_SC", &[]);
        // A 03 0b weight-notification frame is not a command response — it is
        // dropped without a DecodeError, as it is not modelled here.
        let frame = hex("030b000000012b0000002b0000640096010000fa");
        let out = core.on_notification(Source::ScaleCommand, &frame, 1_000);
        assert!(out.events.is_empty());
    }

    #[test]
    fn the_first_weight_notification_issues_the_connect_time_queries() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
        core.on_notification(Source::ScaleWeight, &bookoo_packet(2_000), 1_000);
        // Reconnecting a scale re-arms the one-shot queries.
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("Decent Scale ABC", &[]);
        let frame = [0x03, 0xCE, 0x07, 0xD0, 0x00, 0x00, 0x00];
        let out = core.on_notification(Source::ScaleWeight, &frame, 1_000);
        assert!(
            !out.commands
                .iter()
                .any(|c| matches!(c, Command::WriteScale { .. }))
        );
    }

    // ----- Read-only mirror (multi-device secondary) ----------------------

    #[test]
    fn a_read_only_mirror_suppresses_commands_but_not_events() {
        // The connect-time scale queries are a convenient autonomous command —
        // a normal core emits them on the first capability-scale weight.
        let packet = bookoo_packet(2_000);

        let mut normal = CremaCore::new();
        normal.connect_scale("BOOKOO_SC", &[]);
        let normal_out = normal.on_notification(Source::ScaleWeight, &packet, 1_000);
        assert!(
            !normal_out.commands.is_empty(),
            "baseline: a normal core drives hardware (emits the connect-time queries)",
        );

        // A read-only mirror (a secondary) fed the identical notification must
        // emit zero commands — it decodes for its UI but never drives hardware —
        // while producing exactly the same decoded events.
        let mut mirror = CremaCore::new();
        mirror.connect_scale("BOOKOO_SC", &[]);
        mirror.set_read_only(true);
        let mirror_out = mirror.on_notification(Source::ScaleWeight, &packet, 1_000);
        assert!(
            mirror_out.commands.is_empty(),
            "a read-only mirror must emit no commands",
        );
        assert_eq!(
            mirror_out.events, normal_out.events,
            "read-only suppresses commands only — events are unaffected",
        );
    }

    #[test]
    fn read_only_survives_a_reset() {
        // The BLE manager resets the core on every disconnect; the read-only
        // role is device config, not session state, so it must persist — else a
        // reconnect would turn the mirror back into an authoritative core.
        let mut core = CremaCore::new();
        core.set_read_only(true);
        core.reset();
        assert!(
            core.is_read_only(),
            "reset() must preserve the read-only role"
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
    fn enable_scale_lcd_on_decent_emits_the_known_wire_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        let out = core
            .enable_scale_lcd(WeightUnit::Grams)
            .expect("decent scale supports lcd");
        assert_eq!(the_only_scale_write(&out), decent_scale::LCD_ENABLE_GRAMS);
    }

    #[test]
    fn enable_scale_lcd_on_decent_in_ounces_emits_the_ounces_packet() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        let out = core
            .enable_scale_lcd(WeightUnit::Ounces)
            .expect("decent scale supports lcd");
        assert_eq!(the_only_scale_write(&out), decent_scale::LCD_ENABLE_OUNCES);
    }

    #[test]
    fn disable_scale_lcd_on_decent_emits_the_known_wire_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        let out = core.disable_scale_lcd().expect("decent scale supports lcd");
        assert_eq!(the_only_scale_write(&out), decent_scale::LCD_DISABLE);
    }

    #[test]
    fn scale_heartbeat_on_decent_emits_the_known_wire_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        let out = core
            .scale_heartbeat()
            .expect("decent scale needs heartbeat");
        assert_eq!(the_only_scale_write(&out), decent_scale::HEARTBEAT);
    }

    #[test]
    fn scale_methods_error_when_no_scale_is_connected() {
        let core = CremaCore::new();
        assert!(matches!(
            core.enable_scale_lcd(WeightUnit::Grams).unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
        assert!(matches!(
            core.disable_scale_lcd().unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
        assert!(matches!(
            core.scale_heartbeat().unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
        assert!(matches!(
            core.power_off_scale().unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
    }

    #[test]
    fn lcd_methods_error_for_a_scale_without_lcd() {
        // Bookoo has no settable on-scale LCD — every LCD method must
        // surface `UnsupportedOnHardware` rather than silently no-op.
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        assert!(matches!(
            core.enable_scale_lcd(WeightUnit::Grams).unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
        assert!(matches!(
            core.disable_scale_lcd().unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
        assert!(matches!(
            core.scale_heartbeat().unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
    }

    #[test]
    fn power_off_scale_errors_for_a_scale_without_power_off() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        let err = core.power_off_scale().unwrap_err();
        assert!(matches!(err, AppError::UnsupportedOnHardware { .. }));
    }

    #[test]
    fn power_off_scale_on_decent_emits_the_known_wire_bytes() {
        // The byte sequence is sent unconditionally on any connected
        // Decent Scale — older firmware versions silently no-op on it.
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        let out = core
            .power_off_scale()
            .expect("decent scale supports power-off");
        assert_eq!(the_only_scale_write(&out), decent_scale::POWER_OFF);
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
        core.connect_scale("Decent Scale ABC", &[]);
        core.set_weight_unit_pref(WeightUnit::Ounces);
        core.on_notification(Source::De1State, &[MachineState::Sleep as u8, 0], 1_000);
        let out = core.on_notification(Source::De1State, &[MachineState::Idle as u8, 0], 2_000);
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
        core.connect_scale("Decent Scale ABC", &[]);
        // Transition Sleep -> Idle. The first state notification doesn't
        // emit (no prior state) but the second one — entering Idle from
        // Sleep — must fire the LCD-enable write.
        core.on_notification(Source::De1State, &[MachineState::Sleep as u8, 0], 1_000);
        let out = core.on_notification(Source::De1State, &[MachineState::Idle as u8, 0], 2_000);
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
        core.connect_scale("Decent Scale ABC", &[]);
        // Transition Idle -> Sleep. The second notification — entering
        // Sleep from Idle — must fire the LCD-disable write.
        core.on_notification(Source::De1State, &[MachineState::Idle as u8, 0], 1_000);
        let out = core.on_notification(Source::De1State, &[MachineState::Sleep as u8, 0], 2_000);
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
        core.connect_scale("BOOKOO_SC", &[]);
        core.on_notification(Source::De1State, &[MachineState::Sleep as u8, 0], 1_000);
        let out = core.on_notification(Source::De1State, &[MachineState::Idle as u8, 0], 2_000);
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
        assert!(
            lcd_writes.is_empty(),
            "unexpected LCD writes: {lcd_writes:?}"
        );
    }

    // ----- PR G: per-scale parity sweep -----------------------------------

    /// Helper: collect the bytes of every `WriteScale` command in `out`.
    fn scale_writes(out: &CoreOutput) -> Vec<&[u8]> {
        out.commands
            .iter()
            .filter_map(|c| match c {
                Command::WriteScale { data } => Some(data.as_slice()),
                Command::WriteCharacteristic { .. } => None,
            })
            .collect()
    }

    // ----- Skale II LCD auto-policy ---------------------------------------

    #[test]
    fn enable_scale_lcd_on_skale_emits_screen_on_and_display_weight() {
        let mut core = CremaCore::new();
        core.connect_scale("Skale-9", &[]);
        let out = core
            .enable_scale_lcd(WeightUnit::Grams)
            .expect("skale supports lcd");
        let writes = scale_writes(&out);
        // Legacy sends `ED` (screen-on) then `EC` (display-weight), in order.
        // Grams pref also sends `03` (enable-grams).
        assert_eq!(
            writes,
            vec![
                [skale::CMD_SCREEN_ON].as_slice(),
                [skale::CMD_DISPLAY_WEIGHT].as_slice(),
                [skale::CMD_ENABLE_GRAMS].as_slice(),
            ]
        );
    }

    #[test]
    fn enable_scale_lcd_on_skale_skips_the_unit_write_when_pref_is_ounces() {
        let mut core = CremaCore::new();
        core.connect_scale("Skale-9", &[]);
        let out = core
            .enable_scale_lcd(WeightUnit::Ounces)
            .expect("skale supports lcd");
        let writes = scale_writes(&out);
        // No `0x03` enable-grams write when the user prefers ounces.
        assert!(!writes.contains(&[skale::CMD_ENABLE_GRAMS].as_slice()));
        assert!(writes.contains(&[skale::CMD_SCREEN_ON].as_slice()));
        assert!(writes.contains(&[skale::CMD_DISPLAY_WEIGHT].as_slice()));
    }

    #[test]
    fn disable_scale_lcd_on_skale_emits_screen_off() {
        let mut core = CremaCore::new();
        core.connect_scale("Skale-9", &[]);
        let out = core.disable_scale_lcd().expect("skale supports lcd");
        let writes = scale_writes(&out);
        assert_eq!(writes, vec![[skale::CMD_SCREEN_OFF].as_slice()]);
    }

    #[test]
    fn entering_idle_auto_enables_the_skale_lcd() {
        let mut core = CremaCore::new();
        core.connect_scale("Skale-9", &[]);
        core.on_notification(Source::De1State, &[MachineState::Sleep as u8, 0], 1_000);
        let out = core.on_notification(Source::De1State, &[MachineState::Idle as u8, 0], 2_000);
        let writes = scale_writes(&out);
        assert!(writes.contains(&[skale::CMD_SCREEN_ON].as_slice()));
        assert!(writes.contains(&[skale::CMD_DISPLAY_WEIGHT].as_slice()));
    }

    #[test]
    fn entering_sleep_auto_disables_the_skale_lcd() {
        let mut core = CremaCore::new();
        core.connect_scale("Skale-9", &[]);
        core.on_notification(Source::De1State, &[MachineState::Idle as u8, 0], 1_000);
        let out = core.on_notification(Source::De1State, &[MachineState::Sleep as u8, 0], 2_000);
        let writes = scale_writes(&out);
        assert!(writes.contains(&[skale::CMD_SCREEN_OFF].as_slice()));
    }

    // ----- Eureka Precisa methods + auto-off-on-sleep ---------------------

    #[test]
    fn power_off_scale_on_eureka_emits_the_known_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("CFS-9002", &[]);
        let out = core.power_off_scale().expect("eureka supports power-off");
        let writes = scale_writes(&out);
        assert_eq!(writes, vec![eureka_precisa::TURN_OFF.as_slice()]);
    }

    #[test]
    fn solo_barista_accepts_the_eureka_precisa_commands() {
        // Solo Barista is codec-identical to Eureka Precisa — the same
        // bytes go out via the capability dispatch.
        let mut core = CremaCore::new();
        core.connect_scale("LSJ-001", &[]);
        let out = core
            .power_off_scale()
            .expect("solo barista supports power-off");
        let writes = scale_writes(&out);
        assert_eq!(writes, vec![eureka_precisa::TURN_OFF.as_slice()]);
    }

    #[test]
    fn scale_beep_on_eureka_emits_the_known_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("CFS-9002", &[]);
        let out = core.scale_beep().expect("eureka supports beep");
        let writes = scale_writes(&out);
        assert_eq!(writes, vec![eureka_precisa::BEEP.as_slice()]);
    }

    #[test]
    fn set_scale_unit_grams_on_eureka_emits_the_set_unit_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("CFS-9002", &[]);
        let out = core
            .set_scale_unit_grams()
            .expect("eureka supports set-unit-grams");
        let writes = scale_writes(&out);
        assert_eq!(writes, vec![eureka_precisa::SET_UNIT_GRAMS.as_slice()]);
    }

    #[test]
    fn beep_and_set_unit_error_for_a_scale_without_them() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        assert!(matches!(
            core.scale_beep().unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
        assert!(matches!(
            core.set_scale_unit_grams().unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
    }

    #[test]
    fn auto_off_scale_on_sleep_default_is_false() {
        let core = CremaCore::new();
        assert!(!core.auto_off_scale_on_sleep());
    }

    #[test]
    fn entering_sleep_fires_scale_power_off_when_the_opt_in_is_on() {
        let mut core = CremaCore::new();
        core.connect_scale("CFS-9002", &[]);
        core.set_auto_off_scale_on_sleep(true);
        core.on_notification(Source::De1State, &[MachineState::Idle as u8, 0], 1_000);
        let out = core.on_notification(Source::De1State, &[MachineState::Sleep as u8, 0], 2_000);
        let writes = scale_writes(&out);
        assert!(writes.contains(&eureka_precisa::TURN_OFF.as_slice()));
    }

    #[test]
    fn entering_sleep_does_not_fire_scale_power_off_when_the_opt_in_is_off() {
        let mut core = CremaCore::new();
        core.connect_scale("CFS-9002", &[]);
        core.on_notification(Source::De1State, &[MachineState::Idle as u8, 0], 1_000);
        let out = core.on_notification(Source::De1State, &[MachineState::Sleep as u8, 0], 2_000);
        let writes = scale_writes(&out);
        assert!(!writes.contains(&eureka_precisa::TURN_OFF.as_slice()));
    }

    // ----- Hiroia Jimmy toggle-unit + auto-recovery -----------------------

    #[test]
    fn toggle_scale_unit_on_hiroia_emits_the_known_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("HIROIA JIMMY-X", &[]);
        let out = core
            .toggle_scale_unit()
            .expect("hiroia supports toggle-unit");
        let writes = scale_writes(&out);
        assert_eq!(writes, vec![hiroia_jimmy::TOGGLE_UNIT.as_slice()]);
    }

    #[test]
    fn toggle_scale_unit_errors_for_a_scale_without_toggle() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        assert!(matches!(
            core.toggle_scale_unit().unwrap_err(),
            AppError::UnsupportedOnHardware { .. }
        ));
    }

    #[test]
    fn a_hiroia_non_grams_frame_triggers_an_auto_recovery_toggle() {
        let mut core = CremaCore::new();
        core.connect_scale("HIROIA JIMMY-X", &[]);
        // Mode byte > 0x08 = non-grams unit; reaprime fires TOGGLE_UNIT.
        let frame = [0x09u8, 0, 0, 0, 0xB4, 0x00, 0x00];
        let out = core.on_notification(Source::ScaleWeight, &frame, 1_000);
        let writes = scale_writes(&out);
        assert!(writes.contains(&hiroia_jimmy::TOGGLE_UNIT.as_slice()));
    }

    #[test]
    fn a_hiroia_grams_frame_does_not_trigger_an_auto_recovery() {
        let mut core = CremaCore::new();
        core.connect_scale("HIROIA JIMMY-X", &[]);
        // Mode byte 0x00 = grams; no recovery write.
        let frame = [0x00u8, 0, 0, 0, 0xB4, 0x00, 0x00];
        let out = core.on_notification(Source::ScaleWeight, &frame, 1_000);
        let writes = scale_writes(&out);
        assert!(!writes.contains(&hiroia_jimmy::TOGGLE_UNIT.as_slice()));
    }

    // ----- Difluid SET_UNIT_GRAMS auto-recovery ---------------------------

    #[test]
    fn set_scale_unit_grams_on_difluid_emits_the_known_bytes() {
        let mut core = CremaCore::new();
        core.connect_scale("Microbalance-X", &[]);
        let out = core
            .set_scale_unit_grams()
            .expect("difluid supports set-unit-grams");
        let writes = scale_writes(&out);
        assert_eq!(writes, vec![difluid::SET_UNIT_GRAMS.as_slice()]);
    }

    #[test]
    fn a_difluid_non_grams_frame_triggers_an_auto_recovery_set_unit() {
        let mut core = CremaCore::new();
        core.connect_scale("Microbalance-X", &[]);
        // 19-byte frame, byte [17] = 0x01 (non-grams unit).
        let mut frame = [0u8; 19];
        frame[17] = 0x01;
        let out = core.on_notification(Source::ScaleWeight, &frame, 1_000);
        let writes = scale_writes(&out);
        assert!(writes.contains(&difluid::SET_UNIT_GRAMS.as_slice()));
    }

    #[test]
    fn a_difluid_grams_frame_does_not_trigger_an_auto_recovery() {
        let mut core = CremaCore::new();
        core.connect_scale("Microbalance-X", &[]);
        // 19-byte frame, byte [17] = 0x00 (grams unit).
        let frame = [0u8; 19];
        let out = core.on_notification(Source::ScaleWeight, &frame, 1_000);
        let writes = scale_writes(&out);
        assert!(!writes.contains(&difluid::SET_UNIT_GRAMS.as_slice()));
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
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
        let caps = core.scale_capabilities().expect("a connected scale");
        assert!(caps.volume.is_some());
        assert!(caps.reports_flow);
        assert!(caps.reports_timer);
    }

    #[test]
    fn scale_capabilities_reports_a_weight_only_scale_as_having_none() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        let caps = core.scale_capabilities().expect("a connected scale");
        assert!(caps.volume.is_none());
        assert!(!caps.reports_flow);
        assert!(!caps.reports_timer);
    }

    #[test]
    fn set_scale_volume_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.set_scale_volume(3);
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_volume_clamps_the_level_to_the_capability_range() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
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
        let core = CremaCore::new();
        assert!(core.set_scale_volume(3).commands.is_empty());
    }

    #[test]
    fn set_scale_volume_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        // A weight-only scale has no `volume` capability — the command is
        // capability-gated, so nothing is emitted.
        core.connect_scale("Decent Scale ABC", &[]);
        assert!(core.set_scale_volume(3).commands.is_empty());
    }

    #[test]
    fn set_scale_standby_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.set_scale_standby(15);
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_standby_clamps_to_the_capability_range() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("Decent Scale ABC", &[]);
        assert!(core.set_scale_standby(15).commands.is_empty());
    }

    #[test]
    fn set_scale_flow_smoothing_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        assert!(matches!(
            core.set_scale_flow_smoothing(true).commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_flow_smoothing_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        assert!(core.set_scale_flow_smoothing(true).commands.is_empty());
        assert!(core.set_scale_flow_smoothing(true).commands.is_empty());
    }

    #[test]
    fn set_scale_anti_mistouch_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        assert!(matches!(
            core.set_scale_anti_mistouch(false).commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_anti_mistouch_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        assert!(core.set_scale_anti_mistouch(true).commands.is_empty());
    }

    #[test]
    fn set_scale_mode_emits_three_writes_in_order_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
        // 7 is not a listed Bookoo mode — capability-gated, so nothing emitted.
        assert!(core.set_scale_mode(7).commands.is_empty());
    }

    #[test]
    fn set_scale_mode_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        assert!(core.set_scale_mode(0).commands.is_empty());
    }

    #[test]
    fn set_scale_mode_emits_nothing_without_a_scale() {
        let core = CremaCore::new();
        assert!(core.set_scale_mode(0).commands.is_empty());
    }

    #[test]
    fn set_scale_auto_stop_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        assert!(matches!(
            core.set_scale_auto_stop(1).commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_auto_stop_maps_each_mode_id_to_its_command() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
        // 2 is not a valid auto-stop mode — rejected gracefully, empty output.
        assert!(core.set_scale_auto_stop(2).commands.is_empty());
    }

    #[test]
    fn set_scale_auto_stop_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        assert!(core.set_scale_auto_stop(0).commands.is_empty());
    }

    #[test]
    fn set_scale_auto_stop_emits_nothing_without_a_scale() {
        let core = CremaCore::new();
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
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.set_scale_volume(3);
        // Config write, then the 0x0f settings query.
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_standby_appends_a_settings_query() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.set_scale_standby(15);
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_flow_smoothing_appends_a_settings_query() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.set_scale_flow_smoothing(true);
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_anti_mistouch_appends_a_settings_query() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.set_scale_anti_mistouch(false);
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_auto_stop_appends_a_settings_query() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.set_scale_auto_stop(1);
        // Config write, then the 0x0f settings query.
        assert_eq!(out.commands.len(), 2);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_mode_appends_a_settings_query_after_the_three_writes() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.set_scale_mode(1);
        // Three mode writes, then the 0x0f settings query.
        assert_eq!(out.commands.len(), 4);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn set_scale_methods_append_no_query_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.query_scale_settings();
        assert_eq!(out.commands.len(), 1);
        assert_eq!(last_write_scale(&out), bookoo::QUERY_SETTINGS);
    }

    #[test]
    fn query_scale_settings_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC", &[]);
        assert!(core.query_scale_settings().commands.is_empty());
    }

    #[test]
    fn query_scale_settings_emits_nothing_without_a_scale() {
        let core = CremaCore::new();
        assert!(core.query_scale_settings().commands.is_empty());
    }

    #[test]
    fn a_profile_target_weight_triggers_saw_and_a_stop_command() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        // Default settings (auto_tare=true, stop_on_weight=true) — push only
        // the profile recipe target.
        core.set_profile_target_weight(Some(30.0));
        core.on_notification(Source::De1State, &[4, 5], 0);
        // A 35 g reading well past the 5 s arming delay crosses the 30 g target.
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 6_000);
        assert!(out.events.contains(&Event::StopTriggered {
            reason: StopReason::Weight,
        }));
        assert!(out.commands.iter().any(|c| matches!(
            c,
            Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                ..
            }
        )));
    }

    #[test]
    fn a_shot_dial_override_wins_over_the_profile_target() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        // Profile says 30 g; dial overrides to 50 g.
        core.set_profile_target_weight(Some(30.0));
        core.set_shot_target_weight(Some(50.0));
        core.on_notification(Source::De1State, &[4, 5], 0);
        // 35 g would have stopped the 30 g shot — verify it doesn't here.
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 6_000);
        assert!(!out.events.iter().any(|e| matches!(
            e,
            Event::StopTriggered {
                reason: StopReason::Weight
            }
        )));
    }

    #[test]
    fn stop_on_weight_disabled_suppresses_saw_even_with_a_target() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        core.set_profile_target_weight(Some(30.0));
        core.set_stop_on_weight(false);
        core.on_notification(Source::De1State, &[4, 5], 0);
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 6_000);
        assert!(!out.events.iter().any(|e| matches!(
            e,
            Event::StopTriggered {
                reason: StopReason::Weight
            }
        )));
    }

    #[test]
    fn saw_does_not_arm_without_a_connected_scale() {
        let mut core = CremaCore::new();
        // No connect_scale call.
        core.set_profile_target_weight(Some(30.0));
        core.on_notification(Source::De1State, &[4, 5], 0);
        // Without a scale there's nothing to feed the AutoStop; assert no
        // controller was ever constructed by checking SAV / max-time
        // independence works in a sibling test.
        // Sanity: a sample past a 30 s max-time still doesn't trip SAW.
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 31_000);
        assert!(!out.events.iter().any(|e| matches!(
            e,
            Event::StopTriggered {
                reason: StopReason::Weight
            }
        )));
    }

    #[test]
    fn max_shot_duration_stops_the_shot() {
        let mut core = CremaCore::new();
        core.set_max_shot_duration(Some(30.0));
        core.on_notification(Source::De1State, &[4, 5], 0);
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 31_000);
        assert!(out.events.contains(&Event::StopTriggered {
            reason: StopReason::MaxTime,
        }));
    }

    #[test]
    fn a_scale_connecting_mid_shot_arms_saw() {
        let mut core = CremaCore::new();
        // Target + max-time set, but NO scale yet: the AutoStop arms at first
        // flow with `weight: None` (max-time keeps the targets Some) — the
        // issue #15 shape, where the scale registers a beat after first flow.
        core.set_profile_target_weight(Some(30.0));
        core.set_max_shot_duration(Some(45.0));
        core.on_notification(Source::De1State, &[4, 5], 0);
        core.connect_scale("BOOKOO_SC", &[]);
        // A 35 g reading past the arming delay must now trip SAW.
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 6_000);
        assert!(out.events.contains(&Event::StopTriggered {
            reason: StopReason::Weight,
        }));
    }

    #[test]
    fn a_de1_reset_keeps_the_scale_registration() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        // The BLE managers reset the whole core on a DE1 drop while the scale
        // link stays up; the codec must survive so weight decoding + SAW work
        // in the next session without a scale reconnect (issue #15).
        core.reset();
        assert!(core.scale_capabilities().is_some());
        core.set_profile_target_weight(Some(30.0));
        core.on_notification(Source::De1State, &[4, 5], 0);
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(3_500), 6_000);
        assert!(out.events.contains(&Event::StopTriggered {
            reason: StopReason::Weight,
        }));
    }

    #[test]
    fn a_scale_disconnect_mid_shot_drops_the_saw_leg() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
        core.set_profile_target_weight(Some(30.0));
        core.set_max_shot_duration(Some(45.0));
        core.on_notification(Source::De1State, &[4, 5], 0);
        core.disconnect_scale();
        // The scale is gone; re-registering a different one mid-shot must not
        // fire on the stale weight leg unless the reading really crosses.
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.on_notification(Source::ScaleWeight, &bookoo_packet(1_000), 6_000);
        assert!(!out.events.iter().any(|e| matches!(
            e,
            Event::StopTriggered {
                reason: StopReason::Weight
            }
        )));
    }

    #[test]
    fn a_registered_scale_demotes_the_volume_leg_to_a_fallback() {
        let mut core = CremaCore::new();
        core.set_profile_volume_limit(Some(36.0));
        // No scale: SAV is the fallback stop.
        assert_eq!(
            core.effective_stop_targets().and_then(|t| t.volume),
            Some(36.0)
        );
        // Scale registered: volume must not race the weight target.
        core.connect_scale("BOOKOO_SC", &[]);
        assert_eq!(core.effective_stop_targets().and_then(|t| t.volume), None);
        // …unless the user opts into both caps.
        core.set_volume_stop_with_scale(true);
        assert_eq!(
            core.effective_stop_targets().and_then(|t| t.volume),
            Some(36.0)
        );
        // Scale gone again: the fallback re-arms regardless of the opt-in.
        core.set_volume_stop_with_scale(false);
        core.disconnect_scale();
        assert_eq!(
            core.effective_stop_targets().and_then(|t| t.volume),
            Some(36.0)
        );
    }

    #[test]
    fn defensive_normalisation_treats_zero_and_negative_as_none() {
        let mut core = CremaCore::new();
        core.set_profile_target_weight(Some(0.0));
        core.set_shot_target_weight(Some(-5.0));
        core.set_profile_volume_limit(Some(f32::NAN));
        core.set_max_shot_duration(Some(0.0));
        // None of these arms anything.
        core.on_notification(Source::De1State, &[4, 5], 0);
        let out = core.on_notification(Source::De1ShotSample, &SAMPLE, 31_000);
        assert!(
            !out.events
                .iter()
                .any(|e| matches!(e, Event::StopTriggered { .. }))
        );
    }

    #[test]
    fn a_shot_start_auto_tares_a_connected_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
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

    #[test]
    fn auto_tare_disabled_suppresses_the_tare_at_shot_start() {
        // Shot start emits a tare + timer-reset + timer-start, all as
        // WriteScale commands. With auto_tare disabled we expect two writes
        // (reset + start) rather than three.
        let mut on_core = CremaCore::new();
        on_core.connect_scale("BOOKOO_SC", &[]);
        let on_out = on_core.on_notification(Source::De1State, &[4, 5], 1_000);
        let on_writes = on_out
            .commands
            .iter()
            .filter(|c| matches!(c, Command::WriteScale { .. }))
            .count();

        let mut off_core = CremaCore::new();
        off_core.connect_scale("BOOKOO_SC", &[]);
        off_core.set_auto_tare(false);
        let off_out = off_core.on_notification(Source::De1State, &[4, 5], 1_000);
        let off_writes = off_out
            .commands
            .iter()
            .filter(|c| matches!(c, Command::WriteScale { .. }))
            .count();

        assert_eq!(on_writes, off_writes + 1, "auto_tare=false drops the tare");
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
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
        // The user configures 50 ml while a scale is connected.
        core.set_steam_hotwater_settings(hotwater_settings(50.0));
        // The scale disconnects (an unrecognised name clears `scale`).
        assert_eq!(core.connect_scale("Some Random Device", &[]), None);
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
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
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
        assert!(peak_temp.is_some(), "telemetry arrived → peak_temp is Some",);
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
        core.connect_scale("BOOKOO_SC", &[]);
        // Start an espresso shot.
        core.on_notification(Source::De1State, &[4, 5], 1_000);
        // The auto-tare lands: the scale reports ~zero, clearing the settling
        // gate (otherwise the pre-tare readings below would be suppressed).
        core.on_notification(Source::ScaleWeight, &bookoo_packet(0), 1_050);
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
        core.connect_scale("ACAIA-X", &[]);
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
        core.connect_scale("BOOKOO_SC", &[]);
        let out = core.start_timer();
        let Some(Command::WriteScale { data }) = out.commands.first() else {
            panic!("expected a WriteScale command");
        };
        assert_eq!(data.as_slice(), bookoo::TIMER_START.as_slice());
    }

    #[test]
    fn stop_and_reset_timer_emit_their_writes_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC", &[]);
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
        core.connect_scale("ACAIA-X", &[]);
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
        // TCL + reaprime agree on ×100 + 4-byte; the
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
        // The wire value is seconds × 100. The default
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
        // are distinct registers, not aliases. UserPresent AND FeatureFlags
        // byte_len are both 4 — every DE1 MMR write is a full 32-bit word
        // (de1app Len="04" + reaprime setUint32 LE; a sub-word write is dropped
        // by firmware — AUDIT.md F1). The wire value bytes are unchanged (small
        // values zero-extend); only the Len byte changes.
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
        assert_eq!(up_data[0], 4, "UserPresent Len=4 (matches TCL + reaprime)");
        assert_eq!(
            ff_data[0], 4,
            "FeatureFlags Len=4 — full 32-bit word (AUDIT.md F1)"
        );
        // Boolean payload: low byte = 1, high bytes zero.
        assert_eq!(&up_data[4..8], &[0x01, 0x00, 0x00, 0x00]);
        // 16-bit FeatureFlags value 0x1234 rides the low two bytes, LE, zero-extended.
        assert_eq!(
            &ff_data[4..8],
            &[0x34, 0x12, 0x00, 0x00],
            "FeatureFlags value preserved"
        );
    }

    #[test]
    fn set_heater_voltage_rejects_values_outside_120_or_230() {
        // The firmware accepts only 120 or 230 as user-committed
        // values. Anything else is a clamp failure at the
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
        // Per reaprime de1.models.dart:301 heaterV "Nominal Heater
        // Voltage (0, 120V or 230V). +1000 if it's a set value." —
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

    // ---- Range-clamp safety audit -------------------------------------
    //
    // These tests pin the defense-in-depth clamps the core applies on
    // settings writes that could send dangerous / out-of-range values to
    // the firmware. The shell already steppers most of these into a safe
    // range; the core clamp is the last line for replays, external JS
    // callers, and future bridges.

    /// Pull the raw 4-byte LE payload from a single MMR write.
    fn mmr_write_payload(out: &CoreOutput) -> [u8; 4] {
        assert_eq!(out.commands.len(), 1, "expected one MMR write");
        let Command::WriteCharacteristic { data, .. } = &out.commands[0] else {
            panic!("expected WriteCharacteristic");
        };
        [data[4], data[5], data[6], data[7]]
    }

    #[test]
    fn set_fan_threshold_clamps_above_50c() {
        // reaprime MMRItem.fanThreshold min=0, max=50.
        let core = CremaCore::new();
        let in_range = core.set_fan_threshold(45);
        assert_eq!(mmr_write_payload(&in_range)[0], 45);

        let over = core.set_fan_threshold(200);
        assert_eq!(
            mmr_write_payload(&over)[0],
            50,
            "200 °C must clamp to the 50 °C reaprime cap"
        );

        // The boundary stays untouched.
        let boundary = core.set_fan_threshold(50);
        assert_eq!(mmr_write_payload(&boundary)[0], 50);
    }

    #[test]
    fn set_fan_threshold_emits_len_byte_4_and_4_byte_payload() {
        // Pins the wire Len byte and full payload — reaprime
        // `de1.models.dart:247 fanThreshold` is `MmrValueKind.int32`
        // (4 bytes). The pre-fix `byte_len=1` was the same class of
        // short-packet bug fixed for the 8 heater-tweak setters in
        // `9ef8ee5`; this test pins the Len byte so the regression
        // can't sneak back through the clamp test (which only reads
        // payload[0]).
        let core = CremaCore::new();
        let out = core.set_fan_threshold(45);
        let Some(Command::WriteCharacteristic { target, data }) = out.commands.first() else {
            panic!("expected one WriteCharacteristic");
        };
        assert_eq!(*target, WriteTarget::De1MmrWrite);
        assert_eq!(data.len(), 20, "MMR packet is 20 bytes wide");
        assert_eq!(data[0], 4, "Len byte must be 4 (full MMR word)");
        // 45 °C as little-endian u32 = [0x2D, 0x00, 0x00, 0x00].
        assert_eq!(&data[4..8], &[0x2D, 0x00, 0x00, 0x00]);
    }

    #[test]
    fn set_steam_highflow_start_clamps_above_4_seconds() {
        // reaprime steamStartSecs says "Valid range 0.0 - 4.0".
        let core = CremaCore::new();
        // 0.7 s × 100 = 70 (unchanged — within range).
        let in_range = core.set_steam_highflow_start(0.7);
        assert_eq!(u32::from_le_bytes(mmr_write_payload(&in_range)), 70);

        // 10.0 s clamps to 4.0 s → raw 400.
        let over = core.set_steam_highflow_start(10.0);
        assert_eq!(
            u32::from_le_bytes(mmr_write_payload(&over)),
            400,
            "10.0 s must clamp to the 4.0 s reaprime cap"
        );
    }

    #[test]
    fn set_steam_highflow_start_clamps_below_safe_minimum() {
        // The lower bound is 0.3 s (not 0.0). The firmware spec text
        // warns "0 may result in an overheated heater. Be careful." —
        // 0 skips the high-flow blast that dissipates accumulated
        // heater heat. 0.3 s is the smallest blast that meaningfully
        // clears the steam line.
        let core = CremaCore::new();

        // 0 clamps up to 0.3 → raw 30.
        let zero = core.set_steam_highflow_start(0.0);
        assert_eq!(
            u32::from_le_bytes(mmr_write_payload(&zero)),
            30,
            "0 s must clamp up to the 0.3 s safe minimum",
        );

        // Negative clamps up to 0.3 → raw 30.
        let negative = core.set_steam_highflow_start(-1.0);
        assert_eq!(
            u32::from_le_bytes(mmr_write_payload(&negative)),
            30,
            "negative values must clamp up to the 0.3 s safe minimum",
        );

        // 0.3 s is the boundary; passes through unchanged → raw 30.
        let boundary = core.set_steam_highflow_start(0.3);
        assert_eq!(u32::from_le_bytes(mmr_write_payload(&boundary)), 30);
    }

    #[test]
    fn set_hot_water_idle_temp_clamps_above_99c() {
        // Ceiling = 99 °C (matches TCL `machine.tcl:285
        // hot_water_idle_temp "990"` = 99.0 °C). At 100 °C the boiler
        // boils → pressure event → firmware safety stop; 99 °C is one
        // degree under that trigger. reaprime doesn't clamp.
        let core = CremaCore::new();
        // 95.0 °C × 10 = 950 (legacy default, unchanged).
        let in_range = core.set_hot_water_idle_temp(95.0);
        assert_eq!(u32::from_le_bytes(mmr_write_payload(&in_range)), 950);

        // 99.0 °C is the boundary; passes through unchanged → raw 990.
        let boundary = core.set_hot_water_idle_temp(99.0);
        assert_eq!(u32::from_le_bytes(mmr_write_payload(&boundary)), 990);

        // 100.0 °C clamps down to 99.0 → raw 990.
        let at_boil = core.set_hot_water_idle_temp(100.0);
        assert_eq!(
            u32::from_le_bytes(mmr_write_payload(&at_boil)),
            990,
            "100 °C (boiling) must clamp to the 99 °C TCL safe max",
        );

        // 150.0 °C clamps to 99.0 °C → raw 990.
        let over = core.set_hot_water_idle_temp(150.0);
        assert_eq!(
            u32::from_le_bytes(mmr_write_payload(&over)),
            990,
            "150 °C must clamp to the 99 °C TCL safe max"
        );

        // Negative input clamps to 0.
        let under = core.set_hot_water_idle_temp(-10.0);
        assert_eq!(u32::from_le_bytes(mmr_write_payload(&under)), 0);
    }

    #[test]
    fn set_cup_warmer_temperature_clamps_above_80c() {
        // Shell stepper bounds 0..80; core mirrors.
        let core = CremaCore::new();
        let in_range = core.set_cup_warmer_temperature(60);
        // CupWarmer is 2-byte but mmr_write_payload reads the first 4;
        // the low byte carries the value, the rest are zero.
        assert_eq!(mmr_write_payload(&in_range)[0], 60);

        let over = core.set_cup_warmer_temperature(255);
        assert_eq!(
            mmr_write_payload(&over)[0],
            80,
            "255 °C must clamp to the 80 °C shell cap"
        );
    }

    #[test]
    fn set_calibration_flow_multiplier_clamps_to_reaprime_range() {
        // reaprime calFlowEst min=130, max=2000 raw
        // (= 0.13..=2.0 multiplier). A value outside this range
        // corrupts the flow-estimation algorithm.
        let core = CremaCore::new();
        // 1.0 multiplier → raw 1000 (legacy default), 4-byte LE.
        let nominal = core.set_calibration_flow_multiplier(1.0);
        let raw = u32::from_le_bytes(mmr_write_payload(&nominal));
        assert_eq!(raw, 1000);

        // 0.0 clamps up to 0.13 → raw 130.
        let under = core.set_calibration_flow_multiplier(0.0);
        let raw_under = u32::from_le_bytes(mmr_write_payload(&under));
        assert_eq!(
            raw_under, 130,
            "0.0 must clamp up to the 0.13 reaprime floor"
        );

        // 5.0 clamps down to 2.0 → raw 2000.
        let over = core.set_calibration_flow_multiplier(5.0);
        let raw_over = u32::from_le_bytes(mmr_write_payload(&over));
        assert_eq!(
            raw_over, 2000,
            "5.0 must clamp down to the 2.0 reaprime ceiling"
        );
    }

    #[test]
    fn set_calibration_flow_multiplier_emits_len_byte_4_and_4_byte_payload() {
        // byte_len normalised from 2 → 4 to match TCL `de1_comms.tcl:1336`
        // and reaprime `_writeMMRInt` (both always write 4 wire bytes).
        let core = CremaCore::new();
        let out = core.set_calibration_flow_multiplier(1.0);
        let Some(Command::WriteCharacteristic { target, data }) = out.commands.first() else {
            panic!("expected one WriteCharacteristic");
        };
        assert_eq!(*target, WriteTarget::De1MmrWrite);
        assert_eq!(data[0], 4, "Len byte must be 4 (matches TCL + reaprime)");
        // 1000 = 0x03E8 → little-endian [0xE8, 0x03, 0x00, 0x00].
        assert_eq!(&data[4..8], &[0xE8, 0x03, 0x00, 0x00]);
    }

    #[test]
    fn set_steam_hotwater_settings_clamps_group_temp_above_105c() {
        // TCL vars.tcl range_check_shot_variables clamps
        // espresso_temperature to 0..=105. Above 100 °C the group
        // cannot usefully run; +5 °C head-room matches the TCL guard.
        let mut core = CremaCore::new();
        let settings = ShotSettings {
            group_temp_c: 200.0,
            ..ShotSettings::default()
        };
        let out = core.set_steam_hotwater_settings(settings);
        assert_eq!(out.commands.len(), 1);
        // Round-trip the wire packet to read the clamped group temp.
        let Command::WriteCharacteristic { data, .. } = &out.commands[0] else {
            panic!("expected WriteCharacteristic");
        };
        let decoded = ShotSettings::decode(data).expect("packet round-trips");
        assert_eq!(
            decoded.group_temp_c, 105.0,
            "group temp must clamp to 105 °C"
        );
        // The retained settings carry the clamped value, not the input.
        let stored = core
            .steam_hotwater_settings
            .as_ref()
            .expect("settings retained");
        assert_eq!(stored.group_temp_c, 105.0);
    }

    #[test]
    fn set_steam_hotwater_settings_clamps_hot_water_temp_above_100c() {
        // Above 100 °C boils → pressure event.
        let mut core = CremaCore::new();
        let settings = ShotSettings {
            hot_water_temp_c: 150.0,
            ..ShotSettings::default()
        };
        core.set_steam_hotwater_settings(settings);
        let stored = core
            .steam_hotwater_settings
            .as_ref()
            .expect("settings retained");
        assert_eq!(stored.hot_water_temp_c, 100.0);
    }

    #[test]
    fn set_steam_hotwater_settings_clamps_steam_temp_above_170c() {
        // Legacy default skin slider runs 134..170; above
        // 170 risks over-heating the steam boiler.
        let mut core = CremaCore::new();
        let settings = ShotSettings {
            steam_temp_c: 250.0,
            ..ShotSettings::default()
        };
        core.set_steam_hotwater_settings(settings);
        let stored = core
            .steam_hotwater_settings
            .as_ref()
            .expect("settings retained");
        assert_eq!(stored.steam_temp_c, 170.0);
    }

    #[test]
    fn set_steam_hotwater_settings_snaps_sub_steam_temp_to_zero() {
        // Per legacy binary.tcl:184-186, values below 135 °C are
        // useless for steam (the heater would never actually steam),
        // so the legacy app forces them to 0 (disable steam). Crema
        // matches the legacy 135 °C floor exactly, leaving 0 itself
        // untouched (the explicit "disabled" intent).
        let mut core = CremaCore::new();
        let settings = ShotSettings {
            steam_temp_c: 80.0,
            ..ShotSettings::default()
        };
        core.set_steam_hotwater_settings(settings);
        let stored = core
            .steam_hotwater_settings
            .as_ref()
            .expect("settings retained");
        assert_eq!(
            stored.steam_temp_c, 0.0,
            "80 °C falls below the 135 °C steam floor"
        );

        // 134 °C — one degree under the legacy TCL floor; must also snap to 0.
        let just_under = ShotSettings {
            steam_temp_c: 134.0,
            ..ShotSettings::default()
        };
        core.set_steam_hotwater_settings(just_under);
        let stored = core
            .steam_hotwater_settings
            .as_ref()
            .expect("settings retained");
        assert_eq!(
            stored.steam_temp_c, 0.0,
            "134 °C is below the 135 °C TCL floor and must snap to 0",
        );

        // Explicit 0 stays 0 (do-not-heat intent).
        let off = ShotSettings {
            steam_temp_c: 0.0,
            ..ShotSettings::default()
        };
        core.set_steam_hotwater_settings(off);
        let stored = core
            .steam_hotwater_settings
            .as_ref()
            .expect("settings retained");
        assert_eq!(stored.steam_temp_c, 0.0);

        // In-range 150 °C passes through.
        let normal = ShotSettings {
            steam_temp_c: 150.0,
            ..ShotSettings::default()
        };
        core.set_steam_hotwater_settings(normal);
        let stored = core
            .steam_hotwater_settings
            .as_ref()
            .expect("settings retained");
        assert_eq!(stored.steam_temp_c, 150.0);

        // 135 °C — the boundary value; must pass through unchanged.
        let boundary = ShotSettings {
            steam_temp_c: 135.0,
            ..ShotSettings::default()
        };
        core.set_steam_hotwater_settings(boundary);
        let stored = core
            .steam_hotwater_settings
            .as_ref()
            .expect("settings retained");
        assert_eq!(
            stored.steam_temp_c, 135.0,
            "135 °C is the TCL floor and must pass through"
        );
    }

    #[test]
    fn reset_machine_defaults_emits_8_writes_with_known_baselines() {
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
            // Fan: 50 °C (post-clamp; reaprime's source says 55). 4-byte
            // LE: reaprime declares `MmrValueKind.int32` — raw °C in the
            // low byte, zero-pad the rest.
            (0x0080_3808, 4, &[0x32, 0x00, 0x00, 0x00]),
            // HotWaterIdleTemp: 95 °C × 10 = 950 = 0x03B6.
            (0x0080_3818, 4, &[0xB6, 0x03, 0x00, 0x00]),
            // Phase1FlowRate: 2.0 × 10 = 20 = 0x14.
            (0x0080_3810, 4, &[0x14, 0x00, 0x00, 0x00]),
            // Phase2FlowRate: 4.0 × 10 = 40 = 0x28.
            (0x0080_3814, 4, &[0x28, 0x00, 0x00, 0x00]),
            // HeaterUp2Timeout: 4.0 s × 10 = 40 = 0x28.
            (0x0080_3838, 4, &[0x28, 0x00, 0x00, 0x00]),
            // RefillKit: 2 (auto).
            (0x0080_385C, 4, &[0x02, 0x00, 0x00, 0x00]),
            // CalFlowEst: 1.0 × 1000 = 1000 = 0x03E8. byte_len
            // normalised to 4 (matches TCL + reaprime).
            (0x0080_383C, 4, &[0xE8, 0x03, 0x00, 0x00]),
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
            assert_eq!(&data[4..4 + payload.len()], *payload, "write #{i} payload");
        }
    }

    /// Pull the single MMR write a heater-tweak setter is expected to emit
    /// and assert the wire address + payload bytes. Shared between every
    /// per-register heater-tweak setter test so the assertion is one place,
    /// not eight.
    fn assert_single_mmr_write(out: &CoreOutput, register: MmrRegister, payload: &[u8]) {
        assert_eq!(out.commands.len(), 1, "expected one MMR write");
        let Command::WriteCharacteristic { target, data } = &out.commands[0] else {
            panic!("expected WriteCharacteristic");
        };
        assert_eq!(*target, WriteTarget::De1MmrWrite);
        let addr = register.address();
        let addr_bytes = [
            ((addr >> 16) & 0xFF) as u8,
            ((addr >> 8) & 0xFF) as u8,
            (addr & 0xFF) as u8,
        ];
        assert_eq!(&data[1..4], &addr_bytes, "wire address bytes");
        assert_eq!(&data[4..4 + payload.len()], payload, "wire payload bytes");
    }

    #[test]
    fn set_phase_1_flow_rate_emits_one_phase1_write() {
        let core = CremaCore::new();
        let out = core.set_phase_1_flow_rate(1.5);
        // 1.5 ml/s × 10 = 15 → 4-byte LE (Len=4, one MMR word).
        // TCL `de1_comms.tcl:1127` + reaprime `heaterUp1Flow writeScale 10.0`
        // both write a full 4-byte payload; matched here.
        assert_single_mmr_write(&out, MmrRegister::Phase1FlowRate, &[15, 0, 0, 0]);
    }

    #[test]
    fn set_phase_2_flow_rate_emits_one_phase2_write() {
        let core = CremaCore::new();
        let out = core.set_phase_2_flow_rate(4.0);
        // 4.0 ml/s × 10 = 40 → 4-byte LE.
        assert_single_mmr_write(&out, MmrRegister::Phase2FlowRate, &[40, 0, 0, 0]);
    }

    #[test]
    fn set_hot_water_idle_temp_emits_one_idle_temp_write() {
        let core = CremaCore::new();
        let out = core.set_hot_water_idle_temp(85.0);
        // 85.0 °C × 10 = 850 = 0x352 → 4-byte LE [0x52, 0x03, 0x00, 0x00].
        // TCL `machine.tcl` stores the pre-scaled raw (e.g. 990 = 99.0°C);
        // reaprime `waterHeaterIdleTemp writeScale 10.0`.
        assert_single_mmr_write(
            &out,
            MmrRegister::HotWaterIdleTemp,
            &[0x52, 0x03, 0x00, 0x00],
        );
    }

    #[test]
    fn set_espresso_warmup_timeout_emits_one_warmup_write() {
        let core = CremaCore::new();
        let out = core.set_espresso_warmup_timeout(Duration::from_secs(3));
        // 3.0 s × 10 = 30 → 4-byte LE [0x1E, 0x00, 0x00, 0x00].
        // TCL `machine.tcl espresso_warmup_timeout 10` = 1.0s pre-scaled;
        // reaprime `heaterUp2Timeout writeScale 10.0`.
        assert_single_mmr_write(
            &out,
            MmrRegister::HeaterUp2Timeout,
            &[0x1E, 0x00, 0x00, 0x00],
        );
    }

    #[test]
    fn set_steam_two_tap_stop_emits_one_steam_two_tap_write() {
        let core = CremaCore::new();
        let out = core.set_steam_two_tap_stop(1);
        // 4-byte LE int (reaprime `steamPurgeMode` is `MmrValueKind.int32`).
        assert_single_mmr_write(&out, MmrRegister::SteamTwoTapStop, &[1, 0, 0, 0]);
    }

    #[test]
    fn set_flush_timeout_emits_one_flush_timeout_write() {
        let core = CremaCore::new();
        let out = core.set_flush_timeout(Duration::from_secs(5));
        // 5 s = 5000 ms / 100 = 50 → 4-byte LE.
        assert_single_mmr_write(&out, MmrRegister::FlushTimeout, &[50, 0, 0, 0]);
    }

    #[test]
    fn set_flush_flow_rate_emits_one_flush_flow_write() {
        let core = CremaCore::new();
        let out = core.set_flush_flow_rate(3.0);
        // 3.0 ml/s × 10 = 30 → 4-byte LE.
        assert_single_mmr_write(&out, MmrRegister::FlushFlowRate, &[30, 0, 0, 0]);
    }

    #[test]
    fn set_hot_water_flow_rate_emits_one_hot_water_flow_write() {
        let core = CremaCore::new();
        let out = core.set_hot_water_flow_rate(4.0);
        // 4.0 ml/s × 10 = 40 → 4-byte LE.
        assert_single_mmr_write(&out, MmrRegister::HotWaterFlowRate, &[40, 0, 0, 0]);
    }

    /// Every heater-tweak setter must emit a packet with `Len=4` (one MMR
    /// word) and a 4-byte LE payload. Pins the wire byte that TCL
    /// `mmr_write` always sets to `"04"` and reaprime's
    /// `_mmrWriteRaw` derives from `_packMMRInt` (always 4 bytes). The
    /// pre-2026-05-24 split wrote short packets (Len=1 / Len=2); same
    /// class of bug fixed earlier for `set_steam_flow` (see
    /// `set_steam_flow_scales_by_one_hundred_into_a_four_byte_slot`).
    #[test]
    fn heater_tweak_setters_emit_len_byte_4_and_4_byte_payload() {
        let core = CremaCore::new();
        let cases: [CoreOutput; 8] = [
            core.set_phase_1_flow_rate(2.0),
            core.set_phase_2_flow_rate(4.0),
            core.set_hot_water_idle_temp(95.0),
            core.set_espresso_warmup_timeout(Duration::from_secs(3)),
            core.set_steam_two_tap_stop(1),
            core.set_flush_timeout(Duration::from_secs(5)),
            core.set_flush_flow_rate(6.0),
            core.set_hot_water_flow_rate(1.0),
        ];
        for out in cases {
            let Some(Command::WriteCharacteristic { data, .. }) = out.commands.first() else {
                panic!("expected one WriteCharacteristic");
            };
            assert_eq!(data.len(), 20, "MMR packet is 20 bytes wide");
            assert_eq!(data[0], 4, "Len byte must be 4 (full MMR word)");
            // Bytes 8..20 must be zero — they're payload tail past the word
            // and the wire pad. The first 4 payload bytes (data[4..8]) carry
            // the value; the rest is zero per `mmr::write_request`.
            for (i, &b) in data[8..20].iter().enumerate() {
                assert_eq!(b, 0, "byte {} of pad is non-zero ({:#x})", 8 + i, b);
            }
        }
    }

    /// Round-trip: a setter encodes a value as a 4-byte LE word, and the
    /// scale catalog decodes the same word back to the engineering-units
    /// value. Pins write-scale × read-scale = 1.0 for every heater-tweak
    /// register. Catches a class of bug where the write path scales by ×10
    /// but the read path decodes as ÷100, etc.
    #[test]
    fn heater_tweak_round_trip_through_scale_catalog() {
        let core = CremaCore::new();
        // Each tuple: (the call that emits the write, the register the
        // write targets, the engineering-units value we wrote).
        let cases: [(CoreOutput, MmrRegister, f32); 5] = [
            (
                core.set_phase_1_flow_rate(2.5),
                MmrRegister::Phase1FlowRate,
                2.5,
            ),
            (
                core.set_phase_2_flow_rate(4.0),
                MmrRegister::Phase2FlowRate,
                4.0,
            ),
            (
                core.set_hot_water_idle_temp(95.0),
                MmrRegister::HotWaterIdleTemp,
                95.0,
            ),
            (
                core.set_flush_flow_rate(6.0),
                MmrRegister::FlushFlowRate,
                6.0,
            ),
            (
                core.set_hot_water_flow_rate(1.0),
                MmrRegister::HotWaterFlowRate,
                1.0,
            ),
        ];
        for (out, reg, expected) in cases {
            let Some(Command::WriteCharacteristic { data, .. }) = out.commands.first() else {
                panic!("expected one WriteCharacteristic for {reg:?}");
            };
            // Pull the 4-byte LE payload that the firmware will store.
            let raw = u32::from_le_bytes([data[4], data[5], data[6], data[7]]);
            // Decode through the same scale catalog the read path uses
            // (`reg.scale()` × raw = engineering-units value). Heater-tweak
            // raw values are small (<= 65_535) so an f32 round-trip preserves
            // the integer exactly.
            #[allow(clippy::cast_precision_loss)]
            let decoded = raw as f32 * reg.scale();
            assert!(
                (decoded - expected).abs() < 0.05,
                "{reg:?}: wrote {expected} -> raw {raw} -> decoded {decoded}",
            );
        }
    }

    /// Boundary: every heater-tweak setter clamps gracefully when callers
    /// push extreme inputs through. Pinning the contracts:
    ///
    /// - flow-rate setters clamp at `0.0` (negative → 0) and at the u16
    ///   raw ceiling (65_535 → 6553.5 ml/s);
    /// - `set_hot_water_idle_temp` clamps similarly;
    /// - `set_espresso_warmup_timeout` saturates at u16 raw deciseconds.
    ///
    /// All emit a 4-byte LE payload regardless of value (no silent truncation).
    #[test]
    fn heater_tweak_setters_clamp_at_register_bounds() {
        let core = CremaCore::new();
        // Negative ml/s → clamp to 0, payload [0,0,0,0].
        let neg = core.set_phase_1_flow_rate(-1.0);
        let Some(Command::WriteCharacteristic { data, .. }) = neg.commands.first() else {
            panic!("expected write");
        };
        assert_eq!(&data[4..8], &[0u8; 4], "negative ml/s clamps to 0");
        assert_eq!(data[0], 4, "Len byte stays 4");

        // Huge ml/s → clamp to u16 max (65_535 raw = 6553.5 ml/s).
        let huge = core.set_flush_flow_rate(1e9_f32);
        let Some(Command::WriteCharacteristic { data, .. }) = huge.commands.first() else {
            panic!("expected write");
        };
        assert_eq!(
            &data[4..8],
            &[0xFF, 0xFF, 0x00, 0x00],
            "ml/s clamps at u16 raw"
        );

        // Warmup timeout saturates at u16 raw (~6553.5 s).
        let long = core.set_espresso_warmup_timeout(Duration::from_secs(1_000_000));
        let Some(Command::WriteCharacteristic { data, .. }) = long.commands.first() else {
            panic!("expected write");
        };
        assert_eq!(
            &data[4..8],
            &[0xFF, 0xFF, 0x00, 0x00],
            "warmup timeout saturates at u16 raw"
        );
    }

    /// Positive path of the lockout-attribution test (the existing
    /// `heater_tweak_setters_attribute_lockout_to_their_own_name` only
    /// exercises the blocked case). Without the lockout, every heater-tweak
    /// setter must emit exactly one MMR write and no events — confirming
    /// the lockout assertion isn't a false positive of a setter that
    /// always no-ops.
    #[test]
    fn heater_tweak_setters_emit_a_write_when_not_locked_out() {
        let core = CremaCore::new();
        // firmware_lock_override defaults to false, so this is the open path.
        let cases: [(CoreOutput, MmrRegister); 8] = [
            (core.set_phase_1_flow_rate(2.0), MmrRegister::Phase1FlowRate),
            (core.set_phase_2_flow_rate(4.0), MmrRegister::Phase2FlowRate),
            (
                core.set_hot_water_idle_temp(95.0),
                MmrRegister::HotWaterIdleTemp,
            ),
            (
                core.set_espresso_warmup_timeout(Duration::from_secs(3)),
                MmrRegister::HeaterUp2Timeout,
            ),
            (core.set_steam_two_tap_stop(1), MmrRegister::SteamTwoTapStop),
            (
                core.set_flush_timeout(Duration::from_secs(5)),
                MmrRegister::FlushTimeout,
            ),
            (core.set_flush_flow_rate(6.0), MmrRegister::FlushFlowRate),
            (
                core.set_hot_water_flow_rate(1.0),
                MmrRegister::HotWaterFlowRate,
            ),
        ];
        for (out, reg) in cases {
            assert_eq!(out.commands.len(), 1, "{reg:?}: expected one write");
            assert!(out.events.is_empty(), "{reg:?}: no events on success");
            let Command::WriteCharacteristic { target, data } = &out.commands[0] else {
                panic!("{reg:?}: expected WriteCharacteristic");
            };
            assert_eq!(*target, WriteTarget::De1MmrWrite);
            assert_eq!(data[0], 4, "{reg:?}: Len byte = 4");
        }
    }

    #[test]
    fn heater_tweak_setters_attribute_lockout_to_their_own_name() {
        // Pins per-setter attribution. With the firmware-upload lockout
        // engaged, each split heater-tweak setter must emit a
        // [`Event::FirmwareLockoutHit`] whose `method` names that setter —
        // not the legacy bundled `set_heater_tweaks`. This is the contract
        // the task split establishes (so the shell can correlate the
        // refusal back to the input that triggered it).
        let mut core = CremaCore::new();
        core.firmware_lock_override = true;

        let cases: [(CoreOutput, &str); 8] = [
            (core.set_phase_1_flow_rate(1.5), "set_phase_1_flow_rate"),
            (core.set_phase_2_flow_rate(4.0), "set_phase_2_flow_rate"),
            (
                core.set_hot_water_idle_temp(85.0),
                "set_hot_water_idle_temp",
            ),
            (
                core.set_espresso_warmup_timeout(Duration::from_secs(30)),
                "set_espresso_warmup_timeout",
            ),
            (core.set_steam_two_tap_stop(1), "set_steam_two_tap_stop"),
            (
                core.set_flush_timeout(Duration::from_secs(5)),
                "set_flush_timeout",
            ),
            (core.set_flush_flow_rate(3.0), "set_flush_flow_rate"),
            (core.set_hot_water_flow_rate(4.0), "set_hot_water_flow_rate"),
        ];
        for (out, expected_method) in cases {
            assert!(
                out.commands.is_empty(),
                "lockout must suppress the MMR write for {expected_method}",
            );
            assert_eq!(out.events.len(), 1, "one event for {expected_method}");
            let Event::FirmwareLockoutHit { method } = &out.events[0] else {
                panic!("expected FirmwareLockoutHit for {expected_method}");
            };
            assert_eq!(method, expected_method);
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
        // not an accident.
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

    // ── Profile upload ────────────────────────────────────────────────────

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
                id: String::new(),
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
        fn ack_every_frame(
            core: &mut CremaCore,
            commands: &[Command],
            now_ms_step: u64,
        ) -> Vec<Event> {
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

        /// Upload a one-step profile carrying `volume` and return the tail
        /// write (the last FrameWrite command).
        fn tail_write(core: &mut CremaCore, volume: u16) -> Vec<u8> {
            let mut p = profile("Vol", vec![step("a", Pump::Pressure, None)]);
            p.max_total_volume_ml = volume;
            let out = core.upload_profile(&p, Duration::ZERO).unwrap();
            match out.commands.last().expect("upload emits writes") {
                Command::WriteCharacteristic { data, .. } => data.clone(),
                other => panic!("tail must be a characteristic write, got {other:?}"),
            }
        }

        #[test]
        fn the_uploaded_tail_never_carries_a_volume_limit() {
            // The firmware-side dispensed-volume stop stays disarmed no
            // matter what the profile says (reference-app consensus): a
            // 100 ml profile uploads the same 0 = "no limit" tail.
            let zero_tail = tail_write(&mut CremaCore::new(), 0);
            assert_eq!(tail_write(&mut CremaCore::new(), 100), zero_tail);
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
            assert!(
                last.iter()
                    .any(|e| matches!(e, Event::ProfileUploadCompleted { .. }))
            );
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
            assert!(
                last.iter()
                    .any(|e| matches!(e, Event::ProfileUploadCompleted { .. }))
            );
        }

        #[test]
        fn thirty_two_step_profile_is_at_the_boundary() {
            let mut core = CremaCore::new();
            let p = profile(
                "Max",
                (0..32)
                    .map(|i| step(&format!("s{i}"), Pump::Pressure, None))
                    .collect(),
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
                (0..33)
                    .map(|i| step(&format!("s{i}"), Pump::Pressure, None))
                    .collect(),
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
            let p = profile(
                "Two",
                vec![
                    step("a", Pump::Pressure, None),
                    step("b", Pump::Pressure, None),
                ],
            );
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
            assert!(matches!(out.events[1], Event::ProfileUploadStarted { .. }));
            assert!(core.profile_upload_in_progress());
        }

        #[test]
        fn on_tick_after_timeout_fires_ack_timeout() {
            let mut core = CremaCore::new();
            let p = profile(
                "Two",
                vec![
                    step("a", Pump::Pressure, None),
                    step("b", Pump::Pressure, None),
                ],
            );
            let _ = core.upload_profile(&p, Duration::ZERO).unwrap();
            // Tick at 5 seconds (the timeout); no acks have arrived.
            let tick = core
                .on_tick(u64::try_from(PROFILE_UPLOAD_ACK_TIMEOUT.as_millis()).unwrap_or(u64::MAX));
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
            let p = profile(
                "Two",
                vec![
                    step("a", Pump::Pressure, None),
                    step("b", Pump::Pressure, None),
                ],
            );
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
            let p = profile(
                "Two",
                vec![
                    step("a", Pump::Pressure, None),
                    step("b", Pump::Pressure, None),
                ],
            );
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

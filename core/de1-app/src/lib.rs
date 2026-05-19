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

pub mod error;
pub mod event;

pub use error::AppError;
pub use event::{Command, CoreOutput, Event, Source, WriteTarget};

use de1_domain::{
    AutoStop, FlowAlgorithm, FlowEstimator, STOP_WEIGHT_BEFORE_SECONDS, ShotEvent, ShotMonitor,
    ShotPhase, SteamEvent, SteamMonitor, StopConfig, StopReason, StopTargets, WaterEvent,
    WaterMonitor,
};
use de1_protocol::{
    CalTarget, Calibration, MachineState, MmrReadReply, MmrRegister, ShotSample, ShotSettings,
    StateInfo, Version, WaterLevels, mmr, requested_state,
};
use de1_scale::{Scale, ScaleCapabilities, ScaleUuids, bookoo};

/// Scale sensor lag assumed when no scale is connected — a representative value
/// across the supported scales.
const DEFAULT_SCALE_LAG_SECONDS: f32 = 0.38;
/// Millimetres added to the DE1's reported tank level — the legacy
/// `water_level_mm_correction` (`machine.tcl`).
const WATER_LEVEL_MM_CORRECTION: f32 = 5.0;
/// How long the scale may go without reporting weight before it is considered
/// stale — the legacy app warns after roughly one second of silence.
const SCALE_STALE_TIMEOUT_MS: u64 = 1_000;
/// Hot-water volume (mL) requested when a scale is connected: the legacy app
/// asks for far more water than wanted so the scale's weight-based stop is what
/// cuts off the pour (`binary.tcl` `return_de1_packed_steam_hotwater_settings`).
const HOT_WATER_STOP_ON_SCALE_ML: f32 = 250.0;
/// The legacy `steam_eco_temperature` default (°C): the lower steam target the
/// machine drops to after a long idle period (`machine.tcl:33`).
const STEAM_ECO_TEMPERATURE_C: f32 = 136.0;

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
    steam_eco_temp_c: f32,
    last_state: Option<StateInfo>,
    /// Timestamp the in-progress shot began — stamps telemetry elapsed time.
    /// `None` when no shot is in progress.
    shot_started_ms: Option<u64>,
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
    last_scale_weight_ms: Option<u64>,
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
            steam_eco_temp_c: STEAM_ECO_TEMPERATURE_C,
            last_state: None,
            shot_started_ms: None,
            scale: None,
            flow: FlowEstimator::new(FlowAlgorithm::default()),
            auto_stop_targets: None,
            auto_stop: None,
            last_scale_weight_ms: None,
            scale_stale_reported: false,
            scale_config_queried: false,
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
        self.scale.as_ref().map(|scale| scale.label().to_owned())
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
    /// `max_time` is a shot-duration limit in seconds (legacy
    /// `espresso_max_time`); it stops the shot regardless of weight or volume.
    pub fn arm_auto_stop(
        &mut self,
        weight_g: Option<f32>,
        volume_ml: Option<f32>,
        max_time: Option<f32>,
    ) {
        self.auto_stop_targets = Some(StopTargets {
            weight_g,
            volume_ml,
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
    pub fn request_machine_state(&self, state: MachineState) -> CoreOutput {
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

    /// Build a [`Command`] that writes the DE1's steam / hot-water
    /// [`ShotSettings`] (`cuuid_0B`).
    ///
    /// The caller supplies the user's configured settings. When a scale is
    /// connected the hot-water volume is overridden to
    /// [`HOT_WATER_STOP_ON_SCALE_ML`]: the legacy app asks for far more water
    /// than wanted so the scale's weight-based stop is what cuts the pour off,
    /// since a weight stop is more accurate than the DE1's volume estimate
    /// (`binary.tcl` `return_de1_packed_steam_hotwater_settings`).
    ///
    /// Returns the effective settings written, so the shell can see the
    /// applied hot-water volume.
    ///
    /// The supplied settings are retained so a later eco-mode transition can
    /// rewrite the steam target temperature on the same baseline.
    pub fn set_steam_hotwater_settings(&mut self, settings: ShotSettings) -> CoreOutput {
        let mut settings = settings;
        if self.scale.is_some() {
            settings.hot_water_volume_ml = HOT_WATER_STOP_ON_SCALE_ML;
        }
        self.steam_hotwater_settings = Some(settings.clone());
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
        let mut out = CoreOutput::default();
        for event in self.steam.on_eco_enabled(enabled, now_ms) {
            self.map_steam_event(event, &mut out);
        }
        out
    }

    /// The steam / hot-water [`ShotSettings`] to write, with the steam target
    /// temperature set for the given eco state. Falls back to a representative
    /// default when no settings have been supplied yet.
    fn steam_settings_for_eco(&self, eco: bool) -> ShotSettings {
        let mut settings = self.steam_hotwater_settings.clone().unwrap_or_default();
        if eco {
            settings.steam_temp_c = self.steam_eco_temp_c;
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
    /// [`ScaleCapabilities::standby_minutes`] `[min, max]` bounds before the
    /// command is built. Capability-gated, not device-gated: the command is
    /// emitted only when the connected scale exposes a configurable
    /// auto-standby (its `standby_minutes` capability is `Some`). The result is
    /// empty otherwise. Modelled on [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_standby_minutes(&mut self, minutes: u8) -> CoreOutput {
        let mut out = CoreOutput::default();
        if let Some(scale) = &self.scale
            && let Some(range) = scale.capabilities().standby_minutes
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
    pub fn on_notification(&mut self, source: Source, data: &[u8], now_ms: u64) -> CoreOutput {
        let mut out = CoreOutput::default();
        match source {
            Source::De1State => self.handle_state(data, now_ms, &mut out),
            Source::De1ShotSample => self.handle_sample(data, now_ms, &mut out),
            Source::ScaleWeight => self.handle_scale_weight(data, now_ms, &mut out),
            Source::ScaleCommand => self.handle_scale_command(data, &mut out),
            Source::De1WaterLevels => self.handle_water_levels(data, &mut out),
            Source::De1Version => self.handle_version(data, &mut out),
            Source::De1MmrRead => Self::handle_mmr_read(data, &mut out),
            Source::De1Calibration => Self::handle_calibration(data, &mut out),
        }
        out
    }

    /// Feed a periodic clock tick. Drives the lost-scale watchdog (if a scale
    /// is connected but has not reported weight for [`SCALE_STALE_TIMEOUT_MS`],
    /// emit [`Event::ScaleStale`] once per stale episode) and the steam
    /// eco-mode transition.
    pub fn on_tick(&mut self, now_ms: u64) -> CoreOutput {
        let mut out = CoreOutput::default();
        if self.scale.is_some()
            && !self.scale_stale_reported
            && let Some(last) = self.last_scale_weight_ms
            && now_ms.saturating_sub(last) >= SCALE_STALE_TIMEOUT_MS
        {
            self.scale_stale_reported = true;
            out.events.push(Event::ScaleStale);
        }
        for event in self.steam.on_tick(now_ms) {
            self.map_steam_event(event, &mut out);
        }
        out
    }

    /// Discard all session state — e.g. on disconnect. The connected scale and
    /// armed auto-stop targets are cleared too.
    pub fn reset(&mut self) {
        *self = CremaCore::new();
    }

    /// Decode and process a `StateInfo` notification.
    fn handle_state(&mut self, data: &[u8], now_ms: u64, out: &mut CoreOutput) {
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
            self.last_state = Some(info);
            out.events.push(Event::MachineStateChanged {
                state: info.state,
                substate: info.substate,
            });
        }
        for event in self.monitor.on_state_info(info, now_ms) {
            self.map_shot_event(event, now_ms, out);
        }
        for event in self.water.on_state_info(info, now_ms) {
            Self::map_water_event(event, out);
        }
        for event in self.steam.on_state_info(info, now_ms) {
            self.map_steam_event(event, out);
        }
    }

    /// Decode and process a `ShotSample` telemetry notification.
    fn handle_sample(&mut self, data: &[u8], now_ms: u64, out: &mut CoreOutput) {
        let sample = match ShotSample::parse(data) {
            Ok(sample) => sample,
            Err(e) => {
                out.events.push(Event::DecodeError {
                    message: e.to_string(),
                });
                return;
            }
        };
        for event in self.monitor.on_sample(&sample, now_ms) {
            self.map_shot_event(event, now_ms, out);
        }
        // Record steam telemetry while a steam session is in progress.
        self.steam.on_sample(&sample, now_ms);
        let reason = self
            .auto_stop
            .as_mut()
            .and_then(|stop| stop.on_sample(&sample, now_ms));
        Self::push_stop(reason, out);
        // Cast to u32: a session's elapsed time never approaches u32::MAX ms
        // (~49 days), so the narrowing cannot truncate in practice.
        let elapsed_ms = self
            .shot_started_ms
            .map_or(0, |start| now_ms.saturating_sub(start)) as u32;
        out.events.push(Event::Telemetry {
            elapsed_ms,
            group_pressure: sample.group_pressure,
            group_flow: sample.group_flow,
            head_temp: sample.head_temp,
            mix_temp: sample.mix_temp,
            steam_temp: sample.steam_temp,
        });
    }

    /// Decode and process a scale weight notification.
    fn handle_scale_weight(&mut self, data: &[u8], now_ms: u64, out: &mut CoreOutput) {
        let Some(scale) = &mut self.scale else {
            return;
        };
        let Some(reading) = scale.parse_reading(data) else {
            return;
        };
        // A fresh reading re-arms the lost-scale watchdog.
        self.last_scale_weight_ms = Some(now_ms);
        self.scale_stale_reported = false;
        // The first weight notification stands in for a scale-connected hook:
        // fire the one-shot serial / settings queries so the anti-mistouch
        // state and active mode are fetched as soon as the scale is reporting.
        self.push_connect_queries_once(out);
        let estimate = self.flow.update(reading.weight_g, now_ms);
        out.events.push(Event::ScaleReading {
            weight_g: estimate.weight_g,
            flow_g_per_s: estimate.flow_g_per_s,
            device_flow_g_per_s: reading.flow_g_per_s,
            device_timer_ms: reading.timer_ms,
            device_volume: reading.volume,
            device_standby_minutes: reading.standby_minutes,
            device_battery_percent: reading.battery_percent,
            device_flow_smoothing: reading.flow_smoothing,
            device_auto_stop: reading.auto_stop,
        });
        let reason = self
            .auto_stop
            .as_mut()
            .and_then(|stop| stop.on_weight(reading.weight_g, now_ms));
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
                level_mm: levels.current_mm + WATER_LEVEL_MM_CORRECTION,
                refill_threshold_mm: levels.refill_threshold_mm,
            }),
            Err(e) => out.events.push(Event::DecodeError {
                message: e.to_string(),
            }),
        }
    }

    /// Decode and process a `Version` notification — the DE1's BLE-interface
    /// and CPU-board firmware versions.
    fn handle_version(&self, data: &[u8], out: &mut CoreOutput) {
        match Version::decode(data) {
            Ok(version) => out.events.push(Event::Firmware {
                fw_release: version.fw.release,
                fw_commits: version.fw.commits,
                fw_api_version: version.fw.api_version,
                firmware_string: version.firmware_string(),
            }),
            Err(e) => out.events.push(Event::DecodeError {
                message: e.to_string(),
            }),
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
    fn handle_mmr_read(data: &[u8], out: &mut CoreOutput) {
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

    /// Translate a domain [`ShotEvent`] into FFI [`Event`]s, maintaining the
    /// shot-start timestamp and the auto-stop lifecycle.
    fn map_shot_event(&mut self, event: ShotEvent, now_ms: u64, out: &mut CoreOutput) {
        match event {
            ShotEvent::Started => {
                self.shot_started_ms = Some(now_ms);
                self.flow.reset();
                // Auto-tare the connected scale so the cup starts from zero,
                // mirroring the legacy app's tare-at-shot-start behaviour.
                self.push_tare_command(out);
                out.events.push(Event::ShotStarted);
            }
            ShotEvent::PhaseChanged(phase) => {
                self.arm_auto_stop_if_flowing(phase, now_ms);
                out.events.push(Event::ShotPhaseChanged { phase });
            }
            ShotEvent::FrameChanged(frame) => out.events.push(Event::ShotFrameChanged { frame }),
            ShotEvent::Completed(record) => {
                self.shot_started_ms = None;
                self.auto_stop = None;
                out.events.push(Event::ShotCompleted {
                    // A session never exceeds u32::MAX ms, so this cannot truncate.
                    duration_ms: record.duration_ms as u32,
                    sample_count: record.samples.len() as u32,
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
                    // A session never exceeds u32::MAX ms, so this cannot truncate.
                    duration_ms: record.duration_ms as u32,
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
                    // A session never exceeds u32::MAX ms, so this cannot truncate.
                    duration_ms: record.duration_ms as u32,
                    sample_count: record.samples.len() as u32,
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
    fn arm_auto_stop_if_flowing(&mut self, phase: ShotPhase, now_ms: u64) {
        if self.auto_stop.is_some() {
            return;
        }
        if !matches!(phase, ShotPhase::Preinfusion | ShotPhase::Pouring) {
            return;
        }
        if let Some(targets) = self.auto_stop_targets {
            self.auto_stop = Some(AutoStop::new(targets, self.stop_config(), now_ms));
        }
    }

    /// The [`StopConfig`] for a new [`AutoStop`] — the legacy four-term SAW
    /// lead, using the connected scale's sensor lag.
    fn stop_config(&self) -> StopConfig {
        let scale_lag = self
            .scale
            .as_ref()
            .map_or(DEFAULT_SCALE_LAG_SECONDS, Scale::sensor_lag_seconds);
        StopConfig::with_legacy_lead(
            STOP_WEIGHT_BEFORE_SECONDS,
            scale_lag,
            FlowAlgorithm::TheilSen,
        )
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

    #[test]
    fn a_water_level_notification_applies_the_5mm_correction() {
        let mut core = CremaCore::new();
        // WaterLevels packet: current 100 mm, refill threshold 70 mm.
        let out = core.on_notification(Source::De1WaterLevels, &[0x64, 0x00, 0x46, 0x00], 0);
        assert!(out.events.contains(&Event::WaterLevel {
            level_mm: 105.0,
            refill_threshold_mm: 70.0,
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
                .any(|e| matches!(e, Event::Firmware { fw_commits: 142, .. }))
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
    fn set_scale_standby_minutes_emits_a_write_for_a_capable_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_standby_minutes(15);
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteScale { .. })
        ));
    }

    #[test]
    fn set_scale_standby_minutes_clamps_to_the_capability_range() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        // An out-of-range request clamps to the Bookoo's 5..=30 minute range.
        let Some(Command::WriteScale { data: clamped }) =
            core.set_scale_standby_minutes(99).commands.into_iter().next()
        else {
            panic!("expected a WriteScale command");
        };
        let Some(Command::WriteScale { data: at_max }) =
            core.set_scale_standby_minutes(30).commands.into_iter().next()
        else {
            panic!("expected a WriteScale command");
        };
        assert_eq!(clamped, at_max);
    }

    #[test]
    fn set_scale_standby_minutes_emits_nothing_for_a_weight_only_scale() {
        let mut core = CremaCore::new();
        core.connect_scale("Decent Scale ABC");
        assert!(core.set_scale_standby_minutes(15).commands.is_empty());
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
    fn set_scale_standby_minutes_appends_a_settings_query() {
        let mut core = CremaCore::new();
        core.connect_scale("BOOKOO_SC");
        let out = core.set_scale_standby_minutes(15);
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
        assert!(core.set_scale_standby_minutes(15).commands.is_empty());
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
            duration_ms: 4_000,
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
        // Byte 4 is the hot-water volume (u8p0): the user's 50 mL is unchanged.
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
        // With a scale connected the volume is bumped to 250 mL so the scale's
        // weight stop is what ends the pour.
        assert_eq!(data[4], 250);
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
            duration_ms: 5_000,
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
            duration_ms: 3_000,
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
}

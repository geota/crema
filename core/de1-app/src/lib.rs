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
    AutoStop, FlowAlgorithm, FlowEstimator, ShotEvent, ShotMonitor, ShotPhase, StopConfig,
    StopReason, StopTargets, WaterEvent, WaterMonitor,
};
use de1_protocol::{
    MachineState, ShotSample, ShotSettings, StateInfo, WaterLevels, requested_state,
};
use de1_scale::Scale;

/// The legacy `stop_weight_before_seconds` setting default — the user-tunable
/// part of the SAW look-ahead.
const STOP_WEIGHT_BEFORE_SECONDS: f32 = 0.15;
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
            last_state: None,
            shot_started_ms: None,
            scale: None,
            flow: FlowEstimator::new(FlowAlgorithm::default()),
            auto_stop_targets: None,
            auto_stop: None,
            last_scale_weight_ms: None,
            scale_stale_reported: false,
        }
    }

    /// Identify and connect a scale from its BLE advertised name. Returns
    /// whether the name matched a supported scale.
    pub fn connect_scale(&mut self, advertised_name: &str) -> bool {
        self.scale = Scale::identify(advertised_name);
        self.scale.is_some()
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
    pub fn set_steam_hotwater_settings(&self, settings: ShotSettings) -> CoreOutput {
        let mut settings = settings;
        if self.scale.is_some() {
            settings.hot_water_volume_ml = HOT_WATER_STOP_ON_SCALE_ML;
        }
        let mut out = CoreOutput::default();
        out.commands.push(Command::WriteCharacteristic {
            target: WriteTarget::De1ShotSettings,
            data: settings.encode().to_vec(),
        });
        out
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

    /// Feed a raw GATT notification: `data` is the characteristic's bytes and
    /// `now_ms` a monotonic millisecond timestamp from the shell.
    pub fn on_notification(&mut self, source: Source, data: &[u8], now_ms: u64) -> CoreOutput {
        let mut out = CoreOutput::default();
        match source {
            Source::De1State => self.handle_state(data, now_ms, &mut out),
            Source::De1ShotSample => self.handle_sample(data, now_ms, &mut out),
            Source::ScaleWeight => self.handle_scale_weight(data, now_ms, &mut out),
            Source::De1WaterLevels => self.handle_water_levels(data, &mut out),
        }
        out
    }

    /// Feed a periodic clock tick. Drives the lost-scale watchdog: if a scale
    /// is connected but has not reported weight for [`SCALE_STALE_TIMEOUT_MS`],
    /// emit [`Event::ScaleStale`] once per stale episode.
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
        let reason = self
            .auto_stop
            .as_mut()
            .and_then(|stop| stop.on_sample(&sample, now_ms));
        Self::push_stop(reason, out);
        let elapsed_ms = self
            .shot_started_ms
            .map_or(0, |start| now_ms.saturating_sub(start)) as u32;
        out.events.push(Event::Telemetry {
            elapsed_ms,
            group_pressure: sample.group_pressure,
            group_flow: sample.group_flow,
            head_temp: sample.head_temp,
        });
    }

    /// Decode and process a scale weight notification.
    fn handle_scale_weight(&mut self, data: &[u8], now_ms: u64, out: &mut CoreOutput) {
        let Some(scale) = &mut self.scale else {
            return;
        };
        let Some(weight_g) = scale.parse_weight(data) else {
            return;
        };
        // A fresh reading re-arms the lost-scale watchdog.
        self.last_scale_weight_ms = Some(now_ms);
        self.scale_stale_reported = false;
        let estimate = self.flow.update(weight_g, now_ms);
        out.events.push(Event::ScaleReading {
            weight_g: estimate.weight_g,
            flow_g_per_s: estimate.flow_g_per_s,
        });
        let reason = self
            .auto_stop
            .as_mut()
            .and_then(|stop| stop.on_weight(weight_g, now_ms));
        Self::push_stop(reason, out);
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
                    duration_ms: record.duration_ms as u32,
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
        assert!(core.connect_scale("BOOKOO_SC 1234"));
        assert!(!core.connect_scale("Some Random Device"));
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
        assert!(matches!(
            out.commands.first(),
            Some(Command::WriteCharacteristic {
                target: WriteTarget::De1RequestedState,
                ..
            })
        ));
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
        let core = CremaCore::new();
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

//! # de1-wasm
//!
//! The wasm-bindgen bridge: exposes [`de1_app::CremaCore`] to the web shell.
//! Like `de1-ffi`, it is a thin wrapper — it serializes the rich
//! [`CoreOutput`](de1_app::CoreOutput) to JSON (the "Option S" encoding from
//! `docs/08-ffi-and-web-scope.md`) and marshals simple typed values for inputs.
//!
//! No mutex: wasm is single-threaded, so the wrapped core uses ordinary
//! `&mut self` methods. `unsafe` is unavoidable — wasm-bindgen generates the
//! binding glue — so this crate opts out of the workspace's
//! `unsafe_code = "forbid"` lint (see `Cargo.toml`).

use de1_app::{CoreOutput, CremaCore, Source};
use de1_protocol::{CalTarget, MachineState, MmrRegister, ShotSettings};
use wasm_bindgen::prelude::*;

/// Which BLE characteristic an incoming notification came from — mirrors
/// [`de1_app::Source`] across the wasm boundary.
#[wasm_bindgen]
pub enum NotificationSource {
    /// The DE1 machine state / substate characteristic.
    De1State,
    /// The DE1 shot-telemetry characteristic.
    De1ShotSample,
    /// The connected scale's weight notification.
    ScaleWeight,
    /// The connected scale's command-characteristic notification (the Bookoo's
    /// `ff12` serial / settings responses).
    ScaleCommand,
    /// The DE1 water-tank level characteristic.
    De1WaterLevels,
    /// The DE1 version characteristic — BLE + firmware versions.
    De1Version,
    /// The DE1 `ReadFromMMR` characteristic — memory-mapped register replies.
    De1MmrRead,
    /// The DE1 `Calibration` characteristic — sensor-calibration replies.
    De1Calibration,
}

impl From<NotificationSource> for Source {
    fn from(source: NotificationSource) -> Source {
        match source {
            NotificationSource::De1State => Source::De1State,
            NotificationSource::De1ShotSample => Source::De1ShotSample,
            NotificationSource::ScaleWeight => Source::ScaleWeight,
            NotificationSource::ScaleCommand => Source::ScaleCommand,
            NotificationSource::De1WaterLevels => Source::De1WaterLevels,
            NotificationSource::De1Version => Source::De1Version,
            NotificationSource::De1MmrRead => Source::De1MmrRead,
            NotificationSource::De1Calibration => Source::De1Calibration,
        }
    }
}

/// A DE1 memory-mapped register the shell can ask the core to read — mirrors
/// [`de1_protocol::MmrRegister`] across the wasm boundary.
#[wasm_bindgen]
pub enum MmrReg {
    /// Firmware build number.
    FirmwareVersion,
    /// Group Head Controller info bitmask.
    GhcInfo,
    /// Tank desired water-temperature threshold, °C.
    TankTempThreshold,
    /// Fan-on temperature threshold, °C.
    FanThreshold,
    /// Machine serial number.
    SerialNumber,
    /// Steam flow rate.
    SteamFlow,
    /// Refill-kit presence.
    RefillKit,
    /// Flush flow rate.
    FlushFlowRate,
    /// Hot-water flow rate.
    HotWaterFlowRate,
    /// Hot-water dispense phase-1 flow rate.
    Phase1FlowRate,
    /// Hot-water dispense phase-2 flow rate.
    Phase2FlowRate,
    /// Hot-water idle temperature, °C.
    HotWaterIdleTemp,
    /// Group head control mode.
    GhcMode,
    /// Seconds of high-flow steam at the start of a steam cycle.
    SteamHighFlowStart,
    /// Mains heater voltage.
    HeaterVoltage,
    /// Espresso warmup timeout.
    EspressoWarmupTimeout,
    /// Calibration flow multiplier.
    CalibrationFlowMultiplier,
    /// Flush timeout.
    FlushTimeout,
    /// USB charger on.
    UsbChargerOn,
    /// Feature-flag bitmask.
    FeatureFlags,
    /// Cup-warmer temperature (Bengle models only).
    CupWarmerTemp,
}

impl From<MmrReg> for MmrRegister {
    fn from(reg: MmrReg) -> MmrRegister {
        match reg {
            MmrReg::FirmwareVersion => MmrRegister::FirmwareVersion,
            MmrReg::GhcInfo => MmrRegister::GhcInfo,
            MmrReg::TankTempThreshold => MmrRegister::TankTempThreshold,
            MmrReg::FanThreshold => MmrRegister::FanThreshold,
            MmrReg::SerialNumber => MmrRegister::SerialNumber,
            MmrReg::SteamFlow => MmrRegister::SteamFlow,
            MmrReg::RefillKit => MmrRegister::RefillKit,
            MmrReg::FlushFlowRate => MmrRegister::FlushFlowRate,
            MmrReg::HotWaterFlowRate => MmrRegister::HotWaterFlowRate,
            MmrReg::Phase1FlowRate => MmrRegister::Phase1FlowRate,
            MmrReg::Phase2FlowRate => MmrRegister::Phase2FlowRate,
            MmrReg::HotWaterIdleTemp => MmrRegister::HotWaterIdleTemp,
            MmrReg::GhcMode => MmrRegister::GhcMode,
            MmrReg::SteamHighFlowStart => MmrRegister::SteamHighFlowStart,
            MmrReg::HeaterVoltage => MmrRegister::HeaterVoltage,
            MmrReg::EspressoWarmupTimeout => MmrRegister::EspressoWarmupTimeout,
            MmrReg::CalibrationFlowMultiplier => MmrRegister::CalibrationFlowMultiplier,
            MmrReg::FlushTimeout => MmrRegister::FlushTimeout,
            MmrReg::UsbChargerOn => MmrRegister::UsbChargerOn,
            MmrReg::FeatureFlags => MmrRegister::FeatureFlags,
            MmrReg::CupWarmerTemp => MmrRegister::CupWarmerTemp,
        }
    }
}

/// A DE1 sensor the shell can ask the core to read calibration for — mirrors
/// [`de1_protocol::CalTarget`] across the wasm boundary.
#[wasm_bindgen]
pub enum CalSensor {
    /// The flow-rate sensor.
    Flow,
    /// The pressure sensor.
    Pressure,
    /// The temperature sensor.
    Temperature,
}

impl From<CalSensor> for CalTarget {
    fn from(sensor: CalSensor) -> CalTarget {
        match sensor {
            CalSensor::Flow => CalTarget::Flow,
            CalSensor::Pressure => CalTarget::Pressure,
            CalSensor::Temperature => CalTarget::Temperature,
        }
    }
}

/// A machine state the shell can ask the DE1 to enter.
#[wasm_bindgen]
pub enum MachineRequest {
    /// Go to sleep.
    Sleep,
    /// Return to idle — also stops a running shot and wakes from sleep.
    Idle,
    /// Start an espresso shot.
    Espresso,
    /// Start steam.
    Steam,
    /// Start hot water.
    HotWater,
    /// Start a group-head flush / rinse.
    Flush,
    /// Start a descale cycle.
    Descale,
    /// Start a cleaning cycle.
    Clean,
    /// Advance to the next espresso frame.
    SkipToNext,
}

impl From<MachineRequest> for MachineState {
    fn from(request: MachineRequest) -> MachineState {
        match request {
            MachineRequest::Sleep => MachineState::Sleep,
            MachineRequest::Idle => MachineState::Idle,
            MachineRequest::Espresso => MachineState::Espresso,
            MachineRequest::Steam => MachineState::Steam,
            MachineRequest::HotWater => MachineState::HotWater,
            MachineRequest::Flush => MachineState::HotWaterRinse,
            MachineRequest::Descale => MachineState::Descale,
            MachineRequest::Clean => MachineState::Clean,
            MachineRequest::SkipToNext => MachineState::SkipToNext,
        }
    }
}

/// The Crema core, exposed to the web shell.
///
/// Methods that produce a [`CoreOutput`] return it as a JSON string; the shell
/// parses it into types generated by `typeshare`. Timestamps are `f64`
/// milliseconds — the natural type for the browser's `performance.now()`.
#[wasm_bindgen]
pub struct CremaBridge {
    core: CremaCore,
}

impl Default for CremaBridge {
    fn default() -> CremaBridge {
        CremaBridge::new()
    }
}

#[wasm_bindgen]
impl CremaBridge {
    /// Create a core in the idle state.
    #[wasm_bindgen(constructor)]
    pub fn new() -> CremaBridge {
        CremaBridge {
            core: CremaCore::new(),
        }
    }

    /// Feed a raw GATT notification. Returns a JSON-encoded [`CoreOutput`].
    pub fn on_notification(
        &mut self,
        source: NotificationSource,
        data: Vec<u8>,
        now_ms: f64,
    ) -> String {
        json(
            self.core
                .on_notification(source.into(), &data, now_ms as u64),
        )
    }

    /// Feed a periodic clock tick. Returns a JSON-encoded [`CoreOutput`].
    pub fn on_tick(&mut self, now_ms: f64) -> String {
        json(self.core.on_tick(now_ms as u64))
    }

    /// Discard all session state — e.g. on disconnect.
    pub fn reset(&mut self) {
        self.core.reset();
    }

    /// Identify and connect a scale from its BLE advertised name. Returns the
    /// connected scale's display label, or `undefined` if the name matched no
    /// supported scale.
    pub fn connect_scale(&mut self, advertised_name: String) -> Option<String> {
        self.core.connect_scale(&advertised_name)
    }

    /// Arm automatic shot-stop. `weight` is grams (SAW), `volume` is
    /// millilitres (SAV), and `max_time` is shot-duration seconds.
    pub fn arm_auto_stop(
        &mut self,
        weight: Option<f32>,
        volume: Option<f32>,
        max_time: Option<f32>,
    ) {
        self.core.arm_auto_stop(weight, volume, max_time);
    }

    /// Disarm automatic shot-stop.
    pub fn disarm_auto_stop(&mut self) {
        self.core.disarm_auto_stop();
    }

    /// Build a [`CoreOutput`] (JSON) whose command asks the DE1 to enter
    /// `state`.
    pub fn request_machine_state(&self, state: MachineRequest) -> String {
        json(self.core.request_machine_state(state.into()))
    }

    /// Build a [`CoreOutput`] (JSON) whose command reads one DE1 memory-mapped
    /// register. The DE1 answers with a notification on the `De1MmrRead`
    /// characteristic, which decodes to an `MmrValue` event.
    pub fn read_mmr(&self, register: MmrReg) -> String {
        json(self.core.read_mmr(register.into()))
    }

    /// Build a [`CoreOutput`] (JSON) whose command reads `sensor`'s current
    /// (in-use) calibration. The DE1 answers on the `De1Calibration`
    /// characteristic, which decodes to a `Calibration` event.
    pub fn read_calibration(&self, sensor: CalSensor) -> String {
        json(self.core.read_calibration(sensor.into()))
    }

    /// Build a [`CoreOutput`] (JSON) whose command reads `sensor`'s factory
    /// calibration — the calibration the machine shipped with.
    pub fn read_factory_calibration(&self, sensor: CalSensor) -> String {
        json(self.core.read_factory_calibration(sensor.into()))
    }

    /// Build a [`CoreOutput`] (JSON) whose command tares the connected scale.
    pub fn tare_scale(&mut self) -> String {
        json(self.core.tare_scale())
    }

    /// What the currently-connected scale can do beyond reporting a bare
    /// weight, as a JSON-encoded `ScaleCapabilities` object — or `undefined`
    /// when no scale is connected.
    ///
    /// The shell parses the JSON into the `typeshare`-generated
    /// `ScaleCapabilities` type and drives capability-gated configuration UI
    /// off it: Crema is capability-driven, never device-driven. Returned as a
    /// JSON string, consistent with the rest of the bridge's JSON surface.
    pub fn scale_capabilities(&self) -> Option<String> {
        self.core.scale_capabilities().map(|caps| {
            serde_json::to_string(&caps)
                .expect("ScaleCapabilities is plain data and always serializes")
        })
    }

    /// The connected scale's BLE service and characteristic UUIDs, as a
    /// JSON-encoded `ScaleUuids` object — or `undefined` when no scale is
    /// connected.
    ///
    /// The shell parses the JSON into the `typeshare`-generated `ScaleUuids`
    /// type to know which Web Bluetooth characteristics to subscribe to for
    /// weight notifications and command writes. Returned as a JSON string,
    /// consistent with the rest of the bridge's JSON surface.
    pub fn scale_uuids(&self) -> Option<String> {
        self.core.scale_uuids().map(|uuids| {
            serde_json::to_string(&uuids)
                .expect("ScaleUuids is plain data and always serializes")
        })
    }

    /// Build a [`CoreOutput`] (JSON) whose command queries the connected
    /// scale's settings (the scale answers with a `03 0e …` notification).
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// exposes a configurable setting. Empty otherwise. Wired exactly like
    /// [`tare_scale`](Self::tare_scale).
    pub fn query_scale_settings(&self) -> String {
        json(self.core.query_scale_settings())
    }

    /// Build a [`CoreOutput`] (JSON) whose command sets the connected scale's
    /// beep volume to `level`.
    ///
    /// `level` is clamped to the connected scale's `ScaleCapabilities::volume`
    /// `[min, max]` bounds. Capability-gated: the command is present only when
    /// the connected scale exposes a settable volume. Empty otherwise. Wired
    /// exactly like [`tare_scale`](Self::tare_scale).
    pub fn set_scale_volume(&mut self, level: u8) -> String {
        json(self.core.set_scale_volume(level))
    }

    /// Build a [`CoreOutput`] (JSON) whose command sets the connected scale's
    /// auto-standby timeout to `minutes`.
    ///
    /// `minutes` is clamped to the connected scale's
    /// `ScaleCapabilities::standby_minutes` `[min, max]` bounds. Capability-
    /// gated: the command is present only when the connected scale exposes a
    /// configurable auto-standby. Empty otherwise. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_standby_minutes(&mut self, minutes: u8) -> String {
        json(self.core.set_scale_standby_minutes(minutes))
    }

    /// Build a [`CoreOutput`] (JSON) whose command enables or disables the
    /// connected scale's flow smoothing.
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// supports flow smoothing. Empty otherwise. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_flow_smoothing(&mut self, enabled: bool) -> String {
        json(self.core.set_scale_flow_smoothing(enabled))
    }

    /// Build a [`CoreOutput`] (JSON) whose command enables or disables the
    /// connected scale's anti-mistouch.
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// supports anti-mistouch. Empty otherwise. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_anti_mistouch(&mut self, enabled: bool) -> String {
        json(self.core.set_scale_anti_mistouch(enabled))
    }

    /// Build a [`CoreOutput`] (JSON) whose commands switch the connected scale
    /// to display mode `mode_id`.
    ///
    /// Capability-gated: the commands are present only when the connected
    /// scale exposes switchable modes and `mode_id` is one of the listed
    /// modes. Switching a mode is **three** `WriteScale` commands, emitted in
    /// order — the shell must perform them in that order. Empty otherwise.
    /// Wired exactly like [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_mode(&mut self, mode_id: u8) -> String {
        json(self.core.set_scale_mode(mode_id))
    }

    /// Build a [`CoreOutput`] (JSON) whose command selects the connected
    /// scale's auto-stop mode (`0` = flow-stop, `1` = cup-removal).
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// supports an auto-stop-mode setting and `mode_id` is in range. An
    /// out-of-range `mode_id` yields an empty `CoreOutput`. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_auto_stop(&mut self, mode_id: u8) -> String {
        json(self.core.set_scale_auto_stop(mode_id))
    }

    /// Write the DE1's steam / hot-water settings. The fields are passed as
    /// individual scalars — [`de1_protocol::ShotSettings`] is not (and should
    /// not be) wasm-bindgen-annotated, so the bridge builds it here. Returns a
    /// JSON-encoded [`CoreOutput`] whose command applies the settings (with
    /// the legacy hot-water volume override when a scale is connected).
    #[allow(clippy::too_many_arguments)]
    pub fn set_steam_hotwater_settings(
        &mut self,
        steam_flags: u8,
        steam_temp_c: f32,
        steam_timeout_s: f32,
        hot_water_temp_c: f32,
        hot_water_volume_ml: f32,
        hot_water_timeout_s: f32,
        espresso_volume_ml: f32,
        group_temp_c: f32,
    ) -> String {
        let settings = ShotSettings {
            steam_flags,
            steam_temp_c,
            steam_timeout_s,
            hot_water_temp_c,
            hot_water_volume_ml,
            hot_water_timeout_s,
            espresso_volume_ml,
            group_temp_c,
        };
        json(self.core.set_steam_hotwater_settings(settings))
    }

    /// Enable or disable steam eco mode (the legacy `eco_steam` setting).
    /// Returns a JSON-encoded [`CoreOutput`].
    pub fn enable_steam_eco_mode(&mut self, enabled: bool, now_ms: f64) -> String {
        json(self.core.enable_steam_eco_mode(enabled, now_ms as u64))
    }

    /// The standard DE1 profiles Crema ships built in, as a JSON array string.
    ///
    /// Each element is a `Profile`; the shell parses the array into its own
    /// profile type, consistent with the rest of the JSON-string surface.
    pub fn builtin_profiles_json(&self) -> String {
        self.core.builtin_profiles_json()
    }
}

/// Serialize a [`CoreOutput`] to JSON for the shell. `CoreOutput` is flat plain
/// data, so serialization is infallible in practice — a failure would be a bug.
fn json(output: CoreOutput) -> String {
    output
        .to_json()
        .expect("CoreOutput is plain data and always serializes")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn on_notification_returns_core_output_json() {
        let mut bridge = CremaBridge::new();
        let json = bridge.on_notification(NotificationSource::De1State, vec![4, 5], 1_000.0);
        assert!(json.contains("\"events\""));
        assert!(json.contains("\"ShotStarted\""));
    }

    #[test]
    fn request_machine_state_produces_a_write_command() {
        let bridge = CremaBridge::new();
        let json = bridge.request_machine_state(MachineRequest::Idle);
        assert!(json.contains("\"commands\""));
        assert!(json.contains("WriteCharacteristic"));
    }

    #[test]
    fn read_mmr_produces_a_write_command() {
        let bridge = CremaBridge::new();
        let json = bridge.read_mmr(MmrReg::SerialNumber);
        assert!(json.contains("\"commands\""));
        assert!(json.contains("WriteCharacteristic"));
        assert!(json.contains("De1MmrRequest"));
    }

    #[test]
    fn read_calibration_produces_a_write_command() {
        let bridge = CremaBridge::new();
        for json in [
            bridge.read_calibration(CalSensor::Pressure),
            bridge.read_factory_calibration(CalSensor::Flow),
        ] {
            assert!(json.contains("WriteCharacteristic"));
            assert!(json.contains("De1Calibration"));
        }
    }

    #[test]
    fn connect_scale_identifies_a_known_scale() {
        let mut bridge = CremaBridge::new();
        assert_eq!(
            bridge.connect_scale("BOOKOO_SC".to_owned()),
            Some("Bookoo".to_owned())
        );
        assert_eq!(bridge.connect_scale("Not A Scale".to_owned()), None);
    }

    /// Apply representative steam / hot-water settings to `bridge`.
    fn set_steam_settings(bridge: &mut CremaBridge) -> String {
        bridge.set_steam_hotwater_settings(0, 150.0, 120.0, 85.0, 50.0, 30.0, 36.0, 92.0)
    }

    #[test]
    fn set_steam_hotwater_settings_produces_a_write_command() {
        let mut bridge = CremaBridge::new();
        let json = set_steam_settings(&mut bridge);
        assert!(json.contains("\"commands\""));
        assert!(json.contains("WriteCharacteristic"));
        assert!(json.contains("De1ShotSettings"));
    }

    #[test]
    fn enable_steam_eco_mode_returns_core_output_json() {
        let mut bridge = CremaBridge::new();
        set_steam_settings(&mut bridge);
        bridge.enable_steam_eco_mode(true, 0.0);
        // After the idle delay a tick engages eco mode and rewrites the target.
        let json = bridge.on_tick(600_000.0);
        assert!(json.contains("SteamEcoModeChanged"));
    }

    #[test]
    fn builtin_profiles_json_returns_a_profile_array() {
        let bridge = CremaBridge::new();
        let json = bridge.builtin_profiles_json();
        assert!(json.starts_with('['));
        assert!(json.contains("\"steps\""));
    }

    #[test]
    fn scale_capabilities_is_none_without_a_connected_scale() {
        let bridge = CremaBridge::new();
        assert!(bridge.scale_capabilities().is_none());
    }

    #[test]
    fn scale_capabilities_reports_a_bookoo_as_first_class() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.scale_capabilities().expect("a connected scale");
        let caps: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(caps["volume"]["min"], 0);
        assert_eq!(caps["volume"]["max"], 3);
        assert_eq!(caps["reports_flow"], true);
        assert_eq!(caps["reports_timer"], true);
    }

    #[test]
    fn scale_uuids_is_none_without_a_connected_scale() {
        let bridge = CremaBridge::new();
        assert!(bridge.scale_uuids().is_none());
    }

    #[test]
    fn scale_uuids_reports_the_connected_scales_gatt_uuids() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.scale_uuids().expect("a connected scale");
        let uuids: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(uuids["service"].is_string());
        assert!(uuids["weight_notify"].is_string());
        assert!(uuids["command_write"].is_string());
    }

    #[test]
    fn set_scale_volume_produces_a_write_scale_command_for_a_capable_scale() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_volume(3);
        assert!(json.contains("\"commands\""));
        assert!(json.contains("WriteScale"));
    }

    #[test]
    fn set_scale_mode_produces_three_write_scale_commands_for_a_capable_scale() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_mode(1);
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        // Three mode writes plus the appended 0x0f settings query.
        assert_eq!(parsed["commands"].as_array().unwrap().len(), 4);
    }

    #[test]
    fn query_scale_settings_produces_a_write_scale_command_for_a_capable_scale() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.query_scale_settings();
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        let commands = parsed["commands"].as_array().unwrap();
        assert_eq!(commands.len(), 1);
        assert_eq!(commands[0]["type"], "WriteScale");
    }

    #[test]
    fn scale_config_methods_produce_no_command_for_a_weight_only_scale() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("Decent Scale ABC".to_owned());
        for json in [
            bridge.set_scale_volume(3),
            bridge.set_scale_standby_minutes(15),
            bridge.set_scale_flow_smoothing(true),
            bridge.set_scale_anti_mistouch(true),
            bridge.set_scale_mode(0),
            bridge.set_scale_auto_stop(0),
            bridge.query_scale_settings(),
        ] {
            let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
            assert!(parsed["commands"].as_array().unwrap().is_empty());
        }
    }

    #[test]
    fn mmr_reg_mirror_covers_every_mmr_register() {
        // `MmrReg` is a hand-maintained mirror of `MmrRegister`. This builds
        // one `MmrReg` per `MmrRegister` variant — the match is exhaustive, so
        // adding a core register without a mirror variant fails to compile —
        // then asserts the mirror maps back to the same register, name and
        // count, catching any drift at `cargo test`.
        let mirror: Vec<(MmrReg, MmrRegister)> = MmrRegister::ALL
            .into_iter()
            .map(|core_reg| {
                let mirror = match core_reg {
                    MmrRegister::FirmwareVersion => MmrReg::FirmwareVersion,
                    MmrRegister::GhcInfo => MmrReg::GhcInfo,
                    MmrRegister::TankTempThreshold => MmrReg::TankTempThreshold,
                    MmrRegister::FanThreshold => MmrReg::FanThreshold,
                    MmrRegister::SerialNumber => MmrReg::SerialNumber,
                    MmrRegister::SteamFlow => MmrReg::SteamFlow,
                    MmrRegister::RefillKit => MmrReg::RefillKit,
                    MmrRegister::FlushFlowRate => MmrReg::FlushFlowRate,
                    MmrRegister::HotWaterFlowRate => MmrReg::HotWaterFlowRate,
                    MmrRegister::Phase1FlowRate => MmrReg::Phase1FlowRate,
                    MmrRegister::Phase2FlowRate => MmrReg::Phase2FlowRate,
                    MmrRegister::HotWaterIdleTemp => MmrReg::HotWaterIdleTemp,
                    MmrRegister::GhcMode => MmrReg::GhcMode,
                    MmrRegister::SteamHighFlowStart => MmrReg::SteamHighFlowStart,
                    MmrRegister::HeaterVoltage => MmrReg::HeaterVoltage,
                    MmrRegister::EspressoWarmupTimeout => MmrReg::EspressoWarmupTimeout,
                    MmrRegister::CalibrationFlowMultiplier => MmrReg::CalibrationFlowMultiplier,
                    MmrRegister::FlushTimeout => MmrReg::FlushTimeout,
                    MmrRegister::UsbChargerOn => MmrReg::UsbChargerOn,
                    MmrRegister::FeatureFlags => MmrReg::FeatureFlags,
                    MmrRegister::CupWarmerTemp => MmrReg::CupWarmerTemp,
                };
                (mirror, core_reg)
            })
            .collect();
        // One mirror variant per core register — no extras, none missing.
        assert_eq!(mirror.len(), MmrRegister::ALL.len());
        // Each mirror round-trips back to the register it was built from.
        for (mirror_reg, core_reg) in mirror {
            assert_eq!(MmrRegister::from(mirror_reg), core_reg);
        }
    }

    #[test]
    fn cal_sensor_mirror_covers_every_cal_target() {
        // As `mmr_reg_mirror_covers_every_mmr_register`, for `CalSensor` /
        // `CalTarget`: the exhaustive match fails to compile on drift.
        for target in [CalTarget::Flow, CalTarget::Pressure, CalTarget::Temperature] {
            let mirror = match target {
                CalTarget::Flow => CalSensor::Flow,
                CalTarget::Pressure => CalSensor::Pressure,
                CalTarget::Temperature => CalSensor::Temperature,
            };
            assert_eq!(CalTarget::from(mirror), target);
        }
    }

    #[test]
    fn machine_request_maps_to_the_expected_states() {
        assert_eq!(
            MachineState::from(MachineRequest::Flush),
            MachineState::HotWaterRinse
        );
        assert_eq!(
            MachineState::from(MachineRequest::Descale),
            MachineState::Descale
        );
        assert_eq!(
            MachineState::from(MachineRequest::Clean),
            MachineState::Clean
        );
    }

    /// Every `NotificationSource` the wasm enum can name — used to fuzz the
    /// bridge against malformed input on every characteristic.
    fn every_source() -> [NotificationSource; 8] {
        [
            NotificationSource::De1State,
            NotificationSource::De1ShotSample,
            NotificationSource::ScaleWeight,
            NotificationSource::ScaleCommand,
            NotificationSource::De1WaterLevels,
            NotificationSource::De1Version,
            NotificationSource::De1MmrRead,
            NotificationSource::De1Calibration,
        ]
    }

    #[test]
    fn on_notification_does_not_panic_on_an_empty_slice() {
        for source in every_source() {
            let mut bridge = CremaBridge::new();
            // A scale is connected so the ScaleWeight decode path is exercised
            // rather than being skipped for want of a scale.
            bridge.connect_scale("BOOKOO_SC".to_owned());
            let out = bridge.on_notification(source, Vec::new(), 1_000.0);
            let parsed: serde_json::Value =
                serde_json::from_str(&out).expect("bridge returned malformed JSON");
            assert!(parsed.get("events").is_some());
            assert!(parsed.get("commands").is_some());
        }
    }

    #[test]
    fn on_notification_does_not_panic_on_garbage_bytes() {
        let garbage = vec![0xFF, 0x00, 0xAB, 0xCD, 0xEF, 0x13, 0x37];
        for source in every_source() {
            let mut bridge = CremaBridge::new();
            bridge.connect_scale("BOOKOO_SC".to_owned());
            let out = bridge.on_notification(source, garbage.clone(), 2_000.0);
            let parsed: serde_json::Value =
                serde_json::from_str(&out).expect("bridge returned malformed JSON");
            assert!(parsed["events"].is_array());
        }
    }

    #[test]
    fn on_notification_reports_a_decode_error_for_a_truncated_state_packet() {
        let mut bridge = CremaBridge::new();
        // A one-byte StateInfo packet cannot be decoded — the bridge must
        // surface a DecodeError event, not panic or drop the input silently.
        let out = bridge.on_notification(NotificationSource::De1State, vec![0x01], 1_000.0);
        assert!(out.contains("DecodeError"));
    }

    #[test]
    fn on_notification_reports_a_decode_error_for_a_truncated_water_levels_packet() {
        let mut bridge = CremaBridge::new();
        let out = bridge.on_notification(NotificationSource::De1WaterLevels, vec![0x00], 1_000.0);
        assert!(out.contains("DecodeError"));
    }
}

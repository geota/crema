//! # de1-ffi
//!
//! The UniFFI bridge: exposes [`de1_app::CremaCore`] to the Android (Kotlin)
//! shell. The bridge is deliberately thin — it wraps the core behind a mutex
//! and serializes the rich [`CoreOutput`](de1_app::CoreOutput) to JSON (the
//! "Option S" encoding from `docs/08-ffi-and-web-scope.md`). Inputs are simple
//! typed values UniFFI marshals natively.
//!
//! `unsafe` is unavoidable here — UniFFI generates the `extern "C"` FFI
//! scaffolding — which is why this crate opts out of the workspace's
//! `unsafe_code = "forbid"` lint (see `Cargo.toml`).

use std::sync::{Mutex, MutexGuard};

use de1_app::{CoreOutput, CremaCore, Event, ProfileUploadFailure, Source};
use de1_domain::Profile;
use de1_protocol::{CalTarget, MachineState, MmrRegister, ShotSettings};

uniffi::setup_scaffolding!();

/// Which BLE characteristic an incoming notification came from — mirrors
/// [`de1_app::Source`] across the FFI boundary.
#[derive(uniffi::Enum)]
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
    /// The DE1 `ShotSettings` characteristic (`cuuid_0B`) — steam /
    /// hot-water / group-temp settings, notify + read.
    De1ShotSettings,
    /// **DORMANT** — mirrors `de1_app::Source::De1ProfileHeader`, which the
    /// BLE shell no longer dispatches (docs/16 §6.1). Kept in the mirror
    /// enum for forward-compat. See the core variant for the longer note.
    De1ProfileHeader,
    /// The DE1 `FrameWrite` characteristic — per-frame ack echoes during a
    /// profile upload.
    De1FrameAck,
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
            NotificationSource::De1ShotSettings => Source::De1ShotSettings,
            NotificationSource::De1ProfileHeader => Source::De1ProfileHeader,
            NotificationSource::De1FrameAck => Source::De1FrameAck,
        }
    }
}

/// A DE1 memory-mapped register the shell can ask the core to read — mirrors
/// [`de1_protocol::MmrRegister`] across the FFI boundary.
#[derive(uniffi::Enum)]
pub enum MmrReg {
    /// CPU-board revision (raw value / 1000 → e.g. PCB v1.1).
    CpuBoardVersion,
    /// Machine model identifier (1=DE1, 2=DE1+, 3=DE1PRO, 4=DE1XL, …).
    MachineModel,
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
    /// Flush water target temperature, °C × 10 (modelled by reaprime; the
    /// legacy TCL de1app doesn't reference this address).
    FlushTemp,
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
    /// Whether the user is currently present at the machine.
    UserPresent,
    /// Steam two-tap-stop register.
    SteamTwoTapStop,
    /// Cup-warmer temperature (Bengle models only).
    CupWarmerTemp,
}

impl From<MmrReg> for MmrRegister {
    fn from(reg: MmrReg) -> MmrRegister {
        match reg {
            MmrReg::CpuBoardVersion => MmrRegister::CpuBoardVersion,
            MmrReg::MachineModel => MmrRegister::MachineModel,
            MmrReg::FirmwareVersion => MmrRegister::FirmwareVersion,
            MmrReg::GhcInfo => MmrRegister::GhcInfo,
            MmrReg::TankTempThreshold => MmrRegister::TankTempThreshold,
            MmrReg::FanThreshold => MmrRegister::FanThreshold,
            MmrReg::SerialNumber => MmrRegister::SerialNumber,
            MmrReg::SteamFlow => MmrRegister::SteamFlow,
            MmrReg::RefillKit => MmrRegister::RefillKit,
            MmrReg::FlushFlowRate => MmrRegister::FlushFlowRate,
            MmrReg::FlushTemp => MmrRegister::FlushTemp,
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
            MmrReg::UserPresent => MmrRegister::UserPresent,
            MmrReg::SteamTwoTapStop => MmrRegister::SteamTwoTapStop,
            MmrReg::CupWarmerTemp => MmrRegister::CupWarmerTemp,
        }
    }
}

/// A DE1 sensor the shell can ask the core to read calibration for — mirrors
/// [`de1_protocol::CalTarget`] across the FFI boundary.
#[derive(uniffi::Enum)]
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
#[derive(uniffi::Enum)]
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
    /// Run the steam-wand purge cycle.
    SteamRinse,
    /// Run the group / steam air-purge cycle.
    AirPurge,
    /// Enter scheduled-wake idle (firmware v1293+) — the machine is ready and
    /// holding temperature for a scheduled session. Distinct from regular
    /// `Idle` so a scheduler-aware shell can request it explicitly.
    SchedIdle,
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
            MachineRequest::SteamRinse => MachineState::SteamRinse,
            MachineRequest::AirPurge => MachineState::AirPurge,
            MachineRequest::SchedIdle => MachineState::SchedIdle,
        }
    }
}

/// The DE1's steam / hot-water settings, mirrored across the FFI boundary.
///
/// [`de1_protocol::ShotSettings`] is not (and should not be) UniFFI-annotated,
/// so this bridge-local record marshals it; the bridge builds the protocol
/// type from it inside [`CremaBridge::set_steam_hotwater_settings`].
#[derive(uniffi::Record)]
pub struct SteamHotWaterSettings {
    /// Steam-control flag bits (the legacy app writes 0).
    pub steam_flags: u8,
    /// Target steam temperature, °C.
    pub steam_temp_c: f32,
    /// Steam timeout, seconds.
    pub steam_timeout_s: f32,
    /// Target hot-water temperature, °C.
    pub hot_water_temp_c: f32,
    /// Hot-water volume, mL.
    pub hot_water_volume_ml: f32,
    /// Hot-water maximum time, seconds.
    pub hot_water_timeout_s: f32,
    /// Typical espresso volume, mL.
    pub espresso_volume_ml: f32,
    /// Espresso group target temperature, °C.
    pub group_temp_c: f32,
}

impl From<SteamHotWaterSettings> for ShotSettings {
    fn from(s: SteamHotWaterSettings) -> ShotSettings {
        ShotSettings {
            steam_flags: s.steam_flags,
            steam_temp_c: s.steam_temp_c,
            steam_timeout_s: s.steam_timeout_s,
            hot_water_temp_c: s.hot_water_temp_c,
            hot_water_volume_ml: s.hot_water_volume_ml,
            hot_water_timeout_s: s.hot_water_timeout_s,
            espresso_volume_ml: s.espresso_volume_ml,
            group_temp_c: s.group_temp_c,
        }
    }
}

/// The composite `set_heater_tweaks` argument record, mirroring
/// [`de1_app::HeaterTweaks`] across the UniFFI boundary.
///
/// `Duration` does not cross UniFFI cleanly, so timeouts are flat scalars
/// (`seconds` / `ms`) documented in their field names.
#[derive(uniffi::Record)]
pub struct HeaterTweaksRecord {
    /// Hot-water phase-1 flow rate, mL/s.
    pub phase_1_flow_rate: f32,
    /// Hot-water phase-2 flow rate, mL/s.
    pub phase_2_flow_rate: f32,
    /// Hot-water idle temperature, °C.
    pub hot_water_idle_temp_c: u8,
    /// Espresso warmup timeout, seconds.
    pub espresso_warmup_timeout_seconds: u32,
    /// Steam two-tap-stop register raw byte.
    pub steam_two_tap_stop: u8,
    /// Flush timeout, milliseconds (scaled to deciseconds on the wire).
    pub flush_timeout_ms: u32,
    /// Flush flow rate, mL/s.
    pub flush_flow_rate: f32,
    /// Hot-water flow rate, mL/s.
    pub hot_water_flow_rate: f32,
}

/// The Crema core, exposed to the Kotlin shell.
///
/// Methods that produce a [`CoreOutput`] return it as a JSON string; the shell
/// deserializes it into types generated by `typeshare`. Other methods take and
/// return plain scalars.
#[derive(uniffi::Object)]
pub struct CremaBridge {
    inner: Mutex<CremaCore>,
}

impl CremaBridge {
    /// Lock the wrapped core. The mutex is poisoned only by a panic inside a
    /// core method; the core does not panic, so this never fails in practice.
    fn core(&self) -> MutexGuard<'_, CremaCore> {
        self.inner.lock().expect("CremaCore mutex poisoned")
    }
}

impl Default for CremaBridge {
    fn default() -> CremaBridge {
        CremaBridge::new()
    }
}

#[uniffi::export]
impl CremaBridge {
    /// Create a core in the idle state.
    #[uniffi::constructor]
    pub fn new() -> CremaBridge {
        CremaBridge {
            inner: Mutex::new(CremaCore::new()),
        }
    }

    /// Feed a raw GATT notification. Returns a JSON-encoded [`CoreOutput`].
    pub fn on_notification(
        &self,
        source: NotificationSource,
        data: Vec<u8>,
        now_ms: u64,
    ) -> String {
        json(self.core().on_notification(source.into(), &data, now_ms))
    }

    /// Feed a periodic clock tick. Returns a JSON-encoded [`CoreOutput`].
    pub fn on_tick(&self, now_ms: u64) -> String {
        json(self.core().on_tick(now_ms))
    }

    /// Discard all session state — e.g. on disconnect.
    pub fn reset(&self) {
        self.core().reset();
    }

    /// Identify and connect a scale from its BLE advertised name. Returns the
    /// connected scale's display label, or `None` if the name matched no
    /// supported scale.
    pub fn connect_scale(&self, advertised_name: String) -> Option<String> {
        self.core().connect_scale(&advertised_name)
    }

    /// Arm automatic shot-stop. `weight` is grams (SAW), `volume` is
    /// millilitres (SAV), and `max_time` is shot-duration seconds.
    pub fn arm_auto_stop(
        &self,
        weight: Option<f32>,
        volume: Option<f32>,
        max_time: Option<f32>,
    ) {
        self.core().arm_auto_stop(weight, volume, max_time);
    }

    /// Disarm automatic shot-stop.
    pub fn disarm_auto_stop(&self) {
        self.core().disarm_auto_stop();
    }

    /// Build a [`CoreOutput`] (JSON) whose command asks the DE1 to enter
    /// `state`.
    pub fn request_machine_state(&self, state: MachineRequest) -> String {
        json(self.core().request_machine_state(state.into()))
    }

    /// Build a [`CoreOutput`] (JSON) whose command reads one DE1 memory-mapped
    /// register. The DE1 answers with a notification on the `De1MmrRead`
    /// characteristic, which decodes to an `MmrValue` event.
    pub fn read_mmr(&self, register: MmrReg) -> String {
        json(self.core().read_mmr(register.into()))
    }

    /// Build a [`CoreOutput`] (JSON) whose command reads `sensor`'s current
    /// (in-use) calibration. The DE1 answers on the `De1Calibration`
    /// characteristic, which decodes to a `Calibration` event.
    pub fn read_calibration(&self, sensor: CalSensor) -> String {
        json(self.core().read_calibration(sensor.into()))
    }

    /// Build a [`CoreOutput`] (JSON) whose command reads `sensor`'s factory
    /// calibration — the calibration the machine shipped with.
    pub fn read_factory_calibration(&self, sensor: CalSensor) -> String {
        json(self.core().read_factory_calibration(sensor.into()))
    }

    /// Build a [`CoreOutput`] (JSON) whose command tares the connected scale.
    pub fn tare_scale(&self) -> String {
        json(self.core().tare_scale())
    }

    /// Build a [`CoreOutput`] (JSON) whose command starts the connected
    /// scale's built-in timer. Capability-gated to scales that support
    /// software timer commands (the Bookoo today); empty otherwise.
    pub fn start_timer(&self) -> String {
        json(self.core().start_timer())
    }

    /// Build a [`CoreOutput`] (JSON) whose command stops the connected
    /// scale's built-in timer. Capability-gated like
    /// [`start_timer`](Self::start_timer).
    pub fn stop_timer(&self) -> String {
        json(self.core().stop_timer())
    }

    /// Build a [`CoreOutput`] (JSON) whose command resets the connected
    /// scale's built-in timer to zero. Capability-gated like
    /// [`start_timer`](Self::start_timer).
    pub fn reset_timer(&self) -> String {
        json(self.core().reset_timer())
    }

    /// What the currently-connected scale can do beyond reporting a bare
    /// weight, as a JSON-encoded `ScaleCapabilities` object — or `None` when no
    /// scale is connected.
    ///
    /// The shell deserializes the JSON into the `typeshare`-generated
    /// `ScaleCapabilities` type and drives capability-gated configuration UI
    /// off it: Crema is capability-driven, never device-driven. Returned as a
    /// JSON string, consistent with the rest of the bridge's JSON surface.
    pub fn scale_capabilities(&self) -> Option<String> {
        self.core().scale_capabilities().map(|caps| {
            serde_json::to_string(&caps)
                .expect("ScaleCapabilities is plain data and always serializes")
        })
    }

    /// The connected scale's BLE service and characteristic UUIDs, as a
    /// JSON-encoded `ScaleUuids` object — or `None` when no scale is connected.
    ///
    /// The shell deserializes the JSON into the `typeshare`-generated
    /// `ScaleUuids` type to know which GATT characteristics to subscribe to.
    /// Returned as a JSON string, consistent with the rest of the bridge's
    /// JSON surface; wired exactly like [`scale_capabilities`](Self::scale_capabilities).
    pub fn scale_uuids(&self) -> Option<String> {
        self.core().scale_uuids().map(|uuids| {
            serde_json::to_string(&uuids)
                .expect("ScaleUuids is plain data and always serializes")
        })
    }

    /// The firmware-update check result, as a JSON-encoded
    /// [`FirmwareUpdateStatus`](de1_app::FirmwareUpdateStatus). Pure read —
    /// no BLE traffic. Returns the `Unknown` variant until the DE1's `Version`
    /// characteristic has been observed via `on_notification`.
    pub fn firmware_update_status(&self) -> String {
        serde_json::to_string(&self.core().firmware_update_status())
            .expect("FirmwareUpdateStatus is plain data and always serializes")
    }

    /// Build a [`CoreOutput`] (JSON) whose command sets the connected scale's
    /// beep volume to `level`.
    ///
    /// `level` is clamped to the connected scale's `ScaleCapabilities::volume`
    /// `[min, max]` bounds. Capability-gated: the command is present only when
    /// the connected scale exposes a settable volume. Empty otherwise. Wired
    /// exactly like [`tare_scale`](Self::tare_scale).
    pub fn set_scale_volume(&self, level: u8) -> String {
        json(self.core().set_scale_volume(level))
    }

    /// Build a [`CoreOutput`] (JSON) whose command sets the connected scale's
    /// auto-standby timeout to `minutes`.
    ///
    /// `minutes` is clamped to the connected scale's
    /// `ScaleCapabilities::standby_minutes` `[min, max]` bounds. Capability-
    /// gated: the command is present only when the connected scale exposes a
    /// configurable auto-standby. Empty otherwise. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_standby_minutes(&self, minutes: u8) -> String {
        json(self.core().set_scale_standby_minutes(minutes))
    }

    /// Build a [`CoreOutput`] (JSON) whose command enables or disables the
    /// connected scale's flow smoothing.
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// supports flow smoothing. Empty otherwise. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_flow_smoothing(&self, enabled: bool) -> String {
        json(self.core().set_scale_flow_smoothing(enabled))
    }

    /// Build a [`CoreOutput`] (JSON) whose command enables or disables the
    /// connected scale's anti-mistouch.
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// supports anti-mistouch. Empty otherwise. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_anti_mistouch(&self, enabled: bool) -> String {
        json(self.core().set_scale_anti_mistouch(enabled))
    }

    /// Build a [`CoreOutput`] (JSON) whose commands switch the connected scale
    /// to display mode `mode_id`.
    ///
    /// Capability-gated: the commands are present only when the connected
    /// scale exposes switchable modes and `mode_id` is one of the listed
    /// modes. Switching a mode is **three** `WriteScale` commands, emitted in
    /// order — the shell must perform them in that order. Empty otherwise.
    /// Wired exactly like [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_mode(&self, mode_id: u8) -> String {
        json(self.core().set_scale_mode(mode_id))
    }

    /// Build a [`CoreOutput`] (JSON) whose command selects the connected
    /// scale's auto-stop mode (`0` = flow-stop, `1` = cup-removal).
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// supports an auto-stop-mode setting and `mode_id` is in range. An
    /// out-of-range `mode_id` yields an empty `CoreOutput`. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_auto_stop(&self, mode_id: u8) -> String {
        json(self.core().set_scale_auto_stop(mode_id))
    }

    /// Build a [`CoreOutput`] (JSON) whose command queries the connected
    /// scale's settings (the scale answers with a `03 0e …` notification).
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// exposes a configurable setting. Empty otherwise. Wired exactly like
    /// [`tare_scale`](Self::tare_scale).
    pub fn query_scale_settings(&self) -> String {
        json(self.core().query_scale_settings())
    }

    /// Write the DE1's steam / hot-water settings. Returns a JSON-encoded
    /// [`CoreOutput`] whose command applies them (with the legacy hot-water
    /// volume override when a scale is connected).
    pub fn set_steam_hotwater_settings(&self, settings: SteamHotWaterSettings) -> String {
        json(self.core().set_steam_hotwater_settings(settings.into()))
    }

    /// Enable or disable steam eco mode (the legacy `eco_steam` setting).
    /// Returns a JSON-encoded [`CoreOutput`].
    pub fn enable_steam_eco_mode(&self, enabled: bool, now_ms: u64) -> String {
        json(self.core().enable_steam_eco_mode(enabled, now_ms))
    }

    /// Write the DE1's water-tank refill threshold (`cuuid_11`). `threshold_mm`
    /// is the level at or below which the DE1 should ask for a refill.
    /// Returns a JSON-encoded [`CoreOutput`].
    pub fn set_refill_threshold(&self, threshold_mm: f32) -> String {
        json(self.core().set_refill_threshold(threshold_mm))
    }

    /// Write one DE1 memory-mapped register. `value` is the raw little-endian
    /// word the register expects, already scaled. `byte_len` is 1, 2, or 4 —
    /// the wire `Len` byte of the resulting `WriteToMMR` packet.
    pub fn write_mmr(&self, register: MmrReg, value: u32, byte_len: u8) -> String {
        json(self.core().write_mmr(register.into(), value, byte_len))
    }

    /// Set the fan-on temperature threshold, °C. Legacy
    /// `set_fan_temperature_threshold`; MMR `0x803808`, 1-byte.
    pub fn set_fan_threshold(&self, temp_c: u8) -> String {
        json(self.core().set_fan_threshold(temp_c))
    }

    /// Set the tank desired water-temperature threshold, °C. Legacy
    /// `set_tank_temperature_threshold` (immediate value only); MMR
    /// `0x80380C`, 1-byte.
    pub fn set_tank_threshold(&self, temp_c: u8) -> String {
        json(self.core().set_tank_threshold(temp_c))
    }

    /// Set the steam flow rate, mL/s. Scaled `int(10 × rate)`; MMR
    /// `0x803828`, 1-byte.
    pub fn set_steam_flow(&self, ml_per_s: f32) -> String {
        json(self.core().set_steam_flow(ml_per_s))
    }

    /// Set the seconds of high-flow steam at the start of a steam cycle.
    /// MMR `0x80382C`, 4-byte. Wire value is `seconds × 100`. `f32`
    /// to preserve sub-second precision (legacy default 0.7s).
    /// docs/22 §2.2.
    pub fn set_steam_highflow_start(&self, seconds: f32) -> String {
        json(self.core().set_steam_highflow_start(seconds))
    }

    /// Set the group-head-control mode. MMR `0x803820`, 1-byte.
    pub fn set_ghc_mode(&self, mode: u8) -> String {
        json(self.core().set_ghc_mode(mode))
    }

    /// Set the hot-water flow rate, mL/s. Scaled `int(10 × rate)`; MMR
    /// `0x80384C`, 2-byte.
    pub fn set_hot_water_flow_rate(&self, ml_per_s: f32) -> String {
        json(self.core().set_hot_water_flow_rate(ml_per_s))
    }

    /// Set the flush flow rate, mL/s. Scaled `int(10 × rate)`; MMR
    /// `0x803840`, 2-byte.
    pub fn set_flush_flow_rate(&self, ml_per_s: f32) -> String {
        json(self.core().set_flush_flow_rate(ml_per_s))
    }

    /// Set the flush timeout. `ms` is milliseconds; the wire scale is
    /// `int(10 × seconds)`. MMR `0x803848`, 2-byte.
    pub fn set_flush_timeout(&self, ms: u32) -> String {
        json(
            self.core()
                .set_flush_timeout(std::time::Duration::from_millis(u64::from(ms))),
        )
    }

    /// Enable / disable the tablet's USB charging output. MMR `0x803854`,
    /// 1-byte.
    pub fn set_usb_charger_on(&self, enabled: bool) -> String {
        json(self.core().set_usb_charger_on(enabled))
    }

    /// Tell the firmware whether the user is currently present at the
    /// machine. **Distinct register** from `set_feature_flags`. MMR
    /// `0x803860`, 1-byte.
    pub fn set_user_present(&self, present: bool) -> String {
        json(self.core().set_user_present(present))
    }

    /// Set the firmware feature-flag bitmask. **Distinct register** from
    /// `set_user_present`. MMR `0x803858`, 2-byte.
    pub fn set_feature_flags(&self, flags: u16) -> String {
        json(self.core().set_feature_flags(flags))
    }

    /// Override the refill-kit presence flag (`0`/`1`/`2`). MMR `0x80385C`,
    /// 4-byte.
    pub fn set_refill_kit_present(&self, state: u8) -> String {
        json(self.core().set_refill_kit_present(state))
    }

    /// Set the mains heater voltage. **Damaging if mis-set** — the shell
    /// must wrap this in a typed-to-confirm modal. MMR `0x803834`, 1-byte.
    pub fn set_heater_voltage(&self, volts: u8) -> String {
        json(self.core().set_heater_voltage(volts))
    }

    /// Set the cup-warmer temperature, °C (Bengle models only). MMR
    /// `0x803874`, 2-byte.
    pub fn set_cup_warmer_temperature(&self, temp_c: u8) -> String {
        json(self.core().set_cup_warmer_temperature(temp_c))
    }

    /// Set the flow-calibration multiplier. Scaled `int(1000 × multiplier)`;
    /// MMR `0x80383C`, 2-byte.
    pub fn set_calibration_flow_multiplier(&self, multiplier: f32) -> String {
        json(self.core().set_calibration_flow_multiplier(multiplier))
    }

    /// Apply the seven-register `heater_tweaks` composite write (legacy
    /// `set_heater_tweaks`). The record carries the seven settings the legacy
    /// app pushes at connect time.
    pub fn set_heater_tweaks(&self, tweaks: HeaterTweaksRecord) -> String {
        json(self.core().set_heater_tweaks(de1_app::HeaterTweaks {
            phase_1_flow_rate: tweaks.phase_1_flow_rate,
            phase_2_flow_rate: tweaks.phase_2_flow_rate,
            hot_water_idle_temp_c: tweaks.hot_water_idle_temp_c,
            espresso_warmup_timeout: std::time::Duration::from_secs(u64::from(
                tweaks.espresso_warmup_timeout_seconds,
            )),
            steam_two_tap_stop: tweaks.steam_two_tap_stop,
            flush_timeout: std::time::Duration::from_millis(u64::from(tweaks.flush_timeout_ms)),
            flush_flow_rate: tweaks.flush_flow_rate,
            hot_water_flow_rate: tweaks.hot_water_flow_rate,
        }))
    }

    /// The standard DE1 profiles Crema ships built in, as a JSON array string.
    ///
    /// Each element is a `Profile`; the shell deserializes the array into its
    /// own profile type, consistent with the rest of the JSON-string surface.
    pub fn builtin_profiles_json(&self) -> String {
        self.core().builtin_profiles_json()
    }

    /// Start uploading the profile in `profile_json` to the DE1.
    ///
    /// Returns a JSON-encoded `CoreOutput`. On success, `commands` carries
    /// the full upload sequence and `events` carries one
    /// `ProfileUploadStarted`. On validation failure (`Empty` /
    /// `TooManySteps`), `events` carries one `ProfileUploadFailed` and
    /// `commands` is empty. On a JSON parse failure, `events` carries one
    /// `DecodeError`.
    pub fn upload_profile(&self, profile_json: String, now_ms: u64) -> String {
        let profile: Profile = match serde_json::from_str(&profile_json) {
            Ok(p) => p,
            Err(e) => {
                let mut out = CoreOutput::default();
                out.events.push(Event::DecodeError {
                    message: format!("profile JSON parse failed: {e}"),
                });
                return json(out);
            }
        };
        let now = std::time::Duration::from_millis(now_ms);
        match self.core().upload_profile(&profile, now) {
            Ok(out) => json(out),
            Err(err) => {
                let mut out = CoreOutput::default();
                let reason = match err {
                    de1_app::AppError::ProfileUpload(domain) => match domain {
                        de1_domain::DomainError::NoSteps => ProfileUploadFailure::Empty,
                        de1_domain::DomainError::TooManySteps { count } => {
                            ProfileUploadFailure::TooManySteps {
                                count: u32::try_from(count).unwrap_or(u32::MAX),
                            }
                        }
                        _ => ProfileUploadFailure::Empty,
                    },
                    _ => ProfileUploadFailure::Empty,
                };
                out.events.push(Event::ProfileUploadFailed { reason });
                json(out)
            }
        }
    }

    /// Cancel an in-progress profile upload.
    pub fn cancel_profile_upload(&self) -> String {
        json(self.core().cancel_profile_upload())
    }

    /// `true` from `upload_profile` until the tail-ack / a failure /
    /// `cancel_profile_upload`.
    pub fn profile_upload_in_progress(&self) -> bool {
        self.core().profile_upload_in_progress()
    }

    /// Title of the profile most recently uploaded successfully.
    pub fn active_profile_title(&self) -> Option<String> {
        self.core().active_profile_title().map(str::to_owned)
    }

    /// Volume dispensed in the current shot, mL.
    pub fn dispensed_volume_ml(&self) -> f32 {
        self.core().dispensed_volume_ml()
    }

    /// The effective AC mains frequency the volume integrator uses, Hz.
    /// `None` until either the user pins it via
    /// [`set_line_frequency_override`](Self::set_line_frequency_override)
    /// or the auto-detector locks (1+ second of telemetry into a shot).
    pub fn line_frequency_hz(&self) -> Option<f32> {
        self.core().line_frequency_hz()
    }

    /// Pin the AC mains frequency. `50.0` or `60.0` overrides the
    /// auto-detector; `0.0` returns to auto (the UniFFI surface uses
    /// `0.0` as the "auto" sentinel to keep the type as `f32`).
    pub fn set_line_frequency_override(&self, hz: f32) {
        let override_hz = if hz > 0.0 { Some(hz) } else { None };
        self.core().set_line_frequency_override(override_hz);
    }

    /// Parse a legacy de1app `.shot` (Tcl-dict) history file. Returns
    /// the resulting `StoredShot` as a JSON string the Android shell
    /// can deserialize into its Room history store. docs/22 §5.1.
    /// Stateless — `&self` is required for the UniFFI instance-method
    /// ABI but the import does not touch the core.
    pub fn import_legacy_tcl_shot(&self, content: String) -> Result<String, String> {
        de1_domain::import_legacy_tcl_shot(&content)
            .map_err(|e| e.to_string())
            .and_then(|shot| shot.to_json().map_err(|e| e.to_string()))
    }

    /// Parse a modern de1app v2 `.shot.json` history file. Same return
    /// convention as `import_legacy_tcl_shot`.
    pub fn import_v2_json_shot(&self, content: String) -> Result<String, String> {
        de1_domain::import_v2_json_shot(&content)
            .map_err(|e| e.to_string())
            .and_then(|shot| shot.to_json().map_err(|e| e.to_string()))
    }

    /// Parse a community-v2 `.json` profile file. Returns the parsed
    /// `Profile` as JSON. docs/22 §5.1.
    pub fn import_v2_json_profile(&self, content: String) -> Result<String, String> {
        de1_domain::import_v2_json(&content)
            .map_err(|e| e.to_string())
            .and_then(|p| serde_json::to_string(&p).map_err(|e| e.to_string()))
    }

    /// Parse a legacy de1app `.tcl` profile file.
    pub fn import_legacy_tcl_profile(&self, content: String) -> Result<String, String> {
        de1_domain::import_legacy_tcl(&content)
            .map_err(|e| e.to_string())
            .and_then(|p| serde_json::to_string(&p).map_err(|e| e.to_string()))
    }

    /// Export a Crema `Profile` (as JSON) as a community-v2 `.json`
    /// document. Pure encoder.
    pub fn export_v2_json_profile(&self, profile_json: String) -> Result<String, String> {
        let profile: de1_domain::Profile = serde_json::from_str(&profile_json)
            .map_err(|e| e.to_string())?;
        Ok(de1_domain::export_v2_json(&profile))
    }

    /// Parse a legacy de1app `settings.tdb` file. Returns the parsed
    /// settings as a JSON string. docs/22 §5.4.
    pub fn import_settings_tdb(&self, content: String) -> Result<String, String> {
        de1_domain::import_settings_tdb(&content)
            .map_err(|e| e.to_string())
            .and_then(|s| serde_json::to_string(&s).map_err(|e| e.to_string()))
    }
}

/// Serialize a [`CoreOutput`] to JSON for the shell.
///
/// `CoreOutput` is a flat structure of enums, numbers, strings and vectors, so
/// serialization is infallible in practice — a failure would be a bug here.
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
        let bridge = CremaBridge::new();
        let json = bridge.on_notification(NotificationSource::De1State, vec![4, 5], 1_000);
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
    fn connect_scale_identifies_a_known_scale() {
        let bridge = CremaBridge::new();
        assert_eq!(
            bridge.connect_scale("BOOKOO_SC".to_owned()),
            Some("Bookoo".to_owned())
        );
        assert_eq!(bridge.connect_scale("Not A Scale".to_owned()), None);
    }

    #[test]
    fn scale_capabilities_is_none_without_a_connected_scale() {
        let bridge = CremaBridge::new();
        assert!(bridge.scale_capabilities().is_none());
    }

    #[test]
    fn scale_capabilities_reports_a_bookoo_as_first_class() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.scale_capabilities().expect("a connected scale");
        let caps: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(caps["volume"]["min"], 0);
        assert_eq!(caps["volume"]["max"], 3);
        assert_eq!(caps["reports_flow"], true);
        assert_eq!(caps["reports_timer"], true);
    }

    #[test]
    fn scale_capabilities_reports_a_weight_only_scale_as_having_none() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("Decent Scale ABC".to_owned());
        let json = bridge.scale_capabilities().expect("a connected scale");
        let caps: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(caps["volume"].is_null());
    }

    #[test]
    fn set_scale_volume_produces_a_write_scale_command_for_a_capable_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_volume(3);
        assert!(json.contains("\"commands\""));
        assert!(json.contains("WriteScale"));
    }

    #[test]
    fn set_scale_volume_produces_no_command_for_a_weight_only_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("Decent Scale ABC".to_owned());
        let json = bridge.set_scale_volume(3);
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(parsed["commands"].as_array().unwrap().is_empty());
    }

    #[test]
    fn scale_capabilities_reports_modes_for_a_bookoo() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.scale_capabilities().expect("a connected scale");
        let caps: serde_json::Value = serde_json::from_str(&json).unwrap();
        let modes = caps["modes"].as_array().expect("a modes array");
        assert_eq!(modes.len(), 3);
        assert_eq!(modes[0]["id"], 0);
        assert_eq!(modes[0]["name"], "Flow Rate");
    }

    #[test]
    fn set_scale_standby_minutes_produces_a_write_scale_command_for_a_capable_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_standby_minutes(15);
        assert!(json.contains("WriteScale"));
    }

    #[test]
    fn set_scale_flow_smoothing_produces_a_write_scale_command_for_a_capable_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_flow_smoothing(true);
        assert!(json.contains("WriteScale"));
    }

    #[test]
    fn set_scale_anti_mistouch_produces_a_write_scale_command_for_a_capable_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_anti_mistouch(false);
        assert!(json.contains("WriteScale"));
    }

    #[test]
    fn set_scale_mode_produces_three_write_scale_commands_for_a_capable_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_mode(1);
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        // Three mode writes plus the appended 0x0f settings query.
        assert_eq!(parsed["commands"].as_array().unwrap().len(), 4);
    }

    #[test]
    fn set_scale_auto_stop_produces_a_write_scale_command_for_a_capable_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_auto_stop(1);
        assert!(json.contains("WriteScale"));
    }

    #[test]
    fn set_scale_auto_stop_produces_no_command_for_an_out_of_range_mode_id() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_auto_stop(9);
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(parsed["commands"].as_array().unwrap().is_empty());
    }

    #[test]
    fn query_scale_settings_produces_a_write_scale_command_for_a_capable_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.query_scale_settings();
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        let commands = parsed["commands"].as_array().unwrap();
        assert_eq!(commands.len(), 1);
        assert_eq!(commands[0]["type"], "WriteScale");
    }

    #[test]
    fn query_scale_settings_produces_no_command_for_a_weight_only_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("Decent Scale ABC".to_owned());
        let json = bridge.query_scale_settings();
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(parsed["commands"].as_array().unwrap().is_empty());
    }

    #[test]
    fn scale_config_methods_produce_no_command_for_a_weight_only_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("Decent Scale ABC".to_owned());
        for json in [
            bridge.set_scale_standby_minutes(15),
            bridge.set_scale_flow_smoothing(true),
            bridge.set_scale_anti_mistouch(true),
            bridge.set_scale_mode(0),
            bridge.set_scale_auto_stop(0),
        ] {
            let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
            assert!(parsed["commands"].as_array().unwrap().is_empty());
        }
    }

    /// A `SteamHotWaterSettings` with representative values.
    fn steam_settings() -> SteamHotWaterSettings {
        SteamHotWaterSettings {
            steam_flags: 0,
            steam_temp_c: 150.0,
            steam_timeout_s: 120.0,
            hot_water_temp_c: 85.0,
            hot_water_volume_ml: 50.0,
            hot_water_timeout_s: 30.0,
            espresso_volume_ml: 36.0,
            group_temp_c: 92.0,
        }
    }

    #[test]
    fn set_steam_hotwater_settings_produces_a_write_command() {
        let bridge = CremaBridge::new();
        let json = bridge.set_steam_hotwater_settings(steam_settings());
        assert!(json.contains("\"commands\""));
        assert!(json.contains("WriteCharacteristic"));
        assert!(json.contains("De1ShotSettings"));
    }

    #[test]
    fn enable_steam_eco_mode_returns_core_output_json() {
        let bridge = CremaBridge::new();
        bridge.set_steam_hotwater_settings(steam_settings());
        bridge.enable_steam_eco_mode(true, 0);
        // After the idle delay a tick engages eco mode and rewrites the target.
        let json = bridge.on_tick(600_000);
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

    /// Every `NotificationSource` the FFI enum can name — used to fuzz the
    /// bridge against malformed input on every characteristic.
    fn every_source() -> [NotificationSource; 11] {
        [
            NotificationSource::De1State,
            NotificationSource::De1ShotSample,
            NotificationSource::ScaleWeight,
            NotificationSource::ScaleCommand,
            NotificationSource::De1WaterLevels,
            NotificationSource::De1Version,
            NotificationSource::De1MmrRead,
            NotificationSource::De1Calibration,
            NotificationSource::De1ShotSettings,
            NotificationSource::De1ProfileHeader,
            NotificationSource::De1FrameAck,
        ]
    }

    #[test]
    fn on_notification_does_not_panic_on_an_empty_slice() {
        for source in every_source() {
            let bridge = CremaBridge::new();
            // A scale is connected so the ScaleWeight decode path is exercised
            // rather than being skipped for want of a scale.
            bridge.connect_scale("BOOKOO_SC".to_owned());
            let out = bridge.on_notification(source, Vec::new(), 1_000);
            // The bridge must always return a well-formed CoreOutput envelope.
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
            let bridge = CremaBridge::new();
            bridge.connect_scale("BOOKOO_SC".to_owned());
            let out = bridge.on_notification(source, garbage.clone(), 2_000);
            let parsed: serde_json::Value =
                serde_json::from_str(&out).expect("bridge returned malformed JSON");
            assert!(parsed["events"].is_array());
        }
    }

    #[test]
    fn on_notification_reports_a_decode_error_for_a_truncated_state_packet() {
        let bridge = CremaBridge::new();
        // A one-byte StateInfo packet cannot be decoded — the bridge must
        // surface a DecodeError event, not panic or drop the input silently.
        let out = bridge.on_notification(NotificationSource::De1State, vec![0x01], 1_000);
        assert!(out.contains("DecodeError"));
    }

    #[test]
    fn on_notification_reports_a_decode_error_for_a_truncated_water_levels_packet() {
        let bridge = CremaBridge::new();
        let out = bridge.on_notification(NotificationSource::De1WaterLevels, vec![0x00], 1_000);
        assert!(out.contains("DecodeError"));
    }
}

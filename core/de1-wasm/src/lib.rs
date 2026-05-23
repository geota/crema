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

use de1_app::{CoreOutput, CremaCore, Event, ProfileUploadFailure, Source};
use de1_domain::Profile;
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
    /// The DE1 `ShotSettings` characteristic (`cuuid_0B`) — notifications
    /// when steam / hot-water / group-temp settings change, plus the reply
    /// to a connect-time Read. Mirrors `de1_app::Source::De1ShotSettings`.
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
/// [`de1_protocol::MmrRegister`] across the wasm boundary.
#[wasm_bindgen]
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
    /// Run the steam-wand purge cycle.
    SteamRinse,
    /// Run the group / steam air-purge cycle.
    AirPurge,
    /// Enter scheduled-wake idle (firmware v1293+) — the machine is ready and
    /// holding temperature for a scheduled session. Distinct from regular
    /// `Idle` so a scheduler-aware shell can request it explicitly.
    SchedIdle,
    /// Run the short calibration routine — the firmware's "ShortCal" state
    /// (reaprime calls it `calibration`). Diagnostic; the user should not
    /// be able to trigger this casually.
    ShortCal,
    /// Run the firmware self-test routine. Diagnostic.
    SelfTest,
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
            MachineRequest::ShortCal => MachineState::ShortCal,
            MachineRequest::SelfTest => MachineState::SelfTest,
        }
    }
}

/// Convert a DE1 water-tank depth (mm of sensor reading) to the tank's
/// water volume in mL. Pure helper — does no machine I/O. Exposed here so
/// every shell consumes the same tank-geometry calibration (see
/// `de1_domain::water_tank_ml` for the canonical implementation).
#[wasm_bindgen]
pub fn water_tank_ml(mm: f32) -> u16 {
    de1_domain::water_tank_ml(mm)
}

/// Compute the brew ratio (yield ÷ dose) for a pair of weights in grams.
/// Returns `undefined` (JS `null`/`undefined`) when the ratio is undefined
/// — non-positive dose, non-finite operand, or non-finite quotient.
/// The shell formats the `1:N` label; this is just the number.
#[wasm_bindgen]
pub fn brew_ratio(dose: f32, yield_out: f32) -> Option<f32> {
    de1_domain::brew_ratio(dose, yield_out)
}

// ─── Unit conversions (audit #1) ────────────────────────────────────────
//
// Pure `f32 → f32` math, mirroring `de1_domain::units`. The shell's
// `$lib/settings/format` keeps the unit-label string, the `—` placeholder,
// and the per-dimension decimal precision; it reaches here only for the
// numeric conversion so every shell trusts the same constants.

/// Convert a weight in grams to ounces (`g × 0.035274`).
#[wasm_bindgen]
pub fn grams_to_oz(grams: f32) -> f32 {
    de1_domain::grams_to_oz(grams)
}

/// Convert a weight in ounces to grams (`oz × 28.3495`).
#[wasm_bindgen]
pub fn oz_to_grams(oz: f32) -> f32 {
    de1_domain::oz_to_grams(oz)
}

/// Convert a temperature in Celsius to Fahrenheit (`°C × 9/5 + 32`).
#[wasm_bindgen]
pub fn celsius_to_fahrenheit(celsius: f32) -> f32 {
    de1_domain::celsius_to_fahrenheit(celsius)
}

/// Convert a temperature in Fahrenheit to Celsius (`(°F − 32) × 5/9`).
#[wasm_bindgen]
pub fn fahrenheit_to_celsius(fahrenheit: f32) -> f32 {
    de1_domain::fahrenheit_to_celsius(fahrenheit)
}

/// Convert a pressure in bar to psi (`bar × 14.5038`).
#[wasm_bindgen]
pub fn bar_to_psi(bar: f32) -> f32 {
    de1_domain::bar_to_psi(bar)
}

/// Convert a pressure in psi to bar (`psi × 0.0689476`).
#[wasm_bindgen]
pub fn psi_to_bar(psi: f32) -> f32 {
    de1_domain::psi_to_bar(psi)
}

/// Convert a volume in mL to US fluid ounces (`mL × 0.033814`).
#[wasm_bindgen]
pub fn ml_to_fl_oz(ml: f32) -> f32 {
    de1_domain::ml_to_fl_oz(ml)
}

/// Convert a volume in US fluid ounces to mL (`fl oz × 29.5735`).
#[wasm_bindgen]
pub fn fl_oz_to_ml(fl_oz: f32) -> f32 {
    de1_domain::fl_oz_to_ml(fl_oz)
}

/// Format the Bookoo scale's `u16` firmware version as a `"M.m.p"`
/// string (e.g. `141` → `"1.4.1"`). The Bookoo encodes its firmware as
/// `major × 100 + minor × 10 + patch`. Centralised here so every shell
/// renders the version identically.
#[wasm_bindgen]
pub fn format_bookoo_firmware(encoded: u16) -> String {
    // Re-exported from `de1-scale` via `de1-app::bookoo`, since the wasm
    // crate already depends on `de1-app` (which depends on `de1-scale`).
    de1_app::bookoo::format_firmware_version(encoded)
}

/// Classify a 1..10 roast level into a named band — returns the lowercase
/// wire string (`"light"` / `"medium"` / `"dark"`), or `undefined` for a
/// missing (`None`) level. Values outside 1..10 are clamped first. See
/// `de1_domain::roast_band` for the canonical implementation.
#[wasm_bindgen]
pub fn roast_band(level: Option<i32>) -> Option<String> {
    level.map(|n| de1_domain::roast_band(n).as_str().to_owned())
}

/// Whole calendar days (UTC) between an ISO `yyyy-mm-dd` roast date and
/// `now_unix_ms` — the bean's "days off roast". Returns `undefined` when
/// the date string is malformed or empty. See `de1_domain::days_off_roast`
/// for the canonical implementation.
///
/// `now_unix_ms` is an `f64` because the wasm-bindgen ABI cannot cross
/// `i64` cleanly; the caller passes `Date.now()` (an integer-valued
/// `f64`). Internally truncated to `i64` for the calendar math.
#[wasm_bindgen]
pub fn days_off_roast(roasted_on: Option<String>, now_unix_ms: f64) -> Option<f64> {
    let date = roasted_on?;
    // `Date.now()` is always integer-valued in JS; truncate defensively
    // so a non-finite caller value yields `None` rather than a panic.
    if !now_unix_ms.is_finite() {
        return None;
    }
    #[allow(clippy::cast_possible_truncation)]
    let now_ms = now_unix_ms as i64;
    #[allow(clippy::cast_precision_loss)]
    de1_domain::days_off_roast(&date, now_ms).map(|d| d as f64)
}

/// Rate how a bean's `days` off roast sits against the ideal rest window
/// for its `band` — the bean card's status verdict. `band` is the
/// lowercase wire string (`"light"` / `"medium"` / `"dark"`); `days` is
/// the integer day count. Returns the lowercase wire verdict
/// (`"best"` / `"ok"` / `"bad"`), or `undefined` when either input is
/// missing or `band` is not a recognised band. See
/// `de1_domain::roast_freshness` for the canonical implementation.
#[wasm_bindgen]
pub fn roast_freshness(band: Option<String>, days: Option<f64>) -> Option<String> {
    let band = band?;
    let days = days?;
    if !days.is_finite() {
        return None;
    }
    #[allow(clippy::cast_possible_truncation)]
    let days_i = days as i64;
    let band = match band.as_str() {
        "light" => de1_domain::RoastBand::Light,
        "medium" => de1_domain::RoastBand::Medium,
        "dark" => de1_domain::RoastBand::Dark,
        _ => return None,
    };
    Some(de1_domain::roast_freshness(band, days_i).as_str().to_owned())
}

/// Export a Rust-shape `StoredShot` (the same shape `import_v2_json_shot`
/// returns) as a pretty-printed community-v2 `.shot.json` document.
/// `shot_json` is the JSON of a `de1_domain::StoredShot` — the shell
/// stringifies its `RustStoredShot`-mapped record and passes it
/// straight through.
///
/// Symmetric with `import_v2_json_shot`; round-trip parity is tested
/// in `de1-domain::history_export`. Exposed as a top-level function
/// (not a `CremaBridge` method) because it is pure — no core state —
/// so a shell can call it synchronously from a `Blob` download path.
/// docs/26 audit #6.
#[wasm_bindgen]
pub fn export_v2_json_shot(shot_json: &str) -> Result<String, String> {
    let shot: de1_domain::StoredShot =
        serde_json::from_str(shot_json).map_err(|e| e.to_string())?;
    Ok(de1_domain::export_v2_json_shot(&shot))
}

/// Hard wire-protocol bounds for DE1 profile fields, exposed to shells as
/// a single JSON snapshot so steppers / validators / form widgets reach
/// for the same numbers. The shell parses the result once at module load
/// — these are constants, not live values. See
/// `de1_domain::profile_bounds` for the canonical declarations.
#[wasm_bindgen]
pub fn profile_bounds_json() -> String {
    use de1_domain::profile_bounds as b;
    // Hand-rolled to keep the wasm crate free of an extra serde-of-
    // statics module; the keys mirror the constant names so a reader can
    // pattern-match either way.
    format!(
        "{{\"max_profile_steps\":{},\"max_total_volume_ml\":{},\
         \"min_total_volume_ml\":{},\"min_pressure_bar\":{},\
         \"max_pressure_bar\":{},\"min_flow_ml_per_s\":{},\
         \"max_flow_ml_per_s\":{},\"min_temperature_c\":{},\
         \"max_temperature_c\":{},\"max_steam_temperature_c\":{},\
         \"min_frame_seconds\":{},\"max_frame_seconds\":{},\
         \"max_preinfuse_steps\":{}}}",
        b::MAX_PROFILE_STEPS,
        b::MAX_TOTAL_VOLUME_ML,
        b::MIN_TOTAL_VOLUME_ML,
        b::MIN_PRESSURE_BAR,
        b::MAX_PRESSURE_BAR,
        b::MIN_FLOW_ML_PER_S,
        b::MAX_FLOW_ML_PER_S,
        b::MIN_TEMPERATURE_C,
        b::MAX_TEMPERATURE_C,
        b::MAX_STEAM_TEMPERATURE_C,
        b::MIN_FRAME_SECONDS,
        b::MAX_FRAME_SECONDS,
        b::MAX_PREINFUSE_STEPS
    )
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

    /// Build a [`CoreOutput`] (JSON) whose command starts the connected
    /// scale's built-in timer. Capability-gated to scales that support
    /// software timer commands (the Bookoo today); empty otherwise.
    pub fn start_timer(&self) -> String {
        json(self.core.start_timer())
    }

    /// Build a [`CoreOutput`] (JSON) whose command stops the connected
    /// scale's built-in timer. Capability-gated like
    /// [`start_timer`](Self::start_timer).
    pub fn stop_timer(&self) -> String {
        json(self.core.stop_timer())
    }

    /// Build a [`CoreOutput`] (JSON) whose command resets the connected
    /// scale's built-in timer to zero. Capability-gated like
    /// [`start_timer`](Self::start_timer).
    pub fn reset_timer(&self) -> String {
        json(self.core.reset_timer())
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

    /// The firmware-update check result, as a JSON-encoded
    /// [`FirmwareUpdateStatus`](de1_app::FirmwareUpdateStatus). Pure read —
    /// no BLE traffic. Returns the `Unknown` variant until the DE1's `Version`
    /// characteristic has been observed via `on_notification`.
    pub fn firmware_update_status(&self) -> String {
        serde_json::to_string(&self.core.firmware_update_status())
            .expect("FirmwareUpdateStatus is plain data and always serializes")
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

    /// Write the DE1's water-tank refill threshold (`cuuid_11`). `threshold_mm`
    /// is the level at or below which the DE1 should ask for a refill.
    /// Returns a JSON-encoded [`CoreOutput`].
    pub fn set_refill_threshold(&self, threshold_mm: f32) -> String {
        json(self.core.set_refill_threshold(threshold_mm))
    }

    /// Write one DE1 memory-mapped register. `value` is the raw little-endian
    /// word the register expects, already scaled. `byte_len` is 1, 2, or 4 —
    /// the wire `Len` byte of the resulting `WriteToMMR` packet.
    pub fn write_mmr(&self, register: MmrReg, value: u32, byte_len: u8) -> String {
        json(self.core.write_mmr(register.into(), value, byte_len))
    }

    /// Set the fan-on temperature threshold, °C. Legacy
    /// `set_fan_temperature_threshold`; MMR `0x803808`, 1-byte.
    pub fn set_fan_threshold(&self, temp_c: u8) -> String {
        json(self.core.set_fan_threshold(temp_c))
    }

    /// Set the tank desired water-temperature threshold, °C. Legacy
    /// `set_tank_temperature_threshold` (immediate value only); MMR
    /// `0x80380C`, 1-byte.
    pub fn set_tank_threshold(&self, temp_c: u8) -> String {
        json(self.core.set_tank_threshold(temp_c))
    }

    /// Set the steam flow rate, mL/s. Scaled `int(10 × rate)`; MMR
    /// `0x803828`, 1-byte.
    pub fn set_steam_flow(&self, ml_per_s: f32) -> String {
        json(self.core.set_steam_flow(ml_per_s))
    }

    /// Set the seconds of high-flow steam at the start of a steam cycle.
    /// MMR `0x80382C`, 4-byte. The wire value is `seconds × 100` —
    /// `seconds` is `f32` so sub-second precision survives (legacy
    /// default 0.7s = wire 70). docs/22 §2.2.
    pub fn set_steam_highflow_start(&self, seconds: f32) -> String {
        json(self.core.set_steam_highflow_start(seconds))
    }

    /// Set the group-head-control mode. MMR `0x803820`, 1-byte.
    pub fn set_ghc_mode(&self, mode: u8) -> String {
        json(self.core.set_ghc_mode(mode))
    }

    /// Set the hot-water flow rate, mL/s. Scaled `int(10 × rate)`; MMR
    /// `0x80384C`, 2-byte.
    pub fn set_hot_water_flow_rate(&self, ml_per_s: f32) -> String {
        json(self.core.set_hot_water_flow_rate(ml_per_s))
    }

    /// Set the flush flow rate, mL/s. Scaled `int(10 × rate)`; MMR
    /// `0x803840`, 2-byte.
    pub fn set_flush_flow_rate(&self, ml_per_s: f32) -> String {
        json(self.core.set_flush_flow_rate(ml_per_s))
    }

    /// Set the flush timeout. `ms` is milliseconds; the wire scale is
    /// `int(10 × seconds)`. MMR `0x803848`, 2-byte.
    pub fn set_flush_timeout(&self, ms: u32) -> String {
        json(
            self.core
                .set_flush_timeout(std::time::Duration::from_millis(u64::from(ms))),
        )
    }

    /// Enable / disable the tablet's USB charging output. MMR `0x803854`,
    /// 1-byte.
    pub fn set_usb_charger_on(&self, enabled: bool) -> String {
        json(self.core.set_usb_charger_on(enabled))
    }

    /// Tell the firmware whether the user is currently present at the
    /// machine. **Distinct register** from `set_feature_flags`. MMR
    /// `0x803860`, 1-byte.
    pub fn set_user_present(&self, present: bool) -> String {
        json(self.core.set_user_present(present))
    }

    /// Set the firmware feature-flag bitmask. **Distinct register** from
    /// `set_user_present`. MMR `0x803858`, 2-byte.
    pub fn set_feature_flags(&self, flags: u16) -> String {
        json(self.core.set_feature_flags(flags))
    }

    /// Override the refill-kit presence flag (`0`/`1`/`2`). MMR `0x80385C`,
    /// 4-byte.
    pub fn set_refill_kit_present(&self, state: u8) -> String {
        json(self.core.set_refill_kit_present(state))
    }

    /// Set the mains heater voltage. **Damaging if mis-set** — the shell
    /// must wrap this in a typed-to-confirm modal. MMR `0x803834`, 1-byte.
    pub fn set_heater_voltage(&self, volts: u8) -> String {
        json(self.core.set_heater_voltage(volts))
    }

    /// Set the cup-warmer temperature, °C (Bengle models only). MMR
    /// `0x803874`, 2-byte.
    pub fn set_cup_warmer_temperature(&self, temp_c: u8) -> String {
        json(self.core.set_cup_warmer_temperature(temp_c))
    }

    /// Set the flow-calibration multiplier. Scaled `int(1000 × multiplier)`;
    /// MMR `0x80383C`, 2-byte.
    pub fn set_calibration_flow_multiplier(&self, multiplier: f32) -> String {
        json(self.core.set_calibration_flow_multiplier(multiplier))
    }

    /// Apply the seven-register `heater_tweaks` composite write (legacy
    /// `set_heater_tweaks`). Field arguments are passed flat because
    /// wasm-bindgen cannot cross a `HeaterTweaks` struct; documented units
    /// match the per-setter scales.
    #[allow(clippy::too_many_arguments)]
    pub fn set_heater_tweaks(
        &self,
        phase_1_flow_rate: f32,
        phase_2_flow_rate: f32,
        hot_water_idle_temp_c: u8,
        espresso_warmup_timeout_seconds: u32,
        steam_two_tap_stop: u8,
        flush_timeout_ms: u32,
        flush_flow_rate: f32,
        hot_water_flow_rate: f32,
    ) -> String {
        json(self.core.set_heater_tweaks(de1_app::HeaterTweaks {
            phase_1_flow_rate,
            phase_2_flow_rate,
            hot_water_idle_temp_c,
            espresso_warmup_timeout: std::time::Duration::from_secs(u64::from(
                espresso_warmup_timeout_seconds,
            )),
            steam_two_tap_stop,
            flush_timeout: std::time::Duration::from_millis(u64::from(flush_timeout_ms)),
            flush_flow_rate,
            hot_water_flow_rate,
        }))
    }

    /// The standard DE1 profiles Crema ships built in, as a JSON array string.
    ///
    /// Each element is a `Profile`; the shell parses the array into its own
    /// profile type, consistent with the rest of the JSON-string surface.
    pub fn builtin_profiles_json(&self) -> String {
        self.core.builtin_profiles_json()
    }

    /// Start uploading the profile in `profile_json` to the DE1.
    ///
    /// Returns a JSON-encoded [`CoreOutput`]:
    ///
    /// - On success, `commands` carries the full upload sequence (header
    ///   write, every frame write in order, tail) and `events` carries one
    ///   `ProfileUploadStarted`.
    /// - On validation failure (`Empty` / `TooManySteps`), `events`
    ///   carries one `ProfileUploadFailed` and `commands` is empty.
    /// - On a JSON parse failure, `events` carries one `DecodeError`.
    ///
    /// Subsequent acks arrive via `on_notification(De1FrameAck, …)`; the
    /// core emits `ProfileUploadProgress` / `ProfileUploadCompleted` /
    /// `ProfileUploadFailed` from those.
    pub fn upload_profile(&mut self, profile_json: String, now_ms: f64) -> String {
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
        let now = std::time::Duration::from_millis(now_ms as u64);
        match self.core.upload_profile(&profile, now) {
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
                        // The Serialization variant is for shot history;
                        // upload_profile cannot produce it. Fall through to
                        // Empty as the safest default.
                        _ => ProfileUploadFailure::Empty,
                    },
                    // Other AppError variants (e.g. Serialization) similarly
                    // shouldn't reach this path. Best-effort surface.
                    _ => ProfileUploadFailure::Empty,
                };
                out.events.push(Event::ProfileUploadFailed { reason });
                json(out)
            }
        }
    }

    /// Cancel an in-progress profile upload. Returns the
    /// `ProfileUploadFailed { Aborted }` `CoreOutput`, or an empty one if
    /// no upload is in flight.
    pub fn cancel_profile_upload(&mut self) -> String {
        json(self.core.cancel_profile_upload())
    }

    /// `true` from `upload_profile` until the tail-ack / a failure /
    /// `cancel_profile_upload`.
    pub fn profile_upload_in_progress(&self) -> bool {
        self.core.profile_upload_in_progress()
    }

    /// Title of the profile most recently uploaded successfully — the
    /// "active profile on the DE1" identity the brew page surfaces.
    /// `null` until the first successful upload; cleared by a reset.
    pub fn active_profile_title(&self) -> Option<String> {
        self.core.active_profile_title().map(str::to_owned)
    }

    /// Volume dispensed in the current shot, mL — integrated live from
    /// every `ShotSample`. Resets to 0 on every `Event::ShotStarted`.
    pub fn dispensed_volume_ml(&self) -> f32 {
        self.core.dispensed_volume_ml()
    }

    /// The effective AC mains frequency the volume integrator uses, Hz.
    /// `None` until either the user pins it via
    /// [`set_line_frequency_override`](Self::set_line_frequency_override)
    /// or the auto-detector locks (1+ second of telemetry into a shot).
    pub fn line_frequency_hz(&self) -> Option<f32> {
        self.core.line_frequency_hz()
    }

    /// Pin the AC mains frequency the volume integrator uses. `50.0` or
    /// `60.0` overrides the auto-detector; `0.0` or any other value
    /// returns to auto. (The wasm ABI can't express `Option<f32>`
    /// cleanly, so `0.0` is the "auto" sentinel.)
    pub fn set_line_frequency_override(&mut self, hz: f32) {
        let override_hz = if hz > 0.0 { Some(hz) } else { None };
        self.core.set_line_frequency_override(override_hz);
    }

    /// Parse a legacy de1app `.shot` (Tcl-dict) history file. Returns
    /// the resulting `StoredShot` as a JSON string the shell can
    /// deserialize into its IndexedDB history store. Returns the
    /// `Err.message` on failure (the bridge serializes Option<Err> via
    /// the `BridgeResult` shape consumers already use elsewhere).
    /// docs/22 §5.1. Stateless — takes `&self` for the wasm-bindgen
    /// instance-method ABI but does not touch the core.
    pub fn import_legacy_tcl_shot(&self, content: &str) -> Result<String, String> {
        de1_domain::import_legacy_tcl_shot(content)
            .map_err(|e| e.to_string())
            .and_then(|shot| shot.to_json().map_err(|e| e.to_string()))
    }

    /// Parse a modern de1app v2 `.shot.json` history file. Same return
    /// convention as `import_legacy_tcl_shot`.
    pub fn import_v2_json_shot(&self, content: &str) -> Result<String, String> {
        de1_domain::import_v2_json_shot(content)
            .map_err(|e| e.to_string())
            .and_then(|shot| shot.to_json().map_err(|e| e.to_string()))
    }

    /// Parse a community-v2 `.json` profile file. Returns the parsed
    /// `Profile` as JSON the shell can deserialize into the existing
    /// TS `Profile` shape and feed to `fromCoreProfile`. docs/22 §5.1
    /// (the profile counterpart to the shot importer).
    pub fn import_v2_json_profile(&self, content: &str) -> Result<String, String> {
        de1_domain::import_v2_json(content)
            .map_err(|e| e.to_string())
            .and_then(|p| serde_json::to_string(&p).map_err(|e| e.to_string()))
    }

    /// Parse a legacy de1app `.tcl` profile file. Same return convention
    /// as `import_v2_json_profile`.
    pub fn import_legacy_tcl_profile(&self, content: &str) -> Result<String, String> {
        de1_domain::import_legacy_tcl(content)
            .map_err(|e| e.to_string())
            .and_then(|p| serde_json::to_string(&p).map_err(|e| e.to_string()))
    }

    /// Export a Crema `Profile` (as JSON the shell already has) as a
    /// community-v2 `.json` document. Pure encoder — never fails.
    pub fn export_v2_json_profile(&self, profile_json: &str) -> Result<String, String> {
        let profile: de1_domain::Profile =
            serde_json::from_str(profile_json).map_err(|e| e.to_string())?;
        Ok(de1_domain::export_v2_json(&profile))
    }

    /// Parse a legacy de1app `settings.tdb` file. Returns the
    /// `ImportedDe1AppSettings` struct as a JSON string the shell
    /// can deserialize + selectively apply to Crema's settings store
    /// + queued DE1-side writes. docs/22 §5.4.
    pub fn import_settings_tdb(&self, content: &str) -> Result<String, String> {
        de1_domain::import_settings_tdb(content)
            .map_err(|e| e.to_string())
            .and_then(|s| serde_json::to_string(&s).map_err(|e| e.to_string()))
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
    fn roast_band_classifies_or_returns_none() {
        assert_eq!(roast_band(Some(1)), Some("light".to_owned()));
        assert_eq!(roast_band(Some(5)), Some("medium".to_owned()));
        assert_eq!(roast_band(Some(10)), Some("dark".to_owned()));
        // Clamped: 999 reads as dark.
        assert_eq!(roast_band(Some(999)), Some("dark".to_owned()));
        // None in, None out — the bean has no logged level.
        assert_eq!(roast_band(None), None);
    }

    #[test]
    fn days_off_roast_handles_missing_date_and_now() {
        // 2026-05-22T12:00:00Z — same reference as the de1-domain tests.
        let now_ms = 1_779_451_200_000.0;
        assert_eq!(
            days_off_roast(Some("2026-05-15".to_owned()), now_ms),
            Some(7.0)
        );
        // Missing date → None.
        assert_eq!(days_off_roast(None, now_ms), None);
        // Malformed date → None.
        assert_eq!(
            days_off_roast(Some("not-a-date".to_owned()), now_ms),
            None
        );
        // Non-finite now → None (defensive).
        assert_eq!(
            days_off_roast(Some("2026-05-22".to_owned()), f64::NAN),
            None
        );
    }

    #[test]
    fn roast_freshness_returns_lowercase_verdict_or_none() {
        // Dark roast, 7 days off → inside green [4, 10] → best.
        assert_eq!(
            roast_freshness(Some("dark".to_owned()), Some(7.0)),
            Some("best".to_owned())
        );
        // Light roast, 50 days off → past ok_high (35) → bad.
        assert_eq!(
            roast_freshness(Some("light".to_owned()), Some(50.0)),
            Some("bad".to_owned())
        );
        // Missing band or days → None.
        assert_eq!(roast_freshness(None, Some(7.0)), None);
        assert_eq!(roast_freshness(Some("dark".to_owned()), None), None);
        // Unrecognised band → None (graceful — not a panic).
        assert_eq!(
            roast_freshness(Some("ultraviolet".to_owned()), Some(7.0)),
            None
        );
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
                    MmrRegister::CpuBoardVersion => MmrReg::CpuBoardVersion,
                    MmrRegister::MachineModel => MmrReg::MachineModel,
                    MmrRegister::FirmwareVersion => MmrReg::FirmwareVersion,
                    MmrRegister::GhcInfo => MmrReg::GhcInfo,
                    MmrRegister::TankTempThreshold => MmrReg::TankTempThreshold,
                    MmrRegister::FanThreshold => MmrReg::FanThreshold,
                    MmrRegister::SerialNumber => MmrReg::SerialNumber,
                    MmrRegister::SteamFlow => MmrReg::SteamFlow,
                    MmrRegister::RefillKit => MmrReg::RefillKit,
                    MmrRegister::FlushFlowRate => MmrReg::FlushFlowRate,
                    MmrRegister::FlushTemp => MmrReg::FlushTemp,
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
                    MmrRegister::UserPresent => MmrReg::UserPresent,
                    MmrRegister::SteamTwoTapStop => MmrReg::SteamTwoTapStop,
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

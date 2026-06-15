//! # de1-ffi
//!
//! The UniFFI bridge: exposes [`de1_app::CremaCore`] to the Android (Kotlin)
//! shell. The bridge is deliberately thin — it wraps the core behind a mutex
//! and serializes the rich [`CoreOutput`](de1_app::CoreOutput) to JSON
//! ("Option S" encoding — every CoreOutput crosses as one JSON string).
//! Inputs are simple typed values UniFFI marshals natively.
//!
//! `unsafe` is unavoidable here — UniFFI generates the `extern "C"` FFI
//! scaffolding — which is why this crate opts out of the workspace's
//! `unsafe_code = "forbid"` lint (see `Cargo.toml`).
//!
//! ## Intentional parity gaps vs `de1-wasm` (RS3)
//!
//! The web (`de1-wasm`) bridge exposes two surfaces this FFI bridge deliberately
//! does NOT mirror yet, because the Android shell has no feature that consumes
//! them — mirroring them now would be dead exports (and uniffi scaffolding the
//! Kotlin side would never call):
//!
//! - **`MachineRequest` / machine-state requests** (`ShortCal`, `SelfTest`,
//!   Sleep/Idle/Espresso/… transitions). Android has no machine-control surface
//!   yet — its `WriteCharacteristic` path is unimplemented (tracked as AND5) —
//!   so the request enum + command plumbing has no caller. Mirror it when
//!   Android gains machine control.
//!
//! These are tracked divergences, not oversights: the shared `de1-app` /
//! `de1-domain` logic is identical across shells — only the thin per-shell
//! *exports* differ, scoped to what each shell uses today. Keep this list in
//! sync as the Android surface grows.

use std::sync::{Mutex, MutexGuard};

use de1_app::{CoreOutput, CremaCore, Event, ProfileUploadFailure, Source};
use de1_domain::Profile;
use de1_protocol::{CalTarget, MachineState, MmrRegister, ShotSettings};

uniffi::setup_scaffolding!();

/// The error every fallible FFI export throws.
///
/// UniFFI's bindgen cannot lower a bare `String` throw type — it panics with
/// "unknown throw type: Some(String)" — so each fallible export returns this
/// instead. `#[uniffi(flat_error)]` keeps the wire shape minimal: only the
/// `Display` message crosses to Kotlin, surfaced as a thrown `CremaException`
/// carrying that text (the same message the old `String` error held).
#[derive(Debug, uniffi::Error)]
#[uniffi(flat_error)]
pub enum CremaError {
    /// A human-readable failure message from the core, serde, or argument
    /// validation.
    Failed(String),
}

impl std::fmt::Display for CremaError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            CremaError::Failed(message) => f.write_str(message),
        }
    }
}

impl std::error::Error for CremaError {}

impl From<String> for CremaError {
    fn from(message: String) -> Self {
        CremaError::Failed(message)
    }
}

/// Convert any `Display` error into a [`CremaError`] — the FFI-safe replacement
/// for the old `.map_err(crema_err)`.
fn crema_err<E: std::fmt::Display>(e: E) -> CremaError {
    CremaError::Failed(e.to_string())
}

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
    /// BLE shell no longer dispatches. Kept in the mirror
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
    /// Hot-water volume, ml.
    pub hot_water_volume_ml: f32,
    /// Hot-water maximum time, seconds.
    pub hot_water_timeout_s: f32,
    /// Typical espresso volume, ml.
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

/// Mint a fresh profile ID — a standard UUID v7 (RFC 9562, 2024) in
/// the 36-character dashed form, e.g.
/// `01910f80-7a3b-7c54-b2d1-23a4f8e9cd00`. The 48-bit timestamp prefix
/// makes IDs lexicographically sortable by creation time; the 74 random
/// bits keep collisions astronomical.
///
/// Used by the Android shell when creating, duplicating, or importing a
/// **custom** profile. Built-in IDs are pre-generated and live in
/// `core/de1-domain/profiles/builtin.json`, not minted at runtime.
/// Exposed at the top level rather than on [`CremaBridge`] so the shell
/// can call it without needing a core instance.
///
/// See [`de1_domain::new_profile_id`] for the canonical implementation.
#[uniffi::export]
pub fn new_profile_id() -> String {
    de1_domain::new_profile_id()
}

/// Compute the brew ratio (yield ÷ dose) for a pair of weights in grams.
/// Returns `None` when the ratio is undefined — non-positive dose,
/// non-finite operand, or non-finite quotient. The shell formats the
/// `1:N` label; this is just the number. Mirrors the wasm `brew_ratio`
/// so the web and Android shells share one implementation
/// (`de1_domain::brew_ratio`) — no drift in the ratio math.
#[uniffi::export]
pub fn brew_ratio(dose: f32, yield_out: f32) -> Option<f32> {
    de1_domain::brew_ratio(dose, yield_out)
}

/// Classify a 1..10 roast level into a named band — the lowercase wire
/// string (`"light"` / `"medium"` / `"dark"`), or `None` for a missing
/// level. Values outside 1..10 are clamped first. Mirrors the wasm
/// `roast_band`; see [`de1_domain::roast_band`] for the canonical impl.
#[uniffi::export]
pub fn roast_band(level: Option<i32>) -> Option<String> {
    level.map(|n| de1_domain::roast_band(n).as_str().to_owned())
}

/// Finer 5-bucket **display** label for a 1..10 roast level — `"Light"` /
/// `"Med-light"` / `"Medium"` / `"Med-dark"` / `"Dark"`, or `None` for a
/// missing level. Display-only (the slider caption / tile pill); every
/// comparison and filter rides on the 3-band [`roast_band`]. Mirrors the wasm
/// `roast_band5`; see [`de1_domain::roast_band5`] for the canonical mapping.
#[uniffi::export]
pub fn roast_band5(level: Option<i32>) -> Option<String> {
    level.map(|n| de1_domain::roast_band5(n).to_owned())
}

/// Whole calendar days (UTC) between an ISO `yyyy-mm-dd` roast date and
/// `now_unix_ms` — the bean's "days off roast". `None` when the date is
/// malformed or empty. Mirrors the wasm `days_off_roast`; see
/// [`de1_domain::days_off_roast`].
#[uniffi::export]
pub fn days_off_roast(roasted_on: Option<String>, now_unix_ms: i64) -> Option<i64> {
    de1_domain::days_off_roast(roasted_on.as_deref()?, now_unix_ms)
}

/// Rate how a bean's `days` off roast sits against the ideal rest window
/// for its `band` (the lowercase wire string). The window is **band-aware**
/// — dark roasts degas fastest, light slowest — so this is the canonical
/// freshness verdict shared with the web shell. Returns the lowercase
/// verdict (`"best"` / `"ok"` / `"bad"`), or `None` when an input is missing
/// or `band` is not recognised. Mirrors the wasm `roast_freshness`; see
/// [`de1_domain::roast_freshness`].
#[uniffi::export]
pub fn roast_freshness(band: Option<String>, days: Option<i64>) -> Option<String> {
    let band = match band.as_deref()? {
        "light" => de1_domain::RoastBand::Light,
        "medium" => de1_domain::RoastBand::Medium,
        "dark" => de1_domain::RoastBand::Dark,
        _ => return None,
    };
    Some(de1_domain::roast_freshness(band, days?).as_str().to_owned())
}

// ── Unit conversions ──────────────────────────────────────────────────────
//
// The eight canonical-unit conversions, mirroring the wasm free fns of the same
// names (issue 44). Crema's canonical units are grams / °C / bar / ml; these let
// Android route every display readout through the same `de1_domain::units` math
// the web shell already uses, so the °C/°F & g/oz toggles work without any
// duplicated conversion constants drifting on the Kotlin side.

/// Convert a weight in grams to ounces. See [`de1_domain::grams_to_oz`].
#[uniffi::export]
pub fn grams_to_oz(grams: f32) -> f32 {
    de1_domain::grams_to_oz(grams)
}

/// Convert a weight in ounces to grams. See [`de1_domain::oz_to_grams`].
#[uniffi::export]
pub fn oz_to_grams(oz: f32) -> f32 {
    de1_domain::oz_to_grams(oz)
}

/// Convert a temperature in Celsius to Fahrenheit. See [`de1_domain::celsius_to_fahrenheit`].
#[uniffi::export]
pub fn celsius_to_fahrenheit(celsius: f32) -> f32 {
    de1_domain::celsius_to_fahrenheit(celsius)
}

/// Convert a temperature in Fahrenheit to Celsius. See [`de1_domain::fahrenheit_to_celsius`].
#[uniffi::export]
pub fn fahrenheit_to_celsius(fahrenheit: f32) -> f32 {
    de1_domain::fahrenheit_to_celsius(fahrenheit)
}

/// Convert a pressure in bar to psi. See [`de1_domain::bar_to_psi`].
#[uniffi::export]
pub fn bar_to_psi(bar: f32) -> f32 {
    de1_domain::bar_to_psi(bar)
}

/// Convert a pressure in psi to bar. See [`de1_domain::psi_to_bar`].
#[uniffi::export]
pub fn psi_to_bar(psi: f32) -> f32 {
    de1_domain::psi_to_bar(psi)
}

/// Convert a volume in ml to US fluid ounces. See [`de1_domain::ml_to_fl_oz`].
#[uniffi::export]
pub fn ml_to_fl_oz(ml: f32) -> f32 {
    de1_domain::ml_to_fl_oz(ml)
}

/// Convert a volume in US fluid ounces to ml. See [`de1_domain::fl_oz_to_ml`].
#[uniffi::export]
pub fn fl_oz_to_ml(fl_oz: f32) -> f32 {
    de1_domain::fl_oz_to_ml(fl_oz)
}

// ── Bean / roaster sync surface ──────────────────────────────────────────
//
// The six fns the Visualizer bean-sync flow needs (reconcile + de-dup
// signatures + row coercion). Exported here proactively: Android bean sync is
// still web-only (`visualizer/VisualizerSync.kt`), but exporting now means the
// future Android path routes through the same core logic from day one — no
// re-port. Each mirrors the wasm export of the same name.

/// Reconcile a remote bean pull against the local library → a JSON array of
/// `BeanReconcileAction`. `payload` is the sync-input JSON. Mirrors the wasm
/// `reconcileBeans`; see [`de1_domain::reconcile_beans_json`].
///
/// # Errors
///
/// Returns a [`CremaError`] when `payload` can't be deserialised.
#[uniffi::export]
pub fn reconcile_beans(payload: String) -> Result<String, CremaError> {
    de1_domain::reconcile_beans_json(&payload).map_err(crema_err)
}

/// Reconcile a remote roaster pull against the local library → a JSON array of
/// `RoasterReconcileAction`. Mirrors the wasm `reconcileRoasters`; see
/// [`de1_domain::reconcile_roasters_json`].
///
/// # Errors
///
/// Returns a [`CremaError`] when `payload` can't be deserialised.
#[uniffi::export]
pub fn reconcile_roasters(payload: String) -> Result<String, CremaError> {
    de1_domain::reconcile_roasters_json(&payload).map_err(crema_err)
}

/// djb2 hex de-dup signature for a bean — `(name, roasterName, roastedOn)`.
/// Mirrors the wasm `signatureForBean`; see [`de1_domain::signature_for_bean`].
#[uniffi::export]
pub fn signature_for_bean(
    name: String,
    roaster_name: Option<String>,
    roasted_on: Option<String>,
) -> String {
    de1_domain::signature_for_bean(&name, roaster_name.as_deref(), roasted_on.as_deref())
}

/// djb2 hex de-dup signature for a roaster — its normalised name. Mirrors the
/// wasm `signatureForRoaster`; see [`de1_domain::signature_for_roaster`].
#[uniffi::export]
pub fn signature_for_roaster(name: String) -> String {
    de1_domain::signature_for_roaster(&name)
}

/// Coerce a stored bean row JSON → a normalised `Bean` JSON, or `None` when the
/// row isn't an object or lacks a string `id` / `name`. `now_unix_ms` seeds the
/// timestamp defaults. Tolerant: wrong-typed fields are skipped, not rejected.
/// Mirrors the wasm `coerceBean`; see [`de1_domain::coerce_bean_json`].
#[uniffi::export]
pub fn coerce_bean(raw_json: String, now_unix_ms: i64) -> Option<String> {
    de1_domain::coerce_bean_json(&raw_json, now_unix_ms)
}

/// Coerce a stored roaster row JSON → a normalised `Roaster` JSON, or `None`.
/// Mirrors the wasm `coerceRoaster`; see [`de1_domain::coerce_roaster_json`].
#[uniffi::export]
pub fn coerce_roaster(raw_json: String, now_unix_ms: i64) -> Option<String> {
    de1_domain::coerce_roaster_json(&raw_json, now_unix_ms)
}

// ── Machine error / model strings ────────────────────────────────────────
//
// Human-readable machine text the shells show instead of raw enum / register
// values. The web shell reaches these through the wasm exports of the same
// name; exported here so Android renders the same strings.

/// Human-readable English message for an error substate **name** (the variant
/// name as it crosses the wire, e.g. `"NoWater"`), or `None` when the substate
/// isn't an error (or isn't recognised). Mirrors the wasm `subStateErrorMessage`;
/// see [`de1_protocol::SubState::error_message_for_name`].
#[uniffi::export]
pub fn sub_state_error_message(name: String) -> Option<String> {
    de1_protocol::SubState::error_message_for_name(&name).map(str::to_owned)
}

/// Whether a Visualizer call error (`tag` + optional HTTP `status`) is worth
/// retrying. Mirrors the wasm `isRecoverable`; see [`de1_domain::is_recoverable`].
#[uniffi::export]
pub fn is_recoverable(tag: String, status: Option<u16>) -> bool {
    de1_domain::is_recoverable(&tag, status)
}

/// Human-readable name for a raw `MachineModel` MMR value (e.g. `1` → `"DE1"`),
/// falling back to `"model N"`. Mirrors the wasm `machineModelName`; see
/// [`de1_protocol::machine_model_name`].
#[uniffi::export]
pub fn machine_model_name(raw: u32) -> String {
    de1_protocol::machine_model_name(raw)
}

/// Whether the DE1 with raw `MachineModel` value `raw` has the cup-warmer plate
/// hardware. Mirrors the wasm `hasCupWarmer`; see [`de1_protocol::has_cup_warmer`].
#[uniffi::export]
pub fn has_cup_warmer(raw: u32) -> bool {
    de1_protocol::has_cup_warmer(raw)
}

// ── Visualizer bean / roaster wire converters ────────────────────────────
//
// Encode/decode the Crema bean & roaster shapes ⇄ the Visualizer coffee-bag /
// roaster wire bodies. The web shell reaches these via the wasm exports
// (`bean/visualizer-sync.ts`); Android still hand-assembles the wire today
// (`visualizer/WireShot.kt`, `VisualizerSync.kt`) and has drifted (it emits
// `roastLevel`/`roastedOn` as JsonNull). Exported here so that hand assembly
// can be deleted in favour of one shared converter — see issue 06's Comments
// for why the Android replacement is staged separately. Each mirrors the wasm
// export of the same name (native `i64` `now_unix_ms` instead of the wasm `f64`).

/// Encode a Crema `Bean` JSON → a Visualizer coffee-bag wire body JSON.
/// `roaster_remote_id` links the bag to an already-synced roaster. Mirrors the
/// wasm `beanToWire`; see [`de1_domain::bean_to_wire_json`].
///
/// # Errors
///
/// Returns a [`CremaError`] when `bean_json` can't be deserialised.
#[uniffi::export]
pub fn bean_to_wire(
    bean_json: String,
    roaster_remote_id: Option<String>,
) -> Result<String, CremaError> {
    de1_domain::bean_to_wire_json(&bean_json, roaster_remote_id.as_deref()).map_err(crema_err)
}

/// Decode a Visualizer coffee-bag wire body JSON → a Crema `Bean` JSON.
/// `local_roaster_id` binds it to a local roaster; `fallback_id` is used when
/// the wire body carries no id; `now_unix_ms` seeds timestamp defaults. Mirrors
/// the wasm `beanFromWire`; see [`de1_domain::bean_from_wire_json`].
///
/// # Errors
///
/// Returns a [`CremaError`] when `wire_json` can't be deserialised.
#[uniffi::export]
pub fn bean_from_wire(
    wire_json: String,
    local_roaster_id: Option<String>,
    fallback_id: String,
    now_unix_ms: i64,
) -> Result<String, CremaError> {
    de1_domain::bean_from_wire_json(
        &wire_json,
        local_roaster_id.as_deref(),
        &fallback_id,
        now_unix_ms,
    )
    .map_err(crema_err)
}

/// Encode a Crema `Roaster` JSON → a Visualizer roaster wire body JSON. Mirrors
/// the wasm `roasterToWire`; see [`de1_domain::roaster_to_wire_json`].
///
/// # Errors
///
/// Returns a [`CremaError`] when `roaster_json` can't be deserialised.
#[uniffi::export]
pub fn roaster_to_wire(roaster_json: String) -> Result<String, CremaError> {
    de1_domain::roaster_to_wire_json(&roaster_json).map_err(crema_err)
}

/// Decode a Visualizer roaster wire body JSON → a Crema `Roaster` JSON.
/// `fallback_id` is used when the wire body carries no id; `now_unix_ms` seeds
/// timestamp defaults. Mirrors the wasm `roasterFromWire`; see
/// [`de1_domain::roaster_from_wire_json`].
///
/// # Errors
///
/// Returns a [`CremaError`] when `wire_json` can't be deserialised.
#[uniffi::export]
pub fn roaster_from_wire(
    wire_json: String,
    fallback_id: String,
    now_unix_ms: i64,
) -> Result<String, CremaError> {
    de1_domain::roaster_from_wire_json(&wire_json, &fallback_id, now_unix_ms).map_err(crema_err)
}

/// Crema's 1..10 roast level → Visualizer's free-text band label (e.g. `"light"`),
/// or `None` when the level is unset. Mirrors the wasm `roastLevelToWire`; see
/// [`de1_domain::roast_level_to_wire`].
#[uniffi::export]
pub fn roast_level_to_wire(level: Option<f64>) -> Option<String> {
    de1_domain::roast_level_to_wire(level)
}

/// Visualizer's free-text band label → Crema's 1..10 level (in-band representative),
/// or `None` when unrecognised. Mirrors the wasm `roastLevelFromWire`; see
/// [`de1_domain::roast_level_from_wire`].
#[uniffi::export]
pub fn roast_level_from_wire(label: Option<String>) -> Option<i32> {
    de1_domain::roast_level_from_wire(label.as_deref()).map(i32::from)
}

/// Hard wire-protocol bounds for DE1 profile fields, as a single JSON
/// snapshot the Android profile editors parse once at module load so their
/// steppers / validators reach for the same firmware caps as the core (and
/// the web shell). Keys are snake_case, mirroring the constant names. Steam
/// targets can exceed brew temp — see `max_steam_temperature_c`. Mirrors the
/// wasm `profile_bounds_json`; both delegate to
/// [`de1_domain::profile_bounds::profile_bounds_json`].
#[uniffi::export]
pub fn profile_bounds_json() -> String {
    de1_domain::profile_bounds::profile_bounds_json()
}

/// djb2 base-36 fingerprint of an effective profile (library profile
/// merged with optional Quick-Controls overrides). Both payloads are
/// JSON strings; see [`de1_domain::profile_fingerprint`] for the
/// canonical implementation and cache-compatibility notes.
///
/// # Errors
///
/// Returns the JSON parse error string when either payload fails to
/// deserialise into the expected shape.
#[uniffi::export]
pub fn profile_fingerprint(
    profile_json: String,
    qc_json: Option<String>,
) -> Result<String, CremaError> {
    de1_domain::profile_fingerprint(&profile_json, qc_json.as_deref()).map_err(crema_err)
}

/// Sniff a first weight-notify packet for a known-scale signature.
/// Returns the BLE advertised-name prefix the connect path would
/// consume, or `None` when no signature matches. Used by the shell's
/// replay path for captures that pre-date the META prelude. See
/// [`de1_app::ScaleId::guess_from_first_weight_packet`].
#[uniffi::export]
pub fn guess_scale_from_first_weight_packet(bytes: Vec<u8>) -> Option<String> {
    de1_app::ScaleId::guess_from_first_weight_packet(&bytes).map(str::to_owned)
}

// NOTE (AND2 redesign): a `bookoo_gatt_uuids` accessor was removed here. Baking
// one specific scale into the core's public FFI surface was the wrong
// abstraction — the core identifies ~13 scales (`Scale::identify`) and is
// capability-driven, so the pre-connect scan filter belongs in a GENERIC
// `Scale::scan_uuids()` (exposed to the web via the wasm `scaleScanUuids`). It
// is intentionally NOT mirrored to FFI yet: the Android shell still subscribes
// only to the Bookoo characteristics (its `ScaleUuids` holds them as documented
// shell-side constants), and a UniFFI mirror would be a dead export until
// Android's multi-scale pass (AND6) makes it scan via `scale_scan_uuids()` and
// subscribe via the connected `scale_uuids()`.

/// Fold a JSON array of `src:"META"` payload objects into a merged
/// `ReplayMeta` JSON. See [`de1_domain::fold_meta_jsonl_json`] for
/// the canonical implementation.
///
/// # Errors
///
/// Returns the JSON parse error string when the outer payload isn't a
/// JSON array of objects or any inner payload is malformed.
#[uniffi::export]
pub fn fold_replay_meta_jsonl(payloads_json: String) -> Result<String, CremaError> {
    de1_domain::fold_meta_jsonl_json(&payloads_json).map_err(crema_err)
}

/// Build a Beanconqueror main JSON from a Crema envelope. See
/// [`de1_domain::crema_to_bc_main_json_from_envelope`].
///
/// # Errors
///
/// Returns the JSON parse error string when the envelope is malformed.
#[uniffi::export]
pub fn export_beanconqueror_main_json(
    envelope_json: String,
    now_unix_ms: i64,
) -> Result<String, CremaError> {
    de1_domain::crema_to_bc_main_json_from_envelope(&envelope_json, now_unix_ms).map_err(crema_err)
}

/// Build a Crema JSONL export from an envelope JSON. See
/// [`de1_domain::export_jsonl_from_json`].
///
/// # Errors
///
/// Returns the JSON parse error string when the envelope is
/// malformed.
#[uniffi::export]
pub fn export_crema_jsonl(
    envelope_json: String,
    exported_at_unix_ms: i64,
    crema_version: String,
) -> Result<String, CremaError> {
    de1_domain::export_jsonl_from_json(&envelope_json, exported_at_unix_ms, &crema_version)
        .map_err(crema_err)
}

/// Parse a Crema JSONL export into an `ImportPlan` JSON. See
/// [`de1_domain::import_jsonl_to_plan_json`].
///
/// # Errors
///
/// Currently always Ok; reserved for future schema-version errors.
#[uniffi::export]
pub fn import_crema_jsonl(text: String) -> Result<String, CremaError> {
    de1_domain::import_jsonl_to_plan_json(&text).map_err(crema_err)
}

/// Export a Rust-shape `StoredShot` JSON (the same shape
/// `import_v2_json_shot` returns) as a pretty-printed community-v2
/// `.shot.json` document — the Visualizer upload / archive payload.
/// Mirrors the wasm export of the same name so both shells emit
/// identical wire bytes.
///
/// # Errors
///
/// Returns the JSON parse error when `shot_json` is not a valid
/// `de1_domain::StoredShot`.
#[uniffi::export]
pub fn export_v2_json_shot(shot_json: String) -> Result<String, CremaError> {
    let shot: de1_domain::StoredShot = serde_json::from_str(&shot_json).map_err(crema_err)?;
    de1_domain::export_v2_json_shot(&shot).map_err(crema_err)
}

/// The Rust core's crate version (workspace-versioned) — the Settings →
/// About "Core" identity row. Mirrors the wasm `coreVersion`.
#[uniffi::export]
#[must_use]
pub fn core_version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// Reconcile a remote Visualizer pull against the local shot refs. Payload
/// `{"local": LocalShotRef[], "remote": WireShot[]}` → `ReconcileAction[]`
/// JSON (`kind: add | update | bind`). Mirrors the wasm `reconcileShots`
/// so both shells run the identical core planner. See
/// [`de1_domain::reconcile_shots_json`].
///
/// # Errors
///
/// The JSON error string when the payload can't be deserialised.
#[uniffi::export]
pub fn reconcile_shots(payload_json: String) -> Result<String, CremaError> {
    de1_domain::reconcile_shots_json(&payload_json).map_err(crema_err)
}

/// Convert a Visualizer `ShotSummary` + `ShotDetail` into Crema's `WireShot`
/// JSON. Payload `{"summary": {"id","clock","updated_at"}, "detail": …}`
/// (`clock` / `updated_at` unix sec — the core converts to ms). Mirrors the
/// wasm `wireShotFromDetail`.
///
/// # Errors
///
/// The JSON error string when the payload can't be deserialised.
#[uniffi::export]
pub fn wire_shot_from_detail(payload_json: String) -> Result<String, CremaError> {
    de1_domain::wire_shot_from_detail_json(&payload_json).map_err(crema_err)
}

/// Reconstruct per-sample telemetry (`TimedSample[]` JSON) from a Visualizer
/// `ShotDetail` JSON. Mirrors the wasm `samplesFromVisualizerDetail`.
///
/// # Errors
///
/// The JSON error string when `detail_json` can't be deserialised.
#[uniffi::export]
pub fn samples_from_visualizer_detail(detail_json: String) -> Result<String, CremaError> {
    de1_domain::samples_from_visualizer_detail_json(&detail_json).map_err(crema_err)
}

/// The shot de-dup signature — a pinned djb2 digest of
/// `(completed_at_unix_ms, duration_ms, profile_name, final_weight_g)`.
/// Mirrors the wasm `signatureForShot` so a Kotlin / JS call on the
/// same inputs emits the same hex string (Visualizer sync rides this
/// in `metadata.crema.signature` for cross-device matching).
#[uniffi::export]
pub fn signature_for_shot(
    completed_at_unix_ms: i64,
    duration_ms: i64,
    profile_name: Option<String>,
    final_weight_g: Option<f32>,
) -> String {
    de1_domain::signature_for_shot(
        completed_at_unix_ms,
        duration_ms,
        profile_name.as_deref(),
        final_weight_g,
    )
}

/// Import a Beanconqueror main export JSON. See
/// [`de1_domain::import_beanconqueror_json`].
///
/// # Errors
///
/// Returns the JSON parse error string when the payload isn't a
/// well-formed object.
#[uniffi::export]
pub fn import_beanconqueror_json(
    merged_main_json: String,
    now_unix_ms: i64,
) -> Result<String, CremaError> {
    de1_domain::import_beanconqueror_json(&merged_main_json, now_unix_ms).map_err(crema_err)
}

/// Derive the maintenance readout (filter capacity %, litres since
/// descale, hours since clean) from the persisted state. See
/// [`de1_domain::maintenance_readout_json`].
///
/// # Errors
///
/// Returns the JSON parse error string when `state_json` doesn't
/// deserialise into a `MaintenanceState`.
#[uniffi::export]
pub fn maintenance_readout(state_json: String, now_ms: i64) -> Result<String, CremaError> {
    de1_domain::maintenance_readout_json(&state_json, now_ms).map_err(crema_err)
}

/// Convert an editable `CremaProfile` JSON into the wire `Profile` JSON the DE1
/// upload / v2-export path speaks. The segment↔step adapter lives in the core
/// so every shell shares one mapping. See
/// [`de1_domain::crema_profile_to_wire_json`].
///
/// # Errors
///
/// Returns a [`CremaError`] when the JSON is malformed.
#[uniffi::export]
pub fn crema_profile_to_wire(crema_json: String) -> Result<String, CremaError> {
    de1_domain::crema_profile_to_wire_json(&crema_json).map_err(crema_err)
}

/// Convert a wire `Profile` JSON into an editable `CremaProfile` JSON (the
/// editor / library shape), synthesising roast / tags / defaults. See
/// [`de1_domain::crema_profile_from_wire_json`].
///
/// # Errors
///
/// Returns a [`CremaError`] when the JSON is malformed.
#[uniffi::export]
pub fn crema_profile_from_wire(wire_json: String) -> Result<String, CremaError> {
    de1_domain::crema_profile_from_wire_json(&wire_json).map_err(crema_err)
}

/// Build a fresh, empty custom `CremaProfile` (JSON) seeded from the user's
/// brew defaults (`{doseG, ratio, brewTempC, preinfusionS}` JSON). See
/// [`de1_domain::blank_crema_profile_json`].
///
/// # Errors
///
/// Returns a [`CremaError`] when the defaults JSON is malformed.
#[uniffi::export]
pub fn blank_crema_profile(defaults_json: String) -> Result<String, CremaError> {
    de1_domain::blank_crema_profile_json(&defaults_json).map_err(crema_err)
}

/// The out-of-box brew defaults (`{doseG, ratio, brewTempC, preinfusionS}` JSON)
/// the settings store seeds from — so the Android shell no longer hardcodes
/// `18` / `2.0` / `93` / `8`. Mirrors the wasm `defaultBrewDefaults`; see
/// [`de1_domain::default_brew_defaults_json`].
#[uniffi::export]
pub fn default_brew_defaults_json() -> String {
    de1_domain::default_brew_defaults_json()
}

/// The default segment list for a brand-new profile, as a JSON array of
/// `ProfileSegment`. See [`de1_domain::default_segments_json`].
#[uniffi::export]
pub fn default_profile_segments() -> String {
    de1_domain::default_segments_json()
}

/// Every built-in profile adapted into the editable `CremaProfile` shape, as a
/// JSON array — the library store's single read at startup. See
/// [`de1_domain::builtin_crema_profiles_json`].
#[uniffi::export]
pub fn builtin_crema_profiles() -> String {
    de1_domain::builtin_crema_profiles_json()
}

/// The DE1's fixed GATT layout (UUIDs) as JSON. The shell scans + subscribes
/// using these instead of hardcoding the map, so the web + Android shells share
/// one source. The core stays sans-IO — it only describes the layout. See
/// `de1_app::de1_uuids`.
#[uniffi::export]
pub fn de1_uuids() -> String {
    de1_app::de1_uuids_json()
}

/// The DE1 characteristic UUID a `Command.WriteCharacteristic`'s `WriteTarget`
/// (passed by its serde name, e.g. `"De1ProfileFrame"`) is written to, or
/// `None` for an unknown target. The one `WriteTarget` → UUID map. See
/// `de1_app::de1_write_target_uuid`.
#[uniffi::export]
pub fn de1_write_target_uuid(target: String) -> Option<String> {
    de1_app::de1_write_target_uuid_by_name(&target)
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
    /// core method; the core does not panic in practice, but if it ever does
    /// we recover the inner guard rather than aborting the host process — the
    /// Android shell would otherwise crash the app on a poisoned mutex.
    fn core(&self) -> MutexGuard<'_, CremaCore> {
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
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

    /// Disconnect the scale: reset the core's scale slice (the identified
    /// codec + every scale-derived reading) without touching user prefs or the
    /// shot / profile config (AND4). The shell calls this when the scale's BLE
    /// link drops. See [`de1_app::CremaCore::disconnect_scale`].
    pub fn disconnect_scale(&self) {
        self.core().disconnect_scale();
    }

    /// Enable or disable auto-tare on shot start.
    pub fn set_auto_tare(&self, enabled: bool) {
        self.core().set_auto_tare(enabled);
    }

    /// Enable or disable stop-at-weight (SAW).
    pub fn set_stop_on_weight(&self, enabled: bool) {
        self.core().set_stop_on_weight(enabled);
    }

    /// Per-shot kill switch for the weight target. Independent of
    /// `set_stop_on_weight`; either flag suppresses arming.
    pub fn set_weight_target_disabled(&self, disabled: bool) {
        self.core().set_weight_target_disabled(disabled);
    }

    /// Clear the running scale-derived peaks (peak weight + final
    /// weight). The Scale page's "Reset peak" button.
    pub fn reset_scale_peaks(&self) {
        self.core().reset_scale_peaks();
    }

    /// Set the active profile's recipe target weight, in grams.
    /// `None` (or a non-finite / non-positive value) means "no target."
    pub fn set_profile_target_weight(&self, grams: Option<f32>) {
        self.core().set_profile_target_weight(grams);
    }

    /// Set the per-shot dial override weight, in grams. `None` clears the
    /// override (falls back to the profile recipe target).
    pub fn set_shot_target_weight(&self, grams: Option<f32>) {
        self.core().set_shot_target_weight(grams);
    }

    /// Set the active profile's volume limit (SAV), in millilitres.
    /// `None` means "no limit."
    pub fn set_profile_volume_limit(&self, milliliters: Option<f32>) {
        self.core().set_profile_volume_limit(milliliters);
    }

    /// Set the global maximum shot duration, in seconds. `None` means
    /// "no max." Legacy default is 60 s.
    pub fn set_max_shot_duration(&self, seconds: Option<f32>) {
        self.core().set_max_shot_duration(seconds);
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

    /// Build a [`CoreOutput`] (JSON) whose command enables a connected
    /// Decent Scale's on-scale LCD in the unit the shell passes
    /// (`"grams"` / `"ounces"`, matching the [`de1_domain::WeightUnit`]
    /// serde form). Decent-Scale-only: empty for every other scale, and
    /// for an unconnected core. The shell must follow up with periodic
    /// [`decent_scale_heartbeat`](Self::decent_scale_heartbeat) writes once
    /// the LCD is enabled, since byte 5 of the enable packet arms the
    /// heartbeat requirement on the scale itself.
    ///
    /// Returns an error string (rather than a JSON `CoreOutput`) when
    /// `unit` is not one of the known wire strings — `"grams"` or
    /// `"ounces"`.
    pub fn enable_scale_lcd(&self, unit: &str) -> Result<String, CremaError> {
        let unit = de1_domain::WeightUnit::from_str_lower(unit)
            .ok_or_else(|| format!("unknown weight unit: {unit}"))?;
        self.core()
            .enable_scale_lcd(unit)
            .map(json)
            .map_err(crema_err)
    }

    /// Build a [`CoreOutput`] (JSON) whose commands disable the connected
    /// scale's on-scale LCD. Capability-driven.
    pub fn disable_scale_lcd(&self) -> Result<String, CremaError> {
        self.core()
            .disable_scale_lcd()
            .map(json)
            .map_err(crema_err)
    }

    /// Build a [`CoreOutput`] (JSON) whose command emits one keep-alive
    /// heartbeat to the connected scale. The shell schedules the clock
    /// (Decent Scale's interval is ~`HEARTBEAT_INTERVAL_MS` ms).
    /// Capability-driven — returns an error when the scale doesn't need
    /// a heartbeat.
    pub fn scale_heartbeat(&self) -> Result<String, CremaError> {
        self.core()
            .scale_heartbeat()
            .map(json)
            .map_err(crema_err)
    }

    /// Build a [`CoreOutput`] (JSON) whose commands power off the
    /// connected scale. Capability-driven — Decent / Eureka / Solo
    /// support it; every other scale errors with
    /// `UnsupportedOnHardware`.
    pub fn power_off_scale(&self) -> Result<String, CremaError> {
        self.core()
            .power_off_scale()
            .map(json)
            .map_err(crema_err)
    }

    /// Cache the user's chosen weight unit on the core so the LCD-enable
    /// auto-policy (triggered on the DE1's Idle entry) picks the right
    /// wire packet. `unit` is the same lowercase string the shell keeps
    /// in its settings (`"grams"` / `"ounces"`).
    pub fn set_weight_unit_pref(&self, unit: &str) -> Result<(), CremaError> {
        let unit = de1_domain::WeightUnit::from_str_lower(unit)
            .ok_or_else(|| format!("unknown weight unit: {unit}"))?;
        self.core().set_weight_unit_pref(unit);
        Ok(())
    }

    /// Build a [`CoreOutput`] (JSON) whose command fires a beep on the
    /// connected scale. Capability-driven — Eureka / Solo support it.
    pub fn scale_beep(&self) -> Result<String, CremaError> {
        self.core()
            .scale_beep()
            .map(json)
            .map_err(crema_err)
    }

    /// Build a [`CoreOutput`] (JSON) whose command sets the connected
    /// scale's display unit to grams. Capability-driven — Eureka / Solo
    /// / Difluid expose this. For scales whose unit lives in the
    /// LCD-enable bytes (Decent / Skale) the unit is set via
    /// `enable_scale_lcd`; for toggle-only scales (Hiroia) use
    /// `toggle_scale_unit`.
    pub fn set_scale_unit_grams(&self) -> Result<String, CremaError> {
        self.core()
            .set_scale_unit_grams()
            .map(json)
            .map_err(crema_err)
    }

    /// Build a [`CoreOutput`] (JSON) whose command toggles the connected
    /// scale's display unit. Capability-driven — Hiroia is the only
    /// scale that exposes a toggle today.
    pub fn toggle_scale_unit(&self) -> Result<String, CremaError> {
        self.core()
            .toggle_scale_unit()
            .map(json)
            .map_err(crema_err)
    }

    /// Toggle whether the connected scale should be powered off on
    /// machine Sleep entry. Off by default. Capability-driven — the
    /// reactive auto-policy fires `power_off_command` on Sleep when this
    /// is true and the scale exposes a power-off (Decent / Eureka /
    /// Solo today).
    pub fn set_auto_off_scale_on_sleep(&self, enabled: bool) {
        self.core().set_auto_off_scale_on_sleep(enabled);
    }

    /// Whether the connected scale is configured to power off on machine
    /// Sleep entry.
    pub fn auto_off_scale_on_sleep(&self) -> bool {
        self.core().auto_off_scale_on_sleep()
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
        // RS1: never panic at the boundary — a panic here poisons the ffi mutex
        // (and aborts the wasm sibling). Degrade to `None` on the unreachable
        // serialize error.
        self.core()
            .scale_capabilities()
            .and_then(|caps| serde_json::to_string(&caps).ok())
    }

    /// The connected scale's BLE service and characteristic UUIDs, as a
    /// JSON-encoded `ScaleUuids` object — or `None` when no scale is connected.
    ///
    /// The shell deserializes the JSON into the `typeshare`-generated
    /// `ScaleUuids` type to know which GATT characteristics to subscribe to.
    /// Returned as a JSON string, consistent with the rest of the bridge's
    /// JSON surface; wired exactly like [`scale_capabilities`](Self::scale_capabilities).
    pub fn scale_uuids(&self) -> Option<String> {
        // RS1: degrade to `None` on the (unreachable) serialize error, never panic.
        self.core()
            .scale_uuids()
            .and_then(|uuids| serde_json::to_string(&uuids).ok())
    }

    /// The firmware-update check result, as a JSON-encoded
    /// [`FirmwareUpdateStatus`](de1_app::FirmwareUpdateStatus). Pure read —
    /// no BLE traffic. Returns the `Unknown` variant until the DE1's `Version`
    /// characteristic has been observed via `on_notification`.
    pub fn firmware_update_status(&self) -> String {
        // RS1: fall back to `{"type":"Unknown"}` on the unreachable serialize
        // error instead of poisoning the mutex.
        serde_json::to_string(&self.core().firmware_update_status())
            .unwrap_or_else(|_| r#"{"type":"Unknown"}"#.to_string())
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
    /// `ScaleCapabilities::standby` `[min, max]` bounds. Capability-
    /// gated: the command is present only when the connected scale exposes a
    /// configurable auto-standby. Empty otherwise. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_standby(&self, minutes: u8) -> String {
        json(self.core().set_scale_standby(minutes))
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

    /// Set the steam flow rate, ml/s. Scaled `int(10 × rate)`; MMR
    /// `0x803828`, 1-byte.
    pub fn set_steam_flow(&self, ml_per_s: f32) -> String {
        json(self.core().set_steam_flow(ml_per_s))
    }

    /// Set the seconds of high-flow steam at the start of a steam cycle.
    /// MMR `0x80382C`, 4-byte. Wire value is `seconds × 100`. `f32`
    /// to preserve sub-second precision (legacy default 0.7s).
    pub fn set_steam_highflow_start(&self, seconds: f32) -> String {
        json(self.core().set_steam_highflow_start(seconds))
    }

    /// Set the group-head-control mode. MMR `0x803820`, 1-byte.
    pub fn set_ghc_mode(&self, mode: u8) -> String {
        json(self.core().set_ghc_mode(mode))
    }

    /// Set the hot-water flow rate, ml/s. Scaled `int(10 × rate)`; MMR
    /// `0x80384C`, 2-byte.
    pub fn set_hot_water_flow_rate(&self, ml_per_s: f32) -> String {
        json(self.core().set_hot_water_flow_rate(ml_per_s))
    }

    /// Set the flush flow rate, ml/s. Scaled `int(10 × rate)`; MMR
    /// `0x803840`, 2-byte.
    pub fn set_flush_flow_rate(&self, ml_per_s: f32) -> String {
        json(self.core().set_flush_flow_rate(ml_per_s))
    }

    /// Set the flush water target temperature, °C. Wire value is `°C × 10`;
    /// MMR `0x803844`, 4-byte.
    pub fn set_flush_temp(&self, temp_c: f32) -> String {
        json(self.core().set_flush_temp(temp_c))
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
    /// must wrap this in a typed-to-confirm modal (`MainsConfirmModal`).
    /// MMR `0x803834`, 4-byte. Wire value is `volts + 1000`
    /// (user-committed marker).
    ///
    /// `volts` must be `120` or `230`; the core rejects any other value.
    /// Returns `Result<String, CremaError>` so UniFFI surfaces a thrown
    /// exception on bad inputs.
    ///
    /// # Errors
    ///
    /// Returns the [`AppError`](de1_app::AppError) display string when
    /// `volts` is out of `{120, 230}`.
    pub fn set_heater_voltage(&self, volts: u8) -> Result<String, CremaError> {
        self.core()
            .set_heater_voltage(volts)
            .map(json)
            .map_err(crema_err)
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

    /// Set the hot-water phase-1 flow rate (legacy `heater_tweaks`
    /// `phase_1_flow_rate`). Scaled `int(10 × rate)`; MMR `0x803810`,
    /// 4-byte LE.
    pub fn set_phase_1_flow_rate(&self, rate_ml_per_s: f32) -> String {
        json(self.core().set_phase_1_flow_rate(rate_ml_per_s))
    }

    /// Set the hot-water phase-2 flow rate (legacy `heater_tweaks`
    /// `phase_2_flow_rate`). Scaled `int(10 × rate)`; MMR `0x803814`,
    /// 4-byte LE.
    pub fn set_phase_2_flow_rate(&self, rate_ml_per_s: f32) -> String {
        json(self.core().set_phase_2_flow_rate(rate_ml_per_s))
    }

    /// Set the hot-water boiler idle target temperature, °C (legacy
    /// `heater_tweaks` `hot_water_idle_temp`). MMR `0x803818`, 4-byte LE.
    /// Wire value is `°C × 10` (scaled by reaprime's `writeScale: 10.0`).
    pub fn set_hot_water_idle_temp(&self, temp_c: f32) -> String {
        json(self.core().set_hot_water_idle_temp(temp_c))
    }

    /// Set the espresso group warmup timeout (legacy `heater_tweaks`
    /// `espresso_warmup_timeout`). MMR `0x803838`, 4-byte LE. `seconds`
    /// is scaled to deciseconds on the wire (`int(10 × seconds)`).
    pub fn set_espresso_warmup_timeout(&self, seconds: f32) -> String {
        // 1-hour domain ceiling (ms), replacing the old `u64::MAX as f32` clamp.
        // Far above any real DE1 warmup; it only bounds the conversion below.
        const MAX_WARMUP_TIMEOUT_MS: f32 = 60.0 * 60.0 * 1000.0;
        let dur = if seconds.is_finite() && seconds > 0.0 {
            // Round to whole ms (compensates f32 error, e.g. 5.1 s → 5100 ms),
            // clamp to the ceiling, then widen to f64: `from_secs_f64` rounds to
            // the nearest nanosecond and recovers the integer ms — no float→int
            // cast, hence no `cast_*` allow.
            let ms = (seconds * 1000.0).round().clamp(0.0, MAX_WARMUP_TIMEOUT_MS);
            std::time::Duration::from_secs_f64(f64::from(ms) / 1000.0)
        } else {
            std::time::Duration::ZERO
        };
        json(self.core().set_espresso_warmup_timeout(dur))
    }

    /// Set the steam two-tap-stop (legacy `heater_tweaks`
    /// `steam_two_tap_stop`; reaprime `steamPurgeMode`). MMR `0x803850`,
    /// 4-byte LE int. `0` disables, `1` enables.
    pub fn set_steam_two_tap_stop(&self, value: u8) -> String {
        json(self.core().set_steam_two_tap_stop(value))
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
                        // Any other `DomainError` shouldn't reach this path;
                        // surface its `Display` rather than coercing to `Empty`.
                        e => ProfileUploadFailure::Internal {
                            message: e.to_string(),
                        },
                    },
                    // Other AppError variants shouldn't reach this path either;
                    // surface the underlying cause.
                    e => ProfileUploadFailure::Internal {
                        message: e.to_string(),
                    },
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

    /// The effective AC mains frequency the volume integrator uses, Hz.
    /// `None` until either the user pins it via
    /// [`set_line_frequency_override`](Self::set_line_frequency_override)
    /// or the auto-detector locks (1+ second of telemetry into a shot).
    pub fn line_frequency_hz(&self) -> Option<f32> {
        self.core().line_frequency_hz()
    }

    /// Pin the AC mains frequency. Accepts only `0.0` (auto), `50.0`, or
    /// `60.0`; any other value is rejected (the UniFFI surface throws).
    /// The shell gates `50` / `60` selections behind `MainsConfirmModal`
    /// for symmetric UX with the heater-voltage write, though Hz
    /// mis-calibration only mis-times the AC-period integrator (no
    /// hardware damage).
    ///
    /// # Errors
    ///
    /// Returns an error string when `hz` is not `0.0`, `50.0`, or `60.0`.
    pub fn set_line_frequency_override(&self, hz: f32) -> Result<(), CremaError> {
        // Round, don't truncate: a stray `50.9` must reject (rounds to 51), not
        // silently pass as 50 the way a bare `hz as i32` truncation would.
        let override_hz = match hz.round() as i32 {
            0 => None,
            50 => Some(50.0),
            60 => Some(60.0),
            _ => {
                return Err(CremaError::Failed(format!(
                    "invalid argument for hz: {hz} (must be 0, 50, or 60)"
                )));
            }
        };
        self.core().set_line_frequency_override(override_hz);
        Ok(())
    }

    /// Parse a legacy de1app `.shot` (Tcl-dict) history file. Returns
    /// the resulting `StoredShot` as a JSON string the Android shell
    /// can deserialize into its Room history store.
    /// Stateless — `&self` is required for the UniFFI instance-method
    /// ABI but the import does not touch the core.
    pub fn import_legacy_tcl_shot(&self, content: String) -> Result<String, CremaError> {
        de1_domain::import_legacy_tcl_shot(&content)
            .map_err(crema_err)
            .and_then(|shot| shot.to_json().map_err(crema_err))
    }

    /// Parse a modern de1app v2 `.shot.json` history file. Same return
    /// convention as `import_legacy_tcl_shot`.
    pub fn import_v2_json_shot(&self, content: String) -> Result<String, CremaError> {
        de1_domain::import_v2_json_shot(&content)
            .map_err(crema_err)
            .and_then(|shot| shot.to_json().map_err(crema_err))
    }

    /// Parse a community-v2 `.json` profile file. Returns the parsed
    /// `Profile` as JSON.
    pub fn import_v2_json_profile(&self, content: String) -> Result<String, CremaError> {
        de1_domain::import_v2_json(&content)
            .map_err(crema_err)
            .and_then(|p| serde_json::to_string(&p).map_err(crema_err))
    }

    /// Parse a legacy de1app `.tcl` profile file.
    pub fn import_legacy_tcl_profile(&self, content: String) -> Result<String, CremaError> {
        de1_domain::import_legacy_tcl(&content)
            .map_err(crema_err)
            .and_then(|p| serde_json::to_string(&p).map_err(crema_err))
    }

    /// Export a Crema `Profile` (as JSON) as a community-v2 `.json`
    /// document. Pure encoder.
    pub fn export_v2_json_profile(&self, profile_json: String) -> Result<String, CremaError> {
        let profile: de1_domain::Profile =
            serde_json::from_str(&profile_json).map_err(crema_err)?;
        de1_domain::export_v2_json(&profile).map_err(crema_err)
    }

    /// Parse a legacy de1app `settings.tdb` file. Returns the parsed
    /// settings as a JSON string.
    pub fn import_settings_tdb(&self, content: String) -> Result<String, CremaError> {
        de1_domain::import_settings_tdb(&content)
            .map_err(crema_err)
            .and_then(|s| serde_json::to_string(&s).map_err(crema_err))
    }
}

/// Serialize a [`CoreOutput`] to JSON for the shell.
///
/// `CoreOutput` is a flat structure of enums, numbers, strings and vectors, so
/// serialization is infallible in practice.
///
/// RS1: a panic here poisons the `Mutex` guarding the core — every later call
/// then fails on the poisoned lock. The only realistic failure is a non-finite
/// `f32` in a telemetry event (today's parsers never emit one, but the boundary
/// must not crash on it). Fall back to an empty `CoreOutput` so the shell sees
/// no events/commands for that tick — degraded, not dead.
fn json(output: CoreOutput) -> String {
    output
        .to_json()
        .unwrap_or_else(|_| r#"{"events":[],"commands":[]}"#.to_string())
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
    fn set_scale_standby_produces_a_write_scale_command_for_a_capable_scale() {
        let bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let json = bridge.set_scale_standby(15);
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
            bridge.set_scale_standby(15),
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

    // ── Bean / roaster sync surface (issues 06 + 10), exercised with fake data ──
    //
    // Device-independent proof that the exported converters preserve a bean's
    // `roastLevel` / `roastedOn` — the exact fields the Android *shot* wire emits
    // as null today (issue 06). So that divergence is purely Android not yet
    // plumbing a full `Bean` to the wire site (the StoredShot only carries a flat
    // name), not a gap in the shared core converters.

    /// A realistic library bean, in the shell's persisted shape (the seed data).
    const FAKE_BEAN: &str = r#"{"id":"bean:test","name":"Monarch","roasterId":"roaster:test",
        "roastedOn":"2026-05-20","roastLevel":7,"decaf":false,
        "origin":{"country":"Colombia","variety":"Castillo","processing":"Washed"},
        "bagSize":340.0,"remaining":300.0,"qualityScore":"","tastingNotes":"Cocoa, cherry",
        "rating":0,"notes":"","favourite":false,"grinder":"Niche","grinderSetting":"18",
        "metadata":{},"createdAt":1779238864574,"updatedAt":1779325264574}"#;

    #[test]
    fn bean_wire_round_trip_preserves_roast_level_and_date() {
        // Bean → Visualizer coffee-bag wire → Bean.
        let wire = bean_to_wire(FAKE_BEAN.to_owned(), Some("remote-roaster-9".to_owned()))
            .expect("encode to wire");
        let back = bean_from_wire(
            wire,
            Some("roaster:test".to_owned()),
            "bean:fallback".to_owned(),
            1_790_000_000_000,
        )
        .expect("decode from wire");
        let b: serde_json::Value = serde_json::from_str(&back).unwrap();
        // The roast data the Android shot wire drops survives the round-trip.
        assert_eq!(b["name"], "Monarch");
        assert_eq!(b["roastLevel"], 7);
        assert_eq!(b["roastedOn"], "2026-05-20");
    }

    #[test]
    fn roaster_wire_round_trip_preserves_name() {
        let roaster = r#"{"id":"roaster:test","name":"Onyx Coffee Lab",
            "website":"https://onyx.com","city":"Rogers","country":"USA","notes":"",
            "metadata":{},"createdAt":1770685264574,"updatedAt":1770685264574}"#;
        let wire = roaster_to_wire(roaster.to_owned()).expect("encode");
        let back = roaster_from_wire(wire, "roaster:fallback".to_owned(), 1_790_000_000_000)
            .expect("decode");
        let r: serde_json::Value = serde_json::from_str(&back).unwrap();
        assert_eq!(r["name"], "Onyx Coffee Lab");
    }

    #[test]
    fn coerce_bean_normalises_a_messy_row_and_rejects_a_keyless_one() {
        // A row with a wrong-typed field + junk still coerces, keeping id + name.
        let raw = r#"{"id":"bean:x","name":"Mystery","roastLevel":"not-a-number","junk":42}"#;
        let coerced = coerce_bean(raw.to_owned(), 1_790_000_000_000).expect("coerces");
        let c: serde_json::Value = serde_json::from_str(&coerced).unwrap();
        assert_eq!(c["id"], "bean:x");
        assert_eq!(c["name"], "Mystery");
        // A row missing a string id / name is not a bean.
        assert!(coerce_bean(r#"{"foo":1}"#.to_owned(), 1_790_000_000_000).is_none());
    }

    #[test]
    fn roast_level_wire_maps_band_labels_both_ways() {
        assert!(roast_level_to_wire(Some(7.0)).is_some());
        assert!(roast_level_to_wire(None).is_none());
        assert!(roast_level_from_wire(roast_level_to_wire(Some(2.0))).is_some());
        assert!(roast_level_from_wire(None).is_none());
    }
}

//! # de1-wasm
//!
//! The wasm-bindgen bridge: exposes [`de1_app::CremaCore`] to the web shell.
//! Like `de1-ffi`, it is a thin wrapper — it serializes the rich
//! [`CoreOutput`](de1_app::CoreOutput) to JSON ("Option S" encoding —
//! every CoreOutput crosses as one JSON string) and marshals simple
//! typed values for inputs.
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
/// water volume in ml. Pure helper — does no machine I/O. Exposed here so
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

/// Convert a volume in ml to US fluid ounces (`ml × 0.033814`).
#[wasm_bindgen]
pub fn ml_to_fl_oz(ml: f32) -> f32 {
    de1_domain::ml_to_fl_oz(ml)
}

/// Convert a volume in US fluid ounces to ml (`fl oz × 29.5735`).
#[wasm_bindgen]
pub fn fl_oz_to_ml(fl_oz: f32) -> f32 {
    de1_domain::fl_oz_to_ml(fl_oz)
}

// ─── Visualizer-sync helpers (audit #4) ─────────────────────────────────
//
// Pure de-dup signatures + the reconcile action planner, ported from
// the web shell's `shot-sync-signatures.ts` into `de1_domain::visualizer_sync`
// so every shell (web today, Android tomorrow) shares one algorithm
// and one set of byte-identical hashes. The bridge marshals the
// `reconcile_shots` `(local, remote)` pair as JSON, matching the
// rest of the JSON-string FFI surface (`import_*_json_shot`, etc).

/// djb2 hex signature for a shot — `(completedAt, duration,
/// profileName, finalWeight)`. Pure helper; mirrors the TS
/// `signatureForShot` so a JS / Rust call on the same inputs emits
/// the same hex string. See `de1_domain::signature_for_shot`.
#[wasm_bindgen(js_name = signatureForShot)]
pub fn signature_for_shot(
    completed_at: f64,
    duration: f64,
    profile_name: Option<String>,
    final_weight: Option<f32>,
) -> String {
    // JS hands us integer-valued `f64`s for the unix-ms timestamps (a
    // plain `i64` doesn't cross the wasm-bindgen ABI cleanly); `f64_to_ms`
    // truncates defensively, non-finite → 0 rather than a panic.
    de1_domain::signature_for_shot(
        f64_to_ms(completed_at),
        f64_to_ms(duration),
        profile_name.as_deref(),
        final_weight,
    )
}

/// djb2 hex signature for a bean — `(name, roasterName, roastedOn)`.
/// See `de1_domain::signature_for_bean`.
#[wasm_bindgen(js_name = signatureForBean)]
pub fn signature_for_bean(
    name: String,
    roaster_name: Option<String>,
    roasted_on: Option<String>,
) -> String {
    de1_domain::signature_for_bean(&name, roaster_name.as_deref(), roasted_on.as_deref())
}

/// djb2 hex signature for a roaster — normalised name. See
/// `de1_domain::signature_for_roaster`.
#[wasm_bindgen(js_name = signatureForRoaster)]
pub fn signature_for_roaster(name: String) -> String {
    de1_domain::signature_for_roaster(&name)
}

/// djb2 base-36 fingerprint of an "effective profile" — library
/// `CremaProfile` JSON merged with optional Quick-Controls overrides
/// JSON. Drives the connect-time + shot-start "do we already have these
/// bytes loaded?" decision. See `de1_domain::profile_fingerprint`.
///
/// # Errors
///
/// Returns the JSON parse error string when either payload fails to
/// deserialise into the expected shape.
#[wasm_bindgen(js_name = profileFingerprint)]
pub fn profile_fingerprint(profile_json: &str, qc_json: Option<String>) -> Result<String, String> {
    de1_domain::profile_fingerprint(profile_json, qc_json.as_deref())
}

/// Convert an editable `CremaProfile` JSON into the wire `Profile` JSON the DE1
/// upload / v2-export path speaks. The segment↔step adapter lives in the core
/// (`de1_domain::crema_profile`) so every shell shares one mapping — replacing
/// the web's former TypeScript `toCoreProfile`.
///
/// # Errors
///
/// Returns the JSON error string when `crema_json` is malformed.
#[wasm_bindgen(js_name = cremaProfileToWire)]
pub fn crema_profile_to_wire(crema_json: &str) -> Result<String, String> {
    de1_domain::crema_profile_to_wire_json(crema_json)
}

/// Convert a wire `Profile` JSON into an editable `CremaProfile` JSON (the
/// editor / library shape), synthesising roast / tags / defaults — replacing
/// the web's former TypeScript `fromCoreProfile`. See
/// `de1_domain::crema_profile_from_wire_json`.
///
/// # Errors
///
/// Returns the JSON error string when `wire_json` is malformed.
#[wasm_bindgen(js_name = cremaProfileFromWire)]
pub fn crema_profile_from_wire(wire_json: &str) -> Result<String, String> {
    de1_domain::crema_profile_from_wire_json(wire_json)
}

/// Build a fresh, empty custom `CremaProfile` (JSON) seeded from the user's
/// brew defaults (`{doseG, ratio, brewTempC, preinfusionS}` JSON) — replacing
/// the web's former TypeScript `blankProfile`. See
/// `de1_domain::blank_crema_profile_json`.
///
/// # Errors
///
/// Returns the JSON error string when `defaults_json` is malformed.
#[wasm_bindgen(js_name = blankCremaProfile)]
pub fn blank_crema_profile(defaults_json: &str) -> Result<String, String> {
    de1_domain::blank_crema_profile_json(defaults_json)
}

/// The out-of-box brew defaults (`{doseG, ratio, brewTempC, preinfusionS}` JSON)
/// the settings store seeds from — so the web shell no longer hardcodes
/// `18` / `2.0` / `93` / `8`. See `de1_domain::default_brew_defaults_json`.
#[wasm_bindgen(js_name = defaultBrewDefaults)]
pub fn default_brew_defaults() -> String {
    de1_domain::default_brew_defaults_json()
}

/// The default segment list for a brand-new profile, as a JSON array of
/// `ProfileSegment` — replacing the web's former TypeScript `defaultSegments`.
/// See `de1_domain::default_segments_json`.
#[wasm_bindgen(js_name = defaultProfileSegments)]
pub fn default_profile_segments() -> String {
    de1_domain::default_segments_json()
}

/// Every built-in profile adapted into the editable `CremaProfile` shape, as a
/// JSON array — the library store's single read at startup. See
/// `de1_domain::builtin_crema_profiles_json`.
#[wasm_bindgen(js_name = builtinCremaProfiles)]
pub fn builtin_crema_profiles() -> String {
    de1_domain::builtin_crema_profiles_json()
}

/// The DE1's fixed GATT layout (UUIDs) as JSON — the shell scans + subscribes
/// using these instead of hardcoding the map. The core stays sans-IO; it only
/// describes the layout. See `de1_app::de1_uuids`.
#[wasm_bindgen(js_name = de1Uuids)]
pub fn de1_uuids() -> String {
    de1_app::de1_uuids_json()
}

/// The DE1 characteristic UUID a `WriteTarget` (by its serde name) is written
/// to, or `None` for an unknown target. The one `WriteTarget` → UUID map. See
/// `de1_app::de1_write_target_uuid`.
#[wasm_bindgen(js_name = de1WriteTargetUuid)]
pub fn de1_write_target_uuid(target: &str) -> Option<String> {
    de1_app::de1_write_target_uuid_by_name(target)
}

/// Reconcile a remote Visualizer pull against the local history.
/// `payload` is the JSON of `{"local": StoredShot[], "remote":
/// WireShot[]}`; the planner only reads the slim subset of
/// `StoredShot` (id, completedAt, duration, profileName, finalWeight,
/// visualizerId, deletedAt) so extra fields are ignored. The result
/// is a JSON array of `ReconcileAction` objects (`{"kind":"add"|
/// "update"|"bind", ...}`), preserving remote order. See
/// `de1_domain::reconcile_shots` for the algorithm.
///
/// # Errors
///
/// Returns the JSON parse error string when `payload` cannot be
/// deserialised into the expected shape.
#[wasm_bindgen(js_name = reconcileShots)]
pub fn reconcile_shots(payload: &str) -> Result<String, String> {
    de1_domain::reconcile_shots_json(payload)
}

/// Reconcile a remote roaster pull against the local directory (CORE4).
/// `payload` is `{"local": Roaster[], "remote": RoasterWire[]}`; the result
/// is a JSON array of `RoasterReconcileAction` (`{"kind":"update"|"bind"|
/// "add", ...}`). See `de1_domain::reconcile_roasters`.
///
/// # Errors
/// The JSON error string when `payload` can't be deserialised.
#[wasm_bindgen(js_name = reconcileRoasters)]
pub fn reconcile_roasters(payload: &str) -> Result<String, String> {
    de1_domain::reconcile_roasters_json(payload)
}

/// Reconcile a remote bean pull against the local library (CORE4). `payload`
/// is `{"local": Bean[], "remote": Bean[], "roasterNames": {id: name}}` (the
/// remote beans are already decoded via `beanFromWire`); the result is a JSON
/// array of `BeanReconcileAction`. See `de1_domain::reconcile_beans`.
///
/// # Errors
/// The JSON error string when `payload` can't be deserialised.
#[wasm_bindgen(js_name = reconcileBeans)]
pub fn reconcile_beans(payload: &str) -> Result<String, String> {
    de1_domain::reconcile_beans_json(payload)
}

// ─── Visualizer wire converters (CORE1) ─────────────────────────────────
//
// The bean / roaster ⇄ Visualizer-wire converters + the `ShotDetail`
// parsers, ported from the web shell into `de1_domain::visualizer_wire` so
// every shell shares one byte-identical mapping. JSON-in / JSON-out at the
// boundary; the shell hands `Date.now()` (`now_ms`) + a freshly-minted
// fallback id + the resolved local roaster id in. Siblings of the
// `reconcile_shots` planner above — wasm-only today, like that planner,
// because Android has no bean library / Visualizer sync surface yet (AND6,
// deferred). The pure fns + JSON facades live in `de1_domain`, so a future
// UniFFI mirror is a one-liner when the Android surface grows.

/// Encode a Crema `Bean` JSON → the Visualizer coffee-bag wire body JSON.
/// `roaster_remote_id` is the bag's roaster's Visualizer id. See
/// `de1_domain::bean_to_wire`.
///
/// # Errors
/// The JSON error string when `bean_json` can't be deserialised.
#[wasm_bindgen(js_name = beanToWire)]
pub fn bean_to_wire(bean_json: &str, roaster_remote_id: Option<String>) -> Result<String, String> {
    de1_domain::bean_to_wire_json(bean_json, roaster_remote_id.as_deref())
}

/// Decode a Visualizer coffee-bag wire body JSON → a Crema `Bean` JSON.
/// `local_roaster_id` is the shell-resolved local roaster id; `fallback_id`
/// a freshly-minted `bean:<uuid>` used only when no `crema_id` rides in the
/// metadata; `now_ms` seeds `createdAt` + the `updatedAt` fallback. See
/// `de1_domain::bean_from_wire`.
///
/// # Errors
/// The JSON error string when `wire_json` can't be deserialised.
#[wasm_bindgen(js_name = beanFromWire)]
pub fn bean_from_wire(
    wire_json: &str,
    local_roaster_id: Option<String>,
    fallback_id: String,
    now_ms: f64,
) -> Result<String, String> {
    de1_domain::bean_from_wire_json(
        wire_json,
        local_roaster_id.as_deref(),
        &fallback_id,
        f64_to_ms(now_ms),
    )
}

/// Encode a Crema `Roaster` JSON → the Visualizer roaster wire body JSON.
/// See `de1_domain::roaster_to_wire`.
///
/// # Errors
/// The JSON error string when `roaster_json` can't be deserialised.
#[wasm_bindgen(js_name = roasterToWire)]
pub fn roaster_to_wire(roaster_json: &str) -> Result<String, String> {
    de1_domain::roaster_to_wire_json(roaster_json)
}

/// Decode a Visualizer roaster wire body JSON → a Crema `Roaster` JSON. See
/// `de1_domain::roaster_from_wire`.
///
/// # Errors
/// The JSON error string when `wire_json` can't be deserialised.
#[wasm_bindgen(js_name = roasterFromWire)]
pub fn roaster_from_wire(
    wire_json: &str,
    fallback_id: String,
    now_ms: f64,
) -> Result<String, String> {
    de1_domain::roaster_from_wire_json(wire_json, &fallback_id, f64_to_ms(now_ms))
}

/// Crema's 1..10 roast level → Visualizer's free-text band, or `None`. See
/// `de1_domain::roast_level_to_wire`.
#[wasm_bindgen(js_name = roastLevelToWire)]
#[must_use]
pub fn roast_level_to_wire(level: Option<f64>) -> Option<String> {
    de1_domain::roast_level_to_wire(level)
}

/// Visualizer's free-text band → Crema's 1..10 level (in-band rep), or
/// `None`. See `de1_domain::roast_level_from_wire`.
#[wasm_bindgen(js_name = roastLevelFromWire)]
#[must_use]
pub fn roast_level_from_wire(label: Option<String>) -> Option<i32> {
    de1_domain::roast_level_from_wire(label.as_deref()).map(i32::from)
}

/// Convert a Visualizer `ShotSummary` + `ShotDetail` into Crema's `WireShot`
/// JSON. `payload` is `{"summary": {"id", "clock", "updated_at"}, "detail":
/// <ShotDetail>}` (`clock` / `updated_at` unix sec). See
/// `de1_domain::wire_shot_from_detail`.
///
/// # Errors
/// The JSON error string when `payload` can't be deserialised.
#[wasm_bindgen(js_name = wireShotFromDetail)]
pub fn wire_shot_from_detail(payload: &str) -> Result<String, String> {
    de1_domain::wire_shot_from_detail_json(payload)
}

/// Reconstruct Crema's per-sample telemetry (`TimedSample[]` JSON) from a
/// Visualizer `ShotDetail` JSON. See
/// `de1_domain::samples_from_visualizer_detail`.
///
/// # Errors
/// The JSON error string when `detail_json` can't be deserialised.
#[wasm_bindgen(js_name = samplesFromVisualizerDetail)]
pub fn samples_from_visualizer_detail(detail_json: &str) -> Result<String, String> {
    de1_domain::samples_from_visualizer_detail_json(detail_json)
}

/// The core crate version as a borrowed static; [`core_version`] owns a copy
/// only at the binding boundary (wasm-bindgen can't return a borrowed `&str`).
const CORE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// The Rust core's crate version (workspace-versioned) — the Settings →
/// About "Core" identity row. Mirrors the FFI `core_version`.
#[wasm_bindgen(js_name = coreVersion)]
#[must_use]
pub fn core_version() -> String {
    CORE_VERSION.to_owned()
}

/// JS hands integer-valued `f64`s for unix-ms timestamps (a plain `i64`
/// doesn't cross the wasm-bindgen ABI cleanly); truncate defensively,
/// non-finite → 0 rather than a panic. Mirrors the `signatureForShot` guard.
#[allow(clippy::cast_possible_truncation)]
fn f64_to_ms(now_ms: f64) -> i64 {
    if now_ms.is_finite() { now_ms as i64 } else { 0 }
}

/// Coerce a stored bean row JSON → a normalised `Bean` JSON (CORE2), or
/// `null` when the row isn't an object or lacks a string `id` / `name`.
/// `now_ms` seeds the timestamp defaults. Tolerant: wrong-typed fields are
/// skipped, not rejected. See `de1_domain::coerce_bean`.
#[wasm_bindgen(js_name = coerceBean)]
#[must_use]
pub fn coerce_bean(raw_json: &str, now_ms: f64) -> Option<String> {
    de1_domain::coerce_bean_json(raw_json, f64_to_ms(now_ms))
}

/// Coerce a stored roaster row JSON → a normalised `Roaster` JSON (CORE2), or
/// `null`. See `de1_domain::coerce_roaster`.
#[wasm_bindgen(js_name = coerceRoaster)]
#[must_use]
pub fn coerce_roaster(raw_json: &str, now_ms: f64) -> Option<String> {
    de1_domain::coerce_roaster_json(raw_json, f64_to_ms(now_ms))
}

/// Classify a profile's roast suitability from its `title` + `notes` (CORE3) →
/// `"light"` / `"medium"` / `"dark"`, or `null` when no roast is clearly known.
/// See `de1_domain::roast_from_profile`.
#[wasm_bindgen(js_name = roastFromProfile)]
#[must_use]
pub fn roast_from_profile(title: &str, notes: &str) -> Option<String> {
    de1_domain::roast_from_profile(title, notes).map(str::to_owned)
}

/// The Visualizer-call retry policy (CORE5): whether an error tagged `tag`
/// (with `status` for the HTTP case) is worth a time-based retry. The shell
/// marshals its tagged error into `(tag, status)`. See
/// `de1_domain::VisualizerCallError::is_recoverable`.
#[wasm_bindgen(js_name = isRecoverable)]
#[must_use]
pub fn is_recoverable(tag: &str, status: Option<u32>) -> bool {
    de1_domain::is_recoverable(tag, status.and_then(|s| u16::try_from(s).ok()))
}

/// Human-readable name for a raw `MachineModel` MMR value (e.g. `1` →
/// `"DE1"`, `4` → `"DE1XL"`). Values past the table are reported as
/// `"model N"`. Mirrors [`de1_protocol::machine_model_name`].
#[wasm_bindgen(js_name = machineModelName)]
#[must_use]
pub fn machine_model_name(raw: u32) -> String {
    de1_protocol::machine_model_name(raw)
}

/// Whether the DE1 with raw `MachineModel` value `raw` has the Bengle
/// cup-warmer plate hardware. Mirrors [`de1_protocol::has_cup_warmer`].
#[wasm_bindgen(js_name = hasCupWarmer)]
#[must_use]
pub fn has_cup_warmer(raw: u32) -> bool {
    de1_protocol::has_cup_warmer(raw)
}

/// Human-readable English message for an error substate name (the
/// wire-side variant the FFI emits, e.g. `"ErrorTSensor"`). Returns
/// `null` for non-error substates and unknown names. Mirrors
/// [`de1_protocol::SubState::error_message_for_name`].
#[wasm_bindgen(js_name = subStateErrorMessage)]
#[must_use]
pub fn sub_state_error_message(name: &str) -> Option<String> {
    de1_protocol::SubState::error_message_for_name(name).map(str::to_owned)
}

/// The **generic** pre-connect BLE scan filter set across ALL supported scales
/// — the service-UUID union + the advertised-name-prefix union — as JSON
/// `{"service_uuids":[…],"name_prefixes":[…]}`. The core owns
/// `Scale::identify`, so it owns the scan filters; a shell lists
/// `name_prefixes` in its scan filter + `service_uuids` in a Web Bluetooth
/// `optionalServices`, then learns the connected scale's per-model
/// characteristics from `CremaBridge::scale_uuids()` post-connect. This keeps
/// the scan generic instead of each shell hardcoding one scale. A free fn (no
/// bridge instance) so the gesture-bound BLE-scan path reads it synchronously.
/// See `de1_scale::Scale::scan_uuids`.
#[wasm_bindgen(js_name = scaleScanUuids)]
#[must_use]
pub fn scale_scan_uuids() -> String {
    // `to_string` of two `Vec<&str>` is infallible; fall back rather than
    // panic at the boundary (RS1).
    serde_json::to_string(&de1_app::ScaleId::scan_uuids())
        .unwrap_or_else(|_| r#"{"service_uuids":[],"name_prefixes":[]}"#.to_owned())
}

/// Sniff a first weight-notify packet for a known-scale signature.
/// Returns the BLE advertised-name prefix the connect path would
/// consume, or `None` when no signature matches. Today only the
/// Bookoo's `03 0b` header is recognised; see
/// `de1_scale::Scale::guess_from_first_weight_packet`. Used by the
/// shell's replay path for captures that pre-date the META prelude.
#[wasm_bindgen(js_name = guessScaleFromFirstWeightPacket)]
pub fn guess_scale_from_first_weight_packet(bytes: &[u8]) -> Option<String> {
    de1_app::ScaleId::guess_from_first_weight_packet(bytes).map(str::to_owned)
}

/// Fold a JSON array of `src:"META"` payload objects into a merged
/// `ReplayMeta` JSON. Later entries override earlier ones; each
/// payload is read field-by-field with type guards. See
/// `de1_domain::fold_meta_jsonl_json`.
///
/// # Errors
///
/// Returns the JSON parse error string when the outer payload isn't a
/// JSON array of objects or any inner payload is malformed.
#[wasm_bindgen(js_name = foldReplayMetaJsonl)]
pub fn fold_replay_meta_jsonl(payloads_json: &str) -> Result<String, String> {
    de1_domain::fold_meta_jsonl_json(payloads_json)
}

/// Build a Beanconqueror main JSON from a Crema envelope. The shell
/// packages the result into a ZIP with sibling photo files. See
/// `de1_domain::crema_to_bc_main_json_from_envelope`.
///
/// # Errors
///
/// Returns the JSON parse error string when the envelope is malformed.
#[wasm_bindgen(js_name = exportBeanconquerorMainJson)]
pub fn export_beanconqueror_main_json(
    envelope_json: &str,
    now_unix_ms: f64,
) -> Result<String, String> {
    de1_domain::crema_to_bc_main_json_from_envelope(envelope_json, f64_to_ms(now_unix_ms))
}

/// Build a Crema JSONL export from an envelope JSON
/// `{ "beans": [...], "roasters": [...], "shots": [...] }`. Returns
/// the multi-line JSONL text — one record per line, header first.
/// See `de1_domain::export_jsonl`.
///
/// # Errors
///
/// Returns the JSON parse error string when the envelope is
/// malformed.
#[wasm_bindgen(js_name = exportCremaJsonl)]
pub fn export_crema_jsonl(
    envelope_json: &str,
    exported_at_unix_ms: f64,
    crema_version: &str,
) -> Result<String, String> {
    let exported_at = f64_to_ms(exported_at_unix_ms);
    de1_domain::export_jsonl_from_json(envelope_json, exported_at, crema_version)
}

/// Parse a Crema JSONL export into an ImportPlan JSON (same shape
/// the BC importer returns; the shell's apply path is shared). See
/// `de1_domain::import_jsonl_to_plan_json`.
///
/// # Errors
///
/// Currently always Ok — even an empty string parses cleanly to an
/// empty plan. Reserved for future schema-version errors.
#[wasm_bindgen(js_name = importCremaJsonl)]
pub fn import_crema_jsonl(text: &str) -> Result<String, String> {
    de1_domain::import_jsonl_to_plan_json(text)
}

/// Import a Beanconqueror main export JSON, mapping the high-value
/// subset into Crema's `Bean` / `Roaster` / `StoredShot` types. The
/// shell unzips the BC archive (which packs `Beanconqueror.json` plus
/// chunk files for BREWS/BEANS over 500 items) and concatenates them
/// into one merged JSON before calling here. Returns the import plan
/// serialised as JSON (camelCase field names): `{beans, roasters,
/// shots, diagnostics}`. See `de1_domain::import_beanconqueror_json`
/// for the canonical mapping rules.
///
/// # Errors
///
/// Returns the JSON parse error string when the payload isn't a
/// well-formed object.
#[wasm_bindgen(js_name = importBeanconquerorJson)]
pub fn import_beanconqueror_json(
    merged_main_json: &str,
    now_unix_ms: f64,
) -> Result<String, String> {
    de1_domain::import_beanconqueror_json(merged_main_json, f64_to_ms(now_unix_ms))
}

/// Derive the maintenance readout (filter capacity %, litres since
/// descale, hours since clean) from the persisted state. `state_json`
/// is the shell-side `MaintenanceState` serialised to JSON; `now_ms`
/// is the wall-clock unix-epoch ms. See
/// `de1_domain::maintenance_readout_json`.
///
/// # Errors
///
/// Returns the JSON parse error string when `state_json` doesn't
/// deserialise into a `MaintenanceState`.
#[wasm_bindgen(js_name = maintenanceReadout)]
pub fn maintenance_readout(state_json: &str, now_ms: f64) -> Result<String, String> {
    de1_domain::maintenance_readout_json(state_json, f64_to_ms(now_ms))
}

/// Classify a 1..10 roast level into a named band — returns the lowercase
/// wire string (`"light"` / `"medium"` / `"dark"`), or `undefined` for a
/// missing (`None`) level. Values outside 1..10 are clamped first. See
/// `de1_domain::roast_band` for the canonical implementation.
#[wasm_bindgen]
pub fn roast_band(level: Option<i32>) -> Option<String> {
    level.map(|n| de1_domain::roast_band(n).as_str().to_owned())
}

/// Finer 5-bucket **display** label for a 1..10 roast level (`"Light"` /
/// `"Med-light"` / `"Medium"` / `"Med-dark"` / `"Dark"`), or `undefined` for a
/// missing (`None`) level. Display-only — comparisons ride on the 3-band
/// `roast_band`. See `de1_domain::roast_band5` for the canonical mapping.
#[wasm_bindgen]
pub fn roast_band5(level: Option<i32>) -> Option<String> {
    level.map(|n| de1_domain::roast_band5(n).to_owned())
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
    let band = de1_domain::RoastBand::from_wire_str(&band)?;
    Some(
        de1_domain::roast_freshness(band, days_i)
            .as_str()
            .to_owned(),
    )
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
#[wasm_bindgen]
pub fn export_v2_json_shot(shot_json: &str) -> Result<String, String> {
    let shot: de1_domain::StoredShot =
        serde_json::from_str(shot_json).map_err(|e| e.to_string())?;
    de1_domain::export_v2_json_shot(&shot).map_err(|e| e.to_string())
}

/// Mint a fresh profile ID — a standard UUID v7 (RFC 9562, 2024) in
/// the 36-character dashed form, e.g. `01910f80-7a3b-7c54-b2d1-23a4f8e9cd00`.
/// The 48-bit timestamp prefix makes IDs lexicographically sortable by
/// creation time; the 74 random bits keep collisions astronomical.
///
/// Used by the web shell when creating, duplicating, or importing a
/// **custom** profile. Built-in IDs are pre-generated and live in
/// `core/de1-domain/profiles/builtin.json`, not minted at runtime.
///
/// Exposed under the camelCase JS name `newProfileId` so the TS shell
/// can call it as `bridge.newProfileId()`. See
/// [`de1_domain::new_profile_id`] for the canonical implementation.
#[wasm_bindgen(js_name = newProfileId)]
pub fn new_profile_id() -> String {
    de1_domain::new_profile_id()
}

/// Mint a fresh stored-shot ID — `shot:<uuid-v7>`. Same UUID v7
/// minter as [`new_profile_id`], with the `shot:` prefix the shell's
/// history store expects. Exposed under the JS name `newShotId`.
#[wasm_bindgen(js_name = newShotId)]
pub fn new_shot_id() -> String {
    de1_domain::new_shot_id()
}

/// Derive [`de1_domain::ShotPeaks`] from a [`de1_domain::StoredShot`]
/// JSON. Returns the peaks as a JSON object so the shell consumes the
/// identical numbers each time (`peakWeight`, `finalWeight`,
/// `peakPressure`, `peakTemp`). Used by the History list + detail
/// readouts — peaks are not stored on `StoredShot`, they are derived
/// on read.
///
/// # Errors
///
/// Returns the JSON parse error string when `stored_shot_json` is
/// not a valid `StoredShot`.
#[wasm_bindgen(js_name = peaksForShot)]
pub fn peaks_for_shot(stored_shot_json: &str) -> Result<String, String> {
    let shot: de1_domain::StoredShot =
        serde_json::from_str(stored_shot_json).map_err(|e| e.to_string())?;
    let peaks = shot.peaks();
    serde_json::to_string(&peaks).map_err(|e| e.to_string())
}

/// Hard wire-protocol bounds for DE1 profile fields, exposed to shells as
/// a single JSON snapshot so steppers / validators / form widgets reach
/// for the same numbers. The shell parses the result once at module load
/// — these are constants, not live values. See
/// `de1_domain::profile_bounds` for the canonical declarations.
#[wasm_bindgen]
pub fn profile_bounds_json() -> String {
    de1_domain::profile_bounds::profile_bounds_json()
}

/// The Crema core, exposed to the web shell.
///
/// Methods that produce a [`CoreOutput`] return it as a JSON string; the shell
/// parses it into types generated by `typeshare`. Timestamps are `f64`
/// milliseconds — the natural type for the browser's `performance.now()`.
#[wasm_bindgen]
pub struct CremaBridge {
    core: CremaCore,
    /// Stashed live core during replay. `None` outside replay mode;
    /// `Some(prev)` after `begin_replay()` until `end_replay()` swaps
    /// it back. See docs/48-replay-architecture-problem-statement.md
    /// §4 for the design.
    stashed: Option<CremaCore>,
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
            stashed: None,
        }
    }

    /// Begin replay mode: stash the live core into a hidden slot, install
    /// a fresh [`CremaCore`] for the replay to run against. Returns an
    /// `Err` if a replay is already in progress (double-begin is a
    /// programming error and would lose the original live core).
    ///
    /// The shadow core is `CremaCore::new()` — no user preferences, no
    /// identity-keeper state, no connected scale. The shell calls
    /// `connect_scale(...)` against the shadow core to install the
    /// scale decoder for replay events, then feeds the recorded BLE
    /// bytes via `on_notification(...)`.
    ///
    /// `end_replay()` discards the replay core and restores the live one.
    pub fn begin_replay(&mut self) -> Result<(), String> {
        if self.stashed.is_some() {
            return Err("already in replay mode".to_owned());
        }
        self.stashed = Some(std::mem::replace(&mut self.core, CremaCore::new()));
        Ok(())
    }

    /// End replay mode: discard the replay-driven core, restore the
    /// previously-stashed live core. Idempotent: a no-op outside replay
    /// mode, so exception-path callers can call it unconditionally in
    /// their `finally` blocks.
    pub fn end_replay(&mut self) {
        if let Some(prev) = self.stashed.take() {
            self.core = prev;
        }
    }

    /// True while the replay shadow core is installed (i.e. between
    /// `begin_replay()` and `end_replay()`).
    pub fn in_replay(&self) -> bool {
        self.stashed.is_some()
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

    /// Slice the rolling BLE-capture buffer to JSONL covering
    /// `[from_ms, to_ms]`, with connect-phase identity entries + META
    /// prelude prepended. The shell parses the result back into its
    /// `CaptureEntry[]` for IndexedDB persistence. See
    /// [`CremaCore::capture_slice_jsonl`].
    pub fn capture_slice_jsonl(&self, from_ms: f64, to_ms: f64) -> String {
        self.core.capture_slice_jsonl(from_ms as u64, to_ms as u64)
    }

    /// Drop every recorded entry — called by the shell on BLE disconnect.
    /// Distinct from [`reset`](Self::reset), which wipes the whole core.
    pub fn capture_clear(&mut self) {
        self.core.capture_clear();
    }

    /// Identify and connect a scale from its BLE advertised name. Returns the
    /// connected scale's display label, or `undefined` if the name matched no
    /// supported scale.
    pub fn connect_scale(&mut self, advertised_name: String) -> Option<String> {
        self.core.connect_scale(&advertised_name)
    }

    /// Disconnect the scale: reset the core's scale slice (the identified
    /// codec + every scale-derived reading) without touching user prefs or the
    /// shot / profile config. The web shell calls this when the scale's BLE
    /// link drops (the wasm twin of the ffi `disconnect_scale`, AND4 follow-up).
    /// See `de1_app::CremaCore::disconnect_scale`.
    pub fn disconnect_scale(&mut self) {
        self.core.disconnect_scale();
    }

    /// Enable or disable auto-tare on shot start.
    pub fn set_auto_tare(&mut self, enabled: bool) {
        self.core.set_auto_tare(enabled);
    }

    /// Enable or disable stop-at-weight (SAW).
    pub fn set_stop_on_weight(&mut self, enabled: bool) {
        self.core.set_stop_on_weight(enabled);
    }

    /// Per-shot kill switch for the weight target. Independent of
    /// `set_stop_on_weight`; either flag suppresses arming.
    pub fn set_weight_target_disabled(&mut self, disabled: bool) {
        self.core.set_weight_target_disabled(disabled);
    }

    /// Clear the running scale-derived peaks (peak weight + final
    /// weight). The Scale page's "Reset peak" button.
    pub fn reset_scale_peaks(&mut self) {
        self.core.reset_scale_peaks();
    }

    /// Set the active profile's recipe target weight, in grams.
    /// `None` (or a non-finite / non-positive value) means "no target."
    pub fn set_profile_target_weight(&mut self, grams: Option<f32>) {
        self.core.set_profile_target_weight(grams);
    }

    /// Set the per-shot dial override weight, in grams. `None` clears the
    /// override (falls back to the profile recipe target).
    pub fn set_shot_target_weight(&mut self, grams: Option<f32>) {
        self.core.set_shot_target_weight(grams);
    }

    /// Set the active profile's volume limit (SAV), in millilitres.
    /// `None` means "no limit."
    pub fn set_profile_volume_limit(&mut self, milliliters: Option<f32>) {
        self.core.set_profile_volume_limit(milliliters);
    }

    /// Set the global maximum shot duration, in seconds. `None` means
    /// "no max." Legacy default is 60 s.
    pub fn set_max_shot_duration(&mut self, seconds: Option<f32>) {
        self.core.set_max_shot_duration(seconds);
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

    /// Build a [`CoreOutput`] (JSON) whose command writes a new calibration
    /// for `sensor`: the DE1 reported `reported` while the externally-measured
    /// true value was `measured`. Both arguments are in the sensor's canonical
    /// units (°C / bar / ml·s⁻¹) — the shell converts at the I/O boundary
    /// before calling. From then on the DE1 applies `measured / reported` as
    /// a multiplier on that sensor.
    pub fn write_calibration(&self, sensor: CalSensor, reported: f32, measured: f32) -> String {
        json(
            self.core
                .write_calibration(sensor.into(), reported, measured),
        )
    }

    /// Build a [`CoreOutput`] (JSON) whose command resets `sensor` to its
    /// factory calibration. The DE1 starts using the factory values immediately;
    /// the shell should follow up with a `read_calibration` to surface the new
    /// in-use value.
    pub fn reset_calibration_to_factory(&self, sensor: CalSensor) -> String {
        json(self.core.reset_calibration_to_factory(sensor.into()))
    }

    /// Build a [`CoreOutput`] (JSON) whose command tares the connected scale.
    ///
    /// `&mut self` (unlike the `set_scale_*` siblings): taring bumps the
    /// Decent scale's command sequence counter via `Scale::tare`, so this
    /// genuinely mutates the connected-scale state. The FFI twin is `&self`
    /// only because it wraps the core in a `Mutex`; the WASM bridge owns the
    /// core directly and JS is single-threaded, so `&mut self` is honest here.
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

    /// Build a [`CoreOutput`] (JSON) whose command enables a connected
    /// Decent Scale's on-scale LCD in the unit the shell passes
    /// (`"grams"` / `"ounces"`, matching the [`WeightUnit`] serde
    /// representation). Decent-Scale-only: empty for every other scale,
    /// and for an unconnected core.
    ///
    /// Setting the LCD enable also arms the heartbeat requirement on the
    /// scale — the shell must follow up with periodic
    /// [`decent_scale_heartbeat`](Self::decent_scale_heartbeat) writes to keep
    /// the display awake.
    ///
    /// `unit` is the same lowercase string the web settings store keeps
    /// (`"grams"` / `"ounces"`); a malformed string returns an empty
    /// `CoreOutput` and an error to the JS side via the wasm-bindgen
    /// return type.
    pub fn enable_scale_lcd(&self, unit: &str) -> Result<String, String> {
        let unit = de1_domain::WeightUnit::from_str_lower(unit)
            .ok_or_else(|| format!("unknown weight unit: {unit}"))?;
        self.core
            .enable_scale_lcd(unit)
            .map(json)
            .map_err(|e| e.to_string())
    }

    /// Build a [`CoreOutput`] (JSON) whose commands disable the connected
    /// scale's on-scale LCD. Capability-driven — see
    /// `Scale::lcd_disable_command`. Returns an error when no scale or
    /// the scale has no LCD-disable command.
    pub fn disable_scale_lcd(&self) -> Result<String, String> {
        self.core
            .disable_scale_lcd()
            .map(json)
            .map_err(|e| e.to_string())
    }

    /// Build a [`CoreOutput`] (JSON) whose command emits one keep-alive
    /// heartbeat to the connected scale. The shell schedules the clock
    /// (Decent Scale's interval is ~`HEARTBEAT_INTERVAL_MS` ms).
    /// Capability-driven — returns an error when the scale doesn't need
    /// a heartbeat (`ScaleCapabilities::heartbeat_interval_ms == None`).
    pub fn scale_heartbeat(&self) -> Result<String, String> {
        self.core
            .scale_heartbeat()
            .map(json)
            .map_err(|e| e.to_string())
    }

    /// Build a [`CoreOutput`] (JSON) whose commands power off the
    /// connected scale. Capability-driven — see
    /// `Scale::power_off_command`. Returns an error when no scale or
    /// the scale lacks a host-driven power-off (Decent / Eureka / Solo
    /// support it).
    pub fn power_off_scale(&self) -> Result<String, String> {
        self.core
            .power_off_scale()
            .map(json)
            .map_err(|e| e.to_string())
    }

    /// Cache the user's chosen weight unit on the core so the LCD-enable
    /// auto-policy (triggered on the DE1's Idle entry) picks the right
    /// wire packet. `unit` is the same lowercase string the web settings
    /// store keeps (`"grams"` / `"ounces"`).
    pub fn set_weight_unit_pref(&mut self, unit: &str) -> Result<(), String> {
        let unit = de1_domain::WeightUnit::from_str_lower(unit)
            .ok_or_else(|| format!("unknown weight unit: {unit}"))?;
        self.core.set_weight_unit_pref(unit);
        Ok(())
    }

    /// Build a [`CoreOutput`] (JSON) whose command fires a beep on the
    /// connected scale. Capability-driven — Eureka / Solo support it.
    pub fn scale_beep(&self) -> Result<String, String> {
        self.core.scale_beep().map(json).map_err(|e| e.to_string())
    }

    /// Build a [`CoreOutput`] (JSON) whose command sets the connected
    /// scale's display unit to grams. Capability-driven — Eureka / Solo
    /// / Difluid expose this. Scales whose unit lives in the LCD-enable
    /// bytes (Decent / Skale) return an error; scales with only a
    /// toggle (Hiroia) want `toggle_scale_unit` instead.
    pub fn set_scale_unit_grams(&self) -> Result<String, String> {
        self.core
            .set_scale_unit_grams()
            .map(json)
            .map_err(|e| e.to_string())
    }

    /// Build a [`CoreOutput`] (JSON) whose command toggles the connected
    /// scale's display unit. Capability-driven — Hiroia is the only
    /// scale that exposes a toggle today.
    pub fn toggle_scale_unit(&self) -> Result<String, String> {
        self.core
            .toggle_scale_unit()
            .map(json)
            .map_err(|e| e.to_string())
    }

    /// Toggle whether the connected scale should be powered off on
    /// machine Sleep entry. Off by default. Capability-driven — the
    /// reactive auto-policy fires `power_off_command` on Sleep when this
    /// is true and the scale exposes a power-off (Decent / Eureka /
    /// Solo today).
    pub fn set_auto_off_scale_on_sleep(&mut self, enabled: bool) {
        self.core.set_auto_off_scale_on_sleep(enabled);
    }

    /// Whether the connected scale is configured to power off on machine
    /// Sleep entry.
    pub fn auto_off_scale_on_sleep(&self) -> bool {
        self.core.auto_off_scale_on_sleep()
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
        // RS1: never panic at the boundary (a panic aborts the whole wasm
        // module). Plain data always serializes, but on the unreachable error
        // degrade to `None` (no caps) rather than crashing.
        self.core
            .scale_capabilities()
            .and_then(|caps| serde_json::to_string(&caps).ok())
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
        // RS1: degrade to `None` on the (unreachable) serialize error, never panic.
        self.core
            .scale_uuids()
            .and_then(|uuids| serde_json::to_string(&uuids).ok())
    }

    /// The firmware-update check result, as a JSON-encoded
    /// [`FirmwareUpdateStatus`](de1_app::FirmwareUpdateStatus). Pure read —
    /// no BLE traffic. Returns the `Unknown` variant until the DE1's `Version`
    /// characteristic has been observed via `on_notification`.
    pub fn firmware_update_status(&self) -> String {
        // RS1: fall back to the `Unknown` variant (`{"type":"Unknown"}`) on the
        // unreachable serialize error instead of panicking — the shell renders
        // that as "connect a DE1 to check".
        serde_json::to_string(&self.core.firmware_update_status())
            .unwrap_or_else(|_| r#"{"type":"Unknown"}"#.to_string())
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
    pub fn set_scale_volume(&self, level: u8) -> String {
        json(self.core.set_scale_volume(level))
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
        json(self.core.set_scale_standby(minutes))
    }

    /// Build a [`CoreOutput`] (JSON) whose command enables or disables the
    /// connected scale's flow smoothing.
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// supports flow smoothing. Empty otherwise. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_flow_smoothing(&self, enabled: bool) -> String {
        json(self.core.set_scale_flow_smoothing(enabled))
    }

    /// Build a [`CoreOutput`] (JSON) whose command enables or disables the
    /// connected scale's anti-mistouch.
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// supports anti-mistouch. Empty otherwise. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_anti_mistouch(&self, enabled: bool) -> String {
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
    pub fn set_scale_mode(&self, mode_id: u8) -> String {
        json(self.core.set_scale_mode(mode_id))
    }

    /// Build a [`CoreOutput`] (JSON) whose command selects the connected
    /// scale's auto-stop mode (`0` = flow-stop, `1` = cup-removal).
    ///
    /// Capability-gated: the command is present only when the connected scale
    /// supports an auto-stop-mode setting and `mode_id` is in range. An
    /// out-of-range `mode_id` yields an empty `CoreOutput`. Wired exactly like
    /// [`set_scale_volume`](Self::set_scale_volume).
    pub fn set_scale_auto_stop(&self, mode_id: u8) -> String {
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

    /// Set the steam flow rate, ml/s. Scaled `int(10 × rate)`; MMR
    /// `0x803828`, 1-byte.
    pub fn set_steam_flow(&self, ml_per_s: f32) -> String {
        json(self.core.set_steam_flow(ml_per_s))
    }

    /// Set the seconds of high-flow steam at the start of a steam cycle.
    /// MMR `0x80382C`, 4-byte. The wire value is `seconds × 100` —
    /// `seconds` is `f32` so sub-second precision survives (legacy
    /// default 0.7s = wire 70).
    pub fn set_steam_highflow_start(&self, seconds: f32) -> String {
        json(self.core.set_steam_highflow_start(seconds))
    }

    /// Set the group-head-control mode. MMR `0x803820`, 1-byte.
    pub fn set_ghc_mode(&self, mode: u8) -> String {
        json(self.core.set_ghc_mode(mode))
    }

    /// Set the hot-water flow rate, ml/s. Scaled `int(10 × rate)`; MMR
    /// `0x80384C`, 2-byte.
    pub fn set_hot_water_flow_rate(&self, ml_per_s: f32) -> String {
        json(self.core.set_hot_water_flow_rate(ml_per_s))
    }

    /// Set the flush flow rate, ml/s. Scaled `int(10 × rate)`; MMR
    /// `0x803840`, 2-byte.
    pub fn set_flush_flow_rate(&self, ml_per_s: f32) -> String {
        json(self.core.set_flush_flow_rate(ml_per_s))
    }

    /// Set the flush water target temperature, °C — the temperature the
    /// DE1 holds during a group-flush cycle. Wire value is `°C × 10`;
    /// MMR `0x803844`, 4-byte.
    pub fn set_flush_temp(&self, temp_c: f32) -> String {
        json(self.core.set_flush_temp(temp_c))
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
    /// must wrap this in a typed-to-confirm modal (`MainsConfirmModal`).
    /// MMR `0x803834`, 4-byte. Wire value is `volts + 1000` (user-committed
    /// marker).
    ///
    /// `volts` must be `120` or `230`; anything else is rejected. Returns
    /// `Result<String, String>` so the bridge throws on a bad value — the
    /// shell pre-validates via the modal, this is the last-line guard.
    ///
    /// # Errors
    ///
    /// Returns the [`AppError`](de1_app::AppError) display string when the
    /// core rejects `volts` for being out of `{120, 230}`.
    pub fn set_heater_voltage(&self, volts: u8) -> Result<String, String> {
        self.core
            .set_heater_voltage(volts)
            .map(json)
            .map_err(|e| e.to_string())
    }

    /// Reset 8 machine settings to factory baseline — mirrors reaprime's
    /// `DELETE /api/v1/machine/settings/reset`. Returns a JSON-encoded
    /// [`CoreOutput`] with 8 sequential MMR writes (fan threshold,
    /// hot-water idle temp, heater phase 1/2 flows, espresso warmup
    /// timeout, refill kit auto, flow-calibration multiplier, steam
    /// purge mode). Profiles, history, and app preferences are
    /// untouched.
    ///
    /// Errors are surfaced as a thrown `Error` — today the core's
    /// implementation is infallible, but the `Result` shape mirrors
    /// [`set_heater_voltage`](Self::set_heater_voltage) for forward
    /// symmetry.
    ///
    /// # Errors
    ///
    /// Returns the [`AppError`](de1_app::AppError) display string when
    /// the core rejects the request (none today).
    pub fn reset_machine_defaults(&self) -> Result<String, String> {
        self.core
            .reset_machine_defaults()
            .map(json)
            .map_err(|e| e.to_string())
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

    /// Set the hot-water phase-1 flow rate (legacy `heater_tweaks`
    /// `phase_1_flow_rate`). Scaled `int(10 × rate)`; MMR `0x803810`,
    /// 4-byte LE.
    pub fn set_phase_1_flow_rate(&self, rate_ml_per_s: f32) -> String {
        json(self.core.set_phase_1_flow_rate(rate_ml_per_s))
    }

    /// Set the hot-water phase-2 flow rate (legacy `heater_tweaks`
    /// `phase_2_flow_rate`). Scaled `int(10 × rate)`; MMR `0x803814`,
    /// 4-byte LE.
    pub fn set_phase_2_flow_rate(&self, rate_ml_per_s: f32) -> String {
        json(self.core.set_phase_2_flow_rate(rate_ml_per_s))
    }

    /// Set the hot-water boiler idle target temperature, °C (legacy
    /// `heater_tweaks` `hot_water_idle_temp`). MMR `0x803818`, 4-byte LE.
    /// Wire value is `°C × 10` (per reaprime `writeScale: 10.0`).
    pub fn set_hot_water_idle_temp(&self, temp_c: f32) -> String {
        json(self.core.set_hot_water_idle_temp(temp_c))
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
        json(self.core.set_espresso_warmup_timeout(dur))
    }

    /// Set the steam two-tap-stop (legacy `heater_tweaks`
    /// `steam_two_tap_stop`; reaprime `steamPurgeMode`). MMR `0x803850`,
    /// 4-byte LE int. `0` disables, `1` enables.
    pub fn set_steam_two_tap_stop(&self, value: u8) -> String {
        json(self.core.set_steam_two_tap_stop(value))
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
                        // Any other `DomainError` (e.g. the Serialization
                        // variant used for shot history) shouldn't reach this
                        // path. Surface its `Display` rather than silently
                        // coercing to `Empty`.
                        e => ProfileUploadFailure::Internal {
                            message: e.to_string(),
                        },
                    },
                    // Other AppError variants (e.g. Serialization) similarly
                    // shouldn't reach this path. Surface the underlying cause
                    // so the shell's toast / log carries the real reason.
                    e => ProfileUploadFailure::Internal {
                        message: e.to_string(),
                    },
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

    /// The effective AC mains frequency the volume integrator uses, Hz.
    /// `None` until either the user pins it via
    /// [`set_line_frequency_override`](Self::set_line_frequency_override)
    /// or the auto-detector locks (1+ second of telemetry into a shot).
    pub fn line_frequency_hz(&self) -> Option<f32> {
        self.core.line_frequency_hz()
    }

    /// Pin the AC mains frequency the volume integrator uses. Accepts
    /// only `0.0` (auto), `50.0`, or `60.0`; any other value is rejected.
    /// (The wasm ABI can't express `Option<f32>` cleanly, so `0.0` is the
    /// "auto" sentinel.)
    ///
    /// Hz mis-calibration only mis-times the AC-period integrator (no
    /// hardware damage, unlike heater voltage), but the shell still gates
    /// 50/60 selections behind `MainsConfirmModal` for symmetric UX; this
    /// clamp is the last-line guard.
    ///
    /// # Errors
    ///
    /// Returns an error string when `hz` is not `0.0`, `50.0`, or `60.0`.
    pub fn set_line_frequency_override(&mut self, hz: f32) -> Result<(), String> {
        // Round, don't truncate: a stray `50.9` must reject (rounds to 51), not
        // silently pass as 50 the way a bare `hz as i32` truncation would.
        let override_hz = match hz.round() as i32 {
            0 => None,
            50 => Some(50.0),
            60 => Some(60.0),
            _ => {
                return Err(format!(
                    "invalid argument for hz: {hz} (must be 0, 50, or 60)"
                ));
            }
        };
        self.core.set_line_frequency_override(override_hz);
        Ok(())
    }

    /// Parse a legacy de1app `.shot` (Tcl-dict) history file. Returns
    /// the resulting `StoredShot` as a JSON string the shell can
    /// deserialize into its IndexedDB history store. Returns the
    /// `Err.message` on failure (the bridge serializes Option<Err> via
    /// the `BridgeResult` shape consumers already use elsewhere).
    /// Stateless — takes `&self` for the wasm-bindgen instance-method
    /// ABI but does not touch the core.
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
    /// TS `Profile` shape and feed to `fromCoreProfile`.
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
        de1_domain::export_v2_json(&profile).map_err(|e| e.to_string())
    }

    /// Parse a legacy de1app `settings.tdb` file. Returns the
    /// `ImportedDe1AppSettings` struct as a JSON string the shell
    /// can deserialize + selectively apply to Crema's settings store
    /// + queued DE1-side writes.
    pub fn import_settings_tdb(&self, content: &str) -> Result<String, String> {
        de1_domain::import_settings_tdb(content)
            .map_err(|e| e.to_string())
            .and_then(|s| serde_json::to_string(&s).map_err(|e| e.to_string()))
    }
}

/// Serialize a [`CoreOutput`] to JSON for the shell. `CoreOutput` is flat plain
/// data, so serialization is infallible in practice.
///
/// RS1: a panic here would abort the entire wasm module (and poison the ffi
/// mutex in the sibling crate). The only realistic failure is a non-finite
/// `f32` in a telemetry event — which today's parsers never emit, but the
/// boundary must not crash on. So fall back to an empty `CoreOutput`
/// (`{"events":[],"commands":[]}`): the shell sees no events/commands for that
/// tick — degraded, not dead.
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
    fn write_and_reset_calibration_produce_write_commands() {
        let bridge = CremaBridge::new();
        for json in [
            bridge.write_calibration(CalSensor::Temperature, 92.5, 93.0),
            bridge.reset_calibration_to_factory(CalSensor::Pressure),
        ] {
            assert!(json.contains("WriteCharacteristic"));
            assert!(json.contains("De1Calibration"));
        }
    }

    #[test]
    fn set_line_frequency_override_rounds_rather_than_truncates() {
        let mut bridge = CremaBridge::new();
        // The three valid mains settings pass and actually pin the value.
        assert!(bridge.set_line_frequency_override(50.0).is_ok());
        assert_eq!(bridge.line_frequency_hz(), Some(50.0));
        assert!(bridge.set_line_frequency_override(60.0).is_ok());
        assert!(bridge.set_line_frequency_override(0.0).is_ok());
        // A stray fractional value rejects — it must NOT silently truncate
        // `50.9` down to `50` the way the old bare `hz as i32` cast did.
        assert!(bridge.set_line_frequency_override(50.9).is_err());
        assert!(bridge.set_line_frequency_override(49.0).is_err());
    }

    #[test]
    fn set_espresso_warmup_timeout_rounds_and_clamps_to_an_hour() {
        // Decode the wire deciseconds: a single MMR write whose `data` is
        // [len, addr×3, value×4 LE]; `int(10 × seconds)` lives at data[4..8].
        fn deciseconds(json: &str) -> u64 {
            let parsed: serde_json::Value = serde_json::from_str(json).unwrap();
            let data = parsed["commands"][0]["content"]["data"].as_array().unwrap();
            let mut v = 0u64;
            for (i, b) in data[4..8].iter().enumerate() {
                v |= b.as_u64().unwrap() << (8 * i);
            }
            v
        }
        let bridge = CremaBridge::new();
        let ds = |s: f32| deciseconds(&bridge.set_espresso_warmup_timeout(s));
        // Whole/half seconds are exact; 5.1 s must round to 5100 ms = 51 ds —
        // the f32-error compensation a bare `from_secs_f32` would drop (→ 50).
        assert_eq!(ds(3.0), 30);
        assert_eq!(ds(5.1), 51);
        // Absurd input clamps to the 1-hour ceiling (3600 s = 36000 ds), not the
        // old `u64::MAX`-derived multi-year duration.
        assert_eq!(ds(7200.0), 36000);
    }

    #[test]
    fn reset_machine_defaults_returns_eight_write_characteristic_commands() {
        // The bridge's `reset_machine_defaults` mirrors reaprime's
        // settings-reset endpoint — the JSON should carry exactly 8
        // `WriteCharacteristic` commands targeting `De1MmrWrite`.
        let bridge = CremaBridge::new();
        let json = bridge
            .reset_machine_defaults()
            .expect("infallible without a firmware lockout");
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        let commands = parsed["commands"].as_array().unwrap();
        assert_eq!(commands.len(), 8);
        for cmd in commands {
            assert_eq!(cmd["type"], "WriteCharacteristic");
            assert_eq!(cmd["content"]["target"], "De1MmrWrite");
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

    /// Replay swap: live core's identity (scale name, weight unit pref)
    /// must be untouched by the begin/end-replay round-trip.
    #[test]
    fn replay_swap_preserves_live_core_state() {
        let mut bridge = CremaBridge::new();
        // Establish some live-core state.
        let scale = bridge.connect_scale("BOOKOO_SC".to_owned());
        assert_eq!(scale, Some("Bookoo".to_owned()));
        // Enter replay. The shadow core has no scale; calling
        // scale_capabilities should return None.
        assert!(!bridge.in_replay());
        bridge.begin_replay().expect("fresh begin");
        assert!(bridge.in_replay());
        assert!(
            bridge.scale_capabilities().is_none(),
            "shadow core has no scale"
        );
        // Pretend a replay connected a scale to the shadow core.
        bridge.connect_scale("ACAIA_LUNAR_001".to_owned());
        // End replay. Live core must be restored intact.
        bridge.end_replay();
        assert!(!bridge.in_replay());
        // Scale capabilities should be available again — the live Bookoo's
        // decoder is restored across the swap. Presence is enough to prove
        // the live core's connection state survived; the exact caps JSON
        // shape is covered by the scale module's own tests.
        assert!(bridge.scale_capabilities().is_some(), "live scale restored");
    }

    #[test]
    fn replay_swap_double_begin_errors() {
        let mut bridge = CremaBridge::new();
        bridge.begin_replay().expect("first begin");
        let err = bridge.begin_replay().expect_err("second begin must error");
        assert!(err.contains("already in replay"));
        // Cleanup: caller would still need end_replay to leave bridge sane.
        bridge.end_replay();
        assert!(!bridge.in_replay());
    }

    #[test]
    fn replay_swap_end_is_idempotent() {
        let mut bridge = CremaBridge::new();
        // End without a prior begin: must not panic and must leave state sane.
        bridge.end_replay();
        assert!(!bridge.in_replay());
        // After a real begin+end, a second end is also a no-op.
        bridge.begin_replay().unwrap();
        bridge.end_replay();
        bridge.end_replay();
        assert!(!bridge.in_replay());
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
        assert_eq!(days_off_roast(Some("not-a-date".to_owned()), now_ms), None);
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
            bridge.set_scale_standby(15),
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
    fn scale_lcd_and_heartbeat_bridge_methods_produce_a_write_for_a_decent_scale() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("Decent Scale ABC".to_owned());
        for json in [
            bridge.enable_scale_lcd("grams").unwrap(),
            bridge.disable_scale_lcd().unwrap(),
            bridge.scale_heartbeat().unwrap(),
        ] {
            let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
            let commands = parsed["commands"].as_array().unwrap();
            assert_eq!(commands.len(), 1, "expected one WriteScale command: {json}");
            assert_eq!(commands[0]["type"], "WriteScale");
        }
    }

    #[test]
    fn scale_lcd_and_heartbeat_bridge_methods_error_for_a_scale_without_those_capabilities() {
        // Bookoo has no settable LCD and doesn't need a heartbeat — every
        // capability call surfaces an UnsupportedOnHardware error string.
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        for err in [
            bridge.enable_scale_lcd("grams").unwrap_err(),
            bridge.disable_scale_lcd().unwrap_err(),
            bridge.scale_heartbeat().unwrap_err(),
        ] {
            assert!(
                err.contains("scale_") || err.contains("connected scale"),
                "expected capability-named error: {err}"
            );
        }
    }

    #[test]
    fn enable_scale_lcd_on_decent_in_ounces_emits_the_ounces_packet() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("Decent Scale ABC".to_owned());
        let json = bridge.enable_scale_lcd("ounces").unwrap();
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        let commands = parsed["commands"].as_array().unwrap();
        assert_eq!(commands.len(), 1);
        // The wire bytes are the LCD_ENABLE_OUNCES constant; the JSON
        // encodes them as a u8 array under the serde-adjacent `content`
        // wrapper (`tag = "type", content = "content"`).
        let data: Vec<u8> = commands[0]["content"]["data"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| u8::try_from(v.as_u64().unwrap()).unwrap())
            .collect();
        assert_eq!(
            data,
            [0x03, 0x0A, 0x01, 0x01, 0x01, 0x01, 0x09],
            "expected LCD_ENABLE_OUNCES bytes"
        );
    }

    #[test]
    fn enable_scale_lcd_with_unknown_unit_errors() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("Decent Scale ABC".to_owned());
        assert!(bridge.enable_scale_lcd("kilograms").is_err());
    }

    #[test]
    fn power_off_scale_on_decent_emits_the_power_off_packet() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("Decent Scale ABC".to_owned());
        // Power-off is sent unconditionally on a Decent Scale; older
        // firmware silently no-ops on the byte sequence.
        let json = bridge.power_off_scale().expect("decent scale connected");
        let parsed: serde_json::Value = serde_json::from_str(&json).unwrap();
        let commands = parsed["commands"].as_array().unwrap();
        assert_eq!(commands.len(), 1);
        let data: Vec<u8> = commands[0]["content"]["data"]
            .as_array()
            .unwrap()
            .iter()
            .map(|v| u8::try_from(v.as_u64().unwrap()).unwrap())
            .collect();
        assert_eq!(
            data,
            [0x03, 0x0A, 0x02, 0x00, 0x00, 0x00, 0x0B],
            "expected POWER_OFF bytes"
        );
    }

    #[test]
    fn power_off_scale_errors_for_a_scale_without_power_off() {
        let mut bridge = CremaBridge::new();
        bridge.connect_scale("BOOKOO_SC".to_owned());
        let err = bridge.power_off_scale().unwrap_err();
        assert!(
            err.contains("scale_power_off"),
            "error should name the feature: {err}"
        );
    }

    #[test]
    fn set_weight_unit_pref_validates_and_caches() {
        let mut bridge = CremaBridge::new();
        assert!(bridge.set_weight_unit_pref("ounces").is_ok());
        assert!(bridge.set_weight_unit_pref("grams").is_ok());
        assert!(bridge.set_weight_unit_pref("kilograms").is_err());
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
    fn signature_for_shot_matches_the_pinned_djb2_hex_digest() {
        // The TS `signatureForShot({completedAt:1.7e12, duration:30000,
        // profileName:'best of decent', finalWeight:36})` emits this
        // exact hex string. The Rust impl in `de1_domain` must agree
        // byte-for-byte; that's the contract callers depend on.
        let sig = signature_for_shot(
            1_700_000_000_000.0,
            30_000.0,
            Some("best of decent".to_owned()),
            Some(36.0),
        );
        assert_eq!(sig, "65946a11");
    }

    #[test]
    fn signature_for_shot_handles_a_null_profile_distinctly() {
        let named = signature_for_shot(
            1_700_000_000_000.0,
            30_000.0,
            Some("named".to_owned()),
            Some(36.0),
        );
        let unnamed = signature_for_shot(1_700_000_000_000.0, 30_000.0, None, Some(36.0));
        assert_ne!(named, unnamed);
    }

    #[test]
    fn signature_for_bean_is_case_insensitive_on_name_and_roaster() {
        let a = signature_for_bean(
            "Yirgacheffe".to_owned(),
            Some("Counter Culture".to_owned()),
            Some("2026-05-08".to_owned()),
        );
        let b = signature_for_bean(
            "yirgacheffe".to_owned(),
            Some("COUNTER CULTURE".to_owned()),
            Some("2026-05-08".to_owned()),
        );
        assert_eq!(a, b);
        // And matches the pinned TS digest.
        assert_eq!(a, "481def0f");
    }

    #[test]
    fn signature_for_roaster_collapses_separators_and_strips_punctuation() {
        let a = signature_for_roaster("Onyx Coffee Lab".to_owned());
        let b = signature_for_roaster("onyx-coffee_lab".to_owned());
        let c = signature_for_roaster("  ONYX   COFFEE.LAB ".to_owned());
        assert_eq!(a, b);
        assert_eq!(a, c);
        // Pinned TS digest.
        assert_eq!(a, "cf16b46a");
    }

    #[test]
    fn reconcile_shots_bridges_a_minimum_viable_payload() {
        // The shell hands its own `StoredShot[]` through as JSON.
        // Extra fields on `StoredShot` (`series`, `bean`, …) are
        // ignored by serde, so the minimum viable input only carries
        // the slim subset the planner reads.
        let payload = r#"{
            "local": [
                {"id": "shot:l-1", "completedAt": 1700000000000, "duration": 30000,
                 "profileName": "best of decent", "finalWeight": 36}
            ],
            "remote": [
                {"id": "r-1", "clock": 1700000000000, "duration_ms": 30000,
                 "profile_title": "best of decent", "final_weight_g": 36}
            ]
        }"#;
        let out = reconcile_shots(payload).expect("plan");
        let actions: serde_json::Value = serde_json::from_str(&out).unwrap();
        let arr = actions.as_array().unwrap();
        assert_eq!(arr.len(), 1);
        // Unbound local + signature match → bind.
        assert_eq!(arr[0]["kind"], "bind");
        assert_eq!(arr[0]["localId"], "shot:l-1");
        assert_eq!(arr[0]["visualizerId"], "r-1");
    }

    #[test]
    fn reconcile_shots_surfaces_a_parse_error_for_malformed_input() {
        let err = reconcile_shots("not json").unwrap_err();
        assert!(!err.is_empty(), "the bridge should surface a parse reason");
    }

    #[test]
    fn on_notification_reports_a_decode_error_for_a_truncated_water_levels_packet() {
        let mut bridge = CremaBridge::new();
        let out = bridge.on_notification(NotificationSource::De1WaterLevels, vec![0x00], 1_000.0);
        assert!(out.contains("DecodeError"));
    }
}

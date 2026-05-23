//! # de1-domain
//!
//! The Crema espresso **domain model** — the layer above the wire protocol.
//!
//! Sans-IO, like the rest of the core:
//!
//! - [`profile`] — the [`Profile`] recipe model and its assembly into the
//!   `de1-protocol` upload packets.
//! - [`profile_import`] — importers for the legacy DE1-app profile formats
//!   (v2 JSON and the original Tcl-dictionary `.tcl` files), plus a v2 JSON
//!   exporter.
//! - [`builtin`] — the standard DE1 profiles, vendored and shipped as built-in
//!   Crema [`Profile`]s ("batteries included").
//! - [`shot`] — the [`ShotMonitor`] state machine, which observes a shot and
//!   records it.
//! - [`water`] — the [`WaterMonitor`] state machine, the sibling of
//!   [`ShotMonitor`] for hot-water and flush sessions.
//! - [`steam`] — the [`SteamMonitor`] state machine, the sibling for steam
//!   sessions, eco mode, and steam-clog detection.
//! - [`history`] — [`StoredShot`], a completed shot persisted to history.
//! - [`history_import`] / [`history_export`] — legacy `.shot` and modern v2
//!   `.shot.json` importers, plus the symmetric v2 exporter so a shell can
//!   share / upload a Crema shot in the community contract.
//! - [`stop`] — [`AutoStop`], the stop-at-weight / stop-at-volume controller.
//! - [`flow`] — [`FlowEstimator`], robust weight/mass-flow estimation for SAW.
//! - [`filter`] — [`median`](filter::median) computation, for smoothing a
//!   vibration-noisy signal.
//! - [`session`] — [`SessionTimer`], the timing core shared by the monitors.
//! - [`bean`] — pure roast-classification helpers ([`roast_band`],
//!   [`days_off_roast`], [`roast_freshness`]) shared by every shell.

pub mod bean;
pub mod builtin;
pub mod error;
pub mod filter;
pub mod flow;
pub mod history;
pub mod history_export;
pub mod history_import;
pub mod profile;
pub mod profile_bounds;
pub mod profile_import;
pub mod settings_import;
pub mod session;
pub mod shot;
pub mod steam;
pub mod stop;
pub mod tank;
pub mod units;
pub mod volume;
pub mod water;

pub use bean::{RoastBand, RoastFreshness, days_off_roast, roast_band, roast_freshness};
pub use builtin::{BUILTIN_PROFILE_COUNT, builtin_profiles};
pub use error::{DomainError, ImportError};
pub use flow::{Estimate, FlowAlgorithm, FlowEstimator};
pub use history::{STORED_SHOT_FORMAT_VERSION, ShotMetadata, StoredShot, brew_ratio};
pub use profile::{
    AssembledProfile, BeverageType, Compare, ExitCondition, ExitMetric, Limiter, Profile,
    ProfileStep, Pump, TempSensor, Transition,
};
pub use history_export::export_v2_json_shot;
pub use history_import::{import_legacy_tcl_shot, import_v2_json_shot};
pub use profile_import::{export_v2_json, import_legacy_tcl, import_v2_json};
pub use settings_import::{import_settings_tdb, ImportedDe1AppSettings};
pub use session::SessionTimer;
pub use shot::{
    MAX_SHOT_SAMPLES, ShotEvent, ShotMetrics, ShotMonitor, ShotPhase, ShotRecord, TimedSample,
};
pub use steam::{
    MAX_STEAM_SAMPLES, STEAM_ECO_DELAY, SteamClogReason, SteamEvent, SteamMonitor, SteamRecord,
    SteamSample,
};
pub use stop::{AutoStop, STOP_WEIGHT_BEFORE, StopConfig, StopReason, StopTargets};
pub use tank::{TANK_MM_TO_ML, water_tank_ml};
pub use units::{
    bar_to_psi, celsius_to_fahrenheit, fahrenheit_to_celsius, fl_oz_to_ml, grams_to_oz,
    ml_to_fl_oz, oz_to_grams, psi_to_bar, WeightUnit,
};
pub use volume::{LineFreqDetector, VolumeIntegrator};
pub use water::{WaterEvent, WaterMonitor, WaterRecord, WaterSessionKind};

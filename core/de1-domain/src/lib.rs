//! # de1-domain
//!
//! The Crema espresso **domain model** — the layer above the wire protocol.
//!
//! Sans-IO, like the rest of the core:
//!
//! - [`profile`] — the [`Profile`] recipe model and its assembly into the
//!   `de1-protocol` upload packets.
//! - [`profile_import`] — importers for the legacy DE1-app profile formats
//!   (v2 JSON and the original Tcl-dictionary `.tcl` files).
//! - [`shot`] — the [`ShotMonitor`] state machine, which observes a shot and
//!   records it.
//! - [`water`] — the [`WaterMonitor`] state machine, the sibling of
//!   [`ShotMonitor`] for hot-water and flush sessions.
//! - [`steam`] — the [`SteamMonitor`] state machine, the sibling for steam
//!   sessions, eco mode, and steam-clog detection.
//! - [`history`] — [`StoredShot`], a completed shot persisted to history.
//! - [`stop`] — [`AutoStop`], the stop-at-weight / stop-at-volume controller.
//! - [`flow`] — [`FlowEstimator`], robust weight/mass-flow estimation for SAW.
//! - [`filter`] — [`MedianFilter`], for smoothing a vibration-noisy signal.

pub mod error;
pub mod filter;
pub mod flow;
pub mod history;
pub mod profile;
pub mod profile_import;
pub mod shot;
pub mod steam;
pub mod stop;
pub mod water;

pub use error::{DomainError, ImportError};
pub use filter::MedianFilter;
pub use flow::{Estimate, FlowAlgorithm, FlowEstimator};
pub use history::{STORED_SHOT_FORMAT_VERSION, ShotMetadata, StoredShot};
pub use profile::{
    AssembledProfile, Compare, ExitCondition, ExitMetric, Limiter, Profile, ProfileStep, Pump,
    TempSensor, Transition,
};
pub use profile_import::{import_legacy_tcl, import_v2_json};
pub use shot::{ShotEvent, ShotMetrics, ShotMonitor, ShotPhase, ShotRecord, TimedSample};
pub use steam::{
    STEAM_ECO_DELAY_SECONDS, SteamClogReason, SteamEvent, SteamMonitor, SteamRecord, SteamSample,
};
pub use stop::{AutoStop, StopConfig, StopReason, StopTargets};
pub use water::{WaterEvent, WaterMonitor, WaterRecord, WaterSessionKind};

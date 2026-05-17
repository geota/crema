//! # de1-domain
//!
//! The Crema espresso **domain model** — the layer above the wire protocol.
//!
//! Sans-IO, like the rest of the core:
//!
//! - [`profile`] — the [`Profile`] recipe model and its assembly into the
//!   `de1-protocol` upload packets.
//! - [`shot`] — the [`ShotMonitor`] state machine, which observes a shot and
//!   records it.
//! - [`water`] — the [`WaterMonitor`] state machine, the sibling of
//!   [`ShotMonitor`] for hot-water and flush sessions.
//! - [`history`] — [`StoredShot`], a completed shot persisted to history.
//! - [`stop`] — [`AutoStop`], the stop-at-weight / stop-at-volume controller.
//! - [`flow`] — [`FlowEstimator`], robust weight/mass-flow estimation for SAW.
//! - [`filter`] — [`MedianFilter`], for smoothing a vibration-noisy signal.

pub mod error;
pub mod filter;
pub mod flow;
pub mod history;
pub mod profile;
pub mod shot;
pub mod stop;
pub mod water;

pub use error::DomainError;
pub use filter::MedianFilter;
pub use flow::{Estimate, FlowAlgorithm, FlowEstimator};
pub use history::{STORED_SHOT_FORMAT_VERSION, ShotMetadata, StoredShot};
pub use profile::{
    AssembledProfile, Compare, ExitCondition, ExitMetric, Limiter, Profile, ProfileStep, Pump,
    TempSensor, Transition,
};
pub use shot::{ShotEvent, ShotMetrics, ShotMonitor, ShotPhase, ShotRecord, TimedSample};
pub use stop::{AutoStop, StopConfig, StopReason, StopTargets};
pub use water::{WaterEvent, WaterMonitor, WaterRecord, WaterSessionKind};

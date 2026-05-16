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

pub mod error;
pub mod profile;
pub mod shot;

pub use error::DomainError;
pub use profile::{
    AssembledProfile, Compare, ExitCondition, ExitMetric, Limiter, Profile, ProfileStep, Pump,
    TempSensor, Transition,
};
pub use shot::{ShotEvent, ShotMonitor, ShotPhase, ShotRecord, TimedSample};

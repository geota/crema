//! # de1-domain
//!
//! The Crema espresso **domain model** — the layer above the wire protocol.
//!
//! Sans-IO, like the rest of the core. It currently holds the [`Profile`]
//! model (an espresso recipe) and its assembly into the `de1-protocol` upload
//! packets. The shot state machine and history model will follow.

pub mod error;
pub mod profile;

pub use error::DomainError;
pub use profile::{
    AssembledProfile, Compare, ExitCondition, ExitMetric, Limiter, Profile, ProfileStep, Pump,
    TempSensor, Transition,
};

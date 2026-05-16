//! # de1-protocol
//!
//! Codecs and packet types for the Decent Espresso DE1's Bluetooth LE protocol.
//!
//! This crate is **sans-IO**: it knows nothing about Bluetooth, sockets, or the
//! filesystem. It turns raw GATT packet bytes into typed Rust values (and, for
//! write paths, back again). Every DE1 GATT packet is big-endian.
//!
//! The protocol is documented in `docs/02-ble-protocol.md`; this crate
//! implements it. It is a clean-room reimplementation — the legacy Tcl app is
//! referenced for the protocol's *behaviour*, not copied.
//!
//! ## Phase 0 (Spike A) scope
//!
//! - [`fixed_point`] — the DE1's packed fixed-point number formats.
//! - [`ShotSample`] — the ~4–10 Hz telemetry packet (`cuuid_0D`).
//! - [`StateInfo`] — the machine state / substate packet (`cuuid_0E`).
//!
//! `unsafe` is forbidden crate-wide via the workspace lint table.

pub mod error;
pub mod fixed_point;
pub mod shot_sample;
pub mod state;

pub use error::ProtocolError;
pub use shot_sample::{SHOT_SAMPLE_LEN, ShotSample};
pub use state::{MachineState, STATE_INFO_LEN, StateInfo, SubState};

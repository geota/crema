//! # de1-protocol
//!
//! Codecs and packet types for the Decent Espresso DE1's Bluetooth LE protocol.
//!
//! This crate is **sans-IO**: it knows nothing about Bluetooth, sockets, or the
//! filesystem. It turns raw GATT packet bytes into typed Rust values and back.
//! Every DE1 GATT packet is big-endian.
//!
//! Clean-room reimplementation — the legacy Tcl app is referenced for
//! the protocol's *behaviour*, not copied.
//!
//! ## Modules
//!
//! - [`fixed_point`] — the DE1's packed fixed-point number formats.
//! - [`ShotSample`] — the ~4–10 Hz telemetry packet (`cuuid_0D`).
//! - [`StateInfo`] — the machine state / substate packet (`cuuid_0E`).
//! - [`profile`] — the espresso profile upload codec (`cuuid_0F` / `cuuid_10`).
//! - [`command`] — command and settings packets (`cuuid_02` / `0B` / `11`).
//! - [`mmr`] — the memory-mapped register read/write codec (`cuuid_05` / `06`).
//! - [`calibration`] — sensor calibration packets (`cuuid_12`).
//! - [`firmware`] — the firmware OTA update codec (`cuuid_09` / `06`).
//!
//! `unsafe` is forbidden crate-wide via the workspace lint table.

pub mod calibration;
pub mod command;
pub mod error;
pub mod firmware;
pub mod fixed_point;
pub mod mmr;
pub mod profile;
pub mod shot_sample;
pub mod state;

pub use calibration::{CALIBRATION_LEN, CalCommand, CalTarget, Calibration};
pub use command::{ShotSettings, WaterLevels, requested_state};
pub use error::ProtocolError;
pub use firmware::{
    FIRMWARE_FRAME_DATA_LEN, FIRMWARE_FRAME_LEN, FIRST_ERROR_NONE, FIRST_ERROR_REQUEST,
    FW_MAP_REQUEST_LEN, FWMapRequest, VERSION_LEN, Version, VersionBlock, firmware_write_frame,
};
pub use mmr::{MMR_PACKET_LEN, MmrReadReply, MmrRegister};
pub use profile::{
    EXTENSION_FRAME_INDEX_OFFSET, ExtensionFrame, FrameFlags, SHOT_FRAME_LEN, SHOT_HEADER_LEN,
    ShotFrame, ShotHeader, ShotTail, ack_frame_byte,
};
pub use shot_sample::{SHOT_SAMPLE_LEN, ShotSample};
pub use state::{MachineState, STATE_INFO_LEN, StateInfo, SubState};

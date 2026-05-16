//! Solo Barista LSJ-001 BLE codec (`scale_type` `solo_barista`).
//!
//! The LSJ-001 is protocol-identical to the Eureka Precisa — same UUIDs,
//! weight frame, and command set. This module re-exports
//! [`crate::eureka_precisa`]; the scales differ only in their BLE scan name
//! (`LSJ-001`), which the shell handles.

pub use crate::eureka_precisa::*;

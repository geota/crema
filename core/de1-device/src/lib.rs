//! # de1-device
//!
//! The small part every Bluetooth device other than the DE1 shares: what kind
//! it is, how a scan recognises it, whether its link is alive, and its
//! firmware version. Each kind keeps its own driver crate (`de1-scale`,
//! `de1-grinder`); this crate holds only what more than one kind needs.
//!
//! Sans-IO like the driver crates: time is always passed in, nothing here
//! owns a clock, a thread or a radio.

mod firmware;
mod link;
mod registry;

pub use firmware::FirmwareVersion;
pub use link::{LinkVerdict, LinkWatch};
pub use registry::{DeviceKind, DeviceMatch, RegistryRow, ScanFilters};

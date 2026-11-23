//! # de1-grinder
//!
//! Sans-IO Bluetooth LE drivers for connected grinders, and grind detection.
//!
//! Like `de1-scale`: a driver turns notification bytes into readings and
//! builds the bytes to write; the shells own the radio, the app core owns
//! the state. Time is always passed in. A driver lives for one connection.
//!
//! - [`GrinderDriver`]: identify a grinder, get its UUIDs and capabilities,
//!   write its connect bytes, feed it notifications and ticks.
//! - [`GrinderReading`] / [`GrinderCapabilities`]: the common model the
//!   shells render from; nothing in a shell names a grinder model.
//! - [`GrindTracker`]: completed grinds and purges from any driver that
//!   reports state.
//!
//! Drivers: [`bookoo_motto80`] (BOOKOO Motto 80).

pub mod bookoo_motto80;
mod capabilities;
mod driver;
pub mod fuzzing;
mod reading;
mod tracker;

pub use capabilities::{
    GrinderCapabilities, GrinderCommands, GrinderRange, RpmObservation, SettingKind,
    SettingObservation,
};
pub use driver::{
    GRINDERS, GrinderDiagnostics, GrinderDriver, GrinderEvent, GrinderLinkDeadReason, GrinderUuids,
    UnsupportedReason,
};
pub use reading::{
    GrinderInfo, GrinderReading, GrinderSettings, GrinderState, ObservedSetting, PresetSlot,
};
pub use tracker::{GrindEnd, GrindSession, GrindTracker, MIN_RUN_MS, RunKind};

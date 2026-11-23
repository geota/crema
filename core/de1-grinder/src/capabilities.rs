//! The capability descriptor: what a connected grinder reports and accepts.
//!
//! Commands are kept apart from observations, and a setting is numeric,
//! enumerated or opaque, as in decaid's grinder contract
//! (`lib/src/models/device/grinder_device.dart`); a setting also says its
//! unit, whether that unit is calibrated, and whether it reflects the burrs.
//! Crosses the FFI as JSON. Shells render from it and never name a model.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// What kind of value a grinder's dial is.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum SettingKind {
    /// A number in a range.
    Numeric {
        /// Smallest value.
        min: f32,
        /// Largest value.
        max: f32,
        /// The step between values.
        step: f32,
    },
    /// One of a fixed list of values.
    Enumerated {
        /// The values, in dial order.
        values: Vec<String>,
    },
    /// Text with no structure Crema knows.
    Opaque,
}

/// What a grinder tells us about its dial.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct SettingObservation {
    /// The kind of value.
    pub kind: SettingKind,
    /// The unit ("μm"), empty when unitless.
    pub unit: String,
    /// The unit is a real physical one (μm), not a raw coordinate.
    pub calibrated: bool,
    /// The value reflects the burrs, not just a stored number.
    pub physical: bool,
}

/// What a grinder tells us about one of its speeds.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct RpmObservation {
    /// Smallest value reported, rpm.
    pub min: u32,
    /// Largest value reported, rpm.
    pub max: u32,
    /// The value is the measured motor speed, not the setpoint.
    pub measured: bool,
}

/// A settable range.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct GrinderRange {
    /// Smallest value.
    pub min: u32,
    /// Largest value.
    pub max: u32,
}

/// What a grinder accepts. Nothing yet: every grinder is read-only in Crema.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct GrinderCommands {
    /// Starting and stopping a grind. Never true without the vendor's own
    /// safety statement.
    pub start_stop: bool,
    /// Setting the dial (motorised dials only).
    pub set_setting: Option<SettingKind>,
    /// Setting the grinding speed.
    pub set_grind_rpm: Option<GrinderRange>,
    /// Setting the bean-feeder speed.
    pub set_feed_rpm: Option<GrinderRange>,
    /// Selecting a preset: how many there are.
    pub select_preset: Option<u8>,
}

/// What a connected grinder reports and accepts. Static per model.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GrinderCapabilities {
    /// What it says about its dial.
    pub setting: Option<SettingObservation>,
    /// What it says about its grinding speed.
    pub grind_rpm: Option<RpmObservation>,
    /// What it says about its bean-feeder speed.
    pub feed_rpm: Option<RpmObservation>,
    /// It reports whether it is grinding (grind detection needs this).
    pub reports_state: bool,
    /// It reports a lifetime grind count.
    pub reports_grind_count: bool,
    /// It reports humidity.
    pub reports_humidity: bool,
    /// It reports the dose weight.
    pub reports_weight: bool,
    /// What it accepts.
    pub commands: GrinderCommands,
}

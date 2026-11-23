//! The common reading model: what any connected grinder reports, decoded.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// What the grinder is doing.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[non_exhaustive]
pub enum GrinderState {
    /// Idle.
    Idle,
    /// Grinding a dose.
    Grinding,
    /// Clearing the chamber (a purge or a reverse): not a dose.
    Purging,
    /// Busy with something else: applying settings, the start-up guide.
    Busy,
    /// Updating its firmware: expect a drop, write nothing.
    Updating,
    /// A warning on the grinder.
    Warning,
    /// A state this build doesn't know (the raw string is kept).
    Unknown,
}

/// The grinder's dial as it reported it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ObservedSetting {
    /// The value as text ("500"), always present.
    pub value: String,
    /// The value as a number, when the setting is numeric.
    pub number: Option<f32>,
}

/// Which of the grinder's presets is selected.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum PresetSlot {
    /// No preset: the grinder's manual mode (the Motto's "N mode").
    Manual,
    /// A user preset.
    Slot {
        /// The preset's index on the grinder (0-based).
        index: u8,
    },
}

/// Device configuration a grinder reports alongside its telemetry: shown on
/// the Devices page, never logged with a shot.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct GrinderSettings {
    /// Cup detection is on.
    pub cup_detect: Option<bool>,
    /// The grinder stops on its own (auto stop) is on.
    pub auto_stop: Option<bool>,
    /// Faster clearing of retained grounds is on.
    pub fast_clean: Option<bool>,
    /// Display brightness, 1–5.
    pub brightness: Option<u8>,
    /// Sleep timeout, seconds.
    pub standby_s: Option<u16>,
    /// The grinder's own Wi-Fi is connected. The network's name is never kept.
    pub wifi_connected: Option<bool>,
}

/// One decoded snapshot from a grinder.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GrinderReading {
    /// When it arrived (the core's clock, ms).
    #[typeshare(serialized_as = "U64")]
    pub at_ms: u64,
    /// What the grinder is doing.
    pub state: GrinderState,
    /// The grinder's own state string, for diagnostics.
    pub raw_state: String,
    /// The dial, when reported and in range.
    pub setting: Option<ObservedSetting>,
    /// Grinding speed, rpm, when reported and in range.
    pub grind_rpm: Option<u32>,
    /// Bean-feeder speed, rpm, when reported and in range.
    pub feed_rpm: Option<u32>,
    /// Relative humidity, %, when reported and in range.
    pub humidity_pct: Option<u8>,
    /// The grinder's lifetime grind count.
    #[typeshare(serialized_as = "Option<U64>")]
    pub total_grinds: Option<u64>,
    /// The selected preset, when reported.
    pub preset: Option<PresetSlot>,
    /// Device configuration (Devices page only).
    pub settings: GrinderSettings,
}

/// What a grinder says about itself.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GrinderInfo {
    /// The model id (the grinder catalogue's id, e.g. `bookoo-motto-80`).
    pub model: String,
    /// Firmware version for display, once the grinder reported it.
    pub firmware: Option<String>,
    /// The grinder's serial number, as it reported it. Held in memory like a
    /// scale's serial (`Event::ScaleConfig`); redacted from captures.
    pub serial: Option<String>,
}

impl GrinderState {
    /// Whether this is a dose being ground.
    #[must_use]
    pub fn is_grinding(self) -> bool {
        matches!(self, GrinderState::Grinding)
    }
}

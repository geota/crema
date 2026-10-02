//! The DE1 connect sweep — every machine setting Crema owns a preference for,
//! re-asserted on each connect because the DE1 forgets them across a power
//! cycle (it boots with its own firmware values).
//!
//! One list, owned here and executed by the orchestrator
//! (`CremaCore::connect_sweep`), so web and Android can't drift. Its contents
//! follow de1app `later_new_de1_connection_setup` (`bluetooth.tcl:2374`) +
//! `set_heater_tweaks` (`de1_comms.tcl:1287`), and Decenza
//! `DE1Device::sendInitialSettings` + `MainController::applyAllSettings`:
//!
//! | write | register / characteristic | encoding | source |
//! |---|---|---|---|
//! | USB charger | MMR `0x803854` | 0/1 | de1app `check_battery_charger`, Decenza connect-setup |
//! | user-presence feature | MMR `0x803858` | bit 0 | crema / reaprime `enableUserPresenceFeature` |
//! | fan threshold | MMR `0x803808` | °C | de1app, Decenza |
//! | phase-1 flow | MMR `0x803810` | ml/s × 10 | `set_heater_tweaks` (default 2.0) |
//! | phase-2 flow | MMR `0x803814` | ml/s × 10 | `set_heater_tweaks` (default 4.0) |
//! | hot-water idle temp | MMR `0x803818` | °C × 10 | `set_heater_tweaks` (default 99.0) |
//! | espresso warm-up timeout | MMR `0x803838` | s × 10 | `set_heater_tweaks` (default 1.0) |
//! | steam two-tap stop | MMR `0x803850` | 0/1 | `set_heater_tweaks` |
//! | refill point | `WaterLevels` (`cuuid_11`) | mm | `de1_send_waterlevel_settings` |
//! | tank-temp threshold | MMR `0x80380C` | °C, 0..=45 | profile upload (de1app `de1_send_shot_frames`, Decenza `writeTankPreheatForProfile`) |
//! | steam / hot-water | `ShotSettings` (`cuuid_0B`) | packet | `de1_send_steam_hotwater_settings` |
//! | steam flow | MMR `0x803828` | ml/s × 100 | crema QC |
//! | flush timeout / temp | MMR `0x803848` / `0x803844` | × 10 | `set_heater_tweaks` / reaprime |
//! | steam eco | re-sent `ShotSettings` | — | crema |
//!
//! **USB charging** is app-controlled exactly as de1app does it
//! (`utils.tcl` `check_battery_charger`, run every minute, de1app default
//! `smart_battery_charging 1`): with smart charging the DE1's USB port is
//! switched ON at or below the bottom of a band and OFF at or above its top,
//! holding the last decision in between; with it off the charger is always
//! ON. An unreadable battery level counts as 100 % (de1app `battery_percent`).
//! The DE1 turns the port back on by itself after ~10 minutes, which is why
//! the decision is re-sent every minute ([`USB_CHARGER_CHECK_INTERVAL_MS`]),
//! not once.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// How often the shells re-run the USB-charger decision while connected
/// (de1app `schedule_minute_task`: 60 s).
pub const USB_CHARGER_CHECK_INTERVAL_MS: u32 = 60_000;

/// de1app's `set_heater_tweaks` defaults (`machine.tcl:286-300`), asserted
/// when the user has not set a value: phase-1 flow 2.0 ml/s ("20"), phase-2
/// flow 4.0 ml/s ("40"), hot-water idle 99.0 °C ("990"), espresso warm-up
/// timeout 1.0 s ("10"). Decenza sends the same numbers.
pub const DEFAULT_PHASE_1_FLOW_ML_S: f32 = 2.0;
/// See [`DEFAULT_PHASE_1_FLOW_ML_S`].
pub const DEFAULT_PHASE_2_FLOW_ML_S: f32 = 4.0;
/// See [`DEFAULT_PHASE_1_FLOW_ML_S`].
pub const DEFAULT_HOT_WATER_IDLE_TEMP_C: f32 = 99.0;
/// See [`DEFAULT_PHASE_1_FLOW_ML_S`].
pub const DEFAULT_ESPRESSO_WARMUP_TIMEOUT_S: f32 = 1.0;
/// Highest tank-temperature threshold the DE1 is given, °C (de1app
/// `range_check_variable … 0 45`, Decenza `qBound(0, …, 45)`).
pub const MAX_TANK_TEMP_C: f32 = 45.0;

/// de1app's `smart_battery_charging` setting.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum UsbChargingMode {
    /// `1` (de1app default): keep the tablet between 55 % and 65 %.
    #[default]
    Smart,
    /// `2`: keep it between 90 % and 95 % (down to 15 % while the machine
    /// sleeps).
    SmartHigh,
    /// `0`: smart charging off — the DE1's USB port is always on.
    AlwaysOn,
}

impl UsbChargingMode {
    /// Parse the persisted spelling; anything unknown is the default.
    #[must_use]
    pub fn from_str_lenient(s: &str) -> Self {
        match s {
            "smartHigh" => Self::SmartHigh,
            "alwaysOn" => Self::AlwaysOn,
            _ => Self::Smart,
        }
    }
}

/// de1app `check_battery_charger` as a pure step: given the mode, the tablet
/// battery (`None` = unreadable, counted as 100 %), whether the machine is
/// asleep (de1app's "sleep / saver" context), and the held "discharging"
/// latch, return `(charger_on, discharging)`.
#[must_use]
pub fn usb_charger_decision(
    mode: UsbChargingMode,
    battery_percent: Option<u8>,
    machine_asleep: bool,
    discharging: bool,
) -> (bool, bool) {
    let (bottom, top) = match mode {
        UsbChargingMode::AlwaysOn => return (true, discharging),
        UsbChargingMode::Smart => (55, 65),
        UsbChargingMode::SmartHigh => (if machine_asleep { 15 } else { 90 }, 95),
    };
    let percent = battery_percent.unwrap_or(100);
    if percent <= bottom {
        (true, false)
    } else if percent >= top {
        (false, true)
    } else {
        (!discharging, discharging)
    }
}

/// The shell's snapshot of the user's settings for one connect sweep. Each
/// shell builds it from its own settings store; the core turns it into the
/// writes (see the module table).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ConnectSweepSettings {
    /// Fan-on threshold, °C (clamped 0..=60).
    pub fan_threshold_c: f32,
    /// Two-tap steam stop.
    pub steam_two_tap: bool,
    /// The machine's own refill point, raw sensor mm.
    pub refill_point_mm: f32,
    /// Steam target, °C (0 = heater off).
    pub steam_temp_c: f32,
    /// Steam timeout, s.
    pub steam_timeout_s: f32,
    /// Hot-water temperature, °C.
    pub hot_water_temp_c: f32,
    /// Hot-water volume, ml.
    pub hot_water_volume_ml: f32,
    /// Steam flow, ml/s.
    pub steam_flow_ml_s: f32,
    /// Group-flush timeout, s.
    pub flush_timeout_s: f32,
    /// Group-flush temperature, °C.
    pub flush_temp_c: f32,
    /// Steam eco mode.
    pub steam_eco: bool,
    /// The active profile's tank-temperature target, °C (0 = no preheat).
    pub tank_temp_c: f32,
    /// Steam-heater phase-1 (warm-up) flow, ml/s; `None` = de1app default.
    pub phase_1_flow_ml_s: Option<f32>,
    /// Steam-heater phase-2 (test) flow, ml/s; `None` = de1app default.
    pub phase_2_flow_ml_s: Option<f32>,
    /// Hot-water heater idle temperature, °C; `None` = de1app default.
    pub hot_water_idle_temp_c: Option<f32>,
    /// Espresso heater warm-up timeout, s; `None` = de1app default.
    pub espresso_warmup_timeout_s: Option<f32>,
    /// Smart-charging mode for the DE1's USB port.
    pub usb_charging: UsbChargingMode,
    /// The tablet's battery level, %; `None` when unreadable.
    pub battery_percent: Option<u8>,
}

impl Default for ConnectSweepSettings {
    fn default() -> Self {
        Self {
            fan_threshold_c: 55.0,
            steam_two_tap: false,
            refill_point_mm: crate::tank::DEFAULT_REFILL_POINT_MM,
            steam_temp_c: 160.0,
            steam_timeout_s: 120.0,
            hot_water_temp_c: 85.0,
            hot_water_volume_ml: 120.0,
            steam_flow_ml_s: 1.5,
            flush_timeout_s: 5.0,
            flush_temp_c: 92.0,
            steam_eco: false,
            tank_temp_c: 0.0,
            phase_1_flow_ml_s: None,
            phase_2_flow_ml_s: None,
            hot_water_idle_temp_c: None,
            espresso_warmup_timeout_s: None,
            usb_charging: UsbChargingMode::Smart,
            battery_percent: None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn smart_charging_holds_55_to_65() {
        let m = UsbChargingMode::Smart;
        assert_eq!(
            usb_charger_decision(m, Some(50), false, true),
            (true, false)
        );
        assert_eq!(
            usb_charger_decision(m, Some(55), false, true),
            (true, false)
        );
        assert_eq!(
            usb_charger_decision(m, Some(70), false, false),
            (false, true)
        );
        assert_eq!(
            usb_charger_decision(m, Some(65), false, false),
            (false, true)
        );
        // In the band the last decision holds.
        assert_eq!(
            usb_charger_decision(m, Some(60), false, true),
            (false, true)
        );
        assert_eq!(
            usb_charger_decision(m, Some(60), false, false),
            (true, false)
        );
        // Unreadable counts as 100 %.
        assert_eq!(usb_charger_decision(m, None, false, false), (false, true));
    }

    #[test]
    fn smart_high_drops_to_15_while_asleep() {
        let m = UsbChargingMode::SmartHigh;
        assert_eq!(
            usb_charger_decision(m, Some(80), false, true),
            (true, false)
        );
        assert_eq!(usb_charger_decision(m, Some(80), true, true), (false, true));
        assert_eq!(usb_charger_decision(m, Some(15), true, true), (true, false));
        assert_eq!(
            usb_charger_decision(m, Some(96), false, false),
            (false, true)
        );
    }

    #[test]
    fn always_on_is_always_on() {
        for pct in [None, Some(5), Some(100)] {
            assert!(usb_charger_decision(UsbChargingMode::AlwaysOn, pct, false, true).0);
        }
    }

    #[test]
    fn modes_parse_leniently_and_settings_decode_partial_json() {
        assert_eq!(
            UsbChargingMode::from_str_lenient("alwaysOn"),
            UsbChargingMode::AlwaysOn
        );
        assert_eq!(
            UsbChargingMode::from_str_lenient("smartHigh"),
            UsbChargingMode::SmartHigh
        );
        assert_eq!(
            UsbChargingMode::from_str_lenient("?"),
            UsbChargingMode::Smart
        );
        let s: ConnectSweepSettings =
            serde_json::from_str(r#"{"fanThresholdC":50,"usbCharging":"alwaysOn"}"#).unwrap();
        assert!((s.fan_threshold_c - 50.0).abs() < f32::EPSILON);
        assert_eq!(s.usb_charging, UsbChargingMode::AlwaysOn);
        assert_eq!(s.phase_1_flow_ml_s, None);
    }
}

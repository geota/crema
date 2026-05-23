//! Import legacy de1app `settings.tdb` files into Crema.
//!
//! The `settings.tdb` file is written by the legacy de1app's
//! `save_array_to_file` — each line is `{key} {value}\n`, the same
//! flat-TCL dictionary grammar profile/shot files use. This module
//! reuses [`crate::profile_import::TclDict`] to do the low-level
//! parse, then maps the known keys into a single
//! [`ImportedDe1AppSettings`] struct the shell can apply.
//!
//! The struct is intentionally a flat data carrier — every field is
//! optional so absent keys stay `None`. The shell decides which
//! fields land on which side of the boundary:
//!
//! - **Crema-local** (localStorage settings) — `default_dose_g`,
//!   `default_ratio`, `screensaver`, etc.
//! - **DE1-side** (MMR / ShotSettings writes, queued for next connect) —
//!   `steam_temperature_c`, `hot_water_volume_ml`, `flush_seconds`, etc.
//!
//! Verified against reaprime's
//! `lib/src/import/parsers/settings_tdb_parser.dart` (commit reaprime
//! ec61f8e of 2026-05-21). Field set and value-coercion rules are
//! same-for-same; Crema's struct picks more user-friendly Rust field
//! names (snake_case + units in the suffix where useful).
//!
//! docs/22 §5.4.

use crate::error::ImportError;
use crate::profile_import::TclDict;

/// One parsed `settings.tdb`. Every field is `Option`-typed: a key
/// absent from the file (or carrying an unparseable / sentinel-zero
/// value) is reported as `None`, so a caller can apply only the
/// settings the user actually had stored.
#[derive(Debug, Clone, Default, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct ImportedDe1AppSettings {
    // ── Wake schedule ──────────────────────────────────────────────────
    /// `scheduler_enable` — whether the wake schedule is on.
    pub wake_schedule_enabled: Option<bool>,
    /// Wake hour (0–23), derived from `scheduler_wake` seconds.
    pub wake_hour: Option<u8>,
    /// Wake minute (0–59), derived from `scheduler_wake` seconds.
    pub wake_minute: Option<u8>,
    /// Minutes to keep the machine awake after the wake time,
    /// derived from `scheduler_sleep − scheduler_wake` (wrapped at
    /// 24 h if sleep is the next day).
    pub keep_awake_minutes: Option<u32>,

    // ── Screensaver / scale ────────────────────────────────────────────
    /// `keep_scale_on` — whether the scale should stay awake while
    /// the DE1 is idle.
    pub keep_scale_on: Option<bool>,
    /// `screen_saver_delay` minutes, snapped to one of
    /// `[0, 15, 30, 45, 60, 90, 120, 180]` per reaprime's UI.
    pub sleep_timeout_minutes: Option<u32>,
    /// `smart_battery_charging` mode: `0` disabled, `1` longevity,
    /// `2` high-availability. Unknown / missing → `None`.
    pub charging_mode: Option<u8>,

    // ── Brew defaults / workflow context ──────────────────────────────
    /// `grinder_dose_weight` — dry coffee dose, grams. `None` if 0 or
    /// missing.
    pub dose_g: Option<f32>,
    /// `grinder_setting` — free-text grinder dial; `None` if empty
    /// or `"0"`.
    pub grinder_setting: Option<String>,
    /// `grinder_model` — free-text grinder model; `None` if empty.
    pub grinder_model: Option<String>,
    /// `final_desired_shot_weight_advanced` — yield target, grams.
    /// `None` if 0 or missing.
    pub target_yield_g: Option<f32>,

    // ── Steam ──────────────────────────────────────────────────────────
    /// `steam_temperature` — steam target temperature, °C.
    pub steam_temperature_c: Option<u8>,
    /// `steam_max_time` — steam timeout, seconds.
    pub steam_max_time_s: Option<u32>,

    // ── Hot water ─────────────────────────────────────────────────────
    /// `water_temperature` — hot-water target temperature, °C.
    pub hot_water_temperature_c: Option<u8>,
    /// `water_volume` — hot-water target volume, ml.
    pub hot_water_volume_ml: Option<u32>,

    // ── Flush ─────────────────────────────────────────────────────────
    /// `flush_flow` — flush flow rate, ml/s. The legacy app stores
    /// this as a plain double; the wire value the DE1 expects is
    /// `ml/s × 100` (docs/22 §2.1).
    pub flush_flow_ml_per_s: Option<f32>,
    /// `flush_seconds` — flush duration, seconds.
    pub flush_seconds: Option<u32>,

    // ── Device addresses (Android-style MAC; Web Bluetooth doesn't
    //    use these, but the shell can surface them for the user to
    //    re-pair against). ─────────────────────────────────────────────
    /// `bluetooth_address` of the DE1 the user previously paired.
    pub machine_bluetooth_address: Option<String>,
    /// `scale_bluetooth_address` of the scale the user previously
    /// paired.
    pub scale_bluetooth_address: Option<String>,
}

impl ImportedDe1AppSettings {
    /// `true` when nothing actionable was extracted — every meaningful
    /// field is `None`. The shell can use this to short-circuit "would
    /// you like to apply these settings?" prompts on an empty import.
    pub fn is_empty(&self) -> bool {
        self.wake_hour.is_none()
            && self.wake_minute.is_none()
            && self.keep_awake_minutes.is_none()
            && self.keep_scale_on.is_none()
            && self.sleep_timeout_minutes.is_none()
            && self.charging_mode.is_none()
            && self.dose_g.is_none()
            && self.grinder_setting.is_none()
            && self.grinder_model.is_none()
            && self.target_yield_g.is_none()
            && self.steam_temperature_c.is_none()
            && self.steam_max_time_s.is_none()
            && self.hot_water_temperature_c.is_none()
            && self.hot_water_volume_ml.is_none()
            && self.flush_flow_ml_per_s.is_none()
            && self.flush_seconds.is_none()
    }
}

/// Parse a legacy de1app `settings.tdb` file. Every key Crema knows
/// about is mapped onto a field of the returned struct; unknown keys
/// are ignored.
///
/// # Errors
///
/// [`ImportError::Tcl`] if the source is not a parseable Tcl-dict
/// stream.
pub fn import_settings_tdb(content: &str) -> Result<ImportedDe1AppSettings, ImportError> {
    let dict = TclDict::parse(content)?;

    let wake_seconds = dict.get("scheduler_wake").and_then(|s| s.trim().parse::<u32>().ok());
    let sleep_seconds = dict.get("scheduler_sleep").and_then(|s| s.trim().parse::<u32>().ok());

    let (wake_hour, wake_minute) = match wake_seconds {
        Some(s) => (
            u8::try_from(s / 3600 % 24).ok(),
            u8::try_from(s / 60 % 60).ok(),
        ),
        None => (None, None),
    };

    let keep_awake_minutes = match (wake_seconds, sleep_seconds) {
        (Some(wake), Some(sleep)) => {
            // Wrap at 24 h when sleep is the next day.
            let day = 86_400u32;
            let diff = (sleep + day - wake) % day;
            Some(diff / 60)
        }
        _ => None,
    };

    let sleep_timeout_minutes = dict
        .get_f32("screen_saver_delay")
        .map(|m| snap_to_sleep_option(round_clamped_i32(m)));

    Ok(ImportedDe1AppSettings {
        wake_schedule_enabled: parse_bool(&dict, "scheduler_enable"),
        wake_hour,
        wake_minute,
        keep_awake_minutes,
        keep_scale_on: parse_bool(&dict, "keep_scale_on"),
        sleep_timeout_minutes,
        charging_mode: parse_charging_mode(&dict),
        dose_g: positive_f32(dict.get_f32("grinder_dose_weight")),
        grinder_setting: non_empty_non_zero(dict.get("grinder_setting")),
        grinder_model: non_empty(dict.get("grinder_model")),
        target_yield_g: positive_f32(dict.get_f32("final_desired_shot_weight_advanced")),
        steam_temperature_c: dict.get_f32("steam_temperature").map(round_clamped_u8),
        steam_max_time_s: dict.get_f32("steam_max_time").map(round_clamped_u32),
        hot_water_temperature_c: dict.get_f32("water_temperature").map(round_clamped_u8),
        hot_water_volume_ml: dict.get_f32("water_volume").map(round_clamped_u32),
        flush_flow_ml_per_s: dict.get_f32("flush_flow"),
        flush_seconds: dict.get_f32("flush_seconds").map(round_clamped_u32),
        machine_bluetooth_address: non_empty(dict.get("bluetooth_address")),
        scale_bluetooth_address: non_empty(dict.get("scale_bluetooth_address")),
    })
}

/// Parse a TCL `1`/`0` boolean — anything else is `None`.
fn parse_bool(dict: &TclDict, key: &str) -> Option<bool> {
    match dict.get(key).map(str::trim) {
        Some("1") => Some(true),
        Some("0") => Some(false),
        _ => None,
    }
}

/// Map the legacy `smart_battery_charging` integer to a Crema-side id.
/// Same coding as reaprime: `0` disabled, `1` longevity, `2`
/// high-availability. Unknown / missing → `None`.
fn parse_charging_mode(dict: &TclDict) -> Option<u8> {
    match dict.get("smart_battery_charging").map(str::trim) {
        Some("0") => Some(0),
        Some("1") => Some(1),
        Some("2") => Some(2),
        _ => None,
    }
}

/// `Some(value)` only when the optional float is strictly positive.
fn positive_f32(value: Option<f32>) -> Option<f32> {
    value.filter(|v| *v > 0.0)
}

/// `Some(string)` only when the value is non-empty after trimming.
fn non_empty(value: Option<&str>) -> Option<String> {
    let s = value.map(str::trim).filter(|s| !s.is_empty());
    s.map(str::to_string)
}

/// Like [`non_empty`] but also rejects the literal `"0"` (legacy
/// sentinel for "unset" on the grinder-setting field).
fn non_empty_non_zero(value: Option<&str>) -> Option<String> {
    let s = value.map(str::trim).filter(|s| !s.is_empty() && *s != "0");
    s.map(str::to_string)
}

/// Round + clamp an `f32` to a `u8`. Negative + NaN values clamp to
/// 0; out-of-range positives clamp to `u8::MAX`.
#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn round_clamped_u8(v: f32) -> u8 {
    v.round().clamp(0.0, f32::from(u8::MAX)) as u8
}

/// Round + clamp an `f32` to a `u32`.
#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn round_clamped_u32(v: f32) -> u32 {
    // Settings fields are well within 24 bits; cap at 2^24 so the
    // cast is precision-safe (f32's mantissa is 23 bits).
    const MAX: f32 = 16_777_215.0; // 2^24 − 1
    v.round().clamp(0.0, MAX) as u32
}

/// Round + clamp an `f32` to an `i32`. Caps at 2^23 so the cast is
/// precision-safe — well above any legacy screen-saver value.
#[allow(clippy::cast_possible_truncation)]
fn round_clamped_i32(v: f32) -> i32 {
    const LIMIT: f32 = 8_388_608.0; // 2^23
    v.round().clamp(-LIMIT, LIMIT) as i32
}

/// Snap the imported screen-saver timeout to one of the discrete
/// values Crema's UI ships, matching reaprime's behaviour.
fn snap_to_sleep_option(minutes: i32) -> u32 {
    const OPTIONS: [i32; 8] = [0, 15, 30, 45, 60, 90, 120, 180];
    let mut closest = OPTIONS[0];
    let mut best = (minutes - closest).abs();
    for &opt in &OPTIONS[1..] {
        let diff = (minutes - opt).abs();
        if diff < best {
            best = diff;
            closest = opt;
        }
    }
    u32::try_from(closest).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_input_yields_an_empty_result() {
        let r = import_settings_tdb("").expect("empty parses");
        assert!(r.is_empty());
    }

    #[test]
    fn parses_wake_schedule() {
        // 07:05 wake, sleep at 19:05 → keep awake for 12 h = 720 min.
        let s = "scheduler_enable 1\nscheduler_wake 25500\nscheduler_sleep 68700\n";
        let r = import_settings_tdb(s).unwrap();
        assert_eq!(r.wake_schedule_enabled, Some(true));
        assert_eq!(r.wake_hour, Some(7));
        assert_eq!(r.wake_minute, Some(5));
        assert_eq!(r.keep_awake_minutes, Some(720));
    }

    #[test]
    fn wake_schedule_wraps_at_midnight() {
        // wake 23:00 (82800), sleep 01:00 next day (3600) → keep awake
        // (3600 + 86400 − 82800) / 60 = 120 min.
        let s = "scheduler_wake 82800\nscheduler_sleep 3600\n";
        let r = import_settings_tdb(s).unwrap();
        assert_eq!(r.wake_hour, Some(23));
        assert_eq!(r.wake_minute, Some(0));
        assert_eq!(r.keep_awake_minutes, Some(120));
    }

    #[test]
    fn parses_scale_and_sleep_timeout() {
        let s = "keep_scale_on 1\nscreen_saver_delay 30\n";
        let r = import_settings_tdb(s).unwrap();
        assert_eq!(r.keep_scale_on, Some(true));
        assert_eq!(r.sleep_timeout_minutes, Some(30));
    }

    #[test]
    fn screen_saver_snaps_to_nearest_option() {
        assert_eq!(
            import_settings_tdb("screen_saver_delay 2\n")
                .unwrap()
                .sleep_timeout_minutes,
            Some(0)
        );
        assert_eq!(
            import_settings_tdb("screen_saver_delay 10\n")
                .unwrap()
                .sleep_timeout_minutes,
            Some(15)
        );
        assert_eq!(
            import_settings_tdb("screen_saver_delay 100\n")
                .unwrap()
                .sleep_timeout_minutes,
            Some(90)
        );
    }

    #[test]
    fn parses_charging_mode() {
        assert_eq!(
            import_settings_tdb("smart_battery_charging 0\n")
                .unwrap()
                .charging_mode,
            Some(0)
        );
        assert_eq!(
            import_settings_tdb("smart_battery_charging 1\n")
                .unwrap()
                .charging_mode,
            Some(1)
        );
        assert_eq!(
            import_settings_tdb("smart_battery_charging 2\n")
                .unwrap()
                .charging_mode,
            Some(2)
        );
        // Unknown values stay None.
        assert_eq!(
            import_settings_tdb("smart_battery_charging 99\n")
                .unwrap()
                .charging_mode,
            None
        );
    }

    #[test]
    fn parses_brew_defaults() {
        // Multi-word values are braced in the legacy `save_array_to_file`
        // dump — single-word values are bare. Both must work.
        let s = "grinder_dose_weight 18.5\n\
                 grinder_setting 4.2\n\
                 grinder_model {Niche Zero}\n\
                 final_desired_shot_weight_advanced 36.0\n";
        let r = import_settings_tdb(s).unwrap();
        assert_eq!(r.dose_g, Some(18.5));
        assert_eq!(r.grinder_setting.as_deref(), Some("4.2"));
        assert_eq!(r.grinder_model.as_deref(), Some("Niche Zero"));
        assert_eq!(r.target_yield_g, Some(36.0));
    }

    #[test]
    fn drops_zero_sentinels() {
        // `0` dose / `0` yield are legacy sentinels meaning "unset" —
        // they should not surface as Some(0.0).
        let s = "grinder_dose_weight 0\n\
                 final_desired_shot_weight_advanced 0\n\
                 grinder_setting 0\n";
        let r = import_settings_tdb(s).unwrap();
        assert_eq!(r.dose_g, None);
        assert_eq!(r.target_yield_g, None);
        assert_eq!(r.grinder_setting, None);
    }

    #[test]
    fn parses_steam_and_water_and_flush() {
        let s = "steam_temperature 150\n\
                 steam_max_time 90\n\
                 water_temperature 92\n\
                 water_volume 250\n\
                 flush_flow 6.0\n\
                 flush_seconds 4\n";
        let r = import_settings_tdb(s).unwrap();
        assert_eq!(r.steam_temperature_c, Some(150));
        assert_eq!(r.steam_max_time_s, Some(90));
        assert_eq!(r.hot_water_temperature_c, Some(92));
        assert_eq!(r.hot_water_volume_ml, Some(250));
        assert_eq!(r.flush_flow_ml_per_s, Some(6.0));
        assert_eq!(r.flush_seconds, Some(4));
    }

    #[test]
    fn parses_bluetooth_addresses() {
        let s = "bluetooth_address {AA:BB:CC:DD:EE:FF}\n\
                 scale_bluetooth_address {11:22:33:44:55:66}\n";
        let r = import_settings_tdb(s).unwrap();
        assert_eq!(
            r.machine_bluetooth_address.as_deref(),
            Some("AA:BB:CC:DD:EE:FF")
        );
        assert_eq!(
            r.scale_bluetooth_address.as_deref(),
            Some("11:22:33:44:55:66")
        );
    }

    #[test]
    fn unknown_keys_are_ignored() {
        let s = "completely_unknown_key value\n\
                 another_one 42\n\
                 grinder_dose_weight 19\n";
        let r = import_settings_tdb(s).unwrap();
        assert_eq!(r.dose_g, Some(19.0));
        // Unknown keys didn't crash; the result has only the one
        // recognised field set.
        assert_eq!(r.target_yield_g, None);
    }

    #[test]
    fn is_empty_recognises_actionable_state() {
        let r = ImportedDe1AppSettings::default();
        assert!(r.is_empty());
        let r2 = ImportedDe1AppSettings {
            dose_g: Some(18.0),
            ..ImportedDe1AppSettings::default()
        };
        assert!(!r2.is_empty());
    }
}

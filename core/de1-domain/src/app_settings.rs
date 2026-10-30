//! Shared cross-shell **app-preferences** core — the portable subset of each
//! shell's settings that a whole-app backup carries, as one `#[typeshare]` shape
//! so web and Android serialise byte-identical JSON.
//!
//! This is the "common" half of the **common + per-shell extension** design: a
//! backup's settings line carries [`CommonSettings`] (cross-shell, untagged) plus
//! an `_shell`-tagged platform blob for each shell's device-specific extras (BLE
//! addresses, LAN proxy, webhooks, density, …) which only re-apply on a matching
//! shell. See `.scratch/backup-restore/SETTINGS-UNIFICATION-DESIGN.md`.
//!
//! The canonical field set + names mirror Android's `AppPrefs` (so Android maps
//! near-identity); the web shell maps its differently-named / differently-shaped
//! fields in and out (`theme`↔`themeMode`, `autoTareOnShotStart`↔`autoTare`,
//! `steamEcoMode`↔`steamEco`, `groupFlushBeforeShot`↔`preFlush`,
//! `autoPurgeAfterSteam`↔`steamPurge`, `defaultPreinfusionS`↔`defaultPreinfuseS`,
//! and the eight `show*` chart booleans ↔ [`CommonSettings::chart_channels`],
//! with the `dispensedVolume`/`volume` key alias).
//!
//! NOT here (per-device / per-shell platform extras, kept shell-local and
//! `_shell`-tagged in a backup): BLE addresses, `activeProfileId`, LAN-proxy /
//! paired-devices, `qcGrind` (Android-only), and web's `density` / `screensaver`
//! / `telemetryRateHz` / `lineFrequencyHz` / webhooks / `smoothPressure`.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::hot_water::{HotWaterMode, StoredHotWater};

/// Guided-brew cue sound when the user never chose (issue #10): off.
pub const DEFAULT_BREW_CUE_SOUND: bool = false;
/// Guided-brew cue haptics when the user never chose (issue #10): on.
pub const DEFAULT_BREW_CUE_HAPTICS: bool = true;

/// The portable, cross-shell app-preferences subset. `#[serde(default)]` on the
/// whole struct (via [`Default`]) so a partial blob — an older backup, or one
/// shell omitting a field it shares — fills gaps from the canonical defaults
/// rather than failing.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct CommonSettings {
    /// `"system" | "light" | "dark"`. Web `theme`.
    pub theme_mode: String,
    /// Global max shot duration, seconds (`0` = none). Default off — the
    /// profile dictates shot length; a silent global cap truncated long
    /// profiles with no cue (geota/crema#32).
    pub max_shot_duration_s: f32,
    /// Auto-tare the scale on shot start. Web `autoTareOnShotStart`.
    pub auto_tare: bool,
    /// Enable stop-at-weight. Web `stopOnWeight`.
    pub stop_on_weight: bool,
    /// Opt-in: arm the profile's volume limit (stop-at-volume) even while a
    /// scale is connected. Default off — volume is a no-scale fallback,
    /// never a competitor to stop-at-weight (the reference-app consensus;
    /// mirrors Decenza's `ignoreVolumeWithScale`, inverted sense).
    /// `Option` (not a bare bool) so the generated Kotlin field defaults to
    /// null and a pre-existing `prefs.json` / older backup still decodes —
    /// `None` means "unset", which every consumer reads as `false`.
    pub volume_stop_with_scale: Option<bool>,
    /// Opt-in: refuse to start an espresso shot when stop-at-weight would
    /// arm (a weight target resolves) but no scale is connected — the
    /// de1app `start_espresso_only_if_scale_connected` / reaprime
    /// `blockOnNoScale` pattern, both also default-off (geota/crema#29).
    /// Off = warn non-blockingly instead. `None` means "unset", read as
    /// `false`.
    pub require_scale: Option<bool>,
    /// Steam eco mode. Web `steamEcoMode`.
    pub steam_eco: bool,
    /// DE1 firmware two-tap steam stop (MMR `SteamTwoTapStop`): first stop
    /// tap ends steam without the wand auto-purge; a second tap purges.
    /// Written to the machine on change and re-seeded on connect. `None`
    /// means "unset", read as `false` (firmware purges immediately on stop).
    pub steam_two_tap: Option<bool>,
    /// Fan-on temperature threshold, °C (MMR `FanThreshold`, clamped 0..=60
    /// — de1app/Decenza's range). Re-seeded on every connect — the DE1
    /// boots with a low firmware default and de1app rewrote it each
    /// connect, so without this the fan runs near-constantly under Crema
    /// (geota/crema#31). `None` means "unset", read as the 55 °C default
    /// (splitting de1app/Decenza's 60 and reaprime's 50).
    pub fan_threshold_c: Option<f32>,
    /// Flush the group before each shot. Web `groupFlushBeforeShot`.
    pub pre_flush: bool,
    /// Run a short purge after steaming. Web `autoPurgeAfterSteam`.
    pub steam_purge: bool,
    /// Weight unit `"g" | "oz"`.
    pub weight_unit: String,
    /// Temperature unit `"C" | "F"`.
    pub temp_unit: String,
    /// Pressure unit `"bar" | "psi"`.
    pub pressure_unit: String,
    /// Volume unit `"ml" | "floz"`.
    pub volume_unit: String,
    /// Water-tank readout style `"ml" | "percent"`. `None` means "unset",
    /// read as `"ml"`.
    pub water_level_unit: Option<String>,
    /// Low-water warning threshold, ml of remaining tank water — the shells
    /// notify once per dip when the level crosses below it (issue #33
    /// follow-up). Canonical ml regardless of `water_level_unit`; the
    /// settings UI dials it in the user's chosen unit. `None` means
    /// "unset", read as the 110 ml default (~10% of a typical full fill);
    /// `0` disables the warning.
    pub water_warn_ml: Option<f32>,
    /// The machine's own refill threshold, RAW sensor mm — written to the
    /// DE1's `WaterLevels` `StartFillLevel` at connect. This is the level at
    /// which the machine itself blinks for water and refuses to pour, so a
    /// stale high value left in the firmware makes the DE1 demand a refill
    /// with a half-full tank (geota/crema#47). de1app
    /// (`settings(water_refill_point)`) and Decenza (`water/refillPoint`)
    /// both push it at connect and default to 5 mm; `None` means "unset",
    /// read as `tank::DEFAULT_REFILL_POINT_MM`.
    pub water_refill_point_mm: Option<f32>,
    /// Tablet charging from the DE1's USB port (Decenza `BatteryManager`
    /// modes): `"alwaysOn"` (the default, as decaid), `"smart"` (55–65 %)
    /// or `"smartHigh"` (90–95 %). Asserted on connect and every minute
    /// after. `None` means "unset", read as `"alwaysOn"`.
    pub usb_charging_mode: Option<String>,
    /// Enabled live-chart channel keys (Android's vocabulary:
    /// `pressure`/`flow`/`weight`/`headTemp`/`mixTemp`/`weightFlow`/`resistance`/
    /// `dispensedVolume`). Web maps its eight `show*` booleans to/from this list
    /// (its `volume` ↔ `dispensedVolume`).
    pub chart_channels: Vec<String>,
    /// Hold the screen awake while a shot pulls.
    pub keep_screen_on_brew: bool,
    /// Guided-brew step cues as sound (issue #10). `None` = never set,
    /// read as [`DEFAULT_BREW_CUE_SOUND`] (off: a kitchen-safe default; the
    /// session's visual cues are always on). Option per the
    /// additive-CommonSettings rule so older backups round-trip, and so a
    /// user who never touched it follows the default rather than a frozen
    /// copy of it. Shells persist only an explicit choice.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub brew_cue_sound: Option<bool>,
    /// Guided-brew step cues as vibration, where the device supports it.
    /// `None` = never set, read as [`DEFAULT_BREW_CUE_HAPTICS`] (on).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub brew_cue_haptics: Option<bool>,
    /// Show the debug / event-log panel.
    pub show_debug_panel: bool,
    /// Default dose for new profiles, grams.
    pub default_dose_g: f32,
    /// Default yield-to-dose ratio (the `x` in `1:x`).
    pub default_ratio: f32,
    /// Default group temperature, °C.
    pub default_brew_temp_c: f32,
    /// Default pre-infusion time, seconds. Web `defaultPreinfusionS`.
    pub default_preinfuse_s: f32,
    /// Free-text equipment grinder model.
    pub grinder_model: String,
    /// Default barista name, stamped on each new shot's
    /// [`ShotMetadata::barista`](crate::ShotMetadata::barista). `None` (or
    /// blank) = no default; `Option` so an older `prefs.json` / backup still
    /// decodes.
    pub barista_name: Option<String>,
    /// **Retired** — ignored by every shell. It drove the old "keep the DE1
    /// awake while Crema is open" heartbeat, whose premise (a firmware
    /// sleep timer taking over when the pokes stop) does not hold on a stock
    /// DE1; presence is now poked on real activity only and the app owns
    /// sleep ([`idle_sleep_min`](Self::idle_sleep_min)). Still written
    /// (`true`) so a backup stays readable by older versions, which require
    /// the field.
    pub suppress_de1_sleep: bool,
    /// The idle-sleep timeout ("Sleep after"), minutes — one of
    /// [`IDLE_SLEEP_CHOICES_MIN`](crate::idle_sleep::IDLE_SLEEP_CHOICES_MIN),
    /// `0` = Never. `None` = never set: the shell migrates its old setting
    /// (Android's screensaver-coupled sleep) or uses
    /// [`DEFAULT_IDLE_SLEEP_MIN`](crate::idle_sleep::DEFAULT_IDLE_SLEEP_MIN).
    /// de1app `screen_saver_delay`, Decenza `autoSleepMinutes`, decaid
    /// `sleepTimeoutMinutes`.
    pub idle_sleep_min: Option<u32>,
    /// Quick-Controls steam duration, seconds.
    pub qc_steam_time_s: f32,
    /// Quick-Controls steam flow, ml/s.
    pub qc_steam_flow_ml_s: f32,
    /// Quick-Controls steam temperature, °C.
    pub qc_steam_temp_c: f32,
    /// Quick-Controls hot-water temperature, °C.
    pub qc_hot_water_temp_c: f32,
    /// Quick-Controls hot-water volume, ml: the volume-mode value.
    pub qc_hot_water_volume_ml: f32,
    /// Quick-Controls hot-water mode. `None` in settings from before the
    /// modes: the load-time migration ([`crate::initial_hot_water_dose`])
    /// decides, and the shells persist its result once. Like the three
    /// fields after it, an `Option` so older blobs and backups decode.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub qc_hot_water_mode: Option<HotWaterMode>,
    /// Quick-Controls hot-water time-mode seconds.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub qc_hot_water_time_s: Option<f32>,
    /// Quick-Controls hot-water time mode pours until stopped (honoured only
    /// when the firmware has a value for it).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub qc_hot_water_time_no_limit: Option<bool>,
    /// Quick-Controls hot-water ratio: water per gram of shot.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub qc_hot_water_ratio: Option<f32>,
    /// Quick-Controls group-flush duration, seconds.
    pub qc_flush_time_s: f32,
    /// Quick-Controls group-flush temperature, °C.
    pub qc_flush_temp_c: f32,
}

impl Default for CommonSettings {
    fn default() -> Self {
        Self {
            theme_mode: "dark".to_owned(),
            max_shot_duration_s: 0.0,
            auto_tare: true,
            stop_on_weight: true,
            volume_stop_with_scale: None,
            require_scale: None,
            steam_eco: false,
            steam_two_tap: None,
            fan_threshold_c: None,
            pre_flush: false,
            steam_purge: false,
            weight_unit: "g".to_owned(),
            temp_unit: "C".to_owned(),
            pressure_unit: "bar".to_owned(),
            volume_unit: "ml".to_owned(),
            water_level_unit: None,
            water_warn_ml: None,
            water_refill_point_mm: None,
            usb_charging_mode: None,
            chart_channels: vec![
                "pressure".to_owned(),
                "flow".to_owned(),
                "weight".to_owned(),
            ],
            keep_screen_on_brew: false,
            brew_cue_sound: None,
            brew_cue_haptics: None,
            show_debug_panel: false,
            default_dose_g: 18.0,
            default_ratio: 2.0,
            default_brew_temp_c: 93.0,
            default_preinfuse_s: 8.0,
            grinder_model: String::new(),
            barista_name: None,
            suppress_de1_sleep: true,
            idle_sleep_min: None,
            qc_steam_time_s: 12.0,
            qc_steam_flow_ml_s: 1.2,
            qc_steam_temp_c: 148.0,
            qc_hot_water_temp_c: 80.0,
            qc_hot_water_volume_ml: 150.0,
            qc_hot_water_mode: None,
            qc_hot_water_time_s: None,
            qc_hot_water_time_no_limit: None,
            qc_hot_water_ratio: None,
            qc_flush_time_s: 4.0,
            qc_flush_temp_c: 95.0,
        }
    }
}

impl CommonSettings {
    /// The stored hot-water fields, for [`crate::initial_hot_water_dose`].
    pub fn stored_hot_water(&self) -> StoredHotWater {
        StoredHotWater {
            mode: self.qc_hot_water_mode,
            volume_ml: Some(self.qc_hot_water_volume_ml),
            time_s: self.qc_hot_water_time_s,
            time_no_limit: self.qc_hot_water_time_no_limit,
            ratio: self.qc_hot_water_ratio,
        }
    }

    /// Effective guided-brew cue sound: the explicit choice, else
    /// [`DEFAULT_BREW_CUE_SOUND`].
    pub fn brew_cue_sound_on(&self) -> bool {
        self.brew_cue_sound.unwrap_or(DEFAULT_BREW_CUE_SOUND)
    }

    /// Effective guided-brew cue haptics: the explicit choice, else
    /// [`DEFAULT_BREW_CUE_HAPTICS`].
    pub fn brew_cue_haptics_on(&self) -> bool {
        self.brew_cue_haptics.unwrap_or(DEFAULT_BREW_CUE_HAPTICS)
    }
}

/// The chart-channel keys a [`CommonSettings::chart_channels`] list may hold.
pub const CHART_CHANNEL_KEYS: [&str; 8] = [
    "pressure",
    "flow",
    "weight",
    "headTemp",
    "mixTemp",
    "weightFlow",
    "resistance",
    "dispensedVolume",
];

/// The fan threshold a shell reads for an unset one, °C.
pub const DEFAULT_FAN_THRESHOLD_C: f32 = 55.0;
/// The fan-threshold range the DE1 accepts, °C (de1app / Decenza).
pub const FAN_THRESHOLD_MAX_C: f32 = 60.0;

/// `value` when it is one of `allowed`, else `fallback`.
fn one_of(value: &str, allowed: &[&str], fallback: &str) -> String {
    if allowed.contains(&value) {
        value
    } else {
        fallback
    }
    .to_owned()
}

/// `value` when finite and `> 0`, else `fallback`.
fn positive_or(value: f32, fallback: f32) -> f32 {
    if value.is_finite() && value > 0.0 {
        value
    } else {
        fallback
    }
}

impl CommonSettings {
    /// These settings with every value a shell can rely on, the rule both
    /// shells apply on load and on restore. It repairs, never rejects:
    ///
    /// - the "unset reads as" fields are resolved: `volume_stop_with_scale`,
    ///   `require_scale` and `steam_two_tap` to `false`, `fan_threshold_c` to
    ///   [`DEFAULT_FAN_THRESHOLD_C`], `water_level_unit` to `ml`,
    ///   `usb_charging_mode` to `alwaysOn`. The unsets that mean something stay
    ///   (`brew_cue_*` follow the default, `idle_sleep_min` is migrated,
    ///   `water_warn_ml` / `water_refill_point_mm` use the core default,
    ///   `barista_name` is none);
    /// - an unknown theme, unit or charging mode becomes the default;
    /// - `max_shot_duration_s` and `water_warn_ml` must be finite and `>= 0`
    ///   (a bad threshold becomes unset); `fan_threshold_c` is clamped to
    ///   `0..=60`, `water_refill_point_mm` to the refill range;
    ///   `idle_sleep_min` snaps to a choice;
    /// - `chart_channels` keeps the known keys ([`CHART_CHANNEL_KEYS`]), once;
    /// - a brew default or Quick-Controls value that isn't finite and `> 0`
    ///   becomes the default.
    ///
    /// UI ranges are not enforced here.
    #[must_use]
    pub fn sanitized(self) -> Self {
        let d = Self::default();
        let mut channels: Vec<String> = Vec::new();
        for c in self.chart_channels {
            if CHART_CHANNEL_KEYS.contains(&c.as_str()) && !channels.contains(&c) {
                channels.push(c);
            }
        }
        Self {
            theme_mode: one_of(
                &self.theme_mode,
                &["system", "light", "dark"],
                &d.theme_mode,
            ),
            max_shot_duration_s: if self.max_shot_duration_s.is_finite()
                && self.max_shot_duration_s >= 0.0
            {
                self.max_shot_duration_s
            } else {
                d.max_shot_duration_s
            },
            volume_stop_with_scale: Some(self.volume_stop_with_scale.unwrap_or(false)),
            require_scale: Some(self.require_scale.unwrap_or(false)),
            steam_two_tap: Some(self.steam_two_tap.unwrap_or(false)),
            fan_threshold_c: Some(
                self.fan_threshold_c
                    .filter(|f| f.is_finite())
                    .map_or(DEFAULT_FAN_THRESHOLD_C, |f| {
                        f.clamp(0.0, FAN_THRESHOLD_MAX_C)
                    }),
            ),
            weight_unit: one_of(&self.weight_unit, &["g", "oz"], &d.weight_unit),
            temp_unit: one_of(&self.temp_unit, &["C", "F"], &d.temp_unit),
            pressure_unit: one_of(&self.pressure_unit, &["bar", "psi"], &d.pressure_unit),
            volume_unit: one_of(&self.volume_unit, &["ml", "floz"], &d.volume_unit),
            water_level_unit: Some(one_of(
                self.water_level_unit.as_deref().unwrap_or("ml"),
                &["ml", "percent"],
                "ml",
            )),
            water_warn_ml: self.water_warn_ml.filter(|w| w.is_finite() && *w >= 0.0),
            water_refill_point_mm: self
                .water_refill_point_mm
                .filter(|r| r.is_finite())
                .map(|r| {
                    r.clamp(
                        crate::tank::REFILL_POINT_MIN_MM,
                        crate::tank::REFILL_POINT_MAX_MM,
                    )
                }),
            usb_charging_mode: Some(one_of(
                self.usb_charging_mode.as_deref().unwrap_or("alwaysOn"),
                &["alwaysOn", "smart", "smartHigh"],
                "alwaysOn",
            )),
            chart_channels: channels,
            idle_sleep_min: self
                .idle_sleep_min
                .map(crate::idle_sleep::snap_idle_sleep_minutes),
            default_dose_g: positive_or(self.default_dose_g, d.default_dose_g),
            default_ratio: positive_or(self.default_ratio, d.default_ratio),
            default_brew_temp_c: positive_or(self.default_brew_temp_c, d.default_brew_temp_c),
            default_preinfuse_s: if self.default_preinfuse_s.is_finite()
                && self.default_preinfuse_s >= 0.0
            {
                self.default_preinfuse_s
            } else {
                d.default_preinfuse_s
            },
            qc_steam_time_s: positive_or(self.qc_steam_time_s, d.qc_steam_time_s),
            qc_steam_flow_ml_s: positive_or(self.qc_steam_flow_ml_s, d.qc_steam_flow_ml_s),
            qc_steam_temp_c: positive_or(self.qc_steam_temp_c, d.qc_steam_temp_c),
            qc_hot_water_temp_c: positive_or(self.qc_hot_water_temp_c, d.qc_hot_water_temp_c),
            qc_hot_water_volume_ml: positive_or(
                self.qc_hot_water_volume_ml,
                d.qc_hot_water_volume_ml,
            ),
            qc_flush_time_s: positive_or(self.qc_flush_time_s, d.qc_flush_time_s),
            qc_flush_temp_c: positive_or(self.qc_flush_temp_c, d.qc_flush_temp_c),
            ..self
        }
    }
}

/// The default settings bundle every shell builds its defaults from:
/// [`CommonSettings::default`] with the "unset reads as" fields resolved
/// ([`CommonSettings::sanitized`]).
#[must_use]
pub fn default_common_settings() -> CommonSettings {
    CommonSettings::default().sanitized()
}

/// Read a stored or restored settings blob leniently: start from the
/// defaults and take each known field whose value has the right type, so one
/// wrong-typed field falls back to its default without dropping the rest;
/// then [`CommonSettings::sanitized`]. A blob that isn't a JSON object gives
/// the defaults.
#[must_use]
pub fn sanitize_common_settings(blob: &serde_json::Value) -> CommonSettings {
    let Some(fields) = blob.as_object() else {
        return default_common_settings();
    };
    let mut base = match serde_json::to_value(CommonSettings::default()) {
        Ok(serde_json::Value::Object(map)) => map,
        _ => serde_json::Map::new(),
    };
    for (key, value) in fields {
        let mut trial = base.clone();
        trial.insert(key.clone(), value.clone());
        if serde_json::from_value::<CommonSettings>(serde_json::Value::Object(trial.clone()))
            .is_ok()
        {
            base = trial;
        }
    }
    serde_json::from_value::<CommonSettings>(serde_json::Value::Object(base))
        .unwrap_or_default()
        .sanitized()
}

/// What "Restore defaults" leaves: every common setting back to the default
/// bundle except the two facts about the user's setup it keeps, the weight
/// unit (it matches their scale) and the mains line frequency. The idle-sleep
/// timer gets its default explicitly.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SettingsReset {
    /// The reset common settings.
    pub common: CommonSettings,
    /// The line-frequency override kept from before, Hz: `50`, `60`, or `0`
    /// for auto (anything else reads as auto).
    pub line_frequency_hz: f32,
}

/// The "Restore defaults" rule both shells use. See [`SettingsReset`].
#[must_use]
pub fn reset_settings(current: &CommonSettings, line_frequency_hz: f32) -> SettingsReset {
    let mut common = default_common_settings();
    common.weight_unit = one_of(&current.weight_unit, &["g", "oz"], &common.weight_unit);
    common.idle_sleep_min = Some(crate::idle_sleep::DEFAULT_IDLE_SLEEP_MIN);
    let line_frequency_hz = if line_frequency_hz == 50.0 || line_frequency_hz == 60.0 {
        line_frequency_hz
    } else {
        0.0
    };
    SettingsReset {
        common,
        line_frequency_hz,
    }
}

/// [`default_common_settings`] as JSON.
#[must_use]
pub fn default_common_settings_json() -> String {
    serde_json::to_string(&default_common_settings()).unwrap_or_default()
}

/// JSON-bridged [`sanitize_common_settings`]: any JSON in, a
/// [`CommonSettings`] JSON out. Malformed JSON gives the defaults.
#[must_use]
pub fn sanitize_common_settings_json(blob_json: &str) -> String {
    let blob = serde_json::from_str(blob_json).unwrap_or(serde_json::Value::Null);
    serde_json::to_string(&sanitize_common_settings(&blob)).unwrap_or_default()
}

/// JSON-bridged [`reset_settings`]: the current settings blob (read through
/// [`sanitize_common_settings`]) and line frequency in, a [`SettingsReset`]
/// JSON out.
#[must_use]
pub fn reset_settings_json(current_json: &str, line_frequency_hz: f32) -> String {
    let blob = serde_json::from_str(current_json).unwrap_or(serde_json::Value::Null);
    let current = sanitize_common_settings(&blob);
    serde_json::to_string(&reset_settings(&current, line_frequency_hz)).unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn brew_cues_default_to_haptics_on_sound_off() {
        let d = CommonSettings::default();
        assert_eq!(d.brew_cue_sound, None);
        assert_eq!(d.brew_cue_haptics, None);
        assert!(!d.brew_cue_sound_on());
        assert!(d.brew_cue_haptics_on());
    }

    #[test]
    fn untouched_brew_cues_stay_unset_through_a_round_trip() {
        // A blob that never carried the fields (an older backup, or a user
        // who never touched them) reads as the defaults — and serialises
        // without them, so a later default change still reaches that user.
        let c: CommonSettings = serde_json::from_str(r#"{"themeMode":"light"}"#).unwrap();
        assert!(!c.brew_cue_sound_on());
        assert!(c.brew_cue_haptics_on());
        let json = serde_json::to_string(&c).unwrap();
        assert!(!json.contains("brewCueSound"), "{json}");
        assert!(!json.contains("brewCueHaptics"), "{json}");
    }

    /// AC-HW8: a blob from before the hot-water modes decodes, and the
    /// migration follows the never-changed rule.
    #[test]
    fn old_blob_migrates() {
        use crate::hot_water::{HOT_WATER_FIRMWARE, initial_hot_water_dose};
        let fw = &HOT_WATER_FIRMWARE;
        let c: CommonSettings =
            serde_json::from_str(r#"{"qcHotWaterVolumeMl":150,"qcHotWaterTempC":80}"#).unwrap();
        assert_eq!(c.qc_hot_water_mode, None);
        let d = initial_hot_water_dose(&c.stored_hot_water(), None, fw);
        assert_eq!((d.mode, d.ratio), (HotWaterMode::Ratio, 3.0));
        let c: CommonSettings = serde_json::from_str(r#"{"qcHotWaterVolumeMl":200}"#).unwrap();
        let d = initial_hot_water_dose(&c.stored_hot_water(), None, fw);
        assert_eq!((d.mode, d.volume_ml), (HotWaterMode::Volume, 200.0));
        // Unset fields aren't written, so an older build reads the blob as
        // before; a set mode round-trips.
        let json = serde_json::to_string(&CommonSettings::default()).unwrap();
        assert!(!json.contains("qcHotWaterMode"), "{json}");
        let set = CommonSettings {
            qc_hot_water_mode: Some(HotWaterMode::Time),
            qc_hot_water_time_s: Some(45.0),
            ..CommonSettings::default()
        };
        let back: CommonSettings =
            serde_json::from_str(&serde_json::to_string(&set).unwrap()).unwrap();
        assert_eq!(back, set);
    }

    #[test]
    fn explicit_brew_cue_choices_win_and_round_trip() {
        let c: CommonSettings =
            serde_json::from_str(r#"{"brewCueSound":true,"brewCueHaptics":false}"#).unwrap();
        assert!(c.brew_cue_sound_on());
        assert!(!c.brew_cue_haptics_on());
        let back: CommonSettings =
            serde_json::from_str(&serde_json::to_string(&c).unwrap()).unwrap();
        assert_eq!(back, c);
    }

    // -- F15: defaults, sanitize, reset --------------------------------------

    #[test]
    fn default_bundle_is_the_canonical_default_resolved() {
        let d = default_common_settings();
        assert_eq!(
            d,
            CommonSettings {
                volume_stop_with_scale: Some(false),
                require_scale: Some(false),
                steam_two_tap: Some(false),
                fan_threshold_c: Some(55.0),
                water_level_unit: Some("ml".to_owned()),
                usb_charging_mode: Some("alwaysOn".to_owned()),
                ..CommonSettings::default()
            }
        );
        // The unsets that mean something stay unset.
        assert_eq!(d.brew_cue_sound, None);
        assert_eq!(d.idle_sleep_min, None);
        assert_eq!(d.water_warn_ml, None);
        assert_eq!(d.water_refill_point_mm, None);
        assert_eq!(d.barista_name, None);
        assert_eq!(d.clone().sanitized(), d, "sanitize is idempotent");
    }

    #[test]
    fn sanitize_repairs_unknown_enums_and_bad_numbers() {
        let bad = CommonSettings {
            theme_mode: "neon".into(),
            weight_unit: "kg".into(),
            temp_unit: "K".into(),
            pressure_unit: "atm".into(),
            volume_unit: "cup".into(),
            water_level_unit: Some("litres".into()),
            usb_charging_mode: Some("turbo".into()),
            max_shot_duration_s: -5.0,
            water_warn_ml: Some(-1.0),
            default_dose_g: f32::NAN,
            default_ratio: 0.0,
            default_preinfuse_s: -1.0,
            qc_steam_temp_c: f32::INFINITY,
            qc_hot_water_volume_ml: -10.0,
            chart_channels: vec![
                "pressure".into(),
                "bogus".into(),
                "pressure".into(),
                "flow".into(),
            ],
            ..CommonSettings::default()
        };
        let d = default_common_settings();
        let s = bad.sanitized();
        assert_eq!(
            (
                &*s.theme_mode,
                &*s.weight_unit,
                &*s.temp_unit,
                &*s.pressure_unit,
                &*s.volume_unit
            ),
            ("dark", "g", "C", "bar", "ml")
        );
        assert_eq!(s.water_level_unit.as_deref(), Some("ml"));
        assert_eq!(s.usb_charging_mode.as_deref(), Some("alwaysOn"));
        assert_eq!(s.max_shot_duration_s, 0.0);
        assert_eq!(s.water_warn_ml, None);
        assert_eq!(s.default_dose_g, d.default_dose_g);
        assert_eq!(s.default_ratio, d.default_ratio);
        assert_eq!(s.default_preinfuse_s, d.default_preinfuse_s);
        assert_eq!(s.qc_steam_temp_c, d.qc_steam_temp_c);
        assert_eq!(s.qc_hot_water_volume_ml, d.qc_hot_water_volume_ml);
        assert_eq!(s.chart_channels, ["pressure", "flow"]);
    }

    #[test]
    fn sanitize_clamps_fan_refill_and_snaps_idle_sleep() {
        let s = CommonSettings {
            fan_threshold_c: Some(90.0),
            water_refill_point_mm: Some(500.0),
            idle_sleep_min: Some(44),
            ..CommonSettings::default()
        }
        .sanitized();
        assert_eq!(s.fan_threshold_c, Some(60.0));
        assert_eq!(
            s.water_refill_point_mm,
            Some(crate::tank::REFILL_POINT_MAX_MM)
        );
        assert_eq!(s.idle_sleep_min, Some(30));
        let low = CommonSettings {
            fan_threshold_c: Some(-3.0),
            water_refill_point_mm: Some(0.5),
            ..CommonSettings::default()
        }
        .sanitized();
        assert_eq!(low.fan_threshold_c, Some(0.0));
        assert_eq!(
            low.water_refill_point_mm,
            Some(crate::tank::REFILL_POINT_MIN_MM)
        );
        let nan = CommonSettings {
            fan_threshold_c: Some(f32::NAN),
            water_refill_point_mm: Some(f32::NAN),
            ..CommonSettings::default()
        }
        .sanitized();
        assert_eq!(nan.fan_threshold_c, Some(DEFAULT_FAN_THRESHOLD_C));
        assert_eq!(nan.water_refill_point_mm, None);
    }

    #[test]
    fn sanitize_keeps_valid_values_and_meaningful_unsets() {
        let mine = CommonSettings {
            theme_mode: "light".into(),
            max_shot_duration_s: 60.0,
            auto_tare: false,
            volume_stop_with_scale: Some(true),
            fan_threshold_c: Some(40.0),
            weight_unit: "oz".into(),
            temp_unit: "F".into(),
            pressure_unit: "psi".into(),
            volume_unit: "floz".into(),
            water_level_unit: Some("percent".into()),
            water_warn_ml: Some(0.0),
            water_refill_point_mm: Some(10.0),
            usb_charging_mode: Some("smartHigh".into()),
            chart_channels: vec!["dispensedVolume".into(), "resistance".into()],
            brew_cue_haptics: Some(false),
            idle_sleep_min: Some(0),
            barista_name: Some("Ana".into()),
            default_preinfuse_s: 0.0,
            qc_steam_temp_c: 150.0,
            ..default_common_settings()
        };
        assert_eq!(mine.clone().sanitized(), mine);
    }

    #[test]
    fn sanitize_json_drops_a_wrong_typed_field_not_the_blob() {
        let blob = serde_json::json!({
            "themeMode": "light",
            "autoTare": "yes",
            "maxShotDurationS": 30,
            "chartChannels": "pressure",
            "weightUnit": "oz",
            "brewCueSound": true,
            "unknownKey": 1,
        });
        let s = sanitize_common_settings(&blob);
        assert_eq!(s.theme_mode, "light");
        assert!(s.auto_tare, "wrong type falls back to the default (true)");
        assert_eq!(s.max_shot_duration_s, 30.0);
        assert_eq!(s.chart_channels, default_common_settings().chart_channels);
        assert_eq!(s.weight_unit, "oz");
        assert_eq!(s.brew_cue_sound, Some(true));
        assert_eq!(
            sanitize_common_settings(&serde_json::json!([1, 2])),
            default_common_settings()
        );
        let out: CommonSettings =
            serde_json::from_str(&sanitize_common_settings_json("not json")).unwrap();
        assert_eq!(out, default_common_settings());
    }

    #[test]
    fn reset_keeps_the_weight_unit_and_line_frequency() {
        let mine = CommonSettings {
            theme_mode: "light".into(),
            weight_unit: "oz".into(),
            temp_unit: "F".into(),
            grinder_model: "Niche".into(),
            idle_sleep_min: Some(120),
            qc_steam_temp_c: 150.0,
            ..default_common_settings()
        };
        let r = reset_settings(&mine, 50.0);
        assert_eq!(r.line_frequency_hz, 50.0);
        assert_eq!(
            r.common,
            CommonSettings {
                weight_unit: "oz".into(),
                idle_sleep_min: Some(crate::idle_sleep::DEFAULT_IDLE_SLEEP_MIN),
                ..default_common_settings()
            }
        );
        assert_eq!(reset_settings(&mine, 60.0).line_frequency_hz, 60.0);
        assert_eq!(reset_settings(&mine, 0.0).line_frequency_hz, 0.0);
        assert_eq!(reset_settings(&mine, 55.0).line_frequency_hz, 0.0);
        let json: SettingsReset = serde_json::from_str(&reset_settings_json(
            &serde_json::to_string(&mine).unwrap(),
            60.0,
        ))
        .unwrap();
        assert_eq!(json, reset_settings(&mine, 60.0));
    }
}

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
    /// Enabled live-chart channel keys (Android's vocabulary:
    /// `pressure`/`flow`/`weight`/`headTemp`/`mixTemp`/`weightFlow`/`resistance`/
    /// `dispensedVolume`). Web maps its eight `show*` booleans to/from this list
    /// (its `volume` ↔ `dispensedVolume`).
    pub chart_channels: Vec<String>,
    /// Hold the screen awake while a shot pulls.
    pub keep_screen_on_brew: bool,
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
    /// Keep the DE1 awake while Crema is open.
    pub suppress_de1_sleep: bool,
    /// Quick-Controls steam duration, seconds.
    pub qc_steam_time_s: f32,
    /// Quick-Controls steam flow, ml/s.
    pub qc_steam_flow_ml_s: f32,
    /// Quick-Controls steam temperature, °C.
    pub qc_steam_temp_c: f32,
    /// Quick-Controls hot-water temperature, °C.
    pub qc_hot_water_temp_c: f32,
    /// Quick-Controls hot-water volume, ml.
    pub qc_hot_water_volume_ml: f32,
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
            chart_channels: vec![
                "pressure".to_owned(),
                "flow".to_owned(),
                "weight".to_owned(),
            ],
            keep_screen_on_brew: false,
            show_debug_panel: false,
            default_dose_g: 18.0,
            default_ratio: 2.0,
            default_brew_temp_c: 93.0,
            default_preinfuse_s: 8.0,
            grinder_model: String::new(),
            suppress_de1_sleep: true,
            qc_steam_time_s: 12.0,
            qc_steam_flow_ml_s: 1.2,
            qc_steam_temp_c: 148.0,
            qc_hot_water_temp_c: 80.0,
            qc_hot_water_volume_ml: 150.0,
            qc_flush_time_s: 4.0,
            qc_flush_temp_c: 95.0,
        }
    }
}

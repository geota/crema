//! Service-mode (steam / hot-water / flush) display-target resolution.
//!
//! The Brew screen's resting mode chips show the *target* values the
//! firmware will hold during the next session. Each field resolves with
//! the same precedence on every shell: **live machine value** (the
//! connect-time `ShotSettingsRead` snapshot / `FlushTemp` MMR read,
//! echoed on change) → **the user's persisted Quick-Controls dial** →
//! **legacy de1app default**. The machine value is what the firmware
//! currently has loaded; the QC value is the user's intent before the
//! read lands (or when nothing is connected).
//!
//! Previously each shell derived these independently (web
//! `BrewDashboard` `MODE_TARGET_SEC` / `MODE_TARGET_LABEL` /
//! `flushTempC`; Android `ModeTargets.kt`) and drifted — review #41
//! moves the one derivation here.

use typeshare::typeshare;

/// Legacy de1app default steam target temperature, °C.
pub const DEFAULT_STEAM_TEMP_C: f32 = 148.0;
/// Legacy de1app default steam timeout, seconds.
pub const DEFAULT_STEAM_TIMEOUT_S: f32 = 90.0;
/// Legacy de1app default hot-water target temperature, °C.
pub const DEFAULT_HOT_WATER_TEMP_C: f32 = 92.0;
/// Legacy de1app default hot-water volume, ml.
pub const DEFAULT_HOT_WATER_VOLUME_ML: f32 = 250.0;
/// Legacy de1app default hot-water timeout, seconds.
pub const DEFAULT_HOT_WATER_TIMEOUT_S: f32 = 30.0;
/// Legacy de1app default flush temperature, °C.
pub const DEFAULT_FLUSH_TEMP_C: f32 = 95.0;
/// Legacy 4 s flush window, seconds.
pub const DEFAULT_FLUSH_TIME_S: f32 = 4.0;

/// Everything the resolution consumes, all optional — a shell fills in
/// what it has. `machine_*` fields come from the DE1 itself (`None`
/// until the connect-time reads land); `qc_*` fields are the user's
/// Quick-Controls dials (`None` when never set). A literal `0` in any
/// field is treated as missing — a partial / pre-handshake
/// `ShotSettings` payload can carry zeros, which are meaningless as
/// targets.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ModeTargetInputs {
    /// Machine steam target temperature, °C (`ShotSettingsRead.steam_temp`).
    pub machine_steam_temp_c: Option<f32>,
    /// Machine steam timeout, seconds (`ShotSettingsRead.steam_timeout`).
    pub machine_steam_timeout_s: Option<f32>,
    /// Machine hot-water target temperature, °C (`ShotSettingsRead.hot_water_temp`).
    pub machine_hot_water_temp_c: Option<f32>,
    /// Machine hot-water volume, ml (`ShotSettingsRead.hot_water_volume`).
    pub machine_hot_water_volume_ml: Option<f32>,
    /// Machine hot-water timeout, seconds (`ShotSettingsRead.hot_water_timeout`).
    pub machine_hot_water_timeout_s: Option<f32>,
    /// The machine's stored flush setpoint — the raw `FlushTemp` MMR word
    /// (`0x00803844`), in **deci-°C** exactly as read off the wire; the
    /// conversion lives here, not in the shells.
    pub machine_flush_temp_deci_c: Option<f32>,
    /// Quick-Controls steam temperature, °C.
    pub qc_steam_temp_c: Option<f32>,
    /// Quick-Controls steam duration, seconds.
    pub qc_steam_time_s: Option<f32>,
    /// Quick-Controls hot-water temperature, °C.
    pub qc_hot_water_temp_c: Option<f32>,
    /// Quick-Controls hot-water volume, ml.
    pub qc_hot_water_volume_ml: Option<f32>,
    /// Quick-Controls flush temperature, °C.
    pub qc_flush_temp_c: Option<f32>,
    /// Quick-Controls flush duration, seconds.
    pub qc_flush_time_s: Option<f32>,
}

/// The resolved service-mode targets the Brew mode chips / banners show.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModeTargets {
    /// Steam target temperature, °C.
    pub steam_temp_c: f32,
    /// Steam timeout, seconds — the firmware-enforced ceiling.
    pub steam_timeout_s: f32,
    /// Hot-water target temperature, °C.
    pub hot_water_temp_c: f32,
    /// Hot-water volume, ml.
    pub hot_water_volume_ml: f32,
    /// Hot-water timeout, seconds.
    pub hot_water_timeout_s: f32,
    /// Flush water temperature, °C.
    pub flush_temp_c: f32,
    /// Flush duration, seconds. No machine-side value exists — QC → default.
    pub flush_time_s: f32,
}

/// First positive finite value, else the fallback — `??` would happily
/// pass a partial payload's literal `0` through.
fn pos_or(v: Option<f32>, fallback: f32) -> f32 {
    v.filter(|x| x.is_finite() && *x > 0.0).unwrap_or(fallback)
}

/// Resolve the service-mode targets: machine → Quick Controls → legacy
/// default, field by field (see the module docs). The hot-water timeout
/// has no QC dial and the flush time has no machine value; each skips
/// the missing tier.
#[must_use]
pub fn resolve_mode_targets(inputs: &ModeTargetInputs) -> ModeTargets {
    ModeTargets {
        steam_temp_c: pos_or(
            inputs.machine_steam_temp_c,
            pos_or(inputs.qc_steam_temp_c, DEFAULT_STEAM_TEMP_C),
        ),
        steam_timeout_s: pos_or(
            inputs.machine_steam_timeout_s,
            pos_or(inputs.qc_steam_time_s, DEFAULT_STEAM_TIMEOUT_S),
        ),
        hot_water_temp_c: pos_or(
            inputs.machine_hot_water_temp_c,
            pos_or(inputs.qc_hot_water_temp_c, DEFAULT_HOT_WATER_TEMP_C),
        ),
        hot_water_volume_ml: pos_or(
            inputs.machine_hot_water_volume_ml,
            pos_or(inputs.qc_hot_water_volume_ml, DEFAULT_HOT_WATER_VOLUME_ML),
        ),
        hot_water_timeout_s: pos_or(
            inputs.machine_hot_water_timeout_s,
            DEFAULT_HOT_WATER_TIMEOUT_S,
        ),
        flush_temp_c: pos_or(
            inputs.machine_flush_temp_deci_c.map(|d| d / 10.0),
            pos_or(inputs.qc_flush_temp_c, DEFAULT_FLUSH_TEMP_C),
        ),
        flush_time_s: pos_or(inputs.qc_flush_time_s, DEFAULT_FLUSH_TIME_S),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_inputs_resolve_to_the_legacy_defaults() {
        let t = resolve_mode_targets(&ModeTargetInputs::default());
        assert_eq!(t.steam_temp_c, DEFAULT_STEAM_TEMP_C);
        assert_eq!(t.steam_timeout_s, DEFAULT_STEAM_TIMEOUT_S);
        assert_eq!(t.hot_water_temp_c, DEFAULT_HOT_WATER_TEMP_C);
        assert_eq!(t.hot_water_volume_ml, DEFAULT_HOT_WATER_VOLUME_ML);
        assert_eq!(t.hot_water_timeout_s, DEFAULT_HOT_WATER_TIMEOUT_S);
        assert_eq!(t.flush_temp_c, DEFAULT_FLUSH_TEMP_C);
        assert_eq!(t.flush_time_s, DEFAULT_FLUSH_TIME_S);
    }

    #[test]
    fn the_machine_value_outranks_the_qc_dial() {
        let t = resolve_mode_targets(&ModeTargetInputs {
            machine_steam_temp_c: Some(160.0),
            qc_steam_temp_c: Some(140.0),
            ..ModeTargetInputs::default()
        });
        assert_eq!(t.steam_temp_c, 160.0);
    }

    #[test]
    fn a_partial_payloads_zero_falls_through_to_qc_then_default() {
        let t = resolve_mode_targets(&ModeTargetInputs {
            machine_steam_temp_c: Some(0.0),
            qc_steam_temp_c: Some(140.0),
            machine_hot_water_temp_c: Some(0.0),
            ..ModeTargetInputs::default()
        });
        assert_eq!(t.steam_temp_c, 140.0);
        assert_eq!(t.hot_water_temp_c, DEFAULT_HOT_WATER_TEMP_C);
    }

    #[test]
    fn the_flush_mmr_word_is_deci_celsius() {
        let t = resolve_mode_targets(&ModeTargetInputs {
            machine_flush_temp_deci_c: Some(883.0),
            qc_flush_temp_c: Some(90.0),
            ..ModeTargetInputs::default()
        });
        assert!((t.flush_temp_c - 88.3).abs() < 1e-4);
    }

    #[test]
    fn inputs_round_trip_the_camel_case_wire() {
        // The shells build this JSON — pin one field of each casing family.
        let parsed: ModeTargetInputs =
            serde_json::from_str(r#"{"machineSteamTempC":155,"qcFlushTimeS":6}"#).unwrap();
        assert_eq!(parsed.machine_steam_temp_c, Some(155.0));
        assert_eq!(parsed.qc_flush_time_s, Some(6.0));
        let t = resolve_mode_targets(&parsed);
        assert_eq!(t.steam_temp_c, 155.0);
        assert_eq!(t.flush_time_s, 6.0);
    }
}

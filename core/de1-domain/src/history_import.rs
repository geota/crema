//! Import legacy de1app shot history into Crema [`StoredShot`]s.
//!
//! Reads two source formats users may export from the legacy de1app:
//!
//! - **Legacy TCL** (`.shot`) — the original de1app `history/` files,
//!   Tcl-dict format. Top-level keys are scalar values or parallel
//!   time-series arrays (`espresso_elapsed`, `espresso_pressure`,
//!   `espresso_flow`, …), with a nested `settings {…}` dict holding
//!   the barista metadata (bean / grinder / yield / TDS / EY / notes /
//!   rating).
//!
//! - **Modern v2 JSON** (`.shot.json`) — the v2 schema the de1app added
//!   when it grew structured profile metadata: a top-level document
//!   with `version`, `clock`, `elapsed`, `pressure`, `flow`,
//!   `temperature`, `totals`, `profile`, `meta`, and `app` keys. The
//!   `profile` block is the community v2 profile contract (matches
//!   `import_v2_json`).
//!
//! Both produce a [`StoredShot`] with `format_version =
//! STORED_SHOT_FORMAT_VERSION` so imports drop straight into Crema's
//! history store with no follow-up migration.
//!
//! Verified against reaprime's `lib/src/import/parsers/tcl_shot_parser.dart`
//! and `shot_v2_json_parser.dart`. Crema's importer maps the same
//! legacy keys to the same destinations; differences are documented
//! inline.

use std::time::Duration;

use serde::Deserialize;

use crate::error::ImportError;
use crate::history::{STORED_SHOT_FORMAT_VERSION, ShotMetadata, StoredShot};
use crate::profile::{BeverageType, Profile};
use crate::profile_import::TclDict;
use crate::shot::{ShotRecord, TimedSample};
use de1_protocol::ShotSample;

/// Import a legacy de1app `.shot` (Tcl-dict) history file.
///
/// # Errors
///
/// - [`ImportError::Tcl`] if the source is malformed.
/// - [`ImportError::MissingField`] if a required scalar (`clock`) is
///   absent.
/// - [`ImportError::Json`] never (path is pure Tcl).
pub fn import_legacy_tcl_shot(content: &str) -> Result<StoredShot, ImportError> {
    let dict = TclDict::parse(content)?;

    // `clock` is Unix epoch seconds. The legacy app writes it as the
    // first field on every `.shot` file (de1plus/persistence.tcl).
    let clock_s: u64 = dict
        .get("clock")
        .ok_or(ImportError::MissingField("clock"))?
        .trim()
        .parse()
        .map_err(|_| ImportError::Tcl("`clock` is not an integer".into()))?;
    let recorded_at = clock_s.saturating_mul(1000);

    // Parallel time-series arrays. Every series uses the same length;
    // pre-2026-05-22 reaprime takes `min(lengths)` defensively, Crema
    // follows.
    let elapsed = parse_tcl_double_list(dict.get("espresso_elapsed"));
    let pressure = parse_tcl_double_list(dict.get("espresso_pressure"));
    let flow = parse_tcl_double_list(dict.get("espresso_flow"));
    let temp_basket = parse_tcl_double_list(dict.get("espresso_temperature_basket"));
    let temp_mix = parse_tcl_double_list(dict.get("espresso_temperature_mix"));
    let temp_goal = parse_tcl_double_list(dict.get("espresso_temperature_goal"));
    let pressure_goal = parse_tcl_double_list(dict.get("espresso_pressure_goal"));
    let flow_goal = parse_tcl_double_list(dict.get("espresso_flow_goal"));
    let frame_no = parse_tcl_double_list(dict.get("espresso_frame_number"));
    // The legacy TCL log carries the scale-derived channels alongside
    // the DE1 ones — Crema imports them so the chart can read the
    // weight-based resistance for legacy shots too.
    let scale_weight = parse_tcl_double_list(dict.get("espresso_weight"));
    let scale_flow_weight = parse_tcl_double_list(dict.get("espresso_flow_weight"));
    let dispensed = parse_tcl_double_list(dict.get("espresso_water_dispensed"));

    let count = [
        elapsed.len(),
        pressure.len(),
        flow.len(),
        temp_basket.len(),
        temp_mix.len(),
        temp_goal.len(),
        pressure_goal.len(),
        flow_goal.len(),
    ]
    .into_iter()
    .min()
    .unwrap_or(0);

    let samples = build_samples(
        count,
        &elapsed,
        &pressure,
        &flow,
        &temp_basket,
        &temp_mix,
        &temp_goal,
        &pressure_goal,
        &flow_goal,
        &frame_no,
        &scale_weight,
        &scale_flow_weight,
        &dispensed,
    );

    let duration = samples
        .last()
        .map_or(Duration::ZERO, |t| t.elapsed);

    // The nested `settings {...}` dict.
    let settings = dict
        .get("settings")
        .map(TclDict::parse)
        .transpose()?
        .unwrap_or_else(|| TclDict::parse("").expect("empty TclDict parses"));

    let metadata = tcl_metadata(&settings);
    let profile = tcl_profile(&settings, &metadata);

    let mut stored = StoredShot::new(recorded_at, ShotRecord { duration, samples });
    stored.metadata = metadata;
    if let Some(profile) = profile {
        stored = stored.with_profile(profile);
    }
    stored.format_version = STORED_SHOT_FORMAT_VERSION;
    Ok(stored)
}

/// Import a modern de1app `.shot.json` (v2) history file. The v2 schema
/// nests a community v2 profile under `profile`; the rest is parallel
/// time-series + structured metadata blocks.
///
/// # Errors
///
/// - [`ImportError::Json`] if the source is not valid JSON or shape.
pub fn import_v2_json_shot(content: &str) -> Result<StoredShot, ImportError> {
    let raw: V2ShotJson =
        serde_json::from_str(content).map_err(|e| ImportError::Json(e.to_string()))?;

    let recorded_at = raw.clock.saturating_mul(1000);

    let count = [
        raw.elapsed.len(),
        raw.pressure.pressure.len(),
        raw.flow.flow.len(),
        raw.temperature.basket.len(),
        raw.temperature.mix.len(),
        raw.temperature.goal.len(),
        raw.pressure.goal.len(),
        raw.flow.goal.len(),
    ]
    .into_iter()
    .min()
    .unwrap_or(0);

    let samples = build_samples(
        count,
        &raw.elapsed,
        &raw.pressure.pressure,
        &raw.flow.flow,
        &raw.temperature.basket,
        &raw.temperature.mix,
        &raw.temperature.goal,
        &raw.pressure.goal,
        &raw.flow.goal,
        &[],
        // v2 puts the scale-derived channels under `totals.weight` and
        // `flow.by_weight`; `totals.water_dispensed` carries the pump
        // volume integral. All three are sample-aligned to `elapsed`.
        &raw.totals.weight,
        &raw.flow.by_weight,
        &raw.totals.water_dispensed,
    );
    let duration = samples
        .last()
        .map_or(Duration::ZERO, |t| t.elapsed);

    let metadata = v2_metadata(&raw);
    let profile = v2_profile(&raw);

    let mut stored = StoredShot::new(recorded_at, ShotRecord { duration, samples });
    stored.metadata = metadata;
    if let Some(profile) = profile {
        stored = stored.with_profile(profile);
    }
    stored.format_version = STORED_SHOT_FORMAT_VERSION;
    Ok(stored)
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

#[allow(clippy::too_many_arguments)]
fn build_samples(
    count: usize,
    elapsed: &[f32],
    pressure: &[f32],
    flow: &[f32],
    temp_basket: &[f32],
    temp_mix: &[f32],
    temp_goal: &[f32],
    pressure_goal: &[f32],
    flow_goal: &[f32],
    frame_no: &[f32],
    scale_weight: &[f32],
    scale_flow_weight: &[f32],
    dispensed: &[f32],
) -> Vec<TimedSample> {
    let mut out = Vec::with_capacity(count);
    for i in 0..count {
        let elapsed_dur = duration_from_seconds(elapsed[i]);
        // Build a ShotSample mirroring what the BLE decoder would have
        // produced live. The DE1 protocol fields the legacy app doesn't
        // record (sample_time, group_pressure_set_low, set_mix_temp /
        // set_head_temp split) are reconstructed from the available
        // goal/target series; absent fields default to 0.
        let sample = ShotSample {
            // `sample_time` is the DE1's BLE-side half-cycle counter; the
            // legacy log doesn't preserve it. Synthesize a monotonic value
            // from the sample index so downstream timing logic doesn't
            // divide by zero.
            sample_time: u16::try_from(i).unwrap_or(u16::MAX),
            group_pressure: pressure[i],
            group_flow: flow[i],
            head_temp: temp_basket[i],
            mix_temp: temp_mix[i],
            // The legacy log records a single `temperature_goal` series
            // (no separate mix / head split). Fan it out to both target
            // fields so consumers that read either get a value.
            set_head_temp: temp_goal[i],
            set_mix_temp: temp_goal[i],
            set_group_pressure: pressure_goal[i],
            set_group_flow: flow_goal[i],
            frame_number: frame_no
                .get(i)
                .map_or(0, |f| frame_number_from_f32(*f)),
            steam_temp: 0.0,
        };
        // Overlay channels — each `Some(value)` only when the source
        // array carried this index, so a file that omits the channel
        // (legacy `.shot` without a scale paired, pre-PR v2 export)
        // imports with `None` rather than a misleading `Some(0.0)`.
        let scale_weight_i = scale_weight.get(i).copied();
        let scale_flow_weight_i = scale_flow_weight.get(i).copied();
        let dispensed_i = dispensed.get(i).copied();
        // Re-derive the resistance signals at import time so the chart
        // can render legacy shots through the same auto-switch path
        // live captures use. Same `P / F²` shape + sub-floor guard the
        // live `Event::Telemetry` path runs (see `de1_app::puck_resistance`
        // / `puck_resistance_weight`); kept inline to avoid de1-domain
        // depending back on de1-app.
        let resistance = derive_resistance(pressure[i], flow[i]);
        let resistance_weight = scale_flow_weight_i
            .and_then(|wf| derive_resistance(pressure[i], wf));
        out.push(TimedSample {
            elapsed: elapsed_dur,
            sample,
            scale_weight: scale_weight_i,
            scale_flow_weight: scale_flow_weight_i,
            dispensed_volume: dispensed_i,
            resistance,
            resistance_weight,
        });
    }
    out
}

/// Sub-floor flow guard for the import-side resistance derivation —
/// matches the live core's `RESISTANCE_FLOW_FLOOR_ML_PER_S` (also reused
/// as the g/s floor since both magnitudes coincide at espresso flow
/// rates).
const RESISTANCE_FLOW_FLOOR: f32 = 0.1;

/// `P / F²` with the near-zero-flow guard and non-finite check both
/// resistance signals share. Inline copy of `de1_app::puck_resistance`
/// / `puck_resistance_weight` — kept here so `de1-domain` (which the
/// importer lives in) doesn't depend back on `de1-app`.
fn derive_resistance(pressure: f32, flow: f32) -> Option<f32> {
    if !pressure.is_finite() || !flow.is_finite() {
        return None;
    }
    if flow < RESISTANCE_FLOW_FLOOR {
        return None;
    }
    let r = pressure / (flow * flow);
    if r.is_finite() { Some(r) } else { None }
}

fn duration_from_seconds(s: f32) -> Duration {
    let s = s.max(0.0);
    Duration::from_secs_f32(s)
}

/// Round + clamp a recorded frame-index float into the byte range the
/// DE1's `frame_number` field uses. Legacy logs store the index as a
/// float (the recorded sample's frame at that elapsed time), so the
/// import path has to coerce — clamp explicit so the cast is
/// proof-carrying for clippy.
#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn frame_number_from_f32(f: f32) -> u8 {
    f.round().clamp(0.0, 255.0) as u8
}

/// Parse a Tcl-list string like `"0.0 0.25 0.5"` into a `Vec<f32>`.
fn parse_tcl_double_list(raw: Option<&str>) -> Vec<f32> {
    let Some(s) = raw else { return Vec::new() };
    s.split_whitespace()
        .filter_map(|tok| tok.parse::<f32>().ok())
        .collect()
}

/// Build a [`ShotMetadata`] from the legacy `settings {…}` block.
fn tcl_metadata(settings: &TclDict) -> ShotMetadata {
    let dose = settings.get_f32("grinder_dose_weight");
    let yield_out = settings.get_f32("drink_weight");
    let tds = settings.get_f32("drink_tds");
    let extraction_yield = settings.get_f32("drink_ey");
    let enjoyment = settings.get_f32("espresso_enjoyment");
    let rating = enjoyment.map(enjoyment_to_rating);
    let notes = settings
        .get("espresso_notes")
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);
    let grinder_setting = settings
        .get("grinder_setting")
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(str::to_string);
    let beans = combine_bean_label(
        settings.get("bean_brand"),
        settings.get("bean_type"),
    );

    ShotMetadata {
        dose,
        yield_out,
        beans,
        grinder_setting,
        notes,
        rating,
        tds,
        extraction_yield,
    }
}

/// Build a minimal [`Profile`] from the legacy `settings.profile_title`,
/// or `None` if the title is empty / missing.
fn tcl_profile(settings: &TclDict, metadata: &ShotMetadata) -> Option<Profile> {
    let title = settings.get("profile_title").map(str::trim)?;
    if title.is_empty() {
        return None;
    }
    Some(Profile {
        title: title.to_string(),
        notes: String::new(),
        steps: Vec::new(),
        preinfuse_step_count: 0,
        minimum_pressure: 0.0,
        maximum_flow: 0.0,
        max_total_volume_ml: 0,
        target_weight: metadata.yield_out.unwrap_or(0.0),
        dose: metadata.dose.unwrap_or(0.0),
        author: String::new(),
        beverage_type: BeverageType::Espresso,
        tank_temperature: 0.0,
        version: "2".to_string(),
    })
}

/// Map the legacy 0-100 `espresso_enjoyment` slider to Crema's
/// 1-5 star rating. The conversion is the same the legacy app used in
/// its history rollup (`history.tcl`): 0 → 1, 100 → 5, linear in
/// between, rounded.
#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn enjoyment_to_rating(enj: f32) -> u8 {
    let scaled = (enj.clamp(0.0, 100.0) / 25.0).round() as u8;
    scaled.saturating_add(1).clamp(1, 5)
}

/// Concatenate `bean_brand` + `bean_type` into a single human label.
fn combine_bean_label(brand: Option<&str>, kind: Option<&str>) -> Option<String> {
    let brand = brand.map(str::trim).filter(|s| !s.is_empty());
    let kind = kind.map(str::trim).filter(|s| !s.is_empty());
    match (brand, kind) {
        (Some(b), Some(k)) => Some(format!("{b} · {k}")),
        (Some(b), None) => Some(b.to_string()),
        (None, Some(k)) => Some(k.to_string()),
        (None, None) => None,
    }
}

// ---------------------------------------------------------------------------
// v2 JSON shape (deserialize-only)
// ---------------------------------------------------------------------------

#[derive(Deserialize)]
struct V2ShotJson {
    /// Unix epoch seconds.
    clock: u64,
    #[serde(default)]
    elapsed: Vec<f32>,
    #[serde(default)]
    pressure: V2Pressure,
    #[serde(default)]
    flow: V2Flow,
    #[serde(default)]
    temperature: V2Temperature,
    /// Scale weight series + pump-dispensed-volume series, both
    /// sample-aligned to `elapsed`. Pre-PR exports emitted these as
    /// zeros placeholders; post-PR they carry real values when a
    /// scale was paired / a DE1 was streaming.
    #[serde(default)]
    totals: V2Totals,
    #[serde(default)]
    profile: Option<serde_json::Value>,
    #[serde(default)]
    meta: V2Meta,
}

#[derive(Deserialize, Default)]
struct V2Pressure {
    #[serde(default)]
    pressure: Vec<f32>,
    #[serde(default)]
    goal: Vec<f32>,
}

#[derive(Deserialize, Default)]
struct V2Flow {
    #[serde(default)]
    flow: Vec<f32>,
    #[serde(default)]
    goal: Vec<f32>,
    /// Scale-derived flow rate, sample-aligned to `elapsed`. Empty when
    /// the source export had no scale paired (or pre-PR placeholder
    /// zeros — same shape, just no useful values).
    #[serde(default)]
    by_weight: Vec<f32>,
}

#[derive(Deserialize, Default)]
struct V2Totals {
    /// Cumulative scale weight, grams.
    #[serde(default)]
    weight: Vec<f32>,
    /// Cumulative pump-dispensed water, millilitres.
    #[serde(default)]
    water_dispensed: Vec<f32>,
}

#[derive(Deserialize, Default)]
struct V2Temperature {
    #[serde(default)]
    basket: Vec<f32>,
    #[serde(default)]
    mix: Vec<f32>,
    #[serde(default)]
    goal: Vec<f32>,
}

#[derive(Deserialize, Default)]
struct V2Meta {
    #[serde(default)]
    bean: Option<V2Bean>,
    #[serde(default)]
    shot: Option<V2Shot>,
    #[serde(default)]
    grinder: Option<V2Grinder>,
    /// `in` is a reserved word in Rust — rename for serde so the field
    /// can still be named `in` on the wire (= dose grams).
    #[serde(default, rename = "in")]
    dose_in: Option<f32>,
    #[serde(default)]
    out: Option<f32>,
}

#[derive(Deserialize)]
struct V2Bean {
    #[serde(default)]
    brand: Option<String>,
    #[serde(default, rename = "type")]
    kind: Option<String>,
}

#[derive(Deserialize)]
struct V2Shot {
    #[serde(default)]
    enjoyment: Option<f32>,
    #[serde(default)]
    notes: Option<String>,
    #[serde(default)]
    tds: Option<f32>,
    #[serde(default)]
    ey: Option<f32>,
}

#[derive(Deserialize)]
struct V2Grinder {
    #[serde(default)]
    setting: Option<String>,
}

fn v2_metadata(raw: &V2ShotJson) -> ShotMetadata {
    let bean_label = match &raw.meta.bean {
        Some(b) => combine_bean_label(b.brand.as_deref(), b.kind.as_deref()),
        None => None,
    };
    let dose = raw.meta.dose_in;
    let yield_out = raw.meta.out;
    let grinder_setting = raw
        .meta
        .grinder
        .as_ref()
        .and_then(|g| g.setting.as_ref())
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty());
    let (rating, tds, extraction_yield, notes) = match &raw.meta.shot {
        Some(s) => (
            s.enjoyment.map(enjoyment_to_rating),
            s.tds,
            s.ey,
            s.notes.clone().filter(|s| !s.trim().is_empty()),
        ),
        None => (None, None, None, None),
    };
    ShotMetadata {
        dose,
        yield_out,
        beans: bean_label,
        grinder_setting,
        notes,
        rating,
        tds,
        extraction_yield,
    }
}

/// Reuse `import_v2_json` to re-decode the embedded profile when one is
/// present. Returns `None` if the profile block is absent or malformed —
/// a v2 shot without a profile is still a valid history entry, the
/// shot's telemetry stands on its own.
fn v2_profile(raw: &V2ShotJson) -> Option<Profile> {
    let value = raw.profile.as_ref()?;
    let json = value.to_string();
    crate::profile_import::import_v2_json(&json).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A miniature legacy `.shot` fixture covering every metadata field
    /// the importer maps, plus two parallel telemetry samples so the
    /// build_samples path is exercised.
    const LEGACY_FIXTURE: &str = r#"clock 1699432544
espresso_elapsed {0.0 1.0}
espresso_pressure {0.0 9.0}
espresso_flow {0.0 2.5}
espresso_flow_weight {0.0 2.3}
espresso_weight {0.0 8.5}
espresso_temperature_basket {22.0 93.0}
espresso_temperature_mix {22.0 93.0}
espresso_temperature_goal {93.0 93.0}
espresso_pressure_goal {0.0 9.0}
espresso_flow_goal {0.0 0.0}
settings {
    bean_brand {Banibeans}
    bean_type {Colombia Huila}
    bean_notes {Chocolatey and nutty}
    roast_date {2023-10-20}
    roast_level {Medium}
    grinder_model {Eureka Mignon}
    grinder_setting {2.5}
    grinder_dose_weight {18.5}
    drink_weight {38.0}
    drink_tds {9.0}
    drink_ey {21.0}
    espresso_enjoyment {80}
    espresso_notes {Balanced, good sweetness}
    my_name {Barista}
    profile_title {Default}
}
"#;

    #[test]
    fn legacy_tcl_shot_imports_metadata() {
        let shot = import_legacy_tcl_shot(LEGACY_FIXTURE).expect("imports cleanly");
        // Clock is Unix seconds; recorded_at is Unix ms.
        assert_eq!(shot.recorded_at, 1_699_432_544 * 1000);
        assert_eq!(shot.format_version, STORED_SHOT_FORMAT_VERSION);
        // Bean label combines brand + type.
        assert_eq!(shot.metadata.beans.as_deref(), Some("Banibeans · Colombia Huila"));
        // Weights round-trip.
        assert_eq!(shot.metadata.dose, Some(18.5));
        assert_eq!(shot.metadata.yield_out, Some(38.0));
        assert_eq!(shot.metadata.tds, Some(9.0));
        assert_eq!(shot.metadata.extraction_yield, Some(21.0));
        // Enjoyment 80 → rating 4 (80 / 25 = 3.2 round = 3, + 1 = 4).
        assert_eq!(shot.metadata.rating, Some(4));
        assert_eq!(shot.metadata.notes.as_deref(), Some("Balanced, good sweetness"));
        assert_eq!(shot.metadata.grinder_setting.as_deref(), Some("2.5"));
        // Profile picked up the title.
        let profile = shot.profile.expect("profile present");
        assert_eq!(profile.title, "Default");
        assert_eq!(profile.dose, 18.5);
        assert_eq!(profile.target_weight, 38.0);
    }

    #[test]
    fn legacy_tcl_shot_imports_samples_aligned_to_min_length() {
        let shot = import_legacy_tcl_shot(LEGACY_FIXTURE).expect("imports");
        assert_eq!(shot.record.samples.len(), 2);
        let s = &shot.record.samples[1];
        assert!((s.elapsed.as_secs_f32() - 1.0).abs() < 1e-6);
        assert!((s.sample.group_pressure - 9.0).abs() < 1e-6);
        assert!((s.sample.group_flow - 2.5).abs() < 1e-6);
        assert!((s.sample.head_temp - 93.0).abs() < 1e-6);
        // Duration matches the last elapsed.
        assert!((shot.record.duration.as_secs_f32() - 1.0).abs() < 1e-6);
    }

    #[test]
    fn enjoyment_to_rating_maps_legacy_slider_to_five_stars() {
        // 0 → 1, 25 → 2, 50 → 3, 75 → 4, 100 → 5.
        assert_eq!(enjoyment_to_rating(0.0), 1);
        assert_eq!(enjoyment_to_rating(25.0), 2);
        assert_eq!(enjoyment_to_rating(50.0), 3);
        assert_eq!(enjoyment_to_rating(75.0), 4);
        assert_eq!(enjoyment_to_rating(100.0), 5);
        // Out-of-range values clamp.
        assert_eq!(enjoyment_to_rating(-10.0), 1);
        assert_eq!(enjoyment_to_rating(500.0), 5);
    }

    #[test]
    fn missing_clock_is_an_error() {
        let err = import_legacy_tcl_shot("settings {bean_brand foo}").unwrap_err();
        assert!(matches!(err, ImportError::MissingField("clock")));
    }

    /// A miniature v2 shot JSON covering both telemetry + metadata blocks.
    const V2_JSON_FIXTURE: &str = r#"{
        "version": 2,
        "clock": 1710510622,
        "elapsed": [0.0, 1.0],
        "pressure": {"pressure": [0.0, 9.0], "goal": [0.0, 9.0]},
        "flow": {"flow": [0.0, 2.5], "goal": [0.0, 0.0]},
        "temperature": {"basket": [22.0, 93.0], "mix": [22.0, 93.0], "goal": [93.0, 93.0]},
        "totals": {"weight": [0.0, 8.5]},
        "profile": {
            "version": "2", "title": "Best Practice", "author": "Decent", "beverage_type": "espresso", "tank_temperature": 0,
            "notes": "test",
            "steps": [
                {"name": "preinfusion", "pump": "pressure", "transition": "fast",
                 "volume": 0, "seconds": 10, "temperature": 93.0, "sensor": "coffee", "pressure": 1.0}
            ],
            "target_weight": 36.0
        },
        "meta": {
            "bean": {"brand": "Banibeans", "type": "Ethiopia Yirgacheffe"},
            "shot": {"enjoyment": 75, "notes": "Good body", "tds": 8.5, "ey": 20.5},
            "grinder": {"setting": "15"},
            "in": 18.0, "out": 36.0
        }
    }"#;

    #[test]
    fn v2_json_shot_imports_metadata_and_profile() {
        let shot = import_v2_json_shot(V2_JSON_FIXTURE).expect("imports cleanly");
        assert_eq!(shot.recorded_at, 1_710_510_622 * 1000);
        assert_eq!(shot.metadata.dose, Some(18.0));
        assert_eq!(shot.metadata.yield_out, Some(36.0));
        assert_eq!(shot.metadata.tds, Some(8.5));
        assert_eq!(shot.metadata.extraction_yield, Some(20.5));
        // Enjoyment 75 → rating 4.
        assert_eq!(shot.metadata.rating, Some(4));
        assert_eq!(shot.metadata.notes.as_deref(), Some("Good body"));
        assert_eq!(shot.metadata.grinder_setting.as_deref(), Some("15"));
        assert_eq!(
            shot.metadata.beans.as_deref(),
            Some("Banibeans · Ethiopia Yirgacheffe")
        );

        let profile = shot.profile.expect("profile present");
        assert_eq!(profile.title, "Best Practice");
        assert_eq!(profile.author, "Decent");
        assert_eq!(profile.steps.len(), 1);
    }

    #[test]
    fn v2_json_shot_imports_samples() {
        let shot = import_v2_json_shot(V2_JSON_FIXTURE).expect("imports");
        assert_eq!(shot.record.samples.len(), 2);
        let s = &shot.record.samples[1];
        assert!((s.sample.group_pressure - 9.0).abs() < 1e-6);
        assert!((s.sample.head_temp - 93.0).abs() < 1e-6);
    }
}

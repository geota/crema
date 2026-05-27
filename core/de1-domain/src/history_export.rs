//! Emit a [`StoredShot`] as a community-v2 `.shot.json` document — the
//! same shape [`import_v2_json_shot`](crate::import_v2_json_shot) parses.
//!
//! Symmetric with the parser side of `history_import`. Pushing this
//! down into the domain means every shell (web today, Android next)
//! produces the identical v2 wire shape from one source of truth, and
//! the round-trip `export → import` is exercised by the core's own
//! tests rather than re-discovered per shell.
//!
//! The v2 schema covers parallel telemetry arrays + structured meta;
//! Crema's [`StoredShot`] carries less than the schema permits (no raw
//! scale-flow channel, no per-sample phase boundaries, no roast-date
//! on the bean). Fields Crema doesn't model emit the v2-schema-friendly
//! "absent" value: an empty array, `null`, or zero, matching what the
//! existing shell exporter produced before this push.
//!
//! Verified against `reaprime/test/fixtures/de1app/history_v2/*.json`
//! and the inverse [`crate::import_v2_json_shot`] — the round-trip
//! test in this module is the canonical contract.

use serde::Serialize;

use crate::history::StoredShot;
use crate::profile::{BeverageType, Profile};

/// Export a [`StoredShot`] as a pretty-printed community-v2
/// `.shot.json` string.
///
/// The inverse of [`crate::import_v2_json_shot`]: every field the
/// importer reads has a slot here, so an export ⇒ import round-trip
/// preserves the data Crema's storage shape models. Fields the
/// schema names but Crema does not model (raw scale-flow channel,
/// per-sample phase boundaries, the embedded profile's step list)
/// emit empty arrays so v2 consumers that index by sample index get
/// a same-length series.
///
/// Infallible: a [`StoredShot`] is always serializable.
pub fn export_v2_json_shot(shot: &StoredShot) -> String {
    let doc = build_v2_document(shot);
    serde_json::to_string_pretty(&doc).unwrap_or_default()
}

// ---------------------------------------------------------------------------
// Document assembly
// ---------------------------------------------------------------------------

#[allow(clippy::cast_precision_loss)]
fn build_v2_document(shot: &StoredShot) -> V2DocumentOut {
    let samples = &shot.record.samples;

    // `clock` is Unix seconds in v2; Crema stores `recorded_at` as Unix ms.
    let clock = shot.completed_at / 1000;

    let mut elapsed = Vec::with_capacity(samples.len());
    let mut pressure = Vec::with_capacity(samples.len());
    let mut pressure_goal = Vec::with_capacity(samples.len());
    let mut flow = Vec::with_capacity(samples.len());
    let mut flow_goal = Vec::with_capacity(samples.len());
    let mut basket = Vec::with_capacity(samples.len());
    let mut mix = Vec::with_capacity(samples.len());
    let mut temp_goal = Vec::with_capacity(samples.len());
    // The overlay channels — each emitted as a same-length series of
    // floats. A sample with no value (`None`) renders as `0.0` on the
    // wire to keep the column lengths aligned with `elapsed` (v2
    // readers index by position). When the *whole* shot has no values
    // for a channel (e.g. a shot pulled with no scale paired) the
    // series is all-zero — same shape the pre-PR placeholder emitted,
    // so existing v2 consumers stay happy and Visualizer's parser
    // accepts the silent channel rather than rejecting the upload.
    let mut weight_series = Vec::with_capacity(samples.len());
    let mut flow_by_weight = Vec::with_capacity(samples.len());
    let mut water_dispensed = Vec::with_capacity(samples.len());

    for t in samples {
        elapsed.push(t.elapsed.as_secs_f32());
        pressure.push(t.sample.group_pressure);
        pressure_goal.push(t.sample.set_group_pressure);
        flow.push(t.sample.group_flow);
        flow_goal.push(t.sample.set_group_flow);
        basket.push(t.sample.head_temp);
        mix.push(t.sample.mix_temp);
        // The legacy `temperature.goal` is a single channel; the
        // DE1's separate set_head / set_mix collapse to one on export
        // — same convention `import_v2_json_shot` reads.
        temp_goal.push(t.sample.set_head_temp);
        weight_series.push(t.scale_weight.unwrap_or(0.0));
        flow_by_weight.push(t.scale_flow_weight.unwrap_or(0.0));
        water_dispensed.push(t.dispensed_volume.unwrap_or(0.0));
    }

    // `by_weight_raw` is the unsmoothed sibling of `by_weight`. Crema
    // doesn't model a separate raw channel (the flow estimator's
    // output is the only series), so the raw column mirrors the
    // smoothed one — same trick the legacy de1app uses when the raw
    // stream isn't recorded. Resistance / conductance are deliberately
    // NOT emitted: the Visualizer spec accepts no `espresso_resistance`
    // field (verified against `visualizer-openapi.json` v1.8.2 —
    // resistance / conductance are derived server-side from
    // pressure + flow), so emitting them would just bloat the payload.
    let by_weight_raw = flow_by_weight.clone();

    let enjoyment = shot.metadata.rating.and_then(rating_to_enjoyment);

    // Bean: Crema collapses Visualizer's `brand` + `type` into a flat
    // label on import (`combine_bean_label`). On export we split the
    // label back so a v2 consumer gets the two-field shape. roast_level
    // / roast_date are not in StoredShot — emit empty strings, matching
    // the legacy shell exporter's behaviour for unknown roast info.
    let bean = bean_out(shot.metadata.beans.as_deref());

    let profile = profile_out(
        shot.profile.as_ref(),
        shot.metadata.yield_out.unwrap_or(0.0),
    );

    let meta = V2MetaOut {
        bean,
        shot: V2ShotMetaOut {
            enjoyment,
            notes: shot.metadata.notes.clone().unwrap_or_default(),
            tds: shot.metadata.tds,
            ey: shot.metadata.extraction_yield,
        },
        // Per-shot grinder model isn't stored; setting may be. Emit
        // the grinder block when a setting is present, null otherwise.
        grinder: shot
            .metadata
            .grinder_setting
            .as_ref()
            .map(|setting| V2GrinderOut {
                model: String::new(),
                setting: setting.clone(),
            }),
        dose_in: shot.metadata.dose,
        out: shot.metadata.yield_out,
        time: shot.record.duration.as_secs_f32(),
    };

    V2DocumentOut {
        version: 2,
        clock,
        // `date` is human-readable in v2; the importer doesn't parse
        // it, so we emit an ISO-like string the shell can recreate
        // from clock anyway.
        date: format_clock_label(clock),
        elapsed,
        pressure: V2PressureOut {
            pressure,
            goal: pressure_goal,
        },
        flow: V2FlowOut {
            flow,
            by_weight: flow_by_weight,
            by_weight_raw,
            goal: flow_goal,
        },
        temperature: V2TemperatureOut {
            basket,
            mix,
            goal: temp_goal,
        },
        totals: V2TotalsOut {
            weight: weight_series,
            water_dispensed,
        },
        // Per-sample phase boundaries aren't stored on TimedSample;
        // the legacy log records them as a sparse array, empty is the
        // safe default a v2 reader tolerates.
        state_change: Vec::new(),
        profile,
        meta,
        app: V2AppOut {
            app_name: "crema",
            app_version: env!("CARGO_PKG_VERSION"),
        },
    }
}

/// Map a stored 1..5 star rating back to the legacy 0..100 enjoyment
/// slider. The inverse of [`crate::history_import::enjoyment_to_rating`]:
/// `1 → 0`, `5 → 100`, linear in between.
#[allow(clippy::cast_lossless)]
fn rating_to_enjoyment(rating: u8) -> Option<f32> {
    if rating == 0 {
        return None;
    }
    let clamped = rating.clamp(1, 5);
    Some((clamped - 1) as f32 * 25.0)
}

/// Split Crema's combined `"brand · type"` bean label back into the v2
/// `{brand, type, ...}` shape. Mirrors `beanFromImported` in the shell
/// store, so a shell `bean` → flat `metadata.beans` → v2 `bean` chain
/// preserves brand + type across the round-trip.
fn bean_out(label: Option<&str>) -> Option<V2BeanOut> {
    let s = label?.trim();
    if s.is_empty() {
        return None;
    }
    let mut iter = s.splitn(2, '·');
    let brand = iter.next().map(str::trim).unwrap_or(s).to_string();
    let kind = iter.next().map(str::trim).unwrap_or("").to_string();
    Some(V2BeanOut {
        brand,
        kind,
        notes: String::new(),
        roast_level: String::new(),
        roast_date: String::new(),
    })
}

fn profile_out(profile: Option<&Profile>, fallback_target_weight: f32) -> V2ProfileSlotOut {
    match profile {
        Some(p) => V2ProfileSlotOut {
            version: p.version.clone(),
            title: p.title.clone(),
            notes: p.notes.clone(),
            author: p.author.clone(),
            beverage_type: match p.beverage_type {
                BeverageType::Espresso => "espresso",
                BeverageType::Calibrate => "calibrate",
                BeverageType::Cleaning => "cleaning",
                BeverageType::Manual => "manual",
                BeverageType::Pourover => "pourover",
            },
            // StoredShot doesn't persist the per-sample profile step
            // list (the `profile_title` is recorded, the recipe isn't);
            // the legacy shell exporter also emits `[]` here, and a
            // v2 reader that wants step detail pairs the export with a
            // separate profile JSON.
            steps: Vec::new(),
            target_volume: f32::from(p.max_total_volume_ml),
            target_weight: if p.target_weight > 0.0 {
                p.target_weight
            } else {
                fallback_target_weight
            },
            target_volume_count_start: p.preinfuse_step_count,
            tank_temperature: p.tank_temperature,
        },
        None => V2ProfileSlotOut {
            version: "2".to_string(),
            title: "Unknown profile".to_string(),
            notes: String::new(),
            author: String::new(),
            beverage_type: "espresso",
            steps: Vec::new(),
            target_volume: 0.0,
            target_weight: fallback_target_weight,
            target_volume_count_start: 0,
            tank_temperature: 0.0,
        },
    }
}

/// A best-effort human label for a Unix-seconds clock. The legacy
/// `date` field is not parsed back on import (the importer reads
/// `clock` for the timestamp), so this is informational only and uses
/// a stable UTC ISO-ish format the core can produce without a wall
/// clock or locale.
fn format_clock_label(clock_s: u64) -> String {
    // Avoid pulling in a date crate for one human-readable label.
    // Decompose into Y/M/D/h/m/s using a simple Gregorian algorithm
    // that handles the post-epoch range (1970..) Crema actually sees.
    let secs = clock_s % 60;
    let mins = (clock_s / 60) % 60;
    let hours = (clock_s / 3600) % 24;
    let days_since_epoch = clock_s / 86_400;
    let (year, month, day) = civil_from_days(days_since_epoch);
    format!("{year:04}-{month:02}-{day:02}T{hours:02}:{mins:02}:{secs:02}Z")
}

/// Convert "days since 1970-01-01" to a (year, month, day) triple. Based
/// on Howard Hinnant's `civil_from_days`, restricted to the post-epoch
/// range Crema sees.
#[allow(
    clippy::cast_possible_truncation,
    clippy::cast_possible_wrap,
    clippy::cast_sign_loss
)]
fn civil_from_days(days: u64) -> (u32, u32, u32) {
    let z = days as i64 + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if m <= 2 { y + 1 } else { y };
    (year as u32, m as u32, d as u32)
}

// ---------------------------------------------------------------------------
// Wire shape (serialize-only)
// ---------------------------------------------------------------------------

/// The top-level v2 `.shot.json` document. Field order mirrors the
/// reaprime / legacy de1app shape so consumers that diff on key order
/// (uncommon, but cheap to keep stable) see no churn.
#[derive(Serialize)]
struct V2DocumentOut {
    version: u32,
    /// Unix seconds.
    clock: u64,
    date: String,
    elapsed: Vec<f32>,
    pressure: V2PressureOut,
    flow: V2FlowOut,
    temperature: V2TemperatureOut,
    totals: V2TotalsOut,
    state_change: Vec<u32>,
    profile: V2ProfileSlotOut,
    meta: V2MetaOut,
    app: V2AppOut,
}

#[derive(Serialize)]
struct V2PressureOut {
    pressure: Vec<f32>,
    goal: Vec<f32>,
}

#[derive(Serialize)]
struct V2FlowOut {
    flow: Vec<f32>,
    by_weight: Vec<f32>,
    by_weight_raw: Vec<f32>,
    goal: Vec<f32>,
}

#[derive(Serialize)]
struct V2TemperatureOut {
    basket: Vec<f32>,
    mix: Vec<f32>,
    goal: Vec<f32>,
}

#[derive(Serialize)]
struct V2TotalsOut {
    weight: Vec<f32>,
    water_dispensed: Vec<f32>,
}

#[derive(Serialize)]
struct V2ProfileSlotOut {
    version: String,
    title: String,
    notes: String,
    author: String,
    beverage_type: &'static str,
    steps: Vec<serde_json::Value>,
    target_volume: f32,
    target_weight: f32,
    target_volume_count_start: u8,
    tank_temperature: f32,
}

#[derive(Serialize)]
struct V2MetaOut {
    bean: Option<V2BeanOut>,
    shot: V2ShotMetaOut,
    grinder: Option<V2GrinderOut>,
    #[serde(rename = "in")]
    dose_in: Option<f32>,
    out: Option<f32>,
    time: f32,
}

#[derive(Serialize)]
struct V2BeanOut {
    brand: String,
    #[serde(rename = "type")]
    kind: String,
    notes: String,
    roast_level: String,
    roast_date: String,
}

#[derive(Serialize)]
struct V2ShotMetaOut {
    enjoyment: Option<f32>,
    notes: String,
    tds: Option<f32>,
    ey: Option<f32>,
}

#[derive(Serialize)]
struct V2GrinderOut {
    model: String,
    setting: String,
}

#[derive(Serialize)]
struct V2AppOut {
    app_name: &'static str,
    app_version: &'static str,
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;
    use crate::history::{ShotMetadata, StoredShot};
    use crate::history_import::import_v2_json_shot;
    use crate::shot::{ShotRecord, TimedSample};
    use de1_protocol::ShotSample;

    /// Build a representative two-sample `StoredShot` covering every
    /// field the export touches — metadata, profile slot, samples.
    fn fixture() -> StoredShot {
        let samples = vec![
            TimedSample {
                elapsed: Duration::from_secs_f32(0.0),
                sample: ShotSample {
                    sample_time: 0,
                    group_pressure: 0.0,
                    group_flow: 0.0,
                    head_temp: 22.0,
                    mix_temp: 22.0,
                    set_head_temp: 93.0,
                    set_mix_temp: 93.0,
                    set_group_pressure: 0.0,
                    set_group_flow: 0.0,
                    frame_number: 0,
                    steam_temp: 0.0,
                },
                // Pre-flow — no scale signal, no resistance yet.
                scale_weight: Some(0.0),
                scale_flow_weight: Some(0.0),
                dispensed_volume: Some(0.0),
                resistance: None,
                resistance_weight: None,
            },
            TimedSample {
                elapsed: Duration::from_secs_f32(1.0),
                sample: ShotSample {
                    sample_time: 100,
                    group_pressure: 9.0,
                    group_flow: 2.5,
                    head_temp: 93.0,
                    mix_temp: 93.0,
                    set_head_temp: 93.0,
                    set_mix_temp: 93.0,
                    set_group_pressure: 9.0,
                    set_group_flow: 0.0,
                    frame_number: 2,
                    steam_temp: 0.0,
                },
                // Mid-pour: 9 bar / 2.5 ml/s = 1.44, 9 bar / 2.3 g/s² = 1.70.
                scale_weight: Some(8.5),
                scale_flow_weight: Some(2.3),
                dispensed_volume: Some(15.0),
                resistance: Some(9.0 / (2.5 * 2.5)),
                resistance_weight: Some(9.0 / (2.3 * 2.3)),
            },
        ];
        let record = ShotRecord {
            duration: Duration::from_secs(27),
            samples,
        };
        let mut shot = StoredShot::new(1_710_510_622_000, record);
        shot.metadata = ShotMetadata {
            dose: Some(18.0),
            yield_out: Some(36.0),
            beans: Some("Banibeans · Ethiopia Yirgacheffe".to_string()),
            grinder_setting: Some("15".to_string()),
            notes: Some("Good body".to_string()),
            rating: Some(4),
            tds: Some(8.5),
            extraction_yield: Some(20.5),
        };
        shot
    }

    #[test]
    fn exports_a_v2_document_with_the_expected_top_level_shape() {
        let shot = fixture();
        let json = export_v2_json_shot(&shot);
        let v: serde_json::Value = serde_json::from_str(&json).expect("export emits valid JSON");

        // Top-level keys the v2 schema names.
        for key in [
            "version",
            "clock",
            "date",
            "elapsed",
            "pressure",
            "flow",
            "temperature",
            "totals",
            "state_change",
            "profile",
            "meta",
            "app",
        ] {
            assert!(v.get(key).is_some(), "missing top-level key `{key}`");
        }
        assert_eq!(v["version"], 2);
        // Clock is Unix seconds (ms ÷ 1000).
        assert_eq!(v["clock"], 1_710_510_622_u64);
        // Sample-aligned series are all same length.
        let n = v["elapsed"].as_array().unwrap().len();
        assert_eq!(n, 2);
        assert_eq!(v["pressure"]["pressure"].as_array().unwrap().len(), n);
        assert_eq!(v["pressure"]["goal"].as_array().unwrap().len(), n);
        assert_eq!(v["flow"]["flow"].as_array().unwrap().len(), n);
        assert_eq!(v["flow"]["by_weight"].as_array().unwrap().len(), n);
        assert_eq!(v["flow"]["by_weight_raw"].as_array().unwrap().len(), n);
        assert_eq!(v["flow"]["goal"].as_array().unwrap().len(), n);
        assert_eq!(v["temperature"]["basket"].as_array().unwrap().len(), n);
        assert_eq!(v["temperature"]["mix"].as_array().unwrap().len(), n);
        assert_eq!(v["temperature"]["goal"].as_array().unwrap().len(), n);
        assert_eq!(v["totals"]["weight"].as_array().unwrap().len(), n);
        assert_eq!(v["totals"]["water_dispensed"].as_array().unwrap().len(), n);
        // state_change: empty (Crema doesn't capture sample-level phase boundaries).
        assert_eq!(v["state_change"].as_array().unwrap().len(), 0);
        // Meta basics survive verbatim.
        assert_eq!(v["meta"]["bean"]["brand"], "Banibeans");
        assert_eq!(v["meta"]["bean"]["type"], "Ethiopia Yirgacheffe");
        assert_eq!(v["meta"]["shot"]["notes"], "Good body");
        // rating 4 → enjoyment 75 (inverse of (75/25) round + 1 = 4).
        assert_eq!(v["meta"]["shot"]["enjoyment"], 75.0);
        assert_eq!(v["meta"]["shot"]["tds"], 8.5);
        assert_eq!(v["meta"]["shot"]["ey"], 20.5);
        assert_eq!(v["meta"]["in"], 18.0);
        assert_eq!(v["meta"]["out"], 36.0);
        // App stamp.
        assert_eq!(v["app"]["app_name"], "crema");
    }

    #[test]
    fn rating_to_enjoyment_inverts_enjoyment_to_rating() {
        // 1 → 0, 2 → 25, 3 → 50, 4 → 75, 5 → 100. Matches the inverse
        // of `history_import::enjoyment_to_rating`.
        assert_eq!(rating_to_enjoyment(1), Some(0.0));
        assert_eq!(rating_to_enjoyment(2), Some(25.0));
        assert_eq!(rating_to_enjoyment(3), Some(50.0));
        assert_eq!(rating_to_enjoyment(4), Some(75.0));
        assert_eq!(rating_to_enjoyment(5), Some(100.0));
        // 0 means "unrated" — emit nothing.
        assert_eq!(rating_to_enjoyment(0), None);
    }

    #[test]
    fn bean_out_splits_the_combined_label_back_into_brand_and_type() {
        let b = bean_out(Some("Acme · Decaf")).expect("a bean object");
        assert_eq!(b.brand, "Acme");
        assert_eq!(b.kind, "Decaf");
        // No separator → all in brand, type empty.
        let b = bean_out(Some("OneNameBean")).expect("a bean object");
        assert_eq!(b.brand, "OneNameBean");
        assert_eq!(b.kind, "");
        // Empty or absent → no bean object.
        assert!(bean_out(None).is_none());
        assert!(bean_out(Some("   ")).is_none());
    }

    /// The canonical round-trip: `export → import → equivalence`.
    /// This is the audit's "free test" — the v2 schema is the contract,
    /// and re-importing the export reproduces every field [`StoredShot`]
    /// models.
    #[test]
    fn v2_shot_round_trips_through_export_import() {
        let shot = fixture();
        let exported = export_v2_json_shot(&shot);
        let parsed = import_v2_json_shot(&exported).expect("re-imports cleanly");

        // Timestamp (Unix ms) survives the seconds round-trip.
        assert_eq!(parsed.completed_at, shot.completed_at);
        // Metadata fields the v2 schema carries all round-trip.
        assert_eq!(parsed.metadata.dose, shot.metadata.dose);
        assert_eq!(parsed.metadata.yield_out, shot.metadata.yield_out);
        assert_eq!(parsed.metadata.tds, shot.metadata.tds);
        assert_eq!(
            parsed.metadata.extraction_yield,
            shot.metadata.extraction_yield
        );
        assert_eq!(parsed.metadata.rating, shot.metadata.rating);
        assert_eq!(parsed.metadata.notes, shot.metadata.notes);
        assert_eq!(
            parsed.metadata.grinder_setting,
            shot.metadata.grinder_setting
        );
        assert_eq!(parsed.metadata.beans, shot.metadata.beans);

        // Sample series survives: length + key channels match.
        assert_eq!(parsed.record.samples.len(), shot.record.samples.len());
        for (i, (a, b)) in parsed
            .record
            .samples
            .iter()
            .zip(shot.record.samples.iter())
            .enumerate()
        {
            assert!(
                (a.sample.group_pressure - b.sample.group_pressure).abs() < 1e-4,
                "pressure[{i}] drifted"
            );
            assert!(
                (a.sample.group_flow - b.sample.group_flow).abs() < 1e-4,
                "flow[{i}] drifted"
            );
            assert!(
                (a.sample.head_temp - b.sample.head_temp).abs() < 1e-4,
                "head_temp[{i}] drifted"
            );
            assert!(
                (a.sample.mix_temp - b.sample.mix_temp).abs() < 1e-4,
                "mix_temp[{i}] drifted"
            );
            assert!(
                (a.sample.set_group_pressure - b.sample.set_group_pressure).abs() < 1e-4,
                "set_group_pressure[{i}] drifted"
            );
            assert!(
                (a.sample.set_group_flow - b.sample.set_group_flow).abs() < 1e-4,
                "set_group_flow[{i}] drifted"
            );
            // The v2 temperature.goal channel feeds both set_head_temp
            // and set_mix_temp on re-import (the v2 schema collapses
            // the split). Compare against the export's source channel.
            assert!(
                (a.sample.set_head_temp - b.sample.set_head_temp).abs() < 1e-4,
                "set_head_temp[{i}] drifted"
            );
            // Overlay channels — the export emits a same-length series
            // of floats (`None` → `0.0`), so the round-trip side sees
            // `Some(0.0)` rather than `None` for the absent values.
            // Compare on the numeric value either way.
            let av = a.scale_weight.unwrap_or(0.0);
            let bv = b.scale_weight.unwrap_or(0.0);
            assert!((av - bv).abs() < 1e-4, "scale_weight[{i}] drifted");
            let av = a.scale_flow_weight.unwrap_or(0.0);
            let bv = b.scale_flow_weight.unwrap_or(0.0);
            assert!((av - bv).abs() < 1e-4, "scale_flow_weight[{i}] drifted");
            let av = a.dispensed_volume.unwrap_or(0.0);
            let bv = b.dispensed_volume.unwrap_or(0.0);
            assert!((av - bv).abs() < 1e-4, "dispensed_volume[{i}] drifted");
            // Resistance signals re-derive at import time from
            // pressure / flow + scale_flow_weight, so they should match
            // the original within the sub-floor guard. When both inputs
            // are sub-floor (the t=0 sample) both sides should be
            // `None`; mid-pour both should be `Some`.
            assert_eq!(
                a.resistance.is_some(),
                b.resistance.is_some(),
                "resistance presence[{i}] drifted"
            );
            assert_eq!(
                a.resistance_weight.is_some(),
                b.resistance_weight.is_some(),
                "resistance_weight presence[{i}] drifted"
            );
        }

        // Profile title survives even with an empty step list.
        // (Crema's StoredShot has no profile in the fixture above —
        // the export emits `"Unknown profile"`, the importer accepts
        // a profile block with no steps.)
        assert!(
            parsed.profile.is_none() || parsed.profile.as_ref().unwrap().title == "Unknown profile"
        );
    }

    /// A shot with no metadata fields populated still emits a valid v2
    /// doc — the `meta` block carries nulls and the round-trip preserves
    /// "absent" rather than synthesising defaults.
    #[test]
    fn export_handles_an_empty_metadata_shot() {
        let mut shot = fixture();
        shot.metadata = ShotMetadata::default();
        let exported = export_v2_json_shot(&shot);
        let v: serde_json::Value = serde_json::from_str(&exported).unwrap();
        // Bean object: missing.
        assert!(v["meta"]["bean"].is_null());
        // Enjoyment: missing (no rating).
        assert!(v["meta"]["shot"]["enjoyment"].is_null());
        // dose / yield: missing.
        assert!(v["meta"]["in"].is_null());
        assert!(v["meta"]["out"].is_null());

        let parsed = import_v2_json_shot(&exported).expect("re-imports cleanly");
        assert!(parsed.metadata.dose.is_none());
        assert!(parsed.metadata.yield_out.is_none());
        assert!(parsed.metadata.beans.is_none());
        assert!(parsed.metadata.rating.is_none());
    }

    #[test]
    fn format_clock_label_is_iso_like_for_a_known_epoch() {
        // 2024-03-15 13:50:22 UTC = Unix 1_710_510_622.
        assert_eq!(format_clock_label(1_710_510_622), "2024-03-15T13:50:22Z");
        // Epoch itself.
        assert_eq!(format_clock_label(0), "1970-01-01T00:00:00Z");
    }

    #[test]
    fn profile_slot_survives_when_a_profile_is_present() {
        let mut shot = fixture();
        let profile = Profile {
            id: String::new(),
            title: "Best Practice".to_string(),
            notes: "test".to_string(),
            steps: Vec::new(),
            preinfuse_step_count: 0,
            minimum_pressure: 0.0,
            maximum_flow: 0.0,
            max_total_volume_ml: 0,
            target_weight: 36.0,
            dose: 18.0,
            author: "Decent".to_string(),
            beverage_type: BeverageType::Espresso,
            tank_temperature: 0.0,
            version: "2".to_string(),
        };
        shot = shot.with_profile(profile);
        let exported = export_v2_json_shot(&shot);
        let v: serde_json::Value = serde_json::from_str(&exported).unwrap();
        assert_eq!(v["profile"]["title"], "Best Practice");
        assert_eq!(v["profile"]["author"], "Decent");
        assert_eq!(v["profile"]["beverage_type"], "espresso");
    }
}

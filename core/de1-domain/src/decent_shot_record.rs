//! Emit a [`StoredShot`] as a decaid `ShotRecord` — the JSON document
//! Decent's shot-history ingest (`POST /support/api/shot_upload`) accepts.
//!
//! Mirrors de1app's `plugins/shot_upload/converter.tcl` (legacy `.shot` →
//! ShotRecord) field for field, which itself mirrors decaid's own
//! `ShotRecord.toJson` (`lib/src/models/data/shot_record.dart`). The three
//! uploaders — decaid, de1app, Crema — therefore land the same document
//! shape in the account, so the server-side charts and decaid's importer
//! read all of them alike (geota/crema#84).
//!
//! This replaces the two hand-written shell converters (web
//! `$lib/decent/shot-record.ts`, Android `DecentShotRecord.kt`), which had
//! drifted apart — the Android copy sent the head-temp setpoint as the mix
//! target and a constant 0 steam temperature, dropped TDS/EY, and ignored
//! the shot-level grinder model. The golden fixture under
//! `tests/fixtures/decent/` pins the contract in one place.
//!
//! Pure and clock-free: every timestamp derives from the shot itself
//! (`completed_at − duration` is the start; each measurement is start +
//! the sample's `elapsed`).
//!
//! Numbers follow the same f32 → JSON rule as
//! [`history_export`](crate::history_export) (which serialises the f32
//! fields directly, so serde_json writes the shortest f32 decimal): `92.4`,
//! never the widened `92.4000015258789`. Integral values are written as
//! JSON integers (`93`, not `93.0`), which is what `JSON.stringify` emitted
//! from the TS converter.

use serde_json::{Map, Number, Value};

use crate::history::{ShotMachine, StoredShot};
use crate::history_export::civil_from_days;

/// Build the decaid `ShotRecord` for `shot`.
///
/// - `machine` — the DE1's identity for provenance (the server checks
///   `serialNumber` against the account). Usually `shot.machine`, falling
///   back to the connected machine for shots recorded before that field
///   existed; the shell picks.
/// - `app_version` — the shell's version string, sent as `app.version`.
///
/// **Samples:** the measurements come from `shot.record.samples`. The
/// stored row may be downsampled; a caller that still holds the
/// full-resolution buffer (the live-shot path) puts it into
/// `shot.record.samples` before calling — there is no separate samples
/// argument.
///
/// `id` is `crema-<shot.id>` (and the workflow `crema-wf-<shot.id>`), so a
/// re-upload of the same shot is addressable; the server assigns its own
/// id and hands it back (see [`crate::decent_upload_reply`]).
///
/// Keys whose value is missing, blank or non-finite are omitted from
/// `workflow.context`, `annotations` and `machine` (decaid omits nulls) —
/// including a blank serial number. Telemetry channels are never omitted:
/// a missing / non-finite reading is `0`, keeping the columns equal-length.
#[must_use]
pub fn decent_shot_record(shot: &StoredShot, machine: &ShotMachine, app_version: &str) -> Value {
    let duration_ms = u64::try_from(shot.record.duration.as_millis()).unwrap_or(u64::MAX);
    let start_ms = shot.completed_at.saturating_sub(duration_ms);

    let measurements: Vec<Value> = shot
        .record
        .samples
        .iter()
        .map(|t| {
            let elapsed_ms = u64::try_from(t.elapsed.as_millis()).unwrap_or(u64::MAX);
            let ts = Value::String(iso_millis(start_ms.saturating_add(elapsed_ms)));
            let s = &t.sample;
            let mut m = Map::new();
            m.insert("timestamp".into(), ts.clone());
            m.insert(
                "state".into(),
                obj([
                    ("state", Value::String("espresso".into())),
                    ("substate", Value::String("pouring".into())),
                ]),
            );
            m.insert("flow".into(), num(s.group_flow));
            m.insert("pressure".into(), num(s.group_pressure));
            m.insert("targetFlow".into(), num(s.set_group_flow));
            m.insert("targetPressure".into(), num(s.set_group_pressure));
            m.insert("mixTemperature".into(), num(s.mix_temp));
            m.insert("groupTemperature".into(), num(s.head_temp));
            m.insert("targetMixTemperature".into(), num(s.set_mix_temp));
            m.insert("targetGroupTemperature".into(), num(s.set_head_temp));
            m.insert("profileFrame".into(), Value::from(s.frame_number));
            m.insert("steamTemperature".into(), num(s.steam_temp));

            let mut scale = Map::new();
            scale.insert("timestamp".into(), ts);
            scale.insert("weight".into(), num(t.scale_weight.unwrap_or(0.0)));
            scale.insert("weightFlow".into(), num(t.scale_flow_weight.unwrap_or(0.0)));
            scale.insert("battery".into(), Value::Null);
            scale.insert("timerValue".into(), Value::Null);

            obj([
                ("machine", Value::Object(m)),
                ("scale", Value::Object(scale)),
            ])
        })
        .collect();

    let meta = &shot.metadata;
    let bean = shot.bean.as_ref();
    let dose = meta.dose;
    let actual_yield = meta.yield_out;
    let target_yield = shot.yield_target.filter(|v| v.is_finite()).or(actual_yield);
    let grinder_model =
        non_blank(shot.grinder_model.as_deref()).or_else(|| non_blank(bean?.grinder.as_deref()));
    let grinder_setting = non_blank(meta.grinder_setting.as_deref())
        .or_else(|| non_blank(bean?.grinder_setting.as_deref()));
    let coffee_name =
        non_blank(bean.map(|b| b.name.as_str())).or_else(|| non_blank(meta.beans.as_deref()));

    let mut context = Map::new();
    put_f32(&mut context, "targetDoseWeight", dose);
    put_f32(&mut context, "targetYield", target_yield);
    put_str(&mut context, "grinderModel", grinder_model);
    put_str(&mut context, "grinderSetting", grinder_setting);
    put_str(&mut context, "coffeeName", coffee_name);
    put_str(
        &mut context,
        "coffeeRoaster",
        bean.and_then(|b| b.roaster_name.as_deref()),
    );
    let mut extras = Map::new();
    put_str(
        &mut extras,
        "roastDate",
        iso_date(bean.and_then(|b| b.roasted_on.as_deref())).as_deref(),
    );
    if let Some(level) = bean.and_then(|b| b.roast_level) {
        extras.insert("roastLevel".into(), Value::from(level));
    }
    if !extras.is_empty() {
        context.insert("extras".into(), Value::Object(extras));
    }

    let mut annotations = Map::new();
    put_f32(&mut annotations, "actualDoseWeight", dose);
    put_f32(&mut annotations, "actualYield", actual_yield);
    put_f32(&mut annotations, "drinkTds", meta.tds);
    put_f32(&mut annotations, "drinkEy", meta.extraction_yield);
    // Crema rates 1..5 (0 / absent = unrated); decaid's enjoyment is 0..10.
    if let Some(rating) = meta.rating.filter(|r| *r > 0) {
        annotations.insert("enjoyment".into(), Value::from(rating.min(5) * 2));
    }
    put_str(&mut annotations, "espressoNotes", meta.notes.as_deref());

    let profile_title = non_blank(shot.profile_name.as_deref())
        .or_else(|| non_blank(shot.profile.as_ref().map(|p| p.title.as_str())))
        .unwrap_or("Shot");
    let profile = shot
        .profile
        .as_ref()
        .and_then(|p| serde_json::to_string(p).ok())
        .and_then(|s| serde_json::from_str::<Value>(&s).ok())
        .map(normalise_numbers)
        .unwrap_or_else(|| {
            // No recipe snapshot (a legacy / pulled shot), or — unreachable
            // for a well-formed Profile — one that failed to serialise: the
            // title-only stub decaid accepts.
            obj([
                ("version", Value::String("2".into())),
                ("title", Value::String(profile_title.to_owned())),
                ("steps", Value::Array(Vec::new())),
            ])
        });

    let mut machine_out = Map::new();
    put_str(
        &mut machine_out,
        "serialNumber",
        Some(machine.serial_number.as_str()),
    );
    put_str(
        &mut machine_out,
        "firmwareVersion",
        machine.firmware_version.as_deref(),
    );
    put_str(&mut machine_out, "model", machine.model.as_deref());

    obj([
        ("id", Value::String(format!("crema-{}", shot.id))),
        ("timestamp", Value::String(iso_millis(start_ms))),
        ("measurements", Value::Array(measurements)),
        (
            "workflow",
            obj([
                ("id", Value::String(format!("crema-wf-{}", shot.id))),
                ("name", Value::String(profile_title.to_owned())),
                ("description", Value::Null),
                ("profile", profile),
                ("context", Value::Object(context)),
                ("steamSettings", Value::Object(Map::new())),
                ("hotWaterData", Value::Object(Map::new())),
                ("rinseData", Value::Object(Map::new())),
            ]),
        ),
        ("annotations", Value::Object(annotations)),
        ("machine", Value::Object(machine_out)),
        (
            "app",
            obj([
                ("name", Value::String("crema".into())),
                ("version", Value::String(app_version.to_owned())),
                ("sourceFormat", Value::String("crema".into())),
            ]),
        ),
        ("schemaVersion", Value::from(1)),
    ])
}

/// JSON-bridged [`decent_shot_record`] for the wasm + uniffi facades.
///
/// `shot_json` is a `de1_domain::StoredShot` (the shell's Rust-shape row,
/// with full-resolution samples already swapped into `record.samples` when
/// it has them); `machine_json` a [`ShotMachine`]. Returns the ShotRecord
/// as compact JSON, ready to POST.
///
/// # Errors
///
/// The JSON parse error string when either input does not deserialise, or
/// the (effectively unreachable) serialise error — RS5: surfaced rather
/// than yielding an empty body the server would reject opaquely.
pub fn decent_shot_record_json(
    shot_json: &str,
    machine_json: &str,
    app_version: &str,
) -> Result<String, String> {
    let shot: StoredShot = serde_json::from_str(shot_json).map_err(|e| e.to_string())?;
    let machine: ShotMachine = serde_json::from_str(machine_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&decent_shot_record(&shot, &machine, app_version))
        .map_err(|e| e.to_string())
}

/// Normalise a free-text roast date to ISO `yyyy-mm-dd`, or `None`.
///
/// Accepts only a *leading* `yyyy-m-d` (1–2 digit month and day, anything
/// may follow — so `2026-09-10T00:00:00Z` works); the month must be 1–12
/// and the day 1–31. No loose date parsing: anything else is omitted
/// rather than guessed at.
#[must_use]
pub fn iso_date(s: Option<&str>) -> Option<String> {
    let b = s?.trim().as_bytes();
    let digits = |from: usize, max: usize| -> usize {
        b.iter()
            .skip(from)
            .take(max)
            .take_while(|c| c.is_ascii_digit())
            .count()
    };
    if b.len() < 8 || digits(0, 4) != 4 || b[4] != b'-' {
        return None;
    }
    let mo_len = digits(5, 2);
    if mo_len == 0 || b.get(5 + mo_len) != Some(&b'-') {
        return None;
    }
    let d_from = 6 + mo_len;
    let d_len = digits(d_from, 2);
    if d_len == 0 {
        return None;
    }
    let text = |from: usize, len: usize| std::str::from_utf8(&b[from..from + len]).ok();
    let year = text(0, 4)?;
    let month: u32 = text(5, mo_len)?.parse().ok()?;
    let day: u32 = text(d_from, d_len)?.parse().ok()?;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    Some(format!("{year}-{month:02}-{day:02}"))
}

/// Unix epoch ms → ISO-8601 UTC with millisecond precision and a `Z`
/// suffix, byte-identical to JS `Date.prototype.toISOString()` for the
/// post-epoch range (`2026-01-02T03:04:05.678Z`).
fn iso_millis(ms: u64) -> String {
    let secs = ms / 1000;
    let (year, month, day) = civil_from_days(secs / 86_400);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}.{:03}Z",
        (secs / 3600) % 24,
        (secs / 60) % 60,
        secs % 60,
        ms % 1000
    )
}

/// A telemetry channel value: the shortest f32 decimal, `0` when
/// non-finite. See the module docs for the number rule.
fn num(v: f32) -> Value {
    f32_value(v).unwrap_or_else(|| Value::from(0))
}

/// The shortest-decimal JSON number for a finite `v` (via f32's `Display`,
/// the same digits serde_json writes for an f32 field), integral values as
/// JSON integers; `None` for NaN / ±∞.
fn f32_value(v: f32) -> Option<Value> {
    if !v.is_finite() {
        return None;
    }
    let shortest: f64 = v.to_string().parse().ok()?;
    Some(f64_value(shortest))
}

/// An f64 as a JSON number, integral values (within `i64`) as integers —
/// what `JSON.stringify` prints for them.
#[allow(clippy::cast_possible_truncation, clippy::cast_precision_loss)]
fn f64_value(v: f64) -> Value {
    // 2^53: every integral f64 below this is exactly representable as i64.
    const EXACT: f64 = 9_007_199_254_740_992.0;
    if v.fract() == 0.0 && v.abs() < EXACT {
        Value::from(v as i64)
    } else {
        Number::from_f64(v).map_or(Value::Null, Value::Number)
    }
}

/// Rewrite every integral float in `v` as a JSON integer (see
/// [`f64_value`]) so the embedded profile reads as the shell's stored
/// object did (`"tank_temperature": 0`, not `0.0`).
fn normalise_numbers(v: Value) -> Value {
    match v {
        Value::Number(n) if n.is_f64() => n.as_f64().map_or(Value::Number(n), f64_value),
        Value::Array(a) => Value::Array(a.into_iter().map(normalise_numbers).collect()),
        Value::Object(o) => Value::Object(
            o.into_iter()
                .map(|(k, v)| (k, normalise_numbers(v)))
                .collect(),
        ),
        other => other,
    }
}

fn non_blank(s: Option<&str>) -> Option<&str> {
    s.filter(|s| !s.trim().is_empty())
}

/// Insert a string only when it is present and non-blank.
fn put_str(target: &mut Map<String, Value>, key: &str, value: Option<&str>) {
    if let Some(v) = non_blank(value) {
        target.insert(key.to_owned(), Value::String(v.to_owned()));
    }
}

/// Insert a number only when it is present and finite.
fn put_f32(target: &mut Map<String, Value>, key: &str, value: Option<f32>) {
    if let Some(v) = value.and_then(f32_value) {
        target.insert(key.to_owned(), v);
    }
}

fn obj<const N: usize>(entries: [(&str, Value); N]) -> Value {
    Value::Object(
        entries
            .into_iter()
            .map(|(k, v)| (k.to_owned(), v))
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use serde_json::json;

    use super::*;
    use crate::bean::ShotBean;
    use crate::history::ShotMetadata;
    use crate::profile::Profile;
    use crate::shot::{ShotRecord, TimedSample};
    use de1_protocol::ShotSample;

    const GOLDEN_SHOT: &str = include_str!("../tests/fixtures/decent/stored_shot.json");
    const GOLDEN_RECORD: &str = include_str!("../tests/fixtures/decent/shot_record.json");

    fn sample(elapsed_ms: u64, scale_weight: Option<f32>) -> TimedSample {
        TimedSample {
            elapsed: Duration::from_millis(elapsed_ms),
            sample: ShotSample {
                sample_time: 0,
                group_pressure: 8.5,
                group_flow: 2.1,
                head_temp: 92.4,
                mix_temp: 93.1,
                set_mix_temp: 93.0,
                set_head_temp: 92.0,
                set_group_pressure: 9.0,
                set_group_flow: 0.0,
                frame_number: 2,
                steam_temp: 140.0,
            },
            scale_weight,
            scale_flow_weight: Some(1.9),
            dispensed_volume: None,
            resistance: None,
            resistance_weight: None,
        }
    }

    fn profile() -> Profile {
        let mut p = crate::builtin_profiles()[0].clone();
        p.title = "Blooming Espresso".to_owned();
        p
    }

    /// The web / Android test shot: 2026-09-24T14:10:30Z completion,
    /// 30 s long → starts 14:10:00Z.
    fn shot() -> StoredShot {
        let record = ShotRecord {
            duration: Duration::from_secs(30),
            samples: vec![
                sample(0, Some(12.5)),
                sample(500, Some(12.5)),
                sample(1000, None),
            ],
        };
        let mut s = StoredShot::new(1_790_259_030_000, record)
            .with_profile(profile())
            .with_metadata(ShotMetadata {
                dose: Some(18.0),
                yield_out: Some(36.2),
                rating: Some(4),
                notes: Some("juicy".to_owned()),
                tds: Some(9.1),
                extraction_yield: Some(20.3),
                ..ShotMetadata::default()
            });
        s.id = "shot:0192".to_owned();
        s.profile_name = Some("Blooming Espresso".to_owned());
        s.bean = Some(ShotBean {
            bean_id: Some("bean:1".to_owned()),
            name: "Yirgacheffe".to_owned(),
            roaster_name: Some("Counter Culture".to_owned()),
            roasted_on: Some("2026-09-10".to_owned()),
            roast_level: Some(3),
            tags: Vec::new(),
            grinder_setting: Some("2.4".to_owned()),
            grinder: Some("Niche Zero".to_owned()),
        });
        s.yield_target = Some(36.0);
        s
    }

    fn machine() -> ShotMachine {
        ShotMachine {
            serial_number: "6262".to_owned(),
            firmware_version: Some("v1.43 build 1352".to_owned()),
            model: Some("DE1PRO".to_owned()),
        }
    }

    #[test]
    fn stamps_identity_provenance_and_the_schema_version() {
        let rec = decent_shot_record(&shot(), &machine(), "0.0.7");
        assert_eq!(rec["id"], "crema-shot:0192");
        assert_eq!(rec["schemaVersion"], 1);
        assert_eq!(
            rec["app"],
            json!({ "name": "crema", "version": "0.0.7", "sourceFormat": "crema" })
        );
        assert_eq!(
            rec["machine"],
            json!({ "serialNumber": "6262", "firmwareVersion": "v1.43 build 1352", "model": "DE1PRO" })
        );
        // Start = completion − duration, JS `toISOString()` shape.
        assert_eq!(rec["timestamp"], "2026-09-24T14:10:00.000Z");
    }

    #[test]
    fn emits_one_time_aligned_measurement_per_sample() {
        let rec = decent_shot_record(&shot(), &machine(), "x");
        let m = rec["measurements"].as_array().unwrap();
        assert_eq!(m.len(), 3);
        assert_eq!(m[1]["machine"]["timestamp"], "2026-09-24T14:10:00.500Z");
        assert_eq!(m[1]["scale"]["timestamp"], "2026-09-24T14:10:00.500Z");
        assert_eq!(
            m[0]["machine"],
            json!({
                "timestamp": "2026-09-24T14:10:00.000Z",
                "state": { "state": "espresso", "substate": "pouring" },
                "flow": 2.1,
                "pressure": 8.5,
                "targetFlow": 0,
                "targetPressure": 9,
                "mixTemperature": 93.1,
                "groupTemperature": 92.4,
                "targetMixTemperature": 93,
                "targetGroupTemperature": 92,
                "profileFrame": 2,
                "steamTemperature": 140
            })
        );
        assert_eq!(
            m[0]["scale"],
            json!({
                "timestamp": "2026-09-24T14:10:00.000Z",
                "weight": 12.5,
                "weightFlow": 1.9,
                "battery": null,
                "timerValue": null
            })
        );
        // A missing scale reading is a 0 (equal-length columns), never a hole.
        assert_eq!(m[2]["scale"]["weight"], 0);
    }

    #[test]
    fn mix_and_group_targets_are_their_own_setpoints_and_steam_is_real() {
        // The Android copy sent set_head_temp for both targets and a
        // constant 0 steam temp; the setpoints are distinct channels.
        let mut s = shot();
        let t = &mut s.record.samples[0].sample;
        t.set_mix_temp = 88.5;
        t.set_head_temp = 91.0;
        t.steam_temp = 152.25;
        let rec = decent_shot_record(&s, &machine(), "x");
        let m = &rec["measurements"][0]["machine"];
        assert_eq!(m["targetMixTemperature"], 88.5);
        assert_eq!(m["targetGroupTemperature"], 91);
        assert_eq!(m["steamTemperature"], 152.25);
    }

    #[test]
    fn non_finite_telemetry_is_zero() {
        let mut s = shot();
        s.record.samples[0].sample.group_flow = f32::NAN;
        s.record.samples[0].sample.steam_temp = f32::INFINITY;
        s.record.samples[0].scale_flow_weight = Some(f32::NEG_INFINITY);
        let rec = decent_shot_record(&s, &machine(), "x");
        let m = &rec["measurements"][0];
        assert_eq!(m["machine"]["flow"], 0);
        assert_eq!(m["machine"]["steamTemperature"], 0);
        assert_eq!(m["scale"]["weightFlow"], 0);
    }

    #[test]
    fn floats_serialise_as_their_shortest_f32_decimal() {
        let rec = decent_shot_record(&shot(), &machine(), "x");
        let text = serde_json::to_string(&rec).unwrap();
        assert!(text.contains(r#""groupTemperature":92.4,"#), "{text}");
        assert!(text.contains(r#""actualYield":36.2,"#), "{text}");
        assert!(text.contains(r#""drinkTds":9.1,"#), "{text}");
        assert!(!text.contains("92.40000"), "no widened f32 in {text}");
        assert!(!text.contains("36.20000"), "no widened f32 in {text}");
        // Integral values print as integers, like JSON.stringify did.
        assert!(text.contains(r#""targetPressure":9,"#), "{text}");
        assert!(text.contains(r#""targetDoseWeight":18,"#), "{text}");
    }

    #[test]
    fn maps_the_workflow_context_bean_extras_and_annotations() {
        let rec = decent_shot_record(&shot(), &machine(), "x");
        let wf = &rec["workflow"];
        assert_eq!(wf["id"], "crema-wf-shot:0192");
        assert_eq!(wf["name"], "Blooming Espresso");
        assert_eq!(wf["description"], Value::Null);
        assert_eq!(wf["profile"]["title"], "Blooming Espresso");
        assert_eq!(wf["steamSettings"], json!({}));
        assert_eq!(wf["hotWaterData"], json!({}));
        assert_eq!(wf["rinseData"], json!({}));
        assert_eq!(
            wf["context"],
            json!({
                "targetDoseWeight": 18,
                "targetYield": 36,
                "grinderModel": "Niche Zero",
                "grinderSetting": "2.4",
                "coffeeName": "Yirgacheffe",
                "coffeeRoaster": "Counter Culture",
                "extras": { "roastDate": "2026-09-10", "roastLevel": 3 }
            })
        );
        assert_eq!(
            rec["annotations"],
            json!({
                "actualDoseWeight": 18,
                "actualYield": 36.2,
                "drinkTds": 9.1,
                "drinkEy": 20.3,
                "enjoyment": 8,
                "espressoNotes": "juicy"
            })
        );
    }

    #[test]
    fn shot_level_fields_win_over_the_bean_snapshot() {
        let mut s = shot();
        s.grinder_model = Some("EG-1".to_owned());
        s.metadata.grinder_setting = Some("6.5".to_owned());
        let rec = decent_shot_record(&s, &machine(), "x");
        let ctx = &rec["workflow"]["context"];
        assert_eq!(ctx["grinderModel"], "EG-1");
        assert_eq!(ctx["grinderSetting"], "6.5");
    }

    #[test]
    fn falls_back_to_the_flat_bean_label_and_measured_yield() {
        let mut s = shot();
        s.bean = None;
        s.yield_target = None;
        s.metadata.beans = Some("Onyx · Geometry".to_owned());
        let rec = decent_shot_record(&s, &machine(), "x");
        let ctx = &rec["workflow"]["context"];
        assert_eq!(ctx["coffeeName"], "Onyx · Geometry");
        assert_eq!(ctx["targetYield"], 36.2);
        assert!(ctx.get("coffeeRoaster").is_none());
        assert!(ctx.get("extras").is_none());
    }

    #[test]
    fn omits_what_it_does_not_know_instead_of_sending_nulls() {
        let mut s = shot();
        s.bean = None;
        s.metadata = ShotMetadata {
            rating: Some(0),
            notes: Some("   ".to_owned()),
            tds: Some(f32::NAN),
            ..ShotMetadata::default()
        };
        s.yield_target = None;
        s.grinder_model = None;
        s.profile = None;
        let bare = ShotMachine {
            serial_number: "1".to_owned(),
            firmware_version: Some(String::new()),
            model: None,
        };
        let rec = decent_shot_record(&s, &bare, "x");
        assert_eq!(rec["annotations"], json!({}));
        assert_eq!(rec["workflow"]["context"], json!({}));
        assert_eq!(
            rec["workflow"]["profile"],
            json!({ "version": "2", "title": "Blooming Espresso", "steps": [] })
        );
        assert_eq!(rec["machine"], json!({ "serialNumber": "1" }));
    }

    #[test]
    fn a_blank_serial_is_omitted() {
        let blank = ShotMachine {
            serial_number: "  ".to_owned(),
            ..ShotMachine::default()
        };
        let rec = decent_shot_record(&shot(), &blank, "x");
        assert_eq!(rec["machine"], json!({}));
    }

    #[test]
    fn rating_maps_to_enjoyment_times_two() {
        let mut s = shot();
        for (rating, enjoyment) in [(1, Some(2)), (5, Some(10)), (0, None)] {
            s.metadata.rating = Some(rating);
            let rec = decent_shot_record(&s, &machine(), "x");
            assert_eq!(
                rec["annotations"].get("enjoyment").and_then(Value::as_u64),
                enjoyment
            );
        }
        s.metadata.rating = None;
        let rec = decent_shot_record(&s, &machine(), "x");
        assert!(rec["annotations"].get("enjoyment").is_none());
    }

    #[test]
    fn profile_name_falls_back_to_the_profile_title_then_shot() {
        let mut s = shot();
        s.profile_name = None;
        let mut p = profile();
        p.title = "Londinium".to_owned();
        s.profile = Some(p);
        let rec = decent_shot_record(&s, &machine(), "x");
        assert_eq!(rec["workflow"]["name"], "Londinium");
        s.profile = None;
        let rec = decent_shot_record(&s, &machine(), "x");
        assert_eq!(rec["workflow"]["name"], "Shot");
        assert_eq!(rec["workflow"]["profile"]["title"], "Shot");
    }

    #[test]
    fn the_embedded_profile_is_its_stored_json() {
        let mut p = profile();
        p.tank_temperature = 92.4;
        let expected = serde_json::to_value(&p).unwrap();
        let mut s = shot();
        s.profile = Some(p);
        let rec = decent_shot_record(&s, &machine(), "x");
        let got = &rec["workflow"]["profile"];
        // Same keys (the Rust `Profile` wire shape the web persists) …
        assert_eq!(
            got.as_object().unwrap().keys().collect::<Vec<_>>(),
            expected.as_object().unwrap().keys().collect::<Vec<_>>()
        );
        // … with f32s at their shortest decimal.
        assert!(
            serde_json::to_string(got)
                .unwrap()
                .contains(r#""tank_temperature":92.4"#)
        );
    }

    #[test]
    fn full_resolution_samples_ride_in_the_record() {
        let mut s = shot();
        s.record.samples = (0..4).map(|i| sample(i * 100, Some(1.0))).collect();
        let rec = decent_shot_record(&s, &machine(), "x");
        assert_eq!(rec["measurements"].as_array().unwrap().len(), 4);
        assert_eq!(
            rec["measurements"][3]["machine"]["timestamp"],
            "2026-09-24T14:10:00.300Z"
        );
    }

    #[test]
    fn roast_dates_accept_only_a_leading_iso_date() {
        assert_eq!(iso_date(Some("2026-9-3")).as_deref(), Some("2026-09-03"));
        assert_eq!(
            iso_date(Some(" 2026-09-10 ")).as_deref(),
            Some("2026-09-10")
        );
        assert_eq!(
            iso_date(Some("2026-09-10T08:00:00Z")).as_deref(),
            Some("2026-09-10")
        );
        assert_eq!(iso_date(Some("2026-13-01")), None);
        assert_eq!(iso_date(Some("2026-00-01")), None);
        assert_eq!(iso_date(Some("2026-01-32")), None);
        assert_eq!(iso_date(Some("2026-01-00")), None);
        assert_eq!(iso_date(Some("yesterday-ish")), None);
        assert_eq!(iso_date(Some("Sep 10, 2026")), None);
        assert_eq!(iso_date(Some("10/09/2026")), None);
        assert_eq!(iso_date(Some("26-09-10")), None);
        assert_eq!(iso_date(Some("")), None);
        assert_eq!(iso_date(None), None);
    }

    #[test]
    fn an_unparseable_roast_date_is_omitted() {
        let mut s = shot();
        if let Some(b) = s.bean.as_mut() {
            b.roasted_on = Some("last tuesday".to_owned());
            b.roast_level = None;
        }
        let rec = decent_shot_record(&s, &machine(), "x");
        assert!(rec["workflow"]["context"].get("extras").is_none());
    }

    #[test]
    fn iso_millis_matches_js_to_iso_string() {
        // new Date(1767323045678).toISOString()
        assert_eq!(iso_millis(1_767_323_045_678), "2026-01-02T03:04:05.678Z");
        assert_eq!(iso_millis(0), "1970-01-01T00:00:00.000Z");
        // Leap day.
        assert_eq!(iso_millis(1_709_164_800_001), "2024-02-29T00:00:00.001Z");
    }

    #[test]
    fn the_json_facade_parses_and_emits() {
        let shot_json = serde_json::to_string(&shot()).unwrap();
        let machine_json = serde_json::to_string(&machine()).unwrap();
        let out = decent_shot_record_json(&shot_json, &machine_json, "0.0.7").unwrap();
        let parsed: Value = serde_json::from_str(&out).unwrap();
        assert_eq!(parsed, decent_shot_record(&shot(), &machine(), "0.0.7"));
        assert!(decent_shot_record_json("{", &machine_json, "x").is_err());
        assert!(decent_shot_record_json(&shot_json, "[]", "x").is_err());
    }

    /// The contract, pinned in one place: a web-shaped StoredShot row in,
    /// the exact ShotRecord out.
    #[test]
    fn golden_fixture() {
        let fixture: Value = serde_json::from_str(GOLDEN_SHOT).unwrap();
        let shot: StoredShot = serde_json::from_value(fixture["shot"].clone()).unwrap();
        let machine: ShotMachine = serde_json::from_value(fixture["machine"].clone()).unwrap();
        let app_version = fixture["appVersion"].as_str().unwrap();
        let expected: Value = serde_json::from_str(GOLDEN_RECORD).unwrap();
        let got = decent_shot_record(&shot, &machine, app_version);
        assert!(
            got == expected,
            "ShotRecord drifted from the golden fixture:\n{}",
            serde_json::to_string_pretty(&got).unwrap_or_default()
        );
    }
}

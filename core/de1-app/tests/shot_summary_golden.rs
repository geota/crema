//! The History row's summary and spark (#310, SPEC §4) from a real shot: the
//! recorded session `replay_fixture.rs` replays, through `CremaCore`, to the
//! `StoredShot` a shell would save. The spark is pinned byte for byte, so a
//! change to its rule shows here and must bump its version.

use std::fs::File;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;

use de1_app::{CremaCore, Event, Source};
use de1_domain::shot_summary::{SPARK_GAP, SPARK_MACHINE, SPARK_WEIGHT};
use de1_domain::{ShotCaptureContext, StoredShot, decode_spark, shot_summary};

#[derive(serde::Deserialize)]
struct CaptureLine {
    t: u64,
    dir: String,
    src: String,
    hex: String,
}

/// The fixture's one shot, as the core assembles it.
fn recorded_shot() -> StoredShot {
    let fixture = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures/session-20260517-122732-shot-pull.jsonl");
    let reader = BufReader::new(File::open(fixture).expect("fixture"));
    let mut core = CremaCore::new();
    let mut shot = None;
    for line in reader.lines() {
        let line = line.expect("line");
        if line.trim().is_empty() {
            continue;
        }
        let entry: CaptureLine = serde_json::from_str(&line).expect("capture line");
        let source = match (entry.dir.as_str(), entry.src.as_str()) {
            ("in", "DE1_STATE") => Source::De1State,
            ("in", "DE1_SHOT_SAMPLE") => Source::De1ShotSample,
            ("in", "DE1_WATER_LEVELS") => Source::De1WaterLevels,
            _ => continue,
        };
        let data: Vec<u8> = (0..entry.hex.len())
            .step_by(2)
            .map(|i| u8::from_str_radix(&entry.hex[i..i + 2], 16).expect("hex"))
            .collect();
        let out = core.on_notification(source, &data, entry.t);
        if out
            .events
            .iter()
            .any(|e| matches!(e, Event::ShotCompleted { .. }))
        {
            shot = core.take_stored_shot(ShotCaptureContext {
                completed_at: 1_779_000_000_000,
                id: Some("golden".to_owned()),
                ..Default::default()
            });
        }
    }
    shot.expect("the session holds one shot")
}

/// The pinned spark of the recorded shot, which had no scale.
const GOLDEN_NO_SCALE: &str = "AQEABRIvkJqZmJiYmJiYlJGPjYyKiIaFg4F/fXt6eHZ1c3HO/vtyHRUXGx4iJikqLTAyMzQ0NTc2ODg5Pj8/Pjs2Nv//////////////////////////////////////////";
/// The same shot with a scale simulated: the cup takes the group flow, from
/// the third second on.
const GOLDEN_SCALE: &str = "AQMABRIvkJqZmJiYmJiYlJGPjYyKiIaFg4F/fXt6eHZ1c3HO/vtyHRUXGx4iJikqLTAyMzQ0NTc2ODg5Pj8/Pjs2Nv8NKUJKTVBTV1xgZmxyeH+GjZSbo6qyucHJ0trj6/P7";

#[test]
fn the_recorded_shot_summarises_to_its_pinned_spark() {
    let shot = recorded_shot();
    let summary = shot_summary(&shot);
    let peaks = shot.peaks();
    assert_eq!(summary.peak_pressure, peaks.peak_pressure);
    assert_eq!(summary.peak_temp, peaks.peak_temp);
    assert_eq!(summary.peak_weight, peaks.peak_weight);
    assert_eq!(summary.final_weight, peaks.final_weight);
    assert_eq!(
        summary.sample_count,
        Some(u32::try_from(shot.record.samples.len()).unwrap())
    );
    let spark = summary.spark.expect("a machine shot has a spark");
    let decoded = decode_spark(&spark).expect("it reads back");
    assert_eq!(decoded.flags, SPARK_MACHINE);
    assert!(
        decoded.weight.iter().all(|v| *v == SPARK_GAP),
        "no scale, no weight line"
    );
    assert!(
        decoded.pressure.iter().any(|v| *v > 100),
        "the shot reaches pressure"
    );
    assert_eq!(spark, GOLDEN_NO_SCALE);
}

#[test]
fn the_recorded_shot_with_a_scale_draws_its_cup() {
    let mut shot = recorded_shot();
    let mut cup = 0.0_f32;
    let mut last_ms = 0_u128;
    for s in &mut shot.record.samples {
        let ms = s.elapsed.as_millis();
        #[allow(clippy::cast_precision_loss)]
        let dt = (ms - last_ms) as f32 / 1000.0;
        last_ms = ms;
        if ms >= 3000 {
            cup += s.sample.group_flow * dt;
            s.scale_weight = Some(cup);
        }
    }
    let summary = shot_summary(&shot);
    assert_eq!(summary.peak_weight, shot.peaks().peak_weight);
    assert_eq!(summary.final_weight, shot.peaks().final_weight);
    let spark = summary.spark.expect("a spark");
    let decoded = decode_spark(&spark).expect("it reads back");
    assert_eq!(decoded.flags, SPARK_MACHINE | SPARK_WEIGHT);
    assert_eq!(
        decoded.weight[0], SPARK_GAP,
        "no reading before the scale's first"
    );
    let drawn: Vec<u8> = decoded
        .weight
        .iter()
        .copied()
        .filter(|v| *v != SPARK_GAP)
        .collect();
    assert!(drawn.windows(2).all(|w| w[0] <= w[1]), "the cup only fills");
    assert!(*decoded.weight.last().unwrap() >= 245, "on its own peak");
    assert_eq!(spark, GOLDEN_SCALE);
}

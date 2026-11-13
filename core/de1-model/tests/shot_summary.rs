//! The History row's summary (#310, SPEC §4) over any series: empty, short,
//! with and without a scale. Its peaks are `ShotRecord::peaks`, its spark
//! always reads back, and a channel with values is drawn without gaps.

use std::time::Duration;

use de1_domain::shot_summary::{SPARK_GAP, SPARK_MACHINE, SPARK_WEIGHT};
use de1_domain::{ShotRecord, StoredShot, TimedSample, decode_spark, shot_summary};
use de1_protocol::ShotSample;
use proptest::prelude::*;

fn timed(ms: u64, pressure: f32, flow: f32, weight: Option<f32>) -> TimedSample {
    TimedSample {
        elapsed: Duration::from_millis(ms),
        sample: ShotSample {
            sample_time: 0,
            group_pressure: pressure,
            group_flow: flow,
            head_temp: 90.0 + pressure,
            mix_temp: 92.0,
            set_mix_temp: 92.0,
            set_head_temp: 92.0,
            set_group_pressure: 0.0,
            set_group_flow: 0.0,
            frame_number: 0,
            steam_temp: 140.0,
        },
        scale_weight: weight,
        scale_flow_weight: None,
        dispensed_volume: None,
        resistance: None,
        resistance_weight: None,
    }
}

/// A series: gaps between samples, values, and whether a scale is paired.
fn series() -> impl Strategy<Value = Vec<TimedSample>> {
    (
        prop::collection::vec(
            (0_u64..2_000, 0.0_f32..14.0, 0.0_f32..12.0, 0.0_f32..80.0),
            0..300,
        ),
        any::<bool>(),
        0_usize..40,
    )
        .prop_map(|(raw, scale, first_reading)| {
            let mut t = 0;
            raw.into_iter()
                .enumerate()
                .map(|(i, (gap, p, f, w))| {
                    t += gap;
                    timed(t, p, f, (scale && i >= first_reading).then_some(w))
                })
                .collect()
        })
}

proptest! {
    #[test]
    fn any_series_summarises_and_its_spark_reads_back(samples in series()) {
        let n = samples.len();
        let has_weight = samples.iter().any(|s| s.scale_weight.is_some());
        let duration = samples.last().map_or(Duration::ZERO, |s| s.elapsed);
        let shot = StoredShot::new(1, ShotRecord { duration, samples });
        let summary = shot_summary(&shot);
        let peaks = shot.peaks();
        prop_assert_eq!(summary.peak_pressure, peaks.peak_pressure);
        prop_assert_eq!(summary.peak_temp, peaks.peak_temp);
        prop_assert_eq!(summary.peak_weight, peaks.peak_weight);
        prop_assert_eq!(summary.final_weight, peaks.final_weight);
        prop_assert_eq!(summary.sample_count, Some(u32::try_from(n).unwrap()));
        prop_assert_eq!(summary.brew_sample_count, 0);
        let Some(spark) = summary.spark else {
            prop_assert_eq!(n, 0, "only an empty series has no spark");
            return Ok(());
        };
        prop_assert!(n > 0);
        prop_assert!(spark.len() <= 140);
        let spark = decode_spark(&spark).expect("a spark reads back");
        let flags = if has_weight { SPARK_MACHINE | SPARK_WEIGHT } else { SPARK_MACHINE };
        prop_assert_eq!(spark.flags, flags);
        prop_assert!(spark.pressure.iter().chain(&spark.flow).all(|v| *v != SPARK_GAP), "every slice drawn");
        if !has_weight {
            prop_assert!(spark.weight.iter().all(|v| *v == SPARK_GAP));
        }
        prop_assert!(spark.ticks.is_empty());
    }
}

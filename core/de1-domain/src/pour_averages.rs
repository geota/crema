//! A shot's averages over its pour (#299): pressure, flow and head
//! temperature from the first flow to the last, so neither the preheat nor
//! the trailing drain pulls them toward zero.
//!
//! Derived on display from the recorded samples, like
//! [`ShotRecord::peaks`](crate::shot::ShotRecord::peaks): nothing is stored, so
//! backups and old shots need no migration. A shot with no pour in its samples
//! (none recorded, an import without telemetry) has no averages.
//!
//! The pour is the run of samples from the first whose group flow reaches
//! [`POUR_FLOW_MIN_ML_S`] to the last that does. A live shot's series already
//! starts at first flow (the recorder drops the heating samples); an imported
//! one may carry the preheat, which this trims. A pause inside the pour (a
//! bloom) stays in: it is part of the shot.
//!
//! Each average is weighted by time (the trapezoid rule over the samples'
//! `elapsed`), so a gap in the series or a downsampled stored series counts
//! each stretch of the pour for its length.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::shot::{ShotRecord, TimedSample};

/// Group flow at or above this marks a sample as part of the pour, ml/s.
/// Above the near-zero readings of a heating group, below any preinfusion.
pub const POUR_FLOW_MIN_ML_S: f32 = 0.3;

/// The pour's time-weighted averages.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PourAverages {
    /// Group pressure, bar.
    pub pressure: f32,
    /// Group flow, ml/s.
    pub flow: f32,
    /// Group-head temperature, °C. `None` when the series carries none (an
    /// import that recorded no head temperature reads 0).
    pub head_temp: Option<f32>,
}

/// The pour's averages, or `None` when the samples hold no pour: fewer than
/// two pour samples, or no time between them.
#[must_use]
pub fn pour_averages(record: &ShotRecord) -> Option<PourAverages> {
    let samples = &record.samples;
    let in_pour = |t: &TimedSample| t.sample.group_flow >= POUR_FLOW_MIN_ML_S;
    let first = samples.iter().position(in_pour)?;
    let last = samples.iter().rposition(in_pour)?;
    let pour = &samples[first..=last];
    let pressure = time_weighted(pour, |t| Some(t.sample.group_pressure))?;
    let flow = time_weighted(pour, |t| Some(t.sample.group_flow))?;
    let head_temp = time_weighted(pour, |t| {
        Some(t.sample.head_temp).filter(|c| c.is_finite() && *c > 0.0)
    });
    Some(PourAverages {
        pressure,
        flow,
        head_temp,
    })
}

/// The trapezoid-rule mean of `value` over `pour`, skipping an interval
/// either end of which has no value or whose time doesn't move forward.
/// `None` with no interval left.
fn time_weighted(pour: &[TimedSample], value: impl Fn(&TimedSample) -> Option<f32>) -> Option<f32> {
    let mut area = 0.0f64;
    let mut span = 0.0f64;
    for pair in pour.windows(2) {
        let (a, b) = (&pair[0], &pair[1]);
        let (Some(va), Some(vb)) = (value(a), value(b)) else {
            continue;
        };
        if !va.is_finite() || !vb.is_finite() || b.elapsed <= a.elapsed {
            continue;
        }
        let dt = (b.elapsed - a.elapsed).as_secs_f64();
        area += f64::from(va + vb) / 2.0 * dt;
        span += dt;
    }
    #[allow(clippy::cast_possible_truncation)]
    (span > 0.0).then(|| (area / span) as f32)
}

/// [`pour_averages`] over a `StoredShot` JSON: the averages JSON, or `null`
/// when the shot has no pour.
///
/// # Errors
///
/// The JSON error string when `stored_shot_json` is not a `StoredShot`.
pub fn pour_averages_json(stored_shot_json: &str) -> Result<String, String> {
    let shot: crate::StoredShot =
        serde_json::from_str(stored_shot_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&pour_averages(&shot.record)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use de1_protocol::ShotSample;

    use super::*;

    /// A sample at `ms` with `pressure` bar, `flow` ml/s and `head` °C.
    fn at(ms: u64, pressure: f32, flow: f32, head: f32) -> TimedSample {
        TimedSample {
            elapsed: Duration::from_millis(ms),
            sample: ShotSample {
                sample_time: 0,
                group_pressure: pressure,
                group_flow: flow,
                head_temp: head,
                mix_temp: head,
                set_mix_temp: 0.0,
                set_head_temp: 0.0,
                set_group_pressure: 0.0,
                set_group_flow: 0.0,
                frame_number: 0,
                steam_temp: 0.0,
            },
            scale_weight: None,
            scale_flow_weight: None,
            dispensed_volume: None,
            resistance: None,
            resistance_weight: None,
        }
    }

    fn record(samples: Vec<TimedSample>) -> ShotRecord {
        ShotRecord {
            duration: samples.last().map_or(Duration::ZERO, |s| s.elapsed),
            samples,
        }
    }

    fn close(a: f32, b: f32) -> bool {
        (a - b).abs() < 1e-4
    }

    #[test]
    fn a_steady_pour_averages_to_its_level() {
        let r = record((0..=10).map(|i| at(i * 250, 9.0, 2.0, 93.0)).collect());
        let avg = pour_averages(&r).expect("a pour");
        assert!(close(avg.pressure, 9.0) && close(avg.flow, 2.0));
        assert_eq!(avg.head_temp, Some(93.0));
    }

    /// An import that carries a 30 s preheat (pump off, the group heating)
    /// and a drain: only the pour counts.
    #[test]
    fn a_long_preheat_and_the_drain_are_left_out() {
        let mut samples: Vec<_> = (0..120).map(|i| at(i * 250, 0.0, 0.0, 80.0)).collect();
        samples.extend((120..=160).map(|i| at(i * 250, 8.0, 2.5, 92.0)));
        samples.extend((161..180).map(|i| at(i * 250, 1.0, 0.1, 91.0)));
        let avg = pour_averages(&record(samples)).expect("a pour");
        assert!(close(avg.pressure, 8.0), "{avg:?}");
        assert!(close(avg.flow, 2.5), "{avg:?}");
        assert_eq!(avg.head_temp, Some(92.0));
    }

    /// The average is by time, not by sample: a slow stretch with few
    /// samples counts for its length.
    #[test]
    fn the_average_is_weighted_by_time() {
        // 1 s at 9 bar sampled every 100 ms, then 3 s at 3 bar in one gap.
        let mut samples: Vec<_> = (0..=10).map(|i| at(i * 100, 9.0, 2.0, 93.0)).collect();
        samples.push(at(1_000, 3.0, 2.0, 93.0));
        samples.push(at(4_000, 3.0, 2.0, 93.0));
        let avg = pour_averages(&record(samples)).expect("a pour");
        assert!(close(avg.pressure, 4.5), "{avg:?}");
    }

    /// A bloom inside the pour is part of the shot.
    #[test]
    fn a_pause_inside_the_pour_stays_in() {
        let samples = vec![
            at(0, 4.0, 4.0, 93.0),
            at(1_000, 4.0, 4.0, 93.0),
            at(2_000, 1.0, 0.0, 93.0),
            at(3_000, 1.0, 0.0, 93.0),
            at(4_000, 4.0, 4.0, 93.0),
        ];
        let avg = pour_averages(&record(samples)).expect("a pour");
        // 1 s at 4, 1 s ramp 4→1, 1 s at 1, 1 s ramp 1→4.
        assert!(close(avg.pressure, 2.5), "{avg:?}");
        assert!(close(avg.flow, 2.0), "{avg:?}");
    }

    #[test]
    fn no_pour_means_no_averages() {
        assert_eq!(pour_averages(&record(vec![])), None, "no samples");
        let preheat_only = record((0..20).map(|i| at(i * 250, 0.0, 0.0, 80.0)).collect());
        assert_eq!(pour_averages(&preheat_only), None, "never flowed");
        let one = record(vec![at(0, 0.0, 0.0, 80.0), at(250, 9.0, 2.0, 93.0)]);
        assert_eq!(pour_averages(&one), None, "a single pour sample");
    }

    /// An import with no head temperature reads 0: no average for it.
    #[test]
    fn a_missing_head_temperature_has_no_average() {
        let r = record((0..=4).map(|i| at(i * 250, 9.0, 2.0, 0.0)).collect());
        let avg = pour_averages(&r).expect("a pour");
        assert_eq!(avg.head_temp, None);
        assert!(close(avg.pressure, 9.0));
    }

    #[test]
    fn the_json_face_reads_a_stored_shot() {
        let json = |r: ShotRecord| serde_json::to_string(&crate::StoredShot::new(1, r)).unwrap();
        assert_eq!(pour_averages_json(&json(record(vec![]))).unwrap(), "null");
        let pour = record((0..=4).map(|i| at(i * 250, 9.0, 2.0, 93.0)).collect());
        let out: PourAverages =
            serde_json::from_str(&pour_averages_json(&json(pour)).unwrap()).unwrap();
        assert!(close(out.pressure, 9.0));
        assert!(pour_averages_json("{").is_err());
    }
}

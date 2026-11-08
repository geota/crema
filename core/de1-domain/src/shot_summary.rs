//! A shot's summary: what a History row shows without loading its series
//! (#310, SPEC §4). Written at save, on migration and on restore, and not
//! part of a backup, so the backup format doesn't change.
//!
//! **Spark.** The row chart's silhouette, as base64 of a few bytes:
//!
//! | Bytes | Holds |
//! | --- | --- |
//! | 1 | Version, 1 |
//! | 1 | Flags: [`SPARK_MACHINE`], [`SPARK_WEIGHT`], [`SPARK_BREW`] |
//! | 1 | Stage ticks, 0 to [`SPARK_MAX_TICKS`] |
//! | 32 | Pressure, on [`SPARK_PRESSURE_TOP`] bar |
//! | 32 | Flow, on [`SPARK_FLOW_TOP`] ml/s (g/s for a brew) |
//! | 32 | Weight, on the shot's own peak, at least [`SPARK_WEIGHT_FLOOR`] g |
//! | ticks | Each stage tick's place in the brew, 0 to 255 |
//!
//! Each point is `0..=254` for 0 to 1 of its scale, clamped, or
//! [`SPARK_GAP`] where the channel has no value: no scale, a brew's
//! pressure, or the weight before the first reading. The scales are the web
//! row chart's (`MiniShotChart.svelte`). Point `i` is the mean of the
//! samples in the `i`th of 32 equal slices of the shot's time, or the sample
//! nearest that slice's middle when it has none.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::StoredShot;

/// Points per channel.
pub const SPARK_POINTS: usize = 32;
/// At most this many stage ticks are kept, the first ones.
pub const SPARK_MAX_TICKS: usize = 8;
/// A point with no value.
pub const SPARK_GAP: u8 = 255;
/// The pressure scale's top, bar.
pub const SPARK_PRESSURE_TOP: f32 = 10.0;
/// The flow scale's top, ml/s.
pub const SPARK_FLOW_TOP: f32 = 8.0;
/// The weight scale's top is the shot's peak weight, at least this, grams.
pub const SPARK_WEIGHT_FLOOR: f32 = 30.0;
/// The spark's format.
pub const SPARK_VERSION: u8 = 1;
/// Flag: drawn from the machine's samples.
pub const SPARK_MACHINE: u8 = 1;
/// Flag: some point has a weight.
pub const SPARK_WEIGHT: u8 = 2;
/// Flag: drawn from a brew's scale series.
pub const SPARK_BREW: u8 = 4;

/// What a History row shows of a shot without its series.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotSummary {
    /// The summary's format: 1.
    pub v: u32,
    /// Machine samples in the series. The shell stores `null` when it
    /// couldn't read the series; the core always counts.
    pub sample_count: Option<u32>,
    /// Samples in a brew's scale series; 0 for none.
    pub brew_sample_count: u32,
    /// As [`crate::ShotRecord::peaks`].
    pub peak_weight: Option<f32>,
    /// As [`crate::ShotRecord::peaks`].
    pub final_weight: Option<f32>,
    /// As [`crate::ShotRecord::peaks`].
    pub peak_pressure: f32,
    /// As [`crate::ShotRecord::peaks`].
    pub peak_temp: f32,
    /// The row chart, as described in the module docs; `None` with no series.
    pub spark: Option<String>,
}

/// One sample of a channel set, for the spark.
struct Point {
    t_ms: f64,
    pressure: Option<f32>,
    flow: Option<f32>,
    weight: Option<f32>,
}

/// `shot`'s summary.
#[must_use]
pub fn shot_summary(shot: &StoredShot) -> ShotSummary {
    let peaks = shot.peaks();
    let brew_samples = shot.brew_series.as_ref().map_or(0, |b| b.samples.len());
    ShotSummary {
        v: 1,
        sample_count: Some(u32::try_from(shot.record.samples.len()).unwrap_or(u32::MAX)),
        brew_sample_count: u32::try_from(brew_samples).unwrap_or(u32::MAX),
        peak_weight: peaks.peak_weight,
        final_weight: peaks.final_weight,
        peak_pressure: peaks.peak_pressure,
        peak_temp: peaks.peak_temp,
        spark: spark_of(shot).map(|bytes| base64(&bytes)),
    }
}

/// [`shot_summary`] over JSON: a `StoredShot` in, a [`ShotSummary`] out.
///
/// # Errors
/// When the shot doesn't parse.
pub fn shot_summary_json(stored_shot_json: &str) -> Result<String, String> {
    let shot: StoredShot = serde_json::from_str(stored_shot_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&shot_summary(&shot)).map_err(|e| e.to_string())
}

/// The spark's bytes, or `None` for a shot with no series.
#[allow(clippy::cast_precision_loss)]
fn spark_of(shot: &StoredShot) -> Option<Vec<u8>> {
    let (points, mut flags, ticks): (Vec<Point>, u8, Vec<u64>) = if shot.record.samples.is_empty() {
        let brew = shot
            .brew_series
            .as_ref()
            .filter(|b| !b.samples.is_empty())?;
        let points = brew
            .samples
            .iter()
            .map(|s| Point {
                t_ms: s.elapsed_ms as f64,
                pressure: None,
                flow: s.flow_g_s,
                weight: Some(s.weight_g),
            })
            .collect();
        let ticks = brew.stage_marks.iter().map(|m| m.elapsed_ms).collect();
        (points, SPARK_BREW, ticks)
    } else {
        let points = shot
            .record
            .samples
            .iter()
            .map(|s| Point {
                t_ms: s.elapsed.as_secs_f64() * 1000.0,
                pressure: Some(s.sample.group_pressure),
                flow: Some(s.sample.group_flow),
                weight: s.scale_weight,
            })
            .collect();
        (points, SPARK_MACHINE, Vec::new())
    };
    let end = points.iter().map(|p| p.t_ms).fold(0.0_f64, f64::max);
    let peak_weight = points
        .iter()
        .filter_map(|p| p.weight)
        .filter(|w| w.is_finite())
        .fold(0.0_f32, f32::max);
    let weight_top = peak_weight.max(SPARK_WEIGHT_FLOOR);
    let pressure = channel(&points, end, |p| p.pressure, SPARK_PRESSURE_TOP);
    let flow = channel(&points, end, |p| p.flow, SPARK_FLOW_TOP);
    let weight = channel(&points, end, |p| p.weight, weight_top);
    if weight.iter().any(|v| *v != SPARK_GAP) {
        flags |= SPARK_WEIGHT;
    }
    let ticks: Vec<u8> = ticks
        .into_iter()
        .take(SPARK_MAX_TICKS)
        .map(|t| quantize_place(t as f64, end))
        .collect();
    let mut out = Vec::with_capacity(3 + 3 * SPARK_POINTS + ticks.len());
    out.push(SPARK_VERSION);
    out.push(flags);
    out.push(u8::try_from(ticks.len()).unwrap_or(0));
    out.extend(pressure);
    out.extend(flow);
    out.extend(weight);
    out.extend(ticks);
    Some(out)
}

/// One channel's points over `end` ms, on a scale topping at `top`.
#[allow(clippy::cast_precision_loss)]
fn channel(
    points: &[Point],
    end: f64,
    pick: impl Fn(&Point) -> Option<f32>,
    top: f32,
) -> [u8; SPARK_POINTS] {
    let slice = end / SPARK_POINTS as f64;
    let mut out = [SPARK_GAP; SPARK_POINTS];
    for (i, slot) in out.iter_mut().enumerate() {
        let lo = slice * i as f64;
        let hi = slice * (i + 1) as f64;
        let last = i + 1 == SPARK_POINTS;
        let mut sum = 0.0_f64;
        let mut n = 0_u32;
        let mut any = false;
        for p in points {
            let inside = p.t_ms >= lo && (p.t_ms < hi || (last && p.t_ms <= hi));
            if !inside {
                continue;
            }
            any = true;
            if let Some(v) = pick(p).filter(|v| v.is_finite()) {
                sum += f64::from(v);
                n += 1;
            }
        }
        let value = if any {
            (n > 0).then(|| sum / f64::from(n))
        } else {
            // An empty slice, in a short series: the sample nearest its middle.
            let mid = (lo + hi) / 2.0;
            points
                .iter()
                .min_by(|a, b| (a.t_ms - mid).abs().total_cmp(&(b.t_ms - mid).abs()))
                .and_then(|p| pick(p).filter(|v| v.is_finite()))
                .map(f64::from)
        };
        if let Some(v) = value {
            *slot = quantize(v / f64::from(top));
        }
    }
    out
}

/// `0..=1` as `0..=254`, clamped.
#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn quantize(norm: f64) -> u8 {
    (norm.clamp(0.0, 1.0) * 254.0).round() as u8
}

/// A moment's place in `0..=end` as `0..=255`.
#[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
fn quantize_place(t: f64, end: f64) -> u8 {
    if end <= 0.0 {
        return 0;
    }
    ((t / end).clamp(0.0, 1.0) * 255.0).round() as u8
}

const BASE64: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/// Standard base64, padded.
fn base64(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let b = [
            chunk[0],
            *chunk.get(1).unwrap_or(&0),
            *chunk.get(2).unwrap_or(&0),
        ];
        let n = (u32::from(b[0]) << 16) | (u32::from(b[1]) << 8) | u32::from(b[2]);
        for (i, shift) in [18, 12, 6, 0].into_iter().enumerate() {
            if i <= chunk.len() {
                out.push(char::from(BASE64[((n >> shift) & 63) as usize]));
            } else {
                out.push('=');
            }
        }
    }
    out
}

/// A spark, read back: for tests, and for a shell that decodes in the core.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Spark {
    pub flags: u8,
    pub pressure: [u8; SPARK_POINTS],
    pub flow: [u8; SPARK_POINTS],
    pub weight: [u8; SPARK_POINTS],
    pub ticks: Vec<u8>,
}

/// Read a spark back, or `None` when it isn't one this build writes.
#[must_use]
pub fn decode_spark(spark: &str) -> Option<Spark> {
    let bytes = unbase64(spark)?;
    let (&version, rest) = bytes.split_first()?;
    if version != SPARK_VERSION {
        return None;
    }
    let (&flags, rest) = rest.split_first()?;
    let (&ticks, rest) = rest.split_first()?;
    if rest.len() != 3 * SPARK_POINTS + usize::from(ticks) {
        return None;
    }
    let take = |i: usize| -> [u8; SPARK_POINTS] {
        let mut a = [0; SPARK_POINTS];
        a.copy_from_slice(&rest[i * SPARK_POINTS..(i + 1) * SPARK_POINTS]);
        a
    };
    Some(Spark {
        flags,
        pressure: take(0),
        flow: take(1),
        weight: take(2),
        ticks: rest[3 * SPARK_POINTS..].to_vec(),
    })
}

fn unbase64(text: &str) -> Option<Vec<u8>> {
    let digits: Vec<u32> = text
        .trim_end_matches('=')
        .bytes()
        .map(|c| {
            BASE64
                .iter()
                .position(|b| *b == c)
                .and_then(|p| u32::try_from(p).ok())
        })
        .collect::<Option<_>>()?;
    let mut out = Vec::with_capacity(digits.len() * 3 / 4);
    for chunk in digits.chunks(4) {
        if chunk.len() == 1 {
            return None;
        }
        let n = chunk
            .iter()
            .enumerate()
            .fold(0_u32, |n, (i, d)| n | (d << (18 - 6 * i)));
        let bytes = n.to_be_bytes();
        out.extend_from_slice(&bytes[1..chunk.len()]);
    }
    Some(out)
}

#[cfg(test)]
#[allow(
    clippy::cast_precision_loss,
    clippy::cast_possible_truncation,
    clippy::cast_sign_loss
)]
mod tests {
    use super::*;
    use crate::{ShotRecord, TimedSample};
    use de1_protocol::ShotSample;
    use std::time::Duration;

    pub(crate) fn sample(ms: u64, pressure: f32, flow: f32, weight: Option<f32>) -> TimedSample {
        TimedSample {
            elapsed: Duration::from_millis(ms),
            sample: ShotSample {
                sample_time: 0,
                group_pressure: pressure,
                group_flow: flow,
                head_temp: 92.0,
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

    fn shot(samples: Vec<TimedSample>) -> StoredShot {
        let duration = samples.last().map_or(Duration::ZERO, |s| s.elapsed);
        StoredShot::new(1, ShotRecord { duration, samples })
    }

    #[test]
    fn base64_round_trips_every_length() {
        for len in 0..10 {
            let bytes: Vec<u8> = (0..len).map(|i| (i * 37 + 5) as u8).collect();
            assert_eq!(unbase64(&base64(&bytes)), Some(bytes.clone()), "{len}");
        }
        assert_eq!(base64(b"Man"), "TWFu");
        assert_eq!(base64(b"Ma"), "TWE=");
        assert_eq!(base64(b"M"), "TQ==");
    }

    #[test]
    fn a_ramp_reads_back_on_its_scales() {
        let samples = (0..=320)
            .map(|i| sample(i * 100, i as f32 / 32.0, 2.0, Some(i as f32 / 8.0)))
            .collect();
        let summary = shot_summary(&shot(samples));
        let spark = decode_spark(summary.spark.as_deref().unwrap()).unwrap();
        assert_eq!(spark.flags, SPARK_MACHINE | SPARK_WEIGHT);
        // Pressure climbs to 10 bar: the scale's top, so the last point is full.
        assert!(spark.pressure.windows(2).all(|w| w[0] <= w[1]));
        assert!(spark.pressure[31] >= 250, "{:?}", spark.pressure);
        // Flow holds at 2 of 8 ml/s.
        assert!(spark.flow.iter().all(|v| *v == quantize(0.25)));
        // Weight on its own 40 g peak.
        assert!(spark.weight[31] >= 250);
        assert!(
            summary.spark.unwrap().len() <= 140,
            "about 130 bytes of base64"
        );
    }

    #[test]
    fn a_shot_with_no_scale_has_no_weight() {
        let samples = (0..50).map(|i| sample(i * 200, 9.0, 2.0, None)).collect();
        let spark = decode_spark(&shot_summary(&shot(samples)).spark.unwrap()).unwrap();
        assert_eq!(spark.flags, SPARK_MACHINE);
        assert!(spark.weight.iter().all(|v| *v == SPARK_GAP));
    }

    #[test]
    fn a_manual_log_has_no_spark_and_counts_nothing() {
        let summary = shot_summary(&shot(Vec::new()));
        assert_eq!(summary.spark, None);
        assert_eq!(summary.sample_count, Some(0));
        assert_eq!(summary.brew_sample_count, 0);
        assert_eq!(summary.peak_weight, None);
    }

    #[test]
    fn a_brew_draws_its_scale_series_with_its_stage_ticks() {
        let mut brew = shot(Vec::new());
        brew.brew_method = Some("pourover".to_owned());
        brew.brew_series = Some(crate::brew::BrewSeries {
            samples: (0..=180)
                .map(|i| crate::brew::BrewSample {
                    elapsed_ms: i * 1000,
                    weight_g: i as f32 * 2.0,
                    flow_g_s: Some(2.0),
                })
                .collect(),
            stage_marks: (0..10)
                .map(|i| crate::brew::StageMark {
                    elapsed_ms: i * 18_000,
                    step_index: i,
                    ..Default::default()
                })
                .collect(),
        });
        let summary = shot_summary(&brew);
        assert_eq!(summary.brew_sample_count, 181);
        let spark = decode_spark(&summary.spark.unwrap()).unwrap();
        assert_eq!(spark.flags, SPARK_BREW | SPARK_WEIGHT);
        assert!(
            spark.pressure.iter().all(|v| *v == SPARK_GAP),
            "a brew has no pressure"
        );
        assert_eq!(spark.ticks.len(), SPARK_MAX_TICKS, "the first eight");
        assert_eq!(spark.ticks[0], 0);
        assert_eq!(spark.ticks[1], quantize_place(18_000.0, 180_000.0));
    }

    #[test]
    fn the_summary_parses_from_json() {
        let json = serde_json::to_string(&shot(vec![sample(0, 1.0, 1.0, None)])).unwrap();
        let out: ShotSummary = serde_json::from_str(&shot_summary_json(&json).unwrap()).unwrap();
        assert_eq!(out.v, 1);
        assert_eq!(out.sample_count, Some(1));
        assert!(shot_summary_json("{").is_err());
    }
}

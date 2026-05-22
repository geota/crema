//! Persistable shot history.
//!
//! The core is sans-IO, so it does not write files — but the *format* of a
//! stored shot is domain knowledge, so it is defined and versioned here. A
//! [`StoredShot`] wraps a completed [`ShotRecord`] with the context needed to
//! make sense of it later: the profile pulled, why it stopped, a wall-clock
//! timestamp, and the barista's journal notes.
//!
//! [`StoredShot::to_json`] / [`StoredShot::from_json`] give a stable,
//! human-readable serialization; the shell decides where the bytes live — a
//! file per shot, a database, an upload.

use serde::{Deserialize, Serialize};

use crate::error::DomainError;
use crate::profile::Profile;
use crate::shot::ShotRecord;
use crate::stop::StopReason;

/// Schema version stamped into every [`StoredShot`]. Bump it when the stored
/// shape changes incompatibly; readers branch on [`StoredShot::format_version`].
///
/// `v2` dropped unit-suffixed field names from the persisted shape:
/// [`ShotMetadata::dose`] (was `dose_in_g`), [`ShotMetadata::yield_out`] (was
/// `yield_out_g`), [`StoredShot::recorded_at`] (was `recorded_at_unix_ms`),
/// [`ShotRecord::duration`] / [`TimedSample::elapsed`] (were `_ms` `u64`s,
/// now serde-default `Duration`s with `{secs, nanos}`).
pub const STORED_SHOT_FORMAT_VERSION: u32 = 2;

/// Barista-supplied journal metadata for a shot. Every field is optional — a
/// shot can be stored with none of it and annotated afterwards.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct ShotMetadata {
    /// Dry coffee dose, grams.
    pub dose: Option<f32>,
    /// Weight in the cup, grams.
    pub yield_out: Option<f32>,
    /// Bean / roaster description.
    pub beans: Option<String>,
    /// Grinder setting used.
    pub grinder_setting: Option<String>,
    /// Free-form tasting notes.
    pub notes: Option<String>,
    /// Personal rating, 1–5.
    pub rating: Option<u8>,
    /// Total dissolved solids in the beverage, percent — typically from a
    /// refractometer reading.
    pub tds: Option<f32>,
    /// Extraction yield, percent — the fraction of the dry dose extracted into
    /// the cup.
    pub extraction_yield: Option<f32>,
}

impl ShotMetadata {
    /// The brew ratio (yield ÷ dose), if both weights are recorded and the
    /// dose is positive. Delegates to the free [`brew_ratio`] helper so
    /// every consumer — Crema's history store, an Android shell, the
    /// web ratio label — produces an identical number from the same
    /// inputs.
    pub fn brew_ratio(&self) -> Option<f32> {
        brew_ratio(self.dose?, self.yield_out?)
    }
}

/// Compute the brew ratio (yield ÷ dose) for a pair of weights in grams.
///
/// Returns `None` for a non-positive dose, a non-finite operand, or a
/// non-finite quotient — never panics. The result is the `N` in `1:N`
/// style labels (the shell formats the string).
pub fn brew_ratio(dose: f32, yield_out: f32) -> Option<f32> {
    if !dose.is_finite() || !yield_out.is_finite() || dose <= 0.0 {
        return None;
    }
    let r = yield_out / dose;
    if r.is_finite() { Some(r) } else { None }
}

#[cfg(test)]
mod ratio_tests {
    use super::brew_ratio;

    #[test]
    fn standard_shot() {
        assert_eq!(brew_ratio(18.0, 36.0), Some(2.0));
    }

    #[test]
    fn ristretto() {
        let r = brew_ratio(20.0, 30.0).expect("finite quotient");
        assert!((r - 1.5).abs() < f32::EPSILON);
    }

    #[test]
    fn missing_or_invalid() {
        assert_eq!(brew_ratio(0.0, 36.0), None);
        assert_eq!(brew_ratio(-1.0, 36.0), None);
        assert_eq!(brew_ratio(18.0, f32::NAN), None);
        assert_eq!(brew_ratio(f32::INFINITY, 36.0), None);
    }
}

/// A completed shot persisted to history: the telemetry plus everything
/// needed to interpret it.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct StoredShot {
    /// Schema version — see [`STORED_SHOT_FORMAT_VERSION`].
    pub format_version: u32,
    /// When the shot was pulled, Unix epoch milliseconds. The core has no
    /// clock; the shell supplies this.
    pub recorded_at: u64,
    /// The profile pulled, if known.
    pub profile: Option<Profile>,
    /// Why the shot stopped, if an [`AutoStop`](crate::AutoStop) drove it.
    pub stop_reason: Option<StopReason>,
    /// Barista journal metadata.
    pub metadata: ShotMetadata,
    /// The recorded telemetry.
    pub record: ShotRecord,
}

impl StoredShot {
    /// Wrap a freshly completed `record` for storage, stamped with the current
    /// [`STORED_SHOT_FORMAT_VERSION`] and `recorded_at` (Unix epoch ms; the
    /// core has no clock, the shell supplies this). Profile, stop reason, and
    /// metadata start empty — attach them with the `with_*` setters.
    pub fn new(recorded_at: u64, record: ShotRecord) -> StoredShot {
        StoredShot {
            format_version: STORED_SHOT_FORMAT_VERSION,
            recorded_at,
            profile: None,
            stop_reason: None,
            metadata: ShotMetadata::default(),
            record,
        }
    }

    /// Attach the profile that was pulled.
    #[must_use]
    pub fn with_profile(mut self, profile: Profile) -> StoredShot {
        self.profile = Some(profile);
        self
    }

    /// Attach the reason the shot stopped.
    #[must_use]
    pub fn with_stop_reason(mut self, reason: StopReason) -> StoredShot {
        self.stop_reason = Some(reason);
        self
    }

    /// Attach barista journal metadata.
    #[must_use]
    pub fn with_metadata(mut self, metadata: ShotMetadata) -> StoredShot {
        self.metadata = metadata;
        self
    }

    /// Serialize to pretty JSON — a stable, human-readable format the shell
    /// can persist wherever it likes.
    ///
    /// # Errors
    ///
    /// [`DomainError::Serialization`] if serialization fails (it should not
    /// for a well-formed `StoredShot`).
    pub fn to_json(&self) -> Result<String, DomainError> {
        serde_json::to_string_pretty(self).map_err(|e| DomainError::Serialization(e.to_string()))
    }

    /// Parse a [`StoredShot`] from JSON produced by [`to_json`](Self::to_json).
    ///
    /// # Errors
    ///
    /// [`DomainError::Serialization`] if `json` is malformed or does not match
    /// the schema.
    pub fn from_json(json: &str) -> Result<StoredShot, DomainError> {
        serde_json::from_str(json).map_err(|e| DomainError::Serialization(e.to_string()))
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;
    use crate::shot::TimedSample;
    use de1_protocol::ShotSample;

    /// A one-sample shot record for round-trip tests.
    fn sample_record() -> ShotRecord {
        ShotRecord {
            duration: Duration::from_secs(27),
            samples: vec![TimedSample {
                elapsed: Duration::from_secs(1),
                sample: ShotSample {
                    sample_time: 100,
                    group_pressure: 6.0,
                    group_flow: 2.1,
                    head_temp: 92.3,
                    mix_temp: 88.0,
                    set_mix_temp: 92.0,
                    set_head_temp: 92.0,
                    set_group_pressure: 6.0,
                    set_group_flow: 0.0,
                    frame_number: 2,
                    steam_temp: 0.0,
                },
            }],
        }
    }

    #[test]
    fn new_stamps_the_current_format_version() {
        let shot = StoredShot::new(1_700_000_000_000, sample_record());
        assert_eq!(shot.format_version, STORED_SHOT_FORMAT_VERSION);
        assert!(shot.profile.is_none());
        assert!(shot.stop_reason.is_none());
    }

    #[test]
    fn stored_shot_round_trips_through_json() {
        let shot = StoredShot::new(1_700_000_000_000, sample_record())
            .with_stop_reason(StopReason::Weight)
            .with_metadata(ShotMetadata {
                dose: Some(18.0),
                yield_out: Some(40.5),
                beans: Some("washed Ethiopia".to_owned()),
                grinder_setting: Some("3.2".to_owned()),
                notes: None,
                rating: Some(4),
                tds: Some(9.1),
                extraction_yield: Some(20.5),
            });
        let json = shot.to_json().unwrap();
        assert_eq!(StoredShot::from_json(&json), Ok(shot));
    }

    #[test]
    fn metadata_carries_tds_and_extraction_yield() {
        let meta = ShotMetadata {
            tds: Some(8.7),
            extraction_yield: Some(21.3),
            ..ShotMetadata::default()
        };
        assert_eq!(meta.tds, Some(8.7));
        assert_eq!(meta.extraction_yield, Some(21.3));
        // Defaults leave the new fields unset.
        assert_eq!(ShotMetadata::default().tds, None);
        assert_eq!(ShotMetadata::default().extraction_yield, None);
    }

    #[test]
    fn brew_ratio_divides_yield_by_dose() {
        let meta = ShotMetadata {
            dose: Some(18.0),
            yield_out: Some(45.0),
            ..ShotMetadata::default()
        };
        assert_eq!(meta.brew_ratio(), Some(2.5));
    }

    #[test]
    fn brew_ratio_is_none_without_both_weights() {
        assert_eq!(ShotMetadata::default().brew_ratio(), None);
        let dose_only = ShotMetadata {
            dose: Some(18.0),
            ..ShotMetadata::default()
        };
        assert_eq!(dose_only.brew_ratio(), None);
    }

    #[test]
    fn from_json_rejects_malformed_input() {
        assert!(StoredShot::from_json("not json").is_err());
    }

    #[test]
    fn from_json_preserves_the_format_version() {
        let shot = StoredShot::new(1_700_000_000_000, sample_record());
        let json = shot.to_json().unwrap();
        let parsed = StoredShot::from_json(&json).unwrap();
        assert_eq!(parsed.format_version, STORED_SHOT_FORMAT_VERSION);
    }

    /// Policy: `format_version` is a plain field with no custom validation, so
    /// a stored shot stamped with a *different* version still deserializes —
    /// this is deliberate, so a future reader can branch on the version rather
    /// than failing outright on data it could otherwise migrate.
    #[test]
    fn from_json_accepts_an_unknown_future_format_version() {
        let shot = StoredShot::new(1_700_000_000_000, sample_record());
        let json = shot.to_json().unwrap();
        // Re-stamp the version field to one this build does not know.
        let future = json.replace(
            &format!("\"format_version\": {STORED_SHOT_FORMAT_VERSION}"),
            "\"format_version\": 9999",
        );
        assert_ne!(future, json, "the version field must have been rewritten");
        let parsed = StoredShot::from_json(&future).unwrap();
        // The reader gets the raw version and can branch on it.
        assert_eq!(parsed.format_version, 9999);
        // Every other field survives intact.
        assert_eq!(parsed.record, shot.record);
        assert_eq!(parsed.recorded_at, shot.recorded_at);
    }

    #[test]
    fn from_json_accepts_an_older_format_version() {
        let shot = StoredShot::new(1_700_000_000_000, sample_record());
        let json = shot.to_json().unwrap();
        let older = json.replace(
            &format!("\"format_version\": {STORED_SHOT_FORMAT_VERSION}"),
            "\"format_version\": 0",
        );
        let parsed = StoredShot::from_json(&older).unwrap();
        assert_eq!(parsed.format_version, 0);
    }

    #[test]
    fn from_json_still_rejects_a_structurally_invalid_shot() {
        // A future version does not excuse a missing required field: dropping
        // `record` must still fail, so a stale reader cannot silently accept
        // garbage just because the version is unfamiliar.
        let bad = r#"{"format_version": 9999, "recorded_at": 0,
            "profile": null, "stop_reason": null, "metadata": {}}"#;
        assert!(StoredShot::from_json(bad).is_err());
    }

    #[test]
    fn a_collection_of_shots_round_trips_through_json() {
        let shots = vec![
            StoredShot::new(1_700_000_000_000, sample_record())
                .with_stop_reason(StopReason::Weight),
            StoredShot::new(1_700_000_060_000, sample_record())
                .with_stop_reason(StopReason::MaxTime)
                .with_metadata(ShotMetadata {
                    rating: Some(5),
                    ..ShotMetadata::default()
                }),
            StoredShot::new(1_700_000_120_000, sample_record()),
        ];
        let json = serde_json::to_string(&shots).unwrap();
        let parsed: Vec<StoredShot> = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, shots);
        assert_eq!(parsed.len(), 3);
    }
}

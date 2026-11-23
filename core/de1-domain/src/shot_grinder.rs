//! What a connected grinder reported for a shot (#488 phase 2, G3; SPEC
//! grinder-ble §4.2–4.4): the observation stamped on a [`StoredShot`], the
//! grinding speed, how a shot's grind reads (observed or edited), and the
//! grind text Visualizer gets ("480 700rpm", owner decision 6).
//!
//! The observation is chosen by the app core at shot start (it holds the
//! connected grinder); this module is the stored shape and the pure rules
//! over it.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::history::StoredShot;

/// A grind counts for a shot when it ended at most this long before the
/// shot started (owner decision 8: 10 minutes, a starting default).
pub const GRIND_ATTRIBUTION_WINDOW_MS: u64 = 10 * 60 * 1000;

/// Where a shot's grinder observation came from.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum ObservationSource {
    /// The grind before the shot: the newest completed grind (never a
    /// purge, decision 9) within [`GRIND_ATTRIBUTION_WINDOW_MS`] that no
    /// earlier shot used.
    LastGrind,
    /// No such grind: the grinder's reading when the shot started.
    AtShotStart,
    /// A source a newer build wrote: the observation still decodes.
    #[serde(other)]
    Unknown,
}

/// What the connected grinder reported for a shot. Every field but
/// [`Self::source`] is optional, so a row from any build decodes.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShotGrinderObservation {
    /// The grind before the shot, or the reading at shot start.
    pub source: ObservationSource,
    /// The grinder model id (the catalogue's, `bookoo-motto-80`).
    #[serde(default)]
    pub model: Option<String>,
    /// Its firmware, for display.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub firmware: Option<String>,
    /// The dial as the grinder said it ("480"); the unit is the grinder
    /// record's scale's.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub setting: Option<String>,
    /// Grinding speed, rpm (the median over the grind).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub grind_rpm: Option<u32>,
    /// Bean-feeder speed, rpm.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub feed_rpm: Option<u32>,
    /// How long the grind ran, ms ([`ObservationSource::LastGrind`] only).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub grind_ms: Option<u32>,
    /// How long before the shot started the grind ended, ms
    /// ([`ObservationSource::LastGrind`] only). A duration, not a time: the
    /// core's clock is monotonic.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ended_before_shot_ms: Option<u32>,
    /// The grinder's preset: `-1` = its manual mode, `0..=9` a preset.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub preset: Option<i8>,
    /// Relative humidity, %.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub humidity_pct: Option<u8>,
}

/// How a shot's grind reads beside its observation.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum GrindProvenance {
    /// No connected grinder reported anything: the grind was typed.
    Typed,
    /// The grind is what the grinder said.
    Observed,
    /// The grind differs from what the grinder said ("Edited (grinder said
    /// 480)"): edited later, or typed while following was paused.
    Edited {
        /// What the grinder said.
        grinder_said: String,
    },
}

/// Whether `shot`'s grind is the observed one. Derived, never stored twice:
/// observed = `grinder_observed.setting` equals `metadata.grinder_setting`
/// (as grinds compare, [`crate::same_grind`]).
#[must_use]
pub fn grind_provenance(shot: &StoredShot) -> GrindProvenance {
    let Some(said) = shot
        .grinder_observed
        .as_ref()
        .and_then(|o| o.setting.as_deref())
        .map(str::trim)
        .filter(|s| !s.is_empty())
    else {
        return GrindProvenance::Typed;
    };
    match shot.metadata.grinder_setting.as_deref() {
        Some(grind) if crate::same_grind(grind, said) => GrindProvenance::Observed,
        _ => GrindProvenance::Edited {
            grinder_said: said.to_owned(),
        },
    }
}

/// [`grind_provenance`] over JSON: a `StoredShot` in, a `GrindProvenance` out.
///
/// # Errors
///
/// The JSON error string on a malformed shot.
pub fn grind_provenance_json(shot_json: &str) -> Result<String, String> {
    let shot: StoredShot = serde_json::from_str(shot_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&grind_provenance(&shot)).map_err(|e| e.to_string())
}

/// The grind text Visualizer gets: the setting with the speed appended,
/// "480 700rpm", as the community writes it (owner decision 6). The setting
/// alone without a speed; the speed alone ("700rpm") without a setting;
/// `None` with neither. A setting that already ends in this speed (a shot
/// imported or pulled as "15 1200rpm") goes out as it is, never twice.
#[must_use]
pub fn visualizer_grind_text(setting: Option<&str>, grind_rpm: Option<u32>) -> Option<String> {
    let setting = setting.map(str::trim).filter(|s| !s.is_empty());
    if let (Some(s), Some(rpm)) = (setting, grind_rpm)
        && split_visualizer_grind(s).1 == Some(rpm)
    {
        return Some(s.to_owned());
    }
    match (setting, grind_rpm) {
        (Some(s), Some(rpm)) => Some(format!("{s} {rpm}rpm")),
        (Some(s), None) => Some(s.to_owned()),
        (None, Some(rpm)) => Some(format!("{rpm}rpm")),
        (None, None) => None,
    }
}

/// Split a grind text that came from Visualizer (or a file) back into the
/// setting and the speed: "480 700rpm" → ("480", 700). A text with no
/// trailing "‹digits›rpm" comes back whole, with no speed, so a typed grind
/// ("18 clicks") is never touched. The inverse of [`visualizer_grind_text`].
#[must_use]
pub fn split_visualizer_grind(text: &str) -> (String, Option<u32>) {
    let trimmed = text.trim();
    let lower = trimmed.to_ascii_lowercase();
    let Some(head) = lower.strip_suffix("rpm") else {
        return (trimmed.to_owned(), None);
    };
    let head = head.trim_end();
    let digits_at = head
        .char_indices()
        .rev()
        .take_while(|(_, c)| c.is_ascii_digit())
        .last()
        .map(|(i, _)| i);
    let Some(at) = digits_at else {
        return (trimmed.to_owned(), None);
    };
    let Ok(rpm) = head[at..].parse::<u32>() else {
        return (trimmed.to_owned(), None);
    };
    // The speed must stand apart from the setting ("480 700rpm"), or be
    // the whole text ("700rpm").
    let setting = &trimmed[..at];
    if !setting.is_empty() && !setting.ends_with(char::is_whitespace) {
        return (trimmed.to_owned(), None);
    }
    (setting.trim().to_owned(), Some(rpm))
}

/// A shot read from a file whose grind ends in a speed ("15 1200rpm", as
/// de1app, Decenza and Visualizer users write it): the speed also fills
/// [`StoredShot::grind_rpm`] (when it has none). Additive: the grind text is
/// kept whole, so nothing is lost on a shell that doesn't show the speed,
/// and the export sends it back unchanged ([`visualizer_grind_text`]).
pub fn take_rpm_from_grind(shot: &mut StoredShot) {
    if shot.grind_rpm.is_some() {
        return;
    }
    let Some(text) = shot.metadata.grinder_setting.as_deref() else {
        return;
    };
    shot.grind_rpm = split_visualizer_grind(text).1;
}

/// [`split_visualizer_grind`] as JSON: `{"setting": "480", "grindRpm": 700}`.
#[must_use]
pub fn split_visualizer_grind_json(text: &str) -> String {
    let (setting, rpm) = split_visualizer_grind(text);
    serde_json::json!({ "setting": setting, "grindRpm": rpm }).to_string()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::shot::ShotRecord;

    fn shot(grind: Option<&str>, said: Option<&str>) -> StoredShot {
        let mut s = StoredShot::new(
            1,
            ShotRecord {
                duration: std::time::Duration::ZERO,
                samples: Vec::new(),
            },
        );
        s.metadata.grinder_setting = grind.map(str::to_owned);
        s.grinder_observed = said.map(|said| ShotGrinderObservation {
            source: ObservationSource::LastGrind,
            model: Some("bookoo-motto-80".into()),
            firmware: None,
            setting: Some(said.into()),
            grind_rpm: Some(700),
            feed_rpm: None,
            grind_ms: None,
            ended_before_shot_ms: None,
            preset: None,
            humidity_pct: None,
        });
        s
    }

    #[test]
    fn provenance_is_derived_from_the_two_values() {
        assert_eq!(
            grind_provenance(&shot(Some("480"), None)),
            GrindProvenance::Typed
        );
        assert_eq!(
            grind_provenance(&shot(Some("480"), Some("480"))),
            GrindProvenance::Observed
        );
        assert_eq!(
            grind_provenance(&shot(Some("480.0"), Some("480"))),
            GrindProvenance::Observed
        );
        assert_eq!(
            grind_provenance(&shot(Some("470"), Some("480"))),
            GrindProvenance::Edited {
                grinder_said: "480".into()
            }
        );
        assert_eq!(
            grind_provenance(&shot(None, Some("480"))),
            GrindProvenance::Edited {
                grinder_said: "480".into()
            }
        );
    }

    #[test]
    fn visualizer_text_appends_the_speed() {
        assert_eq!(
            visualizer_grind_text(Some("480"), Some(700)).as_deref(),
            Some("480 700rpm")
        );
        // Already there (an imported "15 1200rpm"): never twice.
        assert_eq!(
            visualizer_grind_text(Some("480 700rpm"), Some(700)).as_deref(),
            Some("480 700rpm")
        );
        assert_eq!(
            visualizer_grind_text(Some("700 RPM"), Some(700)).as_deref(),
            Some("700 RPM")
        );
        assert_eq!(
            visualizer_grind_text(Some(" 480 "), None).as_deref(),
            Some("480")
        );
        assert_eq!(
            visualizer_grind_text(Some(""), Some(700)).as_deref(),
            Some("700rpm")
        );
        assert_eq!(visualizer_grind_text(None, None), None);
    }

    #[test]
    fn the_split_undoes_the_text_and_leaves_typed_grinds_alone() {
        for (text, setting, rpm) in [
            ("480 700rpm", "480", Some(700)),
            ("480 700 RPM", "480", Some(700)),
            ("2.1.3 1200rpm", "2.1.3", Some(1200)),
            ("700rpm", "", Some(700)),
            ("480", "480", None),
            ("18 clicks", "18 clicks", None),
            ("480rpm-ish", "480rpm-ish", None),
            ("abc700rpm", "abc700rpm", None),
            ("rpm", "rpm", None),
            ("", "", None),
            ("480 99999999999rpm", "480 99999999999rpm", None),
        ] {
            assert_eq!(
                split_visualizer_grind(text),
                (setting.to_owned(), rpm),
                "{text}"
            );
        }
        for (s, r) in [
            (Some("480"), Some(700)),
            (Some("2.4"), None),
            (None, Some(900)),
        ] {
            let text = visualizer_grind_text(s, r).expect("text");
            assert_eq!(
                split_visualizer_grind(&text),
                (s.unwrap_or("").to_owned(), r)
            );
        }
    }

    #[test]
    fn an_observation_round_trips_and_old_rows_decode() {
        let s = shot(Some("480"), Some("480"));
        let json = serde_json::to_string(&s).expect("json");
        assert!(
            json.contains("\"grinderObserved\":{\"source\":\"LastGrind\""),
            "{json}"
        );
        let back: StoredShot = serde_json::from_str(&json).expect("back");
        assert_eq!(back, s);
        let old = serde_json::to_string(&StoredShot::new(
            1,
            ShotRecord {
                duration: std::time::Duration::ZERO,
                samples: Vec::new(),
            },
        ))
        .expect("json");
        assert!(
            !old.contains("grinderObserved") && !old.contains("grindRpm"),
            "{old}"
        );
    }

    #[test]
    fn an_unknown_source_still_decodes() {
        let o: ShotGrinderObservation =
            serde_json::from_str(r#"{"source":"FromTheFuture","setting":"480"}"#).expect("decodes");
        assert_eq!(
            (o.source, o.setting.as_deref()),
            (ObservationSource::Unknown, Some("480"))
        );
    }

    #[test]
    fn taking_the_speed_keeps_the_grind_whole() {
        let mut s = shot(Some("15 1200rpm"), None);
        take_rpm_from_grind(&mut s);
        assert_eq!(
            (s.metadata.grinder_setting.as_deref(), s.grind_rpm),
            (Some("15 1200rpm"), Some(1200))
        );
        let mut s = shot(Some("18 clicks"), None);
        take_rpm_from_grind(&mut s);
        assert_eq!(s.grind_rpm, None);
        let mut s = shot(Some("480 700rpm"), None);
        s.grind_rpm = Some(650);
        take_rpm_from_grind(&mut s);
        assert_eq!(s.grind_rpm, Some(650), "a speed it has is kept");
    }
}

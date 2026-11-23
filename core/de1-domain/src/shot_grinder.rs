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
/// `None` with neither. A setting that already ends in a speed (a shot
/// imported or pulled as "15 1200rpm") never gets a second one: the same
/// speed goes out as it is, a different one takes its place ("15 1300rpm").
#[must_use]
pub fn visualizer_grind_text(setting: Option<&str>, grind_rpm: Option<u32>) -> Option<String> {
    let setting = setting.map(str::trim).filter(|s| !s.is_empty());
    if let (Some(s), Some(rpm)) = (setting, grind_rpm) {
        let (dial, had) = split_visualizer_grind(s);
        match had {
            Some(had) if had == rpm => return Some(s.to_owned()),
            Some(_) if dial.is_empty() => return Some(format!("{rpm}rpm")),
            Some(_) => return Some(format!("{dial} {rpm}rpm")),
            None => {}
        }
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

/// A grind text with its trailing speed set to `rpm` (REVIEW-554: editing a
/// shot's RPM): "15 1200rpm" with 1100 is "15 1100rpm", never "15 1200rpm
/// 1100rpm"; with no speed it is "15", and "1200rpm" is `None`. A text that
/// names no speed ("480", "18 clicks") comes back as it is: the speed rides
/// beside it ([`StoredShot::grind_rpm`]) and Visualizer gets "480 1100rpm"
/// ([`visualizer_grind_text`]).
#[must_use]
pub fn grind_with_rpm(text: Option<&str>, rpm: Option<u32>) -> Option<String> {
    let text = text?;
    let (setting, had) = split_visualizer_grind(text);
    if had.is_none() || had == rpm {
        return Some(text.to_owned());
    }
    match (setting.is_empty(), rpm) {
        (true, None) => None,
        (true, Some(rpm)) => Some(format!("{rpm}rpm")),
        (false, None) => Some(setting),
        (false, Some(rpm)) => Some(format!("{setting} {rpm}rpm")),
    }
}

/// What a shot's grind becomes when its speed is edited ([`rpm_edit_grind`]).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RpmEditGrind {
    /// The shot's own grind (`metadata.grinder_setting`) after the edit.
    pub grinder_setting: Option<String>,
    /// Drop the bean snapshot's legacy grind (`bean.grinder_setting`) too:
    /// it held only the speed being cleared, and would otherwise come back
    /// as the shot's grind.
    pub clear_bean_grind: bool,
}

/// The grind a shot keeps when its speed is edited to `rpm` (REVIEW-557 M1):
/// the effective grind (the shot's own, `own`, else its bean snapshot's,
/// `bean_grind`) with its trailing speed replaced or dropped
/// ([`grind_with_rpm`]); `own` as it is when that changes nothing. Clearing
/// the speed of a grind that is only a speed ("1200rpm") clears the grind,
/// and a snapshot grind that is only a speed goes with it, so the cleared
/// speed doesn't come back from the snapshot (REVIEW-555 recheck). A
/// snapshot that names a dial is never dropped.
#[must_use]
pub fn rpm_edit_grind(
    own: Option<&str>,
    bean_grind: Option<&str>,
    rpm: Option<u32>,
) -> RpmEditGrind {
    fn present(s: Option<&str>) -> Option<&str> {
        s.map(str::trim).filter(|s| !s.is_empty())
    }
    let effective = present(own).or_else(|| present(bean_grind));
    let edited = grind_with_rpm(effective, rpm);
    if edited.as_deref() == effective {
        return RpmEditGrind {
            grinder_setting: own.map(str::to_owned),
            clear_bean_grind: false,
        };
    }
    let clear_bean_grind = edited.is_none()
        && present(bean_grind).is_some_and(|b| grind_with_rpm(Some(b), None).is_none());
    RpmEditGrind {
        grinder_setting: edited,
        clear_bean_grind,
    }
}

/// [`rpm_edit_grind`] as JSON: an [`RpmEditGrind`].
#[must_use]
pub fn rpm_edit_grind_json(
    own: Option<&str>,
    bean_grind: Option<&str>,
    rpm: Option<u32>,
) -> String {
    serde_json::to_string(&rpm_edit_grind(own, bean_grind, rpm)).unwrap_or_default()
}

/// The speed a shot keeps when its grind is edited to `grind` (REVIEW-557
/// M1): one the grind names ("15 1300rpm") wins, else `had`.
#[must_use]
pub fn grind_edit_rpm(grind: Option<&str>, had: Option<u32>) -> Option<u32> {
    grind.and_then(|g| split_visualizer_grind(g).1).or(had)
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
        // A different speed takes the old one's place (review #555 M1).
        assert_eq!(
            visualizer_grind_text(Some("15 1200rpm"), Some(1300)).as_deref(),
            Some("15 1300rpm")
        );
        assert_eq!(
            visualizer_grind_text(Some("1200rpm"), Some(800)).as_deref(),
            Some("800rpm")
        );
        assert_eq!(
            visualizer_grind_text(Some("480 700rpm"), Some(800)).as_deref(),
            Some("480 800rpm")
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

    #[test]
    fn a_speed_edit_replaces_the_trailing_speed() {
        // REVIEW-554 recheck: an imported "15 1200rpm" edited to 1100.
        for (text, rpm, out) in [
            (Some("15 1200rpm"), Some(1100), Some("15 1100rpm")),
            (Some("15 1200rpm"), Some(1200), Some("15 1200rpm")),
            (Some("15 1200rpm"), None, Some("15")),
            (Some("1200rpm"), Some(800), Some("800rpm")),
            (Some("1200rpm"), None, None),
            (Some("480"), Some(700), Some("480")),
            (Some("18 clicks"), None, Some("18 clicks")),
            (None, Some(700), None),
        ] {
            assert_eq!(
                grind_with_rpm(text, rpm).as_deref(),
                out,
                "{text:?} {rpm:?}"
            );
        }
    }

    #[test]
    fn a_speed_edit_on_a_shot_follows_its_effective_grind() {
        let edit = |own, bean, rpm| rpm_edit_grind(own, bean, rpm);
        let kept = |g: Option<&str>| RpmEditGrind {
            grinder_setting: g.map(str::to_owned),
            clear_bean_grind: false,
        };
        // A pulled shot whose grind lives on the snapshot.
        assert_eq!(
            edit(None, Some("480 700rpm"), Some(800)),
            kept(Some("480 800rpm"))
        );
        assert_eq!(edit(None, Some("480 700rpm"), None), kept(Some("480")));
        // Nothing changes in the text: the shot's own grind stays as it is.
        assert_eq!(edit(None, Some("480"), Some(700)), kept(None));
        assert_eq!(edit(Some("480"), None, Some(700)), kept(Some("480")));
        assert_eq!(
            edit(Some("15 1200rpm"), None, Some(1100)),
            kept(Some("15 1100rpm"))
        );
        // REVIEW-555 recheck: clearing a speed-only grind sticks; the
        // snapshot that held only that speed goes too.
        assert_eq!(
            edit(None, Some("1200rpm"), None),
            RpmEditGrind {
                grinder_setting: None,
                clear_bean_grind: true
            }
        );
        assert_eq!(
            edit(Some("1200rpm"), Some("1200rpm"), None),
            RpmEditGrind {
                grinder_setting: None,
                clear_bean_grind: true
            }
        );
        // A snapshot that names a dial is never dropped.
        assert_eq!(edit(Some("1200rpm"), Some("2.4"), None), kept(None));
        let json = rpm_edit_grind_json(None, Some("1200rpm"), None);
        assert_eq!(json, r#"{"grinderSetting":null,"clearBeanGrind":true}"#);
    }

    #[test]
    fn a_grind_edit_takes_the_speed_it_names() {
        assert_eq!(grind_edit_rpm(Some("15 1300rpm"), Some(1200)), Some(1300));
        assert_eq!(grind_edit_rpm(Some("16"), Some(1200)), Some(1200));
        assert_eq!(grind_edit_rpm(None, Some(1200)), Some(1200));
        assert_eq!(grind_edit_rpm(Some("481"), None), None);
    }
}

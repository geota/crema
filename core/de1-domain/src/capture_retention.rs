//! Which raw captures to keep (#311).
//!
//! A raw capture can't be rebuilt, so the rule is the same for every shell and
//! a preview shows exactly what a lower limit would delete before it does.
//!
//! - **Web** keeps captures per shot: the newest `keep` captured shots, plus
//!   every pinned shot's, whatever its age.
//! - **Android** records whole BLE sessions, each holding many shots, so it
//!   keeps the newest `keep` sessions, plus any session whose span covers a
//!   pinned shot.
//!
//! There's no age limit: a count is the only thing that removes a capture, and
//! pinned shots are exempt from it.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// How many raw captures to keep.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "details")]
pub enum CaptureKeep {
    /// Record none, and keep only pinned shots' captures.
    Off,
    /// The newest this many.
    Newest { count: u32 },
    /// Every capture: nothing is deleted by count.
    All,
}

impl CaptureKeep {
    fn count(self) -> Option<usize> {
        match self {
            Self::Off => Some(0),
            Self::Newest { count } => Some(usize::try_from(count).unwrap_or(usize::MAX)),
            Self::All => None,
        }
    }
}

/// A shot with a stored capture, as the web shell lists it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CapturedShot {
    pub id: String,
    /// When the shot completed, unix ms.
    #[typeshare(serialized_as = "I64")]
    pub completed_at_ms: i64,
    #[serde(default)]
    pub pinned: bool,
}

/// The captured shots whose captures go under `keep`: everything past the
/// newest `keep`, except pinned shots. Newest by completion; ties keep the
/// list's order. In the order given.
#[must_use]
pub fn captures_to_drop(shots: &[CapturedShot], keep: CaptureKeep) -> Vec<String> {
    let Some(count) = keep.count() else {
        return Vec::new();
    };
    let mut newest_first: Vec<&CapturedShot> = shots.iter().collect();
    newest_first.sort_by_key(|s| std::cmp::Reverse(s.completed_at_ms));
    let kept: std::collections::HashSet<&str> = newest_first
        .iter()
        .take(count)
        .map(|s| s.id.as_str())
        .collect();
    shots
        .iter()
        .filter(|s| !s.pinned && !kept.contains(s.id.as_str()))
        .map(|s| s.id.clone())
        .collect()
}

/// A recorded BLE session file, as the Android shell lists it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CaptureSession {
    pub name: String,
    /// When it started, unix ms: the stamp in its name.
    #[typeshare(serialized_as = "I64")]
    pub started_ms: i64,
    /// When it was last written, unix ms.
    #[typeshare(serialized_as = "I64")]
    pub ended_ms: i64,
}

/// The sessions that go under `keep`: everything past the newest `keep` by
/// last write, except a session whose span covers a pinned shot's completion
/// (`pinned_ms`). In the order given.
#[must_use]
pub fn sessions_to_drop(
    sessions: &[CaptureSession],
    keep: CaptureKeep,
    pinned_ms: &[i64],
) -> Vec<String> {
    let Some(count) = keep.count() else {
        return Vec::new();
    };
    let mut newest_first: Vec<&CaptureSession> = sessions.iter().collect();
    newest_first.sort_by_key(|s| std::cmp::Reverse(s.ended_ms));
    let kept: std::collections::HashSet<&str> = newest_first
        .iter()
        .take(count)
        .map(|s| s.name.as_str())
        .collect();
    let holds_a_pin = |s: &CaptureSession| {
        pinned_ms
            .iter()
            .any(|t| (s.started_ms..=s.ended_ms).contains(t))
    };
    sessions
        .iter()
        .filter(|s| !kept.contains(s.name.as_str()) && !holds_a_pin(s))
        .map(|s| s.name.clone())
        .collect()
}

/// [`captures_to_drop`] for the bridges: `{"shots": [CapturedShot], "keep": CaptureKeep}`
/// in, a JSON array of ids out.
///
/// # Errors
/// When the input doesn't parse.
pub fn captures_to_drop_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct In {
        shots: Vec<CapturedShot>,
        keep: CaptureKeep,
    }
    let input: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    serde_json::to_string(&captures_to_drop(&input.shots, input.keep)).map_err(|e| e.to_string())
}

/// [`sessions_to_drop`] for the bridges:
/// `{"sessions": [CaptureSession], "keep": CaptureKeep, "pinnedMs": [i64]}` in,
/// a JSON array of names out.
///
/// # Errors
/// When the input doesn't parse.
pub fn sessions_to_drop_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct In {
        sessions: Vec<CaptureSession>,
        keep: CaptureKeep,
        #[serde(default)]
        pinned_ms: Vec<i64>,
    }
    let input: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    serde_json::to_string(&sessions_to_drop(
        &input.sessions,
        input.keep,
        &input.pinned_ms,
    ))
    .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shot(id: &str, at: i64, pinned: bool) -> CapturedShot {
        CapturedShot {
            id: id.into(),
            completed_at_ms: at,
            pinned,
        }
    }

    fn session(name: &str, start: i64, end: i64) -> CaptureSession {
        CaptureSession {
            name: name.into(),
            started_ms: start,
            ended_ms: end,
        }
    }

    #[test]
    fn the_newest_stay_and_a_pinned_capture_survives_any_limit() {
        let shots = [
            shot("a", 1, false),
            shot("b", 2, true),
            shot("c", 3, false),
            shot("d", 4, false),
        ];
        assert_eq!(
            captures_to_drop(&shots, CaptureKeep::Newest { count: 2 }),
            ["a"]
        );
        assert_eq!(
            captures_to_drop(&shots, CaptureKeep::Newest { count: 10 }),
            Vec::<String>::new()
        );
        assert_eq!(
            captures_to_drop(&shots, CaptureKeep::Off),
            ["a", "c", "d"],
            "Off keeps only the pinned"
        );
        assert!(captures_to_drop(&shots, CaptureKeep::All).is_empty());
    }

    #[test]
    fn unpinning_makes_a_capture_eligible_at_the_next_prune() {
        let pinned = [shot("old", 1, true), shot("new", 2, false)];
        assert!(captures_to_drop(&pinned, CaptureKeep::Newest { count: 1 }).is_empty());
        let unpinned = [shot("old", 1, false), shot("new", 2, false)];
        assert_eq!(
            captures_to_drop(&unpinned, CaptureKeep::Newest { count: 1 }),
            ["old"]
        );
    }

    #[test]
    fn a_session_holding_a_pinned_shot_stays() {
        let sessions = [
            session("s1", 0, 100),
            session("s2", 200, 300),
            session("s3", 400, 500),
        ];
        assert_eq!(
            sessions_to_drop(&sessions, CaptureKeep::Newest { count: 1 }, &[]),
            ["s1", "s2"]
        );
        assert_eq!(
            sessions_to_drop(&sessions, CaptureKeep::Newest { count: 1 }, &[250]),
            ["s1"]
        );
        assert_eq!(
            sessions_to_drop(&sessions, CaptureKeep::Off, &[50]),
            ["s2", "s3"]
        );
        assert!(sessions_to_drop(&sessions, CaptureKeep::All, &[]).is_empty());
    }

    #[test]
    fn the_bridges_answer_in_json() {
        assert_eq!(
            captures_to_drop_json(r#"{"shots":[{"id":"a","completedAtMs":1},{"id":"b","completedAtMs":2}],"keep":{"kind":"Newest","details":{"count":1}}}"#).unwrap(),
            r#"["a"]"#
        );
        assert_eq!(
            sessions_to_drop_json(r#"{"sessions":[{"name":"s","startedMs":0,"endedMs":5}],"keep":{"kind":"Off"},"pinnedMs":[3]}"#).unwrap(),
            "[]"
        );
    }
}

//! Follow mode (SPEC grinder-ble §5): Brew's grind tracks the connected
//! grinder's dial. The rules, kept apart from the radio:
//!
//! - **When** (§5.1): the grinder is live, linked to the **active** grinder
//!   record, its driver trusts the reading as the grind (the capability's
//!   `physical`; owner decision 2 covers the Motto 80), and the user hasn't
//!   paused it this session (decision 3).
//! - **What** (§5.2): a value counts once it has held for 5 readings (1 s at
//!   the Motto's 5 Hz), so turning the collar doesn't flicker Brew or spam the
//!   relay; the core then asks the shell to set Brew's grind to it
//!   (`Event::FollowGrind`), and again whenever Brew's grind drifts from it
//!   (a typed value loses to the collar, §5.4). On a reconnect the value
//!   replaces Brew's, announced only if it changed.
//! - **Another grinder** (§5.4, decision 4): a live, linked grinder whose
//!   record isn't the active one is only suggested ("Use ‹grinder›"); the core
//!   never switches the active grinder.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// Equal readings in a row before a dial value counts (§5.2: "stable for
/// 1 s (5 equal readings)").
pub const STABLE_READINGS: u32 = 5;

/// Where follow mode is.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[non_exhaustive]
pub enum FollowStatus {
    /// No connected grinder linked to a grinder record: Brew's grind is the
    /// user's.
    Off,
    /// Linked and active, waiting for a steady value from this connection.
    Waiting,
    /// Brew's grind follows the grinder.
    Following,
    /// The user paused following for this session: the stepper is back.
    Paused,
    /// The grinder is live and linked, but its record isn't the active
    /// grinder: suggest "Use ‹grinder›" ([`GrinderFollowView::grinder_id`]).
    Suggest,
    /// The driver doesn't trust its reading as the grind: shown and logged,
    /// never followed (owner decision 2).
    ReadOnly,
    /// The linked grinder dropped: the last value stays, marked stale, and
    /// the stepper is back until it reconnects.
    Stale,
}

/// Follow mode as Brew shows it. Crosses the FFI as JSON; every field but
/// the status is optional so the generated shell types decode it whole.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct GrinderFollowView {
    /// Where it is.
    pub status: FollowStatus,
    /// The grinder record the connected grinder is linked to (the chip's
    /// "Use ‹grinder›" target when [`FollowStatus::Suggest`]).
    #[serde(default)]
    pub grinder_id: Option<String>,
    /// The last steady dial value ("480"), when there is one.
    #[serde(default)]
    pub grind: Option<String>,
    /// The dial is moving: the live value differs from [`Self::grind`] (Brew
    /// shows the live value greyed meanwhile).
    #[serde(default)]
    pub moving: Option<bool>,
    /// Grinding speed now, rpm, when known.
    #[serde(default)]
    pub grind_rpm: Option<u32>,
}

impl Default for GrinderFollowView {
    fn default() -> Self {
        GrinderFollowView {
            status: FollowStatus::Off,
            grinder_id: None,
            grind: None,
            moving: None,
            grind_rpm: None,
        }
    }
}

/// The inputs [`follow_status`] reads.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct FollowInputs<'a> {
    /// The grinder record the connected grinder is linked to (per device).
    pub linked: Option<&'a str>,
    /// Brew's active grinder record.
    pub active: Option<&'a str>,
    /// A driver is connected and has given a reading this connection.
    pub live: bool,
    /// A grinder was live earlier this session (so a value is known).
    pub seen: bool,
    /// The driver trusts its reading as the grind.
    pub trusted: bool,
    /// The user paused following.
    pub paused: bool,
    /// A steady value from this connection.
    pub steady: bool,
}

/// §5.1 and §5.4 as one rule.
pub(crate) fn follow_status(i: FollowInputs<'_>) -> FollowStatus {
    let Some(linked) = i.linked.filter(|l| !l.trim().is_empty()) else {
        return FollowStatus::Off;
    };
    let active = i.active.is_some_and(|a| a == linked);
    if !i.live {
        return if i.seen && active {
            FollowStatus::Stale
        } else {
            FollowStatus::Off
        };
    }
    if !i.trusted {
        return FollowStatus::ReadOnly;
    }
    if !active {
        return FollowStatus::Suggest;
    }
    if i.paused {
        return FollowStatus::Paused;
    }
    if i.steady {
        FollowStatus::Following
    } else {
        FollowStatus::Waiting
    }
}

/// A dial value counts after [`STABLE_READINGS`] equal readings in a row.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub(crate) struct Steadier {
    candidate: Option<String>,
    count: u32,
    /// The steady value, kept across a drop (shown stale).
    pub steady: Option<String>,
    /// [`Self::steady`] came from the current connection.
    pub steady_this_link: bool,
}

impl Steadier {
    /// One reading's dial value.
    pub fn feed(&mut self, value: &str) {
        if self.candidate.as_deref() == Some(value) {
            self.count = self.count.saturating_add(1);
        } else {
            self.candidate = Some(value.to_owned());
            self.count = 1;
        }
        if self.count >= STABLE_READINGS {
            self.steady = self.candidate.clone();
            self.steady_this_link = true;
        }
    }

    /// The live value differs from the steady one.
    pub fn moving(&self) -> bool {
        self.candidate.is_some() && self.candidate != self.steady
    }

    /// A new connection: the old value stays for display, but it must be
    /// seen steady again before it counts.
    pub fn relink(&mut self) {
        self.candidate = None;
        self.count = 0;
        self.steady_this_link = false;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn inputs() -> FollowInputs<'static> {
        FollowInputs {
            linked: Some("grinder:motto"),
            active: Some("grinder:motto"),
            live: true,
            seen: true,
            trusted: true,
            paused: false,
            steady: true,
        }
    }

    #[test]
    fn the_status_rules() {
        assert_eq!(follow_status(inputs()), FollowStatus::Following);
        assert_eq!(
            follow_status(FollowInputs {
                steady: false,
                ..inputs()
            }),
            FollowStatus::Waiting
        );
        assert_eq!(
            follow_status(FollowInputs {
                paused: true,
                ..inputs()
            }),
            FollowStatus::Paused
        );
        assert_eq!(
            follow_status(FollowInputs {
                active: Some("grinder:niche"),
                ..inputs()
            }),
            FollowStatus::Suggest
        );
        assert_eq!(
            follow_status(FollowInputs {
                active: None,
                ..inputs()
            }),
            FollowStatus::Suggest
        );
        assert_eq!(
            follow_status(FollowInputs {
                trusted: false,
                ..inputs()
            }),
            FollowStatus::ReadOnly
        );
        assert_eq!(
            follow_status(FollowInputs {
                live: false,
                ..inputs()
            }),
            FollowStatus::Stale
        );
        assert_eq!(
            follow_status(FollowInputs {
                live: false,
                seen: false,
                ..inputs()
            }),
            FollowStatus::Off
        );
        assert_eq!(
            follow_status(FollowInputs {
                live: false,
                active: Some("grinder:niche"),
                ..inputs()
            }),
            FollowStatus::Off
        );
        assert_eq!(
            follow_status(FollowInputs {
                linked: None,
                ..inputs()
            }),
            FollowStatus::Off
        );
        assert_eq!(
            follow_status(FollowInputs {
                linked: Some("  "),
                ..inputs()
            }),
            FollowStatus::Off
        );
    }

    #[test]
    fn a_value_counts_after_five_equal_readings() {
        let mut s = Steadier::default();
        for _ in 0..4 {
            s.feed("480");
        }
        assert_eq!(s.steady, None);
        assert!(s.moving());
        s.feed("480");
        assert_eq!(s.steady.as_deref(), Some("480"));
        assert!(!s.moving());
        // Turning the collar: moving until 485 holds.
        s.feed("482");
        s.feed("485");
        assert!(s.moving());
        assert_eq!(s.steady.as_deref(), Some("480"));
        for _ in 0..5 {
            s.feed("485");
        }
        assert_eq!(s.steady.as_deref(), Some("485"));
        // A reconnect keeps the value but makes it earn it again.
        s.relink();
        assert!(!s.steady_this_link);
        assert_eq!(s.steady.as_deref(), Some("485"));
    }
}

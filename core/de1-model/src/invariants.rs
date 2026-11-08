//! The design's invariants (§7), as far as the public API can show them.

use std::fmt;

/// An invariant from `DESIGN.md` §7 that this crate can observe.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Invariant {
    /// With a stop owed, the link up and the machine flowing, a `Stop` goes
    /// out within the retry interval until wind-down, session close or attempt
    /// 30, give or take the core's clock.
    I1,
    /// No `Stop` with no session open, and none from the core once the
    /// machine winds down (the honoured-to-flowing re-send comes with a
    /// flowing state).
    I2,
    /// A core-initiated request is never sent while the last reported state is
    /// a foreign flow state.
    I3,
    /// A flowing shot with a configured weight target has a live leg or a
    /// blocked reason, past the link's dead timeout (6 s): a blip or a tare
    /// still landing shows nothing (`DESIGN.md` §6).
    I4,
    /// The weight leg and the step-weight exit are live only on a trusted
    /// weight: a scale connected, reporting within the last second, on a shot
    /// the app saw begin.
    I5,
    /// In a session first seen flowing: no tare, no `SkipToNext`, no
    /// weight-class stop.
    I6,
    /// At most one `SkipToNext` per frame per fresh sample.
    I7,
    /// Every packet's steam temperature is at most the user's target.
    I9,
    /// A mirror emits no machine-state, stop or profile write, even for its
    /// user's tap, and no command of its own at all. No carve-out.
    I10,
    /// `Stale`, `Dead`, `StopTriggered` and `StopNotHonoured` fire once per
    /// episode.
    I11,
    /// Past attempt 30, no retry without two samples at flow since the last
    /// write.
    I17,
    /// No `Weight` or `EstimatedWeight` espresso stop while the true cup
    /// weight since the last tare is under target minus 6 g. Not checked on a
    /// mirror, whose stop is only a display.
    I13,
    /// A new session in the same mode has `pour_ml` at 0 and a fresh
    /// `AutoStop`.
    I20,
    /// A flowing espresso or hot-water session always has an `AutoStop`,
    /// including after `Unknown`. Steam and flush have no app legs.
    I21,
}

/// Whether a test failure on this invariant means a regression.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Status {
    /// Holds today; the model test fails on a violation.
    Enforced,
    /// Doesn't hold yet. The named PR fixes it and moves it to
    /// [`Enforced`](Status::Enforced).
    PendingPr(u8),
}

impl Invariant {
    /// Every invariant, for reports.
    pub const ALL: [Self; 14] = [
        Self::I1,
        Self::I2,
        Self::I3,
        Self::I4,
        Self::I5,
        Self::I6,
        Self::I7,
        Self::I9,
        Self::I10,
        Self::I11,
        Self::I13,
        Self::I17,
        Self::I20,
        Self::I21,
    ];

    /// Where this invariant stands. I8, I12, I14 to I16, I18 and I19 need
    /// types later PRs add; I12's "no panic" holds by running at all.
    #[must_use]
    pub const fn status(self) -> Status {
        match self {
            Self::I1
            | Self::I2
            | Self::I3
            | Self::I4
            | Self::I5
            | Self::I6
            | Self::I7
            | Self::I9
            | Self::I10
            | Self::I11
            | Self::I17
            | Self::I20
            | Self::I21 => Status::Enforced,
            Self::I13 => Status::PendingPr(8),
        }
    }
}

/// One broken invariant, with when and why.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Violation {
    /// Which one.
    pub invariant: Invariant,
    /// Simulated time, ms.
    pub at_ms: u64,
    /// What happened.
    pub detail: String,
}

impl fmt::Display for Violation {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            "{:?} at {} ms: {}",
            self.invariant, self.at_ms, self.detail
        )
    }
}

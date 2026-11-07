//! The design's invariants (§7), as far as the public API can show them.

use std::fmt;

/// An invariant from `DESIGN.md` §7 that this crate can observe.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Invariant {
    /// A core-initiated request is never sent while the last reported state is
    /// a foreign flow state.
    I3,
    /// At most one `SkipToNext` per frame per fresh sample.
    I7,
    /// Every packet's steam temperature is at most the user's target.
    I9,
    /// A mirror emits no commands of its own: a tap on the remote is the
    /// user's, and its transport carries it to the hub.
    I10,
    /// `Stale`, `Dead` and `StopTriggered` fire once per episode.
    I11,
    /// No `Weight` stop while the true cup weight is under target minus
    /// tolerance.
    I13,
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
    pub const ALL: [Self; 6] = [
        Self::I3,
        Self::I7,
        Self::I9,
        Self::I10,
        Self::I11,
        Self::I13,
    ];

    /// Where this invariant stands. I1, I2, I4 to I6, I8, I12 and I14 to I20
    /// need types later PRs add; I12's "no panic" holds by running at all.
    #[must_use]
    pub const fn status(self) -> Status {
        match self {
            Self::I3 | Self::I7 | Self::I9 => Status::PendingPr(2),
            Self::I10 | Self::I11 => Status::Enforced,
            Self::I13 => Status::PendingPr(3),
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

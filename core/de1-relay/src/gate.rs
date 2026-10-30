//! The hub's pairing gate (§2.5): the window, the single pending slot, the
//! failure count and the lockout.
//!
//! One gate per hub process, shared by every `HubConnection` as
//! `Arc<Mutex<PairingGate>>`. Lock order is always connection → gate; the gate
//! never calls into a connection. `close()` only bumps the generation (and
//! frees the slot); the connection holding the slot notices on its next call
//! and aborts.

use serde::Serialize;
use typeshare::typeshare;

use crate::{MAX_PAIR_FAILURES, PAIR_LOCKOUT_MS, PAIR_WINDOW_MS, RelayError};

/// What the hub UI shows about pairing.
#[typeshare]
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct GateStatus {
    /// The window is open.
    pub open: bool,
    /// Seconds left in the window (0 when closed).
    pub secs_left: u32,
    /// Failed codes counted in this window.
    pub failures: u8,
    /// Seconds left in the lockout (0 when not locked).
    pub locked_secs_left: u32,
    /// A remote holds the pairing slot.
    pub pending: bool,
}

/// The hub-wide pairing window, failure count and lockout.
#[derive(Debug, Default)]
pub struct PairingGate {
    window_until: Option<u64>,
    failures: u8,
    locked_until: Option<u64>,
    /// The pending slot and when its lease ends (the holder's own deadline), so
    /// a slot whose connection vanished without releasing it can't block
    /// pairing for good.
    slot: Option<(u64, u64)>,
    generation: u64,
    next_slot: u64,
}

/// A connection's claim on the pairing slot.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) struct Ticket {
    slot: u64,
    generation: u64,
}

/// The gate's answer to an `intent=pair` hello.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum Grant {
    /// The slot is this connection's until the window ends.
    Granted {
        ticket: Ticket,
        window_until: u64,
        secs_left: u32,
    },
    /// The window is closed.
    Closed,
    /// Another pairing holds the slot.
    Busy,
    /// Locked out after too many wrong codes.
    LockedOut { secs_left: u32 },
}

fn secs_until(until: u64, now_ms: u64) -> u32 {
    u32::try_from(until.saturating_sub(now_ms).div_ceil(1000)).unwrap_or(u32::MAX)
}

impl PairingGate {
    /// A closed gate with the default limits (Appendix A).
    pub fn new() -> Self {
        Self::default()
    }

    fn locked(&self, now_ms: u64) -> Option<u64> {
        self.locked_until.filter(|&until| now_ms < until)
    }

    fn window_open(&self, now_ms: u64) -> bool {
        self.locked(now_ms).is_none() && self.window_until.is_some_and(|until| now_ms < until)
    }

    /// Opens the window (the hub user tapped "Pair a device"). Opening an open
    /// window changes nothing.
    ///
    /// # Errors
    /// [`RelayError::LockedOut`] with the seconds left while locked out.
    pub fn open(&mut self, now_ms: u64) -> Result<GateStatus, RelayError> {
        if let Some(until) = self.locked(now_ms) {
            return Err(RelayError::LockedOut {
                secs_left: secs_until(until, now_ms),
            });
        }
        if !self.window_open(now_ms) {
            self.window_until = Some(now_ms + PAIR_WINDOW_MS);
            self.failures = 0;
        }
        Ok(self.status(now_ms))
    }

    /// Closes the window (the user, the window's end, a role change). Bumps
    /// the generation: the slot holder aborts with `CANCELLED` on its next call.
    pub fn close(&mut self) {
        self.window_until = None;
        self.slot = None;
        self.generation += 1;
    }

    /// The current status.
    pub fn status(&self, now_ms: u64) -> GateStatus {
        let open = self.window_open(now_ms);
        GateStatus {
            open,
            secs_left: match (open, self.window_until) {
                (true, Some(until)) => secs_until(until, now_ms),
                _ => 0,
            },
            failures: self.failures,
            locked_secs_left: self.locked(now_ms).map_or(0, |u| secs_until(u, now_ms)),
            pending: open && self.slot_taken(now_ms),
        }
    }

    pub(crate) fn try_acquire(&mut self, now_ms: u64) -> Grant {
        if let Some(until) = self.locked(now_ms) {
            return Grant::LockedOut {
                secs_left: secs_until(until, now_ms),
            };
        }
        let Some(window_until) = self.window_until.filter(|&u| now_ms < u) else {
            return Grant::Closed;
        };
        if self.slot_taken(now_ms) {
            return Grant::Busy;
        }
        self.next_slot += 1;
        self.slot = Some((self.next_slot, window_until));
        Grant::Granted {
            ticket: Ticket {
                slot: self.next_slot,
                generation: self.generation,
            },
            window_until,
            secs_left: secs_until(window_until, now_ms),
        }
    }

    fn slot_taken(&self, now_ms: u64) -> bool {
        self.slot
            .is_some_and(|(_, lease_until)| now_ms < lease_until)
    }

    /// Whether the ticket still holds the slot (no `close()` since).
    pub(crate) fn holds(&self, ticket: Ticket) -> bool {
        self.generation == ticket.generation && self.slot.is_some_and(|(id, _)| id == ticket.slot)
    }

    /// Moves the slot's lease to the holder's new deadline (after code entry).
    pub(crate) fn extend(&mut self, ticket: Ticket, lease_until: u64) {
        if self.holds(ticket) {
            self.slot = Some((ticket.slot, lease_until));
        }
    }

    /// Frees the slot without counting a failure.
    pub(crate) fn release(&mut self, ticket: Ticket) {
        if self.holds(ticket) {
            self.slot = None;
        }
    }

    /// Counts a failed code (message 3 failed after a code entry) and frees
    /// the slot; the last allowed failure closes the window and starts the
    /// lockout. Returns `(failures, locked)`, or `None` if the ticket no longer
    /// holds the slot.
    pub(crate) fn record_failure(&mut self, ticket: Ticket, now_ms: u64) -> Option<(u8, bool)> {
        if !self.holds(ticket) {
            return None;
        }
        self.slot = None;
        self.failures = self.failures.saturating_add(1);
        let locked = self.failures >= MAX_PAIR_FAILURES;
        if locked {
            self.window_until = None;
            self.locked_until = Some(now_ms + PAIR_LOCKOUT_MS);
        }
        Some((self.failures, locked))
    }

    /// A completed pairing: frees the slot and closes the window (one pairing
    /// per window).
    pub(crate) fn record_success(&mut self, ticket: Ticket) {
        if self.holds(ticket) {
            self.slot = None;
            self.window_until = None;
        }
    }

    /// Failed codes in this window and whether pairing is locked out.
    pub(crate) fn failure_state(&self, now_ms: u64) -> (u8, bool) {
        (self.failures, self.locked(now_ms).is_some())
    }
}

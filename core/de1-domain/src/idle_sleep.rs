//! App-owned DE1 **idle sleep** — the "Sleep after" timer.
//!
//! A stock DE1 has no sleep timer of its own that Crema can rely on: with the
//! user-presence feature flag set, the firmware only drops to the
//! `UserNotPresent` substate after a few idle minutes; it never goes to Sleep
//! (de1app `binary.tcl:2114-2122`, reaprime's heartbeat design notes). In
//! every reference app **the app** puts the machine to sleep after an idle
//! period, and this module is Crema's version of that timer:
//!
//! | app | setting | default |
//! |---|---|---|
//! | de1app | `screen_saver_delay`, "Cool down after" | 60 min |
//! | Decenza | `autoSleepMinutes` | 60 min |
//! | decaid / reaprime | `sleepTimeoutMinutes` | 30 min |
//!
//! Crema defaults to [`DEFAULT_IDLE_SLEEP_MIN`] (30, as decaid; de1app and
//! Decenza use 60) and offers [`IDLE_SLEEP_CHOICES_MIN`].
//!
//! [`IdleSleepPolicy`] is a plain, clock-free state machine: every input that
//! needs a time carries the shell's `now_ms` (one monotonic-ish clock per
//! shell), and the orchestrator (`CremaCore::idle_sleep`) turns its decisions
//! into the Sleep write, the throttled `UserPresent` poke and the events.
//!
//! ## What counts as activity
//!
//! Touches, keys and navigation in Crema; commands sent; the app returning to
//! the foreground; mirror relay activity; the machine entering, running or
//! leaving any non-idle state (espresso, steam, hot water, flush, descale,
//! clean, …); a shot, a profile upload, a guided brew or a maintenance cycle
//! in progress; a machine woken from sleep (a GHC tap restarts the timer).
//! **Scale weight is not activity**, as in all three reference apps.
//!
//! ## When the timer expires
//!
//! Sleep is sent once, and only from a sleepable state (Idle, SchedIdle, or
//! Refill when the firmware honours a sleep there — see
//! [`sleep_request_latches_in_refill`]) with the link up. When it can't be
//! sent yet (link down or reconnecting, state not yet known, the Refill
//! latch), the policy keeps trying on every tick
//! ([`IDLE_SLEEP_TICK_MS`]) until it can, or until activity restarts the
//! countdown. de1app (`show_going_to_sleep_page` → `delay_screen_saver`) and
//! decaid (`_onSleepTimeout` → `_resetSleepTimer`) re-arm a whole timeout
//! instead; retrying on the tick sleeps the machine as soon as the block
//! clears rather than a full period later, with the same guards.
//!
//! Once Sleep is sent the policy waits for the machine to report
//! Sleep / GoingToSleep and then stays quiet until the machine wakes. It never
//! re-sends after a sleep that landed, and never sends while the link is
//! down (the Decenza `fed2ed0b` / `5c3efc90` lesson: one Sleep per idle
//! episode). A Sleep the machine never confirms gets one retry after
//! [`IDLE_SLEEP_CONFIRM_MS`]; after that the countdown re-arms in full.

use de1_protocol::{MachineState, SubState, sleep_request_latches_in_refill};
use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// The idle-sleep choices offered in Settings, minutes. `0` is "Never".
pub const IDLE_SLEEP_CHOICES_MIN: [u32; 6] = [0, 15, 30, 60, 90, 120];

/// The default idle-sleep timeout, minutes — decaid's `sleepTimeoutMinutes`
/// default, and the delay Crema's old screensaver-coupled sleep defaulted to
/// (de1app `screen_saver_delay` and Decenza `autoSleepMinutes` use 60).
pub const DEFAULT_IDLE_SLEEP_MIN: u32 = 30;

/// The old Android screensaver delay's default, minutes. A stored saver of
/// exactly this is indistinguishable from one the user never touched, so it
/// carries nothing over (see [`idle_sleep_minutes_from_saver`]).
pub const LEGACY_SAVER_DEFAULT_MIN: u32 = 30;

/// How often the shells tick the policy, ms — also the retry cadence while
/// an expired timer is blocked. Expiry is computed from timestamps, so a
/// late tick (a throttled background tab, a dozing phone) fires on arrival
/// rather than losing the elapsed time.
pub const IDLE_SLEEP_TICK_MS: u64 = 30_000;

/// How long to wait for the machine to report Sleep after the policy sent
/// it before allowing the one retry, ms.
pub const IDLE_SLEEP_CONFIRM_MS: u64 = 60_000;

/// Sleep sends per idle episode before giving up and re-arming the
/// countdown: the first one plus one bounded retry.
pub const IDLE_SLEEP_MAX_ATTEMPTS: u8 = 2;

/// Minimum spacing between `UserPresent` pokes, ms (de1app writes it at most
/// once per 30 s on page loads; reaprime throttles to 5 s).
pub const PRESENCE_THROTTLE_MS: u64 = 30_000;

/// The Bengle firmware's ceiling for its own inactivity timeout (MMR
/// `InactivitySleepTimeout`), minutes — de1app `set_sleep_timeout_minutes`
/// and decaid `kFirmwareMaxSleepTimeoutMinutes` both clamp to 240.
pub const BENGLE_MAX_SLEEP_TIMEOUT_MIN: u32 = 240;

/// What the shell saw the user do. Every kind restarts the countdown the
/// same way; the kind is for diagnostics.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum IdleActivity {
    /// A touch or pointer press anywhere in Crema.
    Touch,
    /// A key press.
    Key,
    /// A screen / route change.
    Navigation,
    /// A command the shell sent outside the core's own request paths.
    Command,
    /// The app returned to the foreground.
    Foreground,
    /// A mirrored peer relayed a command or activity to this primary.
    Mirror,
}

/// One input to the idle-sleep timer, as the shells send it over the
/// `idle_sleep_json` facade. Adjacently tagged like `Event`:
/// `{"type":"Tick"}`, `{"type":"Configure","content":{"minutes":60}}`.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", content = "content")]
pub enum IdleSleepInput {
    /// The user's timeout, minutes (`0` = Never). Sent at startup and on
    /// every change.
    Configure {
        /// Timeout in minutes; `0` turns the timer off.
        minutes: u32,
    },
    /// The user did something in Crema.
    Activity {
        /// What they did.
        kind: IdleActivity,
    },
    /// The DE1 link became ready (`true`) or went away / is reconnecting
    /// (`false`).
    Link {
        /// Whether writes can reach the machine now.
        ready: bool,
    },
    /// The periodic check, every [`IDLE_SLEEP_TICK_MS`] and whenever the
    /// shell regains the CPU (a tab turning visible, the app resuming).
    Tick,
}

/// Why an expired timer could not send Sleep yet.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum IdleSleepBlock {
    /// The DE1 link is down or reconnecting.
    LinkDown,
    /// No machine state has been reported on this link yet.
    StateUnknown,
    /// The machine is in Refill and the firmware would latch the request
    /// and sleep right after the next refill instead (firmware below
    /// 1357, or a Bengle).
    RefillLatch,
}

/// What a [`IdleSleepPolicy::tick`] decided.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum IdleSleepAction {
    /// Nothing to do.
    None,
    /// Send Sleep now. `attempt` is 1 for the first send of an idle episode,
    /// 2 for the one retry of an unconfirmed send.
    Sleep {
        /// Which send of this episode this is.
        attempt: u8,
    },
    /// The timer has expired but Sleep can't be sent yet; retried on every
    /// tick. Reported once per reason change.
    Blocked {
        /// Why.
        reason: IdleSleepBlock,
        /// The machine state when blocked, when known.
        state: Option<MachineState>,
    },
    /// Sleep was sent [`IDLE_SLEEP_MAX_ATTEMPTS`] times and the machine
    /// never reported it; the countdown re-armed in full.
    Unconfirmed,
}

/// Snap any minute count to the nearest of [`IDLE_SLEEP_CHOICES_MIN`]. `0`
/// stays "Never"; a tie goes to the longer choice (the machine stays warm a
/// little longer rather than sleeping sooner than before).
#[must_use]
pub fn snap_idle_sleep_minutes(minutes: u32) -> u32 {
    if minutes == 0 {
        return 0;
    }
    let mut best = IDLE_SLEEP_CHOICES_MIN[1];
    for &choice in &IDLE_SLEEP_CHOICES_MIN[1..] {
        if minutes.abs_diff(choice) <= minutes.abs_diff(best) {
            best = choice;
        }
    }
    best
}

/// The idle-sleep timeout for a user upgrading from the screensaver-coupled
/// sleep (Android's "Screensaver" N minutes + "Sleep machine with
/// screensaver"). Only a value the user actually chose carries over: a saver
/// changed from its old default ([`LEGACY_SAVER_DEFAULT_MIN`]) to N minutes,
/// with the coupling on, becomes an idle sleep of N, snapped to a choice.
/// Everyone else — the saver at its default or off, or the coupling off —
/// gets [`DEFAULT_IDLE_SLEEP_MIN`].
#[must_use]
pub fn idle_sleep_minutes_from_saver(saver_min: i32, sleep_with_saver: bool) -> u32 {
    match u32::try_from(saver_min) {
        Ok(min) if min > 0 && min != LEGACY_SAVER_DEFAULT_MIN && sleep_with_saver => {
            snap_idle_sleep_minutes(min)
        }
        _ => DEFAULT_IDLE_SLEEP_MIN,
    }
}

/// Where the current idle episode stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Phase {
    /// Counting down from the last activity.
    Counting,
    /// Sleep was sent at `at_ms`; waiting for the machine to report it.
    Requested { at_ms: u64, attempts: u8 },
    /// The machine reported Sleep / GoingToSleep (whoever caused it).
    Asleep,
}

/// States the machine is put to sleep from. Refill is one of them unless the
/// firmware would latch the request (checked separately).
fn sleepable(state: MachineState) -> bool {
    matches!(
        state,
        MachineState::Idle | MachineState::SchedIdle | MachineState::Refill
    )
}

fn asleep(state: MachineState) -> bool {
    matches!(state, MachineState::Sleep | MachineState::GoingToSleep)
}

/// "The machine is doing something": any state that is neither a resting
/// state nor asleep — espresso, steam, hot water, flush, steam rinse, air
/// purge, descale, clean, calibration, boot-loader, an error, … Being in one
/// pins the countdown; leaving one restarts it.
fn active(state: MachineState) -> bool {
    !sleepable(state) && !asleep(state)
}

/// The idle-sleep state machine. See the module docs.
#[derive(Debug, Clone)]
pub struct IdleSleepPolicy {
    /// The timeout, minutes; `None` until the shell configures it (inert),
    /// `Some(0)` = Never.
    minutes: Option<u32>,
    last_activity_ms: Option<u64>,
    /// Activity the orchestrator saw without a shell timestamp (a command,
    /// a profile upload, a machine-state transition) — stamped at the next
    /// tick.
    activity_pending: bool,
    /// A Sleep the app sent outside this policy (power button, quit) —
    /// adopted at the next tick so the timer never sends a second one.
    app_sleep_pending: bool,
    state: Option<(MachineState, SubState)>,
    link_ready: bool,
    firmware_build: Option<u32>,
    machine_model: Option<u32>,
    phase: Phase,
    reported_block: Option<IdleSleepBlock>,
    last_presence_ms: Option<u64>,
}

impl Default for IdleSleepPolicy {
    fn default() -> Self {
        Self::new()
    }
}

impl IdleSleepPolicy {
    /// An unconfigured, link-down policy. Inert until [`configure`](Self::configure).
    #[must_use]
    pub fn new() -> Self {
        Self {
            minutes: None,
            last_activity_ms: None,
            activity_pending: false,
            app_sleep_pending: false,
            state: None,
            link_ready: false,
            firmware_build: None,
            machine_model: None,
            phase: Phase::Counting,
            reported_block: None,
            last_presence_ms: None,
        }
    }

    /// The configured timeout, minutes (`None` = not configured yet).
    #[must_use]
    pub fn minutes(&self) -> Option<u32> {
        self.minutes
    }

    /// Whether the link is up, as last reported.
    #[must_use]
    pub fn link_ready(&self) -> bool {
        self.link_ready
    }

    /// Set the timeout, minutes (`0` = Never). Returns whether it changed.
    /// A change restarts the countdown, so turning the timer on (or
    /// shortening it) never sleeps the machine the moment it's set.
    pub fn configure(&mut self, minutes: u32) -> bool {
        if self.minutes == Some(minutes) {
            return false;
        }
        self.minutes = Some(minutes);
        self.activity_pending = true;
        self.reported_block = None;
        true
    }

    /// The user did something at `now_ms`: restart the countdown. Returns
    /// whether to send a `UserPresent` poke now — only to an idle machine on
    /// a ready link (a poke to a sleeping DE1 is a no-op, reaprime
    /// `fe4be55f`; mid-shot it would contend with the stop write), at most
    /// once per [`PRESENCE_THROTTLE_MS`].
    pub fn activity(&mut self, now_ms: u64) -> bool {
        self.last_activity_ms = Some(now_ms);
        self.activity_pending = false;
        self.reported_block = None;
        if matches!(self.phase, Phase::Requested { .. }) {
            self.phase = Phase::Counting;
        }
        let idle = self
            .state
            .is_some_and(|(s, _)| matches!(s, MachineState::Idle | MachineState::SchedIdle));
        let due = self
            .last_presence_ms
            .is_none_or(|last| now_ms.saturating_sub(last) >= PRESENCE_THROTTLE_MS);
        if self.link_ready && idle && due {
            self.last_presence_ms = Some(now_ms);
            true
        } else {
            false
        }
    }

    /// Activity seen without a timestamp (a command the core built, a
    /// profile upload): stamped at the next tick.
    pub fn note_activity(&mut self) {
        self.activity_pending = true;
    }

    /// The app sent Sleep itself (the power button, sleep-on-quit). The
    /// timer adopts it as this episode's send instead of sending another.
    pub fn note_app_sleep(&mut self) {
        self.app_sleep_pending = true;
    }

    /// The firmware build and machine model, once read — they decide the
    /// Refill latch.
    pub fn set_machine_info(&mut self, firmware_build: Option<u32>, machine_model: Option<u32>) {
        self.firmware_build = firmware_build;
        self.machine_model = machine_model;
    }

    /// A machine state report.
    pub fn machine_state(&mut self, state: MachineState, substate: SubState) {
        let prev = self.state.map(|(s, _)| s);
        self.state = Some((state, substate));
        if asleep(state) {
            self.phase = Phase::Asleep;
            self.reported_block = None;
            return;
        }
        if self.phase == Phase::Asleep {
            // Woken — by Crema, the GHC, or anything else: a new episode.
            self.phase = Phase::Counting;
            self.activity_pending = true;
        }
        let was_active = prev.is_some_and(active);
        if active(state) != was_active || (active(state) && prev != Some(state)) {
            self.activity_pending = true;
        }
    }

    /// The link became ready, or went away. Going down forgets the machine
    /// state (stale until the next report) and turns an unconfirmed send
    /// back into a countdown, so a Sleep is never queued across a
    /// disconnect: an expired timer re-sends only once the link is back and
    /// the machine reports a sleepable state.
    pub fn link(&mut self, ready: bool) {
        if ready {
            if !self.link_ready {
                self.last_presence_ms = None;
            }
            self.link_ready = true;
            return;
        }
        self.link_ready = false;
        self.state = None;
        if matches!(self.phase, Phase::Requested { .. }) {
            self.phase = Phase::Counting;
        }
    }

    /// The periodic check. `busy` is the orchestrator's "work that must not
    /// be cut off" — a shot, a profile upload, a guided brew, a maintenance
    /// cycle — which pins the countdown like an active machine state.
    pub fn tick(&mut self, now_ms: u64, busy: bool) -> IdleSleepAction {
        let Some(minutes) = self.minutes else {
            return IdleSleepAction::None;
        };
        if self.app_sleep_pending {
            self.app_sleep_pending = false;
            if self.phase != Phase::Asleep {
                self.phase = Phase::Requested {
                    at_ms: now_ms,
                    attempts: 1,
                };
            }
        }
        // A clock that stepped backwards must not stall the countdown.
        if self.activity_pending
            || busy
            || self.state.is_some_and(|(s, _)| active(s))
            || self.last_activity_ms.is_none_or(|t| t > now_ms)
        {
            self.activity_pending = false;
            self.last_activity_ms = Some(now_ms);
            self.reported_block = None;
        }
        if minutes == 0 {
            return IdleSleepAction::None;
        }
        match self.phase {
            Phase::Asleep => return IdleSleepAction::None,
            Phase::Requested { at_ms, attempts } => {
                if now_ms.saturating_sub(at_ms) < IDLE_SLEEP_CONFIRM_MS {
                    return IdleSleepAction::None;
                }
                if attempts >= IDLE_SLEEP_MAX_ATTEMPTS {
                    self.phase = Phase::Counting;
                    self.last_activity_ms = Some(now_ms);
                    return IdleSleepAction::Unconfirmed;
                }
                if self.blocker().is_none() {
                    self.phase = Phase::Requested {
                        at_ms: now_ms,
                        attempts: attempts + 1,
                    };
                    return IdleSleepAction::Sleep {
                        attempt: attempts + 1,
                    };
                }
                self.phase = Phase::Counting;
            }
            Phase::Counting => {}
        }
        let last = self.last_activity_ms.unwrap_or(now_ms);
        if now_ms.saturating_sub(last) < u64::from(minutes) * 60_000 {
            return IdleSleepAction::None;
        }
        if self.state.is_some_and(|(s, _)| asleep(s)) {
            self.phase = Phase::Asleep;
            return IdleSleepAction::None;
        }
        if let Some(reason) = self.blocker() {
            if self.reported_block == Some(reason) {
                return IdleSleepAction::None;
            }
            self.reported_block = Some(reason);
            return IdleSleepAction::Blocked {
                reason,
                state: self.state.map(|(s, _)| s),
            };
        }
        self.reported_block = None;
        self.phase = Phase::Requested {
            at_ms: now_ms,
            attempts: 1,
        };
        IdleSleepAction::Sleep { attempt: 1 }
    }

    /// Why Sleep can't be sent right now, if anything.
    fn blocker(&self) -> Option<IdleSleepBlock> {
        if !self.link_ready {
            return Some(IdleSleepBlock::LinkDown);
        }
        // An active state never gets here — it pins the countdown — so a
        // known state is a sleepable one.
        let Some((state, substate)) = self.state else {
            return Some(IdleSleepBlock::StateUnknown);
        };
        if sleep_request_latches_in_refill(state, substate, self.firmware_build, self.machine_model)
        {
            return Some(IdleSleepBlock::RefillLatch);
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MIN: u64 = 60_000;
    const T0: u64 = 1_000_000_000;

    /// A configured (60 min), linked policy with an Idle machine on new
    /// firmware, its countdown started at `T0`.
    fn ready() -> IdleSleepPolicy {
        let mut p = IdleSleepPolicy::new();
        p.configure(60);
        p.link(true);
        p.set_machine_info(Some(1400), Some(1));
        p.machine_state(MachineState::Idle, SubState::Ready);
        assert_eq!(p.tick(T0, false), IdleSleepAction::None);
        p
    }

    /// Tick every 30 s from `from` to `to` inclusive, collecting non-None actions.
    fn run(p: &mut IdleSleepPolicy, from: u64, to: u64) -> Vec<(u64, IdleSleepAction)> {
        let mut out = Vec::new();
        let mut t = from;
        while t <= to {
            let a = p.tick(t, false);
            if a != IdleSleepAction::None {
                out.push((t, a));
            }
            t += IDLE_SLEEP_TICK_MS;
        }
        out
    }

    #[test]
    fn expiry_sends_exactly_one_sleep() {
        let mut p = ready();
        assert_eq!(p.tick(T0 + 59 * MIN, false), IdleSleepAction::None);
        assert_eq!(
            p.tick(T0 + 60 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
        // The machine confirms; nothing more, however long it stays asleep.
        p.machine_state(MachineState::GoingToSleep, SubState::Ready);
        p.machine_state(MachineState::Sleep, SubState::Ready);
        assert!(run(&mut p, T0 + 60 * MIN + 30_000, T0 + 600 * MIN).is_empty());
    }

    #[test]
    fn activity_restarts_the_countdown() {
        let mut p = ready();
        p.activity(T0 + 50 * MIN);
        assert_eq!(p.tick(T0 + 60 * MIN, false), IdleSleepAction::None);
        assert_eq!(p.tick(T0 + 109 * MIN, false), IdleSleepAction::None);
        assert_eq!(
            p.tick(T0 + 110 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn untimed_activity_is_stamped_at_the_next_tick() {
        let mut p = ready();
        p.note_activity();
        assert_eq!(p.tick(T0 + 30 * MIN, false), IdleSleepAction::None);
        assert_eq!(p.tick(T0 + 89 * MIN, false), IdleSleepAction::None);
        assert_eq!(
            p.tick(T0 + 90 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn blocked_expiry_retries_every_tick_until_it_can_send() {
        let mut p = ready();
        p.machine_state(MachineState::Refill, SubState::Refill);
        p.set_machine_info(Some(1300), Some(1));
        let actions = run(&mut p, T0 + 60 * MIN, T0 + 70 * MIN);
        // Reported once, then retried silently.
        assert_eq!(
            actions,
            vec![(
                T0 + 60 * MIN,
                IdleSleepAction::Blocked {
                    reason: IdleSleepBlock::RefillLatch,
                    state: Some(MachineState::Refill),
                }
            )]
        );
        // Refilled: the next tick sends.
        p.machine_state(MachineState::Idle, SubState::Ready);
        assert_eq!(
            p.tick(T0 + 70 * MIN + 30_000, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn refill_sleeps_directly_on_firmware_1357_and_up() {
        let mut p = ready();
        p.set_machine_info(Some(1357), Some(1));
        p.machine_state(MachineState::Refill, SubState::Refill);
        assert_eq!(
            p.tick(T0 + 60 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn refill_latches_below_1357_unknown_firmware_and_on_bengle() {
        for (build, model) in [
            (Some(1356), Some(1)),
            (None, Some(1)),
            (Some(1400), Some(128)),
        ] {
            let mut p = ready();
            p.set_machine_info(build, model);
            p.machine_state(MachineState::Idle, SubState::Refill);
            assert_eq!(
                p.tick(T0 + 60 * MIN, false),
                IdleSleepAction::Blocked {
                    reason: IdleSleepBlock::RefillLatch,
                    state: Some(MachineState::Idle),
                },
                "{build:?} / {model:?}"
            );
        }
    }

    #[test]
    fn a_confirmed_sleep_is_never_resent() {
        let mut p = ready();
        assert_eq!(
            p.tick(T0 + 60 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
        p.machine_state(MachineState::Sleep, SubState::Ready);
        // A link drop and reconnect to the sleeping machine: still quiet.
        p.link(false);
        p.link(true);
        p.machine_state(MachineState::Sleep, SubState::Ready);
        assert!(run(&mut p, T0 + 61 * MIN, T0 + 300 * MIN).is_empty());
    }

    #[test]
    fn an_unconfirmed_sleep_gets_one_retry_then_rearms() {
        let mut p = ready();
        assert_eq!(
            p.tick(T0 + 60 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
        assert_eq!(p.tick(T0 + 60 * MIN + 30_000, false), IdleSleepAction::None);
        assert_eq!(
            p.tick(T0 + 61 * MIN, false),
            IdleSleepAction::Sleep { attempt: 2 }
        );
        assert_eq!(p.tick(T0 + 62 * MIN, false), IdleSleepAction::Unconfirmed);
        // A full new countdown, not a send every tick.
        assert!(run(&mut p, T0 + 62 * MIN + 30_000, T0 + 121 * MIN + 30_000).is_empty());
        assert_eq!(
            p.tick(T0 + 122 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn nothing_is_sent_while_disconnected() {
        let mut p = ready();
        p.link(false);
        let actions = run(&mut p, T0 + 60 * MIN, T0 + 200 * MIN);
        assert_eq!(
            actions,
            vec![(
                T0 + 60 * MIN,
                IdleSleepAction::Blocked {
                    reason: IdleSleepBlock::LinkDown,
                    state: None,
                }
            )]
        );
        // Back, but no state yet: still nothing.
        p.link(true);
        assert_eq!(
            p.tick(T0 + 200 * MIN + 30_000, false),
            IdleSleepAction::Blocked {
                reason: IdleSleepBlock::StateUnknown,
                state: None,
            }
        );
        p.machine_state(MachineState::Idle, SubState::Ready);
        assert_eq!(
            p.tick(T0 + 201 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn a_send_lost_to_a_disconnect_is_not_queued() {
        let mut p = ready();
        assert_eq!(
            p.tick(T0 + 60 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
        p.link(false);
        assert!(matches!(
            p.tick(T0 + 62 * MIN, false),
            IdleSleepAction::Blocked {
                reason: IdleSleepBlock::LinkDown,
                ..
            }
        ));
    }

    #[test]
    fn never_sleeps_in_an_active_state_and_restarts_after_it() {
        for state in [
            MachineState::Espresso,
            MachineState::Steam,
            MachineState::HotWater,
            MachineState::HotWaterRinse,
            MachineState::SteamRinse,
            MachineState::Descale,
            MachineState::Clean,
            MachineState::AirPurge,
        ] {
            let mut p = ready();
            p.machine_state(state, SubState::Pouring);
            assert!(
                run(&mut p, T0 + 30_000, T0 + 300 * MIN).is_empty(),
                "{state:?}"
            );
            // Back to Idle: the countdown starts from there (stamped at
            // the next tick).
            p.machine_state(MachineState::Idle, SubState::Ready);
            assert_eq!(p.tick(T0 + 301 * MIN, false), IdleSleepAction::None);
            assert_eq!(p.tick(T0 + 360 * MIN, false), IdleSleepAction::None);
            assert_eq!(
                p.tick(T0 + 361 * MIN, false),
                IdleSleepAction::Sleep { attempt: 1 },
                "{state:?}"
            );
        }
    }

    #[test]
    fn never_sleeps_while_busy() {
        // A shot, a profile upload, a guided brew or a maintenance cycle.
        let mut p = ready();
        let mut t = T0;
        while t <= T0 + 300 * MIN {
            assert_eq!(p.tick(t, true), IdleSleepAction::None);
            t += IDLE_SLEEP_TICK_MS;
        }
        assert_eq!(p.tick(T0 + 359 * MIN, false), IdleSleepAction::None);
        assert_eq!(
            p.tick(T0 + 360 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn never_is_off() {
        let mut p = ready();
        p.configure(0);
        assert!(run(&mut p, T0, T0 + 1000 * MIN).is_empty());
        // Turning it back on starts a fresh countdown, not an instant sleep.
        p.configure(15);
        assert_eq!(
            p.tick(T0 + 1000 * MIN + 30_000, false),
            IdleSleepAction::None
        );
        assert_eq!(
            p.tick(T0 + 1015 * MIN + 30_000, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn unconfigured_is_inert() {
        let mut p = IdleSleepPolicy::new();
        p.link(true);
        p.machine_state(MachineState::Idle, SubState::Ready);
        assert!(run(&mut p, T0, T0 + 1000 * MIN).is_empty());
    }

    #[test]
    fn an_external_wake_restarts_the_timer() {
        let mut p = ready();
        assert_eq!(
            p.tick(T0 + 60 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
        p.machine_state(MachineState::Sleep, SubState::Ready);
        // GHC tap at +200 min.
        p.machine_state(MachineState::Idle, SubState::Heating);
        assert_eq!(p.tick(T0 + 200 * MIN, false), IdleSleepAction::None);
        assert_eq!(p.tick(T0 + 259 * MIN, false), IdleSleepAction::None);
        assert_eq!(
            p.tick(T0 + 260 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn a_wake_while_disconnected_restarts_on_reconnect() {
        let mut p = ready();
        p.machine_state(MachineState::Sleep, SubState::Ready);
        p.link(false);
        p.link(true);
        p.machine_state(MachineState::Idle, SubState::Ready);
        assert_eq!(p.tick(T0 + 100 * MIN, false), IdleSleepAction::None);
        assert_eq!(
            p.tick(T0 + 160 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn an_app_sleep_is_adopted_not_doubled() {
        let mut p = ready();
        // Sleep-on-quit (or the power button) right at expiry.
        p.note_app_sleep();
        assert_eq!(p.tick(T0 + 60 * MIN, false), IdleSleepAction::None);
        p.machine_state(MachineState::Sleep, SubState::Ready);
        assert!(run(&mut p, T0 + 61 * MIN, T0 + 300 * MIN).is_empty());
    }

    #[test]
    fn already_asleep_at_expiry_sends_nothing() {
        let mut p = ready();
        p.machine_state(MachineState::Sleep, SubState::Ready);
        assert!(run(&mut p, T0, T0 + 300 * MIN).is_empty());
    }

    #[test]
    fn a_backwards_clock_does_not_stall_the_countdown() {
        let mut p = ready();
        assert_eq!(p.tick(T0 - 100 * MIN, false), IdleSleepAction::None);
        assert_eq!(
            p.tick(T0 - 40 * MIN, false),
            IdleSleepAction::Sleep { attempt: 1 }
        );
    }

    #[test]
    fn presence_pokes_only_on_activity_throttled_and_never_asleep() {
        let mut p = ready();
        assert!(p.activity(T0 + 1_000));
        assert!(!p.activity(T0 + 10_000), "throttled");
        assert!(p.activity(T0 + 31_000));
        p.machine_state(MachineState::Espresso, SubState::Pouring);
        assert!(!p.activity(T0 + 100_000), "mid-shot");
        p.machine_state(MachineState::Sleep, SubState::Ready);
        assert!(!p.activity(T0 + 200_000), "asleep");
        p.machine_state(MachineState::Idle, SubState::Ready);
        p.link(false);
        assert!(!p.activity(T0 + 300_000), "link down");
        // A fresh link pokes on the first activity, but never by itself.
        p.link(true);
        p.machine_state(MachineState::Idle, SubState::Ready);
        assert!(p.activity(T0 + 300_500));
    }

    #[test]
    fn snapping_to_the_choices() {
        assert_eq!(snap_idle_sleep_minutes(0), 0);
        assert_eq!(snap_idle_sleep_minutes(1), 15);
        assert_eq!(snap_idle_sleep_minutes(20), 15);
        assert_eq!(snap_idle_sleep_minutes(25), 30);
        assert_eq!(snap_idle_sleep_minutes(45), 60, "a tie goes longer");
        assert_eq!(snap_idle_sleep_minutes(50), 60);
        assert_eq!(snap_idle_sleep_minutes(75), 90);
        assert_eq!(snap_idle_sleep_minutes(100), 90);
        assert_eq!(snap_idle_sleep_minutes(110), 120);
        assert_eq!(snap_idle_sleep_minutes(480), 120);
        for c in IDLE_SLEEP_CHOICES_MIN {
            assert_eq!(snap_idle_sleep_minutes(c), c);
        }
    }

    #[test]
    fn migrating_from_the_screensaver_coupling() {
        // A saver the user changed, with the coupling on, carries over.
        assert_eq!(idle_sleep_minutes_from_saver(45, true), 60);
        assert_eq!(idle_sleep_minutes_from_saver(5, true), 15);
        assert_eq!(idle_sleep_minutes_from_saver(60, true), 60);
        assert_eq!(idle_sleep_minutes_from_saver(120, true), 120);
        // The saver at its old default carries nothing: the default.
        assert_eq!(
            idle_sleep_minutes_from_saver(30, true),
            DEFAULT_IDLE_SLEEP_MIN
        );
        // A changed saver with the coupling off: the default.
        assert_eq!(
            idle_sleep_minutes_from_saver(90, false),
            DEFAULT_IDLE_SLEEP_MIN
        );
        // Saver off, or the coupling off: the default.
        assert_eq!(
            idle_sleep_minutes_from_saver(0, true),
            DEFAULT_IDLE_SLEEP_MIN
        );
        assert_eq!(
            idle_sleep_minutes_from_saver(30, false),
            DEFAULT_IDLE_SLEEP_MIN
        );
        assert_eq!(
            idle_sleep_minutes_from_saver(-1, true),
            DEFAULT_IDLE_SLEEP_MIN
        );
    }

    #[test]
    fn inputs_round_trip_as_adjacently_tagged_json() {
        let tick: IdleSleepInput = serde_json::from_str(r#"{"type":"Tick"}"#).unwrap();
        assert_eq!(tick, IdleSleepInput::Tick);
        let cfg: IdleSleepInput =
            serde_json::from_str(r#"{"type":"Configure","content":{"minutes":90}}"#).unwrap();
        assert_eq!(cfg, IdleSleepInput::Configure { minutes: 90 });
        let act: IdleSleepInput =
            serde_json::from_str(r#"{"type":"Activity","content":{"kind":"Touch"}}"#).unwrap();
        assert_eq!(
            act,
            IdleSleepInput::Activity {
                kind: IdleActivity::Touch
            }
        );
        let link: IdleSleepInput =
            serde_json::from_str(r#"{"type":"Link","content":{"ready":true}}"#).unwrap();
        assert_eq!(link, IdleSleepInput::Link { ready: true });
    }
}

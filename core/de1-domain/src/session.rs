//! A lightweight session-timing core shared by the monitors.
//!
//! [`ShotMonitor`](crate::ShotMonitor), [`WaterMonitor`](crate::WaterMonitor)
//! and [`SteamMonitor`](crate::SteamMonitor) each track a session whose
//! lifecycle is the same skeleton: it begins when the DE1 enters a state,
//! holds a start timestamp, and on leaving the state yields the elapsed
//! duration.
//!
//! [`SessionTimer`] captures *only* that skeleton — the start timestamp and
//! the `now - started` duration arithmetic. The monitors genuinely diverge in
//! what else they accumulate (telemetry samples, profile frames, eco mode,
//! clog analysis), so this stays deliberately small: it owns the timing, the
//! monitors own everything else.
//!
//! [`Session`] is the other half: what the core knows about the flow the
//! machine is running, decided once when that flow opens. A link event never
//! destroys it. Stop ownership joins it in later work
//! (`.scratch/machine-control/DESIGN.md` §3.3); today it answers one question,
//! whether the app saw this flow begin.

use std::time::Duration;

use de1_protocol::{MachineState, StateInfo, SubState};

use crate::stop::StopState;

/// Tracks the start time of one in-progress session at a time.
///
/// Sans-IO: it holds no clock. The caller supplies a monotonic `now` from
/// session start — modelled as a [`Duration`] from a shell-chosen epoch (e.g.
/// the browser's `performance.now()` or Android's `SystemClock.uptimeMillis()`).
#[derive(Debug, Default)]
pub struct SessionTimer {
    /// Monotonic timestamp the session began, if a session is running.
    started: Option<Duration>,
}

impl SessionTimer {
    /// Create an idle timer.
    pub fn new() -> SessionTimer {
        SessionTimer::default()
    }

    /// Whether a session is currently being timed.
    pub fn is_running(&self) -> bool {
        self.started.is_some()
    }

    /// Start timing a session at `now`, if one is not already running.
    /// Returns `true` when a new session was started.
    pub fn start(&mut self, now: Duration) -> bool {
        if self.started.is_some() {
            return false;
        }
        self.started = Some(now);
        true
    }

    /// Elapsed time since the running session started — [`Duration::ZERO`] if
    /// no session is running. Saturating: a non-monotonic `now` yields zero.
    pub fn elapsed(&self, now: Duration) -> Duration {
        self.started
            .map_or(Duration::ZERO, |start| now.saturating_sub(start))
    }

    /// Finish the running session, if any, returning its total duration.
    /// Returns `None` when no session was running.
    pub fn finish(&mut self, now: Duration) -> Option<Duration> {
        let started = self.started.take()?;
        Some(now.saturating_sub(started))
    }
}

/// The kind of flow a [`Session`] covers, one per water-moving machine state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActiveMode {
    /// A shot.
    Espresso,
    /// Hot water from the wand.
    HotWater,
    /// A group flush.
    Flush,
    /// Steam.
    Steam,
    /// The steam-wand rinse.
    SteamRinse,
}

impl ActiveMode {
    /// The mode of `state`, or `None` when no water is moving.
    #[must_use]
    pub fn of(state: MachineState) -> Option<ActiveMode> {
        match state {
            MachineState::Espresso => Some(ActiveMode::Espresso),
            MachineState::HotWater => Some(ActiveMode::HotWater),
            MachineState::HotWaterRinse => Some(ActiveMode::Flush),
            MachineState::Steam => Some(ActiveMode::Steam),
            MachineState::SteamRinse => Some(ActiveMode::SteamRinse),
            _ => None,
        }
    }
}

/// The substates before water flows. An unknown substate counts as flowing.
#[must_use]
pub fn is_pre_flow(substate: SubState) -> bool {
    matches!(
        substate,
        SubState::Ready | SubState::Heating | SubState::FinalHeating | SubState::Stabilising
    )
}

/// Whether the app saw a flow begin.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Observed {
    /// First seen before flow, on a live link.
    FromStart,
    /// First seen flowing or winding down: whatever is on the scale can't be
    /// told from a tare offset.
    Unseen,
}

impl Observed {
    /// How a session opening on `substate` was observed. Only the first state
    /// on a link can be unseen, and only past the pre-flow substates; an
    /// unknown substate counts as flowing.
    #[must_use]
    pub fn on_open(first_on_link: bool, substate: SubState) -> Observed {
        if first_on_link && !is_pre_flow(substate) {
            Observed::Unseen
        } else {
            Observed::FromStart
        }
    }
}

/// Why the scale can't speak for a session's cup.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Blocked {
    /// No scale is connected.
    NoScale,
    /// A scale is connected but isn't reporting weight.
    ScaleSilent,
    /// The session began before the app saw it.
    BeganUnseen,
    /// A heavy reading right after first flow never settled: the cup wasn't
    /// tared, and its weight can't be told from coffee.
    UntaredCup,
    /// The scale went quiet before the cup had any weight in it.
    ScaleLost,
}

impl Blocked {
    /// The reason's wire name, as `weightBlocked` carries it.
    #[must_use]
    pub const fn as_str(self) -> &'static str {
        match self {
            Blocked::NoScale => "no-scale",
            Blocked::ScaleSilent => "scale-silent",
            Blocked::BeganUnseen => "began-unseen",
            Blocked::UntaredCup => "untared-cup",
            Blocked::ScaleLost => "scale-lost",
        }
    }

    /// The note a shell shows on the weight row, the same on both shells.
    #[must_use]
    pub const fn note(self) -> &'static str {
        match self {
            Blocked::NoScale => "no scale",
            Blocked::ScaleSilent => "Scale isn't reporting",
            Blocked::BeganUnseen => "Started before Crema connected",
            Blocked::UntaredCup => "cup not tared",
            Blocked::ScaleLost => "Scale stopped reporting. Stop this shot by hand.",
        }
    }

    /// Every reason, for the shells' tests.
    pub const ALL: [Blocked; 5] = [
        Blocked::NoScale,
        Blocked::ScaleSilent,
        Blocked::BeganUnseen,
        Blocked::UntaredCup,
        Blocked::ScaleLost,
    ];
}

/// What the scale's zero means for a session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Baseline {
    /// Tare pending, or no reading yet.
    Unknown,
    /// The zero is good: the tare landed or timed out, auto-tare is off, or
    /// the untared-cup guard latched a virtual zero.
    Tared,
    /// The scale's readings can't be trusted for this session.
    Refused {
        /// Why.
        why: Blocked,
    },
}

/// One flow, from its first state until the machine leaves that mode.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Session {
    /// The kind of flow.
    pub mode: ActiveMode,
    /// Whether the app saw it begin.
    pub observed: Observed,
    /// What the scale's zero means for it.
    pub baseline: Baseline,
    /// Where its stop stands.
    pub stop: StopState,
}

/// How a state report changed the session.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SessionChange {
    /// No session before or after.
    None,
    /// The same session continues.
    Kept,
    /// A session opened where there was none.
    Opened,
    /// The session closed; another may have opened in a new mode.
    Closed,
    /// A new session in the same mode: flow can't go backwards, so a
    /// pre-flow substate on return is a new pour.
    Replaced,
}

/// The substates in which the machine winds a flow down. An unknown substate
/// is never one.
#[must_use]
pub fn is_wind_down(substate: SubState) -> bool {
    matches!(
        substate,
        SubState::Ending | SubState::Puffing | SubState::PausedSteam
    )
}

impl Session {
    /// Open a session in `mode`. An unseen one refuses its scale from the
    /// start.
    #[must_use]
    pub fn open(mode: ActiveMode, observed: Observed) -> Session {
        let baseline = match observed {
            Observed::FromStart => Baseline::Unknown,
            Observed::Unseen => Baseline::Refused {
                why: Blocked::BeganUnseen,
            },
        };
        Session {
            mode,
            observed,
            baseline,
            stop: StopState::Armed,
        }
    }

    /// Open a session in `mode` on its first state, `substate`: one first
    /// seen winding down is already honoured.
    #[must_use]
    pub fn open_on(mode: ActiveMode, first_on_link: bool, substate: SubState) -> Session {
        let mut session = Session::open(mode, Observed::on_open(first_on_link, substate));
        if is_wind_down(substate) {
            session.stop = StopState::Honoured { cause: None };
        }
        session
    }

    /// The session after a state report. A report in the same mode keeps the
    /// session, whatever the link did meanwhile, except a pre-flow substate on
    /// a link's first state: flow can't go backwards, so that's a new pour.
    /// Any other mode closes it, and a report in a new mode opens that mode's.
    /// `first_on_link` is true for the first state on this link generation.
    #[must_use]
    pub fn on_state(
        current: Option<Session>,
        info: StateInfo,
        first_on_link: bool,
    ) -> (Option<Session>, SessionChange) {
        let Some(mode) = ActiveMode::of(info.state) else {
            let change = if current.is_some() {
                SessionChange::Closed
            } else {
                SessionChange::None
            };
            return (None, change);
        };
        match current {
            Some(session)
                if session.mode == mode && first_on_link && is_pre_flow(info.substate) =>
            {
                (
                    Some(Session::open(mode, Observed::FromStart)),
                    SessionChange::Replaced,
                )
            }
            Some(session) if session.mode == mode => (Some(session), SessionChange::Kept),
            Some(_) => (
                Some(Session::open_on(mode, first_on_link, info.substate)),
                SessionChange::Closed,
            ),
            None => (
                Some(Session::open_on(mode, first_on_link, info.substate)),
                SessionChange::Opened,
            ),
        }
    }

    /// Why this session's scale can't be used, if it can't.
    #[must_use]
    pub fn weight_refused(&self) -> Option<Blocked> {
        match self.baseline {
            Baseline::Refused { why } => Some(why),
            Baseline::Unknown | Baseline::Tared => None,
        }
    }

    /// The zero is known: `Unknown` becomes `Tared`. A refused baseline stays
    /// refused for the session.
    pub fn tared(&mut self) {
        if self.baseline == Baseline::Unknown {
            self.baseline = Baseline::Tared;
        }
    }

    /// The untared-cup guard tripped: weight is refused for the session.
    pub fn untared_cup(&mut self) {
        if matches!(self.baseline, Baseline::Unknown | Baseline::Tared) {
            self.baseline = Baseline::Refused {
                why: Blocked::UntaredCup,
            };
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn info(state: MachineState, substate: SubState) -> StateInfo {
        StateInfo { state, substate }
    }

    /// Helper: a `Duration` of `ms` milliseconds — the unit every test below
    /// thinks in.
    fn ms(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    #[test]
    fn a_fresh_timer_is_not_running() {
        let timer = SessionTimer::new();
        assert!(!timer.is_running());
        assert_eq!(timer.elapsed(ms(1_000)), Duration::ZERO);
    }

    #[test]
    fn start_begins_a_session_once() {
        let mut timer = SessionTimer::new();
        assert!(timer.start(ms(1_000)));
        assert!(timer.is_running());
        // A second start while running is a no-op.
        assert!(!timer.start(ms(2_000)));
    }

    #[test]
    fn elapsed_is_measured_from_the_start() {
        let mut timer = SessionTimer::new();
        timer.start(ms(1_000));
        assert_eq!(timer.elapsed(ms(3_500)), ms(2_500));
    }

    #[test]
    fn finish_yields_the_duration_and_clears_the_timer() {
        let mut timer = SessionTimer::new();
        timer.start(ms(1_000));
        assert_eq!(timer.finish(ms(9_500)), Some(ms(8_500)));
        assert!(!timer.is_running());
        // Finishing again with nothing running yields None.
        assert_eq!(timer.finish(ms(10_000)), None);
    }

    #[test]
    fn a_non_monotonic_now_saturates_to_zero() {
        let mut timer = SessionTimer::new();
        timer.start(ms(5_000));
        assert_eq!(timer.elapsed(ms(1_000)), Duration::ZERO);
        assert_eq!(timer.finish(ms(1_000)), Some(Duration::ZERO));
    }

    #[test]
    fn every_flow_state_has_a_mode_and_nothing_else_does() {
        for (state, mode) in [
            (MachineState::Espresso, Some(ActiveMode::Espresso)),
            (MachineState::HotWater, Some(ActiveMode::HotWater)),
            (MachineState::HotWaterRinse, Some(ActiveMode::Flush)),
            (MachineState::Steam, Some(ActiveMode::Steam)),
            (MachineState::SteamRinse, Some(ActiveMode::SteamRinse)),
            (MachineState::Idle, None),
            (MachineState::Sleep, None),
            (MachineState::Clean, None),
        ] {
            assert_eq!(ActiveMode::of(state), mode, "{state:?}");
        }
    }

    #[test]
    fn only_a_first_state_past_the_pre_flow_substates_is_unseen() {
        use Observed::{FromStart, Unseen};
        for (first, substate, observed) in [
            (true, SubState::Pouring, Unseen),
            (true, SubState::Preinfusion, Unseen),
            (true, SubState::Ending, Unseen),
            (true, SubState::Unknown, Unseen),
            (true, SubState::Ready, FromStart),
            (true, SubState::Heating, FromStart),
            (true, SubState::FinalHeating, FromStart),
            (true, SubState::Stabilising, FromStart),
            (false, SubState::Pouring, FromStart),
            (false, SubState::Unknown, FromStart),
        ] {
            assert_eq!(
                Observed::on_open(first, substate),
                observed,
                "first {first}, {substate:?}"
            );
        }
    }

    #[test]
    fn an_unseen_session_refuses_its_scale() {
        let session = Session::open(ActiveMode::Espresso, Observed::Unseen);
        assert_eq!(session.weight_refused(), Some(Blocked::BeganUnseen));
        let session = Session::open(ActiveMode::Espresso, Observed::FromStart);
        assert_eq!(session.weight_refused(), None);
    }

    #[test]
    fn a_state_in_the_same_mode_keeps_the_session_across_a_link_change() {
        let seen = Session::open(ActiveMode::Espresso, Observed::FromStart);
        let next = Session::on_state(
            Some(seen),
            info(MachineState::Espresso, SubState::Pouring),
            true,
        )
        .0;
        assert_eq!(next, Some(seen));
    }

    #[test]
    fn a_state_with_no_mode_closes_the_session() {
        let seen = Session::open(ActiveMode::Espresso, Observed::FromStart);
        let next =
            Session::on_state(Some(seen), info(MachineState::Idle, SubState::Ready), false).0;
        assert_eq!(next, None);
    }

    #[test]
    fn a_first_state_in_another_mode_opens_that_mode_unseen() {
        let seen = Session::open(ActiveMode::Espresso, Observed::FromStart);
        let next = Session::on_state(
            Some(seen),
            info(MachineState::HotWater, SubState::Pouring),
            true,
        )
        .0;
        assert_eq!(
            next,
            Some(Session::open(ActiveMode::HotWater, Observed::Unseen))
        );
    }

    #[test]
    fn a_later_state_opens_a_session_seen() {
        let next =
            Session::on_state(None, info(MachineState::Espresso, SubState::Pouring), false).0;
        assert_eq!(
            next,
            Some(Session::open(ActiveMode::Espresso, Observed::FromStart))
        );
    }

    #[test]
    fn a_pre_flow_state_on_return_is_a_new_pour() {
        let mut seen = Session::open(ActiveMode::Espresso, Observed::FromStart);
        seen.baseline = Baseline::Tared;
        let (next, change) = Session::on_state(
            Some(seen),
            info(MachineState::Espresso, SubState::Heating),
            true,
        );
        assert_eq!(change, SessionChange::Replaced);
        assert_eq!(
            next,
            Some(Session::open(ActiveMode::Espresso, Observed::FromStart))
        );
        // Not on the same link: a heating substate mid-session is just that.
        let (_, change) = Session::on_state(
            Some(seen),
            info(MachineState::Espresso, SubState::Heating),
            false,
        );
        assert_eq!(change, SessionChange::Kept);
    }

    #[test]
    fn a_session_first_seen_winding_down_is_honoured() {
        let (next, change) =
            Session::on_state(None, info(MachineState::Espresso, SubState::Ending), true);
        assert_eq!(change, SessionChange::Opened);
        let session = next.expect("a session");
        assert_eq!(session.observed, Observed::Unseen);
        assert_eq!(session.stop, StopState::Honoured { cause: None });
    }

    #[test]
    fn only_ending_puffing_and_paused_steam_wind_down() {
        assert!(is_wind_down(SubState::Ending));
        assert!(is_wind_down(SubState::Puffing));
        assert!(is_wind_down(SubState::PausedSteam));
        assert!(!is_wind_down(SubState::Steaming));
        assert!(!is_wind_down(SubState::Pouring));
        assert!(!is_wind_down(SubState::Unknown));
    }
}

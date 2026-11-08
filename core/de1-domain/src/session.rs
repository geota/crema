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
    /// A returning scale read outside what the cup could hold: it re-zeroed,
    /// or something landed on it.
    ScaleRezeroed,
    /// Something pressed on, landed on or was lifted off the scale mid-flow.
    ScaleDisturbed,
    /// Auto-tare is off and the scale read over 5 g when the session opened:
    /// whatever was on it can't be told from coffee.
    ScaleNotTared,
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
            Blocked::ScaleRezeroed => "scale-rezeroed",
            Blocked::ScaleDisturbed => "scale-disturbed",
            Blocked::ScaleNotTared => "scale-not-tared",
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
            Blocked::ScaleRezeroed => "Scale re-zeroed",
            Blocked::ScaleDisturbed => "Scale disturbed. Stop this shot by hand.",
            Blocked::ScaleNotTared => "Scale wasn't tared — weight stop off for this shot",
        }
    }

    /// The note while the estimated leg finishes the session in the weight
    /// leg's place (`DESIGN.md` §3.5, §6).
    #[must_use]
    pub const fn estimating_note(self) -> &'static str {
        match self {
            Blocked::ScaleRezeroed => "Estimating, scale re-zeroed",
            Blocked::ScaleDisturbed => "Estimating, scale disturbed",
            _ => "Estimating, scale stopped reporting",
        }
    }

    /// Every reason, for the shells' tests.
    pub const ALL: [Blocked; 8] = [
        Blocked::NoScale,
        Blocked::ScaleSilent,
        Blocked::BeganUnseen,
        Blocked::UntaredCup,
        Blocked::ScaleLost,
        Blocked::ScaleRezeroed,
        Blocked::ScaleDisturbed,
        Blocked::ScaleNotTared,
    ];
}

/// The last weight the session trusted, paired with the pour volume of a
/// sample under 1 s old (`DESIGN.md` §3.4). The estimated leg counts the rest
/// of the pour from it.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct LastTrusted {
    /// The raw net reading, grams: never the smoothed estimate.
    pub weight_g: f32,
    /// The pour volume at that reading, ml.
    pub pour_ml: f32,
    /// When it was read.
    pub at: Duration,
}

/// Weight at or over this, with scale flow at or over [`GATE_FLOW_G_S`],
/// held for [`GATE_HOLD`], says water has reached the cup. Provisional.
pub const GATE_WEIGHT_G: f32 = 5.0;
/// The gate's scale flow, g/s. Provisional.
pub const GATE_FLOW_G_S: f32 = 0.5;
/// How long the gate's condition must hold, so a bump can't open it.
pub const GATE_HOLD: Duration = Duration::from_secs(1);
/// With auto-tare off, a scale reading over this when a session opens refuses
/// the weight stop for that session: the user's zero has something on it.
pub const UNTARED_AT_OPEN_G: f32 = 5.0;

/// A trusted reading under this means the cup is empty again: `last` can't
/// ride back to it.
pub const EMPTY_CUP_G: f32 = 3.0;

/// What the scale's zero means for a session.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Baseline {
    /// Tare pending, or no reading yet.
    Unknown,
    /// The zero is good: the tare landed or timed out, auto-tare is off, or
    /// the untared-cup guard latched a virtual zero. Nothing measured in the
    /// cup yet.
    Tared,
    /// Flow has reached the cup: the gate held.
    Trusted(LastTrusted),
    /// The scale's readings can't be trusted for this session.
    Refused {
        /// Why.
        why: Blocked,
        /// The last trusted weight, if flow had reached the cup: the
        /// estimate finishes from it.
        last: Option<LastTrusted>,
    },
}

impl Baseline {
    /// The last trusted weight, if the gate has opened.
    #[must_use]
    pub const fn last(self) -> Option<LastTrusted> {
        match self {
            Baseline::Trusted(last) => Some(last),
            Baseline::Refused { last, .. } => last,
            Baseline::Unknown | Baseline::Tared => None,
        }
    }
}

/// One flow, from its first state until the machine leaves that mode.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Session {
    /// The kind of flow.
    pub mode: ActiveMode,
    /// Whether the app saw it begin.
    pub observed: Observed,
    /// What the scale's zero means for it.
    pub baseline: Baseline,
    /// Where its stop stands.
    pub stop: StopState,
    /// The machine has reported this session past its pre-flow substates.
    pub flowed: bool,
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
                last: None,
            },
        };
        Session {
            mode,
            observed,
            baseline,
            stop: StopState::Armed,
            flowed: false,
        }
    }

    /// Open a session in `mode` on its first state, `substate`: one first
    /// seen winding down is already honoured.
    #[must_use]
    pub fn open_on(mode: ActiveMode, first_on_link: bool, substate: SubState) -> Session {
        let mut session = Session::open(mode, Observed::on_open(first_on_link, substate));
        session.flowed = !is_pre_flow(substate);
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
            // Flow can't go backwards: an espresso session that has flowed
            // and reports a pre-flow substate is a new shot, on any report.
            // A lost Idle report would otherwise leave the old shot's owed
            // stop firing at the new one. Steam's heating and pauses recur.
            Some(session)
                if session.mode == mode
                    && mode == ActiveMode::Espresso
                    && session.flowed
                    && is_pre_flow(info.substate) =>
            {
                (
                    Some(Session::open(mode, Observed::FromStart)),
                    SessionChange::Replaced,
                )
            }
            Some(mut session) if session.mode == mode => {
                session.flowed |= !is_pre_flow(info.substate);
                (Some(session), SessionChange::Kept)
            }
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
            Baseline::Refused { why, .. } => Some(why),
            Baseline::Unknown | Baseline::Tared | Baseline::Trusted(_) => None,
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
                last: None,
            };
        }
    }

    /// The gate held, or a trusted reading refreshed `last`: `Tared` or
    /// `Trusted` becomes `Trusted(last)`.
    pub fn trusted(&mut self, last: LastTrusted) {
        if matches!(self.baseline, Baseline::Tared | Baseline::Trusted(_)) {
            self.baseline = Baseline::Trusted(last);
        }
    }

    /// A trusted reading under [`EMPTY_CUP_G`]: back to `Tared`, so `last`
    /// can't ride back to an empty cup.
    pub fn emptied(&mut self) {
        if matches!(self.baseline, Baseline::Trusted(_)) {
            self.baseline = Baseline::Tared;
        }
    }

    /// The scale can't speak for the cup for the rest of the session: it
    /// re-zeroed on return, or was disturbed. A trusted session keeps its
    /// `last`, so the estimate finishes; a tared one keeps nothing.
    pub fn refuse(&mut self, why: Blocked) {
        self.baseline = match self.baseline {
            Baseline::Trusted(last) => Baseline::Refused {
                why,
                last: Some(last),
            },
            Baseline::Tared | Baseline::Unknown => Baseline::Refused { why, last: None },
            refused @ Baseline::Refused { .. } => refused,
        };
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
        assert_eq!(
            next,
            Some(Session {
                flowed: true,
                ..seen
            })
        );
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
            Some(Session {
                flowed: true,
                ..Session::open(ActiveMode::HotWater, Observed::Unseen)
            })
        );
    }

    #[test]
    fn a_later_state_opens_a_session_seen() {
        let next =
            Session::on_state(None, info(MachineState::Espresso, SubState::Pouring), false).0;
        assert_eq!(
            next,
            Some(Session {
                flowed: true,
                ..Session::open(ActiveMode::Espresso, Observed::FromStart)
            })
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

    #[test]
    fn an_espresso_session_that_flowed_is_replaced_by_a_pre_flow_report_on_any_link() {
        let (pouring, _) =
            Session::on_state(None, info(MachineState::Espresso, SubState::Pouring), false);
        let (next, change) = Session::on_state(
            pouring,
            info(MachineState::Espresso, SubState::Heating),
            false,
        );
        assert_eq!(change, SessionChange::Replaced);
        assert_eq!(next.map(|s| s.flowed), Some(false));
        // Before it flowed, heating is just heating.
        let (heating, _) =
            Session::on_state(None, info(MachineState::Espresso, SubState::Heating), false);
        let (_, change) = Session::on_state(
            heating,
            info(MachineState::Espresso, SubState::Stabilising),
            false,
        );
        assert_eq!(change, SessionChange::Kept);
        // Steam's pauses and heating recur within one session.
        let (steaming, _) =
            Session::on_state(None, info(MachineState::Steam, SubState::Steaming), false);
        let (_, change) = Session::on_state(
            steaming,
            info(MachineState::Steam, SubState::Heating),
            false,
        );
        assert_eq!(change, SessionChange::Kept);
    }
}

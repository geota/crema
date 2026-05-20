//! The steam session state machine — observing a steam session, eco mode, and
//! steam-clog detection.
//!
//! [`SteamMonitor`] is the sibling of [`ShotMonitor`](crate::ShotMonitor) and
//! [`WaterMonitor`](crate::WaterMonitor): where they track the DE1's `Espresso`
//! and `HotWater` / `HotWaterRinse` states, `SteamMonitor` tracks the `Steam`
//! state. It is sans-IO — feed it the DE1's [`StateInfo`] notifications and
//! [`ShotSample`] telemetry (each with a monotonic timestamp from the shell),
//! and drive its time-based logic from [`on_tick`](SteamMonitor::on_tick).
//!
//! It implements three legacy steam behaviours (`machine.tcl`, `gui.tcl`):
//!
//! 1. **Session tracking** — times the steam session and records its steam
//!    pressure / temperature telemetry, emitting [`SteamEvent::Started`] and
//!    [`SteamEvent::Completed`].
//! 2. **Eco mode** — after the machine has been idle (not steaming) for
//!    [`STEAM_ECO_DELAY`], the steam target temperature should drop to a lower
//!    eco temperature; resuming a steam session disengages it. Modelled as a
//!    time-driven transition on [`on_tick`](SteamMonitor::on_tick), which emits
//!    [`SteamEvent::EcoModeChanged`].
//! 3. **Steam-clog detection** — at the end of a session the recorded steam
//!    pressure / temperature series is analysed (`check_if_steam_clogged`,
//!    `machine.tcl:1171-1226`); a clogged steam wand runs hot and
//!    over-pressure, so a [`SteamEvent::ClogSuspected`] is emitted when too
//!    many samples exceed the thresholds.
//!
//! The legacy **two-tap steam stop** (first tap → gentle puffing, second tap →
//! end + purge) is handled entirely by the DE1 firmware: the first tap moves
//! the machine into the `Steam` / [`SubState::Puffing`] substate and the second
//! ends the session. The core observes this purely as a substate change on the
//! DE1's `StateInfo` stream — there is no domain logic to model — so
//! `SteamMonitor` exposes [`is_puffing`](SteamMonitor::is_puffing) for the
//! shell and leaves the tap handling to the firmware.

use std::time::Duration;

use de1_protocol::{MachineState, ShotSample, StateInfo, SubState};
use typeshare::typeshare;

use crate::session::SessionTimer;

/// Hard cap on the number of steam telemetry samples retained for one session.
///
/// A session ends when the DE1 leaves the `Steam` state — an untrusted signal
/// from the peer — so without a cap a buggy or hostile DE1 could stream
/// samples indefinitely and grow the heap without bound. The cap is sized far
/// beyond any real session: ~1 hour of telemetry at 10 Hz. A real steam
/// session is at most a couple of minutes, so a genuine session is never
/// truncated; once the cap is reached further samples are simply dropped (no
/// panic, no reallocation).
pub const MAX_STEAM_SAMPLES: usize = 36_000;

/// How long the machine must be idle (not steaming) before eco mode engages
/// — the legacy `steam_eco_delay_seconds` default of 600 s (`machine.tcl:34`).
pub const STEAM_ECO_DELAY: Duration = Duration::from_secs(600);

/// Number of recorded samples skipped at the start of a session before clog
/// analysis — steam pressure is high by design at the start. The legacy
/// (`machine.tcl:1199`) deletes sample indices `0..=20` — 21 samples (~2.1 s
/// at 10 Hz); its own comment says "20", but the index list deletes one more.
/// This matches the actual legacy behaviour.
pub const STEAM_CLOG_TRIM_SAMPLES: usize = 21;

/// Minimum recorded sample count for clog analysis to run. A shorter session
/// is treated as a brief purge and skipped (`machine.tcl:1173`).
pub const STEAM_CLOG_MIN_SAMPLES: usize = 30;

/// `clog_analysis` slices `samples[STEAM_CLOG_TRIM_SAMPLES..]` only after the
/// `len() >= STEAM_CLOG_MIN_SAMPLES` early return, so the slice is panic-free
/// only while the minimum is at least the trim count. Enforce that invariant
/// at compile time.
const _: () = assert!(STEAM_CLOG_MIN_SAMPLES >= STEAM_CLOG_TRIM_SAMPLES);

/// Group pressure above which a steam sample counts as over-pressure, bar —
/// the legacy `steam_over_pressure_threshold` (`machine.tcl:1195`).
pub const STEAM_OVER_PRESSURE_THRESHOLD_BAR: f32 = 8.0;

/// Steam temperature above which a steam sample counts as over-temperature, °C
/// — the legacy `steam_over_temp_threshold` (`machine.tcl:1186`).
pub const STEAM_OVER_TEMP_THRESHOLD_C: f32 = 180.0;

/// How many over-pressure samples trip the clog warning — the legacy
/// `steam_over_pressure_count_trigger` default (`machine.tcl:261`).
pub const STEAM_OVER_PRESSURE_COUNT_TRIGGER: usize = 10;

/// How many over-temperature samples trip the clog warning — the legacy
/// `steam_over_temp_count_trigger` default (`machine.tcl:265`).
pub const STEAM_OVER_TEMP_COUNT_TRIGGER: usize = 10;

/// Why a steam-clog warning was raised — which threshold the telemetry tripped.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub enum SteamClogReason {
    /// Steam pressure ran too high for too long — a clogged steam wand.
    OverPressure,
    /// Steam temperature ran too hot for too long.
    OverTemperature,
}

/// A telemetry sample recorded during a steam session: the two values the
/// legacy `check_if_steam_clogged` analyses.
#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct SteamSample {
    /// Group pressure, bar.
    pub pressure: f32,
    /// Steam heater temperature, °C.
    pub steam_temp: f32,
}

/// A completed steam session — its duration and recorded steam telemetry.
#[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize)]
pub struct SteamRecord {
    /// Total session duration.
    pub duration: Duration,
    /// Every steam telemetry sample collected during the session, in order.
    pub samples: Vec<SteamSample>,
}

impl SteamRecord {
    /// Analyse the recorded steam telemetry for a clogged steam wand.
    ///
    /// Mirrors the legacy `check_if_steam_clogged` (`machine.tcl:1171-1226`):
    /// sessions shorter than [`STEAM_CLOG_MIN_SAMPLES`] are treated as a brief
    /// purge and skipped; otherwise the first [`STEAM_CLOG_TRIM_SAMPLES`]
    /// samples are trimmed (pressure is high by design at session start) and
    /// the remainder is scanned. Over-pressure is checked before
    /// over-temperature, matching the legacy precedence.
    ///
    /// Returns the reason a clog is suspected, or `None` if the session looks
    /// healthy or is too short to judge.
    pub fn clog_analysis(&self) -> Option<SteamClogReason> {
        if self.samples.len() < STEAM_CLOG_MIN_SAMPLES {
            return None;
        }
        let trimmed = &self.samples[STEAM_CLOG_TRIM_SAMPLES..];

        let over_pressure = trimmed
            .iter()
            .filter(|s| s.pressure > STEAM_OVER_PRESSURE_THRESHOLD_BAR)
            .count();
        if over_pressure > STEAM_OVER_PRESSURE_COUNT_TRIGGER {
            return Some(SteamClogReason::OverPressure);
        }

        let over_temp = trimmed
            .iter()
            .filter(|s| s.steam_temp > STEAM_OVER_TEMP_THRESHOLD_C)
            .count();
        if over_temp > STEAM_OVER_TEMP_COUNT_TRIGGER {
            return Some(SteamClogReason::OverTemperature);
        }

        None
    }
}

/// A notable change observed by a [`SteamMonitor`].
#[derive(Debug, Clone, PartialEq)]
pub enum SteamEvent {
    /// A steam session has begun (the DE1 entered the `Steam` state).
    Started,
    /// The steam session has finished; carries the completed record.
    Completed(SteamRecord),
    /// The just-finished steam session's telemetry suggests a clogged steam
    /// wand. Always emitted alongside the [`SteamEvent::Completed`] for the
    /// same session.
    ClogSuspected(SteamClogReason),
    /// Eco mode engaged or disengaged. `true` means the steam target should
    /// drop to the lower eco temperature; `false` means it returns to normal.
    EcoModeChanged(bool),
}

/// In-progress steam session accumulation. The session timing lives in a
/// shared [`SessionTimer`]; this holds only what is steam-specific.
#[derive(Debug, Default)]
struct SteamInProgress {
    timer: SessionTimer,
    samples: Vec<SteamSample>,
}

/// Observes the DE1's notification stream and tracks one steam session at a
/// time, plus the time-driven eco-mode state.
///
/// Sans-IO: it holds no clock and performs no I/O. The shell supplies a
/// monotonic `now` with each notification and each [`on_tick`](Self::on_tick).
#[derive(Debug)]
pub struct SteamMonitor {
    /// The session currently being recorded, if any.
    session: Option<SteamInProgress>,
    /// Whether the DE1 is in the `Puffing` substate (gentle-puff stage of the
    /// two-tap stop). Tracked for the shell; the firmware drives the taps.
    puffing: bool,
    /// Whether eco mode is currently engaged.
    eco_mode: bool,
    /// Timestamp the machine was last steaming (session start or end), against
    /// which the eco-mode idle delay is measured. `None` until the first
    /// session, the eco timer being un-armed before any steaming.
    last_steam: Option<Duration>,
    /// Eco-mode idle delay — [`STEAM_ECO_DELAY`] by default; configurable via
    /// [`with_eco_delay`](Self::with_eco_delay).
    eco_delay: Duration,
    /// Whether eco mode is enabled at all (the legacy `eco_steam` setting).
    eco_enabled: bool,
}

impl Default for SteamMonitor {
    fn default() -> SteamMonitor {
        SteamMonitor::new()
    }
}

impl SteamMonitor {
    /// Create a monitor in the idle state, with eco mode disabled.
    pub fn new() -> SteamMonitor {
        SteamMonitor {
            session: None,
            puffing: false,
            eco_mode: false,
            last_steam: None,
            eco_delay: STEAM_ECO_DELAY,
            eco_enabled: false,
        }
    }

    /// Enable or disable eco mode (the legacy `eco_steam` setting). Disabling
    /// it while eco mode is engaged disengages it on the next
    /// [`on_tick`](Self::on_tick); [`on_eco_enabled`](Self::on_eco_enabled)
    /// returns the event directly.
    pub fn on_eco_enabled(&mut self, enabled: bool, now: Duration) -> Vec<SteamEvent> {
        let was_enabled = self.eco_enabled;
        self.eco_enabled = enabled;
        let mut events = Vec::new();
        if !enabled && self.eco_mode {
            self.eco_mode = false;
            events.push(SteamEvent::EcoModeChanged(false));
        }
        // Re-arm the idle clock so a freshly enabled eco mode counts its delay
        // from now, not from a stale earlier timestamp. Only on the off->on
        // edge: re-arming while eco is already enabled would silently push the
        // pending eco transition further out.
        if enabled && !was_enabled {
            self.last_steam = Some(now);
        }
        events
    }

    /// Override the eco-mode idle delay. The default is [`STEAM_ECO_DELAY`].
    pub fn with_eco_delay(mut self, delay: Duration) -> SteamMonitor {
        self.eco_delay = delay;
        self
    }

    /// Whether a steam session is currently in progress.
    pub fn is_session_in_progress(&self) -> bool {
        self.session.is_some()
    }

    /// Whether the DE1 is in the gentle-puffing stage of the two-tap stop.
    pub fn is_puffing(&self) -> bool {
        self.puffing
    }

    /// Whether eco mode is currently engaged (the steam target should be at
    /// the lower eco temperature).
    pub fn is_eco_mode(&self) -> bool {
        self.eco_mode
    }

    /// Feed a [`StateInfo`] notification. `now` is a monotonic timestamp
    /// supplied by the shell (a [`Duration`] from a shell-chosen epoch).
    ///
    /// Returns the events this update produced — empty if nothing notable
    /// changed. A session ending runs clog analysis, so the returned vector
    /// may carry a [`SteamEvent::ClogSuspected`] after the
    /// [`SteamEvent::Completed`].
    pub fn on_state_info(&mut self, info: StateInfo, now: Duration) -> Vec<SteamEvent> {
        let mut events = Vec::new();
        let in_steam = info.state == MachineState::Steam;
        self.puffing = in_steam && info.substate == SubState::Puffing;

        // A session ends when the machine leaves the Steam state.
        if !in_steam && let Some(mut session) = self.session.take() {
            let record = SteamRecord {
                duration: session.timer.finish(now).unwrap_or(Duration::ZERO),
                samples: session.samples,
            };
            let clog = record.clog_analysis();
            events.push(SteamEvent::Completed(record));
            if let Some(reason) = clog {
                events.push(SteamEvent::ClogSuspected(reason));
            }
            self.last_steam = Some(now);
        }

        // A session starts when the machine enters the Steam state. Steaming
        // is activity, so it disengages eco mode and re-arms the idle clock.
        if in_steam && self.session.is_none() {
            let mut session = SteamInProgress::default();
            session.timer.start(now);
            self.session = Some(session);
            self.last_steam = Some(now);
            events.push(SteamEvent::Started);
            if self.eco_mode {
                self.eco_mode = false;
                events.push(SteamEvent::EcoModeChanged(false));
            }
        }
        events
    }

    /// Feed a [`ShotSample`] telemetry notification. The steam pressure and
    /// temperature are recorded only while a steam session is in progress;
    /// other samples are ignored.
    pub fn on_sample(&mut self, sample: &ShotSample, _now: Duration) {
        if let Some(session) = &mut self.session {
            // Drop samples past the cap so an over-long stream cannot grow the
            // heap without bound; see [`MAX_STEAM_SAMPLES`].
            if session.samples.len() < MAX_STEAM_SAMPLES {
                session.samples.push(SteamSample {
                    pressure: sample.group_pressure,
                    steam_temp: sample.steam_temp,
                });
            }
        }
    }

    /// Feed a periodic clock tick. Drives the eco-mode transition: when eco
    /// mode is enabled and the machine has been idle (not steaming) for the
    /// eco delay, eco mode engages and a [`SteamEvent::EcoModeChanged(true)`]
    /// is emitted once.
    ///
    /// Returns the events this tick produced — empty if nothing changed.
    pub fn on_tick(&mut self, now: Duration) -> Vec<SteamEvent> {
        let mut events = Vec::new();
        if self.eco_enabled
            && !self.eco_mode
            && self.session.is_none()
            && let Some(last) = self.last_steam
            && now.saturating_sub(last) >= self.eco_delay
        {
            self.eco_mode = true;
            events.push(SteamEvent::EcoModeChanged(true));
        }
        events
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Helper: a `Duration` of `ms` milliseconds.
    fn ms(ms: u64) -> Duration {
        Duration::from_millis(ms)
    }

    fn state(state: MachineState, substate: SubState) -> StateInfo {
        StateInfo { state, substate }
    }

    /// A telemetry sample carrying the given steam pressure and temperature;
    /// other fields zeroed.
    fn steam_sample(pressure: f32, steam_temp: f32) -> ShotSample {
        ShotSample {
            sample_time: 0,
            group_pressure: pressure,
            group_flow: 0.0,
            head_temp: 0.0,
            mix_temp: 0.0,
            set_mix_temp: 0.0,
            set_head_temp: 0.0,
            set_group_pressure: 0.0,
            set_group_flow: 0.0,
            frame_number: 0,
            steam_temp,
        }
    }

    /// Extract the `SteamRecord` from a `Completed` event in `events`.
    fn completed_record(events: &[SteamEvent]) -> SteamRecord {
        events
            .iter()
            .find_map(|event| match event {
                SteamEvent::Completed(record) => Some(record.clone()),
                _ => None,
            })
            .expect("a Completed event")
    }

    #[test]
    fn entering_steam_starts_a_session() {
        let mut monitor = SteamMonitor::new();
        let events = monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(1_000));
        assert_eq!(events, vec![SteamEvent::Started]);
        assert!(monitor.is_session_in_progress());
    }

    #[test]
    fn a_repeated_steam_state_does_not_re_start_the_session() {
        let mut monitor = SteamMonitor::new();
        monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(1_000));
        let events = monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(2_000));
        assert!(events.is_empty());
        assert!(monitor.is_session_in_progress());
    }

    #[test]
    fn a_full_steam_session_yields_a_record_with_duration() {
        let mut monitor = SteamMonitor::new();
        monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(1_000));
        let done = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(9_500));
        let record = completed_record(&done);
        assert_eq!(record.duration, ms(8_500)); // 9500 - 1000
        assert!(!monitor.is_session_in_progress());
    }

    #[test]
    fn samples_are_recorded_only_during_a_session() {
        let mut monitor = SteamMonitor::new();
        // No session yet: this sample is dropped.
        monitor.on_sample(&steam_sample(2.0, 150.0), ms(500));
        monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(1_000));
        monitor.on_sample(&steam_sample(3.0, 155.0), ms(1_100));
        monitor.on_sample(&steam_sample(4.0, 160.0), ms(1_200));
        let done = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(2_000));
        let record = completed_record(&done);
        assert_eq!(record.samples.len(), 2);
        assert_eq!(record.samples[0].pressure, 3.0);
        assert_eq!(record.samples[1].steam_temp, 160.0);
    }

    #[test]
    fn the_puffing_substate_is_tracked_for_the_two_tap_stop() {
        let mut monitor = SteamMonitor::new();
        monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(1_000));
        assert!(!monitor.is_puffing());
        monitor.on_state_info(state(MachineState::Steam, SubState::Puffing), ms(2_000));
        assert!(monitor.is_puffing());
        // Leaving the Steam state clears the puffing flag.
        monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(3_000));
        assert!(!monitor.is_puffing());
    }

    #[test]
    fn the_sample_buffer_is_capped() {
        let mut monitor = SteamMonitor::new();
        monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(0));
        // Feed well past the cap: the excess samples are dropped, not retained.
        for _ in 0..MAX_STEAM_SAMPLES + 100 {
            monitor.on_sample(&steam_sample(2.0, 150.0), ms(100));
        }
        let done = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(1_000));
        let record = completed_record(&done);
        assert_eq!(record.samples.len(), MAX_STEAM_SAMPLES);
    }

    /// A `SteamRecord` of `count` samples all carrying `pressure` and `temp`.
    fn record_of(count: usize, pressure: f32, temp: f32) -> SteamRecord {
        SteamRecord {
            duration: Duration::ZERO,
            samples: vec![
                SteamSample {
                    pressure,
                    steam_temp: temp,
                };
                count
            ],
        }
    }

    #[test]
    fn a_short_session_is_not_clog_analysed() {
        // Fewer than the minimum sample count: treated as a brief purge.
        let record = record_of(STEAM_CLOG_MIN_SAMPLES - 1, 12.0, 200.0);
        assert_eq!(record.clog_analysis(), None);
    }

    #[test]
    fn a_healthy_session_reports_no_clog() {
        let record = record_of(60, 2.0, 150.0);
        assert_eq!(record.clog_analysis(), None);
    }

    #[test]
    fn sustained_over_pressure_reports_a_clog() {
        // STEAM_CLOG_TRIM_SAMPLES trimmed, then 11 over-pressure samples —
        // one past the trigger of 10.
        let mut samples = vec![
            SteamSample {
                pressure: 2.0,
                steam_temp: 150.0,
            };
            STEAM_CLOG_TRIM_SAMPLES
        ];
        samples.extend(vec![
            SteamSample {
                pressure: 12.0,
                steam_temp: 150.0,
            };
            STEAM_OVER_PRESSURE_COUNT_TRIGGER + 1
        ]);
        let record = SteamRecord {
            duration: Duration::ZERO,
            samples,
        };
        assert_eq!(record.clog_analysis(), Some(SteamClogReason::OverPressure));
    }

    #[test]
    fn the_first_trimmed_samples_are_ignored_for_clogging() {
        // High pressure only in the trimmed start region: not a clog.
        let mut samples = vec![
            SteamSample {
                pressure: 12.0,
                steam_temp: 150.0,
            };
            STEAM_CLOG_TRIM_SAMPLES
        ];
        samples.extend(vec![
            SteamSample {
                pressure: 2.0,
                steam_temp: 150.0,
            };
            40
        ]);
        let record = SteamRecord {
            duration: Duration::ZERO,
            samples,
        };
        assert_eq!(record.clog_analysis(), None);
    }

    #[test]
    fn just_at_the_pressure_trigger_is_not_a_clog() {
        // Exactly the trigger count, not "more than": no warning.
        let mut samples = vec![
            SteamSample {
                pressure: 2.0,
                steam_temp: 150.0,
            };
            STEAM_CLOG_TRIM_SAMPLES
        ];
        samples.extend(vec![
            SteamSample {
                pressure: 12.0,
                steam_temp: 150.0,
            };
            STEAM_OVER_PRESSURE_COUNT_TRIGGER
        ]);
        let record = SteamRecord {
            duration: Duration::ZERO,
            samples,
        };
        assert_eq!(record.clog_analysis(), None);
    }

    #[test]
    fn sustained_over_temperature_reports_a_clog() {
        let mut samples = vec![
            SteamSample {
                pressure: 2.0,
                steam_temp: 150.0,
            };
            STEAM_CLOG_TRIM_SAMPLES
        ];
        samples.extend(vec![
            SteamSample {
                pressure: 2.0,
                steam_temp: 200.0,
            };
            STEAM_OVER_TEMP_COUNT_TRIGGER + 1
        ]);
        let record = SteamRecord {
            duration: Duration::ZERO,
            samples,
        };
        assert_eq!(
            record.clog_analysis(),
            Some(SteamClogReason::OverTemperature)
        );
    }

    #[test]
    fn a_completed_clogged_session_emits_clog_suspected() {
        let mut monitor = SteamMonitor::new();
        monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(0));
        // 20 trimmed startup samples, then a sustained over-pressure run.
        for _ in 0..STEAM_CLOG_TRIM_SAMPLES {
            monitor.on_sample(&steam_sample(2.0, 150.0), ms(0));
        }
        for _ in 0..STEAM_OVER_PRESSURE_COUNT_TRIGGER + 1 {
            monitor.on_sample(&steam_sample(12.0, 150.0), ms(0));
        }
        let events = monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(5_000));
        assert!(events.contains(&SteamEvent::ClogSuspected(SteamClogReason::OverPressure)));
    }

    #[test]
    fn eco_mode_engages_after_the_idle_delay() {
        let mut monitor = SteamMonitor::new().with_eco_delay(Duration::from_secs(600));
        monitor.on_eco_enabled(true, ms(0));
        // Before the delay: no change.
        assert!(monitor.on_tick(ms(599_000)).is_empty());
        assert!(!monitor.is_eco_mode());
        // At the delay: eco mode engages.
        let events = monitor.on_tick(ms(600_000));
        assert_eq!(events, vec![SteamEvent::EcoModeChanged(true)]);
        assert!(monitor.is_eco_mode());
        // It engages only once.
        assert!(monitor.on_tick(ms(700_000)).is_empty());
    }

    #[test]
    fn eco_mode_does_not_engage_when_disabled() {
        let mut monitor = SteamMonitor::new().with_eco_delay(Duration::from_secs(600));
        // eco mode never enabled.
        assert!(monitor.on_tick(ms(10_000_000)).is_empty());
        assert!(!monitor.is_eco_mode());
    }

    #[test]
    fn steaming_disengages_eco_mode() {
        let mut monitor = SteamMonitor::new().with_eco_delay(Duration::from_secs(600));
        monitor.on_eco_enabled(true, ms(0));
        monitor.on_tick(ms(600_000));
        assert!(monitor.is_eco_mode());
        // A steam session is activity: it disengages eco mode.
        let events = monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(601_000));
        assert!(events.contains(&SteamEvent::EcoModeChanged(false)));
        assert!(!monitor.is_eco_mode());
    }

    #[test]
    fn eco_mode_re_engages_after_a_session_and_a_fresh_idle_delay() {
        let mut monitor = SteamMonitor::new().with_eco_delay(Duration::from_secs(600));
        monitor.on_eco_enabled(true, ms(0));
        // Steam at t = 100 s, ending at t = 110 s: the idle clock restarts.
        monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(100_000));
        monitor.on_state_info(state(MachineState::Idle, SubState::Ready), ms(110_000));
        // 600 s after the session ended, eco mode engages again.
        assert!(monitor.on_tick(ms(700_000)).is_empty());
        let events = monitor.on_tick(ms(710_000));
        assert_eq!(events, vec![SteamEvent::EcoModeChanged(true)]);
    }

    #[test]
    fn disabling_eco_mode_disengages_it() {
        let mut monitor = SteamMonitor::new().with_eco_delay(Duration::from_secs(600));
        monitor.on_eco_enabled(true, ms(0));
        monitor.on_tick(ms(600_000));
        assert!(monitor.is_eco_mode());
        let events = monitor.on_eco_enabled(false, ms(700_000));
        assert_eq!(events, vec![SteamEvent::EcoModeChanged(false)]);
        assert!(!monitor.is_eco_mode());
    }

    #[test]
    fn re_enabling_eco_mode_does_not_delay_a_pending_transition() {
        let mut monitor = SteamMonitor::new().with_eco_delay(Duration::from_secs(600));
        monitor.on_eco_enabled(true, ms(0));
        // A redundant enable partway through the idle delay must not re-arm
        // the idle clock and push the transition out.
        monitor.on_eco_enabled(true, ms(300_000));
        // Eco still engages 600 s after the *original* enable, not 900 s.
        let events = monitor.on_tick(ms(600_000));
        assert_eq!(events, vec![SteamEvent::EcoModeChanged(true)]);
    }

    #[test]
    fn eco_mode_does_not_engage_during_an_active_session() {
        let mut monitor = SteamMonitor::new().with_eco_delay(Duration::from_secs(600));
        monitor.on_eco_enabled(true, ms(0));
        monitor.on_state_info(state(MachineState::Steam, SubState::Steaming), ms(1_000));
        // A very long session: eco mode must not engage while steaming.
        assert!(monitor.on_tick(ms(10_000_000)).is_empty());
        assert!(!monitor.is_eco_mode());
    }
}

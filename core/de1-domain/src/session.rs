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

use std::time::Duration;

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

#[cfg(test)]
mod tests {
    use super::*;

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
}

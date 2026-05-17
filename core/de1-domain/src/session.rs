//! A lightweight session-timing core shared by the monitors.
//!
//! [`ShotMonitor`](crate::ShotMonitor), [`WaterMonitor`](crate::WaterMonitor)
//! and [`SteamMonitor`](crate::SteamMonitor) each track a session whose
//! lifecycle is the same skeleton: it begins when the DE1 enters a state,
//! holds a `started_ms`, and on leaving the state yields the elapsed duration.
//!
//! [`SessionTimer`] captures *only* that skeleton — the start timestamp and
//! the `now_ms.saturating_sub(started_ms)` duration arithmetic. The monitors
//! genuinely diverge in what else they accumulate (telemetry samples, profile
//! frames, eco mode, clog analysis), so this stays deliberately small: it owns
//! the timing, the monitors own everything else.

/// Tracks the start time of one in-progress session at a time.
///
/// Sans-IO: it holds no clock. The caller supplies a monotonic `now_ms`.
#[derive(Debug, Default)]
pub struct SessionTimer {
    started_ms: Option<u64>,
}

impl SessionTimer {
    /// Create an idle timer.
    pub fn new() -> SessionTimer {
        SessionTimer::default()
    }

    /// Whether a session is currently being timed.
    pub fn is_running(&self) -> bool {
        self.started_ms.is_some()
    }

    /// Start timing a session at `now_ms`, if one is not already running.
    /// Returns `true` when a new session was started.
    pub fn start(&mut self, now_ms: u64) -> bool {
        if self.started_ms.is_some() {
            return false;
        }
        self.started_ms = Some(now_ms);
        true
    }

    /// Elapsed time since the running session started, milliseconds — `0` if
    /// no session is running. Saturating: a non-monotonic `now_ms` yields `0`.
    pub fn elapsed_ms(&self, now_ms: u64) -> u64 {
        self.started_ms
            .map_or(0, |start| now_ms.saturating_sub(start))
    }

    /// Finish the running session, if any, returning its total duration in
    /// milliseconds. Returns `None` when no session was running.
    pub fn finish(&mut self, now_ms: u64) -> Option<u64> {
        let started_ms = self.started_ms.take()?;
        Some(now_ms.saturating_sub(started_ms))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_fresh_timer_is_not_running() {
        let timer = SessionTimer::new();
        assert!(!timer.is_running());
        assert_eq!(timer.elapsed_ms(1_000), 0);
    }

    #[test]
    fn start_begins_a_session_once() {
        let mut timer = SessionTimer::new();
        assert!(timer.start(1_000));
        assert!(timer.is_running());
        // A second start while running is a no-op.
        assert!(!timer.start(2_000));
    }

    #[test]
    fn elapsed_is_measured_from_the_start() {
        let mut timer = SessionTimer::new();
        timer.start(1_000);
        assert_eq!(timer.elapsed_ms(3_500), 2_500);
    }

    #[test]
    fn finish_yields_the_duration_and_clears_the_timer() {
        let mut timer = SessionTimer::new();
        timer.start(1_000);
        assert_eq!(timer.finish(9_500), Some(8_500));
        assert!(!timer.is_running());
        // Finishing again with nothing running yields None.
        assert_eq!(timer.finish(10_000), None);
    }

    #[test]
    fn a_non_monotonic_now_saturates_to_zero() {
        let mut timer = SessionTimer::new();
        timer.start(5_000);
        assert_eq!(timer.elapsed_ms(1_000), 0);
        assert_eq!(timer.finish(1_000), Some(0));
    }
}

//! Float seconds to [`Duration`], total over hostile input.
//!
//! `Duration::from_secs_f32` panics on a non-finite value **and** on any
//! finite value too large for a `Duration` (about 1.8e19 s). Seconds reach
//! the core from imported files, restored backups and persisted settings, so
//! a bare call is a crash waiting for one bad number (cohort review
//! 2026-10-25, report 01 P1-1 / P1-2). Every float-seconds conversion on an
//! untrusted path goes through one of these helpers instead: a non-finite or
//! negative value reads as zero, and a finite value is clamped to a domain
//! ceiling the caller names.

use std::time::Duration;

/// Ceiling for a machine timeout written over MMR (flush timeout, espresso
/// warm-up timeout): one hour. Far above any real DE1 setting; it only
/// bounds the conversion. The FFI / wasm warm-up setters and the connect
/// sweep share it so the two paths cannot disagree.
pub const MAX_MACHINE_TIMEOUT_S: f32 = 60.0 * 60.0;

/// Ceiling for a time read from an imported shot (`elapsed[i]`,
/// `meta.time`): one day. No real shot runs that long.
pub const MAX_IMPORTED_SHOT_TIME_S: f32 = 24.0 * 60.0 * 60.0;

/// `seconds` as a [`Duration`] clamped into `0..=max_s`. NaN, infinities
/// and negatives read as zero. Never panics.
#[must_use]
pub fn secs_clamped(seconds: f32, max_s: f32) -> Duration {
    if !seconds.is_finite() {
        return Duration::ZERO;
    }
    // `f32::max` ignores a NaN operand, so `ceiling` is finite-or-inf and
    // never below zero — `clamp` cannot panic on it.
    let ceiling = max_s.max(0.0);
    Duration::try_from_secs_f32(seconds.clamp(0.0, ceiling)).unwrap_or(Duration::ZERO)
}

/// [`secs_clamped`], rounded to whole milliseconds first. The rounding
/// compensates f32 error so 5.1 s becomes 5100 ms (a bare conversion gives
/// 5099.999… ms, which the deciseconds wire encoding truncates to 50
/// instead of 51). Never panics.
#[must_use]
pub fn secs_clamped_whole_ms(seconds: f32, max_s: f32) -> Duration {
    if !seconds.is_finite() {
        return Duration::ZERO;
    }
    let ceiling_ms = max_s.max(0.0) * 1000.0;
    let ms = (seconds * 1000.0).round().clamp(0.0, ceiling_ms);
    // Widen to f64: `from_secs_f64` rounds to the nearest nanosecond, which
    // recovers the integer millisecond exactly.
    Duration::try_from_secs_f64(f64::from(ms) / 1000.0).unwrap_or(Duration::ZERO)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn non_finite_and_negative_read_as_zero() {
        for f in [secs_clamped, secs_clamped_whole_ms] {
            assert_eq!(f(f32::NAN, 10.0), Duration::ZERO);
            assert_eq!(f(f32::INFINITY, 10.0), Duration::ZERO);
            assert_eq!(f(f32::NEG_INFINITY, 10.0), Duration::ZERO);
            assert_eq!(f(-3.0, 10.0), Duration::ZERO);
        }
    }

    #[test]
    fn huge_finite_values_clamp_instead_of_panicking() {
        // The reviewer's trigger: `Duration::from_secs_f32(1e30)` panics.
        assert_eq!(secs_clamped(1e30, 60.0), Duration::from_secs(60));
        assert_eq!(secs_clamped_whole_ms(1e30, 60.0), Duration::from_secs(60));
        assert_eq!(
            secs_clamped(f32::MAX, MAX_IMPORTED_SHOT_TIME_S),
            Duration::from_secs(86_400)
        );
        // A degenerate ceiling still never panics.
        assert_eq!(secs_clamped(5.0, f32::NAN), Duration::ZERO);
        assert_eq!(secs_clamped(5.0, -1.0), Duration::ZERO);
        assert_eq!(secs_clamped(1e30, f32::INFINITY), Duration::ZERO);
        assert_eq!(secs_clamped_whole_ms(1e30, f32::MAX), Duration::ZERO);
    }

    #[test]
    fn in_range_values_pass_through() {
        assert_eq!(secs_clamped(2.5, 60.0), Duration::from_millis(2500));
        assert_eq!(
            secs_clamped_whole_ms(5.1, 60.0),
            Duration::from_millis(5100)
        );
        assert_eq!(secs_clamped_whole_ms(3.0, 60.0), Duration::from_secs(3));
    }
}

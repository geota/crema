//! Roast-classification helpers for the **current bean** — the bag of
//! coffee being pulled.
//!
//! Three pure helpers shared by every shell:
//!
//! - [`roast_band`] — classify a 1..10 roast level into a named band
//!   (`Light` / `Medium` / `Dark`), the same band the Visualizer v2
//!   `.shot.json` `bean.roast_level` field encodes.
//! - [`days_off_roast`] — whole days between an ISO `yyyy-mm-dd` roast
//!   date and a "now" timestamp, computed on UTC calendar days so the
//!   readout is stable across the day.
//! - [`roast_freshness`] — rate that day count against the ideal rest
//!   window for its band, the verdict (`Best` / `Ok` / `Bad`) that
//!   drives the bean card's status dot.
//!
//! Per `docs/26-shell-to-core-audit.md` push #5/#9, this module covers
//! the pure classification helpers only. A typed bean shape and the
//! profile-roast inference (`roastFromProfile`) are explicitly
//! deferred until Android starts modelling beans.

use serde::{Deserialize, Serialize};

/// A coffee roast level, categorized into a named band — the same scale
/// Visualizer's v2 `.shot.json` `bean.roast_level` field uses (1..10 with
/// 1 = lightest, 10 = darkest, in three equal-ish bands). Serializes as
/// the lowercase wire spelling (`"light"` / `"medium"` / `"dark"`) to
/// match the shell's `Roast` type alias and the v2 JSON contract.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RoastBand {
    /// Light roast — 1..3 on the Visualizer scale.
    Light,
    /// Medium roast — 4..6 on the Visualizer scale.
    Medium,
    /// Dark roast — 7..10 on the Visualizer scale.
    Dark,
}

impl RoastBand {
    /// The lowercase wire string for this band (`"light"` / `"medium"` /
    /// `"dark"`) — the same spelling the shell's `Roast` type alias uses.
    pub fn as_str(self) -> &'static str {
        match self {
            RoastBand::Light => "light",
            RoastBand::Medium => "medium",
            RoastBand::Dark => "dark",
        }
    }
}

/// A bean's rest verdict against the ideal degas window for its
/// [`RoastBand`]. Serializes as lowercase (`"best"` / `"ok"` / `"bad"`),
/// matching the shell's `Freshness` type alias.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum RoastFreshness {
    /// Inside the green window — the best part of the bean's life.
    Best,
    /// Still drinkable but outside the green window — too gassy below it,
    /// fading above it.
    Ok,
    /// Past the rest window — the bean is stale.
    Bad,
}

impl RoastFreshness {
    /// The lowercase wire string for this verdict.
    pub fn as_str(self) -> &'static str {
        match self {
            RoastFreshness::Best => "best",
            RoastFreshness::Ok => "ok",
            RoastFreshness::Bad => "bad",
        }
    }
}

/// Per-band rest windows, in days off roast:
/// - `green` is the `[low, high]` best window;
/// - `ok_high` is the upper bound of the still-drinkable fading window —
///   past it the bean is stale.
///
/// Below the green window the bean also only rates `ok` (still too
/// gassy / unstable). The windows differ because degassing tracks bean
/// density: darker beans are porous and degas fast (earliest, shortest
/// window); light roasts are dense, hold CO₂ longest, and need the most
/// rest.
struct RestWindow {
    green: (i64, i64),
    ok_high: i64,
}

/// Dark — best 4–10, ok 0–3 / 11–14, bad 15+.
const DARK_WINDOW: RestWindow = RestWindow {
    green: (4, 10),
    ok_high: 14,
};
/// Medium — best 6–14, ok 0–5 / 15–21, bad 22+.
const MEDIUM_WINDOW: RestWindow = RestWindow {
    green: (6, 14),
    ok_high: 21,
};
/// Light — best 10–24, ok 0–9 / 25–35, bad 36+.
const LIGHT_WINDOW: RestWindow = RestWindow {
    green: (10, 24),
    ok_high: 35,
};

/// Milliseconds per UTC day. Calendar days are not constant length under
/// DST, but the days-off-roast math is done in UTC, where every day is
/// exactly 86 400 000 ms.
const MS_PER_DAY: i64 = 86_400_000;

/// Classify a 1..10 roast level into a named [`RoastBand`]:
///
/// - `1..=3` → [`RoastBand::Light`]
/// - `4..=6` → [`RoastBand::Medium`]
/// - `7..=10` → [`RoastBand::Dark`]
///
/// Values outside 1..10 are clamped into range first (negative levels
/// read as Light; large levels read as Dark). The 1..10 scale is the
/// Visualizer v2 `bean.roast_level` contract.
pub fn roast_band(level: i32) -> RoastBand {
    let clamped = level.clamp(1, 10);
    if clamped <= 3 {
        RoastBand::Light
    } else if clamped <= 6 {
        RoastBand::Medium
    } else {
        RoastBand::Dark
    }
}

/// Whole calendar days (UTC) between an ISO `yyyy-mm-dd` roast date and
/// the `now_unix_ms` reference time — the bean's "days off roast".
///
/// Returns `None` when the date string is malformed (not 10 chars in
/// `yyyy-mm-dd` shape, or non-numeric / out-of-range parts). The result
/// is clamped at `0` so a future-dated roast never reads negative.
///
/// The arithmetic is done on the calendar day only (UTC midnight of
/// each day), so a shot pulled at any time of day reports a stable
/// integer — matching the shell implementation.
///
/// `now_unix_ms` is a parameter rather than a wall-clock read so the
/// core stays sans-IO; the shell passes `Date.now()` at the call site.
pub fn days_off_roast(roasted_on: &str, now_unix_ms: i64) -> Option<i64> {
    let roast_unix_ms = parse_iso_date_to_unix_ms(roasted_on)?;
    // Floor-divide `now_unix_ms` to the UTC midnight of "today" so the
    // result is the integer day count regardless of the time of day
    // `now` is read at. Rust's integer `div_euclid` floors toward
    // negative infinity, matching the JS `Math.floor` semantics.
    let today_unix_ms = now_unix_ms.div_euclid(MS_PER_DAY) * MS_PER_DAY;
    let days = (today_unix_ms - roast_unix_ms).div_euclid(MS_PER_DAY);
    Some(days.max(0))
}

/// Rate how a bean's `days` off roast sits against the ideal rest window
/// for its `band` — the bean card's status dot.
///
/// - [`RoastFreshness::Best`] inside the band's green window;
/// - [`RoastFreshness::Bad`] past the `ok_high` ceiling;
/// - [`RoastFreshness::Ok`] either side in between (still too gassy
///   below the green window, fading above it).
///
/// Days are clamped at 0 (negative day counts cannot reach the green
/// window and read as `Ok`).
pub fn roast_freshness(band: RoastBand, days: i64) -> RoastFreshness {
    let w = match band {
        RoastBand::Light => LIGHT_WINDOW,
        RoastBand::Medium => MEDIUM_WINDOW,
        RoastBand::Dark => DARK_WINDOW,
    };
    if days >= w.green.0 && days <= w.green.1 {
        RoastFreshness::Best
    } else if days > w.ok_high {
        RoastFreshness::Bad
    } else {
        RoastFreshness::Ok
    }
}

/// Parse an ISO `yyyy-mm-dd` date string into Unix-epoch milliseconds at
/// UTC midnight. Returns `None` on a malformed string — anything that
/// is not exactly 10 ASCII characters in `dddd-dd-dd` shape, with parts
/// that are not in calendar range.
///
/// Calendar validation is deliberately coarse (month 1..=12,
/// day-of-month 1..=31) — refusing 2024-02-30 is not the job of this
/// parser; the caller's source data is the bean editor, which already
/// constrains input via a `<input type="date">`. A grossly-invalid
/// string still reports `None` rather than panicking.
fn parse_iso_date_to_unix_ms(s: &str) -> Option<i64> {
    let bytes = s.as_bytes();
    if bytes.len() != 10 || bytes[4] != b'-' || bytes[7] != b'-' {
        return None;
    }
    let year: i32 = s.get(0..4)?.parse().ok()?;
    let month: u32 = s.get(5..7)?.parse().ok()?;
    let day: u32 = s.get(8..10)?.parse().ok()?;
    if !(1..=12).contains(&month) || !(1..=31).contains(&day) {
        return None;
    }
    days_from_civil(year, month, day).map(|d| d * MS_PER_DAY)
}

/// Convert a (year, month, day) UTC date to whole days since the Unix
/// epoch (1970-01-01 UTC), using Howard Hinnant's `days_from_civil`
/// algorithm — branch-free and exact for any Gregorian date in the
/// `i32` year range.
///
/// Returns `None` on overflow (the multiplication / addition stays
/// within `i64` for any plausible roast date, but the helper is
/// defensive in case the caller passes a year far outside the
/// representable range).
fn days_from_civil(y: i32, m: u32, d: u32) -> Option<i64> {
    // Hinnant's algorithm: shift March 1 to be the first day of the
    // "year" so leap-day arithmetic is uniform.
    let y = i64::from(if m <= 2 { y - 1 } else { y });
    let m = i64::from(m);
    let d = i64::from(d);
    let era = if y >= 0 { y } else { y - 399 }.div_euclid(400);
    let yoe = y - era * 400; // [0, 399]
    let doy = (153 * (m + if m > 2 { -3 } else { 9 }) + 2) / 5 + d - 1; // [0, 365]
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy; // [0, 146096]
    // Days since 0000-03-01, then shift the epoch to 1970-01-01.
    era.checked_mul(146_097)?
        .checked_add(doe)?
        .checked_sub(719_468)
}

#[cfg(test)]
mod tests {
    use super::*;

    // -- roast_band ---------------------------------------------------

    #[test]
    fn roast_band_classifies_named_thresholds() {
        assert_eq!(roast_band(1), RoastBand::Light);
        assert_eq!(roast_band(3), RoastBand::Light);
        assert_eq!(roast_band(4), RoastBand::Medium);
        assert_eq!(roast_band(5), RoastBand::Medium);
        assert_eq!(roast_band(6), RoastBand::Medium);
        assert_eq!(roast_band(7), RoastBand::Dark);
        assert_eq!(roast_band(10), RoastBand::Dark);
    }

    #[test]
    fn roast_band_clamps_out_of_range() {
        assert_eq!(roast_band(0), RoastBand::Light);
        assert_eq!(roast_band(-5), RoastBand::Light);
        assert_eq!(roast_band(11), RoastBand::Dark);
        assert_eq!(roast_band(999), RoastBand::Dark);
    }

    #[test]
    fn roast_band_serializes_lowercase() {
        assert_eq!(
            serde_json::to_string(&RoastBand::Light).unwrap(),
            "\"light\""
        );
        assert_eq!(
            serde_json::to_string(&RoastBand::Medium).unwrap(),
            "\"medium\""
        );
        assert_eq!(
            serde_json::to_string(&RoastBand::Dark).unwrap(),
            "\"dark\""
        );
    }

    // -- days_off_roast -----------------------------------------------

    /// `2026-05-22T12:00:00Z` — a stable reference "now" for the tests.
    const NOW: i64 = 1_779_451_200_000;
    /// `2026-05-22T00:00:00Z` — the UTC-midnight of the reference now.
    const TODAY_MIDNIGHT: i64 = 1_779_408_000_000;

    #[test]
    fn days_off_roast_zero_today() {
        assert_eq!(days_off_roast("2026-05-22", NOW), Some(0));
    }

    #[test]
    fn days_off_roast_handles_time_of_day_consistently() {
        // Same calendar day, any time of day → same answer.
        assert_eq!(days_off_roast("2026-05-22", TODAY_MIDNIGHT), Some(0));
        // 23:59:59 UTC on the same day still reads 0.
        assert_eq!(
            days_off_roast("2026-05-22", TODAY_MIDNIGHT + (86_400_000 - 1)),
            Some(0)
        );
    }

    #[test]
    fn days_off_roast_counts_whole_days() {
        // 7 days earlier reads as 7.
        assert_eq!(days_off_roast("2026-05-15", NOW), Some(7));
        // 1 day earlier reads as 1.
        assert_eq!(days_off_roast("2026-05-21", NOW), Some(1));
    }

    #[test]
    fn days_off_roast_clamps_future_dates_to_zero() {
        // A future roast date never reads negative.
        assert_eq!(days_off_roast("2026-06-01", NOW), Some(0));
        assert_eq!(days_off_roast("2099-01-01", NOW), Some(0));
    }

    #[test]
    fn days_off_roast_crosses_year_boundary() {
        // 2026-05-22 vs 2025-05-22 → 365 days (2025 is not a leap year).
        assert_eq!(days_off_roast("2025-05-22", NOW), Some(365));
        // Across a leap day: 2024-02-28 → 2024-03-01 is 2 days.
        let mar_1_2024 = days_from_civil(2024, 3, 1).unwrap() * MS_PER_DAY;
        assert_eq!(days_off_roast("2024-02-28", mar_1_2024), Some(2));
    }

    #[test]
    fn days_off_roast_rejects_malformed_strings() {
        assert_eq!(days_off_roast("", NOW), None);
        assert_eq!(days_off_roast("not-a-date", NOW), None);
        assert_eq!(days_off_roast("2026/05/22", NOW), None);
        assert_eq!(days_off_roast("2026-5-22", NOW), None); // single-digit month
        assert_eq!(days_off_roast("2026-13-01", NOW), None); // month out of range
        assert_eq!(days_off_roast("2026-05-32", NOW), None); // day out of range
        assert_eq!(days_off_roast("20260522xx", NOW), None); // shape mismatch
    }

    // -- roast_freshness ----------------------------------------------

    #[test]
    fn roast_freshness_inside_green_window_is_best() {
        // Dark: green = [4, 10].
        assert_eq!(roast_freshness(RoastBand::Dark, 4), RoastFreshness::Best);
        assert_eq!(roast_freshness(RoastBand::Dark, 7), RoastFreshness::Best);
        assert_eq!(roast_freshness(RoastBand::Dark, 10), RoastFreshness::Best);
        // Medium: green = [6, 14].
        assert_eq!(roast_freshness(RoastBand::Medium, 6), RoastFreshness::Best);
        assert_eq!(roast_freshness(RoastBand::Medium, 14), RoastFreshness::Best);
        // Light: green = [10, 24].
        assert_eq!(roast_freshness(RoastBand::Light, 10), RoastFreshness::Best);
        assert_eq!(roast_freshness(RoastBand::Light, 24), RoastFreshness::Best);
    }

    #[test]
    fn roast_freshness_below_green_window_is_ok() {
        assert_eq!(roast_freshness(RoastBand::Dark, 0), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Dark, 3), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Medium, 5), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Light, 9), RoastFreshness::Ok);
    }

    #[test]
    fn roast_freshness_above_green_within_ok_is_ok() {
        // Dark: ok_high = 14.
        assert_eq!(roast_freshness(RoastBand::Dark, 11), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Dark, 14), RoastFreshness::Ok);
        // Medium: ok_high = 21.
        assert_eq!(roast_freshness(RoastBand::Medium, 15), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Medium, 21), RoastFreshness::Ok);
        // Light: ok_high = 35.
        assert_eq!(roast_freshness(RoastBand::Light, 25), RoastFreshness::Ok);
        assert_eq!(roast_freshness(RoastBand::Light, 35), RoastFreshness::Ok);
    }

    #[test]
    fn roast_freshness_past_ok_window_is_bad() {
        assert_eq!(roast_freshness(RoastBand::Dark, 15), RoastFreshness::Bad);
        assert_eq!(roast_freshness(RoastBand::Medium, 22), RoastFreshness::Bad);
        assert_eq!(roast_freshness(RoastBand::Light, 36), RoastFreshness::Bad);
        assert_eq!(roast_freshness(RoastBand::Light, 365), RoastFreshness::Bad);
    }

    #[test]
    fn roast_freshness_serializes_lowercase() {
        assert_eq!(
            serde_json::to_string(&RoastFreshness::Best).unwrap(),
            "\"best\""
        );
        assert_eq!(
            serde_json::to_string(&RoastFreshness::Ok).unwrap(),
            "\"ok\""
        );
        assert_eq!(
            serde_json::to_string(&RoastFreshness::Bad).unwrap(),
            "\"bad\""
        );
    }

    // -- days_from_civil sanity --------------------------------------

    #[test]
    fn days_from_civil_matches_known_unix_epoch_dates() {
        // 1970-01-01 → 0 (the Unix epoch).
        assert_eq!(days_from_civil(1970, 1, 1), Some(0));
        // 1970-01-02 → 1.
        assert_eq!(days_from_civil(1970, 1, 2), Some(1));
        // 2000-01-01 → 10957.
        assert_eq!(days_from_civil(2000, 1, 1), Some(10_957));
        // 2024-02-29 (leap day) is valid.
        assert!(days_from_civil(2024, 2, 29).is_some());
    }
}

//! Pure derivation of the Settings → Water maintenance readouts
//! (filter capacity %, litres since descale, hours since clean).
//!
//! The DE1 has no cumulative water-volume counter; the shell integrates
//! `group_flow × Δt` into a persisted `total_litres` field. From that
//! one counter plus three user-set intervals and three "last
//! performed" timestamps, this module derives the seven readout
//! fields the UI shows. Both shells call the same fn so the maths
//! cannot drift between web and Android.
//!
//! Persistence stays shell-side (the baselines, intervals, and
//! v1→v2 migration are shell concerns); only the derivation moves.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

/// The persisted maintenance state — counters, baselines, and
/// user-set intervals. Mirrors the TS `MaintenanceState`.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MaintenanceState {
    /// Total litres of water dispensed, ever — the integrated flow counter.
    pub total_litres: f64,
    /// `total_litres` at the last filter clean.
    pub filter_baseline_litres: f64,
    /// `total_litres` at the last descale.
    pub descale_baseline_litres: f64,
    /// Unix epoch ms of the last clean cycle.
    #[typeshare(serialized_as = "I64")]
    pub clean_at_ms: i64,
    /// Unix epoch ms of the last filter clean.
    #[typeshare(serialized_as = "I64")]
    pub filter_at_ms: i64,
    /// Unix epoch ms of the last descale.
    #[typeshare(serialized_as = "I64")]
    pub descale_at_ms: i64,
    /// Filter clean-interval threshold, litres.
    pub filter_capacity_litres: f64,
    /// Descale interval, litres.
    pub descale_interval_litres: f64,
    /// Clean cycle interval, hours.
    pub clean_interval_hours: f64,
}

/// A derived maintenance readout — counters, percentages, and due/ok
/// verdicts. Mirrors the TS `MaintenanceReadout`.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MaintenanceReadout {
    /// Litres since the last filter change.
    pub filter_used_litres: f64,
    /// Filter capacity remaining, 0–100 %.
    pub filter_percent: f64,
    /// Whether the filter still has usable capacity.
    pub filter_ok: bool,
    /// Litres dispensed since the last descale.
    pub descale_since_litres: f64,
    /// Whether the descale interval has not yet been exceeded.
    pub descale_ok: bool,
    /// Whole hours since the last clean cycle.
    #[typeshare(serialized_as = "I64")]
    pub clean_since_hours: i64,
    /// Whether the clean interval has not yet been exceeded.
    pub clean_ok: bool,
}

/// Derive a [`MaintenanceReadout`] from the persisted state. `now_ms`
/// is the wall-clock unix-epoch ms used to compute "hours since clean".
#[must_use]
pub fn maintenance_readout(state: &MaintenanceState, now_ms: i64) -> MaintenanceReadout {
    let filter_used_litres = (state.total_litres - state.filter_baseline_litres).max(0.0);
    let filter_percent = if state.filter_capacity_litres > 0.0 {
        (100.0 - (filter_used_litres / state.filter_capacity_litres) * 100.0).clamp(0.0, 100.0)
    } else {
        0.0
    };
    let descale_since_litres = (state.total_litres - state.descale_baseline_litres).max(0.0);
    // 3_600_000 ms per hour — saturating sub keeps a clock skewed into the
    // past from producing a negative count.
    let clean_since_hours = ((now_ms.saturating_sub(state.clean_at_ms)).max(0) / 3_600_000).max(0);
    // Compare hours-against-hours in f64 — the i64 → f64 cast costs no
    // precision at the magnitudes involved (~ten-thousand hours = a
    // career of espresso) and avoids inventing a parallel i64 interval
    // path. Sentinel: a non-finite interval lands as `false` for
    // `clean_ok` via NaN-poisoning the comparison.
    #[allow(clippy::cast_precision_loss)]
    let clean_since_hours_f = clean_since_hours as f64;
    MaintenanceReadout {
        filter_used_litres,
        filter_percent,
        filter_ok: filter_used_litres < state.filter_capacity_litres,
        descale_since_litres,
        descale_ok: descale_since_litres < state.descale_interval_litres,
        clean_since_hours,
        clean_ok: clean_since_hours_f < state.clean_interval_hours,
    }
}

/// JSON-in / JSON-out adapter for the wasm + uniffi bridges.
///
/// # Errors
///
/// Returns the JSON parse error string when `state_json` doesn't
/// deserialise into a `MaintenanceState`.
pub fn maintenance_readout_json(state_json: &str, now_ms: i64) -> Result<String, String> {
    let state: MaintenanceState = serde_json::from_str(state_json).map_err(|e| e.to_string())?;
    let out = maintenance_readout(&state, now_ms);
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fresh_state() -> MaintenanceState {
        MaintenanceState {
            total_litres: 0.0,
            filter_baseline_litres: 0.0,
            descale_baseline_litres: 0.0,
            clean_at_ms: 1_700_000_000_000,
            filter_at_ms: 1_700_000_000_000,
            descale_at_ms: 1_700_000_000_000,
            filter_capacity_litres: 50.0,
            descale_interval_litres: 120.0,
            clean_interval_hours: 48.0,
        }
    }

    #[test]
    fn fresh_state_at_baseline_is_all_ok() {
        let r = maintenance_readout(&fresh_state(), 1_700_000_000_000);
        assert_eq!(r.filter_used_litres, 0.0);
        assert_eq!(r.filter_percent, 100.0);
        assert!(r.filter_ok);
        assert_eq!(r.descale_since_litres, 0.0);
        assert!(r.descale_ok);
        assert_eq!(r.clean_since_hours, 0);
        assert!(r.clean_ok);
    }

    #[test]
    fn filter_percent_drops_linearly_with_usage() {
        let mut s = fresh_state();
        s.total_litres = 25.0;
        let r = maintenance_readout(&s, 1_700_000_000_000);
        assert_eq!(r.filter_used_litres, 25.0);
        assert_eq!(r.filter_percent, 50.0);
        assert!(r.filter_ok);
    }

    #[test]
    fn filter_clamps_at_zero_percent_when_over_capacity() {
        let mut s = fresh_state();
        s.total_litres = 75.0; // 1.5× capacity
        let r = maintenance_readout(&s, 1_700_000_000_000);
        assert_eq!(r.filter_percent, 0.0);
        assert!(!r.filter_ok);
    }

    #[test]
    fn clean_since_hours_is_whole_hours() {
        let now = 1_700_000_000_000_i64;
        let mut s = fresh_state();
        // 5h30m → 5 hours floor.
        s.clean_at_ms = now - 5 * 3_600_000 - 1_800_000;
        let r = maintenance_readout(&s, now);
        assert_eq!(r.clean_since_hours, 5);
        assert!(r.clean_ok);
    }

    #[test]
    fn clean_due_once_interval_exceeded() {
        let now = 1_700_000_000_000_i64;
        let mut s = fresh_state();
        s.clean_at_ms = now - 49 * 3_600_000;
        let r = maintenance_readout(&s, now);
        assert_eq!(r.clean_since_hours, 49);
        assert!(!r.clean_ok);
    }

    #[test]
    fn descale_due_once_interval_litres_exceeded() {
        let mut s = fresh_state();
        s.total_litres = 121.0;
        let r = maintenance_readout(&s, 1_700_000_000_000);
        assert_eq!(r.descale_since_litres, 121.0);
        assert!(!r.descale_ok);
    }

    #[test]
    fn descale_baseline_offsets_descale_counter() {
        // A descale at 80 L baseline + 30 L since → 30 L counted, well
        // inside the 120 L interval.
        let mut s = fresh_state();
        s.total_litres = 110.0;
        s.descale_baseline_litres = 80.0;
        let r = maintenance_readout(&s, 1_700_000_000_000);
        assert_eq!(r.descale_since_litres, 30.0);
        assert!(r.descale_ok);
    }

    #[test]
    fn negative_clock_skew_does_not_panic() {
        let mut s = fresh_state();
        s.clean_at_ms = 2_000_000_000_000;
        // `now` is earlier than `clean_at_ms` — clamp the count to 0.
        let r = maintenance_readout(&s, 1_700_000_000_000);
        assert_eq!(r.clean_since_hours, 0);
        assert!(r.clean_ok);
    }

    #[test]
    fn json_adapter_round_trips() {
        let s = fresh_state();
        let json = serde_json::to_string(&s).unwrap();
        let out = maintenance_readout_json(&json, 1_700_000_000_000).unwrap();
        let parsed: MaintenanceReadout = serde_json::from_str(&out).unwrap();
        assert_eq!(parsed.filter_percent, 100.0);
    }

    #[test]
    fn json_adapter_rejects_malformed_input() {
        assert!(maintenance_readout_json("not json", 0).is_err());
    }
}

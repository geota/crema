//! Non-espresso brew support — the Brew Log (issue #10).
//!
//! A "brew" is any prepared coffee: a machine shot, a manually logged
//! espresso, a V60, a cold brew. All of them persist as
//! [`StoredShot`](crate::StoredShot) rows; what distinguishes them is the
//! optional `brew_method` string (`None` = machine espresso, so every
//! pre-existing record is already valid) and, for guided sessions, an
//! optional weight-only [`BrewSeries`].
//!
//! This module owns the *pure* brew domain:
//!
//! - method-family classification ([`is_espresso_method`],
//!   [`normalize_brew_method`]),
//! - the method-aware ratio rule ([`ratio_for_method`]) — espresso speaks
//!   beverage-out (1:2), filter methods speak water-in (1:16),
//! - the method-aware History stat strip ([`brew_history_stats`]),
//! - the guided-brew data types ([`BrewSeries`], [`BrewRecipe`],
//!   [`BrewStep`]).
//!
//! The live session state machine lives in
//! [`brew_session`](crate::brew_session); this module stays data-only.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::history::brew_ratio;

// ── Method classification ────────────────────────────────────────────

/// Whether a `brew_method` value belongs to the espresso family.
///
/// `None`, the empty string, and `"espresso"` (any case / padding) are
/// espresso; everything else — including free-text methods — is a
/// filter/immersion-style brew. This single rule decides which ratio
/// semantics apply and which rows feed the espresso-scoped averages.
#[must_use]
pub fn is_espresso_method(method: Option<&str>) -> bool {
    match method {
        None => true,
        Some(m) => {
            let t = m.trim();
            t.is_empty() || t.eq_ignore_ascii_case("espresso")
        }
    }
}

/// Canonicalize a user- or import-supplied method string for storage:
/// trimmed, lowercased, inner whitespace runs collapsed to `_`. Returns
/// `None` for an effectively empty input. `"French Press"` →
/// `"french_press"`; `"  V60 "` → `"v60"`.
#[must_use]
pub fn normalize_brew_method(raw: &str) -> Option<String> {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return None;
    }
    let mut out = String::with_capacity(trimmed.len());
    let mut last_was_sep = false;
    for c in trimmed.chars() {
        if c.is_whitespace() || c == '_' || c == '-' {
            if !last_was_sep {
                out.push('_');
                last_was_sep = true;
            }
        } else {
            for lc in c.to_lowercase() {
                out.push(lc);
            }
            last_was_sep = false;
        }
    }
    let out = out.trim_matches('_').to_owned();
    if out.is_empty() { None } else { Some(out) }
}

/// The curated method presets, in the order the shells display them.
/// Storage accepts *any* normalized string — these are the ones that get
/// a chip and an icon. (Tea is deliberately absent: a BC tea brew still
/// imports, carrying its name as a free-text method.)
pub const BREW_METHOD_PRESETS: [&str; 10] = [
    "espresso",
    "pourover",
    "aeropress",
    "french_press",
    "moka",
    "cold_brew",
    "drip",
    "siphon",
    "clever",
    "other",
];

// ── Ratio ────────────────────────────────────────────────────────────

/// The method-aware brew ratio — the `N` in `1:N`.
///
/// Espresso-family rows divide beverage-out by dose
/// (`yield_g / dose`); everything else divides water-in by dose,
/// falling back to beverage-out when no water weight was recorded.
/// Delegates the arithmetic (and the non-finite / non-positive
/// guards) to [`brew_ratio`].
#[must_use]
pub fn ratio_for_method(
    method: Option<&str>,
    dose: Option<f32>,
    water_g: Option<f32>,
    yield_g: Option<f32>,
) -> Option<f32> {
    let dose = dose?;
    let numerator = if is_espresso_method(method) {
        yield_g?
    } else {
        water_g.filter(|w| w.is_finite() && *w > 0.0).or(yield_g)?
    };
    brew_ratio(dose, numerator)
}

// ── History stat strip, method-aware ─────────────────────────────────

/// One brew's inputs to the method-aware History summary strip — the
/// [`ShotStatInput`](crate::ShotStatInput) projection plus the two
/// fields that separate a pourover from a shot.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewStatInput {
    /// Total brew duration, milliseconds. `0` = not recorded (manual
    /// logs may omit time) — such rows are excluded from the time
    /// average rather than dragging it toward zero.
    #[typeshare(serialized_as = "I64")]
    pub duration_ms: u64,
    /// Final settled beverage weight, grams, or `None`.
    pub final_weight_g: Option<f32>,
    /// Peak scale weight, grams — the yield fallback.
    pub peak_weight_g: Option<f32>,
    /// Dry dose, grams.
    pub dose_g: Option<f32>,
    /// Water in, grams — set on filter/immersion brews.
    pub water_g: Option<f32>,
    /// Star rating 1..=5; `None` / 0 = unrated.
    pub rating: Option<u8>,
    /// The brew method; `None` = machine espresso.
    pub brew_method: Option<String>,
}

/// Summary metrics over a (filter/range-scoped) set of brews — the
/// History stat strip once non-espresso rows exist. `None` = "no data"
/// (render as "—").
///
/// Scope rule (spec §4): count, beans-used, and rating span every row;
/// the weight / ratio / time averages compute over one method family —
/// the whole set when it is single-method, espresso rows only when it
/// is mixed (`mixed_methods = true`, shells tag the tiles "esp").
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewHistoryStats {
    /// Number of brews in the set.
    pub count: u32,
    /// Total dry coffee consumed, grams (Σ dose over all rows) — the
    /// inventory number the Brew Log exists for.
    pub beans_used_g: Option<f32>,
    /// Mean beverage weight, grams, over the scoped rows.
    pub avg_weight_g: Option<f32>,
    /// Mean ratio over the scoped rows, in the scoped method's own
    /// semantics (espresso: yield ÷ dose; filter: water ÷ dose).
    pub avg_ratio: Option<f32>,
    /// Mean duration, seconds, over scoped rows that recorded a time.
    pub avg_time_s: Option<f32>,
    /// Mean star rating over rated rows (all methods).
    pub avg_rating: Option<f32>,
    /// `true` when the set spans more than one method family — the
    /// weight / ratio / time averages are then espresso-scoped and the
    /// shell tags those tiles.
    pub mixed_methods: bool,
    /// The single non-espresso method the whole set belongs to, when it
    /// does — lets the shell title the strip ("V60") and pick the
    /// water-in ratio label. `None` for espresso-only or mixed sets.
    pub scope_method: Option<String>,
}

/// Derive the method-aware History stat strip over `brews`.
// Display averages over ≤300 rows: f32 mantissa is plenty.
#[allow(clippy::cast_precision_loss)]
#[must_use]
pub fn brew_history_stats(brews: &[BrewStatInput]) -> BrewHistoryStats {
    let family = |b: &BrewStatInput| {
        if is_espresso_method(b.brew_method.as_deref()) {
            None
        } else {
            // Normalized-or-raw: inputs are stored normalized, but be
            // tolerant of foreign spellings.
            Some(
                normalize_brew_method(b.brew_method.as_deref().unwrap_or_default())
                    .unwrap_or_default(),
            )
        }
    };
    let mut families: Vec<Option<String>> = Vec::new();
    for b in brews {
        let f = family(b);
        if !families.contains(&f) {
            families.push(f);
        }
    }
    let mixed = families.len() > 1;
    let scope_family: Option<String> = if mixed {
        None // espresso-scoped
    } else {
        families.first().cloned().flatten()
    };
    let in_scope = |b: &BrewStatInput| family(b) == scope_family;

    let yield_of = |b: &BrewStatInput| {
        b.final_weight_g
            .or(b.peak_weight_g)
            .filter(|y| y.is_finite() && *y > 0.0)
    };
    let yields: Vec<f32> = brews
        .iter()
        .filter(|b| in_scope(b))
        .filter_map(yield_of)
        .collect();
    let ratios: Vec<f32> = brews
        .iter()
        .filter(|b| in_scope(b))
        .filter_map(|b| {
            ratio_for_method(b.brew_method.as_deref(), b.dose_g, b.water_g, yield_of(b))
        })
        .collect();
    let times: Vec<f32> = brews
        .iter()
        .filter(|b| in_scope(b) && b.duration_ms > 0)
        .map(|b| b.duration_ms as f32 / 1000.0)
        .collect();
    let ratings: Vec<f32> = brews
        .iter()
        .filter_map(|b| b.rating.filter(|r| *r > 0).map(f32::from))
        .collect();
    let doses: Vec<f32> = brews
        .iter()
        .filter_map(|b| b.dose_g.filter(|d| d.is_finite() && *d > 0.0))
        .collect();
    let mean = |v: &[f32]| {
        if v.is_empty() {
            None
        } else {
            Some(v.iter().sum::<f32>() / v.len() as f32)
        }
    };
    BrewHistoryStats {
        count: u32::try_from(brews.len()).unwrap_or(u32::MAX),
        beans_used_g: if doses.is_empty() {
            None
        } else {
            Some(doses.iter().sum())
        },
        avg_weight_g: mean(&yields),
        avg_ratio: mean(&ratios),
        avg_time_s: mean(&times),
        avg_rating: mean(&ratings),
        mixed_methods: mixed,
        scope_method: scope_family,
    }
}

// ── Guided-brew data types (Phase 2) ─────────────────────────────────

/// One sample of a guided brew's weight-only telemetry, ~4 Hz.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewSample {
    /// Milliseconds since the session clock started.
    #[typeshare(serialized_as = "I64")]
    pub elapsed_ms: u64,
    /// Net scale weight, grams.
    pub weight_g: f32,
    /// Estimated pour rate, g/s, when derivable.
    pub flow_g_s: Option<f32>,
}

/// A recipe-step boundary as it actually happened in a session.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct StageMark {
    /// Milliseconds since the session clock started.
    #[typeshare(serialized_as = "I64")]
    pub elapsed_ms: u64,
    /// Index into the recipe's `steps` of the step that *began* here.
    #[typeshare(serialized_as = "I64")]
    pub step_index: u64,
    /// The step's cumulative planned water target, grams, snapshotted
    /// from the recipe at this boundary (so the "planned vs poured"
    /// chart survives the recipe being edited, duplicated or deleted).
    /// `None` for timed steps (no target) and for older records.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_water_g: Option<f32>,
}

/// The weight-only telemetry of a guided brew session, persisted on
/// [`StoredShot::brew_series`](crate::StoredShot). Deliberately not a
/// [`TimedSample`](crate::TimedSample) series — that type's DE1
/// `ShotSample` is structurally required, and a pourover has none.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewSeries {
    pub samples: Vec<BrewSample>,
    /// Step boundaries as they actually happened (skips included).
    pub stage_marks: Vec<StageMark>,
}

/// What a [`BrewStep`] is, for its icon / default label. Lowercase wire
/// spelling, like [`BeverageType`](crate::BeverageType).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BrewStepKind {
    /// The first wetting pour, usually followed by a rest.
    Bloom,
    /// A pour to a cumulative weight target.
    #[default]
    Pour,
    /// A timed rest.
    Wait,
    /// A timed immersion rest (French press, clever).
    Steep,
    /// A timed stir.
    Stir,
    /// The plunge (AeroPress, French press).
    Press,
    /// The final drain — often open-ended ("until you tap").
    Drawdown,
    /// Anything else; carries its meaning in `label`.
    Other,
}

/// How a step ends during a guided session.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum StepAdvance {
    /// Advance when the target is met — a timed step's countdown
    /// reaching zero, or a pour step's weight target (scale present).
    /// Scale-less pour steps fall back to manual.
    #[default]
    Auto,
    /// Advance only on an explicit tap.
    Manual,
}

/// One step of a [`BrewRecipe`].
///
/// A step may carry a cumulative water target, a duration, or both
/// (bloom: pour to 45 g, rest until 0:45 *from step start*). A step
/// with neither is open-ended — it holds until tapped, regardless of
/// `advance`.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewStep {
    pub kind: BrewStepKind,
    /// Display label override; the shell derives one from `kind` when
    /// absent.
    pub label: Option<String>,
    /// Cumulative scale weight at which this step's pour is complete,
    /// grams.
    pub target_water_g: Option<f32>,
    /// Step duration, seconds, counted from step start.
    #[typeshare(serialized_as = "Option<I64>")]
    pub duration_s: Option<u64>,
    pub advance: StepAdvance,
}

/// A named multi-stage plan for a brew method. Followed by a human —
/// deliberately *not* a `Profile`, which is executed by the machine.
///
/// Shell-persisted (like beans); the core owns the shape and the
/// session engine that runs it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewRecipe {
    /// Stable id — `recipe:<uuid-v7>`.
    pub id: String,
    pub name: String,
    /// Normalized method string ([`normalize_brew_method`]).
    pub method: String,
    /// Planned dry dose, grams.
    pub dose_g: f32,
    /// Planned total water, grams.
    pub water_g: f32,
    #[serde(default)]
    pub temp_c: Option<f32>,
    #[serde(default)]
    pub steps: Vec<BrewStep>,
    #[serde(default)]
    pub notes: Option<String>,
    #[serde(default)]
    pub favourite: bool,
    #[typeshare(serialized_as = "I64")]
    pub created_at: i64,
    #[typeshare(serialized_as = "I64")]
    pub updated_at: i64,
    /// Soft-delete tombstone, Unix ms — same lifecycle as beans.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub deleted_at: Option<i64>,
}

impl BrewRecipe {
    /// The recipe's planned cumulative pour total — the largest step
    /// water target, when any step has one. The editor compares this
    /// against `water_g` for its "250 g planned · matches water" check.
    #[must_use]
    pub fn planned_pour_total_g(&self) -> Option<f32> {
        self.steps
            .iter()
            .filter_map(|s| s.target_water_g)
            .filter(|w| w.is_finite())
            .fold(None, |acc, w| Some(acc.map_or(w, |a: f32| a.max(w))))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── method classification ────────────────────────────────────

    #[test]
    fn espresso_family_covers_none_empty_and_any_case() {
        assert!(is_espresso_method(None));
        assert!(is_espresso_method(Some("")));
        assert!(is_espresso_method(Some("  ")));
        assert!(is_espresso_method(Some("espresso")));
        assert!(is_espresso_method(Some(" Espresso ")));
        assert!(!is_espresso_method(Some("pourover")));
        assert!(!is_espresso_method(Some("v60")));
    }

    #[test]
    fn normalize_collapses_case_and_separators() {
        assert_eq!(
            normalize_brew_method("French Press"),
            Some("french_press".to_owned())
        );
        assert_eq!(normalize_brew_method("  V60 "), Some("v60".to_owned()));
        assert_eq!(
            normalize_brew_method("Cold - Brew"),
            Some("cold_brew".to_owned())
        );
        assert_eq!(normalize_brew_method("   "), None);
        assert_eq!(normalize_brew_method("_"), None);
    }

    // ── ratio ────────────────────────────────────────────────────

    #[test]
    fn espresso_ratio_uses_yield() {
        let r = ratio_for_method(None, Some(18.0), None, Some(36.0));
        assert_eq!(r, Some(2.0));
        // Water is ignored for espresso even when present.
        let r = ratio_for_method(Some("espresso"), Some(18.0), Some(250.0), Some(36.0));
        assert_eq!(r, Some(2.0));
    }

    #[test]
    fn filter_ratio_uses_water_with_yield_fallback() {
        let r = ratio_for_method(Some("pourover"), Some(15.0), Some(250.0), Some(210.0));
        assert!((r.unwrap() - 250.0 / 15.0).abs() < 1e-4);
        // No water recorded → beverage weight stands in.
        let r = ratio_for_method(Some("pourover"), Some(15.0), None, Some(210.0));
        assert!((r.unwrap() - 14.0).abs() < 1e-4);
        // Neither → None.
        assert_eq!(
            ratio_for_method(Some("pourover"), Some(15.0), None, None),
            None
        );
    }

    // ── stats ────────────────────────────────────────────────────

    fn esp(duration_ms: u64, final_g: f32, dose: f32, rating: Option<u8>) -> BrewStatInput {
        BrewStatInput {
            duration_ms,
            final_weight_g: Some(final_g),
            dose_g: Some(dose),
            rating,
            ..BrewStatInput::default()
        }
    }

    fn brew(
        method: &str,
        duration_ms: u64,
        dose: f32,
        water: Option<f32>,
        rating: Option<u8>,
    ) -> BrewStatInput {
        BrewStatInput {
            duration_ms,
            dose_g: Some(dose),
            water_g: water,
            rating,
            brew_method: Some(method.to_owned()),
            ..BrewStatInput::default()
        }
    }

    #[test]
    fn espresso_only_set_matches_v1_semantics() {
        let s = brew_history_stats(&[
            esp(30_000, 36.0, 18.0, Some(4)),
            esp(20_000, 30.0, 15.0, None),
        ]);
        assert_eq!(s.count, 2);
        assert!(!s.mixed_methods);
        assert_eq!(s.scope_method, None);
        assert_eq!(s.beans_used_g, Some(33.0));
        assert_eq!(s.avg_weight_g, Some(33.0));
        assert_eq!(s.avg_ratio, Some(2.0));
        assert_eq!(s.avg_time_s, Some(25.0));
        assert_eq!(s.avg_rating, Some(4.0));
    }

    #[test]
    fn mixed_set_scopes_averages_to_espresso_but_counts_everything() {
        let s = brew_history_stats(&[
            esp(30_000, 36.0, 18.0, Some(4)),
            brew("pourover", 185_000, 15.0, Some(250.0), Some(5)),
        ]);
        assert_eq!(s.count, 2);
        assert!(s.mixed_methods);
        assert_eq!(s.scope_method, None);
        // Dose sums across methods — the inventory view.
        assert_eq!(s.beans_used_g, Some(33.0));
        // Averages stay espresso-scoped.
        assert_eq!(s.avg_weight_g, Some(36.0));
        assert_eq!(s.avg_ratio, Some(2.0));
        assert_eq!(s.avg_time_s, Some(30.0));
        // Rating spans all rows.
        assert_eq!(s.avg_rating, Some(4.5));
    }

    #[test]
    fn single_method_set_scopes_to_that_method() {
        let s = brew_history_stats(&[
            brew("pourover", 185_000, 15.0, Some(250.0), None),
            brew("pourover", 0, 15.0, Some(240.0), Some(4)),
        ]);
        assert!(!s.mixed_methods);
        assert_eq!(s.scope_method.as_deref(), Some("pourover"));
        // Water-in ratio semantics: (250/15 + 240/15) / 2.
        let expected = (250.0 / 15.0 + 240.0 / 15.0) / 2.0;
        assert!((s.avg_ratio.unwrap() - expected).abs() < 1e-4);
        // The un-timed manual log doesn't drag the average to zero.
        assert_eq!(s.avg_time_s, Some(185.0));
    }

    #[test]
    fn mixed_without_any_espresso_rows_yields_empty_averages() {
        let s = brew_history_stats(&[
            brew("pourover", 185_000, 15.0, Some(250.0), None),
            brew("aeropress", 130_000, 14.0, Some(220.0), None),
        ]);
        assert!(s.mixed_methods);
        assert_eq!(s.avg_weight_g, None);
        assert_eq!(s.avg_ratio, None);
        assert_eq!(s.avg_time_s, None);
        assert_eq!(s.beans_used_g, Some(29.0));
    }

    #[test]
    fn foreign_method_spellings_group_into_one_family() {
        let s = brew_history_stats(&[
            brew("French Press", 240_000, 30.0, Some(500.0), None),
            brew("french_press", 250_000, 30.0, Some(500.0), None),
        ]);
        assert!(!s.mixed_methods);
        assert_eq!(s.scope_method.as_deref(), Some("french_press"));
    }

    // ── recipe helpers ───────────────────────────────────────────

    #[test]
    fn planned_pour_total_is_the_largest_cumulative_target() {
        let recipe = BrewRecipe {
            id: "recipe:test".to_owned(),
            name: "Morning V60".to_owned(),
            method: "pourover".to_owned(),
            dose_g: 15.0,
            water_g: 250.0,
            temp_c: Some(96.0),
            steps: vec![
                BrewStep {
                    kind: BrewStepKind::Bloom,
                    target_water_g: Some(45.0),
                    duration_s: Some(45),
                    ..BrewStep::default()
                },
                BrewStep {
                    kind: BrewStepKind::Pour,
                    target_water_g: Some(250.0),
                    ..BrewStep::default()
                },
                BrewStep {
                    kind: BrewStepKind::Drawdown,
                    advance: StepAdvance::Manual,
                    ..BrewStep::default()
                },
            ],
            notes: None,
            favourite: false,
            created_at: 0,
            updated_at: 0,
            deleted_at: None,
        };
        assert_eq!(recipe.planned_pour_total_g(), Some(250.0));
    }

    #[test]
    fn brew_series_round_trips_and_defaults_are_tolerant() {
        let series = BrewSeries {
            samples: vec![BrewSample {
                elapsed_ms: 250,
                weight_g: 1.2,
                flow_g_s: Some(0.4),
            }],
            stage_marks: vec![StageMark {
                elapsed_ms: 0,
                step_index: 0,
                target_water_g: Some(45.0),
            }],
        };
        let json = serde_json::to_string(&series).unwrap();
        let parsed: BrewSeries = serde_json::from_str(&json).unwrap();
        assert_eq!(parsed, series);
        // A bare object parses to empty defaults.
        let empty: BrewSeries = serde_json::from_str("{}").unwrap();
        assert!(empty.samples.is_empty());
    }

    #[test]
    fn stage_mark_target_is_optional_on_the_wire() {
        // An older record, written before targets were snapshotted.
        let old: StageMark = serde_json::from_str(r#"{"elapsedMs":45000,"stepIndex":1}"#).unwrap();
        assert_eq!(old.target_water_g, None);
        assert_eq!(old.step_index, 1);
        // A timed step's mark omits the field rather than writing null.
        let timed = StageMark {
            elapsed_ms: 90_000,
            step_index: 2,
            target_water_g: None,
        };
        assert!(
            !serde_json::to_string(&timed)
                .unwrap()
                .contains("targetWaterG")
        );
        let pour = StageMark {
            target_water_g: Some(250.0),
            ..timed
        };
        let json = serde_json::to_string(&pour).unwrap();
        assert!(json.contains(r#""targetWaterG":250.0"#), "{json}");
        assert_eq!(serde_json::from_str::<StageMark>(&json).unwrap(), pour);
    }
}

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
//! - the curated method presets and their seeds ([`brew_method_presets`]),
//! - the Log-brew form's seeding rule ([`brew_log_seeds`]),
//! - the guided-brew data types ([`BrewSeries`], [`BrewRecipe`],
//!   [`BrewStep`]), the live/saved stage-mark rule ([`stage_mark_for`]) and the
//!   planned-vs-poured staircase ([`planned_staircase`]).
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

// ── Method presets ───────────────────────────────────────────────────

/// One curated method preset — a chip in the log form plus the numeric
/// seeds a first-ever log of that method opens with.
///
/// The core owns the ids and the seed numbers; the shells own the display
/// label and icon, keyed by [`id`](Self::id). "Other" is deliberately *not*
/// a preset: it is the shells' free-text escape hatch (the typed name is
/// normalized and stored as-is), so it has no seeds.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewMethodPreset {
    /// The stored method string (`"french_press"`).
    pub id: String,
    /// Seed dry dose, grams.
    pub seed_dose_g: f32,
    /// Seed water-in, grams — `None` for espresso, which speaks yield.
    pub seed_water_g: Option<f32>,
    /// Seed beverage-out, grams — espresso only.
    pub seed_yield_g: Option<f32>,
    /// Seed water temperature, °C — `None` where it isn't meaningful
    /// (moka on the stove, cold brew).
    pub seed_temp_c: Option<f32>,
}

/// `(id, dose, water, yield, temp)` — the curated presets in display
/// order (spec §3). Tea is deliberately absent: a BC tea brew still
/// imports, carrying its name as a free-text method.
type PresetRow = (&'static str, f32, Option<f32>, Option<f32>, Option<f32>);
const PRESET_TABLE: [PresetRow; 11] = [
    ("espresso", 18.0, None, Some(36.0), Some(93.0)),
    ("pourover", 15.0, Some(250.0), None, Some(96.0)),
    ("aeropress", 14.0, Some(220.0), None, Some(90.0)),
    ("french_press", 30.0, Some(500.0), None, Some(95.0)),
    ("moka", 15.0, Some(150.0), None, None),
    ("cold_brew", 60.0, Some(700.0), None, None),
    ("drip", 30.0, Some(500.0), None, Some(94.0)),
    ("siphon", 20.0, Some(300.0), None, Some(92.0)),
    ("clever", 18.0, Some(300.0), None, Some(94.0)),
    ("chemex", 42.0, Some(700.0), None, Some(96.0)),
    ("kalita_wave", 21.0, Some(345.0), None, Some(96.0)),
];

/// The id of the shells' free-text chip. Not a preset (no seeds); the
/// seeding rule treats it as "method not chosen yet".
pub const BREW_METHOD_OTHER: &str = "other";

/// The method the log form opens on when nothing was ever logged.
pub const DEFAULT_LOG_METHOD: &str = "pourover";

/// The curated method presets, in the order the shells display them.
#[must_use]
pub fn brew_method_presets() -> Vec<BrewMethodPreset> {
    PRESET_TABLE
        .iter()
        .map(|&(id, dose, water, yld, temp)| BrewMethodPreset {
            id: id.to_owned(),
            seed_dose_g: dose,
            seed_water_g: water,
            seed_yield_g: yld,
            seed_temp_c: temp,
        })
        .collect()
}

/// The preset for a stored method string (any case / padding), or `None`
/// for a free-text method (and for `"other"`).
#[must_use]
pub fn brew_method_preset(method: &str) -> Option<BrewMethodPreset> {
    let key = normalize_brew_method(method)?;
    brew_method_presets().into_iter().find(|p| p.id == key)
}

/// [`brew_method_presets`] as a camelCase JSON array — what both shells
/// read instead of hardcoding the seed table (mirrors
/// [`default_brew_defaults_json`](crate::default_brew_defaults_json)).
#[must_use]
pub fn brew_method_presets_json() -> String {
    // Infallible: a fixed vec of strings + finite f32s always serialises.
    serde_json::to_string(&brew_method_presets()).unwrap_or_default()
}

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

// ── Log-form seeding ─────────────────────────────────────────────────

/// One prior brew's inputs to [`brew_log_seeds`] — the
/// [`BrewStatInput`]-style light projection of a stored row. Shells pass
/// their history **newest first**.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewSeedInput {
    /// The row's method; `None` / empty = machine espresso.
    pub brew_method: Option<String>,
    /// The library bag the row debited, when attributed.
    pub bean_id: Option<String>,
    /// Dry dose, grams.
    pub dose_g: Option<f32>,
    /// Water in, grams (filter / immersion rows).
    pub water_g: Option<f32>,
    /// Beverage out, grams.
    pub yield_g: Option<f32>,
    /// The grind the row recorded, as the raw setting string.
    pub grinder_setting: Option<String>,
    /// Brew water temperature, °C.
    pub temp_c: Option<f32>,
    /// Total brew time, milliseconds; `0` = not recorded.
    #[typeshare(serialized_as = "I64")]
    pub duration_ms: u64,
}

/// Explicit seed values a caller opens the form with — "Log again" (a
/// whole prior brew) or a finished guided session's measured summary.
/// Every field optional.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewLogPrefill {
    /// The method the prefill belongs to. The prefill's numbers only seed
    /// that method — switching the form to another method ignores them.
    /// `None` = applies to whatever method the form is on.
    pub method: Option<String>,
    pub dose_g: Option<f32>,
    pub water_g: Option<f32>,
    pub yield_g: Option<f32>,
    pub grinder_setting: Option<String>,
    pub temp_c: Option<f32>,
    #[typeshare(serialized_as = "Option<I64>")]
    pub duration_ms: Option<u64>,
}

/// Everything [`brew_log_seeds`] needs to seed the Log-brew form.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewLogSeedInput {
    /// The method to seed for. `None` = open the form: the prefill's
    /// method, else [`last_used_method`](Self::last_used_method), else
    /// [`DEFAULT_LOG_METHOD`].
    pub method: Option<String>,
    /// The shell-remembered method of the last saved log.
    pub last_used_method: Option<String>,
    /// The bag the form is logging against.
    pub bean_id: Option<String>,
    /// That bag's own grinder setting — the grind fallback.
    pub bean_grinder_setting: Option<String>,
    pub prefill: Option<BrewLogPrefill>,
    /// Prior brews, newest first.
    pub rows: Vec<BrewSeedInput>,
}

/// The seeded numeric fields of the Log-brew form. Shells apply each value
/// only to fields the user has not edited.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewLogSeeds {
    /// The resolved method id the seeds belong to.
    pub method: String,
    /// Dry dose, grams.
    pub dose: f32,
    /// Water-in, grams, for filter methods — **beverage-out** for the
    /// espresso family (the form's single "water / yield" field).
    pub water: f32,
    /// Grind setting, or `None` when nothing numeric is known.
    pub grind: Option<f32>,
    /// Water temperature, °C, or `None`.
    pub temp_c: Option<f32>,
    /// Brew time, milliseconds, or `None` (the time field stays blank).
    #[typeshare(serialized_as = "Option<I64>")]
    pub duration_ms: Option<u64>,
}

/// Dose seed when neither history, prefill nor a preset knows better.
const FALLBACK_DOSE_G: f32 = 15.0;
/// Espresso beverage-out seed of last resort.
const FALLBACK_YIELD_G: f32 = 36.0;
/// Filter water-in seed of last resort.
const FALLBACK_WATER_G: f32 = 250.0;

/// Family key rows are matched by: espresso-family spellings collapse to
/// `"espresso"`, everything else is [`normalize_brew_method`]-ed.
fn method_key(method: Option<&str>) -> String {
    if is_espresso_method(method) {
        "espresso".to_owned()
    } else {
        normalize_brew_method(method.unwrap_or_default()).unwrap_or_default()
    }
}

fn positive(v: Option<f32>) -> Option<f32> {
    v.filter(|x| x.is_finite() && *x > 0.0)
}

/// A grind setting string's leading number (`"12"`, `"3.5 clicks"`), or
/// `None` when it has none or it isn't positive.
fn parse_grind(raw: Option<&str>) -> Option<f32> {
    let t = raw?.trim();
    let end = t
        .char_indices()
        .take_while(|&(i, c)| c.is_ascii_digit() || c == '.' || (i == 0 && c == '+'))
        .map(|(i, c)| i + c.len_utf8())
        .last()?;
    positive(t[..end].parse::<f32>().ok())
}

/// Seed the Log-brew form's numeric fields.
///
/// Per field, the first source that knows wins:
///
/// 1. the **prefill** ("Log again" / a guided summary) — only when it
///    belongs to the seeded method;
/// 2. the **last brew of this method** — on this bag first, else on any
///    bag (rows are newest first);
/// 3. for grind only, the **bag's own grinder setting**;
/// 4. the **method preset** seed ([`brew_method_presets`]), then a
///    generic fallback (15 g dose, 36 g yield / 250 g water, no temp).
///
/// The espresso family seeds `water` from beverage-out (yield); filter
/// methods from water-in, falling back to beverage-out. Time is seeded
/// from the prefill or last brew, never from a preset. `"other"` (the
/// free-text chip) matches no history and no preset.
#[must_use]
pub fn brew_log_seeds(input: &BrewLogSeedInput) -> BrewLogSeeds {
    let non_empty = |m: &Option<String>| {
        m.as_deref()
            .map(str::trim)
            .filter(|t| !t.is_empty())
            .map(str::to_owned)
    };
    let method = non_empty(&input.method)
        .or_else(|| input.prefill.as_ref().and_then(|p| non_empty(&p.method)))
        .or_else(|| non_empty(&input.last_used_method))
        .unwrap_or_else(|| DEFAULT_LOG_METHOD.to_owned());
    let is_other = method.eq_ignore_ascii_case(BREW_METHOD_OTHER);
    let key = method_key(Some(&method));
    let esp = !is_other && is_espresso_method(Some(&method));

    let prefill = input.prefill.as_ref().filter(|p| {
        !is_other && non_empty(&p.method).is_none_or(|pm| method_key(Some(&pm)) == key)
    });
    let last = if is_other {
        None
    } else {
        let matches = |r: &&BrewSeedInput| method_key(r.brew_method.as_deref()) == key;
        input
            .bean_id
            .as_deref()
            .and_then(|bid| {
                input
                    .rows
                    .iter()
                    .filter(matches)
                    .find(|r| r.bean_id.as_deref() == Some(bid))
            })
            .or_else(|| input.rows.iter().find(matches))
    };
    let preset = if is_other {
        None
    } else {
        brew_method_preset(&method)
    };

    let beverage = |dose_water: (Option<f32>, Option<f32>)| {
        let (water, yld) = dose_water;
        if esp {
            positive(yld)
        } else {
            positive(water).or(positive(yld))
        }
    };

    let dose = positive(prefill.and_then(|p| p.dose_g))
        .or_else(|| positive(last.and_then(|r| r.dose_g)))
        .or_else(|| preset.as_ref().map(|p| p.seed_dose_g))
        .unwrap_or(FALLBACK_DOSE_G);
    let water = prefill
        .and_then(|p| beverage((p.water_g, p.yield_g)))
        .or_else(|| last.and_then(|r| beverage((r.water_g, r.yield_g))))
        .or_else(|| {
            preset
                .as_ref()
                .and_then(|p| if esp { p.seed_yield_g } else { p.seed_water_g })
        })
        .unwrap_or(if esp {
            FALLBACK_YIELD_G
        } else {
            FALLBACK_WATER_G
        });
    let grind = parse_grind(prefill.and_then(|p| p.grinder_setting.as_deref()))
        .or_else(|| parse_grind(last.and_then(|r| r.grinder_setting.as_deref())))
        .or_else(|| parse_grind(input.bean_grinder_setting.as_deref()));
    let temp_c = positive(prefill.and_then(|p| p.temp_c))
        .or_else(|| positive(last.and_then(|r| r.temp_c)))
        .or_else(|| preset.as_ref().and_then(|p| p.seed_temp_c));
    let duration_ms = prefill
        .and_then(|p| p.duration_ms)
        .filter(|ms| *ms > 0)
        .or_else(|| last.map(|r| r.duration_ms).filter(|ms| *ms > 0));

    BrewLogSeeds {
        method,
        dose,
        water,
        grind,
        temp_c,
        duration_ms,
    }
}

/// JSON-bridged [`brew_log_seeds`]. Input: a [`BrewLogSeedInput`] JSON.
/// Output: a [`BrewLogSeeds`] JSON.
///
/// # Errors
/// The JSON parse error string on a malformed `input_json`.
pub fn brew_log_seeds_json(input_json: &str) -> Result<String, String> {
    let input: BrewLogSeedInput = serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&brew_log_seeds(&input)).map_err(|e| e.to_string())
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
    /// Who the recipe is by — "James Hoffmann — The Ultimate V60
    /// Technique (2019)". Set on the built-in catalogue
    /// ([`builtin_brew_recipes`](crate::builtin_brew_recipes)); a copy of
    /// a built-in carries "Adapted from …". Shown as small secondary
    /// text, never as an endorsement. Absent on older stored recipes.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credit: Option<String>,
    /// The primary source the recipe was taken from (video, brew guide),
    /// opened externally by the shells.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_url: Option<String>,
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

    /// The recipe's estimated run time — the sum of every step's
    /// duration, a tap-to-finish step's *expected* one included (a
    /// drawdown's "finishes around 3:00"). No step gets a hidden default
    /// window: an untimed step adds nothing.
    ///
    /// `None` when no step carries a duration (the moka pot): there is
    /// nothing meaningful to show, so the shells show no estimate at all.
    /// Otherwise [`RecipeTimeEstimate::open_ended`] flags a plan with a
    /// step that holds for an unknown time — neither a duration nor a
    /// water target, e.g. the AeroPress press — which the shells render
    /// as "~2:45+". A pour-to-weight step without a duration is bounded
    /// by its target and does not set the flag. Drives the library
    /// card's "~m:ss" and the guided panel's "of about m:ss".
    #[must_use]
    pub fn estimated_duration(&self) -> Option<RecipeTimeEstimate> {
        let mut timed = false;
        let mut total_ms = 0u64;
        let mut open_ended = false;
        for s in &self.steps {
            match s.duration_s {
                Some(d) => {
                    timed = true;
                    total_ms = total_ms.saturating_add(d.saturating_mul(1000));
                }
                None => open_ended |= s.target_water_g.is_none(),
            }
        }
        timed.then_some(RecipeTimeEstimate {
            total_ms,
            open_ended,
        })
    }
}

impl BrewStep {
    /// Whether this step's duration is an *expectation*, not a countdown:
    /// a tap-to-finish (`advance: Manual`) drawdown that says how long
    /// the drain usually takes. The session never advances it on time,
    /// raises no end-of-step cue for it, and the shells show the time as
    /// guidance ("about 0:40 left", then "+0:12 over").
    #[must_use]
    pub fn has_expected_duration(&self) -> bool {
        self.kind == BrewStepKind::Drawdown
            && self.advance == StepAdvance::Manual
            && self.duration_s.is_some()
    }
}

/// A recipe's estimated run time ([`BrewRecipe::estimated_duration`]).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecipeTimeEstimate {
    /// The sum of the step durations, milliseconds.
    #[typeshare(serialized_as = "I64")]
    pub total_ms: u64,
    /// Some step holds for an unknown time (no duration, no water
    /// target) — the shells append "+" ("~2:45+").
    pub open_ended: bool,
}

/// JSON-bridged [`BrewRecipe::planned_pour_total_g`]. Input: a
/// [`BrewRecipe`] JSON. `None` when no step carries a finite target.
///
/// # Errors
/// The JSON parse error string on a malformed `recipe_json`.
pub fn recipe_planned_pour_total_g_json(recipe_json: &str) -> Result<Option<f32>, String> {
    let recipe: BrewRecipe = serde_json::from_str(recipe_json).map_err(|e| e.to_string())?;
    Ok(recipe.planned_pour_total_g())
}

/// JSON-bridged [`BrewRecipe::estimated_duration`]. Input: a
/// [`BrewRecipe`] JSON; output: a [`RecipeTimeEstimate`] JSON, or `None`
/// when no step carries a duration (show no estimate).
///
/// # Errors
/// The JSON parse error string on a malformed `recipe_json`.
pub fn recipe_estimated_duration_json(recipe_json: &str) -> Result<Option<String>, String> {
    let recipe: BrewRecipe = serde_json::from_str(recipe_json).map_err(|e| e.to_string())?;
    recipe
        .estimated_duration()
        .map(|e| serde_json::to_string(&e).map_err(|e| e.to_string()))
        .transpose()
}

// ── Blank recipe ─────────────────────────────────────────────────────

/// The editor's starting point for a brand-new recipe of `method` — the
/// "+ New recipe" door. Not a runnable default (the per-method defaults
/// are the credited built-ins, [`default_builtin_recipe_id`](crate::default_builtin_recipe_id)):
/// dose / water (or yield) / temp come from the method preset
/// ([`brew_method_preset`]; 15 g / 250 g / no temp for free text) and the
/// plan is one pour to the water target, for the user to build on. The
/// name is empty — the shells fill in their own placeholder — and there
/// is no credit: a new recipe is the user's own.
#[must_use]
pub fn blank_recipe(method: &str, id: &str, now_ms: i64) -> BrewRecipe {
    let preset = brew_method_preset(method);
    let dose = preset.as_ref().map_or(FALLBACK_DOSE_G, |p| p.seed_dose_g);
    let water = preset
        .as_ref()
        .and_then(|p| p.seed_water_g.or(p.seed_yield_g))
        .unwrap_or(FALLBACK_WATER_G);
    BrewRecipe {
        id: id.to_owned(),
        name: String::new(),
        method: normalize_brew_method(method).unwrap_or_else(|| BREW_METHOD_OTHER.to_owned()),
        dose_g: dose,
        water_g: water,
        temp_c: preset.as_ref().and_then(|p| p.seed_temp_c),
        steps: vec![BrewStep {
            kind: BrewStepKind::Pour,
            target_water_g: Some(water),
            ..BrewStep::default()
        }],
        notes: None,
        favourite: false,
        created_at: now_ms,
        updated_at: now_ms,
        deleted_at: None,
        credit: None,
        source_url: None,
    }
}

/// JSON-bridged [`blank_recipe`]: a [`BrewRecipe`] JSON.
#[must_use]
pub fn blank_recipe_json(method: &str, id: &str, now_ms: i64) -> String {
    // Infallible: strings + finite f32s always serialize.
    serde_json::to_string(&blank_recipe(method, id, now_ms)).unwrap_or_default()
}

// ── Stage marks + the planned-vs-poured staircase ────────────────────

/// The stage mark for step `step_index` of `recipe` beginning at `at_ms`,
/// snapshotting the step's cumulative planned water target. A step-less
/// recipe runs as one implicit pour to its water target (see
/// [`BrewSessionMonitor::new`](crate::BrewSessionMonitor::new)), so its
/// step 0 carries `water_g`. The one rule for the session engine's saved
/// marks and the shells' live marks, so the live staircase matches the
/// saved one.
#[must_use]
pub fn stage_mark_for(recipe: &BrewRecipe, step_index: u64, at_ms: u64) -> StageMark {
    let target = if recipe.steps.is_empty() {
        (step_index == 0 && recipe.water_g.is_finite() && recipe.water_g > 0.0)
            .then_some(recipe.water_g)
    } else {
        usize::try_from(step_index)
            .ok()
            .and_then(|i| recipe.steps.get(i))
            .and_then(|s| s.target_water_g)
    };
    StageMark {
        elapsed_ms: at_ms,
        step_index,
        target_water_g: target,
    }
}

/// JSON-bridged [`stage_mark_for`]. Input: a [`BrewRecipe`] JSON; output:
/// a [`StageMark`] JSON.
///
/// # Errors
/// The JSON parse error string on a malformed `recipe_json`.
pub fn stage_mark_for_json(
    recipe_json: &str,
    step_index: u64,
    at_ms: u64,
) -> Result<String, String> {
    let recipe: BrewRecipe = serde_json::from_str(recipe_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&stage_mark_for(&recipe, step_index, at_ms)).map_err(|e| e.to_string())
}

/// One horizontal run of the planned-water staircase: level `target_g`
/// from `t0_ms` to `t1_ms` (session ms).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StairSegment {
    #[typeshare(serialized_as = "I64")]
    pub t0_ms: u64,
    #[typeshare(serialized_as = "I64")]
    pub t1_ms: u64,
    pub target_g: f32,
}

/// Build the "planned vs poured" staircase from a series' stage marks.
///
/// - Each segment starts at a mark and runs to the next mark, or to
///   `end_ms` (the end of the series / the live session clock).
/// - Its level is the most recent finite target at or before that mark,
///   so a timed step (a wait, the drawdown) carries the last pour's
///   target forward.
/// - Nothing is emitted before the first mark with a target, and no
///   targets at all means an empty staircase (manual logs, old records).
///
/// Marks need not arrive sorted. Consecutive segments share endpoints,
/// so a renderer joining them with vertical risers draws a staircase.
/// Same-instant marks produce no zero-width run.
#[must_use]
pub fn planned_staircase(marks: &[StageMark], end_ms: u64) -> Vec<StairSegment> {
    let mut sorted: Vec<&StageMark> = marks.iter().collect();
    sorted.sort_by_key(|m| m.elapsed_ms);
    let mut out = Vec::with_capacity(sorted.len());
    let mut level: Option<f32> = None;
    for (i, m) in sorted.iter().enumerate() {
        if let Some(t) = m.target_water_g.filter(|t| t.is_finite()) {
            level = Some(t);
        }
        let Some(target_g) = level else { continue };
        let t0_ms = m.elapsed_ms;
        let t1_ms = match sorted.get(i + 1) {
            Some(next) if next.elapsed_ms <= t0_ms => continue,
            Some(next) => next.elapsed_ms,
            None => t0_ms.max(end_ms),
        };
        out.push(StairSegment {
            t0_ms,
            t1_ms,
            target_g,
        });
    }
    out
}

/// The largest finite planned target among `marks`, or 0 when none
/// carries one — the chart's y-range floor for the staircase.
#[must_use]
pub fn max_planned_target(marks: &[StageMark]) -> f32 {
    marks
        .iter()
        .filter_map(|m| m.target_water_g)
        .filter(|t| t.is_finite())
        .fold(0.0, f32::max)
}

/// JSON-bridged [`planned_staircase`]. Input: a [`StageMark`] JSON array;
/// output: a [`StairSegment`] JSON array.
///
/// # Errors
/// The JSON parse error string on a malformed `marks_json`.
pub fn planned_staircase_json(marks_json: &str, end_ms: u64) -> Result<String, String> {
    let marks: Vec<StageMark> = serde_json::from_str(marks_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&planned_staircase(&marks, end_ms)).map_err(|e| e.to_string())
}

/// JSON-bridged [`max_planned_target`]. Input: a [`StageMark`] JSON array.
///
/// # Errors
/// The JSON parse error string on a malformed `marks_json`.
pub fn max_planned_target_json(marks_json: &str) -> Result<f32, String> {
    let marks: Vec<StageMark> = serde_json::from_str(marks_json).map_err(|e| e.to_string())?;
    Ok(max_planned_target(&marks))
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

    // ── method presets ───────────────────────────────────────────

    #[test]
    fn presets_are_the_eleven_shell_chips_without_other() {
        let ids: Vec<String> = brew_method_presets().into_iter().map(|p| p.id).collect();
        assert_eq!(
            ids,
            [
                "espresso",
                "pourover",
                "aeropress",
                "french_press",
                "moka",
                "cold_brew",
                "drip",
                "siphon",
                "clever",
                "chemex",
                "kalita_wave"
            ]
        );
        assert!(brew_method_preset(BREW_METHOD_OTHER).is_none());
        assert!(brew_method_preset("karlsbad_kanne").is_none());
    }

    #[test]
    fn preset_seeds_match_the_spec_table() {
        let esp = brew_method_preset("espresso").unwrap();
        assert_eq!(esp.seed_dose_g, 18.0);
        assert_eq!(esp.seed_water_g, None);
        assert_eq!(esp.seed_yield_g, Some(36.0));
        assert_eq!(esp.seed_temp_c, Some(93.0));
        let fp = brew_method_preset(" French Press ").unwrap();
        assert_eq!(fp.id, "french_press");
        assert_eq!((fp.seed_dose_g, fp.seed_water_g), (30.0, Some(500.0)));
        assert_eq!(brew_method_preset("cold_brew").unwrap().seed_temp_c, None);
    }

    #[test]
    fn presets_json_is_camel_case() {
        let json = brew_method_presets_json();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert_eq!(v[1]["id"], "pourover");
        assert_eq!(v[1]["seedDoseG"], 15.0);
        assert_eq!(v[1]["seedWaterG"], 250.0);
        assert!(v[1]["seedYieldG"].is_null());
        assert_eq!(v[1]["seedTempC"], 96.0);
    }

    // ── log-form seeding ─────────────────────────────────────────

    fn row(
        method: Option<&str>,
        bean: Option<&str>,
        dose: f32,
        water: Option<f32>,
    ) -> BrewSeedInput {
        BrewSeedInput {
            brew_method: method.map(str::to_owned),
            bean_id: bean.map(str::to_owned),
            dose_g: Some(dose),
            water_g: water,
            yield_g: None,
            grinder_setting: None,
            temp_c: Some(94.0),
            duration_ms: 185_000,
        }
    }

    #[test]
    fn seeds_open_on_the_last_used_method_not_pourover() {
        // Drift bug 1: Android always opened on pourover.
        let s = brew_log_seeds(&BrewLogSeedInput {
            last_used_method: Some("aeropress".to_owned()),
            ..BrewLogSeedInput::default()
        });
        assert_eq!(s.method, "aeropress");
        assert_eq!((s.dose, s.water, s.temp_c), (14.0, 220.0, Some(90.0)));
        // Nothing remembered → pourover.
        let s = brew_log_seeds(&BrewLogSeedInput::default());
        assert_eq!(s.method, DEFAULT_LOG_METHOD);
        assert_eq!((s.dose, s.water), (15.0, 250.0));
        // An explicit method wins over last-used.
        let s = brew_log_seeds(&BrewLogSeedInput {
            method: Some("moka".to_owned()),
            last_used_method: Some("aeropress".to_owned()),
            ..BrewLogSeedInput::default()
        });
        assert_eq!(s.method, "moka");
    }

    #[test]
    fn seeds_prefer_this_bags_last_brew_of_the_method_then_any_bag() {
        let rows = vec![
            row(Some("pourover"), Some("bean:other"), 20.0, Some(320.0)),
            row(Some("aeropress"), Some("bean:1"), 14.0, Some(200.0)),
            row(Some("pourover"), Some("bean:1"), 16.0, Some(260.0)),
        ];
        let mut input = BrewLogSeedInput {
            method: Some("pourover".to_owned()),
            bean_id: Some("bean:1".to_owned()),
            rows,
            ..BrewLogSeedInput::default()
        };
        let s = brew_log_seeds(&input);
        assert_eq!((s.dose, s.water), (16.0, 260.0));
        // A bag with no pourover of its own → the newest pourover anywhere.
        input.bean_id = Some("bean:new".to_owned());
        let s = brew_log_seeds(&input);
        assert_eq!((s.dose, s.water), (20.0, 320.0));
    }

    #[test]
    fn grind_falls_back_to_the_bags_grinder_setting() {
        // Drift bug 2: Android fell back to 0.
        let mut input = BrewLogSeedInput {
            method: Some("pourover".to_owned()),
            bean_grinder_setting: Some("22 clicks".to_owned()),
            ..BrewLogSeedInput::default()
        };
        assert_eq!(brew_log_seeds(&input).grind, Some(22.0));
        // The last brew's own grind wins over the bag's.
        let mut r = row(Some("pourover"), None, 15.0, Some(250.0));
        r.grinder_setting = Some("18.5".to_owned());
        input.rows = vec![r];
        assert_eq!(brew_log_seeds(&input).grind, Some(18.5));
        // A non-numeric setting falls through rather than blanking.
        input.rows[0].grinder_setting = Some("fine".to_owned());
        assert_eq!(brew_log_seeds(&input).grind, Some(22.0));
        // Nothing numeric anywhere → None, never 0.
        input.bean_grinder_setting = None;
        assert_eq!(brew_log_seeds(&input).grind, None);
    }

    #[test]
    fn time_seeds_from_the_last_brew_and_reseeds_per_method() {
        // Drift bug 3: Android never seeded time, nor reseeded it.
        let mut quick = row(Some("aeropress"), None, 14.0, Some(220.0));
        quick.duration_ms = 120_000;
        let rows = vec![quick, row(Some("pourover"), None, 15.0, Some(250.0))];
        let input = |m: &str| BrewLogSeedInput {
            method: Some(m.to_owned()),
            rows: rows.clone(),
            ..BrewLogSeedInput::default()
        };
        assert_eq!(
            brew_log_seeds(&input("pourover")).duration_ms,
            Some(185_000)
        );
        assert_eq!(
            brew_log_seeds(&input("aeropress")).duration_ms,
            Some(120_000)
        );
        // No history for the method → blank, never a preset time.
        assert_eq!(brew_log_seeds(&input("moka")).duration_ms, None);
    }

    #[test]
    fn espresso_seeds_yield_where_filter_seeds_water() {
        let mut shot = row(None, None, 18.5, Some(999.0));
        shot.yield_g = Some(40.0);
        let mut pour = row(Some("pourover"), None, 15.0, None);
        pour.yield_g = Some(210.0);
        let rows = vec![shot, pour];
        let esp = brew_log_seeds(&BrewLogSeedInput {
            method: Some("espresso".to_owned()),
            rows: rows.clone(),
            ..BrewLogSeedInput::default()
        });
        // Machine shots (`None` method) count as espresso; water is ignored.
        assert_eq!((esp.dose, esp.water), (18.5, 40.0));
        let filter = brew_log_seeds(&BrewLogSeedInput {
            method: Some("pourover".to_owned()),
            rows,
            ..BrewLogSeedInput::default()
        });
        // No water recorded → beverage weight stands in.
        assert_eq!(filter.water, 210.0);
        // Preset fallbacks per family.
        let e = brew_log_seeds(&BrewLogSeedInput {
            method: Some("espresso".to_owned()),
            ..BrewLogSeedInput::default()
        });
        assert_eq!((e.dose, e.water, e.temp_c), (18.0, 36.0, Some(93.0)));
    }

    #[test]
    fn a_prefill_seeds_its_own_method_only() {
        let prefill = BrewLogPrefill {
            method: Some("aeropress".to_owned()),
            dose_g: Some(13.0),
            water_g: Some(200.0),
            grinder_setting: Some("9".to_owned()),
            temp_c: Some(85.0),
            duration_ms: Some(95_000),
            ..BrewLogPrefill::default()
        };
        let rows = vec![row(Some("aeropress"), None, 14.0, Some(220.0))];
        let open = brew_log_seeds(&BrewLogSeedInput {
            last_used_method: Some("pourover".to_owned()),
            prefill: Some(prefill.clone()),
            rows: rows.clone(),
            ..BrewLogSeedInput::default()
        });
        // Opens on the prefill's method, and its numbers win over history.
        assert_eq!(open.method, "aeropress");
        assert_eq!((open.dose, open.water), (13.0, 200.0));
        assert_eq!((open.grind, open.temp_c), (Some(9.0), Some(85.0)));
        assert_eq!(open.duration_ms, Some(95_000));
        // Switching away ignores the prefill: preset seeds, not 13 g.
        let switched = brew_log_seeds(&BrewLogSeedInput {
            method: Some("french_press".to_owned()),
            prefill: Some(prefill),
            rows,
            ..BrewLogSeedInput::default()
        });
        assert_eq!((switched.dose, switched.water), (30.0, 500.0));
        assert_eq!(switched.duration_ms, None);
    }

    #[test]
    fn a_methodless_prefill_applies_to_the_current_method() {
        let s = brew_log_seeds(&BrewLogSeedInput {
            method: Some("espresso".to_owned()),
            prefill: Some(BrewLogPrefill {
                yield_g: Some(42.0),
                water_g: Some(300.0),
                ..BrewLogPrefill::default()
            }),
            ..BrewLogSeedInput::default()
        });
        assert_eq!(s.water, 42.0);
    }

    #[test]
    fn other_is_free_text_with_generic_fallbacks() {
        let s = brew_log_seeds(&BrewLogSeedInput {
            method: Some(BREW_METHOD_OTHER.to_owned()),
            bean_grinder_setting: Some("12".to_owned()),
            rows: vec![row(Some("other"), None, 99.0, Some(999.0))],
            ..BrewLogSeedInput::default()
        });
        assert_eq!(s.method, "other");
        assert_eq!((s.dose, s.water, s.temp_c), (15.0, 250.0, None));
        assert_eq!(s.grind, Some(12.0));
        assert_eq!(s.duration_ms, None);
    }

    #[test]
    fn seeds_json_round_trips_camel_case() {
        let out = brew_log_seeds_json(
            r#"{"method":null,"lastUsedMethod":"espresso","beanId":"b","rows":[{"brewMethod":"espresso","beanId":"b","doseG":19,"yieldG":38,"durationMs":28000}]}"#,
        )
        .unwrap();
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(v["method"], "espresso");
        assert_eq!(v["dose"], 19.0);
        assert_eq!(v["water"], 38.0);
        assert_eq!(v["durationMs"], 28000);
        assert!(v["grind"].is_null());
        assert!(brew_log_seeds_json("not json").is_err());
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
            credit: None,
            source_url: None,
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

    // ── staircase / stage marks / templates (#100 core moves) ────────

    fn mark(t: u64, i: u64, target: Option<f32>) -> StageMark {
        StageMark {
            elapsed_ms: t,
            step_index: i,
            target_water_g: target,
        }
    }

    fn seg(t0_ms: u64, t1_ms: u64, target_g: f32) -> StairSegment {
        StairSegment {
            t0_ms,
            t1_ms,
            target_g,
        }
    }

    /// V60: bloom 45 → pour 150 → wait → pour 250 → drawdown.
    fn v60_marks() -> Vec<StageMark> {
        vec![
            mark(0, 0, Some(45.0)),
            mark(45_000, 1, Some(150.0)),
            mark(75_000, 2, None),
            mark(100_000, 3, Some(250.0)),
            mark(130_000, 4, None),
        ]
    }

    #[test]
    fn staircase_carries_targets_through_timed_steps() {
        assert_eq!(
            planned_staircase(&v60_marks(), 180_000),
            vec![
                seg(0, 45_000, 45.0),
                seg(45_000, 75_000, 150.0),
                seg(75_000, 100_000, 150.0),
                seg(100_000, 130_000, 250.0),
                seg(130_000, 180_000, 250.0),
            ]
        );
        assert!((max_planned_target(&v60_marks()) - 250.0).abs() < f32::EPSILON);
    }

    #[test]
    fn staircase_follows_a_skip_in_any_input_order() {
        // Bloom skipped at 20 s straight into the 250 g pour.
        let marks = [
            mark(20_000, 1, Some(250.0)),
            mark(0, 0, Some(45.0)),
            mark(60_000, 3, None),
        ];
        assert_eq!(
            planned_staircase(&marks, 90_000),
            vec![
                seg(0, 20_000, 45.0),
                seg(20_000, 60_000, 250.0),
                seg(60_000, 90_000, 250.0),
            ]
        );
    }

    #[test]
    fn staircase_is_empty_without_targets() {
        let old = [mark(0, 0, None), mark(30_000, 1, None)];
        assert!(planned_staircase(&old, 60_000).is_empty());
        assert!(planned_staircase(&[], 60_000).is_empty());
        assert!(max_planned_target(&old).abs() < f32::EPSILON);
        // Non-finite targets count as none.
        let bad = [
            mark(0, 0, Some(f32::NAN)),
            mark(1_000, 1, Some(f32::INFINITY)),
        ];
        assert!(planned_staircase(&bad, 5_000).is_empty());
        assert!(max_planned_target(&bad).abs() < f32::EPSILON);
    }

    #[test]
    fn staircase_starts_at_the_first_target() {
        let marks = [mark(0, 0, None), mark(10_000, 1, Some(60.0))];
        assert_eq!(
            planned_staircase(&marks, 30_000),
            vec![seg(10_000, 30_000, 60.0)]
        );
    }

    #[test]
    fn a_live_staircase_ends_at_the_clock_mid_step() {
        let live = &v60_marks()[..2];
        assert_eq!(
            planned_staircase(live, 52_000),
            vec![seg(0, 45_000, 45.0), seg(45_000, 52_000, 150.0)]
        );
        // A clock behind the last mark never runs a segment backwards.
        assert_eq!(
            planned_staircase(live, 10_000),
            vec![seg(0, 45_000, 45.0), seg(45_000, 45_000, 150.0)]
        );
    }

    #[test]
    fn same_instant_marks_emit_no_zero_width_run() {
        let marks = [mark(0, 0, Some(45.0)), mark(0, 1, Some(150.0))];
        assert_eq!(
            planned_staircase(&marks, 10_000),
            vec![seg(0, 10_000, 150.0)]
        );
    }

    #[test]
    fn staircase_json_facades_speak_camel_case() {
        let marks = serde_json::to_string(&v60_marks()[..2]).unwrap();
        let out = planned_staircase_json(&marks, 60_000).unwrap();
        assert_eq!(
            out,
            r#"[{"t0Ms":0,"t1Ms":45000,"targetG":45.0},{"t0Ms":45000,"t1Ms":60000,"targetG":150.0}]"#
        );
        assert!((max_planned_target_json(&marks).unwrap() - 150.0).abs() < f32::EPSILON);
        assert!(planned_staircase_json("nope", 0).is_err());
        assert!(max_planned_target_json("{").is_err());
    }

    fn v60_recipe() -> BrewRecipe {
        BrewRecipe {
            id: "r".to_owned(),
            name: "V60".to_owned(),
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
                    kind: BrewStepKind::Wait,
                    duration_s: Some(30),
                    ..BrewStep::default()
                },
            ],
            notes: None,
            favourite: false,
            created_at: 1,
            updated_at: 1,
            deleted_at: None,
            credit: None,
            source_url: None,
        }
    }

    #[test]
    fn stage_marks_snapshot_the_recipe_targets() {
        let r = v60_recipe();
        assert_eq!(stage_mark_for(&r, 0, 0), mark(0, 0, Some(45.0)));
        assert_eq!(stage_mark_for(&r, 1, 45_000), mark(45_000, 1, None));
        // Past the end of the plan: a mark, no target.
        assert_eq!(stage_mark_for(&r, 7, 99_000), mark(99_000, 7, None));
        // A step-less recipe runs as one implicit pour to its water.
        let stepless = BrewRecipe {
            steps: vec![],
            ..r.clone()
        };
        assert_eq!(stage_mark_for(&stepless, 0, 0), mark(0, 0, Some(250.0)));
        assert_eq!(stage_mark_for(&stepless, 1, 5), mark(5, 1, None));
        let dry = BrewRecipe {
            water_g: 0.0,
            ..stepless
        };
        assert_eq!(stage_mark_for(&dry, 0, 0), mark(0, 0, None));
    }

    #[test]
    fn stage_mark_json_facade_round_trips() {
        let json = serde_json::to_string(&v60_recipe()).unwrap();
        let out = stage_mark_for_json(&json, 0, 1_500).unwrap();
        assert_eq!(
            out,
            r#"{"elapsedMs":1500,"stepIndex":0,"targetWaterG":45.0}"#
        );
        let timed = stage_mark_for_json(&json, 1, 45_000).unwrap();
        assert_eq!(timed, r#"{"elapsedMs":45000,"stepIndex":1}"#);
        assert!(stage_mark_for_json("[]", 0, 0).is_err());
    }

    #[test]
    fn estimate_sums_every_duration_without_hidden_windows() {
        let mut r = v60_recipe();
        // bloom 45 s (duration wins over its target) + wait 30 s.
        let est = |r: &BrewRecipe| r.estimated_duration().map(|e| (e.total_ms, e.open_ended));
        assert_eq!(est(&r), Some((75_000, false)));
        // A pour-to-weight step adds nothing and stays bounded.
        r.steps.push(BrewStep {
            kind: BrewStepKind::Pour,
            target_water_g: Some(250.0),
            ..BrewStep::default()
        });
        assert_eq!(est(&r), Some((75_000, false)));
        // An open drawdown adds nothing but makes the total open-ended.
        r.steps.push(BrewStep {
            kind: BrewStepKind::Drawdown,
            advance: StepAdvance::Manual,
            ..BrewStep::default()
        });
        assert_eq!(est(&r), Some((75_000, true)));
        // Its expected duration counts, and closes the plan.
        r.steps.last_mut().unwrap().duration_s = Some(60);
        assert_eq!(est(&r), Some((135_000, false)));
        assert!(r.steps.last().unwrap().has_expected_duration());
        r.steps.clear();
        assert_eq!(r.estimated_duration(), None);
    }

    #[test]
    fn builtin_estimates_match_the_sources_finish_times() {
        let est = |id: &str| {
            crate::builtin_brew_recipe(id)
                .unwrap()
                .estimated_duration()
                .map(|e| (e.total_ms / 1000, e.open_ended))
        };
        assert_eq!(est("builtin:hoffmann-1-cup-v60"), Some((180, false)));
        assert_eq!(est("builtin:hoffmann-ultimate-v60"), Some((210, false)));
        assert_eq!(est("builtin:kasuya-4-6"), Some((210, false)));
        assert_eq!(est("builtin:stumptown-chemex"), Some((240, false)));
        assert_eq!(est("builtin:stumptown-kalita-wave"), Some((180, false)));
        assert_eq!(est("builtin:hoffmann-ultimate-clever"), Some((220, false)));
        // Moka: no step has a duration — no estimate at all (it used to
        // read "~0:30" from a notional pour window).
        assert_eq!(est("builtin:hoffmann-moka"), None);
        // The presses hold for an unknown time: "~2:45+".
        assert_eq!(
            est("builtin:hoffmann-ultimate-aeropress"),
            Some((165, true))
        );
        assert_eq!(est("builtin:aeropress-official"), Some((63, true)));
        assert_eq!(est("builtin:merikanto-wac-2021"), Some((120, false)));
        assert_eq!(est("builtin:hario-syphon"), Some((80, true)));
        assert_eq!(est("builtin:hoffmann-cold-brew"), Some((43_230, true)));
    }

    #[test]
    fn estimate_json_bridges_and_is_null_without_durations() {
        let v60 = crate::builtin_brew_recipe("builtin:hoffmann-1-cup-v60").unwrap();
        let json = serde_json::to_string(&v60).unwrap();
        assert_eq!(
            recipe_estimated_duration_json(&json).unwrap().as_deref(),
            Some(r#"{"totalMs":180000,"openEnded":false}"#)
        );
        let moka = crate::builtin_brew_recipe("builtin:hoffmann-moka").unwrap();
        let json = serde_json::to_string(&moka).unwrap();
        assert_eq!(recipe_estimated_duration_json(&json).unwrap(), None);
        assert!(recipe_estimated_duration_json("x").is_err());
    }

    #[test]
    fn planned_pour_total_skips_non_finite_targets_and_bridges() {
        let mut r = v60_recipe();
        r.steps[1].target_water_g = Some(f32::NAN);
        assert_eq!(r.planned_pour_total_g(), Some(45.0));
        r.steps.clear();
        assert_eq!(r.planned_pour_total_g(), None);
        let official = crate::builtin_brew_recipe("builtin:aeropress-official").unwrap();
        let json = serde_json::to_string(&official).unwrap();
        assert_eq!(
            recipe_planned_pour_total_g_json(&json).unwrap(),
            Some(220.0)
        );
        let empty = serde_json::to_string(&r).unwrap();
        assert_eq!(recipe_planned_pour_total_g_json(&empty).unwrap(), None);
        assert!(recipe_planned_pour_total_g_json("").is_err());
    }

    #[test]
    fn a_recipe_stored_before_credits_parses_unchanged() {
        // Stored by the Brew Log before `credit` / `sourceUrl` existed.
        let old = r#"{"id":"recipe:x","name":"Morning V60","method":"pourover",
            "doseG":15,"waterG":250,"tempC":96,"steps":[],"notes":null,
            "favourite":true,"createdAt":1,"updatedAt":2,"deletedAt":null}"#;
        let r: BrewRecipe = serde_json::from_str(old).unwrap();
        assert_eq!((r.credit.as_deref(), r.source_url.as_deref()), (None, None));
        assert_eq!(r.name, "Morning V60");
        assert!(r.favourite);
        // …and re-serializes without the new keys, so older shells and
        // backups read it back byte-for-byte compatible.
        let json = serde_json::to_string(&r).unwrap();
        assert!(
            !json.contains("credit") && !json.contains("sourceUrl"),
            "{json}"
        );
        // With a credit, both keys ride camelCase.
        let credited = BrewRecipe {
            credit: Some("Someone".to_owned()),
            source_url: Some("https://example.com".to_owned()),
            ..r
        };
        let json = serde_json::to_string(&credited).unwrap();
        assert!(json.contains(r#""credit":"Someone""#), "{json}");
        assert!(
            json.contains(r#""sourceUrl":"https://example.com""#),
            "{json}"
        );
        let back: BrewRecipe = serde_json::from_str(&json).unwrap();
        assert_eq!(back, credited);
    }

    #[test]
    fn a_blank_recipe_seeds_from_the_preset_with_one_pour() {
        let r = blank_recipe("Chemex", "recipe:n", 7);
        assert_eq!(
            (r.id.as_str(), r.method.as_str(), r.name.as_str()),
            ("recipe:n", "chemex", "")
        );
        assert_eq!((r.dose_g, r.water_g, r.temp_c), (42.0, 700.0, Some(96.0)));
        assert_eq!(r.steps.len(), 1);
        assert_eq!(
            (r.steps[0].kind, r.steps[0].target_water_g),
            (BrewStepKind::Pour, Some(700.0))
        );
        assert_eq!(
            (r.created_at, r.updated_at, r.credit.as_deref()),
            (7, 7, None)
        );
        // Espresso speaks yield; free text falls back to 15 g / 250 g.
        let e = blank_recipe("espresso", "r", 0);
        assert_eq!((e.dose_g, e.water_g, e.temp_c), (18.0, 36.0, Some(93.0)));
        let free = blank_recipe("karlsbad kanne", "r", 0);
        assert_eq!(
            (free.method.as_str(), free.dose_g, free.water_g, free.temp_c),
            ("karlsbad_kanne", 15.0, 250.0, None)
        );
        // Never mistaken for a legacy starter.
        assert!(!crate::is_legacy_default_recipe(&blank_recipe(
            "pourover", "r", 0
        )));
        let back: BrewRecipe = serde_json::from_str(&blank_recipe_json("moka", "r", 1)).unwrap();
        assert_eq!(back, blank_recipe("moka", "r", 1));
    }
}

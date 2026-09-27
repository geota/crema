//! User-defined brewing methods — "add my ORB as a method" (issue #10
//! feedback).
//!
//! The curated presets ([`brew_method_presets`]) are a fixed table; a
//! [`CustomBrewMethod`] is the user's own entry beside them. Like brew
//! recipes, custom methods are **shell-persisted** (the core owns the
//! shape and the rules, never a store) and ride in backups on their own
//! `kind:"brewMethod"` line
//! ([`export_backup_jsonl_from_json`](crate::export_backup_jsonl_from_json)).
//!
//! - **Ids** live in the reserved `custom:` namespace
//!   ([`new_custom_method_id`](crate::new_custom_method_id)) and are stored
//!   verbatim as a brew row's `brew_method` —
//!   [`normalize_brew_method`] leaves them untouched.
//! - **Style** ([`BrewMethodStyle`]) stands in for the numbers the user
//!   left blank ([`brew_method_style_seeds`]) and shapes the method's
//!   first recipe ([`blank_recipe_for_style`]).
//! - **Merged presets** ([`brew_method_presets_with_custom`]) are what
//!   every method picker lists; tombstoned methods drop out of pickers but
//!   still resolve ([`resolve_brew_method_preset`]) so old rows seed and
//!   label correctly.
//! - **Labels** are validated once, here ([`validate_custom_method_label`]).
//!   A brew saved with a custom method snapshots the label on the row
//!   ([`StoredShot::brew_method_label`](crate::StoredShot)), so a deleted
//!   method's past brews keep their name.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::brew::{
    BREW_METHOD_OTHER, BrewMethodPreset, BrewRecipe, BrewStep, BrewStepKind, StepAdvance,
    brew_method_preset, brew_method_presets, normalize_brew_method,
};

/// The reserved id namespace of user-defined methods.
pub const CUSTOM_METHOD_ID_PREFIX: &str = "custom:";

/// The longest custom-method label, in characters (after trimming).
pub const CUSTOM_METHOD_LABEL_MAX_CHARS: usize = 40;

/// How a custom method brews — picks the seed numbers a blank field falls
/// back to and the shape of the method's first recipe. Lowercase wire
/// spelling, like [`BrewStepKind`].
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BrewMethodStyle {
    /// Water poured through a bed (V60, Kalita, the ORB).
    #[default]
    Percolation,
    /// Grounds steep in the water, then separate (French press, clever).
    Immersion,
    /// Steep, then push through (AeroPress-like).
    Pressure,
    /// Long, cold steep (cold brew, Kyoto drip).
    Cold,
}

impl BrewMethodStyle {
    /// All four styles, in the order the shells show them.
    pub const ALL: [BrewMethodStyle; 4] = [
        BrewMethodStyle::Percolation,
        BrewMethodStyle::Immersion,
        BrewMethodStyle::Pressure,
        BrewMethodStyle::Cold,
    ];

    /// The curated preset whose seeds this style borrows:
    ///
    /// | style        | preset         | dose | water | temp  |
    /// |--------------|----------------|------|-------|-------|
    /// | percolation  | `pourover`     | 15 g | 250 g | 96 °C |
    /// | immersion    | `french_press` | 30 g | 500 g | 95 °C |
    /// | pressure     | `aeropress`    | 14 g | 220 g | 90 °C |
    /// | cold         | `cold_brew`    | 60 g | 700 g | —     |
    ///
    /// Borrowing (rather than copying numbers) keeps the style defaults in
    /// step with the preset table.
    #[must_use]
    pub fn seed_preset_id(self) -> &'static str {
        match self {
            BrewMethodStyle::Percolation => "pourover",
            BrewMethodStyle::Immersion => "french_press",
            BrewMethodStyle::Pressure => "aeropress",
            BrewMethodStyle::Cold => "cold_brew",
        }
    }
}

/// One user-defined brewing method.
///
/// Shell-persisted with the beans/recipes lifecycle: timestamps in Unix
/// ms and a soft-delete tombstone. The seeds are optional — a blank one
/// falls back to the [`style`](Self::style)'s default
/// ([`brew_method_style_seeds`]).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CustomBrewMethod {
    /// Stable id — `custom:<uuid-v7>`; the stored `brew_method` value.
    pub id: String,
    /// The user's name for it ("ORB"), trimmed, 1–40 characters.
    pub label: String,
    #[serde(default)]
    pub style: BrewMethodStyle,
    /// A shell icon key from the small shared set ("drop", "funnel", …);
    /// `None` = the style's default icon.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub icon: Option<String>,
    /// Seed dry dose, grams; `None` = the style default.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seed_dose_g: Option<f32>,
    /// Seed water-in, grams; `None` = the style default.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seed_water_g: Option<f32>,
    /// Seed water temperature, °C; `None` = the style default (none for
    /// cold).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seed_temp_c: Option<f32>,
    #[typeshare(serialized_as = "I64")]
    pub created_at: i64,
    #[typeshare(serialized_as = "I64")]
    pub updated_at: i64,
    /// Soft-delete tombstone, Unix ms. A tombstoned method leaves every
    /// picker but keeps resolving for the rows that used it.
    #[serde(default)]
    #[typeshare(serialized_as = "Option<I64>")]
    pub deleted_at: Option<i64>,
}

/// Whether `method` names a user-defined method (the `custom:` namespace,
/// any case / padding).
#[must_use]
pub fn is_custom_method_id(method: &str) -> bool {
    method
        .trim()
        .get(..CUSTOM_METHOD_ID_PREFIX.len())
        .is_some_and(|p| p.eq_ignore_ascii_case(CUSTOM_METHOD_ID_PREFIX))
}

fn positive(v: Option<f32>) -> Option<f32> {
    v.filter(|x| x.is_finite() && *x > 0.0)
}

/// The seeds `style` stands in with when a custom method leaves a field
/// blank — a [`BrewMethodPreset`] whose id is the borrowed preset's
/// ([`BrewMethodStyle::seed_preset_id`]). The add-method dialog prefills
/// its Dose / Water / Temp fields from this.
#[must_use]
pub fn brew_method_style_seeds(style: BrewMethodStyle) -> BrewMethodPreset {
    // The four borrowed ids are pinned by a test to exist in the table.
    brew_method_preset(style.seed_preset_id()).unwrap_or(BrewMethodPreset {
        id: style.seed_preset_id().to_owned(),
        seed_dose_g: 15.0,
        seed_water_g: Some(250.0),
        seed_yield_g: None,
        seed_temp_c: None,
        label: None,
        style: None,
        icon: None,
    })
}

/// JSON-bridged [`brew_method_style_seeds`]: `style` is the lowercase wire
/// name (`"immersion"`); output is a [`BrewMethodPreset`] JSON.
///
/// # Errors
/// The parse error string when `style` is not one of the four styles.
pub fn brew_method_style_seeds_json(style: &str) -> Result<String, String> {
    let style: BrewMethodStyle =
        serde_json::from_value(serde_json::Value::String(style.trim().to_ascii_lowercase()))
            .map_err(|e| e.to_string())?;
    serde_json::to_string(&brew_method_style_seeds(style)).map_err(|e| e.to_string())
}

/// A custom method as a picker entry — its own seeds where set, the
/// style's where blank, and the label / style / icon the shells render
/// (presets leave those three `None`).
#[must_use]
pub fn custom_method_preset(method: &CustomBrewMethod) -> BrewMethodPreset {
    let base = brew_method_style_seeds(method.style);
    BrewMethodPreset {
        id: method.id.trim().to_owned(),
        seed_dose_g: positive(method.seed_dose_g).unwrap_or(base.seed_dose_g),
        seed_water_g: positive(method.seed_water_g).or(base.seed_water_g),
        seed_yield_g: None,
        seed_temp_c: positive(method.seed_temp_c).or(base.seed_temp_c),
        label: Some(method.label.clone()),
        style: Some(method.style),
        icon: method.icon.clone(),
    }
}

/// Every method a picker lists: the curated presets in display order,
/// then the live custom methods in the given order. Tombstoned methods are
/// left out (their rows still resolve through
/// [`resolve_brew_method_preset`]).
#[must_use]
pub fn brew_method_presets_with_custom(custom: &[CustomBrewMethod]) -> Vec<BrewMethodPreset> {
    let mut out = brew_method_presets();
    out.extend(
        custom
            .iter()
            .filter(|m| m.deleted_at.is_none())
            .map(custom_method_preset),
    );
    out
}

/// JSON-bridged [`brew_method_presets_with_custom`]. Input: a
/// [`CustomBrewMethod`] JSON array; output: a [`BrewMethodPreset`] JSON
/// array.
///
/// # Errors
/// The JSON parse error string on a malformed `custom_json`.
pub fn brew_method_presets_with_custom_json(custom_json: &str) -> Result<String, String> {
    let custom: Vec<CustomBrewMethod> =
        serde_json::from_str(custom_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&brew_method_presets_with_custom(&custom)).map_err(|e| e.to_string())
}

/// The preset for any stored method string: a curated preset first, then
/// a custom method by id — **tombstoned ones included**, so an old row's
/// "Log again" still seeds from its method. `None` for free text and
/// `"other"`.
#[must_use]
pub fn resolve_brew_method_preset(
    method: &str,
    custom: &[CustomBrewMethod],
) -> Option<BrewMethodPreset> {
    if is_custom_method_id(method) {
        let key = normalize_brew_method(method)?;
        custom
            .iter()
            .find(|m| normalize_brew_method(&m.id).as_deref() == Some(key.as_str()))
            .map(custom_method_preset)
    } else {
        brew_method_preset(method)
    }
}

/// The editor's starting point for a custom method's first recipe — the
/// "+ New recipe for ORB" door. Dose / water / temp are the method's
/// (style fallbacks for blanks); the steps follow the style:
///
/// - **percolation** — one pour to the water target;
/// - **immersion** — pour, then a 4:00 steep;
/// - **pressure** — pour, a 1:30 steep, then an open-ended press;
/// - **cold** — pour, then a 12-hour steep (tap to finish).
///
/// Like [`blank_recipe`](crate::blank_recipe): empty name, no credit.
#[must_use]
pub fn blank_recipe_for_style(method: &CustomBrewMethod, id: &str, now_ms: i64) -> BrewRecipe {
    let seeds = custom_method_preset(method);
    let water = seeds.seed_water_g.unwrap_or(250.0);
    let pour = BrewStep {
        kind: BrewStepKind::Pour,
        target_water_g: Some(water),
        ..BrewStep::default()
    };
    let steep = |secs: u64, advance: StepAdvance| BrewStep {
        kind: BrewStepKind::Steep,
        duration_s: Some(secs),
        advance,
        ..BrewStep::default()
    };
    let steps = match method.style {
        BrewMethodStyle::Percolation => vec![pour],
        BrewMethodStyle::Immersion => vec![pour, steep(240, StepAdvance::Auto)],
        BrewMethodStyle::Pressure => vec![
            pour,
            steep(90, StepAdvance::Auto),
            BrewStep {
                kind: BrewStepKind::Press,
                advance: StepAdvance::Manual,
                ..BrewStep::default()
            },
        ],
        BrewMethodStyle::Cold => vec![pour, steep(12 * 3600, StepAdvance::Manual)],
    };
    BrewRecipe {
        id: id.to_owned(),
        name: String::new(),
        method: normalize_brew_method(&method.id).unwrap_or_else(|| BREW_METHOD_OTHER.to_owned()),
        dose_g: seeds.seed_dose_g,
        water_g: water,
        temp_c: seeds.seed_temp_c,
        steps,
        notes: None,
        favourite: false,
        created_at: now_ms,
        updated_at: now_ms,
        deleted_at: None,
        credit: None,
        source_url: None,
    }
}

/// JSON-bridged [`blank_recipe_for_style`]. Input: a [`CustomBrewMethod`]
/// JSON; output: a [`BrewRecipe`] JSON.
///
/// # Errors
/// The JSON parse error string on a malformed `method_json`.
pub fn blank_recipe_for_style_json(
    method_json: &str,
    id: &str,
    now_ms: i64,
) -> Result<String, String> {
    let method: CustomBrewMethod = serde_json::from_str(method_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&blank_recipe_for_style(&method, id, now_ms)).map_err(|e| e.to_string())
}

// ── Label validation ─────────────────────────────────────────────────

/// Why a custom-method label was refused. camelCase wire spelling; the
/// shells map each to their own copy.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum CustomMethodLabelError {
    /// Blank after trimming.
    Empty,
    /// Longer than [`CUSTOM_METHOD_LABEL_MAX_CHARS`] after trimming.
    TooLong,
    /// Clashes (case-insensitively) with a preset or a live custom method.
    Duplicate,
}

/// Input to [`validate_custom_method_label`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct CustomMethodLabelInput {
    /// The label as typed.
    pub label: String,
    /// The method being renamed, so it doesn't clash with itself.
    pub editing_id: Option<String>,
    /// The user's custom methods (tombstoned ones are ignored — a deleted
    /// name is free again).
    pub custom_methods: Vec<CustomBrewMethod>,
    /// The shell's display labels for the curated presets ("V60 /
    /// pourover") — the core owns only the ids, which are checked too.
    pub preset_labels: Vec<String>,
}

/// The verdict of [`validate_custom_method_label`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CustomMethodLabelCheck {
    /// The trimmed label — what to store when `error` is `None`.
    pub label: String,
    pub error: Option<CustomMethodLabelError>,
}

/// Validate a custom method's name: trimmed, 1–40 characters, and unique
/// case-insensitively against the presets (their ids — "Chemex" and
/// "French Press" clash through [`normalize_brew_method`] — and the
/// shell labels passed in), the free-text `"other"` chip, and every
/// **live** custom method but the one being renamed.
#[must_use]
pub fn validate_custom_method_label(input: &CustomMethodLabelInput) -> CustomMethodLabelCheck {
    let label = input.label.trim().to_owned();
    let error = if label.is_empty() {
        Some(CustomMethodLabelError::Empty)
    } else if label.chars().count() > CUSTOM_METHOD_LABEL_MAX_CHARS {
        Some(CustomMethodLabelError::TooLong)
    } else {
        let key = normalize_brew_method(&label);
        let lower = label.to_lowercase();
        let same = |other: &str| {
            other.trim().to_lowercase() == lower || normalize_brew_method(other) == key
        };
        let preset_clash = brew_method_presets().iter().any(|p| same(&p.id))
            || same(BREW_METHOD_OTHER)
            || input.preset_labels.iter().any(|l| same(l));
        let editing = input.editing_id.as_deref().map(str::trim);
        let custom_clash = input
            .custom_methods
            .iter()
            .filter(|m| m.deleted_at.is_none() && Some(m.id.trim()) != editing)
            .any(|m| same(&m.label));
        (preset_clash || custom_clash).then_some(CustomMethodLabelError::Duplicate)
    };
    CustomMethodLabelCheck { label, error }
}

/// JSON-bridged [`validate_custom_method_label`]. Input: a
/// [`CustomMethodLabelInput`] JSON; output: a [`CustomMethodLabelCheck`]
/// JSON.
///
/// # Errors
/// The JSON parse error string on a malformed `input_json`.
pub fn validate_custom_method_label_json(input_json: &str) -> Result<String, String> {
    let input: CustomMethodLabelInput =
        serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&validate_custom_method_label(&input)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::brew::{BrewLogSeedInput, BrewSeedInput, brew_log_seeds};

    const ORB: &str = "custom:01920000-0000-7000-8000-00000000abcd";

    fn orb() -> CustomBrewMethod {
        CustomBrewMethod {
            id: ORB.to_owned(),
            label: "ORB".to_owned(),
            style: BrewMethodStyle::Percolation,
            icon: None,
            seed_dose_g: None,
            seed_water_g: None,
            seed_temp_c: None,
            created_at: 1,
            updated_at: 1,
            deleted_at: None,
        }
    }

    #[test]
    fn custom_ids_survive_normalization_verbatim() {
        assert!(is_custom_method_id(ORB));
        assert!(is_custom_method_id("  Custom:X "));
        assert!(!is_custom_method_id("pourover"));
        assert!(!is_custom_method_id("custom"));
        // The uuid's dashes must not collapse to `_`.
        assert_eq!(normalize_brew_method(ORB).as_deref(), Some(ORB));
        assert_eq!(
            normalize_brew_method(&format!("  {} ", ORB.to_uppercase())).as_deref(),
            Some(ORB)
        );
    }

    #[test]
    fn style_seeds_borrow_the_matching_presets() {
        let s = |st| {
            let p = brew_method_style_seeds(st);
            (p.seed_dose_g, p.seed_water_g, p.seed_temp_c)
        };
        assert_eq!(
            s(BrewMethodStyle::Percolation),
            (15.0, Some(250.0), Some(96.0))
        );
        assert_eq!(
            s(BrewMethodStyle::Immersion),
            (30.0, Some(500.0), Some(95.0))
        );
        assert_eq!(
            s(BrewMethodStyle::Pressure),
            (14.0, Some(220.0), Some(90.0))
        );
        assert_eq!(s(BrewMethodStyle::Cold), (60.0, Some(700.0), None));
        for st in BrewMethodStyle::ALL {
            assert!(brew_method_preset(st.seed_preset_id()).is_some());
        }
        let v: serde_json::Value =
            serde_json::from_str(&brew_method_style_seeds_json(" Immersion").unwrap()).unwrap();
        assert_eq!(v["seedDoseG"], 30.0);
        assert!(brew_method_style_seeds_json("espresso").is_err());
    }

    #[test]
    fn merged_presets_append_live_custom_methods_only() {
        let mut gone = orb();
        gone.id = "custom:gone".to_owned();
        gone.label = "Gone".to_owned();
        gone.deleted_at = Some(5);
        let mut mine = orb();
        mine.seed_dose_g = Some(18.0);
        mine.seed_temp_c = Some(0.0); // not positive → style default
        mine.icon = Some("funnel".to_owned());
        let merged = brew_method_presets_with_custom(&[mine, gone]);
        assert_eq!(merged.len(), brew_method_presets().len() + 1);
        let last = merged.last().unwrap();
        assert_eq!(last.id, ORB);
        assert_eq!(last.label.as_deref(), Some("ORB"));
        assert_eq!(last.style, Some(BrewMethodStyle::Percolation));
        assert_eq!(last.icon.as_deref(), Some("funnel"));
        assert_eq!(
            (last.seed_dose_g, last.seed_water_g, last.seed_temp_c),
            (18.0, Some(250.0), Some(96.0))
        );
        // Presets serialize without the custom-only keys.
        let json = brew_method_presets_with_custom_json(&serde_json::to_string(&[orb()]).unwrap())
            .unwrap();
        let v: serde_json::Value = serde_json::from_str(&json).unwrap();
        assert!(v[0].get("label").is_none());
        assert_eq!(v[11]["label"], "ORB");
        assert_eq!(v[11]["style"], "percolation");
    }

    #[test]
    fn tombstoned_methods_still_resolve_for_old_rows() {
        let mut gone = orb();
        gone.deleted_at = Some(9);
        gone.seed_water_g = Some(300.0);
        let p = resolve_brew_method_preset(ORB, &[gone]).unwrap();
        assert_eq!(p.seed_water_g, Some(300.0));
        assert!(resolve_brew_method_preset(ORB, &[]).is_none());
        assert_eq!(
            resolve_brew_method_preset("Chemex", &[]).unwrap().id,
            "chemex"
        );
    }

    #[test]
    fn log_seeds_resolve_custom_methods() {
        let mut m = orb();
        m.style = BrewMethodStyle::Immersion;
        m.seed_water_g = Some(320.0);
        let input = BrewLogSeedInput {
            method: Some(ORB.to_owned()),
            custom_methods: vec![m],
            ..BrewLogSeedInput::default()
        };
        let s = brew_log_seeds(&input);
        assert_eq!(s.method, ORB);
        // Own water, style dose / temp.
        assert_eq!((s.dose, s.water, s.temp_c), (30.0, 320.0, Some(95.0)));
        // History of the custom method wins over its seeds.
        let with_row = BrewLogSeedInput {
            rows: vec![BrewSeedInput {
                brew_method: Some(ORB.to_owned()),
                dose_g: Some(21.0),
                water_g: Some(340.0),
                ..BrewSeedInput::default()
            }],
            ..input.clone()
        };
        let s = brew_log_seeds(&with_row);
        assert_eq!((s.dose, s.water), (21.0, 340.0));
        // The opening method may be a custom last-used one.
        let opening = BrewLogSeedInput {
            method: None,
            last_used_method: Some(ORB.to_owned()),
            ..input
        };
        assert_eq!(brew_log_seeds(&opening).method, ORB);
        // Unknown custom id (method list lost) → generic filter fallbacks.
        let s = brew_log_seeds(&BrewLogSeedInput {
            method: Some(ORB.to_owned()),
            ..BrewLogSeedInput::default()
        });
        assert_eq!((s.dose, s.water, s.temp_c), (15.0, 250.0, None));
    }

    #[test]
    fn blank_recipes_follow_the_style() {
        let kinds = |st| {
            let mut m = orb();
            m.style = st;
            blank_recipe_for_style(&m, "recipe:x", 7)
                .steps
                .iter()
                .map(|s| s.kind)
                .collect::<Vec<_>>()
        };
        use BrewStepKind::{Pour, Press, Steep};
        assert_eq!(kinds(BrewMethodStyle::Percolation), [Pour]);
        assert_eq!(kinds(BrewMethodStyle::Immersion), [Pour, Steep]);
        assert_eq!(kinds(BrewMethodStyle::Pressure), [Pour, Steep, Press]);
        assert_eq!(kinds(BrewMethodStyle::Cold), [Pour, Steep]);

        let mut m = orb();
        m.seed_water_g = Some(300.0);
        let r = blank_recipe_for_style(&m, "recipe:x", 7);
        assert_eq!(r.method, ORB);
        assert_eq!((r.dose_g, r.water_g, r.temp_c), (15.0, 300.0, Some(96.0)));
        assert_eq!(r.steps[0].target_water_g, Some(300.0));
        assert!(r.name.is_empty() && r.credit.is_none());
        assert_eq!((r.created_at, r.updated_at), (7, 7));
        // The facade speaks the same JSON.
        let json = blank_recipe_for_style_json(&serde_json::to_string(&m).unwrap(), "recipe:y", 8)
            .unwrap();
        let back: BrewRecipe = serde_json::from_str(&json).unwrap();
        assert_eq!(back.id, "recipe:y");
        assert_eq!(back.method, ORB);
    }

    fn check(
        label: &str,
        custom: Vec<CustomBrewMethod>,
        editing: Option<&str>,
    ) -> CustomMethodLabelCheck {
        validate_custom_method_label(&CustomMethodLabelInput {
            label: label.to_owned(),
            editing_id: editing.map(str::to_owned),
            custom_methods: custom,
            preset_labels: vec!["V60 / pourover".to_owned(), "French press".to_owned()],
        })
    }

    #[test]
    fn labels_are_trimmed_bounded_and_unique() {
        let ok = check("  ORB ", vec![], None);
        assert_eq!((ok.label.as_str(), ok.error), ("ORB", None));
        assert_eq!(
            check("   ", vec![], None).error,
            Some(CustomMethodLabelError::Empty)
        );
        assert_eq!(check(&"x".repeat(40), vec![], None).error, None);
        assert_eq!(
            check(&"é".repeat(41), vec![], None).error,
            Some(CustomMethodLabelError::TooLong)
        );
        // Presets by id (through normalization) and by shell label.
        for clash in [
            "chemex",
            "Kalita wave",
            "v60 / POUROVER",
            "other",
            "French  Press",
        ] {
            assert_eq!(
                check(clash, vec![], None).error,
                Some(CustomMethodLabelError::Duplicate),
                "{clash}"
            );
        }
        // Live custom methods, but not the one being renamed, nor tombstones.
        assert_eq!(
            check("orb", vec![orb()], None).error,
            Some(CustomMethodLabelError::Duplicate)
        );
        assert_eq!(check("orb", vec![orb()], Some(ORB)).error, None);
        let mut gone = orb();
        gone.deleted_at = Some(1);
        assert_eq!(check("ORB", vec![gone], None).error, None);
        // Facade.
        let json = validate_custom_method_label_json(r#"{"label":""}"#).unwrap();
        assert!(json.contains(r#""error":"empty""#), "{json}");
        let json = validate_custom_method_label_json(r#"{"label":"a"}"#).unwrap();
        assert!(json.contains(r#""error":null"#), "{json}");
    }

    #[test]
    fn custom_method_wire_shape_is_camel_case_and_tolerant() {
        let json = serde_json::to_string(&orb()).unwrap();
        assert!(json.contains(r#""style":"percolation""#), "{json}");
        assert!(json.contains(r#""createdAt":1"#), "{json}");
        assert!(
            !json.contains("seedDoseG"),
            "blank seeds are omitted: {json}"
        );
        // A minimal record (future/older shell) parses with defaults.
        let back: CustomBrewMethod =
            serde_json::from_str(r#"{"id":"custom:a","label":"A","createdAt":1,"updatedAt":2}"#)
                .unwrap();
        assert_eq!(back.style, BrewMethodStyle::Percolation);
        assert_eq!(back.deleted_at, None);
    }
}

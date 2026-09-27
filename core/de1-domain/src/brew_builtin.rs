//! Built-in brew recipes — real, credited recipes, "batteries included".
//!
//! Mirrors the built-in DE1 profiles ([`builtin`](crate::builtin)):
//!
//! - **Bundled** as one JSON array of [`BrewRecipe`],
//!   `de1-domain/recipes/builtin.json` (see the `README.md` there for the
//!   sources), embedded at compile time with [`include_str!`] — a
//!   build-time read, so the core stays sans-IO — parsed once and memoized.
//! - **Identified** by catalogue membership ([`is_builtin_recipe`]), the
//!   way the shells' `isBuiltinProfile` checks the built-in profile set.
//!   Built-in ids are stable, human-readable and live in the reserved
//!   `builtin:` namespace; user recipes mint `recipe:<uuid-v7>`.
//! - **Read-only**: never persisted by a shell, never written to a backup
//!   ([`export_backup_jsonl_from_json`](crate::export_backup_jsonl_from_json)
//!   drops them), and edited only through a copy ([`duplicate_recipe`]),
//!   which credits the original ("Adapted from …").
//!
//! Every recipe carries a [`credit`](BrewRecipe::credit) and a
//! [`source_url`](BrewRecipe::source_url) pointing at its primary source.
//! The numbers are the sources' own; the tests pin their shape (targets
//! rise, the last pour lands on `water_g`, auto timed steps have a
//! duration).
//!
//! This module also owns the one-time **migration** off the generic
//! "`<method>` classic" starter recipes the Brew Log shipped before the
//! catalogue ([`migrate_recipe_library`]).

use std::collections::HashMap;
use std::sync::OnceLock;

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::brew::{BrewRecipe, BrewStep, BrewStepKind, StepAdvance, normalize_brew_method};

/// The built-in recipes, embedded at compile time.
const BUILTIN_RECIPES_JSON: &str = include_str!("../recipes/builtin.json");

/// The parsed catalogue, memoized on first access.
static BUILTIN_RECIPES: OnceLock<Vec<BrewRecipe>> = OnceLock::new();

/// The reserved id namespace of the built-in recipes.
pub const BUILTIN_RECIPE_ID_PREFIX: &str = "builtin:";

/// How many built-in recipes Crema ships. The
/// `builtin_brew_recipes_ship_the_full_catalogue` test pins it.
pub const BUILTIN_BREW_RECIPE_COUNT: usize = 13;

/// The per-method default built-in — what the Brew setup opens on for a
/// method until the user makes another recipe that method's default.
/// Methods absent here (espresso, drip, free text) have no built-in.
const DEFAULT_BUILTIN_BY_METHOD: [(&str, &str); 9] = [
    ("pourover", "builtin:hoffmann-1-cup-v60"),
    ("aeropress", "builtin:aeropress-official"),
    ("french_press", "builtin:hoffmann-ultimate-french-press"),
    ("clever", "builtin:hoffmann-ultimate-clever"),
    ("chemex", "builtin:stumptown-chemex"),
    ("kalita_wave", "builtin:stumptown-kalita-wave"),
    ("cold_brew", "builtin:hoffmann-cold-brew"),
    ("moka", "builtin:hoffmann-moka"),
    ("siphon", "builtin:hario-syphon"),
];

fn catalogue() -> &'static [BrewRecipe] {
    // RS5/RS1, as `builtin_profiles`: never panic behind the bridges; the
    // `builtin_brew_recipes_all_load` test guarantees the asset parses.
    BUILTIN_RECIPES.get_or_init(|| serde_json::from_str(BUILTIN_RECIPES_JSON).unwrap_or_default())
}

/// Every built-in brew recipe, in catalogue order (grouped by method).
#[must_use]
pub fn builtin_brew_recipes() -> Vec<BrewRecipe> {
    catalogue().to_vec()
}

/// [`builtin_brew_recipes`] as a camelCase [`BrewRecipe`] JSON array.
#[must_use]
pub fn builtin_brew_recipes_json() -> String {
    // Infallible: the catalogue round-trips through serde (tested).
    serde_json::to_string(catalogue()).unwrap_or_else(|_| "[]".to_owned())
}

/// The built-in recipe with `id`, if there is one.
#[must_use]
pub fn builtin_brew_recipe(id: &str) -> Option<BrewRecipe> {
    catalogue().iter().find(|r| r.id == id).cloned()
}

/// Whether `id` names a built-in recipe — catalogue membership, like the
/// shells' `isBuiltinProfile`. Built-ins are read-only: the shells offer
/// "Duplicate to edit" instead of "Edit", never delete them, and never
/// persist or back them up.
#[must_use]
pub fn is_builtin_recipe(id: &str) -> bool {
    catalogue().iter().any(|r| r.id == id)
}

/// The id of `method`'s default built-in recipe (any case / padding), or
/// `None` for a method without one (espresso, drip, free text).
#[must_use]
pub fn default_builtin_recipe_id(method: &str) -> Option<&'static str> {
    let key = normalize_brew_method(method)?;
    DEFAULT_BUILTIN_BY_METHOD
        .iter()
        .find(|(m, _)| *m == key)
        .map(|(_, id)| *id)
}

/// The prefix a copy's credit gets — "Adapted from James Hoffmann — …".
const ADAPTED_FROM: &str = "Adapted from ";

/// A user-owned, editable copy of `source` — the "Duplicate" /
/// "Duplicate to edit" door. The copy gets `new_id`, the name
/// "`<name>` (copy)", `now_ms` timestamps, no favourite flag and no
/// tombstone. Its credit becomes "Adapted from `<credit>`" (left as-is
/// when it already reads "Adapted from …") and it keeps the
/// `source_url`, so the original author stays credited.
#[must_use]
pub fn duplicate_recipe(source: &BrewRecipe, new_id: &str, now_ms: i64) -> BrewRecipe {
    let credit = source
        .credit
        .as_deref()
        .map(str::trim)
        .filter(|c| !c.is_empty())
        .map(|c| {
            if c.starts_with(ADAPTED_FROM) {
                c.to_owned()
            } else {
                format!("{ADAPTED_FROM}{c}")
            }
        });
    BrewRecipe {
        id: new_id.to_owned(),
        name: format!("{} (copy)", source.name.trim()),
        favourite: false,
        created_at: now_ms,
        updated_at: now_ms,
        deleted_at: None,
        credit,
        ..source.clone()
    }
}

/// JSON-bridged [`duplicate_recipe`]. Input: a [`BrewRecipe`] JSON;
/// output: the copy's [`BrewRecipe`] JSON.
///
/// # Errors
/// The JSON parse error string on a malformed `recipe_json`.
pub fn duplicate_recipe_json(
    recipe_json: &str,
    new_id: &str,
    now_ms: i64,
) -> Result<String, String> {
    let recipe: BrewRecipe = serde_json::from_str(recipe_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&duplicate_recipe(&recipe, new_id, now_ms)).map_err(|e| e.to_string())
}

// ── Migration off the generic "classic" starters ────────────────────

/// A shell's stored recipe library: its recipes plus the per-method
/// default pointer (web `lastUsed`, Android `lastUsedByMethod`).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RecipeLibrary {
    pub recipes: Vec<BrewRecipe>,
    /// Method → the id of the recipe the Brew setup opens on.
    pub default_by_method: HashMap<String, String>,
}

/// [`migrate_recipe_library`]'s result.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RecipeLibraryMigration {
    /// The recipes to keep, in their stored order.
    pub recipes: Vec<BrewRecipe>,
    /// The pointers, with any pointer at a dropped recipe moved to the
    /// method's default built-in (or removed when it has none).
    pub default_by_method: HashMap<String, String>,
    /// The ids that were dropped — empty means nothing changed and the
    /// shell need not rewrite its store.
    pub dropped_ids: Vec<String>,
}

/// Clean a stored recipe library on load:
///
/// - drop every **untouched legacy starter** ([`is_legacy_default_recipe`])
///   — the generic "`<method>` classic" recipes the shells auto-persisted
///   when one was run before the catalogue existed;
/// - drop any stored copy of a **built-in** id (built-ins are bundled,
///   never stored);
/// - repoint a default pointer at a dropped recipe to the method's
///   default built-in ([`default_builtin_recipe_id`]), or remove it when
///   the method has none.
///
/// Edited starters (renamed, re-numbered, re-stepped, noted, favourited)
/// are user data and are kept, as is every tombstone.
#[must_use]
pub fn migrate_recipe_library(library: RecipeLibrary) -> RecipeLibraryMigration {
    let mut dropped_ids = Vec::new();
    let mut recipes = Vec::with_capacity(library.recipes.len());
    for r in library.recipes {
        if is_builtin_recipe(&r.id) || is_legacy_default_recipe(&r) {
            dropped_ids.push(r.id);
        } else {
            recipes.push(r);
        }
    }
    let default_by_method = library
        .default_by_method
        .into_iter()
        .filter_map(|(method, id)| {
            if dropped_ids.contains(&id) && !is_builtin_recipe(&id) {
                default_builtin_recipe_id(&method).map(|b| (method, b.to_owned()))
            } else {
                Some((method, id))
            }
        })
        .collect();
    RecipeLibraryMigration {
        recipes,
        default_by_method,
        dropped_ids,
    }
}

/// JSON-bridged [`migrate_recipe_library`]. Input: a [`RecipeLibrary`]
/// JSON; output: a [`RecipeLibraryMigration`] JSON.
///
/// # Errors
/// The JSON parse error string on a malformed `library_json`.
pub fn migrate_recipe_library_json(library_json: &str) -> Result<String, String> {
    let library: RecipeLibrary = serde_json::from_str(library_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&migrate_recipe_library(library)).map_err(|e| e.to_string())
}

/// Whether `recipe` is an untouched copy of the generic starter the
/// removed `default_recipe` generated: a live, unfavourited, uncredited,
/// note-less recipe named "`… classic`" (the shells' "V60 classic",
/// "French press classic", or the core's "`<method>` classic") whose
/// dose, water, temperature and steps equal that generator's output for
/// its method exactly.
///
/// `created_at == updated_at` can't be the test: the web persisted a run
/// starter via an upsert that re-stamps `updated_at`. Content equality is
/// the reliable signal — any edit in the recipe editor changes a number,
/// a step, the name or the notes; an "edit" that changed nothing leaves
/// a recipe indistinguishable from the starter, and dropping it loses
/// nothing.
#[must_use]
pub fn is_legacy_default_recipe(recipe: &BrewRecipe) -> bool {
    if recipe.deleted_at.is_some()
        || recipe.favourite
        || recipe.credit.is_some()
        || recipe.source_url.is_some()
        || recipe
            .notes
            .as_deref()
            .is_some_and(|n| !n.trim().is_empty())
        || !recipe.name.trim().ends_with(" classic")
    {
        return false;
    }
    let (dose, water, temp) = legacy_seeds(&recipe.method);
    recipe.dose_g == dose
        && recipe.water_g == water
        && recipe.temp_c == temp
        && recipe.steps == legacy_steps(&recipe.method, dose, water)
}

/// The retired generator's `(dose, water-or-yield, temp)` per method — a
/// frozen copy of the preset table as it stood when the starters were
/// generated, so later preset changes can't break the migration.
fn legacy_seeds(method: &str) -> (f32, f32, Option<f32>) {
    match method {
        "espresso" => (18.0, 36.0, Some(93.0)),
        "pourover" => (15.0, 250.0, Some(96.0)),
        "aeropress" => (14.0, 220.0, Some(90.0)),
        "french_press" => (30.0, 500.0, Some(95.0)),
        "moka" => (15.0, 150.0, None),
        "cold_brew" => (60.0, 700.0, None),
        "drip" => (30.0, 500.0, Some(94.0)),
        "siphon" => (20.0, 300.0, Some(92.0)),
        "clever" => (18.0, 300.0, Some(94.0)),
        _ => (15.0, 250.0, None),
    }
}

/// The retired generator's per-method step plan (frozen).
fn legacy_steps(method: &str, dose: f32, water: f32) -> Vec<BrewStep> {
    let pour = |target: f32| BrewStep {
        kind: BrewStepKind::Pour,
        target_water_g: Some(target),
        ..BrewStep::default()
    };
    let timed = |kind: BrewStepKind, s: u64| BrewStep {
        kind,
        duration_s: Some(s),
        ..BrewStep::default()
    };
    let open = |kind: BrewStepKind| BrewStep {
        kind,
        advance: StepAdvance::Manual,
        ..BrewStep::default()
    };
    match method {
        "pourover" => {
            let bloom = (dose * 3.0).round().min((water * 0.25).round());
            vec![
                BrewStep {
                    kind: BrewStepKind::Bloom,
                    target_water_g: Some(bloom),
                    duration_s: Some(45),
                    ..BrewStep::default()
                },
                pour((water * 0.6).round()),
                timed(BrewStepKind::Wait, 30),
                pour(water),
                open(BrewStepKind::Drawdown),
            ]
        }
        "aeropress" => vec![
            pour(water),
            timed(BrewStepKind::Stir, 10),
            timed(BrewStepKind::Steep, 60),
            timed(BrewStepKind::Press, 25),
        ],
        "french_press" => vec![
            pour(water),
            timed(BrewStepKind::Steep, 240),
            open(BrewStepKind::Press),
        ],
        "clever" => vec![
            pour(water),
            timed(BrewStepKind::Steep, 150),
            open(BrewStepKind::Drawdown),
        ],
        "siphon" => vec![
            pour(water),
            timed(BrewStepKind::Steep, 90),
            open(BrewStepKind::Drawdown),
        ],
        _ => vec![pour(water)],
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::brew::brew_method_preset;

    #[test]
    fn builtin_brew_recipes_all_load() {
        assert!(
            !builtin_brew_recipes().is_empty(),
            "recipes/builtin.json failed to parse"
        );
    }

    #[test]
    fn builtin_brew_recipes_ship_the_full_catalogue() {
        assert_eq!(builtin_brew_recipes().len(), BUILTIN_BREW_RECIPE_COUNT);
    }

    #[test]
    fn ids_are_unique_stable_and_namespaced() {
        let ids: Vec<String> = builtin_brew_recipes().into_iter().map(|r| r.id).collect();
        let mut dedup = ids.clone();
        dedup.sort();
        dedup.dedup();
        assert_eq!(dedup.len(), ids.len(), "duplicate built-in id in {ids:?}");
        // Stable: shells persist these ids as default pointers, so the set
        // is pinned. Renaming one is a migration, not an edit.
        assert_eq!(
            ids,
            [
                "builtin:hoffmann-1-cup-v60",
                "builtin:hoffmann-ultimate-v60",
                "builtin:kasuya-4-6",
                "builtin:hoffmann-ultimate-aeropress",
                "builtin:aeropress-official",
                "builtin:merikanto-wac-2021",
                "builtin:hoffmann-ultimate-french-press",
                "builtin:hoffmann-ultimate-clever",
                "builtin:stumptown-chemex",
                "builtin:stumptown-kalita-wave",
                "builtin:hoffmann-cold-brew",
                "builtin:hoffmann-moka",
                "builtin:hario-syphon",
            ]
        );
        for id in &ids {
            assert!(id.starts_with(BUILTIN_RECIPE_ID_PREFIX), "{id}");
            assert!(is_builtin_recipe(id));
        }
        assert!(!is_builtin_recipe("recipe:0191"));
        assert!(!is_builtin_recipe("builtin:retired-or-typo"));
    }

    #[test]
    fn every_builtin_is_credited_with_a_source() {
        for r in builtin_brew_recipes() {
            let credit = r.credit.as_deref().unwrap_or_default();
            assert!(!credit.trim().is_empty(), "{} has no credit", r.id);
            let url = r.source_url.as_deref().unwrap_or_default();
            assert!(url.starts_with("https://"), "{} source_url {url:?}", r.id);
            assert!(!r.name.trim().is_empty());
            assert!(!r.steps.is_empty(), "{} has no steps", r.id);
            assert_eq!(r.deleted_at, None);
            assert!(!r.favourite);
            // The method is stored normalized and is a curated preset.
            assert_eq!(
                normalize_brew_method(&r.method).as_deref(),
                Some(r.method.as_str())
            );
            assert!(
                brew_method_preset(&r.method).is_some(),
                "{}: {}",
                r.id,
                r.method
            );
        }
    }

    #[test]
    fn every_builtins_steps_are_well_formed() {
        for r in builtin_brew_recipes() {
            // Water targets strictly rise.
            let targets: Vec<f32> = r.steps.iter().filter_map(|s| s.target_water_g).collect();
            for w in targets.windows(2) {
                assert!(w[1] > w[0], "{}: targets must rise, got {targets:?}", r.id);
            }
            // For every recipe with pours, the last pour lands on the
            // recipe's water and planned_pour_total_g agrees. (The syphon
            // is the one pour-less plan: its water is in the bottom bulb
            // before the session starts.)
            if targets.is_empty() {
                assert_eq!(r.id, "builtin:hario-syphon", "unexpected pour-less recipe");
                assert_eq!(r.planned_pour_total_g(), None);
            } else {
                assert_eq!(targets.last().copied(), Some(r.water_g), "{}", r.id);
                assert_eq!(r.planned_pour_total_g(), Some(r.water_g), "{}", r.id);
            }
            for (i, s) in r.steps.iter().enumerate() {
                // An auto step that isn't a pour needs a duration, else it
                // could never auto-advance.
                if s.advance == StepAdvance::Auto && s.target_water_g.is_none() {
                    assert!(
                        s.duration_s.is_some(),
                        "{} step {i}: auto timed step without a duration",
                        r.id
                    );
                }
                if let Some(d) = s.duration_s {
                    assert!(d > 0, "{} step {i}", r.id);
                }
                if let Some(l) = &s.label {
                    assert!(!l.trim().is_empty(), "{} step {i}: blank label", r.id);
                }
            }
            assert!(r.dose_g > 0.0 && r.water_g > r.dose_g, "{}", r.id);
        }
    }

    #[test]
    fn numbers_are_the_sources_own() {
        let get = |id: &str| builtin_brew_recipe(id).unwrap();
        let v60 = get("builtin:hoffmann-1-cup-v60");
        assert_eq!(
            (v60.dose_g, v60.water_g, v60.temp_c),
            (15.0, 250.0, Some(100.0))
        );
        assert_eq!(v60.steps.len(), 10);
        assert_eq!(v60.steps[0].kind, BrewStepKind::Bloom);
        assert_eq!(v60.steps[0].label.as_deref(), Some("Swirl gently at 0:10"));
        let k = get("builtin:kasuya-4-6");
        assert_eq!((k.dose_g, k.water_g, k.temp_c), (20.0, 300.0, Some(92.0)));
        let wac = get("builtin:merikanto-wac-2021");
        assert_eq!(
            wac.notes.as_deref(),
            Some("Inverted, two rinsed paper filters")
        );
        let cold = get("builtin:hoffmann-cold-brew");
        assert_eq!(
            (cold.dose_g, cold.water_g, cold.temp_c),
            (75.0, 1000.0, None)
        );
        let steep = &cold.steps[2];
        assert_eq!(
            (steep.kind, steep.duration_s, steep.advance),
            (BrewStepKind::Steep, Some(43_200), StepAdvance::Manual)
        );
        let chemex = get("builtin:stumptown-chemex");
        assert_eq!(
            (chemex.method.as_str(), chemex.dose_g, chemex.water_g),
            ("chemex", 42.0, 700.0)
        );
        let kalita = get("builtin:stumptown-kalita-wave");
        assert_eq!(
            (kalita.method.as_str(), kalita.dose_g, kalita.water_g),
            ("kalita_wave", 21.0, 345.0)
        );
    }

    #[test]
    fn each_default_exists_and_matches_its_method() {
        let with_default = [
            "pourover",
            "aeropress",
            "french_press",
            "clever",
            "chemex",
            "kalita_wave",
            "cold_brew",
            "moka",
            "siphon",
        ];
        for m in with_default {
            let id = default_builtin_recipe_id(m).unwrap_or_else(|| panic!("{m} has no default"));
            let r = builtin_brew_recipe(id).unwrap_or_else(|| panic!("{id} missing"));
            assert_eq!(r.method, m);
        }
        assert_eq!(
            default_builtin_recipe_id(" Pourover "),
            Some("builtin:hoffmann-1-cup-v60")
        );
        for m in ["espresso", "drip", "other", "karlsbad_kanne", "", "  "] {
            assert_eq!(default_builtin_recipe_id(m), None, "{m}");
        }
        // Every method with a built-in has a default.
        for r in builtin_brew_recipes() {
            assert!(
                default_builtin_recipe_id(&r.method).is_some(),
                "{}",
                r.method
            );
        }
    }

    #[test]
    fn catalogue_round_trips_through_json() {
        let json = builtin_brew_recipes_json();
        let back: Vec<BrewRecipe> = serde_json::from_str(&json).unwrap();
        assert_eq!(back, builtin_brew_recipes());
        assert!(json.contains(r#""sourceUrl":"https://"#), "camelCase wire");
        assert!(json.contains(r#""advance":"manual""#));
    }

    #[test]
    fn duplicating_a_builtin_credits_the_original() {
        let src = builtin_brew_recipe("builtin:hoffmann-ultimate-v60").unwrap();
        let copy = duplicate_recipe(&src, "recipe:new", 1_700);
        assert_eq!(copy.id, "recipe:new");
        assert!(!is_builtin_recipe(&copy.id));
        assert_eq!(copy.name, "Ultimate V60 (copy)");
        assert_eq!(
            copy.credit.as_deref(),
            Some("Adapted from James Hoffmann — The Ultimate V60 Technique (2019)")
        );
        assert_eq!(copy.source_url, src.source_url);
        assert_eq!((copy.created_at, copy.updated_at), (1_700, 1_700));
        assert_eq!(copy.steps, src.steps);
        assert_eq!(
            (copy.dose_g, copy.water_g, copy.temp_c),
            (src.dose_g, src.water_g, src.temp_c)
        );
        // An already-adapted credit isn't doubled.
        let moka = builtin_brew_recipe("builtin:hoffmann-moka").unwrap();
        let again = duplicate_recipe(&duplicate_recipe(&moka, "recipe:a", 1), "recipe:b", 2);
        assert_eq!(again.credit, moka.credit);
        assert_eq!(again.name, "Moka Pot (copy) (copy)");
        // An uncredited recipe stays uncredited.
        let mut own = src.clone();
        own.credit = None;
        own.source_url = None;
        assert_eq!(duplicate_recipe(&own, "recipe:c", 3).credit, None);
        // JSON bridge.
        let json =
            duplicate_recipe_json(&serde_json::to_string(&src).unwrap(), "recipe:j", 9).unwrap();
        let back: BrewRecipe = serde_json::from_str(&json).unwrap();
        assert_eq!(back.id, "recipe:j");
        assert!(duplicate_recipe_json("nope", "x", 0).is_err());
    }

    // ── migration ────────────────────────────────────────────────

    /// What the removed `default_recipe` produced, renamed the way the
    /// shells named it.
    fn legacy(method: &str, id: &str, name: &str) -> BrewRecipe {
        let (dose, water, temp) = legacy_seeds(method);
        BrewRecipe {
            id: id.to_owned(),
            name: name.to_owned(),
            method: method.to_owned(),
            dose_g: dose,
            water_g: water,
            temp_c: temp,
            steps: legacy_steps(method, dose, water),
            notes: None,
            favourite: false,
            created_at: 1_000,
            updated_at: 5_000,
            deleted_at: None,
            credit: None,
            source_url: None,
        }
    }

    #[test]
    fn legacy_pourover_starter_is_the_old_plan() {
        let r = legacy("pourover", "recipe:a", "V60 classic");
        let plan: Vec<_> = r
            .steps
            .iter()
            .map(|s| (s.kind, s.target_water_g, s.duration_s, s.advance))
            .collect();
        assert_eq!(
            plan,
            vec![
                (BrewStepKind::Bloom, Some(45.0), Some(45), StepAdvance::Auto),
                (BrewStepKind::Pour, Some(150.0), None, StepAdvance::Auto),
                (BrewStepKind::Wait, None, Some(30), StepAdvance::Auto),
                (BrewStepKind::Pour, Some(250.0), None, StepAdvance::Auto),
                (BrewStepKind::Drawdown, None, None, StepAdvance::Manual),
            ]
        );
        assert!(is_legacy_default_recipe(&r));
    }

    #[test]
    fn an_untouched_starter_is_dropped_and_its_pointer_repointed() {
        let lib = RecipeLibrary {
            recipes: vec![
                legacy("pourover", "recipe:v60", "V60 classic"),
                legacy("espresso", "recipe:esp", "Espresso classic"),
                legacy("french_press", "recipe:fp", "french_press classic"),
            ],
            default_by_method: HashMap::from([
                ("pourover".to_owned(), "recipe:v60".to_owned()),
                ("espresso".to_owned(), "recipe:esp".to_owned()),
            ]),
        };
        let out = migrate_recipe_library(lib);
        assert!(out.recipes.is_empty());
        assert_eq!(out.dropped_ids, ["recipe:v60", "recipe:esp", "recipe:fp"]);
        // Pourover now opens on its built-in default; espresso has none.
        assert_eq!(
            out.default_by_method,
            HashMap::from([(
                "pourover".to_owned(),
                "builtin:hoffmann-1-cup-v60".to_owned()
            )])
        );
    }

    #[test]
    fn an_edited_starter_is_user_data_and_is_kept() {
        let mut renamed = legacy("pourover", "recipe:renamed", "My V60");
        renamed.updated_at = renamed.created_at;
        let mut redosed = legacy("pourover", "recipe:dose", "V60 classic");
        redosed.dose_g = 16.0;
        let mut restepped = legacy("aeropress", "recipe:steps", "AeroPress classic");
        restepped.steps[2].duration_s = Some(90);
        let mut noted = legacy("clever", "recipe:notes", "Clever classic");
        noted.notes = Some("finer".to_owned());
        let mut starred = legacy("siphon", "recipe:fav", "Siphon classic");
        starred.favourite = true;
        let mut labelled = legacy("moka", "recipe:label", "Moka classic");
        labelled.steps[0].label = Some("Fill".to_owned());
        let mut tomb = legacy("drip", "recipe:tomb", "Drip classic");
        tomb.deleted_at = Some(9);
        let kept = vec![renamed, redosed, restepped, noted, starred, labelled, tomb];
        let lib = RecipeLibrary {
            recipes: kept.clone(),
            default_by_method: HashMap::from([("pourover".to_owned(), "recipe:dose".to_owned())]),
        };
        let out = migrate_recipe_library(lib.clone());
        assert_eq!(out.recipes, kept);
        assert!(out.dropped_ids.is_empty());
        assert_eq!(out.default_by_method, lib.default_by_method);
    }

    #[test]
    fn stored_builtins_are_dropped_but_pointers_at_builtins_survive() {
        let stored = builtin_brew_recipe("builtin:hario-syphon").unwrap();
        let lib = RecipeLibrary {
            recipes: vec![stored],
            default_by_method: HashMap::from([
                ("siphon".to_owned(), "builtin:hario-syphon".to_owned()),
                ("pourover".to_owned(), "builtin:kasuya-4-6".to_owned()),
            ]),
        };
        let out = migrate_recipe_library(lib.clone());
        assert!(out.recipes.is_empty());
        assert_eq!(out.dropped_ids, ["builtin:hario-syphon"]);
        assert_eq!(out.default_by_method, lib.default_by_method);
    }

    #[test]
    fn migration_json_facade_and_old_recipes_parse() {
        // A recipe stored before `credit` / `sourceUrl` existed parses
        // unchanged, and the new fields stay off the wire when absent.
        let old = r#"{"recipes":[{"id":"recipe:x","name":"V60 classic","method":"pourover",
            "doseG":15,"waterG":250,"tempC":96,
            "steps":[{"kind":"bloom","label":null,"targetWaterG":45,"durationS":45,"advance":"auto"},
                     {"kind":"pour","label":null,"targetWaterG":150,"durationS":null,"advance":"auto"},
                     {"kind":"wait","label":null,"targetWaterG":null,"durationS":30,"advance":"auto"},
                     {"kind":"pour","label":null,"targetWaterG":250,"durationS":null,"advance":"auto"},
                     {"kind":"drawdown","label":null,"targetWaterG":null,"durationS":null,"advance":"manual"}],
            "notes":null,"favourite":false,"createdAt":1,"updatedAt":2,"deletedAt":null}],
            "defaultByMethod":{"pourover":"recipe:x"}}"#;
        let out: RecipeLibraryMigration =
            serde_json::from_str(&migrate_recipe_library_json(old).unwrap()).unwrap();
        assert_eq!(out.dropped_ids, ["recipe:x"]);
        assert_eq!(
            out.default_by_method["pourover"],
            "builtin:hoffmann-1-cup-v60"
        );
        assert!(migrate_recipe_library_json("[").is_err());
        // Empty input is a no-op.
        let none: RecipeLibraryMigration =
            serde_json::from_str(&migrate_recipe_library_json("{}").unwrap()).unwrap();
        assert!(none.dropped_ids.is_empty() && none.recipes.is_empty());
    }
}

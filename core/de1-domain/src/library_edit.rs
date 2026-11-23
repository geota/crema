//! # library_edit
//!
//! The Save of a bag, roaster or profile editor, onto the row as it stands now
//! (save-boundaries review F2, 2026-11-21). The port of Android's
//! `BeanDraft.kt` (geota/crema#324), moved here so both shells share it.
//!
//! An editor opens on a row (`base`) and the user edits a copy of it
//! (`edited`). Meanwhile the stored row (`current`) may have moved on: a shot
//! debited `remaining`, a Visualizer pull or an import rewrote fields. Save
//! applies **only the fields the user changed** (those where `edited` differs
//! from `base`) onto `current`; every other field keeps `current`'s value.
//! Nested objects (`origin`, `metadata`) merge key by key the same way; one
//! the editor opened as `null` counts as empty.
//!
//! The row's identity (`id`, `created_at`) is always `current`'s. When the
//! result differs from `current`, `updated_at` is bumped to `now_unix_ms`
//! (never backwards); a Save that changes nothing returns `current` as it is,
//! so it doesn't look like a fresh edit to sync.
//!
//! A bag's saved grinds (`Bean::grinds`, #488) merge row by row, by grinder
//! id ([`crate::bag_grind`]): the editor's edits, adds, removals and star move
//! onto the stored rows in one Save, and a row auto-saved meanwhile stays.
//!
//! A bag's size and remaining ([`edited_remaining`], REVIEW-CORE H6): a
//! first size fills the bag; a bag that had a size keeps its remaining.

use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use typeshare::typeshare;

use crate::bean::{Bean, Roaster};
use crate::crema_profile::CremaProfile;

/// `current` with the keys that differ between `base` and `edited` taken
/// from `edited`, recursing into objects all three hold.
fn changes_over(
    current: &Map<String, Value>,
    edited: &Map<String, Value>,
    base: &Map<String, Value>,
) -> Map<String, Value> {
    let mut out = Map::new();
    let keys = current.keys().chain(edited.keys()).chain(base.keys());
    for key in keys {
        if out.contains_key(key) {
            continue;
        }
        if let Some(v) = merged(current.get(key), edited.get(key), base.get(key)) {
            out.insert(key.clone(), v);
        }
    }
    out
}

fn merged(current: Option<&Value>, edited: Option<&Value>, base: Option<&Value>) -> Option<Value> {
    if edited == base {
        return current.cloned();
    }
    match (current, edited, base) {
        (Some(Value::Object(c)), Some(Value::Object(e)), Some(Value::Object(b))) => {
            Some(Value::Object(changes_over(c, e, b)))
        }
        // An object the editor opened empty (`metadata` starts as `null`):
        // the user's keys land beside the ones added elsewhere.
        (Some(Value::Object(c)), Some(Value::Object(e)), None | Some(Value::Null)) => {
            Some(Value::Object(changes_over(c, e, &Map::new())))
        }
        _ => edited.cloned(),
    }
}

/// The three-way merge over any row type. Falls back to a top-level merge
/// (each changed field whole from `edited`) when a nested merge doesn't make
/// a valid row, and to `edited` if even that fails.
pub(crate) fn merge_row<T: Serialize + DeserializeOwned + Clone>(
    base: &T,
    edited: &T,
    current: &T,
) -> T {
    let tree = |row: &T| match serde_json::to_value(row) {
        Ok(Value::Object(m)) => Some(m),
        _ => None,
    };
    let (Some(b), Some(e), Some(c)) = (tree(base), tree(edited), tree(current)) else {
        return edited.clone();
    };
    if let Ok(row) = serde_json::from_value(Value::Object(changes_over(&c, &e, &b))) {
        return row;
    }
    let mut flat = c.clone();
    for key in e.keys().chain(b.keys()) {
        if e.get(key) != b.get(key) {
            match e.get(key) {
                Some(v) => flat.insert(key.clone(), v.clone()),
                None => flat.remove(key),
            };
        }
    }
    serde_json::from_value(Value::Object(flat)).unwrap_or_else(|_| edited.clone())
}

/// A bag editor's Save: the fields the user changed (`edited` against
/// `base`, the bag the editor opened on) applied onto `current`, the bag as
/// stored now. See the module docs.
#[must_use]
pub fn merge_bean_edit(base: &Bean, edited: &Bean, current: &Bean, now_unix_ms: i64) -> Bean {
    let mut out = merge_row(base, edited, current);
    // The saved grinds merge row by row, by grinder id (#488).
    out.grinds = crate::bag_grind::merge_bag_grinds(
        base.grinds.as_deref(),
        edited.grinds.as_deref(),
        current.grinds.as_deref(),
    );
    if out.grinds.is_some() {
        crate::bag_grind::mark_legacy_grind_pending(current, &mut out);
    }
    out.id.clone_from(&current.id);
    out.created_at = current.created_at;
    out.updated_at = current.updated_at;
    if out != *current {
        out.updated_at = now_unix_ms.max(current.updated_at);
    }
    out
}

/// What [`edited_remaining`] reads: the bag editor's size and remaining.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RemainingEdit {
    /// The bag the editor opened on; `None` for a new bag.
    pub base: Option<Bean>,
    /// The size in the editor now (g).
    pub bag_size: f32,
    /// The remaining in the editor now (g).
    pub remaining: f32,
    /// The user set Remaining in this editor: their value stands.
    pub remaining_touched: bool,
}

fn grams(v: f32) -> f32 {
    if v.is_finite() { v.max(0.0) } else { 0.0 }
}

/// The bag's remaining after a bag-editor edit (REVIEW-CORE H6), one rule
/// for both shells, both while editing (to show it) and on Save (into
/// [`merge_bean_edit`]):
///
/// 1. Remaining set by the user in this editor: it stands.
/// 2. A new bag, or one that had no size before: the size (a first size
///    fills the bag; a remaining of 0 would read as empty).
/// 3. A bag that had a size: its remaining as it was. A half-used bag keeps
///    what it has left, and a bag that **ran out stays empty** when its size
///    is corrected: a resize is a correction, not a refill. A refill is set
///    on Remaining (or a Duplicate, which starts full).
///
/// Negative or non-finite grams read as 0.
#[must_use]
pub fn edited_remaining(edit: &RemainingEdit) -> f32 {
    if edit.remaining_touched {
        return grams(edit.remaining);
    }
    match edit.base.as_ref() {
        Some(b) if grams(b.bag_size) > 0.0 => grams(b.remaining),
        _ => grams(edit.bag_size),
    }
}

/// [`edited_remaining`] over JSON: a [`RemainingEdit`] JSON in, the grams
/// (a JSON number) out.
///
/// # Errors
///
/// The JSON error string on malformed input.
pub fn edited_remaining_json(input_json: &str) -> Result<String, String> {
    let edit: RemainingEdit = serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&edited_remaining(&edit)).map_err(|e| e.to_string())
}

/// A roaster editor's Save — [`merge_bean_edit`] for a [`Roaster`].
#[must_use]
pub fn merge_roaster_edit(
    base: &Roaster,
    edited: &Roaster,
    current: &Roaster,
    now_unix_ms: i64,
) -> Roaster {
    let mut out = merge_row(base, edited, current);
    out.id.clone_from(&current.id);
    out.created_at = current.created_at;
    out.updated_at = current.updated_at;
    if out != *current {
        out.updated_at = now_unix_ms.max(current.updated_at);
    }
    out
}

/// The keys of a profile's body: its segments and how many lead as
/// preinfusion. They move together (a count is only right for its own
/// segments), so a Save that changed either takes both from the editor.
const PROFILE_BODY: [&str; 2] = ["segments", "preinfuseStepCount"];

/// [`merge_profile_edit`] over the rows as JSON trees, so keys a shell keeps
/// that the core doesn't model ride along untouched.
fn merge_profile_tree(
    base: &Map<String, Value>,
    edited: &Map<String, Value>,
    current: &Map<String, Value>,
) -> Option<Value> {
    let mut out = changes_over(current, edited, base);
    if PROFILE_BODY.iter().any(|k| edited.get(*k) != base.get(*k)) {
        for key in PROFILE_BODY {
            match edited.get(key) {
                Some(v) => out.insert(key.to_owned(), v.clone()),
                None => out.remove(key),
            };
        }
    }
    // The row's identity is the stored one's.
    for key in ["id", "source"] {
        if let Some(v) = current.get(key) {
            out.insert(key.to_owned(), v.clone());
        }
    }
    let typed: CremaProfile = serde_json::from_value(Value::Object(out.clone())).ok()?;
    let brew_temp = crate::with_derived_brew_temp(typed).brew_temp;
    out.insert("brewTemp".to_owned(), serde_json::json!(brew_temp));
    Some(Value::Object(out))
}

/// A profile editor's Save (REVIEW-FINAL S6, REVIEW-FINAL-2 N2), field by
/// field like [`merge_bean_edit`]: the fields the user changed (`edited`
/// against `base`, the profile the editor opened on) applied onto `current`,
/// the profile as stored now. So a pin, a Quick Save in place or a rename
/// written meanwhile (another tab, a remote's Save on the hub) stays unless
/// the user changed that field too.
///
/// - The body (the segments and the preinfusion count) is one field: an
///   edit to it takes the editor's whole body, since a step list merged step
///   by step could leave a recipe nobody wrote.
/// - `brew_temp` is derived again from the result
///   ([`crate::with_derived_brew_temp`]).
/// - `id` and `source` are `current`'s.
///
/// A profile has no edit stamp, so unlike the bag merge there is no
/// `updated_at` to bump.
#[must_use]
pub fn merge_profile_edit(
    base: &CremaProfile,
    edited: &CremaProfile,
    current: &CremaProfile,
) -> CremaProfile {
    let tree = |p: &CremaProfile| match serde_json::to_value(p) {
        Ok(Value::Object(m)) => Some(m),
        _ => None,
    };
    let (Some(b), Some(e), Some(c)) = (tree(base), tree(edited), tree(current)) else {
        return edited.clone();
    };
    merge_profile_tree(&b, &e, &c)
        .and_then(|v| serde_json::from_value(v).ok())
        .unwrap_or_else(|| edited.clone())
}

/// [`merge_profile_edit`] over profile JSON (the FFI / wasm boundary). Keys
/// the core doesn't model are merged too and kept.
///
/// # Errors
///
/// The JSON error string when a row is malformed (not an object, or not a
/// profile).
pub fn merge_profile_edit_json(
    base_json: &str,
    edited_json: &str,
    current_json: &str,
) -> Result<String, String> {
    let parse = |s: &str| -> Result<Map<String, Value>, String> {
        serde_json::from_str::<CremaProfile>(s).map_err(|e| e.to_string())?;
        match serde_json::from_str(s).map_err(|e| e.to_string())? {
            Value::Object(m) => Ok(m),
            _ => Err("a profile is a JSON object".to_owned()),
        }
    };
    let (b, e, c) = (parse(base_json)?, parse(edited_json)?, parse(current_json)?);
    let out = merge_profile_tree(&b, &e, &c).unwrap_or(Value::Object(e));
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

/// [`merge_bean_edit`] over `Bean` JSON (the FFI / wasm boundary).
///
/// # Errors
///
/// The JSON error string when a row is malformed.
pub fn merge_bean_edit_json(
    base_json: &str,
    edited_json: &str,
    current_json: &str,
    now_unix_ms: i64,
) -> Result<String, String> {
    let parse = |s: &str| serde_json::from_str::<Bean>(s).map_err(|e| e.to_string());
    let out = merge_bean_edit(
        &parse(base_json)?,
        &parse(edited_json)?,
        &parse(current_json)?,
        now_unix_ms,
    );
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

/// [`merge_roaster_edit`] over `Roaster` JSON (the FFI / wasm boundary).
///
/// # Errors
///
/// The JSON error string when a row is malformed.
pub fn merge_roaster_edit_json(
    base_json: &str,
    edited_json: &str,
    current_json: &str,
    now_unix_ms: i64,
) -> Result<String, String> {
    let parse = |s: &str| serde_json::from_str::<Roaster>(s).map_err(|e| e.to_string());
    let out = merge_roaster_edit(
        &parse(base_json)?,
        &parse(edited_json)?,
        &parse(current_json)?,
        now_unix_ms,
    );
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    const OPENED_AT: i64 = 1_000;
    const NOW: i64 = 5_000;

    fn bag() -> Bean {
        let mut b = Bean::new("bean:1".into(), "Geometry".into(), OPENED_AT);
        b.remaining = 250.0;
        b.bag_size = 250.0;
        b.notes = "fruity".into();
        b.origin.country = Some("Ethiopia".into());
        b
    }

    // ── Size and remaining (REVIEW-CORE H6) ──

    fn sized(size: f32, remaining: f32) -> Bean {
        let mut b = bag();
        b.bag_size = size;
        b.remaining = remaining;
        b
    }

    fn remaining_after(base: Option<Bean>, size: f32, remaining: f32, touched: bool) -> f32 {
        edited_remaining(&RemainingEdit {
            base,
            bag_size: size,
            remaining,
            remaining_touched: touched,
        })
    }

    #[test]
    fn a_new_bag_starts_full() {
        assert_eq!(remaining_after(None, 340.0, 0.0, false), 340.0);
        // A remaining the user typed stands, even on a new bag.
        assert_eq!(remaining_after(None, 340.0, 120.0, true), 120.0);
    }

    #[test]
    fn a_first_size_fills_a_bag_that_had_none() {
        assert_eq!(
            remaining_after(Some(sized(0.0, 0.0)), 250.0, 0.0, false),
            250.0
        );
    }

    #[test]
    fn a_half_used_bag_keeps_its_remaining_on_a_resize() {
        assert_eq!(
            remaining_after(Some(sized(250.0, 90.0)), 340.0, 90.0, false),
            90.0
        );
    }

    #[test]
    fn a_bag_that_ran_out_stays_empty_on_a_resize() {
        // REVIEW-CORE divergence 8: 250 g bag, remaining 0, size corrected to
        // 340 g. The rule picked: it stays empty (web refilled it).
        assert_eq!(
            remaining_after(Some(sized(250.0, 0.0)), 340.0, 0.0, false),
            0.0
        );
        // A refill is the user's, on Remaining.
        assert_eq!(
            remaining_after(Some(sized(250.0, 0.0)), 340.0, 340.0, true),
            340.0
        );
    }

    #[test]
    fn bad_grams_read_as_zero() {
        assert_eq!(remaining_after(None, f32::NAN, 0.0, false), 0.0);
        assert_eq!(remaining_after(None, 250.0, -5.0, true), 0.0);
        assert_eq!(
            remaining_after(Some(sized(250.0, f32::INFINITY)), 250.0, 0.0, false),
            0.0
        );
    }

    #[test]
    fn edited_remaining_json_round_trips() {
        let input = serde_json::json!({
            "base": sized(250.0, 0.0),
            "bagSize": 340.0,
            "remaining": 0.0,
        });
        assert_eq!(edited_remaining_json(&input.to_string()).unwrap(), "0.0");
        assert_eq!(
            edited_remaining_json(r#"{"bagSize": 340}"#).unwrap(),
            "340.0"
        );
        assert!(edited_remaining_json("nope").is_err());
    }

    #[test]
    fn a_debit_during_the_edit_survives_and_the_users_change_wins() {
        let base = bag();
        let mut edited = base.clone();
        edited.name = "Geometry II".into();
        let mut current = base.clone();
        current.remaining = 232.0; // a shot debited the bag
        current.updated_at = 2_000;
        let saved = merge_bean_edit(&base, &edited, &current, NOW);
        assert_eq!(saved.name, "Geometry II");
        assert_eq!(saved.remaining, 232.0);
        assert_eq!(saved.updated_at, NOW);
        assert_eq!(saved.created_at, OPENED_AT);
    }

    #[test]
    fn a_field_the_user_changed_wins_over_a_change_elsewhere() {
        let base = bag();
        let mut edited = base.clone();
        edited.remaining = 100.0; // the user corrected the weight
        let mut current = base.clone();
        current.remaining = 232.0;
        assert_eq!(
            merge_bean_edit(&base, &edited, &current, NOW).remaining,
            100.0
        );
    }

    #[test]
    fn a_visualizer_pull_during_the_edit_survives() {
        let base = bag();
        let mut edited = base.clone();
        edited.rating = 4;
        let mut current = base.clone();
        current.visualizer_id = Some("vb-1".into());
        current.tasting_notes = "from Visualizer".into();
        current.origin.region = Some("Guji".into());
        let saved = merge_bean_edit(&base, &edited, &current, NOW);
        assert_eq!(saved.rating, 4);
        assert_eq!(saved.visualizer_id.as_deref(), Some("vb-1"));
        assert_eq!(saved.tasting_notes, "from Visualizer");
        assert_eq!(saved.origin.region.as_deref(), Some("Guji"));
        assert_eq!(saved.origin.country.as_deref(), Some("Ethiopia"));
    }

    #[test]
    fn nested_fields_merge_key_by_key() {
        let base = bag();
        let mut edited = base.clone();
        edited.origin.country = Some("Kenya".into());
        edited.metadata = serde_json::json!({ "crema": { "a": 1 } });
        let mut current = base.clone();
        current.origin.variety = Some("SL28".into());
        current.metadata = serde_json::json!({ "bc": { "uuid": "x" } });
        let saved = merge_bean_edit(&base, &edited, &current, NOW);
        assert_eq!(saved.origin.country.as_deref(), Some("Kenya"));
        assert_eq!(saved.origin.variety.as_deref(), Some("SL28"));
        assert_eq!(
            saved.metadata,
            serde_json::json!({ "bc": { "uuid": "x" }, "crema": { "a": 1 } })
        );
    }

    #[test]
    fn clearing_a_field_clears_it() {
        let base = bag();
        let mut edited = base.clone();
        edited.origin.country = None;
        edited.notes = String::new();
        let saved = merge_bean_edit(&base, &edited, &base, NOW);
        assert_eq!(saved.origin.country, None);
        assert_eq!(saved.notes, "");
    }

    #[test]
    fn a_save_with_no_changes_leaves_the_row_untouched() {
        let base = bag();
        let mut current = base.clone();
        current.remaining = 232.0;
        current.updated_at = 2_000;
        assert_eq!(merge_bean_edit(&base, &base, &current, NOW), current);
    }

    #[test]
    fn identity_and_time_never_go_backwards() {
        let base = bag();
        let mut edited = base.clone();
        edited.id = "bean:other".into();
        edited.created_at = 0;
        edited.name = "Renamed".into();
        let mut current = base.clone();
        current.updated_at = 9_000; // a row stamped by a clock ahead of ours
        let saved = merge_bean_edit(&base, &edited, &current, NOW);
        assert_eq!(saved.id, "bean:1");
        assert_eq!(saved.created_at, OPENED_AT);
        assert_eq!(saved.updated_at, 9_000);
    }

    #[test]
    fn a_roaster_edit_merges_the_same_way() {
        let base = Roaster::new("roaster:1".into(), "Onyx".into(), OPENED_AT);
        let mut edited = base.clone();
        edited.city = Some("Rogers".into());
        let mut current = base.clone();
        current.visualizer_id = Some("vr-1".into());
        current.website = Some("https://onyx.example".into());
        let saved = merge_roaster_edit(&base, &edited, &current, NOW);
        assert_eq!(saved.city.as_deref(), Some("Rogers"));
        assert_eq!(saved.visualizer_id.as_deref(), Some("vr-1"));
        assert_eq!(saved.website.as_deref(), Some("https://onyx.example"));
        assert_eq!(saved.updated_at, NOW);
        assert_eq!(merge_roaster_edit(&base, &base, &current, NOW), current);
    }

    #[test]
    fn the_json_facades_round_trip_and_reject_garbage() {
        let base = bag();
        let mut edited = base.clone();
        edited.name = "New".into();
        let j = |b: &Bean| serde_json::to_string(b).unwrap();
        let out = merge_bean_edit_json(&j(&base), &j(&edited), &j(&base), NOW).unwrap();
        assert_eq!(
            serde_json::from_str::<Bean>(&out).unwrap(),
            merge_bean_edit(&base, &edited, &base, NOW)
        );
        assert!(merge_bean_edit_json("nope", &j(&edited), &j(&base), NOW).is_err());
        let r = Roaster::new("roaster:1".into(), "Onyx".into(), OPENED_AT);
        let rj = serde_json::to_string(&r).unwrap();
        assert_eq!(
            serde_json::from_str::<Roaster>(&merge_roaster_edit_json(&rj, &rj, &rj, NOW).unwrap())
                .unwrap(),
            r
        );
        assert!(merge_roaster_edit_json(&rj, "{}", &rj, NOW).is_err());
    }

    // ── Profile editor Save (REVIEW-FINAL S6) ──

    fn profile() -> CremaProfile {
        let mut p = crate::blank_profile(&crate::crema_profile::BrewDefaults::default());
        p.id = "p:1".into();
        p.source = crate::crema_profile::ProfileSource::Custom;
        p.name = "Bloom".into();
        p
    }

    #[test]
    fn a_profile_save_keeps_a_pin_and_a_quick_save_made_meanwhile() {
        let base = profile();
        let mut edited = base.clone();
        edited.name = "Bloom 2".into();
        edited.notes = "slower".into();
        // Meanwhile another tab pinned it and Quick Saved a dose in place.
        let mut current = base.clone();
        current.pinned = true;
        current.dose = 20.0;
        let out = merge_profile_edit(&base, &edited, &current);
        assert_eq!(
            (out.name.as_str(), out.notes.as_str()),
            ("Bloom 2", "slower")
        );
        assert!(out.pinned);
        assert_eq!(out.dose, 20.0);
        // Both changed the dose: the editor's wins.
        edited.dose = 19.0;
        assert_eq!(merge_profile_edit(&base, &edited, &current).dose, 19.0);
        // Nothing edited: the stored profile as it is.
        assert_eq!(merge_profile_edit(&base, &base, &current), current);
    }

    #[test]
    fn a_profile_body_moves_whole_and_the_brew_temp_follows_it() {
        let base = profile();
        assert!(!base.segments.is_empty());
        let mut edited = base.clone();
        edited.segments[0].temp = 88.0;
        // Meanwhile the stored body changed too (another tab's Save).
        let mut current = base.clone();
        current.segments.push(current.segments[0].clone());
        current.preinfuse_step_count = 2;
        current.name = "Renamed".into();
        let out = merge_profile_edit(&base, &edited, &current);
        assert_eq!(out.segments, edited.segments, "the editor's whole body");
        assert_eq!(out.preinfuse_step_count, edited.preinfuse_step_count);
        assert_eq!(out.brew_temp, 88.0, "derived from the first step");
        assert_eq!(out.name, "Renamed");
        // Only the count edited: the editor's segments come with it.
        let mut count_only = base.clone();
        count_only.preinfuse_step_count = 0;
        let out = merge_profile_edit(&base, &count_only, &current);
        assert_eq!(
            (out.segments.len(), out.preinfuse_step_count),
            (base.segments.len(), 0)
        );
        // An untouched body is the stored one's.
        let mut rename = base.clone();
        rename.name = "Mine".into();
        let out = merge_profile_edit(&base, &rename, &current);
        assert_eq!(out.segments, current.segments);
        assert_eq!(out.preinfuse_step_count, 2);
    }

    #[test]
    fn a_profile_keeps_its_stored_identity_and_unknown_keys() {
        let base = profile();
        let mut edited = base.clone();
        edited.id = "p:other".into();
        edited.source = crate::crema_profile::ProfileSource::Builtin;
        let out = merge_profile_edit(&base, &edited, &base);
        assert_eq!(out.id, "p:1");
        assert_eq!(out.source, crate::crema_profile::ProfileSource::Custom);
        // Over JSON, a key the core doesn't model is merged and kept.
        let with = |p: &CremaProfile, extra: serde_json::Value| {
            let mut v = serde_json::to_value(p).unwrap();
            v["shellOnly"] = extra;
            v.to_string()
        };
        let mut renamed = base.clone();
        renamed.name = "New".into();
        let out = merge_profile_edit_json(
            &with(&base, serde_json::json!(1)),
            &with(&renamed, serde_json::json!(1)),
            &with(&base, serde_json::json!(2)),
        )
        .unwrap();
        let v: serde_json::Value = serde_json::from_str(&out).unwrap();
        assert_eq!(
            (v["name"].as_str(), v["shellOnly"].as_i64()),
            (Some("New"), Some(2))
        );
        let pj = serde_json::to_string(&base).unwrap();
        assert!(merge_profile_edit_json(&pj, "{}", &pj).is_err());
        assert!(merge_profile_edit_json(&pj, "[1]", &pj).is_err());
    }
}

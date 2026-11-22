//! Brew's selection and the mode it decides (brew modes, spec §2, §4, §5).
//!
//! Brew has one selection: a DE1 profile or a brew recipe. The mode follows
//! from it and is never stored on its own: a profile means **Espresso** (the
//! DE1 dashboard), a recipe means **Manual** (the guided brew).
//!
//! **Storage** stays in the shells, under the key that holds the selected
//! profile today (Android `AppPrefs.activeProfileId`, web
//! `crema.profiles.activeId.v1`), plus one sibling key for the selected recipe.
//! The profile key keeps meaning "the profile Espresso uses": it is what the
//! hub relays and what a remote follows, so a recipe never goes in it.
//! [`StoredBrewSelection`] is the pair; [`brew_selection`] reads it and
//! [`pick_brew_selection`] writes a pick into it. An install from before
//! brew modes has no recipe key, so its selected profile reads as
//! `Profile(id)`; a fresh install has neither, so it opens in Espresso.
//!
//! **Remotes** (spec §5 as chosen): recipes are per device. A remote's
//! profile pick still goes to the hub (`setActiveProfile`), and the hub's
//! active profile still lands in the remote's profile key
//! ([`follow_hub_profile`]). Neither touches the recipe key, so a hub profile change never pulls a remote out of Manual,
//! and nothing new travels over the relay.
//!
//! **The lock** ([`brew_pick_lock`], §4): while a DE1 shot runs, the profile
//! rows are disabled; while this device runs a guided brew, every row is. The
//! hub refuses a remote's `setActiveProfile` by the same rule.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::brew::{BrewRecipe, RecipeTimeEstimate};

/// The longest stored id the selection reads, in characters. Profile and
/// recipe ids are far shorter (`recipe:` plus a UUID, `builtin:<slug>`).
pub const MAX_SELECTION_ID_CHARS: usize = 128;

/// Which kind of thing Brew has selected.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum BrewSelectionKind {
    /// A DE1 profile.
    Profile,
    /// A brew recipe.
    Recipe,
}

/// Brew's selection: `Profile(id)` or `Recipe(id)`. A struct with a kind
/// rather than a data-carrying enum, so both shells read it as plain JSON
/// (`{"kind":"recipe","id":"recipe:…"}`).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewSelection {
    pub kind: BrewSelectionKind,
    pub id: String,
}

impl BrewSelection {
    /// A profile selection.
    #[must_use]
    pub fn profile(id: impl Into<String>) -> Self {
        Self {
            kind: BrewSelectionKind::Profile,
            id: id.into(),
        }
    }

    /// A recipe selection.
    #[must_use]
    pub fn recipe(id: impl Into<String>) -> Self {
        Self {
            kind: BrewSelectionKind::Recipe,
            id: id.into(),
        }
    }

    /// The mode this selection puts Brew in.
    #[must_use]
    pub fn mode(&self) -> BrewMode {
        match self.kind {
            BrewSelectionKind::Profile => BrewMode::Espresso,
            BrewSelectionKind::Recipe => BrewMode::Manual,
        }
    }
}

/// Brew's mode, derived from the selection ([`BrewSelection::mode`]).
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum BrewMode {
    /// The DE1 dashboard. Also the mode with nothing selected.
    #[default]
    Espresso,
    /// A guided brew with a recipe.
    Manual,
}

/// The mode for a selection, or Espresso with none.
#[must_use]
pub fn brew_mode(selection: Option<&BrewSelection>) -> BrewMode {
    selection.map_or(BrewMode::Espresso, BrewSelection::mode)
}

/// What a shell stores for the selection: the profile key it has today and
/// the recipe key beside it. `None` (or an unusable id) = not set.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct StoredBrewSelection {
    /// The selected profile, also the one Espresso uses while a recipe is
    /// selected. On a remote, the hub's active profile.
    pub active_profile_id: Option<String>,
    /// The selected recipe; set means Manual. Never relayed.
    pub active_recipe_id: Option<String>,
}

/// [`brew_selection`]'s input, for the JSON bridges.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewSelectionInput {
    /// As [`StoredBrewSelection::active_profile_id`].
    pub active_profile_id: Option<String>,
    /// As [`StoredBrewSelection::active_recipe_id`].
    pub active_recipe_id: Option<String>,
    /// The stored recipe is one this device can brew now: a built-in, or a
    /// custom recipe that isn't deleted.
    pub recipe_live: bool,
}

/// The selection and the mode it decides.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewSelectionView {
    /// `None` = nothing selected (a fresh install).
    pub selection: Option<BrewSelection>,
    pub mode: BrewMode,
}

/// A stored id, trimmed, or `None` when it is empty, too long, or holds a
/// control or format character (storage the shell didn't write that way).
fn usable_id(raw: Option<&str>) -> Option<&str> {
    let id = raw?.trim();
    let ok = !id.is_empty()
        && id.chars().count() <= MAX_SELECTION_ID_CHARS
        && !id.chars().any(is_control_or_format);
    ok.then_some(id)
}

/// Control characters and the invisible format characters (bidi controls,
/// zero-width marks, the BOM) that would change how an id reads.
fn is_control_or_format(c: char) -> bool {
    c.is_control()
        || matches!(
            c,
            '\u{00AD}'
                | '\u{061C}'
                | '\u{180E}'
                | '\u{200B}'..='\u{200F}'
                | '\u{202A}'..='\u{202E}'
                | '\u{2060}'..='\u{2064}'
                | '\u{2066}'..='\u{206F}'
                | '\u{FEFF}'
                | '\u{FFF9}'..='\u{FFFB}'
        )
}

/// Brew's selection from what the shell stored (and the migration from
/// before brew modes: a stored profile with no recipe is `Profile(id)`).
///
/// A recipe wins when it is set and [`recipe_live`](BrewSelectionInput::recipe_live).
/// A recipe that isn't live (deleted, or its library not loaded yet) falls
/// back to the profile without being forgotten: the shell keeps the key until
/// the next pick, so a library that loads late still finds it.
#[must_use]
pub fn brew_selection(stored: &StoredBrewSelection, recipe_live: bool) -> Option<BrewSelection> {
    if recipe_live && let Some(id) = usable_id(stored.active_recipe_id.as_deref()) {
        return Some(BrewSelection::recipe(id));
    }
    usable_id(stored.active_profile_id.as_deref()).map(BrewSelection::profile)
}

/// [`brew_selection`] with its mode.
#[must_use]
pub fn brew_selection_view(input: &BrewSelectionInput) -> BrewSelectionView {
    let stored = StoredBrewSelection {
        active_profile_id: input.active_profile_id.clone(),
        active_recipe_id: input.active_recipe_id.clone(),
    };
    let selection = brew_selection(&stored, input.recipe_live);
    BrewSelectionView {
        mode: brew_mode(selection.as_ref()),
        selection,
    }
}

/// JSON-bridged [`brew_selection_view`]: a [`BrewSelectionInput`] JSON in, a
/// [`BrewSelectionView`] JSON out.
///
/// # Errors
/// The JSON parse error string on a malformed `input_json`.
pub fn brew_selection_view_json(input_json: &str) -> Result<String, String> {
    let input: BrewSelectionInput = serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&brew_selection_view(&input)).map_err(|e| e.to_string())
}

/// What the shell stores after the user picks `pick` on this device.
///
/// - A profile replaces the profile and clears the recipe: Espresso.
/// - A recipe sets the recipe and keeps the profile, which Espresso uses
///   again when the user picks a profile, and which stays the DE1's.
///
/// Only a pick on this device comes here. The hub's active profile reaching
/// a remote goes through [`follow_hub_profile`] instead, which never changes a
/// remote's recipe or mode.
#[must_use]
pub fn pick_brew_selection(
    stored: &StoredBrewSelection,
    pick: &BrewSelection,
) -> StoredBrewSelection {
    match pick.kind {
        BrewSelectionKind::Profile => StoredBrewSelection {
            active_profile_id: Some(pick.id.trim().to_owned()),
            active_recipe_id: None,
        },
        BrewSelectionKind::Recipe => StoredBrewSelection {
            active_profile_id: stored.active_profile_id.clone(),
            active_recipe_id: Some(pick.id.trim().to_owned()),
        },
    }
}

/// What a remote stores when the hub's active profile reaches it (the
/// relayed `activeProfileId`): the profile only. A remote in Espresso shows
/// the hub's profile; one in Manual stays there, and leaves Manual when its
/// user picks a profile. `None` (the hub has none) leaves the stored profile.
#[must_use]
pub fn follow_hub_profile(
    stored: &StoredBrewSelection,
    hub_profile_id: Option<&str>,
) -> StoredBrewSelection {
    StoredBrewSelection {
        active_profile_id: usable_id(hub_profile_id)
            .map(str::to_owned)
            .or_else(|| stored.active_profile_id.clone()),
        active_recipe_id: stored.active_recipe_id.clone(),
    }
}

/// [`follow_hub_profile`]'s input, for the JSON bridges.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct HubProfileInput {
    pub stored: StoredBrewSelection,
    pub hub_profile_id: Option<String>,
}

/// JSON-bridged [`follow_hub_profile`]: a [`HubProfileInput`] JSON in, the
/// [`StoredBrewSelection`] JSON to store out.
///
/// # Errors
/// The JSON parse error string on a malformed `input_json`.
pub fn follow_hub_profile_json(input_json: &str) -> Result<String, String> {
    let input: HubProfileInput = serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&follow_hub_profile(
        &input.stored,
        input.hub_profile_id.as_deref(),
    ))
    .map_err(|e| e.to_string())
}

/// [`pick_brew_selection`]'s input, for the JSON bridges.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewPickInput {
    #[serde(default)]
    pub stored: StoredBrewSelection,
    pub pick: BrewSelection,
}

/// JSON-bridged [`pick_brew_selection`]: a [`BrewPickInput`] JSON in, the
/// [`StoredBrewSelection`] JSON to store out.
///
/// # Errors
/// The JSON parse error string on a malformed `input_json`.
pub fn pick_brew_selection_json(input_json: &str) -> Result<String, String> {
    let input: BrewPickInput = serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&pick_brew_selection(&input.stored, &input.pick))
        .map_err(|e| e.to_string())
}

// ── The lock (§4) ────────────────────────────────────────────────────

/// What runs, for [`brew_pick_lock`].
#[typeshare]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BrewPickLockInput {
    /// A DE1 espresso shot is running on the machine this device shows: its
    /// own, or on a remote the hub's (from the relayed machine state).
    pub shot_running: bool,
    /// This device is running a guided brew (armed, running or paused; not
    /// done). Guided brews run on the device that started them, so a remote
    /// never knows of the hub's, and the hub checks its own.
    pub guided_brew_running: bool,
}

/// Why the picker's rows are disabled.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum BrewPickLockReason {
    Shot,
    GuidedBrew,
}

/// The picker is locked: the profile rows are disabled, and the recipe rows
/// too when [`recipes_locked`](Self::recipes_locked). The tabs still browse.
/// The hub refuses a remote's `setActiveProfile` with [`note`](Self::note).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BrewPickLock {
    pub reason: BrewPickLockReason,
    /// The recipe rows are disabled as well (a guided brew runs here).
    pub recipes_locked: bool,
    /// The note under the disabled rows, and the hub's refusal.
    pub note: String,
}

/// The note while a shot runs.
pub const LOCK_NOTE_SHOT: &str = "Finish the current shot first";
/// The note while a guided brew runs.
pub const LOCK_NOTE_BREW: &str = "Finish the current brew first";

/// Whether Brew's selection may change now (§4), or why not.
///
/// - A DE1 shot locks the profile rows: a profile pick would change the
///   targets the running shot stops on. Recipe rows stay open, since a
///   recipe pick is local and leaves the shot alone.
/// - A guided brew on this device locks every row: the brew runs on the
///   recipe it started with, and the page must not switch under it.
///
/// With both running, the guided brew is named, as it locks more.
#[must_use]
pub fn brew_pick_lock(input: BrewPickLockInput) -> Option<BrewPickLock> {
    let (reason, recipes_locked, note) = if input.guided_brew_running {
        (BrewPickLockReason::GuidedBrew, true, LOCK_NOTE_BREW)
    } else if input.shot_running {
        (BrewPickLockReason::Shot, false, LOCK_NOTE_SHOT)
    } else {
        return None;
    };
    Some(BrewPickLock {
        reason,
        recipes_locked,
        note: note.to_owned(),
    })
}

/// JSON-bridged [`brew_pick_lock`]: a [`BrewPickLockInput`] JSON in, a
/// [`BrewPickLock`] JSON out, or `None` when the rows are enabled.
///
/// # Errors
/// The JSON parse error string on a malformed `input_json`.
pub fn brew_pick_lock_json(input_json: &str) -> Result<Option<String>, String> {
    let input: BrewPickLockInput = serde_json::from_str(input_json).map_err(|e| e.to_string())?;
    brew_pick_lock(input)
        .map(|lock| serde_json::to_string(&lock).map_err(|e| e.to_string()))
        .transpose()
}

// ── Recipe summary ───────────────────────────────────────────────────

/// What the header block, the phone strip and the picker's Manual rows show
/// of a recipe: "RECIPE · ‹method›", the name, and dose · water · temp · time.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecipeSummary {
    pub id: String,
    pub name: String,
    /// The normalized method id; the shells label it as they do elsewhere
    /// (a custom method's id resolves through its record).
    pub method: String,
    pub dose_g: f32,
    pub water_g: f32,
    pub temp_c: Option<f32>,
    /// [`BrewRecipe::estimated_duration`]; `None` = show no time.
    pub estimated: Option<RecipeTimeEstimate>,
    pub step_count: u32,
}

/// The [`RecipeSummary`] of `recipe`.
#[must_use]
pub fn recipe_summary(recipe: &BrewRecipe) -> RecipeSummary {
    RecipeSummary {
        id: recipe.id.clone(),
        name: recipe.name.trim().to_owned(),
        method: recipe.method.clone(),
        dose_g: recipe.dose_g,
        water_g: recipe.water_g,
        temp_c: recipe.temp_c.filter(|t| t.is_finite()),
        estimated: recipe.estimated_duration(),
        step_count: u32::try_from(recipe.steps.len()).unwrap_or(u32::MAX),
    }
}

/// JSON-bridged [`recipe_summary`]: a [`BrewRecipe`] JSON in, a
/// [`RecipeSummary`] JSON out.
///
/// # Errors
/// The JSON parse error string on a malformed `recipe_json`.
pub fn recipe_summary_json(recipe_json: &str) -> Result<String, String> {
    let recipe: BrewRecipe = serde_json::from_str(recipe_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&recipe_summary(&recipe)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::brew::{BrewStep, BrewStepKind, StepAdvance};

    fn stored(profile: Option<&str>, recipe: Option<&str>) -> StoredBrewSelection {
        StoredBrewSelection {
            active_profile_id: profile.map(str::to_owned),
            active_recipe_id: recipe.map(str::to_owned),
        }
    }

    #[test]
    fn a_stored_profile_from_before_brew_modes_is_a_profile_selection() {
        let old = stored(Some("01910f80-7a3b-7c54-b2d1-23a4f8e9cd00"), None);
        let sel = brew_selection(&old, false).unwrap();
        assert_eq!(
            sel,
            BrewSelection::profile("01910f80-7a3b-7c54-b2d1-23a4f8e9cd00")
        );
        assert_eq!(sel.mode(), BrewMode::Espresso);
    }

    #[test]
    fn the_old_prefs_json_decodes_without_a_recipe_key() {
        let input: BrewSelectionInput =
            serde_json::from_str(r#"{"activeProfileId":"p1"}"#).unwrap();
        let view = brew_selection_view(&input);
        assert_eq!(view.selection, Some(BrewSelection::profile("p1")));
        assert_eq!(view.mode, BrewMode::Espresso);
    }

    #[test]
    fn a_fresh_install_opens_in_espresso_with_nothing_selected() {
        let view = brew_selection_view(&BrewSelectionInput::default());
        assert_eq!(view.selection, None);
        assert_eq!(view.mode, BrewMode::Espresso);
    }

    #[test]
    fn a_live_recipe_wins_and_means_manual() {
        let s = stored(Some("p1"), Some("builtin:hoffmann-v60"));
        let sel = brew_selection(&s, true).unwrap();
        assert_eq!(sel, BrewSelection::recipe("builtin:hoffmann-v60"));
        assert_eq!(brew_mode(Some(&sel)), BrewMode::Manual);
    }

    #[test]
    fn a_recipe_that_is_not_live_falls_back_to_the_profile() {
        let s = stored(Some("p1"), Some("recipe:gone"));
        assert_eq!(
            brew_selection(&s, false),
            Some(BrewSelection::profile("p1"))
        );
        assert_eq!(
            brew_selection(&stored(None, Some("recipe:gone")), false),
            None
        );
    }

    #[test]
    fn unusable_stored_ids_read_as_unset() {
        let long = "x".repeat(MAX_SELECTION_ID_CHARS + 1);
        for bad in ["", "   ", "a\nb", "id\u{202E}", "\u{200B}", long.as_str()] {
            assert_eq!(
                brew_selection(&stored(Some(bad), Some(bad)), true),
                None,
                "{bad:?}"
            );
        }
        let max = "y".repeat(MAX_SELECTION_ID_CHARS);
        assert_eq!(
            brew_selection(&stored(Some(&max), None), false),
            Some(BrewSelection::profile(max.clone()))
        );
        assert_eq!(
            brew_selection(&stored(Some("  p1 "), None), false),
            Some(BrewSelection::profile("p1")),
            "trimmed"
        );
    }

    #[test]
    fn picking_a_profile_clears_the_recipe() {
        let s = stored(Some("p1"), Some("recipe:a"));
        let after = pick_brew_selection(&s, &BrewSelection::profile("p2"));
        assert_eq!(after, stored(Some("p2"), None));
        assert_eq!(
            brew_mode(brew_selection(&after, true).as_ref()),
            BrewMode::Espresso
        );
    }

    #[test]
    fn picking_a_recipe_keeps_the_profile_for_espresso() {
        let s = stored(Some("p1"), None);
        let after = pick_brew_selection(&s, &BrewSelection::recipe("recipe:a"));
        assert_eq!(after, stored(Some("p1"), Some("recipe:a")));
        assert_eq!(
            brew_mode(brew_selection(&after, true).as_ref()),
            BrewMode::Manual
        );
        // Back to Espresso: the profile the user had.
        let back = pick_brew_selection(&after, &BrewSelection::profile("p1"));
        assert_eq!(back, stored(Some("p1"), None));
    }

    #[test]
    fn the_hubs_profile_reaching_a_remote_leaves_it_in_manual() {
        // A remote in Manual; the hub's snapshot sets only the profile key.
        let remote = follow_hub_profile(&stored(Some("p1"), Some("recipe:a")), Some("hub-p"));
        assert_eq!(remote, stored(Some("hub-p"), Some("recipe:a")));
        assert_eq!(
            brew_selection(&remote, true),
            Some(BrewSelection::recipe("recipe:a"))
        );
        // The user leaves Manual by picking a profile.
        let back = pick_brew_selection(&remote, &BrewSelection::profile("hub-p"));
        assert_eq!(
            brew_mode(brew_selection(&back, true).as_ref()),
            BrewMode::Espresso
        );
        // A remote in Espresso shows the hub's profile.
        let espresso = follow_hub_profile(&stored(Some("p1"), None), Some("hub-p"));
        assert_eq!(
            brew_selection(&espresso, false),
            Some(BrewSelection::profile("hub-p"))
        );
        // No hub profile keeps the stored one.
        assert_eq!(follow_hub_profile(&espresso, None), espresso);
        assert_eq!(
            follow_hub_profile_json(r#"{"stored":{"activeRecipeId":"r"},"hubProfileId":"h"}"#)
                .unwrap(),
            r#"{"activeProfileId":"h","activeRecipeId":"r"}"#
        );
    }

    #[test]
    fn the_selection_round_trips_as_plain_json() {
        let sel = BrewSelection::recipe("recipe:a");
        let json = serde_json::to_string(&sel).unwrap();
        assert_eq!(json, r#"{"kind":"recipe","id":"recipe:a"}"#);
        assert_eq!(serde_json::from_str::<BrewSelection>(&json).unwrap(), sel);
        let input = r#"{"stored":{"activeProfileId":"p1"},"pick":{"kind":"recipe","id":"r"}}"#;
        assert_eq!(
            pick_brew_selection_json(input).unwrap(),
            r#"{"activeProfileId":"p1","activeRecipeId":"r"}"#
        );
        assert!(pick_brew_selection_json("{").is_err());
        assert!(brew_selection_view_json("\"nope\"").is_err());
        assert_eq!(
            brew_selection_view_json(r#"{"activeRecipeId":"r","recipeLive":true}"#).unwrap(),
            r#"{"selection":{"kind":"recipe","id":"r"},"mode":"manual"}"#
        );
    }

    #[test]
    fn nothing_running_leaves_the_rows_enabled() {
        assert_eq!(brew_pick_lock(BrewPickLockInput::default()), None);
        assert_eq!(brew_pick_lock_json("{}").unwrap(), None);
    }

    #[test]
    fn a_shot_locks_the_profile_rows_only() {
        let shot = brew_pick_lock(BrewPickLockInput {
            shot_running: true,
            guided_brew_running: false,
        })
        .unwrap();
        assert_eq!(shot.reason, BrewPickLockReason::Shot);
        assert!(!shot.recipes_locked, "a recipe pick is local");
        assert_eq!(shot.note, LOCK_NOTE_SHOT);
    }

    #[test]
    fn a_guided_brew_locks_every_row() {
        for shot_running in [false, true] {
            let brew = brew_pick_lock(BrewPickLockInput {
                shot_running,
                guided_brew_running: true,
            })
            .unwrap();
            assert_eq!(
                brew.reason,
                BrewPickLockReason::GuidedBrew,
                "shot {shot_running}"
            );
            assert!(brew.recipes_locked);
            assert_eq!(brew.note, LOCK_NOTE_BREW);
        }
        assert_eq!(
            brew_pick_lock_json(r#"{"guidedBrewRunning":true}"#)
                .unwrap()
                .unwrap(),
            r#"{"reason":"guidedBrew","recipesLocked":true,"note":"Finish the current brew first"}"#
        );
    }

    #[test]
    fn a_recipe_summary_has_the_header_line() {
        let recipe = BrewRecipe {
            id: "recipe:a".into(),
            name: "  Morning V60 ".into(),
            method: "v60".into(),
            dose_g: 15.0,
            water_g: 250.0,
            temp_c: Some(94.0),
            steps: vec![
                BrewStep {
                    kind: BrewStepKind::Bloom,
                    label: None,
                    target_water_g: Some(45.0),
                    duration_s: Some(45),
                    advance: StepAdvance::Auto,
                },
                BrewStep {
                    kind: BrewStepKind::Drawdown,
                    label: None,
                    target_water_g: None,
                    duration_s: None,
                    advance: StepAdvance::Manual,
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
        let s = recipe_summary(&recipe);
        assert_eq!(s.name, "Morning V60");
        assert_eq!(
            (s.method.as_str(), s.dose_g, s.water_g, s.temp_c),
            ("v60", 15.0, 250.0, Some(94.0))
        );
        assert_eq!(s.step_count, 2);
        assert_eq!(
            s.estimated,
            Some(RecipeTimeEstimate {
                total_ms: 45_000,
                open_ended: true
            })
        );
        let json = recipe_summary_json(&serde_json::to_string(&recipe).unwrap()).unwrap();
        assert!(json.contains(r#""stepCount":2"#), "{json}");
        assert!(recipe_summary_json("{}").is_err());
    }

    #[test]
    fn every_builtin_recipe_summarises() {
        for r in crate::builtin_brew_recipes() {
            let s = recipe_summary(&r);
            assert_eq!(s.id, r.id);
            assert!(!s.name.is_empty(), "{}", r.id);
            // A built-in is always live, so selecting one is Manual.
            let sel = brew_selection(&stored(None, Some(&r.id)), crate::is_builtin_recipe(&r.id));
            assert_eq!(brew_mode(sel.as_ref()), BrewMode::Manual);
        }
    }
}

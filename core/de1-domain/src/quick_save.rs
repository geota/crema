//! # quick_save
//!
//! What Quick Controls' **Save** writes, and its Undo (REVIEW-CORE M2;
//! save-boundaries review F1). One plan for both shells, so web and Android
//! stop writing the rule twice:
//!
//! - **Grind only** (Brew's grind differs from the bag's saved grind for the
//!   active grinder, the marker's rule, [`crate::brew_grind_view`]) → the
//!   grind goes to that (bag, grinder) row ([`crate::save_bag_grind`]); no
//!   profile copy, no name prompt.
//! - **Recipe changed** (a Quick Controls override is stored for the active
//!   profile) → a **custom** profile is updated in place, with an Undo and
//!   "Save as new…" beside Save; a **built-in** is read-only, so it is copied
//!   under a name the user gives, and the copy made active.
//! - **Nothing changed** → Save is off.
//! - **No profile** → Save saves the grind only.
//!
//! The Undo of an in-place Save ([`quick_save_undo`]) puts back each part
//! only while it still holds what the Save wrote: the profile while its
//! stored body is the written one (a later pin, rename or edit stands), and
//! the bag's grind while that one (bag, grinder) row is the written one (an
//! auto-save to another grinder's row in between doesn't block it).
//!
//! **Stays in the shells:** the name prompt, the profile store writes, the
//! baked profile (`effective_crema_profile`), the message wording, and
//! relaying Save to the hub on a remote.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bag_grind::{BagGrind, saved_grind};
use crate::bean::Bean;
use crate::effective_profile::BrewOverrides;
use crate::grind_value::{BrewGrindInput, brew_grind_view};
use crate::grinder::Grinder;

/// The kind of profile Save would write to.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum QuickSaveProfileKind {
    /// No active profile: Save saves the grind only.
    #[default]
    None,
    /// A user-owned profile: updated in place.
    Custom,
    /// A read-only built-in: copied under a new name.
    Builtin,
}

/// The active profile, as the plan needs it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct QuickSaveProfile {
    /// Its library id.
    pub id: String,
    /// Its name.
    pub name: String,
    /// Custom or built-in (`None` is read as no profile).
    pub kind: QuickSaveProfileKind,
}

/// What [`quick_save_plan`] reads: the state on screen (on a remote, the
/// hub's).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct QuickSaveInput {
    /// The bag on Brew, if any.
    pub bag: Option<Bean>,
    /// The active grinder; `None` = the "no grinder" slot.
    pub grinder: Option<Grinder>,
    /// Brew's grind (the per-device session value); `None` = not set.
    pub brew_grind: Option<String>,
    /// The active profile, if any.
    pub profile: Option<QuickSaveProfile>,
    /// The Quick Controls override as stored, normalized by the core's
    /// lifecycle rule (`normalize_brew_overrides`): a dial back at the
    /// profile's own value is no override, so an empty one is no change.
    pub recipe_overrides: BrewOverrides,
}

/// How Save writes the recipe.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum QuickSaveProfileWrite {
    /// The recipe isn't written (unchanged, or no profile).
    #[default]
    None,
    /// The custom profile is updated in place; the message offers Undo.
    InPlace,
    /// A built-in is copied under a name the user gives; the copy is made
    /// active.
    Copy,
}

/// What Save would write, for the state on screen.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuickSavePlan {
    /// The bag the grind would be saved to.
    pub bean_id: Option<String>,
    /// Its name, for the message.
    pub bean_name: Option<String>,
    /// The active grinder's id; `None` = the "no grinder" slot.
    pub grinder_id: Option<String>,
    /// Its name, for the message.
    pub grinder_name: Option<String>,
    /// Brew's grind to save to the (bag, grinder) row, when it differs from
    /// the saved one ([`crate::BrewGrindView::save_grind`]); else `None`.
    pub grind: Option<String>,
    /// The active profile's id.
    pub profile_id: Option<String>,
    /// The active profile's name.
    pub profile_name: Option<String>,
    /// The kind of profile Save writes to.
    pub profile_kind: QuickSaveProfileKind,
    /// The recipe dials differ from the active profile's own.
    pub recipe_changed: bool,
    /// How the recipe is written ("Save" with nothing else chosen).
    pub profile_write: QuickSaveProfileWrite,
    /// Save writes the recipe to a profile (there is one to write to).
    pub writes_recipe: bool,
    /// Save has something to write; else the button is off.
    pub enabled: bool,
    /// Save asks for a name first: a built-in is read-only, so it's a copy.
    pub needs_name: bool,
    /// "Save as new…" is offered: a custom profile would otherwise be
    /// updated in place.
    pub offers_save_as_new: bool,
    /// Save includes the grind: the button carries the unsaved-grind dot.
    pub includes_grind: bool,
    /// The name a copy's prompt starts from: "‹name› (copy)", or empty.
    pub copy_name_seed: String,
}

/// The Quick Controls Save plan (REVIEW-CORE M2): see the module docs.
#[must_use]
pub fn quick_save_plan(input: &QuickSaveInput) -> QuickSavePlan {
    let view = brew_grind_view(&BrewGrindInput {
        bag: input.bag.clone(),
        grinder: input.grinder.clone(),
        brew_grind: input.brew_grind.clone(),
    });
    let profile = input
        .profile
        .as_ref()
        .filter(|p| p.kind != QuickSaveProfileKind::None);
    let profile_kind = profile.map_or(QuickSaveProfileKind::None, |p| p.kind);
    let recipe_changed = profile.is_some() && !input.recipe_overrides.is_empty();
    let writes_recipe = recipe_changed;
    let grind = view.save_grind;
    let enabled = grind.is_some() || writes_recipe;
    let profile_write = match (writes_recipe, profile_kind) {
        (true, QuickSaveProfileKind::Custom) => QuickSaveProfileWrite::InPlace,
        (true, QuickSaveProfileKind::Builtin) => QuickSaveProfileWrite::Copy,
        _ => QuickSaveProfileWrite::None,
    };
    QuickSavePlan {
        bean_id: input.bag.as_ref().map(|b| b.id.clone()),
        bean_name: input.bag.as_ref().map(|b| b.name.clone()),
        grinder_id: input.grinder.as_ref().map(|g| g.id.clone()),
        grinder_name: input.grinder.as_ref().map(|g| g.name.clone()),
        includes_grind: grind.is_some(),
        grind,
        profile_id: profile.map(|p| p.id.clone()),
        profile_name: profile.map(|p| p.name.clone()),
        profile_kind,
        recipe_changed,
        profile_write,
        writes_recipe,
        enabled,
        needs_name: enabled && profile_write == QuickSaveProfileWrite::Copy,
        offers_save_as_new: enabled && profile_write == QuickSaveProfileWrite::InPlace,
        copy_name_seed: profile
            .map(|p| p.name.trim())
            .filter(|n| !n.is_empty())
            .map(|n| format!("{n} (copy)"))
            .unwrap_or_default(),
    }
}

/// The grind half of an in-place Save's Undo: the one (bag, grinder) row as
/// it was before the Save and as the Save wrote it.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct QuickSaveGrindUndo {
    /// The bag.
    pub bean_id: String,
    /// The row's grinder id ([`crate::NO_GRINDER_ID`] for the "no grinder"
    /// slot).
    pub grinder_id: String,
    /// The row before the Save; `None` = there was none (Undo removes it).
    pub before: Option<BagGrind>,
    /// The row as the Save wrote it.
    pub written: Option<BagGrind>,
    /// The bag had no grinds list before the Save (`grinds: None`, not
    /// migrated): an Undo that leaves no rows puts `None` back, not `[]`
    /// (#488 follow-up 44). `None` (absent on an older Undo record) =
    /// `false`; optional so the generated shell types decode JSON without
    /// it (closing check, PLAN 63).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub grinds_were_none: Option<bool>,
}

impl QuickSaveGrindUndo {
    /// The grind half for a Save that turned `before` into `after` for
    /// `grinder_id`.
    #[must_use]
    pub fn of(before: &Bean, after: &Bean, grinder_id: &str) -> Self {
        Self {
            bean_id: after.id.clone(),
            grinder_id: grinder_id.to_owned(),
            before: saved_grind(before, grinder_id).cloned(),
            written: saved_grind(after, grinder_id).cloned(),
            grinds_were_none: Some(before.grinds.is_none()),
        }
    }
}

/// What an in-place Save wrote, for its Undo.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct QuickSaveUndo {
    /// The profile updated in place.
    pub profile_id: String,
    /// Its name, for the message.
    pub profile_name: String,
    /// Its stored body as the Save wrote it (the shell's stored JSON): Undo
    /// restores the profile only over this, compared by value.
    #[typeshare(serialized_as = "Json")]
    pub written_profile: serde_json::Value,
    /// The grind the same Save wrote, if it wrote one.
    pub grind: Option<QuickSaveGrindUndo>,
}

/// What [`quick_save_undo`] reads: the Undo and the state now.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct QuickSaveUndoInput {
    /// What the Save wrote.
    pub undo: QuickSaveUndo,
    /// The profile's stored body now (as JSON); `null` = it is gone.
    #[typeshare(serialized_as = "Json")]
    pub now_profile: serde_json::Value,
    /// The bag as stored now; `None` = it is gone.
    pub now_bag: Option<Bean>,
}

/// How an Undo came out; the shells word the message.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum QuickSaveUndoOutcome {
    /// Everything the Save wrote came back: "Save undone".
    Undone,
    /// The profile came back; the grind changed since and stands.
    ProfileOnly,
    /// The grind came back; the profile changed since and stands: "Grind put
    /// back; “X” changed since, not undone".
    GrindOnly,
    /// Nothing came back: "“X” changed since; not undone".
    NotUndone,
}

/// What an Undo writes.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct QuickSaveUndoPlan {
    /// Store the profile's body from before the Save (the shell kept it).
    pub restore_profile: bool,
    /// The bag to store with the row put back (`updated_at` moved), or `None`
    /// to leave the bag alone.
    pub restore_bean: Option<Bean>,
    /// How it came out.
    pub outcome: QuickSaveUndoOutcome,
}

/// The Undo of an in-place Save (REVIEW-CORE M2): each half comes back only
/// while it still holds what the Save wrote. The profile while its stored
/// body equals [`QuickSaveUndo::written_profile`] (by value); the grind
/// while the bag's row for that grinder equals the written row, checked per
/// row, so a save to another grinder's row since doesn't block it. The row
/// goes back in place (or goes, when there was none before); a bag that had
/// no grinds list before the Save gets `None` back when no rows are left.
#[must_use]
pub fn quick_save_undo(input: &QuickSaveUndoInput, now_ms: i64) -> QuickSaveUndoPlan {
    let undo = &input.undo;
    let restore_profile = !input.now_profile.is_null() && input.now_profile == undo.written_profile;
    let restore_bean = undo.grind.as_ref().and_then(|g| {
        let bag = input.now_bag.as_ref().filter(|b| b.id == g.bean_id)?;
        let written = g.written.as_ref()?;
        if saved_grind(bag, &g.grinder_id) != Some(written) {
            return None;
        }
        let mut out = bag.clone();
        let rows = out.grinds.get_or_insert_with(Vec::new);
        match (
            rows.iter().position(|r| r.grinder_id == g.grinder_id),
            &g.before,
        ) {
            (Some(i), Some(before)) => rows[i].clone_from(before),
            (Some(i), None) => {
                rows.remove(i);
            }
            (None, _) => return None,
        }
        // The Save made the list: an Undo that empties it puts back `None`.
        if g.grinds_were_none == Some(true) && rows.is_empty() {
            out.grinds = None;
            out.legacy_grind_pending = None;
        }
        out.updated_at = now_ms.max(bag.updated_at);
        Some(out)
    });
    let grind_back = restore_bean.is_some();
    let outcome = match (restore_profile, undo.grind.is_some(), grind_back) {
        (true, false, _) | (true, true, true) => QuickSaveUndoOutcome::Undone,
        (true, true, false) => QuickSaveUndoOutcome::ProfileOnly,
        (false, _, true) => QuickSaveUndoOutcome::GrindOnly,
        (false, _, false) => QuickSaveUndoOutcome::NotUndone,
    };
    QuickSaveUndoPlan {
        restore_profile,
        restore_bean,
        outcome,
    }
}

fn parse<T: serde::de::DeserializeOwned>(json: &str) -> Result<T, String> {
    serde_json::from_str(json).map_err(|e| e.to_string())
}

fn to_json<T: Serialize>(value: &T) -> Result<String, String> {
    serde_json::to_string(value).map_err(|e| e.to_string())
}

/// JSON-bridged [`quick_save_plan`]: a [`QuickSaveInput`] JSON in, a
/// [`QuickSavePlan`] JSON out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn quick_save_plan_json(input_json: &str) -> Result<String, String> {
    to_json(&quick_save_plan(&parse(input_json)?))
}

/// JSON-bridged [`quick_save_undo`]: a [`QuickSaveUndoInput`] JSON and now
/// in, a [`QuickSaveUndoPlan`] JSON out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn quick_save_undo_json(input_json: &str, now_ms: i64) -> Result<String, String> {
    to_json(&quick_save_undo(&parse(input_json)?, now_ms))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::bag_grind::save_bag_grind;
    use serde_json::json;

    const NOW: i64 = 9_000;

    fn grinder(id: &str, name: &str) -> Grinder {
        Grinder {
            id: id.into(),
            name: name.into(),
            ..Grinder::default()
        }
    }

    fn row(id: &str, grind: &str) -> BagGrind {
        BagGrind {
            grinder_id: id.into(),
            grinder_name: id.into(),
            grind: grind.into(),
            starred: false,
        }
    }

    fn bag(rows: Vec<BagGrind>) -> Bean {
        let mut b = Bean::new("bean:1".into(), "Guji".into(), 100);
        b.grinds = Some(rows);
        b
    }

    fn profile(kind: QuickSaveProfileKind) -> Option<QuickSaveProfile> {
        Some(QuickSaveProfile {
            id: "profile:1".into(),
            name: "Blooming".into(),
            kind,
        })
    }

    fn dialled() -> BrewOverrides {
        BrewOverrides {
            dose: Some(19.0),
            ..BrewOverrides::default()
        }
    }

    fn input(
        brew_grind: Option<&str>,
        profile: Option<QuickSaveProfile>,
        qc: BrewOverrides,
    ) -> QuickSaveInput {
        QuickSaveInput {
            bag: Some(bag(vec![row("grinder:ek", "8")])),
            grinder: Some(grinder("grinder:ek", "EK43")),
            brew_grind: brew_grind.map(str::to_owned),
            profile,
            recipe_overrides: qc,
        }
    }

    #[test]
    fn grind_only_goes_to_the_bag_with_no_name_prompt() {
        let p = quick_save_plan(&input(
            Some("9"),
            profile(QuickSaveProfileKind::Builtin),
            BrewOverrides::default(),
        ));
        assert_eq!(p.grind.as_deref(), Some("9"));
        assert!(p.enabled && p.includes_grind);
        assert_eq!(p.profile_write, QuickSaveProfileWrite::None);
        assert!(!p.needs_name && !p.offers_save_as_new && !p.writes_recipe);
        assert_eq!(
            (p.bean_id.as_deref(), p.grinder_name.as_deref()),
            (Some("bean:1"), Some("EK43"))
        );
        // The same grind as saved (as numbers) is no change.
        let same = quick_save_plan(&input(Some("8.0"), None, BrewOverrides::default()));
        assert_eq!(same.grind, None);
        assert!(!same.enabled, "nothing changed: Save is off");
    }

    #[test]
    fn a_custom_profile_is_updated_in_place_and_a_builtin_copied_under_a_name() {
        let custom = quick_save_plan(&input(
            None,
            profile(QuickSaveProfileKind::Custom),
            dialled(),
        ));
        assert_eq!(custom.profile_write, QuickSaveProfileWrite::InPlace);
        assert!(
            custom.enabled
                && custom.writes_recipe
                && custom.offers_save_as_new
                && !custom.needs_name
        );
        assert!(!custom.includes_grind);
        let builtin = quick_save_plan(&input(
            Some("9"),
            profile(QuickSaveProfileKind::Builtin),
            dialled(),
        ));
        assert_eq!(builtin.profile_write, QuickSaveProfileWrite::Copy);
        assert!(builtin.needs_name && !builtin.offers_save_as_new && builtin.includes_grind);
        assert_eq!(builtin.copy_name_seed, "Blooming (copy)");
    }

    #[test]
    fn with_no_profile_save_saves_the_grind_only() {
        let p = quick_save_plan(&input(Some("9"), None, dialled()));
        assert!(!p.recipe_changed && !p.writes_recipe);
        assert_eq!(p.profile_kind, QuickSaveProfileKind::None);
        assert!(p.enabled && p.includes_grind);
        assert_eq!(p.copy_name_seed, "");
        let none_kind =
            quick_save_plan(&input(None, profile(QuickSaveProfileKind::None), dialled()));
        assert!(!none_kind.enabled, "a profile of kind None is no profile");
        // No bag: no grind to save.
        let mut no_bag = input(Some("9"), None, BrewOverrides::default());
        no_bag.bag = None;
        assert!(!quick_save_plan(&no_bag).enabled);
    }

    fn undo_after_save(before: &Bean, grinder_id: &str, grind: &str) -> (Bean, QuickSaveUndo) {
        let g = grinder(grinder_id, grinder_id);
        let after = save_bag_grind(before, Some(&g), grind, NOW).bean;
        let undo = QuickSaveUndo {
            profile_id: "profile:1".into(),
            profile_name: "Blooming".into(),
            written_profile: json!({ "id": "profile:1", "dose": 19 }),
            grind: Some(QuickSaveGrindUndo::of(before, &after, grinder_id)),
        };
        (after, undo)
    }

    #[test]
    fn undo_puts_back_each_part_that_still_holds_what_save_wrote() {
        let before = bag(vec![row("grinder:ek", "8")]);
        let (after, undo) = undo_after_save(&before, "grinder:ek", "9");
        let run = |now_profile: serde_json::Value, now_bag: Option<Bean>| {
            quick_save_undo(
                &QuickSaveUndoInput {
                    undo: undo.clone(),
                    now_profile,
                    now_bag,
                },
                NOW + 1,
            )
        };
        // Key order doesn't matter: compared by value.
        let all = run(
            json!({ "dose": 19, "id": "profile:1" }),
            Some(after.clone()),
        );
        assert_eq!(all.outcome, QuickSaveUndoOutcome::Undone);
        assert!(all.restore_profile);
        let back = all.restore_bean.unwrap();
        assert_eq!(back.grinds, before.grinds);
        assert_eq!(back.updated_at, NOW + 1);
        // A rename since: the profile stands, the grind comes back.
        let renamed = run(
            json!({ "id": "profile:1", "dose": 19, "name": "Mine" }),
            Some(after.clone()),
        );
        assert_eq!(renamed.outcome, QuickSaveUndoOutcome::GrindOnly);
        assert!(!renamed.restore_profile && renamed.restore_bean.is_some());
        // A new grind since on that row: it stands.
        let regrind = save_bag_grind(&after, Some(&grinder("grinder:ek", "EK43")), "10", NOW).bean;
        let p = run(
            json!({ "id": "profile:1", "dose": 19 }),
            Some(regrind.clone()),
        );
        assert_eq!(p.outcome, QuickSaveUndoOutcome::ProfileOnly);
        assert!(p.restore_bean.is_none());
        assert_eq!(
            run(json!(null), Some(regrind)).outcome,
            QuickSaveUndoOutcome::NotUndone
        );
        assert_eq!(
            run(json!(null), None).outcome,
            QuickSaveUndoOutcome::NotUndone
        );
    }

    #[test]
    fn a_save_to_another_grinders_row_since_does_not_block_the_grind_undo() {
        // REVIEW-CORE M2: web compared the whole list, Android the row.
        let before = bag(vec![row("grinder:ek", "8")]);
        let (after, undo) = undo_after_save(&before, "grinder:ek", "9");
        let auto = save_bag_grind(&after, Some(&grinder("grinder:c40", "C40")), "22", NOW).bean;
        let plan = quick_save_undo(
            &QuickSaveUndoInput {
                undo,
                now_profile: json!("changed"),
                now_bag: Some(auto),
            },
            NOW,
        );
        assert_eq!(plan.outcome, QuickSaveUndoOutcome::GrindOnly);
        assert_eq!(
            plan.restore_bean.unwrap().grinds.unwrap(),
            vec![
                row("grinder:ek", "8"),
                BagGrind {
                    grinder_name: "C40".into(),
                    ..row("grinder:c40", "22")
                }
            ]
        );
    }

    #[test]
    fn undo_removes_a_row_the_save_added_and_ignores_another_bag() {
        let before = bag(Vec::new());
        let (after, undo) = undo_after_save(&before, "grinder:ek", "9");
        let plan = quick_save_undo(
            &QuickSaveUndoInput {
                undo: undo.clone(),
                now_profile: undo.written_profile.clone(),
                now_bag: Some(after.clone()),
            },
            NOW,
        );
        assert_eq!(plan.restore_bean.unwrap().grinds, Some(Vec::new()));
        let mut other = after;
        other.id = "bean:2".into();
        let plan = quick_save_undo(
            &QuickSaveUndoInput {
                undo,
                now_profile: json!(null),
                now_bag: Some(other),
            },
            NOW,
        );
        assert!(plan.restore_bean.is_none());
    }

    /// #488 follow-up 44: a bag whose grinds were `None` gets `None` back.
    #[test]
    fn undo_puts_back_no_grinds_list_when_the_bag_had_none() {
        let mut before = bag(Vec::new());
        before.grinds = None;
        before.grinder_setting = "14".to_owned();
        let (after, undo) = undo_after_save(&before, "grinder:ek", "9");
        // The old grind waits to be folded in (save-boundaries follow-up 60).
        assert_eq!(after.legacy_grind_pending, Some(true));
        let grind = undo.grind.clone().unwrap();
        assert_eq!(grind.grinds_were_none, Some(true));
        let run = |now_bag: Bean| {
            quick_save_undo(
                &QuickSaveUndoInput {
                    undo: undo.clone(),
                    now_profile: json!(null),
                    now_bag: Some(now_bag),
                },
                NOW,
            )
        };
        let back = run(after.clone()).restore_bean.unwrap();
        assert_eq!(back.grinds, None);
        assert_eq!(
            back.legacy_grind_pending, None,
            "exactly as before the Save"
        );
        // A row for another grinder added since stays, so the list does too.
        let auto = save_bag_grind(&after, Some(&grinder("grinder:c40", "C40")), "22", NOW).bean;
        let kept_bag = run(auto).restore_bean.unwrap();
        assert_eq!(kept_bag.legacy_grind_pending, Some(true), "still waiting");
        let kept = kept_bag.grinds.unwrap();
        assert_eq!(kept.len(), 1);
        assert_eq!(kept[0].grinder_id, "grinder:c40");
        // On the wire; an older record without it reads as false.
        let wire = serde_json::to_value(&grind).unwrap();
        assert_eq!(wire["grindsWereNone"], true);
        let old: QuickSaveGrindUndo =
            serde_json::from_value(json!({ "beanId": "bean:1", "grinderId": "g" })).unwrap();
        assert_eq!(old.grinds_were_none, None, "absent: read as false");
        let migrated = QuickSaveGrindUndo::of(&bag(Vec::new()), &after, "grinder:ek");
        assert_eq!(
            migrated.grinds_were_none,
            Some(false),
            "a bag with a list, even empty"
        );
    }

    #[test]
    fn the_bridges_round_trip() {
        let inp = serde_json::to_string(&input(
            Some("9"),
            profile(QuickSaveProfileKind::Custom),
            dialled(),
        ))
        .unwrap();
        let plan: QuickSavePlan =
            serde_json::from_str(&quick_save_plan_json(&inp).unwrap()).unwrap();
        assert_eq!(plan.profile_write, QuickSaveProfileWrite::InPlace);
        let s = quick_save_plan_json(
            r#"{"profile":{"id":"p","name":"P","kind":"builtin"},"recipeOverrides":{"yield":40}}"#,
        )
        .unwrap();
        assert!(s.contains(r#""profileWrite":"copy""#), "{s}");
        let undo = quick_save_undo_json(
            r#"{"undo":{"profileId":"p","writtenProfile":{"a":1}},"nowProfile":{"a":1}}"#,
            NOW,
        )
        .unwrap();
        assert!(undo.contains(r#""outcome":"undone""#), "{undo}");
        assert!(quick_save_plan_json("nope").is_err());
    }
}

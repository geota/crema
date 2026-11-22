//! # bag_grind
//!
//! Saved grinds on a bag, the star, and how Brew picks the grind (#488, "Bag
//! grinds", 2026-11-21).
//!
//! **The model.** [`Bean::grinds`] holds at most one [`BagGrind`] per grinder
//! id and at most one starred ("this bag's grinder"). `None` = not migrated
//! yet ([`migrate_bag_grinds`]). A row for an unknown or archived grinder is
//! kept, never dropped. The "no grinder" slot is the row whose `grinder_id`
//! is [`NO_GRINDER_ID`] (Brew with no active grinder saves there).
//!
//! **What writes it:** only an explicit save: the bag editor's Save
//! ([`crate::library_edit::merge_bean_edit`], row by row), Brew's Save
//! ([`save_bag_grind`]) and auto-save when switched on
//! ([`auto_save_shot_grind`]). Nothing else does.
//!
//! **How Brew picks the grind** on selecting a bag or switching grinder
//! ([`pick_grind`]): the bag's saved grind for the active grinder, else the
//! newest shot on that bag with that grinder (by `grinder_id`; rows without
//! one matched by name, read-only), else the grind stays as it is. A bag
//! selection is one change with one inverse for Undo
//! ([`resolve_bag_selection`], review F6), and its Undo reverts only what
//! still holds what the selection set ([`bag_selection_undo`]).
//!
//! **Adding a grinder from the bag editor** is staged in the editor's draft
//! (FINAL S7): [`stage_grinder_pick`] answers the row without storing
//! anything, and [`staged_grinders_on_save`] says what the editor's Save
//! writes. Cancel drops the staged list, so nothing was written.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bean::Bean;
use crate::grind_value::same_grind;
use crate::grinder::{
    Grinder, GrinderAddKind, GrinderMatchKind, active_grinder, add_grinder, grinder_named,
    grinders_for_names, restore_grinder, shot_names_grinder,
};
#[cfg(doc)]
use crate::history::StoredShot;

/// The `grinder_id` of the "no grinder" slot.
pub const NO_GRINDER_ID: &str = "";

/// A grind saved on a bag for one grinder.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BagGrind {
    /// The grinder's id ([`Grinder::id`]); [`NO_GRINDER_ID`] for the "no
    /// grinder" slot. May name a grinder that is archived or unknown here:
    /// kept, never dropped.
    pub grinder_id: String,
    /// The grinder's name when saved, so the row still reads right after the
    /// grinder is removed from Devices.
    pub grinder_name: String,
    /// The grind, as typed or recorded; never clamped or rewritten.
    pub grind: String,
    /// "This bag's grinder": at most one row per bag.
    pub starred: bool,
}

/// The newest grind recorded on one bag with one grinder: what the history
/// answers per (bag, grinder) (`de1_history`'s `last_grinds_for_bag`).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LastGrind {
    /// The grinder id the shot was stamped with; `None` on rows from before
    /// grinder ids, which are matched by `grinder` (the name).
    pub grinder_id: Option<String>,
    /// The grinder name the shot was stamped with, as spelled on the newest.
    pub grinder: String,
    /// The grind it recorded, as stored.
    pub grind: String,
    /// When that shot completed, Unix epoch ms.
    #[typeshare(serialized_as = "I64")]
    pub completed_at: i64,
}

/// The newest shot grind among `last_grinds` for `grinder`: a row stamped
/// with its id, or a row with no id whose name is the grinder's own or a
/// catalogue alias of that model ([`crate::grinder::shot_names_grinder`]),
/// never a numbered second unit's ("Niche Zero 2"). Blank grinds don't count.
#[must_use]
pub fn newest_shot_grind<'a>(
    last_grinds: &'a [LastGrind],
    grinder: &Grinder,
) -> Option<&'a LastGrind> {
    last_grinds
        .iter()
        .filter(|l| !l.grind.trim().is_empty())
        .filter(
            |l| match l.grinder_id.as_deref().filter(|id| !id.is_empty()) {
                Some(id) => id == grinder.id,
                None => shot_names_grinder(grinder, &l.grinder),
            },
        )
        .max_by_key(|l| l.completed_at)
}

/// The bag's saved row for `grinder_id`, if any.
#[must_use]
pub fn saved_grind<'a>(bag: &'a Bean, grinder_id: &str) -> Option<&'a BagGrind> {
    bag.grinds
        .as_deref()?
        .iter()
        .find(|g| g.grinder_id == grinder_id)
}

/// How Brew picks the grind for `bag` with `grinder` active (`None` = no
/// grinder): the saved grind for it, else the newest shot on the bag with it,
/// else `None` (the grind stays as it is). As stored, never clamped.
#[must_use]
pub fn pick_grind(
    bag: &Bean,
    grinder: Option<&Grinder>,
    last_grinds: &[LastGrind],
) -> Option<String> {
    let id = grinder.map_or(NO_GRINDER_ID, |g| g.id.as_str());
    if let Some(saved) = saved_grind(bag, id).filter(|s| !s.grind.trim().is_empty()) {
        return Some(saved.grind.clone());
    }
    grinder
        .and_then(|g| newest_shot_grind(last_grinds, g))
        .map(|l| l.grind.clone())
}

/// What a save did to the bag.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrindSave {
    /// The bag to store (as it was when nothing changed).
    pub bean: Bean,
    /// Whether the saved value changed: the shells show "Grind 480 μm saved
    /// to …" only then.
    pub changed: bool,
}

/// Brew's Save of the grind (and auto-save's): the (bag, grinder) row takes
/// `grind`, or is added (not starred). The star never changes. `grinder` is
/// `None` for the "no grinder" slot. A blank grind changes nothing. The row's
/// name snapshot follows the grinder's name. `updated_at` moves only when the
/// value changed.
#[must_use]
pub fn save_bag_grind(
    bag: &Bean,
    grinder: Option<&Grinder>,
    grind: &str,
    now_ms: i64,
) -> GrindSave {
    let unchanged = || GrindSave {
        bean: bag.clone(),
        changed: false,
    };
    if grind.trim().is_empty() {
        return unchanged();
    }
    let (id, name) = grinder.map_or((NO_GRINDER_ID, ""), |g| (g.id.as_str(), g.name.as_str()));
    let mut bean = bag.clone();
    let rows = bean.grinds.get_or_insert_with(Vec::new);
    match rows.iter_mut().find(|r| r.grinder_id == id) {
        Some(row) if same_grind(&row.grind, grind) => return unchanged(),
        Some(row) => {
            row.grind = grind.to_owned();
            if !name.trim().is_empty() {
                row.grinder_name = name.to_owned();
            }
        }
        None => rows.push(BagGrind {
            grinder_id: id.to_owned(),
            grinder_name: name.to_owned(),
            grind: grind.to_owned(),
            starred: false,
        }),
    }
    bean.updated_at = now_ms.max(bag.updated_at);
    GrindSave {
        bean,
        changed: true,
    }
}

/// The rows the bag editor's Save refuses ("Enter a grind or remove this
/// row"): those with a blank grind, by grinder id.
#[must_use]
pub fn bag_grinds_to_fix(bag: &Bean) -> Vec<String> {
    bag.grinds
        .as_deref()
        .unwrap_or_default()
        .iter()
        .filter(|g| g.grind.trim().is_empty())
        .map(|g| g.grinder_id.clone())
        .collect()
}

/// The bag editor's grind rows (`edited` against `base`, the rows the editor
/// opened on) applied onto `current`, the rows as stored now, row by row by
/// grinder id: an edited or added row takes the editor's grind and name, a
/// removed row goes, and a changed star moves to the editor's starred row
/// (or off). Rows the editor didn't touch, and rows added elsewhere
/// meanwhile (auto-save), keep `current`'s values. At most one row per id and
/// one star come out.
#[must_use]
pub(crate) fn merge_bag_grinds(
    base: Option<&[BagGrind]>,
    edited: Option<&[BagGrind]>,
    current: Option<&[BagGrind]>,
) -> Option<Vec<BagGrind>> {
    if edited == base {
        return current.map(<[BagGrind]>::to_vec);
    }
    let base = base.unwrap_or_default();
    let edited = edited.unwrap_or_default();
    let mut out: Vec<BagGrind> = Vec::new();
    for row in current.unwrap_or_default() {
        if !out.iter().any(|r| r.grinder_id == row.grinder_id) {
            out.push(row.clone());
        }
    }
    let in_edited = |id: &str| edited.iter().any(|r| r.grinder_id == id);
    out.retain(|r| !base.iter().any(|b| b.grinder_id == r.grinder_id) || in_edited(&r.grinder_id));
    for row in edited {
        let was = base.iter().find(|b| b.grinder_id == row.grinder_id);
        let touched =
            was.is_none_or(|b| b.grind != row.grind || b.grinder_name != row.grinder_name);
        if !touched {
            continue;
        }
        match out.iter_mut().find(|r| r.grinder_id == row.grinder_id) {
            Some(r) => {
                r.grind.clone_from(&row.grind);
                r.grinder_name.clone_from(&row.grinder_name);
            }
            None => out.push(BagGrind {
                starred: false,
                ..row.clone()
            }),
        }
    }
    let starred = |rows: &[BagGrind]| {
        rows.iter()
            .find(|r| r.starred)
            .map(|r| r.grinder_id.clone())
    };
    let edited_star = starred(edited);
    if edited_star != starred(base) {
        for r in &mut out {
            r.starred = edited_star.as_deref() == Some(r.grinder_id.as_str());
        }
    } else {
        // Keep current's star, at most one.
        let mut seen = false;
        for r in &mut out {
            if r.starred {
                r.starred = !seen;
                seen = true;
            }
        }
    }
    Some(out)
}

/// One bag's part of the library migration (#488): its old `grinder_setting`
/// becomes a saved grind, when `grinds` is `None`; else the bag comes back
/// unchanged. The setting (when not blank) is saved for the grinder the bag's
/// `grinder` names, or, when that is blank, the one `grinder_model` names
/// (today's Save writes the setting without a bag grinder); no name at all →
/// the "no grinder" slot. A name with no record among `grinders` gets one,
/// returned second. The row is starred when the bag's own `grinder` was set
/// (the user tied the bag to that grinder; with one row, it's the only one).
/// `grinds` becomes `Some` either way; the old fields and `updated_at` stay
/// as they are.
#[must_use]
pub fn migrate_bag_grinds(
    bean: &Bean,
    grinders: &[Grinder],
    grinder_model: &str,
    now_ms: i64,
) -> (Bean, Option<Grinder>) {
    let mut out = bean.clone();
    if bean.grinds.is_some() {
        return (out, None);
    }
    if bean.grinder_setting.trim().is_empty() {
        out.grinds = Some(Vec::new());
        return (out, None);
    }
    let own = !bean.grinder.trim().is_empty();
    let name = if own {
        bean.grinder.as_str()
    } else {
        grinder_model
    };
    let mut added = None;
    let row = if name.trim().is_empty() {
        BagGrind {
            grinder_id: NO_GRINDER_ID.to_owned(),
            grinder_name: String::new(),
            grind: bean.grinder_setting.clone(),
            starred: false,
        }
    } else {
        let found = grinder_named(grinders, name).cloned().or_else(|| {
            let g = grinders_for_names(grinders, &[name], now_ms).pop();
            added.clone_from(&g);
            g
        });
        let (id, gname) = found.map_or_else(
            || (NO_GRINDER_ID.to_owned(), String::new()),
            |g| (g.id, g.name),
        );
        BagGrind {
            starred: own && !id.is_empty(),
            grinder_id: id,
            grinder_name: gname,
            grind: bean.grinder_setting.clone(),
        }
    };
    out.grinds = Some(vec![row]);
    (out, added)
}

/// Set a pointer or value to `value` (`None` clears it).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SetTo {
    /// The new value; `None` = none.
    pub value: Option<String>,
}

/// What a bag selection changes besides the bag: each part `None` when it
/// stays as it is.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SelectionChange {
    /// The active profile id.
    pub profile_id: Option<SetTo>,
    /// The active grinder id.
    pub grinder_id: Option<SetTo>,
    /// Brew's grind value.
    pub grind: Option<SetTo>,
}

impl SelectionChange {
    /// Nothing besides the bag changes: no message.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.profile_id.is_none() && self.grinder_id.is_none() && self.grind.is_none()
    }
}

/// What [`resolve_bag_selection`] reads.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BagSelectInput {
    /// The bag being selected.
    pub bag: Option<Bean>,
    /// The active profile id now.
    pub active_profile_id: Option<String>,
    /// The bag's linked profile isn't in this device's library (a dangling
    /// id): it isn't loaded, and a notice says so.
    pub linked_profile_missing: bool,
    /// The name of the bag's linked profile in this library, for the message
    /// and notices. `None` (or blank) reads as "the linked profile".
    pub linked_profile_name: Option<String>,
    /// A shot is in progress (REVIEW-FINAL-2 SF4): loading a profile would
    /// upload it to the machine mid-extraction, so the linked profile isn't
    /// loaded, and a notice says so. The grinder and grind still change.
    pub shot_in_progress: bool,
    /// The active grinder pointer now.
    pub active_grinder_id: Option<String>,
    /// Brew's grind value now.
    pub grind: Option<String>,
    /// The grinder records.
    pub grinders: Vec<Grinder>,
    /// The bag's newest grind per grinder, from the history.
    pub last_grinds: Vec<LastGrind>,
    /// The bag that was selected before this pick. Picking the bag that is
    /// already selected changes nothing (FINAL N7): an unsaved grind on Brew
    /// stays, with no message.
    pub active_bean_id: Option<String>,
}

/// Why a bag pick left something out, for a passing notice.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SelectionNoticeKind {
    /// The bag's linked profile isn't in this library: nothing was loaded.
    LinkedProfileMissing,
    /// A shot is in progress: the linked profile wasn't loaded.
    ProfileNotLoaded,
}

/// A passing notice about a bag pick (no Undo), shown verbatim.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SelectionNotice {
    /// What it's about.
    pub kind: SelectionNoticeKind,
    /// The text to show.
    pub text: String,
}

/// One bag selection, resolved (review F6): the change, its inverse for the
/// one Undo, and the words to show (REVIEW-FINAL-2 SF4), so every shell
/// shows the same thing verbatim.
///
/// It is self-contained, so a relayed pick can be answered with it: the hub
/// resolves and applies the pick, and sends this back to the remote that
/// picked, which shows [`Self::message`] (with its own Undo) and
/// [`Self::notices`] as they are, with no lookups in its own library. The
/// remote's Undo is [`bag_selection_undo`] of this against the hub's state as
/// the remote mirrors it, relayed back as ordinary profile, grinder and grind
/// commands.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BagSelection {
    /// What to apply, with the bag.
    pub change: SelectionChange,
    /// What Undo applies: the previous value of each changed part.
    pub undo: SelectionChange,
    /// Something besides the bag changed: show one message ("Ethiopia Guji:
    /// Blooming profile, Niche Zero at 14") with one Undo.
    pub changed: bool,
    /// The grinder switched to, for the message; `None` when it stays.
    pub grinder_name: Option<String>,
    /// The one message with the Undo ("Ethiopia Guji: Blooming profile, Niche
    /// Zero at 14"), set exactly when [`Self::changed`].
    #[serde(default)]
    pub message: Option<String>,
    /// Passing notices, no Undo, in order (a missing linked profile, a
    /// profile not loaded mid-shot). They can come with no change.
    #[serde(default)]
    pub notices: Vec<SelectionNotice>,
}

fn non_blank(s: Option<&str>) -> Option<&str> {
    s.filter(|s| !s.trim().is_empty())
}

/// Resolve selecting a bag on Brew into one change (review F6):
///
/// 1. **Profile:** the bag's linked profile, when set, here, and not active,
///    and no shot is in progress. A linked profile missing here, or one held
///    back by a shot in progress, gives a notice instead (SF4).
/// 2. **Grinder:** the bag's starred grinder, when it's still on Devices
///    (live) and not active. No star, or a starred grinder that was removed:
///    the active grinder stays.
/// 3. **Grind:** [`pick_grind`] with the grinder active after step 2, when it
///    differs from Brew's grind now.
///
/// The inverse restores the previous value of each part that changes. The
/// message reads "‹bag›: ‹profile› profile, ‹grinder› at ‹grind›", the grind
/// formatted on the grinder's scale ([`crate::format_grind`]); a grind change
/// with no grinder reads "grind ‹grind›".
#[must_use]
pub fn resolve_bag_selection(input: &BagSelectInput) -> BagSelection {
    let Some(bag) = input.bag.as_ref() else {
        return BagSelection::default();
    };
    if input.active_bean_id.as_deref() == Some(bag.id.as_str()) {
        return BagSelection::default();
    }
    let mut change = SelectionChange::default();
    let mut undo = SelectionChange::default();
    let mut notices = Vec::new();
    let profile_name = non_blank(input.linked_profile_name.as_deref()).map(str::trim);
    if let Some(linked) = non_blank(bag.linked_profile_id.as_deref()) {
        if input.linked_profile_missing {
            notices.push(SelectionNotice {
                kind: SelectionNoticeKind::LinkedProfileMissing,
                text: format!("{}\u{2019}s linked profile no longer exists", bag.name),
            });
        } else if input.active_profile_id.as_deref() == Some(linked) {
            // Already loaded: nothing to change, nothing to say.
        } else if input.shot_in_progress {
            let name = profile_name.map_or_else(
                || "the linked profile".to_owned(),
                |n| format!("\u{201c}{n}\u{201d}"),
            );
            notices.push(SelectionNotice {
                kind: SelectionNoticeKind::ProfileNotLoaded,
                text: format!("Shot in progress \u{2014} {name} not loaded"),
            });
        } else {
            change.profile_id = Some(SetTo {
                value: Some(linked.to_owned()),
            });
            undo.profile_id = Some(SetTo {
                value: input.active_profile_id.clone(),
            });
        }
    }
    let active_now = active_grinder(&input.grinders, input.active_grinder_id.as_deref());
    let starred = bag
        .grinds
        .as_deref()
        .unwrap_or_default()
        .iter()
        .find(|g| g.starred)
        .and_then(|s| active_grinder(&input.grinders, Some(&s.grinder_id)));
    let mut grinder_name = None;
    let active_after = match starred {
        Some(s) if active_now.is_none_or(|a| a.id != s.id) => {
            change.grinder_id = Some(SetTo {
                value: Some(s.id.clone()),
            });
            undo.grinder_id = Some(SetTo {
                value: input.active_grinder_id.clone(),
            });
            grinder_name = Some(s.name.clone());
            Some(s)
        }
        _ => active_now,
    };
    if let Some(grind) = pick_grind(bag, active_after, &input.last_grinds)
        && input
            .grind
            .as_deref()
            .is_none_or(|now| !same_grind(now, &grind))
    {
        change.grind = Some(SetTo { value: Some(grind) });
        undo.grind = Some(SetTo {
            value: input.grind.clone(),
        });
    }
    let changed = !change.is_empty();
    let message = changed.then(|| {
        let mut parts = Vec::new();
        if change.profile_id.is_some() {
            parts.push(format!("{} profile", profile_name.unwrap_or("linked")));
        }
        let grind = change
            .grind
            .as_ref()
            .and_then(|g| g.value.as_deref())
            .map(|g| {
                crate::format_grind(
                    g,
                    &active_after.map(|a| a.scale.clone()).unwrap_or_default(),
                )
            });
        let grinder = grinder_name
            .as_deref()
            .or_else(|| active_after.map(|a| a.name.as_str()));
        match (change.grinder_id.is_some(), grinder, grind) {
            (true, g, Some(at)) => parts.push(format!("{} at {at}", g.unwrap_or("No grinder"))),
            (true, g, None) => parts.push(g.unwrap_or("No grinder").to_owned()),
            (false, Some(g), Some(at)) => parts.push(format!("{g} at {at}")),
            (false, None, Some(at)) => parts.push(format!("grind {at}")),
            (false, _, None) => {}
        }
        format!("{}: {}", bag.name, parts.join(", "))
    });
    BagSelection {
        changed,
        change,
        undo,
        grinder_name,
        message,
        notices,
    }
}

/// What Brew holds now, for [`bag_selection_undo`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct SelectionState {
    /// The active profile id now.
    pub profile_id: Option<String>,
    /// The active grinder pointer now.
    pub grinder_id: Option<String>,
    /// Brew's grind now.
    pub grind: Option<String>,
    /// A shot is in progress: loading a profile would upload it to the
    /// machine mid-extraction, so Undo leaves the profile as it is.
    pub shot_in_progress: bool,
}

fn still_set(now: Option<&str>, set: Option<&SetTo>) -> bool {
    set.is_some_and(|set| now == set.value.as_deref())
}

fn still_grind(now: Option<&str>, set: Option<&SetTo>) -> bool {
    let Some(set) = set else { return false };
    match (non_blank(now), non_blank(set.value.as_deref())) {
        (Some(now), Some(set)) => same_grind(now, set),
        (None, None) => true,
        _ => false,
    }
}

/// The Undo of a bag selection (REVIEW-CORE H4, FINAL S9): each part of
/// `selection.undo` only while Brew still holds what the selection set, so a
/// change made since (a dial, a grinder pick, a profile pick) stands. The
/// grind compares as [`same_grind`] (`"15"` is `"15.0"`), and blank matches
/// blank. With a shot in progress the profile part is left out. The bag
/// itself is the shell's to put back.
#[must_use]
pub fn bag_selection_undo(selection: &BagSelection, now: &SelectionState) -> SelectionChange {
    let SelectionChange {
        profile_id,
        grinder_id,
        grind,
    } = &selection.change;
    SelectionChange {
        profile_id: selection.undo.profile_id.clone().filter(|_| {
            !now.shot_in_progress && still_set(now.profile_id.as_deref(), profile_id.as_ref())
        }),
        grinder_id: selection
            .undo
            .grinder_id
            .clone()
            .filter(|_| still_set(now.grinder_id.as_deref(), grinder_id.as_ref())),
        grind: selection
            .undo
            .grind
            .clone()
            .filter(|_| still_grind(now.grind.as_deref(), grind.as_ref())),
    }
}

/// What [`resolve_grinder_switch`] reads.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct GrinderSwitchInput {
    /// The selected bag, if any.
    pub bag: Option<Bean>,
    /// The grinder being switched to; `None` = no grinder.
    pub grinder_id: Option<String>,
    /// The grinder records.
    pub grinders: Vec<Grinder>,
    /// The bag's newest grind per grinder, from the history.
    pub last_grinds: Vec<LastGrind>,
}

/// The grind Brew takes on switching grinder: [`pick_grind`] for the selected
/// bag and the new grinder. `None` (no bag, or nothing found) = the grind
/// stays as it is.
#[must_use]
pub fn resolve_grinder_switch(input: &GrinderSwitchInput) -> Option<String> {
    let bag = input.bag.as_ref()?;
    let grinder = active_grinder(&input.grinders, input.grinder_id.as_deref());
    pick_grind(bag, grinder, &input.last_grinds)
}

/// What [`auto_save_shot_grind`] reads.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct AutoSaveInput {
    /// The device's "Save each shot's grind to its bag" setting
    /// ([`crate::app_settings::CommonSettings::grind_auto_save`]).
    pub enabled: bool,
    /// This device recorded the shot (the hub, or a device on its own). A
    /// remote's mirror copy of a shot never auto-saves (review F8).
    pub recorded_here: bool,
    /// The kept shot's bag id (`bean.beanId`). A discarded aborted shot
    /// isn't kept; an undone discard is.
    pub shot_bean_id: Option<String>,
    /// The shot's grinder id ([`StoredShot::grinder_id`]); `None` = no
    /// grinder.
    pub shot_grinder_id: Option<String>,
    /// The shot's grinder name snapshot ([`StoredShot::grinder_model`]).
    pub shot_grinder_name: Option<String>,
    /// The shot's grind, as stored (web `metadata.grinderSetting`, Android
    /// `grindSetting` as text).
    pub shot_grind: Option<String>,
    /// The shot's bag, as stored now.
    pub bag: Option<Bean>,
}

/// Auto-save (#488): after a kept shot on the device that recorded it, with
/// the setting on, the shot's grind is saved for its bag and grinder as
/// Brew's Save would ([`save_bag_grind`]: adds a row if missing, never
/// changes the star). The grinder is the shot's (`grinder_id` and name
/// snapshot); a shot with none saves to the "no grinder" slot. `None` when
/// it doesn't apply: setting off, a mirror copy, no bag, a shot on another
/// bag, or no grind.
#[must_use]
pub fn auto_save_shot_grind(input: &AutoSaveInput, now_ms: i64) -> Option<GrindSave> {
    if !input.enabled || !input.recorded_here {
        return None;
    }
    let bag = input.bag.as_ref()?;
    let grind = non_blank(input.shot_grind.as_deref())?;
    if input.shot_bean_id.as_deref() != Some(bag.id.as_str()) {
        return None;
    }
    let grinder = non_blank(input.shot_grinder_id.as_deref()).map(|id| Grinder {
        id: id.to_owned(),
        name: input.shot_grinder_name.clone().unwrap_or_default(),
        ..Grinder::default()
    });
    Some(save_bag_grind(bag, grinder.as_ref(), grind, now_ms))
}

// ── Adding a grinder from the bag editor (FINAL S7) ──

/// The grinders as the bag editor sees them: `stored` with the draft's
/// `staged` records over them by id (a staged revival reads as live), then
/// the staged new ones. The editor's type-or-pick box and its rows read
/// this.
#[must_use]
pub fn bag_editor_grinders(stored: &[Grinder], staged: &[Grinder]) -> Vec<Grinder> {
    let mut out: Vec<Grinder> = stored
        .iter()
        .map(|g| staged.iter().find(|s| s.id == g.id).unwrap_or(g).clone())
        .collect();
    out.extend(
        staged
            .iter()
            .filter(|s| !stored.iter().any(|g| g.id == s.id))
            .cloned(),
    );
    out
}

/// A pick in the bag editor's type-or-pick box, for [`stage_grinder_pick`].
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct GrinderPickInput {
    /// The grinder records as stored.
    pub grinders: Vec<Grinder>,
    /// The records this editor staged so far (from earlier picks).
    pub staged: Vec<Grinder>,
    /// Which list the pick came from; `None` = "+ Create" with `name`.
    pub kind: Option<GrinderMatchKind>,
    /// The grinder id (owned, archived) or the catalogue entry id.
    pub id: String,
    /// The typed name ("+ Create").
    pub name: String,
}

/// What [`stage_grinder_pick`] answers.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StagedGrinderPick {
    /// The grinder for the new bag row, as it will be stored on Save.
    pub grinder: Grinder,
    /// Already owned (or staged earlier), a new record, or an archived one
    /// brought back.
    pub kind: GrinderAddKind,
    /// The draft's staged records afterwards; the editor keeps it until Save
    /// or Cancel.
    pub staged: Vec<Grinder>,
}

/// A grinder picked in the bag editor's Add box (FINAL S7), staged in the
/// draft: as [`add_grinder`] (or [`restore_grinder`] for an archived pick)
/// over [`bag_editor_grinders`], but nothing is stored and the active
/// grinder doesn't change. A typo ("Nich") then never reaches Devices unless
/// it is saved. `None` when the pick names nothing (an unknown id or entry,
/// a blank name).
#[must_use]
pub fn stage_grinder_pick(input: &GrinderPickInput, now_ms: i64) -> Option<StagedGrinderPick> {
    let view = bag_editor_grinders(&input.grinders, &input.staged);
    let (grinder, kind) = match input.kind {
        Some(GrinderMatchKind::Owned | GrinderMatchKind::Archived) => {
            let found = view
                .iter()
                .find(|g| g.id == input.id && g.deleted_at.is_none())?;
            if found.is_live() {
                (found.clone(), GrinderAddKind::Existing)
            } else {
                (restore_grinder(found, now_ms), GrinderAddKind::Revived)
            }
        }
        Some(GrinderMatchKind::Catalogue) => {
            let add = add_grinder(&view, "", Some(&input.id), now_ms)?;
            (add.grinder, add.kind)
        }
        None => {
            let add = add_grinder(&view, &input.name, None, now_ms)?;
            (add.grinder, add.kind)
        }
    };
    let mut staged = input.staged.clone();
    if kind != GrinderAddKind::Existing {
        match staged.iter_mut().find(|s| s.id == grinder.id) {
            Some(s) => s.clone_from(&grinder),
            None => staged.push(grinder.clone()),
        }
    }
    Some(StagedGrinderPick {
        grinder,
        kind,
        staged,
    })
}

/// What [`staged_grinders_on_save`] reads.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct StagedGrindersInput {
    /// The grinder records as stored now (at Save).
    pub grinders: Vec<Grinder>,
    /// The editor's staged records.
    pub staged: Vec<Grinder>,
    /// The bag as the Save stores it (after [`crate::merge_bean_edit`]).
    pub bag: Option<Bean>,
    /// The active grinder pointer now.
    pub active_grinder_id: Option<String>,
}

/// What the bag editor's Save writes besides the bag.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StagedGrindersSave {
    /// Grinder records to upsert, in pick order.
    pub grinders: Vec<Grinder>,
    /// The active grinder pointer to set; `None` = it stays.
    pub active_grinder_id: Option<SetTo>,
}

/// The bag editor's Save of its staged grinders (FINAL S7):
///
/// - only the staged records the saved bag still has a row for (a row added
///   and removed again writes nothing);
/// - a new record as staged; an archived one restored from the row as stored
///   now (so a Devices edit made meanwhile stays); one already live, or
///   deleted meanwhile, writes nothing;
/// - with no live grinder active, the one written becomes active (the bag's
///   starred one first, else the first picked; review 499 "a new grinder
///   becomes active when none is"), here at Save, not at the pick.
///
/// Cancel discards the staged list: nothing to call.
#[must_use]
pub fn staged_grinders_on_save(input: &StagedGrindersInput, now_ms: i64) -> StagedGrindersSave {
    let rows = input
        .bag
        .as_ref()
        .and_then(|b| b.grinds.as_deref())
        .unwrap_or_default();
    let mut out = StagedGrindersSave::default();
    for staged in &input.staged {
        if !rows.iter().any(|r| r.grinder_id == staged.id) {
            continue;
        }
        match input.grinders.iter().find(|g| g.id == staged.id) {
            None => out.grinders.push(staged.clone()),
            Some(stored) if stored.deleted_at.is_none() && !stored.is_live() => {
                out.grinders.push(restore_grinder(stored, now_ms));
            }
            Some(_) => {}
        }
    }
    if active_grinder(&input.grinders, input.active_grinder_id.as_deref()).is_none() {
        let starred = |g: &&Grinder| rows.iter().any(|r| r.starred && r.grinder_id == g.id);
        let pick = out
            .grinders
            .iter()
            .find(starred)
            .or_else(|| out.grinders.first());
        if let Some(g) = pick {
            out.active_grinder_id = Some(SetTo {
                value: Some(g.id.clone()),
            });
        }
    }
    out
}

// ── History lookups (REVIEW-CORE M1) ──

/// One shot row on a bag, as a shell holds it in memory, for
/// [`last_grinds_from_rows`]: the same columns the history's
/// `last_grinds_for_bag` reads.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct LastGrindRow {
    /// When the shot completed, Unix epoch ms.
    #[typeshare(serialized_as = "I64")]
    pub completed_at: i64,
    /// The shot's id: the tie-break among rows that completed at the same
    /// time (the higher id is newer, as in the history query).
    pub id: String,
    /// The grinder id the shot was stamped with; `None` or blank on rows
    /// from before grinder ids.
    pub grinder_id: Option<String>,
    /// The grinder name the shot was stamped with (`grinderModel`).
    pub grinder_model: Option<String>,
    /// The grind it recorded, as stored; a number reads as its text.
    #[serde(deserialize_with = "grind_or_number")]
    pub grind: Option<String>,
}

/// A stored grind that may be a number (old Android rows): its text.
fn grind_or_number<'de, D: serde::Deserializer<'de>>(d: D) -> Result<Option<String>, D::Error> {
    Ok(match serde_json::Value::deserialize(d)? {
        serde_json::Value::String(s) => Some(s),
        serde_json::Value::Number(n) if n.as_f64().is_some_and(f64::is_finite) => {
            Some(n.to_string())
        }
        _ => None,
    })
}

/// The newest grind per grinder on a bag, newest first, from its shot rows:
/// the grouping the history's `last_grinds_for_bag` applies, for shells
/// that hold rows in memory (web's fallback, Android). Rows without a grind
/// (or a blank one), and rows that name no grinder (no id and no name), are
/// skipped. One entry per grinder id; rows without an id are grouped by
/// name (trimmed, any case), and a blank name names no grinder.
#[must_use]
pub fn last_grinds_from_rows(rows: &[LastGrindRow]) -> Vec<LastGrind> {
    let mut sorted: Vec<&LastGrindRow> = rows.iter().collect();
    sorted.sort_by(|a, b| {
        b.completed_at
            .cmp(&a.completed_at)
            .then_with(|| b.id.cmp(&a.id))
    });
    let mut out: Vec<LastGrind> = Vec::new();
    for row in sorted {
        let Some(grind) = row
            .grind
            .as_deref()
            .map(str::trim)
            .filter(|g| !g.is_empty())
        else {
            continue;
        };
        let grinder_id = row
            .grinder_id
            .as_deref()
            .map(str::trim)
            .filter(|id| !id.is_empty())
            .map(str::to_owned);
        let grinder = row.grinder_model.clone().unwrap_or_default();
        let seen = match grinder_id.as_deref() {
            Some(id) => out.iter().any(|l| l.grinder_id.as_deref() == Some(id)),
            None => {
                crate::grinder::grinder_key(&grinder).is_empty()
                    || out.iter().any(|l| {
                        l.grinder_id.is_none() && crate::grinder::same_grinder(&l.grinder, &grinder)
                    })
            }
        };
        if seen {
            continue;
        }
        out.push(LastGrind {
            grinder_id,
            grinder,
            grind: grind.to_owned(),
            completed_at: row.completed_at,
        });
    }
    out
}

/// One grinder row in Brew's grinder pickers, for the selected bag
/// ([`grinder_pick_rows`]). The shells word it: "This bag's grinder · …",
/// "Saved 480 μm", "Nothing saved · last shot 32", "Nothing saved"; with no
/// bag, the grinder's range.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrinderPickRow {
    /// The grinder's id.
    pub grinder_id: String,
    /// The bag's row for it is starred ("This bag's grinder").
    pub starred: bool,
    /// The bag's saved grind for it, when there is one (not blank).
    pub saved: Option<String>,
    /// With nothing saved: the newest shot grind on the bag with it
    /// ([`newest_shot_grind`]: by id, or an older row by name through the
    /// catalogue's exact aliases, so "Niche" finds the Niche Zero, but never
    /// the "Niche Zero 2").
    pub last_shot: Option<String>,
}

/// The pickers' rows for `grinders` (the live ones, as listed) with `bag`
/// selected (`None` = no bag: every row empty), from the bag's
/// `last_grinds` (REVIEW-CORE M1: the same lookups as [`pick_grind`], so a
/// row never says "Nothing saved" where selecting the bag loads a grind).
#[must_use]
pub fn grinder_pick_rows(
    grinders: &[Grinder],
    bag: Option<&Bean>,
    last_grinds: &[LastGrind],
) -> Vec<GrinderPickRow> {
    grinders
        .iter()
        .map(|g| {
            let row = bag.and_then(|b| saved_grind(b, &g.id));
            let saved = row
                .map(|r| r.grind.trim())
                .filter(|v| !v.is_empty())
                .map(str::to_owned);
            let last_shot = match (bag, &saved) {
                (Some(_), None) => {
                    newest_shot_grind(last_grinds, g).map(|l| l.grind.trim().to_owned())
                }
                _ => None,
            };
            GrinderPickRow {
                grinder_id: g.id.clone(),
                starred: row.is_some_and(|r| r.starred),
                saved,
                last_shot,
            }
        })
        .collect()
}

/// The row the bag editor adds for `grinder` (REVIEW-CORE M1, the editor
/// seed): its grind starts as the newest shot grind on the bag with that
/// grinder ([`newest_shot_grind`] over the bag's `last_grinds`), else empty
/// (the editor asks for one). Not starred.
#[must_use]
pub fn new_bag_grind_row(grinder: &Grinder, last_grinds: &[LastGrind]) -> BagGrind {
    BagGrind {
        grinder_id: grinder.id.clone(),
        grinder_name: grinder.name.clone(),
        grind: newest_shot_grind(last_grinds, grinder)
            .map(|l| l.grind.trim().to_owned())
            .unwrap_or_default(),
        starred: false,
    }
}

// ── JSON bridges ──

fn parse<T: serde::de::DeserializeOwned>(json: &str) -> Result<T, String> {
    serde_json::from_str(json).map_err(|e| e.to_string())
}

fn to_json<T: Serialize>(value: &T) -> Result<String, String> {
    serde_json::to_string(value).map_err(|e| e.to_string())
}

/// JSON-bridged [`save_bag_grind`]: a `Bean` JSON, the grinder (a `Grinder`
/// JSON, or `null` for the "no grinder" slot), the grind and now in, a
/// [`GrindSave`] out.
///
/// # Errors
/// The JSON parse error string on a malformed bag or grinder.
pub fn save_bag_grind_json(
    bean_json: &str,
    grinder_json: &str,
    grind: &str,
    now_ms: i64,
) -> Result<String, String> {
    let bag: Bean = parse(bean_json)?;
    let grinder: Option<Grinder> = parse(grinder_json)?;
    to_json(&save_bag_grind(&bag, grinder.as_ref(), grind, now_ms))
}

/// JSON-bridged [`bag_grinds_to_fix`]: a `Bean` JSON in, the grinder ids
/// (a JSON array) out.
///
/// # Errors
/// The JSON parse error string on a malformed bag.
pub fn bag_grinds_to_fix_json(bean_json: &str) -> Result<String, String> {
    to_json(&bag_grinds_to_fix(&parse(bean_json)?))
}

/// JSON-bridged [`resolve_bag_selection`]: a [`BagSelectInput`] JSON in, a
/// [`BagSelection`] out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn resolve_bag_selection_json(input_json: &str) -> Result<String, String> {
    to_json(&resolve_bag_selection(&parse(input_json)?))
}

/// JSON-bridged [`bag_selection_undo`]: `{"selection": BagSelection, "now":
/// SelectionState}` in, a [`SelectionChange`] out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn bag_selection_undo_json(input_json: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    struct In {
        selection: BagSelection,
        now: SelectionState,
    }
    let inp: In = parse(input_json)?;
    to_json(&bag_selection_undo(&inp.selection, &inp.now))
}

/// JSON-bridged [`bag_editor_grinders`]: the stored and staged `Grinder[]`
/// JSON in, a `Grinder[]` JSON out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn bag_editor_grinders_json(stored_json: &str, staged_json: &str) -> Result<String, String> {
    let stored: Vec<Grinder> = parse(stored_json)?;
    let staged: Vec<Grinder> = parse(staged_json)?;
    to_json(&bag_editor_grinders(&stored, &staged))
}

/// JSON-bridged [`stage_grinder_pick`]: a [`GrinderPickInput`] JSON and now
/// in, a [`StagedGrinderPick`] JSON or `null` out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn stage_grinder_pick_json(input_json: &str, now_ms: i64) -> Result<String, String> {
    to_json(&stage_grinder_pick(&parse(input_json)?, now_ms))
}

/// JSON-bridged [`staged_grinders_on_save`]: a [`StagedGrindersInput`] JSON
/// and now in, a [`StagedGrindersSave`] JSON out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn staged_grinders_on_save_json(input_json: &str, now_ms: i64) -> Result<String, String> {
    to_json(&staged_grinders_on_save(&parse(input_json)?, now_ms))
}

/// JSON-bridged [`resolve_grinder_switch`]: a [`GrinderSwitchInput`] JSON in,
/// the grind as a JSON string or `null` out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn resolve_grinder_switch_json(input_json: &str) -> Result<String, String> {
    to_json(&resolve_grinder_switch(&parse(input_json)?))
}

/// JSON-bridged [`auto_save_shot_grind`]: an [`AutoSaveInput`] JSON and now
/// in, a [`GrindSave`] JSON or `null` out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn auto_save_shot_grind_json(input_json: &str, now_ms: i64) -> Result<String, String> {
    to_json(&auto_save_shot_grind(&parse(input_json)?, now_ms))
}

/// What the history-lookup bridges read: a bag's `lastGrinds` and a grinder
/// or the grinders, and the bag.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LookupIn {
    #[serde(default)]
    last_grinds: Vec<LastGrind>,
    #[serde(default)]
    grinder: Option<Grinder>,
    #[serde(default)]
    grinders: Vec<Grinder>,
    #[serde(default)]
    bag: Option<Bean>,
}

/// JSON-bridged [`newest_shot_grind`]: `{"lastGrinds": LastGrind[],
/// "grinder": Grinder}` in, a `LastGrind` JSON or `null` out.
///
/// # Errors
/// The JSON parse error string on malformed input, or a missing grinder.
pub fn newest_shot_grind_json(input_json: &str) -> Result<String, String> {
    let inp: LookupIn = parse(input_json)?;
    let grinder = inp.grinder.ok_or("missing grinder")?;
    to_json(&newest_shot_grind(&inp.last_grinds, &grinder))
}

/// JSON-bridged [`last_grinds_from_rows`]: a `LastGrindRow` array in, a
/// `LastGrind` array out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn last_grinds_from_rows_json(rows_json: &str) -> Result<String, String> {
    let rows: Vec<LastGrindRow> = parse(rows_json)?;
    to_json(&last_grinds_from_rows(&rows))
}

/// JSON-bridged [`grinder_pick_rows`]: `{"grinders": Grinder[], "bag":
/// Bean | null, "lastGrinds": LastGrind[]}` in, a `GrinderPickRow` array
/// out.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn grinder_pick_rows_json(input_json: &str) -> Result<String, String> {
    let inp: LookupIn = parse(input_json)?;
    to_json(&grinder_pick_rows(
        &inp.grinders,
        inp.bag.as_ref(),
        &inp.last_grinds,
    ))
}

/// JSON-bridged [`new_bag_grind_row`]: `{"grinder": Grinder, "lastGrinds":
/// LastGrind[]}` in, a `BagGrind` out.
///
/// # Errors
/// The JSON parse error string on malformed input, or a missing grinder.
pub fn new_bag_grind_row_json(input_json: &str) -> Result<String, String> {
    let inp: LookupIn = parse(input_json)?;
    let grinder = inp.grinder.ok_or("missing grinder")?;
    to_json(&new_bag_grind_row(&grinder, &inp.last_grinds))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grinder::{
        GrinderLibraryMigration, MigrationGate, add_grinder, archive_grinder,
        migrate_grinder_library,
    };

    const NOW: i64 = 5_000;

    fn g(id: &str, name: &str) -> Grinder {
        Grinder {
            id: id.into(),
            name: name.into(),
            updated_at: 1,
            ..Grinder::default()
        }
    }

    fn row(id: &str, grind: &str, starred: bool) -> BagGrind {
        BagGrind {
            grinder_id: id.into(),
            grinder_name: id.trim_start_matches("grinder:").into(),
            grind: grind.into(),
            starred,
        }
    }

    fn bag_with(rows: Vec<BagGrind>) -> Bean {
        let mut b = Bean::new("bean:1".into(), "Guji".into(), 100);
        b.grinds = Some(rows);
        b
    }

    fn last(id: Option<&str>, name: &str, grind: &str, at: i64) -> LastGrind {
        LastGrind {
            grinder_id: id.map(str::to_owned),
            grinder: name.into(),
            grind: grind.into(),
            completed_at: at,
        }
    }

    // ── Bag migration (one test per rule) ──

    fn old_bag(id: &str, grinder: &str, setting: &str) -> Bean {
        let mut b = Bean::new(id.into(), id.into(), 100);
        b.grinder = grinder.into();
        b.grinder_setting = setting.into();
        b
    }

    fn migrated(bags: &[Bean], model: &str) -> GrinderLibraryMigration {
        migrate_grinder_library(&[], bags, model, MigrationGate::READ, NOW)
    }

    #[test]
    fn a_bags_setting_becomes_a_starred_grind_for_its_own_grinder() {
        let m = migrated(&[old_bag("b1", "Niche Zero", "14")], "EK43");
        let grinds = m.updated_beans[0].grinds.as_deref().unwrap();
        assert_eq!(
            grinds,
            [BagGrind {
                grinder_id: "grinder:niche-zero".into(),
                grinder_name: "Niche Zero".into(),
                grind: "14".into(),
                starred: true,
            }]
        );
    }

    #[test]
    fn a_setting_with_no_bag_grinder_falls_back_to_the_settings_grinder_unstarred() {
        let m = migrated(&[old_bag("b1", "", "6 + a tooth")], "EK43");
        let grinds = m.updated_beans[0].grinds.as_deref().unwrap();
        assert_eq!(grinds.len(), 1);
        assert_eq!(grinds[0].grinder_id, "grinder:ek43");
        assert_eq!(grinds[0].grind, "6 + a tooth");
        assert!(!grinds[0].starred, "the bag wasn't tied to that grinder");
    }

    #[test]
    fn a_setting_with_no_grinder_name_at_all_goes_to_the_no_grinder_slot() {
        let m = migrated(&[old_bag("b1", "  ", "3.5")], "");
        let grinds = m.updated_beans[0].grinds.as_deref().unwrap();
        assert_eq!(
            grinds,
            [BagGrind {
                grind: "3.5".into(),
                ..BagGrind::default()
            }]
        );
        assert!(m.new_grinders.is_empty());
    }

    #[test]
    fn a_bag_with_no_setting_gets_no_grinds_but_is_marked_migrated() {
        let m = migrated(&[old_bag("b1", "Niche Zero", " ")], "");
        assert_eq!(m.updated_beans[0].grinds, Some(Vec::new()));
    }

    #[test]
    fn the_bag_step_maps_names_through_the_catalogue_and_adds_missing_grinders() {
        // Records exist (the grinders step doesn't run): "niche" finds the
        // Niche Zero record; "Comandante" has none yet, so one is added.
        let nz = add_grinder(&[], "", Some("niche-zero"), 1).unwrap().grinder;
        let m = migrate_grinder_library(
            std::slice::from_ref(&nz),
            &[
                old_bag("b1", "niche", "14"),
                old_bag("b2", "Comandante", "22"),
            ],
            "",
            MigrationGate::READ,
            NOW,
        );
        assert_eq!(
            m.updated_beans[0].grinds.as_deref().unwrap()[0].grinder_id,
            nz.id
        );
        assert_eq!(m.new_grinders.len(), 1);
        assert_eq!(m.new_grinders[0].name, "Comandante");
        assert_eq!(
            m.updated_beans[1].grinds.as_deref().unwrap()[0].grinder_id,
            m.new_grinders[0].id
        );
    }

    #[test]
    fn an_archived_grinder_still_takes_the_bags_old_grind() {
        let nz = g("grinder:nz", "Niche Zero");
        let archived = archive_grinder(&nz, None, 2).grinder;
        let (b, added) =
            migrate_bag_grinds(&old_bag("b1", "Niche Zero", "14"), &[archived], "", NOW);
        assert_eq!(added, None);
        assert_eq!(b.grinds.unwrap()[0].grinder_id, "grinder:nz");
    }

    #[test]
    fn a_migrated_bag_is_left_alone() {
        let b = bag_with(vec![row("grinder:a", "1", false)]);
        let mut with_old = b.clone();
        with_old.grinder_setting = "9".into();
        assert_eq!(
            migrate_bag_grinds(&with_old, &[], "EK43", NOW),
            (with_old, None)
        );
    }

    #[test]
    fn the_old_fields_survive_a_migration_and_a_round_trip() {
        let before = old_bag("b1", "Niche Zero", "14");
        let m = migrated(std::slice::from_ref(&before), "EK43");
        let after = &m.updated_beans[0];
        let json = serde_json::to_string(after).unwrap();
        let back: Bean = serde_json::from_str(&json).unwrap();
        assert_eq!(&back, after);
        assert_eq!(
            (back.grinder.as_str(), back.grinder_setting.as_str()),
            ("Niche Zero", "14")
        );
        let coerced = crate::coerce_bean(&serde_json::from_str(&json).unwrap(), 0).unwrap();
        assert_eq!(
            coerced.grinds, after.grinds,
            "a stored row reads back with its grinds"
        );
        assert_eq!(coerced.grinder_setting, "14");
    }

    // ── Pick order ──

    #[test]
    fn a_saved_grind_beats_shots() {
        let nz = g("grinder:nz", "Niche Zero");
        let bag = bag_with(vec![row("grinder:nz", "14", false)]);
        let lasts = vec![last(Some("grinder:nz"), "Niche Zero", "12", 99)];
        assert_eq!(pick_grind(&bag, Some(&nz), &lasts).as_deref(), Some("14"));
    }

    #[test]
    fn else_the_newest_shot_by_id_then_by_name_for_rows_without_one() {
        let nz = add_grinder(&[], "Niche Zero", None, 1).unwrap().grinder;
        let bag = bag_with(Vec::new());
        let lasts = vec![
            last(Some(&nz.id), "Renamed", "15", 20),
            last(None, "niche", "13", 30),
            last(Some("grinder:other"), "Niche Zero", "9", 40),
            last(None, "EK43", "1", 50),
        ];
        // The old row named through the catalogue is newest.
        assert_eq!(pick_grind(&bag, Some(&nz), &lasts).as_deref(), Some("13"));
        // A row stamped with another id never counts, even with the name.
        let lasts = vec![last(Some("grinder:other"), "Niche Zero", "9", 40)];
        assert_eq!(pick_grind(&bag, Some(&nz), &lasts), None);
    }

    #[test]
    fn else_the_grind_stays_and_values_are_never_clamped() {
        let motto = add_grinder(&[], "", Some("bookoo-motto-80"), 1)
            .unwrap()
            .grinder;
        assert_eq!(pick_grind(&bag_with(Vec::new()), Some(&motto), &[]), None);
        let bag = bag_with(vec![row(&motto.id, "1200", false)]);
        assert_eq!(pick_grind(&bag, Some(&motto), &[]).as_deref(), Some("1200"));
    }

    #[test]
    fn with_no_grinder_the_no_grinder_slot_is_used() {
        let bag = bag_with(vec![row(NO_GRINDER_ID, "3.5", false)]);
        assert_eq!(
            pick_grind(&bag, None, &[last(None, "EK43", "1", 1)]).as_deref(),
            Some("3.5")
        );
        assert_eq!(
            pick_grind(&bag_with(Vec::new()), None, &[last(None, "EK43", "1", 1)]),
            None
        );
    }

    // ── Brew's Save ──

    #[test]
    fn save_updates_or_adds_the_row_never_the_star_and_says_if_it_changed() {
        let nz = g("grinder:nz", "Niche Zero");
        let ek = g("grinder:ek", "EK43");
        let bag = bag_with(vec![row("grinder:nz", "14", true)]);
        let same = save_bag_grind(&bag, Some(&nz), "14", NOW);
        assert_eq!((same.changed, &same.bean), (false, &bag));
        let edited = save_bag_grind(&bag, Some(&nz), "15", NOW);
        assert!(edited.changed);
        assert_eq!(
            edited.bean.grinds.as_deref().unwrap(),
            [BagGrind {
                grinder_name: "Niche Zero".into(),
                ..row("grinder:nz", "15", true)
            }]
        );
        assert_eq!(edited.bean.updated_at, NOW);
        let added = save_bag_grind(&bag, Some(&ek), "9", NOW).bean;
        let rows = added.grinds.unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(
            (rows[1].grinder_name.as_str(), rows[1].starred),
            ("EK43", false)
        );
        assert!(rows[0].starred, "the star stays");
        // A blank grind writes nothing; an unmigrated bag starts its list.
        assert!(!save_bag_grind(&bag, Some(&nz), "  ", NOW).changed);
        let fresh = Bean::new("bean:2".into(), "x".into(), 1);
        assert_eq!(
            save_bag_grind(&fresh, None, "4", NOW).bean.grinds.unwrap(),
            [row(NO_GRINDER_ID, "4", false)].map(|r| BagGrind {
                grinder_name: String::new(),
                ..r
            })
        );
    }

    #[test]
    fn rows_for_unknown_or_archived_grinders_are_kept() {
        let bag = bag_with(vec![
            row("grinder:gone", "7", true),
            row("grinder:nz", "14", false),
        ]);
        let out = save_bag_grind(&bag, Some(&g("grinder:nz", "Niche Zero")), "15", NOW).bean;
        assert_eq!(out.grinds.unwrap()[0], row("grinder:gone", "7", true));
    }

    #[test]
    fn empty_rows_are_refused_by_the_editor() {
        let bag = bag_with(vec![
            row("grinder:a", " ", false),
            row("grinder:b", "2", false),
        ]);
        assert_eq!(bag_grinds_to_fix(&bag), ["grinder:a"]);
        assert_eq!(
            bag_grinds_to_fix_json(&serde_json::to_string(&bag).unwrap()).unwrap(),
            r#"["grinder:a"]"#
        );
    }

    // ── The bag editor's Save (row by row) ──

    fn edit(base: Vec<BagGrind>, edited: Vec<BagGrind>, current: Vec<BagGrind>) -> Vec<BagGrind> {
        let b = bag_with(base);
        let e = bag_with(edited);
        let c = bag_with(current);
        crate::library_edit::merge_bean_edit(&b, &e, &c, NOW)
            .grinds
            .unwrap()
    }

    #[test]
    fn the_editor_applies_edits_adds_removals_and_the_star_at_once() {
        let base = vec![
            row("grinder:a", "1", true),
            row("grinder:b", "2", false),
            row("grinder:c", "3", false),
        ];
        let edited = vec![
            row("grinder:a", "1", false),
            row("grinder:b", "2.5", true),
            row("grinder:d", "4", false),
        ];
        assert_eq!(
            edit(base.clone(), edited, base),
            [
                row("grinder:a", "1", false),
                row("grinder:b", "2.5", true),
                row("grinder:d", "4", false)
            ]
        );
    }

    #[test]
    fn an_auto_save_during_the_edit_survives() {
        let base = vec![row("grinder:a", "1", false)];
        let edited = vec![row("grinder:a", "1.5", false)];
        // Meanwhile a shot auto-saved a new grinder's row.
        let current = vec![row("grinder:a", "1", false), row("grinder:b", "9", false)];
        assert_eq!(
            edit(base, edited, current),
            [row("grinder:a", "1.5", false), row("grinder:b", "9", false)]
        );
        // Untouched rows keep the value saved meanwhile.
        let base = vec![row("grinder:a", "1", false), row("grinder:b", "2", false)];
        let edited = vec![row("grinder:a", "1", false)];
        let current = vec![row("grinder:a", "1.2", false), row("grinder:b", "2", false)];
        assert_eq!(
            edit(base, edited, current),
            [row("grinder:a", "1.2", false)]
        );
    }

    #[test]
    fn removing_the_starred_row_removes_the_star_and_an_untouched_star_stays() {
        let base = vec![row("grinder:a", "1", true), row("grinder:b", "2", false)];
        let edited = vec![row("grinder:b", "2", false)];
        assert_eq!(
            edit(base.clone(), edited, base.clone()),
            [row("grinder:b", "2", false)]
        );
        let edited = vec![row("grinder:a", "1", true), row("grinder:b", "3", false)];
        assert_eq!(
            edit(base.clone(), edited, base),
            [row("grinder:a", "1", true), row("grinder:b", "3", false)]
        );
    }

    #[test]
    fn an_editor_that_didnt_touch_the_grinds_keeps_current() {
        let base = bag_with(vec![row("grinder:a", "1", false)]);
        let mut edited = base.clone();
        edited.name = "Renamed".into();
        let current = bag_with(vec![
            row("grinder:a", "1", false),
            row("grinder:b", "9", false),
        ]);
        let out = crate::library_edit::merge_bean_edit(&base, &edited, &current, NOW);
        assert_eq!(out.grinds, current.grinds);
        assert_eq!(out.name, "Renamed");
        // And an unmigrated current gets the editor's rows.
        let mut unmigrated = current.clone();
        unmigrated.grinds = None;
        let mut edited = base.clone();
        edited.grinds = Some(vec![row("grinder:a", "2", false)]);
        let out = crate::library_edit::merge_bean_edit(&base, &edited, &unmigrated, NOW);
        assert_eq!(out.grinds.unwrap(), [row("grinder:a", "2", false)]);
    }

    // ── Selecting a bag (F6) ──

    fn select(
        bag: Bean,
        profile: Option<&str>,
        grinder: Option<&str>,
        grind: Option<&str>,
        grinders: Vec<Grinder>,
    ) -> BagSelection {
        resolve_bag_selection(&BagSelectInput {
            bag: Some(bag),
            active_profile_id: profile.map(str::to_owned),
            active_grinder_id: grinder.map(str::to_owned),
            grind: grind.map(str::to_owned),
            grinders,
            ..BagSelectInput::default()
        })
    }

    fn set(v: Option<&str>) -> Option<SetTo> {
        Some(SetTo {
            value: v.map(str::to_owned),
        })
    }

    #[test]
    fn selecting_a_starred_bag_switches_profile_grinder_and_grind_with_one_undo() {
        let mut bag = bag_with(vec![
            row("grinder:nz", "14", true),
            row("grinder:ek", "9", false),
        ]);
        bag.linked_profile_id = Some("p:bloom".into());
        let out = select(
            bag,
            Some("p:default"),
            Some("grinder:ek"),
            Some("9"),
            vec![g("grinder:nz", "Niche Zero"), g("grinder:ek", "EK43")],
        );
        assert!(out.changed);
        assert_eq!(
            out.change,
            SelectionChange {
                profile_id: set(Some("p:bloom")),
                grinder_id: set(Some("grinder:nz")),
                grind: set(Some("14"))
            }
        );
        assert_eq!(
            out.undo,
            SelectionChange {
                profile_id: set(Some("p:default")),
                grinder_id: set(Some("grinder:ek")),
                grind: set(Some("9"))
            }
        );
        assert_eq!(out.grinder_name.as_deref(), Some("Niche Zero"));
    }

    #[test]
    fn nothing_besides_the_bag_changing_is_no_message() {
        let mut bag = bag_with(vec![row("grinder:nz", "14", true)]);
        bag.linked_profile_id = Some("p:bloom".into());
        let out = select(
            bag,
            Some("p:bloom"),
            Some("grinder:nz"),
            Some("14"),
            vec![g("grinder:nz", "Niche Zero")],
        );
        assert_eq!(out, BagSelection::default());
    }

    #[test]
    fn a_removed_starred_grinder_leaves_the_active_one_and_picks_its_grind() {
        let nz = archive_grinder(&g("grinder:nz", "Niche Zero"), None, 2).grinder;
        let bag = bag_with(vec![
            row("grinder:nz", "14", true),
            row("grinder:ek", "9", false),
        ]);
        let out = select(
            bag,
            None,
            Some("grinder:ek"),
            Some("4.2"),
            vec![nz, g("grinder:ek", "EK43")],
        );
        assert_eq!(
            out.change,
            SelectionChange {
                grind: set(Some("9")),
                ..SelectionChange::default()
            }
        );
        assert_eq!(out.undo.grind, set(Some("4.2")));
    }

    #[test]
    fn no_star_keeps_the_grinder_and_an_unknown_linked_profile_isnt_loaded() {
        let mut bag = bag_with(vec![row("grinder:ek", "9", false)]);
        bag.linked_profile_id = Some("p:gone".into());
        let out = resolve_bag_selection(&BagSelectInput {
            bag: Some(bag),
            linked_profile_missing: true,
            active_grinder_id: None,
            grind: None,
            grinders: vec![g("grinder:ek", "EK43")],
            ..BagSelectInput::default()
        });
        // No grinder active, nothing saved for "no grinder": nothing changes.
        assert!(!out.changed);
    }

    #[test]
    fn selecting_from_no_grinder_to_the_starred_one_undoes_back_to_none() {
        let bag = bag_with(vec![row("grinder:nz", "14", true)]);
        let out = select(bag, None, None, None, vec![g("grinder:nz", "Niche Zero")]);
        assert_eq!(
            out.undo,
            SelectionChange {
                grinder_id: set(None),
                grind: set(None),
                ..SelectionChange::default()
            }
        );
        let json: BagSelection = serde_json::from_str(
            &resolve_bag_selection_json(&serde_json::json!({ "bag": bag_with(vec![row("grinder:nz", "14", true)]), "grinders": [g("grinder:nz", "Niche Zero")] }).to_string()).unwrap(),
        )
        .unwrap();
        assert_eq!(json, out);
    }

    fn starred_pick() -> BagSelection {
        let mut bag = bag_with(vec![row("grinder:nz", "14", true)]);
        bag.linked_profile_id = Some("p:bloom".into());
        select(
            bag,
            Some("p:default"),
            Some("grinder:ek"),
            Some("9"),
            vec![g("grinder:nz", "Niche Zero"), g("grinder:ek", "EK43")],
        )
    }

    fn state(profile: &str, grinder: &str, grind: Option<&str>) -> SelectionState {
        SelectionState {
            profile_id: Some(profile.into()),
            grinder_id: Some(grinder.into()),
            grind: grind.map(str::to_owned),
            shot_in_progress: false,
        }
    }

    #[test]
    fn undo_with_nothing_changed_since_reverts_every_part() {
        let sel = starred_pick();
        let undo = bag_selection_undo(&sel, &state("p:bloom", "grinder:nz", Some("14.0")));
        assert_eq!(undo, sel.undo);
    }

    #[test]
    fn undo_keeps_a_dial_change_made_since() {
        // REVIEW-CORE divergence 5: select bag X (Niche Zero at 14), dial 15,
        // Undo. The 15 stays; the grinder and profile go back.
        let sel = starred_pick();
        let undo = bag_selection_undo(&sel, &state("p:bloom", "grinder:nz", Some("15")));
        assert_eq!(
            undo,
            SelectionChange {
                profile_id: set(Some("p:default")),
                grinder_id: set(Some("grinder:ek")),
                grind: None,
            }
        );
    }

    #[test]
    fn undo_keeps_a_profile_picked_since() {
        // FINAL S9 repro: the pick loads "Blooming", the user picks X by hand,
        // then taps Undo: X stays.
        let sel = starred_pick();
        let undo = bag_selection_undo(&sel, &state("p:x", "grinder:nz", Some("14")));
        assert_eq!(undo.profile_id, None);
        assert_eq!(undo.grinder_id, set(Some("grinder:ek")));
        assert_eq!(undo.grind, set(Some("9")));
    }

    #[test]
    fn undo_keeps_a_grinder_picked_since() {
        let sel = starred_pick();
        let undo = bag_selection_undo(&sel, &state("p:bloom", "grinder:df", Some("14")));
        assert_eq!(undo.grinder_id, None);
        assert_eq!(undo.profile_id, set(Some("p:default")));
    }

    #[test]
    fn undo_leaves_the_profile_while_a_shot_is_in_progress() {
        let sel = starred_pick();
        let mut now = state("p:bloom", "grinder:nz", Some("14"));
        now.shot_in_progress = true;
        let undo = bag_selection_undo(&sel, &now);
        assert_eq!(undo.profile_id, None);
        assert_eq!(undo.grinder_id, set(Some("grinder:ek")));
    }

    #[test]
    fn undo_matches_a_cleared_grind_only_against_a_cleared_one() {
        let sel = BagSelection {
            change: SelectionChange {
                grind: set(None),
                ..SelectionChange::default()
            },
            undo: SelectionChange {
                grind: set(Some("9")),
                ..SelectionChange::default()
            },
            changed: true,
            ..BagSelection::default()
        };
        let blank = SelectionState {
            grind: Some("  ".into()),
            ..SelectionState::default()
        };
        assert_eq!(bag_selection_undo(&sel, &blank).grind, set(Some("9")));
        let dialed = SelectionState {
            grind: Some("10".into()),
            ..SelectionState::default()
        };
        assert_eq!(bag_selection_undo(&sel, &dialed).grind, None);
        // Nothing to undo: nothing comes back.
        assert!(bag_selection_undo(&BagSelection::default(), &blank).is_empty());
    }

    #[test]
    fn undo_json_round_trips() {
        let sel = starred_pick();
        let now = state("p:bloom", "grinder:nz", Some("15"));
        let out: SelectionChange = serde_json::from_str(
            &bag_selection_undo_json(
                &serde_json::json!({ "selection": sel, "now": now }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(out, bag_selection_undo(&sel, &now));
        assert!(bag_selection_undo_json("{}").is_err());
    }

    // ── The words a pick shows (REVIEW-FINAL-2 SF4) ──

    #[test]
    fn the_message_names_the_profile_the_grinder_and_the_grind() {
        let mut bag = bag_with(vec![row("grinder:nz", "14", true)]);
        bag.name = "Ethiopia Guji".into();
        bag.linked_profile_id = Some("p:bloom".into());
        let input = BagSelectInput {
            bag: Some(bag),
            active_profile_id: Some("p:default".into()),
            linked_profile_name: Some("Blooming".into()),
            active_grinder_id: Some("grinder:ek".into()),
            grind: Some("9".into()),
            grinders: vec![g("grinder:nz", "Niche Zero"), g("grinder:ek", "EK43")],
            ..BagSelectInput::default()
        };
        let out = resolve_bag_selection(&input);
        assert_eq!(
            out.message.as_deref(),
            Some("Ethiopia Guji: Blooming profile, Niche Zero at 14")
        );
        assert!(out.notices.is_empty());
        // A grind-only change names the grinder it is on (web's wording).
        let mut grind_only = input.clone();
        grind_only.bag.as_mut().unwrap().linked_profile_id = None;
        grind_only.active_grinder_id = Some("grinder:nz".into());
        assert_eq!(
            resolve_bag_selection(&grind_only).message.as_deref(),
            Some("Ethiopia Guji: Niche Zero at 14")
        );
        // No message without a change.
        let mut same = grind_only.clone();
        same.grind = Some("14.0".into());
        let out = resolve_bag_selection(&same);
        assert!(!out.changed && out.message.is_none());
    }

    #[test]
    fn a_grind_with_no_grinder_reads_grind_and_follows_the_scale() {
        let mut bag = bag_with(vec![row("", "14.50", false)]);
        bag.name = "Kenya".into();
        let out = resolve_bag_selection(&BagSelectInput {
            bag: Some(bag),
            grind: Some("9".into()),
            ..BagSelectInput::default()
        });
        let text = out.message.expect("a grind change");
        assert!(text.starts_with("Kenya: grind "), "{text}");
        assert_eq!(
            text,
            format!(
                "Kenya: grind {}",
                crate::format_grind("14.50", &crate::grinder::GrindScale::default())
            )
        );
    }

    #[test]
    fn mid_shot_the_linked_profile_is_held_back_with_a_notice() {
        let mut bag = bag_with(vec![row("grinder:nz", "14", true)]);
        bag.name = "Kenya".into();
        bag.linked_profile_id = Some("p:bloom".into());
        let input = BagSelectInput {
            bag: Some(bag),
            active_profile_id: Some("p:default".into()),
            linked_profile_name: Some("Blooming".into()),
            shot_in_progress: true,
            grinders: vec![g("grinder:nz", "Niche Zero")],
            ..BagSelectInput::default()
        };
        let out = resolve_bag_selection(&input);
        assert_eq!(out.change.profile_id, None, "no upload mid-shot");
        assert_eq!(out.undo.profile_id, None);
        assert_eq!(
            out.change.grinder_id,
            set(Some("grinder:nz")),
            "the rest still changes"
        );
        assert_eq!(out.message.as_deref(), Some("Kenya: Niche Zero at 14"));
        assert_eq!(
            out.notices,
            [SelectionNotice {
                kind: SelectionNoticeKind::ProfileNotLoaded,
                text: "Shot in progress \u{2014} \u{201c}Blooming\u{201d} not loaded".into(),
            }]
        );
        // The linked profile already active: nothing held back, no notice.
        let mut loaded = input.clone();
        loaded.active_profile_id = Some("p:bloom".into());
        assert!(resolve_bag_selection(&loaded).notices.is_empty());
        // No name known: "the linked profile".
        let mut nameless = input;
        nameless.linked_profile_name = None;
        assert_eq!(
            resolve_bag_selection(&nameless).notices[0].text,
            "Shot in progress \u{2014} the linked profile not loaded"
        );
    }

    #[test]
    fn a_missing_linked_profile_is_a_notice_even_with_no_change() {
        let mut bag = bag_with(Vec::new());
        bag.name = "Kenya".into();
        bag.linked_profile_id = Some("p:gone".into());
        let input = BagSelectInput {
            bag: Some(bag.clone()),
            linked_profile_missing: true,
            ..BagSelectInput::default()
        };
        let out = resolve_bag_selection(&input);
        assert!(!out.changed && out.message.is_none());
        assert_eq!(
            out.notices,
            [SelectionNotice {
                kind: SelectionNoticeKind::LinkedProfileMissing,
                text: "Kenya\u{2019}s linked profile no longer exists".into(),
            }]
        );
        // A re-pick says nothing at all.
        let again = BagSelectInput {
            active_bean_id: Some(bag.id.clone()),
            ..input
        };
        assert_eq!(resolve_bag_selection(&again), BagSelection::default());
    }

    #[test]
    fn a_relayed_pick_travels_back_whole_for_the_remotes_undo() {
        // F6: the hub answers a remote's pick with the selection; the remote
        // shows its words and works out its Undo against the hub's state.
        let sel = starred_pick();
        let wire = serde_json::to_string(&sel).unwrap();
        let back: BagSelection = serde_json::from_str(&wire).unwrap();
        assert_eq!(back, sel);
        assert!(back.message.is_some());
        let hub_now = state("p:bloom", "grinder:nz", Some("14"));
        assert_eq!(bag_selection_undo(&back, &hub_now), sel.undo);
        // An older reply (no message or notices) still reads.
        let old: BagSelection =
            serde_json::from_str(r#"{"change":{},"undo":{},"changed":false,"grinderName":null}"#)
                .unwrap();
        assert!(old.message.is_none() && old.notices.is_empty());
    }

    #[test]
    fn re_picking_the_selected_bag_changes_nothing() {
        // FINAL N7: Brew holds an unsaved 15 on the selected bag; picking the
        // same bag again keeps it, with no message.
        let bag = bag_with(vec![row("grinder:nz", "14", true)]);
        let input = BagSelectInput {
            bag: Some(bag.clone()),
            active_grinder_id: Some("grinder:nz".into()),
            grind: Some("15".into()),
            grinders: vec![g("grinder:nz", "Niche Zero")],
            active_bean_id: Some(bag.id.clone()),
            ..BagSelectInput::default()
        };
        assert_eq!(resolve_bag_selection(&input), BagSelection::default());
        // From another bag, the same pick resets the dial to the saved 14.
        let other = BagSelectInput {
            active_bean_id: Some("bean:other".into()),
            ..input
        };
        assert_eq!(resolve_bag_selection(&other).change.grind, set(Some("14")));
    }

    // ── Adding a grinder from the bag editor (FINAL S7) ──

    fn pick(
        stored: &[Grinder],
        staged: &[Grinder],
        kind: Option<GrinderMatchKind>,
        id: &str,
        name: &str,
    ) -> Option<StagedGrinderPick> {
        stage_grinder_pick(
            &GrinderPickInput {
                grinders: stored.to_vec(),
                staged: staged.to_vec(),
                kind,
                id: id.into(),
                name: name.into(),
            },
            NOW,
        )
    }

    fn save_staged(
        stored: &[Grinder],
        staged: &[Grinder],
        rows: Vec<BagGrind>,
        active: Option<&str>,
    ) -> StagedGrindersSave {
        staged_grinders_on_save(
            &StagedGrindersInput {
                grinders: stored.to_vec(),
                staged: staged.to_vec(),
                bag: Some(bag_with(rows)),
                active_grinder_id: active.map(str::to_owned),
            },
            NOW,
        )
    }

    #[test]
    fn a_typed_grinder_is_staged_not_stored_and_cancel_leaves_nothing() {
        // FINAL S7 repro: Enter on a typo ("Nich") used to create the grinder
        // at once, and Cancel kept it. Now the pick only stages it.
        let stored = vec![g("grinder:ek", "EK43")];
        let out = pick(&stored, &[], None, "", "Nich").unwrap();
        assert_eq!(out.kind, GrinderAddKind::Created);
        assert_eq!(out.grinder.name, "Nich");
        assert_eq!(out.staged, std::slice::from_ref(&out.grinder));
        // Cancel: the draft (and its staged list) is dropped; `stored` was
        // never written. A Save without its row writes nothing either.
        let none = save_staged(
            &stored,
            &out.staged,
            vec![row("grinder:ek", "9", false)],
            None,
        );
        assert_eq!(none, StagedGrindersSave::default());
    }

    #[test]
    fn a_staged_grinder_reads_as_owned_and_isnt_staged_twice() {
        let first = pick(
            &[],
            &[],
            Some(GrinderMatchKind::Catalogue),
            "niche-zero",
            "",
        )
        .unwrap();
        assert_eq!(first.kind, GrinderAddKind::Created);
        let view = bag_editor_grinders(&[], &first.staged);
        assert_eq!(view, first.staged);
        // The same entry again, or the staged row picked as owned: Existing.
        let again = pick(
            &[],
            &first.staged,
            Some(GrinderMatchKind::Catalogue),
            "niche-zero",
            "",
        )
        .unwrap();
        assert_eq!(again.kind, GrinderAddKind::Existing);
        assert_eq!(again.staged, first.staged);
        let owned = pick(
            &[],
            &first.staged,
            Some(GrinderMatchKind::Owned),
            &first.grinder.id,
            "",
        )
        .unwrap();
        assert_eq!(owned.kind, GrinderAddKind::Existing);
        // A second new grinder gets its own id.
        let second = pick(&[], &first.staged, None, "", "My Hand Grinder").unwrap();
        assert_ne!(second.grinder.id, first.grinder.id);
        assert_eq!(second.staged.len(), 2);
        assert!(pick(&[], &[], Some(GrinderMatchKind::Owned), "grinder:nope", "").is_none());
        assert!(pick(&[], &[], None, "", "  ").is_none());
    }

    #[test]
    fn an_archived_pick_is_revived_only_on_save_from_the_row_as_stored() {
        let archived = archive_grinder(&g("grinder:nz", "Niche Zero"), None, 2).grinder;
        let out = pick(
            std::slice::from_ref(&archived),
            &[],
            Some(GrinderMatchKind::Archived),
            "grinder:nz",
            "",
        )
        .unwrap();
        assert_eq!(out.kind, GrinderAddKind::Revived);
        assert!(out.grinder.is_live());
        // The stored row was renamed on Devices meanwhile: Save restores that.
        let mut renamed = archived.clone();
        renamed.name = "Niche (old)".into();
        let saved = save_staged(
            std::slice::from_ref(&renamed),
            &out.staged,
            vec![row("grinder:nz", "14", false)],
            Some("grinder:ek"),
        );
        assert_eq!(saved.grinders.len(), 1);
        assert_eq!(saved.grinders[0].name, "Niche (old)");
        assert!(saved.grinders[0].is_live());
        // Restored meanwhile: nothing to write.
        let live = restore_grinder(&renamed, 3);
        let saved = save_staged(
            &[live],
            &out.staged,
            vec![row("grinder:nz", "14", false)],
            None,
        );
        assert!(saved.grinders.is_empty());
    }

    #[test]
    fn save_makes_a_written_grinder_active_only_when_none_is() {
        let a = pick(&[], &[], None, "", "Alpha").unwrap();
        let b = pick(&[], &a.staged, None, "", "Beta").unwrap();
        let rows = vec![
            row(&a.grinder.id, "1", false),
            row(&b.grinder.id, "2", true),
        ];
        let saved = save_staged(&[], &b.staged, rows.clone(), None);
        assert_eq!(saved.grinders, b.staged);
        // The starred one first.
        assert_eq!(saved.active_grinder_id, set(Some(b.grinder.id.as_str())));
        // A live grinder already active: it stays.
        let ek = g("grinder:ek", "EK43");
        let saved = save_staged(
            std::slice::from_ref(&ek),
            &b.staged,
            rows,
            Some("grinder:ek"),
        );
        assert_eq!(saved.active_grinder_id, None);
        // The pick itself never touched the pointer (there is none to touch).
        let json: StagedGrindersSave = serde_json::from_str(
            &staged_grinders_on_save_json(
                &serde_json::json!({ "staged": b.staged, "bag": bag_with(vec![row(&a.grinder.id, "1", false)]) })
                    .to_string(),
                NOW,
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(json.grinders, std::slice::from_ref(&a.grinder));
        assert_eq!(json.active_grinder_id, set(Some(a.grinder.id.as_str())));
        let staged_json = stage_grinder_pick_json(r#"{"name":"Gamma"}"#, NOW).unwrap();
        let staged: StagedGrinderPick = serde_json::from_str(&staged_json).unwrap();
        assert_eq!(staged.grinder.name, "Gamma");
        assert_eq!(
            bag_editor_grinders_json("[]", &serde_json::to_string(&staged.staged).unwrap())
                .unwrap(),
            serde_json::to_string(&staged.staged).unwrap()
        );
        assert!(stage_grinder_pick_json("nope", NOW).is_err());
    }

    #[test]
    fn a_grinder_switch_uses_the_pick_order_only() {
        let bag = bag_with(vec![row("grinder:nz", "14", true)]);
        let grinders = vec![g("grinder:nz", "Niche Zero"), g("grinder:ek", "EK43")];
        let switch = |id: &str, lasts: Vec<LastGrind>| {
            resolve_grinder_switch(&GrinderSwitchInput {
                bag: Some(bag.clone()),
                grinder_id: Some(id.into()),
                grinders: grinders.clone(),
                last_grinds: lasts,
            })
        };
        assert_eq!(switch("grinder:nz", Vec::new()).as_deref(), Some("14"));
        assert_eq!(
            switch("grinder:ek", vec![last(Some("grinder:ek"), "EK43", "8", 1)]).as_deref(),
            Some("8")
        );
        assert_eq!(switch("grinder:ek", Vec::new()), None);
        assert_eq!(resolve_grinder_switch(&GrinderSwitchInput::default()), None);
        assert_eq!(resolve_grinder_switch_json("{}").unwrap(), "null");
    }

    // ── Auto-save (F8) ──

    fn kept_shot(bean_id: &str, grinder: Option<(&str, &str)>, grind: &str) -> AutoSaveInput {
        AutoSaveInput {
            shot_bean_id: Some(bean_id.into()),
            shot_grinder_id: grinder.map(|g| g.0.to_owned()),
            shot_grinder_name: grinder.map(|g| g.1.to_owned()),
            shot_grind: Some(grind.into()),
            ..AutoSaveInput::default()
        }
    }

    fn auto(enabled: bool, here: bool, shot: AutoSaveInput) -> Option<GrindSave> {
        auto_save_shot_grind(
            &AutoSaveInput {
                enabled,
                recorded_here: here,
                bag: Some(bag_with(vec![row("grinder:nz", "14", true)])),
                ..shot
            },
            NOW,
        )
    }

    #[test]
    fn auto_save_saves_a_kept_shots_grind_on_the_recording_device_only() {
        let shot = kept_shot("bean:1", Some(("grinder:nz", "Niche Zero")), "15");
        let out = auto(true, true, shot.clone()).unwrap();
        assert!(out.changed);
        assert_eq!(out.bean.grinds.as_deref().unwrap()[0].grind, "15");
        assert!(
            out.bean.grinds.as_deref().unwrap()[0].starred,
            "never changes the star"
        );
        // Off by default, and a remote's mirror copy never saves.
        assert_eq!(auto(false, true, shot.clone()), None);
        assert_eq!(auto(true, false, shot), None);
        // Another bag's shot, or no grind: nothing.
        assert_eq!(auto(true, true, kept_shot("bean:2", None, "15")), None);
        assert_eq!(auto(true, true, kept_shot("bean:1", None, " ")), None);
        // The same value: saved, not changed (no message).
        assert!(
            !auto(
                true,
                true,
                kept_shot("bean:1", Some(("grinder:nz", "Niche Zero")), "14")
            )
            .unwrap()
            .changed
        );
        // A shot with no grinder saves to the "no grinder" slot.
        let none = auto(true, true, kept_shot("bean:1", None, "3"))
            .unwrap()
            .bean;
        assert_eq!(none.grinds.unwrap()[1].grinder_id, NO_GRINDER_ID);
        assert!(auto_save_shot_grind_json("{}", NOW).unwrap() == "null");
    }

    // ── History lookups (REVIEW-CORE M1) ──

    fn niche_zero() -> Grinder {
        add_grinder(&[], "", Some("niche-zero"), NOW)
            .unwrap()
            .grinder
    }

    fn shot_row(
        at: i64,
        id: &str,
        gid: Option<&str>,
        model: Option<&str>,
        grind: Option<&str>,
    ) -> LastGrindRow {
        LastGrindRow {
            completed_at: at,
            id: id.into(),
            grinder_id: gid.map(str::to_owned),
            grinder_model: model.map(str::to_owned),
            grind: grind.map(str::to_owned),
        }
    }

    #[test]
    fn an_alias_named_old_shot_reaches_the_picker_row_and_the_editor_seed() {
        // REVIEW-CORE M1 repro: a pre-#488 shot stamped "Niche", no id; the
        // grinder record is the preset Niche Zero.
        let nz = niche_zero();
        let bag = bag_with(Vec::new());
        let lasts = vec![last(None, "Niche", "14", 10)];
        assert_eq!(pick_grind(&bag, Some(&nz), &lasts).as_deref(), Some("14"));
        let rows = grinder_pick_rows(std::slice::from_ref(&nz), Some(&bag), &lasts);
        assert_eq!(
            rows,
            vec![GrinderPickRow {
                grinder_id: nz.id.clone(),
                starred: false,
                saved: None,
                last_shot: Some("14".into()),
            }],
            "not \"Nothing saved\""
        );
        assert_eq!(new_bag_grind_row(&nz, &lasts).grind, "14");
        // A typo isn't an alias.
        let typo = vec![last(None, "Nich", "14", 10)];
        assert_eq!(new_bag_grind_row(&nz, &typo).grind, "");
    }

    /// #488 follow-up 43 (REVIEW-514 2, REVIEW-515 2): with two Niche Zeros,
    /// an old id-less "Niche Zero" shot is the first unit's only.
    #[test]
    fn an_old_shot_is_never_a_numbered_second_units() {
        let first = niche_zero();
        let second = crate::grinder::add_another_grinder(
            std::slice::from_ref(&first),
            "",
            Some("niche-zero"),
            NOW,
        )
        .unwrap()
        .grinder;
        assert_eq!(second.name, "Niche Zero 2");
        assert_eq!(second.preset_id.as_deref(), Some("niche-zero"));
        let bag = bag_with(Vec::new());
        for name in ["Niche Zero", "niche"] {
            let lasts = vec![last(None, name, "14", 10)];
            assert_eq!(newest_shot_grind(&lasts, &first).unwrap().grind, "14");
            assert_eq!(newest_shot_grind(&lasts, &second), None, "{name}");
            let rows = grinder_pick_rows(&[first.clone(), second.clone()], Some(&bag), &lasts);
            assert_eq!(rows[0].last_shot.as_deref(), Some("14"));
            assert_eq!(rows[1].last_shot, None, "{name}: not on the second unit");
            assert_eq!(new_bag_grind_row(&second, &lasts).grind, "");
            assert_eq!(pick_grind(&bag, Some(&second), &lasts), None);
        }
        // Its own old rows, by its exact name, and every row with its id.
        let lasts = vec![
            last(None, "niche zero 2", "16", 10),
            last(Some(&first.id), "Niche Zero", "12", 20),
        ];
        assert_eq!(newest_shot_grind(&lasts, &second).unwrap().grind, "16");
        assert_eq!(newest_shot_grind(&lasts, &first).unwrap().grind, "12");
        // The catalogue link alone no longer counts: a renamed unit keeps
        // only the rows stamped with its id or its new name.
        let mut renamed = first.clone();
        renamed.name = "Office grinder".into();
        let lasts = vec![last(None, "Niche Zero", "14", 10)];
        assert_eq!(newest_shot_grind(&lasts, &renamed), None);
        assert!(
            crate::grinder::names_grinder(&renamed, "Niche Zero"),
            "imports still match"
        );
    }

    #[test]
    fn picker_rows_show_the_saved_grind_and_the_star_else_the_last_shot() {
        let ek = g("grinder:ek", "EK43");
        let c40 = g("grinder:c40", "Comandante C40");
        let bag = bag_with(vec![
            row("grinder:ek", " 8 ", true),
            row("grinder:c40", "", false),
        ]);
        let lasts = vec![
            last(Some("grinder:ek"), "EK43", "9", 20),
            last(Some("grinder:c40"), "C40", "22", 10),
        ];
        let rows = grinder_pick_rows(&[ek.clone(), c40.clone()], Some(&bag), &lasts);
        assert_eq!(
            (
                rows[0].starred,
                rows[0].saved.as_deref(),
                rows[0].last_shot.as_deref()
            ),
            (true, Some("8"), None)
        );
        assert_eq!(
            (rows[1].saved.as_deref(), rows[1].last_shot.as_deref()),
            (None, Some("22")),
            "a blank row is nothing saved"
        );
        // The newest of id and name matches, not the first by list order.
        let lasts = vec![
            last(Some("grinder:c40"), "C40", "22", 10),
            last(None, "comandante c40", "24", 30),
        ];
        assert_eq!(
            grinder_pick_rows(
                std::slice::from_ref(&c40),
                Some(&bag_with(Vec::new())),
                &lasts
            )[0]
            .last_shot
            .as_deref(),
            Some("24")
        );
        // No bag: nothing.
        let rows = grinder_pick_rows(&[ek], None, &lasts);
        assert_eq!(
            (
                rows[0].starred,
                rows[0].saved.as_deref(),
                rows[0].last_shot.as_deref()
            ),
            (false, None, None)
        );
    }

    #[test]
    fn rows_group_into_the_newest_grind_per_grinder_as_the_history_does() {
        let rows = vec![
            shot_row(10, "shot:a", Some("grinder:ek"), Some("EK43"), Some("8")),
            shot_row(30, "shot:b", Some("grinder:ek"), Some("EK43"), Some(" 9 ")),
            shot_row(20, "shot:c", None, Some("Niche"), Some("14")),
            shot_row(25, "shot:d", None, Some(" niche "), Some("15")),
            shot_row(40, "shot:e", None, Some("  "), Some("1")),
            shot_row(50, "shot:f", Some("grinder:c40"), None, Some("  ")),
            shot_row(60, "shot:g", None, None, Some("3")),
            shot_row(30, "shot:h", Some("grinder:x"), Some("X"), Some("5")),
        ];
        let out = last_grinds_from_rows(&rows);
        assert_eq!(
            out,
            vec![
                last(Some("grinder:x"), "X", "5", 30),
                last(Some("grinder:ek"), "EK43", "9", 30),
                last(None, " niche ", "15", 25),
            ],
            "newest first, ties by the higher id; blank grinds and names skipped"
        );
        let json: Vec<LastGrind> = serde_json::from_str(
            &last_grinds_from_rows_json(
                r#"[{"completedAt":1,"id":"s","grinderId":"grinder:ek","grind":14.5}]"#,
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(
            json,
            vec![last(Some("grinder:ek"), "", "14.5", 1)],
            "a number reads as its text"
        );
    }

    #[test]
    fn the_lookup_bridges_round_trip() {
        let nz = niche_zero();
        let lasts = vec![last(None, "Niche", "14", 10)];
        let input = serde_json::json!({ "grinder": nz, "lastGrinds": lasts }).to_string();
        let got: Option<LastGrind> =
            serde_json::from_str(&newest_shot_grind_json(&input).unwrap()).unwrap();
        assert_eq!(got.unwrap().grind, "14");
        let row: BagGrind = serde_json::from_str(&new_bag_grind_row_json(&input).unwrap()).unwrap();
        assert_eq!(
            (row.grinder_id.as_str(), row.grind.as_str()),
            (nz.id.as_str(), "14")
        );
        let rows = serde_json::json!({ "grinders": [nz], "bag": bag_with(Vec::new()), "lastGrinds": lasts }).to_string();
        let rows: Vec<GrinderPickRow> =
            serde_json::from_str(&grinder_pick_rows_json(&rows).unwrap()).unwrap();
        assert_eq!(rows[0].last_shot.as_deref(), Some("14"));
        assert!(newest_shot_grind_json("{}").is_err());
    }
}

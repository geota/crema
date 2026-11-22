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
//! ([`resolve_bag_selection`], review F6).

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bean::Bean;
use crate::grinder::{Grinder, active_grinder, grinder_named, grinders_for_names, names_grinder};
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
/// with its id, or a row with no id whose name names it
/// ([`crate::grinder::names_grinder`]). Blank grinds don't count.
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
                None => names_grinder(grinder, &l.grinder),
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
        Some(row) if row.grind == grind => return unchanged(),
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
    /// id): it isn't loaded.
    pub linked_profile_missing: bool,
    /// The active grinder pointer now.
    pub active_grinder_id: Option<String>,
    /// Brew's grind value now.
    pub grind: Option<String>,
    /// The grinder records.
    pub grinders: Vec<Grinder>,
    /// The bag's newest grind per grinder, from the history.
    pub last_grinds: Vec<LastGrind>,
}

/// One bag selection, resolved (review F6): the change, its inverse for the
/// one Undo, and whether to show the message.
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
}

fn non_blank(s: Option<&str>) -> Option<&str> {
    s.filter(|s| !s.trim().is_empty())
}

/// Resolve selecting a bag on Brew into one change (review F6):
///
/// 1. **Profile:** the bag's linked profile, when set, here, and not active.
/// 2. **Grinder:** the bag's starred grinder, when it's still on Devices
///    (live) and not active. No star, or a starred grinder that was removed:
///    the active grinder stays.
/// 3. **Grind:** [`pick_grind`] with the grinder active after step 2, when it
///    differs from Brew's grind now.
///
/// The inverse restores the previous value of each part that changes.
#[must_use]
pub fn resolve_bag_selection(input: &BagSelectInput) -> BagSelection {
    let Some(bag) = input.bag.as_ref() else {
        return BagSelection::default();
    };
    let mut change = SelectionChange::default();
    let mut undo = SelectionChange::default();
    if !input.linked_profile_missing
        && let Some(linked) = non_blank(bag.linked_profile_id.as_deref())
        && input.active_profile_id.as_deref() != Some(linked)
    {
        change.profile_id = Some(SetTo {
            value: Some(linked.to_owned()),
        });
        undo.profile_id = Some(SetTo {
            value: input.active_profile_id.clone(),
        });
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
        && input.grind.as_deref() != Some(grind.as_str())
    {
        change.grind = Some(SetTo { value: Some(grind) });
        undo.grind = Some(SetTo {
            value: input.grind.clone(),
        });
    }
    BagSelection {
        changed: !change.is_empty(),
        change,
        undo,
        grinder_name,
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::grinder::{
        GrinderLibraryMigration, add_grinder, archive_grinder, migrate_grinder_library,
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
        migrate_grinder_library(&[], bags, model, NOW)
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
}

//! # grinder
//!
//! Owned grinders and their grind scales (#488).
//!
//! The grinder is equipment the user picks on Brew, not a field on the bag.
//! **Owned grinders are library records** ([`Grinder`]), stored next to the
//! roasters with the same rules (save-boundaries review F3, 2026-11-21): an
//! id, `updated_at`, `archived_at`, a `deleted_at` tombstone, merged by id in
//! backups and imports. **Remove = archive** ([`archive_grinder`]): an
//! archived grinder is hidden from the pickers and stays on bag grinds and
//! shots; adding the same catalogue grinder or a matching name brings the
//! record back with the same id ([`add_grinder`]), so its bag grinds come
//! back.
//!
//! **The active grinder is a per-device pointer** the shells keep outside
//! settings and backups (web its own storage key, Android next to
//! `activeBeanId`). [`active_grinder`] resolves it: a pointer at an archived,
//! deleted or unknown grinder is no active grinder. Archiving the active one
//! clears the pointer (review F5).
//!
//! **The scale** with no active grinder is [`GrindScale::default`] (0–20,
//! step 0.1, no unit: the range before #488). The Quick Controls grind range
//! follows the active grinder's ([`crate::bounds::quick_control_bounds_for`]).
//! **Grind values are never clamped or rewritten**: a scale is only the
//! stepper's range.
//!
//! **The migration** ([`migrate_grinder_library`]) runs on every load and
//! import, only once both the library and the settings read
//! ([`MigrationGate`]), and is idempotent: grinder records for the old names
//! (`CommonSettings::grinder_model`, `Bean::grinder`) when there are none,
//! then each bag's old `grinder_setting` as a saved grind
//! ([`crate::bag_grind`]). It reads the old fields and never writes them.

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bean::Bean;
use crate::grinder_catalogue::{
    CatalogueGrinder, GRINDER_CATALOGUE, catalogue_entry, catalogue_entry_for_name, compact_key,
};
use crate::hot_water::StepRange;

/// The scale with no active grinder: the Quick Controls grind range before
/// #488.
pub const DEFAULT_GRIND_MIN: f32 = 0.0;
/// See [`DEFAULT_GRIND_MIN`].
pub const DEFAULT_GRIND_MAX: f32 = 20.0;
/// See [`DEFAULT_GRIND_MIN`].
pub const DEFAULT_GRIND_STEP: f32 = 0.1;

/// A grinder's grind scale: the stepper's range and the unit shown next to a
/// value. `unit` is free text and may be empty ("μm", "clicks", "turns", …).
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct GrindScale {
    /// Lowest setting.
    pub min: f32,
    /// Highest setting.
    pub max: f32,
    /// Step between settings.
    pub step: f32,
    /// The unit label; empty for none.
    pub unit: String,
}

impl Default for GrindScale {
    fn default() -> Self {
        Self {
            min: DEFAULT_GRIND_MIN,
            max: DEFAULT_GRIND_MAX,
            step: DEFAULT_GRIND_STEP,
            unit: String::new(),
        }
    }
}

impl GrindScale {
    /// The stepper range: this scale, repaired so a stepper can use it. A
    /// non-finite bound or step takes the default's, a step that isn't
    /// positive takes the default step, and swapped bounds are put in order.
    /// The stored scale is not changed.
    #[must_use]
    pub fn step_range(&self) -> StepRange {
        let d = Self::default();
        let finite_or = |v: f32, fallback: f32| if v.is_finite() { v } else { fallback };
        let a = finite_or(self.min, d.min);
        let b = finite_or(self.max, d.max);
        let step = if self.step.is_finite() && self.step > 0.0 {
            self.step
        } else {
            d.step
        };
        StepRange {
            min: a.min(b),
            max: a.max(b),
            step,
            chips: Vec::new(),
        }
    }

    /// Whether `value` lies inside the scale: the shells warn on a typed value
    /// outside it, and keep the value.
    #[must_use]
    pub fn contains(&self, value: f32) -> bool {
        self.step_range().contains(value)
    }
}

/// A grinder the user owns: a bean-library record, next to the roasters.
/// Read leniently (`default` on every field), so a stored row with a missing
/// field still loads.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Grinder {
    /// Stable id, `grinder:<slug>` ([`add_grinder`] mints it).
    pub id: String,
    /// The name shots are stamped with: `"Niche Zero"`.
    pub name: String,
    /// Its grind scale; the user may change any of it.
    pub scale: GrindScale,
    /// The catalogue entry it came from ([`crate::grinder_catalogue`]), if
    /// any.
    pub preset_id: Option<String>,
    /// Unix epoch ms of the last change.
    #[typeshare(serialized_as = "I64")]
    pub updated_at: i64,
    /// Unix epoch ms when it was removed from Devices; `None` = owned.
    #[typeshare(serialized_as = "Option<I64>")]
    pub archived_at: Option<i64>,
    /// Unix epoch ms of its tombstone; `None` = not deleted.
    #[typeshare(serialized_as = "Option<I64>")]
    pub deleted_at: Option<i64>,
}

impl Grinder {
    /// Neither archived nor deleted: shown in the pickers, can be active.
    #[must_use]
    pub fn is_live(&self) -> bool {
        self.archived_at.is_none() && self.deleted_at.is_none()
    }

    /// The catalogue entry it came from, if any.
    #[must_use]
    pub fn catalogue_entry(&self) -> Option<&'static CatalogueGrinder> {
        self.preset_id.as_deref().and_then(catalogue_entry)
    }
}

/// What two grinder names are compared by: trimmed and lowercased. Empty for
/// a blank name, which names no grinder.
#[must_use]
pub fn grinder_key(name: &str) -> String {
    name.trim().to_lowercase()
}

/// Whether `a` and `b` name the same grinder (ignoring case and surrounding
/// spaces). A blank name matches nothing.
#[must_use]
pub fn same_grinder(a: &str, b: &str) -> bool {
    let a = grinder_key(a);
    !a.is_empty() && a == grinder_key(b)
}

/// Whether the free-text `name` names `grinder`: the same name
/// ([`same_grinder`]), or both name the same catalogue entry strictly
/// ([`catalogue_entry_for_name`]: "niche" and "Niche Zero"). For imports, the
/// migration and shots without a grinder id.
#[must_use]
pub fn names_grinder(grinder: &Grinder, name: &str) -> bool {
    if same_grinder(&grinder.name, name) {
        return true;
    }
    let Some(entry) = catalogue_entry_for_name(name) else {
        return false;
    };
    grinder.preset_id.as_deref() == Some(entry.id)
        || catalogue_entry_for_name(&grinder.name).is_some_and(|e| e.id == entry.id)
}

/// The grinder `name` names among `grinders` that aren't deleted, if any
/// ([`names_grinder`]): one with exactly that name first (live, then
/// archived), so "Niche Zero 2" is the second Niche Zero, not the first
/// (#488 follow-up 18); then one through the catalogue (live, then archived).
#[must_use]
pub fn grinder_named<'a>(grinders: &'a [Grinder], name: &str) -> Option<&'a Grinder> {
    if grinder_key(name).is_empty() {
        return None;
    }
    let not_deleted = || grinders.iter().filter(|g| g.deleted_at.is_none());
    let find = |matches: &dyn Fn(&Grinder) -> bool| {
        not_deleted()
            .filter(|g| g.is_live())
            .find(|g| matches(g))
            .or_else(|| not_deleted().find(|g| matches(g)))
    };
    find(&|g| same_grinder(&g.name, name)).or_else(|| find(&|g| names_grinder(g, name)))
}

/// The active grinder: the live grinder `active_id` names. `None` when no id
/// is set, or it names an archived, deleted or unknown grinder.
#[must_use]
pub fn active_grinder<'a>(grinders: &'a [Grinder], active_id: Option<&str>) -> Option<&'a Grinder> {
    let id = active_id?;
    grinders.iter().find(|g| g.id == id && g.is_live())
}

/// The active grinder's scale, else [`GrindScale::default`].
#[must_use]
pub fn active_grind_scale(grinders: &[Grinder], active_id: Option<&str>) -> GrindScale {
    active_grinder(grinders, active_id).map_or_else(GrindScale::default, |g| g.scale.clone())
}

/// An id for a new grinder called `name`, unique among `taken` (tombstones
/// included): `grinder:` and the name's lowercase letters and digits joined
/// by `-`, with `-2`, `-3`, … when that is taken. Deterministic, so the
/// migration gives the same ids each time it runs on the same data.
fn mint_id(name: &str, taken: &[Grinder]) -> String {
    let mut slug = String::new();
    for c in crate::bean_search::fold_text(name.trim()).chars() {
        if c.is_alphanumeric() {
            slug.push(c);
        } else if !slug.is_empty() && !slug.ends_with('-') {
            slug.push('-');
        }
    }
    let slug = slug.trim_end_matches('-');
    let base = if slug.is_empty() {
        "grinder:grinder".to_owned()
    } else {
        format!("grinder:{slug}")
    };
    let free = |id: &str| !taken.iter().any(|g| g.id == id);
    if free(&base) {
        return base;
    }
    (2_u32..)
        .map(|n| format!("{base}-{n}"))
        .find(|id| free(id))
        .unwrap_or(base)
}

/// A new grinder called `name` (trimmed), from `entry` when given, else from
/// the entry the name names strictly: that entry's verified scale, else the
/// default.
fn new_grinder(
    name: &str,
    entry: Option<&'static CatalogueGrinder>,
    taken: &[Grinder],
    now_ms: i64,
) -> Grinder {
    let entry = entry.or_else(|| catalogue_entry_for_name(name));
    Grinder {
        id: mint_id(name, taken),
        name: name.trim().to_owned(),
        scale: entry.map_or_else(GrindScale::default, CatalogueGrinder::starting_scale),
        preset_id: entry.map(|e| e.id.to_owned()),
        updated_at: now_ms,
        archived_at: None,
        deleted_at: None,
    }
}

/// How [`add_grinder`] got its grinder.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum GrinderAddKind {
    /// A new record.
    Created,
    /// An archived record brought back, same id: its bag grinds come back.
    Revived,
    /// Already owned: nothing changed.
    Existing,
}

/// What [`add_grinder`] answers: the row to store and how it came about.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrinderAdd {
    /// The grinder row; the shell upserts it (unless `Existing`).
    pub grinder: Grinder,
    /// Created, revived or already owned.
    pub kind: GrinderAddKind,
}

/// Add a grinder: from the catalogue entry `catalogue_id`, or the typed
/// `name`. A record that isn't deleted and is that entry (by
/// [`Grinder::preset_id`]) or that name ([`names_grinder`]) is reused: an
/// archived one is revived with the same id, an owned one comes back as
/// `Existing`. Otherwise a new record: the entry's verified scale (or the
/// entry the name names strictly), else the default; its name is `name`, or
/// the entry's when `name` is blank. `None` when both are blank or the entry
/// is unknown.
#[must_use]
pub fn add_grinder(
    grinders: &[Grinder],
    name: &str,
    catalogue_id: Option<&str>,
    now_ms: i64,
) -> Option<GrinderAdd> {
    let entry = match catalogue_id.filter(|id| !id.trim().is_empty()) {
        Some(id) => Some(catalogue_entry(id)?),
        None => None,
    };
    let name = if grinder_key(name).is_empty() {
        entry?.name()
    } else {
        name.trim().to_owned()
    };
    let not_deleted = || grinders.iter().filter(|g| g.deleted_at.is_none());
    let by_entry = entry.and_then(|e| {
        let of_entry = |g: &&Grinder| g.preset_id.as_deref() == Some(e.id);
        not_deleted()
            .filter(|g| g.is_live())
            .find(of_entry)
            .or_else(|| not_deleted().find(of_entry))
    });
    if let Some(found) = by_entry.or_else(|| grinder_named(grinders, &name)) {
        if found.is_live() {
            return Some(GrinderAdd {
                grinder: found.clone(),
                kind: GrinderAddKind::Existing,
            });
        }
        return Some(GrinderAdd {
            grinder: restore_grinder(found, now_ms),
            kind: GrinderAddKind::Revived,
        });
    }
    Some(GrinderAdd {
        grinder: new_grinder(&name, entry, grinders, now_ms),
        kind: GrinderAddKind::Created,
    })
}

/// Add **another** grinder of a model the user already owns (#488
/// follow-up 18: two Niche Zeros). When no live record is that entry or
/// that name, this is [`add_grinder`]. Otherwise:
///
/// - an archived record of that entry or name comes back (same id, its bag
///   grinds with it), the one whose name matches exactly first;
/// - else a new record named "‹name› 2" (then 3, …: the first number no
///   record that isn't deleted already has, and that doesn't name another
///   catalogue entry), from the same entry (its verified scale and
///   `preset_id`), with its own id ([`mint_id`]: `grinder:niche-zero-2`).
///
/// Matching keeps working: the new name names this record exactly
/// ([`grinder_named`] prefers exact names), and old shot rows stamped with
/// the model name still match through the catalogue. `None` as for
/// [`add_grinder`].
#[must_use]
pub fn add_another_grinder(
    grinders: &[Grinder],
    name: &str,
    catalogue_id: Option<&str>,
    now_ms: i64,
) -> Option<GrinderAdd> {
    let first = add_grinder(grinders, name, catalogue_id, now_ms)?;
    if first.kind != GrinderAddKind::Existing {
        return Some(first);
    }
    let entry = match catalogue_id.filter(|id| !id.trim().is_empty()) {
        Some(id) => catalogue_entry(id),
        None => None,
    };
    let base = if grinder_key(name).is_empty() {
        entry?.name()
    } else {
        name.trim().to_owned()
    };
    let entry = entry.or_else(|| catalogue_entry_for_name(&base));
    let not_deleted = || grinders.iter().filter(|g| g.deleted_at.is_none());
    let is_model = |g: &Grinder| {
        names_grinder(g, &base) || entry.is_some_and(|e| g.preset_id.as_deref() == Some(e.id))
    };
    let archived = || not_deleted().filter(|g| !g.is_live() && is_model(g));
    let revive = archived()
        .find(|g| same_grinder(&g.name, &base))
        .or_else(|| archived().next());
    if let Some(found) = revive {
        return Some(GrinderAdd {
            grinder: restore_grinder(found, now_ms),
            kind: GrinderAddKind::Revived,
        });
    }
    let free = |candidate: &str| {
        !not_deleted().any(|g| same_grinder(&g.name, candidate))
            && catalogue_entry_for_name(candidate)
                .is_none_or(|e| entry.is_some_and(|m| m.id == e.id))
    };
    let numbered = (2_u32..1000)
        .map(|n| format!("{base} {n}"))
        .find(|c| free(c))?;
    Some(GrinderAdd {
        grinder: new_grinder(&numbered, entry, grinders, now_ms),
        kind: GrinderAddKind::Created,
    })
}

/// What [`archive_grinder`] answers.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrinderArchive {
    /// The archived row to store.
    pub grinder: Grinder,
    /// The active-grinder pointer afterwards: cleared when it was this one
    /// (review F5), else as it was.
    pub active_grinder_id: Option<String>,
}

/// Remove a grinder from Devices: archive it (`archived_at` = now). Shots and
/// bag grinds are not touched. When it was the active one, no grinder is
/// active afterwards: picking another would silently change the next shot's
/// grind scale, so the user picks one (review F5). An already archived one
/// keeps its first `archived_at`.
#[must_use]
pub fn archive_grinder(grinder: &Grinder, active_id: Option<&str>, now_ms: i64) -> GrinderArchive {
    let mut g = grinder.clone();
    if g.archived_at.is_none() {
        g.archived_at = Some(now_ms);
        g.updated_at = now_ms.max(g.updated_at);
    }
    GrinderArchive {
        active_grinder_id: active_id.filter(|id| *id != g.id).map(str::to_owned),
        grinder: g,
    }
}

/// An archived grinder brought back (Devices' "Restore", or re-adding it):
/// owned again, same id. A live one comes back unchanged.
#[must_use]
pub fn restore_grinder(grinder: &Grinder, now_ms: i64) -> Grinder {
    let mut g = grinder.clone();
    if g.archived_at.is_some() {
        g.archived_at = None;
        g.updated_at = now_ms.max(g.updated_at);
    }
    g
}

/// The Save of a grinder edit on Devices (rename, scale, preset): the fields
/// the user changed (`edited` against `base`) applied onto `current`, the
/// row as stored now, as [`crate::library_edit::merge_roaster_edit`] does.
#[must_use]
pub fn merge_grinder_edit(
    base: &Grinder,
    edited: &Grinder,
    current: &Grinder,
    now_ms: i64,
) -> Grinder {
    let mut out = crate::library_edit::merge_row(base, edited, current);
    out.id.clone_from(&current.id);
    out.updated_at = current.updated_at;
    if out != *current {
        out.updated_at = now_ms.max(current.updated_at);
    }
    out
}

/// New grinder records for each name in `names` that no grinder (archived
/// included, tombstones excluded) has yet ([`grinder_named`]): trimmed,
/// blank names skipped, a repeated name once, the first spelling kept. For
/// imports that carry grinder names (bags' `grinder`, shots'
/// `grinderModel`). Idempotent.
#[must_use]
pub fn grinders_for_names<S: AsRef<str>>(
    grinders: &[Grinder],
    names: &[S],
    now_ms: i64,
) -> Vec<Grinder> {
    let mut all: Vec<Grinder> = grinders.to_vec();
    let mut out = Vec::new();
    for name in names {
        let name = name.as_ref();
        if grinder_key(name).is_empty() || grinder_named(&all, name).is_some() {
            continue;
        }
        let g = new_grinder(name, None, &all, now_ms);
        all.push(g.clone());
        out.push(g);
    }
    out
}

/// What [`migrate_grinder_library`] changes. Empty lists when there is
/// nothing to do.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrinderLibraryMigration {
    /// Grinder records to add.
    pub new_grinders: Vec<Grinder>,
    /// Bags whose `grinds` it filled (they were `None`); `updated_at` is not
    /// changed, since the migration isn't an edit.
    pub updated_beans: Vec<Bean>,
    /// When the grinders step ran: the grinder to make active (the one the
    /// settings `grinder_model` names), else none. `None` when it didn't
    /// run: keep the pointer as it is.
    pub active_grinder_id: Option<String>,
    /// Whether the grinders step ran (there were no grinder records).
    pub grinders_migrated: bool,
    /// Why nothing ran: an input wasn't read ([`MigrationGate`]). `None` when
    /// the migration ran (even with nothing to do).
    pub skipped: Option<MigrationSkip>,
    /// Bags left unmigrated (`grinds` still `None`) because the settings were
    /// quarantined and the bag names no grinder of its own
    /// ([`MigrationGate::settings_quarantined`], PLAN follow-up 40): they
    /// migrate on a later load whose settings read.
    #[serde(default)]
    pub left_for_settings: u32,
}

/// Whether the migration's inputs were read (REVIEW-CORE H5). A shell
/// passes what it knows; the core runs nothing unless both read.
#[typeshare]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MigrationGate {
    /// The library file read, or there was none (a first run). `false` when
    /// it exists but couldn't be read: its rows would look absent.
    pub library: bool,
    /// The settings read, or there were none. `false` when they exist but
    /// couldn't be read: `grinder_model` would look blank and every bag's
    /// old grind would land under "no grinder" for good.
    pub settings: bool,
    /// A damaged settings copy was kept aside (quarantined on an earlier
    /// load) and the settings `grinder_model` is blank now, whether the
    /// settings read as absent or were written again since (REVIEW-514): the
    /// grinder the old settings named is unknown, not blank (PLAN follow-up
    /// 40). Only the bags that name their own grinder migrate; the rest wait
    /// until a settings grinder is known (picked, or restored). Settings
    /// never written, with nothing kept aside, are not this: they read, with
    /// no settings grinder.
    #[serde(default)]
    pub settings_quarantined: bool,
}

impl MigrationGate {
    /// Both inputs read.
    pub const READ: Self = Self {
        library: true,
        settings: true,
        settings_quarantined: false,
    };
}

/// Why [`migrate_grinder_library`] ran nothing.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum MigrationSkip {
    /// The library couldn't be read.
    LibraryUnreadable,
    /// The settings couldn't be read.
    SettingsUnreadable,
}

/// The library migration (#488): run on every load and after every import.
/// Idempotent, and it never changes `Bean::grinder`, `Bean::grinder_setting`
/// or the settings `grinder_model`.
///
/// 1. **Grinders**, only when `grinders` is empty (tombstones and archived
///    records count): one per distinct non-empty name among `grinder_model`
///    then the bags' `grinder` (bags not deleted, in library order), trimmed,
///    de-duplicated ignoring case and through the catalogue ("niche" and
///    "Niche Zero" are one), keeping the first spelling. Each takes the
///    catalogue's verified scale when its name names an entry, else the
///    default. Active = the one `grinder_model` names, else none.
/// 2. **Bags** with `grinds` = `None` (not deleted): see
///    [`crate::bag_grind::migrate_bag_grinds`]. A grinder name with no record
///    yet (a bag imported later) gets one.
///
/// **The gate** (REVIEW-CORE H5): nothing runs unless `gate` says both the
/// library and the settings were read; the answer is then empty with
/// [`GrinderLibraryMigration::skipped`] set (the library first). A later
/// load that reads both runs it. **Quarantined settings** with bags in the
/// library ([`MigrationGate::settings_quarantined`], PLAN follow-up 40) give
/// an unknown settings grinder, not a blank one: `grinder_model` is ignored,
/// no grinder is made active, and a bag that names no grinder of its own is
/// left as it is ([`GrinderLibraryMigration::left_for_settings`]), so its old
/// grind isn't filed under "no grinder" for good. Settings never written
/// (absent, nothing kept aside) read as no settings grinder, as before.
#[must_use]
pub fn migrate_grinder_library(
    grinders: &[Grinder],
    beans: &[Bean],
    grinder_model: &str,
    gate: MigrationGate,
    now_ms: i64,
) -> GrinderLibraryMigration {
    let mut out = GrinderLibraryMigration::default();
    if !gate.library {
        out.skipped = Some(MigrationSkip::LibraryUnreadable);
        return out;
    }
    if !gate.settings {
        out.skipped = Some(MigrationSkip::SettingsUnreadable);
        return out;
    }
    let live_beans = || beans.iter().filter(|b| b.deleted_at.is_none());
    // Quarantined settings over a library with bags: the settings grinder
    // is unknown, so nothing falls back to it.
    let unknown_settings = gate.settings_quarantined && live_beans().next().is_some();
    let grinder_model = if unknown_settings { "" } else { grinder_model };
    let mut all: Vec<Grinder> = grinders.to_vec();
    if grinders.is_empty() {
        out.grinders_migrated = true;
        let names: Vec<&str> = std::iter::once(grinder_model)
            .chain(live_beans().map(|b| b.grinder.as_str()))
            .collect();
        out.new_grinders = grinders_for_names(&[], &names, now_ms);
        out.active_grinder_id =
            grinder_named(&out.new_grinders, grinder_model).map(|g| g.id.clone());
        all.clone_from(&out.new_grinders);
    }
    for bean in live_beans().filter(|b| b.grinds.is_none()) {
        if unknown_settings && grinder_key(&bean.grinder).is_empty() {
            out.left_for_settings += 1;
            continue;
        }
        let (migrated, added) =
            crate::bag_grind::migrate_bag_grinds(bean, &all, grinder_model, now_ms);
        if let Some(g) = added {
            all.push(g.clone());
            out.new_grinders.push(g);
        }
        out.updated_beans.push(migrated);
    }
    out
}

/// JSON-bridged [`migrate_grinder_library`]: `{"grinders": Grinder[],
/// "beans": Bean[], "grinderModel": string, "gate": MigrationGate}` in
/// (`grinders` and `grinderModel` default to empty; `beans` and `gate` are
/// required, so a shell can't forget the gate), a
/// [`GrinderLibraryMigration`] out.
///
/// # Errors
/// The JSON parse error string on malformed input: the shell keeps what it
/// has, and the migration runs on a later load.
pub fn migrate_grinder_library_json(payload: &str, now_ms: i64) -> Result<String, String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct In {
        #[serde(default)]
        grinders: Vec<Grinder>,
        beans: Vec<Bean>,
        #[serde(default)]
        grinder_model: String,
        gate: MigrationGate,
    }
    let inp: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    let out = migrate_grinder_library(
        &inp.grinders,
        &inp.beans,
        &inp.grinder_model,
        inp.gate,
        now_ms,
    );
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

/// Which list a [`GrinderMatch`] comes from, in the order they are listed.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum GrinderMatchKind {
    /// One of the user's grinders.
    Owned,
    /// A catalogue entry the user doesn't own.
    Catalogue,
    /// One of the user's archived grinders: picking it restores it.
    Archived,
}

/// One row of the type-or-pick box.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GrinderMatch {
    /// Owned, catalogue or archived.
    pub kind: GrinderMatchKind,
    /// The grinder's id (owned, archived) or the catalogue entry's id.
    pub id: String,
    /// What to show.
    pub name: String,
    /// How well it matched, `0..=1`; `0` for a blank query.
    pub score: f32,
}

/// The type-or-pick box ("Add a grinder: type or pick"; Devices' Add grinder;
/// the Brew picker): the user's grinders not in `exclude_ids` (those already
/// on the bag), then the catalogue entries they don't own, then their
/// archived grinders, each list best match first. Typos, missing maker names
/// and spacing don't matter ("df46" → DF64; "niche" → Niche Zero; "c40" →
/// Comandante C40), through the bean search's matcher. A catalogue entry the
/// user has a record of (archived included) is listed as that record. A
/// blank query lists everything in list order. The shell adds
/// "+ Create “<typed text>”" last.
#[must_use]
pub fn match_grinders(
    query: &str,
    grinders: &[Grinder],
    exclude_ids: &[String],
) -> Vec<GrinderMatch> {
    let blank = crate::bean_search::is_blank_query(query);
    let score = |names: &[String]| -> Option<f32> {
        if blank {
            return Some(0.0);
        }
        let compact: Vec<String> = names.iter().map(|n| compact_key(n)).collect();
        let texts: Vec<&str> = names.iter().chain(&compact).map(String::as_str).collect();
        let spaced = crate::bean_search::name_match_score(&texts, query);
        let joined = crate::bean_search::name_match_score(&texts, &compact_key(query));
        match (spaced, joined) {
            (Some(a), Some(b)) => Some(a.max(b)),
            (a, b) => a.or(b),
        }
    };
    let mut out: Vec<GrinderMatch> = Vec::new();
    let kept = grinders.iter().filter(|g| g.deleted_at.is_none());
    let record_names = |g: &Grinder| {
        let mut names = vec![g.name.clone()];
        if let Some(e) = g.catalogue_entry() {
            names.extend(e.names());
        }
        names
    };
    for g in kept.clone() {
        let kind = if g.is_live() {
            if exclude_ids.contains(&g.id) {
                continue;
            }
            GrinderMatchKind::Owned
        } else {
            GrinderMatchKind::Archived
        };
        if let Some(s) = score(&record_names(g)) {
            out.push(GrinderMatch {
                kind,
                id: g.id.clone(),
                name: g.name.clone(),
                score: s,
            });
        }
    }
    // The entries the user has a record of: by preset, or by a name that
    // names the entry strictly.
    let recorded: Vec<&str> = kept
        .clone()
        .filter_map(|g| {
            g.catalogue_entry()
                .or_else(|| catalogue_entry_for_name(&g.name))
                .map(|e| e.id)
        })
        .collect();
    // Hidden entries only merge spellings: never offered.
    for e in GRINDER_CATALOGUE.iter().filter(|e| e.listed()) {
        if recorded.contains(&e.id) {
            continue;
        }
        if let Some(s) = score(&e.names()) {
            out.push(GrinderMatch {
                kind: GrinderMatchKind::Catalogue,
                id: e.id.to_owned(),
                name: e.name(),
                score: s,
            });
        }
    }
    // Stable: a blank query keeps list order within each kind.
    out.sort_by(|a, b| a.kind.cmp(&b.kind).then(b.score.total_cmp(&a.score)));
    out
}

/// JSON-bridged [`match_grinders`]: the query, a `Grinder[]` JSON and the ids
/// to leave out (a JSON array of strings; malformed = none) in, a
/// `GrinderMatch[]` JSON out.
///
/// # Errors
/// The JSON parse error string when the grinders are malformed.
pub fn match_grinders_json(
    query: &str,
    grinders_json: &str,
    exclude_ids_json: &str,
) -> Result<String, String> {
    let grinders: Vec<Grinder> = serde_json::from_str(grinders_json).map_err(|e| e.to_string())?;
    let exclude: Vec<String> = serde_json::from_str(exclude_ids_json).unwrap_or_default();
    serde_json::to_string(&match_grinders(query, &grinders, &exclude)).map_err(|e| e.to_string())
}

/// JSON-bridged [`add_grinder`]: a `Grinder[]` JSON, the typed name, the
/// catalogue id (empty for none) and now in, a [`GrinderAdd`] JSON or `null`
/// out.
///
/// # Errors
/// The JSON parse error string when the grinders are malformed.
pub fn add_grinder_json(
    grinders_json: &str,
    name: &str,
    catalogue_id: &str,
    now_ms: i64,
) -> Result<String, String> {
    let grinders: Vec<Grinder> = serde_json::from_str(grinders_json).map_err(|e| e.to_string())?;
    let out = add_grinder(&grinders, name, Some(catalogue_id), now_ms);
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

/// JSON-bridged [`add_another_grinder`]: as [`add_grinder_json`].
///
/// # Errors
/// The JSON parse error string when the grinders are malformed.
pub fn add_another_grinder_json(
    grinders_json: &str,
    name: &str,
    catalogue_id: &str,
    now_ms: i64,
) -> Result<String, String> {
    let grinders: Vec<Grinder> = serde_json::from_str(grinders_json).map_err(|e| e.to_string())?;
    let out = add_another_grinder(&grinders, name, Some(catalogue_id), now_ms);
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

/// JSON-bridged [`archive_grinder`]: a `Grinder` JSON, the active id (empty
/// for none) and now in, a [`GrinderArchive`] JSON out.
///
/// # Errors
/// The JSON parse error string when the grinder is malformed.
pub fn archive_grinder_json(
    grinder_json: &str,
    active_id: &str,
    now_ms: i64,
) -> Result<String, String> {
    let g: Grinder = serde_json::from_str(grinder_json).map_err(|e| e.to_string())?;
    let active = Some(active_id).filter(|id| !id.is_empty());
    serde_json::to_string(&archive_grinder(&g, active, now_ms)).map_err(|e| e.to_string())
}

/// JSON-bridged [`restore_grinder`].
///
/// # Errors
/// The JSON parse error string when the grinder is malformed.
pub fn restore_grinder_json(grinder_json: &str, now_ms: i64) -> Result<String, String> {
    let g: Grinder = serde_json::from_str(grinder_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&restore_grinder(&g, now_ms)).map_err(|e| e.to_string())
}

/// JSON-bridged [`merge_grinder_edit`].
///
/// # Errors
/// The JSON error string when a row is malformed.
pub fn merge_grinder_edit_json(
    base_json: &str,
    edited_json: &str,
    current_json: &str,
    now_ms: i64,
) -> Result<String, String> {
    let parse = |s: &str| serde_json::from_str::<Grinder>(s).map_err(|e| e.to_string());
    let out = merge_grinder_edit(
        &parse(base_json)?,
        &parse(edited_json)?,
        &parse(current_json)?,
        now_ms,
    );
    serde_json::to_string(&out).map_err(|e| e.to_string())
}

/// JSON-bridged [`grinders_for_names`]: a `Grinder[]` JSON and the names (a
/// JSON array; non-strings skipped, anything else = none) in, the new
/// `Grinder[]` out.
///
/// # Errors
/// The JSON parse error string when the grinders are malformed.
pub fn grinders_for_names_json(
    grinders_json: &str,
    names_json: &str,
    now_ms: i64,
) -> Result<String, String> {
    let grinders: Vec<Grinder> = serde_json::from_str(grinders_json).map_err(|e| e.to_string())?;
    let names: Vec<String> = match serde_json::from_str(names_json) {
        Ok(serde_json::Value::Array(names)) => names
            .iter()
            .filter_map(serde_json::Value::as_str)
            .map(str::to_owned)
            .collect(),
        _ => Vec::new(),
    };
    serde_json::to_string(&grinders_for_names(&grinders, &names, now_ms)).map_err(|e| e.to_string())
}

/// JSON-bridged [`active_grinder`]: a `Grinder[]` JSON and the pointer (empty
/// for none) in, the active `Grinder` JSON or `null` out. Malformed grinders
/// are no active grinder.
#[must_use]
pub fn active_grinder_json(grinders_json: &str, active_id: &str) -> String {
    let grinders: Vec<Grinder> = serde_json::from_str(grinders_json).unwrap_or_default();
    let active = active_grinder(&grinders, Some(active_id).filter(|id| !id.is_empty()));
    serde_json::to_string(&active).unwrap_or_else(|_| "null".to_owned())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::QuickControlBounds;
    use crate::bounds::{quick_control_bounds, quick_control_bounds_for_grinder_json};

    const NOW: i64 = 1_000;

    pub(crate) fn grinder(id: &str, name: &str) -> Grinder {
        Grinder {
            id: id.to_owned(),
            name: name.to_owned(),
            updated_at: 1,
            ..Grinder::default()
        }
    }

    fn bag(id: &str, grinder: &str, setting: &str) -> Bean {
        let mut b = Bean::new(id.into(), id.into(), 1);
        b.grinder = grinder.into();
        b.grinder_setting = setting.into();
        b
    }

    fn names(gs: &[Grinder]) -> Vec<&str> {
        gs.iter().map(|g| g.name.as_str()).collect()
    }

    // ── Scales and bounds ──

    #[test]
    fn the_default_scale_is_the_range_before_488() {
        let r = GrindScale::default().step_range();
        assert_eq!((r.min, r.max, r.step), (0.0, 20.0, 0.1));
        assert_eq!(quick_control_bounds().grind, r);
        assert_eq!(quick_control_bounds().grind_unit, "");
    }

    #[test]
    fn the_grind_bounds_follow_the_active_grinder() {
        let motto = add_grinder(&[], "BOOKOO Motto 80", None, NOW)
            .unwrap()
            .grinder;
        let q: QuickControlBounds = serde_json::from_str(&quick_control_bounds_for_grinder_json(
            &serde_json::to_string(&motto).unwrap(),
        ))
        .unwrap();
        assert_eq!((q.grind.min, q.grind.max, q.grind.step), (0.0, 999.0, 1.0));
        assert_eq!(q.grind_unit, "μm");
        assert_eq!(
            QuickControlBounds {
                grind: quick_control_bounds().grind,
                grind_unit: String::new(),
                ..q
            },
            quick_control_bounds()
        );
        // No grinder, or garbage: the default table.
        for none in ["null", "", "nope"] {
            let q: QuickControlBounds =
                serde_json::from_str(&quick_control_bounds_for_grinder_json(none)).unwrap();
            assert_eq!(q, quick_control_bounds(), "{none:?}");
        }
    }

    #[test]
    fn a_broken_scale_is_repaired_for_the_stepper_but_not_in_storage() {
        let broken = GrindScale {
            min: 50.0,
            max: f32::NAN,
            step: -1.0,
            unit: "x".to_owned(),
        };
        let r = broken.step_range();
        assert_eq!((r.min, r.max, r.step), (20.0, 50.0, 0.1));
        assert!(broken.max.is_nan(), "the stored scale is untouched");
        assert!(!r.contains(1200.0));
    }

    // ── Active grinder ──

    #[test]
    fn the_active_grinder_is_live_and_known() {
        let mut archived = grinder("grinder:b", "B");
        archived.archived_at = Some(5);
        let mut deleted = grinder("grinder:c", "C");
        deleted.deleted_at = Some(5);
        let gs = vec![grinder("grinder:a", "A"), archived, deleted];
        assert_eq!(
            active_grinder(&gs, Some("grinder:a")).map(|g| g.name.as_str()),
            Some("A")
        );
        for id in [
            Some("grinder:b"),
            Some("grinder:c"),
            Some("grinder:gone"),
            None,
        ] {
            assert!(active_grinder(&gs, id).is_none(), "{id:?}");
            assert_eq!(active_grind_scale(&gs, id), GrindScale::default());
        }
        assert_eq!(active_grinder_json("not json", "grinder:a"), "null");
    }

    // ── Add, archive, restore ──

    #[test]
    fn adding_a_catalogue_grinder_takes_its_verified_scale() {
        let a = add_grinder(&[], "", Some("niche-zero"), NOW).unwrap();
        assert_eq!(a.kind, GrinderAddKind::Created);
        assert_eq!(a.grinder.id, "grinder:niche-zero");
        assert_eq!(a.grinder.name, "Niche Zero");
        assert_eq!(a.grinder.preset_id.as_deref(), Some("niche-zero"));
        assert_eq!(a.grinder.scale.max, 50.0);
        assert_eq!(a.grinder.updated_at, NOW);
        let ek = add_grinder(&[], "", Some("mahlkonig-ek43"), NOW)
            .unwrap()
            .grinder;
        assert_eq!(
            (ek.name.as_str(), ek.scale.min, ek.scale.max, ek.scale.step),
            ("Mahlkönig EK43", 0.0, 16.0, 0.1)
        );
        // A typical range comes with the entry too.
        let c40 = add_grinder(&[], "c40", None, NOW).unwrap().grinder;
        assert_eq!(
            (
                c40.preset_id.as_deref(),
                c40.scale.max,
                c40.scale.unit.as_str()
            ),
            (Some("comandante-c40"), 40.0, "clicks")
        );
        // A hidden entry links the record (its spellings merge) but gives no
        // range: the default scale.
        let df = add_grinder(&[], "P64", None, NOW).unwrap().grinder;
        assert_eq!(
            (df.preset_id.as_deref(), df.scale),
            (Some("lagom-p64"), GrindScale::default())
        );
        assert_eq!(add_grinder(&[], "", Some("retired-grinder"), NOW), None);
        // A typed name the catalogue knows strictly takes that entry.
        let nz = add_grinder(&[], "nichezero", None, NOW).unwrap().grinder;
        assert_eq!(
            (nz.name.as_str(), nz.preset_id.as_deref()),
            ("nichezero", Some("niche-zero"))
        );
        // A custom name: the default scale, no preset.
        let custom = add_grinder(&[], "  Bar grinder ", None, NOW)
            .unwrap()
            .grinder;
        assert_eq!(
            (custom.id.as_str(), custom.preset_id),
            ("grinder:bar-grinder", None)
        );
        assert_eq!(add_grinder(&[], " ", None, NOW), None);
        assert_eq!(add_grinder(&[], "x", Some("no-such-entry"), NOW), None);
    }

    /// A grinder added from an entry the catalogue later dropped, or moved
    /// to another tier, keeps its stored name, scale and `preset_id`; nothing
    /// rewrites it, and it still answers to its own name.
    #[test]
    fn a_grinder_from_a_dropped_entry_is_never_rewritten() {
        let mut df = grinder("grinder:turin-df64", "Turin DF64");
        df.preset_id = Some("retired-grinder".into());
        df.scale = GrindScale {
            min: 0.0,
            max: 90.0,
            step: 1.0,
            unit: String::new(),
        };
        let before = df.clone();
        let gs = vec![df];
        assert_eq!(gs[0].catalogue_entry(), None);
        assert!(names_grinder(&gs[0], "turin df64"));
        assert_eq!(grinder_named(&gs, "Turin DF64"), Some(&before));
        assert!(grinders_for_names(&gs, &["Turin DF64"], NOW).is_empty());
        let again = add_grinder(&gs, "Turin DF64", None, NOW).unwrap();
        assert_eq!(
            (again.kind, &again.grinder),
            (GrinderAddKind::Existing, &before)
        );
        let m = migrate_grinder_library(
            &gs,
            &[bag("b1", "Turin DF64", "")],
            "Turin DF64",
            MigrationGate::READ,
            NOW,
        );
        assert!(m.new_grinders.is_empty());
        assert_eq!(
            active_grind_scale(&gs, Some("grinder:turin-df64")),
            before.scale
        );
        let found = match_grinders("df64", &gs, &[]);
        assert_eq!(found[0].id, "grinder:turin-df64");
        assert_eq!(gs[0], before);
    }

    #[test]
    fn adding_an_archived_grinder_again_revives_the_same_record() {
        let nz = add_grinder(&[], "", Some("niche-zero"), NOW)
            .unwrap()
            .grinder;
        let archived = archive_grinder(&nz, None, 2_000).grinder;
        assert_eq!(archived.archived_at, Some(2_000));
        for (name, entry) in [
            ("", Some("niche-zero")),
            ("niche", None),
            ("NICHE ZERO", None),
        ] {
            let again = add_grinder(std::slice::from_ref(&archived), name, entry, 3_000).unwrap();
            assert_eq!(again.kind, GrinderAddKind::Revived, "{name:?}");
            assert_eq!(again.grinder.id, nz.id, "same id: its bag grinds come back");
            assert_eq!(again.grinder.archived_at, None);
            assert_eq!(again.grinder.updated_at, 3_000);
        }
        // Owned already: nothing changes.
        let again = add_grinder(std::slice::from_ref(&nz), "Niche", None, 3_000).unwrap();
        assert_eq!((again.kind, again.grinder), (GrinderAddKind::Existing, nz));
    }

    #[test]
    fn a_tombstoned_grinder_is_not_revived_and_keeps_its_id_taken() {
        let mut dead = grinder("grinder:ek43", "EK43");
        dead.deleted_at = Some(5);
        let a = add_grinder(&[dead], "EK43", None, NOW).unwrap();
        assert_eq!(a.kind, GrinderAddKind::Created);
        assert_eq!(a.grinder.id, "grinder:ek43-2");
    }

    #[test]
    fn archiving_the_active_grinder_clears_the_pointer() {
        let g = grinder("grinder:a", "A");
        let out = archive_grinder(&g, Some("grinder:a"), NOW);
        assert_eq!(out.active_grinder_id, None);
        assert_eq!(out.grinder.archived_at, Some(NOW));
        let other = archive_grinder(&g, Some("grinder:b"), NOW);
        assert_eq!(other.active_grinder_id.as_deref(), Some("grinder:b"));
        // Archiving twice keeps the first time.
        assert_eq!(
            archive_grinder(&out.grinder, None, 9_000).grinder,
            out.grinder
        );
        // Restore brings it back.
        let back = restore_grinder(&out.grinder, 2 * NOW);
        assert_eq!((back.archived_at, back.updated_at), (None, 2 * NOW));
        assert_eq!(restore_grinder(&back, 9_000), back);
    }

    #[test]
    fn a_grinder_edit_merges_onto_the_row_as_stored() {
        let base = grinder("grinder:a", "A");
        let mut edited = base.clone();
        edited.scale.max = 40.0;
        let mut current = base.clone();
        current.name = "Renamed elsewhere".into();
        let out = merge_grinder_edit(&base, &edited, &current, NOW);
        assert_eq!(
            (out.name.as_str(), out.scale.max),
            ("Renamed elsewhere", 40.0)
        );
        assert_eq!(out.updated_at, NOW);
        assert_eq!(merge_grinder_edit(&base, &base, &current, NOW), current);
    }

    // ── Two of the same model (follow-up 18) ──

    #[test]
    fn a_second_grinder_of_an_owned_model_gets_its_own_id_and_name() {
        let first = add_grinder(&[], "", Some("niche-zero"), NOW)
            .unwrap()
            .grinder;
        let owned = vec![first.clone()];
        // Plain add still answers the owned one.
        assert_eq!(
            add_grinder(&owned, "", Some("niche-zero"), NOW)
                .unwrap()
                .kind,
            GrinderAddKind::Existing
        );
        let second = add_another_grinder(&owned, "", Some("niche-zero"), NOW).unwrap();
        assert_eq!(second.kind, GrinderAddKind::Created);
        let g2 = second.grinder;
        assert_eq!(g2.name, "Niche Zero 2");
        assert_eq!(g2.id, "grinder:niche-zero-2");
        assert_ne!(g2.id, first.id);
        assert_eq!(g2.preset_id.as_deref(), Some("niche-zero"));
        assert_eq!(g2.scale, first.scale, "the verified scale, not the default");
        // A third by typed name.
        let two = vec![first.clone(), g2.clone()];
        let third = add_another_grinder(&two, "Niche Zero", None, NOW)
            .unwrap()
            .grinder;
        assert_eq!(
            (third.name.as_str(), third.id.as_str()),
            ("Niche Zero 3", "grinder:niche-zero-3")
        );
        // Matching: the exact name finds each record, the model name the first.
        assert_eq!(grinder_named(&two, "Niche Zero 2").unwrap().id, g2.id);
        assert_eq!(grinder_named(&two, "niche zero").unwrap().id, first.id);
        assert_eq!(
            add_grinder(&two, "Niche Zero 2", None, NOW)
                .unwrap()
                .grinder
                .id,
            g2.id,
            "typing its name finds it"
        );
        // A model nobody owns yet is a plain add.
        let ek = add_another_grinder(&two, "EK43", None, NOW).unwrap();
        assert_eq!(
            (ek.kind, ek.grinder.name.as_str()),
            (GrinderAddKind::Created, "EK43")
        );
    }

    #[test]
    fn adding_another_revives_an_archived_one_of_that_model_first() {
        let first = add_grinder(&[], "", Some("niche-zero"), NOW)
            .unwrap()
            .grinder;
        let g2 = add_another_grinder(std::slice::from_ref(&first), "", Some("niche-zero"), NOW)
            .unwrap()
            .grinder;
        let archived = archive_grinder(&g2, None, NOW).grinder;
        let lib = vec![first.clone(), archived];
        let again = add_another_grinder(&lib, "", Some("niche-zero"), NOW + 1).unwrap();
        assert_eq!(again.kind, GrinderAddKind::Revived);
        assert_eq!(again.grinder.id, g2.id, "same id: its bag grinds come back");
        // A tombstone is never revived, and its id stays taken.
        let mut dead = g2.clone();
        dead.deleted_at = Some(NOW);
        let lib = vec![first, dead];
        let fresh = add_another_grinder(&lib, "", Some("niche-zero"), NOW)
            .unwrap()
            .grinder;
        assert_eq!(fresh.name, "Niche Zero 2");
        assert_eq!(fresh.id, "grinder:niche-zero-2-2");
        let out: Option<GrinderAdd> = serde_json::from_str(
            &add_another_grinder_json(&serde_json::to_string(&lib).unwrap(), "", "niche-zero", NOW)
                .unwrap(),
        )
        .unwrap();
        assert_eq!(out.unwrap().grinder.id, "grinder:niche-zero-2-2");
    }

    // ── Names ──

    #[test]
    fn names_match_through_the_catalogue_but_not_by_typo() {
        let nz = add_grinder(&[], "Niche Zero", None, NOW).unwrap().grinder;
        assert!(names_grinder(&nz, " niche "));
        assert!(names_grinder(&nz, "NicheZero"));
        assert!(!names_grinder(&nz, "Niche Duo"));
        let custom = grinder("grinder:x", "My Niche");
        assert!(!names_grinder(&custom, "Niche"));
        let df64 = grinder("grinder:df64", "DF64");
        assert!(!names_grinder(&df64, "DF54"));
    }

    #[test]
    fn an_import_adds_only_new_grinder_names() {
        let have = vec![grinder("grinder:ek43", "EK43")];
        let out = grinders_for_names(
            &have,
            &["ek43", " Niche Zero ", "", "niche", "nichezero"],
            NOW,
        );
        assert_eq!(names(&out), ["Niche Zero"]);
        assert_eq!(out[0].preset_id.as_deref(), Some("niche-zero"));
        let mut all = have;
        all.extend(out);
        assert!(grinders_for_names(&all, &["Niche Zero", "EK43"], NOW).is_empty());
        let out: Vec<Grinder> = serde_json::from_str(
            &grinders_for_names_json(
                &serde_json::to_string(&all).unwrap(),
                r#"["Comandante C40", 3, null]"#,
                NOW,
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(names(&out), ["Comandante C40"]);
    }

    // ── Migration: grinders (one test per rule) ──

    #[test]
    fn migration_creates_one_grinder_per_name_in_settings_then_bags() {
        let m = migrate_grinder_library(
            &[],
            &[bag("b1", "Niche Zero", ""), bag("b2", "Comandante", "")],
            "EK43",
            MigrationGate::READ,
            NOW,
        );
        assert!(m.grinders_migrated);
        assert_eq!(names(&m.new_grinders), ["EK43", "Niche Zero", "Comandante"]);
    }

    #[test]
    fn migration_skips_blank_names_and_trims_the_rest() {
        let m = migrate_grinder_library(
            &[],
            &[
                bag("b1", "", ""),
                bag("b2", "  Niche Zero  ", ""),
                bag("b3", "\t", ""),
            ],
            "  ",
            MigrationGate::READ,
            NOW,
        );
        assert_eq!(names(&m.new_grinders), ["Niche Zero"]);
        assert_eq!(m.active_grinder_id, None);
    }

    #[test]
    fn migration_dedupes_ignoring_case_and_through_the_catalogue_keeping_the_first_spelling() {
        let m = migrate_grinder_library(
            &[],
            &[
                bag("b1", "Niche Zero", ""),
                bag("b2", "NicheZero", ""),
                bag("b3", " NICHE", ""),
                bag("b4", "EK43", ""),
            ],
            "niche zero",
            MigrationGate::READ,
            NOW,
        );
        assert_eq!(names(&m.new_grinders), ["niche zero", "EK43"]);
    }

    #[test]
    fn migration_takes_a_verified_scale_else_the_default() {
        let m = migrate_grinder_library(
            &[],
            &[bag("b1", "My Hand Grinder", "")],
            "motto 80",
            MigrationGate::READ,
            NOW,
        );
        let gs = &m.new_grinders;
        assert_eq!(gs[0].preset_id.as_deref(), Some("bookoo-motto-80"));
        assert_eq!(gs[0].scale.max, 999.0);
        assert_eq!(
            (gs[1].preset_id.as_deref(), &gs[1].scale),
            (None, &GrindScale::default())
        );
    }

    #[test]
    fn migration_makes_the_settings_grinder_active_else_none() {
        let m = migrate_grinder_library(
            &[],
            &[bag("b1", "Niche Zero", ""), bag("b2", "EK43", "")],
            " ek43 ",
            MigrationGate::READ,
            NOW,
        );
        assert_eq!(names(&m.new_grinders), ["ek43", "Niche Zero"]);
        assert_eq!(m.active_grinder_id.as_deref(), Some("grinder:ek43"));
        // No settings grinder: none is active (not the first bag's).
        let m = migrate_grinder_library(
            &[],
            &[bag("b1", "Niche Zero", "")],
            "",
            MigrationGate::READ,
            NOW,
        );
        assert_eq!(m.active_grinder_id, None);
    }

    #[test]
    fn migration_runs_nothing_unless_library_and_settings_read() {
        // REVIEW-CORE H5 repro: the settings file is unreadable, so the
        // grinder name reads blank. Run anyway, the bag's old grind would be
        // filed under "no grinder" and `grinds` set, so it never re-runs.
        let bags = [bag("b1", "", "14")];
        let skipped = |gate| migrate_grinder_library(&[], &bags, "", gate, NOW);
        let no_settings = skipped(MigrationGate {
            library: true,
            settings: false,
            settings_quarantined: false,
        });
        assert_eq!(
            no_settings,
            GrinderLibraryMigration {
                skipped: Some(MigrationSkip::SettingsUnreadable),
                ..GrinderLibraryMigration::default()
            }
        );
        let no_library = skipped(MigrationGate {
            library: false,
            settings: true,
            settings_quarantined: false,
        });
        assert_eq!(no_library.skipped, Some(MigrationSkip::LibraryUnreadable));
        assert!(no_library.new_grinders.is_empty() && no_library.updated_beans.is_empty());
        assert_eq!(
            skipped(MigrationGate::default()).skipped,
            Some(MigrationSkip::LibraryUnreadable),
            "the default gate is closed"
        );
        // The next load reads both: the bag migrates under the settings grinder.
        let ran = migrate_grinder_library(&[], &bags, "Niche Zero", MigrationGate::READ, NOW);
        assert_eq!(ran.skipped, None);
        let rows = ran.updated_beans[0].grinds.as_deref().unwrap();
        assert_eq!(rows[0].grinder_id, "grinder:niche-zero");
        assert_eq!(rows[0].grind, "14");
    }

    #[test]
    fn quarantined_settings_over_bags_migrate_only_bags_with_their_own_grinder() {
        // PLAN follow-up 40: the settings were damaged on the upgrade launch
        // and kept aside, so this one reads them as absent.
        let bags = [bag("b1", "", "14"), bag("b2", "EK43", "8")];
        let quarantined = MigrationGate {
            settings_quarantined: true,
            ..MigrationGate::READ
        };
        let m = migrate_grinder_library(&[], &bags, "Niche Zero", quarantined, NOW);
        assert_eq!(m.skipped, None);
        assert_eq!(
            names(&m.new_grinders),
            ["EK43"],
            "no settings grinder is made"
        );
        assert_eq!(m.active_grinder_id, None);
        assert_eq!(m.updated_beans.len(), 1);
        assert_eq!(m.updated_beans[0].id, "b2");
        assert_eq!(m.left_for_settings, 1);
        // A later load whose settings read files b1 under their grinder.
        let later = migrate_grinder_library(
            &m.new_grinders,
            &bags[..1],
            "Niche Zero",
            MigrationGate::READ,
            NOW,
        );
        let rows = later.updated_beans[0].grinds.as_deref().unwrap();
        assert_eq!(rows[0].grinder_id, "grinder:niche-zero");
        assert_eq!(later.left_for_settings, 0);
        // No bags: nothing waits.
        let first = migrate_grinder_library(&[], &[], "", quarantined, NOW);
        assert_eq!((first.skipped, first.grinders_migrated), (None, true));
        // Deleted bags don't count as a library.
        let mut gone = bag("b9", "", "14");
        gone.deleted_at = Some(1);
        let m = migrate_grinder_library(&[], &[gone], "", quarantined, NOW);
        assert_eq!(m.left_for_settings, 0);
        // Over the JSON bridge too (`settingsQuarantined` is false when missing).
        let json = serde_json::json!({
            "beans": [bag("b1", "", "14")],
            "grinderModel": "EK43",
            "gate": { "library": true, "settings": true, "settingsQuarantined": true },
        });
        let out: GrinderLibraryMigration =
            serde_json::from_str(&migrate_grinder_library_json(&json.to_string(), NOW).unwrap())
                .unwrap();
        assert!(out.updated_beans.is_empty());
        assert_eq!(out.left_for_settings, 1);
    }

    #[test]
    fn settings_never_written_read_as_no_settings_grinder() {
        // Absent with nothing kept aside (web writes its settings only on a
        // change): every bag migrates, a bag with no grinder of its own
        // under "no grinder", as before.
        let bags = [bag("b1", "", "14"), bag("b2", "EK43", "8")];
        let m = migrate_grinder_library(&[], &bags, "", MigrationGate::READ, NOW);
        assert_eq!(m.updated_beans.len(), 2);
        assert_eq!(m.left_for_settings, 0);
        let rows = m.updated_beans[0].grinds.as_deref().unwrap();
        assert_eq!(
            (rows[0].grinder_id.as_str(), rows[0].grind.as_str()),
            (crate::bag_grind::NO_GRINDER_ID, "14")
        );
        let json = serde_json::json!({
            "beans": [bag("b1", "", "14")],
            "gate": { "library": true, "settings": true },
        });
        let out: GrinderLibraryMigration =
            serde_json::from_str(&migrate_grinder_library_json(&json.to_string(), NOW).unwrap())
                .unwrap();
        assert_eq!(out.updated_beans.len(), 1);
    }

    #[test]
    fn migration_json_requires_the_gate() {
        let beans = serde_json::to_value([bag("b1", "", "14")]).unwrap();
        let no_gate = serde_json::json!({ "beans": beans, "grinderModel": "EK43" });
        assert!(migrate_grinder_library_json(&no_gate.to_string(), NOW).is_err());
        let closed = serde_json::json!({
            "beans": beans,
            "grinderModel": "EK43",
            "gate": { "library": true, "settings": false },
        });
        let out: GrinderLibraryMigration =
            serde_json::from_str(&migrate_grinder_library_json(&closed.to_string(), NOW).unwrap())
                .unwrap();
        assert_eq!(out.skipped, Some(MigrationSkip::SettingsUnreadable));
        assert!(out.updated_beans.is_empty());
        let s = serde_json::to_string(&out).unwrap();
        assert!(s.contains(r#""skipped":"settingsUnreadable""#), "{s}");
    }

    #[test]
    fn migration_gives_distinct_stable_ids() {
        let run = || {
            migrate_grinder_library(
                &[],
                &[bag("b1", "Niche-Zero 2", ""), bag("b2", "EK43", "")],
                "Niche Zero",
                MigrationGate::READ,
                NOW,
            )
        };
        let ids: Vec<String> = run().new_grinders.into_iter().map(|g| g.id).collect();
        assert_eq!(
            ids,
            ["grinder:niche-zero", "grinder:niche-zero-2", "grinder:ek43"]
        );
        assert_eq!(run(), run(), "deterministic");
    }

    #[test]
    fn the_grinders_step_runs_only_with_no_grinder_records() {
        let mut archived = grinder("grinder:ek43", "EK43");
        archived.archived_at = Some(5);
        let m = migrate_grinder_library(
            &[archived],
            &[bag("b1", "Niche Zero", "")],
            "EK43",
            MigrationGate::READ,
            NOW,
        );
        assert!(!m.grinders_migrated);
        assert_eq!(m.active_grinder_id, None, "the pointer is left alone");
        // Only the bag step ran: the bag has no setting, so no grinder is
        // needed for it.
        assert!(m.new_grinders.is_empty());
    }

    #[test]
    fn migration_skips_deleted_bags() {
        let mut gone = bag("b1", "Niche Zero", "14");
        gone.deleted_at = Some(5);
        let m = migrate_grinder_library(&[], &[gone], "", MigrationGate::READ, NOW);
        assert!(m.new_grinders.is_empty() && m.updated_beans.is_empty());
    }

    #[test]
    fn migration_is_idempotent() {
        let beans = vec![
            bag("b1", "Niche Zero", "14"),
            bag("b2", "", "3"),
            bag("b3", "EK43", ""),
        ];
        let once = migrate_grinder_library(&[], &beans, "Comandante", MigrationGate::READ, NOW);
        let mut beans_after = beans.clone();
        for b in &once.updated_beans {
            let slot = beans_after.iter_mut().find(|x| x.id == b.id).unwrap();
            *slot = b.clone();
        }
        let twice = migrate_grinder_library(
            &once.new_grinders,
            &beans_after,
            "Comandante",
            MigrationGate::READ,
            NOW + 1,
        );
        assert_eq!(twice, GrinderLibraryMigration::default());
    }

    #[test]
    fn migration_never_changes_the_old_fields() {
        let beans = vec![bag("b1", " Niche Zero ", " 14 "), bag("b2", "", "3")];
        let m = migrate_grinder_library(&[], &beans, "EK43", MigrationGate::READ, NOW);
        for (before, after) in beans.iter().zip(&m.updated_beans) {
            assert_eq!(after.grinder, before.grinder);
            assert_eq!(after.grinder_setting, before.grinder_setting);
            assert_eq!(after.updated_at, before.updated_at, "not an edit");
            assert_eq!(
                Bean {
                    grinds: None,
                    ..after.clone()
                },
                *before
            );
        }
    }

    #[test]
    fn migration_json_round_trips_and_rejects_garbage() {
        let payload = serde_json::json!({
            "beans": [bag("b1", "Niche Zero", "14")],
            "grinderModel": "EK43",
            "gate": MigrationGate::READ,
        });
        let out: GrinderLibraryMigration =
            serde_json::from_str(&migrate_grinder_library_json(&payload.to_string(), NOW).unwrap())
                .unwrap();
        assert_eq!(names(&out.new_grinders), ["EK43", "Niche Zero"]);
        assert_eq!(out.updated_beans.len(), 1);
        // A library that isn't there yet must not spend the migration.
        for bad in ["not json", "", "null", "{}", r#"{"grinders":[]}"#] {
            assert!(migrate_grinder_library_json(bad, NOW).is_err(), "{bad:?}");
        }
    }

    // ── The type-or-pick box ──

    fn ids(ms: &[GrinderMatch]) -> Vec<(GrinderMatchKind, &str)> {
        ms.iter().map(|m| (m.kind, m.id.as_str())).collect()
    }

    #[test]
    fn typos_missing_makers_and_spacing_dont_matter() {
        for (query, id) in [
            ("nihce", "niche-zero"),
            ("niche", "niche-zero"),
            ("nichezero", "niche-zero"),
            ("niche  zero", "niche-zero"),
            ("smart grinder", "breville-smart-grinder-pro"),
            ("virtuso", "baratza-virtuoso-plus"),
            ("sculptor 64s", "timemore-sculptor-064s"),
            ("mahlkonig x54", "mahlkonig-x54"),
        ] {
            let top = match_grinders(query, &[], &[]);
            assert_eq!(
                top.first().map(|m| m.id.as_str()),
                Some(id),
                "{query}: {:?}",
                ids(&top)
            );
        }
        assert!(match_grinders("zzzzqqq", &[], &[]).is_empty());
    }

    #[test]
    fn owned_grinders_come_first_then_the_catalogue_then_archived() {
        let mine = grinder("grinder:my-niche", "My Niche");
        let mut old = add_grinder(&[], "", Some("niche-duo"), NOW)
            .unwrap()
            .grinder;
        old.archived_at = Some(5);
        let on_bag = grinder("grinder:niche-zero", "Niche Zero");
        let mut on_bag = on_bag;
        on_bag.preset_id = Some("niche-zero".into());
        let gs = vec![old.clone(), mine.clone(), on_bag.clone()];
        let out = match_grinders("niche", &gs, &[on_bag.id.clone()]);
        assert_eq!(
            out[0],
            GrinderMatch {
                kind: GrinderMatchKind::Owned,
                id: mine.id.clone(),
                name: "My Niche".into(),
                score: out[0].score
            }
        );
        // The bag already has the Niche Zero: not listed, and its catalogue
        // entry isn't either (it is owned). The Duo shows as the archived
        // record, not the catalogue entry.
        assert!(
            !out.iter()
                .any(|m| m.id == on_bag.id || m.id == "niche-zero" || m.id == "niche-duo")
        );
        assert_eq!(
            out.last().map(|m| (m.kind, m.id.as_str())),
            Some((GrinderMatchKind::Archived, old.id.as_str()))
        );
    }

    #[test]
    fn a_blank_query_lists_everything_in_order() {
        let gs = vec![grinder("grinder:b", "B"), grinder("grinder:a", "A")];
        let out = match_grinders("  ", &gs, &[]);
        assert_eq!(
            &ids(&out)[..2],
            [
                (GrinderMatchKind::Owned, "grinder:b"),
                (GrinderMatchKind::Owned, "grinder:a")
            ]
        );
        // Listed entries only: hidden ones are never offered.
        let listed = GRINDER_CATALOGUE.iter().filter(|e| e.listed()).count();
        assert_eq!(out.len(), 2 + listed);
        assert_eq!(out[2].id, GRINDER_CATALOGUE[0].id);
        assert!(match_grinders("j-ultra", &[], &[]).is_empty());
        let json: Vec<GrinderMatch> = serde_json::from_str(
            &match_grinders_json("", &serde_json::to_string(&gs).unwrap(), "nope").unwrap(),
        )
        .unwrap();
        assert_eq!(json, out);
    }
}

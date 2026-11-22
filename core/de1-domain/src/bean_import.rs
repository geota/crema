//! # bean-import
//!
//! How an imported bean library (a Crema `crema/v1` export or a Beanconqueror
//! plan) merges into the local one — one rule both shells call, so a file
//! imports the same way on web and Android. Sans-IO: the shells parse the file
//! through the core importers, call [`merge_bean_import`], then write the
//! result to their stores and land any shots through `bean_id_remap`.

use crate::bag_grind::BagGrind;
use crate::bean::{Bean, Roaster};
use crate::grinder::{Grinder, clean_grinder_name, grinder_named};
use serde::{Deserialize, Serialize};
use std::collections::{HashMap, HashSet};
use typeshare::typeshare;

/// What an import changes in the library.
#[typeshare]
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BeanImportMerge {
    /// Beans whose id the library doesn't have, roaster pointers resolved.
    pub new_beans: Vec<Bean>,
    /// Incoming beans that replace the library row with the same id (newer
    /// `updated_at`), roaster pointers resolved.
    pub updated_beans: Vec<Bean>,
    /// Roasters the new and updated beans need that the library doesn't have.
    pub new_roasters: Vec<Roaster>,
    /// Incoming roasters that replace the library row with the same id.
    pub updated_roasters: Vec<Roaster>,
    /// Incoming grinder records (#488) the library doesn't have, by id or by
    /// name. Not deleted ones: there's nothing to bring.
    pub new_grinders: Vec<Grinder>,
    /// Incoming grinder records that replace the library row with the same
    /// id (newer `updated_at`, as for roasters).
    pub updated_grinders: Vec<Grinder>,
    /// Incoming bean id → the library bean id its shots should reference.
    pub bean_id_remap: HashMap<String, String>,
    /// Incoming beans that change nothing: already in the library and not
    /// newer, an earlier Beanconqueror import, or a (roaster, name, roast
    /// date) duplicate.
    pub skipped: u32,
}

/// When a library row last changed, its tombstone included.
fn local_stamp(updated_at: i64, deleted_at: Option<i64>) -> i64 {
    deleted_at.map_or(updated_at, |d| d.max(updated_at))
}

/// Merge an import's beans and roasters into the library.
///
/// For each incoming bean, in order:
///
/// 1. **Same id** as a library bean (tombstones included): the newer wins. The
///    incoming row replaces the library one only when its `updated_at` is
///    later than the library row's last change (`updated_at`, or `deleted_at`
///    for a tombstone); a tie keeps the library row. So an older incoming row
///    never resurrects a bean deleted here.
/// 2. **Same `beanconqueror_id`** as a live library bean: skipped (an earlier,
///    possibly interrupted, Beanconqueror import of the same file).
/// 3. Its roaster is **repointed** to a live library roaster of the same name
///    (case-insensitive), so the import doesn't add a duplicate roaster.
/// 4. **Same (roaster, name, roast date)** as a live library bean: skipped
///    (a hand-entered bag that matches the import).
/// 5. Otherwise it is new.
///
/// Incoming roasters are added only when a new or updated bean points at them;
/// one with an id the library has follows the same newer-wins rule. Skipped
/// beans remap to the library row they matched, kept beans to themselves.
///
/// **Grinder records** (#488) merge by id like roasters (newer wins, a
/// tombstone here is not resurrected by an older row); an incoming record
/// whose id is new but whose name names a library grinder
/// ([`crate::grinder::grinder_named`]: "niche" is the Niche Zero) is that
/// grinder, and the kept beans' saved grinds are repointed to it. A bag that
/// then has two rows for one grinder id (it carried both ids) keeps the row
/// that already had the library's id, starred if either was (PLAN
/// follow-up 16): one row per grinder id always comes out.
///
/// **Saved grinds merge by grinder id (PLAN follow-up 17):** they are
/// per-device knowledge, so a newer incoming bag doesn't replace them
/// wholesale. Its rows win per grinder id; a row only the library has is
/// kept. The star is the incoming one when it has one, else the library's
/// (on a row the incoming bag doesn't have). An updated bean whose `grinds`
/// is `None` (written by an app from before #488) keeps the library's
/// `grinds` (review F7).
#[must_use]
pub fn merge_bean_import(
    incoming_beans: &[Bean],
    incoming_roasters: &[Roaster],
    library_beans: &[Bean],
    library_roasters: &[Roaster],
) -> BeanImportMerge {
    merge_library_import(
        incoming_beans,
        incoming_roasters,
        &[],
        library_beans,
        library_roasters,
        &[],
    )
}

/// [`merge_bean_import`] with grinder records: see its docs.
#[must_use]
pub fn merge_library_import(
    incoming_beans: &[Bean],
    incoming_roasters: &[Roaster],
    incoming_grinders: &[Grinder],
    library_beans: &[Bean],
    library_roasters: &[Roaster],
    library_grinders: &[Grinder],
) -> BeanImportMerge {
    let live_beans = || library_beans.iter().filter(|b| b.deleted_at.is_none());
    let library_bean_by_id: HashMap<&str, &Bean> =
        library_beans.iter().map(|b| (b.id.as_str(), b)).collect();
    let library_bean_by_bc_id: HashMap<&str, &Bean> = live_beans()
        .filter_map(|b| b.beanconqueror_id.as_deref().map(|id| (id, b)))
        .collect();
    let library_roaster_by_name: HashMap<String, &Roaster> = library_roasters
        .iter()
        .filter(|r| r.deleted_at.is_none())
        .map(|r| (r.name.trim().to_lowercase(), r))
        .collect();
    let incoming_roaster_by_id: HashMap<&str, &Roaster> = incoming_roasters
        .iter()
        .map(|r| (r.id.as_str(), r))
        .collect();

    let mut out = BeanImportMerge::default();

    // Grinder records, by id, else by name.
    let library_grinder_by_id: HashMap<&str, &Grinder> = library_grinders
        .iter()
        .map(|g| (g.id.as_str(), g))
        .collect();
    let mut grinder_remap: HashMap<String, String> = HashMap::new();
    for g in incoming_grinders {
        // Stored with a clean name (REVIEW-513 follow-up 1).
        let cleaned = || {
            let mut g = g.clone();
            g.name = clean_grinder_name(&g.name);
            g
        };
        if let Some(local) = library_grinder_by_id.get(g.id.as_str()) {
            if g.updated_at > local_stamp(local.updated_at, local.deleted_at) {
                out.updated_grinders.push(cleaned());
            }
        } else if let Some(local) = grinder_named(library_grinders, &g.name) {
            grinder_remap.insert(g.id.clone(), local.id.clone());
        } else if g.deleted_at.is_none() && !out.new_grinders.iter().any(|n| n.id == g.id) {
            out.new_grinders.push(cleaned());
        }
    }

    let mut needed_roasters: HashSet<&str> = HashSet::new();
    for bean in incoming_beans {
        // Roaster resolution, shared by new and updated beans.
        let incoming_roaster = bean
            .roaster_id
            .as_deref()
            .and_then(|id| incoming_roaster_by_id.get(id).copied());
        let library_roaster = incoming_roaster.and_then(|r| {
            library_roaster_by_name
                .get(&r.name.trim().to_lowercase())
                .copied()
        });
        let mut resolved = bean.clone();
        resolved.grinds = resolved
            .grinds
            .as_deref()
            .map(|rows| remap_grinds(rows, &grinder_remap));
        match (incoming_roaster, library_roaster) {
            (Some(inc), Some(lib)) if inc.id != lib.id => {
                resolved.roaster_id = Some(lib.id.clone())
            }
            (Some(inc), _) => {
                needed_roasters.insert(inc.id.as_str());
            }
            (None, _) => {}
        }

        if let Some(local) = library_bean_by_id.get(bean.id.as_str()) {
            out.bean_id_remap.insert(bean.id.clone(), local.id.clone());
            if bean.updated_at > local_stamp(local.updated_at, local.deleted_at) {
                resolved.grinds = match (resolved.grinds.as_deref(), local.grinds.as_deref()) {
                    (None, _) => local.grinds.clone(),
                    (Some(incoming), Some(here)) => Some(merge_imported_grinds(incoming, here)),
                    (Some(_), None) => resolved.grinds,
                };
                out.updated_beans.push(resolved);
            } else {
                out.skipped += 1;
            }
            continue;
        }
        if let Some(prior) = bean
            .beanconqueror_id
            .as_deref()
            .and_then(|id| library_bean_by_bc_id.get(id))
        {
            out.bean_id_remap.insert(bean.id.clone(), prior.id.clone());
            out.skipped += 1;
            continue;
        }
        let triple = library_roaster.and_then(|lib| {
            live_beans().find(|b| {
                b.roaster_id.as_deref() == Some(lib.id.as_str())
                    && b.name.trim().to_lowercase() == bean.name.trim().to_lowercase()
                    && b.roasted_on == bean.roasted_on
            })
        });
        if let Some(dupe) = triple {
            out.bean_id_remap.insert(bean.id.clone(), dupe.id.clone());
            out.skipped += 1;
            continue;
        }
        out.bean_id_remap.insert(bean.id.clone(), bean.id.clone());
        out.new_beans.push(resolved);
    }

    // A roaster pointed at only by skipped beans is not needed.
    let kept: HashSet<&str> = out
        .new_beans
        .iter()
        .chain(&out.updated_beans)
        .filter_map(|b| b.roaster_id.as_deref())
        .collect();
    let library_roaster_by_id: HashMap<&str, &Roaster> = library_roasters
        .iter()
        .map(|r| (r.id.as_str(), r))
        .collect();
    for roaster in incoming_roasters {
        let id = roaster.id.as_str();
        if !needed_roasters.contains(id) || !kept.contains(id) {
            continue;
        }
        match library_roaster_by_id.get(id) {
            None => out.new_roasters.push(roaster.clone()),
            Some(local) if roaster.updated_at > local_stamp(local.updated_at, local.deleted_at) => {
                out.updated_roasters.push(roaster.clone());
            }
            Some(_) => {}
        }
    }

    out
}

/// A bag's incoming rows with grinder ids repointed through `remap`, one
/// row per id (PLAN follow-up 16): when a repointed row lands on an id the
/// bag already has a row for, the row that had that id is kept (it was saved
/// for this library's grinder) and is starred if either was. Otherwise the
/// first row per id wins. At most one star comes out.
fn remap_grinds(rows: &[BagGrind], remap: &HashMap<String, String>) -> Vec<BagGrind> {
    let mut out: Vec<BagGrind> = Vec::new();
    let mut from_remap: Vec<bool> = Vec::new();
    for row in rows {
        let to = remap.get(&row.grinder_id);
        let mut row = row.clone();
        if let Some(to) = to {
            row.grinder_id.clone_from(to);
        }
        match out.iter().position(|r| r.grinder_id == row.grinder_id) {
            Some(i) => {
                let starred = out[i].starred || row.starred;
                if from_remap[i] && to.is_none() {
                    out[i] = row;
                    from_remap[i] = false;
                }
                out[i].starred = starred;
            }
            None => {
                out.push(row);
                from_remap.push(to.is_some());
            }
        }
    }
    one_star(&mut out);
    out
}

/// At most one starred row: the first stays starred.
fn one_star(rows: &mut [BagGrind]) {
    let mut seen = false;
    for r in rows {
        if r.starred {
            r.starred = !seen;
            seen = true;
        }
    }
}

/// A newer incoming bag's saved grinds merged onto the library's (PLAN
/// follow-up 17): by grinder id, incoming rows win, rows only the library
/// has are kept (after the incoming ones). The incoming star wins when it
/// has one; else the library's stays, on a row the incoming bag doesn't
/// have (an incoming row that names the library's starred grinder without a
/// star is the newer word).
fn merge_imported_grinds(incoming: &[BagGrind], here: &[BagGrind]) -> Vec<BagGrind> {
    let incoming_star = incoming.iter().any(|r| r.starred);
    let mut out = incoming.to_vec();
    for row in here {
        if out.iter().any(|r| r.grinder_id == row.grinder_id) {
            continue;
        }
        out.push(BagGrind {
            starred: row.starred && !incoming_star,
            ..row.clone()
        });
    }
    one_star(&mut out);
    out
}

/// JSON-bridged [`merge_library_import`]. Input: `{"incomingBeans": Bean[],
/// "incomingRoasters": Roaster[], "incomingGrinders": Grinder[],
/// "libraryBeans": Bean[], "libraryRoasters": Roaster[], "libraryGrinders":
/// Grinder[]}` (each defaults to empty); output: a [`BeanImportMerge`]. After
/// applying it, the shell runs the library migration
/// ([`crate::grinder::migrate_grinder_library`]) so imported bags from an
/// older app get their saved grinds.
///
/// # Errors
/// The JSON parse error string on malformed input.
pub fn merge_bean_import_json(payload: &str) -> Result<String, String> {
    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct In {
        #[serde(default)]
        incoming_beans: Vec<Bean>,
        #[serde(default)]
        incoming_roasters: Vec<Roaster>,
        #[serde(default)]
        library_beans: Vec<Bean>,
        #[serde(default)]
        library_roasters: Vec<Roaster>,
        #[serde(default)]
        incoming_grinders: Vec<Grinder>,
        #[serde(default)]
        library_grinders: Vec<Grinder>,
    }
    let inp: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    let merge = merge_library_import(
        &inp.incoming_beans,
        &inp.incoming_roasters,
        &inp.incoming_grinders,
        &inp.library_beans,
        &inp.library_roasters,
        &inp.library_grinders,
    );
    serde_json::to_string(&merge).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn bean(id: &str, name: &str, roaster: Option<&str>, updated_at: i64) -> Bean {
        let mut b = Bean::new(id.into(), name.into(), 0);
        b.roaster_id = roaster.map(str::to_owned);
        b.updated_at = updated_at;
        b
    }

    fn roaster(id: &str, name: &str, updated_at: i64) -> Roaster {
        let mut r = Roaster::new(id.into(), name.into(), 0);
        r.updated_at = updated_at;
        r
    }

    #[test]
    fn same_id_newer_incoming_replaces_the_library_row() {
        let local = bean("bean:1", "Gesha", None, 100);
        let incoming = bean("bean:1", "Gesha (renamed)", None, 200);
        let m = merge_bean_import(std::slice::from_ref(&incoming), &[], &[local], &[]);
        assert_eq!(m.updated_beans, vec![incoming]);
        assert!(m.new_beans.is_empty());
        assert_eq!(m.skipped, 0);
        assert_eq!(m.bean_id_remap["bean:1"], "bean:1");
    }

    #[test]
    fn same_id_older_or_tied_incoming_keeps_the_library_row() {
        let local = bean("bean:1", "Gesha (edited here)", None, 200);
        for stamp in [100, 200] {
            let incoming = bean("bean:1", "Gesha", None, stamp);
            let m = merge_bean_import(&[incoming], &[], std::slice::from_ref(&local), &[]);
            assert!(
                m.updated_beans.is_empty() && m.new_beans.is_empty(),
                "{stamp}"
            );
            assert_eq!(m.skipped, 1, "{stamp}");
            assert_eq!(m.bean_id_remap["bean:1"], "bean:1");
        }
    }

    #[test]
    fn a_tombstone_is_not_resurrected_by_an_older_incoming_row() {
        let mut deleted = bean("bean:1", "Gesha", None, 100);
        deleted.deleted_at = Some(300);
        // Newer than the last edit but older than the delete: stays deleted.
        let older = bean("bean:1", "Gesha", None, 250);
        let m = merge_bean_import(&[older], &[], &[deleted.clone()], &[]);
        assert!(m.updated_beans.is_empty() && m.new_beans.is_empty());
        assert_eq!(m.skipped, 1);
        // Tie with the delete: stays deleted.
        let tied = bean("bean:1", "Gesha", None, 300);
        assert!(
            merge_bean_import(&[tied], &[], &[deleted.clone()], &[])
                .updated_beans
                .is_empty()
        );
        // Edited after the delete: the newer row wins.
        let newer = bean("bean:1", "Gesha", None, 400);
        let m = merge_bean_import(std::slice::from_ref(&newer), &[], &[deleted], &[]);
        assert_eq!(m.updated_beans, vec![newer]);
    }

    #[test]
    fn beanconqueror_reimport_triple_dupes_and_roaster_repoint_hold() {
        let lib_roaster = roaster("roaster:lib", "Onyx", 0);
        let mut bc_prior = bean("bean:bc-prior", "Kenya", Some("roaster:lib"), 0);
        bc_prior.beanconqueror_id = Some("bc-1".into());
        let mut hand = bean("bean:hand", "Ethiopia", Some("roaster:lib"), 0);
        hand.roasted_on = Some("2026-09-01".into());
        let library = vec![bc_prior, hand];

        let wire_roaster = roaster("roaster:wire", " onyx ", 0);
        let mut bc_again = bean("bean:wire-1", "Kenya", Some("roaster:wire"), 0);
        bc_again.beanconqueror_id = Some("bc-1".into());
        let mut triple = bean("bean:wire-2", "ETHIOPIA", Some("roaster:wire"), 0);
        triple.roasted_on = Some("2026-09-01".into());
        let fresh = bean("bean:wire-3", "Colombia", Some("roaster:wire"), 0);
        let m = merge_bean_import(
            &[bc_again, triple, fresh],
            &[wire_roaster],
            &library,
            &[lib_roaster],
        );
        assert_eq!(m.skipped, 2);
        assert_eq!(m.bean_id_remap["bean:wire-1"], "bean:bc-prior");
        assert_eq!(m.bean_id_remap["bean:wire-2"], "bean:hand");
        assert_eq!(m.new_beans.len(), 1);
        assert_eq!(m.new_beans[0].roaster_id.as_deref(), Some("roaster:lib"));
        assert!(
            m.new_roasters.is_empty(),
            "the duplicate-name roaster is dropped"
        );
    }

    #[test]
    fn roasters_follow_the_beans_that_need_them() {
        let lib = roaster("roaster:1", "Sey", 100);
        let renamed = roaster("roaster:1", "Sey Coffee", 200);
        let other = roaster("roaster:2", "Tim Wendelboe", 0);
        let unused = roaster("roaster:3", "Nobody", 0);
        let a = bean("bean:a", "A", Some("roaster:1"), 0);
        let b = bean("bean:b", "B", Some("roaster:2"), 0);
        let m = merge_bean_import(
            &[a, b],
            &[renamed.clone(), other.clone(), unused],
            &[],
            std::slice::from_ref(&lib),
        );
        assert_eq!(m.new_roasters, vec![other]);
        assert_eq!(m.updated_roasters, vec![renamed]);
        // An older incoming roaster keeps the library one.
        let stale = roaster("roaster:1", "Old name", 50);
        let m = merge_bean_import(
            &[bean("bean:a", "A", Some("roaster:1"), 0)],
            &[stale],
            &[],
            &[lib],
        );
        assert!(m.new_roasters.is_empty() && m.updated_roasters.is_empty());
    }

    fn grinds(id: &str, grind: &str) -> Option<Vec<crate::bag_grind::BagGrind>> {
        Some(vec![crate::bag_grind::BagGrind {
            grinder_id: id.into(),
            grinder_name: "Niche Zero".into(),
            grind: grind.into(),
            starred: true,
        }])
    }

    #[test]
    fn a_newer_row_from_an_older_app_keeps_the_local_saved_grinds() {
        // F7: the incoming row has no `grinds` (written before #488).
        let mut local = bean("bean:1", "Gesha", None, 100);
        local.grinds = grinds("grinder:nz", "14");
        let incoming = bean("bean:1", "Gesha (renamed)", None, 200);
        let m = merge_bean_import(&[incoming], &[], std::slice::from_ref(&local), &[]);
        assert_eq!(m.updated_beans[0].name, "Gesha (renamed)");
        assert_eq!(m.updated_beans[0].grinds, local.grinds);
        // A row with no saved grinds doesn't wipe the library's (PLAN
        // follow-up 17, REVIEW-498 probe P2): they merge by grinder id.
        let mut cleared = bean("bean:1", "Gesha", None, 300);
        cleared.grinds = Some(Vec::new());
        let m = merge_bean_import(&[cleared], &[], std::slice::from_ref(&local), &[]);
        assert_eq!(m.updated_beans[0].grinds, local.grinds);
    }

    fn row(id: &str, grind: &str, starred: bool) -> BagGrind {
        BagGrind {
            grinder_id: id.into(),
            grinder_name: id.trim_start_matches("grinder:").into(),
            grind: grind.into(),
            starred,
        }
    }

    #[test]
    fn a_newer_bag_merges_its_saved_grinds_by_grinder_id() {
        let mut local = bean("bean:1", "Gesha", None, 100);
        local.grinds = Some(vec![
            row("grinder:nz", "14", true),
            row("grinder:ek", "8", false),
        ]);
        let mut incoming = bean("bean:1", "Gesha", None, 200);
        incoming.grinds = Some(vec![
            row("grinder:nz", "15", false),
            row("grinder:c40", "22", false),
        ]);
        let m = merge_bean_import(&[incoming], &[], std::slice::from_ref(&local), &[]);
        let got = m.updated_beans[0].grinds.clone().unwrap();
        assert_eq!(
            got,
            vec![
                row("grinder:nz", "15", false),
                row("grinder:c40", "22", false),
                row("grinder:ek", "8", false),
            ],
            "incoming wins per row (its unstarred Niche row too); the library's EK43 row stays"
        );
        // The incoming star wins; with none on its rows, the library's stays
        // on a row only the library has.
        let mut starred = bean("bean:1", "Gesha", None, 200);
        starred.grinds = Some(vec![row("grinder:c40", "22", true)]);
        let mut here = local.clone();
        here.grinds = Some(vec![row("grinder:ek", "8", true)]);
        let got = merge_bean_import(&[starred], &[], std::slice::from_ref(&here), &[])
            .updated_beans[0]
            .grinds
            .clone()
            .unwrap();
        assert_eq!(
            got,
            vec![
                row("grinder:c40", "22", true),
                row("grinder:ek", "8", false)
            ]
        );
        let mut plain = bean("bean:1", "Gesha", None, 200);
        plain.grinds = Some(vec![row("grinder:c40", "22", false)]);
        let got = merge_bean_import(&[plain], &[], &[here], &[]).updated_beans[0]
            .grinds
            .clone()
            .unwrap();
        assert_eq!(
            got,
            vec![
                row("grinder:c40", "22", false),
                row("grinder:ek", "8", true)
            ]
        );
        // An older incoming bag changes nothing.
        let mut older = bean("bean:1", "Gesha", None, 50);
        older.grinds = Some(vec![row("grinder:c40", "22", false)]);
        let m = merge_bean_import(&[older], &[], &[local], &[]);
        assert!(m.updated_beans.is_empty());
        assert_eq!(m.skipped, 1);
    }

    #[test]
    fn a_remap_never_leaves_two_rows_for_one_grinder_id() {
        // REVIEW-498 probe P1: the incoming bag carries both the other
        // device's id and this library's, and the incoming record's name is
        // the library's grinder.
        let lib = vec![grinder("grinder:niche-zero", "Niche Zero", 100)];
        let incoming = vec![grinder("grinder:nz-other", "Niche Zero", 100)];
        let mut b = bean("bean:new", "Kenya", None, 0);
        b.grinds = Some(vec![
            row("grinder:nz-other", "15", true),
            row("grinder:niche-zero", "14", false),
        ]);
        let m = merge_library_import(&[b], &[], &incoming, &[], &[], &lib);
        assert_eq!(
            m.new_beans[0].grinds.clone().unwrap(),
            vec![BagGrind {
                grinder_name: "niche-zero".into(),
                ..row("grinder:niche-zero", "14", true)
            }],
            "the row that had the library's id is kept, with the star"
        );
        // Same with the library row first; and on an updated bag merged
        // with the library's own rows.
        let mut b = bean("bean:1", "Kenya", None, 200);
        b.grinds = Some(vec![
            row("grinder:niche-zero", "14", false),
            row("grinder:nz-other", "15", false),
        ]);
        let mut here = bean("bean:1", "Kenya", None, 100);
        here.grinds = Some(vec![row("grinder:niche-zero", "12", true)]);
        let m = merge_library_import(&[b], &[], &incoming, &[here], &[], &lib);
        let got = m.updated_beans[0].grinds.clone().unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0].grind, "14");
    }

    fn grinder(id: &str, name: &str, updated_at: i64) -> Grinder {
        Grinder {
            id: id.into(),
            name: name.into(),
            updated_at,
            ..Grinder::default()
        }
    }

    #[test]
    fn grinder_records_merge_by_id_then_by_name_and_bag_grinds_follow() {
        let lib = vec![
            grinder("grinder:ek43", "EK43", 100),
            grinder("grinder:niche-zero", "Niche Zero", 100),
        ];
        let incoming = vec![
            grinder("grinder:ek43", "EK43 (renamed)", 200),
            grinder("grinder:niche", "niche", 50),
            grinder("grinder:c40", "Comandante C40", 50),
            Grinder {
                deleted_at: Some(9),
                ..grinder("grinder:gone", "Gone", 50)
            },
        ];
        let mut b = bean("bean:new", "Kenya", None, 0);
        b.grinds = grinds("grinder:niche", "14");
        let m = merge_library_import(&[b], &[], &incoming, &[], &[], &lib);
        assert_eq!(m.updated_grinders, [incoming[0].clone()]);
        assert_eq!(m.new_grinders, [incoming[2].clone()]);
        assert_eq!(
            m.new_beans[0].grinds.as_deref().unwrap()[0].grinder_id,
            "grinder:niche-zero",
            "the incoming 'niche' is the library's Niche Zero"
        );
        // An older or tied incoming row keeps the library's; a tombstone here
        // isn't resurrected by an older row.
        let mut dead = grinder("grinder:ek43", "EK43", 100);
        dead.deleted_at = Some(300);
        let m = merge_library_import(
            &[],
            &[],
            &[grinder("grinder:ek43", "EK43", 250)],
            &[],
            &[],
            &[dead],
        );
        assert!(m.updated_grinders.is_empty() && m.new_grinders.is_empty());
    }

    /// REVIEW-513 follow-up 1: an imported grinder's name is stored clean.
    #[test]
    fn imported_grinder_names_lose_their_control_characters() {
        let lib = vec![grinder("grinder:ek43", "EK43", 100)];
        let incoming = vec![
            grinder("grinder:ek43", "EK43\nshop", 200),
            grinder("grinder:c40", "Comandante\r\nC40", 50),
        ];
        let m = merge_library_import(&[], &[], &incoming, &[], &[], &lib);
        assert_eq!(m.updated_grinders[0].name, "EK43 shop");
        assert_eq!(m.new_grinders[0].name, "Comandante C40");
    }

    #[test]
    fn merge_bean_import_json_round_trips() {
        let payload = serde_json::json!({
            "incomingBeans": [bean("bean:1", "Gesha", None, 200)],
            "libraryBeans": [bean("bean:1", "Gesha", None, 100)],
        });
        let out: BeanImportMerge =
            serde_json::from_str(&merge_bean_import_json(&payload.to_string()).unwrap()).unwrap();
        assert_eq!(out.updated_beans.len(), 1);
        assert!(merge_bean_import_json("nope").is_err());
    }
}

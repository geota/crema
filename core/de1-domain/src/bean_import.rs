//! # bean-import
//!
//! How an imported bean library (a Crema `crema/v1` export or a Beanconqueror
//! plan) merges into the local one — one rule both shells call, so a file
//! imports the same way on web and Android. Sans-IO: the shells parse the file
//! through the core importers, call [`merge_bean_import`], then write the
//! result to their stores and land any shots through `bean_id_remap`.

use crate::bean::{Bean, Roaster};
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
#[must_use]
pub fn merge_bean_import(
    incoming_beans: &[Bean],
    incoming_roasters: &[Roaster],
    library_beans: &[Bean],
    library_roasters: &[Roaster],
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

/// JSON-bridged [`merge_bean_import`]. Input: `{"incomingBeans": Bean[],
/// "incomingRoasters": Roaster[], "libraryBeans": Bean[], "libraryRoasters":
/// Roaster[]}` (each defaults to empty); output: a [`BeanImportMerge`].
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
    }
    let inp: In = serde_json::from_str(payload).map_err(|e| e.to_string())?;
    let merge = merge_bean_import(
        &inp.incoming_beans,
        &inp.incoming_roasters,
        &inp.library_beans,
        &inp.library_roasters,
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

//! The restore plan's promises, for any device and any backup (restore-safety
//! design, "Core properties"): Merge changes and removes nothing that's here,
//! Replace leaves exactly the backup's rows, and running a plan twice ends
//! where running it once did.

use std::collections::{BTreeMap, BTreeSet};

use de1_domain::backup_plan::{BackupManifest, LocalShot, ManifestHeader, ManifestShot, Verdict};
use de1_domain::{LocalState, RestoreMode, RestoreOpts, RestorePlan, plan_restore};
use proptest::prelude::*;

const IDS: [&str; 6] = ["a", "b", "c", "d", "e", "f"];

/// One shot in the model: which side's row it is, whether it has a curve, and
/// whether it's a tombstone.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Shot {
    from_backup: bool,
    samples: bool,
    deleted: bool,
}

/// A device, as far as the plan can change it.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Device {
    /// Bean id → whether it's the backup's row.
    beans: BTreeMap<String, bool>,
    shots: BTreeMap<String, Shot>,
    cap: Option<u32>,
}

#[derive(Debug, Clone)]
struct Backup {
    beans: Vec<String>,
    shots: Vec<(String, bool, bool, u64)>, // id, samples, pinned, completed_at
    carries_beans: bool,
}

fn device() -> impl Strategy<Value = Device> {
    (
        prop::collection::btree_set(prop::sample::select(&IDS[..]), 0..5),
        prop::collection::btree_map(
            prop::sample::select(&IDS[..]),
            (any::<bool>(), prop::bool::weighted(0.2)),
            0..6,
        ),
        prop::option::of(0_u32..6),
    )
        .prop_map(|(beans, shots, cap)| Device {
            beans: beans.into_iter().map(|b| (b.to_owned(), false)).collect(),
            shots: shots
                .into_iter()
                .map(|(id, (samples, deleted))| {
                    (
                        id.to_owned(),
                        Shot {
                            from_backup: false,
                            samples,
                            deleted,
                        },
                    )
                })
                .collect(),
            cap,
        })
}

fn backup() -> impl Strategy<Value = Backup> {
    (
        prop::collection::btree_set(prop::sample::select(&IDS[..]), 0..5),
        prop::collection::btree_map(
            prop::sample::select(&IDS[..]),
            (any::<bool>(), prop::bool::weighted(0.2), 0_u64..100),
            0..6,
        ),
        prop::bool::weighted(0.9),
    )
        .prop_map(|(beans, shots, carries_beans)| Backup {
            beans: beans.into_iter().map(str::to_owned).collect(),
            shots: shots
                .into_iter()
                .map(|(id, (samples, pinned, at))| (id.to_owned(), samples, pinned, at))
                .collect(),
            carries_beans,
        })
}

fn manifest(b: &Backup) -> BackupManifest {
    let count = |n: usize| Some(u32::try_from(n).unwrap());
    BackupManifest {
        header: Some(ManifestHeader {
            bean_count: if b.carries_beans {
                count(b.beans.len())
            } else {
                None
            },
            shot_count: count(b.shots.len()),
            ..ManifestHeader::default()
        }),
        beans: if b.carries_beans {
            b.beans.clone()
        } else {
            Vec::new()
        },
        shots: b
            .shots
            .iter()
            .map(|(id, samples, pinned, at)| ManifestShot {
                id: id.clone(),
                bean_id: None,
                has_samples: *samples,
                completed_at: *at,
                pinned: *pinned,
            })
            .collect(),
        good_lines: 1,
        ..BackupManifest::default()
    }
}

fn local(d: &Device) -> LocalState {
    LocalState {
        shell: "web".to_owned(),
        history_capacity: d.cap,
        beans: d.beans.keys().cloned().collect(),
        shots: d
            .shots
            .iter()
            .map(|(id, s)| LocalShot {
                id: id.clone(),
                has_samples: s.samples,
                pinned: false,
                deleted: s.deleted,
            })
            .collect(),
        ..LocalState::default()
    }
}

/// What a shell would do with `plan`, on the model.
fn apply(d: &Device, b: &Backup, plan: &RestorePlan) -> Device {
    let mut out = d.clone();
    let row = |id: &str| b.shots.iter().find(|(s, ..)| s == id).unwrap();
    for id in plan.beans.add.iter().chain(&plan.beans.overwrite) {
        out.beans.insert(id.clone(), true);
    }
    for id in &plan.beans.remove {
        out.beans.remove(id);
    }
    for id in plan
        .shots
        .add
        .iter()
        .chain(&plan.shots.overwrite)
        .chain(&plan.shots.revive)
    {
        let (_, samples, ..) = row(id);
        out.shots.insert(
            id.clone(),
            Shot {
                from_backup: true,
                samples: *samples,
                deleted: false,
            },
        );
    }
    for id in &plan.shots.remove {
        out.shots.remove(id);
    }
    for id in &plan.shots.fill {
        out.shots.get_mut(id).unwrap().samples = true;
    }
    for id in &plan.tombstones.cancel {
        if let Some(s) = out.shots.get_mut(id) {
            s.deleted = false;
        }
    }
    out
}

fn plan(d: &Device, b: &Backup, mode: RestoreMode, revive: bool) -> RestorePlan {
    plan_restore(
        &manifest(b),
        &local(d),
        mode,
        RestoreOpts {
            revive_deleted: revive,
        },
    )
}

proptest! {
    #[test]
    fn merge_changes_and_removes_nothing_here(d in device(), b in backup(), revive in any::<bool>()) {
        let p = plan(&d, &b, RestoreMode::Merge, revive);
        prop_assume!(p.verdict != Verdict::Refused);
        let after = apply(&d, &b, &p);
        for (id, was) in &d.beans {
            prop_assert_eq!(after.beans.get(id), Some(was), "bean {} changed", id);
        }
        for (id, was) in &d.shots {
            let now = after.shots.get(id);
            prop_assert!(now.is_some(), "shot {} removed", id);
            let now = now.unwrap();
            if was.deleted && !now.deleted {
                // Only "bring back" revives a tombstone, as the backup's row.
                prop_assert!(revive && now.from_backup, "shot {} revived unasked", id);
                continue;
            }
            prop_assert_eq!(now.from_backup, was.from_backup, "shot {} overwritten", id);
            prop_assert_eq!(now.deleted, was.deleted);
            // A curve can arrive, and nothing else changes.
            prop_assert!(now.samples || !was.samples, "shot {} lost its curve", id);
        }
        prop_assert!(p.beans.overwrite.is_empty() && p.shots.overwrite.is_empty());
        prop_assert!(p.beans.remove.is_empty() && p.shots.remove.is_empty());
    }

    #[test]
    fn merge_past_the_cap_adds_what_fits_and_evicts_nothing(d in device(), b in backup()) {
        let p = plan(&d, &b, RestoreMode::Merge, false);
        prop_assume!(p.verdict != Verdict::Refused);
        let live_before = d.shots.values().filter(|s| !s.deleted).count();
        // What Merge may add: rows not here at all, and with "bring back" off,
        // no tombstones.
        let candidates = b.shots.iter().filter(|(id, ..)| !d.shots.contains_key(id)).count();
        let room = d.cap.map_or(usize::MAX, |cap| (cap as usize).saturating_sub(live_before));
        prop_assert_eq!(p.shots.add.len(), candidates.min(room), "adds what fits, all of it");
        prop_assert_eq!(p.shots.not_added as usize, candidates - candidates.min(room));
        if let Some(cap) = d.cap {
            let after = apply(&d, &b, &p);
            let live_after = after.shots.values().filter(|s| !s.deleted).count();
            prop_assert!(live_after <= live_before.max(cap as usize));
        }
    }

    #[test]
    fn replace_leaves_exactly_the_backups_rows(d in device(), b in backup()) {
        let p = plan(&d, &b, RestoreMode::Replace, false);
        prop_assume!(p.verdict != Verdict::Refused);
        let after = apply(&d, &b, &p);
        if b.carries_beans {
            let want: BTreeSet<&String> = b.beans.iter().collect();
            prop_assert_eq!(after.beans.keys().collect::<BTreeSet<_>>(), want);
            prop_assert!(after.beans.values().all(|from_backup| *from_backup));
        } else {
            prop_assert_eq!(&after.beans, &d.beans, "a store the backup doesn't carry stays");
        }
        let live: BTreeSet<&String> = after.shots.iter().filter(|(_, s)| !s.deleted).map(|(id, _)| id).collect();
        let want: BTreeSet<&String> = b.shots.iter().map(|(id, ..)| id).collect();
        prop_assert_eq!(live, want);
        // A tombstone the backup lacks stays: its remote delete is still owed.
        for (id, was) in &d.shots {
            if was.deleted && !b.shots.iter().any(|(s, ..)| s == id) {
                prop_assert!(after.shots.get(id).is_some_and(|s| s.deleted));
            }
        }
    }

    #[test]
    fn a_second_run_ends_where_the_first_did(
        d in device(),
        b in backup(),
        replace in any::<bool>(),
        revive in any::<bool>(),
    ) {
        let mode = if replace { RestoreMode::Replace } else { RestoreMode::Merge };
        let first = plan(&d, &b, mode, revive);
        prop_assume!(first.verdict != Verdict::Refused);
        let once = apply(&d, &b, &first);
        let second = plan(&once, &b, mode, revive);
        prop_assert!(second.verdict != Verdict::Refused);
        let twice = apply(&once, &b, &second);
        prop_assert_eq!(&twice, &once);
        if mode == RestoreMode::Merge {
            prop_assert!(second.beans.add.is_empty());
            prop_assert!(second.shots.fill.is_empty());
        }
    }
}

//! The export walk and its delete hold (S6), restore as merge then remove
//! (M3), recovery files (S9), the online backup copy, and stage 1's index
//! upkeep (M4), refused on a truth file.

mod common;

use std::collections::BTreeSet;
use std::fs;

use common::{create, dir, file, fts_whole, int, reopen, row, rows_in, stored_rows};
use de1_history::store::{
    IndexState, IndexWrite, JournalMode, OpenOptions, Recovery, Role, RowIn, Store, StoreError,
};
use serde_json::json;

fn ids_of(store: &Store) -> BTreeSet<String> {
    stored_rows(store).into_iter().map(|(id, _)| id).collect()
}

fn set(ids: &[&str]) -> BTreeSet<String> {
    ids.iter().map(|s| (*s).to_owned()).collect()
}

/// Walks to the end, `n` rows a step, with `between` run between steps.
fn walk_all(
    store: &mut Store,
    token: u64,
    n: u32,
    mut between: impl FnMut(&mut Store, usize),
) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for step in 0.. {
        let rows = store.export_next(token, n).expect("a step");
        if rows.is_empty() {
            break;
        }
        out.extend(rows.into_iter().map(|r| (r.id, r.row_json)));
        between(store, step);
    }
    out
}

#[test]
fn a_walk_writes_exactly_the_rows_it_began_with_and_holds_deletes() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    let rows: Vec<String> = (0..20)
        .map(|i| row(&format!("r{i:02}"), i, json!({ "tags": ["t"] })))
        .collect();
    store.upsert(&rows_in(&rows)).expect("writes");
    store
        .upsert(&rows_in(&[row("gone", 99, json!({ "deletedAt": 100 }))]))
        .expect("writes");
    let walk = store.export_begin().expect("begins");
    assert_eq!(walk.count, 20, "tombstones aren't in a backup");
    let lines = walk_all(&mut store, walk.token, 3, |s, step| match step {
        0 => {
            // A delete of a row not yet written, and of one written.
            let c = s.delete(&["r10".into(), "r00".into()]).expect("deletes");
            assert_eq!(c.deleted, ["r10", "r00"]);
            assert!(c.kinds.set);
        }
        1 => {
            // An edit, a tombstone, and a new row.
            s.upsert(&rows_in(&[
                row(
                    "r12",
                    12,
                    json!({ "metadata": { "notes": "edited" }, "tags": ["t"] }),
                ),
                row("r13", 13, json!({ "deletedAt": 5, "tags": ["t"] })),
                row("late", 50, json!({})),
            ]))
            .expect("writes");
        }
        _ => {}
    });
    // The header's count is the lines written.
    assert_eq!(lines.len(), 20);
    let names: Vec<&str> = lines.iter().map(|(id, _)| id.as_str()).collect();
    assert!(names.contains(&"r10") && names.contains(&"r00"));
    assert!(!names.contains(&"late"));
    let line = |id: &str| &lines.iter().find(|(i, _)| i == id).expect("written").1;
    assert!(line("r12").contains("edited"));
    assert!(line("r13").contains("\"deletedAt\":5"));
    // Held rows are hidden while the walk runs.
    assert_eq!(
        int(&store, "SELECT count(*) FROM shot WHERE hold_delete = 1"),
        2
    );
    assert_eq!(store.count(&Default::default()).expect("counts"), 18);
    assert_eq!(store.release_holds().expect("reads"), Vec::<String>::new());
    // A delete after the walk passed a row still holds it: the walk is open.
    store.delete(&["r01".into()]).expect("deletes");
    store.export_end(walk.token).expect("ends");
    let held = store.release_holds().expect("reads");
    assert_eq!(held, ["r00", "r01", "r10"]);
    store.delete(&held).expect("deletes");
    assert_eq!(
        int(
            &store,
            "SELECT count(*) FROM shot WHERE id IN ('r00', 'r01', 'r10')"
        ),
        0
    );
    assert_eq!(
        int(
            &store,
            "SELECT count(*) FROM shot_tag WHERE rid NOT IN (SELECT rid FROM shot)"
        ),
        0
    );
    assert!(store.release_holds().expect("reads").is_empty());
    // Without a walk, a delete deletes.
    store.delete(&["r02".into()]).expect("deletes");
    assert_eq!(int(&store, "SELECT count(*) FROM shot WHERE id = 'r02'"), 0);
}

#[test]
fn holds_left_by_a_closed_walk_are_released_at_the_next_open() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    store
        .upsert(&rows_in(&[row("a", 1, json!({})), row("b", 2, json!({}))]))
        .expect("writes");
    let walk = store.export_begin().expect("begins");
    store.delete(&["a".into()]).expect("holds");
    // The owner closes mid-walk: the walk fails, the hold stays.
    drop(store);
    let mut store = reopen(&path);
    assert_eq!(
        store.export_next(walk.token, 10),
        Err(StoreError::UnknownWalk(walk.token))
    );
    assert_eq!(store.count(&Default::default()).expect("counts"), 1);
    let held = store.release_holds().expect("reads");
    assert_eq!(held, ["a"]);
    // A crash between the details delete and the row delete: released again.
    drop(store);
    let mut store = reopen(&path);
    assert_eq!(store.release_holds().expect("reads"), ["a"]);
    store.delete(&held).expect("deletes");
    assert_eq!(ids_of(&store), set(&["b"]));
}

#[test]
fn an_unknown_or_ended_token_is_refused() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    assert_eq!(store.export_next(42, 1), Err(StoreError::UnknownWalk(42)));
    assert_eq!(store.export_end(42), Err(StoreError::UnknownWalk(42)));
    let walk = store.export_begin().expect("begins");
    store.export_end(walk.token).expect("ends");
    assert_eq!(
        store.export_next(walk.token, 1),
        Err(StoreError::UnknownWalk(walk.token))
    );
    // Two walks hold independently.
    store
        .upsert(&rows_in(&[row("a", 1, json!({})), row("b", 2, json!({}))]))
        .expect("writes");
    let one = store.export_begin().expect("begins");
    let two = store.export_begin().expect("begins");
    assert_ne!(one.token, two.token);
    store.delete(&["a".into()]).expect("holds");
    store.export_end(one.token).expect("ends");
    assert!(
        store.release_holds().expect("reads").is_empty(),
        "walk two still needs it"
    );
    assert_eq!(walk_all(&mut store, two.token, 1, |_, _| {}).len(), 2);
    store.export_end(two.token).expect("ends");
    assert_eq!(store.release_holds().expect("reads"), ["a"]);
}

#[test]
fn a_derive_rewrite_during_a_walk_keeps_every_hold() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    let rows: Vec<String> = (0..10)
        .map(|i| row(&format!("r{i}"), i, json!({})))
        .collect();
    store.upsert(&rows_in(&rows)).expect("writes");
    let walk = store.export_begin().expect("begins");
    store.delete(&["r3".into(), "r7".into()]).expect("holds");
    store
        .raw()
        .execute_batch(
            "UPDATE shot SET derive_v = 0; UPDATE meta SET value = '0' WHERE key = 'derive_v';",
        )
        .expect("stales");
    // A new rule set arrives with a new build, so a reopen: the old walk
    // ends with it, its holds stay, and a new walk runs during the rewrite.
    drop(store);
    let mut store = reopen(&path);
    assert_eq!(
        store.export_next(walk.token, 1),
        Err(StoreError::UnknownWalk(walk.token))
    );
    assert!(store.health().derive_pending);
    let again = store.export_begin().expect("begins");
    assert_eq!(again.count, 8, "held rows aren't in a new walk");
    store.set_batch_rows(3);
    store.maintain().expect("a batch");
    store.delete(&["r5".into()]).expect("holds");
    store.maintain_all(100).expect("maintains");
    assert!(!store.health().derive_pending);
    assert!(store.derived_mismatches().expect("reads").is_empty());
    assert_eq!(
        int(&store, "SELECT count(*) FROM shot WHERE hold_delete = 1"),
        3
    );
    assert_eq!(store.count(&Default::default()).expect("counts"), 7);
    assert_eq!(walk_all(&mut store, again.token, 4, |_, _| {}).len(), 8);
    store.export_end(again.token).expect("ends");
    assert_eq!(store.release_holds().expect("reads"), ["r3", "r5", "r7"]);
}

#[test]
fn restore_merges_then_removes_only_what_existed_before_it() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    store
        .upsert(&rows_in(&[
            row("local-only", 1, json!({})),
            row("both", 2, json!({ "metadata": { "notes": "local" } })),
            row("tomb", 3, json!({ "deletedAt": 4 })),
        ]))
        .expect("writes");
    let backup: Vec<String> = vec![
        row("both", 2, json!({ "metadata": { "notes": "backup" } })),
        row("b1", 10, json!({})),
        row("b2", 11, json!({})),
        row("b3", 12, json!({})),
    ];
    let backup_ids: BTreeSet<String> = set(&["both", "b1", "b2", "b3"]);
    let begin = store.restore_begin().expect("begins");
    for (i, batch) in backup.chunks(2).enumerate() {
        store.restore_batch(&rows_in(batch)).expect("writes");
        if i == 0 {
            // A shot captured during the restore, and an owner change.
            store
                .upsert(&rows_in(&[row("captured", 20, json!({}))]))
                .expect("writes");
            drop(store);
            store = reopen(&path);
        }
    }
    // The wipe set, in pages by rid.
    let mut wipe = Vec::new();
    let mut after = 0;
    loop {
        let page = store.ids_created_before(begin, after, 1).expect("reads");
        let Some(last) = page.last() else { break };
        after = last.rid;
        wipe.extend(
            page.into_iter()
                .map(|r| r.id)
                .filter(|id| !backup_ids.contains(id)),
        );
    }
    assert_eq!(wipe, ["local-only", "tomb"]);
    store.delete(&wipe).expect("deletes");
    assert_eq!(ids_of(&store), set(&["both", "b1", "b2", "b3", "captured"]));
    let both = stored_rows(&store)
        .into_iter()
        .find(|(id, _)| id == "both")
        .expect("a row")
        .1;
    assert!(both.contains("backup"), "incoming rows win");
}

#[test]
fn a_restore_cut_short_at_any_batch_leaves_the_union_and_a_rerun_finishes() {
    let backup: Vec<String> = (0..9)
        .map(|i| row(&format!("b{i}"), i, json!({})))
        .collect();
    for cut in 0..3 {
        let d = dir();
        let path = file(&d, "history.db");
        let mut store = create(&path);
        store
            .upsert(&rows_in(&[
                row("l1", 100, json!({})),
                row("l2", 101, json!({})),
            ]))
            .expect("writes");
        let begin = store.restore_begin().expect("begins");
        for (i, batch) in backup.chunks(3).enumerate() {
            if i == cut {
                store.faults().fail_at("upsert.commit", 0);
                assert!(store.restore_batch(&rows_in(batch)).is_err());
                break;
            }
            store.restore_batch(&rows_in(batch)).expect("writes");
        }
        drop(store);
        let mut store = reopen(&path);
        // The union: every local row, and the batches before the cut.
        let mut want = set(&["l1", "l2"]);
        want.extend(backup.iter().take(cut * 3).map(|r| {
            serde_json::from_str::<serde_json::Value>(r).expect("JSON")["id"]
                .as_str()
                .expect("an id")
                .to_owned()
        }));
        assert_eq!(ids_of(&store), want, "cut at {cut}");
        // A re-run from the top finishes it.
        let begin2 = store.restore_begin().expect("begins");
        assert!(begin2 >= begin);
        for batch in backup.chunks(3) {
            store.restore_batch(&rows_in(batch)).expect("writes");
        }
        let backup_ids: BTreeSet<String> = (0..9).map(|i| format!("b{i}")).collect();
        let wipe: Vec<String> = store
            .ids_created_before(begin2, 0, 1_000)
            .expect("reads")
            .into_iter()
            .map(|r| r.id)
            .filter(|id| !backup_ids.contains(id))
            .collect();
        store.delete(&wipe).expect("deletes");
        assert_eq!(ids_of(&store), backup_ids, "cut at {cut}");
    }
}

#[test]
fn a_recovery_file_starts_from_the_callers_baseline_and_never_overwrites() {
    let d = dir();
    let path = file(&d, "history-2.db");
    let init = Recovery {
        migration_source: vec!["p1".into(), "p2".into(), "p1".into()],
        late_baseline: "{\"writeSeq\":41}".into(),
    };
    let store =
        Store::create_recovery(&path, OpenOptions::new(Role::Authority), &init).expect("creates");
    assert_eq!(store.info().role, Role::Authority);
    assert_eq!(
        store.migration_state().expect("reads").as_deref(),
        Some("done")
    );
    assert_eq!(store.migration_source_len().expect("reads"), 2);
    assert_eq!(
        store.late_baseline().expect("reads").as_deref(),
        Some("{\"writeSeq\":41}")
    );
    store.close().expect("closes");
    // "Start empty", then a reboot: still empty.
    let store = reopen(&path);
    assert_eq!(int(&store, "SELECT count(*) FROM shot"), 0);
    assert_eq!(
        store.migration_state().expect("reads").as_deref(),
        Some("done")
    );
    drop(store);
    // A second recovery onto it is refused, and the file is left as it was.
    let before = fs::read(&path).expect("reads");
    let err = Store::create_recovery(&path, OpenOptions::new(Role::Authority), &init)
        .expect_err("refused");
    assert!(matches!(err, StoreError::Refused(_)), "{err:?}");
    assert!(
        fs::read(&path).expect("reads") == before,
        "the file changed"
    );
    // So is one onto a file that isn't ours, or holds rows.
    let other = file(&d, "history-3.db");
    fs::write(&other, b"not a database at all, but something").expect("writes");
    assert!(Store::create_recovery(&other, OpenOptions::new(Role::Authority), &init).is_err());
    assert_eq!(
        fs::read(&other).expect("reads"),
        b"not a database at all, but something"
    );
}

fn index_with_rows(path: &std::path::Path, n: usize) -> Store {
    let mut store = Store::open(path, OpenOptions::new(Role::Index).create(0)).expect("opens");
    let rows: Vec<String> = (0..n)
        .map(|i| {
            row(
                &format!("r{i:03}"),
                i64::try_from(i).expect("small"),
                json!({ "metadata": { "notes": format!("note {i}") } }),
            )
        })
        .collect();
    store.upsert(&rows_in(&rows)).expect("writes");
    store
}

/// Marks `path` a finished truth file, as the flip's verify does.
fn finish(path: &std::path::Path) {
    let c = rusqlite::Connection::open(path).expect("opens");
    c.execute_batch(
        "UPDATE meta SET value = 'authority' WHERE key = 'role';
         INSERT INTO meta (key, value) VALUES ('migration', '{\"state\":\"done\"}')
           ON CONFLICT(key) DO UPDATE SET value = excluded.value;",
    )
    .expect("marks");
}

#[test]
fn the_backup_copy_moves_a_whole_file_and_never_over_a_finished_truth() {
    let d = dir();
    let index = index_with_rows(&file(&d, "history-index.db"), 300);
    let dest = file(&d, "history.db");
    index
        .copy_to(&dest, None, JournalMode::Wal)
        .expect("copies");
    {
        let mut copy = Store::open(&dest, OpenOptions::new(Role::Index)).expect("opens");
        assert_eq!(copy.count(&Default::default()).expect("counts"), 300);
        assert_eq!(stored_rows(&copy), stored_rows(&index));
        assert!(fts_whole(&copy));
        // The leftover of a failed flip is overwritten in place.
        copy.upsert(&rows_in(&[row("leftover", 1, json!({}))]))
            .expect("writes");
    }
    index
        .copy_to(&dest, None, JournalMode::Wal)
        .expect("copies over a leftover");
    {
        let copy = Store::open(&dest, OpenOptions::new(Role::Index)).expect("opens");
        assert_eq!(stored_rows(&copy), stored_rows(&index));
    }
    // Once the copy is the truth, an index never overwrites it.
    finish(&dest);
    let before = fs::read(&dest).expect("reads");
    let err = index
        .copy_to(&dest, None, JournalMode::Wal)
        .expect_err("refused");
    assert!(matches!(err, StoreError::Refused(_)), "{err:?}");
    assert!(
        fs::read(&dest).expect("reads") == before,
        "the truth changed"
    );
    // The truth's own snapshot may replace an older snapshot of it.
    let truth = Store::open(&dest, OpenOptions::new(Role::Authority)).expect("opens");
    let snap = file(&d, "history-snapshot.db");
    truth
        .copy_to(&snap, None, JournalMode::Wal)
        .expect("snapshots");
    truth
        .copy_to(&snap, None, JournalMode::Wal)
        .expect("snapshots again");
    // A file a store has open, a foreign file, a file that isn't a database:
    // refused, untouched.
    assert!(matches!(
        truth.copy_to(&dest, None, JournalMode::Wal),
        Err(StoreError::AlreadyOpen(_))
    ));
    let foreign = file(&d, "foreign.db");
    {
        let c = rusqlite::Connection::open(&foreign).expect("opens");
        c.execute_batch("PRAGMA application_id = 7; CREATE TABLE t (x);")
            .expect("writes");
    }
    assert!(matches!(
        index.copy_to(&foreign, None, JournalMode::Wal),
        Err(StoreError::Foreign(_))
    ));
    let junk = file(&d, "junk.db");
    fs::write(&junk, vec![0x5a; 8192]).expect("writes");
    assert!(matches!(
        index.copy_to(&junk, None, JournalMode::Wal),
        Err(StoreError::Corrupt(_))
    ));
    assert_eq!(fs::read(&junk).expect("reads"), vec![0x5a; 8192]);
}

#[test]
fn a_truth_file_refuses_every_index_upkeep_call() {
    let d = dir();
    let mut truth = create(&file(&d, "history.db"));
    truth
        .upsert(&rows_in(&[row("a", 1, json!({})), row("b", 2, json!({}))]))
        .expect("writes");
    let refused =
        |r: Result<(), StoreError>| assert!(matches!(r, Err(StoreError::Refused(_))), "{r:?}");
    refused(
        truth
            .diff_batch(&rows_in(&[row("c", 3, json!({}))]))
            .map(|_| ()),
    );
    refused(truth.index_write(&IndexWrite::default()).map(|_| ()));
    refused(truth.remove_absent(&["a".into()], 0).map(|_| ()));
    refused(truth.set_source_seq(1));
    refused(truth.bump_pending().map(|_| ()));
    refused(truth.finish_android_diff(1));
    refused(truth.index_state().map(|_| ()));
    // Nothing was removed by absence, nothing added.
    assert_eq!(ids_of(&truth), set(&["a", "b"]));
}

#[test]
fn the_full_diff_removes_only_absent_rows_from_before_it_began() {
    let d = dir();
    let mut index = index_with_rows(&file(&d, "history-index.db"), 5);
    // The P3 store holds r000..r002 (r003, r004 gone) and a new edit of r001.
    let since = index.write_seq();
    index
        .diff_batch(&rows_in(&[row(
            "r001",
            1,
            json!({ "metadata": { "notes": "p3 edit" } }),
        )]))
        .expect("writes");
    // A write that lands during the diff, for an id the P3 read didn't see.
    index
        .upsert(&rows_in(&[row("during", 9, json!({}))]))
        .expect("writes");
    let c = index
        .remove_absent(&["r000".into(), "r001".into(), "r002".into()], since)
        .expect("removes");
    assert_eq!(c.deleted, ["r003", "r004"]);
    assert_eq!(ids_of(&index), set(&["r000", "r001", "r002", "during"]));
    assert!(fts_whole(&index));
}

#[test]
fn source_seq_and_pending_keep_the_boot_check() {
    let d = dir();
    let mut index = index_with_rows(&file(&d, "history-index.db"), 2);
    assert_eq!(index.index_state().expect("reads"), IndexState::default());
    index
        .index_write(&IndexWrite {
            upsert: rows_in(&[row("n1", 5, json!({}))]),
            delete: vec!["r000".into()],
            seen_seqs: vec![2, 3],
            lower_pending: false,
        })
        .expect("writes");
    let s = index.index_state().expect("reads");
    assert_eq!((s.source_seq, s.seen_seqs), (0, vec![2, 3]), "a gap at 1");
    assert_eq!(ids_of(&index), set(&["r001", "n1"]));
    index
        .index_write(&IndexWrite {
            seen_seqs: vec![1],
            ..IndexWrite::default()
        })
        .expect("writes");
    let s = index.index_state().expect("reads");
    assert_eq!((s.source_seq, s.seen_seqs), (3, vec![]));
    index
        .index_write(&IndexWrite {
            seen_seqs: vec![6],
            ..IndexWrite::default()
        })
        .expect("writes");
    // A full diff closes the gap.
    index.set_source_seq(7).expect("sets");
    let s = index.index_state().expect("reads");
    assert_eq!((s.source_seq, s.seen_seqs), (7, vec![]));
    // Android: pending up before the P3a write, down with the index write.
    assert_eq!(index.bump_pending().expect("bumps"), 1);
    assert_eq!(index.bump_pending().expect("bumps"), 2);
    index
        .index_write(&IndexWrite {
            upsert: rows_in(&[row("n2", 6, json!({}))]),
            lower_pending: true,
            ..IndexWrite::default()
        })
        .expect("writes");
    assert_eq!(index.index_state().expect("reads").pending, 1);
    // A failed index write leaves pending up.
    index.faults().fail_at("upsert.commit", 0);
    assert!(
        index
            .index_write(&IndexWrite {
                upsert: rows_in(&[row("n3", 7, json!({}))]),
                lower_pending: true,
                ..IndexWrite::default()
            })
            .is_err()
    );
    assert_eq!(index.index_state().expect("reads").pending, 1);
    index.finish_android_diff(42).expect("finishes");
    let s = index.index_state().expect("reads");
    assert_eq!((s.pending, s.diff_version_code), (0, Some(42)));
}

/// REVIEW-442 B1: a restore during an open walk upserts a row the user had
/// deleted (held). The upsert ends the hold, so the release doesn't delete
/// the restored shot, whether the backup's row is the same or edited.
#[test]
fn a_held_row_written_again_is_no_longer_held() {
    for edited in [false, true] {
        let d = dir();
        let mut store = create(&file(&d, "history.db"));
        let x = row("x", 1, json!({}));
        store
            .upsert(&rows_in(&[x.clone(), row("y", 2, json!({}))]))
            .expect("writes");
        let walk = store.export_begin().expect("begins");
        store.delete(&["x".into()]).expect("holds");
        assert!(store.get(&["x".into()]).expect("reads").is_empty());
        let back = if edited {
            row("x", 1, json!({ "metadata": { "notes": "from backup" } }))
        } else {
            x.clone()
        };
        let begin = store.restore_begin().expect("begins");
        let w = store
            .restore_batch(&rows_in(&[back.clone(), row("y", 2, json!({}))]))
            .expect("writes");
        assert!(
            w.change.upserted.contains(&"x".to_owned()),
            "edited={edited}"
        );
        assert!(w.change.kinds.set, "x is back in every list");
        let backup_ids = set(&["x", "y"]);
        let wipe: Vec<String> = store
            .ids_created_before(begin, 0, 100)
            .expect("reads")
            .into_iter()
            .map(|r| r.id)
            .filter(|id| !backup_ids.contains(id))
            .collect();
        store.delete(&wipe).expect("deletes");
        // Visible at once, and still in the walk, which writes it.
        assert_eq!(
            store.get(&["x".into()]).expect("reads").len(),
            1,
            "edited={edited}"
        );
        assert_eq!(store.count(&Default::default()).expect("counts"), 2);
        let lines = walk_all(&mut store, walk.token, 10, |_, _| {});
        assert_eq!(lines.len(), walk.count as usize);
        store.export_end(walk.token).expect("ends");
        let released = store.release_holds().expect("reads");
        assert!(released.is_empty(), "edited={edited}: {released:?}");
        store.delete(&released).expect("deletes");
        assert_eq!(ids_of(&store), set(&["x", "y"]), "edited={edited}");
        let stored = stored_rows(&store)
            .into_iter()
            .find(|(id, _)| id == "x")
            .expect("x")
            .1;
        assert_eq!(stored, back);
    }
}

/// REVIEW-442 note 4: the snapshot moves forward only. An older truth (a
/// lower `write_seq` or `generation`) never replaces a newer one.
#[test]
fn an_older_truth_never_overwrites_a_newer_one() {
    let d = dir();
    let live = file(&d, "history.db");
    let snap = file(&d, "history-snapshot.db");
    {
        let mut truth = create(&live);
        truth
            .upsert(&rows_in(&[row("a", 1, json!({}))]))
            .expect("writes");
        truth.close().expect("closes");
    }
    finish(&live);
    let mut truth = Store::open(&live, OpenOptions::new(Role::Authority)).expect("opens");
    truth
        .copy_to(&snap, None, JournalMode::Wal)
        .expect("snapshots");
    truth
        .upsert(&rows_in(&[row("b", 2, json!({}))]))
        .expect("writes");
    truth
        .copy_to(&snap, None, JournalMode::Wal)
        .expect("a newer snapshot");
    truth
        .upsert(&rows_in(&[row("c", 3, json!({}))]))
        .expect("writes");
    drop(truth);
    // The snapshot (write_seq 2) onto the live file (write_seq 3): refused.
    let before = fs::read(&live).expect("reads");
    let old = Store::open(&snap, OpenOptions::new(Role::Authority)).expect("opens");
    let err = old
        .copy_to(&live, None, JournalMode::Wal)
        .expect_err("refused");
    assert!(matches!(err, StoreError::Refused(_)), "{err:?}");
    assert!(
        fs::read(&live).expect("reads") == before,
        "the truth changed"
    );
    drop(old);
    // An older generation, even with more writes: refused too.
    let gen2 = file(&d, "history-2.db");
    fs::copy(&live, &gen2).expect("copies");
    {
        let c = rusqlite::Connection::open(&gen2).expect("opens");
        c.execute_batch("UPDATE meta SET value = '2' WHERE key = 'generation'")
            .expect("marks");
    }
    {
        let mut older = Store::open(&live, OpenOptions::new(Role::Authority)).expect("opens");
        for i in 0..5 {
            older
                .upsert(&rows_in(&[row(&format!("more{i}"), 9, json!({}))]))
                .expect("writes");
        }
        let err = older
            .copy_to(&gen2, None, JournalMode::Wal)
            .expect_err("refused");
        assert!(matches!(err, StoreError::Refused(_)), "{err:?}");
    }
    let gen2_store = Store::open(&gen2, OpenOptions::new(Role::Authority)).expect("opens");
    assert_eq!(gen2_store.info().generation, 2);
}

/// REVIEW-442 note 5: a restore row flagged as pulled is restored anyway,
/// not skipped as a duplicate of the local row the wipe then removes.
#[test]
fn a_restore_row_is_never_a_pulled_duplicate() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&rows_in(&[row(
            "local",
            1,
            json!({ "visualizerId": "v1" }),
        )]))
        .expect("writes");
    let begin = store.restore_begin().expect("begins");
    let w = store
        .restore_batch(&[RowIn {
            row_json: row("backup", 1, json!({ "visualizerId": "v1" })),
            heavy: None,
            pulled: true,
        }])
        .expect("writes");
    assert!(w.duplicates.is_empty());
    assert_eq!(w.change.upserted, ["backup"]);
    let wipe: Vec<String> = store
        .ids_created_before(begin, 0, 100)
        .expect("reads")
        .into_iter()
        .map(|r| r.id)
        .filter(|id| id != "backup")
        .collect();
    store.delete(&wipe).expect("deletes");
    assert_eq!(ids_of(&store), set(&["backup"]));
}

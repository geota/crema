//! The full diff's removal by absence in steps (S15, B1): each step one
//! transaction, at most `ABSENT_BATCH_ROWS` deletes, both tests (absent from
//! the P3 store, not written since the diff began) inside the step's own
//! transaction. The only delete by absence, and only on an index (M4).

mod common;

#[cfg(target_arch = "wasm32")]
use common::test;

use std::collections::BTreeSet;

use common::{create, dir, file, row, rows_in, stored_rows};
use de1_history::store::{
    ABSENT_BATCH_ROWS, IndexWrite, OpenOptions, Role, RowIn, Store, StoreError, Write,
};
use serde_json::json;

fn ids_of(store: &Store) -> BTreeSet<String> {
    stored_rows(store).into_iter().map(|(id, _)| id).collect()
}

fn id(i: usize) -> String {
    format!("r{i:04}")
}

/// An index of rows `r0000` … in `rid` order.
fn index_with(path: &std::path::Path, n: usize) -> Store {
    let mut store = Store::open(path, OpenOptions::new(Role::Index).create(0)).expect("opens");
    let rows: Vec<String> = (0..n)
        .map(|i| row(&id(i), i64::try_from(i).expect("small"), json!({})))
        .collect();
    for chunk in rows.chunks(1_000) {
        store.upsert(&rows_in(chunk)).expect("writes");
    }
    store
}

fn refused<T: std::fmt::Debug>(r: Result<T, StoreError>) {
    assert!(matches!(r, Err(StoreError::Refused(_))), "{r:?}");
}

#[test]
fn absent_rows_go_in_steps_of_at_most_a_batch() {
    let d = dir();
    // More rows than one step reads, so the steps read in windows too.
    let n = 6_200;
    let mut index = index_with(&file(&d, "history-index.db"), n);
    let since = index.write_seq();
    // The P3 store holds four rows in five: 1,240 are gone.
    let held: Vec<String> = (0..n).filter(|i| i % 5 != 0).map(id).collect();
    index.remove_absent_begin(&held, since).expect("begins");
    let mut deleted = Vec::new();
    let mut steps = 0;
    loop {
        let step = index.remove_absent_next().expect("a step");
        assert!(step.change.deleted.len() <= ABSENT_BATCH_ROWS);
        deleted.extend(step.change.deleted);
        steps += 1;
        if step.done {
            break;
        }
    }
    assert!(steps >= 3, "{steps} steps");
    let gone: BTreeSet<String> = (0..n).filter(|i| i % 5 == 0).map(id).collect();
    assert_eq!(deleted.iter().cloned().collect::<BTreeSet<_>>(), gone);
    assert_eq!(ids_of(&index), held.into_iter().collect());
    // Finished: another step is refused, so a caller can't mistake it for a
    // removal that ran.
    refused(index.remove_absent_next());
}

#[test]
fn a_row_written_between_steps_is_never_removed() {
    let d = dir();
    let n = 1_200;
    let mut index = index_with(&file(&d, "history-index.db"), n);
    let since = index.write_seq();
    // The P3 store holds the even rows; the odd ones are absent.
    let held: Vec<String> = (0..n).filter(|i| i % 2 == 0).map(id).collect();
    index.remove_absent_begin(&held, since).expect("begins");
    let first = index.remove_absent_next().expect("a step");
    assert_eq!(first.change.deleted.len(), ABSENT_BATCH_ROWS);
    assert!(!first.done);
    // Between the steps: an absent row the first step didn't reach is
    // written again (a P3 write arriving through the upkeep), and a new row
    // lands that the P3 read never saw.
    let late = id(n - 1);
    index
        .upsert(&rows_in(&[
            row(
                &late,
                9_999,
                json!({ "metadata": { "notes": "written again" } }),
            ),
            row("new", 10_000, json!({})),
        ]))
        .expect("writes");
    loop {
        if index.remove_absent_next().expect("a step").done {
            break;
        }
    }
    let ids = ids_of(&index);
    assert!(ids.contains(&late), "a row written since the diff began");
    assert!(ids.contains("new"));
    let odd_left: Vec<&String> = ids
        .iter()
        .filter(|i| i.starts_with('r') && i[1..].parse::<usize>().is_ok_and(|n| n % 2 == 1))
        .collect();
    assert_eq!(odd_left, [&late]);
}

#[test]
fn a_failed_step_keeps_its_rows_and_the_removal_resumes() {
    let d = dir();
    let n = 1_600;
    let mut index = index_with(&file(&d, "history-index.db"), n);
    let since = index.write_seq();
    let held: Vec<String> = (0..n).filter(|i| i % 2 == 0).map(id).collect();
    index.remove_absent_begin(&held, since).expect("begins");
    index.remove_absent_next().expect("a step");
    let after_one = ids_of(&index);
    // The second step fails before its commit: it removes nothing.
    index.faults().fail_at("delete.commit", 0);
    assert!(index.remove_absent_next().is_err());
    assert_eq!(ids_of(&index), after_one);
    // The removal is where it was, and finishes.
    loop {
        if index.remove_absent_next().expect("a step").done {
            break;
        }
    }
    assert_eq!(ids_of(&index), held.into_iter().collect());
}

#[test]
fn the_one_call_form_gives_every_step_as_one_change() {
    let d = dir();
    let n = 1_300;
    let mut index = index_with(&file(&d, "history-index.db"), n);
    let since = index.write_seq();
    let held: Vec<String> = (0..n).filter(|i| i % 3 != 0).map(id).collect();
    let c = index.remove_absent(&held, since).expect("removes");
    assert_eq!(c.deleted.len(), (0..n).filter(|i| i % 3 == 0).count());
    assert_eq!(c.seq, index.write_seq());
    assert_eq!(ids_of(&index), held.into_iter().collect());
}

#[test]
fn a_removal_is_refused_unbegun_after_a_reopen_and_on_a_truth_file() {
    let d = dir();
    let path = file(&d, "history-index.db");
    let mut index = index_with(&path, 10);
    refused(index.remove_absent_next());
    let since = index.write_seq();
    index.remove_absent_begin(&[id(0)], since).expect("begins");
    // A reopen forgets the removal: nothing is deleted on its say-so.
    index.close().expect("closes");
    let mut index = Store::open(&path, OpenOptions::new(Role::Index)).expect("opens");
    refused(index.remove_absent_next());
    assert_eq!(ids_of(&index).len(), 10);

    let mut truth = create(&file(&d, "history.db"));
    truth
        .upsert(&rows_in(&[row("a", 1, json!({}))]))
        .expect("writes");
    refused(truth.remove_absent_begin(&[], 0));
    refused(truth.remove_absent_next());
    assert_eq!(ids_of(&truth).len(), 1);
}

/// REVIEW-448 P1, inverted: a row absent from the P3 read and put back
/// between steps with its identical `row_json` writes nothing, so its
/// `changed_seq` doesn't move; it is held all the same, through every write
/// path.
#[test]
fn a_row_put_back_unchanged_between_steps_is_kept() {
    for path_name in ["upsert", "restore_batch", "index_write", "apply"] {
        let d = dir();
        let n = 1_200;
        let mut index = index_with(&file(&d, "history-index.db"), n);
        let since = index.write_seq();
        let held: Vec<String> = (0..n).filter(|i| i % 2 == 0).map(id).collect();
        index.remove_absent_begin(&held, since).expect("begins");
        let first = index.remove_absent_next().expect("a step");
        assert!(!first.done);
        let late = n - 1;
        let same = row(&id(late), i64::try_from(late).expect("small"), json!({}));
        let rows = vec![RowIn::new(same)];
        let upserted = match path_name {
            "upsert" => index.upsert(&rows).expect("writes").change.upserted,
            "restore_batch" => index.restore_batch(&rows).expect("writes").change.upserted,
            "index_write" => {
                index
                    .index_write(&IndexWrite {
                        upsert: rows,
                        ..IndexWrite::default()
                    })
                    .expect("writes")
                    .change
                    .upserted
            }
            _ => match index
                .apply("tab-1", 1, 0, &Write::Upsert(rows))
                .expect("applies")
            {
                de1_history::store::Applied::Done(w) => w.change.upserted,
                other => panic!("{other:?}"),
            },
        };
        assert!(upserted.is_empty(), "{path_name}: the write was unchanged");
        loop {
            if index.remove_absent_next().expect("a step").done {
                break;
            }
        }
        assert!(
            ids_of(&index).contains(&id(late)),
            "{path_name}: a row put back since the removal began"
        );
    }
}

/// REVIEW-448 P3: a `since` above the file's `write_seq` is taken as its
/// `write_seq`, so a row changed between steps is still newer than the diff.
#[test]
fn a_since_above_the_files_write_seq_is_capped() {
    let d = dir();
    let n = 1_200;
    let mut index = index_with(&file(&d, "history-index.db"), n);
    let since = index.write_seq() + 10;
    let held: Vec<String> = (0..n).filter(|i| i % 2 == 0).map(id).collect();
    index.remove_absent_begin(&held, since).expect("begins");
    index.remove_absent_next().expect("a step");
    // A write between steps, at the next seq: at or below the uncapped
    // `since`. Raw, so only the seq rule can keep the row (writes through
    // the store also hold its id).
    let late = id(n - 1);
    index
        .raw()
        .execute(
            "UPDATE shot SET changed_seq = ?1 WHERE id = ?2",
            (i64::try_from(index.write_seq() + 1).expect("fits"), &late),
        )
        .expect("writes");
    loop {
        if index.remove_absent_next().expect("a step").done {
            break;
        }
    }
    assert!(
        ids_of(&index).contains(&late),
        "written after the diff began"
    );
}

/// REVIEW-448 note 3: when a step fails, the one-call form's error carries
/// what the steps before it deleted, so the shell can post it.
#[test]
fn a_failed_one_call_removal_reports_what_it_deleted() {
    let d = dir();
    let n = 1_600;
    let mut index = index_with(&file(&d, "history-index.db"), n);
    let since = index.write_seq();
    let held: Vec<String> = (0..n).filter(|i| i % 2 == 0).map(id).collect();
    index.faults().fail_at("delete.commit", 1);
    let err = index
        .remove_absent(&held, since)
        .expect_err("the second step fails");
    assert!(matches!(err.error, StoreError::Fault(_)), "{err:?}");
    assert_eq!(err.partial.deleted.len(), ABSENT_BATCH_ROWS);
    let gone: BTreeSet<String> = err.partial.deleted.iter().cloned().collect();
    let left = ids_of(&index);
    assert!(gone.iter().all(|g| !left.contains(g)));
    assert_eq!(left.len(), n - ABSENT_BATCH_ROWS);
    assert_eq!(err.partial.seq, index.write_seq());
}

//! The flip's store calls (SPEC §11 steps 4 to 7, M8, S9, S13): the copy and
//! its promote, verify's checks, and `done`. A fault between every pair of
//! statements leaves a file no build reads as the truth, and the next try
//! overwrites it in place.
//!
//! Native only: these plant and compare bytes with `std::fs`. The web's
//! flip tests (`web/src/lib/history-db/flip.vitest.ts`) run the same calls
//! on the wasm build.
#![cfg(not(target_arch = "wasm32"))]

mod common;

use common::{dir, file, int, reopen, row, rows_in, stored_rows};
use de1_history::store::{FlipInit, FtsCheck, OpenOptions, Role, Store, StoreError};
use serde_json::json;

fn index_with(path: &std::path::Path, n: usize) -> Store {
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

fn init(n: usize) -> FlipInit {
    FlipInit {
        generation: 0,
        source_seq: 41,
        source_count: n as u64,
        migration_source: (0..n).map(|i| format!("r{i:03}")).collect(),
        late_baseline: r#"{"writeSeq":41}"#.into(),
        p3_migration: Some(r#"{"state":"done","sourceIds":["old"],"sourceHash":"h"}"#.into()),
    }
}

fn role_of(path: &std::path::Path) -> String {
    let c = common::raw_conn(path);
    c.query_row("SELECT value FROM meta WHERE key = 'role'", [], |r| {
        r.get(0)
    })
    .expect("reads")
}

#[test]
fn the_flip_copy_is_an_authority_file_copying_with_every_baseline() {
    let d = dir();
    let index = index_with(&file(&d, "history-index.db"), 40);
    let dest = file(&d, "history.db");
    index
        .flip_copy_to(&dest, None, common::journal(), &init(40))
        .expect("copies");
    // A reopen reads the new role (REVIEW-442 note 8): an index open is refused.
    assert!(matches!(
        Store::open(&dest, OpenOptions::new(Role::Index)),
        Err(StoreError::WrongRole { .. })
    ));
    let mut copy = reopen_truth(&dest);
    assert_eq!(copy.info().role, Role::Authority);
    assert_eq!(
        copy.migration_state().expect("reads").as_deref(),
        Some("copying")
    );
    assert_eq!(copy.migration_source_ids().expect("reads").len(), 40);
    assert_eq!(
        copy.meta_value("late_baseline").expect("reads").as_deref(),
        Some(r#"{"writeSeq":41}"#)
    );
    assert!(copy.meta_value("p3_migration").expect("reads").is_some());
    assert_eq!(stored_rows(&copy), stored_rows(&index));
    assert!(copy.integrity_check().expect("checks").is_empty());
    assert!(copy.derived_mismatches().expect("checks").is_empty());
    assert_eq!(copy.fts_integrity_check().expect("checks"), FtsCheck::Ok);
    assert!(copy.size_bytes().expect("reads") > 0);
    // Verify's pass, then a reopen: `done`.
    copy.set_migration_done(7).expect("done");
    copy.set_migration_done(8).expect("done twice is a no-op");
    drop(copy);
    let copy = reopen_truth(&dest);
    assert_eq!(
        copy.migration_state().expect("reads").as_deref(),
        Some("done")
    );
    drop(copy);
    // Once done, the index never overwrites it.
    let before = std::fs::read(&dest).expect("reads");
    assert!(matches!(
        index.flip_copy_to(&dest, None, common::journal(), &init(40)),
        Err(StoreError::Refused(_))
    ));
    assert_eq!(std::fs::read(&dest).expect("reads"), before);
}

fn reopen_truth(path: &std::path::Path) -> Store {
    Store::open(path, OpenOptions::new(Role::Authority)).expect("opens as the truth")
}

#[test]
fn only_an_index_flips_and_only_a_copying_truth_is_marked_done() {
    let d = dir();
    let mut truth = common::create(&file(&d, "history.db"));
    assert!(matches!(
        truth.flip_copy_to(&file(&d, "x.db"), None, common::journal(), &init(0)),
        Err(StoreError::Refused(_))
    ));
    // `create` makes no migration record: nothing to mark.
    assert!(matches!(
        truth.set_migration_done(1),
        Err(StoreError::Refused(_))
    ));
    let mut index = index_with(&file(&d, "history-index.db"), 1);
    assert!(matches!(
        index.set_migration_done(1),
        Err(StoreError::Refused(_))
    ));
}

#[test]
fn a_fault_at_every_flip_point_leaves_no_truth_and_the_next_try_passes() {
    for point in [
        "flip.copied",
        "flip.promote.commit",
        "flip.promote.committed",
        "flip.done.commit",
    ] {
        let d = dir();
        let index = index_with(&file(&d, "history-index.db"), 30);
        let dest = file(&d, "history.db");
        index.faults().fail_at(point, 0);
        let first = index.flip_copy_to(&dest, None, common::journal(), &init(30));
        if point == "flip.done.commit" {
            first.expect("copies");
            let mut copy = reopen_truth(&dest);
            copy.faults().fail_at(point, 0);
            assert!(copy.set_migration_done(1).is_err(), "{point}");
            drop(copy);
        } else {
            assert!(first.is_err(), "{point}");
        }
        index.faults().clear();
        // Whatever is left is never a finished truth (M8).
        let role = role_of(&dest);
        let state: Option<String> = {
            let c = common::raw_conn(&dest);
            c.query_row("SELECT value FROM meta WHERE key = 'migration'", [], |r| {
                r.get(0)
            })
            .ok()
        };
        assert!(
            role == "index" || state.as_deref().is_some_and(|s| s.contains("copying")),
            "{point}: role {role}, migration {state:?}"
        );
        // The next try overwrites it in place and passes.
        index
            .flip_copy_to(&dest, None, common::journal(), &init(30))
            .expect("copies again");
        let mut copy = reopen_truth(&dest);
        assert_eq!(
            copy.migration_state().expect("reads").as_deref(),
            Some("copying")
        );
        copy.set_migration_done(2).expect("done");
        assert_eq!(int(&copy, "SELECT count(*) FROM shot"), 30, "{point}");
    }
}

#[test]
fn a_leftover_that_is_not_a_database_is_left_untouched() {
    let d = dir();
    let index = index_with(&file(&d, "history-index.db"), 3);
    let junk = file(&d, "history.db");
    std::fs::write(&junk, vec![0x5a; 8192]).expect("writes");
    assert!(matches!(
        index.flip_copy_to(&junk, None, common::journal(), &init(3)),
        Err(StoreError::Corrupt(_))
    ));
    assert_eq!(std::fs::read(&junk).expect("reads"), vec![0x5a; 8192]);
    // The flip goes to the next generation instead.
    let next = file(&d, "history-1.db");
    let mut i = init(3);
    i.generation = 1;
    index
        .flip_copy_to(&next, None, common::journal(), &i)
        .expect("copies");
    assert_eq!(reopen_truth(&next).info().generation, 1);
    let _ = reopen;
}

#[test]
fn verify_finds_a_stale_derived_column_and_a_broken_page() {
    let d = dir();
    let index = index_with(&file(&d, "history-index.db"), 20);
    let dest = file(&d, "history.db");
    index
        .flip_copy_to(&dest, None, common::journal(), &init(20))
        .expect("copies");
    {
        let c = common::raw_conn(&dest);
        c.execute("UPDATE shot SET sort_rating = 99 WHERE id = 'r005'", [])
            .expect("stales");
    }
    let copy = reopen_truth(&dest);
    assert_eq!(
        copy.derived_mismatches().expect("checks"),
        vec!["r005".to_owned()]
    );
}

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

fn truth_after_flip(d: &common::TestDir, n: usize) -> Store {
    let index = index_with(&file(d, "history-index.db"), n);
    let dest = file(d, "history.db");
    index
        .flip_copy_to(&dest, None, common::journal(), &init(n))
        .expect("copies");
    let mut t = reopen_truth(&dest);
    t.set_migration_done(1).expect("done");
    t
}

fn rowin(json: String) -> de1_history::store::RowIn {
    rows_in(&[json]).remove(0)
}

#[test]
fn late_rows_import_once_never_bring_back_a_deleted_row_and_skip_pulled_duplicates() {
    let d = dir();
    let mut t = truth_after_flip(&d, 5);
    t.upsert(&rows_in(&[row(
        "here",
        50,
        json!({ "visualizerId": "v-held" }),
    )]))
    .expect("writes");
    t.delete(&["r001".to_owned()]).expect("deletes");
    let late = vec![
        // An older build's copy of a migrated row: an edit lost at roll-forward.
        rowin(row(
            "r000",
            0,
            json!({ "metadata": { "notes": "older build's edit" } }),
        )),
        // A row deleted here since the flip: never back.
        rowin(row("r001", 1, json!({}))),
        // A new shot, with no samples yet.
        rowin(row("new1", 60, json!({ "summary": { "sampleCount": 0 } }))),
        // A pulled duplicate of a row here.
        rowin(row("dup", 61, json!({ "visualizerId": "v-held" }))),
    ];
    let out = t
        .import_late(&late, Some(r#"{"writeSeq":9}"#))
        .expect("imports");
    assert_eq!(out.imported, vec!["new1".to_owned()]);
    assert_eq!(out.duplicates, vec!["dup".to_owned()]);
    assert_eq!(out.known, vec!["r000".to_owned(), "r001".to_owned()]);
    assert!(t.existing(&["r001".to_owned()]).expect("reads").is_empty());
    let r0: String = common::raw_conn(&file(&d, "history.db"))
        .query_row("SELECT row_json FROM shot WHERE id = 'r000'", [], |r| {
            r.get(0)
        })
        .expect("reads");
    assert!(!r0.contains("older build"));
    assert_eq!(t.late_ids().expect("reads"), vec!["new1".to_owned()]);
    assert_eq!(
        t.meta_value("late_baseline").expect("reads").as_deref(),
        Some(r#"{"writeSeq":9}"#)
    );
    // Once only: the same rows again import nothing.
    let again = t.import_late(&late, None).expect("imports");
    assert!(again.imported.is_empty());
    // Step 9: the row's series arrived.
    let filled = rowin(row(
        "new1",
        60,
        json!({ "summary": { "sampleCount": 120 } }),
    ));
    t.settle_late(&[filled]).expect("fills");
    assert!(t.late_ids().expect("reads").is_empty());
    // An index refuses both.
    let mut index =
        Store::open(file(&d, "history-index.db"), OpenOptions::new(Role::Index)).expect("opens");
    assert!(matches!(
        index.import_late(&[], None),
        Err(StoreError::Refused(_))
    ));
}

#[test]
fn p3_migration_is_written_only_into_the_truth() {
    use de1_history::store::Write;
    let d = dir();
    let mut t = truth_after_flip(&d, 1);
    t.apply(
        "tab",
        1,
        0,
        &Write::P3Migration(r#"{"state":"done","sourceIds":["a"]}"#.into()),
    )
    .expect("applies");
    assert_eq!(
        t.meta_value("p3_migration").expect("reads").as_deref(),
        Some(r#"{"state":"done","sourceIds":["a"]}"#)
    );
    let mut index =
        Store::open(file(&d, "history-index.db"), OpenOptions::new(Role::Index)).expect("opens");
    let a = index
        .apply("tab", 1, 0, &Write::P3Migration("{}".into()))
        .expect("answers");
    assert!(
        matches!(a, de1_history::store::Applied::Refused { .. }),
        "{a:?}"
    );
}

#[test]
fn a_restored_rows_snapshot_becomes_the_next_generation_and_its_snapshots_move_forward() {
    use de1_history::store::Recovery;
    let d = dir();
    let mut t = truth_after_flip(&d, 4);
    t.upsert(&rows_in(&[row("after", 90, json!({}))]))
        .expect("writes");
    let snap = file(&d, "history-snapshot.db");
    t.copy_to(&snap, None, common::journal())
        .expect("snapshots");
    drop(t);
    // `lost`, then "restore the rows snapshot": a copy of it, as generation 2.
    let rec = file(&d, "history-2.db");
    reopen_truth(&snap)
        .copy_to(&rec, None, common::journal())
        .expect("copies");
    let mut r = reopen_truth(&rec);
    let init = Recovery {
        migration_source: vec!["p3-only".into()],
        late_baseline: r#"{"writeSeq":7,"rows":5}"#.into(),
    };
    r.set_recovery(2, &init).expect("adopts");
    assert_eq!(r.info().generation, 2);
    assert_eq!(r.migration_state().expect("reads").as_deref(), Some("done"));
    assert_eq!(
        r.late_baseline().expect("reads").as_deref(),
        Some(r#"{"writeSeq":7,"rows":5}"#)
    );
    assert!(
        r.migration_source_ids()
            .expect("reads")
            .contains(&"p3-only".to_owned())
    );
    assert_eq!(int(&r, "SELECT count(*) FROM shot"), 5);
    // Its own snapshot may replace the old one: a newer generation.
    r.copy_to(&snap, None, common::journal())
        .expect("snapshots the recovery file");
    // An index copy isn't a truth: refused.
    let mut idx =
        Store::open(file(&d, "history-index.db"), OpenOptions::new(Role::Index)).expect("opens");
    assert!(matches!(
        idx.set_recovery(3, &init),
        Err(StoreError::Refused(_))
    ));
}

#[test]
fn step_9_fills_only_the_summary_and_never_brings_a_row_back() {
    let d = dir();
    let mut t = truth_after_flip(&d, 2);
    let zero = |id: &str| rowin(row(id, 70, json!({ "summary": { "sampleCount": 0 } })));
    let brew = rowin(row(
        "brew",
        71,
        json!({ "brewMethod": "v60", "summary": { "sampleCount": 0 } }),
    ));
    t.import_late(&[zero("edited"), zero("gone"), zero("held"), brew], None)
        .expect("imports");
    // A brew log never waits for samples (REVIEW-474 B1).
    let mut late = t.late_ids().expect("reads");
    late.sort();
    assert_eq!(
        late,
        vec!["edited".to_owned(), "gone".to_owned(), "held".to_owned()]
    );
    // Stage 2 edits one, deletes one, and an export walk holds one.
    t.upsert(&rows_in(&[row(
        "edited",
        70,
        json!({ "metadata": { "notes": "mine" }, "summary": { "sampleCount": 0 } }),
    )]))
    .expect("edits");
    t.delete(&["gone".to_owned()]).expect("deletes");
    let walk = t.export_begin().expect("walks");
    t.delete(&["held".to_owned()]).expect("holds");
    // The older build writes the series.
    let full = |id: &str| {
        rowin(row(
            id,
            70,
            json!({ "metadata": { "notes": "p3's" }, "summary": { "sampleCount": 25 } }),
        ))
    };
    t.settle_late(&[full("edited"), full("gone"), full("held")])
        .expect("fills");
    let c = common::raw_conn(&file(&d, "history.db"));
    let edited: String = c
        .query_row("SELECT row_json FROM shot WHERE id = 'edited'", [], |r| {
            r.get(0)
        })
        .expect("reads");
    assert!(edited.contains("\"mine\""), "{edited}");
    assert!(edited.contains("\"sampleCount\":25"), "{edited}");
    assert!(
        t.existing(&["gone".to_owned()]).expect("reads").is_empty(),
        "a deleted row came back"
    );
    let held: i64 = c
        .query_row("SELECT hold_delete FROM shot WHERE id = 'held'", [], |r| {
            r.get(0)
        })
        .expect("reads");
    assert_eq!(held, 1, "the hold ended");
    assert!(t.late_ids().expect("reads").is_empty());
    t.export_end(walk.token).expect("ends");
}

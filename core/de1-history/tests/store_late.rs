//! Android stage 2 (PLAN A5): `shot_heavy` read and copied (M10), step 8's
//! late-row import (SPEC §11, S13, N8), and the sync projection's final
//! weight and `visualizerUpdatedAt` (PLAN A3a).

mod common;

#[cfg(target_arch = "wasm32")]
use common::test;

use common::{create, dir, file, int, reopen, row, rows_in, stored_rows};
use de1_history::store::{Heavy, LateImport, OpenOptions, Role, RowIn, Store, StoreError};
use serde_json::json;

fn heavy(p: &str, s: &str) -> Heavy {
    Heavy {
        profile_json: Some(p.to_owned()),
        brew_series_json: Some(s.to_owned()),
    }
}

fn ids(v: &[&str]) -> Vec<String> {
    v.iter().map(|s| (*s).to_owned()).collect()
}

#[test]
fn heavy_parts_are_copied_only_into_rows_the_file_holds_and_never_thinned() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&rows_in(&[row("a", 1, json!({})), row("b", 2, json!({}))]))
        .expect("writes");
    let missing = store
        .put_heavy(&[
            ("a".into(), heavy(r#"{"p":1}"#, r#"{"s":1}"#)),
            ("x".into(), heavy(r#"{"p":9}"#, r#"{"s":9}"#)),
        ])
        .expect("copies");
    assert_eq!(
        missing,
        ids(&["x"]),
        "a row the file lacks is named, not made"
    );
    assert_eq!(
        int(&store, "SELECT count(*) FROM shot"),
        2,
        "no row was written"
    );
    // A NULL part keeps the stored one (M10).
    store
        .put_heavy(&[(
            "a".into(),
            Heavy {
                profile_json: None,
                brew_series_json: Some(r#"{"s":2}"#.into()),
            },
        )])
        .expect("copies");
    let h = store.heavy(&ids(&["a", "b", "x"])).expect("reads");
    assert_eq!(h.len(), 1, "only a has heavy parts");
    assert_eq!(h[0].id, "a");
    assert_eq!(h[0].profile_json.as_deref(), Some(r#"{"p":1}"#));
    assert_eq!(h[0].brew_series_json.as_deref(), Some(r#"{"s":2}"#));
    assert_eq!(store.ids_with_profile().expect("reads"), ids(&["a"]));
    // A light upsert of the row keeps both (M10).
    store
        .upsert(&rows_in(&[row(
            "a",
            1,
            json!({ "metadata": { "rating": 5 } }),
        )]))
        .expect("writes");
    let h = store.heavy(&ids(&["a"])).expect("reads");
    assert_eq!(h[0].profile_json.as_deref(), Some(r#"{"p":1}"#));
    // Only delete(ids) takes them.
    store.delete(&ids(&["a"])).expect("deletes");
    assert!(store.heavy(&ids(&["a"])).expect("reads").is_empty());
    assert_eq!(int(&store, "SELECT count(*) FROM shot_heavy"), 0);
}

#[test]
fn an_index_holds_no_heavy_parts_and_takes_no_late_rows() {
    let d = dir();
    let path = file(&d, "history-index.db");
    let mut index = Store::open(&path, OpenOptions::new(Role::Index).create(0)).expect("opens");
    index
        .upsert(&rows_in(&[row("a", 1, json!({}))]))
        .expect("writes");
    assert!(matches!(
        index.put_heavy(&[("a".into(), heavy("{}", "{}"))]),
        Err(StoreError::Refused(_))
    ));
    assert!(matches!(
        index.import_late(&LateImport::default()),
        Err(StoreError::Refused(_))
    ));
}

#[test]
fn a_late_row_goes_in_once_and_never_over_a_row_the_file_holds() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    store
        .upsert(&rows_in(&[row(
            "kept",
            1,
            json!({ "metadata": { "rating": 5 } }),
        )]))
        .expect("writes");
    let before = store.write_seq();
    let out = store
        .import_late(&LateImport {
            rows: vec![
                RowIn::new(row("late", 2, json!({}))),
                // The file holds "kept" as edited since: the older copy never lands.
                RowIn::new(row("kept", 1, json!({ "metadata": { "rating": 1 } }))),
            ],
            source_ids: ids(&["looked-at"]),
            late_ids: Some(ids(&["late"])),
            late_baseline: Some(r#"{"shotsDb":[1,2]}"#.into()),
        })
        .expect("imports");
    assert_eq!(out.present, ids(&["kept"]));
    assert_eq!(out.written.change.upserted, ids(&["late"]));
    assert!(store.write_seq() > before);
    let rows = stored_rows(&store);
    let kept = rows.iter().find(|(id, _)| id == "kept").expect("kept");
    assert!(
        kept.1.contains(r#""rating":5"#),
        "the edit stands: {}",
        kept.1
    );
    assert_eq!(
        store
            .not_in_migration_source(&ids(&["late", "kept", "looked-at", "new"]))
            .expect("reads"),
        ids(&["new"]),
        "every id given joins migration_source, present or not"
    );
    assert_eq!(store.late_ids().expect("reads"), ids(&["late"]));
    assert_eq!(
        store.late_baseline().expect("reads").as_deref(),
        Some(r#"{"shotsDb":[1,2]}"#)
    );
    // Again, as after a crash that cut the first run short: nothing changes.
    let seq = store.write_seq();
    let again = store
        .import_late(&LateImport {
            rows: vec![RowIn::new(row("late", 2, json!({ "notes": "older" })))],
            source_ids: Vec::new(),
            late_ids: None,
            late_baseline: None,
        })
        .expect("imports");
    assert_eq!(again.present, ids(&["late"]));
    assert_eq!(store.write_seq(), seq, "nothing new was written");
    drop(store);
    let store = reopen(&path);
    assert_eq!(store.late_ids().expect("reads"), ids(&["late"]), "it lasts");
}

#[test]
fn a_pulled_late_row_whose_visualizer_copy_is_held_is_skipped() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&rows_in(&[row(
            "mine",
            1,
            json!({ "visualizerId": "v-1" }),
        )]))
        .expect("writes");
    let out = store
        .import_late(&LateImport {
            rows: vec![RowIn {
                row_json: row("theirs", 2, json!({ "visualizerId": "v-1" })),
                heavy: None,
                pulled: true,
            }],
            source_ids: Vec::new(),
            late_ids: None,
            late_baseline: None,
        })
        .expect("imports");
    assert_eq!(out.written.duplicates.len(), 1);
    assert_eq!(
        int(&store, "SELECT count(*) FROM shot"),
        1,
        "stored once (N8)"
    );
    assert!(
        store
            .not_in_migration_source(&ids(&["theirs"]))
            .expect("reads")
            .is_empty(),
        "and not looked at again"
    );
}

#[cfg(feature = "faults")]
#[test]
fn a_fault_inside_the_late_import_leaves_nothing_of_it() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    for point in ["upsert.row", "late.source", "late.commit"] {
        store.faults().fail_at(point, 0);
        let seq = store.write_seq();
        let out = store.import_late(&LateImport {
            rows: vec![RowIn::new(row("late", 2, json!({})))],
            source_ids: ids(&["late"]),
            late_ids: Some(ids(&["late"])),
            late_baseline: Some("b".into()),
        });
        assert!(out.is_err(), "{point}");
        assert_eq!(store.write_seq(), seq, "{point}");
        assert_eq!(int(&store, "SELECT count(*) FROM shot"), 0, "{point}");
        assert_eq!(
            int(&store, "SELECT count(*) FROM migration_source"),
            0,
            "{point}"
        );
        assert!(store.late_ids().expect("reads").is_empty(), "{point}");
    }
}

#[test]
fn the_sync_projection_carries_the_final_weight_and_the_visualizer_version() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&rows_in(&[
            row(
                "a",
                1,
                json!({ "visualizerUpdatedAt": 1_700_000_000_123_i64 }),
            ),
            row("b", 2, json!({ "summary": { "finalWeight": null } })),
            // Not a number: no value, never a failed read.
            row(
                "c",
                3,
                json!({ "visualizerUpdatedAt": "soon", "summary": { "finalWeight": "x" } }),
            ),
            row("d", 4, json!({ "summary": { "finalWeight": 40 } })),
        ]))
        .expect("writes");
    let page = store.sync_projection().expect("reads");
    let by = |id: &str| {
        page.rows
            .iter()
            .find(|r| r.id == id)
            .expect("a row")
            .clone()
    };
    assert_eq!(by("a").final_weight, Some(36.0));
    assert_eq!(by("a").visualizer_updated_at, Some(1_700_000_000_123));
    assert_eq!(by("b").final_weight, None);
    assert_eq!(by("b").visualizer_updated_at, None);
    assert_eq!(by("c").final_weight, None);
    assert_eq!(by("c").visualizer_updated_at, None);
    assert_eq!(by("d").final_weight, Some(40.0));
    let paged = store.sync_rows(i64::MIN, 10).expect("reads");
    assert_eq!(paged.rows, page.rows, "the paged reads carry them too");
}

#[test]
fn a_details_write_moves_write_seq_and_nothing_else() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&rows_in(&[row("a", 1, json!({}))]))
        .expect("writes");
    let before = store.write_seq();
    let rows = stored_rows(&store);
    let seq = store
        .note_details_written(1_800_000_000_000)
        .expect("notes");
    assert_eq!(seq, before + 1);
    assert_eq!(store.write_seq(), seq);
    assert_eq!(stored_rows(&store), rows, "no row changed");
}

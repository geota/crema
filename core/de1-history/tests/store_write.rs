//! The store's writes (SPEC §4, §9, §10): one transaction per call, a
//! `ChangeSet` with its seq, children deleted first, `shot_heavy` never
//! thinned (M10), pulled duplicates skipped (N8), client writes applied in
//! order (M11).

mod common;

#[cfg(target_arch = "wasm32")]
use common::test;

use common::{create, dir, file, int, reopen, row, rows_in, stored_rows};
use de1_history::store::{Applied, Duplicate, Heavy, RowIn, Store, StoreError, Write};
use de1_history::{ChangeKinds, Live, LiveBean, LiveMethod, SortKey};
use serde_json::json;

fn heavy(store: &Store, id: &str) -> Option<(Option<String>, Option<String>)> {
    store
        .raw()
        .query_row(
            "SELECT h.profile_json, h.brew_series_json FROM shot_heavy h JOIN shot s ON s.rid = h.rid WHERE s.id = ?1",
            [id],
            |r| Ok((r.get(0)?, r.get(1)?)),
        )
        .ok()
}

fn with_heavy(row_json: String, profile: Option<&str>, series: Option<&str>) -> RowIn {
    RowIn {
        row_json,
        heavy: Some(Heavy {
            profile_json: profile.map(str::to_owned),
            brew_series_json: series.map(str::to_owned),
        }),
        pulled: false,
    }
}

#[test]
fn an_upsert_is_one_seq_and_says_what_changed() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    let w = store
        .upsert(&rows_in(&[
            row("a", 10, json!({ "tags": ["sour"] })),
            row("b", 20, json!({})),
        ]))
        .expect("writes");
    assert_eq!(w.change.seq, 1);
    assert_eq!(w.change.upserted, ["a", "b"]);
    assert_eq!(w.change.kinds, ChangeKinds::ALL);
    assert!(w.duplicates.is_empty());
    assert_eq!(store.write_seq(), 1);
    assert_eq!(
        int(&store, "SELECT created_seq FROM shot WHERE id = 'a'"),
        1
    );
    assert_eq!(int(&store, "SELECT count(*) FROM shot_tag"), 1);

    // The same rows again: nothing changes, no seq.
    let again = store
        .upsert(&rows_in(&[row("a", 10, json!({ "tags": ["sour"] }))]))
        .expect("writes");
    assert_eq!(again.change.seq, 1);
    assert!(again.change.is_empty());

    // A star tap: stats, the rating order, the row's display; nothing else.
    let tap = store
        .upsert(&rows_in(&[row(
            "a",
            10,
            json!({ "tags": ["sour"], "metadata": { "rating": 5 } }),
        )]))
        .expect("writes");
    assert_eq!(tap.change.seq, 2);
    assert_eq!(
        tap.change.kinds,
        ChangeKinds {
            order: SortKey::Rating.bit(),
            stats: true,
            display: true,
            ..ChangeKinds::default()
        }
    );
    // The edit kept the row's first seq and moved its last.
    assert_eq!(
        int(&store, "SELECT created_seq FROM shot WHERE id = 'a'"),
        1
    );
    assert_eq!(
        int(&store, "SELECT changed_seq FROM shot WHERE id = 'a'"),
        2
    );
    store.close().expect("closes");
    assert_eq!(reopen(&path).write_seq(), 2);
}

#[test]
fn every_derived_column_is_written_from_row_json() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    let rows = common::fixture_rows("web");
    store.upsert(&rows_in(&rows)).expect("writes");
    let android = common::fixture_rows("android");
    // The Android shape of the same shots, under other ids.
    let renamed: Vec<String> = android
        .iter()
        .map(|r| {
            let mut v: serde_json::Value = serde_json::from_str(r).expect("JSON");
            v["id"] = json!(format!("android:{}", v["id"].as_str().expect("an id")));
            v.to_string()
        })
        .collect();
    store.upsert(&rows_in(&renamed)).expect("writes");
    assert_eq!(
        int(&store, "SELECT count(*) FROM shot"),
        i64::try_from(rows.len() + renamed.len()).expect("fits")
    );
    assert!(store.derived_mismatches().expect("reads").is_empty());
    // row_json is stored as given.
    let stored = stored_rows(&store);
    for r in &rows {
        let v: serde_json::Value = serde_json::from_str(r).expect("JSON");
        let id = v["id"].as_str().expect("an id");
        assert!(stored.iter().any(|(i, j)| i == id && j == r), "{id}");
    }
}

#[test]
fn a_bad_row_refuses_the_whole_write() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    for bad in ["not json", "[1, 2]", r#"{"completedAt": 1}"#] {
        let err = store
            .upsert(&rows_in(&[row("ok", 1, json!({})), bad.to_owned()]))
            .expect_err("refused");
        assert!(matches!(err, StoreError::Refused(_)), "{err:?}");
        assert_eq!(int(&store, "SELECT count(*) FROM shot"), 0);
        assert_eq!(store.write_seq(), 0);
    }
}

#[test]
fn delete_removes_children_first_and_skips_unknown_ids() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&[
            with_heavy(
                row("a", 1, json!({ "tags": ["x", "y"] })),
                Some("{}"),
                Some("[]"),
            ),
            RowIn::new(row("b", 2, json!({ "tags": ["x"] }))),
        ])
        .expect("writes");
    let c = store.delete(&["a".into(), "nope".into()]).expect("deletes");
    assert_eq!(c.deleted, ["a"]);
    assert_eq!(c.seq, 2);
    assert!(c.kinds.set);
    assert_eq!(int(&store, "SELECT count(*) FROM shot"), 1);
    assert_eq!(int(&store, "SELECT count(*) FROM shot_tag"), 1);
    assert_eq!(int(&store, "SELECT count(*) FROM shot_heavy"), 0);
    // Nothing to delete: no seq.
    let none = store.delete(&["nope".into()]).expect("deletes");
    assert!(none.is_empty());
    assert_eq!(none.seq, 2);
    // Deleting a tombstoned row changes no list.
    store
        .upsert(&rows_in(&[row("t", 3, json!({ "deletedAt": 5 }))]))
        .expect("writes");
    let t = store.delete(&["t".into()]).expect("deletes");
    assert_eq!(t.deleted, ["t"]);
    assert_eq!(t.kinds, ChangeKinds::default());
}

#[test]
fn the_schema_refuses_a_parent_only_delete_and_a_replace_with_a_new_rid() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&[with_heavy(row("a", 1, json!({})), Some("{\"p\":1}"), None)])
        .expect("writes");
    let raw = store.raw();
    assert!(raw.execute("DELETE FROM shot WHERE id = 'a'", []).is_err());
    // A REPLACE that gives the row a new rid fails on the key too.
    let replace = format!(
        "INSERT OR REPLACE INTO shot (id, row_json, created_seq, changed_seq, derive_v, completed_at, pinned, \
         is_brew_log, method, has_tags, sort_rating, sort_profile, sort_bean, sort_yield, sort_time, search_text) \
         VALUES ('a', '{}', 9, 9, 1, 1, 0, 0, 'espresso', 0, 0, '', '', 0, 0, 'x')",
        row("a", 1, json!({}))
    );
    assert!(raw.execute(&replace, []).is_err());
    assert_eq!(heavy(&store, "a"), Some((Some("{\"p\":1}".into()), None)));
}

#[test]
fn shot_heavy_is_written_after_its_row_and_never_thinned() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    // Row, then heavy, in one transaction: a failure at the heavy write
    // leaves neither.
    store.faults().fail_at("upsert.heavy", 0);
    let err = store
        .upsert(&[with_heavy(row("a", 1, json!({})), Some("P1"), Some("S1"))])
        .expect_err("fails");
    assert_eq!(err, StoreError::Fault("upsert.heavy"));
    assert_eq!(int(&store, "SELECT count(*) FROM shot"), 0);
    store
        .upsert(&[with_heavy(row("a", 1, json!({})), Some("P1"), Some("S1"))])
        .expect("writes");
    let both = Some((Some("P1".into()), Some("S1".into())));
    assert_eq!(heavy(&store, "a"), both);

    // NULL heavy fields keep what is stored.
    store
        .upsert(&[with_heavy(
            row("a", 1, json!({ "metadata": { "notes": "n" } })),
            None,
            None,
        )])
        .expect("writes");
    assert_eq!(heavy(&store, "a"), both);
    // A light edit, with no heavy at all.
    store
        .upsert(&rows_in(&[row(
            "a",
            1,
            json!({ "metadata": { "rating": 4 } }),
        )]))
        .expect("writes");
    assert_eq!(heavy(&store, "a"), both);
    // One field given: only that one moves.
    let w = store
        .upsert(&[with_heavy(
            row("a", 1, json!({ "metadata": { "rating": 4 } })),
            None,
            Some("S2"),
        )])
        .expect("writes");
    assert_eq!(w.change.upserted, ["a"], "a heavy-only change is a change");
    assert_eq!(
        heavy(&store, "a"),
        Some((Some("P1".into()), Some("S2".into())))
    );
    // The same heavy again: nothing.
    let seq = store.write_seq();
    let same = store
        .upsert(&[with_heavy(
            row("a", 1, json!({ "metadata": { "rating": 4 } })),
            None,
            Some("S2"),
        )])
        .expect("writes");
    assert!(same.change.is_empty());
    assert_eq!(store.write_seq(), seq);
    // A derive rewrite.
    store
        .raw()
        .execute("UPDATE shot SET derive_v = 0, sort_rating = 99", [])
        .expect("stales");
    store.close().expect("closes");
    let mut store = reopen(&path);
    assert!(store.health().derive_pending);
    store.maintain_all(100).expect("maintains");
    assert!(!store.health().derive_pending);
    assert_eq!(
        heavy(&store, "a"),
        Some((Some("P1".into()), Some("S2".into())))
    );
    // A REPLACE that keeps the rid keeps the heavy row.
    let rid = int(&store, "SELECT rid FROM shot WHERE id = 'a'");
    store
        .raw()
        .execute_batch(&format!(
            "INSERT OR REPLACE INTO shot (rid, id, row_json, created_seq, changed_seq, derive_v, completed_at, pinned, \
             is_brew_log, method, has_tags, sort_rating, sort_profile, sort_bean, sort_yield, sort_time, search_text) \
             VALUES ({rid}, 'a', '{{\"id\":\"a\"}}', 9, 9, 1, 1, 0, 0, 'espresso', 0, 0, '', '', 0, 0, 'x')"
        ))
        .expect("replaces");
    assert_eq!(
        heavy(&store, "a"),
        Some((Some("P1".into()), Some("S2".into())))
    );
    // Only delete removes it.
    store.delete(&["a".into()]).expect("deletes");
    assert_eq!(int(&store, "SELECT count(*) FROM shot_heavy"), 0);
}

#[test]
fn set_live_rewrites_the_mirror_and_skips_an_unchanged_library() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    let live = Live {
        beans: vec![LiveBean {
            bean_id: "b1".into(),
            name: " Guji ".into(),
            roaster: Some("Onyx".into()),
            tags: vec!["Floral".into(), "Floral".into()],
        }],
        methods: vec![LiveMethod {
            method: "custom:x".into(),
            label: "Cafézinho".into(),
        }],
    };
    let c = store.set_live(&live).expect("writes");
    assert_eq!(c.seq, 1);
    assert!(c.kinds.facet && c.kinds.text && !c.kinds.set);
    assert_eq!(int(&store, "SELECT count(*) FROM live_bean"), 1);
    assert_eq!(int(&store, "SELECT count(*) FROM live_bean_tag"), 1);
    let label: String = store
        .raw()
        .query_row("SELECT label_folded FROM live_method", [], |r| r.get(0))
        .expect("a label");
    assert_eq!(label, "cafezinho");
    assert!(store.set_live(&live).expect("writes").is_empty());
    assert_eq!(store.write_seq(), 1);
    let c = store.set_live(&Live::default()).expect("writes");
    assert_eq!(c.seq, 2);
    assert_eq!(int(&store, "SELECT count(*) FROM live_bean"), 0);
}

#[test]
fn a_pulled_row_whose_visualizer_id_is_held_is_not_inserted() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&rows_in(&[row(
            "local",
            1,
            json!({ "visualizerId": "v1" }),
        )]))
        .expect("writes");
    let pulled = |id: &str| RowIn {
        row_json: row(id, 2, json!({ "visualizerId": "v1" })),
        heavy: Some(Heavy {
            profile_json: Some("P".into()),
            brew_series_json: None,
        }),
        pulled: true,
    };
    let w = store.upsert(&[pulled("remote")]).expect("writes");
    assert_eq!(
        w.duplicates,
        [Duplicate {
            id: "remote".into(),
            existing_id: "local".into()
        }]
    );
    assert!(w.change.is_empty());
    assert_eq!(int(&store, "SELECT count(*) FROM shot"), 1);
    assert_eq!(int(&store, "SELECT count(*) FROM shot_heavy"), 0);
    // A pulled row that is already ours updates as usual.
    let w = store.upsert(&[pulled("local")]).expect("writes");
    assert!(w.duplicates.is_empty());
    assert_eq!(w.change.upserted, ["local"]);
    // Two pulls of one remote shot in one write store it once.
    let mut both = pulled("p1");
    both.row_json = row("p1", 3, json!({ "visualizerId": "v2" }));
    let mut again = both.clone();
    again.row_json = row("p2", 3, json!({ "visualizerId": "v2" }));
    let w = store.upsert(&[both, again]).expect("writes");
    assert_eq!(w.change.upserted, ["p1"]);
    assert_eq!(w.duplicates.len(), 1);
    // A row that isn't pulled is written whatever its visualizer id.
    store
        .upsert(&rows_in(&[row("copy", 4, json!({ "visualizerId": "v1" }))]))
        .expect("writes");
    assert_eq!(
        int(
            &store,
            "SELECT count(*) FROM shot WHERE visualizer_id = 'v1'"
        ),
        2
    );
}

#[test]
fn every_fault_point_leaves_the_write_whole_or_absent() {
    for (point, landed) in [
        ("upsert.row", false),
        ("upsert.tags", false),
        ("upsert.commit", false),
        ("upsert.committed", true),
    ] {
        let d = dir();
        let mut store = create(&file(&d, "history.db"));
        // The second row's statement, or the one commit.
        let after = u32::from(!point.contains("commit"));
        store.faults().fail_at(point, after);
        let err = store
            .upsert(&rows_in(&[
                row("a", 1, json!({ "tags": ["t"] })),
                row("b", 2, json!({ "tags": ["t"] })),
            ]))
            .expect_err("fails");
        assert_eq!(err, StoreError::Fault(point));
        let n = int(&store, "SELECT count(*) FROM shot");
        assert_eq!(n, if landed { 2 } else { 0 }, "{point}");
        assert_eq!(int(&store, "SELECT count(*) FROM shot_tag"), n, "{point}");
        assert_eq!(store.write_seq(), u64::from(landed), "{point}");
        assert!(common::fts_whole(&store));
    }
    for (point, landed) in [
        ("delete.row", false),
        ("delete.commit", false),
        ("delete.committed", true),
    ] {
        let d = dir();
        let mut store = create(&file(&d, "history.db"));
        store
            .upsert(&rows_in(&[
                row("a", 1, json!({ "tags": ["t"] })),
                row("b", 2, json!({})),
            ]))
            .expect("writes");
        store.faults().fail_at(point, 0);
        let err = store.delete(&["a".into(), "b".into()]).expect_err("fails");
        assert_eq!(err, StoreError::Fault(point));
        let n = int(&store, "SELECT count(*) FROM shot");
        assert_eq!(n, if landed { 0 } else { 2 }, "{point}");
        assert!(common::fts_whole(&store));
    }
}

/// A row for tab writes: `id` rated `rating`.
fn rated(id: &str, rating: i64) -> Write {
    Write::Upsert(rows_in(&[row(
        id,
        1,
        json!({ "metadata": { "rating": rating } }),
    )]))
}

fn rating_of(store: &Store, id: &str) -> i64 {
    store
        .raw()
        .query_row("SELECT sort_rating FROM shot WHERE id = ?1", [id], |r| {
            r.get(0)
        })
        .expect("a row")
}

#[test]
fn apply_takes_each_tabs_writes_once_and_in_order() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    // Tab A's first write.
    let a1 = store.apply("A", 1, 100, &rated("s", 1)).expect("applies");
    assert!(matches!(a1, Applied::Done(ref w) if w.change.seq == 1));
    // Tab A's third, before its second: it waits.
    assert_eq!(
        store.apply("A", 3, 100, &rated("s", 3)).expect("answers"),
        Applied::Gap { last: 1 }
    );
    assert_eq!(rating_of(&store, "s"), 1);
    // Tab B's first lands between.
    assert!(matches!(
        store.apply("B", 1, 100, &rated("s", 4)).expect("applies"),
        Applied::Done(_)
    ));
    // A resends its outbox whole, in order: 2, then 3.
    assert!(matches!(
        store.apply("A", 2, 100, &rated("s", 2)).expect("applies"),
        Applied::Done(_)
    ));
    assert!(matches!(
        store.apply("A", 3, 100, &rated("s", 3)).expect("applies"),
        Applied::Done(_)
    ));
    assert_eq!(rating_of(&store, "s"), 3);
    // B's first arrives again late: already applied, and it doesn't undo A's.
    assert_eq!(
        store.apply("B", 1, 100, &rated("s", 4)).expect("answers"),
        Applied::AlreadyApplied { last: 1 }
    );
    assert_eq!(rating_of(&store, "s"), 3);
    // The applied seqs survive a new owner.
    store.close().expect("closes");
    let mut store = reopen(&path);
    assert_eq!(store.applied_seq("A").expect("reads"), 3);
    assert_eq!(
        store.apply("A", 2, 100, &rated("s", 2)).expect("answers"),
        Applied::AlreadyApplied { last: 3 }
    );
    assert_eq!(rating_of(&store, "s"), 3);
    // A tab's first seq is 1.
    assert_eq!(
        store.apply("C", 2, 100, &rated("s", 5)).expect("answers"),
        Applied::Gap { last: 0 }
    );
    // Deletes and the live mirror go through the same rule.
    assert!(matches!(
        store.apply("C", 1, 100, &Write::Delete(vec!["s".into()])).expect("applies"),
        Applied::Done(ref w) if w.change.deleted == ["s"]
    ));
    assert!(matches!(
        store
            .apply("C", 2, 100, &Write::SetLive(Live::default()))
            .expect("applies"),
        Applied::Done(_)
    ));
    assert_eq!(
        store
            .apply("C", 1, 100, &Write::Delete(vec!["s".into()]))
            .expect("answers"),
        Applied::AlreadyApplied { last: 2 }
    );
}

#[test]
fn a_write_and_its_applied_seq_land_together() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store.faults().fail_at("apply.commit", 0);
    assert_eq!(
        store.apply("A", 1, 1, &rated("s", 2)),
        Err(StoreError::Fault("apply.commit"))
    );
    assert_eq!(store.applied_seq("A").expect("reads"), 0);
    assert_eq!(int(&store, "SELECT count(*) FROM shot"), 0);
    // After the commit, before the answer: both landed, so the resend is
    // already applied.
    store.faults().fail_at("apply.committed", 0);
    assert_eq!(
        store.apply("A", 1, 1, &rated("s", 2)),
        Err(StoreError::Fault("apply.committed"))
    );
    assert_eq!(
        store.apply("A", 1, 1, &rated("s", 2)).expect("answers"),
        Applied::AlreadyApplied { last: 1 }
    );
    // A write refused for good consumes its seq and writes nothing of
    // itself, so the tab's next write isn't stuck behind it (REVIEW-439
    // note 7).
    let bad = Write::Upsert(rows_in(&[
        row("good-before-bad", 1, json!({})),
        "{}".to_owned(),
    ]));
    assert!(matches!(
        store.apply("A", 2, 1, &bad).expect("answers"),
        Applied::Refused { .. }
    ));
    assert_eq!(store.applied_seq("A").expect("reads"), 2);
    assert_eq!(
        int(
            &store,
            "SELECT count(*) FROM shot WHERE id = 'good-before-bad'"
        ),
        0
    );
    let seq = store.write_seq();
    assert!(matches!(
        store.apply("A", 3, 1, &rated("s", 4)).expect("applies"),
        Applied::Done(ref w) if w.change.seq == seq + 1
    ));
    // Its resend is already applied, not refused again.
    assert_eq!(
        store.apply("A", 2, 1, &bad).expect("answers"),
        Applied::AlreadyApplied { last: 3 }
    );
}

#[test]
fn prune_forgets_only_gone_tabs_past_the_cutoff() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store.apply("old", 1, 100, &rated("s", 1)).expect("applies");
    store.apply("new", 1, 900, &rated("s", 2)).expect("applies");
    store
        .apply("alive", 1, 100, &rated("s", 3))
        .expect("applies");
    let n = store
        .prune_tabs(&["old".into(), "new".into()], 500)
        .expect("prunes");
    assert_eq!(n, 1);
    assert_eq!(store.applied_seq("old").expect("reads"), 0);
    assert_eq!(store.applied_seq("new").expect("reads"), 1);
    assert_eq!(store.applied_seq("alive").expect("reads"), 1);
    // A pruned tab that comes back (frozen, then resumed) with its next seq
    // waits for good: `Gap { last: 0 }` tells it the store forgot it, and it
    // takes a new tab id, starting at 1 (REVIEW-439 note 7).
    assert_eq!(
        store
            .apply("old", 2, 1_000, &rated("s", 5))
            .expect("answers"),
        Applied::Gap { last: 0 }
    );
    assert!(matches!(
        store
            .apply("old-2", 1, 1_000, &rated("s", 5))
            .expect("applies"),
        Applied::Done(_)
    ));
    assert_eq!(rating_of(&store, "s"), 5);
}

/// Three tabs' outboxes, delivered late, twice and interleaved, with the
/// store reopened between deliveries: each tab's writes apply once, in order,
/// and the last user action wins (M11).
#[test]
fn three_tabs_resending_across_reopens_apply_each_write_once() {
    let d = dir();
    let path = file(&d, "history.db");
    // Each tab rates its own shot 1..=5 and the shared one with its number.
    let tabs = ["A", "B", "C"];
    let outbox = |tab: usize, seq: u64| -> Write {
        let rating = i64::try_from(seq).expect("small");
        let own = format!("own-{}", tabs[tab]);
        Write::Upsert(rows_in(&[
            row(
                &own,
                1,
                json!({ "metadata": { "rating": rating, "notes": format!("{seq}") } }),
            ),
            row(
                "shared",
                1,
                json!({ "metadata": { "notes": format!("{}{seq}", tabs[tab]) } }),
            ),
        ]))
    };
    // A delivery order with delays and resends: (tab, seq).
    let deliveries: Vec<(usize, u64)> = vec![
        (0, 1),
        (1, 2),
        (2, 1),
        (0, 3),
        (1, 1),
        (0, 2),
        (0, 3),
        (1, 2),
        (2, 1),
        (2, 2),
        (1, 3),
        (0, 1),
        (2, 4),
        (2, 3),
        (2, 4),
        (1, 4),
        (1, 5),
        (0, 4),
        (0, 5),
        (2, 5),
        (1, 5),
        (0, 5),
    ];
    let mut store = create(&path);
    let mut applied: Vec<(usize, u64)> = Vec::new();
    for (n, &(tab, seq)) in deliveries.iter().enumerate() {
        if n % 5 == 4 {
            store.close().expect("closes");
            store = reopen(&path);
        }
        match store
            .apply(tabs[tab], seq, 0, &outbox(tab, seq))
            .expect("answers")
        {
            Applied::Done(_) => applied.push((tab, seq)),
            Applied::AlreadyApplied { last } => assert!(seq <= last),
            Applied::Gap { last } => assert!(seq > last + 1),
            Applied::Refused { reason } => panic!("{reason}"),
        }
    }
    // Every write applied exactly once, each tab's in order.
    for (tab, name) in tabs.iter().enumerate() {
        let seqs: Vec<u64> = applied
            .iter()
            .filter(|(t, _)| *t == tab)
            .map(|(_, s)| *s)
            .collect();
        assert_eq!(seqs, [1, 2, 3, 4, 5], "tab {name}");
        assert_eq!(rating_of(&store, &format!("own-{name}")), 5);
    }
    // The shared row holds the last applied write's notes.
    let (last_tab, last_seq) = *applied.last().expect("applied");
    let notes: String = store
        .raw()
        .query_row(
            "SELECT json_extract(row_json, '$.metadata.notes') FROM shot WHERE id = 'shared'",
            [],
            |r| r.get(0),
        )
        .expect("a row");
    assert_eq!(notes, format!("{}{last_seq}", tabs[last_tab]));
}

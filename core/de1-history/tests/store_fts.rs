//! Search and its index (SPEC §5, M6, S15): the trigram index and the scan
//! path both give the reference's substring rule, the index survives damage
//! as derived data, and the batched rebuild keeps every write.

mod common;

#[cfg(target_arch = "wasm32")]
use common::test;

use std::cell::RefCell;
use std::collections::BTreeMap;

use common::{create, dir, file, fixture_rows, fts_whole, int, reopen, row, rows_in};
use de1_history::reference::{RefRow, filter_sort, ref_rows};
use de1_history::schema::FTS_TRIGGERS_V1;
use de1_history::sql::TextPath;
use de1_history::store::{FtsCheck, FtsStatus, Maintained, Store, StoreError};
use de1_history::{HistoryQuery, Live, fold};
use serde_json::{Value, json};

/// The rows a store should hold, by id, to hold it to the reference.
#[derive(Default)]
struct Model(BTreeMap<String, String>, RefCell<Option<Vec<RefRow>>>);

impl Model {
    fn put(&mut self, rows: &[String]) {
        for r in rows {
            let v: Value = serde_json::from_str(r).expect("JSON");
            self.0
                .insert(v["id"].as_str().expect("an id").to_owned(), r.clone());
        }
        self.1.replace(None);
    }

    fn remove(&mut self, ids: &[String]) {
        for id in ids {
            self.0.remove(id);
        }
        self.1.replace(None);
    }

    fn expect(&self, q: &HistoryQuery) -> Vec<String> {
        let mut cache = self.1.borrow_mut();
        let refs = cache.get_or_insert_with(|| {
            let values: Vec<Value> = self
                .0
                .values()
                .map(|r| serde_json::from_str(r).expect("JSON"))
                .collect();
            ref_rows(&values)
        });
        filter_sort(refs, q, &Live::default())
    }
}

fn search(text: &str) -> HistoryQuery {
    HistoryQuery {
        text: text.into(),
        ..HistoryQuery::default()
    }
}

/// Every 3- to 6-character run of each row's searchable text, folded, a few
/// per row, plus their upper-case and accented spellings.
fn needles(rows: &[String]) -> Vec<String> {
    let mut out = Vec::new();
    for (n, r) in rows.iter().enumerate().step_by(13) {
        let v: Value = serde_json::from_str(r).expect("JSON");
        let fields = [
            v["profileName"].as_str(),
            v["metadata"]["notes"].as_str(),
            v["bean"]["name"].as_str(),
            v["bean"]["roasterName"].as_str(),
            v["metadata"]["barista"].as_str(),
        ];
        for f in fields.into_iter().flatten() {
            let chars: Vec<char> = fold(f).chars().collect();
            for len in 3..=6 {
                if chars.len() >= len {
                    let start = (n * 3) % (chars.len() - len + 1);
                    let s: String = chars[start..start + len].iter().collect();
                    out.push(s.clone());
                    out.push(s.to_uppercase());
                }
            }
            out.push(f.to_owned());
        }
    }
    out.extend(
        [
            "ESPRESSO", "spres", "etiopia", "Etiopía", "zzzqqq", "café", "cafe",
        ]
        .map(str::to_owned),
    );
    out.sort();
    out.dedup();
    out
}

fn assert_searches(store: &mut Store, model: &Model, needles: &[String], path: TextPath) {
    assert_eq!(store.text_path(), path);
    for n in needles {
        let q = search(n);
        let want = model.expect(&q);
        assert_eq!(
            store.listed_ids(&q).expect("answers"),
            want,
            "{n:?} on {path:?}"
        );
        assert_eq!(
            store.count(&q).expect("answers") as usize,
            want.len(),
            "{n:?}"
        );
    }
}

fn loaded() -> (common::TestDir, Store, Model, Vec<String>) {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    let rows = fixture_rows("web");
    store.upsert(&rows_in(&rows)).expect("writes");
    let mut model = Model::default();
    model.put(&rows);
    let needles = needles(&rows);
    (d, store, model, needles)
}

#[test]
fn the_index_and_the_scan_give_the_substring_rule() {
    let (_d, mut store, model, needles) = loaded();
    assert!(needles.len() > 100);
    assert_searches(&mut store, &model, &needles, TextPath::Fts);
    store.start_fts_rebuild().expect("starts");
    assert_searches(&mut store, &model, &needles, TextPath::Scan);
    store.maintain_all(1_000).expect("maintains");
    assert_eq!(store.fts_status(), FtsStatus::Ready);
    assert_searches(&mut store, &model, &needles, TextPath::Fts);
}

#[test]
fn a_short_search_is_no_search_and_runs_no_text_statement() {
    let (_d, mut store, model, _) = loaded();
    let all = store.listed_ids(&HistoryQuery::default()).expect("answers");
    assert_eq!(all, model.expect(&HistoryQuery::default()));
    for text in ["e", "Es", " é ", "zz", "ŝp"] {
        for rebuild in [false, true] {
            if rebuild {
                store.start_fts_rebuild().expect("starts");
            }
            store.faults().start_log();
            assert_eq!(
                store.listed_ids(&search(text)).expect("answers"),
                all,
                "{text:?}"
            );
            assert_eq!(
                store.count(&search(text)).expect("answers") as usize,
                all.len()
            );
            let log = store.faults().take_log();
            assert_eq!(log.len(), 2);
            for sql in log {
                assert!(!sql.contains("shot_fts") && !sql.contains("instr"), "{sql}");
            }
        }
        store.maintain_all(1_000).expect("maintains");
    }
}

#[test]
fn query_syntax_in_user_text_is_literal_and_no_match_spans_two_fields() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    let notes = [
        "a*b and more",
        "axxb",
        "x?y",
        "xzy",
        "[z] bracket",
        "z bracket",
        "say \"hi\" now",
        "near the NEAR",
        "this AND that",
        "this that",
        "col:umn",
        "^caret$",
    ];
    let mut rows: Vec<String> = notes
        .iter()
        .enumerate()
        .map(|(i, n)| {
            row(
                &format!("n{i:02}"),
                100 + i64::try_from(i).expect("small"),
                json!({ "metadata": { "notes": n } }),
            )
        })
        .collect();
    // Profile "turbo", notes "bloom": "turbo\u{1f}bloom" holds "bob" only
    // across the separator.
    rows.push(row(
        "split",
        1,
        json!({ "profileName": "Turbo", "metadata": { "notes": "bloom" } }),
    ));
    store.upsert(&rows_in(&rows)).expect("writes");
    let mut model = Model::default();
    model.put(&rows);
    let cases = [
        ("a*b", vec!["n00"]),
        ("x?y", vec!["n02"]),
        ("[z]", vec!["n04"]),
        ("\"hi\"", vec!["n06"]),
        ("near", vec!["n07"]),
        ("NEAR the", vec!["n07"]),
        ("this AND", vec!["n08"]),
        ("and tha", vec!["n08"]),
        ("s that", vec!["n09"]),
        ("col:umn", vec!["n10"]),
        ("^caret$", vec!["n11"]),
        ("bob", vec![]),
        ("o\u{1f}bl", vec![]),
    ];
    for rebuild in [false, true] {
        if rebuild {
            store.start_fts_rebuild().expect("starts");
        }
        for (text, want) in &cases {
            let q = search(text);
            let got = store.listed_ids(&q).expect("answers");
            assert_eq!(got, *want, "{text:?}");
            assert_eq!(got, model.expect(&q), "{text:?}");
        }
    }
}

/// Removes `id`'s row behind the index's back: its entry stays, stale.
fn plant_stale(store: &Store, id: &str) {
    store
        .raw()
        .execute_batch(&format!(
            "DROP TRIGGER shot_fts_ad;
             DELETE FROM shot_tag WHERE rid = (SELECT rid FROM shot WHERE id = '{id}');
             DELETE FROM shot_heavy WHERE rid = (SELECT rid FROM shot WHERE id = '{id}');
             DELETE FROM shot WHERE id = '{id}';"
        ))
        .expect("plants");
    // Put the delete trigger back as it was.
    let ad = FTS_TRIGGERS_V1
        .split("END;")
        .find(|t| t.contains("shot_fts_ad"))
        .expect("the delete trigger");
    store
        .raw()
        .execute_batch(&format!("{ad}END;"))
        .expect("restores");
}

#[test]
fn a_damaged_index_gives_reindexing_never_corrupt_and_is_rebuilt() {
    let (_d, mut store, mut model, needles) = loaded();
    let victim = model.0.keys().nth(10).expect("a row").clone();
    let text: String = store
        .raw()
        .query_row(
            "SELECT profile_name FROM shot WHERE id = ?1",
            [&victim],
            |r| r.get(0),
        )
        .expect("a profile");
    plant_stale(&store, &victim);
    model.remove(std::slice::from_ref(&victim));
    // The planted entry really breaks the index.
    let broken = store.raw().query_row(
        "SELECT count(*) FROM shot_fts WHERE search_text GLOB ?1",
        [format!("*{}*", fold(&text))],
        |r| r.get::<_, i64>(0),
    );
    assert!(broken.is_err());
    assert!(!fts_whole(&store));

    // A search that touches it answers, by scan, with health `reindexing`.
    let q = search(&text);
    assert_eq!(store.listed_ids(&q).expect("answers"), model.expect(&q));
    let health = store.health();
    assert!(health.reindexing);
    assert_eq!(health.fts, FtsStatus::Missing);
    assert_eq!(store.text_path(), TextPath::Scan);
    assert_eq!(
        int(
            &store,
            "SELECT count(*) FROM sqlite_schema WHERE name LIKE 'shot_fts%'"
        ),
        0
    );

    // Writes go on while it rebuilds.
    let more: Vec<String> = (0..20)
        .map(|i| {
            row(
                &format!("new{i}"),
                2_000_000_000_000 + i,
                json!({ "profileName": "Gaggiuino" }),
            )
        })
        .collect();
    store.upsert(&rows_in(&more)).expect("writes");
    model.put(&more);
    let gone = vec![model.0.keys().next().expect("a row").clone()];
    store.delete(&gone).expect("deletes");
    model.remove(&gone);
    assert_searches(&mut store, &model, &needles, TextPath::Scan);

    store.set_batch_rows(50);
    let mut batches = 0;
    loop {
        match store.maintain().expect("maintains") {
            Maintained::FtsBatch { rows } => {
                assert!(rows <= 50);
                batches += 1;
                assert_eq!(store.text_path(), TextPath::Scan);
            }
            Maintained::Idle => break,
            _ => {}
        }
    }
    assert!(batches >= 6);
    assert_eq!(store.fts_status(), FtsStatus::Ready);
    assert!(!store.health().reindexing);
    assert_eq!(store.fts_integrity_check().expect("checks"), FtsCheck::Ok);
    assert_searches(&mut store, &model, &needles, TextPath::Fts);
}

#[test]
fn the_integrity_check_finds_a_stale_entry_and_drops_the_index() {
    let (_d, mut store, model, needles) = loaded();
    assert_eq!(store.fts_integrity_check().expect("checks"), FtsCheck::Ok);
    let victim = model.0.keys().nth(3).expect("a row").clone();
    plant_stale(&store, &victim);
    let mut model = model;
    model.remove(&[victim]);
    assert_eq!(
        store.fts_integrity_check().expect("checks"),
        FtsCheck::Damaged
    );
    assert_eq!(store.fts_status(), FtsStatus::Missing);
    assert_eq!(
        store.fts_integrity_check().expect("checks"),
        FtsCheck::NotReady
    );
    assert_searches(&mut store, &model, &needles, TextPath::Scan);
    store.maintain_all(1_000).expect("maintains");
    assert_eq!(store.fts_integrity_check().expect("checks"), FtsCheck::Ok);
}

/// The check reads the whole index in one statement (B1: 0.9 s at 100,000
/// rows), so it never runs while a shot does: even a stale entry waits.
#[test]
fn the_integrity_check_waits_while_a_shot_runs() {
    let (_d, mut store, model, _) = loaded();
    let victim = model.0.keys().nth(3).expect("a row").clone();
    plant_stale(&store, &victim);
    store.set_busy(true);
    assert_eq!(
        store.fts_integrity_check().expect("checks"),
        FtsCheck::NotReady
    );
    assert_eq!(store.fts_status(), FtsStatus::Ready, "nothing dropped yet");
    store.set_busy(false);
    assert_eq!(
        store.fts_integrity_check().expect("checks"),
        FtsCheck::Damaged
    );
}

#[test]
fn writes_between_rebuild_batches_end_up_in_the_new_index() {
    let (d, mut store, mut model, needles) = loaded();
    store.set_batch_rows(40);
    store.start_fts_rebuild().expect("starts");
    let ids: Vec<String> = model.0.keys().cloned().collect();
    let mut step = 0usize;
    while let FtsStatus::Building { cursor } = store.fts_status() {
        // Between batches: rows below and above the cursor edited, deleted,
        // and new rows added.
        let below: Vec<String> = ids
            .iter()
            .filter(|id| model.0.contains_key(*id))
            .filter(|id| {
                int(
                    &store,
                    &format!("SELECT coalesce((SELECT rid FROM shot WHERE id = '{id}'), 0)"),
                ) <= cursor
            })
            .take(2)
            .cloned()
            .collect();
        let above: Vec<String> = ids
            .iter()
            .filter(|id| model.0.contains_key(*id))
            .rev()
            .take(2)
            .cloned()
            .collect();
        let mut edits = Vec::new();
        for id in below.iter().chain(above.iter()).take(3) {
            let mut v: Value = serde_json::from_str(&model.0[id]).expect("JSON");
            v["metadata"]["notes"] = json!(format!("rebuilt edit {step} quokka"));
            edits.push(v.to_string());
        }
        edits.push(row(
            &format!("added{step}"),
            1_000 + i64::try_from(step).expect("small"),
            json!({ "metadata": { "notes": "wombat" } }),
        ));
        store.upsert(&rows_in(&edits)).expect("writes");
        model.put(&edits);
        if let Some(id) = below.get(1).or(above.last()) {
            store.delete(std::slice::from_ref(id)).expect("deletes");
            model.remove(std::slice::from_ref(id));
        }
        if step == 3 {
            // A reopen mid-build resumes it.
            drop(store);
            store = reopen(&file(&d, "history.db"));
            store.set_batch_rows(40);
            assert!(matches!(store.fts_status(), FtsStatus::Building { .. }));
        }
        match store.maintain().expect("maintains") {
            Maintained::FtsBatch { rows } => assert!(rows <= 40),
            Maintained::FtsSwapped | Maintained::Merged { .. } | Maintained::Idle => {}
            other => panic!("{other:?}"),
        }
        step += 1;
    }
    assert!(step > 7);
    assert_eq!(store.fts_status(), FtsStatus::Ready);
    assert!(fts_whole(&store));
    let mut all = needles;
    all.extend(["quokka", "wombat", "rebuilt edit"].map(str::to_owned));
    assert_searches(&mut store, &model, &all, TextPath::Fts);
}

#[test]
fn the_swap_is_one_transaction() {
    let (_d, mut store, model, needles) = loaded();
    store.start_fts_rebuild().expect("starts");
    store.set_batch_rows(100);
    store.faults().fail_at("fts.swap", 0);
    let mut batches = 0;
    let before = loop {
        let cursor = match store.fts_status() {
            FtsStatus::Building { cursor } => cursor,
            other => panic!("{other:?}"),
        };
        match store.maintain() {
            Ok(Maintained::FtsBatch { rows }) => {
                assert_eq!(rows, 100);
                batches += 1;
            }
            Err(StoreError::Fault("fts.swap")) => break cursor,
            other => panic!("{other:?}"),
        }
    };
    assert_eq!(batches, 2);
    // Nothing of the last batch or the swap landed: still building, at the
    // same cursor, both tables as they were.
    assert_eq!(store.fts_status(), FtsStatus::Building { cursor: before });
    assert_eq!(
        int(
            &store,
            "SELECT count(*) FROM sqlite_schema WHERE name = 'shot_fts_next'"
        ),
        1
    );
    assert_eq!(
        int(
            &store,
            "SELECT count(*) FROM sqlite_schema WHERE name = 'shot_fts'"
        ),
        1
    );
    assert_searches(&mut store, &model, &needles, TextPath::Scan);
    assert_eq!(store.maintain().expect("swaps"), Maintained::FtsSwapped);
    assert_eq!(
        int(
            &store,
            "SELECT count(*) FROM sqlite_schema WHERE name = 'shot_fts_next'"
        ),
        0
    );
    assert_eq!(
        int(
            &store,
            "SELECT count(*) FROM sqlite_schema WHERE type = 'trigger'"
        ),
        3
    );
    assert!(fts_whole(&store));
    assert_searches(&mut store, &model, &needles, TextPath::Fts);
}

#[test]
fn a_fault_at_any_build_step_loses_nothing() {
    for point in [
        "fts.build.begin",
        "fts.build.batch",
        "fts.build.commit",
        "fts.swap",
        "fts.merge",
    ] {
        let (_d, mut store, model, needles) = loaded();
        store.set_batch_rows(100);
        let once = ["fts.build.begin", "fts.swap"].contains(&point);
        store.faults().fail_at(point, u32::from(!once));
        let mut faulted = match store.start_fts_rebuild() {
            Ok(()) => false,
            Err(e) => {
                assert_eq!(e, StoreError::Fault(point));
                true
            }
        };
        for _ in 0..100 {
            match store.maintain() {
                Ok(Maintained::Idle) => break,
                Ok(_) => {}
                Err(StoreError::Fault(p)) => {
                    assert_eq!(p, point);
                    faulted = true;
                }
                Err(e) => panic!("{point}: {e}"),
            }
        }
        assert!(faulted, "{point}");
        assert_eq!(store.fts_status(), FtsStatus::Ready, "{point}");
        assert!(fts_whole(&store), "{point}");
        assert_searches(&mut store, &model, &needles[..20], TextPath::Fts);
    }
}

#[test]
fn merges_run_in_steps_when_idle_and_never_while_busy() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    for i in 0..60 {
        store
            .upsert(&rows_in(&[row(
                &format!("r{i}"),
                i,
                json!({ "metadata": { "notes": format!("note {i}") } }),
            )]))
            .expect("writes");
    }
    store.set_busy(true);
    assert_eq!(store.maintain().expect("answers"), Maintained::Busy);
    store.set_busy(false);
    let mut merged = 0;
    loop {
        match store.maintain().expect("maintains") {
            Maintained::Merged { .. } => merged += 1,
            Maintained::Idle => break,
            other => panic!("{other:?}"),
        }
    }
    assert!(merged >= 1);
    assert!(fts_whole(&store));
}

/// A small deterministic generator, so the sequence is the same every run.
struct Lcg(u64);

impl Lcg {
    fn next(&mut self, n: u64) -> u64 {
        self.0 = self
            .0
            .wrapping_mul(6_364_136_223_846_793_005)
            .wrapping_add(1_442_695_040_888_963_407);
        (self.0 >> 33) % n
    }
}

#[test]
fn random_writes_keep_the_index_and_every_derived_column_in_step() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    let mut model = Model::default();
    let mut rng = Lcg(7);
    let words = [
        "Café",
        "spresso",
        "bloom",
        "Ñandú",
        "茶道",
        "🙂 smile",
        "sour",
        "",
    ];
    for step in 0..400 {
        let id = format!("s{}", rng.next(40));
        match rng.next(5) {
            0 | 1 => {
                // An edit that changes the search text.
                let notes = format!(
                    "{} {step}",
                    words[usize::try_from(rng.next(8)).expect("small")]
                );
                let r = row(
                    &id,
                    i64::try_from(rng.next(5)).expect("small"),
                    json!({ "metadata": { "notes": notes }, "tags": [words[usize::try_from(rng.next(8)).expect("small")]] }),
                );
                store
                    .upsert(&rows_in(std::slice::from_ref(&r)))
                    .expect("writes");
                model.put(&[r]);
            }
            2 => {
                // An edit that doesn't: the rating only.
                if let Some(old) = model.0.get(&id).cloned() {
                    let mut v: Value = serde_json::from_str(&old).expect("JSON");
                    v["metadata"]["rating"] = json!(rng.next(6));
                    let r = v.to_string();
                    store
                        .upsert(&rows_in(std::slice::from_ref(&r)))
                        .expect("writes");
                    model.put(&[r]);
                }
            }
            3 => {
                store.delete(std::slice::from_ref(&id)).expect("deletes");
                model.remove(&[id]);
            }
            _ => {
                store.maintain().expect("maintains");
            }
        }
    }
    assert!(fts_whole(&store));
    assert!(store.derived_mismatches().expect("reads").is_empty());
    for n in ["cafe", "spres", "nandu", "茶道", "smile", "sou"] {
        let q = search(n);
        assert_eq!(
            store.listed_ids(&q).expect("answers"),
            model.expect(&q),
            "{n}"
        );
    }
}

#[test]
fn an_insert_or_replace_sneaking_in_fails_the_consistency_check() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&rows_in(&[row(
            "a",
            1,
            json!({ "metadata": { "notes": "original" } }),
        )]))
        .expect("writes");
    assert!(fts_whole(&store));
    let rid = int(&store, "SELECT rid FROM shot WHERE id = 'a'");
    // REPLACE's implicit delete fires no delete trigger: the old entry stays.
    store
        .raw()
        .execute_batch(&format!(
            "INSERT OR REPLACE INTO shot (rid, id, row_json, created_seq, changed_seq, derive_v, completed_at, pinned, \
             is_brew_log, method, has_tags, sort_rating, sort_profile, sort_bean, sort_yield, sort_time, search_text) \
             VALUES ({rid}, 'a', '{{\"id\":\"a\"}}', 9, 9, 1, 1, 0, 0, 'espresso', 0, 0, '', '', 0, 0, 'replaced')"
        ))
        .expect("replaces");
    assert!(!fts_whole(&store));
    assert!(!store.derived_mismatches().expect("reads").is_empty());
}

#[test]
fn a_derive_rewrite_puts_every_column_back_and_keeps_holds() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    let rows = fixture_rows("android");
    store.upsert(&rows_in(&rows)).expect("writes");
    // Stale rules everywhere, a hold on one row, a fault on the way.
    store
        .raw()
        .execute_batch(
            "UPDATE shot SET derive_v = 0, sort_rating = 42, search_text = 'stale', method = 'x';
             DELETE FROM shot_tag;
             UPDATE shot SET hold_delete = 1 WHERE rid = 5;
             UPDATE meta SET value = '0' WHERE key = 'derive_v';",
        )
        .expect("stales");
    store.close().expect("closes");
    let mut store = reopen(&path);
    let health = store.health();
    assert!(health.derive_pending && health.reindexing);
    store.set_batch_rows(64);
    store.faults().fail_at("derive.batch", 2);
    let mut seqs = Vec::new();
    let mut faulted = false;
    loop {
        match store.maintain() {
            Ok(Maintained::Derived { rows, change }) => {
                assert!(rows <= 64);
                assert!(!change.upserted.is_empty());
                seqs.push(change.seq);
            }
            Ok(Maintained::DeriveDone) => break,
            Ok(other) => panic!("{other:?}"),
            Err(StoreError::Fault("derive.batch")) => {
                faulted = true;
                // A restart resumes.
                drop(store);
                store = reopen(&path);
                store.set_batch_rows(64);
            }
            Err(e) => panic!("{e}"),
        }
    }
    assert!(faulted);
    assert!(seqs.windows(2).all(|w| w[1] == w[0] + 1));
    assert!(!store.health().derive_pending);
    assert!(store.derived_mismatches().expect("reads").is_empty());
    assert_eq!(int(&store, "SELECT hold_delete FROM shot WHERE rid = 5"), 1);
    assert_eq!(int(&store, "SELECT sum(hold_delete) FROM shot"), 1);
    assert!(fts_whole(&store));
    let seq = store.write_seq();
    store.close().expect("closes");
    let mut store = reopen(&path);
    assert!(!store.health().derive_pending);
    assert_eq!(store.maintain_all(10).expect("maintains"), 0);
    assert_eq!(store.write_seq(), seq);
}

#[test]
fn a_reopen_on_web_sets_the_index_aside_for_a_batched_rebuild() {
    // REVIEW-439 note 6: web never runs the one-statement check after a
    // reopen. The index is set aside instead: searches scan, health says
    // `reindexing`, and maintenance rebuilds it in batches.
    let d = dir();
    let path = file(&d, "history.db");
    let mut opts =
        de1_history::store::OpenOptions::new(de1_history::store::Role::Authority).create(1);
    opts.fts_check_on_reopen = false;
    let mut store = Store::open(&path, opts).expect("opens");
    let rows = fixture_rows("web");
    store.upsert(&rows_in(&rows)).expect("writes");
    let victim = store.listed_ids(&HistoryQuery::default()).expect("ids")[10].clone();
    plant_stale(&store, &victim);
    let err = store.reopen_as_after_corrupt();
    assert!(matches!(err, StoreError::Reopened), "{err:?}");
    // Not checked, so not dropped: still `Ready` on file, but set aside.
    assert_eq!(store.fts_status(), FtsStatus::Ready);
    assert_eq!(store.text_path(), TextPath::Scan);
    assert!(store.health().reindexing);
    assert_eq!(store.maintain().expect("starts"), Maintained::FtsStarted);
    store.maintain_all(10_000).expect("rebuilds");
    assert_eq!(store.fts_status(), FtsStatus::Ready);
    assert_eq!(store.text_path(), TextPath::Fts);
    assert!(!store.health().reindexing);
    assert!(fts_whole(&store), "the rebuilt index is whole");
}

#[test]
fn a_reopen_natively_checks_the_index_at_once() {
    let d = dir();
    let path = file(&d, "history.db");
    let mut store = create(&path);
    store
        .upsert(&rows_in(&fixture_rows("web")))
        .expect("writes");
    let victim = store.listed_ids(&HistoryQuery::default()).expect("ids")[10].clone();
    plant_stale(&store, &victim);
    let err = store.reopen_as_after_corrupt();
    assert!(matches!(err, StoreError::Reopened), "{err:?}");
    // The check ran, found the stale entry and dropped the index.
    assert_eq!(store.fts_status(), FtsStatus::Missing);
}

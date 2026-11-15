//! PLAN C2's property tests for the store's write half, on real SQLite files:
//! `derive_row` of any row inserts without error (S8), and any sequence of
//! upserts, edits that do and don't change the search text, deletes and
//! maintenance keeps the search index whole, every derived column equal to
//! `derive_row(row_json)`, and every search equal to the reference, on the
//! index and on the scan path.

mod common;

use std::collections::BTreeMap;

use common::row_like;
use de1_history::reference::{filter_sort, ref_rows};
use de1_history::store::{FtsCheck, FtsStatus, OpenOptions, Role, RowIn, Store};
use de1_history::{HistoryQuery, Live, fold};
use proptest::prelude::*;
use serde_json::{Value, json};

fn open(dir: &tempfile::TempDir) -> Store {
    Store::open(
        dir.path().join("h.db"),
        OpenOptions::new(Role::Index).create(0),
    )
    .expect("opens")
}

/// A row-like object with a string id.
fn with_id(mut v: Value, id: &str) -> Value {
    if let Value::Object(m) = &mut v {
        m.insert("id".into(), json!(id));
    }
    v
}

#[derive(Debug, Clone)]
enum Op {
    /// Writes row-like `value` under id `n`.
    Put(u8, Value),
    /// Changes `n`'s rating only: the search text stays.
    Rate(u8, i64),
    /// Changes `n`'s notes: the search text moves.
    Note(u8, String),
    Delete(u8),
    Maintain,
    Rebuild,
}

fn op() -> impl Strategy<Value = Op> {
    prop_oneof![
        4 => (0u8..12, row_like()).prop_map(|(n, v)| Op::Put(n, v)),
        2 => (0u8..12, 0i64..6).prop_map(|(n, r)| Op::Rate(n, r)),
        2 => (0u8..12, prop_oneof![Just("Café crème".to_owned()), Just("spresso".to_owned()), "\\PC{0,10}"])
            .prop_map(|(n, s)| Op::Note(n, s)),
        2 => (0u8..12).prop_map(Op::Delete),
        2 => Just(Op::Maintain),
        1 => Just(Op::Rebuild),
    ]
}

fn expect(model: &BTreeMap<String, Value>, q: &HistoryQuery) -> Vec<String> {
    let rows: Vec<Value> = model.values().cloned().collect();
    filter_sort(&ref_rows(&rows), q, &Live::default())
}

/// Searches for runs of the rows' own folded text, and a few fixed ones.
fn needles(model: &BTreeMap<String, Value>) -> Vec<String> {
    let mut out: Vec<String> = ["spres", "cafe", "CRÈME", "zzzz"]
        .map(str::to_owned)
        .to_vec();
    for v in model.values() {
        for key in ["profileName", "notes"] {
            let text = v
                .get(key)
                .or_else(|| v.get("metadata").and_then(|m| m.get(key)));
            if let Some(s) = text.and_then(Value::as_str) {
                let chars: Vec<char> = fold(s).chars().collect();
                if chars.len() >= 3 {
                    out.push(chars[..chars.len().min(5)].iter().collect());
                }
            }
        }
    }
    out
}

fn check_searches(store: &mut Store, model: &BTreeMap<String, Value>) -> Result<(), TestCaseError> {
    for n in needles(model) {
        let q = HistoryQuery {
            text: n.clone(),
            ..HistoryQuery::default()
        };
        prop_assert_eq!(
            store.listed_ids(&q).expect("answers"),
            expect(model, &q),
            "{:?}",
            n
        );
    }
    Ok(())
}

proptest! {
    #![proptest_config(ProptestConfig {
        cases: std::env::var("PROPTEST_CASES").ok().and_then(|c| c.parse().ok()).unwrap_or(64).min(2_000),
        ..ProptestConfig::default()
    })]

    /// S8: whatever a row holds, its derived values fit the schema, so the
    /// insert never fails and a shot never sticks in a journal for good.
    #[test]
    fn any_row_inserts(rows in prop::collection::vec(row_like(), 1..8)) {
        let d = tempfile::tempdir().expect("a dir");
        let mut store = open(&d);
        let rows: Vec<RowIn> = rows
            .into_iter()
            .enumerate()
            .map(|(i, v)| RowIn::new(with_id(v, &format!("r{i}")).to_string()))
            .collect();
        store.upsert(&rows).expect("inserts");
        // And again, as an update of every row.
        store.upsert(&rows).expect("updates");
        prop_assert!(store.derived_mismatches().expect("reads").is_empty());
        prop_assert_eq!(store.fts_integrity_check().expect("checks"), FtsCheck::Ok);
    }

    /// Random writes keep the index and the derived columns in step, and every
    /// search answers as the reference does.
    #[test]
    fn random_writes_keep_every_derived_thing_in_step(ops in prop::collection::vec(op(), 1..40)) {
        let d = tempfile::tempdir().expect("a dir");
        let mut store = open(&d);
        store.set_batch_rows(3);
        let mut model: BTreeMap<String, Value> = BTreeMap::new();
        for op in ops {
            match op {
                Op::Put(n, v) => {
                    let id = format!("s{n}");
                    let v = with_id(v, &id);
                    store.upsert(&[RowIn::new(v.to_string())]).expect("writes");
                    model.insert(id, v);
                }
                Op::Rate(n, r) => {
                    let id = format!("s{n}");
                    if let Some(mut v) = model.get(&id).cloned() {
                        v["rating"] = json!(r);
                        store.upsert(&[RowIn::new(v.to_string())]).expect("writes");
                        model.insert(id, v);
                    }
                }
                Op::Note(n, s) => {
                    let id = format!("s{n}");
                    if let Some(mut v) = model.get(&id).cloned() {
                        v["notes"] = json!(s);
                        store.upsert(&[RowIn::new(v.to_string())]).expect("writes");
                        model.insert(id, v);
                    }
                }
                Op::Delete(n) => {
                    let id = format!("s{n}");
                    store.delete(std::slice::from_ref(&id)).expect("deletes");
                    model.remove(&id);
                }
                Op::Maintain => {
                    store.maintain().expect("maintains");
                }
                Op::Rebuild => store.start_fts_rebuild().expect("starts"),
            }
        }
        prop_assert!(store.derived_mismatches().expect("reads").is_empty());
        // Mid-rebuild, searches run by scan; then on the finished index.
        check_searches(&mut store, &model)?;
        store.maintain_all(10_000).expect("maintains");
        prop_assert_eq!(store.fts_status(), FtsStatus::Ready);
        prop_assert_eq!(store.fts_integrity_check().expect("checks"), FtsCheck::Ok);
        check_searches(&mut store, &model)?;
    }
}

//! PLAN C2's "a 100,000-row database builds in the test suite", and S15:
//! the search index's rebuild at 100,000 rows runs in batches, none larger
//! than its batch size. The times are printed; B1 owns the budgets, so they
//! are asserted only in an optimised build, against B1's 100 ms cap.
// Timed with `Instant`, which `std` lacks on wasm32; B1 times the browser.
#![cfg(not(target_arch = "wasm32"))]

mod common;

use std::time::{Duration, Instant};

use common::{create, dir, file, fixture_rows, fts_whole, rows_in};
use de1_history::store::Maintained;
use de1_history::{Anchor, Dir, HistoryQuery, Sort, SortKey};
use serde_json::{Value, json};

const ROWS: usize = 100_000;
const CAP: Duration = Duration::from_millis(100);

/// `ROWS` real-shaped rows: the fixture's 300, again and again, each with a
/// new id and its time moved back.
fn rows() -> Vec<String> {
    let base: Vec<Value> = fixture_rows("web")
        .iter()
        .map(|r| serde_json::from_str(r).expect("JSON"))
        .collect();
    (0..ROWS)
        .map(|i| {
            let mut v = base[i % base.len()].clone();
            v["id"] = json!(format!("shot:{i:06}"));
            let at = v["completedAt"].as_i64().unwrap_or(0)
                - i64::try_from(i / base.len()).expect("small") * 86_400_000;
            v["completedAt"] = json!(at);
            v.to_string()
        })
        .collect()
}

#[test]
fn a_100k_row_database_builds_answers_and_rebuilds_its_index_in_batches() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    let rows = rows();
    let t = Instant::now();
    for chunk in rows.chunks(1_000) {
        store.upsert(&rows_in(chunk)).expect("writes");
    }
    eprintln!("built {ROWS} rows in {:?}", t.elapsed());
    let all = HistoryQuery::default();
    assert_eq!(store.count(&all).expect("counts") as usize, ROWS);

    let t = Instant::now();
    let q = HistoryQuery {
        text: "guji".into(),
        sort: Sort {
            key: SortKey::Rating,
            dir: Dir::Desc,
        },
        ..HistoryQuery::default()
    };
    let n = store.count(&q).expect("counts");
    let first = store.page(&q, &Anchor::Start, 64).expect("a page");
    assert!(n > 0 && first.rows.len() == 64);
    let end = store
        .page(
            &all,
            &Anchor::Offset(u32::try_from(ROWS).expect("fits") - 10),
            64,
        )
        .expect("a page");
    assert!(end.at_end && end.rows.len() == 10);
    store.stats(&all).expect("stats");
    store.facets(&all).expect("facets");
    eprintln!("search, pages, stats and facets in {:?}", t.elapsed());

    store.start_fts_rebuild().expect("starts");
    let batch = 500;
    store.set_batch_rows(batch);
    let mut longest = Duration::ZERO;
    let mut steps = 0;
    loop {
        let t = Instant::now();
        let step = store.maintain().expect("maintains");
        longest = longest.max(t.elapsed());
        match step {
            Maintained::FtsBatch { rows } => {
                assert!(rows <= batch);
                steps += 1;
            }
            Maintained::FtsSwapped => break,
            other => panic!("{other:?}"),
        }
    }
    assert!(steps >= ROWS / batch as usize - 1);
    eprintln!("rebuilt the index in {steps} batches, the longest {longest:?}");
    if !cfg!(debug_assertions) {
        assert!(longest <= CAP, "{longest:?}");
    }
    assert!(fts_whole(&store));
    assert_eq!(store.count(&q).expect("counts"), n);
}

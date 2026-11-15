//! The query plans B1's numbers rest on (SPEC §4, §7), checked on every run
//! with `EXPLAIN QUERY PLAN`. SQLite plans without statistics here (nothing
//! runs `ANALYZE`), so a small file plans as a large one does.
//! - Every sort, both directions, pages by walking an index: no temp sort.
//! - `index_of` counts a range of the sort's index.
//! - A search count drives from the match set and reads no other row; a
//!   search page walks the sort's index, probing the set.

mod common;

#[cfg(target_arch = "wasm32")]
use common::test;

use common::{create, dir, file, fixture_rows, rows_in};
use de1_history::sql::{self, Param, Seek, TextPath};
use de1_history::store::Store;
use de1_history::{Dir, HistoryQuery, Live, Sort, SortKey};

fn plan(store: &Store, st: &sql::Stmt) -> String {
    let params: Vec<rusqlite::types::Value> = st
        .params
        .iter()
        .map(|p| match p {
            Param::Int(i) => rusqlite::types::Value::Integer(*i),
            Param::Real(r) => rusqlite::types::Value::Real(*r),
            Param::Text(t) => rusqlite::types::Value::Text(t.clone()),
        })
        .collect();
    let mut q = store
        .raw()
        .prepare(&format!("EXPLAIN QUERY PLAN {}", st.sql))
        .expect("explains");
    let rows: Vec<String> = q
        .query_map(rusqlite::params_from_iter(params), |r| r.get(3))
        .expect("runs")
        .collect::<rusqlite::Result<_>>()
        .expect("reads");
    rows.join(" | ")
}

/// The plan's top-level steps only: a subquery's own sort (a `UNION`'s
/// merge) is not the statement's.
fn top(store: &Store, st: &sql::Stmt) -> String {
    let params: Vec<rusqlite::types::Value> = st
        .params
        .iter()
        .map(|p| match p {
            Param::Int(i) => rusqlite::types::Value::Integer(*i),
            Param::Real(r) => rusqlite::types::Value::Real(*r),
            Param::Text(t) => rusqlite::types::Value::Text(t.clone()),
        })
        .collect();
    let mut q = store
        .raw()
        .prepare(&format!("EXPLAIN QUERY PLAN {}", st.sql))
        .expect("explains");
    let rows: Vec<(i64, String)> = q
        .query_map(rusqlite::params_from_iter(params), |r| {
            Ok((r.get(1)?, r.get(3)?))
        })
        .expect("runs")
        .collect::<rusqlite::Result<_>>()
        .expect("reads");
    rows.into_iter()
        .filter(|(parent, _)| *parent == 0)
        .map(|(_, d)| d)
        .collect::<Vec<_>>()
        .join(" | ")
}

fn sorts() -> Vec<Sort> {
    SortKey::ALL
        .iter()
        .flat_map(|&key| [Dir::Desc, Dir::Asc].map(|dir| Sort { key, dir }))
        .collect()
}

fn index_for(key: SortKey) -> &'static str {
    match key {
        SortKey::Date => "shot_by_date",
        SortKey::Rating => "shot_sort_rating",
        SortKey::Profile => "shot_sort_profile",
        SortKey::Bean => "shot_sort_bean",
        SortKey::Yield => "shot_sort_yield",
        SortKey::Time => "shot_sort_time",
    }
}

fn store() -> (common::TestDir, Store) {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    let live: Live = serde_json::from_str(common::fixture("live.json")).expect("a library");
    store.set_live(&live).expect("a library");
    store
        .upsert(&rows_in(&fixture_rows("web")))
        .expect("writes");
    (d, store)
}

#[test]
fn every_sort_pages_by_walking_its_index_both_ways() {
    let (_d, store) = store();
    for sort in sorts() {
        let q = HistoryQuery {
            sort,
            ..HistoryQuery::default()
        };
        let p = top(
            &store,
            &sql::page(&q, Seek::Start, 64, TextPath::Fts)
                .expect("builds")
                .stmt,
        );
        assert!(!p.contains("TEMP B-TREE"), "{sort:?}: {p}");
        assert!(p.contains(index_for(sort.key)), "{sort:?}: {p}");
    }
}

#[test]
fn index_of_counts_a_range_of_the_sorts_index() {
    let (_d, store) = store();
    for sort in sorts() {
        let q = HistoryQuery {
            sort,
            ..HistoryQuery::default()
        };
        let p = plan(&store, &sql::index_of(&q, "shot:x", TextPath::Fts));
        assert!(
            p.contains(&format!("SEARCH s USING INDEX {}", index_for(sort.key))),
            "{sort:?}: {p}"
        );
    }
}

#[test]
fn a_search_counts_only_its_matches_and_pages_by_the_sorts_index() {
    let (_d, store) = store();
    for sort in sorts() {
        let q = HistoryQuery {
            text: "zzqx".into(),
            sort,
            ..HistoryQuery::default()
        };
        let c = format!("{} ", plan(&store, &sql::count(&q, TextPath::Fts)));
        assert!(
            c.contains("SEARCH s USING INTEGER PRIMARY KEY"),
            "{sort:?}: {c}"
        );
        for scan in ["SCAN s ", "SCAN s_m", "SCAN s_b"] {
            assert!(!c.contains(scan), "{sort:?}: {c}");
        }
        let p = top(
            &store,
            &sql::page(&q, Seek::Start, 64, TextPath::Fts)
                .expect("builds")
                .stmt,
        );
        assert!(!p.contains("TEMP B-TREE"), "{sort:?}: {p}");
        assert!(p.contains(index_for(sort.key)), "{sort:?}: {p}");
    }
}

/// A statement with no order to serve (a count, the stats, a facet, the day
/// groups) walks `shot_by_date`, never a sort index: those are in random
/// `rid` order, and B1 measured the stats and facets twice as slow through
/// one. A search on the index and a bag start from their own sets.
#[test]
fn whole_list_statements_never_walk_a_sort_index() {
    let (_d, store) = store();
    let filters = [
        HistoryQuery::default(),
        HistoryQuery {
            profile: Some("Turbo Bloom".into()),
            pinned_only: true,
            ..HistoryQuery::default()
        },
        HistoryQuery {
            since_ms: Some(1_789_000_000_000),
            tags_all: vec!["dial-in".into()],
            ..HistoryQuery::default()
        },
        HistoryQuery {
            bean: Some(de1_history::BeanScope::Coffee("onyx|etiopía guji".into())),
            ..HistoryQuery::default()
        },
        HistoryQuery {
            text: "sour".into(),
            ..HistoryQuery::default()
        },
    ];
    for q in &filters {
        for path in [TextPath::Fts, TextPath::Scan] {
            let f = sql::facets(q, path);
            let all = [
                ("count", sql::count(q, path)),
                ("stats", sql::stats(q, path)),
                ("group_counts", sql::group_counts(q, &[1, 2], path)),
                ("profiles", f.profiles),
                ("methods", f.methods),
                ("tags", f.tags),
                ("coffees", f.coffees),
                ("pinned", f.pinned),
            ];
            for (name, st) in all {
                let p = plan(&store, &st);
                assert!(!p.contains("shot_sort_"), "{name} {q:?} {path:?}: {p}");
            }
        }
    }
    let bag = HistoryQuery {
        bean: Some(de1_history::BeanScope::Bag("bean:b".into())),
        ..HistoryQuery::default()
    };
    let p = plan(&store, &sql::stats(&bag, TextPath::Fts));
    assert!(p.contains("shot_by_bean"), "{p}");
}

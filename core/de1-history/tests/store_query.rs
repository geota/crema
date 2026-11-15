//! Every query of SPEC §7 against a real database, held to the reference
//! rules on the cross-shell fixture (300 real-shaped rows, the shells' own
//! queries and live library), and the lookups sync and the bean card use.

mod common;

#[cfg(target_arch = "wasm32")]
use common::test;

use std::collections::BTreeMap;

use common::{create, dir, file, fixture_rows, int, row, rows_in};
use de1_history::reference::{RefRow, filter_sort, group_counts, ref_rows, stats};
use de1_history::store::{Destination, Page, RowIn, Store, StoreError};
use de1_history::{Anchor, BeanScope, Dir, HistoryQuery, Live, Sort, SortKey};
use serde::Deserialize;
use serde_json::{Value, json};

const DAY_MS: i64 = 86_400_000;

#[derive(Deserialize)]
struct FixtureSort {
    key: SortKey,
    dir: Dir,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct FixtureQuery {
    name: String,
    range: String,
    text: String,
    profile: Option<String>,
    bean: Option<String>,
    method: Option<String>,
    tags: Vec<String>,
    pinned_only: bool,
    sort: FixtureSort,
}

#[derive(Deserialize)]
struct Queries {
    now: i64,
    queries: Vec<FixtureQuery>,
}

fn read(name: &str) -> String {
    common::fixture(name).to_owned()
}

fn queries() -> Vec<(String, HistoryQuery)> {
    let spec: Queries = serde_json::from_str(&read("queries.json")).expect("queries.json");
    let mut out: Vec<(String, HistoryQuery)> = spec
        .queries
        .iter()
        .map(|q| {
            (
                q.name.clone(),
                HistoryQuery {
                    text: q.text.clone(),
                    since_ms: match q.range.as_str() {
                        "7d" => Some(spec.now - 7 * DAY_MS),
                        "30d" => Some(spec.now - 30 * DAY_MS),
                        _ => None,
                    },
                    profile: q.profile.clone(),
                    bean: q.bean.as_ref().map(|b| match b.strip_prefix("coffee:") {
                        Some(key) => BeanScope::Coffee(key.to_owned()),
                        None => BeanScope::Bag(b.clone()),
                    }),
                    method: q.method.clone(),
                    tags_all: q.tags.clone(),
                    pinned_only: q.pinned_only,
                    sort: Sort {
                        key: q.sort.key,
                        dir: q.sort.dir,
                    },
                },
            )
        })
        .collect();
    // Every sort both ways, unfiltered, on top of the shells' own.
    for key in SortKey::ALL {
        for dir in [Dir::Asc, Dir::Desc] {
            out.push((
                format!("all-{key:?}-{dir:?}"),
                HistoryQuery {
                    sort: Sort { key, dir },
                    ..HistoryQuery::default()
                },
            ));
        }
    }
    out
}

struct Loaded {
    _dir: common::TestDir,
    store: Store,
    refs: Vec<RefRow>,
    live: Live,
}

fn loaded() -> Loaded {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    let rows = fixture_rows("web");
    store.upsert(&rows_in(&rows)).expect("writes");
    let live: Live = serde_json::from_str(&read("live.json")).expect("live.json");
    store.set_live(&live).expect("writes");
    let values: Vec<Value> = rows
        .iter()
        .map(|r| serde_json::from_str(r).expect("JSON"))
        .collect();
    Loaded {
        _dir: d,
        store,
        refs: ref_rows(&values),
        live,
    }
}

fn ids(p: &Page) -> Vec<String> {
    p.rows.iter().map(|r| r.id.clone()).collect()
}

/// Every row of `q`, page by page forward from the start.
fn forward(store: &mut Store, q: &HistoryQuery, size: u32) -> Vec<String> {
    let mut out = Vec::new();
    let mut page = store.page(q, &Anchor::Start, size).expect("a page");
    assert!(page.at_start);
    assert_eq!(page.start_index, Some(0));
    loop {
        out.extend(ids(&page));
        if page.at_end {
            return out;
        }
        let last = page.last.clone().expect("a full page has a last row");
        page = store.page(q, &Anchor::After(last), size).expect("a page");
        assert!(!page.at_start);
        assert_eq!(page.start_index, None);
    }
}

/// Every row of `q`, page by page backward from the end.
fn backward(store: &mut Store, q: &HistoryQuery, size: u32, count: u32) -> Vec<String> {
    let mut chunks = Vec::new();
    let start = count.saturating_sub(size);
    let mut page = store.page(q, &Anchor::Offset(start), size).expect("a page");
    assert!(page.at_end);
    loop {
        chunks.push(ids(&page));
        if page.at_start {
            break;
        }
        let first = page.first.clone().expect("a page has a first row");
        page = store.page(q, &Anchor::Before(first), size).expect("a page");
        assert!(!page.at_end);
    }
    chunks.into_iter().rev().flatten().collect()
}

#[test]
fn pages_counts_positions_stats_and_groups_match_the_reference() {
    let Loaded {
        _dir,
        mut store,
        refs,
        live,
    } = loaded();
    for (name, q) in queries() {
        let want = filter_sort(&refs, &q, &live);
        let count = store.count(&q).expect("counts");
        assert_eq!(count as usize, want.len(), "{name}");
        for size in [1, 7, 64] {
            assert_eq!(forward(&mut store, &q, size), want, "{name} forward {size}");
            if !want.is_empty() {
                assert_eq!(
                    backward(&mut store, &q, size, count),
                    want,
                    "{name} backward {size}"
                );
            }
        }
        for (i, id) in want.iter().enumerate().step_by(17) {
            assert_eq!(
                store.index_of(&q, id).expect("finds"),
                Some(u32::try_from(i).expect("small")),
                "{name} {id}"
            );
        }
        assert_eq!(store.index_of(&q, "no-such-row").expect("answers"), None);
        let s = store.stats(&q).expect("stats");
        let r = stats(&refs, &q, &live);
        assert!(common::stats_close(&s, &r), "{name}: {s:?} vs {r:?}");
        let edges = [1_789_900_000_000, 1_789_000_000_000, 1_780_000_000_000];
        assert_eq!(
            store.group_counts(&q, &edges).expect("counts"),
            group_counts(&refs, &q, &live, &edges),
            "{name}"
        );
    }
}

#[test]
fn an_offset_jump_and_the_page_around_a_row_land_where_the_reference_says() {
    let Loaded {
        _dir,
        mut store,
        refs,
        live,
    } = loaded();
    let q = HistoryQuery {
        sort: Sort {
            key: SortKey::Rating,
            dir: Dir::Asc,
        },
        ..HistoryQuery::default()
    };
    let want = filter_sort(&refs, &q, &live);
    let p = store.page(&q, &Anchor::Offset(100), 10).expect("a page");
    assert_eq!(ids(&p), want[100..110]);
    assert_eq!(p.start_index, Some(100));
    assert!(!p.at_start && !p.at_end);
    let p = store
        .page(
            &q,
            &Anchor::Offset(u32::try_from(want.len()).expect("small") + 5),
            10,
        )
        .expect("a page");
    assert!(p.rows.is_empty() && p.at_end);
    for (i, before) in [(150, 5), (2, 5), (0, 3), (want.len() - 1, 4), (40, 30)] {
        let id = &want[i];
        let p = store
            .page(
                &q,
                &Anchor::Around {
                    id: id.clone(),
                    before,
                },
                10,
            )
            .expect("a page");
        let back = (before as usize).min(9).min(i);
        let start = i - back;
        let end = (start + 10).min(want.len());
        assert_eq!(ids(&p), want[start..end], "around {i}");
        assert_eq!(p.start_index, Some(u32::try_from(start).expect("small")));
        assert_eq!(p.at_start, start == 0, "around {i}");
        assert_eq!(p.at_end, end == want.len(), "around {i}");
    }
    let err = store
        .page(
            &q,
            &Anchor::Around {
                id: "nope".into(),
                before: 3,
            },
            10,
        )
        .expect_err("refused");
    assert_eq!(err, StoreError::NotListed("nope".into()));
    assert!(matches!(
        store.page(&q, &Anchor::Start, 0),
        Err(StoreError::Refused(_))
    ));
    // A cursor from another query is refused.
    let other = HistoryQuery::default();
    let c = store
        .page(&other, &Anchor::Start, 3)
        .expect("a page")
        .last
        .expect("a row");
    assert!(matches!(
        store.page(&q, &Anchor::After(c), 3),
        Err(StoreError::Refused(_))
    ));
}

#[test]
fn each_page_carries_the_seq_it_was_read_at() {
    let Loaded {
        _dir, mut store, ..
    } = loaded();
    let q = HistoryQuery::default();
    let seq = store.write_seq();
    assert_eq!(store.page(&q, &Anchor::Start, 5).expect("a page").seq, seq);
    store
        .upsert(&rows_in(&[row("new", 1, json!({}))]))
        .expect("writes");
    assert_eq!(
        store.page(&q, &Anchor::Start, 5).expect("a page").seq,
        seq + 1
    );
    assert_eq!(store.sync_rows(0, 5).expect("a page").seq, seq + 1);
}

/// What the facets must say, from the reference: the query without one
/// dimension, grouped.
fn grouped(
    refs: &[RefRow],
    ids: &[String],
    by: impl Fn(&RefRow) -> Vec<String>,
) -> BTreeMap<String, u32> {
    let mut out = BTreeMap::new();
    for r in refs.iter().filter(|r| ids.contains(&r.id)) {
        for k in by(r) {
            *out.entry(k).or_insert(0) += 1;
        }
    }
    out
}

#[test]
fn facets_count_each_dimension_without_its_own_filter() {
    let Loaded {
        _dir,
        mut store,
        refs,
        live,
    } = loaded();
    let live_rows = de1_history::derive::derive_live(&live);
    for (name, q) in queries() {
        let f = store.facets(&q).expect("facets");
        let without = |edit: &dyn Fn(&mut HistoryQuery)| {
            let mut q = q.clone();
            edit(&mut q);
            filter_sort(&refs, &q, &live)
        };
        let profiles = grouped(&refs, &without(&|q| q.profile = None), |r| {
            r.derived
                .profile_name
                .clone()
                .filter(|p| !p.is_empty())
                .into_iter()
                .collect()
        });
        assert_eq!(
            f.profiles.iter().cloned().collect::<BTreeMap<_, _>>(),
            profiles,
            "{name}"
        );
        let methods = grouped(&refs, &without(&|q| q.method = None), |r| {
            vec![r.derived.method.clone()]
        });
        assert_eq!(
            f.methods.iter().cloned().collect::<BTreeMap<_, _>>(),
            methods,
            "{name}"
        );
        let tags = grouped(&refs, &without(&|q| q.tags_all.clear()), |r| {
            if r.derived.has_tags {
                r.derived.tags.clone()
            } else {
                live_rows
                    .bean_tags
                    .iter()
                    .filter(|t| Some(&t.bean_id) == r.derived.bean_id.as_ref())
                    .map(|t| t.tag.clone())
                    .collect()
            }
        });
        assert_eq!(
            f.tags.iter().cloned().collect::<BTreeMap<_, _>>(),
            tags,
            "{name}"
        );
        let coffees = grouped(&refs, &without(&|q| q.bean = None), |r| {
            let live_bean = live_rows
                .beans
                .iter()
                .find(|b| Some(&b.bean_id) == r.derived.bean_id.as_ref());
            match live_bean {
                Some(b) => b.coffee_key.clone(),
                None => r.derived.coffee_key.clone(),
            }
            .into_iter()
            .collect()
        });
        let got: BTreeMap<String, u32> = f
            .coffees
            .iter()
            .map(|c| (c.coffee_key.clone(), c.count))
            .collect();
        assert_eq!(got, coffees, "{name}");
        let pinned = without(&|q| q.pinned_only = false)
            .iter()
            .filter(|id| refs.iter().any(|r| &r.id == *id && r.derived.pinned))
            .count();
        assert_eq!(f.pinned as usize, pinned, "{name}");
    }
}

#[test]
fn lookups_by_id_remote_id_bag_and_backlog() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&rows_in(&[
            row("a", 10, json!({ "visualizerId": "v1", "bean": { "beanId": "bag" }, "metadata": { "dose": 18.5 } })),
            row("b", 20, json!({ "decentId": "d1", "bean": { "beanId": "bag" }, "metadata": { "dose": 19.0 } })),
            row("c", 30, json!({ "bean": { "beanId": "bag" }, "metadata": { "dose": 0 } })),
            row("t", 40, json!({ "deletedAt": 41, "visualizerId": "v2" })),
            row("brew", 50, json!({ "brewMethod": "v60" })),
        ]))
        .expect("writes");
    let get = store
        .get(&["c".into(), "a".into(), "zz".into(), "a".into()])
        .expect("gets");
    assert_eq!(
        get.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(),
        ["c", "a"]
    );
    assert_eq!(store.by_remote_id("v1").expect("finds")[0].id, "a");
    assert_eq!(
        store.by_remote_id("v2").expect("finds")[0].id,
        "t",
        "tombstones are bound too"
    );
    assert!(store.by_remote_id("v9").expect("answers").is_empty());
    assert_eq!(store.by_decent_id("d1").expect("finds")[0].id, "b");
    // Newest first, positive doses only.
    assert_eq!(store.doses_for_bag("bag", 10).expect("reads"), [19.0, 18.5]);
    assert_eq!(store.doses_for_bag("bag", 1).expect("reads"), [19.0]);
    let unsent: Vec<String> = store
        .unsent(Destination::Visualizer, 0, 10)
        .expect("reads")
        .into_iter()
        .map(|r| r.id)
        .collect();
    assert_eq!(unsent, ["b", "c"]);
    let first = store.unsent(Destination::Decent, 0, 1).expect("reads");
    assert_eq!(first[0].id, "a");
    let rest = store
        .unsent(Destination::Decent, first[0].rid, 10)
        .expect("reads");
    assert_eq!(
        rest.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(),
        ["c"]
    );
}

#[test]
fn the_sync_projection_pages_equal_the_whole_and_carry_their_seq() {
    let Loaded {
        _dir, mut store, ..
    } = loaded();
    let whole = store.sync_projection().expect("reads");
    assert_eq!(whole.rows.len(), 300);
    assert_eq!(whole.seq, store.write_seq());
    let mut paged = Vec::new();
    let mut after = 0;
    loop {
        let p = store.sync_rows(after, 64).expect("reads");
        assert_eq!(p.seq, whole.seq);
        let Some(last) = p.rows.last() else { break };
        after = last.rid;
        paged.extend(p.rows);
    }
    assert_eq!(paged, whole.rows);
    // Every row, tombstones included, with its derived fields.
    assert_eq!(
        whole.rows.iter().filter(|r| r.deleted_at.is_some()).count(),
        usize::try_from(int(
            &store,
            "SELECT count(*) FROM shot WHERE deleted_at IS NOT NULL"
        ))
        .expect("small")
    );
    let one = &whole.rows[0];
    assert_eq!(
        one.duration_ms,
        int(
            &store,
            &format!("SELECT sort_time FROM shot WHERE rid = {}", one.rid)
        )
    );
}

#[test]
fn held_rows_are_hidden_from_every_query() {
    let d = dir();
    let mut store = create(&file(&d, "history.db"));
    store
        .upsert(&[
            RowIn::new(row(
                "keep",
                1,
                json!({ "visualizerId": "v1", "bean": { "beanId": "bag" } }),
            )),
            RowIn::new(row(
                "held",
                2,
                json!({ "visualizerId": "v2", "bean": { "beanId": "bag" } }),
            )),
        ])
        .expect("writes");
    let walk = store.export_begin().expect("begins");
    store.delete(&["held".into()]).expect("holds");
    let q = HistoryQuery::default();
    assert_eq!(store.count(&q).expect("counts"), 1);
    assert_eq!(
        ids(&store.page(&q, &Anchor::Start, 10).expect("a page")),
        ["keep"]
    );
    assert!(store.get(&["held".into()]).expect("gets").is_empty());
    assert!(store.by_remote_id("v2").expect("finds").is_empty());
    assert_eq!(store.doses_for_bag("bag", 10).expect("reads").len(), 1);
    assert!(
        store
            .unsent(Destination::Decent, 0, 10)
            .expect("reads")
            .iter()
            .all(|r| r.id != "held")
    );
    let sync = store.sync_projection().expect("reads");
    assert!(sync.rows.iter().any(|r| r.id == "held" && r.held));
    store.export_end(walk.token).expect("ends");
}

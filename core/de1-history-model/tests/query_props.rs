//! PLAN C2's property tests for the queries, on real SQLite files: random
//! rows (empty strings, NULLs, equal timestamps, accented text, emoji) and
//! random queries and sorts, ascending sorts with equal keys among them (S7).
//!
//! - Page by page, forward and backward, at any page size, the store lists
//!   exactly `reference::filter_sort`; `count` is its length; `index_of` is
//!   each row's position; `stats` and `group_counts` equal the reference.
//! - With writes between pages, a row appears twice, or goes missing, only
//!   if it was deleted or edited: its sort key may have moved across the
//!   cursor. Inserts and deletes alone never repeat a row.

mod common;

use std::collections::BTreeSet;

use de1_domain::BrewHistoryStats;
use de1_history::derive::coffee_key;
use de1_history::reference::{filter_sort, group_counts, ref_rows, stats};
use de1_history::store::{OpenOptions, Page, Role, RowIn, Store};
use de1_history::{
    Anchor, BeanScope, Dir, HistoryQuery, Live, LiveBean, LiveMethod, Sort, SortKey,
};
use proptest::prelude::*;
use serde_json::{Value, json};

fn pick<T: Clone + std::fmt::Debug + 'static>(items: &'static [T]) -> impl Strategy<Value = T> {
    prop::sample::select(items)
}

const TEXTS: &[&str] = &[
    "",
    "Turbo",
    "turbo",
    "Café Crème",
    "Ñandú",
    "🙂 smile",
    "a*b",
    "Guji",
    "x",
];
const WORDS: &[&str] = &["sour", "floral", "dial-in"];

/// One row, in the web shape or Android's, with few distinct values so ties,
/// NULLs and matches are common.
fn row(id: usize) -> impl Strategy<Value = Value> {
    (
        (any::<bool>(), pick(&[1_000i64, 2_000, 2_000, 3_000, 4_500])),
        prop::option::of(pick(TEXTS)),
        prop::option::of(pick(&[0.0f64, 18.0, 20.5])),
        prop::option::of(pick(&[0.0f64, 36.0, 40.0])),
        prop::option::of(0i64..=5),
        prop::option::of(pick(TEXTS)),
        prop::option::of((
            prop::option::of(pick(&["b1", "b2", "b3"])),
            prop::option::of(pick(&["Guji", "Gesha", ""])),
            prop::option::of(pick(&["Onyx", ""])),
        )),
        prop::sample::subsequence(WORDS, 0..=2),
        (
            prop::option::of(pick(&["v60", "espresso", "custom:x", "AeroPress"])),
            prop::option::of(pick(&[20_000i64, 30_000, 30_000])),
            any::<bool>(),
            prop::option::weighted(0.1, Just(5i64)),
        ),
    )
        .prop_map(
            move |((android, at), profile, dose, yld, rating, notes, bean, tags, (method, dur, pinned, deleted))| {
                let bean = bean.map(|(id, name, roaster)| json!({ "beanId": id, "name": name, "roasterName": roaster }));
                if android {
                    json!({
                        "id": format!("s{id}"), "completedAtMs": at, "profileName": profile,
                        "doseG": dose, "yieldG": yld, "rating": rating, "notes": notes,
                        "bean": bean, "tags": tags, "brewMethod": method, "durationMs": dur,
                        "pinned": pinned, "deletedAt": deleted, "summary": { "sampleCount": 10 }
                    })
                } else {
                    json!({
                        "id": format!("s{id}"), "completedAt": at, "profileName": profile,
                        "metadata": { "dose": dose, "yieldOut": yld, "rating": rating, "notes": notes },
                        "bean": bean, "tags": tags, "brewMethod": method, "record": { "duration": dur },
                        "pinned": pinned, "deletedAt": deleted, "summary": { "sampleCount": 10 }
                    })
                }
            },
        )
}

fn rows() -> impl Strategy<Value = Vec<Value>> {
    (0usize..30).prop_flat_map(|n| (0..n).map(row).collect::<Vec<_>>())
}

fn query() -> impl Strategy<Value = HistoryQuery> {
    (
        pick(&[
            "", "", "gu", "guji", "turbo", "CAFÉ", "sour", "flo", "🙂 s", "nandu", "a*b",
            "espresso",
        ]),
        prop::option::of(pick(&[1_500i64, 2_000, 2_500])),
        prop::option::of(pick(&["Turbo", "Café Crème"])),
        prop::option::of(prop_oneof![
            pick(&["b1", "b2"]).prop_map(|b| BeanScope::Bag(b.to_owned())),
            pick(&[
                ("Guji", "Onyx"),
                ("Gesha", ""),
                ("Live Name", "Live Roaster")
            ])
            .prop_map(|(n, r)| {
                BeanScope::Coffee(
                    coffee_key(Some(n), Some(r).filter(|r| !r.is_empty())).unwrap_or_default(),
                )
            }),
        ]),
        prop::option::of(pick(&["espresso", "v60", "custom:x"])),
        prop::sample::subsequence(WORDS, 0..=2),
        any::<bool>(),
        (pick(&SortKey::ALL), any::<bool>()),
    )
        .prop_map(
            |(text, since, profile, bean, method, tags, pinned, (key, asc))| HistoryQuery {
                text: text.to_owned(),
                since_ms: since,
                profile: profile.map(str::to_owned),
                bean,
                method: method.map(str::to_owned),
                tags_all: tags.into_iter().map(str::to_owned).collect(),
                pinned_only: pinned,
                sort: Sort {
                    key,
                    dir: if asc { Dir::Asc } else { Dir::Desc },
                },
            },
        )
}

fn live() -> impl Strategy<Value = Live> {
    (any::<bool>(), any::<bool>()).prop_map(|(beans, methods)| Live {
        beans: if beans {
            vec![
                LiveBean {
                    bean_id: "b1".into(),
                    name: "Live Name".into(),
                    roaster: Some("Live Roaster".into()),
                    tags: vec!["floral".into(), "Sour".into()],
                },
                LiveBean {
                    bean_id: "b3".into(),
                    name: "Guji".into(),
                    roaster: Some("Onyx".into()),
                    tags: vec![],
                },
            ]
        } else {
            vec![]
        },
        methods: if methods {
            vec![LiveMethod {
                method: "custom:x".into(),
                label: "Cafézinho Espresso".into(),
            }]
        } else {
            vec![]
        },
    })
}

fn open(dir: &tempfile::TempDir, rows: &[Value], live: &Live) -> Store {
    let mut store = Store::open(
        dir.path().join("h.db"),
        OpenOptions::new(Role::Index).create(0),
    )
    .expect("opens");
    let rows: Vec<RowIn> = rows.iter().map(|r| RowIn::new(r.to_string())).collect();
    store.upsert(&rows).expect("writes");
    store.set_live(live).expect("writes");
    store
}

fn ids(p: &Page) -> Vec<String> {
    p.rows.iter().map(|r| r.id.clone()).collect()
}

fn close(a: Option<f32>, b: Option<f32>) -> bool {
    match (a, b) {
        (None, None) => true,
        (Some(a), Some(b)) => a == b || (a - b).abs() <= 1e-6 * a.abs().max(b.abs()),
        _ => false,
    }
}

fn stats_close(a: &BrewHistoryStats, b: &BrewHistoryStats) -> bool {
    a.count == b.count
        && a.mixed_methods == b.mixed_methods
        && a.scope_method == b.scope_method
        && close(a.beans_used_g, b.beans_used_g)
        && close(a.avg_weight_g, b.avg_weight_g)
        && close(a.avg_ratio, b.avg_ratio)
        && close(a.avg_time_s, b.avg_time_s)
        && close(a.avg_rating, b.avg_rating)
}

proptest! {
    #![proptest_config(ProptestConfig {
        cases: std::env::var("PROPTEST_CASES").ok().and_then(|c| c.parse().ok()).unwrap_or(64).min(2_000),
        ..ProptestConfig::default()
    })]

    #[test]
    fn every_query_answers_as_the_reference(rows in rows(), q in query(), live in live(), size in 1u32..9) {
        let d = tempfile::tempdir().expect("a dir");
        let mut store = open(&d, &rows, &live);
        let want = filter_sort(&ref_rows(&rows), &q, &live);
        let count = store.count(&q).expect("counts");
        prop_assert_eq!(count as usize, want.len());

        // Forward.
        let mut got = Vec::new();
        let mut page = store.page(&q, &Anchor::Start, size).expect("a page");
        loop {
            got.extend(ids(&page));
            if page.at_end { break; }
            page = store.page(&q, &Anchor::After(page.last.clone().expect("a row")), size).expect("a page");
        }
        prop_assert_eq!(&got, &want);

        // Backward, from an offset jump to the end.
        if count > 0 {
            let mut chunks = Vec::new();
            let mut page = store.page(&q, &Anchor::Offset(count.saturating_sub(size)), size).expect("a page");
            prop_assert!(page.at_end);
            loop {
                chunks.push(ids(&page));
                if page.at_start { break; }
                page = store.page(&q, &Anchor::Before(page.first.clone().expect("a row")), size).expect("a page");
            }
            let back: Vec<String> = chunks.into_iter().rev().flatten().collect();
            prop_assert_eq!(&back, &want);
        }

        for (i, id) in want.iter().enumerate() {
            prop_assert_eq!(store.index_of(&q, id).expect("finds"), Some(u32::try_from(i).expect("small")));
            let around = store.page(&q, &Anchor::Around { id: id.clone(), before: size / 2 }, size).expect("a page");
            let start = i - (size as usize / 2).min(size as usize - 1).min(i);
            prop_assert_eq!(ids(&around), want[start..(start + size as usize).min(want.len())].to_vec());
        }
        for r in &rows {
            let id = r["id"].as_str().expect("an id");
            if !want.iter().any(|w| w == id) {
                prop_assert_eq!(store.index_of(&q, id).expect("answers"), None);
            }
        }
        let s = store.stats(&q).expect("stats");
        let r = stats(&ref_rows(&rows), &q, &live);
        prop_assert!(stats_close(&s, &r), "{:?} vs {:?}", s, r);
        let edges = [3_000, 2_000];
        prop_assert_eq!(store.group_counts(&q, &edges).expect("counts"), group_counts(&ref_rows(&rows), &q, &live, &edges));
    }

    #[test]
    fn writes_between_pages_repeat_or_lose_only_edited_rows(
        rows in rows(),
        q in query(),
        size in 1u32..5,
        writes in prop::collection::vec((0usize..30, 0u8..3, 0i64..=5), 0..12),
    ) {
        let d = tempfile::tempdir().expect("a dir");
        let live = Live::default();
        let mut store = open(&d, &rows, &live);
        let before = filter_sort(&ref_rows(&rows), &q, &live);
        let mut current: Vec<Value> = rows.clone();
        let mut touched: BTreeSet<String> = BTreeSet::new();
        let mut writes = writes.into_iter();
        let mut got: Vec<String> = Vec::new();
        let mut page = store.page(&q, &Anchor::Start, size).expect("a page");
        loop {
            got.extend(ids(&page));
            // A write between pages: an edit of a row's sort fields, a
            // delete, or a new row.
            if let Some((n, kind, v)) = writes.next() && !current.is_empty() {
                let i = n % current.len();
                let id = current[i]["id"].as_str().expect("an id").to_owned();
                match kind {
                    0 => {
                        let mut r = current[i].clone();
                        r["rating"] = json!(v);
                        r["completedAt"] = json!(1_000 + v * 700);
                        r["profileName"] = json!(TEXTS[usize::try_from(v).expect("small")]);
                        store.upsert(&[RowIn::new(r.to_string())]).expect("writes");
                        current[i] = r;
                        touched.insert(id);
                    }
                    1 => {
                        store.delete(std::slice::from_ref(&id)).expect("deletes");
                        current.remove(i);
                        touched.insert(id);
                    }
                    _ => {
                        let mut r = current[i].clone();
                        r["id"] = json!(format!("new{n}-{v}-{}", got.len()));
                        store.upsert(&[RowIn::new(r.to_string())]).expect("writes");
                        current.push(r);
                    }
                }
            }
            if page.at_end { break; }
            let last = page.last.clone().expect("a row");
            page = store.page(&q, &Anchor::After(last), size).expect("a page");
        }
        // Keyset pages never repeat a row whose sort key stayed put; an edit
        // that moves a key from behind the cursor to ahead of it shows the
        // row again, which the window's ORDER notice refetches (SPEC §10).
        let mut seen: BTreeSet<&String> = BTreeSet::new();
        for id in &got {
            if !seen.insert(id) {
                prop_assert!(touched.contains(id), "{} appeared twice untouched: {:?}", id, got);
            }
        }
        for id in &before {
            if !got.contains(id) {
                prop_assert!(touched.contains(id), "{} went missing untouched", id);
            }
        }
    }
}

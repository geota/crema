//! PLAN C1's "done when": `reference::filter_sort` reproduces today's web
//! `filtered` and Android `filterAndSortShots` on 300 real-shaped rows, except
//! where SPEC §6 changes the rule, and each exception has a named test.
//!
//! The goldens are the shells' own answers: `golden.web.json` is written by
//! `web/src/lib/history/reference-golden.svelte.vitest.ts` through the page's
//! `HistoryListModel`, and `golden.android.json` by
//! `HistoryReferenceGoldenTest.kt` through `filterAndSortShots`. Both run in the
//! shells' suites, so a change to either shell's rules fails there first.
// Reads the repository's files with `std::fs`: native only.
#![cfg(not(target_arch = "wasm32"))]

use std::collections::BTreeMap;
use std::fs;
use std::path::PathBuf;

use de1_history::reference::{RefRow, filter_sort, ref_rows};
use de1_history::{BeanScope, Derived, Dir, HistoryQuery, Live, Sort, SortKey, derive_value};
use serde::Deserialize;
use serde_json::Value;

const DAY_MS: i64 = 24 * 60 * 60 * 1000;

fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures")
}

fn read(name: &str) -> String {
    fs::read_to_string(fixtures().join(name)).unwrap_or_else(|e| panic!("{name}: {e}"))
}

fn rows(name: &str) -> Vec<Value> {
    read(name)
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(|l| serde_json::from_str(l).expect("a JSON row"))
        .collect()
}

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

/// The shells' filter values as a core query.
fn to_query(q: &FixtureQuery, now: i64) -> HistoryQuery {
    HistoryQuery {
        text: q.text.clone(),
        since_ms: match q.range.as_str() {
            "7d" => Some(now - 7 * DAY_MS),
            "30d" => Some(now - 30 * DAY_MS),
            "all" => None,
            other => panic!("range {other}"),
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
    }
}

/// How the reference must relate to a shell's answer.
#[derive(Debug, Clone, Copy, PartialEq)]
enum Expect {
    /// The same ids in the same order.
    Exact,
    /// The same ids in the same order, except that each run of equal sort
    /// keys is reversed: Android sorts ascending, stably over the store's
    /// newest-first order, then reverses the whole list for a descending sort,
    /// so its ties come oldest first. The reference keeps them newest first
    /// (`change_descending_sorts_keep_ties_newest_first`,
    /// `change_equal_times_order_by_id_descending`).
    TiesReversed,
    /// A rule SPEC §5 or §6 changed: the answers differ, and the named test in
    /// `src/reference.rs` pins the new rule.
    Changed(&'static str),
}

const ACCENTS: &str = "change_search_ignores_accents_and_case";
const SHORT: &str = "change_searches_under_three_characters_filter_nothing";
const TAGS: &str = "change_search_reaches_effective_tags_on_both_shells";
const SPAN: &str = "change_search_never_spans_two_fields";
const BEAN_SORT: &str = "change_bean_sort_is_the_folded_label_the_row_shows";

/// The web queries whose rule changed.
const WEB_CHANGED: &[(&str, &str)] = &[
    ("sort-bean-desc", BEAN_SORT),
    ("sort-bean-asc", BEAN_SORT),
    ("combo-pinned-bean-asc", BEAN_SORT),
    ("text-cafe-plain", ACCENTS),
    ("text-zoe-plain", ACCENTS),
    ("text-two-chars", SHORT),
];

/// The Android queries whose rule changed.
const ANDROID_CHANGED: &[(&str, &str)] = &[
    ("text-cafe-plain", ACCENTS),
    ("text-zoe-plain", ACCENTS),
    ("text-two-chars", SHORT),
    ("text-sour", TAGS),
    ("text-cafe-accented", TAGS),
    ("text-dial", TAGS),
    ("text-bag-tag-floral", TAGS),
    ("text-legacy-label", SPAN),
];

fn expect_web(name: &str) -> Expect {
    WEB_CHANGED
        .iter()
        .find(|(n, _)| *n == name)
        .map_or(Expect::Exact, |(_, t)| Expect::Changed(t))
}

fn expect_android(q: &HistoryQuery, name: &str) -> Expect {
    if let Some((_, t)) = ANDROID_CHANGED.iter().find(|(n, _)| *n == name) {
        return Expect::Changed(t);
    }
    match q.sort.dir {
        Dir::Desc => Expect::TiesReversed,
        Dir::Asc => Expect::Exact,
    }
}

/// `ids` with each run of equal sort keys reversed.
fn ties_reversed(ids: &[String], by_id: &BTreeMap<String, Derived>, key: SortKey) -> Vec<String> {
    let k = |id: &String| {
        let d = &by_id[id];
        match key {
            SortKey::Date => format!("{}", d.completed_at),
            _ => format!("{:?}", d.sort_value(key)),
        }
    };
    let mut out = Vec::with_capacity(ids.len());
    let mut i = 0;
    while i < ids.len() {
        let mut j = i + 1;
        while j < ids.len() && k(&ids[j]) == k(&ids[i]) {
            j += 1;
        }
        out.extend(ids[i..j].iter().rev().cloned());
        i = j;
    }
    out
}

fn live() -> Live {
    serde_json::from_str(&read("live.json")).expect("live.json")
}

/// Checks the reference against one shell's golden; returns how many queries
/// matched exactly, with reversed ties, and changed.
fn check_shell(rows_file: &str, golden_file: &str, android: bool) -> (usize, usize, usize) {
    let raw = rows(rows_file);
    let rows: Vec<RefRow> = ref_rows(&raw);
    assert_eq!(rows.len(), 300);
    let by_id: BTreeMap<String, Derived> = rows
        .iter()
        .map(|r| (r.id.clone(), r.derived.clone()))
        .collect();
    let spec: Queries = serde_json::from_str(&read("queries.json")).expect("queries.json");
    let golden: BTreeMap<String, Vec<usize>> =
        serde_json::from_str(&read(golden_file)).expect("golden");
    let live = live();
    let mut tally = (0, 0, 0);
    let mut failures = Vec::new();
    for fq in &spec.queries {
        let Some(want) = golden.get(&fq.name) else {
            continue;
        };
        let want: Vec<String> = want.iter().map(|i| rows[*i].id.clone()).collect();
        let q = to_query(fq, spec.now);
        let got = filter_sort(&rows, &q, &live);
        let expect = if android {
            expect_android(&q, &fq.name)
        } else {
            expect_web(&fq.name)
        };
        let ok = match expect {
            Expect::Exact => {
                tally.0 += 1;
                got == want
            }
            Expect::TiesReversed => {
                tally.1 += 1;
                ties_reversed(&got, &by_id, q.sort.key) == want
            }
            Expect::Changed(_) => {
                tally.2 += 1;
                got != want
            }
        };
        if !ok {
            failures.push(format!(
                "{} ({expect:?}): reference {} rows, shell {} rows",
                fq.name,
                got.len(),
                want.len()
            ));
        }
    }
    assert!(
        failures.is_empty(),
        "{golden_file}:\n{}",
        failures.join("\n")
    );
    // Every listed exception is a query the shell answers.
    let changed = if android {
        ANDROID_CHANGED
    } else {
        WEB_CHANGED
    };
    for (name, _) in changed {
        assert!(golden.contains_key(*name), "{name} isn't in {golden_file}");
    }
    tally
}

#[test]
fn the_reference_reproduces_the_web_list() {
    let (exact, ties, changed) = check_shell("rows.web.jsonl", "golden.web.json", false);
    assert_eq!(ties, 0);
    assert_eq!(changed, WEB_CHANGED.len());
    assert!(exact >= 30, "{exact} exact");
}

#[test]
fn the_reference_reproduces_the_android_list() {
    let (exact, ties, changed) = check_shell("rows.android.jsonl", "golden.android.json", true);
    assert_eq!(changed, ANDROID_CHANGED.len());
    assert!(
        exact + ties >= 30,
        "{exact} exact, {ties} with reversed ties"
    );
}

#[test]
fn the_fixture_has_the_ties_and_variety_the_comparison_needs() {
    let rows = ref_rows(&rows("rows.web.jsonl"));
    let mut times: Vec<i64> = rows.iter().map(|r| r.derived.completed_at).collect();
    times.sort_unstable();
    assert!(times.windows(2).any(|w| w[0] == w[1]), "equal times");
    assert!(rows.iter().any(|r| r.derived.is_brew_log));
    assert!(rows.iter().any(|r| r.derived.pinned));
    assert!(
        rows.iter()
            .any(|r| r.derived.bean_id.is_none() && r.derived.coffee_key.is_some())
    );
    assert!(
        rows.iter()
            .any(|r| !r.derived.has_tags && r.derived.bean_id.is_some())
    );
    assert!(rows.iter().any(|r| r.derived.search_text.contains('苦')));
}

#[test]
fn both_shapes_of_a_row_derive_alike() {
    // The web row also carries the legacy flat bean label; Android's never did.
    let web = rows("rows.web.jsonl");
    let android = rows("rows.android.jsonl");
    assert_eq!(web.len(), android.len());
    for (w, a) in web.iter().zip(&android) {
        let dw = derive_value(w).expect("derives");
        let da = derive_value(a).expect("derives");
        let legacy = w["metadata"]["beans"].as_str().map(de1_history::fold);
        let web_text = match &legacy {
            Some(l) => dw.search_text.replacen(&format!("\u{1F}{l}"), "", 1),
            None => dw.search_text.clone(),
        };
        assert_eq!(web_text, da.search_text, "{}", w["id"]);
        assert_eq!(
            Derived {
                search_text: String::new(),
                ..dw
            },
            Derived {
                search_text: String::new(),
                ..da
            },
            "{}",
            w["id"]
        );
    }
}

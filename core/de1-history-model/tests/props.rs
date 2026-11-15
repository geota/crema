//! PLAN C1's property tests: cursor round-trip; fold idempotent; `derive_row`
//! total on arbitrary JSON objects, with every value one the schema takes
//! (S8); the per-family stats combination equals `brew_history_stats` over the
//! same rows within 1e-6 relative.

use de1_domain::{BrewHistoryStats, brew_history_stats};
use de1_history::cursor::Cursor;
use de1_history::derive::stat_input;
use de1_history::fold::FIELD_SEPARATOR;
use de1_history::{
    HistoryQuery, SortValue, combine, derive_row, derive_value, family_sums, fold, search_needle,
};
use proptest::prelude::*;
use serde_json::{Map, Value, json};

// ── Generators ──

/// Numbers a row may carry: ordinary ones, fractional times, and values past
/// f32's range or i64's.
fn number() -> impl Strategy<Value = Value> {
    prop_oneof![
        (-5i64..=5).prop_map(Value::from),
        (0.0f64..100.0).prop_map(Value::from),
        (0.0f64..2e12).prop_map(Value::from),
        any::<i64>().prop_map(Value::from),
        any::<u64>().prop_map(Value::from),
        prop_oneof![
            Just(1e39),
            Just(-1e39),
            Just(1e300),
            Just(-1e300),
            Just(f64::MAX),
            Just(-0.0),
            Just(f64::MIN_POSITIVE)
        ]
        .prop_map(Value::from),
        any::<f64>()
            .prop_filter("finite", |f| f.is_finite())
            .prop_map(Value::from),
    ]
}

fn text() -> impl Strategy<Value = String> {
    prop_oneof![
        "[a-zA-Z ]{0,12}",
        Just(String::new()),
        Just("Café Crème".to_owned()),
        Just("custom:abc".to_owned()),
        Just("İ\u{0307}e\u{0301}\u{1F}\t".to_owned()),
        "\\PC{0,8}",
        any::<String>(),
    ]
}

fn leaf() -> impl Strategy<Value = Value> {
    prop_oneof![
        Just(Value::Null),
        any::<bool>().prop_map(Value::from),
        number(),
        text().prop_map(Value::from),
    ]
}

fn any_json() -> impl Strategy<Value = Value> {
    leaf().prop_recursive(3, 24, 4, |inner| {
        prop_oneof![
            prop::collection::vec(inner.clone(), 0..4).prop_map(Value::Array),
            prop::collection::btree_map("[a-zA-Z]{1,6}", inner, 0..4)
                .prop_map(|m| Value::Object(m.into_iter().collect())),
        ]
    })
}

/// A value for a field the rules read: usually of the right type, sometimes not.
fn field() -> impl Strategy<Value = Value> {
    prop_oneof![4 => leaf(), 1 => any_json()]
}

/// An object with `keys`, each present or not, each any field value.
fn object(keys: &'static [&'static str]) -> impl Strategy<Value = Value> {
    prop::collection::vec(prop::option::of(field()), keys.len()).prop_map(move |vals| {
        let mut m = Map::new();
        for (k, v) in keys.iter().zip(vals) {
            if let Some(v) = v {
                m.insert((*k).to_owned(), v);
            }
        }
        Value::Object(m)
    })
}

/// An object shaped like a row of either shell, with any values.
fn row_like() -> impl Strategy<Value = Value> {
    const TOP: &[&str] = &[
        "id",
        "completedAt",
        "completedAtMs",
        "deletedAt",
        "pinned",
        "brewMethod",
        "brewMethodLabel",
        "profileName",
        "recipeName",
        "grinderModel",
        "visualizerId",
        "decentId",
        "durationMs",
        "doseG",
        "yieldG",
        "waterG",
        "rating",
        "notes",
        "nextPlan",
        "barista",
        "drinker",
        "peakWeightG",
    ];
    const META: &[&str] = &[
        "dose", "yieldOut", "waterG", "rating", "notes", "nextPlan", "beans", "barista", "drinker",
    ];
    const BEAN: &[&str] = &["beanId", "name", "roasterName", "grinder", "tags"];
    const SUMMARY: &[&str] = &["sampleCount", "peakWeight", "finalWeight"];
    (
        object(TOP),
        prop::option::of(object(META)),
        prop::option::of(object(&["duration"])),
        prop::option::of(object(BEAN)),
        prop::option::of(object(SUMMARY)),
        prop::option::of(prop::collection::vec(field(), 0..4)),
        prop::collection::btree_map("[a-z]{1,4}", any_json(), 0..2),
    )
        .prop_map(|(top, meta, record, bean, summary, tags, extra)| {
            let mut m = match top {
                Value::Object(m) => m,
                _ => Map::new(),
            };
            let mut put = |k: &str, v: Option<Value>| {
                if let Some(v) = v {
                    m.insert(k.to_owned(), v);
                }
            };
            put("metadata", meta);
            put("record", record);
            put("bean", bean);
            put("summary", summary);
            put("tags", tags.map(Value::Array));
            for (k, v) in extra {
                m.entry(k).or_insert(v);
            }
            Value::Object(m)
        })
}

/// A row with sensible stat inputs, in the web shape or Android's, with the
/// edges the stat rules care about: none, zero, negative, huge.
fn stat_row() -> impl Strategy<Value = Value> {
    let grams = || {
        prop_oneof![
            3 => (0.1f64..300.0).prop_map(Some),
            1 => Just(None),
            1 => Just(Some(0.0)),
            1 => Just(Some(-3.0)),
            1 => Just(Some(1e39)),
        ]
    };
    let method = prop_oneof![
        Just(None),
        Just(Some("espresso")),
        Just(Some("Espresso ")),
        Just(Some("")),
        Just(Some("pourover")),
        Just(Some("Pour Over")),
        Just(Some("aeropress")),
        Just(Some("custom:Ab-1")),
    ];
    (
        grams(),
        grams(),
        grams(),
        grams(),
        prop_oneof![Just(None), (0u8..=7).prop_map(Some)],
        prop_oneof![Just(0u64), 1u64..400_000],
        method,
        any::<bool>(),
    )
        .prop_map(|(dose, yld, water, peak, rating, duration, method, android)| {
            if android {
                json!({ "id": "a", "doseG": dose, "yieldG": yld, "waterG": water, "peakWeightG": peak,
                        "rating": rating, "durationMs": duration, "brewMethod": method })
            } else {
                json!({ "id": "w", "metadata": { "dose": dose, "yieldOut": yld, "waterG": water, "rating": rating },
                        "record": { "duration": duration }, "summary": { "peakWeight": peak }, "brewMethod": method })
            }
        })
}

fn sort_value() -> impl Strategy<Value = SortValue> {
    prop_oneof![
        Just(SortValue::Date),
        any::<i64>().prop_map(SortValue::Int),
        any::<f64>()
            .prop_filter("finite", |f| f.is_finite())
            .prop_map(SortValue::Real),
        any::<String>().prop_map(SortValue::Text),
    ]
}

// ── Helpers ──

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

// ── Properties ──

proptest! {
    #[test]
    fn a_cursor_round_trips(hash in any::<u64>(), key in sort_value(), at in any::<i64>(), id in any::<String>()) {
        let c = Cursor { query_hash: hash, key, completed_at: at, id };
        let text = c.encode();
        prop_assert!(text.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_'));
        prop_assert_eq!(Cursor::decode(&text), Ok(c));
    }

    #[test]
    fn junk_never_decodes_to_a_cursor_by_accident(text in "[A-Za-z0-9_-]{0,40}") {
        // Whatever decodes must encode back to the same text: one spelling each.
        if let Ok(c) = Cursor::decode(&text) {
            prop_assert_eq!(c.encode(), text);
        }
    }

    #[test]
    fn fold_is_idempotent(s in any::<String>()) {
        let once = fold(&s);
        prop_assert_eq!(fold(&once), once.clone());
        prop_assert!(!once.chars().any(char::is_control));
    }

    #[test]
    fn a_search_is_the_fold_of_itself(s in any::<String>()) {
        if let Some(n) = search_needle(&s) {
            prop_assert_eq!(search_needle(&n), Some(n.clone()));
            prop_assert!(n.chars().count() >= 3);
            // A query that equals a field always finds it.
            let row = json!({ "id": "x", "profileName": s });
            let d = derive_value(&row).expect("derives");
            prop_assert!(d.search_text.contains(&n));
        }
    }

    #[test]
    fn derive_row_is_total_and_fits_the_schema(v in prop_oneof![1 => any_json(), 4 => row_like()]) {
        let text = v.to_string();
        let result = derive_row(&text);
        prop_assert_eq!(result.is_ok(), v.is_object());
        if let Ok(d) = result {
            prop_assert!(d.sort_yield.is_finite());
            for st in [d.st_dose, d.st_yield, d.st_ratio, d.st_time_s, d.st_rating].into_iter().flatten() {
                prop_assert!(st.is_finite());
            }
            // Only the separator is a control character in the search text, and
            // never at either end or twice in a row.
            prop_assert!(d.search_text.chars().all(|c| c == FIELD_SEPARATOR || !c.is_control()));
            prop_assert!(!d.search_text.starts_with(FIELD_SEPARATOR));
            prop_assert!(!d.search_text.ends_with(FIELD_SEPARATOR));
            let doubled: String = [FIELD_SEPARATOR, FIELD_SEPARATOR].iter().collect();
            prop_assert!(!d.search_text.contains(&doubled));
            let mut tags = d.tags.clone();
            tags.sort();
            tags.dedup();
            prop_assert_eq!(tags.len(), d.tags.len());
            prop_assert_eq!(d.has_tags, !d.tags.is_empty());
            // The text and its parsed value derive alike. (Through the same
            // parser: serde_json's default float parse may be an ulp off the
            // value the text was printed from, the same in every build.)
            let parsed: Value = serde_json::from_str(&text).expect("JSON");
            prop_assert_eq!(derive_value(&parsed).ok(), Some(d));
        }
    }

    #[test]
    fn the_stats_combination_equals_brew_history_stats(rows in prop::collection::vec(stat_row(), 0..40)) {
        let derived: Vec<_> = rows.iter().map(|r| derive_value(r).expect("derives")).collect();
        let inputs: Vec<_> = rows.iter().map(stat_input).collect();
        let sql = combine(&family_sums(&derived));
        let core = brew_history_stats(&inputs);
        prop_assert!(stats_close(&sql, &core), "{sql:?} vs {core:?}");
    }

    #[test]
    fn the_stats_combination_ignores_how_rows_split_into_families(
        rows in prop::collection::vec(stat_row(), 0..40),
        cut in 0usize..40,
    ) {
        // Two stats queries' sums, added, are one query's: the order SQLite
        // groups in can't change the answer beyond rounding.
        let derived: Vec<_> = rows.iter().map(|r| derive_value(r).expect("derives")).collect();
        let cut = cut.min(derived.len());
        let mut sums = family_sums(&derived[..cut]);
        sums.extend(family_sums(&derived[cut..]));
        let core = brew_history_stats(&rows.iter().map(stat_input).collect::<Vec<_>>());
        let merged = combine(&merge(&sums));
        prop_assert!(stats_close(&merged, &core), "{merged:?} vs {core:?}");
    }

    #[test]
    fn equal_queries_hash_alike(text in "[a-zA-Z ]{0,8}", pinned in any::<bool>()) {
        let a = HistoryQuery { text: text.clone(), pinned_only: pinned, ..HistoryQuery::default() };
        let b = HistoryQuery { text: format!("  {}  ", text.to_uppercase()), pinned_only: pinned, ..HistoryQuery::default() };
        prop_assert_eq!(a.hash(), b.hash());
    }
}

/// Per-family sums merged by family, as SQLite's `GROUP BY` would give them.
fn merge(sums: &[de1_history::FamilySums]) -> Vec<de1_history::FamilySums> {
    let mut out: Vec<de1_history::FamilySums> = Vec::new();
    for s in sums {
        match out.iter_mut().find(|o| o.family == s.family) {
            Some(o) => {
                o.count += s.count;
                for (a, b) in [
                    (&mut o.dose, s.dose),
                    (&mut o.yield_g, s.yield_g),
                    (&mut o.ratio, s.ratio),
                    (&mut o.time_s, s.time_s),
                    (&mut o.rating, s.rating),
                ] {
                    a.0 += b.0;
                    a.1 += b.1;
                }
            }
            None => out.push(s.clone()),
        }
    }
    out
}

//! Generators shared by the property tests: JSON of any shape, and objects
//! shaped like either shell's row with any values in them.
#![allow(dead_code)]

use proptest::prelude::*;
use serde_json::{Map, Value};

/// Numbers a row may carry: ordinary ones, fractional times, and values past
/// f32's range or i64's.
pub fn number() -> impl Strategy<Value = Value> {
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

pub fn text() -> impl Strategy<Value = String> {
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

pub fn leaf() -> impl Strategy<Value = Value> {
    prop_oneof![
        Just(Value::Null),
        any::<bool>().prop_map(Value::from),
        number(),
        text().prop_map(Value::from),
    ]
}

pub fn any_json() -> impl Strategy<Value = Value> {
    leaf().prop_recursive(3, 24, 4, |inner| {
        prop_oneof![
            prop::collection::vec(inner.clone(), 0..4).prop_map(Value::Array),
            prop::collection::btree_map("[a-zA-Z]{1,6}", inner, 0..4)
                .prop_map(|m| Value::Object(m.into_iter().collect())),
        ]
    })
}

/// A value for a field the rules read: usually of the right type, sometimes not.
pub fn field() -> impl Strategy<Value = Value> {
    prop_oneof![4 => leaf(), 1 => any_json()]
}

/// An object with `keys`, each present or not, each any field value.
pub fn object(keys: &'static [&'static str]) -> impl Strategy<Value = Value> {
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
pub fn row_like() -> impl Strategy<Value = Value> {
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

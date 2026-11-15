//! The reference rules (SPEC §6): the History list's filter, search, sort and
//! stats over rows in memory. This is the spec of the rules: the SQL in
//! [`crate::sql`] must give the same ids in the same order (C2's property
//! tests), and stage 1's dev oracle compares every index answer with it.
//!
//! It ports today's web `filtered` (`list-filter.ts`) and Android
//! `filterAndSortShots` (`ShotFilter.kt`), with the decisions SPEC §5 and §6
//! made where they differed or changed:
//! - search folds case and accents, needs three characters, and matches one
//!   field at a time, among them the effective tags (Android searched no tags
//!   and matched "Roaster · Name" as one field);
//! - the tag filter applies on both shells;
//! - the bean sort is the folded "Roaster · Name" the row shows (web sorted
//!   by name), and the profile sort is folded;
//! - ties follow the sort's direction: `completed_at`, then `id`, descending
//!   under a descending sort and ascending under an ascending one, so one
//!   index serves both (B1). Today web keeps ties newest first both ways, and
//!   Android puts them oldest first under a descending sort.

use std::cmp::Ordering;

use de1_domain::{BrewHistoryStats, brew_history_stats};
use serde_json::Value;

use crate::derive::{Derived, LiveRows, derive_live, derive_value, row_id, stat_input};
use crate::query::{BeanScope, Dir, HistoryQuery, Live, SortKey};

/// One row as the reference reads it: its id, its derived columns, and the
/// stat input `brew_history_stats` takes today.
#[derive(Debug, Clone)]
pub struct RefRow {
    /// The row's id.
    pub id: String,
    /// [`derive_value`] of its `row_json`.
    pub derived: Derived,
    /// [`stat_input`] of its `row_json`.
    pub stat: de1_domain::BrewStatInput,
}

impl RefRow {
    /// The row for a parsed `row_json`, or `None` when it has no id or isn't
    /// an object.
    #[must_use]
    pub fn new(row: &Value) -> Option<RefRow> {
        Some(RefRow {
            id: row_id(row)?.to_owned(),
            derived: derive_value(row).ok()?,
            stat: stat_input(row),
        })
    }
}

/// The rows in memory: rows without an id are skipped.
#[must_use]
pub fn ref_rows(rows: &[Value]) -> Vec<RefRow> {
    rows.iter().filter_map(RefRow::new).collect()
}

/// The ids `q` lists, in its order.
#[must_use]
pub fn filter_sort(rows: &[RefRow], q: &HistoryQuery, live: &Live) -> Vec<String> {
    let live = derive_live(live);
    let mut hits: Vec<&RefRow> = rows.iter().filter(|r| passes(r, q, &live)).collect();
    hits.sort_by(|a, b| order(&a.derived, &a.id, &b.derived, &b.id, q));
    hits.into_iter().map(|r| r.id.clone()).collect()
}

/// The stat strip over the rows `q` lists: `brew_history_stats` over their
/// stat inputs, as both shells compute it today.
#[must_use]
pub fn stats(rows: &[RefRow], q: &HistoryQuery, live: &Live) -> BrewHistoryStats {
    let live = derive_live(live);
    let inputs: Vec<_> = rows
        .iter()
        .filter(|r| passes(r, q, &live))
        .map(|r| r.stat.clone())
        .collect();
    brew_history_stats(&inputs)
}

/// How many of the rows `q` lists fall in each bucket the descending `edges`
/// cut: `completed_at >= edges[0]`, then `[edges[1], edges[0])`, and so on,
/// and last `< edges[n-1]`. The day groups' counts.
#[must_use]
pub fn group_counts(rows: &[RefRow], q: &HistoryQuery, live: &Live, edges_ms: &[i64]) -> Vec<u32> {
    let live = derive_live(live);
    let mut edges = edges_ms.to_vec();
    edges.sort_unstable_by(|a, b| b.cmp(a));
    let mut counts = vec![0_u32; edges.len() + 1];
    for r in rows.iter().filter(|r| passes(r, q, &live)) {
        let at = r.derived.completed_at;
        let bucket = edges.iter().position(|e| at >= *e).unwrap_or(edges.len());
        counts[bucket] = counts[bucket].saturating_add(1);
    }
    counts
}

/// The dev oracle's entry point for the bridges: [`filter_sort`] over JSON. Takes
/// a JSON array of rows (either shell's `row_json` objects), a [`HistoryQuery`]
/// and a [`Live`]; returns the listed ids as a JSON array.
///
/// # Errors
/// When an argument doesn't parse.
pub fn filter_sort_json(
    rows_json: &str,
    query_json: &str,
    live_json: &str,
) -> Result<String, String> {
    let (rows, q, live) = parse(rows_json, query_json, live_json)?;
    serde_json::to_string(&filter_sort(&rows, &q, &live)).map_err(|e| e.to_string())
}

/// [`stats`] over JSON, as [`filter_sort_json`]; returns `BrewHistoryStats` JSON.
///
/// # Errors
/// When an argument doesn't parse.
pub fn stats_json(rows_json: &str, query_json: &str, live_json: &str) -> Result<String, String> {
    let (rows, q, live) = parse(rows_json, query_json, live_json)?;
    serde_json::to_string(&stats(&rows, &q, &live)).map_err(|e| e.to_string())
}

fn parse(
    rows_json: &str,
    query_json: &str,
    live_json: &str,
) -> Result<(Vec<RefRow>, HistoryQuery, Live), String> {
    let rows: Vec<Value> = serde_json::from_str(rows_json).map_err(|e| format!("rows: {e}"))?;
    let q: HistoryQuery = serde_json::from_str(query_json).map_err(|e| format!("query: {e}"))?;
    let live: Live = serde_json::from_str(live_json).map_err(|e| format!("live: {e}"))?;
    Ok((ref_rows(&rows), q, live))
}

/// The row's effective coffee key: its live bag's, when the bag is in the
/// library, else its snapshot's. The shells' `shotCoffee`.
fn effective_coffee<'a>(d: &'a Derived, live: &'a LiveRows) -> Option<&'a str> {
    let bean = d
        .bean_id
        .as_deref()
        .and_then(|id| live.beans.iter().find(|b| b.bean_id == id));
    match bean {
        Some(b) => b.coffee_key.as_deref(),
        None => d.coffee_key.as_deref(),
    }
}

/// Whether the row carries `tag`: among its own tags, or its live bag's when
/// it has none.
fn has_effective_tag(d: &Derived, tag: &str, live: &LiveRows) -> bool {
    if d.has_tags {
        return d.tags.iter().any(|t| t == tag);
    }
    d.bean_id.as_deref().is_some_and(|id| {
        live.bean_tags
            .iter()
            .any(|bt| bt.bean_id == id && bt.tag == tag)
    })
}

/// Whether the folded `needle` is in one of the row's fields: its search
/// text, its live custom-method label, or, with no tags of its own, one of its
/// live bag's tags.
fn matches_text(d: &Derived, needle: &str, live: &LiveRows) -> bool {
    d.search_text.contains(needle)
        || live
            .methods
            .iter()
            .any(|m| m.method == d.method && m.label_folded.contains(needle))
        || (!d.has_tags
            && d.bean_id.as_deref().is_some_and(|id| {
                live.bean_tags
                    .iter()
                    .any(|bt| bt.bean_id == id && bt.tag_folded.contains(needle))
            }))
}

/// Whether row `r` is in `q`'s list.
fn passes(r: &RefRow, q: &HistoryQuery, live: &LiveRows) -> bool {
    let d = &r.derived;
    if !d.is_live() {
        return false;
    }
    if q.since_ms.is_some_and(|since| d.completed_at < since) {
        return false;
    }
    if q.profile
        .as_deref()
        .is_some_and(|p| d.profile_name.as_deref() != Some(p))
    {
        return false;
    }
    if q.pinned_only && !d.pinned {
        return false;
    }
    let in_scope = match &q.bean {
        None => true,
        Some(BeanScope::Bag(id)) => d.bean_id.as_deref() == Some(id.as_str()),
        Some(BeanScope::Coffee(key)) => effective_coffee(d, live) == Some(key.as_str()),
    };
    if !in_scope {
        return false;
    }
    if q.method.as_deref().is_some_and(|m| d.method != m) {
        return false;
    }
    if !q.tags().iter().all(|t| has_effective_tag(d, t, live)) {
        return false;
    }
    match q.needle() {
        None => true,
        Some(needle) => matches_text(d, &needle, live),
    }
}

/// The total order: the sort key, then `completed_at`, then `id`, all in
/// the chosen direction. Text compares by code point, as SQLite's `BINARY`
/// does.
fn order(a: &Derived, a_id: &str, b: &Derived, b_id: &str, q: &HistoryQuery) -> Ordering {
    let key = match q.sort.key {
        SortKey::Date => a.completed_at.cmp(&b.completed_at),
        SortKey::Rating => a.sort_rating.cmp(&b.sort_rating),
        SortKey::Profile => a.sort_profile.cmp(&b.sort_profile),
        SortKey::Bean => a.sort_bean.cmp(&b.sort_bean),
        SortKey::Yield => a.sort_yield.total_cmp(&b.sort_yield),
        SortKey::Time => a.sort_time.cmp(&b.sort_time),
    };
    let ascending = key
        .then_with(|| a.completed_at.cmp(&b.completed_at))
        .then_with(|| a_id.cmp(b_id));
    match q.sort.dir {
        Dir::Asc => ascending,
        Dir::Desc => ascending.reverse(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::query::{LiveBean, LiveMethod, Sort};
    use serde_json::json;

    fn rows(v: &[Value]) -> Vec<RefRow> {
        ref_rows(v)
    }

    fn q() -> HistoryQuery {
        HistoryQuery::default()
    }

    fn sorted(key: SortKey, dir: Dir) -> HistoryQuery {
        HistoryQuery {
            sort: Sort { key, dir },
            ..q()
        }
    }

    fn search(text: &str) -> HistoryQuery {
        HistoryQuery {
            text: text.into(),
            ..q()
        }
    }

    fn ids(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| (*s).to_owned()).collect()
    }

    // ── Where SPEC §5 and §6 change today's rules: one named test each ──

    #[test]
    fn change_search_ignores_accents_and_case() {
        let r = rows(&[
            json!({ "id": "a", "profileName": "Café Crème" }),
            json!({ "id": "b", "metadata": { "notes": "COLÔMBIA" } }),
            json!({ "id": "c", "profileName": "Turbo" }),
        ]);
        let live = Live::default();
        assert_eq!(filter_sort(&r, &search("cafe"), &live), ids(&["a"]));
        assert_eq!(filter_sort(&r, &search("CAFÉ"), &live), ids(&["a"]));
        assert_eq!(filter_sort(&r, &search("colombia"), &live), ids(&["b"]));
    }

    #[test]
    fn change_searches_under_three_characters_filter_nothing() {
        let r = rows(&[
            json!({ "id": "a", "completedAt": 2, "profileName": "Ki" }),
            json!({ "id": "b", "completedAt": 1, "profileName": "Turbo" }),
        ]);
        let live = Live::default();
        assert_eq!(filter_sort(&r, &search("ki"), &live), ids(&["a", "b"]));
        assert_eq!(
            filter_sort(&r, &search("tu"), &live),
            filter_sort(&r, &q(), &live)
        );
        assert_eq!(filter_sort(&r, &search("tur"), &live), ids(&["b"]));
    }

    #[test]
    fn change_search_never_spans_two_fields() {
        // Android matched "Roaster · Name" as one string; each is its own field now.
        let r = rows(&[json!({ "id": "a", "bean": { "name": "Kenya", "roasterName": "Sey" } })]);
        let live = Live::default();
        assert!(filter_sort(&r, &search("sey · ken"), &live).is_empty());
        assert_eq!(filter_sort(&r, &search("kenya"), &live), ids(&["a"]));
        assert_eq!(filter_sort(&r, &search("sey"), &live), ids(&["a"]));
    }

    #[test]
    fn change_search_reaches_effective_tags_on_both_shells() {
        // Android searched no tags; web searched the effective tags.
        let r = rows(&[
            json!({ "id": "a", "completedAt": 3, "tags": ["Dial-in"] }),
            json!({ "id": "b", "completedAt": 2, "bean": { "beanId": "b1", "name": "Guji" } }),
            json!({ "id": "c", "completedAt": 1, "tags": ["milk"], "bean": { "beanId": "b1", "name": "Guji" } }),
        ]);
        let live = Live {
            beans: vec![LiveBean {
                bean_id: "b1".into(),
                name: "Guji".into(),
                roaster: None,
                tags: vec!["Floral".into()],
            }],
            methods: vec![],
        };
        assert_eq!(filter_sort(&r, &search("dial"), &live), ids(&["a"]));
        // The bag's tag counts only for a row with no tags of its own.
        assert_eq!(filter_sort(&r, &search("floral"), &live), ids(&["b"]));
    }

    #[test]
    fn change_tag_filter_applies_on_both_shells() {
        let r = rows(&[
            json!({ "id": "a", "completedAt": 3, "tags": ["dial-in", "sour"] }),
            json!({ "id": "b", "completedAt": 2, "tags": ["dial-in"] }),
            json!({ "id": "c", "completedAt": 1, "bean": { "beanId": "b1", "name": "Guji" } }),
        ]);
        let live = Live {
            beans: vec![LiveBean {
                bean_id: "b1".into(),
                name: "Guji".into(),
                roaster: None,
                tags: vec!["dial-in".into()],
            }],
            methods: vec![],
        };
        let tagged = |t: &[&str]| HistoryQuery {
            tags_all: t.iter().map(|s| (*s).to_owned()).collect(),
            ..q()
        };
        assert_eq!(
            filter_sort(&r, &tagged(&["dial-in"]), &live),
            ids(&["a", "b", "c"])
        );
        assert_eq!(
            filter_sort(&r, &tagged(&["sour", "dial-in"]), &live),
            ids(&["a"])
        );
        // Exact tags: a case difference is another tag.
        assert!(filter_sort(&r, &tagged(&["Dial-in"]), &live).is_empty());
    }

    #[test]
    fn change_bean_sort_is_the_folded_label_the_row_shows() {
        // Web sorted by the bean name alone: "Aa" (roaster Zed) before "Bb" (roaster Ace).
        let r = rows(&[
            json!({ "id": "a", "completedAt": 2, "bean": { "name": "Aa", "roasterName": "Zed" } }),
            json!({ "id": "b", "completedAt": 1, "bean": { "name": "Bb", "roasterName": "Ácé" } }),
            json!({ "id": "c", "completedAt": 3 }),
        ]);
        assert_eq!(
            filter_sort(&r, &sorted(SortKey::Bean, Dir::Asc), &Live::default()),
            ids(&["c", "b", "a"])
        );
    }

    #[test]
    fn change_profile_sort_ignores_accents() {
        let r = rows(&[
            json!({ "id": "a", "completedAt": 1, "profileName": "Éclair" }),
            json!({ "id": "b", "completedAt": 2, "profileName": "Default" }),
            json!({ "id": "c", "completedAt": 3, "profileName": "Fast" }),
        ]);
        assert_eq!(
            filter_sort(&r, &sorted(SortKey::Profile, Dir::Asc), &Live::default()),
            ids(&["b", "a", "c"])
        );
    }

    #[test]
    fn change_ties_follow_the_sort_direction() {
        // Android reversed a stable ascending sort, so its descending ties went
        // oldest first; web kept them newest first both ways. Now they run the
        // sort's way, so one index serves both directions (B1).
        let r = rows(&[
            json!({ "id": "old", "completedAt": 1, "metadata": { "rating": 4 } }),
            json!({ "id": "new", "completedAt": 2, "metadata": { "rating": 4 } }),
            json!({ "id": "top", "completedAt": 0, "metadata": { "rating": 5 } }),
        ]);
        let live = Live::default();
        assert_eq!(
            filter_sort(&r, &sorted(SortKey::Rating, Dir::Desc), &live),
            ids(&["top", "new", "old"])
        );
        assert_eq!(
            filter_sort(&r, &sorted(SortKey::Rating, Dir::Asc), &live),
            ids(&["old", "new", "top"])
        );
    }

    #[test]
    fn change_equal_times_order_by_id_the_sorts_way() {
        let r = rows(&[
            json!({ "id": "a", "completedAt": 5 }),
            json!({ "id": "c", "completedAt": 5 }),
            json!({ "id": "b", "completedAt": 5 }),
        ]);
        let live = Live::default();
        assert_eq!(filter_sort(&r, &q(), &live), ids(&["c", "b", "a"]));
        assert_eq!(
            filter_sort(&r, &sorted(SortKey::Date, Dir::Asc), &live),
            ids(&["a", "b", "c"])
        );
    }

    #[test]
    fn change_yield_and_time_sorts_on_both_shells() {
        let r = rows(&[
            json!({ "id": "a", "completedAt": 1, "metadata": { "yieldOut": 40.0 }, "record": { "duration": 20_000 } }),
            json!({ "id": "b", "completedAt": 2, "summary": { "finalWeight": 30.0 }, "record": { "duration": 30_000 } }),
            json!({ "id": "c", "completedAt": 3 }),
        ]);
        let live = Live::default();
        assert_eq!(
            filter_sort(&r, &sorted(SortKey::Yield, Dir::Desc), &live),
            ids(&["a", "b", "c"])
        );
        assert_eq!(
            filter_sort(&r, &sorted(SortKey::Time, Dir::Asc), &live),
            ids(&["c", "a", "b"])
        );
    }

    #[test]
    fn change_custom_method_matches_its_live_label_and_its_snapshot() {
        // The live label is matched at query time; the snapshot is in the row's
        // own search text, so a renamed method's old name still finds it.
        let r = rows(&[json!({ "id": "a", "brewMethod": "custom:x", "brewMethodLabel": "Ibrik" })]);
        let live = Live {
            beans: vec![],
            methods: vec![LiveMethod {
                method: "custom:x".into(),
                label: "Cezve".into(),
            }],
        };
        assert_eq!(filter_sort(&r, &search("cezve"), &live), ids(&["a"]));
        assert_eq!(filter_sort(&r, &search("ibrik"), &live), ids(&["a"]));
        assert!(filter_sort(&r, &search("cezve"), &Live::default()).is_empty());
    }

    // ── Today's rules, kept ──

    #[test]
    fn tombstones_are_never_listed() {
        let r = rows(&[json!({ "id": "a", "deletedAt": 5 }), json!({ "id": "b" })]);
        assert_eq!(filter_sort(&r, &q(), &Live::default()), ids(&["b"]));
    }

    #[test]
    fn a_coffee_resolves_through_the_live_bag_then_the_snapshot() {
        let r = rows(&[
            // Renamed in the library: the live name decides.
            json!({ "id": "a", "completedAt": 3, "bean": { "beanId": "b1", "name": "Old", "roasterName": "Sey" } }),
            // Gone from the library: the snapshot decides.
            json!({ "id": "b", "completedAt": 2, "bean": { "beanId": "gone", "name": "Kenya", "roasterName": "sey" } }),
            json!({ "id": "c", "completedAt": 1, "bean": { "beanId": "b2", "name": "Kenya", "roasterName": "Sey" } }),
        ]);
        let live = Live {
            beans: vec![
                LiveBean {
                    bean_id: "b1".into(),
                    name: "Kenya".into(),
                    roaster: Some("Sey".into()),
                    tags: vec![],
                },
                // In the library with an empty name: no coffee, not the snapshot's.
                LiveBean {
                    bean_id: "b2".into(),
                    name: " ".into(),
                    roaster: None,
                    tags: vec![],
                },
            ],
            methods: vec![],
        };
        let coffee = HistoryQuery {
            bean: Some(BeanScope::Coffee("sey|kenya".into())),
            ..q()
        };
        assert_eq!(filter_sort(&r, &coffee, &live), ids(&["a", "b"]));
        let bag = HistoryQuery {
            bean: Some(BeanScope::Bag("b2".into())),
            ..q()
        };
        assert_eq!(filter_sort(&r, &bag, &live), ids(&["c"]));
    }

    #[test]
    fn range_profile_method_and_pinned_filters() {
        let r = rows(&[
            json!({ "id": "a", "completedAt": 10, "profileName": "Turbo", "pinned": true }),
            json!({ "id": "b", "completedAt": 20, "brewMethod": "Pourover" }),
            json!({ "id": "c", "completedAt": 30, "brewMethod": "espresso", "profileName": "turbo" }),
        ]);
        let live = Live::default();
        let since = HistoryQuery {
            since_ms: Some(20),
            ..q()
        };
        assert_eq!(filter_sort(&r, &since, &live), ids(&["c", "b"]));
        let profile = HistoryQuery {
            profile: Some("Turbo".into()),
            ..q()
        };
        assert_eq!(filter_sort(&r, &profile, &live), ids(&["a"]));
        let espresso = HistoryQuery {
            method: Some("espresso".into()),
            ..q()
        };
        assert_eq!(filter_sort(&r, &espresso, &live), ids(&["c", "a"]));
        let pinned = HistoryQuery {
            pinned_only: true,
            ..q()
        };
        assert_eq!(filter_sort(&r, &pinned, &live), ids(&["a"]));
    }

    #[test]
    fn stats_cover_the_listed_rows_only() {
        let r = rows(&[
            json!({ "id": "a", "profileName": "Turbo", "metadata": { "rating": 5 } }),
            json!({ "id": "b", "profileName": "Other", "metadata": { "rating": 1 } }),
        ]);
        let profile = HistoryQuery {
            profile: Some("Turbo".into()),
            ..q()
        };
        let s = stats(&r, &profile, &Live::default());
        assert_eq!(s.count, 1);
        assert_eq!(s.avg_rating, Some(5.0));
    }

    #[test]
    fn group_counts_bucket_by_descending_edges() {
        let r = rows(&[
            json!({ "id": "a", "completedAt": 100 }),
            json!({ "id": "b", "completedAt": 90 }),
            json!({ "id": "c", "completedAt": 50 }),
            json!({ "id": "d", "completedAt": 10 }),
        ]);
        assert_eq!(
            group_counts(&r, &q(), &Live::default(), &[50, 95, 20]),
            vec![1, 2, 0, 1]
        );
        assert_eq!(group_counts(&r, &q(), &Live::default(), &[]), vec![4]);
    }

    #[test]
    fn the_json_entry_points_parse_and_answer() {
        let rows =
            r#"[{"id":"a","completedAt":1,"profileName":"Turbo"},{"id":"b","completedAt":2}]"#;
        let out = filter_sort_json(rows, r#"{"profile":"Turbo"}"#, "{}").expect("answers");
        assert_eq!(out, r#"["a"]"#);
        let s = stats_json(rows, "{}", "{}").expect("answers");
        assert!(s.contains(r#""count":2"#));
        assert!(filter_sort_json("{", "{}", "{}").is_err());
        assert!(filter_sort_json("[]", r#"{"sort":{"key":"nope"}}"#, "{}").is_err());
    }

    #[test]
    fn rows_without_an_id_are_skipped() {
        assert_eq!(
            ref_rows(&[json!({}), json!([]), json!({ "id": "a" })]).len(),
            1
        );
    }
}

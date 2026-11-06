//! The bean picker's list — which bags a picker offers and in what order, the
//! one implementation every shell calls (geota/crema#301).
//!
//! The pickers are where a bag gets chosen rather than browsed: re-attributing
//! a shot, logging a brew, the Brew strip's swap. They had drifted: Android
//! ranked in Kotlin and hid archived bags on some paths, web sorted by
//! `updated_at`, and each app's "store order" meant something else (Android
//! appends a new bag, web prepends it).
//!
//! # Order
//!
//! [`rank_beans_for_picker`] is a total order, so two shells holding the same
//! bags in different store orders list them identically:
//!
//! 1. the current bag (the shot's, or the loaded one);
//! 2. favourites;
//! 3. the rest.
//!
//! Inside a group, oldest first by `created_at`, then by id. That is the order
//! Android's picker already showed for bags made on the device.
//!
//! # What is listed
//!
//! [`bean_picker`] drops tombstones always and archived bags unless the
//! "Include archived" toggle is on. The current bag stays listed with the
//! toggle off even when archived: a shot can already be bound to a finished
//! bag, and the picker has to show which one.
//!
//! While searching, the matcher in [`crate::bean_search`] decides which bags
//! show and leads the order, as on the library pages. Bags that tie exactly
//! keep the order above.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bean::{Bean, Roaster};
use crate::bean_search::{is_blank_query, search_beans};

/// What a picker is showing.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BeanPickerQuery {
    /// The bag the picker opened on: the shot's, the draft's, or the loaded
    /// one. It leads the list and stays listed when archived.
    pub current_id: Option<String>,
    /// The "Include archived" toggle.
    pub include_archived: bool,
    /// The search box. Blank means no search.
    pub search: String,
}

/// The picker's rows.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BeanPickerResult {
    /// The bags to list, in display order.
    pub ids: Vec<String>,
    /// Whether the library holds an archived bag besides the current one —
    /// whether "Include archived" can change the list at all. Ignores the
    /// search, so the toggle doesn't come and go while typing.
    pub has_archived: bool,
}

/// `beans` in picker order: the current bag, then favourites, then the rest,
/// each group oldest first with the id as the final tiebreak. The caller
/// decides which bags go in; see the [module docs](self) for why the order
/// never depends on the input's.
pub fn rank_beans_for_picker<'a>(
    beans: impl IntoIterator<Item = &'a Bean>,
    current_id: Option<&str>,
) -> Vec<&'a Bean> {
    let mut ranked: Vec<&Bean> = beans.into_iter().collect();
    ranked.sort_by_key(|b| {
        (
            current_id != Some(b.id.as_str()),
            !b.favourite,
            b.created_at,
            b.id.as_str(),
        )
    });
    ranked
}

/// The rows of one picker: see the [module docs](self). Roasters are passed so
/// a bag can match on its roastery's name.
pub fn bean_picker(
    beans: &[Bean],
    roasters: &[Roaster],
    query: &BeanPickerQuery,
) -> BeanPickerResult {
    let current = query.current_id.as_deref();
    let is_current = |b: &Bean| current == Some(b.id.as_str());
    let live = || beans.iter().filter(|b| b.deleted_at.is_none());

    let listed =
        live().filter(|b| query.include_archived || b.archived_at.is_none() || is_current(b));
    let mut ranked = rank_beans_for_picker(listed, current);

    if !is_blank_query(&query.search) {
        let hits = search_beans(beans, roasters, &query.search);
        let scores: HashMap<&str, f32> = hits.iter().map(|h| (h.id.as_str(), h.score)).collect();
        ranked.retain(|b| scores.contains_key(b.id.as_str()));
        // A stable sort, so bags that tie exactly keep their rank.
        ranked.sort_by(|a, b| {
            let score = |bean: &Bean| scores.get(bean.id.as_str()).copied().unwrap_or(-1.0);
            score(b).total_cmp(&score(a))
        });
    }

    BeanPickerResult {
        ids: ranked.into_iter().map(|b| b.id.clone()).collect(),
        has_archived: live().any(|b| b.archived_at.is_some() && !is_current(b)),
    }
}

/// [`bean_picker`] over JSON: `beans_json` and `roasters_json` are arrays of
/// `Bean` / `Roaster`, `query_json` a [`BeanPickerQuery`], and the result a
/// [`BeanPickerResult`].
pub fn bean_picker_json(
    beans_json: &str,
    roasters_json: &str,
    query_json: &str,
) -> Result<String, String> {
    let beans: Vec<Bean> = serde_json::from_str(beans_json).map_err(|e| e.to_string())?;
    let roasters: Vec<Roaster> = serde_json::from_str(roasters_json).map_err(|e| e.to_string())?;
    let query: BeanPickerQuery = serde_json::from_str(query_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&bean_picker(&beans, &roasters, &query)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A bag created at `created_at`, named after its id.
    fn bag(id: &str, created_at: i64) -> Bean {
        let mut b = Bean::new(id.into(), id.into(), created_at);
        b.created_at = created_at;
        b.updated_at = created_at;
        b
    }
    fn fav(mut b: Bean) -> Bean {
        b.favourite = true;
        b
    }
    fn archived(mut b: Bean) -> Bean {
        b.archived_at = Some(1);
        b
    }
    fn deleted(mut b: Bean) -> Bean {
        b.deleted_at = Some(1);
        b
    }

    fn ranked<'a>(beans: &'a [Bean], current: Option<&str>) -> Vec<&'a str> {
        rank_beans_for_picker(beans, current)
            .into_iter()
            .map(|b| b.id.as_str())
            .collect()
    }

    fn query(current: Option<&str>, include_archived: bool, search: &str) -> BeanPickerQuery {
        BeanPickerQuery {
            current_id: current.map(str::to_owned),
            include_archived,
            search: search.to_owned(),
        }
    }

    fn ids(beans: &[Bean], q: &BeanPickerQuery) -> Vec<String> {
        bean_picker(beans, &[], q).ids
    }

    #[test]
    fn the_current_bag_leads_then_favourites_then_the_rest() {
        let beans = [bag("a", 1), fav(bag("b", 2)), bag("c", 3), fav(bag("d", 4))];
        assert_eq!(ranked(&beans, Some("c")), ["c", "b", "d", "a"]);
    }

    #[test]
    fn a_current_favourite_outranks_the_other_favourites() {
        let beans = [fav(bag("a", 1)), fav(bag("b", 2))];
        assert_eq!(ranked(&beans, Some("b")), ["b", "a"]);
    }

    #[test]
    fn no_current_bag_leaves_favourites_first() {
        let beans = [bag("a", 1), fav(bag("b", 2))];
        assert_eq!(ranked(&beans, None), ["b", "a"]);
    }

    #[test]
    fn a_current_id_outside_the_library_changes_nothing() {
        let beans = [bag("a", 1), fav(bag("b", 2))];
        assert_eq!(ranked(&beans, Some("elsewhere")), ["b", "a"]);
    }

    #[test]
    fn each_group_runs_oldest_first() {
        let beans = [bag("new", 3), bag("old", 1), bag("mid", 2)];
        assert_eq!(ranked(&beans, None), ["old", "mid", "new"]);
    }

    #[test]
    fn the_id_settles_bags_created_together() {
        let beans = [bag("b", 1), bag("a", 1)];
        assert_eq!(ranked(&beans, None), ["a", "b"]);
    }

    #[test]
    fn store_order_never_reaches_the_result() {
        // Android appends a new bag and web prepends it: same bags, reversed.
        let forward = [bag("a", 1), fav(bag("b", 2)), bag("c", 2), bag("d", 3)];
        let mut backward = forward.clone();
        backward.reverse();
        assert_eq!(ranked(&forward, Some("d")), ranked(&backward, Some("d")));
    }

    #[test]
    fn updating_a_bag_does_not_move_it() {
        let mut touched = bag("a", 1);
        touched.updated_at = 99;
        let beans = [touched, bag("b", 2)];
        assert_eq!(ranked(&beans, None), ["a", "b"]);
    }

    #[test]
    fn archived_bags_stay_out_until_the_toggle() {
        let beans = [bag("live", 1), archived(bag("done", 2))];
        assert_eq!(ids(&beans, &query(None, false, "")), ["live"]);
        assert_eq!(ids(&beans, &query(None, true, "")), ["live", "done"]);
    }

    #[test]
    fn the_toggle_ranks_archived_bags_with_the_rest() {
        let beans = [archived(fav(bag("done", 1))), bag("live", 2)];
        assert_eq!(ids(&beans, &query(None, true, "")), ["done", "live"]);
    }

    #[test]
    fn an_archived_current_bag_shows_with_the_toggle_off() {
        let beans = [
            bag("live", 1),
            archived(bag("done", 2)),
            archived(bag("other", 3)),
        ];
        assert_eq!(
            ids(&beans, &query(Some("done"), false, "")),
            ["done", "live"]
        );
    }

    #[test]
    fn tombstones_never_show() {
        let beans = [bag("live", 1), deleted(bag("gone", 2))];
        assert_eq!(ids(&beans, &query(None, true, "")), ["live"]);
        assert_eq!(ids(&beans, &query(Some("gone"), true, "")), ["live"]);
    }

    #[test]
    fn a_search_narrows_the_list() {
        let beans = [bag("Kenya", 1), bag("Ethiopia", 2)];
        assert_eq!(ids(&beans, &query(None, false, "ethiop")), ["Ethiopia"]);
    }

    #[test]
    fn a_search_matches_on_the_roastery() {
        let mut b = bag("Kenya", 1);
        b.roaster_id = Some("roaster:1".into());
        let r = Roaster::new("roaster:1".into(), "Tim Wendelboe".into(), 0);
        let found = bean_picker(&[b], &[r], &query(None, false, "wendelboe"));
        assert_eq!(found.ids, ["Kenya"]);
    }

    #[test]
    fn a_search_hides_archived_bags_with_the_toggle_off() {
        let beans = [archived(bag("Kenya", 1))];
        assert!(ids(&beans, &query(None, false, "kenya")).is_empty());
        assert_eq!(ids(&beans, &query(None, true, "kenya")), ["Kenya"]);
    }

    #[test]
    fn relevance_leads_over_the_rank_while_searching() {
        // "Kenya" matches the name of one and only the notes of the other,
        // and the weaker match is current and a favourite.
        let mut notes = fav(bag("House blend", 1));
        notes.notes = "Reminds me of a Kenya".into();
        let beans = [notes, bag("Kenya", 2)];
        let found = ids(&beans, &query(Some("House blend"), false, "kenya"));
        assert_eq!(found, ["Kenya", "House blend"]);
    }

    #[test]
    fn bags_that_tie_on_relevance_keep_their_rank() {
        let beans = [bag("Kenya", 2), fav(bag("Kenya ", 3)), bag("Kenya  ", 1)];
        let found = ids(&beans, &query(None, false, "kenya"));
        assert_eq!(found, ["Kenya ", "Kenya  ", "Kenya"]);
    }

    #[test]
    fn a_blank_search_is_no_search() {
        let beans = [bag("a", 1), bag("b", 2)];
        assert_eq!(ids(&beans, &query(None, false, "   ")), ["a", "b"]);
    }

    #[test]
    fn a_search_matching_nothing_lists_nothing() {
        let beans = [bag("Kenya", 1)];
        assert!(ids(&beans, &query(None, false, "zzzzzz")).is_empty());
    }

    #[test]
    fn has_archived_counts_bags_the_toggle_could_add() {
        let has = |beans: &[Bean], current| {
            bean_picker(beans, &[], &query(current, false, "")).has_archived
        };
        assert!(!has(&[bag("a", 1)], None));
        assert!(has(&[archived(bag("a", 1))], None));
        // The current bag is already listed; a tombstone can't be added.
        assert!(!has(&[archived(bag("a", 1))], Some("a")));
        assert!(!has(&[deleted(archived(bag("a", 1)))], None));
    }

    #[test]
    fn has_archived_ignores_the_search() {
        let beans = [bag("Kenya", 1), archived(bag("Ethiopia", 2))];
        let found = bean_picker(&beans, &[], &query(None, false, "kenya"));
        assert!(found.has_archived);
    }

    #[test]
    fn the_bridge_speaks_camel_case() {
        let beans = serde_json::to_string(&[bag("a", 1), archived(bag("b", 2))]).unwrap();
        let out = bean_picker_json(&beans, "[]", r#"{"currentId":"b"}"#).unwrap();
        assert_eq!(out, r#"{"ids":["b","a"],"hasArchived":false}"#);
    }

    #[test]
    fn the_bridge_defaults_a_missing_query_field() {
        let beans = serde_json::to_string(&[archived(bag("a", 1))]).unwrap();
        let out = bean_picker_json(&beans, "[]", "{}").unwrap();
        assert_eq!(out, r#"{"ids":[],"hasArchived":true}"#);
    }

    #[test]
    fn the_bridge_rejects_bad_json() {
        assert!(bean_picker_json("nope", "[]", "{}").is_err());
        assert!(bean_picker_json("[]", "nope", "{}").is_err());
        assert!(bean_picker_json("[]", "[]", "nope").is_err());
    }
}

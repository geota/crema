//! Bean-library facet filter + chip counts — the one implementation every
//! shell calls (geota/crema#124).
//!
//! The library's filter rail used to be hand-rolled per shell, and the shells
//! had drifted:
//!
//! - **Android** (phone and tablet) kept a *single* facet: picking the
//!   Archived chip deselected the roast chips and vice versa, and every roast
//!   chip silently excluded archived bags — so archived bags could only ever be
//!   sorted, never filtered ("Archived beans unable to be filtered", #124).
//! - **Web** composed status, roast and tags, but its chip counts ignored the
//!   other selections, and its roast bands (≤4 / 5–7 / ≥8) disagreed with the
//!   canonical [`roast_band`] (1–3 / 4–6 / 7–10) Android used, so a level-4 bag
//!   was "Light" on one and "Medium" on the other.
//!
//! [`filter_beans`] makes every facet an independent axis that composes with
//! the others — status, roast band, tags, roaster scope and the search query —
//! and treats "archived" as one more status rather than a separate mode:
//!
//! - **Status** ([`BeanStatusFilter`]) picks a lifecycle subset: everything,
//!   active, frozen, favourites, or archived.
//! - **Include archived** widens the "everything" and "favourites" subsets to
//!   archived bags too (shown dimmed by the shells). Off by default, so the
//!   working list looks exactly as it always did until the user opts in. A
//!   roaster scope (#86 — a roaster's shelf) always includes them.
//! - **Roast**, **tags** (all must match), the **roaster scope** and the
//!   **search** hits narrow whichever status is selected.
//!
//! The counts are classic faceted counts: each chip shows how many bags the
//! list would hold if that chip were picked, given every *other* selection —
//! so the numbers always agree with what tapping the chip produces.
//!
//! Sorting stays in the shells: each shell offers its own sort keys and pins
//! the loaded bag / favourites itself, and the core returns ids in input order
//! so that order is untouched. Search ranking stays in [`crate::bean_search`];
//! the caller passes the matched ids in (it already holds the hits for
//! highlighting and relevance order), so a keystroke never searches twice.

use std::collections::{HashMap, HashSet};

use serde::{Deserialize, Serialize};
use typeshare::typeshare;

use crate::bean::{Bean, RoastBand, roast_band};

/// The lifecycle subset a library list shows — the Status chip group.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BeanStatusFilter {
    /// Every bag (archived ones only with `include_archived`).
    #[default]
    All,
    /// In use: not frozen, not archived.
    Active,
    /// In the freezer (frozen, not defrosted) and not archived.
    Frozen,
    /// Favourited bags (archived ones only with `include_archived`).
    Favourite,
    /// Archived bags only — composes with every other facet like any status.
    Archived,
}

/// The selections on the filter rail. Every field is an independent axis;
/// the result is the bags that pass all of them.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct BeanFilterQuery {
    /// The Status chip.
    pub status: BeanStatusFilter,
    /// Also show archived bags under "All" / "Favourite" (dimmed). Ignored
    /// for "Archived" (already archived-only) and implied by `roaster_id`.
    pub include_archived: bool,
    /// Roast band — `"light"` / `"medium"` / `"dark"` (canonical
    /// [`roast_band`] thresholds), or `None` for no roast filter. A bag with
    /// no roast level matches no band.
    pub roast: Option<String>,
    /// Tags the bag must carry — all of them.
    pub tags: Vec<String>,
    /// Roaster scope (#86): only this roaster's bags, archived included.
    pub roaster_id: Option<String>,
    /// Ids the search matched, or `None` when no query is running. An empty
    /// list means a query that matched nothing.
    pub match_ids: Option<Vec<String>>,
}

/// Per-status chip counts, each given the other selections.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BeanStatusCounts {
    pub all: u32,
    pub active: u32,
    pub frozen: u32,
    pub favourite: u32,
    pub archived: u32,
}

/// Per-band roast chip counts, each given the other selections.
#[typeshare]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BeanRoastCounts {
    pub light: u32,
    pub medium: u32,
    pub dark: u32,
}

/// One tag chip: the tag and how many bags the list would hold with it added.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BeanTagCount {
    pub tag: String,
    pub count: u32,
}

/// The filtered library plus every chip's count.
#[typeshare]
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BeanFilterResult {
    /// The bags that pass every facet, in input order (the shell sorts).
    pub ids: Vec<String>,
    pub status_counts: BeanStatusCounts,
    pub roast_counts: BeanRoastCounts,
    /// Every tag in the (scoped) library, most-used first then by name — a
    /// stable order so chips don't jump while filtering — each with its count
    /// given the other selections. Selected tags are always listed.
    pub tag_counts: Vec<BeanTagCount>,
    /// Archived bags that pass every other facet but are hidden because
    /// "include archived" is off — the count for that toggle. 0 when the
    /// toggle has no effect (archived already shown, or an Active / Frozen
    /// status that never includes them).
    pub archived_hidden: u32,
    /// Whether archived bags are currently part of the list: "Archived"
    /// status, "include archived", or a roaster scope.
    pub showing_archived: bool,
}

fn present(s: &Option<String>) -> bool {
    s.as_deref().is_some_and(|v| !v.trim().is_empty())
}

fn is_archived(b: &Bean) -> bool {
    b.archived_at.is_some()
}

/// Frozen right now: a freeze date and no defrost after it (web `isFrozen`,
/// Android `Bean.isFrozen`). `frozen_on` alone is freeze history.
fn is_frozen(b: &Bean) -> bool {
    present(&b.frozen_on) && !present(&b.defrosted_on)
}

fn band_of(b: &Bean) -> Option<RoastBand> {
    b.roast_level.map(|l| roast_band(i32::from(l)))
}

/// Whether `b` belongs to `status`. `include_archived` widens All / Favourite.
fn matches_status(b: &Bean, status: BeanStatusFilter, include_archived: bool) -> bool {
    let archived = is_archived(b);
    match status {
        BeanStatusFilter::All => include_archived || !archived,
        BeanStatusFilter::Active => !archived && !is_frozen(b),
        BeanStatusFilter::Frozen => !archived && is_frozen(b),
        BeanStatusFilter::Favourite => b.favourite && (include_archived || !archived),
        BeanStatusFilter::Archived => archived,
    }
}

/// Filter `beans` by `query` and count every chip. Tombstoned bags
/// (`deleted_at`) never appear. Unknown roast spellings filter nothing out.
pub fn filter_beans(beans: &[Bean], query: &BeanFilterQuery) -> BeanFilterResult {
    let scoped: Vec<&Bean> = beans
        .iter()
        .filter(|b| b.deleted_at.is_none())
        .filter(|b| match &query.roaster_id {
            Some(id) => b.roaster_id.as_deref() == Some(id.as_str()),
            None => true,
        })
        .collect();
    let include = query.include_archived || query.roaster_id.is_some();
    let roast = query.roast.as_deref().and_then(RoastBand::from_wire_str);
    let matched: Option<HashSet<&str>> = query
        .match_ids
        .as_ref()
        .map(|ids| ids.iter().map(String::as_str).collect());

    let search_ok = |b: &Bean| matched.as_ref().is_none_or(|m| m.contains(b.id.as_str()));
    let roast_ok = |b: &Bean| roast.is_none_or(|r| band_of(b) == Some(r));
    let tags_ok = |b: &Bean, skip: Option<&str>| {
        query
            .tags
            .iter()
            .filter(|t| Some(t.as_str()) != skip)
            .all(|t| b.tags.iter().any(|bt| bt == t))
    };
    let status_ok = |b: &Bean| matches_status(b, query.status, include);

    let mut result = BeanFilterResult {
        showing_archived: include || query.status == BeanStatusFilter::Archived,
        ..BeanFilterResult::default()
    };

    // Library-wide tag frequency, for a stable chip order.
    let mut tag_freq: HashMap<&str, u32> = HashMap::new();
    for b in &scoped {
        for t in &b.tags {
            *tag_freq.entry(t.as_str()).or_default() += 1;
        }
    }
    for t in &query.tags {
        tag_freq.entry(t.as_str()).or_default();
    }
    let mut tag_order: Vec<(&str, u32)> = tag_freq.into_iter().collect();
    tag_order.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.cmp(b.0)));
    let mut tag_counts: HashMap<&str, u32> = HashMap::new();

    for &b in &scoped {
        let common = search_ok(b);
        if !common {
            continue;
        }
        let r_ok = roast_ok(b);
        let t_ok = tags_ok(b, None);
        let s_ok = status_ok(b);

        // Status chips: given roast + tags + search + scope.
        if r_ok && t_ok {
            let c = &mut result.status_counts;
            let archived = is_archived(b);
            if matches_status(b, BeanStatusFilter::All, include) {
                c.all += 1;
            }
            if matches_status(b, BeanStatusFilter::Active, include) {
                c.active += 1;
            }
            if matches_status(b, BeanStatusFilter::Frozen, include) {
                c.frozen += 1;
            }
            if matches_status(b, BeanStatusFilter::Favourite, include) {
                c.favourite += 1;
            }
            if archived {
                c.archived += 1;
            }
            // Hidden only by the include toggle.
            if archived && !s_ok && matches_status(b, query.status, true) {
                result.archived_hidden += 1;
            }
        }
        // Roast chips: given status + tags + search + scope.
        if s_ok && t_ok {
            match band_of(b) {
                Some(RoastBand::Light) => result.roast_counts.light += 1,
                Some(RoastBand::Medium) => result.roast_counts.medium += 1,
                Some(RoastBand::Dark) => result.roast_counts.dark += 1,
                None => {}
            }
        }
        // Tag chips: given status + roast + search + scope + the other tags.
        if s_ok && r_ok {
            for (tag, _) in &tag_order {
                if b.tags.iter().any(|bt| bt == tag) && tags_ok(b, Some(tag)) {
                    *tag_counts.entry(tag).or_default() += 1;
                }
            }
        }
        if s_ok && r_ok && t_ok {
            result.ids.push(b.id.clone());
        }
    }

    result.tag_counts = tag_order
        .into_iter()
        .map(|(tag, _)| BeanTagCount {
            tag: tag.to_owned(),
            count: tag_counts.get(tag).copied().unwrap_or(0),
        })
        .collect();
    result
}

/// [`filter_beans`] over JSON: `beans_json` is a `Bean[]`, `query_json` a
/// [`BeanFilterQuery`]; the result is a [`BeanFilterResult`].
pub fn filter_beans_json(beans_json: &str, query_json: &str) -> Result<String, String> {
    let beans: Vec<Bean> = serde_json::from_str(beans_json).map_err(|e| e.to_string())?;
    let query: BeanFilterQuery = serde_json::from_str(query_json).map_err(|e| e.to_string())?;
    serde_json::to_string(&filter_beans(&beans, &query)).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A bag with the given id, roast level and lifecycle flags.
    fn bag(id: &str, level: Option<u8>) -> Bean {
        let mut b = Bean::new(id.into(), id.into(), 0);
        b.roast_level = level;
        b
    }
    fn archived(mut b: Bean) -> Bean {
        b.archived_at = Some(1);
        b
    }
    fn frozen(mut b: Bean) -> Bean {
        b.frozen_on = Some("2026-01-01".into());
        b
    }
    fn fav(mut b: Bean) -> Bean {
        b.favourite = true;
        b
    }
    fn tagged(mut b: Bean, tags: &[&str]) -> Bean {
        b.tags = tags.iter().map(|t| (*t).to_owned()).collect();
        b
    }
    fn roaster(mut b: Bean, id: &str) -> Bean {
        b.roaster_id = Some(id.into());
        b
    }

    /// Light (2), medium (5), dark (8) active bags + the same three archived,
    /// plus a frozen medium and an archived favourite light.
    fn library() -> Vec<Bean> {
        vec![
            bag("a-light", Some(2)),
            bag("a-medium", Some(5)),
            bag("a-dark", Some(8)),
            archived(bag("x-light", Some(2))),
            archived(bag("x-medium", Some(5))),
            archived(bag("x-dark", Some(8))),
            frozen(bag("f-medium", Some(5))),
            fav(archived(bag("x-fav-light", Some(3)))),
        ]
    }

    fn q() -> BeanFilterQuery {
        BeanFilterQuery::default()
    }

    fn ids(r: &BeanFilterResult) -> Vec<&str> {
        r.ids.iter().map(String::as_str).collect()
    }

    #[test]
    fn the_default_query_hides_archived_bags_as_before() {
        let r = filter_beans(&library(), &q());
        assert_eq!(ids(&r), ["a-light", "a-medium", "a-dark", "f-medium"]);
        assert!(!r.showing_archived);
        assert_eq!(r.archived_hidden, 4);
    }

    #[test]
    fn archived_composes_with_roast_band() {
        // #124: Archive → Light must narrow to archived light bags.
        let query = BeanFilterQuery {
            status: BeanStatusFilter::Archived,
            roast: Some("light".into()),
            ..q()
        };
        let r = filter_beans(&library(), &query);
        assert_eq!(ids(&r), ["x-light", "x-fav-light"]);
        // Roast chips count archived bags while Archived is selected.
        assert_eq!(
            r.roast_counts,
            BeanRoastCounts {
                light: 2,
                medium: 1,
                dark: 1
            }
        );
        // Status chips count within the Light band.
        assert_eq!(r.status_counts.archived, 2);
        assert_eq!(r.status_counts.all, 1);
        assert_eq!(r.status_counts.active, 1);
    }

    #[test]
    fn archived_composes_with_search() {
        let query = BeanFilterQuery {
            status: BeanStatusFilter::Archived,
            match_ids: Some(vec!["x-dark".into(), "a-dark".into()]),
            ..q()
        };
        let r = filter_beans(&library(), &query);
        assert_eq!(ids(&r), ["x-dark"]);
        assert_eq!(r.status_counts.archived, 1);
        assert_eq!(r.status_counts.all, 1);
        assert_eq!(
            r.roast_counts,
            BeanRoastCounts {
                light: 0,
                medium: 0,
                dark: 1
            }
        );
    }

    #[test]
    fn a_search_that_matched_nothing_empties_the_list() {
        let query = BeanFilterQuery {
            match_ids: Some(vec![]),
            ..q()
        };
        let r = filter_beans(&library(), &query);
        assert!(r.ids.is_empty());
        assert_eq!(r.status_counts, BeanStatusCounts::default());
    }

    #[test]
    fn include_archived_widens_all_and_counts_follow() {
        let query = BeanFilterQuery {
            include_archived: true,
            ..q()
        };
        let r = filter_beans(&library(), &query);
        assert_eq!(r.ids.len(), 8);
        assert!(r.showing_archived);
        assert_eq!(r.archived_hidden, 0);
        assert_eq!(r.status_counts.all, 8);
        assert_eq!(r.status_counts.favourite, 1);
        // Active / Frozen never include archived bags.
        assert_eq!(r.status_counts.active, 3);
        assert_eq!(r.status_counts.frozen, 1);
        assert_eq!(
            r.roast_counts,
            BeanRoastCounts {
                light: 3,
                medium: 3,
                dark: 2
            }
        );
    }

    #[test]
    fn include_archived_composes_with_roast() {
        let query = BeanFilterQuery {
            include_archived: true,
            roast: Some("medium".into()),
            ..q()
        };
        let r = filter_beans(&library(), &query);
        assert_eq!(ids(&r), ["a-medium", "x-medium", "f-medium"]);
        assert_eq!(r.status_counts.all, 3);
        assert_eq!(r.status_counts.archived, 1);
    }

    #[test]
    fn archived_hidden_counts_only_what_the_toggle_would_add() {
        let query = BeanFilterQuery {
            roast: Some("light".into()),
            ..q()
        };
        let r = filter_beans(&library(), &query);
        assert_eq!(ids(&r), ["a-light"]);
        assert_eq!(r.archived_hidden, 2);
        // Favourite: only the archived favourite would be added.
        let fav_q = BeanFilterQuery {
            status: BeanStatusFilter::Favourite,
            ..q()
        };
        assert_eq!(filter_beans(&library(), &fav_q).archived_hidden, 1);
        // Active never includes archived, so the toggle adds nothing.
        let act_q = BeanFilterQuery {
            status: BeanStatusFilter::Active,
            ..q()
        };
        assert_eq!(filter_beans(&library(), &act_q).archived_hidden, 0);
    }

    #[test]
    fn roast_bands_use_the_canonical_thresholds() {
        // Level 4 is Medium (1–3 / 4–6 / 7–10), on every shell.
        let beans = vec![
            bag("four", Some(4)),
            bag("seven", Some(7)),
            bag("none", None),
        ];
        let r = filter_beans(&beans, &q());
        assert_eq!(
            r.roast_counts,
            BeanRoastCounts {
                light: 0,
                medium: 1,
                dark: 1
            }
        );
        let medium = BeanFilterQuery {
            roast: Some("medium".into()),
            ..q()
        };
        assert_eq!(ids(&filter_beans(&beans, &medium)), ["four"]);
    }

    #[test]
    fn tags_all_must_match_and_compose_with_archived() {
        let beans = vec![
            tagged(bag("a", Some(2)), &["washed", "gesha"]),
            tagged(archived(bag("b", Some(2))), &["washed"]),
            tagged(archived(bag("c", Some(2))), &["washed", "gesha"]),
        ];
        let query = BeanFilterQuery {
            status: BeanStatusFilter::Archived,
            tags: vec!["washed".into()],
            ..q()
        };
        let r = filter_beans(&beans, &query);
        assert_eq!(ids(&r), ["b", "c"]);
        // Most-used first, then name; each counted given the other filters.
        assert_eq!(
            r.tag_counts,
            vec![
                BeanTagCount {
                    tag: "washed".into(),
                    count: 2
                },
                BeanTagCount {
                    tag: "gesha".into(),
                    count: 1
                },
            ]
        );
        let both = BeanFilterQuery {
            tags: vec!["washed".into(), "gesha".into()],
            include_archived: true,
            ..q()
        };
        assert_eq!(ids(&filter_beans(&beans, &both)), ["a", "c"]);
    }

    #[test]
    fn a_selected_tag_missing_from_the_library_still_has_a_chip() {
        let query = BeanFilterQuery {
            tags: vec!["gone".into()],
            ..q()
        };
        let r = filter_beans(&library(), &query);
        assert!(r.ids.is_empty());
        assert_eq!(
            r.tag_counts,
            vec![BeanTagCount {
                tag: "gone".into(),
                count: 0
            }]
        );
    }

    #[test]
    fn a_roaster_scope_shows_its_archived_bags_and_still_filters() {
        let beans = vec![
            roaster(bag("r1-live", Some(2)), "r1"),
            roaster(archived(bag("r1-old", Some(8))), "r1"),
            roaster(bag("r2-live", Some(2)), "r2"),
        ];
        let scope = BeanFilterQuery {
            roaster_id: Some("r1".into()),
            ..q()
        };
        let r = filter_beans(&beans, &scope);
        assert_eq!(ids(&r), ["r1-live", "r1-old"]);
        assert!(r.showing_archived);
        assert_eq!(r.status_counts.all, 2);
        let dark = BeanFilterQuery {
            roast: Some("dark".into()),
            ..scope
        };
        assert_eq!(ids(&filter_beans(&beans, &dark)), ["r1-old"]);
    }

    #[test]
    fn defrosted_bags_are_active_and_tombstones_never_show() {
        let mut thawed = frozen(bag("thawed", Some(5)));
        thawed.defrosted_on = Some("2026-02-01".into());
        let mut gone = bag("gone", Some(5));
        gone.deleted_at = Some(1);
        let beans = vec![thawed, gone];
        let r = filter_beans(&beans, &q());
        assert_eq!(ids(&r), ["thawed"]);
        assert_eq!(r.status_counts.active, 1);
        assert_eq!(r.status_counts.frozen, 0);
    }

    #[test]
    fn json_bridge_round_trips_and_defaults_missing_fields() {
        let beans = serde_json::to_string(&library()).unwrap();
        let out = filter_beans_json(&beans, r#"{"status":"archived","roast":"dark"}"#).unwrap();
        let r: BeanFilterResult = serde_json::from_str(&out).unwrap();
        assert_eq!(ids(&r), ["x-dark"]);
        assert!(out.contains("\"statusCounts\""));
        assert!(out.contains("\"archivedHidden\""));
        assert!(filter_beans_json("nope", "{}").is_err());
        assert!(filter_beans_json("[]", "{\"status\":\"nope\"}").is_err());
    }
}

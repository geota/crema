//! Change kinds (SPEC §10): what a write changed, worked out once in the core
//! from the old and new derived columns, so each shell's list refetches only
//! what the change can move.

use serde::{Deserialize, Serialize};

use crate::derive::Derived;
use crate::query::{HistoryQuery, SortKey};

/// What a write changed. A star tap is `stats | order(Rating) | display`; a
/// notes edit is `text | display`; a new shot is everything.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ChangeKinds {
    /// Rows were added, removed or tombstoned: every count can move.
    pub set: bool,
    /// The sort keys whose values moved, as [`SortKey::bit`]s. A moved
    /// `completed_at` sets every key, since it breaks every order's ties.
    pub order: u8,
    /// A column a filter or a facet reads moved: profile, method, bean,
    /// coffee, tags, pinned, or the date a range cuts on.
    pub facet: bool,
    /// A stat input or the stats family moved.
    pub stats: bool,
    /// The search text moved.
    pub text: bool,
    /// Something a row shows moved: its `row_json`.
    pub display: bool,
}

impl ChangeKinds {
    /// Everything: a row added or removed.
    pub const ALL: ChangeKinds = ChangeKinds {
        set: true,
        order: 0x3f,
        facet: true,
        stats: true,
        text: true,
        display: true,
    };

    /// What changed from `old` to `new` for one row. `None` is "no row", or a
    /// row lists don't show. `row_json_changed` says whether the row's text
    /// moved, which decides [`ChangeKinds::display`].
    #[must_use]
    pub fn between(
        old: Option<&Derived>,
        new: Option<&Derived>,
        row_json_changed: bool,
    ) -> ChangeKinds {
        match (old.filter(|d| d.is_live()), new.filter(|d| d.is_live())) {
            (None, None) => ChangeKinds::default(),
            (Some(_), None) | (None, Some(_)) => ChangeKinds::ALL,
            (Some(a), Some(b)) => {
                let mut order = 0;
                if a.completed_at != b.completed_at {
                    order = ChangeKinds::ALL.order;
                }
                for key in SortKey::ALL {
                    if a.sort_value(key) != b.sort_value(key) {
                        order |= key.bit();
                    }
                }
                ChangeKinds {
                    set: false,
                    order,
                    facet: a.profile_name != b.profile_name
                        || a.method != b.method
                        || a.bean_id != b.bean_id
                        || a.coffee_key != b.coffee_key
                        || a.tags != b.tags
                        || a.pinned != b.pinned
                        || a.completed_at != b.completed_at,
                    stats: a.family != b.family
                        || a.st_dose != b.st_dose
                        || a.st_yield != b.st_yield
                        || a.st_ratio != b.st_ratio
                        || a.st_time_s != b.st_time_s
                        || a.st_rating != b.st_rating,
                    text: a.search_text != b.search_text,
                    display: row_json_changed,
                }
            }
        }
    }

    /// Both changes at once.
    #[must_use]
    pub fn union(self, other: ChangeKinds) -> ChangeKinds {
        ChangeKinds {
            set: self.set || other.set,
            order: self.order | other.order,
            facet: self.facet || other.facet,
            stats: self.stats || other.stats,
            text: self.text || other.text,
            display: self.display || other.display,
        }
    }

    /// Whether the change can move `q`'s rows or their order, so a window
    /// must refetch its count and pages. Otherwise it refetches only the
    /// changed rows it holds.
    #[must_use]
    pub fn moves_list(&self, q: &HistoryQuery) -> bool {
        self.set
            || self.order & q.sort.key.bit() != 0
            || (self.facet && q.is_filtered())
            || (self.text && q.needle().is_some())
    }

    /// Whether `q`'s stats can move: a stat input moved, or the set of rows
    /// `q` lists did. An order change alone moves no stat.
    #[must_use]
    pub fn moves_stats(&self, q: &HistoryQuery) -> bool {
        self.stats
            || self.set
            || (self.facet && q.is_filtered())
            || (self.text && q.needle().is_some())
    }
}

/// What one write transaction changed (SPEC §10): the notice every write
/// returns, and the shells broadcast. `seq` is the `write_seq` it committed,
/// so a window orders notices against pages and sees a gap (S14). A write
/// that changed nothing carries the current `write_seq` and empty lists.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ChangeSet {
    /// The write's `write_seq`.
    pub seq: u64,
    /// Rows added or changed, in the order the write took them.
    pub upserted: Vec<String>,
    /// Rows removed.
    pub deleted: Vec<String>,
    /// What lists and stats can see of the change.
    pub kinds: ChangeKinds,
}

impl ChangeSet {
    /// Whether the write changed anything.
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.upserted.is_empty() && self.deleted.is_empty() && self.kinds == ChangeKinds::default()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::derive::derive_value;
    use crate::query::{Dir, Sort};
    use serde_json::json;

    fn d(v: serde_json::Value) -> Derived {
        derive_value(&v).expect("derives")
    }

    fn base() -> serde_json::Value {
        json!({
            "id": "a", "completedAt": 10, "profileName": "Turbo",
            "metadata": { "dose": 18.0, "yieldOut": 36.0, "rating": 3, "notes": "ok" },
            "record": { "duration": 30_000 }, "tags": ["x"]
        })
    }

    fn with(f: impl FnOnce(&mut serde_json::Value)) -> Derived {
        let mut v = base();
        f(&mut v);
        d(v)
    }

    #[test]
    fn a_star_tap_is_stats_order_by_rating_and_display() {
        let k = ChangeKinds::between(
            Some(&d(base())),
            Some(&with(|v| v["metadata"]["rating"] = json!(5))),
            true,
        );
        assert_eq!(
            k,
            ChangeKinds {
                stats: true,
                order: SortKey::Rating.bit(),
                display: true,
                ..ChangeKinds::default()
            }
        );
        let by_date = HistoryQuery::default();
        let by_rating = HistoryQuery {
            sort: Sort {
                key: SortKey::Rating,
                dir: Dir::Desc,
            },
            ..HistoryQuery::default()
        };
        assert!(!k.moves_list(&by_date));
        assert!(k.moves_list(&by_rating));
        assert!(k.moves_stats(&by_date));
    }

    #[test]
    fn a_notes_edit_is_text_and_display() {
        let k = ChangeKinds::between(
            Some(&d(base())),
            Some(&with(|v| v["metadata"]["notes"] = json!("sour"))),
            true,
        );
        assert_eq!(
            k,
            ChangeKinds {
                text: true,
                display: true,
                ..ChangeKinds::default()
            }
        );
        assert!(!k.moves_list(&HistoryQuery::default()));
        let searching = HistoryQuery {
            text: "sour".into(),
            ..HistoryQuery::default()
        };
        assert!(k.moves_list(&searching));
        // Under three characters it is no search.
        let short = HistoryQuery {
            text: "so".into(),
            ..HistoryQuery::default()
        };
        assert!(!k.moves_list(&short));
        assert!(!k.moves_stats(&HistoryQuery::default()));
    }

    #[test]
    fn a_new_row_or_a_tombstone_is_everything() {
        let row = d(base());
        assert_eq!(
            ChangeKinds::between(None, Some(&row), true),
            ChangeKinds::ALL
        );
        let tomb = with(|v| v["deletedAt"] = json!(99));
        assert_eq!(
            ChangeKinds::between(Some(&row), Some(&tomb), true),
            ChangeKinds::ALL
        );
        assert_eq!(
            ChangeKinds::between(Some(&tomb), None, false),
            ChangeKinds::default()
        );
    }

    #[test]
    fn a_moved_date_moves_every_order_and_the_range() {
        let k = ChangeKinds::between(
            Some(&d(base())),
            Some(&with(|v| v["completedAt"] = json!(11))),
            true,
        );
        assert_eq!(k.order, ChangeKinds::ALL.order);
        assert!(k.facet);
    }

    #[test]
    fn a_retag_is_a_facet_and_text_change() {
        let k = ChangeKinds::between(
            Some(&d(base())),
            Some(&with(|v| v["tags"] = json!(["y"]))),
            true,
        );
        assert!(k.facet && k.text && !k.stats && !k.set);
        assert!(!k.moves_list(&HistoryQuery::default()));
        let tagged = HistoryQuery {
            tags_all: vec!["x".into()],
            ..HistoryQuery::default()
        };
        assert!(k.moves_list(&tagged));
    }

    #[test]
    fn an_unchanged_row_is_nothing() {
        let k = ChangeKinds::between(Some(&d(base())), Some(&d(base())), false);
        assert_eq!(k, ChangeKinds::default());
        assert_eq!(k.union(ChangeKinds::ALL), ChangeKinds::ALL);
    }
}

//! Typed queries (SPEC §6, §7): what a shell asks for. Shells never write SQL
//! (rule 5); the core turns these into statements ([`crate::sql`]) and into the
//! reference rules ([`crate::reference`]).

use serde::{Deserialize, Serialize};

use crate::fold::search_needle;

/// A sort key. The core offers all six on both shells (owner question 2).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum SortKey {
    /// `completed_at`.
    #[default]
    Date,
    /// `sort_rating`: the rating, 0 when unrated.
    Rating,
    /// `sort_profile`: the folded profile name, `""` when none.
    Profile,
    /// `sort_bean`: the folded bean label the row shows, "Roaster · Name".
    Bean,
    /// `sort_yield`: the yield, 0 when none.
    Yield,
    /// `sort_time`: the duration, ms.
    Time,
}

impl SortKey {
    /// Every key, in a fixed order.
    pub const ALL: [SortKey; 6] = [
        SortKey::Date,
        SortKey::Rating,
        SortKey::Profile,
        SortKey::Bean,
        SortKey::Yield,
        SortKey::Time,
    ];

    /// The key's bit in [`crate::change::ChangeKinds::order`].
    #[must_use]
    pub const fn bit(self) -> u8 {
        match self {
            SortKey::Date => 1,
            SortKey::Rating => 2,
            SortKey::Profile => 4,
            SortKey::Bean => 8,
            SortKey::Yield => 16,
            SortKey::Time => 32,
        }
    }
}

/// A sort direction.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub enum Dir {
    /// Smallest first.
    Asc,
    /// Largest first.
    #[default]
    Desc,
}

/// The list's order: `key` in `dir`, then `completed_at` descending, then `id`
/// descending. Ties are always newest first (SPEC §6).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Sort {
    /// What to sort by.
    pub key: SortKey,
    /// Which way.
    pub dir: Dir,
}

/// The bean filter: one bag, or every bag of one coffee.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum BeanScope {
    /// The snapshot's `beanId`.
    Bag(String),
    /// A coffee key ([`crate::derive::coffee_key`]), through the live library.
    Coffee(String),
}

/// What the History list shows: filters, a search and a sort (SPEC §6).
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct HistoryQuery {
    /// The typed search. The core folds and trims it; under three folded
    /// characters it is no search ([`HistoryQuery::needle`]).
    pub text: String,
    /// Rows before this `completed_at` are out. The shell works out local
    /// midnight and the rolling windows; the core has no time zone.
    pub since_ms: Option<i64>,
    /// Exactly this profile name.
    pub profile: Option<String>,
    /// One bag, or one coffee.
    pub bean: Option<BeanScope>,
    /// A normalized method; `"espresso"` covers machine shots.
    pub method: Option<String>,
    /// Every one must be among the row's effective tags: its own, or its live
    /// bean's when it has none.
    pub tags_all: Vec<String>,
    /// Pinned rows only.
    pub pinned_only: bool,
    /// The order.
    pub sort: Sort,
}

/// The parts of a query that decide its rows and their order, in a fixed
/// form: what [`HistoryQuery::hash`] covers.
#[derive(Serialize)]
struct Canonical<'a> {
    needle: Option<String>,
    since_ms: Option<i64>,
    profile: Option<&'a str>,
    bean: Option<&'a BeanScope>,
    method: Option<&'a str>,
    tags_all: Vec<&'a str>,
    pinned_only: bool,
    sort: Sort,
}

impl HistoryQuery {
    /// The folded search, or `None` for no search.
    #[must_use]
    pub fn needle(&self) -> Option<String> {
        search_needle(&self.text)
    }

    /// The tags to require, sorted and without repeats: AND doesn't care
    /// about order or repeats.
    #[must_use]
    pub fn tags(&self) -> Vec<&str> {
        let mut tags: Vec<&str> = self.tags_all.iter().map(String::as_str).collect();
        tags.sort_unstable();
        tags.dedup();
        tags
    }

    /// Whether any filter or search narrows the rows.
    #[must_use]
    pub fn is_filtered(&self) -> bool {
        self.needle().is_some()
            || self.since_ms.is_some()
            || self.profile.is_some()
            || self.bean.is_some()
            || self.method.is_some()
            || !self.tags_all.is_empty()
            || self.pinned_only
    }

    /// A stable hash of what decides the rows and their order, for cursors:
    /// two queries that list the same rows in the same order hash the same,
    /// in every build and on both shells (FNV-1a over a canonical JSON form).
    #[must_use]
    pub fn hash(&self) -> u64 {
        let canonical = Canonical {
            needle: self.needle(),
            since_ms: self.since_ms,
            profile: self.profile.as_deref(),
            bean: self.bean.as_ref(),
            method: self.method.as_deref(),
            tags_all: self.tags(),
            pinned_only: self.pinned_only,
            sort: self.sort,
        };
        // Infallible: plain strings, integers and enums always serialize.
        let bytes = serde_json::to_vec(&canonical).unwrap_or_default();
        fnv1a(&bytes)
    }
}

/// FNV-1a, 64-bit.
fn fnv1a(bytes: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for b in bytes {
        h ^= u64::from(*b);
        h = h.wrapping_mul(0x0100_0000_01b3);
    }
    h
}

/// A row's value for the sort key: what a cursor carries besides
/// `completed_at` and `id`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SortValue {
    /// The date sort: the key is `completed_at` itself.
    Date,
    /// `sort_rating` or `sort_time`.
    Int(i64),
    /// `sort_yield`; always finite.
    Real(f64),
    /// `sort_profile` or `sort_bean`.
    Text(String),
}

/// Where a page starts (SPEC §7).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Anchor {
    /// The first page.
    Start,
    /// The rows after this one, in the query's order.
    After(crate::cursor::Cursor),
    /// The rows before this one, in the query's order.
    Before(crate::cursor::Cursor),
    /// A jump: skip this many rows.
    Offset(u32),
    /// The page that holds a row: `before` rows ahead of it, then it and the
    /// rows after.
    #[serde(rename_all = "camelCase")]
    Around {
        /// The row.
        id: String,
        /// How many rows to show ahead of it.
        before: u32,
    },
}

/// A bag in the live bean library, as the shell resolves it: its current name
/// and its roaster's name (a merged duplicate roaster already resolved), and
/// its tags.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct LiveBean {
    /// The bag's id, as a shot's `bean.beanId` names it.
    pub bean_id: String,
    /// The bag's current coffee name.
    pub name: String,
    /// Its roaster's name, if any.
    pub roaster: Option<String>,
    /// The bag's tags.
    pub tags: Vec<String>,
}

/// A user-defined method's current label. Deleted methods stay listed, so
/// their rows keep their name.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct LiveMethod {
    /// The method id, `custom:<uuid>`.
    pub method: String,
    /// Its label.
    pub label: String,
}

/// The live libraries a query reads besides the rows: the coffee key, the
/// effective tags and a custom method's label (SPEC §5, §6).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Live {
    /// The bean library.
    pub beans: Vec<LiveBean>,
    /// The custom methods.
    pub methods: Vec<LiveMethod>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_hash_ignores_what_does_not_change_the_rows() {
        let a = HistoryQuery {
            text: "Café".into(),
            tags_all: vec!["b".into(), "a".into(), "a".into()],
            ..HistoryQuery::default()
        };
        let b = HistoryQuery {
            text: "  cafe ".into(),
            tags_all: vec!["a".into(), "b".into()],
            ..HistoryQuery::default()
        };
        assert_eq!(a.hash(), b.hash());
        // A short search is no search.
        let short = HistoryQuery {
            text: "ca".into(),
            ..HistoryQuery::default()
        };
        assert_eq!(short.hash(), HistoryQuery::default().hash());
    }

    #[test]
    fn the_hash_moves_with_the_rows_or_the_order() {
        let base = HistoryQuery::default();
        let variants = [
            HistoryQuery {
                text: "sour".into(),
                ..HistoryQuery::default()
            },
            HistoryQuery {
                since_ms: Some(1),
                ..HistoryQuery::default()
            },
            HistoryQuery {
                profile: Some("Turbo".into()),
                ..HistoryQuery::default()
            },
            HistoryQuery {
                bean: Some(BeanScope::Bag("b".into())),
                ..HistoryQuery::default()
            },
            HistoryQuery {
                bean: Some(BeanScope::Coffee("b".into())),
                ..HistoryQuery::default()
            },
            HistoryQuery {
                method: Some("espresso".into()),
                ..HistoryQuery::default()
            },
            HistoryQuery {
                tags_all: vec!["x".into()],
                ..HistoryQuery::default()
            },
            HistoryQuery {
                pinned_only: true,
                ..HistoryQuery::default()
            },
            HistoryQuery {
                sort: Sort {
                    key: SortKey::Date,
                    dir: Dir::Asc,
                },
                ..HistoryQuery::default()
            },
            HistoryQuery {
                sort: Sort {
                    key: SortKey::Rating,
                    dir: Dir::Desc,
                },
                ..HistoryQuery::default()
            },
        ];
        let mut hashes = vec![base.hash()];
        for v in &variants {
            hashes.push(v.hash());
        }
        let mut unique = hashes.clone();
        unique.sort_unstable();
        unique.dedup();
        assert_eq!(unique.len(), hashes.len());
    }

    #[test]
    fn the_hash_is_stable_across_builds() {
        // Cursors outlive a build: a change here breaks every saved cursor,
        // which is allowed only on purpose.
        assert_eq!(HistoryQuery::default().hash(), 0xe061_c0a6_0e35_3e18);
    }

    #[test]
    fn a_query_reads_from_camel_case_json_with_defaults() {
        let q: HistoryQuery = serde_json::from_str(
            r#"{"text":"sey","sinceMs":5,"bean":{"coffee":"sey|kenya"},"tagsAll":["x"],"sort":{"key":"rating"}}"#,
        )
        .expect("parses");
        assert_eq!(q.since_ms, Some(5));
        assert_eq!(q.bean, Some(BeanScope::Coffee("sey|kenya".into())));
        assert_eq!(
            q.sort,
            Sort {
                key: SortKey::Rating,
                dir: Dir::Desc
            }
        );
        assert!(!q.pinned_only);
    }
}

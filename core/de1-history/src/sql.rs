//! The SQL for every query in SPEC §7, built from a [`HistoryQuery`]. Builders
//! only: C2's store runs them, and its property tests hold them to
//! [`crate::reference`].
//!
//! Every list query filters with the same text, so the partial date index
//! applies: `s.deleted_at IS NULL AND s.hold_delete = 0`. Parameters are
//! numbered (`?1`, `?2`, …) so one value can appear twice. The main table is
//! `s`, and the live bag it joins is `lb`.

use crate::cursor::{Cursor, CursorError};
use crate::query::{BeanScope, Dir, HistoryQuery, Sort, SortKey};

/// A bound value.
#[derive(Debug, Clone, PartialEq)]
pub enum Param {
    /// An integer.
    Int(i64),
    /// A real.
    Real(f64),
    /// Text.
    Text(String),
}

/// A statement and its parameters, `params[0]` being `?1`.
#[derive(Debug, Clone, PartialEq)]
pub struct Stmt {
    /// The SQL text.
    pub sql: String,
    /// The values for `?1`, `?2`, ….
    pub params: Vec<Param>,
}

/// How a search runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TextPath {
    /// Through the trigram index, `GLOB`.
    Fts,
    /// `instr` over `search_text`, while the index is being rebuilt or after
    /// it was dropped for damage (M6).
    Scan,
}

/// Where a page starts, for [`page`]. [`crate::query::Anchor::Around`] is
/// [`row_cursor`] and [`index_of`], then a [`Seek::Before`] page and a
/// [`Seek::From`] page.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Seek<'a> {
    /// The first rows.
    Start,
    /// The rows after this one.
    After(&'a Cursor),
    /// The rows before this one, nearest first in the statement's order; the
    /// caller reverses them ([`PageStmt::reversed`]).
    Before(&'a Cursor),
    /// This row, if it is still in the query, and the rows after it.
    From(&'a Cursor),
    /// A jump: skip this many rows.
    Offset(u32),
}

/// A page statement. Its columns: `id`, `row_json`, the sort value (`NULL`
/// for the date sort) and `completed_at`, which make each row's cursor.
#[derive(Debug, Clone, PartialEq)]
pub struct PageStmt {
    /// The statement.
    pub stmt: Stmt,
    /// The rows come back in reverse order and must be reversed.
    pub reversed: bool,
}

/// The facet statements (SPEC §7): each runs the query's filter minus its own
/// dimension.
#[derive(Debug, Clone, PartialEq)]
pub struct FacetStmts {
    /// `profile_name, count`, most used first.
    pub profiles: Stmt,
    /// `method, count`, most used first.
    pub methods: Stmt,
    /// `tag, count` over the effective tags, most used first.
    pub tags: Stmt,
    /// `coffee_key, name, roaster, newest completed_at, count, bags`, most
    /// used first. Name and roaster are the live bag's, else the newest row's
    /// snapshot.
    pub coffees: Stmt,
    /// The pinned rows' count.
    pub pinned: Stmt,
}

/// Why a statement couldn't be built.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SqlError {
    /// The cursor belongs to another query.
    Cursor(CursorError),
}

impl std::fmt::Display for SqlError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SqlError::Cursor(e) => e.fmt(f),
        }
    }
}

impl std::error::Error for SqlError {}

/// The filter's dimensions, so a facet can leave its own out.
#[derive(Clone, Copy, Default)]
struct Skip {
    profile: bool,
    bean: bool,
    method: bool,
    tags: bool,
    pinned: bool,
}

/// Collects SQL text and numbered parameters.
#[derive(Default)]
struct Builder {
    params: Vec<Param>,
}

impl Builder {
    /// Binds `p` and returns its placeholder.
    fn bind(&mut self, p: Param) -> String {
        self.params.push(p);
        format!("?{}", self.params.len())
    }

    fn text(&mut self, s: &str) -> String {
        self.bind(Param::Text(s.to_owned()))
    }

    fn int(&mut self, i: i64) -> String {
        self.bind(Param::Int(i))
    }

    fn finish(self, sql: String) -> Stmt {
        Stmt {
            sql,
            params: self.params,
        }
    }
}

/// The sort column for `key`, on table `t`.
fn key_col(key: SortKey, t: &str) -> String {
    let col = match key {
        SortKey::Date => "completed_at",
        SortKey::Rating => "sort_rating",
        SortKey::Profile => "sort_profile",
        SortKey::Bean => "sort_bean",
        SortKey::Yield => "sort_yield",
        SortKey::Time => "sort_time",
    };
    format!("{t}.{col}")
}

/// The `FROM` clause: the shot table as `s`, and the live bag when the query
/// needs it.
fn from(needs_live_bean: bool) -> &'static str {
    if needs_live_bean {
        "FROM shot s LEFT JOIN live_bean lb ON lb.bean_id = s.bean_id"
    } else {
        "FROM shot s"
    }
}

/// The row's effective coffee key, on table `t` with live bag `lb`: the live
/// bag's when the bag is in the library, else the snapshot's (the shells'
/// `shotCoffee`).
fn coffee(t: &str, lb: &str) -> String {
    format!("(CASE WHEN {lb}.bean_id IS NOT NULL THEN {lb}.coffee_key ELSE {t}.coffee_key END)")
}

/// `pattern` with `GLOB`'s special characters made literal.
fn glob_escape(needle: &str) -> String {
    let mut out = String::with_capacity(needle.len() + 2);
    for c in needle.chars() {
        match c {
            '*' => out.push_str("[*]"),
            '?' => out.push_str("[?]"),
            '[' => out.push_str("[[]"),
            c => out.push(c),
        }
    }
    out
}

/// The query's `WHERE` terms on table `t` (live bag `lb`), joined by `AND`.
fn filter(
    b: &mut Builder,
    q: &HistoryQuery,
    t: &str,
    lb: &str,
    path: TextPath,
    skip: Skip,
) -> String {
    let mut terms = vec![format!("{t}.deleted_at IS NULL AND {t}.hold_delete = 0")];
    if let Some(since) = q.since_ms {
        let p = b.int(since);
        terms.push(format!("{t}.completed_at >= {p}"));
    }
    if let (Some(profile), false) = (&q.profile, skip.profile) {
        let p = b.text(profile);
        terms.push(format!("{t}.profile_name = {p}"));
    }
    if q.pinned_only && !skip.pinned {
        terms.push(format!("{t}.pinned = 1"));
    }
    match (&q.bean, skip.bean) {
        (Some(BeanScope::Bag(id)), false) => {
            let p = b.text(id);
            terms.push(format!("{t}.bean_id = {p}"));
        }
        (Some(BeanScope::Coffee(key)), false) => {
            let p = b.text(key);
            terms.push(format!("{} = {p}", coffee(t, lb)));
        }
        _ => {}
    }
    if let (Some(method), false) = (&q.method, skip.method) {
        let p = b.text(method);
        terms.push(format!("{t}.method = {p}"));
    }
    if !skip.tags {
        for tag in q.tags() {
            let p = b.text(tag);
            terms.push(format!(
                "(EXISTS (SELECT 1 FROM shot_tag st WHERE st.rid = {t}.rid AND st.tag = {p}) \
                 OR ({t}.has_tags = 0 AND EXISTS (SELECT 1 FROM live_bean_tag bt WHERE bt.bean_id = {t}.bean_id AND bt.tag = {p})))"
            ));
        }
    }
    if let Some(needle) = q.needle() {
        let (own, n) = match path {
            TextPath::Fts => {
                let p = b.text(&format!("*{}*", glob_escape(&needle)));
                let n = b.text(&needle);
                (
                    format!("{t}.rid IN (SELECT rowid FROM shot_fts WHERE search_text GLOB {p})"),
                    n,
                )
            }
            TextPath::Scan => {
                let n = b.text(&needle);
                (format!("instr({t}.search_text, {n}) > 0"), n)
            }
        };
        terms.push(format!(
            "({own} \
             OR {t}.method IN (SELECT method FROM live_method WHERE instr(label_folded, {n}) > 0) \
             OR ({t}.has_tags = 0 AND {t}.bean_id IN (SELECT bean_id FROM live_bean_tag WHERE instr(tag_folded, {n}) > 0)))"
        ));
    }
    terms.join(" AND ")
}

/// Whether the filter reads the live bag (so `lb` must be joined).
fn needs_live_bean(q: &HistoryQuery, skip: Skip) -> bool {
    matches!(q.bean, Some(BeanScope::Coffee(_))) && !skip.bean
}

/// Which side of a row a keyset predicate takes.
#[derive(Clone, Copy, PartialEq, Eq)]
enum Side {
    /// Strictly after the row in the query's order.
    After,
    /// The row and everything after it.
    From,
    /// Strictly before the row.
    Before,
}

/// The keyset predicate for rows of table `t` on `side` of the row whose sort
/// tuple is `(vk, vc, vi)` (S7). The order is `key dir, completed_at DESC,
/// id DESC`, so a single row-value comparison is right only when every column
/// runs the same way: for a descending key. An ascending key, and the
/// ascending date sort, are spelled out.
fn keyset(sort: Sort, side: Side, t: &str, vk: &str, vc: &str, vi: &str) -> String {
    let k = key_col(sort.key, t);
    let (c, i) = (format!("{t}.completed_at"), format!("{t}.id"));
    // The operators that put a row on `side`: for the tie columns, which run
    // descending, and for an ascending key.
    let (tie_op, key_op) = match side {
        Side::After => ("<", ">"),
        Side::From => ("<=", ">"),
        Side::Before => (">", "<"),
    };
    match (sort.key, sort.dir) {
        (SortKey::Date, Dir::Desc) => format!("({c}, {i}) {tie_op} ({vc}, {vi})"),
        (SortKey::Date, Dir::Asc) => {
            // After: completed_at > ?c OR (completed_at = ?c AND id < ?i).
            format!("({c} {key_op} {vc} OR ({c} = {vc} AND {i} {tie_op} {vi}))")
        }
        (_, Dir::Desc) => format!("({k}, {c}, {i}) {tie_op} ({vk}, {vc}, {vi})"),
        (_, Dir::Asc) => {
            // After: key > ?k OR (key = ?k AND (completed_at, id) < (?c, ?i)).
            format!("({k} {key_op} {vk} OR ({k} = {vk} AND ({c}, {i}) {tie_op} ({vc}, {vi})))")
        }
    }
}

/// The `ORDER BY` terms for table `t`, forward or reversed.
fn order_by(sort: Sort, t: &str, reversed: bool) -> String {
    let (key_dir, tie_dir) = match (sort.dir, reversed) {
        (Dir::Desc, false) => ("DESC", "DESC"),
        (Dir::Asc, false) => ("ASC", "DESC"),
        (Dir::Desc, true) => ("ASC", "ASC"),
        (Dir::Asc, true) => ("DESC", "ASC"),
    };
    let tie = format!("{t}.completed_at {tie_dir}, {t}.id {tie_dir}");
    match sort.key {
        SortKey::Date => format!("{t}.completed_at {key_dir}, {t}.id {tie_dir}"),
        key => format!("{} {key_dir}, {tie}", key_col(key, t)),
    }
}

/// Binds a cursor's sort tuple and returns the placeholders.
fn bind_cursor(b: &mut Builder, c: &Cursor) -> (String, String, String) {
    use crate::query::SortValue;
    let vk = match &c.key {
        SortValue::Date => String::new(),
        SortValue::Int(i) => b.int(*i),
        SortValue::Real(r) => b.bind(Param::Real(*r)),
        SortValue::Text(s) => b.text(s),
    };
    let vc = b.int(c.completed_at);
    let vi = b.text(&c.id);
    (vk, vc, vi)
}

/// `SELECT COUNT(*)` of the rows `q` lists.
#[must_use]
pub fn count(q: &HistoryQuery, path: TextPath) -> Stmt {
    let mut b = Builder::default();
    let w = filter(&mut b, q, "s", "lb", path, Skip::default());
    let sql = format!(
        "SELECT COUNT(*) {} WHERE {w}",
        from(needs_live_bean(q, Skip::default()))
    );
    b.finish(sql)
}

/// A page of `q` from `seek`, at most `limit` rows.
///
/// # Errors
/// When a cursor belongs to another query.
pub fn page(
    q: &HistoryQuery,
    seek: Seek<'_>,
    limit: u32,
    path: TextPath,
) -> Result<PageStmt, SqlError> {
    let mut b = Builder::default();
    let mut w = filter(&mut b, q, "s", "lb", path, Skip::default());
    let mut reversed = false;
    let mut offset = None;
    match seek {
        Seek::Start => {}
        Seek::Offset(n) => offset = Some(n),
        Seek::After(c) | Seek::Before(c) | Seek::From(c) => {
            c.check(q).map_err(SqlError::Cursor)?;
            let side = match seek {
                Seek::After(_) => Side::After,
                Seek::From(_) => Side::From,
                _ => {
                    reversed = true;
                    Side::Before
                }
            };
            let (vk, vc, vi) = bind_cursor(&mut b, c);
            w = format!("{w} AND {}", keyset(q.sort, side, "s", &vk, &vc, &vi));
        }
    }
    let key = match q.sort.key {
        SortKey::Date => "NULL".to_owned(),
        k => key_col(k, "s"),
    };
    let lim = b.int(i64::from(limit));
    let off = match offset {
        Some(n) => format!(" OFFSET {}", b.int(i64::from(n))),
        None => String::new(),
    };
    let sql = format!(
        "SELECT s.id, s.row_json, {key}, s.completed_at {} WHERE {w} ORDER BY {} LIMIT {lim}{off}",
        from(needs_live_bean(q, Skip::default())),
        order_by(q.sort, "s", reversed),
    );
    Ok(PageStmt {
        stmt: b.finish(sql),
        reversed,
    })
}

/// The sort tuple of row `id` in `q` (`key, completed_at, id`), or no row
/// when it isn't listed: what [`Seek::From`] and an `Around` anchor start from.
#[must_use]
pub fn row_cursor(q: &HistoryQuery, id: &str, path: TextPath) -> Stmt {
    let mut b = Builder::default();
    let w = filter(&mut b, q, "s", "lb", path, Skip::default());
    let p = b.text(id);
    let key = match q.sort.key {
        SortKey::Date => "NULL".to_owned(),
        k => key_col(k, "s"),
    };
    let sql = format!(
        "SELECT {key}, s.completed_at, s.id {} WHERE s.id = {p} AND {w}",
        from(needs_live_bean(q, Skip::default()))
    );
    b.finish(sql)
}

/// Row `id`'s position in `q`: how many rows come before it. No row when it
/// isn't listed, so the caller clears the filter or falls back.
#[must_use]
pub fn index_of(q: &HistoryQuery, id: &str, path: TextPath) -> Stmt {
    let mut b = Builder::default();
    let p = b.text(id);
    let me = filter(&mut b, q, "m", "mlb", path, Skip::default());
    let w = filter(&mut b, q, "s", "lb", path, Skip::default());
    let before = keyset(
        q.sort,
        Side::Before,
        "s",
        &key_col(q.sort.key, "m"),
        "m.completed_at",
        "m.id",
    );
    let live = needs_live_bean(q, Skip::default());
    let me_from = if live {
        "FROM shot m LEFT JOIN live_bean mlb ON mlb.bean_id = m.bean_id"
    } else {
        "FROM shot m"
    };
    let sql = format!(
        "SELECT (SELECT COUNT(*) {} WHERE {w} AND {before}) {me_from} WHERE m.id = {p} AND {me}",
        from(live)
    );
    b.finish(sql)
}

/// The rows `q` lists in each bucket the `edges` cut (descending, in ms):
/// `completed_at >= edges[0]`, then `[edges[1], edges[0])`, …, then
/// `< edges[n-1]`. One row of `edges.len() + 1` counts: the day groups.
#[must_use]
pub fn group_counts(q: &HistoryQuery, edges_ms: &[i64], path: TextPath) -> Stmt {
    let mut b = Builder::default();
    let w = filter(&mut b, q, "s", "lb", path, Skip::default());
    let mut edges = edges_ms.to_vec();
    edges.sort_unstable_by(|a, b| b.cmp(a));
    let ps: Vec<String> = edges.iter().map(|e| b.int(*e)).collect();
    let mut cols = Vec::with_capacity(ps.len() + 1);
    for (i, p) in ps.iter().enumerate() {
        let cond = match i {
            0 => format!("s.completed_at >= {p}"),
            _ => format!("s.completed_at >= {p} AND s.completed_at < {}", ps[i - 1]),
        };
        cols.push(format!("COALESCE(SUM({cond}), 0)"));
    }
    cols.push(match ps.last() {
        Some(p) => format!("COALESCE(SUM(s.completed_at < {p}), 0)"),
        None => "COUNT(*)".to_owned(),
    });
    let sql = format!(
        "SELECT {} {} WHERE {w}",
        cols.join(", "),
        from(needs_live_bean(q, Skip::default()))
    );
    b.finish(sql)
}

/// One row per stats family: `family, count`, then each stat input's sum and
/// count. [`crate::stats::combine`] applies the scope rule.
#[must_use]
pub fn stats(q: &HistoryQuery, path: TextPath) -> Stmt {
    let mut b = Builder::default();
    let w = filter(&mut b, q, "s", "lb", path, Skip::default());
    let sql = format!(
        "SELECT s.family, COUNT(*), \
         SUM(s.st_dose), COUNT(s.st_dose), SUM(s.st_yield), COUNT(s.st_yield), \
         SUM(s.st_ratio), COUNT(s.st_ratio), SUM(s.st_time_s), COUNT(s.st_time_s), \
         SUM(s.st_rating), COUNT(s.st_rating) {} WHERE {w} GROUP BY s.family",
        from(needs_live_bean(q, Skip::default()))
    );
    b.finish(sql)
}

/// The facet statements for `q`.
#[must_use]
pub fn facets(q: &HistoryQuery, path: TextPath) -> FacetStmts {
    let one = |skip: Skip, select: &dyn Fn(&str, &str) -> String| {
        let mut b = Builder::default();
        let w = filter(&mut b, q, "s", "lb", path, skip);
        let sql = select(from(needs_live_bean(q, skip)), &w);
        b.finish(sql)
    };
    let profiles = one(
        Skip {
            profile: true,
            ..Skip::default()
        },
        &|from, w| {
            format!(
                "SELECT s.profile_name, COUNT(*) {from} WHERE {w} AND s.profile_name IS NOT NULL AND s.profile_name <> '' \
                 GROUP BY s.profile_name ORDER BY COUNT(*) DESC, s.profile_name"
            )
        },
    );
    let methods = one(
        Skip {
            method: true,
            ..Skip::default()
        },
        &|from, w| {
            format!(
                "SELECT s.method, COUNT(*) {from} WHERE {w} GROUP BY s.method ORDER BY COUNT(*) DESC, s.method"
            )
        },
    );
    let tags = one(
        Skip {
            tags: true,
            ..Skip::default()
        },
        &|from, w| {
            format!(
                "SELECT tag, COUNT(*) FROM (\
                 SELECT st.tag AS tag {from} JOIN shot_tag st ON st.rid = s.rid WHERE {w} \
                 UNION ALL \
                 SELECT bt.tag AS tag {from} JOIN live_bean_tag bt ON bt.bean_id = s.bean_id WHERE s.has_tags = 0 AND {w}\
                 ) GROUP BY tag ORDER BY COUNT(*) DESC, tag"
            )
        },
    );
    let coffees = one(
        Skip {
            bean: true,
            ..Skip::default()
        },
        &|_, w| {
            format!(
                "SELECT {} AS coffee, \
                 CASE WHEN lb.bean_id IS NOT NULL THEN lb.name ELSE json_extract(s.row_json, '$.bean.name') END, \
                 CASE WHEN lb.bean_id IS NOT NULL THEN lb.roaster ELSE json_extract(s.row_json, '$.bean.roasterName') END, \
                 MAX(s.completed_at), COUNT(*), COUNT(DISTINCT s.bean_id) \
                 {} WHERE {w} GROUP BY coffee HAVING coffee IS NOT NULL \
                 ORDER BY COUNT(*) DESC, coffee",
                coffee("s", "lb"),
                from(true)
            )
        },
    );
    let pinned = one(
        Skip {
            pinned: true,
            ..Skip::default()
        },
        &|from, w| format!("SELECT COUNT(*) {from} WHERE {w} AND s.pinned = 1"),
    );
    FacetStmts {
        profiles,
        methods,
        tags,
        coffees,
        pinned,
    }
}

/// `id, row_json` of the named rows, held ones left out. One parameter, a
/// JSON array of ids, so any number fits.
#[must_use]
pub fn get(ids: &[String]) -> Stmt {
    let mut b = Builder::default();
    let p = b.text(&serde_json::to_string(ids).unwrap_or_else(|_| "[]".to_owned()));
    let sql = format!(
        "SELECT s.id, s.row_json FROM shot s WHERE s.id IN (SELECT value FROM json_each({p})) AND s.hold_delete = 0"
    );
    b.finish(sql)
}

/// The doses of a bag's newest `limit` listed rows, for the stock estimate
/// (`BeanContextCard`): `st_dose`, the positive doses.
#[must_use]
pub fn doses_for_bag(bean_id: &str, limit: u32) -> Stmt {
    let mut b = Builder::default();
    let p = b.text(bean_id);
    let lim = b.int(i64::from(limit));
    let sql = format!(
        "SELECT s.st_dose FROM shot s WHERE s.deleted_at IS NULL AND s.hold_delete = 0 AND s.bean_id = {p} \
         AND s.st_dose IS NOT NULL ORDER BY s.completed_at DESC, s.id DESC LIMIT {lim}"
    );
    b.finish(sql)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::query::SortValue;

    fn sorted(key: SortKey, dir: Dir) -> HistoryQuery {
        HistoryQuery {
            sort: Sort { key, dir },
            ..HistoryQuery::default()
        }
    }

    fn cursor(q: &HistoryQuery, key: SortValue) -> Cursor {
        Cursor {
            query_hash: q.hash(),
            key,
            completed_at: 100,
            id: "m".into(),
        }
    }

    fn where_of(p: &PageStmt) -> &str {
        let sql = &p.stmt.sql;
        let start = sql.find(" WHERE ").expect("a WHERE");
        let end = sql.find(" ORDER BY ").expect("an ORDER BY");
        &sql[start..end]
    }

    // ── The keyset predicate, per direction (S7) ──

    #[test]
    fn a_descending_key_compares_one_row_value() {
        let q = sorted(SortKey::Rating, Dir::Desc);
        let c = cursor(&q, SortValue::Int(4));
        let after = page(&q, Seek::After(&c), 64, TextPath::Fts).expect("builds");
        assert!(
            where_of(&after).ends_with("AND (s.sort_rating, s.completed_at, s.id) < (?1, ?2, ?3)")
        );
        assert!(
            after
                .stmt
                .sql
                .contains("ORDER BY s.sort_rating DESC, s.completed_at DESC, s.id DESC")
        );
        assert!(!after.reversed);
        let before = page(&q, Seek::Before(&c), 64, TextPath::Fts).expect("builds");
        assert!(
            where_of(&before).ends_with("AND (s.sort_rating, s.completed_at, s.id) > (?1, ?2, ?3)")
        );
        assert!(
            before
                .stmt
                .sql
                .contains("ORDER BY s.sort_rating ASC, s.completed_at ASC, s.id ASC")
        );
        assert!(before.reversed);
        assert_eq!(
            after.stmt.params,
            vec![
                Param::Int(4),
                Param::Int(100),
                Param::Text("m".into()),
                Param::Int(64)
            ]
        );
    }

    #[test]
    fn an_ascending_key_spells_out_its_newest_first_ties() {
        let q = sorted(SortKey::Profile, Dir::Asc);
        let c = cursor(&q, SortValue::Text("turbo".into()));
        let after = page(&q, Seek::After(&c), 10, TextPath::Fts).expect("builds");
        assert!(where_of(&after).ends_with(
            "AND (s.sort_profile > ?1 OR (s.sort_profile = ?1 AND (s.completed_at, s.id) < (?2, ?3)))"
        ));
        assert!(
            after
                .stmt
                .sql
                .contains("ORDER BY s.sort_profile ASC, s.completed_at DESC, s.id DESC")
        );
        let before = page(&q, Seek::Before(&c), 10, TextPath::Fts).expect("builds");
        assert!(where_of(&before).ends_with(
            "AND (s.sort_profile < ?1 OR (s.sort_profile = ?1 AND (s.completed_at, s.id) > (?2, ?3)))"
        ));
        assert!(
            before
                .stmt
                .sql
                .contains("ORDER BY s.sort_profile DESC, s.completed_at ASC, s.id ASC")
        );
        let from = page(&q, Seek::From(&c), 10, TextPath::Fts).expect("builds");
        assert!(where_of(&from).ends_with(
            "AND (s.sort_profile > ?1 OR (s.sort_profile = ?1 AND (s.completed_at, s.id) <= (?2, ?3)))"
        ));
    }

    #[test]
    fn the_date_sort_compares_time_then_id() {
        let desc = sorted(SortKey::Date, Dir::Desc);
        let c = cursor(&desc, SortValue::Date);
        let after = page(&desc, Seek::After(&c), 10, TextPath::Fts).expect("builds");
        assert!(where_of(&after).ends_with("AND (s.completed_at, s.id) < (?1, ?2)"));
        assert!(
            after
                .stmt
                .sql
                .contains("ORDER BY s.completed_at DESC, s.id DESC")
        );

        let asc = sorted(SortKey::Date, Dir::Asc);
        let c = cursor(&asc, SortValue::Date);
        let after = page(&asc, Seek::After(&c), 10, TextPath::Fts).expect("builds");
        assert!(
            where_of(&after)
                .ends_with("AND (s.completed_at > ?1 OR (s.completed_at = ?1 AND s.id < ?2))")
        );
        assert!(
            after
                .stmt
                .sql
                .contains("ORDER BY s.completed_at ASC, s.id DESC")
        );
        let before = page(&asc, Seek::Before(&c), 10, TextPath::Fts).expect("builds");
        assert!(
            where_of(&before)
                .ends_with("AND (s.completed_at < ?1 OR (s.completed_at = ?1 AND s.id > ?2))")
        );
        assert!(
            before
                .stmt
                .sql
                .contains("ORDER BY s.completed_at DESC, s.id ASC")
        );
    }

    #[test]
    fn a_cursor_from_another_query_builds_nothing() {
        let q = sorted(SortKey::Rating, Dir::Desc);
        let other = sorted(SortKey::Time, Dir::Desc);
        let c = cursor(&other, SortValue::Int(1));
        assert_eq!(
            page(&q, Seek::After(&c), 1, TextPath::Fts),
            Err(SqlError::Cursor(CursorError::OtherQuery))
        );
    }

    #[test]
    fn an_offset_jump_skips_rows() {
        let q = HistoryQuery::default();
        let p = page(&q, Seek::Offset(300), 64, TextPath::Fts).expect("builds");
        assert!(p.stmt.sql.ends_with("LIMIT ?1 OFFSET ?2"));
        assert_eq!(p.stmt.params, vec![Param::Int(64), Param::Int(300)]);
    }

    // ── Filters ──

    #[test]
    fn every_list_query_spells_the_partial_index_condition() {
        let q = HistoryQuery {
            text: "sour".into(),
            bean: Some(BeanScope::Coffee("sey|kenya".into())),
            ..HistoryQuery::default()
        };
        let cond = "s.deleted_at IS NULL AND s.hold_delete = 0";
        let f = facets(&q, TextPath::Fts);
        for stmt in [
            count(&q, TextPath::Fts),
            page(&q, Seek::Start, 1, TextPath::Fts)
                .expect("builds")
                .stmt,
            row_cursor(&q, "a", TextPath::Fts),
            index_of(&q, "a", TextPath::Fts),
            group_counts(&q, &[1, 2], TextPath::Fts),
            stats(&q, TextPath::Fts),
            f.profiles,
            f.methods,
            f.tags,
            f.coffees,
            f.pinned,
        ] {
            assert!(stmt.sql.contains(cond), "{}", stmt.sql);
        }
    }

    #[test]
    fn a_search_is_a_glob_with_its_specials_escaped() {
        let q = HistoryQuery {
            text: " A*b?[c] ".into(),
            ..HistoryQuery::default()
        };
        let s = count(&q, TextPath::Fts);
        assert!(
            s.sql
                .contains("s.rid IN (SELECT rowid FROM shot_fts WHERE search_text GLOB ?1)")
        );
        assert_eq!(s.params[0], Param::Text("*a[*]b[?][[]c]*".into()));
        assert_eq!(s.params[1], Param::Text("a*b?[c]".into()));
        // No FTS5 query syntax is ever live.
        assert!(!s.sql.contains("MATCH"));
        let scan = count(&q, TextPath::Scan);
        assert!(scan.sql.contains("instr(s.search_text, ?1) > 0"));
        assert!(!scan.sql.contains("shot_fts"));
    }

    #[test]
    fn a_short_search_runs_no_text_statement() {
        let q = HistoryQuery {
            text: "ki".into(),
            ..HistoryQuery::default()
        };
        for path in [TextPath::Fts, TextPath::Scan] {
            let s = count(&q, path);
            assert_eq!(s, count(&HistoryQuery::default(), path));
            assert!(!s.sql.contains("shot_fts") && !s.sql.contains("instr"));
        }
    }

    #[test]
    fn every_filter_binds_its_values() {
        let q = HistoryQuery {
            since_ms: Some(7),
            profile: Some("Turbo".into()),
            bean: Some(BeanScope::Bag("b1".into())),
            method: Some("espresso".into()),
            tags_all: vec!["sour".into(), "dial-in".into(), "sour".into()],
            pinned_only: true,
            ..HistoryQuery::default()
        };
        let s = count(&q, TextPath::Fts);
        assert!(s.sql.contains("s.completed_at >= ?1"));
        assert!(s.sql.contains("s.profile_name = ?2"));
        assert!(s.sql.contains("s.pinned = 1"));
        assert!(s.sql.contains("s.bean_id = ?3"));
        assert!(s.sql.contains("s.method = ?4"));
        assert!(s.sql.contains("st.tag = ?5") && s.sql.contains("st.tag = ?6"));
        assert!(!s.sql.contains("live_bean lb"), "a bag needs no live join");
        assert_eq!(
            s.params,
            vec![
                Param::Int(7),
                Param::Text("Turbo".into()),
                Param::Text("b1".into()),
                Param::Text("espresso".into()),
                Param::Text("dial-in".into()),
                Param::Text("sour".into()),
            ]
        );
    }

    #[test]
    fn a_coffee_resolves_through_the_live_bag() {
        let q = HistoryQuery {
            bean: Some(BeanScope::Coffee("sey|kenya".into())),
            ..HistoryQuery::default()
        };
        let s = count(&q, TextPath::Fts);
        assert!(
            s.sql
                .contains("LEFT JOIN live_bean lb ON lb.bean_id = s.bean_id")
        );
        assert!(s.sql.contains(
            "(CASE WHEN lb.bean_id IS NOT NULL THEN lb.coffee_key ELSE s.coffee_key END) = ?1"
        ));
        let i = index_of(&q, "a", TextPath::Fts);
        assert!(
            i.sql
                .contains("LEFT JOIN live_bean mlb ON mlb.bean_id = m.bean_id")
        );
        assert!(i.sql.contains(
            "(CASE WHEN mlb.bean_id IS NOT NULL THEN mlb.coffee_key ELSE m.coffee_key END) = ?2"
        ));
    }

    #[test]
    fn index_of_counts_the_rows_before_the_row_under_the_same_filter() {
        let q = sorted(SortKey::Rating, Dir::Asc);
        let i = index_of(&q, "a", TextPath::Fts);
        assert!(i.sql.contains(
            "(s.sort_rating < m.sort_rating OR (s.sort_rating = m.sort_rating AND (s.completed_at, s.id) > (m.completed_at, m.id)))"
        ));
        assert!(
            i.sql
                .ends_with("WHERE m.id = ?1 AND m.deleted_at IS NULL AND m.hold_delete = 0")
        );
    }

    #[test]
    fn group_counts_cut_descending_edges() {
        let s = group_counts(&HistoryQuery::default(), &[10, 30, 20], TextPath::Fts);
        assert!(s.sql.starts_with(
            "SELECT COALESCE(SUM(s.completed_at >= ?1), 0), \
             COALESCE(SUM(s.completed_at >= ?2 AND s.completed_at < ?1), 0), \
             COALESCE(SUM(s.completed_at >= ?3 AND s.completed_at < ?2), 0), \
             COALESCE(SUM(s.completed_at < ?3), 0) FROM shot s"
        ));
        assert_eq!(
            s.params,
            vec![Param::Int(30), Param::Int(20), Param::Int(10)]
        );
        let none = group_counts(&HistoryQuery::default(), &[], TextPath::Fts);
        assert!(none.sql.starts_with("SELECT COUNT(*) FROM shot s"));
    }

    #[test]
    fn each_facet_drops_its_own_dimension() {
        let q = HistoryQuery {
            profile: Some("Turbo".into()),
            method: Some("espresso".into()),
            tags_all: vec!["x".into()],
            pinned_only: true,
            bean: Some(BeanScope::Bag("b".into())),
            ..HistoryQuery::default()
        };
        let f = facets(&q, TextPath::Fts);
        assert!(!f.profiles.sql.contains("s.profile_name = ?"));
        assert!(f.profiles.sql.contains("s.method = ?"));
        assert!(!f.methods.sql.contains("s.method = ?"));
        assert!(!f.tags.sql.contains("st.tag = ?"));
        assert!(!f.coffees.sql.contains("s.bean_id = ?"));
        assert!(f.coffees.sql.contains("s.pinned = 1"));
        assert!(f.pinned.sql.contains("s.bean_id = ?"));
        assert_eq!(f.pinned.sql.matches("s.pinned = 1").count(), 1);
    }

    #[test]
    fn get_takes_any_number_of_ids_in_one_parameter() {
        let s = get(&["a".into(), "b\"c".into()]);
        assert_eq!(s.params, vec![Param::Text(r#"["a","b\"c"]"#.into())]);
        assert!(s.sql.contains("json_each(?1)"));
    }
}

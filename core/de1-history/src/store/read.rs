//! Running the query builders of [`crate::sql`] (SPEC §7). Every search runs
//! on the path [`Store::text_path`] gives; a failure on the index drops it
//! and the query runs again on the scan path (M6), so a search on a damaged
//! index answers, with health `reindexing`, and never gives `Corrupt`.

use de1_domain::{BrewHistoryStats, LastGrind};
use rusqlite::types::{Value as Sql, ValueRef};
use rusqlite::{Connection, Row, params_from_iter};
use serde::{Deserialize, Serialize};

use super::error::{Fail, Result, Source, StoreError};
use super::faults::Faults;
use super::{Settled, Store};
use crate::cursor::Cursor;
use crate::query::{Anchor, HistoryQuery, SortKey, SortValue};
use crate::sql::{self, Param, Seek, Stmt, TextPath};
use crate::stats::{FamilySums, combine};

/// A row as queries return it: its id and its `row_json`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RowOut {
    /// The row's id.
    pub id: String,
    /// The row, as stored.
    pub row_json: String,
}

/// A row with its `rid`, for walks that page by `rid`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RidRow {
    /// The row's `rid`: the next page starts after the last one.
    pub rid: i64,
    /// The row's id.
    pub id: String,
    /// The row, as stored.
    pub row_json: String,
}

/// A page of a query (SPEC §7).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Page {
    /// The rows, in the query's order.
    pub rows: Vec<RowOut>,
    /// The first row's position in the query, when the anchor tells it
    /// (`Start`, `Offset`, `Around`); `None` for keyset pages.
    pub start_index: Option<u32>,
    /// The first row's cursor: `Before` it is the page above.
    pub first: Option<Cursor>,
    /// The last row's cursor: `After` it is the page below.
    pub last: Option<Cursor>,
    /// No row of the query comes before this page.
    pub at_start: bool,
    /// No row of the query comes after it.
    pub at_end: bool,
    /// The `write_seq` the page was read at (S14): a window drops a page
    /// older than the last notice it applied.
    pub seq: u64,
}

/// One coffee in the facets: the effective coffee key, its label, and use.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CoffeeFacet {
    /// The coffee key.
    pub coffee_key: String,
    /// The live bag's name, else a row's snapshot.
    pub name: Option<String>,
    /// The live bag's roaster, else a row's snapshot.
    pub roaster: Option<String>,
    /// The newest row's `completed_at`.
    pub last_ms: i64,
    /// Rows.
    pub count: u32,
    /// Distinct bags.
    pub bags: u32,
}

/// The filter options next to a query (SPEC §7): each over the query's
/// filter without its own dimension.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Facets {
    /// `(profile, rows)`, most used first.
    pub profiles: Vec<(String, u32)>,
    /// `(method, rows)`, most used first.
    pub methods: Vec<(String, u32)>,
    /// `(tag, rows)` over the effective tags, most used first.
    pub tags: Vec<(String, u32)>,
    /// The coffees, most used first.
    pub coffees: Vec<CoffeeFacet>,
    /// Pinned rows.
    pub pinned: u32,
}

/// The light projection sync's planners read (SPEC §9, S3): every row,
/// tombstones and held rows included, from the derived columns.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncRow {
    /// The row's `rid`.
    pub rid: i64,
    /// The row's id.
    pub id: String,
    /// Its Visualizer id.
    pub visualizer_id: Option<String>,
    /// Its Decent id.
    pub decent_id: Option<String>,
    /// When it was pulled.
    pub completed_at: i64,
    /// Its duration, ms.
    pub duration_ms: i64,
    /// Its tombstone.
    pub deleted_at: Option<i64>,
    /// A Brew Log row.
    pub is_brew_log: bool,
    /// Its sample count; `None` when unknown.
    pub sample_count: Option<i64>,
    /// Its profile name.
    pub profile_name: Option<String>,
    /// Deleted during an export walk and not yet removed: hidden.
    pub held: bool,
    /// Its final weight (`summary.finalWeight`), the reconcile signature's
    /// weight (PLAN A3a, A5).
    pub final_weight: Option<f64>,
    /// When its Visualizer copy last changed (`visualizerUpdatedAt`), the
    /// known rows' version (PLAN A3a, A5).
    pub visualizer_updated_at: Option<i64>,
}

/// A page of the sync projection, with the `write_seq` it was read at.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncPage {
    /// The rows, by `rid`.
    pub rows: Vec<SyncRow>,
    /// The `write_seq` they were read at. The shell reads every page, then
    /// starts again if it moved (S3).
    pub seq: u64,
}

/// Where an upload goes, for [`Store::unsent`].
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum Destination {
    /// Visualizer: rows with no `visualizer_id`.
    Visualizer,
    /// Decent: rows with no `decent_id`.
    Decent,
}

/// A statement's parameters as SQLite values.
pub(crate) fn sql_params(params: &[Param]) -> Vec<Sql> {
    params
        .iter()
        .map(|p| match p {
            Param::Int(i) => Sql::Integer(*i),
            Param::Real(r) => Sql::Real(*r),
            Param::Text(t) => Sql::Text(t.clone()),
        })
        .collect()
}

/// Runs `stmt` and maps each row. A statement that reads the search index
/// fails as the index's (M6).
pub(crate) fn rows<T>(
    conn: &Connection,
    faults: &Faults,
    stmt: &Stmt,
    mut map: impl FnMut(&Row<'_>) -> rusqlite::Result<T>,
) -> std::result::Result<Vec<T>, Fail> {
    faults.log(&stmt.sql);
    let source = if stmt.sql.contains("shot_fts") {
        Source::Fts
    } else {
        Source::Session
    };
    let mut run = || -> rusqlite::Result<Vec<T>> {
        let mut prepared = conn.prepare_cached(&stmt.sql)?;
        let mut out = Vec::new();
        let mut rows = prepared.query(params_from_iter(sql_params(&stmt.params)))?;
        while let Some(row) = rows.next()? {
            out.push(map(row)?);
        }
        Ok(out)
    };
    run().map_err(|e| Fail::Sql(e, source))
}

/// A row's grind as text: a non-blank string as stored, a finite number in
/// its shortest form (`15`, `15.5`, `480`); anything else is none.
fn grind_text(v: ValueRef<'_>) -> Option<String> {
    match v {
        ValueRef::Text(t) => std::str::from_utf8(t)
            .ok()
            .filter(|t| !t.trim().is_empty())
            .map(str::to_owned),
        ValueRef::Integer(i) => Some(i.to_string()),
        ValueRef::Real(f) if f.is_finite() => Some(format!("{f}")),
        _ => None,
    }
}

/// The `write_seq` committed, read in the caller's transaction.
fn seq_in(conn: &Connection) -> rusqlite::Result<u64> {
    conn.query_row("SELECT value FROM meta WHERE key = 'write_seq'", [], |r| {
        r.get::<_, String>(0)
    })
    .map(|s| s.parse().unwrap_or(0))
}

/// A sort column's value as a cursor carries it.
fn sort_value(key: SortKey, v: ValueRef<'_>) -> rusqlite::Result<SortValue> {
    use rusqlite::types::FromSql;
    let conv = |e| rusqlite::Error::FromSqlConversionFailure(2, v.data_type(), Box::new(e));
    Ok(match key {
        SortKey::Date => SortValue::Date,
        SortKey::Rating | SortKey::Time => SortValue::Int(i64::column_result(v).map_err(conv)?),
        SortKey::Yield => SortValue::Real(f64::column_result(v).map_err(conv)?),
        SortKey::Profile | SortKey::Bean => {
            SortValue::Text(String::column_result(v).map_err(conv)?)
        }
    })
}

/// A page statement's rows, each with its cursor, in the query's order.
fn seek(
    conn: &Connection,
    faults: &Faults,
    q: &HistoryQuery,
    at: Seek<'_>,
    limit: u32,
    path: TextPath,
) -> std::result::Result<Vec<(RowOut, Cursor)>, Fail> {
    let stmt = sql::page(q, at, limit, path).map_err(|e| StoreError::Refused(e.to_string()))?;
    let hash = q.hash();
    let mut out = rows(conn, faults, &stmt.stmt, |r| {
        let id: String = r.get(0)?;
        let cursor = Cursor {
            query_hash: hash,
            key: sort_value(q.sort.key, r.get_ref(2)?)?,
            completed_at: r.get(3)?,
            id: id.clone(),
        };
        Ok((
            RowOut {
                id,
                row_json: r.get(1)?,
            },
            cursor,
        ))
    })?;
    if stmt.reversed {
        out.reverse();
    }
    Ok(out)
}

/// Whether any row of `q` comes before (`before`) or after `c`.
fn any_beyond(
    conn: &Connection,
    faults: &Faults,
    q: &HistoryQuery,
    c: &Cursor,
    before: bool,
    path: TextPath,
) -> std::result::Result<bool, Fail> {
    let at = if before {
        Seek::Before(c)
    } else {
        Seek::After(c)
    };
    Ok(!seek(conn, faults, q, at, 1, path)?.is_empty())
}

fn u32_of(n: i64) -> u32 {
    u32::try_from(n.max(0)).unwrap_or(u32::MAX)
}

fn pairs(
    conn: &Connection,
    faults: &Faults,
    stmt: &Stmt,
) -> std::result::Result<Vec<(String, u32)>, Fail> {
    rows(conn, faults, stmt, |r| {
        Ok((r.get::<_, String>(0)?, u32_of(r.get(1)?)))
    })
}

fn sync_row(r: &Row<'_>) -> rusqlite::Result<SyncRow> {
    Ok(SyncRow {
        rid: r.get(0)?,
        id: r.get(1)?,
        visualizer_id: r.get(2)?,
        decent_id: r.get(3)?,
        completed_at: r.get(4)?,
        duration_ms: r.get(5)?,
        deleted_at: r.get(6)?,
        is_brew_log: r.get(7)?,
        sample_count: r.get(8)?,
        profile_name: r.get(9)?,
        held: r.get(10)?,
        final_weight: r.get(11)?,
        visualizer_updated_at: r.get(12)?,
    })
}

const SYNC_COLUMNS: &str = "rid, id, visualizer_id, decent_id, completed_at, sort_time, deleted_at, \
     is_brew_log, sample_count, profile_name, hold_delete, \
     CASE WHEN json_type(row_json, '$.summary.finalWeight') IN ('integer', 'real') \
       THEN CAST(json_extract(row_json, '$.summary.finalWeight') AS REAL) END, \
     CASE WHEN json_type(row_json, '$.visualizerUpdatedAt') IN ('integer', 'real') \
       THEN CAST(json_extract(row_json, '$.visualizerUpdatedAt') AS INTEGER) END";

impl Store {
    /// Runs a read on the current text path, in one read transaction. When
    /// it fails on the search index, the index is dropped and the read runs
    /// once more, by scan.
    pub(crate) fn read<T>(
        &mut self,
        mut body: impl FnMut(&Connection, &Faults, TextPath) -> std::result::Result<T, Fail>,
    ) -> Result<T> {
        self.usable()?;
        let mut retried = false;
        loop {
            let path = self.text_path();
            let attempt = (|| {
                let tx = self.conn.unchecked_transaction()?;
                let out = body(&tx, &self.faults, path)?;
                tx.commit()?;
                Ok(out)
            })();
            match attempt {
                Ok(out) => return Ok(out),
                Err(fail) => match self.settle(fail) {
                    Settled::Retry if !retried => retried = true,
                    Settled::Retry => {
                        return Err(StoreError::FtsDamaged(
                            "the read failed again on the scan path".into(),
                        ));
                    }
                    Settled::Error(e) => return Err(e),
                },
            }
        }
    }

    /// How many rows `q` lists.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn count(&mut self, q: &HistoryQuery) -> Result<u32> {
        self.read(|conn, faults, path| {
            let n: Vec<i64> = rows(conn, faults, &sql::count(q, path), |r| r.get(0))?;
            Ok(u32_of(n.first().copied().unwrap_or(0)))
        })
    }

    /// A page of `q` at `at`, at most `limit` rows (SPEC §7): keyset after or
    /// before a cursor, an offset jump, or the page around a row. Reads in
    /// one transaction, and carries the `write_seq` it was read at (S14).
    ///
    /// # Errors
    /// [`StoreError::Refused`] for a cursor of another query or a `limit` of
    /// 0; [`StoreError::NotListed`] when an `Around` row isn't in `q`; a
    /// SQLite error class otherwise.
    pub fn page(&mut self, q: &HistoryQuery, at: &Anchor, limit: u32) -> Result<Page> {
        if limit == 0 {
            return Err(StoreError::Refused("a page of 0 rows".into()));
        }
        self.read(|conn, faults, path| {
            let seq = seq_in(conn)?;
            let mut start_index = None;
            let (rows_c, at_start, at_end) = match at {
                Anchor::Start | Anchor::Offset(_) => {
                    let (s, n) = match at {
                        Anchor::Offset(n) => (Seek::Offset(*n), *n),
                        _ => (Seek::Start, 0),
                    };
                    let mut got = seek(conn, faults, q, s, limit.saturating_add(1), path)?;
                    let at_end = got.len() <= limit as usize;
                    got.truncate(limit as usize);
                    start_index = Some(n);
                    (got, n == 0, at_end)
                }
                Anchor::After(c) => {
                    let mut got = seek(
                        conn,
                        faults,
                        q,
                        Seek::After(c),
                        limit.saturating_add(1),
                        path,
                    )?;
                    let at_end = got.len() <= limit as usize;
                    got.truncate(limit as usize);
                    let first = got.first().map_or(c, |(_, fc)| fc);
                    let at_start = !any_beyond(conn, faults, q, first, true, path)?;
                    (got, at_start, at_end)
                }
                Anchor::Before(c) => {
                    let mut got = seek(
                        conn,
                        faults,
                        q,
                        Seek::Before(c),
                        limit.saturating_add(1),
                        path,
                    )?;
                    let at_start = got.len() <= limit as usize;
                    if !at_start {
                        got.remove(0);
                    }
                    let last = got.last().map_or(c, |(_, lc)| lc);
                    let at_end = !any_beyond(conn, faults, q, last, false, path)?;
                    (got, at_start, at_end)
                }
                Anchor::Around { id, before } => {
                    let cur = rows(conn, faults, &sql::row_cursor(q, id, path), |r| {
                        Ok(Cursor {
                            query_hash: q.hash(),
                            key: sort_value(q.sort.key, r.get_ref(0)?)?,
                            completed_at: r.get(1)?,
                            id: r.get(2)?,
                        })
                    })?;
                    let Some(c) = cur.into_iter().next() else {
                        return Err(StoreError::NotListed(id.clone()).into());
                    };
                    let index: Vec<i64> =
                        rows(conn, faults, &sql::index_of(q, id, path), |r| r.get(0))?;
                    let index = u32_of(index.first().copied().unwrap_or(0));
                    let want_back = (*before).min(limit - 1);
                    let back = if want_back == 0 {
                        Vec::new()
                    } else {
                        seek(conn, faults, q, Seek::Before(&c), want_back, path)?
                    };
                    let back_n = u32::try_from(back.len()).unwrap_or(0);
                    let fwd_n = limit - back_n;
                    let mut fwd = seek(
                        conn,
                        faults,
                        q,
                        Seek::From(&c),
                        fwd_n.saturating_add(1),
                        path,
                    )?;
                    let at_end = fwd.len() <= fwd_n as usize;
                    fwd.truncate(fwd_n as usize);
                    let at_start = back_n == index;
                    start_index = Some(index.saturating_sub(back_n));
                    let mut got = back;
                    got.extend(fwd);
                    (got, at_start, at_end)
                }
            };
            Ok(Page {
                first: rows_c.first().map(|(_, c)| c.clone()),
                last: rows_c.last().map(|(_, c)| c.clone()),
                rows: rows_c.into_iter().map(|(r, _)| r).collect(),
                start_index,
                at_start,
                at_end,
                seq,
            })
        })
    }

    /// Row `id`'s position in `q`, or `None` when `q` doesn't list it.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn index_of(&mut self, q: &HistoryQuery, id: &str) -> Result<Option<u32>> {
        self.read(|conn, faults, path| {
            let n: Vec<i64> = rows(conn, faults, &sql::index_of(q, id, path), |r| r.get(0))?;
            Ok(n.first().map(|n| u32_of(*n)))
        })
    }

    /// The rows `q` lists in each bucket the `edges_ms` cut, newest first:
    /// the day groups (SPEC §7). `edges_ms.len() + 1` counts.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn group_counts(&mut self, q: &HistoryQuery, edges_ms: &[i64]) -> Result<Vec<u32>> {
        self.read(|conn, faults, path| {
            let stmt = sql::group_counts(q, edges_ms, path);
            let n = edges_ms.len() + 1;
            let got = rows(conn, faults, &stmt, |r| {
                (0..n)
                    .map(|i| r.get::<_, i64>(i).map(u32_of))
                    .collect::<rusqlite::Result<Vec<u32>>>()
            })?;
            Ok(got.into_iter().next().unwrap_or_else(|| vec![0; n]))
        })
    }

    /// The stat strip over the rows `q` lists: one `GROUP BY family` pass,
    /// combined under `brew_history_stats`'s scope rule (SPEC §7).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn stats(&mut self, q: &HistoryQuery) -> Result<BrewHistoryStats> {
        self.read(|conn, faults, path| {
            let pair = |r: &Row<'_>, i: usize| -> rusqlite::Result<(f64, u64)> {
                Ok((
                    r.get::<_, Option<f64>>(i)?.unwrap_or(0.0),
                    u64::try_from(r.get::<_, i64>(i + 1)?).unwrap_or(0),
                ))
            };
            let groups = rows(conn, faults, &sql::stats(q, path), |r| {
                Ok(FamilySums {
                    family: r.get(0)?,
                    count: u64::try_from(r.get::<_, i64>(1)?).unwrap_or(0),
                    dose: pair(r, 2)?,
                    yield_g: pair(r, 4)?,
                    ratio: pair(r, 6)?,
                    time_s: pair(r, 8)?,
                    rating: pair(r, 10)?,
                })
            })?;
            Ok(combine(&groups))
        })
    }

    /// The facets next to `q` (SPEC §7).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn facets(&mut self, q: &HistoryQuery) -> Result<Facets> {
        self.read(|conn, faults, path| {
            let f = sql::facets(q, path);
            let pinned: Vec<i64> = rows(conn, faults, &f.pinned, |r| r.get(0))?;
            Ok(Facets {
                profiles: pairs(conn, faults, &f.profiles)?,
                methods: pairs(conn, faults, &f.methods)?,
                tags: pairs(conn, faults, &f.tags)?,
                coffees: rows(conn, faults, &f.coffees, |r| {
                    Ok(CoffeeFacet {
                        coffee_key: r.get(0)?,
                        name: r.get(1)?,
                        roaster: r.get(2)?,
                        last_ms: r.get(3)?,
                        count: u32_of(r.get(4)?),
                        bags: u32_of(r.get(5)?),
                    })
                })?,
                pinned: u32_of(pinned.first().copied().unwrap_or(0)),
            })
        })
    }

    /// The named rows, in the order named; unknown and held rows left out.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn get(&mut self, ids: &[String]) -> Result<Vec<RowOut>> {
        self.read(|conn, faults, _| {
            let got = rows(conn, faults, &sql::get(ids), |r| {
                Ok(RowOut {
                    id: r.get(0)?,
                    row_json: r.get(1)?,
                })
            })?;
            let mut out = Vec::with_capacity(got.len());
            for id in ids {
                if let Some(r) = got.iter().find(|r| &r.id == id)
                    && !out.iter().any(|o: &RowOut| &o.id == id)
                {
                    out.push(r.clone());
                }
            }
            Ok(out)
        })
    }

    /// The rows bound to a Visualizer id, tombstones included, held rows not,
    /// by `rid`. Indexed.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn by_remote_id(&mut self, visualizer_id: &str) -> Result<Vec<RowOut>> {
        self.by_column("visualizer_id", visualizer_id)
    }

    /// The rows bound to a Decent id, as [`Store::by_remote_id`].
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn by_decent_id(&mut self, decent_id: &str) -> Result<Vec<RowOut>> {
        self.by_column("decent_id", decent_id)
    }

    fn by_column(&mut self, col: &'static str, value: &str) -> Result<Vec<RowOut>> {
        let stmt = Stmt {
            sql: format!(
                "SELECT id, row_json FROM shot WHERE {col} = ?1 AND hold_delete = 0 ORDER BY rid"
            ),
            params: vec![Param::Text(value.to_owned())],
        };
        self.read(|conn, faults, _| {
            rows(conn, faults, &stmt, |r| {
                Ok(RowOut {
                    id: r.get(0)?,
                    row_json: r.get(1)?,
                })
            })
        })
    }

    /// The doses of a bag's newest `limit` listed rows: the stock estimate.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn doses_for_bag(&mut self, bean_id: &str, limit: u32) -> Result<Vec<f64>> {
        self.read(|conn, faults, _| {
            rows(conn, faults, &sql::doses_for_bag(bean_id, limit), |r| {
                r.get(0)
            })
        })
    }

    /// The newest grind per grinder on a bag (#488): for each grinder the
    /// bag's listed rows name, the grind of its newest row that recorded one,
    /// newest first. A row stamped with a grinder id counts for that id; a
    /// row from before grinder ids counts for its grinder name (matched
    /// ignoring case and surrounding spaces, [`de1_domain::same_grinder`]),
    /// for the core to match by name ([`de1_domain::newest_shot_grind`]). A
    /// grind is the row's text as stored, or an Android row's number written
    /// without a trailing `.0`; a blank one doesn't count. Values are never
    /// clamped or rewritten otherwise.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn last_grinds_for_bag(&mut self, bean_id: &str) -> Result<Vec<LastGrind>> {
        let found = self.read(|conn, faults, _| {
            rows(conn, faults, &sql::grinds_for_bag(bean_id), |r| {
                Ok((
                    r.get::<_, Option<String>>(0).ok().flatten(),
                    grind_text(r.get_ref(1)?),
                    r.get::<_, i64>(2)?,
                    r.get::<_, Option<String>>(3).ok().flatten(),
                ))
            })
        })?;
        let mut out: Vec<LastGrind> = Vec::new();
        for (grinder, grind, completed_at, grinder_id) in found {
            let Some(grind) = grind else {
                continue;
            };
            let grinder = grinder.unwrap_or_default();
            let grinder_id = grinder_id.filter(|id| !id.trim().is_empty());
            let seen = match grinder_id.as_deref() {
                Some(id) => out.iter().any(|l| l.grinder_id.as_deref() == Some(id)),
                None => {
                    de1_domain::grinder_key(&grinder).is_empty()
                        || out.iter().any(|l| {
                            l.grinder_id.is_none() && de1_domain::same_grinder(&l.grinder, &grinder)
                        })
                }
            };
            if seen {
                continue;
            }
            out.push(LastGrind {
                grinder_id,
                grinder,
                grind,
                completed_at,
            });
        }
        Ok(out)
    }

    /// The upload backlog for `dest`, by `rid` after `after_rid`: live, not
    /// held, not a Brew Log row (which never syncs), and not yet bound there.
    /// The shell's own upload rules (held uploads, exclusions) apply on top.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn unsent(&mut self, dest: Destination, after_rid: i64, limit: u32) -> Result<Vec<RidRow>> {
        let col = match dest {
            Destination::Visualizer => "visualizer_id",
            Destination::Decent => "decent_id",
        };
        let stmt = Stmt {
            sql: format!(
                "SELECT rid, id, row_json FROM shot WHERE {col} IS NULL AND deleted_at IS NULL \
                 AND hold_delete = 0 AND is_brew_log = 0 AND rid > ?1 ORDER BY rid LIMIT ?2"
            ),
            params: vec![Param::Int(after_rid), Param::Int(i64::from(limit))],
        };
        self.read(|conn, faults, _| {
            rows(conn, faults, &stmt, |r| {
                Ok(RidRow {
                    rid: r.get(0)?,
                    id: r.get(1)?,
                    row_json: r.get(2)?,
                })
            })
        })
    }

    /// A page of the sync projection by `rid` after `after_rid`, with the
    /// `write_seq` it was read at. The shell reads every page and starts
    /// again if the seq moved between the first and the last (S3).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn sync_rows(&mut self, after_rid: i64, limit: u32) -> Result<SyncPage> {
        let stmt = Stmt {
            sql: format!("SELECT {SYNC_COLUMNS} FROM shot WHERE rid > ?1 ORDER BY rid LIMIT ?2"),
            params: vec![Param::Int(after_rid), Param::Int(i64::from(limit))],
        };
        self.read(|conn, faults, _| {
            Ok(SyncPage {
                seq: seq_in(conn)?,
                rows: rows(conn, faults, &stmt, sync_row)?,
            })
        })
    }

    /// The whole sync projection in one read transaction (S3): what the
    /// owner sends after three moving tries, and what Android reads.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn sync_projection(&mut self) -> Result<SyncPage> {
        let stmt = Stmt {
            sql: format!("SELECT {SYNC_COLUMNS} FROM shot ORDER BY rid"),
            params: Vec::new(),
        };
        self.read(|conn, faults, _| {
            Ok(SyncPage {
                seq: seq_in(conn)?,
                rows: rows(conn, faults, &stmt, sync_row)?,
            })
        })
    }

    /// Every id `q` lists, in its order, through the page statement with no
    /// limit. For tests of the text paths (`faults` feature); the paged
    /// queries are the shells' way in.
    ///
    /// # Errors
    /// A SQLite error class.
    #[cfg(feature = "faults")]
    pub fn listed_ids(&mut self, q: &HistoryQuery) -> Result<Vec<String>> {
        self.read(|conn, faults, path| {
            let page = sql::page(q, sql::Seek::Start, u32::MAX, path)
                .map_err(|e| StoreError::Refused(e.to_string()))?;
            rows(conn, faults, &page.stmt, |r| r.get(0))
        })
    }
}

//! Running the query builders of [`crate::sql`] (SPEC §7). Every search runs
//! on the path [`Store::text_path`] gives; a failure on the index drops it
//! and the query runs again on the scan path (M6), so a search on a damaged
//! index answers, with health `reindexing`, and never gives `Corrupt`.

use rusqlite::types::Value as Sql;
use rusqlite::{Connection, Row, params_from_iter};

use super::error::{Fail, Result, Source, StoreError};
use super::faults::Faults;
use super::{Settled, Store};
use crate::query::HistoryQuery;
use crate::sql::{self, Param, Stmt, TextPath};

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

impl Store {
    /// Runs a read on the current text path. When it fails on the search
    /// index, the index is dropped and the read runs once more, by scan.
    pub(crate) fn read<T>(
        &mut self,
        mut body: impl FnMut(&Connection, &Faults, TextPath) -> std::result::Result<T, Fail>,
    ) -> Result<T> {
        self.usable()?;
        let mut retried = false;
        loop {
            let path = self.text_path();
            match body(&self.conn, &self.faults, path) {
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
            Ok(u32::try_from(n.first().copied().unwrap_or(0)).unwrap_or(u32::MAX))
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

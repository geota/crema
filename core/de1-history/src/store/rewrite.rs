//! The derive rewrite (SPEC §4): when [`DERIVE_V`] moves, every row's derived
//! columns are rewritten from its `row_json`, in batches by `rid`, each its
//! own transaction, in the background. Resumable: a row's own `derive_v`
//! says whether it is done, so a rewrite cut short picks up where it was at
//! the next open.
//!
//! It writes the derived columns and `changed_seq` only. `row_json`,
//! `created_seq` and `hold_delete` are left alone: a hold is state, and a
//! rewrite that cleared it would bring a deleted row back (S6). The search
//! text moves through `UPDATE OF search_text`, so the triggers keep the
//! index in step as it goes.

use rusqlite::types::Value as Sql;
use rusqlite::{Connection, OptionalExtension, params_from_iter};

use super::error::{Result, StoreError};
use super::fts::Maintained;
use super::write::{UPDATE_DERIVED, derived_values, stored_derived, with_sorted_tags, write_tags};
use super::{Store, change_set};
use crate::change::ChangeKinds;
use crate::derive::{DERIVE_V, derive_row};

/// Where the rewrite is.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) struct State {
    /// Rows with other rules' columns may remain.
    pub(crate) pending: bool,
    /// The highest `rid` this session has rewritten.
    cursor: i64,
}

impl State {
    /// Pending when `meta.derive_v` isn't this build's, or any row's isn't.
    pub(crate) fn load(conn: &Connection) -> rusqlite::Result<State> {
        let meta: Option<String> = conn
            .query_row("SELECT value FROM meta WHERE key = 'derive_v'", [], |r| {
                r.get(0)
            })
            .optional()?;
        let stale_row: Option<i64> = conn
            .query_row(
                "SELECT rid FROM shot WHERE derive_v <> ?1 LIMIT 1",
                [DERIVE_V],
                |r| r.get(0),
            )
            .optional()?;
        Ok(State {
            pending: meta.as_deref() != Some(DERIVE_V.to_string().as_str()) || stale_row.is_some(),
            cursor: 0,
        })
    }
}

impl Store {
    /// One rewrite batch: up to the batch size of rows past the cursor whose
    /// `derive_v` isn't this build's. When none are left, `meta.derive_v`
    /// moves and the rewrite is done.
    pub(crate) fn derive_step(&mut self) -> Result<Maintained> {
        let cursor = self.derive.cursor;
        let batch = i64::from(self.derive_batch_rows);
        let ((rows, last, changed, kinds), st) =
            self.write("derive.commit", "derive.commit", |tx, st, f| {
                f.hit("derive.batch")?;
                let todo: Vec<(i64, String, String)> = tx
                    .prepare_cached(
                        "SELECT rid, id, row_json FROM shot WHERE rid > ?1 AND derive_v <> ?2 \
                         ORDER BY rid LIMIT ?3",
                    )?
                    .query_map((cursor, DERIVE_V, batch), |r| {
                        Ok((r.get(0)?, r.get(1)?, r.get(2)?))
                    })?
                    .collect::<rusqlite::Result<_>>()?;
                let Some(&(last, _, _)) = todo.last() else {
                    tx.execute(
                        "INSERT INTO meta (key, value) VALUES ('derive_v', ?1) \
                         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                        [DERIVE_V.to_string()],
                    )?;
                    return Ok((0, None, Vec::new(), ChangeKinds::default()));
                };
                let mut changed = Vec::new();
                let mut kinds = ChangeKinds::default();
                for (rid, id, row_json) in &todo {
                    let Ok(new) = derive_row(row_json) else {
                        // Every stored row derived when it was written; one
                        // that no longer does keeps its columns rather than
                        // stopping the rewrite for good.
                        tx.prepare_cached("UPDATE shot SET derive_v = ?1 WHERE rid = ?2")?
                            .execute((DERIVE_V, rid))?;
                        continue;
                    };
                    let mut old = stored_derived(tx, *rid)?;
                    old.derive_v = new.derive_v;
                    if old == with_sorted_tags(&new) {
                        tx.prepare_cached("UPDATE shot SET derive_v = ?1 WHERE rid = ?2")?
                            .execute((DERIVE_V, rid))?;
                        continue;
                    }
                    let seq = i64::try_from(st.next())
                        .map_err(|_| StoreError::Refused("write_seq overflow".into()))?;
                    let mut params = derived_values(&new);
                    params.push(Sql::Integer(seq));
                    params.push(Sql::Integer(*rid));
                    tx.prepare_cached(&UPDATE_DERIVED)?
                        .execute(params_from_iter(params))?;
                    write_tags(tx, *rid, &new.tags)?;
                    // A changed rule can move anything a list shows.
                    kinds = kinds.union(ChangeKinds::ALL);
                    changed.push(id.clone());
                }
                Ok((
                    u32::try_from(todo.len()).unwrap_or(u32::MAX),
                    Some(last),
                    changed,
                    kinds,
                ))
            })?;
        match last {
            Some(last) => {
                self.derive.cursor = last;
                Ok(Maintained::Derived {
                    rows,
                    change: change_set(&st, changed, Vec::new(), kinds),
                })
            }
            None => {
                self.derive.pending = false;
                self.derive.cursor = 0;
                Ok(Maintained::DeriveDone)
            }
        }
    }

    /// The ids of rows whose stored derived columns or tags differ from
    /// [`derive_row`] of their `row_json` under this build's rules: a stale
    /// index (SPEC §11, verify). `hold_delete` is state and isn't compared.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn derived_mismatches(&self) -> Result<Vec<String>> {
        self.usable()?;
        let run = || -> rusqlite::Result<Vec<String>> {
            let tx = self.conn.unchecked_transaction()?;
            let rows: Vec<(i64, String, String)> = tx
                .prepare("SELECT rid, id, row_json FROM shot ORDER BY rid")?
                .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
                .collect::<rusqlite::Result<_>>()?;
            let mut out = Vec::new();
            for (rid, id, row_json) in rows {
                let stored = stored_derived(&tx, rid)?;
                match derive_row(&row_json) {
                    Ok(d) if with_sorted_tags(&d) == stored => {}
                    _ => out.push(id),
                }
            }
            Ok(out)
        };
        run().map_err(|e| super::error::error_of(&e, super::error::Source::Session))
    }
}

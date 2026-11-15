//! The export walk and restore (SPEC §9, M3, S6).
//!
//! **Export.** [`Store::export_begin`] fixes the walk's set: the `rid`s of
//! the rows a backup takes (live, not tombstoned), in a `TEMP` table on this
//! connection, and gives a token and the count for the header.
//! [`Store::export_next`] returns the next rows of the set in `rid` order with
//! their current `row_json`, each step its own short read, so writes go on
//! between steps. A delete during a walk doesn't remove a row of the set: it
//! sets `hold_delete`, which hides it everywhere, so every `rid` of the set
//! still has a row and the header's count is the lines written. When the walk
//! ends, or at the next open, [`Store::release_holds`] gives the held ids; the
//! shell deletes their details, then calls [`Store::delete`] for them.
//!
//! **Restore** is merge, then remove (M3): [`Store::restore_begin`] gives the
//! `write_seq` the restore starts at; batches upsert with incoming rows
//! winning; then [`Store::ids_created_before`] lists, in pages by `rid`, the
//! rows that existed before the restore began, and the shell deletes those
//! the backup doesn't hold. A row created after the restore began has a later
//! `created_seq`, so it is never in the wipe, and nothing depends on what one
//! connection wrote. No `TEMP` table.

use std::collections::BTreeMap;

use rusqlite::OptionalExtension;

use super::Store;
use super::error::{Result, Source, StoreError, error_of};
use super::read::RowOut;
use super::write::{RowIn, Written};

/// The export walks open on this connection.
#[derive(Debug, Default)]
pub(crate) struct Walks {
    /// A per-open base, so a token from an earlier connection is unknown.
    base: u64,
    /// Tokens handed out so far.
    issued: u64,
    /// Open walks, by token: the last `rid` returned.
    active: BTreeMap<u64, i64>,
}

/// A per-open token base from the clock: 46 bits of it, shifted 16, so the
/// base and every token issued after it (`base + n`, `n` below 2^16 many
/// times over) stay below `i64::MAX`, which is how SQLite stores them
/// (REVIEW-442 N1).
fn token_base(nanos: u128) -> u64 {
    let low = u64::try_from(nanos & ((1u128 << 46) - 1)).unwrap_or(1);
    low.max(1) << 16
}

impl Walks {
    /// Walks for a new connection.
    pub(crate) fn new() -> Walks {
        // Not a secret, only distinct per open: tokens from an earlier
        // connection must not name a walk on this one.
        let nanos = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_or(1, |d| d.as_nanos());
        let base = token_base(nanos);
        Walks {
            base,
            issued: 0,
            active: BTreeMap::new(),
        }
    }

    /// Whether any walk is open: deletes hold instead of removing.
    pub(crate) fn any(&self) -> bool {
        !self.active.is_empty()
    }

    /// Forgets every walk: the connection that held their sets is gone.
    pub(crate) fn clear(&mut self) {
        self.active.clear();
    }
}

/// A begun export walk.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ExportWalk {
    /// Names the walk in every later call.
    pub token: u64,
    /// The rows the walk will return: the backup header's count.
    pub count: u32,
}

/// A row's `rid` and id, for paging by `rid`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RidId {
    /// The row's `rid`: the next page starts after the last one.
    pub rid: i64,
    /// The row's id.
    pub id: String,
}

/// The walk set, on this connection only (M3).
const WALK_SET: &str = "CREATE TEMP TABLE IF NOT EXISTS walk_set (
  rid INTEGER NOT NULL,
  token INTEGER NOT NULL,
  PRIMARY KEY (rid, token)
) WITHOUT ROWID";

impl Store {
    /// Begins an export walk over the live rows (SPEC §9).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn export_begin(&mut self) -> Result<ExportWalk> {
        self.usable()?;
        self.walks.issued += 1;
        let token = self.walks.base + self.walks.issued;
        let token_i =
            i64::try_from(token).map_err(|_| StoreError::Refused("token range".into()))?;
        let run = || -> rusqlite::Result<usize> {
            let tx = self.conn.unchecked_transaction()?;
            tx.execute_batch(WALK_SET)?;
            let n = tx.execute(
                "INSERT INTO temp.walk_set (rid, token) \
                 SELECT rid, ?1 FROM shot WHERE deleted_at IS NULL AND hold_delete = 0",
                [token_i],
            )?;
            tx.commit()?;
            Ok(n)
        };
        let n = run().map_err(|e| error_of(&e, Source::Session))?;
        self.walks.active.insert(token, 0);
        Ok(ExportWalk {
            token,
            count: u32::try_from(n).unwrap_or(u32::MAX),
        })
    }

    /// The walk's next `n` rows, in `rid` order, with their current
    /// `row_json`: an edit since the walk began is in the line, and a row
    /// tombstoned since is written with its tombstone. Empty at the end.
    ///
    /// # Errors
    /// [`StoreError::UnknownWalk`] for a token this connection didn't begin
    /// or that ended; a SQLite error class otherwise.
    pub fn export_next(&mut self, token: u64, n: u32) -> Result<Vec<RowOut>> {
        self.usable()?;
        let Some(&after) = self.walks.active.get(&token) else {
            return Err(StoreError::UnknownWalk(token));
        };
        let token_i = i64::try_from(token).map_err(|_| StoreError::UnknownWalk(token))?;
        let rows: Vec<(i64, RowOut)> = self
            .conn
            .prepare_cached(
                "SELECT s.rid, s.id, s.row_json FROM temp.walk_set w JOIN shot s ON s.rid = w.rid \
                 WHERE w.token = ?1 AND w.rid > ?2 ORDER BY w.rid LIMIT ?3",
            )
            .and_then(|mut st| {
                st.query_map((token_i, after, i64::from(n)), |r| {
                    Ok((
                        r.get(0)?,
                        RowOut {
                            id: r.get(1)?,
                            row_json: r.get(2)?,
                        },
                    ))
                })?
                .collect()
            })
            .map_err(|e| error_of(&e, Source::Session))?;
        if let Some((rid, _)) = rows.last() {
            self.walks.active.insert(token, *rid);
        }
        Ok(rows.into_iter().map(|(_, r)| r).collect())
    }

    /// Ends a walk. Rows held for it may then be released
    /// ([`Store::release_holds`]).
    ///
    /// # Errors
    /// [`StoreError::UnknownWalk`] for a token that isn't open.
    pub fn export_end(&mut self, token: u64) -> Result<()> {
        self.usable()?;
        if self.walks.active.remove(&token).is_none() {
            return Err(StoreError::UnknownWalk(token));
        }
        let token_i = i64::try_from(token).map_err(|_| StoreError::UnknownWalk(token))?;
        self.conn
            .execute("DELETE FROM temp.walk_set WHERE token = ?1", [token_i])
            .map(|_| ())
            .map_err(|e| error_of(&e, Source::Session))
    }

    /// The ids of rows held by a delete during a walk that no open walk still
    /// needs: after a walk ends, and at every open for holds a closed or
    /// crashed session left (S6). The shell deletes their details first, then
    /// calls [`Store::delete`] with them; a crash between leaves a held,
    /// hidden row, released again next time, never details without a row.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn release_holds(&self) -> Result<Vec<String>> {
        self.usable()?;
        let sql = if self.walks.any() {
            "SELECT id FROM shot WHERE hold_delete = 1 \
             AND rid NOT IN (SELECT rid FROM temp.walk_set) ORDER BY rid"
        } else {
            "SELECT id FROM shot WHERE hold_delete = 1 ORDER BY rid"
        };
        self.conn
            .prepare_cached(sql)
            .and_then(|mut st| st.query_map([], |r| r.get(0))?.collect())
            .map_err(|e| error_of(&e, Source::Session))
    }

    /// Begins a restore: the `write_seq` now. Rows created after this are
    /// never in the wipe.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn restore_begin(&self) -> Result<u64> {
        self.usable()?;
        let seq: Option<String> = self
            .conn
            .query_row("SELECT value FROM meta WHERE key = 'write_seq'", [], |r| {
                r.get(0)
            })
            .optional()
            .map_err(|e| error_of(&e, Source::Session))?;
        Ok(seq.and_then(|s| s.parse().ok()).unwrap_or(0))
    }

    /// One restore batch: an upsert by id, the incoming rows winning (P3
    /// spec P3). The same call as [`Store::upsert`], named for the flow.
    ///
    /// # Errors
    /// As [`Store::upsert`].
    pub fn restore_batch(&mut self, rows: &[RowIn]) -> Result<Written> {
        self.upsert(rows)
    }

    /// Up to `limit` rows, by `rid` after `after_rid`, that the store held
    /// when the restore began (`created_seq <= begin_seq`), tombstones and
    /// held rows included: the shell takes the backup's ids away from them,
    /// shows the count, and deletes the rest (M3).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn ids_created_before(
        &self,
        begin_seq: u64,
        after_rid: i64,
        limit: u32,
    ) -> Result<Vec<RidId>> {
        self.usable()?;
        let begin = i64::try_from(begin_seq).unwrap_or(i64::MAX);
        self.conn
            .prepare_cached(
                "SELECT rid, id FROM shot WHERE created_seq <= ?1 AND rid > ?2 ORDER BY rid LIMIT ?3",
            )
            .and_then(|mut st| {
                st.query_map((begin, after_rid, i64::from(limit)), |r| {
                    Ok(RidId {
                        rid: r.get(0)?,
                        id: r.get(1)?,
                    })
                })?
                .collect()
            })
            .map_err(|e| error_of(&e, Source::Session))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_token_fits_sqlites_integer_whatever_the_clock_says() {
        for nanos in [
            0u128,
            1,
            u128::MAX,
            (1u128 << 46) - 1,
            1u128 << 47,
            0xffff_ffff_ffff,
            // 2026-11-16 02:33 PST, when bit 47 of the old base turned on.
            1_794_825_180_000_000_000,
        ] {
            let base = token_base(nanos);
            assert!(base > 0);
            // A million walks on one connection still fit.
            assert!(i64::try_from(base + 1_000_000).is_ok(), "{nanos}");
        }
    }
}

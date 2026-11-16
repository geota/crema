//! The export walk and restore (SPEC §9, M3, S6).
//!
//! **Export.** [`Store::export_begin`] fixes the walk's set: the `rid`s of
//! the rows a backup takes (live, not tombstoned), in a `TEMP` table on this
//! connection, and gives a token and the count for the header. It fixes the
//! order too: newest first, ties by id, as the P3 stores list their rows, so
//! a backup of the same history is the same bytes from either store (R1a).
//! [`Store::export_next`] returns the next rows of the set in that order with
//! their current `row_json`, each step its own short read, so writes go on
//! between steps; an edit that moves a row's date doesn't move it in the walk.
//! [`Store::export_rewind`] starts the same set again, for a shell that reads
//! it twice (Android counts the lines it can write before the header). A delete during a walk doesn't remove a row of the set: it
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
    /// Open walks, by token: the last place returned.
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

/// Opens so far in this process, so two opens in one millisecond still get
/// distinct bases on wasm32.
static OPENS: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// The clock in ns, for the token base. Natively the system clock. wasm32
/// has none in `std` (`SystemTime::now` panics there), so it is the open's
/// `now_ms`, which the shell sets from `Date.now()`, plus a count of opens.
fn clock_nanos(now_ms: i64) -> u128 {
    let opens = u128::from(OPENS.fetch_add(1, std::sync::atomic::Ordering::Relaxed));
    if cfg!(all(target_family = "wasm", target_os = "unknown")) {
        let ms = u128::try_from(now_ms).unwrap_or(0);
        return ms * 1_000_000 + (opens % 1_000) * 1_000;
    }
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(1 + opens, |d| d.as_nanos())
}

impl Walks {
    /// Walks for a new connection, opened at `now_ms`.
    pub(crate) fn new(now_ms: i64) -> Walks {
        // Not a secret, only distinct per open: tokens from an earlier
        // connection must not name a walk on this one.
        let base = token_base(clock_nanos(now_ms));
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

/// The walk set, on this connection only (M3): each row's place in the walk,
/// fixed at its start, and its `rid`, which a delete checks.
const WALK_SET: &str = "CREATE TEMP TABLE IF NOT EXISTS walk_set (
  token INTEGER NOT NULL,
  pos INTEGER NOT NULL,
  rid INTEGER NOT NULL,
  PRIMARY KEY (token, pos)
) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS temp.walk_set_rid ON walk_set(rid)";

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
                "INSERT INTO temp.walk_set (token, pos, rid) \
                 SELECT ?1, ROW_NUMBER() OVER (ORDER BY completed_at DESC, id DESC), rid \
                 FROM shot WHERE deleted_at IS NULL AND hold_delete = 0",
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

    /// The walk's next `n` rows, newest first as the walk began, with their
    /// current `row_json`: an edit since the walk began is in the line, and a
    /// row tombstoned since is written with its tombstone. Empty at the end.
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
                "SELECT w.pos, s.id, s.row_json FROM temp.walk_set w JOIN shot s ON s.rid = w.rid \
                 WHERE w.token = ?1 AND w.pos > ?2 ORDER BY w.pos LIMIT ?3",
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
        if let Some((pos, _)) = rows.last() {
            self.walks.active.insert(token, *pos);
        }
        Ok(rows.into_iter().map(|(_, r)| r).collect())
    }

    /// Starts the walk's set again from its first row: the same rows in the
    /// same order, each read as it is now. The holds stay.
    ///
    /// # Errors
    /// [`StoreError::UnknownWalk`] for a token that isn't open.
    pub fn export_rewind(&mut self, token: u64) -> Result<()> {
        self.usable()?;
        match self.walks.active.get_mut(&token) {
            Some(at) => {
                *at = 0;
                Ok(())
            }
            None => Err(StoreError::UnknownWalk(token)),
        }
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
    /// spec P3). [`Store::upsert`] with every row's `pulled` forced off.
    ///
    /// # Errors
    /// As [`Store::upsert`].
    pub fn restore_batch(&mut self, rows: &[RowIn]) -> Result<Written> {
        // A backup row is never a pulled duplicate: skipping it would leave
        // the wipe to remove the local row too (REVIEW-442 note 5).
        let rows: Vec<RowIn> = rows
            .iter()
            .map(|r| RowIn {
                pulled: false,
                ..r.clone()
            })
            .collect();
        self.upsert(&rows)
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

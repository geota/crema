//! Stage 1's index upkeep (SPEC §3, §11 stage 1, M4): `history-index.db`,
//! derived from the P3 store, which stays the truth.
//!
//! - [`Store::diff_batch`] and [`Store::index_write`] bring rows in; an
//!   unchanged `row_json` writes nothing.
//! - [`Store::remove_absent`] is the full diff's last step, and **the only
//!   place a file deletes by absence**: allowed because the index is derived
//!   (rule 4), and refused on any file whose role is `authority`.
//! - Web keeps `source_seq` (every P3 `writeSeq` at or below it applied) and
//!   `seen_seqs` (those applied above it); `source_seq` moves only across
//!   seqs it has seen. Android keeps `pending`, raised before a P3a write and
//!   lowered with the index write, and `diff_version_code`.
//!
//! Every call here is refused unless the file's role is `index`.

use rusqlite::{OptionalExtension, Transaction};
use serde::{Deserialize, Serialize};

use super::error::{Fail, Result, StoreError};
use super::write::{Outcome, RowIn, Written, delete_in, upsert_in};
use super::{Role, Store};
use crate::change::ChangeSet;

/// One write into the index: rows in, rows out, the P3 seqs it applies
/// (web), and whether it lowers `pending` (Android), all in one transaction.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct IndexWrite {
    /// Rows to bring in, read from the P3 store.
    pub upsert: Vec<RowIn>,
    /// Rows the P3 store deleted, by name.
    pub delete: Vec<String>,
    /// The P3 `writeSeq`s this write applies.
    pub seen_seqs: Vec<u64>,
    /// Lower `pending` by one (the Android hook's second half).
    pub lower_pending: bool,
}

/// The index's bookkeeping, for the boot check (SPEC §3).
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IndexState {
    /// Every P3 `writeSeq` at or below this one is applied.
    pub source_seq: u64,
    /// The P3 seqs applied above `source_seq`, ascending.
    pub seen_seqs: Vec<u64>,
    /// P3a writes begun and not yet mirrored here.
    pub pending: u64,
    /// The `versionCode` that last ran a full diff.
    pub diff_version_code: Option<i64>,
}

fn meta_get(tx: &rusqlite::Connection, key: &str) -> rusqlite::Result<Option<String>> {
    tx.query_row("SELECT value FROM meta WHERE key = ?1", [key], |r| r.get(0))
        .optional()
}

fn meta_set(tx: &Transaction<'_>, key: &str, value: &str) -> rusqlite::Result<()> {
    tx.execute(
        "INSERT INTO meta (key, value) VALUES (?1, ?2) \
         ON CONFLICT(key) DO UPDATE SET value = excluded.value",
        (key, value),
    )
    .map(|_| ())
}

fn state_in(conn: &rusqlite::Connection) -> rusqlite::Result<IndexState> {
    let num = |k: &str| -> rusqlite::Result<u64> {
        Ok(meta_get(conn, k)?.and_then(|v| v.parse().ok()).unwrap_or(0))
    };
    Ok(IndexState {
        source_seq: num("source_seq")?,
        seen_seqs: meta_get(conn, "seen_seqs")?
            .and_then(|v| serde_json::from_str(&v).ok())
            .unwrap_or_default(),
        pending: num("pending")?,
        diff_version_code: meta_get(conn, "diff_version_code")?.and_then(|v| v.parse().ok()),
    })
}

/// Applies `seqs` to `(source_seq, seen_seqs)`: seen above, then
/// `source_seq` forward across every seq seen next to it.
fn advance(state: &mut IndexState, seqs: &[u64]) {
    for &s in seqs {
        if s > state.source_seq && !state.seen_seqs.contains(&s) {
            state.seen_seqs.push(s);
        }
    }
    state.seen_seqs.sort_unstable();
    while state.seen_seqs.first() == Some(&(state.source_seq + 1)) {
        state.seen_seqs.remove(0);
        state.source_seq += 1;
    }
}

fn save_seqs(tx: &Transaction<'_>, state: &IndexState) -> rusqlite::Result<()> {
    meta_set(tx, "source_seq", &state.source_seq.to_string())?;
    meta_set(
        tx,
        "seen_seqs",
        &serde_json::to_string(&state.seen_seqs).unwrap_or_else(|_| "[]".into()),
    )
}

impl Store {
    /// Refuses unless this file is an index (SPEC §11 stage 1 step 1): an
    /// `authority` file is the truth, and deleting by absence there would
    /// lose rows.
    fn index_only(&self, what: &str) -> Result<()> {
        if self.info.role == Role::Index {
            Ok(())
        } else {
            Err(StoreError::Refused(format!(
                "{what} on a file whose role is {}",
                self.info.role.as_str()
            )))
        }
    }

    /// The full diff's batch: rows read from the P3 store, upserted where
    /// their `row_json` differs.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file; as [`Store::upsert`].
    pub fn diff_batch(&mut self, rows: &[RowIn]) -> Result<Written> {
        self.index_write(&IndexWrite {
            upsert: rows.to_vec(),
            ..IndexWrite::default()
        })
    }

    /// One write into the index, in one transaction: rows in, named rows
    /// out, the P3 seqs it applies, and `pending` lowered.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file; as [`Store::upsert`].
    pub fn index_write(&mut self, w: &IndexWrite) -> Result<Written> {
        self.index_only("an index write")?;
        let walking = self.walks.any();
        let (out, st) = self.write("upsert.commit", "upsert.committed", |tx, st, f| {
            let mut out = upsert_in(tx, st, f, &w.upsert)?;
            let del = delete_in(tx, st, f, &w.delete, walking)?;
            out.deleted = del.deleted;
            out.kinds = out.kinds.union(del.kinds);
            if !w.seen_seqs.is_empty() {
                let mut state = state_in(tx)?;
                advance(&mut state, &w.seen_seqs);
                save_seqs(tx, &state)?;
            }
            if w.lower_pending {
                let p = state_in(tx)?.pending.saturating_sub(1);
                meta_set(tx, "pending", &p.to_string())?;
            }
            Ok::<Outcome, Fail>(out)
        })?;
        Ok(out.written(&st))
    }

    /// The full diff's last step (SPEC §11 stage 1 step 3): removes the rows
    /// whose ids aren't in `ids` (every id the P3 store holds, read in one
    /// transaction), except rows the index wrote after `since_seq`, its own
    /// `write_seq` when the diff began. The only delete by absence, and
    /// only on an index.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file; a SQLite error class.
    pub fn remove_absent(&mut self, ids: &[String], since_seq: u64) -> Result<ChangeSet> {
        self.index_only("remove_absent")?;
        let since = i64::try_from(since_seq).unwrap_or(i64::MAX);
        let held = serde_json::to_string(ids).map_err(|e| StoreError::Refused(e.to_string()))?;
        let walking = self.walks.any();
        let (out, st) = self.write("delete.commit", "delete.committed", |tx, st, f| {
            let absent: Vec<String> = tx
                .prepare(
                    "SELECT id FROM shot WHERE changed_seq <= ?1 \
                     AND id NOT IN (SELECT value FROM json_each(?2)) ORDER BY rid",
                )?
                .query_map((since, &held), |r| r.get(0))?
                .collect::<rusqlite::Result<_>>()?;
            delete_in(tx, st, f, &absent, walking)
        })?;
        Ok(out.written(&st).change)
    }

    /// After a full diff: every P3 seq up to `seq`, the `writeSeq` read
    /// before its first batch, is applied.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file; a SQLite error class.
    pub fn set_source_seq(&mut self, seq: u64) -> Result<()> {
        self.index_only("set_source_seq")?;
        self.write("upsert.commit", "upsert.committed", |tx, _, _| {
            let mut state = state_in(tx)?;
            state.source_seq = seq;
            state.seen_seqs.retain(|s| *s > seq);
            advance(&mut state, &[]);
            save_seqs(tx, &state)?;
            Ok(())
        })
        .map(|_| ())
    }

    /// The Android hook's first half: `pending` up by one, in its own
    /// transaction, before P3a writes `shots.db`. Returns the new count.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file; a SQLite error class.
    pub fn bump_pending(&mut self) -> Result<u64> {
        self.index_only("bump_pending")?;
        let (p, _) = self.write("upsert.commit", "upsert.committed", |tx, _, _| {
            let p = state_in(tx)?.pending + 1;
            meta_set(tx, "pending", &p.to_string())?;
            Ok(p)
        })?;
        Ok(p)
    }

    /// After a full diff on Android: `pending` back to 0 and the
    /// `versionCode` that ran it.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file; a SQLite error class.
    pub fn finish_android_diff(&mut self, version_code: i64) -> Result<()> {
        self.index_only("finish_android_diff")?;
        self.write("upsert.commit", "upsert.committed", |tx, _, _| {
            meta_set(tx, "pending", "0")?;
            meta_set(tx, "diff_version_code", &version_code.to_string())?;
            Ok(())
        })
        .map(|_| ())
    }

    /// The index's bookkeeping.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file; a SQLite error class.
    pub fn index_state(&self) -> Result<IndexState> {
        self.usable()?;
        self.index_only("index_state")?;
        state_in(&self.conn).map_err(|e| super::error::error_of(&e, super::error::Source::Session))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn source_seq_moves_only_across_seen_seqs() {
        let mut s = IndexState::default();
        advance(&mut s, &[2, 3]);
        assert_eq!((s.source_seq, s.seen_seqs.clone()), (0, vec![2, 3]));
        advance(&mut s, &[1]);
        assert_eq!((s.source_seq, s.seen_seqs.clone()), (3, vec![]));
        advance(&mut s, &[3, 5, 5, 7]);
        assert_eq!((s.source_seq, s.seen_seqs.clone()), (3, vec![5, 7]));
        advance(&mut s, &[4, 6]);
        assert_eq!((s.source_seq, s.seen_seqs), (7, vec![]));
    }
}

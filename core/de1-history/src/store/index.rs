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

use std::collections::HashSet;

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

/// The rows a removal-by-absence step deletes at most (S15, B1: 500 deletes
/// took about 21 ms natively at 100,000 rows).
pub const ABSENT_BATCH_ROWS: usize = 500;

/// The rows a removal-by-absence step reads at most, by `rid`, to find the
/// absent ones: a range read, a few ms at 100,000 rows.
const ABSENT_SCAN_ROWS: i64 = 5_000;

/// A removal by absence under way ([`Store::remove_absent_begin`]): the ids
/// the P3 store holds, with every id written since the removal began, the
/// index's `write_seq` when the diff began, and the last `rid` examined. In
/// memory only: a new `Store`, or a reopen after a mid-session
/// `SQLITE_CORRUPT`, forgets it, and the next step is refused, so the diff
/// runs again.
#[derive(Debug, Clone)]
pub(crate) struct AbsentScan {
    present: HashSet<String>,
    since: i64,
    after_rid: i64,
}

/// A removal by absence that failed part way ([`Store::remove_absent`]): the
/// error, and what the steps before it deleted, which stay deleted. The shell
/// posts `partial` as the change notice, so open lists drop those rows.
#[derive(Debug, Clone, PartialEq)]
pub struct AbsentError {
    /// The failed step's error.
    pub error: StoreError,
    /// The committed steps' change set.
    pub partial: ChangeSet,
}

impl From<AbsentError> for StoreError {
    fn from(e: AbsentError) -> StoreError {
        e.error
    }
}

/// One step of a removal by absence ([`Store::remove_absent_next`]).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct AbsentStep {
    /// What the step deleted.
    pub change: ChangeSet,
    /// Every row has been examined: the removal is finished.
    pub done: bool,
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
    while let Some(next) = state.source_seq.checked_add(1) {
        if state.seen_seqs.first() != Some(&next) {
            break;
        }
        state.seen_seqs.remove(0);
        state.source_seq = next;
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
        self.note_written(&w.upsert);
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
    /// It runs [`Store::remove_absent_begin`] and every
    /// [`Store::remove_absent_next`] step, one transaction each, and returns
    /// their change sets as one, at the last step's `seq`. A caller with others
    /// to serve calls the steps itself and yields between them (S15).
    ///
    /// # Errors
    /// [`AbsentError`]: [`StoreError::Refused`] on an `authority` file; a
    /// SQLite error class. A failed step leaves the steps before it
    /// committed, and the error carries their change set: the index is
    /// derived, and the next full diff finishes the job.
    pub fn remove_absent(
        &mut self,
        ids: &[String],
        since_seq: u64,
    ) -> std::result::Result<ChangeSet, AbsentError> {
        let mut all = ChangeSet {
            seq: self.write_seq,
            ..ChangeSet::default()
        };
        if let Err(error) = self.remove_absent_begin(ids, since_seq) {
            return Err(AbsentError {
                error,
                partial: all,
            });
        }
        loop {
            let step = match self.remove_absent_next() {
                Ok(step) => step,
                Err(error) => {
                    return Err(AbsentError {
                        error,
                        partial: all,
                    });
                }
            };
            all.seq = step.change.seq;
            all.deleted.extend(step.change.deleted);
            all.upserted.extend(step.change.upserted);
            all.kinds = all.kinds.union(step.change.kinds);
            if step.done {
                return Ok(all);
            }
        }
    }

    /// Begins a removal by absence: `ids` is every id the P3 store holds,
    /// read in one transaction, and `since_seq` the index's `write_seq` when
    /// the diff began, taken as at most this store's `write_seq` now. Nothing
    /// is written. A removal already under way is replaced. Until it ends,
    /// every id a write passes in ([`Store::upsert`], [`Store::restore_batch`],
    /// [`Store::apply`], [`Store::index_write`]) joins the held ids, written
    /// or unchanged, so a row put back between steps is never removed
    /// (REVIEW-448 note 1).
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file.
    pub fn remove_absent_begin(&mut self, ids: &[String], since_seq: u64) -> Result<()> {
        self.usable()?;
        self.index_only("remove_absent")?;
        // A `since` above what this file has written would let a row
        // changed between steps pass for one from before the diff
        // (REVIEW-448 note 2).
        let since = since_seq.min(self.write_seq);
        self.absent = Some(AbsentScan {
            present: ids.iter().cloned().collect(),
            since: i64::try_from(since).unwrap_or(i64::MAX),
            after_rid: 0,
        });
        Ok(())
    }

    /// Adds the ids of `rows` to a removal under way: a row written between
    /// its steps is held, whether the write changed it or not.
    pub(crate) fn note_written(&mut self, rows: &[RowIn]) {
        let Some(scan) = self.absent.as_mut() else {
            return;
        };
        for r in rows {
            let id = serde_json::from_str::<serde_json::Value>(&r.row_json)
                .ok()
                .and_then(|v| crate::derive::row_id(&v).map(str::to_owned));
            if let Some(id) = id {
                scan.present.insert(id);
            }
        }
    }

    /// One step of the removal, in one transaction: reads up to 5,000 rows by
    /// `rid` past the last step, and deletes those whose id isn't held and
    /// that the index hasn't written since the diff began, at most
    /// [`ABSENT_BATCH_ROWS`] of them. Both tests run in the step's own write
    /// transaction, so a row written after the diff began is never deleted.
    /// `done` when every row has been examined; the removal then ends.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file, or with no removal
    /// under way (none begun, finished, or forgotten by a new `Store` or a
    /// reopen): the caller
    /// runs the diff again. A SQLite error class otherwise, with the step
    /// rolled back and the removal where it was.
    pub fn remove_absent_next(&mut self) -> Result<AbsentStep> {
        self.index_only("remove_absent")?;
        let Some(scan) = self.absent.take() else {
            return Err(StoreError::Refused(
                "no removal by absence under way".into(),
            ));
        };
        let walking = self.walks.any();
        let run = self.write("delete.commit", "delete.committed", |tx, st, f| {
            let rows: Vec<(i64, String, i64)> = tx
                .prepare_cached(
                    "SELECT rid, id, changed_seq FROM shot WHERE rid > ?1 ORDER BY rid LIMIT ?2",
                )?
                .query_map((scan.after_rid, ABSENT_SCAN_ROWS), |r| {
                    Ok((r.get(0)?, r.get(1)?, r.get(2)?))
                })?
                .collect::<rusqlite::Result<_>>()?;
            let mut done = i64::try_from(rows.len()).unwrap_or(i64::MAX) < ABSENT_SCAN_ROWS;
            let mut last = scan.after_rid;
            let mut absent = Vec::new();
            for (rid, id, changed_seq) in rows {
                if absent.len() == ABSENT_BATCH_ROWS {
                    done = false;
                    break;
                }
                last = rid;
                if changed_seq <= scan.since && !scan.present.contains(&id) {
                    absent.push(id);
                }
            }
            let out = delete_in(tx, st, f, &absent, walking)?;
            Ok::<_, Fail>((out, last, done))
        });
        match run {
            Ok(((out, last, done), st)) => {
                if !done {
                    self.absent = Some(AbsentScan {
                        after_rid: last,
                        ..scan
                    });
                }
                Ok(AbsentStep {
                    change: out.written(&st).change,
                    done,
                })
            }
            Err(e) => {
                self.absent = Some(scan);
                Err(e)
            }
        }
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

    /// Restarts the seqs at `seq` with none seen past a gap: the start of a
    /// full diff on Android, under the shell's lock, where `seq` is the
    /// `index_seq` `shots.db` has then (REVIEW-450 B). A seen seq from an
    /// older file, or from an index copied later than its `shots.db`, must
    /// not stand in for one this file makes during the diff.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file; a SQLite error class.
    pub fn reset_seqs(&mut self, seq: u64) -> Result<()> {
        self.index_only("reset_seqs")?;
        self.write("upsert.commit", "upsert.committed", |tx, _, _| {
            save_seqs(
                tx,
                &IndexState {
                    source_seq: seq,
                    ..IndexState::default()
                },
            )?;
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

    /// Every row the index holds, tombstones included: the web boot check
    /// compares it with the P3 store's row count (SPEC §3).
    ///
    /// # Errors
    /// [`StoreError::Refused`] on an `authority` file; a SQLite error class.
    pub fn row_count(&self) -> Result<u64> {
        self.usable()?;
        self.index_only("row_count")?;
        self.conn
            .query_row("SELECT COUNT(*) FROM shot", [], |r| r.get::<_, i64>(0))
            .map(|n| u64::try_from(n).unwrap_or(0))
            .map_err(|e| super::error::error_of(&e, super::error::Source::Session))
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
    fn advance_stops_at_the_top_without_overflow() {
        let mut s = IndexState {
            source_seq: u64::MAX - 1,
            seen_seqs: vec![u64::MAX],
            ..IndexState::default()
        };
        advance(&mut s, &[]);
        assert_eq!((s.source_seq, s.seen_seqs.clone()), (u64::MAX, vec![]));
        advance(&mut s, &[3]);
        assert_eq!(s.source_seq, u64::MAX);
    }

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

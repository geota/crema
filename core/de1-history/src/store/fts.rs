//! The search index's upkeep (SPEC §5, M6, S15).
//!
//! - **Merge:** incremental `'merge'` steps, each its own transaction, never
//!   `'optimize'`.
//! - **Rebuild:** a new table, `shot_fts_next`, built in batches by `rid`,
//!   each its own short transaction. Its triggers keep it in step for rows at
//!   or below the build's cursor (`meta.fts_build_rid`); the batches add the
//!   rest. At the end one transaction drops the old table and its triggers,
//!   renames the new one `shot_fts` and puts the triggers on it. Never
//!   `'rebuild'` as one statement: at 100,000 rows it would hold web's only
//!   connection past the 5 s timeout.
//! - **Damage:** a failure on the index drops it and its triggers in one
//!   short transaction (derived data, rule 4), so writes and lists work at
//!   once; searches run on the scan path, health says `reindexing`, and
//!   maintenance builds a new table. Never `lost`.
//! - **Integrity check:** `'integrity-check'` with `rank = 1`, the only form
//!   that compares an external-content index with its table.

use rusqlite::{Connection, OptionalExtension, Transaction, TransactionBehavior};

use super::Store;
use super::error::{Result, Source, StoreError, error_of, on_fts};
use crate::change::ChangeSet;
use crate::schema::{DROP_FTS, DROP_FTS_NEXT, FTS_NEXT_V1, FTS_TRIGGERS_V1};

/// The search index's state.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FtsStatus {
    /// `shot_fts` is whole and in step: searches use it.
    Ready,
    /// A rebuild is under way; `shot_fts_next` holds every row up to
    /// `cursor`. Searches run on the scan path.
    Building {
        /// The highest `rid` the new table holds.
        cursor: i64,
    },
    /// No index: dropped for damage, or a build not started. Searches run on
    /// the scan path; maintenance starts a build.
    Missing,
}

/// What an integrity check found.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FtsCheck {
    /// The index matches its table.
    Ok,
    /// It didn't: it was dropped, and a rebuild is due.
    Damaged,
    /// There is no whole index to check (building or missing).
    NotReady,
}

/// What one maintenance step did.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Maintained {
    /// Nothing to do.
    Idle,
    /// A shot is running ([`Store::set_busy`]): nothing done.
    Busy,
    /// A derive rewrite batch: this many rows read, and the change.
    Derived {
        /// Rows read in the batch.
        rows: u32,
        /// What lists see of it.
        change: ChangeSet,
    },
    /// The derive rewrite finished: every row is at the current rules.
    DeriveDone,
    /// A search index rebuild started.
    FtsStarted,
    /// A rebuild batch: this many rows added to the new table.
    FtsBatch {
        /// Rows added.
        rows: u32,
    },
    /// The new table replaced the old one: searches use the index again.
    FtsSwapped,
    /// One merge step; `more` while it still moved pages.
    Merged {
        /// Another step may help.
        more: bool,
    },
}

fn exists(conn: &Connection, name: &str) -> rusqlite::Result<bool> {
    conn.query_row(
        "SELECT 1 FROM sqlite_schema WHERE name = ?1",
        [name],
        |_| Ok(()),
    )
    .optional()
    .map(|o| o.is_some())
}

/// The index's state, from the schema and `meta`.
pub(crate) fn load(conn: &Connection) -> rusqlite::Result<FtsStatus> {
    let next = exists(conn, "shot_fts_next")?;
    let cursor: Option<i64> = conn
        .query_row(
            "SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'fts_build_rid'",
            [],
            |r| r.get(0),
        )
        .optional()?;
    Ok(match (next, cursor) {
        (true, Some(cursor)) => FtsStatus::Building { cursor },
        // A new table without its cursor can't be trusted: start again.
        (true, None) => FtsStatus::Missing,
        (false, _) if exists(conn, "shot_fts")? => FtsStatus::Ready,
        (false, _) => FtsStatus::Missing,
    })
}

impl Store {
    /// The search index's state.
    #[must_use]
    pub fn fts_status(&self) -> FtsStatus {
        self.fts
    }

    /// Drops the damaged index and its triggers in one transaction (M6), so
    /// writes and lists work at once; searches go to the scan path. On a file
    /// this build may not write, the index is only set aside in memory.
    pub(crate) fn on_fts_damage(&mut self) -> Result<()> {
        self.fts = FtsStatus::Missing;
        if !self.writable {
            return Ok(());
        }
        self.faults.hit("fts.drop")?;
        // Not through `write`: a failure here is final, never another round
        // of damage handling.
        let drop = || -> rusqlite::Result<()> {
            let tx = Transaction::new_unchecked(&self.conn, TransactionBehavior::Immediate)?;
            tx.execute_batch(DROP_FTS_NEXT)?;
            tx.execute_batch(DROP_FTS)?;
            tx.execute("DELETE FROM meta WHERE key = 'fts_build_rid'", [])?;
            tx.commit()
        };
        drop().map_err(|e| match error_of(&e, Source::Session) {
            StoreError::Corrupt(m) => StoreError::FtsDamaged(format!("dropping the index: {m}")),
            other => other,
        })
    }

    /// Starts a rebuild of the search index: a new, empty `shot_fts_next`
    /// with its triggers, the cursor at 0. A build already under way is
    /// dropped and started again. The old index, if any, keeps its triggers
    /// until the swap. After damage, and for the flip's copy (SPEC §11 step 5).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn start_fts_rebuild(&mut self) -> Result<()> {
        let started = self.write("fts.build.commit", "fts.build.commit", |tx, _, f| {
            f.hit("fts.build.begin")?;
            tx.execute_batch(DROP_FTS_NEXT)?;
            tx.execute(
                "INSERT INTO meta (key, value) VALUES ('fts_build_rid', '0') \
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                [],
            )?;
            tx.execute_batch(FTS_NEXT_V1)?;
            Ok(())
        });
        self.fts = load(&self.conn).map_err(|e| error_of(&e, Source::Session))?;
        started.map(|_| ())
    }

    /// One rebuild step: the next batch of rows into the new table; the last
    /// one also swaps the new table in. The cursor is read in the step's own
    /// transaction, so a step whose answer was lost after its commit is never
    /// added twice.
    fn fts_build_step(&mut self) -> Result<Maintained> {
        let batch = i64::from(self.batch_rows);
        let step = self.write("fts.build.commit", "fts.build.commit", |tx, _, f| {
            let cursor: i64 = tx.query_row(
                "SELECT CAST(value AS INTEGER) FROM meta WHERE key = 'fts_build_rid'",
                [],
                |r| r.get(0),
            )?;
            let end: Option<i64> = tx
                .prepare_cached(
                    "SELECT max(rid) FROM (SELECT rid FROM shot WHERE rid > ?1 ORDER BY rid LIMIT ?2)",
                )?
                .query_row((cursor, batch), |r| r.get(0))?;
            if let Some(end) = end {
                f.hit("fts.build.batch")?;
                let rows = tx
                    .prepare_cached(
                        "INSERT INTO shot_fts_next (rowid, search_text) \
                         SELECT rid, search_text FROM shot WHERE rid > ?1 AND rid <= ?2",
                    )?
                    .execute((cursor, end))
                    .map_err(on_fts)?;
                tx.execute(
                    "UPDATE meta SET value = ?1 WHERE key = 'fts_build_rid'",
                    [end.to_string()],
                )?;
                let more: Option<i64> = tx
                    .prepare_cached("SELECT rid FROM shot WHERE rid > ?1 LIMIT 1")?
                    .query_row([end], |r| r.get(0))
                    .optional()?;
                if more.is_some() {
                    return Ok(Maintained::FtsBatch {
                        rows: u32::try_from(rows).unwrap_or(u32::MAX),
                    });
                }
            }
            // The last batch and the swap are one transaction, so writes that
            // keep adding rows can't keep the build from finishing.
            f.hit("fts.swap")?;
            tx.execute_batch(DROP_FTS_NEXT_TRIGGERS)?;
            tx.execute_batch(DROP_FTS)?;
            tx.execute_batch("ALTER TABLE shot_fts_next RENAME TO shot_fts")?;
            tx.execute_batch(FTS_TRIGGERS_V1)?;
            tx.execute("DELETE FROM meta WHERE key = 'fts_build_rid'", [])?;
            Ok(Maintained::FtsSwapped)
        });
        // Whatever happened, the schema and `meta` say where the build is.
        self.fts = load(&self.conn).map_err(|e| error_of(&e, Source::Session))?;
        let (step, _) = step?;
        if step == Maintained::FtsSwapped {
            self.merge_pending = true;
        }
        Ok(step)
    }

    /// One incremental merge step, its own transaction. `more` while the step
    /// still moved pages.
    ///
    /// # Errors
    /// A SQLite error class. A failure on the index drops it (M6).
    pub fn fts_merge_step(&mut self) -> Result<Maintained> {
        if self.fts != FtsStatus::Ready {
            self.merge_pending = false;
            return Ok(Maintained::Idle);
        }
        let (moved, _) = self.write("fts.merge", "fts.merge", |tx, _, f| {
            f.hit("fts.merge")?;
            let before = tx.total_changes();
            tx.execute(
                "INSERT INTO shot_fts (shot_fts, rank) VALUES ('merge', 500)",
                [],
            )
            .map_err(on_fts)?;
            Ok(tx.total_changes() - before)
        })?;
        let more = moved >= 2;
        self.merge_pending = more;
        Ok(Maintained::Merged { more })
    }

    /// The index's integrity check against its table (`rank = 1`). A failure
    /// drops the index for a rebuild (M6) and gives [`FtsCheck::Damaged`].
    /// Weekly when idle and alone, and at the flip's verify; the shell
    /// decides when.
    ///
    /// # Errors
    /// A SQLite error class other than the index's own damage.
    pub fn fts_integrity_check(&mut self) -> Result<FtsCheck> {
        self.usable()?;
        if self.fts != FtsStatus::Ready {
            return Ok(FtsCheck::NotReady);
        }
        let run = self
            .conn
            .execute(
                "INSERT INTO shot_fts (shot_fts, rank) VALUES ('integrity-check', 1)",
                [],
            )
            .map_err(on_fts);
        match run {
            Ok(_) => Ok(FtsCheck::Ok),
            Err(fail) => match self.settle(fail) {
                super::Settled::Retry => Ok(FtsCheck::Damaged),
                super::Settled::Error(e) => Err(e),
            },
        }
    }

    /// One unit of background work, in priority order: a derive rewrite
    /// batch, then the search index's rebuild, then a merge step. Nothing
    /// while a shot runs (SPEC §9), and nothing on a file this build may not
    /// write. Each step is one short transaction, so a caller loops with
    /// yields between steps until [`Maintained::Idle`].
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn maintain(&mut self) -> Result<Maintained> {
        self.usable()?;
        if self.busy {
            return Ok(Maintained::Busy);
        }
        if !self.writable {
            return Ok(Maintained::Idle);
        }
        if self.derive.pending {
            return self.derive_step();
        }
        match self.fts {
            FtsStatus::Missing => {
                self.start_fts_rebuild()?;
                Ok(Maintained::FtsStarted)
            }
            FtsStatus::Building { .. } => self.fts_build_step(),
            FtsStatus::Ready if self.merge_pending => self.fts_merge_step(),
            FtsStatus::Ready => Ok(Maintained::Idle),
        }
    }

    /// Runs [`Store::maintain`] until it is idle or busy, at most `steps`
    /// times. Returns the steps taken. For tests and for callers with no
    /// one to yield to.
    ///
    /// # Errors
    /// The first step's error.
    pub fn maintain_all(&mut self, steps: u32) -> Result<u32> {
        for n in 0..steps {
            match self.maintain()? {
                Maintained::Idle | Maintained::Busy => return Ok(n),
                _ => {}
            }
        }
        Ok(steps)
    }
}

/// The rebuild's triggers alone, for the swap: the new table is renamed, not
/// dropped.
const DROP_FTS_NEXT_TRIGGERS: &str = "DROP TRIGGER IF EXISTS shot_fts_next_ai;
DROP TRIGGER IF EXISTS shot_fts_next_ad;
DROP TRIGGER IF EXISTS shot_fts_next_au;
";

//! Copies and new files (SPEC §11): the online backup copy, for the flip and
//! the rows snapshot, and recovery files.
//!
//! - [`Store::copy_to`] copies this file into another, through SQLite's
//!   online backup API, between two connections to two files (S4). It never
//!   overwrites a finished truth file from anything but another one: a copy
//!   from an index onto a `history.db` whose `meta.migration` says `done` is
//!   refused, as is a truth file onto a newer one (a higher `generation`, or
//!   the same one and a higher `write_seq`), and any destination that isn't ours or doesn't open.
//! - [`Store::flip_copy_to`] is the flip's copy (SPEC §11 step 5): the same
//!   copy, then in one transaction on the copy its `role = 'authority'`, its
//!   generation, `meta.migration = copying`, `migration_source` and both
//!   late-row baselines (S13). A crash between the copy and that transaction
//!   leaves `role = 'index'`, which the next try overwrites (M8 step 4).
//! - [`Store::set_migration_done`] is verify's pass: `copying` becomes
//!   `done`, then a checkpoint, before the shell writes its done-record.
//! - [`Store::create_recovery`] makes a new `history-<n>.db` after `lost`,
//!   with its `migration_source` and late-row baseline from the caller, so
//!   the next boot's late-row step imports only what an older build writes
//!   after the choice, and "start empty" stays empty (S9). It never
//!   overwrites a file that holds anything.

use std::path::Path;
use std::time::Duration;

use rusqlite::backup::{Backup, StepResult};
use rusqlite::{Connection, OpenFlags, OptionalExtension, Transaction, TransactionBehavior};

use super::error::{Result, Source, StoreError, error_of};
use super::{Claim, JournalMode, OpenOptions, Role, Store, file_key};
use crate::schema::APPLICATION_ID;

/// What a recovery file starts from (S9).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Recovery {
    /// Every id the P3 store holds at the moment of the choice.
    pub migration_source: Vec<String>,
    /// The late-row baseline at that moment, as the shell records it: web's
    /// `writeSeq`, Android's sizes and mtimes. Opaque to the core.
    pub late_baseline: String,
}

/// What the flip writes into its copy (SPEC §11 step 5, S9, S13).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct FlipInit {
    /// The file's generation: 0 for `history.db`, `n` for `history-<n>.db`.
    pub generation: u32,
    /// The P3 store's write counter read before the full diff's first batch.
    pub source_seq: u64,
    /// How many rows the P3 store held then.
    pub source_count: u64,
    /// Every id the P3 store holds (`migration_source`).
    pub migration_source: Vec<String>,
    /// Step 8's late-row baseline at the copy, as the shell records it
    /// (web: `{"writeSeq":n}`). Opaque to the core.
    pub late_baseline: String,
    /// The P3 store's own late-row bookkeeping, its `meta.migration`
    /// (`sourceIds`, `sourceHash`, `lateIds`), copied so its pre-P3 late-row
    /// step can go on in this file (S13). Opaque; `None` when it has none.
    pub p3_migration: Option<String>,
}

/// `meta.migration`'s state, when the file has one.
fn migration_state(conn: &Connection) -> rusqlite::Result<Option<String>> {
    let has_meta: Option<i64> = conn
        .query_row(
            "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = 'meta'",
            [],
            |r| r.get(0),
        )
        .optional()?;
    if has_meta.is_none() {
        return Ok(None);
    }
    let raw: Option<String> = conn
        .query_row("SELECT value FROM meta WHERE key = 'migration'", [], |r| {
            r.get(0)
        })
        .optional()?;
    Ok(raw.and_then(|r| {
        serde_json::from_str::<serde_json::Value>(&r)
            .ok()
            .and_then(|v| v.get("state").and_then(|s| s.as_str()).map(str::to_owned))
    }))
}

fn meta_text(conn: &Connection, key: &str) -> rusqlite::Result<Option<String>> {
    conn.query_row("SELECT value FROM meta WHERE key = ?1", [key], |r| r.get(0))
        .optional()
}

/// Whether the file is a finished truth file: role `authority` and
/// `migration` `done`.
fn finished_truth(conn: &Connection) -> rusqlite::Result<bool> {
    if !has_table(conn, "meta")? {
        return Ok(false);
    }
    Ok(
        meta_text(conn, "role")?.as_deref() == Some(Role::Authority.as_str())
            && migration_state(conn)?.as_deref() == Some("done"),
    )
}

/// Whether the file at `path` holds anything a recovery mustn't overwrite:
/// anything but nothing, or a schema this store made and never wrote.
fn holds_something(path: &Path, vfs: Option<&str>) -> Result<bool> {
    let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    let conn = match vfs {
        Some(v) => Connection::open_with_flags_and_vfs(path, flags, v),
        None => Connection::open_with_flags(path, flags),
    };
    // No file: nothing to keep.
    let Ok(conn) = conn else { return Ok(false) };
    let check = || -> rusqlite::Result<bool> {
        let app: i32 = conn.query_row("PRAGMA application_id", [], |r| r.get(0))?;
        let objects: i64 =
            conn.query_row("SELECT count(*) FROM sqlite_schema", [], |r| r.get(0))?;
        if app == 0 && objects == 0 {
            return Ok(false);
        }
        if app != APPLICATION_ID || !has_table(&conn, "meta")? || !has_table(&conn, "shot")? {
            return Ok(true);
        }
        let rows: i64 = conn.query_row("SELECT count(*) FROM shot", [], |r| r.get(0))?;
        let seq = meta_text(&conn, "write_seq")?;
        Ok(rows > 0 || seq.as_deref() != Some("0") || migration_state(&conn)?.is_some())
    };
    // A file that doesn't read is kept too.
    Ok(check().unwrap_or(true))
}

/// `meta.write_seq` and `meta.generation`, 0 when missing.
fn seq_and_generation(conn: &Connection) -> rusqlite::Result<(u64, u64)> {
    let num = |k: &str| -> rusqlite::Result<u64> {
        Ok(meta_text(conn, k)?
            .and_then(|v| v.parse().ok())
            .unwrap_or(0))
    };
    Ok((num("write_seq")?, num("generation")?))
}

fn has_table(conn: &Connection, name: &str) -> rusqlite::Result<bool> {
    conn.query_row(
        "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = ?1",
        [name],
        |_| Ok(()),
    )
    .optional()
    .map(|o| o.is_some())
}

impl Store {
    /// `meta.migration`'s state (`copying`, `done`), when the file has one.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn migration_state(&self) -> Result<Option<String>> {
        self.usable()?;
        migration_state(&self.conn).map_err(|e| error_of(&e, Source::Session))
    }

    /// The late-row baseline a recovery file started from.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn late_baseline(&self) -> Result<Option<String>> {
        self.usable()?;
        meta_text(&self.conn, "late_baseline").map_err(|e| error_of(&e, Source::Session))
    }

    /// How many ids `migration_source` holds.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn migration_source_len(&self) -> Result<u64> {
        self.usable()?;
        self.conn
            .query_row("SELECT count(*) FROM migration_source", [], |r| {
                r.get::<_, i64>(0)
            })
            .map(|n| u64::try_from(n).unwrap_or(0))
            .map_err(|e| error_of(&e, Source::Session))
    }

    /// Copies this file into `dest` with the online backup API, a few pages
    /// a step (S15): between two connections to two files (S4), through
    /// `vfs` when given. `dest` is claimed for the copy, so a store that has
    /// it open refuses it. Refused, and left untouched: a destination that
    /// doesn't open or isn't ours, and a finished truth file (`authority`,
    /// `migration` `done`) when this file isn't one too.
    ///
    /// # Errors
    /// [`StoreError::AlreadyOpen`], [`StoreError::Foreign`],
    /// [`StoreError::Corrupt`] or [`StoreError::Refused`] as above; a SQLite
    /// error class.
    pub fn copy_to(&self, dest: &Path, vfs: Option<&str>, journal: JournalMode) -> Result<()> {
        self.usable()?;
        let _claim = Claim::take(file_key(dest, vfs)?)?;
        self.copy_into(dest, vfs, journal).map(drop)
    }

    /// The flip's copy (SPEC §11 step 5): [`Store::copy_to`] from this index,
    /// then, on the copy and in one transaction, `role = 'authority'`, the
    /// generation, `meta.migration = {state: 'copying', sourceSeq,
    /// sourceCount}`, `migration_source` and both late-row baselines (S13).
    /// Only from an `index` file. The copy is closed when this returns, so
    /// the next open of it reads its new role (REVIEW-442 note 8). A crash
    /// between the copy and its transaction leaves `role = 'index'`, never
    /// the truth; the next try overwrites it in place (M8).
    ///
    /// # Errors
    /// As [`Store::copy_to`]; [`StoreError::Refused`] from a file that isn't
    /// an index.
    pub fn flip_copy_to(
        &self,
        dest: &Path,
        vfs: Option<&str>,
        journal: JournalMode,
        init: &FlipInit,
    ) -> Result<()> {
        self.usable()?;
        if self.info.role != Role::Index {
            return Err(StoreError::Refused(
                "the flip copies only from the index".into(),
            ));
        }
        let _claim = Claim::take(file_key(dest, vfs)?)?;
        let dst = self.copy_into(dest, vfs, journal)?;
        self.faults.hit("flip.copied")?;
        let ids = serde_json::to_string(&init.migration_source)
            .map_err(|e| StoreError::Refused(e.to_string()))?;
        let migration = serde_json::json!({
            "state": "copying",
            "sourceSeq": init.source_seq,
            "sourceCount": init.source_count,
        })
        .to_string();
        let generation = init.generation.to_string();
        let run = || -> std::result::Result<(), StoreError> {
            let sql = |e: rusqlite::Error| error_of(&e, Source::Session);
            let tx =
                Transaction::new_unchecked(&dst, TransactionBehavior::Immediate).map_err(sql)?;
            let put = |k: &str, v: &str| -> std::result::Result<(), StoreError> {
                tx.execute(
                    "INSERT INTO meta (key, value) VALUES (?1, ?2) \
                     ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    (k, v),
                )
                .map(drop)
                .map_err(sql)
            };
            put("role", Role::Authority.as_str())?;
            put("generation", &generation)?;
            put("migration", &migration)?;
            put("late_baseline", &init.late_baseline)?;
            if let Some(p3) = &init.p3_migration {
                put("p3_migration", p3)?;
            }
            // Never opened as a session yet: its first open needn't check it.
            put("open", "0")?;
            tx.execute("DELETE FROM migration_source", [])
                .map_err(sql)?;
            tx.execute(
                "INSERT INTO migration_source (id) SELECT DISTINCT value FROM json_each(?1)",
                [&ids],
            )
            .map_err(sql)?;
            self.faults.hit("flip.promote.commit")?;
            tx.commit().map_err(sql)?;
            self.faults.hit("flip.promote.committed")
        };
        run()?;
        dst.close().map_err(|(_, e)| error_of(&e, Source::Session))
    }

    /// The backup copy itself; `dest`'s claim is the caller's.
    fn copy_into(
        &self,
        dest: &Path,
        vfs: Option<&str>,
        journal: JournalMode,
    ) -> Result<Connection> {
        let open_err = |e: rusqlite::Error| error_of(&e, Source::Open);
        let flags = OpenFlags::SQLITE_OPEN_READ_WRITE
            | OpenFlags::SQLITE_OPEN_CREATE
            | OpenFlags::SQLITE_OPEN_NO_MUTEX;
        let mut dst = match vfs {
            Some(v) => Connection::open_with_flags_and_vfs(dest, flags, v),
            None => Connection::open_with_flags(dest, flags),
        }
        .map_err(open_err)?;
        if journal == JournalMode::ExclusiveWal {
            dst.query_row("PRAGMA locking_mode = EXCLUSIVE", [], |r| {
                r.get::<_, String>(0)
            })
            .map_err(open_err)?;
        }
        // Read before anything writes: a destination that isn't a database
        // fails here and stays as it is.
        let app: i32 = dst
            .query_row("PRAGMA application_id", [], |r| r.get(0))
            .map_err(open_err)?;
        let objects: i64 = dst
            .query_row("SELECT count(*) FROM sqlite_schema", [], |r| r.get(0))
            .map_err(open_err)?;
        if !(app == 0 && objects == 0) {
            if app != APPLICATION_ID {
                return Err(StoreError::Foreign(format!("application_id {app:#x}")));
            }
            let src_truth =
                finished_truth(&self.conn).map_err(|e| error_of(&e, Source::Session))?;
            if finished_truth(&dst).map_err(open_err)? {
                if !src_truth {
                    return Err(StoreError::Refused(
                        "the destination is a finished history file".into(),
                    ));
                }
                // Truth onto truth is the snapshot, which only moves forward:
                // an older snapshot or generation never replaces a newer
                // truth (REVIEW-442 note 4). Generation first: a recovery
                // file starts its seq again, and is still the newer truth.
                let (src_seq, src_gen) =
                    seq_and_generation(&self.conn).map_err(|e| error_of(&e, Source::Session))?;
                let (dst_seq, dst_gen) = seq_and_generation(&dst).map_err(open_err)?;
                if (dst_gen, dst_seq) > (src_gen, src_seq) {
                    return Err(StoreError::Refused(format!(
                        "the destination is newer (write_seq {dst_seq}, generation {dst_gen}) \
                         than this file (write_seq {src_seq}, generation {src_gen})"
                    )));
                }
            }
        }
        let backup =
            Backup::new(&self.conn, &mut dst).map_err(|e| error_of(&e, Source::Session))?;
        // Stepped here, not with `run_to_completion`, which sleeps between
        // steps: `std` can't sleep on wasm32 and panics.
        loop {
            match backup
                .step(256)
                .map_err(|e| error_of(&e, Source::Session))?
            {
                StepResult::Done => break,
                StepResult::More => {}
                // Another connection holds a lock. Natively it may let go;
                // on wasm32 nothing else runs while this waits.
                StepResult::Busy | StepResult::Locked => {
                    if cfg!(all(target_family = "wasm", target_os = "unknown")) {
                        return Err(StoreError::Busy);
                    }
                    std::thread::sleep(Duration::ZERO);
                }
                // `StepResult` is non-exhaustive.
                _ => return Err(StoreError::Busy),
            }
        }
        drop(backup);
        Ok(dst)
    }

    /// Verify's pass (SPEC §11 step 7): `meta.migration`'s state goes from
    /// `copying` to `done`, in one transaction, then a checkpoint, so the
    /// shell's done-record and marker, written after, never name a file that
    /// isn't `done` on disk. Only on an `authority` file whose migration is
    /// `copying`; on one already `done` it does nothing.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on any other file; a SQLite error class.
    pub fn set_migration_done(&mut self, done_at_ms: i64) -> Result<()> {
        self.writable()?;
        if self.info.role != Role::Authority {
            return Err(StoreError::Refused(
                "only the truth file is migrated".into(),
            ));
        }
        let sql = |e: rusqlite::Error| error_of(&e, Source::Session);
        let raw = meta_text(&self.conn, "migration").map_err(sql)?;
        let mut m: serde_json::Value = raw
            .as_deref()
            .and_then(|r| serde_json::from_str(r).ok())
            .ok_or_else(|| StoreError::Refused("no migration record".into()))?;
        match m.get("state").and_then(|s| s.as_str()) {
            Some("done") => return Ok(()),
            Some("copying") => {}
            other => return Err(StoreError::Refused(format!("migration state {other:?}"))),
        }
        m["state"] = "done".into();
        m["doneAtMs"] = done_at_ms.into();
        let text = m.to_string();
        let tx =
            Transaction::new_unchecked(&self.conn, TransactionBehavior::Immediate).map_err(sql)?;
        tx.execute(
            "UPDATE meta SET value = ?1 WHERE key = 'migration'",
            [&text],
        )
        .map_err(sql)?;
        self.faults.hit("flip.done.commit")?;
        tx.commit().map_err(sql)?;
        // The commit is durable under `synchronous = FULL`; the checkpoint
        // moves it into the main file too. Best-effort: a rollback-journal
        // file has nothing to move.
        let _ = self
            .conn
            .query_row("PRAGMA wal_checkpoint(TRUNCATE)", [], |_| Ok(()));
        Ok(())
    }

    /// Makes this file, a copy of a rows snapshot, the recovery file of
    /// generation `generation` after `lost` (S9), in one transaction: its
    /// `generation`, `migration = done` (source `snapshot`), the late-row
    /// baseline from `init`, and `init`'s ids added to `migration_source`, so
    /// step 8 imports only what an older build writes after the choice.
    ///
    /// # Errors
    /// [`StoreError::Refused`] on a file that isn't a finished truth file; a
    /// SQLite error class.
    pub fn set_recovery(&mut self, generation: u32, init: &Recovery) -> Result<()> {
        self.writable()?;
        let sql = |e: rusqlite::Error| error_of(&e, Source::Session);
        if !finished_truth(&self.conn).map_err(sql)? {
            return Err(StoreError::Refused(
                "only a finished truth file becomes a recovery file".into(),
            ));
        }
        let ids = serde_json::to_string(&init.migration_source)
            .map_err(|e| StoreError::Refused(e.to_string()))?;
        let tx =
            Transaction::new_unchecked(&self.conn, TransactionBehavior::Immediate).map_err(sql)?;
        for (k, v) in [
            ("generation", generation.to_string()),
            ("late_baseline", init.late_baseline.clone()),
            (
                "migration",
                r#"{"state":"done","source":"snapshot"}"#.to_owned(),
            ),
        ] {
            tx.execute(
                "INSERT INTO meta (key, value) VALUES (?1, ?2) \
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                (k, v),
            )
            .map_err(sql)?;
        }
        tx.execute(
            "INSERT OR IGNORE INTO migration_source (id) SELECT DISTINCT value FROM json_each(?1)",
            [&ids],
        )
        .map_err(sql)?;
        tx.commit().map_err(sql)?;
        self.info.generation = generation;
        Ok(())
    }

    /// `PRAGMA integrity_check`, the whole of it (verify, SPEC §11): the
    /// problems it reports, or none. It reads every page, so the shell calls
    /// it only at the flip, before anything else uses the file (N4).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn integrity_check(&self) -> Result<Vec<String>> {
        self.usable()?;
        let run = || -> rusqlite::Result<Vec<String>> {
            let mut st = self.conn.prepare("PRAGMA integrity_check")?;
            let rows = st
                .query_map([], |r| r.get::<_, String>(0))?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(if rows == ["ok"] { Vec::new() } else { rows })
        };
        run().map_err(|e| error_of(&e, Source::Session))
    }

    /// Every id in `migration_source`, sorted (verify, SPEC §11).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn migration_source_ids(&self) -> Result<Vec<String>> {
        self.usable()?;
        let run = || -> rusqlite::Result<Vec<String>> {
            self.conn
                .prepare("SELECT id FROM migration_source ORDER BY id")?
                .query_map([], |r| r.get(0))?
                .collect()
        };
        run().map_err(|e| error_of(&e, Source::Session))
    }

    /// The file's size: its page count times its page size (the flip's
    /// free-space check, SPEC §11 step 3).
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn size_bytes(&self) -> Result<u64> {
        self.usable()?;
        let n = |p: &str| -> rusqlite::Result<i64> { self.conn.query_row(p, [], |r| r.get(0)) };
        let run = || -> rusqlite::Result<u64> {
            let pages = n("PRAGMA page_count")?;
            let size = n("PRAGMA page_size")?;
            Ok(u64::try_from(pages.saturating_mul(size)).unwrap_or(0))
        };
        run().map_err(|e| error_of(&e, Source::Session))
    }

    /// A `meta` value by key: the late-row baselines (`late_baseline`,
    /// `p3_migration`) for step 8.
    ///
    /// # Errors
    /// A SQLite error class.
    pub fn meta_value(&self, key: &str) -> Result<Option<String>> {
        self.usable()?;
        meta_text(&self.conn, key).map_err(|e| error_of(&e, Source::Session))
    }

    /// Creates the recovery file `path` (`history-<n>.db`) after `lost`
    /// (SPEC §11, S9): an `authority` file with `migration` `done`, its
    /// `migration_source` and late-row baseline from `init`, in one
    /// transaction. A file at `path` that holds anything is refused and left
    /// as it is.
    ///
    /// # Errors
    /// [`StoreError::Refused`] for a file that holds something; as
    /// [`Store::open`].
    pub fn create_recovery(
        path: impl AsRef<Path>,
        opts: OpenOptions,
        init: &Recovery,
    ) -> Result<Store> {
        let path = path.as_ref();
        let mut opts = opts;
        opts.role = Role::Authority;
        opts.create = true;
        // Checked before the open, which would write its open mark.
        if holds_something(path, opts.vfs.as_deref())? {
            return Err(StoreError::Refused(format!(
                "{} already holds something",
                path.display()
            )));
        }
        let store = Store::open(path, opts)?;
        let ids = serde_json::to_string(&init.migration_source)
            .map_err(|e| StoreError::Refused(e.to_string()))?;
        let run = || -> rusqlite::Result<()> {
            let tx = Transaction::new_unchecked(&store.conn, TransactionBehavior::Immediate)?;
            tx.execute(
                "INSERT INTO migration_source (id) SELECT DISTINCT value FROM json_each(?1)",
                [&ids],
            )?;
            for (k, v) in [
                ("late_baseline", init.late_baseline.as_str()),
                ("migration", r#"{"state":"done","source":"recovery"}"#),
            ] {
                tx.execute(
                    "INSERT INTO meta (key, value) VALUES (?1, ?2) \
                     ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                    (k, v),
                )?;
            }
            tx.commit()
        };
        run().map_err(|e| error_of(&e, Source::Session))?;
        Ok(store)
    }
}

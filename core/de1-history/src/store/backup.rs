//! Copies and new files (SPEC §11): the online backup copy, for the flip and
//! the rows snapshot, and recovery files.
//!
//! - [`Store::copy_to`] copies this file into another, through SQLite's
//!   online backup API, between two connections to two files (S4). It never
//!   overwrites a finished truth file from anything but another one: a copy
//!   from an index onto a `history.db` whose `meta.migration` says `done` is
//!   refused, as is a truth file onto a newer one (a higher `write_seq` or
//!   `generation`), and any destination that isn't ours or doesn't open.
//! - [`Store::create_recovery`] makes a new `history-<n>.db` after `lost`,
//!   with its `migration_source` and late-row baseline from the caller, so
//!   the next boot's late-row step imports only what an older build writes
//!   after the choice, and "start empty" stays empty (S9). It never
//!   overwrites a file that holds anything.

use std::path::Path;
use std::time::Duration;

use rusqlite::backup::Backup;
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
                // truth (REVIEW-442 note 4).
                let (src_seq, src_gen) =
                    seq_and_generation(&self.conn).map_err(|e| error_of(&e, Source::Session))?;
                let (dst_seq, dst_gen) = seq_and_generation(&dst).map_err(open_err)?;
                if dst_seq > src_seq || dst_gen > src_gen {
                    return Err(StoreError::Refused(format!(
                        "the destination is newer (write_seq {dst_seq}, generation {dst_gen}) \
                         than this file (write_seq {src_seq}, generation {src_gen})"
                    )));
                }
            }
        }
        let backup =
            Backup::new(&self.conn, &mut dst).map_err(|e| error_of(&e, Source::Session))?;
        backup
            .run_to_completion(256, Duration::ZERO, None)
            .map_err(|e| error_of(&e, Source::Session))?;
        drop(backup);
        Ok(())
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

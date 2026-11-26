//! The store (SPEC §4, §5, §9, §11): the history database itself, over
//! `rusqlite` with SQLite compiled in. Behind the `store` feature.
//!
//! One [`Store`] is one connection to one file. It opens the file and checks
//! it ([`Store::open`]), writes rows in one transaction per call, each
//! returning a [`ChangeSet`] with its `write_seq` ([`Store::upsert`],
//! [`Store::delete`], [`Store::set_live`], [`Store::apply`]), keeps the search
//! index in step and repairs it ([`Store::maintain`]), and answers queries.
//!
//! **What it never does.** It never deletes a row except through
//! [`Store::delete`] naming it (rule 1), never writes `INSERT OR REPLACE`, and
//! never deletes or recreates a file: a failed open is an error, and the file
//! is left exactly as it was. Derived data (the search index, the derived
//! columns, the live mirror) it may drop and rebuild (rule 4).
//!
//! **One connection per file** (S4). A second open of a file this process's
//! store already has open is refused with [`StoreError::AlreadyOpen`];
//! connections to two different files are fine.

mod backup;
mod error;
mod export;
pub mod faults;
mod fts;
mod index;
mod late;
mod read;
mod rewrite;
mod write;

use std::collections::{BTreeSet, HashMap};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::Duration;

use rusqlite::{Connection, OpenFlags, OptionalExtension, Transaction, TransactionBehavior};

pub use backup::{FlipInit, Recovery};
pub use error::{Result, StoreError};
pub use export::{ExportWalk, RidId};
pub use faults::Faults;
pub use fts::{FtsCheck, FtsStatus, Maintained};
pub use index::{ABSENT_BATCH_ROWS, AbsentError, AbsentStep, IndexState, IndexWrite};
pub use late::{HeavyOut, LateImport, LateImported, LateRows};
pub use read::{CoffeeFacet, Destination, Facets, Page, RidRow, RowOut, SyncPage, SyncRow};
pub use write::{Applied, Duplicate, Heavy, RowIn, Write, Written};

use crate::change::ChangeSet;
use crate::derive::DERIVE_V;
use crate::schema::{APPLICATION_ID, PRAGMAS, SCHEMA_V, V1};
use crate::sql::TextPath;
use error::{Class, Fail, Source, classify, error_of};

/// The SQLite both builds compile in (SPEC §1, "One SQLite version"):
/// `libsqlite3-sys` 0.40.0 natively, `sqlite-wasm-rs` 0.6.3 on wasm32. The
/// same test runs in both builds against this one constant, so a dependency
/// bump that splits them fails CI.
pub const SQLITE_VERSION: &str = "3.54.1";

/// The SQLite this build runs, from the library itself.
#[must_use]
pub fn sqlite_version() -> &'static str {
    rusqlite::version()
}

/// A week, for the weekly `quick_check`.
const WEEK_MS: i64 = 7 * 24 * 60 * 60 * 1000;

/// The rows a search index rebuild batch takes (S15). B1 measured 2 ms for a
/// median batch of 1,000 rows natively at 100,000 rows, 15 ms at worst, under
/// the 100 ms cap with room for a slower device. [`Store::set_batch_rows`]
/// overrides it.
pub const FTS_BATCH_ROWS: u32 = 1_000;

/// The rows a derive rewrite batch takes (S15). Each row rewrites its columns,
/// tags, sort-index entries and search text: B1 measured a median batch of 200
/// rows at 31 ms natively at 100,000 rows. [`Store::set_batch_rows`]
/// overrides it.
pub const DERIVE_BATCH_ROWS: u32 = 200;

/// What a file is for (SPEC §3): `meta.role`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Role {
    /// Stage 1's `history-index.db`: derived from the P3 store, disposable.
    Index,
    /// Stage 2's `history.db`: the truth.
    Authority,
}

impl Role {
    /// The `meta.role` text.
    #[must_use]
    pub fn as_str(self) -> &'static str {
        match self {
            Role::Index => "index",
            Role::Authority => "authority",
        }
    }

    fn parse(s: &str) -> Option<Role> {
        match s {
            "index" => Some(Role::Index),
            "authority" => Some(Role::Authority),
            _ => None,
        }
    }
}

/// How the file journals (SPEC §4, §8). The shell's choice: WAL on Android,
/// and on web whichever W0's kill test keeps.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JournalMode {
    /// `journal_mode=WAL`.
    Wal,
    /// `journal_mode=TRUNCATE`: a rollback journal, for a VFS without shared
    /// memory.
    Truncate,
    /// `locking_mode=EXCLUSIVE`, then `journal_mode=WAL`: WAL without shared
    /// memory (sqlite.org/wal.html §8).
    ExclusiveWal,
}

/// How to open a file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OpenOptions {
    /// The role the file must hold; any other is [`StoreError::WrongRole`].
    pub role: Role,
    /// Create the file, with the schema, when it doesn't exist or is empty.
    /// Without it a missing file is [`StoreError::CantOpen`] and an empty one
    /// [`StoreError::Foreign`].
    pub create: bool,
    /// `meta.generation` for a file this open creates.
    pub generation: u32,
    /// The journal mode.
    pub journal: JournalMode,
    /// The SQLite VFS to open through; `None` is the platform's default.
    pub vfs: Option<String>,
    /// The time now, in ms since the epoch: when a weekly `quick_check` is due.
    pub now_ms: i64,
    /// Run `PRAGMA quick_check` at this open whatever the schedule says, as
    /// after a `SQLITE_CORRUPT` error. An open after a session that didn't
    /// close cleanly runs it anyway.
    pub quick_check: bool,
    /// How long a locked file is waited for before [`StoreError::Busy`].
    pub busy_timeout_ms: u32,
    /// After a mid-session `SQLITE_CORRUPT` reopens the file and its checks
    /// pass, run the search index's `'integrity-check'` at once (`true`, the
    /// default), or set the index aside for a rebuild in batches (`false`).
    /// The check is one statement over the whole index, which at 100,000
    /// rows holds web's one connection past S15's cap, so web passes `false`
    /// (REVIEW-439 note 6). Searches run on the scan path until the rebuilt
    /// index is swapped in.
    pub fts_check_on_reopen: bool,
}

impl OpenOptions {
    /// Opens an existing file of `role`, WAL, default VFS. WAL is
    /// [`JournalMode::ExclusiveWal`] on wasm32, whose VFSes have no shared
    /// memory; the web shell sets the mode W0 keeps.
    #[must_use]
    pub fn new(role: Role) -> OpenOptions {
        OpenOptions {
            role,
            create: false,
            generation: 0,
            journal: if cfg!(all(target_family = "wasm", target_os = "unknown")) {
                JournalMode::ExclusiveWal
            } else {
                JournalMode::Wal
            },
            vfs: None,
            now_ms: 0,
            quick_check: false,
            busy_timeout_ms: 5_000,
            fts_check_on_reopen: true,
        }
    }

    /// The same, creating the file when it doesn't exist.
    #[must_use]
    pub fn create(mut self, generation: u32) -> OpenOptions {
        self.create = true;
        self.generation = generation;
        self
    }
}

/// What the file's `meta` says about it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileInfo {
    /// The schema version that wrote the file.
    pub schema_v: u32,
    /// The oldest store code that may read it.
    pub min_reader_v: u32,
    /// The oldest store code that may write it.
    pub min_writer_v: u32,
    /// Its role.
    pub role: Role,
    /// Its generation (`history-<n>.db`).
    pub generation: u32,
}

/// The store's health as the core sees it (SPEC §12). The shell adds what it
/// alone knows: the marker, the owner, quota.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Health {
    /// Derived data is being rebuilt: the search index, or the derived
    /// columns. Lists answer; searches run on the scan path.
    pub reindexing: bool,
    /// The search index's state.
    pub fts: FtsStatus,
    /// Rows whose derived columns predate [`DERIVE_V`] remain.
    pub derive_pending: bool,
    /// Writes are allowed: `false` when the file's `min_writer_v` is above
    /// this build (read-only, "update the app").
    pub writable: bool,
    /// This open ran `PRAGMA quick_check`, and it passed.
    pub quick_checked: bool,
}

/// The files this process's stores have open (S4), by key.
static OPEN_FILES: Mutex<BTreeSet<String>> = Mutex::new(BTreeSet::new());

/// A file held open; released on drop.
#[derive(Debug)]
struct Claim(String);

impl Claim {
    fn take(key: String) -> Result<Claim> {
        let mut open = OPEN_FILES
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if !open.insert(key.clone()) {
            return Err(StoreError::AlreadyOpen(key));
        }
        Ok(Claim(key))
    }
}

impl Drop for Claim {
    fn drop(&mut self) {
        let mut open = OPEN_FILES
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        open.remove(&self.0);
    }
}

/// Whether the VFS reaches the platform's file system: natively the default
/// VFS and SQLite's `unix` family, which all name the same files. On wasm32
/// there is none: the default is `memvfs`.
fn on_disk(vfs: Option<&str>) -> bool {
    if cfg!(all(target_family = "wasm", target_os = "unknown")) {
        return false;
    }
    vfs.is_none_or(|v| v == "unix" || v.starts_with("unix-"))
}

/// A path in a VFS without a file system, normalised: empty and `.`
/// components dropped, `..` taking the one before it, from a leading `/`.
fn lexical(path: &Path) -> String {
    let text = path.to_string_lossy();
    let mut parts: Vec<&str> = Vec::new();
    for part in text.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                parts.pop();
            }
            p => parts.push(p),
        }
    }
    format!("/{}", parts.join("/"))
}

/// The key a file is claimed under (S4), so that two spellings of one file
/// are one claim (REVIEW-439 note 8):
/// - on the file system (the default VFS or a `unix` one, natively), `file:`
///   and the canonical path, whichever of those VFSes names it;
/// - in a VFS of its own (a `sahpool` pool, `memvfs`), the VFS name and the
///   [`lexical`] path, so `crema-db:history.db` and `crema-db:/history.db`
///   are one claim. On wasm32 no VFS named is `memvfs`, the default there.
///
/// A key may join two names the VFS keeps apart (`sahpool` names files
/// exactly as given), which refuses an open it could have allowed. It never
/// splits one file into two claims.
pub(crate) fn file_key(path: &Path, vfs: Option<&str>) -> Result<String> {
    if !on_disk(vfs) {
        return Ok(format!("{}:{}", vfs.unwrap_or("memvfs"), lexical(path)));
    }
    let canonical = match path.canonicalize() {
        Ok(p) => p,
        Err(_) => {
            let name = path
                .file_name()
                .ok_or_else(|| StoreError::CantOpen(format!("{}: no file name", path.display())))?;
            let parent = match path.parent() {
                Some(p) if !p.as_os_str().is_empty() => p,
                _ => Path::new("."),
            };
            parent
                .canonicalize()
                .map_err(|e| StoreError::CantOpen(format!("{}: {e}", parent.display())))?
                .join(name)
        }
    };
    Ok(format!("file:{}", canonical.display()))
}

/// What an open learned about the file.
struct Opened {
    info: FileInfo,
    writable: bool,
    write_seq: u64,
    quick_checked: bool,
}

/// The state a write carries: the `write_seq` it will commit, bumped once by
/// the first change.
pub(crate) struct TxState {
    seq: u64,
    dirty: bool,
}

impl TxState {
    /// The write's seq, bumping `write_seq` on the first call.
    pub(crate) fn next(&mut self) -> u64 {
        if !self.dirty {
            self.dirty = true;
            self.seq += 1;
        }
        self.seq
    }
}

/// One connection to one history file.
pub struct Store {
    // Declared first, so it closes before the claim is released.
    conn: Connection,
    path: PathBuf,
    opts: OpenOptions,
    info: FileInfo,
    writable: bool,
    write_seq: u64,
    fts: FtsStatus,
    /// Merge steps may have work: rows were written since the last empty one.
    merge_pending: bool,
    derive: rewrite::State,
    busy: bool,
    fts_batch_rows: u32,
    derive_batch_rows: u32,
    quick_checked: bool,
    walks: export::Walks,
    /// A removal by absence under way (the full diff's last step).
    absent: Option<index::AbsentScan>,
    /// The index is set aside after a reopen, unchecked
    /// ([`OpenOptions::fts_check_on_reopen`] `false`): searches scan, and
    /// the next [`Store::maintain`] starts a rebuild.
    fts_suspect: bool,
    faults: Faults,
    poisoned: Option<StoreError>,
    closed: bool,
    _claim: Claim,
}

impl std::fmt::Debug for Store {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Store")
            .field("path", &self.path)
            .field("info", &self.info)
            .field("write_seq", &self.write_seq)
            .field("fts", &self.fts)
            .finish_non_exhaustive()
    }
}

impl Store {
    /// Opens `path` and checks it (SPEC §11, "Store lost or unreadable"):
    /// - not a database, or `quick_check` failing: [`StoreError::Corrupt`];
    /// - another `application_id`, or no `meta`: [`StoreError::Foreign`];
    /// - `min_reader_v` above this build: [`StoreError::TooNew`];
    /// - another role: [`StoreError::WrongRole`];
    /// - missing (without `create`), unopenable: [`StoreError::CantOpen`];
    /// - read-only: [`StoreError::ReadOnly`];
    /// - already open in this process: [`StoreError::AlreadyOpen`] (S4).
    ///
    /// `quick_check` runs after a session that didn't close cleanly, when
    /// [`OpenOptions::quick_check`] asks, and weekly. None of these failures
    /// writes to the file or removes it.
    ///
    /// # Errors
    /// As above.
    pub fn open(path: impl AsRef<Path>, opts: OpenOptions) -> Result<Store> {
        let path = path.as_ref().to_path_buf();
        let claim = Claim::take(file_key(&path, opts.vfs.as_deref())?)?;
        let check = if opts.quick_check {
            Check::Quick
        } else {
            Check::Scheduled
        };
        let (conn, opened) = open_conn(&path, &opts, check)?;
        let fts = fts::load(&conn).map_err(|e| error_of(&e, Source::Open))?;
        let derive = rewrite::State::load(&conn).map_err(|e| error_of(&e, Source::Open))?;
        let walks = export::Walks::new(opts.now_ms);
        Ok(Store {
            conn,
            path,
            opts,
            info: opened.info,
            writable: opened.writable,
            write_seq: opened.write_seq,
            fts,
            merge_pending: false,
            derive,
            busy: false,
            fts_batch_rows: FTS_BATCH_ROWS,
            derive_batch_rows: DERIVE_BATCH_ROWS,
            quick_checked: opened.quick_checked,
            walks,
            fts_suspect: false,
            absent: None,
            faults: Faults::default(),
            poisoned: None,
            closed: false,
            _claim: claim,
        })
    }

    /// Closes the file cleanly, so the next open needn't run `quick_check`.
    ///
    /// # Errors
    /// When the clean-close mark can't be written; the file still closes.
    pub fn close(mut self) -> Result<()> {
        self.closed = true;
        if self.poisoned.is_some() || !self.writable {
            return Ok(());
        }
        self.conn
            .execute("UPDATE meta SET value = '0' WHERE key = 'open'", [])
            .map(|_| ())
            .map_err(|e| error_of(&e, Source::Session))
    }

    /// What the file's `meta` said at open.
    #[must_use]
    pub fn info(&self) -> &FileInfo {
        &self.info
    }

    /// The last committed `write_seq`.
    #[must_use]
    pub fn write_seq(&self) -> u64 {
        self.write_seq
    }

    /// The store's health.
    #[must_use]
    pub fn health(&self) -> Health {
        Health {
            reindexing: self.fts != FtsStatus::Ready || self.fts_suspect || self.derive.pending,
            fts: self.fts,
            derive_pending: self.derive.pending,
            writable: self.writable,
            quick_checked: self.quick_checked,
        }
    }

    /// Moves what the WAL holds into the main file, without waiting on any
    /// reader (`PRAGMA wal_checkpoint(PASSIVE)`): the shell calls it after a
    /// shot's save, so the shot is in the main file and a damaged `-wal`
    /// can't take it with it. A rollback-journal file has nothing to move.
    ///
    /// # Errors
    /// A SQLite error class; the write it follows has committed either way.
    pub fn checkpoint(&mut self) -> Result<()> {
        self.usable()?;
        self.conn
            .query_row("PRAGMA wal_checkpoint(PASSIVE)", [], |_| Ok(()))
            .map_err(|e| error_of(&e, Source::Session))
    }

    /// The shell says a shot is running (SPEC §9): while `busy`, maintenance
    /// does nothing.
    pub fn set_busy(&mut self, busy: bool) {
        self.busy = busy;
    }

    /// The rows each maintenance batch takes (at least 1), for the search
    /// index rebuild and the derive rewrite alike; by default
    /// [`FTS_BATCH_ROWS`] and [`DERIVE_BATCH_ROWS`].
    pub fn set_batch_rows(&mut self, rows: u32) {
        self.fts_batch_rows = rows.max(1);
        self.derive_batch_rows = rows.max(1);
    }

    /// The fault hook (`faults` feature).
    #[cfg(feature = "faults")]
    #[must_use]
    pub fn faults(&self) -> &Faults {
        &self.faults
    }

    /// The raw connection, for tests that plant damage (`faults` feature).
    #[cfg(feature = "faults")]
    #[must_use]
    pub fn raw(&self) -> &Connection {
        &self.conn
    }

    /// Closes and reopens the file as a mid-session `SQLITE_CORRUPT` does,
    /// with `quick_check` (`faults` feature): the path
    /// [`OpenOptions::fts_check_on_reopen`] decides.
    #[cfg(feature = "faults")]
    pub fn reopen_as_after_corrupt(&mut self) -> StoreError {
        self.reopen(Check::Quick)
    }

    /// How searches run now: through the index when it is whole, by scan
    /// while it is being rebuilt or after it was dropped (M6).
    #[must_use]
    pub fn text_path(&self) -> TextPath {
        if self.fts == FtsStatus::Ready && !self.fts_suspect {
            TextPath::Fts
        } else {
            TextPath::Scan
        }
    }

    /// Fails when the store can't be used at all.
    fn usable(&self) -> Result<()> {
        match &self.poisoned {
            Some(e) => Err(StoreError::Poisoned(Box::new(e.clone()))),
            None => Ok(()),
        }
    }

    /// Fails when the store can't be written.
    fn writable(&self) -> Result<()> {
        self.usable()?;
        if self.writable {
            Ok(())
        } else {
            Err(StoreError::ReadOnlyTooNew {
                min_writer_v: self.info.min_writer_v,
            })
        }
    }

    /// Runs `body` in one `IMMEDIATE` transaction and commits it, with the
    /// new `write_seq` when the body changed anything. The fault points
    /// `commit` and `committed` fail just before and just after the commit.
    ///
    /// When the body fails on the search index, the store drops it (M6) and
    /// runs the body once more. When it fails with `SQLITE_CORRUPT` elsewhere,
    /// the store reopens the file and the open-time checks decide (M6).
    fn write<T>(
        &mut self,
        commit: &'static str,
        committed: &'static str,
        mut body: impl FnMut(&Transaction<'_>, &mut TxState, &Faults) -> std::result::Result<T, Fail>,
    ) -> Result<(T, TxState)> {
        self.writable()?;
        let mut retried = false;
        loop {
            let attempt = {
                let mut st = TxState {
                    seq: self.write_seq,
                    dirty: false,
                };
                let mut run = || -> std::result::Result<T, Fail> {
                    let tx =
                        Transaction::new_unchecked(&self.conn, TransactionBehavior::Immediate)?;
                    let out = body(&tx, &mut st, &self.faults)?;
                    if st.dirty {
                        tx.execute(
                            "UPDATE meta SET value = ?1 WHERE key = 'write_seq'",
                            [st.seq.to_string()],
                        )?;
                    }
                    self.faults.hit(commit)?;
                    tx.commit()?;
                    Ok(out)
                };
                run().map(|out| (out, st))
            };
            match attempt {
                Ok((out, st)) => {
                    self.write_seq = st.seq;
                    if st.dirty {
                        self.merge_pending = true;
                    }
                    self.faults.hit(committed)?;
                    return Ok((out, st));
                }
                Err(fail) => match self.settle(fail) {
                    Settled::Retry if !retried => retried = true,
                    Settled::Retry => {
                        return Err(StoreError::FtsDamaged(
                            "the write failed again after the index was dropped".into(),
                        ));
                    }
                    Settled::Error(e) => return Err(e),
                },
            }
        }
    }

    /// Acts on a failed statement: drops a damaged search index (the caller
    /// may retry), reopens after a mid-session corrupt error, or gives the
    /// error.
    fn settle(&mut self, fail: Fail) -> Settled {
        let (e, source) = match fail {
            Fail::Store(e) => return Settled::Error(e),
            Fail::Sql(e, source) => (e, source),
        };
        match classify(&e, source) {
            Class::Error(StoreError::FtsDamaged(_)) => match self.on_fts_damage() {
                Ok(()) => Settled::Retry,
                Err(e) => Settled::Error(e),
            },
            Class::Error(e) => Settled::Error(e),
            Class::Reopen { full, .. } => {
                Settled::Error(self.reopen(if full { Check::Full } else { Check::Quick }))
            }
        }
    }

    /// Closes and reopens the file after a mid-session `SQLITE_CORRUPT`, with
    /// `quick_check` (M6). Returns the error the caller gets: `Reopened` when
    /// the checks passed, else theirs, and the store is then unusable.
    fn reopen(&mut self, check: Check) -> StoreError {
        let placeholder = match Connection::open_in_memory() {
            Ok(c) => c,
            Err(e) => {
                let err = StoreError::Sqlite(e.to_string());
                self.poisoned = Some(err.clone());
                return err;
            }
        };
        drop(std::mem::replace(&mut self.conn, placeholder));
        let mut opts = self.opts.clone();
        opts.create = false;
        self.walks.clear();
        self.absent = None;
        let reopened = open_conn(&self.path, &opts, check).and_then(|(conn, opened)| {
            let fts = fts::load(&conn).map_err(|e| error_of(&e, Source::Open))?;
            let derive = rewrite::State::load(&conn).map_err(|e| error_of(&e, Source::Open))?;
            Ok((conn, opened, fts, derive))
        });
        match reopened {
            Ok((conn, opened, fts, derive)) => {
                self.conn = conn;
                self.info = opened.info;
                self.writable = opened.writable;
                self.write_seq = opened.write_seq;
                self.quick_checked = opened.quick_checked;
                self.fts = fts;
                self.derive = derive;
                // The main database is whole, so the error may have come
                // from the search index's own tables through a trigger. Run
                // its check here, not through `settle`, so a corrupt answer
                // drops the index rather than reopening again. Or, where one
                // statement over the whole index is too long (web, S15), set
                // the index aside: searches scan, and the next `maintain`
                // rebuilds it in batches (REVIEW-439 note 6).
                if self.fts == FtsStatus::Ready && !self.opts.fts_check_on_reopen {
                    self.fts_suspect = true;
                } else if self.fts == FtsStatus::Ready {
                    let check = self.conn.execute(
                        "INSERT INTO shot_fts (shot_fts, rank) VALUES ('integrity-check', 1)",
                        [],
                    );
                    if let Err(e) = check {
                        let damaged = e.sqlite_error().is_some_and(|x| {
                            let p = x.extended_code & 0xff;
                            p == rusqlite::ffi::SQLITE_CORRUPT || p == rusqlite::ffi::SQLITE_NOTADB
                        });
                        if !damaged {
                            return error_of(&e, Source::Session);
                        }
                        if let Err(e) = self.on_fts_damage() {
                            return e;
                        }
                    }
                }
                StoreError::Reopened
            }
            Err(e) => {
                self.poisoned = Some(e.clone());
                e
            }
        }
    }
}

/// What [`Store::settle`] decided.
enum Settled {
    /// The search index was dropped; run the statement again.
    Retry,
    /// Give this error.
    Error(StoreError),
}

impl Drop for Store {
    fn drop(&mut self) {
        if !self.closed && self.poisoned.is_none() && self.writable {
            // Best effort: a process that dies here leaves the mark, and the
            // next open runs quick_check.
            let _ = self
                .conn
                .execute("UPDATE meta SET value = '0' WHERE key = 'open'", []);
        }
    }
}

/// Opens one connection and runs the open-time checks.
/// Which check an open runs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Check {
    /// `quick_check` when the schedule says: after an unclean close, weekly.
    Scheduled,
    /// `quick_check` now.
    Quick,
    /// `integrity_check` now: after an index disagreed with its table
    /// (`SQLITE_CORRUPT_INDEX`), which `quick_check` doesn't look at.
    Full,
}

fn open_conn(path: &Path, opts: &OpenOptions, check: Check) -> Result<(Connection, Opened)> {
    let open_err = |e: rusqlite::Error| error_of(&e, Source::Open);
    let mut flags = OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX;
    if opts.create {
        flags |= OpenFlags::SQLITE_OPEN_CREATE;
    }
    let conn = match &opts.vfs {
        Some(vfs) => Connection::open_with_flags_and_vfs(path, flags, vfs.as_str()),
        None => Connection::open_with_flags(path, flags),
    }
    .map_err(open_err)?;
    conn.busy_timeout(Duration::from_millis(u64::from(opts.busy_timeout_ms)))
        .map_err(open_err)?;
    conn.execute_batch(PRAGMAS).map_err(open_err)?;
    #[cfg(feature = "faults")]
    {
        let pages = faults::MAX_PAGES_FOR_TEST.load(std::sync::atomic::Ordering::Relaxed);
        if pages > 0 {
            // SQLite never sets it below the file's size: any growth is `SQLITE_FULL`.
            conn.query_row(&format!("PRAGMA max_page_count = {pages}"), [], |r| {
                r.get::<_, i64>(0)
            })
            .map_err(open_err)?;
        }
    }
    // WAL without shared memory needs exclusive locking before the file is
    // first read (REVIEW-439 note 1). A connection setting: it writes nothing.
    if opts.journal == JournalMode::ExclusiveWal {
        conn.query_row("PRAGMA locking_mode = EXCLUSIVE", [], |r| {
            r.get::<_, String>(0)
        })
        .map_err(open_err)?;
    }
    if conn.is_readonly("main").map_err(open_err)? {
        return Err(StoreError::ReadOnly);
    }
    // The first statements that read the file: one that isn't a database
    // fails here with SQLITE_NOTADB. They only read.
    let app: i32 = conn
        .query_row("PRAGMA application_id", [], |r| r.get(0))
        .map_err(open_err)?;
    let objects: i64 = conn
        .query_row("SELECT count(*) FROM sqlite_schema", [], |r| r.get(0))
        .map_err(open_err)?;
    let fresh = app == 0 && objects == 0;
    if fresh && !opts.create {
        return Err(StoreError::Foreign("an empty file".into()));
    }
    if !fresh && app != APPLICATION_ID {
        return Err(StoreError::Foreign(format!("application_id {app:#x}")));
    }
    if fresh {
        set_modes(&conn, opts)?;
        create_schema(&conn, opts).map_err(open_err)?;
    }
    let created = fresh;
    let meta = read_meta(&conn)?;
    let num = |key: &str| -> Result<u64> {
        meta.get(key)
            .and_then(|v| v.parse::<u64>().ok())
            .ok_or_else(|| StoreError::Foreign(format!("meta.{key} missing or not a number")))
    };
    let small = |key: &str| -> Result<u32> {
        u32::try_from(num(key)?)
            .map_err(|_| StoreError::Foreign(format!("meta.{key} out of range")))
    };
    let min_reader_v = small("min_reader_v")?;
    if min_reader_v > SCHEMA_V {
        return Err(StoreError::TooNew { min_reader_v });
    }
    let role = meta
        .get("role")
        .and_then(|r| Role::parse(r))
        .ok_or_else(|| StoreError::Foreign("meta.role missing or unknown".into()))?;
    if role != opts.role {
        return Err(StoreError::WrongRole {
            found: role,
            expected: opts.role,
        });
    }
    let info = FileInfo {
        schema_v: small("schema_v")?,
        min_reader_v,
        min_writer_v: small("min_writer_v")?,
        role,
        generation: small("generation")?,
    };
    let write_seq = num("write_seq")?;
    let writable = info.min_writer_v <= SCHEMA_V;
    let unclean = meta.get("open").map(String::as_str) == Some("1");
    let last_check = meta
        .get("quick_check_ms")
        .and_then(|v| v.parse::<i64>().ok())
        .unwrap_or(0);
    let due =
        check != Check::Scheduled || unclean || opts.now_ms.saturating_sub(last_check) >= WEEK_MS;
    let quick_checked = due && !created;
    if quick_checked {
        quick_check(
            &conn,
            if check == Check::Full {
                "integrity_check"
            } else {
                "quick_check"
            },
        )?;
    }
    if !writable {
        return Ok((
            conn,
            Opened {
                info,
                writable,
                write_seq,
                quick_checked,
            },
        ));
    }
    // Only now, with the file known to be ours, whole and writable by this
    // build, anything that may write to it: the journal mode and the open
    // mark. A refused file is left exactly as it was.
    if !fresh {
        set_modes(&conn, opts)?;
    }
    {
        let tx =
            Transaction::new_unchecked(&conn, TransactionBehavior::Immediate).map_err(open_err)?;
        tx.execute("UPDATE meta SET value = '1' WHERE key = 'open'", [])
            .map_err(open_err)?;
        if quick_checked || created {
            tx.execute(
                "INSERT INTO meta (key, value) VALUES ('quick_check_ms', ?1) \
                 ON CONFLICT(key) DO UPDATE SET value = excluded.value",
                [opts.now_ms.to_string()],
            )
            .map_err(open_err)?;
        }
        tx.commit().map_err(open_err)?;
    }
    Ok((
        conn,
        Opened {
            info,
            writable,
            write_seq,
            quick_checked,
        },
    ))
}

/// The journal mode and `synchronous`: the first settings that may write to
/// the file, so they wait until it is known to be ours.
fn set_modes(conn: &Connection, opts: &OpenOptions) -> Result<()> {
    let open_err = |e: rusqlite::Error| error_of(&e, Source::Open);
    // `locking_mode = EXCLUSIVE` for `ExclusiveWal` was set before the first
    // read.
    let asked = match opts.journal {
        JournalMode::Wal | JournalMode::ExclusiveWal => "wal",
        JournalMode::Truncate => "truncate",
    };
    let got: String = conn
        .query_row(&format!("PRAGMA journal_mode = {asked}"), [], |r| r.get(0))
        .map_err(open_err)?;
    // The pragma answers with the mode it took; a VFS without shared memory
    // silently keeps `delete` for a plain WAL (REVIEW-439 note 2).
    if !got.eq_ignore_ascii_case(asked) {
        return Err(StoreError::JournalMode {
            asked: asked.to_owned(),
            got,
        });
    }
    conn.execute_batch("PRAGMA synchronous = FULL")
        .map_err(open_err)
}

/// Writes schema 1 and its `meta` into an empty file, in one transaction.
fn create_schema(conn: &Connection, opts: &OpenOptions) -> rusqlite::Result<()> {
    let tx = Transaction::new_unchecked(conn, TransactionBehavior::Immediate)?;
    tx.execute_batch(V1)?;
    tx.execute_batch(&format!("PRAGMA application_id = {APPLICATION_ID}"))?;
    let rows = [
        ("schema_v", SCHEMA_V.to_string()),
        ("min_reader_v", "1".to_owned()),
        ("min_writer_v", "1".to_owned()),
        ("role", opts.role.as_str().to_owned()),
        ("generation", opts.generation.to_string()),
        ("write_seq", "0".to_owned()),
        ("derive_v", DERIVE_V.to_string()),
        ("open", "0".to_owned()),
    ];
    for (k, v) in rows {
        tx.execute("INSERT INTO meta (key, value) VALUES (?1, ?2)", (k, v))?;
    }
    tx.commit()
}

/// Every `meta` row. A file without the table isn't ours.
fn read_meta(conn: &Connection) -> Result<HashMap<String, String>> {
    let has: Option<i64> = conn
        .query_row(
            "SELECT 1 FROM sqlite_schema WHERE type = 'table' AND name = 'meta'",
            [],
            |r| r.get(0),
        )
        .optional()
        .map_err(|e| error_of(&e, Source::Open))?;
    if has.is_none() {
        return Err(StoreError::Foreign("no meta table".into()));
    }
    let mut stmt = conn
        .prepare("SELECT key, value FROM meta")
        .map_err(|e| error_of(&e, Source::Open))?;
    let rows = stmt
        .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
        .and_then(Iterator::collect)
        .map_err(|e| error_of(&e, Source::Open))?;
    Ok(rows)
}

/// `PRAGMA quick_check` or `integrity_check`: anything but one `ok` is
/// [`StoreError::Corrupt`].
fn quick_check(conn: &Connection, pragma: &str) -> Result<()> {
    let mut stmt = conn
        .prepare(&format!("PRAGMA {pragma}"))
        .map_err(|e| error_of(&e, Source::Open))?;
    let rows: Vec<String> = stmt
        .query_map([], |r| r.get(0))
        .and_then(Iterator::collect)
        .map_err(|e| error_of(&e, Source::Open))?;
    if rows == ["ok"] {
        Ok(())
    } else {
        let first: Vec<&str> = rows.iter().take(3).map(String::as_str).collect();
        Err(StoreError::Corrupt(format!(
            "{pragma}: {}",
            first.join("; ")
        )))
    }
}

/// Builds a change set from a finished write.
pub(crate) fn change_set(
    st: &TxState,
    upserted: Vec<String>,
    deleted: Vec<String>,
    kinds: crate::ChangeKinds,
) -> ChangeSet {
    ChangeSet {
        seq: st.seq,
        upserted,
        deleted,
        kinds,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_named_vfs_keys_two_spellings_of_one_path_as_one_claim() {
        let key = |p: &str| file_key(Path::new(p), Some("crema-db")).expect("a key");
        for p in [
            "history.db",
            "/history.db",
            "//history.db",
            "./history.db",
            "/a/../history.db",
        ] {
            assert_eq!(key(p), "crema-db:/history.db", "{p}");
        }
        assert_ne!(key("/history.db"), key("/history-2.db"));
        assert_ne!(
            key("/history.db"),
            file_key(Path::new("/history.db"), Some("crema-index")).expect("a key")
        );
    }

    #[cfg(unix)]
    #[test]
    fn the_unix_vfses_and_the_default_key_one_file_as_one_claim() {
        let dir = std::env::temp_dir();
        let path = dir.join("de1-history-key.db");
        let none = file_key(&path, None).expect("a key");
        assert!(none.starts_with("file:"), "{none}");
        assert_eq!(file_key(&path, Some("unix")).expect("a key"), none);
        assert_eq!(file_key(&path, Some("unix-dotfile")).expect("a key"), none);
    }
}

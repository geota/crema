//! The store's error classes (SPEC §11, M6): each SQLite failure is classified
//! by its extended code **and** by where it came from, because the same
//! primary code means different things in different places.
//!
//! - `SQLITE_CORRUPT_VTAB` (267), or any other failure of a statement on
//!   `shot_fts`, is [`StoreError::FtsDamaged`]: derived data, never `lost`.
//!   The store drops the search index and searches by scan while it rebuilds,
//!   so callers see this class only when that path itself fails.
//! - [`StoreError::Corrupt`] comes only from the open-time checks of the main
//!   database: the header, `application_id`, `meta`, `PRAGMA quick_check`.
//! - A `SQLITE_CORRUPT` from any other statement mid-session isn't `Corrupt`:
//!   the store reopens the file, the open-time checks decide, and the caller
//!   gets [`StoreError::Reopened`] when they pass.

use rusqlite::ffi;

use super::Role;

/// What went wrong. The shells map these onto health states (SPEC §12):
/// `Corrupt` and `Foreign` are `lost` when the marker says `done`; `CantOpen`,
/// `Busy` and `ReadOnly` are `unavailable`; `TooNew` is `too_new`; `Full` is
/// `full`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StoreError {
    /// The search index is damaged and the store couldn't recover by dropping
    /// it. Derived data (rule 4): never `lost`.
    FtsDamaged(String),
    /// The open-time checks found the main database damaged: not a database,
    /// or `quick_check` failed. The file is left exactly as it is.
    Corrupt(String),
    /// The disk or quota is full (`SQLITE_FULL`). The write was rolled back.
    Full,
    /// The file is locked by another connection past the wait.
    Busy,
    /// The file couldn't be opened: missing (and not to be created), a
    /// directory, an I/O error at open.
    CantOpen(String),
    /// The file or its directory is read-only.
    ReadOnly,
    /// The file needs a newer reader (`min_reader_v` above this build).
    TooNew {
        /// The file's `min_reader_v`.
        min_reader_v: u32,
    },
    /// The file may be read but not written by this build (`min_writer_v`
    /// above it): the write was refused.
    ReadOnlyTooNew {
        /// The file's `min_writer_v`.
        min_writer_v: u32,
    },
    /// Not a Crema history file: another `application_id`, or no `meta`.
    Foreign(String),
    /// The file's `role` isn't the one asked for: an `authority` file opened
    /// as an index, or the reverse. Nothing was touched.
    WrongRole {
        /// The role the file holds.
        found: Role,
        /// The role asked for.
        expected: Role,
    },
    /// This store already has the file open (S4): one connection per file.
    AlreadyOpen(String),
    /// A statement failed with `SQLITE_CORRUPT` mid-session. The store
    /// reopened the file and its open-time checks passed; the call did
    /// nothing and may be retried.
    Reopened,
    /// The call was refused: a bad argument (a row without an id, text that
    /// isn't a JSON object, a cursor of another query) or an operation this
    /// file's role doesn't allow.
    Refused(String),
    /// An injected failure (`faults` feature), at this point.
    Fault(&'static str),
    /// A failed reopen left the store unusable; every call returns the error
    /// that reopen gave.
    Poisoned(Box<StoreError>),
    /// The file wouldn't take the journal mode asked for: WAL on a VFS
    /// without shared memory, say. Nothing was written.
    JournalMode {
        /// The mode asked for.
        asked: String,
        /// The mode SQLite answered with.
        got: String,
    },
    /// The row isn't in the query: an `Around` anchor on a row the filter
    /// leaves out. The caller clears the filter or falls back.
    NotListed(String),
    /// The export walk token isn't one this connection began, or it ended.
    UnknownWalk(u64),
    /// Any other SQLite failure, with its message.
    Sqlite(String),
}

impl std::fmt::Display for StoreError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            StoreError::FtsDamaged(m) => write!(f, "search index damaged: {m}"),
            StoreError::Corrupt(m) => write!(f, "database corrupt: {m}"),
            StoreError::Full => f.write_str("disk or quota full"),
            StoreError::Busy => f.write_str("database busy"),
            StoreError::CantOpen(m) => write!(f, "can't open the database: {m}"),
            StoreError::ReadOnly => f.write_str("database is read-only"),
            StoreError::TooNew { min_reader_v } => {
                write!(f, "written by a newer build (min_reader_v {min_reader_v})")
            }
            StoreError::ReadOnlyTooNew { min_writer_v } => {
                write!(
                    f,
                    "read-only: written by a newer build (min_writer_v {min_writer_v})"
                )
            }
            StoreError::Foreign(m) => write!(f, "not a Crema history database: {m}"),
            StoreError::WrongRole { found, expected } => {
                write!(f, "the file's role is {found:?}, not {expected:?}")
            }
            StoreError::AlreadyOpen(p) => write!(f, "{p} is already open in this store"),
            StoreError::Reopened => f.write_str("the database was reopened after an error; retry"),
            StoreError::Refused(m) => write!(f, "refused: {m}"),
            StoreError::Fault(p) => write!(f, "injected fault at {p}"),
            StoreError::Poisoned(e) => write!(f, "store unusable after a failed reopen: {e}"),
            StoreError::JournalMode { asked, got } => {
                write!(f, "journal_mode {asked} gave {got}")
            }
            StoreError::NotListed(id) => write!(f, "{id} isn't in the query"),
            StoreError::UnknownWalk(t) => write!(f, "no export walk {t} on this connection"),
            StoreError::Sqlite(m) => write!(f, "sqlite: {m}"),
        }
    }
}

impl std::error::Error for StoreError {}

/// The store's result.
pub type Result<T> = std::result::Result<T, StoreError>;

/// Where a failing statement ran, which decides its class.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum Source {
    /// The open-time checks of the main database.
    Open,
    /// A statement on `shot_fts`: a search, a merge, an integrity check.
    Fts,
    /// Any other statement in a session.
    Session,
}

/// A failure inside a store operation, before it is classified: a SQLite
/// error with the kind of statement that raised it, or a final error.
#[derive(Debug)]
pub(crate) enum Fail {
    /// A SQLite error and its statement's source.
    Sql(rusqlite::Error, Source),
    /// A final error.
    Store(StoreError),
}

impl From<rusqlite::Error> for Fail {
    /// A statement outside the search index.
    fn from(e: rusqlite::Error) -> Fail {
        Fail::Sql(e, Source::Session)
    }
}

impl From<StoreError> for Fail {
    fn from(e: StoreError) -> Fail {
        Fail::Store(e)
    }
}

/// A statement on the search index failed.
pub(crate) fn on_fts(e: rusqlite::Error) -> Fail {
    Fail::Sql(e, Source::Fts)
}

/// What a failure means, before the store acts on it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum Class {
    /// A final error.
    Error(StoreError),
    /// `SQLITE_CORRUPT` mid-session, outside the search index, or a plain
    /// (not VTAB) one from a statement that also reads `shot`: reopen, and
    /// let the open-time checks decide. `full` when the code says an index
    /// disagrees with its table, which only `integrity_check` sees.
    Reopen {
        /// The error's text.
        msg: String,
        /// Run `integrity_check`, not `quick_check`.
        full: bool,
    },
}

/// `SQLITE_CORRUPT_VTAB`: a virtual table's content is inconsistent. The
/// primary code is `SQLITE_CORRUPT`; only the extended code tells it apart.
pub(crate) const CORRUPT_VTAB: i32 = ffi::SQLITE_CORRUPT_VTAB;

/// The class of `e`, raised by a statement from `source`.
pub(crate) fn classify(e: &rusqlite::Error, source: Source) -> Class {
    let Some(err) = e.sqlite_error() else {
        return Class::Error(StoreError::Sqlite(e.to_string()));
    };
    let msg = e.to_string();
    if err.extended_code == CORRUPT_VTAB {
        return Class::Error(StoreError::FtsDamaged(msg));
    }
    let primary = err.extended_code & 0xff;
    match primary {
        ffi::SQLITE_FULL => return Class::Error(StoreError::Full),
        ffi::SQLITE_BUSY | ffi::SQLITE_LOCKED => return Class::Error(StoreError::Busy),
        ffi::SQLITE_READONLY => return Class::Error(StoreError::ReadOnly),
        ffi::SQLITE_CANTOPEN => return Class::Error(StoreError::CantOpen(msg)),
        _ => {}
    }
    let full = err.extended_code == ffi::SQLITE_CORRUPT_INDEX;
    match source {
        // A plain corrupt error on a statement that reads the index may come
        // from `shot` itself (the rebuild's `SELECT … FROM shot`): the
        // open-time checks decide, and FTS damage is found after them
        // (REVIEW-439 note 3).
        Source::Fts if primary == ffi::SQLITE_CORRUPT || primary == ffi::SQLITE_NOTADB => {
            Class::Reopen { msg, full }
        }
        // Any other failure of a statement on the search index is damage to
        // it: derived data (M6).
        Source::Fts => Class::Error(StoreError::FtsDamaged(msg)),
        Source::Open => match primary {
            ffi::SQLITE_CORRUPT | ffi::SQLITE_NOTADB => Class::Error(StoreError::Corrupt(msg)),
            ffi::SQLITE_IOERR | ffi::SQLITE_PERM | ffi::SQLITE_AUTH => {
                Class::Error(StoreError::CantOpen(msg))
            }
            _ => Class::Error(StoreError::Sqlite(msg)),
        },
        Source::Session => match primary {
            ffi::SQLITE_CORRUPT | ffi::SQLITE_NOTADB => Class::Reopen { msg, full },
            _ => Class::Error(StoreError::Sqlite(msg)),
        },
    }
}

/// [`classify`] where a reopen isn't possible: at open, and where the caller
/// only wants the final error.
pub(crate) fn error_of(e: &rusqlite::Error, source: Source) -> StoreError {
    match classify(e, source) {
        Class::Error(e) => e,
        Class::Reopen { msg, .. } => StoreError::Corrupt(msg),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn failure(extended: i32) -> rusqlite::Error {
        rusqlite::Error::SqliteFailure(ffi::Error::new(extended), None)
    }

    #[test]
    fn corrupt_vtab_is_fts_damage_wherever_it_comes_from() {
        for source in [Source::Open, Source::Fts, Source::Session] {
            assert!(matches!(
                classify(&failure(CORRUPT_VTAB), source),
                Class::Error(StoreError::FtsDamaged(_))
            ));
        }
    }

    #[test]
    fn plain_corrupt_is_corrupt_only_at_open() {
        assert!(matches!(
            classify(&failure(ffi::SQLITE_CORRUPT), Source::Open),
            Class::Error(StoreError::Corrupt(_))
        ));
        assert!(matches!(
            classify(&failure(ffi::SQLITE_NOTADB), Source::Open),
            Class::Error(StoreError::Corrupt(_))
        ));
        assert!(matches!(
            classify(&failure(ffi::SQLITE_CORRUPT), Source::Session),
            Class::Reopen { full: false, .. }
        ));
        // From a statement on the index, a plain corrupt error may be
        // `shot`'s own: the open-time checks decide (REVIEW-439 note 3).
        assert!(matches!(
            classify(&failure(ffi::SQLITE_CORRUPT), Source::Fts),
            Class::Reopen { full: false, .. }
        ));
        // An index that disagrees with its table: only integrity_check sees
        // it (REVIEW-439 note 5).
        for source in [Source::Session, Source::Fts] {
            assert!(matches!(
                classify(&failure(ffi::SQLITE_CORRUPT_INDEX), source),
                Class::Reopen { full: true, .. }
            ));
        }
    }

    #[test]
    fn any_error_on_the_search_index_is_damage_but_full_and_busy_keep_their_class() {
        assert!(matches!(
            classify(&failure(ffi::SQLITE_ERROR), Source::Fts),
            Class::Error(StoreError::FtsDamaged(_))
        ));
        assert_eq!(
            classify(&failure(ffi::SQLITE_FULL), Source::Fts),
            Class::Error(StoreError::Full)
        );
        assert_eq!(
            classify(&failure(ffi::SQLITE_BUSY), Source::Fts),
            Class::Error(StoreError::Busy)
        );
        assert!(matches!(
            classify(&failure(ffi::SQLITE_IOERR_WRITE), Source::Open),
            Class::Error(StoreError::CantOpen(_))
        ));
        assert_eq!(
            classify(&failure(ffi::SQLITE_READONLY), Source::Session),
            Class::Error(StoreError::ReadOnly)
        );
    }
}

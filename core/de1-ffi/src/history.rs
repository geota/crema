//! The SQLite history store over UniFFI (#310 C3, `.scratch/history-sqlite/`
//! SPEC §9 and PLAN C3): [`HistoryDb`] is one [`Store`], one connection to one
//! file, behind one mutex.
//!
//! **One connection, not a reader beside a writer** (REVIEW-442 note 3). The
//! store refuses a second connection to a file it has open (S4), and the
//! export walk's set, the delete holds, the search index repair and the reopen
//! after an error all live on that one connection. Every call, read or write,
//! takes the lock, so two coroutines calling at once run one after the other.
//! A long read (`sync_projection` at 100,000 rows) delays a write by its
//! length; B1 measures that.
//!
//! **`page` is for display only** (REVIEW-442 note 2). An edit that moves a
//! row's sort key across a cursor shows that row again on a later page.
//! Anything that acts on each row (bulk upload, CSV, bulk edit) reads a
//! `rid`-ordered call instead, [`HistoryDb::unsent`], [`HistoryDb::sync_rows`]
//! or the export walk, or dedupes by id.
//!
//! **An `authority` file opens with `create: false`** (REVIEW-439 note 10):
//! [`HistoryDb::open`] refuses `create` for one, so a missing `history.db`
//! is `CantOpen` (`lost` once the marker says `done`), never a fresh empty
//! truth. A new authority file comes only from [`HistoryDb::copy_to`] (the
//! flip) or [`HistoryDb::create_recovery`].
//!
//! **Erase** (S11) calls [`HistoryDb::close_all`] first: it closes the file
//! under the lock, and every later call fails with
//! [`HistoryDbError::Closed`], so no write lands on a file erase unlinked.
//!
//! Queries, pages, change sets and errors are typed records. Rows cross as
//! their `row_json` text, cursors as their encoded text, and stats as
//! `BrewHistoryStats` JSON, as [`crate::brew_history_stats`] returns them.

use std::sync::{Arc, Mutex, MutexGuard};

use de1_history::store::{
    self, Applied, CoffeeFacet, Destination, Duplicate, ExportWalk, Facets, FileInfo, FlipInit,
    FtsCheck, FtsStatus, Health, Heavy, HeavyOut, IndexState, IndexWrite, JournalMode,
    LateImported, LateRows, Maintained, OpenOptions, Page, Recovery, RidId, RidRow, Role, RowIn,
    RowOut, Store, StoreError, SyncPage, SyncRow, Write, Written,
};
use de1_history::{
    Anchor, BeanScope, ChangeKinds, ChangeSet, Cursor, Dir, Live, LiveBean, LiveMethod, Sort,
    SortKey,
};

// ── Errors ───────────────────────────────────────────────────────────────

/// What went wrong, by class (SPEC §11, §12; the store's `StoreError`). The
/// shell maps these onto health states: `Corrupt` and `Foreign` are `lost`
/// when the marker says `done`; `CantOpen`, `Busy` and `ReadOnly` are
/// `unavailable`; `TooNew` is `too_new`; `Full` is `full`. `FtsDamaged` is
/// derived data and never `lost`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Error)]
pub enum HistoryDbError {
    /// The search index is damaged and dropping it didn't recover.
    FtsDamaged { detail: String },
    /// The open-time checks found the main database damaged. The file is
    /// left exactly as it is.
    Corrupt { detail: String },
    /// The disk is full. The write was rolled back.
    Full,
    /// The file is locked by another connection past the wait.
    Busy,
    /// The file couldn't be opened: missing (an `authority` file never
    /// opens with `create`), a directory, an I/O error.
    CantOpen { detail: String },
    /// The file or its directory is read-only.
    ReadOnly,
    /// The file needs a newer reader.
    TooNew { min_reader_v: u32 },
    /// The file may be read but not written by this build.
    ReadOnlyTooNew { min_writer_v: u32 },
    /// Not a Crema history file.
    Foreign { detail: String },
    /// The file's role isn't the one asked for. Nothing was touched.
    WrongRole {
        found: HistoryRole,
        expected: HistoryRole,
    },
    /// The file is already open in this process (S4).
    AlreadyOpen { path: String },
    /// A statement failed with `SQLITE_CORRUPT` mid-session; the store
    /// reopened the file and its checks passed. The call did nothing and may
    /// be retried.
    Reopened,
    /// The call was refused: a bad argument, or an operation this file's
    /// role doesn't allow.
    Refused { detail: String },
    /// An injected failure (the store's `faults` feature; never in the app).
    Fault { point: String },
    /// A failed reopen left the store unusable: every call gives this, with
    /// the reopen's own class. The shell closes this `HistoryDb` and decides
    /// from `kind`, as for an open.
    Poisoned {
        kind: HistoryErrorKind,
        detail: String,
    },
    /// The file wouldn't take the journal mode asked for. Nothing was
    /// written.
    JournalMode { asked: String, got: String },
    /// The row isn't in the query (an `Around` anchor).
    NotListed { id: String },
    /// The export walk token isn't one this store began, or it ended.
    UnknownWalk { token: u64 },
    /// Any other SQLite failure.
    Sqlite { detail: String },
    /// [`HistoryDb::close_all`] closed this store (S11): nothing more runs
    /// on it. Open a new one.
    Closed,
    /// An earlier call panicked while it held the lock, so the store's state
    /// can't be trusted: nothing more runs on it. Close and reopen.
    Panicked,
}

/// A [`HistoryDbError`]'s class without its details: what a poisoned store's
/// reopen failed with.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryErrorKind {
    FtsDamaged,
    Corrupt,
    Full,
    Busy,
    CantOpen,
    ReadOnly,
    TooNew,
    ReadOnlyTooNew,
    Foreign,
    WrongRole,
    AlreadyOpen,
    Reopened,
    Refused,
    Fault,
    JournalMode,
    NotListed,
    UnknownWalk,
    Sqlite,
}

fn kind_of(e: &StoreError) -> HistoryErrorKind {
    match e {
        StoreError::FtsDamaged(_) => HistoryErrorKind::FtsDamaged,
        StoreError::Corrupt(_) => HistoryErrorKind::Corrupt,
        StoreError::Full => HistoryErrorKind::Full,
        StoreError::Busy => HistoryErrorKind::Busy,
        StoreError::CantOpen(_) => HistoryErrorKind::CantOpen,
        StoreError::ReadOnly => HistoryErrorKind::ReadOnly,
        StoreError::TooNew { .. } => HistoryErrorKind::TooNew,
        StoreError::ReadOnlyTooNew { .. } => HistoryErrorKind::ReadOnlyTooNew,
        StoreError::Foreign(_) => HistoryErrorKind::Foreign,
        StoreError::WrongRole { .. } => HistoryErrorKind::WrongRole,
        StoreError::AlreadyOpen(_) => HistoryErrorKind::AlreadyOpen,
        StoreError::Reopened => HistoryErrorKind::Reopened,
        StoreError::Refused(_) => HistoryErrorKind::Refused,
        StoreError::Fault(_) => HistoryErrorKind::Fault,
        // A reopen's error is never itself `Poisoned`; take its cause.
        StoreError::Poisoned(inner) => kind_of(inner),
        StoreError::JournalMode { .. } => HistoryErrorKind::JournalMode,
        StoreError::NotListed(_) => HistoryErrorKind::NotListed,
        StoreError::UnknownWalk(_) => HistoryErrorKind::UnknownWalk,
        StoreError::Sqlite(_) => HistoryErrorKind::Sqlite,
    }
}

impl From<StoreError> for HistoryDbError {
    fn from(e: StoreError) -> HistoryDbError {
        match e {
            StoreError::FtsDamaged(detail) => HistoryDbError::FtsDamaged { detail },
            StoreError::Corrupt(detail) => HistoryDbError::Corrupt { detail },
            StoreError::Full => HistoryDbError::Full,
            StoreError::Busy => HistoryDbError::Busy,
            StoreError::CantOpen(detail) => HistoryDbError::CantOpen { detail },
            StoreError::ReadOnly => HistoryDbError::ReadOnly,
            StoreError::TooNew { min_reader_v } => HistoryDbError::TooNew { min_reader_v },
            StoreError::ReadOnlyTooNew { min_writer_v } => {
                HistoryDbError::ReadOnlyTooNew { min_writer_v }
            }
            StoreError::Foreign(detail) => HistoryDbError::Foreign { detail },
            StoreError::WrongRole { found, expected } => HistoryDbError::WrongRole {
                found: found.into(),
                expected: expected.into(),
            },
            StoreError::AlreadyOpen(path) => HistoryDbError::AlreadyOpen { path },
            StoreError::Reopened => HistoryDbError::Reopened,
            StoreError::Refused(detail) => HistoryDbError::Refused { detail },
            StoreError::Fault(point) => HistoryDbError::Fault {
                point: point.to_owned(),
            },
            StoreError::Poisoned(inner) => HistoryDbError::Poisoned {
                kind: kind_of(&inner),
                detail: inner.to_string(),
            },
            StoreError::JournalMode { asked, got } => HistoryDbError::JournalMode { asked, got },
            StoreError::NotListed(id) => HistoryDbError::NotListed { id },
            StoreError::UnknownWalk(token) => HistoryDbError::UnknownWalk { token },
            StoreError::Sqlite(detail) => HistoryDbError::Sqlite { detail },
        }
    }
}

impl std::fmt::Display for HistoryDbError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HistoryDbError::Closed => f.write_str("the history store is closed"),
            HistoryDbError::Panicked => {
                f.write_str("an earlier history call panicked; the store is unusable")
            }
            HistoryDbError::Poisoned { kind, detail } => {
                write!(
                    f,
                    "store unusable after a failed reopen ({kind:?}): {detail}"
                )
            }
            HistoryDbError::Fault { point } => write!(f, "injected fault at {point}"),
            HistoryDbError::WrongRole { found, expected } => {
                write!(f, "the file's role is {found:?}, not {expected:?}")
            }
            other => {
                // The store's own wording, for every class it has.
                let e: StoreError = match other.clone() {
                    HistoryDbError::FtsDamaged { detail } => StoreError::FtsDamaged(detail),
                    HistoryDbError::Corrupt { detail } => StoreError::Corrupt(detail),
                    HistoryDbError::Full => StoreError::Full,
                    HistoryDbError::Busy => StoreError::Busy,
                    HistoryDbError::CantOpen { detail } => StoreError::CantOpen(detail),
                    HistoryDbError::ReadOnly => StoreError::ReadOnly,
                    HistoryDbError::TooNew { min_reader_v } => StoreError::TooNew { min_reader_v },
                    HistoryDbError::ReadOnlyTooNew { min_writer_v } => {
                        StoreError::ReadOnlyTooNew { min_writer_v }
                    }
                    HistoryDbError::Foreign { detail } => StoreError::Foreign(detail),
                    HistoryDbError::AlreadyOpen { path } => StoreError::AlreadyOpen(path),
                    HistoryDbError::Reopened => StoreError::Reopened,
                    HistoryDbError::Refused { detail } => StoreError::Refused(detail),
                    HistoryDbError::JournalMode { asked, got } => {
                        StoreError::JournalMode { asked, got }
                    }
                    HistoryDbError::NotListed { id } => StoreError::NotListed(id),
                    HistoryDbError::UnknownWalk { token } => StoreError::UnknownWalk(token),
                    HistoryDbError::Sqlite { detail } => StoreError::Sqlite(detail),
                    _ => return f.write_str("history store error"),
                };
                e.fmt(f)
            }
        }
    }
}

impl std::error::Error for HistoryDbError {}

fn refused(detail: impl Into<String>) -> HistoryDbError {
    HistoryDbError::Refused {
        detail: detail.into(),
    }
}

// ── Opening ──────────────────────────────────────────────────────────────

/// What a file is for (SPEC §3): `meta.role`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryRole {
    /// Stage 1's `history-index.db`: derived, disposable.
    Index,
    /// Stage 2's `history.db`: the truth.
    Authority,
}

impl From<Role> for HistoryRole {
    fn from(r: Role) -> HistoryRole {
        match r {
            Role::Index => HistoryRole::Index,
            Role::Authority => HistoryRole::Authority,
        }
    }
}

impl From<HistoryRole> for Role {
    fn from(r: HistoryRole) -> Role {
        match r {
            HistoryRole::Index => Role::Index,
            HistoryRole::Authority => Role::Authority,
        }
    }
}

/// How the file journals. Android uses `Wal`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryJournalMode {
    Wal,
    Truncate,
    ExclusiveWal,
}

impl From<HistoryJournalMode> for JournalMode {
    fn from(m: HistoryJournalMode) -> JournalMode {
        match m {
            HistoryJournalMode::Wal => JournalMode::Wal,
            HistoryJournalMode::Truncate => JournalMode::Truncate,
            HistoryJournalMode::ExclusiveWal => JournalMode::ExclusiveWal,
        }
    }
}

/// How to open a file. The platform's default VFS always.
#[derive(Debug, Clone, uniffi::Record)]
pub struct HistoryOpenOptions {
    /// The role the file must hold; any other is `WrongRole`.
    pub role: HistoryRole,
    /// Create the file with the schema when it doesn't exist or is empty.
    /// Refused for `Authority` (REVIEW-439 note 10).
    #[uniffi(default = false)]
    pub create: bool,
    /// `meta.generation` for a file this open creates.
    #[uniffi(default = 0)]
    pub generation: u32,
    pub journal: HistoryJournalMode,
    /// The time now, ms since the epoch: when the weekly `quick_check` is due.
    #[uniffi(default = 0)]
    pub now_ms: i64,
    /// Run `quick_check` at this open whatever the schedule says.
    #[uniffi(default = false)]
    pub quick_check: bool,
    /// How long a locked file is waited for before `Busy`.
    #[uniffi(default = 5000)]
    pub busy_timeout_ms: u32,
}

impl HistoryOpenOptions {
    /// From `OpenOptions::new`, so a field the store adds keeps its default
    /// here: natively `fts_check_on_reopen` stays on (REVIEW-439 note 6 is
    /// web's one long statement).
    fn to_store(&self) -> OpenOptions {
        let mut o = OpenOptions::new(self.role.into());
        o.create = self.create;
        o.generation = self.generation;
        o.journal = self.journal.into();
        o.vfs = None;
        o.now_ms = self.now_ms;
        o.quick_check = self.quick_check;
        o.busy_timeout_ms = self.busy_timeout_ms;
        o
    }
}

/// What the file's `meta` said at open.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryFileInfo {
    pub schema_v: u32,
    pub min_reader_v: u32,
    pub min_writer_v: u32,
    pub role: HistoryRole,
    pub generation: u32,
}

impl From<&FileInfo> for HistoryFileInfo {
    fn from(i: &FileInfo) -> HistoryFileInfo {
        HistoryFileInfo {
            schema_v: i.schema_v,
            min_reader_v: i.min_reader_v,
            min_writer_v: i.min_writer_v,
            role: i.role.into(),
            generation: i.generation,
        }
    }
}

/// The search index's state.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryFtsStatus {
    /// Whole and in step: searches use it.
    Ready,
    /// Being rebuilt, up to `cursor`; searches scan.
    Building { cursor: i64 },
    /// Dropped or not built; searches scan.
    Missing,
}

impl From<FtsStatus> for HistoryFtsStatus {
    fn from(s: FtsStatus) -> HistoryFtsStatus {
        match s {
            FtsStatus::Ready => HistoryFtsStatus::Ready,
            FtsStatus::Building { cursor } => HistoryFtsStatus::Building { cursor },
            FtsStatus::Missing => HistoryFtsStatus::Missing,
        }
    }
}

/// The store's health as the core sees it (SPEC §12).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryHealth {
    /// Derived data is being rebuilt; lists answer, searches scan.
    pub reindexing: bool,
    pub fts: HistoryFtsStatus,
    pub derive_pending: bool,
    /// `false` when a newer build's `min_writer_v` makes the file read-only.
    pub writable: bool,
    pub quick_checked: bool,
}

impl From<Health> for HistoryHealth {
    fn from(h: Health) -> HistoryHealth {
        HistoryHealth {
            reindexing: h.reindexing,
            fts: h.fts.into(),
            derive_pending: h.derive_pending,
            writable: h.writable,
            quick_checked: h.quick_checked,
        }
    }
}

/// What a recovery file starts from (S9).
#[derive(Debug, Clone, uniffi::Record)]
pub struct HistoryRecovery {
    /// Every id the P3 store holds at the moment of the choice.
    pub migration_source: Vec<String>,
    /// The late-row baseline (sizes and mtimes on Android). Opaque.
    pub late_baseline: String,
}

// ── Queries ──────────────────────────────────────────────────────────────

/// The sort keys (SPEC §6).
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HistorySortKey {
    Date,
    Rating,
    Profile,
    Bean,
    Yield,
    Time,
}

impl From<HistorySortKey> for SortKey {
    fn from(k: HistorySortKey) -> SortKey {
        match k {
            HistorySortKey::Date => SortKey::Date,
            HistorySortKey::Rating => SortKey::Rating,
            HistorySortKey::Profile => SortKey::Profile,
            HistorySortKey::Bean => SortKey::Bean,
            HistorySortKey::Yield => SortKey::Yield,
            HistorySortKey::Time => SortKey::Time,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryDir {
    Asc,
    Desc,
}

/// The newest grind on a bag with one grinder (#488; core `LastGrind`).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryLastGrind {
    /// The grinder id the shots were stamped with; `None` on rows from
    /// before grinder ids (matched by `grinder`, the name).
    pub grinder_id: Option<String>,
    /// The grinder, as the newest shot spells it.
    pub grinder: String,
    /// The grind, as stored.
    pub grind: String,
    /// When that shot completed, Unix epoch ms.
    pub completed_at: i64,
}

impl From<de1_domain::LastGrind> for HistoryLastGrind {
    fn from(l: de1_domain::LastGrind) -> Self {
        Self {
            grinder_id: l.grinder_id,
            grinder: l.grinder,
            grind: l.grind,
            completed_at: l.completed_at,
        }
    }
}

/// A sort: newest first by default.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct HistorySort {
    pub key: HistorySortKey,
    pub dir: HistoryDir,
}

impl From<HistorySort> for Sort {
    fn from(s: HistorySort) -> Sort {
        Sort {
            key: s.key.into(),
            dir: match s.dir {
                HistoryDir::Asc => Dir::Asc,
                HistoryDir::Desc => Dir::Desc,
            },
        }
    }
}

/// A bean filter: one bag, or every bag of one coffee.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryBeanScope {
    Bag { bean_id: String },
    Coffee { coffee_key: String },
}

/// What History lists (SPEC §6). The defaults list every row, newest first.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryQuery {
    /// Search text; under 3 folded characters is no search.
    #[uniffi(default = "")]
    pub text: String,
    #[uniffi(default = None)]
    pub since_ms: Option<i64>,
    #[uniffi(default = None)]
    pub profile: Option<String>,
    #[uniffi(default = None)]
    pub bean: Option<HistoryBeanScope>,
    #[uniffi(default = None)]
    pub method: Option<String>,
    #[uniffi(default = [])]
    pub tags_all: Vec<String>,
    #[uniffi(default = false)]
    pub pinned_only: bool,
    pub sort: HistorySort,
}

impl From<HistoryQuery> for de1_history::HistoryQuery {
    fn from(q: HistoryQuery) -> de1_history::HistoryQuery {
        de1_history::HistoryQuery {
            text: q.text,
            since_ms: q.since_ms,
            profile: q.profile,
            bean: q.bean.map(|b| match b {
                HistoryBeanScope::Bag { bean_id } => BeanScope::Bag(bean_id),
                HistoryBeanScope::Coffee { coffee_key } => BeanScope::Coffee(coffee_key),
            }),
            method: q.method,
            tags_all: q.tags_all,
            pinned_only: q.pinned_only,
            sort: q.sort.into(),
        }
    }
}

/// Where a page starts (SPEC §7). Cursors are the text a page gave.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryAnchor {
    Start,
    /// The page after this cursor (a page's `last`).
    After {
        cursor: String,
    },
    /// The page before this cursor (a page's `first`).
    Before {
        cursor: String,
    },
    /// The page at a position: a scrollbar jump.
    Offset {
        offset: u32,
    },
    /// The page holding row `id`, with `before` rows above it.
    Around {
        id: String,
        before: u32,
    },
}

fn cursor_of(text: &str) -> Result<Cursor, HistoryDbError> {
    Cursor::decode(text).map_err(|e| refused(e.to_string()))
}

impl TryFrom<HistoryAnchor> for Anchor {
    type Error = HistoryDbError;

    fn try_from(a: HistoryAnchor) -> Result<Anchor, HistoryDbError> {
        Ok(match a {
            HistoryAnchor::Start => Anchor::Start,
            HistoryAnchor::After { cursor } => Anchor::After(cursor_of(&cursor)?),
            HistoryAnchor::Before { cursor } => Anchor::Before(cursor_of(&cursor)?),
            HistoryAnchor::Offset { offset } => Anchor::Offset(offset),
            HistoryAnchor::Around { id, before } => Anchor::Around { id, before },
        })
    }
}

/// A row: its id and its `row_json`, as stored.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryRow {
    pub id: String,
    pub row_json: String,
}

impl From<RowOut> for HistoryRow {
    fn from(r: RowOut) -> HistoryRow {
        HistoryRow {
            id: r.id,
            row_json: r.row_json,
        }
    }
}

fn rows_of(rows: Vec<RowOut>) -> Vec<HistoryRow> {
    rows.into_iter().map(HistoryRow::from).collect()
}

/// A row with its `rid`: the next page starts after the last one.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryRidRow {
    pub rid: i64,
    pub id: String,
    pub row_json: String,
}

impl From<RidRow> for HistoryRidRow {
    fn from(r: RidRow) -> HistoryRidRow {
        HistoryRidRow {
            rid: r.rid,
            id: r.id,
            row_json: r.row_json,
        }
    }
}

/// A row's `rid` and id.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryRidId {
    pub rid: i64,
    pub id: String,
}

impl From<RidId> for HistoryRidId {
    fn from(r: RidId) -> HistoryRidId {
        HistoryRidId {
            rid: r.rid,
            id: r.id,
        }
    }
}

/// A page of a query, **for display only**: a row whose sort key moves
/// across a cursor can show again on a later page. See the module docs.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryPage {
    pub rows: Vec<HistoryRow>,
    /// The first row's position, when the anchor tells it.
    pub start_index: Option<u32>,
    /// The first row's cursor: `Before` it is the page above.
    pub first: Option<String>,
    /// The last row's cursor: `After` it is the page below.
    pub last: Option<String>,
    pub at_start: bool,
    pub at_end: bool,
    /// The `write_seq` it was read at (S14).
    pub seq: u64,
}

impl From<Page> for HistoryPage {
    fn from(p: Page) -> HistoryPage {
        HistoryPage {
            rows: rows_of(p.rows),
            start_index: p.start_index,
            first: p.first.map(|c| c.encode()),
            last: p.last.map(|c| c.encode()),
            at_start: p.at_start,
            at_end: p.at_end,
            seq: p.seq,
        }
    }
}

/// A value and how many rows have it.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryFacetCount {
    pub value: String,
    pub count: u32,
}

fn counts_of(v: Vec<(String, u32)>) -> Vec<HistoryFacetCount> {
    v.into_iter()
        .map(|(value, count)| HistoryFacetCount { value, count })
        .collect()
}

/// One coffee in the facets.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryCoffeeFacet {
    pub coffee_key: String,
    pub name: Option<String>,
    pub roaster: Option<String>,
    pub last_ms: i64,
    pub count: u32,
    pub bags: u32,
}

impl From<CoffeeFacet> for HistoryCoffeeFacet {
    fn from(c: CoffeeFacet) -> HistoryCoffeeFacet {
        HistoryCoffeeFacet {
            coffee_key: c.coffee_key,
            name: c.name,
            roaster: c.roaster,
            last_ms: c.last_ms,
            count: c.count,
            bags: c.bags,
        }
    }
}

/// The filter options next to a query, most used first.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryFacets {
    pub profiles: Vec<HistoryFacetCount>,
    pub methods: Vec<HistoryFacetCount>,
    pub tags: Vec<HistoryFacetCount>,
    pub coffees: Vec<HistoryCoffeeFacet>,
    pub pinned: u32,
}

impl From<Facets> for HistoryFacets {
    fn from(f: Facets) -> HistoryFacets {
        HistoryFacets {
            profiles: counts_of(f.profiles),
            methods: counts_of(f.methods),
            tags: counts_of(f.tags),
            coffees: f.coffees.into_iter().map(Into::into).collect(),
            pinned: f.pinned,
        }
    }
}

/// A row of the sync projection (SPEC §9, S3). A `held` row is deleted by
/// name (REVIEW-442 note 7), not absent.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HistorySyncRow {
    pub rid: i64,
    pub id: String,
    pub visualizer_id: Option<String>,
    pub decent_id: Option<String>,
    pub completed_at: i64,
    pub duration_ms: i64,
    pub deleted_at: Option<i64>,
    pub is_brew_log: bool,
    pub sample_count: Option<i64>,
    pub profile_name: Option<String>,
    pub held: bool,
    /// `summary.finalWeight`: the reconcile signature's weight (PLAN A5).
    pub final_weight: Option<f64>,
    /// `visualizerUpdatedAt`: the known rows' version (PLAN A5).
    pub visualizer_updated_at: Option<i64>,
}

impl From<SyncRow> for HistorySyncRow {
    fn from(r: SyncRow) -> HistorySyncRow {
        HistorySyncRow {
            rid: r.rid,
            id: r.id,
            visualizer_id: r.visualizer_id,
            decent_id: r.decent_id,
            completed_at: r.completed_at,
            duration_ms: r.duration_ms,
            deleted_at: r.deleted_at,
            is_brew_log: r.is_brew_log,
            sample_count: r.sample_count,
            profile_name: r.profile_name,
            held: r.held,
            final_weight: r.final_weight,
            visualizer_updated_at: r.visualizer_updated_at,
        }
    }
}

/// A page of the sync projection and the `write_seq` it was read at.
#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct HistorySyncPage {
    pub rows: Vec<HistorySyncRow>,
    pub seq: u64,
}

impl From<SyncPage> for HistorySyncPage {
    fn from(p: SyncPage) -> HistorySyncPage {
        HistorySyncPage {
            rows: p.rows.into_iter().map(Into::into).collect(),
            seq: p.seq,
        }
    }
}

/// Where an upload goes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryDestination {
    Visualizer,
    Decent,
}

// ── Writes ───────────────────────────────────────────────────────────────

/// A row's heavy parts (`shot_heavy`). A `null` keeps what is stored.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryHeavy {
    #[uniffi(default = None)]
    pub profile_json: Option<String>,
    #[uniffi(default = None)]
    pub brew_series_json: Option<String>,
}

/// A row to write: the P3 `row_json`, a JSON object with an `id`.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryRowIn {
    pub row_json: String,
    #[uniffi(default = None)]
    pub heavy: Option<HistoryHeavy>,
    /// Pulled from Visualizer: a new row whose `visualizer_id` another row
    /// holds isn't written (N8).
    #[uniffi(default = false)]
    pub pulled: bool,
}

impl From<HistoryRowIn> for RowIn {
    fn from(r: HistoryRowIn) -> RowIn {
        RowIn {
            row_json: r.row_json,
            heavy: r.heavy.map(|h| Heavy {
                profile_json: h.profile_json,
                brew_series_json: h.brew_series_json,
            }),
            pulled: r.pulled,
        }
    }
}

fn rows_in(rows: Vec<HistoryRowIn>) -> Vec<RowIn> {
    rows.into_iter().map(RowIn::from).collect()
}

/// A row's heavy parts as `shot_heavy` holds them (PLAN A5).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryHeavyRow {
    pub id: String,
    pub profile_json: Option<String>,
    pub brew_series_json: Option<String>,
}

impl From<HeavyOut> for HistoryHeavyRow {
    fn from(h: HeavyOut) -> HistoryHeavyRow {
        HistoryHeavyRow {
            id: h.id,
            profile_json: h.profile_json,
            brew_series_json: h.brew_series_json,
        }
    }
}

/// Step 8's import into a truth file (SPEC §11, S13): rows the file doesn't
/// hold go in, every id joins `migration_source`, and the baseline and
/// `late_ids` move, in one transaction.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryLateImport {
    pub rows: Vec<HistoryRowIn>,
    pub source_ids: Vec<String>,
    #[uniffi(default = None)]
    pub late_ids: Option<Vec<String>>,
    #[uniffi(default = None)]
    pub late_baseline: Option<String>,
}

/// What a late import did: the write, and the rows left out because the
/// file already held their id.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryLateImported {
    pub written: HistoryWritten,
    pub present: Vec<String>,
}

impl From<LateImported> for HistoryLateImported {
    fn from(l: LateImported) -> HistoryLateImported {
        HistoryLateImported {
            written: l.written.into(),
            present: l.present,
        }
    }
}

/// What the flip writes into its copy (SPEC §11 step 5, S9, S13).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryFlipInit {
    pub generation: u32,
    pub source_seq: u64,
    pub source_count: u64,
    pub migration_source: Vec<String>,
    pub late_baseline: String,
    #[uniffi(default = None)]
    pub p3_migration: Option<String>,
}

impl From<HistoryFlipInit> for FlipInit {
    fn from(f: HistoryFlipInit) -> FlipInit {
        FlipInit {
            generation: f.generation,
            source_seq: f.source_seq,
            source_count: f.source_count,
            migration_source: f.migration_source,
            late_baseline: f.late_baseline,
            p3_migration: f.p3_migration,
        }
    }
}

/// What a write changed (SPEC §10). `order` holds the moved sort keys'
/// bits: `history_change_moves_list` reads it against a query.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct HistoryChangeKinds {
    pub set: bool,
    pub order: u8,
    pub facet: bool,
    pub stats: bool,
    pub text: bool,
    pub display: bool,
}

impl From<ChangeKinds> for HistoryChangeKinds {
    fn from(k: ChangeKinds) -> HistoryChangeKinds {
        HistoryChangeKinds {
            set: k.set,
            order: k.order,
            facet: k.facet,
            stats: k.stats,
            text: k.text,
            display: k.display,
        }
    }
}

impl From<HistoryChangeKinds> for ChangeKinds {
    fn from(k: HistoryChangeKinds) -> ChangeKinds {
        ChangeKinds {
            set: k.set,
            order: k.order,
            facet: k.facet,
            stats: k.stats,
            text: k.text,
            display: k.display,
        }
    }
}

/// One write transaction's notice: its `write_seq`, the rows, the kinds.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryChangeSet {
    pub seq: u64,
    pub upserted: Vec<String>,
    pub deleted: Vec<String>,
    pub kinds: HistoryChangeKinds,
}

impl From<ChangeSet> for HistoryChangeSet {
    fn from(c: ChangeSet) -> HistoryChangeSet {
        HistoryChangeSet {
            seq: c.seq,
            upserted: c.upserted,
            deleted: c.deleted,
            kinds: c.kinds.into(),
        }
    }
}

/// A pulled row that wasn't written: `existing_id` holds its Visualizer id.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryDuplicate {
    pub id: String,
    pub existing_id: String,
}

impl From<Duplicate> for HistoryDuplicate {
    fn from(d: Duplicate) -> HistoryDuplicate {
        HistoryDuplicate {
            id: d.id,
            existing_id: d.existing_id,
        }
    }
}

/// What a write did.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryWritten {
    pub change: HistoryChangeSet,
    pub duplicates: Vec<HistoryDuplicate>,
}

impl From<Written> for HistoryWritten {
    fn from(w: Written) -> HistoryWritten {
        HistoryWritten {
            change: w.change.into(),
            duplicates: w.duplicates.into_iter().map(Into::into).collect(),
        }
    }
}

/// A bag in the live bean library.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryLiveBean {
    pub bean_id: String,
    pub name: String,
    #[uniffi(default = None)]
    pub roaster: Option<String>,
    #[uniffi(default = [])]
    pub tags: Vec<String>,
}

/// A brew method's live label.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryLiveMethod {
    pub method: String,
    pub label: String,
}

/// The live libraries the derived coffee and method columns read.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryLive {
    pub beans: Vec<HistoryLiveBean>,
    pub methods: Vec<HistoryLiveMethod>,
}

impl From<HistoryLive> for Live {
    fn from(l: HistoryLive) -> Live {
        Live {
            beans: l
                .beans
                .into_iter()
                .map(|b| LiveBean {
                    bean_id: b.bean_id,
                    name: b.name,
                    roaster: b.roaster,
                    tags: b.tags,
                })
                .collect(),
            methods: l
                .methods
                .into_iter()
                .map(|m| LiveMethod {
                    method: m.method,
                    label: m.label,
                })
                .collect(),
        }
    }
}

/// One client write, for [`HistoryDb::apply`].
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryWrite {
    Upsert { rows: Vec<HistoryRowIn> },
    Delete { ids: Vec<String> },
    SetLive { live: HistoryLive },
}

impl From<HistoryWrite> for Write {
    fn from(w: HistoryWrite) -> Write {
        match w {
            HistoryWrite::Upsert { rows } => Write::Upsert(rows_in(rows)),
            HistoryWrite::Delete { ids } => Write::Delete(ids),
            HistoryWrite::SetLive { live } => Write::SetLive(live.into()),
        }
    }
}

/// What [`HistoryDb::apply`] did (M11).
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryApplied {
    Done {
        written: HistoryWritten,
    },
    AlreadyApplied {
        last: u64,
    },
    /// An earlier seq hasn't arrived; nothing was written.
    Gap {
        last: u64,
    },
    /// Refused for good; its seq is consumed.
    Refused {
        reason: String,
    },
}

impl From<Applied> for HistoryApplied {
    fn from(a: Applied) -> HistoryApplied {
        match a {
            Applied::Done(w) => HistoryApplied::Done { written: w.into() },
            Applied::AlreadyApplied { last } => HistoryApplied::AlreadyApplied { last },
            Applied::Gap { last } => HistoryApplied::Gap { last },
            Applied::Refused { reason } => HistoryApplied::Refused { reason },
        }
    }
}

/// A begun export walk: its token and the header's count.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Record)]
pub struct HistoryExportWalk {
    pub token: u64,
    pub count: u32,
}

impl From<ExportWalk> for HistoryExportWalk {
    fn from(w: ExportWalk) -> HistoryExportWalk {
        HistoryExportWalk {
            token: w.token,
            count: w.count,
        }
    }
}

// ── Maintenance and the stage-1 index ────────────────────────────────────

/// What one maintenance step did.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryMaintained {
    Idle,
    Busy,
    Derived { rows: u32, change: HistoryChangeSet },
    DeriveDone,
    FtsStarted,
    FtsBatch { rows: u32 },
    FtsSwapped,
    Merged { more: bool },
}

impl From<Maintained> for HistoryMaintained {
    fn from(m: Maintained) -> HistoryMaintained {
        match m {
            Maintained::Idle => HistoryMaintained::Idle,
            Maintained::Busy => HistoryMaintained::Busy,
            Maintained::Derived { rows, change } => HistoryMaintained::Derived {
                rows,
                change: change.into(),
            },
            Maintained::DeriveDone => HistoryMaintained::DeriveDone,
            Maintained::FtsStarted => HistoryMaintained::FtsStarted,
            Maintained::FtsBatch { rows } => HistoryMaintained::FtsBatch { rows },
            Maintained::FtsSwapped => HistoryMaintained::FtsSwapped,
            Maintained::Merged { more } => HistoryMaintained::Merged { more },
        }
    }
}

/// What a search index integrity check found.
#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum HistoryFtsCheck {
    Ok,
    /// Dropped; a rebuild is due.
    Damaged,
    /// Not checked now: no whole index to check (building or missing), or a
    /// shot is running. Try again when idle; never read it as a pass.
    NotReady,
}

impl From<FtsCheck> for HistoryFtsCheck {
    fn from(c: FtsCheck) -> HistoryFtsCheck {
        match c {
            FtsCheck::Ok => HistoryFtsCheck::Ok,
            FtsCheck::Damaged => HistoryFtsCheck::Damaged,
            FtsCheck::NotReady => HistoryFtsCheck::NotReady,
        }
    }
}

/// One write into the stage-1 index, in one transaction.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryIndexWrite {
    #[uniffi(default = [])]
    pub upsert: Vec<HistoryRowIn>,
    #[uniffi(default = [])]
    pub delete: Vec<String>,
    #[uniffi(default = [])]
    pub seen_seqs: Vec<u64>,
    #[uniffi(default = false)]
    pub lower_pending: bool,
}

/// The index's bookkeeping, for the boot check.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct HistoryIndexState {
    pub source_seq: u64,
    pub seen_seqs: Vec<u64>,
    pub pending: u64,
    pub diff_version_code: Option<i64>,
}

impl From<IndexState> for HistoryIndexState {
    fn from(s: IndexState) -> HistoryIndexState {
        HistoryIndexState {
            source_seq: s.source_seq,
            seen_seqs: s.seen_seqs,
            pending: s.pending,
            diff_version_code: s.diff_version_code,
        }
    }
}

// ── The object ───────────────────────────────────────────────────────────

/// One history file, open: one [`Store`] behind one mutex. See the module
/// docs for the rules a shell follows.
#[derive(uniffi::Object)]
pub struct HistoryDb {
    path: String,
    /// `None` once [`HistoryDb::close_all`] ran.
    store: Mutex<Option<Store>>,
}

impl std::fmt::Debug for HistoryDb {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HistoryDb")
            .field("path", &self.path)
            .finish_non_exhaustive()
    }
}

type HResult<T> = Result<T, HistoryDbError>;

impl HistoryDb {
    fn new(path: String, store: Store) -> Arc<HistoryDb> {
        Arc::new(HistoryDb {
            path,
            store: Mutex::new(Some(store)),
        })
    }

    /// The store, under the lock. A lock a panicking call poisoned fails
    /// closed: the store's in-memory state may not match its file.
    fn lock(&self) -> HResult<MutexGuard<'_, Option<Store>>> {
        self.store.lock().map_err(|_| HistoryDbError::Panicked)
    }

    /// Runs `f` on the open store, under the lock.
    fn with<R>(&self, f: impl FnOnce(&mut Store) -> store::Result<R>) -> HResult<R> {
        let mut guard = self.lock()?;
        let store = guard.as_mut().ok_or(HistoryDbError::Closed)?;
        f(store).map_err(HistoryDbError::from)
    }

    /// Runs `f` on the open store for a value the store gives without error.
    fn infallible<R>(&self, f: impl FnOnce(&mut Store) -> R) -> HResult<R> {
        self.with(|s| Ok(f(s)))
    }
}

#[uniffi::export]
impl HistoryDb {
    /// Opens `path` and checks it (the store's `Store::open`). An
    /// `authority` file is never created here (REVIEW-439 note 10): a
    /// missing one is `CantOpen`.
    ///
    /// # Errors
    /// Every class an open gives; `Refused` for `create` with `Authority`.
    #[uniffi::constructor]
    pub fn open(path: String, options: HistoryOpenOptions) -> HResult<Arc<HistoryDb>> {
        if options.create && options.role == HistoryRole::Authority {
            return Err(refused(
                "an authority file opens with create: false; a new one comes from \
                 copyTo or createRecovery",
            ));
        }
        let store = Store::open(&path, options.to_store())?;
        Ok(HistoryDb::new(path, store))
    }

    /// Creates the recovery file `path` (`history-<n>.db`) after `lost`
    /// (S9), an `authority` file whose baseline is `recovery`, and opens it.
    /// A file there that holds anything is refused and left as it is.
    ///
    /// # Errors
    /// `Refused` for a file that holds something; every class an open gives.
    #[uniffi::constructor]
    pub fn create_recovery(
        path: String,
        options: HistoryOpenOptions,
        recovery: HistoryRecovery,
    ) -> HResult<Arc<HistoryDb>> {
        let init = Recovery {
            migration_source: recovery.migration_source,
            late_baseline: recovery.late_baseline,
        };
        let store = Store::create_recovery(&path, options.to_store(), &init)?;
        Ok(HistoryDb::new(path, store))
    }

    /// The path this store opened.
    pub fn path(&self) -> String {
        self.path.clone()
    }

    /// Closes the file under the lock (S11), cleanly when it can. Erase
    /// calls this before it removes any file. Every later call fails with
    /// `Closed`; closing again does nothing. It closes even when an earlier
    /// call panicked.
    pub fn close_all(&self) {
        let mut guard = self
            .store
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        if let Some(store) = guard.take() {
            // A failed clean-close mark only means the next open runs
            // quick_check; the connection closes either way.
            let _ = store.close();
        }
    }

    /// Whether [`HistoryDb::close_all`] ran.
    pub fn is_closed(&self) -> bool {
        self.store
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .is_none()
    }

    /// What the file's `meta` said at open.
    ///
    /// # Errors
    /// `Closed`, `Panicked`.
    pub fn info(&self) -> HResult<HistoryFileInfo> {
        self.infallible(|s| s.info().into())
    }

    /// The last committed `write_seq`.
    ///
    /// # Errors
    /// `Closed`, `Panicked`.
    pub fn write_seq(&self) -> HResult<u64> {
        self.infallible(|s| s.write_seq())
    }

    /// The store's health.
    ///
    /// # Errors
    /// `Closed`, `Panicked`.
    pub fn health(&self) -> HResult<HistoryHealth> {
        self.infallible(|s| s.health().into())
    }

    /// Moves the WAL's committed writes into the main file, without waiting
    /// on a reader (`wal_checkpoint(PASSIVE)`), after a shot's save.
    ///
    /// # Errors
    /// `Closed`, `Panicked`, a SQLite error class.
    pub fn checkpoint(&self) -> HResult<()> {
        self.with(Store::checkpoint)
    }

    /// A shot is running: maintenance does nothing while `busy`.
    ///
    /// # Errors
    /// `Closed`, `Panicked`.
    pub fn set_busy(&self, busy: bool) -> HResult<()> {
        self.infallible(|s| s.set_busy(busy))
    }

    /// The rows each maintenance batch takes.
    ///
    /// # Errors
    /// `Closed`, `Panicked`.
    pub fn set_batch_rows(&self, rows: u32) -> HResult<()> {
        self.infallible(|s| s.set_batch_rows(rows))
    }

    // ── Writes ──

    /// Inserts or updates rows by id, in one transaction.
    ///
    /// # Errors
    /// A store error class; `Refused` for a row that isn't a JSON object
    /// with an id.
    pub fn upsert(&self, rows: Vec<HistoryRowIn>) -> HResult<HistoryWritten> {
        let rows = rows_in(rows);
        self.with(|s| s.upsert(&rows)).map(Into::into)
    }

    /// Deletes rows by name (rule 1), children first. During an export walk
    /// a row is held instead, until [`HistoryDb::release_holds`].
    ///
    /// # Errors
    /// A store error class.
    pub fn delete(&self, ids: Vec<String>) -> HResult<HistoryChangeSet> {
        self.with(|s| s.delete(&ids)).map(Into::into)
    }

    /// Replaces the live bean and method libraries.
    ///
    /// # Errors
    /// A store error class.
    pub fn set_live(&self, live: HistoryLive) -> HResult<HistoryChangeSet> {
        let live: Live = live.into();
        self.with(|s| s.set_live(&live)).map(Into::into)
    }

    /// A client's write with its per-tab seq (M11).
    ///
    /// # Errors
    /// A store error class.
    pub fn apply(
        &self,
        tab: String,
        seq: u64,
        at_ms: i64,
        write: HistoryWrite,
    ) -> HResult<HistoryApplied> {
        let write: Write = write.into();
        self.with(|s| s.apply(&tab, seq, at_ms, &write))
            .map(Into::into)
    }

    /// A tab's last applied seq; 0 for one the store doesn't know.
    ///
    /// # Errors
    /// A store error class.
    pub fn applied_seq(&self, tab: String) -> HResult<u64> {
        self.with(|s| s.applied_seq(&tab))
    }

    /// Forgets gone tabs' applied seqs written before `before_ms`.
    ///
    /// # Errors
    /// A store error class.
    pub fn prune_tabs(&self, gone: Vec<String>, before_ms: i64) -> HResult<u64> {
        self.with(|s| s.prune_tabs(&gone, before_ms))
            .map(|n| u64::try_from(n).unwrap_or(u64::MAX))
    }

    // ── Queries ──

    /// How many rows `query` lists.
    ///
    /// # Errors
    /// A store error class.
    pub fn count(&self, query: HistoryQuery) -> HResult<u32> {
        let q = query.into();
        self.with(|s| s.count(&q))
    }

    /// A page of `query` at `at`, at most `limit` rows. **For display
    /// only**; see the module docs.
    ///
    /// # Errors
    /// `Refused` for a cursor of another query, a malformed one or a `limit`
    /// of 0; `NotListed` for an `Around` row the query leaves out.
    pub fn page(&self, query: HistoryQuery, at: HistoryAnchor, limit: u32) -> HResult<HistoryPage> {
        let q = query.into();
        let at = Anchor::try_from(at)?;
        self.with(|s| s.page(&q, &at, limit)).map(Into::into)
    }

    /// Row `id`'s position in `query`, or `null`.
    ///
    /// # Errors
    /// A store error class.
    pub fn index_of(&self, query: HistoryQuery, id: String) -> HResult<Option<u32>> {
        let q = query.into();
        self.with(|s| s.index_of(&q, &id))
    }

    /// The rows `query` lists in each bucket `edges_ms` cuts, newest first.
    ///
    /// # Errors
    /// A store error class.
    pub fn group_counts(&self, query: HistoryQuery, edges_ms: Vec<i64>) -> HResult<Vec<u32>> {
        let q = query.into();
        self.with(|s| s.group_counts(&q, &edges_ms))
    }

    /// The stat strip over what `query` lists, as `BrewHistoryStats` JSON.
    ///
    /// # Errors
    /// A store error class.
    pub fn stats_json(&self, query: HistoryQuery) -> HResult<String> {
        let q = query.into();
        let stats = self.with(|s| s.stats(&q))?;
        serde_json::to_string(&stats).map_err(|e| HistoryDbError::Sqlite {
            detail: e.to_string(),
        })
    }

    /// The filter options next to `query`.
    ///
    /// # Errors
    /// A store error class.
    pub fn facets(&self, query: HistoryQuery) -> HResult<HistoryFacets> {
        let q = query.into();
        self.with(|s| s.facets(&q)).map(Into::into)
    }

    /// The visible rows among `ids`.
    ///
    /// # Errors
    /// A store error class.
    pub fn get(&self, ids: Vec<String>) -> HResult<Vec<HistoryRow>> {
        self.with(|s| s.get(&ids)).map(rows_of)
    }

    /// The rows bound to this Visualizer id.
    ///
    /// # Errors
    /// A store error class.
    pub fn by_remote_id(&self, visualizer_id: String) -> HResult<Vec<HistoryRow>> {
        self.with(|s| s.by_remote_id(&visualizer_id)).map(rows_of)
    }

    /// The rows bound to this Decent id.
    ///
    /// # Errors
    /// A store error class.
    pub fn by_decent_id(&self, decent_id: String) -> HResult<Vec<HistoryRow>> {
        self.with(|s| s.by_decent_id(&decent_id)).map(rows_of)
    }

    /// The doses of a bag's newest `limit` listed rows.
    ///
    /// # Errors
    /// A store error class.
    pub fn doses_for_bag(&self, bean_id: String, limit: u32) -> HResult<Vec<f64>> {
        self.with(|s| s.doses_for_bag(&bean_id, limit))
    }

    /// The newest grind per grinder on a bag, newest first (#488): what
    /// selecting a bag or switching grinder reads
    /// (`resolveBagSelectionJson`, `resolveGrinderSwitchJson`).
    ///
    /// # Errors
    /// A store error class.
    pub fn last_grinds_for_bag(&self, bean_id: String) -> HResult<Vec<HistoryLastGrind>> {
        self.with(|s| s.last_grinds_for_bag(&bean_id))
            .map(|v| v.into_iter().map(Into::into).collect())
    }

    /// The upload backlog for `destination`, by `rid` after `after_rid`.
    ///
    /// # Errors
    /// A store error class.
    pub fn unsent(
        &self,
        destination: HistoryDestination,
        after_rid: i64,
        limit: u32,
    ) -> HResult<Vec<HistoryRidRow>> {
        let dest = match destination {
            HistoryDestination::Visualizer => Destination::Visualizer,
            HistoryDestination::Decent => Destination::Decent,
        };
        self.with(|s| s.unsent(dest, after_rid, limit))
            .map(|v| v.into_iter().map(Into::into).collect())
    }

    /// A page of the sync projection by `rid`, with its `write_seq`.
    ///
    /// # Errors
    /// A store error class.
    pub fn sync_rows(&self, after_rid: i64, limit: u32) -> HResult<HistorySyncPage> {
        self.with(|s| s.sync_rows(after_rid, limit)).map(Into::into)
    }

    /// The whole sync projection in one read transaction (S3).
    ///
    /// # Errors
    /// A store error class.
    pub fn sync_projection(&self) -> HResult<HistorySyncPage> {
        self.with(Store::sync_projection).map(Into::into)
    }

    // ── Export walk and restore ──

    /// Begins an export walk (M3): fixes the rows a backup takes.
    ///
    /// # Errors
    /// A store error class.
    pub fn export_begin(&self) -> HResult<HistoryExportWalk> {
        self.with(Store::export_begin).map(Into::into)
    }

    /// The walk's next `n` rows, newest first as the walk began (ties by id
    /// descending, the P3 stores' order). Empty at the end.
    ///
    /// # Errors
    /// `UnknownWalk`; a store error class.
    pub fn export_next(&self, token: u64, n: u32) -> HResult<Vec<HistoryRow>> {
        self.with(|s| s.export_next(token, n)).map(rows_of)
    }

    /// Starts the walk's set again from its first row (R1a): the same rows in
    /// the same order, each as it is now. The holds stay.
    ///
    /// # Errors
    /// `UnknownWalk`; a store error class.
    pub fn export_rewind(&self, token: u64) -> HResult<()> {
        self.with(|s| s.export_rewind(token))
    }

    /// Ends the walk. Then [`HistoryDb::release_holds`].
    ///
    /// # Errors
    /// `UnknownWalk`; a store error class.
    pub fn export_end(&self, token: u64) -> HResult<()> {
        self.with(|s| s.export_end(token))
    }

    /// The held ids no open walk needs (S6). The shell deletes their details
    /// first, then calls [`HistoryDb::delete`] with them.
    ///
    /// # Errors
    /// A store error class.
    pub fn release_holds(&self) -> HResult<Vec<String>> {
        self.with(|s| s.release_holds())
    }

    /// Removes the rows of `ids` still held that no walk needs, and returns
    /// the ids it removed (SPEC "From R1w"): the shell deletes the details
    /// of exactly those after this, so a row an upsert brought back keeps
    /// its curve.
    ///
    /// # Errors
    /// A store error class.
    pub fn delete_held(&self, ids: Vec<String>) -> HResult<Vec<String>> {
        self.with(|s| s.delete_held(&ids))
    }

    /// Begins a restore: the current `write_seq` (M3).
    ///
    /// # Errors
    /// A store error class.
    pub fn restore_begin(&self) -> HResult<u64> {
        self.with(|s| s.restore_begin())
    }

    /// Upserts a restore batch, incoming rows winning.
    ///
    /// # Errors
    /// A store error class.
    pub fn restore_batch(&self, rows: Vec<HistoryRowIn>) -> HResult<HistoryWritten> {
        let rows = rows_in(rows);
        self.with(|s| s.restore_batch(&rows)).map(Into::into)
    }

    /// Up to `limit` ids by `rid` after `after_rid` that the store held when
    /// the restore began.
    ///
    /// # Errors
    /// A store error class.
    pub fn ids_created_before(
        &self,
        begin_seq: u64,
        after_rid: i64,
        limit: u32,
    ) -> HResult<Vec<HistoryRidId>> {
        self.with(|s| s.ids_created_before(begin_seq, after_rid, limit))
            .map(|v| v.into_iter().map(Into::into).collect())
    }

    // ── Maintenance ──

    /// One maintenance step: derive rewrite, index rebuild or merge.
    ///
    /// # Errors
    /// A store error class.
    pub fn maintain(&self) -> HResult<HistoryMaintained> {
        self.with(Store::maintain).map(Into::into)
    }

    /// Up to `steps` maintenance steps; how many did something.
    ///
    /// # Errors
    /// A store error class.
    pub fn maintain_all(&self, steps: u32) -> HResult<u32> {
        self.with(|s| s.maintain_all(steps))
    }

    /// Starts a search index rebuild.
    ///
    /// # Errors
    /// A store error class.
    pub fn start_fts_rebuild(&self) -> HResult<()> {
        self.with(Store::start_fts_rebuild)
    }

    /// The search index's integrity check; a failing one drops it.
    ///
    /// # Errors
    /// A store error class.
    pub fn fts_integrity_check(&self) -> HResult<HistoryFtsCheck> {
        self.with(Store::fts_integrity_check).map(Into::into)
    }

    /// The ids whose derived columns differ from this build's rules.
    ///
    /// # Errors
    /// A store error class.
    pub fn derived_mismatches(&self) -> HResult<Vec<String>> {
        self.with(|s| s.derived_mismatches())
    }

    // ── Stage-1 index (refused on `authority` files) ──

    /// A full diff batch: rows whose `row_json` differs are upserted.
    ///
    /// # Errors
    /// `Refused` on an `authority` file; a store error class.
    pub fn diff_batch(&self, rows: Vec<HistoryRowIn>) -> HResult<HistoryWritten> {
        let rows = rows_in(rows);
        self.with(|s| s.diff_batch(&rows)).map(Into::into)
    }

    /// One upkeep write into the index.
    ///
    /// # Errors
    /// `Refused` on an `authority` file; a store error class.
    pub fn index_write(&self, write: HistoryIndexWrite) -> HResult<HistoryWritten> {
        let w = IndexWrite {
            upsert: rows_in(write.upsert),
            delete: write.delete,
            seen_seqs: write.seen_seqs,
            lower_pending: write.lower_pending,
        };
        self.with(|s| s.index_write(&w)).map(Into::into)
    }

    /// The diff's delete by absence: the index only, in steps of at most
    /// 500 deletes, each its own transaction.
    ///
    /// # Errors
    /// `Refused` on an `authority` file; a store error class. Steps before a
    /// failed one stay committed, and their change set doesn't cross this
    /// boundary: after an error the shell refreshes its lists.
    pub fn remove_absent(&self, ids: Vec<String>, since_seq: u64) -> HResult<HistoryChangeSet> {
        self.with(|s| s.remove_absent(&ids, since_seq).map_err(Into::into))
            .map(Into::into)
    }

    /// Sets `source_seq`.
    ///
    /// # Errors
    /// `Refused` on an `authority` file; a store error class.
    pub fn set_source_seq(&self, seq: u64) -> HResult<()> {
        self.with(|s| s.set_source_seq(seq))
    }

    /// Restarts `source_seq` at `seq` and clears `seen_seqs` (a full diff's
    /// start on Android, REVIEW-450 B).
    ///
    /// # Errors
    /// `Refused` on an `authority` file; a store error class.
    pub fn reset_seqs(&self, seq: u64) -> HResult<()> {
        self.with(|s| s.reset_seqs(seq))
    }

    /// Raises `pending` (the Android hook's first half).
    ///
    /// # Errors
    /// `Refused` on an `authority` file; a store error class.
    pub fn bump_pending(&self) -> HResult<u64> {
        self.with(Store::bump_pending)
    }

    /// Records a finished full diff for `version_code`.
    ///
    /// # Errors
    /// `Refused` on an `authority` file; a store error class.
    pub fn finish_android_diff(&self, version_code: i64) -> HResult<()> {
        self.with(|s| s.finish_android_diff(version_code))
    }

    /// The index's bookkeeping.
    ///
    /// # Errors
    /// A store error class.
    pub fn index_state(&self) -> HResult<HistoryIndexState> {
        self.with(|s| s.index_state()).map(Into::into)
    }

    // ── Files ──

    /// `meta.migration`'s state, when the file has one.
    ///
    /// # Errors
    /// A store error class.
    pub fn migration_state(&self) -> HResult<Option<String>> {
        self.with(|s| s.migration_state())
    }

    /// The late-row baseline, when the file has one.
    ///
    /// # Errors
    /// A store error class.
    pub fn late_baseline(&self) -> HResult<Option<String>> {
        self.with(|s| s.late_baseline())
    }

    /// How many ids `migration_source` holds.
    ///
    /// # Errors
    /// A store error class.
    pub fn migration_source_len(&self) -> HResult<u64> {
        self.with(|s| s.migration_source_len())
    }

    /// Copies this file into `dest` with the online backup API: the flip and
    /// the snapshot. Refused for a destination open in this process, foreign,
    /// or a newer finished truth.
    ///
    /// # Errors
    /// `AlreadyOpen`, `Foreign`, `Corrupt`, `Refused`; a store error class.
    pub fn copy_to(&self, dest: String, journal: HistoryJournalMode) -> HResult<()> {
        self.with(|s| s.copy_to(std::path::Path::new(&dest), None, journal.into()))
    }

    /// The flip's copy (SPEC §11 step 5): this index into `dest`, then on
    /// the copy, in one transaction, `role = 'authority'`, the generation,
    /// `migration = copying`, `migration_source` and the baselines. The copy
    /// is closed when this returns; open it as `authority` to go on.
    ///
    /// # Errors
    /// As [`HistoryDb::copy_to`]; `Refused` from a file that isn't an index.
    pub fn flip_copy_to(
        &self,
        dest: String,
        journal: HistoryJournalMode,
        init: HistoryFlipInit,
    ) -> HResult<()> {
        self.with(|s| {
            s.flip_copy_to(
                std::path::Path::new(&dest),
                None,
                journal.into(),
                &init.into(),
            )
        })
    }

    /// Verify's pass (SPEC §11 step 7): `migration` from `copying` to
    /// `done`, then a checkpoint. Does nothing on a file already `done`.
    ///
    /// # Errors
    /// `Refused` on an index or a file with no `copying` migration.
    pub fn set_migration_done(&self, done_at_ms: i64) -> HResult<()> {
        self.with(|s| s.set_migration_done(done_at_ms))
    }

    /// `PRAGMA integrity_check`, whole: the problems it reports, empty when
    /// none. Reads every page: only at the flip.
    ///
    /// # Errors
    /// A store error class.
    pub fn integrity_check(&self) -> HResult<Vec<String>> {
        self.with(|s| s.integrity_check())
    }

    /// Every id in `migration_source`, sorted.
    ///
    /// # Errors
    /// A store error class.
    pub fn migration_source_ids(&self) -> HResult<Vec<String>> {
        self.with(|s| s.migration_source_ids())
    }

    /// Which of `ids` `migration_source` doesn't hold (step 8).
    ///
    /// # Errors
    /// A store error class.
    pub fn not_in_migration_source(&self, ids: Vec<String>) -> HResult<Vec<String>> {
        self.with(|s| s.not_in_migration_source(&ids))
    }

    /// The file's size in bytes, by its pages.
    ///
    /// # Errors
    /// A store error class.
    pub fn size_bytes(&self) -> HResult<u64> {
        self.with(|s| s.size_bytes())
    }

    /// A `meta` value by key (`late_baseline`, `p3_migration`).
    ///
    /// # Errors
    /// A store error class.
    pub fn meta_value(&self, key: String) -> HResult<Option<String>> {
        self.with(|s| s.meta_value(&key))
    }

    /// `shot_heavy` for `ids`; an id with none is left out (PLAN A5).
    ///
    /// # Errors
    /// A store error class.
    pub fn heavy(&self, ids: Vec<String>) -> HResult<Vec<HistoryHeavyRow>> {
        self.with(|s| s.heavy(&ids))
            .map(|v| v.into_iter().map(Into::into).collect())
    }

    /// The ids of the rows whose profile is stored.
    ///
    /// # Errors
    /// A store error class.
    pub fn ids_with_profile(&self) -> HResult<Vec<String>> {
        self.with(|s| s.ids_with_profile())
    }

    /// The flip's step 6: heavy parts into `shot_heavy` for rows the file
    /// holds, in one transaction; returns the ids it doesn't hold. Truth
    /// file only.
    ///
    /// # Errors
    /// `Refused` on an index; a store error class.
    pub fn put_heavy(&self, rows: Vec<HistoryHeavyRow>) -> HResult<Vec<String>> {
        let rows: Vec<(String, Heavy)> = rows
            .into_iter()
            .map(|r| {
                (
                    r.id,
                    Heavy {
                        profile_json: r.profile_json,
                        brew_series_json: r.brew_series_json,
                    },
                )
            })
            .collect();
        self.with(|s| s.put_heavy(&rows))
    }

    /// `meta.late_ids`: rows that arrived with no series (step 9).
    ///
    /// # Errors
    /// A store error class.
    pub fn late_ids(&self) -> HResult<Vec<String>> {
        self.with(|s| s.late_ids())
    }

    /// Series written with no row write: `write_seq` moves, so the backup
    /// mark does (REVIEW-466 note 1). Returns the new `write_seq`.
    ///
    /// # Errors
    /// A store error class.
    pub fn note_details_written(&self, at_ms: i64) -> HResult<u64> {
        self.with(|s| s.note_details_written(at_ms))
    }

    /// Step 8's import into a truth file, in one transaction.
    ///
    /// # Errors
    /// `Refused` on an index or for a row with no id; a store error class.
    pub fn import_late(&self, import: HistoryLateImport) -> HResult<HistoryLateImported> {
        let imp = LateRows {
            rows: rows_in(import.rows),
            source_ids: import.source_ids,
            late_ids: import.late_ids,
            late_baseline: import.late_baseline,
        };
        self.with(|s| s.import_late_rows(&imp)).map(Into::into)
    }
}

/// Caps every history file opened from now on at `pages` pages (0: no
/// cap), so a write that grows it fails with a real `SQLITE_FULL` (feature
/// `test-seeds`; never in the APK).
#[cfg(feature = "test-seeds")]
#[uniffi::export]
pub fn history_max_pages_for_test(pages: u32) {
    de1_history::store::faults::MAX_PAGES_FOR_TEST
        .store(pages, std::sync::atomic::Ordering::Relaxed);
}

/// Whether a change moves `query`'s list: its count or its order.
#[uniffi::export]
pub fn history_change_moves_list(kinds: HistoryChangeKinds, query: HistoryQuery) -> bool {
    ChangeKinds::from(kinds).moves_list(&query.into())
}

/// Whether a change moves `query`'s stat strip.
#[uniffi::export]
pub fn history_change_moves_stats(kinds: HistoryChangeKinds, query: HistoryQuery) -> bool {
    ChangeKinds::from(kinds).moves_stats(&query.into())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn opts(role: HistoryRole, create: bool) -> HistoryOpenOptions {
        HistoryOpenOptions {
            role,
            create,
            generation: 0,
            journal: HistoryJournalMode::Wal,
            now_ms: 0,
            quick_check: false,
            busy_timeout_ms: 5_000,
        }
    }

    fn query() -> HistoryQuery {
        HistoryQuery {
            text: String::new(),
            since_ms: None,
            profile: None,
            bean: None,
            method: None,
            tags_all: vec![],
            pinned_only: false,
            sort: HistorySort {
                key: HistorySortKey::Date,
                dir: HistoryDir::Desc,
            },
        }
    }

    fn row(id: &str, at: i64) -> HistoryRowIn {
        HistoryRowIn {
            row_json: format!(r#"{{"id":"{id}","completedAtMs":{at},"notes":"sour {id}"}}"#),
            heavy: None,
            pulled: false,
        }
    }

    fn path(dir: &tempfile::TempDir, name: &str) -> String {
        dir.path().join(name).display().to_string()
    }

    #[test]
    fn an_authority_file_is_never_created_by_open() {
        let dir = tempfile::tempdir().unwrap();
        let p = path(&dir, "history.db");
        let err = HistoryDb::open(p.clone(), opts(HistoryRole::Authority, true)).unwrap_err();
        assert!(matches!(err, HistoryDbError::Refused { .. }), "{err:?}");
        assert!(!std::path::Path::new(&p).exists());
        let err = HistoryDb::open(p.clone(), opts(HistoryRole::Authority, false)).unwrap_err();
        assert!(matches!(err, HistoryDbError::CantOpen { .. }), "{err:?}");
        assert!(!std::path::Path::new(&p).exists());
    }

    #[test]
    fn close_all_closes_under_the_lock_and_later_calls_fail() {
        let dir = tempfile::tempdir().unwrap();
        let p = path(&dir, "history-index.db");
        let db = HistoryDb::open(p.clone(), opts(HistoryRole::Index, true)).unwrap();
        db.upsert(vec![row("a", 1)]).unwrap();
        // A second connection to an open file is refused (S4).
        let again = HistoryDb::open(p.clone(), opts(HistoryRole::Index, false)).unwrap_err();
        assert!(matches!(again, HistoryDbError::AlreadyOpen { .. }));
        db.close_all();
        db.close_all();
        assert!(db.is_closed());
        assert_eq!(db.upsert(vec![row("b", 2)]), Err(HistoryDbError::Closed));
        assert_eq!(db.count(query()), Err(HistoryDbError::Closed));
        // The claim is released: the file opens again, with only `a`.
        let db = HistoryDb::open(p, opts(HistoryRole::Index, false)).unwrap();
        assert_eq!(db.count(query()).unwrap(), 1);
    }

    #[test]
    fn pages_carry_cursors_that_page_on() {
        let dir = tempfile::tempdir().unwrap();
        let db = HistoryDb::open(path(&dir, "i.db"), opts(HistoryRole::Index, true)).unwrap();
        let rows: Vec<_> = (0..5).map(|i| row(&format!("r{i}"), i)).collect();
        let w = db.upsert(rows).unwrap();
        assert_eq!(w.change.upserted.len(), 5);
        assert!(history_change_moves_list(w.change.kinds, query()));
        let first = db.page(query(), HistoryAnchor::Start, 2).unwrap();
        let ids: Vec<_> = first.rows.iter().map(|r| r.id.as_str()).collect();
        assert_eq!(ids, ["r4", "r3"]);
        assert_eq!(first.start_index, Some(0));
        let next = db
            .page(
                query(),
                HistoryAnchor::After {
                    cursor: first.last.unwrap(),
                },
                10,
            )
            .unwrap();
        let ids: Vec<_> = next.rows.iter().map(|r| r.id.as_str()).collect();
        assert_eq!(ids, ["r2", "r1", "r0"]);
        assert!(next.at_end);
        let bad = db
            .page(
                query(),
                HistoryAnchor::After {
                    cursor: "nope".into(),
                },
                10,
            )
            .unwrap_err();
        assert!(matches!(bad, HistoryDbError::Refused { .. }));
    }

    #[test]
    fn a_poisoned_lock_fails_closed_and_still_closes() {
        let dir = tempfile::tempdir().unwrap();
        let db = HistoryDb::open(path(&dir, "i.db"), opts(HistoryRole::Index, true)).unwrap();
        let held = Arc::clone(&db);
        let _ = std::thread::spawn(move || {
            let _guard = held.store.lock().unwrap();
            panic!("a call panicked under the lock");
        })
        .join();
        assert_eq!(db.count(query()), Err(HistoryDbError::Panicked));
        db.close_all();
        assert!(db.is_closed());
    }

    #[test]
    fn store_errors_keep_their_class() {
        let e: HistoryDbError =
            StoreError::Poisoned(Box::new(StoreError::Corrupt("header".into()))).into();
        assert_eq!(
            e,
            HistoryDbError::Poisoned {
                kind: HistoryErrorKind::Corrupt,
                detail: "database corrupt: header".into(),
            }
        );
        let e: HistoryDbError = StoreError::TooNew { min_reader_v: 9 }.into();
        assert_eq!(e.to_string(), "written by a newer build (min_reader_v 9)");
    }
}

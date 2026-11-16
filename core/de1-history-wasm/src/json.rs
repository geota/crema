//! The JSON the Worker's methods take and give (camelCase). Queries, anchors,
//! pages, change sets, stats and facets are `de1-history`'s own serde types;
//! this module adds the store's write and status types, and the error shape.

use de1_history::store::{
    AbsentStep, Applied, Duplicate, ExportWalk, FileInfo, FlipInit, FtsCheck, FtsStatus, Health,
    Heavy, IndexWrite, JournalMode, Maintained, Recovery, RidId, Role, RowIn, StoreError, Write,
    Written,
};
use de1_history::{ChangeSet, Live};
use serde::{Deserialize, Serialize};

use crate::pools::{PoolError, Which};

/// A row to write (`RowIn`).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RowJson {
    /// The row, as a JSON object with an `id`, as text.
    pub row_json: String,
    /// `shot_heavy` parts (Android writes them; web leaves them out).
    #[serde(default)]
    pub heavy: Option<HeavyJson>,
    /// Pulled from a remote: a duplicate `visualizerId` is skipped (N8).
    #[serde(default)]
    pub pulled: bool,
}

/// `Heavy`.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HeavyJson {
    /// The profile, as JSON text.
    #[serde(default)]
    pub profile_json: Option<String>,
    /// The brew series, as JSON text.
    #[serde(default)]
    pub brew_series_json: Option<String>,
}

impl From<RowJson> for RowIn {
    fn from(r: RowJson) -> RowIn {
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

/// Rows in.
pub fn rows_in(rows: Vec<RowJson>) -> Vec<RowIn> {
    rows.into_iter().map(RowIn::from).collect()
}

/// `Written`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WrittenJson {
    /// The change notice.
    pub change: ChangeSet,
    /// Pulled rows skipped as duplicates.
    pub duplicates: Vec<DuplicateJson>,
}

/// `Duplicate`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DuplicateJson {
    /// The pulled row's id.
    pub id: String,
    /// The row that holds its `visualizerId`.
    pub existing_id: String,
}

impl From<Written> for WrittenJson {
    fn from(w: Written) -> WrittenJson {
        WrittenJson {
            change: w.change,
            duplicates: w
                .duplicates
                .into_iter()
                .map(|Duplicate { id, existing_id }| DuplicateJson { id, existing_id })
                .collect(),
        }
    }
}

/// A client write for `apply` (`Write`), tagged by `kind`.
#[derive(Debug, Clone, Deserialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum WriteJson {
    /// `upsert`.
    Upsert {
        /// The rows.
        rows: Vec<RowJson>,
    },
    /// `delete`.
    Delete {
        /// The ids.
        ids: Vec<String>,
    },
    /// `setLive`.
    SetLive {
        /// The live library.
        live: Live,
    },
}

impl From<WriteJson> for Write {
    fn from(w: WriteJson) -> Write {
        match w {
            WriteJson::Upsert { rows } => Write::Upsert(rows_in(rows)),
            WriteJson::Delete { ids } => Write::Delete(ids),
            WriteJson::SetLive { live } => Write::SetLive(live),
        }
    }
}

/// `Applied`, tagged by `outcome`.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "outcome", rename_all = "camelCase")]
pub enum AppliedJson {
    /// Applied now.
    Done {
        /// What it wrote.
        written: WrittenJson,
    },
    /// At or below the tab's last seq: not again.
    AlreadyApplied {
        /// The tab's last applied seq.
        last: u64,
    },
    /// A seq before it is missing; nothing written.
    Gap {
        /// The tab's last applied seq.
        last: u64,
    },
    /// Refused for good; the seq is consumed.
    Refused {
        /// Why.
        reason: String,
    },
}

impl From<Applied> for AppliedJson {
    fn from(a: Applied) -> AppliedJson {
        match a {
            Applied::Done(w) => AppliedJson::Done { written: w.into() },
            Applied::AlreadyApplied { last } => AppliedJson::AlreadyApplied { last },
            Applied::Gap { last } => AppliedJson::Gap { last },
            Applied::Refused { reason } => AppliedJson::Refused { reason },
        }
    }
}

/// `IndexWrite`.
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct IndexWriteJson {
    /// Rows to bring in.
    pub upsert: Vec<RowJson>,
    /// Rows the P3 store deleted.
    pub delete: Vec<String>,
    /// The P3 `writeSeq`s this write applies.
    pub seen_seqs: Vec<u64>,
    /// Lower `pending` by one.
    pub lower_pending: bool,
}

impl From<IndexWriteJson> for IndexWrite {
    fn from(w: IndexWriteJson) -> IndexWrite {
        IndexWrite {
            upsert: rows_in(w.upsert),
            delete: w.delete,
            seen_seqs: w.seen_seqs,
            lower_pending: w.lower_pending,
        }
    }
}

/// `Recovery`.
/// One step of a removal by absence (`remove_absent_next`).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AbsentStepJson {
    /// What the step deleted.
    pub change: ChangeSet,
    /// Every row has been examined.
    pub done: bool,
}

impl From<AbsentStep> for AbsentStepJson {
    fn from(s: AbsentStep) -> AbsentStepJson {
        AbsentStepJson {
            change: s.change,
            done: s.done,
        }
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct RecoveryJson {
    /// Every id the P3 store holds at the choice.
    pub migration_source: Vec<String>,
    /// The late-row baseline, opaque.
    pub late_baseline: String,
}

impl From<RecoveryJson> for Recovery {
    fn from(r: RecoveryJson) -> Recovery {
        Recovery {
            migration_source: r.migration_source,
            late_baseline: r.late_baseline,
        }
    }
}

/// The flip's [`FlipInit`] (SPEC §11 step 5).
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FlipInitJson {
    pub generation: u32,
    pub source_seq: u64,
    pub source_count: u64,
    pub migration_source: Vec<String>,
    pub late_baseline: String,
    #[serde(default)]
    pub p3_migration: Option<String>,
}

impl From<FlipInitJson> for FlipInit {
    fn from(f: FlipInitJson) -> FlipInit {
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

/// `FtsCheck`.
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum FtsCheckJson {
    Ok,
    Damaged,
    NotReady,
}

impl From<FtsCheck> for FtsCheckJson {
    fn from(c: FtsCheck) -> FtsCheckJson {
        match c {
            FtsCheck::Ok => FtsCheckJson::Ok,
            FtsCheck::Damaged => FtsCheckJson::Damaged,
            FtsCheck::NotReady => FtsCheckJson::NotReady,
        }
    }
}

/// `ExportWalk`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WalkJson {
    /// The walk's token, as text: it can pass 2^53.
    pub token: String,
    /// The rows it returns.
    pub count: u32,
}

impl From<ExportWalk> for WalkJson {
    fn from(w: ExportWalk) -> WalkJson {
        WalkJson {
            token: w.token.to_string(),
            count: w.count,
        }
    }
}

/// `RidId`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RidIdJson {
    /// The row's `rid`.
    pub rid: i64,
    /// Its id.
    pub id: String,
}

impl From<RidId> for RidIdJson {
    fn from(r: RidId) -> RidIdJson {
        RidIdJson {
            rid: r.rid,
            id: r.id,
        }
    }
}

/// `FileInfo`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InfoJson {
    /// The schema version that wrote the file.
    pub schema_v: u32,
    /// The oldest store code that may read it.
    pub min_reader_v: u32,
    /// The oldest store code that may write it.
    pub min_writer_v: u32,
    /// `index` or `authority`.
    pub role: RoleJson,
    /// Its generation.
    pub generation: u32,
}

impl From<&FileInfo> for InfoJson {
    fn from(i: &FileInfo) -> InfoJson {
        InfoJson {
            schema_v: i.schema_v,
            min_reader_v: i.min_reader_v,
            min_writer_v: i.min_writer_v,
            role: i.role.into(),
            generation: i.generation,
        }
    }
}

/// `Role`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RoleJson {
    /// Stage 1's disposable index.
    Index,
    /// Stage 2's truth.
    Authority,
}

impl From<Role> for RoleJson {
    fn from(r: Role) -> RoleJson {
        match r {
            Role::Index => RoleJson::Index,
            Role::Authority => RoleJson::Authority,
        }
    }
}

impl From<RoleJson> for Role {
    fn from(r: RoleJson) -> Role {
        match r {
            RoleJson::Index => Role::Index,
            RoleJson::Authority => Role::Authority,
        }
    }
}

/// `JournalMode`. Plain `wal` needs shared memory, which no web VFS has;
/// W0 picks between these two.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum JournalJson {
    /// `journal_mode=TRUNCATE`.
    Truncate,
    /// `locking_mode=EXCLUSIVE`, then WAL.
    ExclusiveWal,
}

impl From<JournalJson> for JournalMode {
    fn from(j: JournalJson) -> JournalMode {
        match j {
            JournalJson::Truncate => JournalMode::Truncate,
            JournalJson::ExclusiveWal => JournalMode::ExclusiveWal,
        }
    }
}

/// Where a file lives: a pool, or memory (Node tests).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum PlaceJson {
    /// `crema-index`.
    Index,
    /// `crema-db`.
    Db,
    /// `memvfs`: gone with the Worker. Tests only.
    Memory,
}

impl PlaceJson {
    /// The pool, if any.
    #[must_use]
    pub fn pool(self) -> Option<Which> {
        match self {
            PlaceJson::Index => Some(Which::Index),
            PlaceJson::Db => Some(Which::Db),
            PlaceJson::Memory => None,
        }
    }
}

/// `Health`.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthJson {
    /// Derived data being rebuilt.
    pub reindexing: bool,
    /// The search index's state.
    pub fts: FtsJson,
    /// Rows at an older `DERIVE_V` remain.
    pub derive_pending: bool,
    /// Writes allowed.
    pub writable: bool,
    /// This open ran `quick_check`, and it passed.
    pub quick_checked: bool,
}

impl From<Health> for HealthJson {
    fn from(h: Health) -> HealthJson {
        HealthJson {
            reindexing: h.reindexing,
            fts: h.fts.into(),
            derive_pending: h.derive_pending,
            writable: h.writable,
            quick_checked: h.quick_checked,
        }
    }
}

/// `FtsStatus`.
#[derive(Debug, Clone, Copy, Serialize)]
#[serde(tag = "state", rename_all = "camelCase")]
pub enum FtsJson {
    /// Whole.
    Ready,
    /// Being rebuilt; the new table holds rows up to `cursor`.
    Building {
        /// The highest `rid` it holds.
        cursor: i64,
    },
    /// None.
    Missing,
}

impl From<FtsStatus> for FtsJson {
    fn from(f: FtsStatus) -> FtsJson {
        match f {
            FtsStatus::Ready => FtsJson::Ready,
            FtsStatus::Building { cursor } => FtsJson::Building { cursor },
            FtsStatus::Missing => FtsJson::Missing,
        }
    }
}

/// `Maintained`, tagged by `step`.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "step", rename_all = "camelCase")]
pub enum MaintainedJson {
    /// Nothing to do.
    Idle,
    /// A shot is running.
    Busy,
    /// A derive rewrite batch.
    Derived {
        /// Rows read.
        rows: u32,
        /// What lists see of it.
        change: ChangeSet,
    },
    /// The derive rewrite finished.
    DeriveDone,
    /// An index rebuild started.
    FtsStarted,
    /// A rebuild batch.
    FtsBatch {
        /// Rows added.
        rows: u32,
    },
    /// The rebuilt index replaced the old.
    FtsSwapped,
    /// A merge step.
    Merged {
        /// Another step may help.
        more: bool,
    },
}

impl From<Maintained> for MaintainedJson {
    fn from(m: Maintained) -> MaintainedJson {
        match m {
            Maintained::Idle => MaintainedJson::Idle,
            Maintained::Busy => MaintainedJson::Busy,
            Maintained::Derived { rows, change } => MaintainedJson::Derived { rows, change },
            Maintained::DeriveDone => MaintainedJson::DeriveDone,
            Maintained::FtsStarted => MaintainedJson::FtsStarted,
            Maintained::FtsBatch { rows } => MaintainedJson::FtsBatch { rows },
            Maintained::FtsSwapped => MaintainedJson::FtsSwapped,
            Maintained::Merged { more } => MaintainedJson::Merged { more },
        }
    }
}

/// What a call throws: `{ "code", "message", ... }` as JSON text. `code` is
/// the error class the shell maps to a health state (SPEC §12).
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ErrorJson {
    /// `ftsDamaged`, `corrupt`, `full`, `busy`, `cantOpen`, `readOnly`,
    /// `tooNew`, `readOnlyTooNew`, `foreign`, `wrongRole`, `alreadyOpen`,
    /// `reopened`, `refused`, `fault`, `poisoned`, `journalMode`,
    /// `notListed`, `unknownWalk`, `sqlite`;
    /// `badJson`, `closed`; or a pool's code (`pool.<code>`).
    pub code: String,
    /// The text.
    pub message: String,
    /// For `poisoned`: the error the failed reopen gave.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cause: Option<Box<ErrorJson>>,
}

impl ErrorJson {
    /// An error of `code`.
    pub fn new(code: &str, message: impl Into<String>) -> ErrorJson {
        ErrorJson {
            code: code.to_owned(),
            message: message.into(),
            cause: None,
        }
    }
}

impl From<&StoreError> for ErrorJson {
    fn from(e: &StoreError) -> ErrorJson {
        let code = match e {
            StoreError::FtsDamaged(_) => "ftsDamaged",
            StoreError::Corrupt(_) => "corrupt",
            StoreError::Full => "full",
            StoreError::Busy => "busy",
            StoreError::CantOpen(_) => "cantOpen",
            StoreError::ReadOnly => "readOnly",
            StoreError::TooNew { .. } => "tooNew",
            StoreError::ReadOnlyTooNew { .. } => "readOnlyTooNew",
            StoreError::Foreign(_) => "foreign",
            StoreError::WrongRole { .. } => "wrongRole",
            StoreError::AlreadyOpen(_) => "alreadyOpen",
            StoreError::Reopened => "reopened",
            StoreError::Refused(_) => "refused",
            StoreError::Fault(_) => "fault",
            StoreError::Poisoned(_) => "poisoned",
            StoreError::JournalMode { .. } => "journalMode",
            StoreError::NotListed(_) => "notListed",
            StoreError::UnknownWalk(_) => "unknownWalk",
            _ => "sqlite",
        };
        ErrorJson {
            code: code.to_owned(),
            message: e.to_string(),
            cause: match e {
                StoreError::Poisoned(inner) => Some(Box::new(ErrorJson::from(&**inner))),
                _ => None,
            },
        }
    }
}

impl From<StoreError> for ErrorJson {
    fn from(e: StoreError) -> ErrorJson {
        ErrorJson::from(&e)
    }
}

impl From<PoolError> for ErrorJson {
    fn from(e: PoolError) -> ErrorJson {
        ErrorJson::new(&format!("pool.{}", e.code), e.message)
    }
}

impl From<serde_json::Error> for ErrorJson {
    fn from(e: serde_json::Error) -> ErrorJson {
        ErrorJson::new("badJson", e.to_string())
    }
}

//! The one module that configures the `sahpool` pools (SPEC §8, M7, S5, N2).
//!
//! Two pools, one per role, each its own VFS:
//! - `crema-index`, in `.crema-history-index/`: `history-index.db`, stage 1's
//!   disposable index. Capacity 6.
//! - `crema-db`, in `.crema-history-db/`: `history.db`, any `history-<n>.db`
//!   and the snapshots, the truth. Capacity 16: the database, its journal,
//!   two snapshots and their journals, recovery files, transient files.
//!
//! The names are H0's (`web/src/lib/history-db/names.ts`); a test holds the
//! two copies equal. A repair generation `g` above 0 adds `-<g>` to the
//! directory and keeps the VFS name.
//!
//! **Nothing here deletes** (M7). `sqlite-wasm-vfs` can: `clear_on_init(true)`
//! frees every slot at install, and its util's `clear`, `remove` and
//! `reduce_capacity` drop files and slots. This module installs with
//! `clear_on_init(false)` stated, never calls the other three, and keeps the
//! util private, so no other code can. A CI test (`tests/sources.rs`)
//! fails on any of those names in this file outside its own tests, and on any
//! use of the `sahpool` util anywhere else.
//!
//! **Capacity** (S5): each pool gets its capacity at install. A write that
//! finds no free slot (`NoCapacity`, which SQLite sees as `SQLITE_CANTOPEN`)
//! makes [`Pool::retry_on_no_capacity`] add [`GROW`] slots and run the call
//! once more. Capacity is never reduced.

use std::cell::RefCell;
use std::collections::BTreeMap;

use de1_history::store::StoreError;
use serde::Serialize;
use sqlite_wasm_rs::WasmOsCallback;
use sqlite_wasm_vfs::sahpool::{self, OpfsSAHError, OpfsSAHPoolCfgBuilder, OpfsSAHPoolUtil};
// For `names()` only: the trait also carries `clear` and `remove`, which this
// module never calls (the CI test checks).
use sqlite_wasm_rs::vfs::VfsFilesManager as _;

/// One pool's fixed configuration.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PoolSpec {
    /// The SQLite VFS name it installs as.
    pub vfs: &'static str,
    /// Its OPFS directory, at the root, for generation 0.
    pub dir: &'static str,
    /// The slots it holds from install on, journals included.
    pub capacity: usize,
    /// Always `false`: `true` would free every slot at install (S5).
    pub clear_on_init: bool,
}

/// `crema-index`: stage 1's index (H0's `INDEX_POOL_VFS`, `INDEX_POOL_DIR`).
pub const INDEX: PoolSpec = PoolSpec {
    vfs: "crema-index",
    dir: ".crema-history-index",
    capacity: 6,
    clear_on_init: false,
};

/// `crema-db`: the truth (H0's `DB_POOL_VFS`, `DB_POOL_DIR`).
pub const DB: PoolSpec = PoolSpec {
    vfs: "crema-db",
    dir: ".crema-history-db",
    capacity: 16,
    clear_on_init: false,
};

/// Slots added when a write finds none free.
pub const GROW: usize = 4;

/// Every OPFS entry the history keeps starts with this (H0's
/// `HISTORY_SQLITE_OPFS_PREFIX`).
pub const OPFS_PREFIX: &str = ".crema-history-";

/// Which pool.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, serde::Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Which {
    /// `crema-index`.
    Index,
    /// `crema-db`.
    Db,
}

impl Which {
    /// Its spec.
    #[must_use]
    pub fn spec(self) -> &'static PoolSpec {
        match self {
            Which::Index => &INDEX,
            Which::Db => &DB,
        }
    }
}

impl PoolSpec {
    /// The directory of repair generation `generation`: the base directory
    /// for 0, `-<g>` added above it.
    #[must_use]
    pub fn dir_for(&self, generation: u32) -> String {
        if generation == 0 {
            self.dir.to_owned()
        } else {
            format!("{}-{generation}", self.dir)
        }
    }
}

/// An installed pool.
pub struct Pool {
    which: Which,
    generation: u32,
    util: OpfsSAHPoolUtil,
}

/// What failed in a pool call.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PoolError {
    /// A stable name: `notSupported`, `invalidHeader`, `filesInUse`,
    /// `noCapacity`, `paused`, `busy`, `needsRecovery`, `configurationMismatch`,
    /// `directoryInUse`, `opfs`, `notInstalled`, `other`.
    pub code: &'static str,
    /// The text.
    pub message: String,
}

impl PoolError {
    fn of(e: &OpfsSAHError) -> PoolError {
        let code = match e {
            OpfsSAHError::NotSupported => "notSupported",
            OpfsSAHError::InvalidHeader { .. } | OpfsSAHError::DuplicateFilename(_) => {
                "invalidHeader"
            }
            OpfsSAHError::FilesInUse | OpfsSAHError::FileInUse(_) => "filesInUse",
            OpfsSAHError::NoCapacity => "noCapacity",
            OpfsSAHError::Paused => "paused",
            OpfsSAHError::Busy => "busy",
            OpfsSAHError::ConfigurationMismatch => "configurationMismatch",
            OpfsSAHError::DirectoryInUse(_) => "directoryInUse",
            OpfsSAHError::Opfs { .. } => "opfs",
            // Its text advises a clear, which discards everything: the shell
            // pauses and resumes instead (REVIEW-444 note 9).
            OpfsSAHError::NeedsRecovery => "needsRecovery",
            _ => "other",
        };
        PoolError {
            code,
            message: e.to_string(),
        }
    }

    pub(crate) fn not_installed(which: Which) -> PoolError {
        PoolError {
            code: "notInstalled",
            message: format!("the {} pool isn't installed", which.spec().vfs),
        }
    }
}

thread_local! {
    /// The pools installed in this Worker, by role.
    static POOLS: RefCell<BTreeMap<Which, Pool>> = const { RefCell::new(BTreeMap::new()) };
}

/// The `sahpool` configuration for `spec` at `generation`. `clear_on_init`
/// comes from the spec, which is `false` for both pools.
fn config(spec: &PoolSpec, generation: u32) -> sahpool::OpfsSAHPoolCfg {
    OpfsSAHPoolCfgBuilder::new()
        .vfs_name(spec.vfs)
        .directory(&spec.dir_for(generation))
        .clear_on_init(spec.clear_on_init)
        .initial_capacity(spec.capacity)
        .build()
}

/// Installs `which` at `generation`, or finds it installed. Never the
/// default VFS: a store names its pool, and an unnamed open stays in memory.
///
/// A failed install leaves the directory as it was: `sahpool` removes only a
/// slot it had just created and not finished (RESEARCH §2). A slot with an
/// invalid header fails every install (S5): the shell's answer is
/// [`crate::repair`], never a clear.
///
/// # Errors
/// The pool's error.
pub async fn install(which: Which, generation: u32) -> Result<PoolInfo, PoolError> {
    if let Some(info) = with(which, |p| (p.generation == generation).then(|| p.info()))
        .ok()
        .flatten()
    {
        return Ok(info);
    }
    let util = sahpool::install::<WasmOsCallback>(&config(which.spec(), generation), false)
        .await
        .map_err(|e| PoolError::of(&e))?;
    let pool = Pool {
        which,
        generation,
        util,
    };
    let info = pool.info();
    POOLS.with(|p| p.borrow_mut().insert(which, pool));
    Ok(info)
}

/// What a pool holds now.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PoolInfo {
    /// The VFS name.
    pub vfs: &'static str,
    /// The directory.
    pub dir: String,
    /// The repair generation.
    pub generation: u32,
    /// Slots held, assigned and free.
    pub capacity: usize,
    /// The files in it, by name; empty while paused.
    pub files: Vec<String>,
    /// Paused: handles released, files unreachable until resumed.
    pub paused: bool,
}

impl Pool {
    fn info(&self) -> PoolInfo {
        let mut files = self.util.names().unwrap_or_default();
        files.sort();
        PoolInfo {
            vfs: self.which.spec().vfs,
            dir: self.which.spec().dir_for(self.generation),
            generation: self.generation,
            capacity: self.util.capacity(),
            files,
            paused: self.util.is_paused(),
        }
    }

    /// No slot is free: every held slot has a file.
    fn full(&self) -> bool {
        match self.util.names() {
            Ok(names) => names.len() >= self.util.capacity(),
            Err(_) => false,
        }
    }
}

/// Runs `f` on the installed pool `which`.
fn with<T>(which: Which, f: impl FnOnce(&Pool) -> T) -> Result<T, PoolError> {
    POOLS.with(|p| {
        p.borrow()
            .get(&which)
            .map(f)
            .ok_or_else(|| PoolError::not_installed(which))
    })
}

/// The installed pool's state.
///
/// # Errors
/// [`PoolError`] `notInstalled`.
pub fn info(which: Which) -> Result<PoolInfo, PoolError> {
    with(which, Pool::info)
}

/// The VFS name of `which`, when it is installed.
#[must_use]
pub fn vfs_if_installed(which: Which) -> Option<&'static str> {
    with(which, |_| which.spec().vfs).ok()
}

/// Adds `n` slots to `which`. Never fewer: capacity only grows (S5).
///
/// # Errors
/// The pool's error.
pub async fn add_capacity(which: Which, n: usize) -> Result<usize, PoolError> {
    let util = with(which, |p| p.util.clone())?;
    util.add_capacity(n).await.map_err(|e| PoolError::of(&e))
}

/// Runs `op`, and when it fails for want of a slot in `which`, adds
/// [`GROW`] slots and runs it once more (S5). Want of a slot is SQLite's
/// `SQLITE_CANTOPEN` with every held slot assigned. A call that failed that
/// way wrote nothing (it couldn't open the file it needed), so running it
/// again is safe. In a pool not installed, or in memory (`None`), `op` runs
/// once.
///
/// # Errors
/// `op`'s error, or the pool's as [`StoreError::CantOpen`].
pub async fn retry_on_no_capacity<T>(
    which: Option<Which>,
    mut op: impl FnMut() -> Result<T, StoreError>,
) -> Result<T, StoreError> {
    let first = op();
    let Some(which) = which else { return first };
    match first {
        Err(StoreError::CantOpen(msg)) if with(which, Pool::full).unwrap_or(false) => {
            add_capacity(which, GROW).await.map_err(|e| {
                StoreError::CantOpen(format!("{msg}; adding capacity failed: {}", e.message))
            })?;
            op()
        }
        other => other,
    }
}

/// Releases every handle of `which` (handover, S1). Every store on it must be
/// closed first: `sahpool` refuses with `FilesInUse` otherwise.
///
/// # Errors
/// The pool's error.
pub fn pause(which: Which) -> Result<(), PoolError> {
    with(which, |p| p.util.pause())?.map_err(|e| PoolError::of(&e))
}

/// Takes the handles of a paused `which` again.
///
/// # Errors
/// The pool's error.
pub async fn resume(which: Which) -> Result<(), PoolError> {
    let util = with(which, |p| p.util.clone())?;
    util.resume().await.map_err(|e| PoolError::of(&e))
}

/// Unregisters `which` and releases its handles, keeping every file, so it
/// can be installed again (at another generation, or in a test). Every store
/// on it must be closed first.
///
/// # Errors
/// The pool's error; `filesInUse` while a store has a file open.
pub fn uninstall(which: Which) -> Result<(), PoolError> {
    let util = with(which, |p| p.util.clone())?;
    // SAFETY: every store on this pool is closed (the caller's contract,
    // which `sahpool` checks as `FilesInUse`), this module keeps no VFS
    // pointer, and the Worker runs one call at a time.
    unsafe { util.uninstall() }.map_err(|e| PoolError::of(&e))?;
    POOLS.with(|p| p.borrow_mut().retain(|k, _| *k != which));
    Ok(())
}

/// Both specs, as the build configures them: for the `clear_on_init` check
/// (S5) and for the shell's logs.
#[must_use]
pub fn specs() -> [&'static PoolSpec; 2] {
    [&INDEX, &DB]
}

#[cfg(test)]
mod tests {
    use super::*;
    use wasm_bindgen_test::wasm_bindgen_test as test;

    #[test]
    fn neither_pool_clears_on_install() {
        for spec in specs() {
            assert!(!spec.clear_on_init, "{}", spec.vfs);
        }
    }

    #[test]
    fn generations_add_a_suffix_and_keep_the_prefix() {
        assert_eq!(DB.dir_for(0), ".crema-history-db");
        assert_eq!(DB.dir_for(2), ".crema-history-db-2");
        assert_eq!(INDEX.dir_for(1), ".crema-history-index-1");
        for spec in specs() {
            assert!(spec.dir_for(7).starts_with(OPFS_PREFIX));
        }
    }
}

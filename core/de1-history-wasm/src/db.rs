//! `HistoryDb`: one store on one file, for the Worker (PLAN C4). JSON in,
//! JSON out; the methods mirror the FFI's (C3), one per store call.
//!
//! - Every argument is JSON text; every answer is JSON text, or a `Promise`
//!   of it for a call that may need a new pool slot (opens, writes,
//!   maintenance, copies), which [`crate::pools::retry_on_no_capacity`]
//!   adds and retries once (S5).
//! - Every failure throws the JSON text of an [`ErrorJson`]: its `code` is
//!   the error class the shell maps to a health state (SPEC §12).
//! - The Worker makes one call at a time (W1's queue). Nothing here stops a
//!   second call while a `Promise` waits for a slot: no transaction is open
//!   across that wait, but the pool is busy, so the second call may fail
//!   (REVIEW-444 note 5).
//!
//! What it holds to:
//! - **Files live in their role's pool** (M7): an `index` file in
//!   `crema-index`, an `authority` file in `crema-db`, and a copy lands only in
//!   `crema-db`. `memory` is for Node tests, and holds an `authority` file
//!   only with the `memory-truth` test feature.
//! - **`cantOpen` doesn't tell absent from unopenable** (REVIEW-444 note 7):
//!   SPEC §8 makes an absent `history.db` `lost` and an unopenable one
//!   `unavailable`, so the shell decides from `poolInfo().files`.
//! - **`authority` files open with `create: false`** (REVIEW-439 note 10):
//!   after the flip a missing `history.db` is `lost`, never recreated. They
//!   come into being only by [`HistoryDb::copy_to`] (the flip, the snapshot)
//!   and [`HistoryDb::create_recovery`].
//! - **`page` is for display only** (REVIEW-442 note 2): a row whose sort
//!   key moves across the cursor between two pages shows twice or not at all.
//!   Anything that acts on each row (bulk upload, CSV, bulk edit) pages by
//!   `rid` (`unsent`, `syncRows`, the export walk) or dedupes by id.
//! - **No long statement** (S15, REVIEW-439 note 6): opens set
//!   `fts_check_on_reopen` off, so a reopen after a mid-session
//!   `SQLITE_CORRUPT` rebuilds the search index in batches instead of
//!   checking it in one statement, and the one-statement
//!   `fts_integrity_check` is not offered here.

use std::cell::RefCell;
use std::path::PathBuf;
use std::rc::Rc;

use de1_history::store::{Destination, OpenOptions, Role, Store, StoreError};
use de1_history::{Anchor, HistoryQuery, Live};
use js_sys::Promise;
use serde::Deserialize;
use serde::Serialize;
use serde::de::DeserializeOwned;
use wasm_bindgen::prelude::*;
use wasm_bindgen_futures::future_to_promise;

use crate::json::{
    AppliedJson, ErrorJson, HealthJson, IndexWriteJson, InfoJson, JournalJson, MaintainedJson,
    PlaceJson, RecoveryJson, RidIdJson, RoleJson, RowJson, WalkJson, WriteJson, WrittenJson,
    rows_in,
};
use crate::pools::{self, PoolError, Which};

/// An error, thrown as its JSON text.
pub(crate) fn throw(e: impl Into<ErrorJson>) -> JsValue {
    let e: ErrorJson = e.into();
    JsValue::from_str(&serde_json::to_string(&e).unwrap_or_else(|_| e.message.clone()))
}

/// `v` as JSON text.
pub(crate) fn out<T: Serialize>(v: &T) -> Result<String, JsValue> {
    serde_json::to_string(v).map_err(|e| throw(ErrorJson::new("sqlite", e.to_string())))
}

fn parse<T: DeserializeOwned>(text: &str) -> Result<T, JsValue> {
    serde_json::from_str(text).map_err(|e| throw(ErrorJson::from(e)))
}

/// How to open a file.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
struct OpenJson {
    /// The pool, or `memory`.
    place: PlaceJson,
    /// The file's name in it: `history-index.db`, `history.db`, …
    file: String,
    /// The role it must hold.
    role: RoleJson,
    /// Create it when missing: `index` files only.
    #[serde(default)]
    create: bool,
    /// `meta.generation` for a file this open creates.
    #[serde(default)]
    generation: u32,
    /// The journal mode (W0 picks): `truncate` or `exclusiveWal`.
    journal: JournalJson,
    /// Now, in ms since the epoch; `Date.now()` when left out.
    #[serde(default)]
    now_ms: Option<i64>,
    /// Run `quick_check` whatever the schedule says.
    #[serde(default)]
    quick_check: bool,
    /// How long a locked file is waited for.
    #[serde(default)]
    busy_timeout_ms: Option<u32>,
}

/// A file's path in its VFS: `/` and its name. One spelling, so the store's
/// one-connection claim (S4) and `sahpool`'s names agree.
fn path_of(file: &str) -> Result<PathBuf, JsValue> {
    let plain = !file.is_empty()
        && !file.starts_with('.')
        && !file.starts_with('-')
        && file
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'-' | b'_'));
    if !plain {
        return Err(throw(ErrorJson::new(
            "refused",
            format!("{file:?} isn't a plain file name"),
        )));
    }
    Ok(PathBuf::from(format!("/{file}")))
}

/// The VFS name for `place`; a pool must be installed.
fn vfs_of(place: PlaceJson) -> Result<String, JsValue> {
    match place.pool() {
        None => Ok("memvfs".to_owned()),
        Some(which) => pools::vfs_if_installed(which)
            .map(str::to_owned)
            .ok_or_else(|| throw(PoolError::not_installed(which))),
    }
}

/// The role a place may hold (M7): the index never shares a pool with the
/// truth.
fn check_place(place: PlaceJson, role: Role) -> Result<(), JsValue> {
    place_fits(place, role, cfg!(feature = "memory-truth"))
        .map_err(|m| throw(ErrorJson::new("refused", m)))
}

/// Whether a file of `role` may live in `place`. `memory` holds an
/// `authority` file only when `memory_truth` (the `memory-truth` feature,
/// tests only): in a shipped build the truth never lives in RAM, gone with
/// the Worker (REVIEW-444 note 6).
pub(crate) fn place_fits(place: PlaceJson, role: Role, memory_truth: bool) -> Result<(), String> {
    let fits = match place {
        PlaceJson::Memory => role == Role::Index || memory_truth,
        PlaceJson::Index => role == Role::Index,
        PlaceJson::Db => role == Role::Authority,
    };
    if fits {
        Ok(())
    } else {
        Err(format!(
            "a {} file doesn't belong in {place:?}",
            role.as_str()
        ))
    }
}

fn options(o: &OpenJson) -> Result<OpenOptions, JsValue> {
    let role: Role = o.role.into();
    check_place(o.place, role)?;
    if o.create && role == Role::Authority {
        return Err(throw(ErrorJson::new(
            "refused",
            "authority files open with create: false; a copy or a recovery makes them \
             (REVIEW-439 note 10)",
        )));
    }
    let mut opts = OpenOptions::new(role);
    opts.create = o.create;
    opts.generation = o.generation;
    opts.journal = o.journal.into();
    opts.vfs = Some(vfs_of(o.place)?);
    opts.now_ms = o.now_ms.unwrap_or_else(now_ms);
    opts.quick_check = o.quick_check;
    if let Some(ms) = o.busy_timeout_ms {
        opts.busy_timeout_ms = ms;
    }
    // S15: no one-statement index check on web (REVIEW-439 note 6).
    opts.fts_check_on_reopen = false;
    Ok(opts)
}

/// `Date.now()`, whole ms.
fn now_ms() -> i64 {
    #[allow(clippy::cast_possible_truncation)]
    {
        js_sys::Date::now() as i64
    }
}

struct Inner {
    store: Option<Store>,
    pool: Option<Which>,
}

/// One store on one file.
#[wasm_bindgen]
pub struct HistoryDb {
    inner: Rc<RefCell<Inner>>,
}

fn closed() -> StoreError {
    StoreError::Refused("the store is closed".into())
}

/// Runs `f` on the open store.
fn on<T>(
    inner: &RefCell<Inner>,
    f: impl FnOnce(&mut Store) -> Result<T, StoreError>,
) -> Result<T, StoreError> {
    let mut inner = inner.try_borrow_mut().map_err(|_| StoreError::Busy)?;
    let store = inner.store.as_mut().ok_or_else(closed)?;
    f(store)
}

impl HistoryDb {
    fn sync<T: Serialize>(
        &self,
        f: impl FnOnce(&mut Store) -> Result<T, StoreError>,
    ) -> Result<String, JsValue> {
        let v = on(&self.inner, f).map_err(throw)?;
        out(&v)
    }

    /// A call that may need a new slot: a `Promise` of its JSON.
    fn slot<T: Serialize + 'static>(
        &self,
        mut f: impl FnMut(&mut Store) -> Result<T, StoreError> + 'static,
    ) -> Promise {
        let inner = Rc::clone(&self.inner);
        future_to_promise(async move {
            let pool = inner
                .try_borrow()
                .map_err(|_| throw(StoreError::Busy))?
                .pool;
            let v = pools::retry_on_no_capacity(pool, || on(&inner, &mut f))
                .await
                .map_err(throw)?;
            Ok(JsValue::from_str(&out(&v)?))
        })
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PageArgs {
    query: HistoryQuery,
    anchor: Anchor,
    limit: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct IndexOfArgs {
    query: HistoryQuery,
    id: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GroupArgs {
    query: HistoryQuery,
    edges_ms: Vec<i64>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApplyArgs {
    tab: String,
    seq: u64,
    at_ms: i64,
    write: WriteJson,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PruneArgs {
    gone: Vec<String>,
    before_ms: i64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct UnsentArgs {
    dest: Destination,
    after_rid: i64,
    limit: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RidArgs {
    after_rid: i64,
    limit: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DosesArgs {
    bean_id: String,
    limit: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NextArgs {
    token: String,
    n: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CreatedBeforeArgs {
    begin_seq: u64,
    after_rid: i64,
    limit: u32,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RemoveAbsentArgs {
    ids: Vec<String>,
    since_seq: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CopyArgs {
    place: PlaceJson,
    file: String,
    journal: JournalJson,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RecoveryArgs {
    place: PlaceJson,
    file: String,
    generation: u32,
    journal: JournalJson,
    #[serde(default)]
    now_ms: Option<i64>,
    recovery: RecoveryJson,
}

fn token(t: &str) -> Result<u64, StoreError> {
    t.parse()
        .map_err(|_| StoreError::Refused(format!("{t:?} isn't a walk token")))
}

impl HistoryDb {
    /// [`HistoryDb::open`], for Rust callers and tests.
    ///
    /// # Errors
    /// The thrown JSON.
    pub async fn opened(json: &str) -> Result<HistoryDb, JsValue> {
        let o: OpenJson = parse(json)?;
        let opts = options(&o)?;
        let path = path_of(&o.file)?;
        let pool = o.place.pool();
        let store = pools::retry_on_no_capacity(pool, || Store::open(&path, opts.clone()))
            .await
            .map_err(throw)?;
        Ok(HistoryDb::of(store, pool))
    }

    /// [`HistoryDb::create_recovery`], for Rust callers and tests.
    ///
    /// # Errors
    /// The thrown JSON.
    pub async fn recovered(json: &str) -> Result<HistoryDb, JsValue> {
        let a: RecoveryArgs = parse(json)?;
        check_place(a.place, Role::Authority)?;
        let mut opts = OpenOptions::new(Role::Authority).create(a.generation);
        opts.journal = a.journal.into();
        opts.vfs = Some(vfs_of(a.place)?);
        opts.now_ms = a.now_ms.unwrap_or_else(now_ms);
        opts.fts_check_on_reopen = false;
        let path = path_of(&a.file)?;
        let pool = a.place.pool();
        let init = a.recovery.into();
        let store = pools::retry_on_no_capacity(pool, || {
            Store::create_recovery(&path, opts.clone(), &init)
        })
        .await
        .map_err(throw)?;
        Ok(HistoryDb::of(store, pool))
    }

    fn of(store: Store, pool: Option<Which>) -> HistoryDb {
        HistoryDb {
            inner: Rc::new(RefCell::new(Inner {
                store: Some(store),
                pool,
            })),
        }
    }
}

#[wasm_bindgen]
impl HistoryDb {
    /// Opens a file (`OpenJson`): a `Promise` of the `HistoryDb`. Refused
    /// before anything opens: an `authority` file with `create`, a file
    /// outside its role's pool, a name that isn't plain.
    #[wasm_bindgen]
    pub fn open(json: &str) -> Promise {
        let json = json.to_owned();
        future_to_promise(async move { Ok(HistoryDb::opened(&json).await?.into()) })
    }

    /// Creates the recovery file `history-<n>.db` after `lost` (S9), in
    /// `crema-db`: a `Promise` of the `HistoryDb`. A file there that holds
    /// anything is refused and left as it is.
    #[wasm_bindgen(js_name = createRecovery)]
    pub fn create_recovery(json: &str) -> Promise {
        let json = json.to_owned();
        future_to_promise(async move { Ok(HistoryDb::recovered(&json).await?.into()) })
    }

    /// Closes the file cleanly. Every later call is refused.
    pub fn close(&self) -> Result<(), JsValue> {
        let store = self
            .inner
            .try_borrow_mut()
            .map_err(|_| throw(StoreError::Busy))?
            .store
            .take();
        match store {
            Some(s) => s.close().map_err(throw),
            None => Ok(()),
        }
    }

    /// `FileInfo`.
    pub fn info(&self) -> Result<String, JsValue> {
        self.sync(|s| Ok(InfoJson::from(s.info())))
    }

    /// The last committed `write_seq`.
    #[wasm_bindgen(js_name = writeSeq)]
    pub fn write_seq(&self) -> Result<String, JsValue> {
        self.sync(|s| Ok(s.write_seq()))
    }

    /// `Health`.
    pub fn health(&self) -> Result<String, JsValue> {
        self.sync(|s| Ok(HealthJson::from(s.health())))
    }

    /// A shot is running: maintenance waits.
    #[wasm_bindgen(js_name = setBusy)]
    pub fn set_busy(&self, busy: bool) -> Result<(), JsValue> {
        on(&self.inner, |s| {
            s.set_busy(busy);
            Ok(())
        })
        .map_err(throw)
    }

    /// The rows each maintenance batch takes (B1 sets it under S15's cap).
    #[wasm_bindgen(js_name = setBatchRows)]
    pub fn set_batch_rows(&self, rows: u32) -> Result<(), JsValue> {
        on(&self.inner, |s| {
            s.set_batch_rows(rows);
            Ok(())
        })
        .map_err(throw)
    }

    // ── Writes ──────────────────────────────────────────────────────────

    /// `upsert(rows)`: `[RowJson]` → `WrittenJson`.
    pub fn upsert(&self, rows: &str) -> Promise {
        let rows = match parse::<Vec<RowJson>>(rows) {
            Ok(r) => rows_in(r),
            Err(e) => return Promise::reject(&e),
        };
        self.slot(move |s| s.upsert(&rows).map(WrittenJson::from))
    }

    /// `delete(ids)`: `[id]` → `ChangeSet`. The only way a row goes (rule 1).
    pub fn delete(&self, ids: &str) -> Promise {
        let ids = match parse::<Vec<String>>(ids) {
            Ok(i) => i,
            Err(e) => return Promise::reject(&e),
        };
        self.slot(move |s| s.delete(&ids))
    }

    /// `set_live(live)`: `Live` → `ChangeSet`.
    #[wasm_bindgen(js_name = setLive)]
    pub fn set_live(&self, live: &str) -> Promise {
        let live = match parse::<Live>(live) {
            Ok(l) => l,
            Err(e) => return Promise::reject(&e),
        };
        self.slot(move |s| s.set_live(&live))
    }

    /// `apply(tab, seq, atMs, write)` (M11) → `AppliedJson`.
    pub fn apply(&self, json: &str) -> Promise {
        let a = match parse::<ApplyArgs>(json) {
            Ok(a) => a,
            Err(e) => return Promise::reject(&e),
        };
        let write = a.write.into();
        self.slot(move |s| {
            s.apply(&a.tab, a.seq, a.at_ms, &write)
                .map(AppliedJson::from)
        })
    }

    /// The tab's last applied seq.
    #[wasm_bindgen(js_name = appliedSeq)]
    pub fn applied_seq(&self, tab: &str) -> Result<String, JsValue> {
        self.sync(|s| s.applied_seq(tab))
    }

    /// Forgets tabs whose lock is gone: `{gone, beforeMs}` → count.
    #[wasm_bindgen(js_name = pruneTabs)]
    pub fn prune_tabs(&self, json: &str) -> Promise {
        let a = match parse::<PruneArgs>(json) {
            Ok(a) => a,
            Err(e) => return Promise::reject(&e),
        };
        self.slot(move |s| s.prune_tabs(&a.gone, a.before_ms))
    }

    // ── Reads ───────────────────────────────────────────────────────────

    /// `count(query)`.
    pub fn count(&self, query: &str) -> Result<String, JsValue> {
        let q: HistoryQuery = parse(query)?;
        self.sync(|s| s.count(&q))
    }

    /// `page({query, anchor, limit})` → `Page`. **For display only**
    /// (REVIEW-442 note 2): a row whose sort key moves across the cursor
    /// shows again. Per-row work pages by `rid` or dedupes by id. A page's
    /// `first` and `last` are opaque cursor text, as the FFI's; an anchor
    /// takes them back as `{after: last}` or `{before: first}`.
    pub fn page(&self, json: &str) -> Result<String, JsValue> {
        let a: PageArgs = parse(json)?;
        self.sync(|s| s.page(&a.query, &a.anchor, a.limit))
    }

    /// `index_of({query, id})`: the row's position, or `null`.
    #[wasm_bindgen(js_name = indexOf)]
    pub fn index_of(&self, json: &str) -> Result<String, JsValue> {
        let a: IndexOfArgs = parse(json)?;
        self.sync(|s| s.index_of(&a.query, &a.id))
    }

    /// `group_counts({query, edgesMs})`.
    #[wasm_bindgen(js_name = groupCounts)]
    pub fn group_counts(&self, json: &str) -> Result<String, JsValue> {
        let a: GroupArgs = parse(json)?;
        self.sync(|s| s.group_counts(&a.query, &a.edges_ms))
    }

    /// `stats(query)` → `BrewHistoryStats`.
    pub fn stats(&self, query: &str) -> Result<String, JsValue> {
        let q: HistoryQuery = parse(query)?;
        self.sync(|s| s.stats(&q))
    }

    /// `facets(query)` → `Facets`.
    pub fn facets(&self, query: &str) -> Result<String, JsValue> {
        let q: HistoryQuery = parse(query)?;
        self.sync(|s| s.facets(&q))
    }

    /// `get(ids)` → `[RowOut]`.
    pub fn get(&self, ids: &str) -> Result<String, JsValue> {
        let ids: Vec<String> = parse(ids)?;
        self.sync(|s| s.get(&ids))
    }

    /// `by_remote_id(visualizerId)` → `[RowOut]`.
    #[wasm_bindgen(js_name = byRemoteId)]
    pub fn by_remote_id(&self, visualizer_id: &str) -> Result<String, JsValue> {
        self.sync(|s| s.by_remote_id(visualizer_id))
    }

    /// `by_decent_id(decentId)` → `[RowOut]`.
    #[wasm_bindgen(js_name = byDecentId)]
    pub fn by_decent_id(&self, decent_id: &str) -> Result<String, JsValue> {
        self.sync(|s| s.by_decent_id(decent_id))
    }

    /// `doses_for_bag({beanId, limit})` → `[f64]`.
    #[wasm_bindgen(js_name = dosesForBag)]
    pub fn doses_for_bag(&self, json: &str) -> Result<String, JsValue> {
        let a: DosesArgs = parse(json)?;
        self.sync(|s| s.doses_for_bag(&a.bean_id, a.limit))
    }

    /// `unsent({dest, afterRid, limit})` → `[RidRow]`, by `rid`.
    pub fn unsent(&self, json: &str) -> Result<String, JsValue> {
        let a: UnsentArgs = parse(json)?;
        self.sync(|s| s.unsent(a.dest, a.after_rid, a.limit))
    }

    /// `sync_rows({afterRid, limit})` → `SyncPage`, by `rid`.
    #[wasm_bindgen(js_name = syncRows)]
    pub fn sync_rows(&self, json: &str) -> Result<String, JsValue> {
        let a: RidArgs = parse(json)?;
        self.sync(|s| s.sync_rows(a.after_rid, a.limit))
    }

    /// `sync_projection()` → `SyncPage`, in one read transaction (S3).
    #[wasm_bindgen(js_name = syncProjection)]
    pub fn sync_projection(&self) -> Result<String, JsValue> {
        self.sync(Store::sync_projection)
    }

    // ── Search index and upkeep ─────────────────────────────────────────

    /// One unit of background work (`MaintainedJson`): each one short
    /// transaction; the caller yields between steps.
    pub fn maintain(&self) -> Promise {
        self.slot(|s| s.maintain().map(MaintainedJson::from))
    }

    /// Starts a rebuild of the search index, in batches.
    #[wasm_bindgen(js_name = startFtsRebuild)]
    pub fn start_fts_rebuild(&self) -> Promise {
        self.slot(Store::start_fts_rebuild)
    }

    /// One incremental merge step.
    #[wasm_bindgen(js_name = ftsMergeStep)]
    pub fn fts_merge_step(&self) -> Promise {
        self.slot(|s| s.fts_merge_step().map(MaintainedJson::from))
    }

    /// The search index's state.
    #[wasm_bindgen(js_name = ftsStatus)]
    pub fn fts_status(&self) -> Result<String, JsValue> {
        self.sync(|s| Ok(crate::json::FtsJson::from(s.fts_status())))
    }

    // ── Export walk and restore (M3, S6) ────────────────────────────────

    /// `export_begin()` → `{token, count}`. The token is text.
    #[wasm_bindgen(js_name = exportBegin)]
    pub fn export_begin(&self) -> Promise {
        self.slot(|s| s.export_begin().map(WalkJson::from))
    }

    /// `export_next({token, n})` → `[RowOut]`.
    #[wasm_bindgen(js_name = exportNext)]
    pub fn export_next(&self, json: &str) -> Result<String, JsValue> {
        let a: NextArgs = parse(json)?;
        self.sync(|s| s.export_next(token(&a.token)?, a.n))
    }

    /// `export_rewind(token)`: the same set again from its first row, for
    /// a backup that reads it twice (R1w).
    #[wasm_bindgen(js_name = exportRewind)]
    pub fn export_rewind(&self, walk: &str) -> Result<String, JsValue> {
        self.sync(|s| s.export_rewind(token(walk)?))
    }

    /// `export_end(token)`.
    #[wasm_bindgen(js_name = exportEnd)]
    pub fn export_end(&self, walk: &str) -> Promise {
        let walk = walk.to_owned();
        self.slot(move |s| s.export_end(token(&walk)?))
    }

    /// `release_holds()` → `[id]` still held, for `delete`.
    #[wasm_bindgen(js_name = releaseHolds)]
    pub fn release_holds(&self) -> Result<String, JsValue> {
        self.sync(|s| s.release_holds())
    }

    /// `restore_begin()` → `begin_seq`.
    #[wasm_bindgen(js_name = restoreBegin)]
    pub fn restore_begin(&self) -> Result<String, JsValue> {
        self.sync(|s| s.restore_begin())
    }

    /// `restore_batch(rows)` → `WrittenJson`.
    #[wasm_bindgen(js_name = restoreBatch)]
    pub fn restore_batch(&self, rows: &str) -> Promise {
        let rows = match parse::<Vec<RowJson>>(rows) {
            Ok(r) => rows_in(r),
            Err(e) => return Promise::reject(&e),
        };
        self.slot(move |s| s.restore_batch(&rows).map(WrittenJson::from))
    }

    /// `ids_created_before({beginSeq, afterRid, limit})` → `[{rid, id}]`.
    #[wasm_bindgen(js_name = idsCreatedBefore)]
    pub fn ids_created_before(&self, json: &str) -> Result<String, JsValue> {
        let a: CreatedBeforeArgs = parse(json)?;
        self.sync(|s| {
            s.ids_created_before(a.begin_seq, a.after_rid, a.limit)
                .map(|v| v.into_iter().map(RidIdJson::from).collect::<Vec<_>>())
        })
    }

    // ── Stage-1 index (M4); refused on `authority` files ────────────────

    /// `diff_batch(rows)` → `WrittenJson`.
    #[wasm_bindgen(js_name = diffBatch)]
    pub fn diff_batch(&self, rows: &str) -> Promise {
        let rows = match parse::<Vec<RowJson>>(rows) {
            Ok(r) => rows_in(r),
            Err(e) => return Promise::reject(&e),
        };
        self.slot(move |s| s.diff_batch(&rows).map(WrittenJson::from))
    }

    /// `index_write(IndexWriteJson)` → `WrittenJson`.
    #[wasm_bindgen(js_name = indexWrite)]
    pub fn index_write(&self, json: &str) -> Promise {
        let w = match parse::<IndexWriteJson>(json) {
            Ok(w) => w.into(),
            Err(e) => return Promise::reject(&e),
        };
        self.slot(move |s| s.index_write(&w).map(WrittenJson::from))
    }

    /// `remove_absent({ids, sinceSeq})` → `ChangeSet`, in steps of at most
    /// 500 deletes. Steps before a failed one stay committed, and their change
    /// set doesn't cross this boundary: after a rejection the shell refreshes
    /// its lists.
    #[wasm_bindgen(js_name = removeAbsent)]
    pub fn remove_absent(&self, json: &str) -> Promise {
        let a = match parse::<RemoveAbsentArgs>(json) {
            Ok(a) => a,
            Err(e) => return Promise::reject(&e),
        };
        self.slot(move |s| s.remove_absent(&a.ids, a.since_seq).map_err(Into::into))
    }

    /// `set_source_seq(seq)`.
    #[wasm_bindgen(js_name = setSourceSeq)]
    pub fn set_source_seq(&self, seq: &str) -> Promise {
        let seq = match seq.parse::<u64>() {
            Ok(s) => s,
            Err(_) => {
                return Promise::reject(&throw(ErrorJson::new("badJson", "not a seq")));
            }
        };
        self.slot(move |s| s.set_source_seq(seq))
    }

    /// `index_state()` → `IndexState`.
    #[wasm_bindgen(js_name = indexState)]
    pub fn index_state(&self) -> Result<String, JsValue> {
        self.sync(|s| s.index_state())
    }

    // ── Copies and recovery (SPEC §11) ──────────────────────────────────

    /// Copies this file into `{place, file, journal}` with the online backup
    /// API, between two connections to two files (S4): the flip
    /// (`crema-index` → `crema-db`) and the snapshot (`crema-db` → `crema-db`).
    /// Only into `crema-db`: a copy is the truth's (M7).
    #[wasm_bindgen(js_name = copyTo)]
    pub fn copy_to(&self, json: &str) -> Promise {
        let parsed = parse::<CopyArgs>(json).and_then(|a| {
            check_place(a.place, Role::Authority)?;
            Ok((
                path_of(&a.file)?,
                vfs_of(a.place)?,
                a.place.pool(),
                a.journal,
            ))
        });
        let inner = Rc::clone(&self.inner);
        future_to_promise(async move {
            let (path, vfs, pool, journal) = parsed?;
            pools::retry_on_no_capacity(pool, || {
                on(&inner, |s| s.copy_to(&path, Some(&vfs), journal.into()))
            })
            .await
            .map_err(throw)?;
            Ok(JsValue::NULL)
        })
    }

    /// `meta.migration`'s state, or `null`.
    #[wasm_bindgen(js_name = migrationState)]
    pub fn migration_state(&self) -> Result<String, JsValue> {
        self.sync(|s| s.migration_state())
    }

    /// The late-row baseline a recovery file started from, or `null`.
    #[wasm_bindgen(js_name = lateBaseline)]
    pub fn late_baseline(&self) -> Result<String, JsValue> {
        self.sync(|s| s.late_baseline())
    }

    /// How many ids `migration_source` holds.
    #[wasm_bindgen(js_name = migrationSourceLen)]
    pub fn migration_source_len(&self) -> Result<String, JsValue> {
        self.sync(|s| s.migration_source_len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use wasm_bindgen_test::wasm_bindgen_test as test;

    #[test]
    fn the_truth_lives_in_memory_only_in_tests() {
        assert!(place_fits(PlaceJson::Memory, Role::Authority, false).is_err());
        assert!(place_fits(PlaceJson::Memory, Role::Authority, true).is_ok());
        assert!(place_fits(PlaceJson::Memory, Role::Index, false).is_ok());
        assert!(place_fits(PlaceJson::Db, Role::Authority, false).is_ok());
        assert!(place_fits(PlaceJson::Db, Role::Index, true).is_err());
        assert!(place_fits(PlaceJson::Index, Role::Authority, true).is_err());
    }
}

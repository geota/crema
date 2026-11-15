//! `de1-history-wasm`: the SQLite history's store for the web Worker (#310
//! PLAN C4, SPEC §1 and §8).
//!
//! `de1-history`'s store over `rusqlite` and `sqlite-wasm-rs` (SQLite 3.54.1,
//! the same as natively), on OPFS through `sqlite-wasm-vfs`'s `sahpool`, in
//! a dedicated Worker (no COOP/COEP). JSON in, JSON out:
//! - [`db::HistoryDb`]: one store on one file, its methods mirroring C3's;
//! - [`pools`]: the one module that installs the two pools, `crema-index`
//!   and `crema-db`, and the only code that touches `sahpool`'s util;
//! - [`repair`]: Repair storage, for a pool that won't install (S5);
//! - [`sqlite_version`], equal to the native build's (CI checks).
//!
//! The pools are never SQLite's default VFS, so an open that names none
//! stays in `memvfs`, in memory. Node tests run there (`memory`).
//!
//! Natively this crate is empty but for its tests, which check the sources
//! (no deleting calls, H0's names): the workspace's native build links no
//! second SQLite.

#[cfg(all(target_family = "wasm", target_os = "unknown"))]
pub mod db;
#[cfg(all(target_family = "wasm", target_os = "unknown"))]
pub mod json;
#[cfg(all(target_family = "wasm", target_os = "unknown"))]
pub mod pools;
#[cfg(all(target_family = "wasm", target_os = "unknown"))]
pub mod repair;

#[cfg(all(target_family = "wasm", target_os = "unknown"))]
mod exports {
    use js_sys::Promise;
    use serde::Deserialize;
    use wasm_bindgen::prelude::*;
    use wasm_bindgen_futures::future_to_promise;

    use crate::db::{out, throw};
    use crate::pools::{self, Which};
    use crate::repair;

    /// The SQLite this build runs. Equal to `de1_history::store::SQLITE_VERSION`
    /// and to the native build's (SPEC §1).
    #[wasm_bindgen(js_name = sqliteVersion)]
    #[must_use]
    pub fn sqlite_version() -> String {
        de1_history::store::sqlite_version().to_owned()
    }

    /// Both pools' configuration as built: `[{vfs, dir, capacity,
    /// clearOnInit}]`. `clearOnInit` is `false` for both (S5).
    #[wasm_bindgen(js_name = poolSpecs)]
    pub fn pool_specs() -> Result<String, JsValue> {
        out(&pools::specs())
    }

    fn which(name: &str) -> Result<Which, JsValue> {
        serde_json::from_value(serde_json::Value::String(name.to_owned()))
            .map_err(|e| throw(crate::json::ErrorJson::from(e)))
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct InstallArgs {
        pool: Which,
        #[serde(default)]
        generation: u32,
    }

    /// Installs a pool: `{pool: "index" | "db", generation}` → a `Promise`
    /// of `PoolInfo`. A failed install leaves the directory as it was; one
    /// already installed at that generation answers as it is.
    #[wasm_bindgen(js_name = installPool)]
    pub fn install_pool(json: &str) -> Promise {
        let args: Result<InstallArgs, JsValue> =
            serde_json::from_str(json).map_err(|e| throw(crate::json::ErrorJson::from(e)));
        future_to_promise(async move {
            let a = args?;
            let info = pools::install(a.pool, a.generation).await.map_err(throw)?;
            Ok(JsValue::from_str(&out(&info)?))
        })
    }

    /// A pool's state: `PoolInfo`.
    #[wasm_bindgen(js_name = poolInfo)]
    pub fn pool_info(pool: &str) -> Result<String, JsValue> {
        out(&pools::info(which(pool)?).map_err(throw)?)
    }

    /// Releases a pool's handles (handover, S1). Close its stores first.
    #[wasm_bindgen(js_name = pausePool)]
    pub fn pause_pool(pool: &str) -> Result<(), JsValue> {
        pools::pause(which(pool)?).map_err(throw)
    }

    /// Takes a paused pool's handles again.
    #[wasm_bindgen(js_name = resumePool)]
    pub fn resume_pool(pool: &str) -> Promise {
        let w = which(pool);
        future_to_promise(async move {
            pools::resume(w?).await.map_err(throw)?;
            Ok(JsValue::NULL)
        })
    }

    /// Unregisters a pool and releases its handles, keeping every file.
    /// Close its stores first.
    #[wasm_bindgen(js_name = uninstallPool)]
    pub fn uninstall_pool(pool: &str) -> Result<(), JsValue> {
        pools::uninstall(which(pool)?).map_err(throw)
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct RepairArgs {
        pool: Which,
        from: u32,
        to: u32,
    }

    /// Repair storage (S5): `{pool, from, to}` → a `Promise` of the
    /// `RepairReport`. Copies every named slot of generation `from` into a
    /// new directory for `to`; the source is only read.
    #[wasm_bindgen(js_name = repairPool)]
    pub fn repair_pool(json: &str) -> Promise {
        let args: Result<RepairArgs, JsValue> =
            serde_json::from_str(json).map_err(|e| throw(crate::json::ErrorJson::from(e)));
        future_to_promise(async move {
            let a = args?;
            let report = repair::repair(a.pool, a.from, a.to)
                .await
                .map_err(|e| repair::throw(&e))?;
            Ok(JsValue::from_str(&out(&report)?))
        })
    }
}

#[cfg(all(target_family = "wasm", target_os = "unknown"))]
pub use exports::sqlite_version;

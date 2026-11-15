//! Helpers for the store's tests: real SQLite files in temp dirs, rows in
//! the web shape, the cross-shell fixtures.
//!
//! The same tests run on wasm32 (PLAN C4) under `wasm-bindgen-test` in Node,
//! where files live in SQLite's in-memory VFS (`memvfs`, the default there):
//! a [`TestDir`] is then a fresh path prefix, and the fixtures are compiled
//! in. Tests that plant bytes in a file with `std::fs` stay native; the
//! browser suite in `de1-history-wasm` plants them in OPFS.
#![allow(dead_code)]

use std::path::{Path, PathBuf};

use de1_history::store::{OpenOptions, Role, RowIn, Store};
use serde_json::{Value, json};

/// `#[test]` on wasm32 is `wasm-bindgen-test`'s: each test file imports
/// this under `cfg(target_arch = "wasm32")`.
#[cfg(target_arch = "wasm32")]
pub use wasm_bindgen_test::wasm_bindgen_test as test;

/// A temp dir that lives as long as the test holds it.
#[cfg(not(target_arch = "wasm32"))]
pub struct TestDir(tempfile::TempDir);

/// A path prefix no other test uses, in `memvfs`.
#[cfg(target_arch = "wasm32")]
pub struct TestDir(PathBuf);

impl TestDir {
    /// The dir's path.
    #[must_use]
    pub fn path(&self) -> &Path {
        #[cfg(not(target_arch = "wasm32"))]
        return self.0.path();
        #[cfg(target_arch = "wasm32")]
        return &self.0;
    }
}

/// A new temp dir.
#[cfg(not(target_arch = "wasm32"))]
pub fn dir() -> TestDir {
    TestDir(tempfile::tempdir().expect("a temp dir"))
}

/// A new path prefix.
#[cfg(target_arch = "wasm32")]
pub fn dir() -> TestDir {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    TestDir(PathBuf::from(format!(
        "/t{}",
        NEXT.fetch_add(1, Ordering::Relaxed)
    )))
}

/// `name` inside `dir`.
pub fn file(dir: &TestDir, name: &str) -> PathBuf {
    dir.path().join(name)
}

/// A fixture's text, compiled in so the wasm32 build has it too.
pub fn fixture(name: &str) -> &'static str {
    match name {
        "rows.web.jsonl" => include_str!("../fixtures/rows.web.jsonl"),
        "rows.android.jsonl" => include_str!("../fixtures/rows.android.jsonl"),
        "queries.json" => include_str!("../fixtures/queries.json"),
        "live.json" => include_str!("../fixtures/live.json"),
        other => panic!("no fixture {other}"),
    }
}

/// A raw connection to `path`, behind the store's back. On wasm32 it locks
/// the file exclusively before the first read: the store's files there are
/// WAL without shared memory, which SQLite opens no other way.
pub fn raw_conn(path: &Path) -> rusqlite::Connection {
    let c = rusqlite::Connection::open(path).expect("opens");
    if cfg!(target_arch = "wasm32") {
        c.query_row("PRAGMA locking_mode = EXCLUSIVE", [], |r| {
            r.get::<_, String>(0)
        })
        .expect("locks");
    }
    c
}

/// The platform's default journal mode: WAL, exclusive on wasm32.
pub fn journal() -> de1_history::store::JournalMode {
    OpenOptions::new(Role::Authority).journal
}

/// Opens (creating) an authority file at `path`.
pub fn create(path: &Path) -> Store {
    Store::open(path, OpenOptions::new(Role::Authority).create(1)).expect("opens")
}

/// Opens an existing authority file at `path`.
pub fn reopen(path: &Path) -> Store {
    Store::open(path, OpenOptions::new(Role::Authority)).expect("reopens")
}

/// A web-shaped row: `id`, `completedAt`, and `extra` merged on top.
pub fn row(id: &str, completed_at: i64, extra: Value) -> String {
    let mut v = json!({
        "id": id,
        "completedAt": completed_at,
        "profileName": "Turbo",
        "metadata": { "dose": 18.0, "yieldOut": 36.0, "rating": 3, "notes": null },
        "record": { "duration": 30000 },
        "tags": [],
        "summary": { "v": 1, "sampleCount": 120, "finalWeight": 36.0 }
    });
    merge(&mut v, extra);
    v.to_string()
}

/// Merges `patch` into `base`, objects key by key.
pub fn merge(base: &mut Value, patch: Value) {
    match (base, patch) {
        (Value::Object(b), Value::Object(p)) => {
            for (k, v) in p {
                merge(b.entry(k).or_insert(Value::Null), v);
            }
        }
        (b, p) => *b = p,
    }
}

/// `rows` as plain writes.
pub fn rows_in(rows: &[String]) -> Vec<RowIn> {
    rows.iter().map(|r| RowIn::new(r.clone())).collect()
}

/// The cross-shell fixture rows for `shell` (`web` or `android`).
pub fn fixture_rows(shell: &str) -> Vec<String> {
    fixture(&format!("rows.{shell}.jsonl"))
        .lines()
        .filter(|l| !l.trim().is_empty())
        .map(str::to_owned)
        .collect()
}

/// Every `row_json` in the store, by id, read through the raw connection.
pub fn stored_rows(store: &Store) -> Vec<(String, String)> {
    let mut stmt = store
        .raw()
        .prepare("SELECT id, row_json FROM shot ORDER BY id")
        .expect("prepares");
    stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))
        .expect("runs")
        .collect::<rusqlite::Result<_>>()
        .expect("reads")
}

/// One integer from the raw connection.
pub fn int(store: &Store, sql: &str) -> i64 {
    store
        .raw()
        .query_row(sql, [], |r| r.get(0))
        .expect("an integer")
}

/// The FTS integrity check, run raw: `true` when it passes.
pub fn fts_whole(store: &Store) -> bool {
    store
        .raw()
        .execute(
            "INSERT INTO shot_fts (shot_fts, rank) VALUES ('integrity-check', 1)",
            [],
        )
        .is_ok()
}

fn close(a: Option<f32>, b: Option<f32>) -> bool {
    match (a, b) {
        (None, None) => true,
        (Some(a), Some(b)) => a == b || (a - b).abs() <= 1e-6 * a.abs().max(b.abs()),
        _ => false,
    }
}

/// Stats equal within 1e-6 relative (SPEC §7: SQLite sums in its own order).
pub fn stats_close(a: &de1_domain::BrewHistoryStats, b: &de1_domain::BrewHistoryStats) -> bool {
    a.count == b.count
        && a.mixed_methods == b.mixed_methods
        && a.scope_method == b.scope_method
        && close(a.beans_used_g, b.beans_used_g)
        && close(a.avg_weight_g, b.avg_weight_g)
        && close(a.avg_ratio, b.avg_ratio)
        && close(a.avg_time_s, b.avg_time_s)
        && close(a.avg_rating, b.avg_rating)
}

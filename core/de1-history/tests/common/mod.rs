//! Helpers for the store's tests: real SQLite files in temp dirs, rows in
//! the web shape, the cross-shell fixtures.
#![allow(dead_code)]

use std::path::{Path, PathBuf};

use de1_history::store::{OpenOptions, Role, RowIn, Store};
use serde_json::{Value, json};

/// A temp dir that lives as long as the test holds it.
pub fn dir() -> tempfile::TempDir {
    tempfile::tempdir().expect("a temp dir")
}

/// `name` inside `dir`.
pub fn file(dir: &tempfile::TempDir, name: &str) -> PathBuf {
    dir.path().join(name)
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
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("tests/fixtures")
        .join(format!("rows.{shell}.jsonl"));
    std::fs::read_to_string(&path)
        .expect("fixture")
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

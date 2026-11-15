//! `derive_row` on every shot in `core/de1-domain/tests/fixtures`: the shells'
//! cross-shell exports and backups, written by each shell's real code.
// Reads the repository's files with `std::fs`: native only.
#![cfg(not(target_arch = "wasm32"))]

use std::fs;
use std::path::PathBuf;

use de1_history::derive_row;
use serde_json::Value;

/// Every `kind: "shot"` line of every JSONL fixture under `dir`.
fn shot_lines(dir: &str) -> Vec<(String, String)> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../de1-domain/tests/fixtures")
        .join(dir);
    let mut out = Vec::new();
    let mut entries: Vec<_> = fs::read_dir(&root)
        .unwrap_or_else(|e| panic!("{}: {e}", root.display()))
        .filter_map(Result::ok)
        .map(|e| e.path())
        .filter(|p| p.extension().is_some_and(|x| x == "jsonl"))
        .collect();
    entries.sort();
    for path in entries {
        let text = fs::read_to_string(&path).expect("readable");
        for line in text.lines().filter(|l| !l.trim().is_empty()) {
            let v: Value = serde_json::from_str(line).expect("JSON");
            if v["kind"] == "shot" {
                out.push((path.display().to_string(), line.to_owned()));
            }
        }
    }
    out
}

#[test]
fn every_fixture_shot_derives() {
    let mut n = 0;
    for dir in ["shots", "backup"] {
        for (file, line) in shot_lines(dir) {
            let v: Value = serde_json::from_str(&line).expect("JSON");
            let d = derive_row(&line).unwrap_or_else(|e| panic!("{file}: {e}"));
            assert_eq!(Some(d.completed_at), v["completedAt"].as_i64(), "{file}");
            assert_eq!(
                d.profile_name.as_deref(),
                v["profileName"].as_str(),
                "{file}"
            );
            assert_eq!(d.is_live(), v["deletedAt"].is_null(), "{file}");
            assert!(d.sort_yield.is_finite());
            if let Some(ms) = v["record"]["duration"].as_i64() {
                assert_eq!(d.sort_time, ms, "{file}");
            }
            n += 1;
        }
    }
    assert!(n >= 4, "{n} fixture shots");
}

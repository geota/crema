//! The new strict reader and the old tolerant one agree on everything a backup
//! made by Crema holds: any export, read back, gives the same records both ways
//! (restore-safety design, PR6). Where they differ is named in
//! `de1-domain/tests/backup_plan.rs`.

use de1_domain::{
    export_backup_jsonl_from_json, import_backup_bytes, parse_backup_jsonl, scan_backup_bytes,
};
use proptest::prelude::*;
use serde_json::{Value, json};

const WEB: &str = include_str!("../../de1-domain/tests/fixtures/backup/web-backup.crema.jsonl");
const ANDROID: &str =
    include_str!("../../de1-domain/tests/fixtures/backup/android-backup.crema.jsonl");

/// A fixture's first row of `kind`, with `kind` gone.
fn row(fixture: &str, kind: &str) -> Value {
    let mut v: Value = fixture
        .lines()
        .map(|l| serde_json::from_str::<Value>(l).unwrap())
        .find(|v| v["kind"] == kind)
        .unwrap();
    v.as_object_mut().unwrap().remove("kind");
    v
}

fn rows(fixture: &str, kind: &str, n: usize, tag: &str) -> Vec<Value> {
    (0..n)
        .map(|i| {
            let mut r = row(fixture, kind);
            r["id"] = json!(format!("{tag}{i}"));
            r
        })
        .collect()
}

proptest! {
    #[test]
    fn any_export_reads_the_same_both_ways(
        android in any::<bool>(),
        beans in 0_usize..4,
        roasters in 0_usize..3,
        shots in 0_usize..4,
        profiles in 0_usize..3,
        recipes in 0_usize..3,
        methods in 0_usize..3,
        singletons in any::<bool>(),
    ) {
        let f = if android { ANDROID } else { WEB };
        let mut envelope = json!({
            "beans": rows(f, "bean", beans, "bean-"),
            "roasters": rows(f, "roaster", roasters, "roaster-"),
            "shots": rows(f, "shot", shots, "shot-"),
            "profiles": rows(f, "profile", profiles, "profile-"),
            "recipes": rows(f, "recipe", recipes, "recipe:x-"),
            "customMethods": rows(f, "brewMethod", methods, "custom:m-"),
        });
        if singletons {
            for (key, kind) in [("settings", "settings"), ("profileMeta", "profileMeta"), ("recipeMeta", "recipeMeta"), ("maintenance", "maintenance"), ("visualizerPrefs", "visualizerPrefs")] {
                envelope[key] = row(f, kind);
            }
        }
        let built = export_backup_jsonl_from_json(&envelope.to_string(), 1, "t", "t").unwrap();
        let new = serde_json::to_value(import_backup_bytes(built.jsonl.as_bytes())).unwrap();
        let old = serde_json::to_value(parse_backup_jsonl(&built.jsonl)).unwrap();
        prop_assert_eq!(new, old);
        let m = scan_backup_bytes(built.jsonl.as_bytes());
        prop_assert!(m.problems.is_empty() && m.count_mismatches.is_empty(), "{:?}", m);
    }
}

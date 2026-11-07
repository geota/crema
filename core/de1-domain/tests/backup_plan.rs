//! The restore plan: strict reading of a backup's bytes, and the decisions a
//! plan makes per store (restore-safety design, sections 1 and 3).

use std::collections::HashMap;

use de1_domain::app_settings::CommonSettings;
use de1_domain::backup_plan::{LineError, LineIssue, PlanCode, RowStore, Verdict};
use de1_domain::{
    BackupLine, LocalState, RestoreMode, RestoreOpts, common_is_pristine,
    export_backup_jsonl_from_json, import_backup_bytes, parse_backup_jsonl, parse_backup_line,
    plan_restore, scan_backup_bytes,
};
use serde_json::{Value, json};

const WEB: &[u8] = include_bytes!("fixtures/backup/web-backup.crema.jsonl");
const ANDROID: &[u8] = include_bytes!("fixtures/backup/android-backup.crema.jsonl");
const MATRIX: &str = include_str!("fixtures/restore/matrix.json");

fn lines_of(bytes: &[u8]) -> Vec<Value> {
    bytes
        .split(|&b| b == b'\n')
        .filter(|l| !l.is_empty())
        .map(|l| serde_json::from_slice(l).unwrap())
        .collect()
}

fn to_bytes(lines: &[Value]) -> Vec<u8> {
    let mut out = Vec::new();
    for line in lines {
        out.extend(serde_json::to_vec(line).unwrap());
        out.push(b'\n');
    }
    out
}

/// A device with a row in every store, as a fresh install has none.
fn device_with_rows() -> LocalState {
    serde_json::from_value(json!({
        "shell": "web",
        "beans": ["here"], "roasters": ["here"], "profiles": ["here"],
        "recipes": ["recipe:here"], "customMethods": ["custom:here"],
        "shots": [{ "id": "here", "hasSamples": true }],
    }))
    .unwrap()
}

fn replace(bytes: &[u8]) -> Verdict {
    plan_restore(
        &scan_backup_bytes(bytes),
        &device_with_rows(),
        RestoreMode::Replace,
        RestoreOpts::default(),
    )
    .verdict
}

// ── Reading ────────────────────────────────────────────────────────────────

#[test]
fn both_fixtures_scan_clean_and_carry_every_store() {
    for bytes in [WEB, ANDROID] {
        let m = scan_backup_bytes(bytes);
        assert!(m.header.is_some());
        assert!(!m.header_damaged && !m.newer_format);
        assert_eq!(m.problems, vec![], "{m:?}");
        assert_eq!(m.bad_lines, 0);
        assert!(m.count_mismatches.is_empty());
        for store in [
            RowStore::Beans,
            RowStore::Roasters,
            RowStore::Shots,
            RowStore::Profiles,
            RowStore::Recipes,
            RowStore::CustomMethods,
        ] {
            assert!(m.carries(store), "{store:?}");
        }
        assert!(m.settings.is_some() && m.has_maintenance && m.has_visualizer_prefs);
        assert_eq!(m.shots.len(), 1);
        assert!(m.shots[0].has_samples);
    }
}

#[test]
fn import_reads_what_the_old_parser_read() {
    for bytes in [WEB, ANDROID] {
        let new = serde_json::to_value(import_backup_bytes(bytes)).unwrap();
        let old =
            serde_json::to_value(parse_backup_jsonl(std::str::from_utf8(bytes).unwrap())).unwrap();
        assert_eq!(new, old);
    }
}

#[test]
fn a_line_is_read_strictly() {
    assert_eq!(parse_backup_line(b"\xff{}"), Err(LineError::Utf8));
    assert_eq!(parse_backup_line(b"{\"kind\":"), Err(LineError::Json));
    assert_eq!(parse_backup_line(b"[1]"), Err(LineError::NotObject));
    assert_eq!(parse_backup_line(b"{\"id\":\"x\"}"), Err(LineError::NoKind));
    assert_eq!(
        parse_backup_line(b"{\"kind\":\"bean\",\"id\":1}"),
        Err(LineError::BadRow)
    );
    assert_eq!(
        parse_backup_line(b"{\"kind\":\"profile\"}"),
        Err(LineError::BadRow)
    );
    assert_eq!(
        parse_backup_line(b"{\"kind\":\"crema-backup/v2\"}"),
        Err(LineError::NewerFormat)
    );
    assert!(
        matches!(parse_backup_line(b"{\"kind\":\"later\"}"), Ok(BackupLine::Unknown(k)) if k == "later")
    );
}

#[test]
fn every_prefix_but_the_whole_file_is_refused_for_replace() {
    for bytes in [WEB, ANDROID] {
        assert_eq!(replace(bytes), Verdict::Confirm, "the whole file");
        let whole_minus_newline = bytes.len() - 1;
        for end in 0..bytes.len() {
            let verdict = replace(&bytes[..end]);
            if end == whole_minus_newline {
                assert_eq!(verdict, Verdict::Confirm, "all but the final newline");
            } else {
                assert_eq!(verdict, Verdict::Refused, "a prefix of {end} bytes");
            }
        }
    }
}

#[test]
fn one_bad_byte_costs_one_line() {
    let mut bytes = WEB.to_vec();
    let at = bytes.windows(9).position(|w| w == b"\"kind\":\"b").unwrap() + 3;
    bytes[at] = 0xff; // inside the bean line
    let m = scan_backup_bytes(&bytes);
    assert_eq!(m.bad_lines, 1);
    assert_eq!(m.problems[0].issue, LineIssue::Utf8);
    assert!(m.beans.is_empty() && m.shots.len() == 1);

    let merge = plan_restore(
        &m,
        &LocalState::default(),
        RestoreMode::Merge,
        RestoreOpts::default(),
    );
    assert_eq!(
        merge.verdict,
        Verdict::Confirm,
        "merge goes ahead, after the report"
    );
    assert_eq!(merge.shots.add.len(), 1);
    assert_eq!(replace(&bytes), Verdict::Refused);
}

#[test]
fn a_damaged_header_is_not_not_a_backup() {
    let mut lines = lines_of(WEB);
    let mut bytes = b"{\"kind\":\"crema-backup/v1\",\"createdAt\":".to_vec();
    lines.remove(0);
    bytes.push(b'\n');
    bytes.extend(to_bytes(&lines));
    let m = scan_backup_bytes(&bytes);
    assert!(m.header_damaged && m.header.is_none());
    let merge = plan_restore(
        &m,
        &LocalState::default(),
        RestoreMode::Merge,
        RestoreOpts::default(),
    );
    assert_eq!(merge.verdict, Verdict::Confirm);
    assert!(
        merge
            .warnings
            .iter()
            .any(|w| w.code == PlanCode::HeaderDamaged)
    );
    let replace = plan_restore(
        &m,
        &device_with_rows(),
        RestoreMode::Replace,
        RestoreOpts::default(),
    );
    assert!(
        replace
            .refusals
            .iter()
            .any(|r| r.code == PlanCode::HeaderDamaged)
    );
}

#[test]
fn a_file_that_is_no_backup_is_refused() {
    for bytes in [&b"hello\nworld\n"[..], b"", b"{\"kind\":\"later\"}\n"] {
        let plan = plan_restore(
            &scan_backup_bytes(bytes),
            &LocalState::default(),
            RestoreMode::Merge,
            RestoreOpts::default(),
        );
        assert_eq!(plan.refusals[0].code, PlanCode::NotABackup, "{bytes:?}");
    }
}

#[test]
fn a_newer_format_says_update() {
    let mut lines = lines_of(WEB);
    lines[0]["kind"] = json!("crema-backup/v2");
    let m = scan_backup_bytes(&to_bytes(&lines));
    let plan = plan_restore(
        &m,
        &LocalState::default(),
        RestoreMode::Merge,
        RestoreOpts::default(),
    );
    assert_eq!(plan.refusals[0].code, PlanCode::NewerFormat);

    let mut lines = lines_of(WEB);
    let shot = lines.iter_mut().find(|l| l["kind"] == "shot").unwrap();
    shot["formatVersion"] = json!(4);
    let m = scan_backup_bytes(&to_bytes(&lines));
    assert!(m.newer_format);
}

#[test]
fn a_count_mismatch_is_refused_for_replace_and_noted_for_merge() {
    let mut lines = lines_of(WEB);
    lines[0]["beanCount"] = json!(2);
    let m = scan_backup_bytes(&to_bytes(&lines));
    assert_eq!(m.count_mismatches.len(), 1);
    assert_eq!(replace(&to_bytes(&lines)), Verdict::Refused);
    let merge = plan_restore(
        &m,
        &LocalState::default(),
        RestoreMode::Merge,
        RestoreOpts::default(),
    );
    assert_eq!(merge.verdict, Verdict::Confirm);
}

#[test]
fn a_line_with_no_kind_is_a_bad_line_not_a_skip() {
    let mut bytes = WEB.to_vec();
    bytes.extend(b"{\"id\":\"stray\"}\n");
    assert_eq!(scan_backup_bytes(&bytes).bad_lines, 1);
    assert_eq!(replace(&bytes), Verdict::Refused);
}

#[test]
fn a_duplicate_id_keeps_the_first_and_is_noted() {
    let mut lines = lines_of(WEB);
    let bean = lines.iter().find(|l| l["kind"] == "bean").unwrap().clone();
    lines.push(bean);
    lines[0]["beanCount"] = json!(2);
    let m = scan_backup_bytes(&to_bytes(&lines));
    assert_eq!(m.beans.len(), 1);
    assert_eq!(m.duplicates, 1);
    assert_eq!(import_backup_bytes(&to_bytes(&lines)).beans.len(), 1);
}

#[test]
fn an_export_scans_clean() {
    for bytes in [WEB, ANDROID] {
        let plan = parse_backup_jsonl(std::str::from_utf8(bytes).unwrap());
        let envelope = json!({
            "profiles": plan.profiles, "beans": plan.beans, "roasters": plan.roasters,
            "shots": plan.shots, "recipes": plan.recipes, "customMethods": plan.custom_methods,
            "settings": plan.settings, "profileMeta": plan.profile_meta, "recipeMeta": plan.recipe_meta,
            "maintenance": plan.maintenance, "visualizerPrefs": plan.visualizer_prefs,
        });
        let built = export_backup_jsonl_from_json(&envelope.to_string(), 1, "t", "t").unwrap();
        let m = scan_backup_bytes(built.jsonl.as_bytes());
        assert!(
            m.problems.is_empty() && m.count_mismatches.is_empty(),
            "{m:?}"
        );
    }
}

// ── Settings ───────────────────────────────────────────────────────────────

#[test]
fn a_fresh_install_is_pristine_on_both_shells() {
    assert!(common_is_pristine(&CommonSettings::default()));
    // The web after `migrateHotWater`: the four hot-water fields it fills.
    let web = CommonSettings {
        qc_hot_water_mode: Some(de1_domain::hot_water::HotWaterMode::Ratio),
        qc_hot_water_time_s: Some(30.0),
        qc_hot_water_time_no_limit: Some(false),
        qc_hot_water_ratio: Some(3.0),
        idle_sleep_min: Some(30),
        ..CommonSettings::default()
    };
    assert!(common_is_pristine(&web));
    // Android's first launch: the idle-sleep default and `resolveHotWater`.
    let reset = de1_domain::app_settings::reset_settings(&CommonSettings::default(), 0.0);
    assert!(common_is_pristine(&reset.common));
}

#[test]
fn a_setting_someone_changed_is_not_pristine() {
    let themed = CommonSettings {
        theme_mode: "light".to_owned(),
        ..CommonSettings::default()
    };
    assert!(!common_is_pristine(&themed));
    let poured = CommonSettings {
        qc_hot_water_volume_ml: 210.0,
        ..CommonSettings::default()
    };
    assert!(!common_is_pristine(&poured));
}

// ── The matrix ─────────────────────────────────────────────────────────────

/// The fixture's first row of each kind, to give a case's ids real rows.
fn templates() -> HashMap<String, Value> {
    let mut out = HashMap::new();
    for line in lines_of(WEB) {
        let kind = line["kind"].as_str().unwrap().to_owned();
        out.entry(kind).or_insert(line);
    }
    out
}

/// A case's backup as bytes, and its photos.
fn backup_of(spec: &Value) -> (Vec<u8>, Vec<String>) {
    let t = templates();
    let ids = |key: &str| -> Vec<String> {
        spec.get(key)
            .and_then(Value::as_array)
            .map(|a| a.iter().map(|v| v.as_str().unwrap().to_owned()).collect())
            .unwrap_or_default()
    };
    let shots = spec
        .get("shots")
        .and_then(Value::as_array)
        .cloned()
        .unwrap_or_default();
    let mut header = t["crema-backup/v1"].clone();
    for (key, n) in [
        ("beanCount", ids("beans").len()),
        ("roasterCount", ids("roasters").len()),
        ("shotCount", shots.len()),
        ("profileCount", ids("profiles").len()),
        ("recipeCount", ids("recipes").len()),
        ("customMethodCount", ids("customMethods").len()),
    ] {
        header[key] = json!(n);
    }
    for key in ids("omitCounts") {
        header.as_object_mut().unwrap().remove(&key);
    }
    let mut lines = vec![header];
    if let Some(settings) = spec.get("settings") {
        let mut line = t["settings"].clone();
        line["_shell"] = settings["_shell"].clone();
        lines.push(line);
    }
    for key in ["profileMeta", "recipeMeta"] {
        if let Some(Value::Object(meta)) = spec.get(key) {
            let mut line = Value::Object(meta.clone());
            line["kind"] = json!(key);
            lines.push(line);
        }
    }
    for key in ["maintenance", "visualizerPrefs"] {
        if spec.get(key) == Some(&json!(true)) {
            lines.push(t[key].clone());
        }
    }
    let row = |kind: &str, id: &str| {
        let mut line = t[kind].clone();
        line["id"] = json!(id);
        line
    };
    lines.extend(ids("roasters").iter().map(|id| row("roaster", id)));
    lines.extend(ids("beans").iter().map(|id| row("bean", id)));
    for shot in &shots {
        let mut line = row("shot", shot["id"].as_str().unwrap());
        match shot.get("bean").and_then(Value::as_str) {
            Some(bean) => line["bean"]["beanId"] = json!(bean),
            None => {
                line.as_object_mut().unwrap().remove("bean");
            }
        }
        if shot.get("samples") != Some(&json!(true)) {
            line["record"]["samples"] = json!([]);
        }
        line["completedAt"] = shot.get("completedAt").cloned().unwrap_or(json!(1));
        if shot.get("pinned") == Some(&json!(true)) {
            line["pinned"] = json!(true);
        }
        lines.push(line);
    }
    lines.extend(ids("customMethods").iter().map(|id| row("brewMethod", id)));
    lines.extend(ids("recipes").iter().map(|id| row("recipe", id)));
    lines.extend(ids("profiles").iter().map(|id| row("profile", id)));
    (to_bytes(&lines), ids("photos"))
}

#[test]
fn the_matrix() {
    let matrix: Value = serde_json::from_str(MATRIX).unwrap();
    let cases = matrix["cases"].as_array().unwrap();
    assert!(cases.len() >= 40);
    let mut failures = Vec::new();
    for case in cases {
        let name = case["name"].as_str().unwrap();
        let (bytes, photos) = backup_of(&case["backup"]);
        let mut m = scan_backup_bytes(&bytes);
        assert!(
            m.problems.is_empty() && m.count_mismatches.is_empty(),
            "{name}: the case's backup is bad: {m:?}"
        );
        m.photos = photos;
        let local: LocalState = serde_json::from_value(case["local"].clone()).unwrap();
        let mode: RestoreMode = serde_json::from_value(case["mode"].clone()).unwrap();
        let opts: RestoreOpts = case.get("opts").map_or_else(RestoreOpts::default, |o| {
            serde_json::from_value(o.clone()).unwrap()
        });
        let plan = serde_json::to_value(plan_restore(&m, &local, mode, opts)).unwrap();
        for (pointer, want) in case["expect"].as_object().unwrap() {
            let got = plan.pointer(pointer);
            if got != Some(want) {
                failures.push(format!("{name}: {pointer} is {got:?}, want {want}"));
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

//! The line-by-line backup (#310, SPEC §7): a prelude, one line per shot and
//! a tail, joined, give the bytes `export_backup_jsonl_from_json` gives for
//! the same envelope, rows that won't read included.

use de1_domain::{
    BackupShotLine, backup_prelude_jsonl, backup_shot_line, backup_tail_jsonl,
    builtin_brew_recipes, export_backup_jsonl_from_json, parse_backup_jsonl,
    parse_backup_line_json,
};
use serde_json::{Value, json};

const WEB: &str = include_str!("fixtures/backup/web-backup.crema.jsonl");
const ANDROID: &str = include_str!("fixtures/backup/android-backup.crema.jsonl");
const AT: i64 = 1_790_000_000_000;

/// A fixture's records as an export envelope.
fn envelope_of(fixture: &str) -> Value {
    serde_json::to_value(parse_backup_jsonl(fixture)).expect("plan to JSON")
}

/// What a shell streaming the backup writes: the prelude, each shot that
/// reads, the tail.
fn by_lines(envelope: &Value) -> String {
    let shots = envelope["shots"].as_array().cloned().unwrap_or_default();
    let mut lines: Vec<BackupShotLine> = Vec::new();
    let mut left_out = 0;
    for shot in &shots {
        match backup_shot_line(&shot.to_string()) {
            Ok(line) => lines.push(line),
            Err(_) => left_out += 1,
        }
    }
    let mut rest = envelope.clone();
    rest.as_object_mut().unwrap().remove("shots");
    let env = rest.to_string();
    let prelude = backup_prelude_jsonl(&env, lines.len(), left_out, AT, "1.2.3", "tablet").unwrap();
    let mut out = prelude.jsonl;
    for line in &lines {
        out.push_str(&line.line);
    }
    out.push_str(&backup_tail_jsonl(&env).unwrap());
    out
}

fn whole(envelope: &Value) -> String {
    export_backup_jsonl_from_json(&envelope.to_string(), AT, "1.2.3", "tablet")
        .unwrap()
        .jsonl
}

#[test]
fn both_fixtures_give_the_same_bytes_line_by_line() {
    for fixture in [WEB, ANDROID] {
        let envelope = envelope_of(fixture);
        assert!(!envelope["shots"].as_array().unwrap().is_empty());
        assert_eq!(by_lines(&envelope), whole(&envelope));
    }
}

#[test]
fn many_shots_and_rows_that_dont_read_give_the_same_bytes() {
    let mut envelope = envelope_of(WEB);
    let shot = envelope["shots"][0].clone();
    let mut shots: Vec<Value> = (0..50)
        .map(|i| {
            let mut s = shot.clone();
            s["id"] = json!(format!("shot-{i}"));
            s["completedAt"] = json!(1_780_000_000_000_u64 + i * 60_000);
            s
        })
        .collect();
    // One a shell encoded as a string, one whose profile won't read, and one
    // that won't read at all.
    let mut text = shot.clone();
    text["id"] = json!("as-text");
    shots.push(Value::String(text.to_string()));
    let mut bad_profile = shot.clone();
    bad_profile["id"] = json!("bad-profile");
    bad_profile["profile"] = json!({ "steps": "not a list" });
    shots.push(bad_profile);
    shots.push(json!({ "id": "broken", "record": "nope" }));
    envelope["shots"] = Value::Array(shots);
    // A bean that won't read, and a built-in recipe, which is never written.
    envelope["beans"]
        .as_array_mut()
        .unwrap()
        .push(json!({ "id": 7 }));
    let builtin = serde_json::to_value(&builtin_brew_recipes()[0]).unwrap();
    envelope["recipes"].as_array_mut().unwrap().push(builtin);

    let joined = by_lines(&envelope);
    assert_eq!(joined, whole(&envelope));
    let header: Value = serde_json::from_str(joined.lines().next().unwrap()).unwrap();
    assert_eq!(header["shotCount"], 52);
    assert_eq!(header["omittedCount"], 2, "the broken shot and the bean");
    assert_eq!(header["recipeCount"], 1, "a built-in is never written");
}

#[test]
fn a_shot_line_says_what_it_wrote() {
    let shot = envelope_of(ANDROID)["shots"][0].clone();
    let line = backup_shot_line(&shot.to_string()).unwrap();
    assert!(line.line.contains(r#""kind":"shot""#) && line.line.ends_with('\n'));
    assert!(!line.without_profile);
    let mut bad = shot;
    bad["profile"] = json!(3);
    assert!(backup_shot_line(&bad.to_string()).unwrap().without_profile);
    assert!(backup_shot_line("{\"id\":\"x\"}").is_err());
    assert!(backup_shot_line("{").is_err());
}

#[test]
fn each_line_reads_back_strictly() {
    let joined = by_lines(&envelope_of(WEB));
    let kinds: Vec<String> = joined
        .lines()
        .map(|l| {
            let parsed: Value =
                serde_json::from_str(&parse_backup_line_json(l.as_bytes())).unwrap();
            parsed["line"].as_str().unwrap().to_owned()
        })
        .collect();
    assert_eq!(kinds[0], "header");
    for want in [
        "shot",
        "bean",
        "roaster",
        "recipe",
        "brewMethod",
        "profile",
        "singleton",
    ] {
        assert!(kinds.iter().any(|k| k == want), "{want} in {kinds:?}");
    }
    assert!(!kinds.iter().any(|k| k == "error"));
    let settings = joined
        .lines()
        .find(|l| l.contains(r#""kind":"settings""#))
        .unwrap();
    let parsed: Value = serde_json::from_str(&parse_backup_line_json(settings.as_bytes())).unwrap();
    assert_eq!(parsed["kind"], "settings");
    let bad: Value =
        serde_json::from_str(&parse_backup_line_json(br#"{"kind":"bean","id":7}"#)).unwrap();
    assert_eq!(bad, json!({ "line": "error", "error": "badRow" }));
    let cut: Value = serde_json::from_str(&parse_backup_line_json(br#"{"kind":"bean""#)).unwrap();
    assert_eq!(cut["error"], "json");
}

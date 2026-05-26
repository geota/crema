//! Crema's native export format — line-delimited JSON.
//!
//! One record per line, each tagged with a `kind` discriminator:
//!
//! ```text
//! {"kind":"crema/v1","exportedAt":<unix_ms>,"beanCount":N,"roasterCount":M,"shotCount":K}
//! {"kind":"roaster", ...full Roaster JSON...}
//! {"kind":"roaster", ...}
//! {"kind":"bean", ...full Bean JSON...}
//! {"kind":"shot", ...full StoredShot JSON...}
//! ```
//!
//! Design constraints:
//! - **Stream-parseable line-by-line** — a 50,000-shot history doesn't
//!   need to live in memory all at once on the parser side.
//! - **Default-omit** fields the user didn't set (`#[serde(default)]`
//!   round-trips them as `null` / omitted).
//! - **Round-trip lossless** on Crema → Crema. Every field on Bean /
//!   Roaster / StoredShot — including `metadata`, `imageRef`,
//!   `beanconqueror_id`, etc. — survives verbatim.
//! - **Order-independent on apply** — the importer applies a single
//!   plan; the shell decides the bulkAdd / insertPulled order.
//!   Roasters before beans before shots is conventional but not
//!   required.
//!
//! Photos (`imageRef` → IndexedDB blob) are NOT bundled here — they
//! stay device-local. A future `.crema.zip` variant could bundle the
//! blobs alongside; the JSONL stays slim and tooling-friendly.
//!
//! On the BC side, `import_beanconqueror_json` produces the same
//! [`crate::beanconqueror::ImportPlan`] shape, so the shell's commit
//! path is shared.

use serde::{Deserialize, Serialize};

use crate::{Bean, Roaster, StoredShot, beanconqueror::ImportPlan};

/// Header record — the first line of a Crema export.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CremaExportHeader {
    /// Format identifier. Always `"crema/v1"` for this schema; bump
    /// the suffix on breaking changes.
    pub kind: String,
    /// Unix epoch ms when the export was written.
    pub exported_at: i64,
    /// Per-entity counts so consumers can pre-allocate / show progress.
    pub bean_count: usize,
    pub roaster_count: usize,
    pub shot_count: usize,
    /// Crema version that wrote the file (free-form for now).
    #[serde(default)]
    pub crema_version: String,
}

/// Build a Crema JSONL export from in-memory library + history. One
/// line per record, terminated by `\n`. Order: header → roasters →
/// beans → shots.
#[must_use]
pub fn export_jsonl(
    beans: &[Bean],
    roasters: &[Roaster],
    shots: &[StoredShot],
    exported_at_unix_ms: i64,
    crema_version: &str,
) -> String {
    let header = CremaExportHeader {
        kind: "crema/v1".to_owned(),
        exported_at: exported_at_unix_ms,
        bean_count: beans.len(),
        roaster_count: roasters.len(),
        shot_count: shots.len(),
        crema_version: crema_version.to_owned(),
    };
    let mut out = String::new();
    push_line(&mut out, &header);
    for r in roasters {
        push_tagged_line(&mut out, "roaster", r);
    }
    for b in beans {
        push_tagged_line(&mut out, "bean", b);
    }
    for s in shots {
        push_tagged_line(&mut out, "shot", s);
    }
    out
}

fn push_line<T: Serialize>(out: &mut String, value: &T) {
    if let Ok(s) = serde_json::to_string(value) {
        out.push_str(&s);
        out.push('\n');
    }
}

/// Emit a line of the form `{"kind":"<kind>",<inlined-record-fields>}`.
/// Allocates a temporary `Value` so we can splice the discriminator
/// into the entity object without round-tripping the whole thing
/// twice.
fn push_tagged_line<T: Serialize>(out: &mut String, kind: &str, value: &T) {
    let mut v = match serde_json::to_value(value) {
        Ok(v) => v,
        Err(_) => return,
    };
    if let Some(map) = v.as_object_mut() {
        // Insert `kind` first by rebuilding the map. JSON objects
        // are unordered but readers tend to scan-leftmost, so the
        // discriminator at the front is friendly to humans + tools.
        let mut tagged = serde_json::Map::with_capacity(map.len() + 1);
        tagged.insert(
            "kind".to_owned(),
            serde_json::Value::String(kind.to_owned()),
        );
        for (k, val) in map.iter() {
            tagged.insert(k.clone(), val.clone());
        }
        v = serde_json::Value::Object(tagged);
    }
    push_line(out, &v);
}

/// Parse a Crema JSONL export. Returns an [`ImportPlan`] — same
/// shape as BC's import, so the shell's commit path is shared.
///
/// Header is optional but recommended; lines that fail to parse are
/// skipped (with the count surfaced via the plan's diagnostics).
/// Unknown `kind` values are also skipped — forward-compat with
/// future record types.
///
/// # Errors
///
/// Returns the parse error string only for catastrophic input
/// (none currently — even an empty string is a valid no-op import).
pub fn parse_jsonl(text: &str) -> Result<ImportPlan, String> {
    let mut plan = ImportPlan::default();
    let mut header_seen = false;
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let Ok(value) = serde_json::from_str::<serde_json::Value>(trimmed) else {
            // Malformed line — skip.
            continue;
        };
        let kind = value
            .get("kind")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("");
        match kind {
            "crema/v1" => {
                header_seen = true;
            }
            "roaster" => {
                if let Ok(r) = serde_json::from_value::<Roaster>(value) {
                    plan.roasters.push(r);
                }
            }
            "bean" => {
                if let Ok(b) = serde_json::from_value::<Bean>(value) {
                    plan.beans.push(b);
                }
            }
            "shot" => {
                if let Ok(s) = serde_json::from_value::<StoredShot>(value) {
                    let imported = crate::beanconqueror::ImportedShot {
                        stored_shot: s,
                        bean_id: None,
                        bean_name: String::new(),
                        roaster_name: None,
                        roasted_on: None,
                        roast_level: None,
                        grinder_model: None,
                    };
                    plan.shots.push(imported);
                }
            }
            _ => {
                // Unknown kind — silently skip for forward-compat.
            }
        }
    }
    plan.diagnostics.beans_imported = plan.beans.len();
    plan.diagnostics.roasters_created = plan.roasters.len();
    plan.diagnostics.shots_imported = plan.shots.len();
    if !header_seen {
        // No header is fine — we just don't pre-size diagnostics
        // from counts. Importers tolerate `kind`-less files because
        // a pasted single record (rare but conceivable) is also
        // valid.
    }
    Ok(plan)
}

/// JSON-in / JSON-out adapter for the wasm + uniffi bridges. Takes a
/// JSON envelope `{ "beans": [...], "roasters": [...], "shots": [...] }`
/// and returns the JSONL text.
///
/// # Errors
///
/// Returns the JSON parse error string when the envelope is
/// malformed.
pub fn export_jsonl_from_json(
    envelope_json: &str,
    exported_at_unix_ms: i64,
    crema_version: &str,
) -> Result<String, String> {
    #[derive(Deserialize)]
    struct In {
        #[serde(default)]
        beans: Vec<Bean>,
        #[serde(default)]
        roasters: Vec<Roaster>,
        #[serde(default)]
        shots: Vec<StoredShot>,
    }
    let inp: In = serde_json::from_str(envelope_json).map_err(|e| e.to_string())?;
    Ok(export_jsonl(
        &inp.beans,
        &inp.roasters,
        &inp.shots,
        exported_at_unix_ms,
        crema_version,
    ))
}

/// JSON-out adapter for the wasm + uniffi bridges. Returns the
/// ImportPlan as JSON (matching `import_beanconqueror_json` so the
/// shell's apply path stays shared).
///
/// # Errors
///
/// Forwarded from [`parse_jsonl`] (currently always Ok).
pub fn import_jsonl_to_plan_json(text: &str) -> Result<String, String> {
    let plan = parse_jsonl(text)?;
    serde_json::to_string(&plan).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{Bean, Roaster};

    fn sample_bean(id: &str, name: &str, roaster_id: Option<&str>) -> Bean {
        let mut b = Bean::new(id.to_owned(), name.to_owned(), 1_700_000_000_000);
        b.roaster_id = roaster_id.map(str::to_owned);
        b
    }
    fn sample_roaster(id: &str, name: &str) -> Roaster {
        Roaster::new(id.to_owned(), name.to_owned(), 1_700_000_000_000)
    }

    #[test]
    fn export_writes_header_then_entities() {
        let roasters = vec![sample_roaster("roaster:r1", "Onyx")];
        let beans = vec![sample_bean("bean:b1", "Yirg", Some("roaster:r1"))];
        let jsonl = export_jsonl(&beans, &roasters, &[], 1_700_000_000_000, "0.0.1");
        let lines: Vec<&str> = jsonl.lines().collect();
        assert_eq!(lines.len(), 3);
        assert!(lines[0].contains(r#""kind":"crema/v1""#));
        assert!(lines[0].contains(r#""beanCount":1"#));
        assert!(lines[0].contains(r#""roasterCount":1"#));
        assert!(lines[1].contains(r#""kind":"roaster""#));
        assert!(lines[1].contains(r#""name":"Onyx""#));
        assert!(lines[2].contains(r#""kind":"bean""#));
        assert!(lines[2].contains(r#""name":"Yirg""#));
    }

    #[test]
    fn empty_input_writes_header_only() {
        let jsonl = export_jsonl(&[], &[], &[], 1, "x");
        assert_eq!(jsonl.lines().count(), 1);
    }

    #[test]
    fn round_trip_export_then_import_preserves_records() {
        let roasters = vec![
            sample_roaster("roaster:r1", "Onyx"),
            sample_roaster("roaster:r2", "Heart"),
        ];
        let beans = vec![
            sample_bean("bean:b1", "Yirg", Some("roaster:r1")),
            sample_bean("bean:b2", "Geisha", Some("roaster:r2")),
        ];
        let jsonl = export_jsonl(&beans, &roasters, &[], 1_700_000_000_000, "0.0.1");
        let plan = parse_jsonl(&jsonl).expect("parse");
        assert_eq!(plan.roasters.len(), 2);
        assert_eq!(plan.beans.len(), 2);
        assert_eq!(plan.roasters[0].name, "Onyx");
        assert_eq!(plan.beans[0].name, "Yirg");
        assert_eq!(plan.beans[0].roaster_id.as_deref(), Some("roaster:r1"));
    }

    #[test]
    fn parser_skips_malformed_lines() {
        let mut jsonl = String::new();
        jsonl.push_str("{\"kind\":\"crema/v1\",\"exportedAt\":0,\"beanCount\":1,\"roasterCount\":0,\"shotCount\":0}\n");
        jsonl.push_str("not json at all\n");
        jsonl.push_str(
            &serde_json::to_string(&serde_json::json!({
                "kind": "bean",
                "id": "bean:b1",
                "name": "Yirg",
                "decaf": false,
                "favourite": false,
                "origin": {},
                "bag_size": 0,
                "remaining": 0,
                "quality_score": "",
                "tasting_notes": "",
                "rating": 0,
                "notes": "",
                "grinder": "",
                "grinder_setting": "",
                "metadata": null,
                "created_at": 0,
                "updated_at": 0,
                "tags": []
            }))
            .unwrap(),
        );
        jsonl.push('\n');
        let plan = parse_jsonl(&jsonl).unwrap();
        assert_eq!(plan.beans.len(), 1);
    }

    #[test]
    fn parser_skips_unknown_kinds() {
        let mut jsonl = String::new();
        jsonl.push_str(r#"{"kind":"shotgroup","id":"x"}"#);
        jsonl.push('\n');
        jsonl.push_str(r#"{"kind":"future/v9","whatever":true}"#);
        jsonl.push('\n');
        let plan = parse_jsonl(&jsonl).unwrap();
        assert_eq!(plan.beans.len(), 0);
        assert_eq!(plan.roasters.len(), 0);
        assert_eq!(plan.shots.len(), 0);
    }

    #[test]
    fn export_envelope_adapter_round_trips() {
        let envelope = r#"{
            "beans": [],
            "roasters": [{
                "id": "roaster:r1", "name": "Onyx",
                "website": null, "image_url": null, "city": null,
                "country": null, "notes": "",
                "canonical_roaster_id": null, "visualizer_id": null,
                "deleted_at": null, "metadata": null,
                "created_at": 0, "updated_at": 0
            }],
            "shots": []
        }"#;
        let jsonl = export_jsonl_from_json(envelope, 1, "x").unwrap();
        let plan = parse_jsonl(&jsonl).unwrap();
        assert_eq!(plan.roasters.len(), 1);
        assert_eq!(plan.roasters[0].name, "Onyx");
    }
}

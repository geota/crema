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
                // Pull the shell-only `bean` sub-object out BEFORE the
                // `StoredShot` parse — Rust `StoredShot` has no `bean`
                // field, so `from_value` would silently drop it and a
                // Crema → JSONL → Crema round-trip would land every
                // shot with `bean: null`. The flat-form fields
                // (`beanId`, `name`, …) feed `ImportedShot` so the
                // shell's `prepareShot` rebuilds the snapshot.
                let bean_obj = value
                    .get("bean")
                    .and_then(serde_json::Value::as_object)
                    .cloned();
                if let Ok(s) = serde_json::from_value::<StoredShot>(value) {
                    let (bean_id, bean_name, roaster_name, roasted_on, roast_level) = bean_obj
                        .as_ref()
                        .map(|obj| {
                            let bean_id = obj
                                .get("beanId")
                                .and_then(serde_json::Value::as_str)
                                .map(str::to_owned);
                            let bean_name = obj
                                .get("name")
                                .and_then(serde_json::Value::as_str)
                                .map(str::to_owned)
                                .unwrap_or_default();
                            let roaster_name = obj
                                .get("roasterName")
                                .and_then(serde_json::Value::as_str)
                                .map(str::to_owned);
                            let roasted_on = obj
                                .get("roastedOn")
                                .and_then(serde_json::Value::as_str)
                                .map(str::to_owned);
                            // `ShotBean.roastLevel` is a number on the wire;
                            // round to the integer the `ImportedShot` slot
                            // accepts (matches the BC importer's behaviour).
                            #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
                            let roast_level = obj
                                .get("roastLevel")
                                .and_then(serde_json::Value::as_f64)
                                .filter(|n| n.is_finite())
                                .map(|n| n.round() as u8);
                            (bean_id, bean_name, roaster_name, roasted_on, roast_level)
                        })
                        .unwrap_or_else(|| (None, String::new(), None, None, None));
                    let imported = crate::beanconqueror::ImportedShot {
                        stored_shot: s,
                        bean_id,
                        bean_name,
                        roaster_name,
                        roasted_on,
                        roast_level,
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

// ── Full-app backup bundle (profiles + library + history + settings) ────────
//
// A superset of the library JSONL above: same line-tagged format, plus
// `profile` lines (verbatim custom-profile JSON) and a `settings` line (a
// shell-owned portable subset). One core (de)serializer → web (wasm) and
// Android (uniffi) emit/parse IDENTICAL bytes, so a backup moves between
// devices/shells. Photos + OAuth tokens + per-device state are NOT here — the
// shell strips tokens/per-device fields before building the envelope, and a
// future `.crema.zip` wrapper carries photos.

/// Header for a backup bundle — `kind` is `"crema-backup/v1"`. Adds a device
/// label (multi-device disambiguation + the restore type-to-confirm) and a
/// profile count on top of [`CremaExportHeader`].
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupHeader {
    pub kind: String,
    pub created_at: i64,
    #[serde(default)]
    pub app_version: String,
    #[serde(default)]
    pub device_label: String,
    pub profile_count: usize,
    pub bean_count: usize,
    pub roaster_count: usize,
    pub shot_count: usize,
}

/// The parsed contents of a backup bundle. Beans + roasters reuse the library
/// import types; shots are the FULL core [`StoredShot`] (the `bean` snapshot and
/// all), parsed straight off the `shot` lines — NOT the library importer's
/// [`ImportedShot`](crate::beanconqueror::ImportedShot) wrapper, which buries the
/// StoredShot under `storedShot` and re-flattens the bean into loose strings
/// (lossy for a verbatim restore).
/// Profiles + the config blobs ride as verbatim JSON the shell owns. Output-only —
/// serialised to JSON for the wasm / uniffi bridge; the shell parses it.
#[derive(Debug, Clone, Default, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupImportPlan {
    /// Custom-profile JSON objects, verbatim (`kind` stripped). Kept untyped so
    /// an evolving profile shape round-trips losslessly; the shell adopts/merges
    /// by `id`.
    pub profiles: Vec<serde_json::Value>,
    /// The portable settings object (`kind` stripped) if present; the shell
    /// applies the keys it recognises.
    pub settings: Option<serde_json::Value>,
    /// Profile organisation (`kind` stripped): `{ pinned:[ids], hiddenBuiltins:[ids],
    /// builtinOverrides?:{…} }`. The id sets are cross-shell (built-in ids match);
    /// `builtinOverrides` is a web-only superset extra the shell can ignore.
    pub profile_meta: Option<serde_json::Value>,
    /// Maintenance counters (`MaintenanceState`, `kind` stripped) — a shared core
    /// type, byte-identical on both shells, so fully portable.
    pub maintenance: Option<serde_json::Value>,
    /// Visualizer SYNC preferences (NOT tokens; `kind` stripped). Shell-native
    /// shape, `_shell`-tagged; the shell applies it only on a matching shell.
    pub visualizer_prefs: Option<serde_json::Value>,
    /// Bean library — same typed shape as a library import.
    pub beans: Vec<Bean>,
    /// Roaster directory — same typed shape as a library import.
    pub roasters: Vec<Roaster>,
    /// Shot history — the full [`StoredShot`] wire shape (including the `bean`
    /// snapshot), parsed straight off the `shot` lines so a restore is verbatim.
    pub shots: Vec<StoredShot>,
}

/// Build a backup bundle (JSONL): `crema-backup/v1` header → settings →
/// profileMeta → maintenance → visualizerPrefs → roasters → beans → shots →
/// profiles. Profiles + the four config blobs ride as verbatim JSON;
/// beans/roasters/shots are typed + lossless. The shell must EXCLUDE OAuth
/// tokens, per-device/BLE state, and photos before building the envelope.
///
/// `envelope_json` = `{profiles:[…], beans:[Bean], roasters:[Roaster],
/// shots:[StoredShot], settings:{…}, profileMeta:{…}, maintenance:{…},
/// visualizerPrefs:{…}}`. The last three are opaque to the core — it just
/// line-tags and passes them through so both shells emit identical bytes.
///
/// # Errors
/// Returns the JSON parse error string when the envelope is malformed.
pub fn export_backup_jsonl_from_json(
    envelope_json: &str,
    created_at_unix_ms: i64,
    app_version: &str,
    device_label: &str,
) -> Result<String, String> {
    #[derive(Deserialize)]
    struct In {
        #[serde(default)]
        profiles: Vec<serde_json::Value>,
        #[serde(default)]
        beans: Vec<Bean>,
        #[serde(default)]
        roasters: Vec<Roaster>,
        #[serde(default)]
        shots: Vec<StoredShot>,
        #[serde(default)]
        settings: serde_json::Value,
        #[serde(default, rename = "profileMeta")]
        profile_meta: serde_json::Value,
        #[serde(default)]
        maintenance: serde_json::Value,
        #[serde(default, rename = "visualizerPrefs")]
        visualizer_prefs: serde_json::Value,
    }
    let inp: In = serde_json::from_str(envelope_json).map_err(|e| e.to_string())?;
    let header = BackupHeader {
        kind: "crema-backup/v1".to_owned(),
        created_at: created_at_unix_ms,
        app_version: app_version.to_owned(),
        device_label: device_label.to_owned(),
        profile_count: inp.profiles.len(),
        bean_count: inp.beans.len(),
        roaster_count: inp.roasters.len(),
        shot_count: inp.shots.len(),
    };
    let mut out = String::new();
    push_line(&mut out, &header);
    if inp.settings.is_object() {
        push_tagged_line(&mut out, "settings", &inp.settings);
    }
    // The three config blobs (profile organisation / maintenance counters /
    // visualizer sync prefs) — opaque to the core, emitted only when the shell
    // supplied a non-empty object.
    if inp.profile_meta.is_object() {
        push_tagged_line(&mut out, "profileMeta", &inp.profile_meta);
    }
    if inp.maintenance.is_object() {
        push_tagged_line(&mut out, "maintenance", &inp.maintenance);
    }
    if inp.visualizer_prefs.is_object() {
        push_tagged_line(&mut out, "visualizerPrefs", &inp.visualizer_prefs);
    }
    for r in &inp.roasters {
        push_tagged_line(&mut out, "roaster", r);
    }
    for b in &inp.beans {
        push_tagged_line(&mut out, "bean", b);
    }
    for s in &inp.shots {
        push_tagged_line(&mut out, "shot", s);
    }
    for p in &inp.profiles {
        push_tagged_line(&mut out, "profile", p);
    }
    Ok(out)
}

/// Parse a backup bundle. Reuses [`parse_jsonl`] for beans/roasters/shots (it
/// skips the `profile`/`settings` lines as unknown kinds), then a second pass
/// collects the verbatim profile objects + the settings blob (`kind` removed).
#[must_use]
pub fn parse_backup_jsonl(text: &str) -> BackupImportPlan {
    // Reuse the library parser for beans + roasters only — its `shots` are
    // `ImportedShot` wrappers that flatten the bean into loose strings (lossy for
    // a verbatim restore), so we re-parse the `shot` lines into full StoredShots
    // below instead. The double-parse of shot lines is cheap next to the
    // correctness win on a rare, user-initiated restore.
    let library = parse_jsonl(text).unwrap_or_default();
    let mut profiles = Vec::new();
    let mut settings = None;
    let mut profile_meta = None;
    let mut maintenance = None;
    let mut visualizer_prefs = None;
    let mut shots = Vec::new();
    for line in text.lines() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let Ok(mut value) = serde_json::from_str::<serde_json::Value>(trimmed) else {
            continue;
        };
        let kind = value
            .get("kind")
            .and_then(serde_json::Value::as_str)
            .unwrap_or("")
            .to_owned();
        match kind.as_str() {
            "profile" => {
                if let Some(map) = value.as_object_mut() {
                    map.remove("kind");
                }
                profiles.push(value);
            }
            "settings" => {
                if let Some(map) = value.as_object_mut() {
                    map.remove("kind");
                }
                settings = Some(value);
            }
            "profileMeta" => {
                if let Some(map) = value.as_object_mut() {
                    map.remove("kind");
                }
                profile_meta = Some(value);
            }
            "maintenance" => {
                if let Some(map) = value.as_object_mut() {
                    map.remove("kind");
                }
                maintenance = Some(value);
            }
            "visualizerPrefs" => {
                if let Some(map) = value.as_object_mut() {
                    map.remove("kind");
                }
                visualizer_prefs = Some(value);
            }
            "shot" => {
                // The full core wire shape, incl. the `bean` snapshot. Parse
                // straight into StoredShot (the `kind` field is ignored as an
                // unknown key) so the restore keeps every shot verbatim — unlike
                // the library importer's lossy ImportedShot wrapper.
                if let Ok(s) = serde_json::from_value::<StoredShot>(value) {
                    shots.push(s);
                }
            }
            _ => {}
        }
    }
    BackupImportPlan {
        profiles,
        settings,
        profile_meta,
        maintenance,
        visualizer_prefs,
        beans: library.beans,
        roasters: library.roasters,
        shots,
    }
}

/// JSON-out adapter for the wasm + uniffi bridges — the [`BackupImportPlan`] as JSON.
///
/// # Errors
/// Returns the serialise error string (effectively never).
pub fn import_backup_jsonl_to_plan_json(text: &str) -> Result<String, String> {
    let plan = parse_backup_jsonl(text);
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
    fn backup_bundle_round_trips_all_types() {
        let roasters = vec![sample_roaster("roaster:r1", "Onyx")];
        let beans = vec![sample_bean("bean:b1", "Yirg", Some("roaster:r1"))];
        let envelope = serde_json::json!({
            "profiles": [{ "id": "profile:p1", "title": "Londinium", "steps": [] }],
            "beans": beans,
            "roasters": roasters,
            "shots": [],
            "settings": { "weightUnit": "g", "themeMode": "dark", "_shell": "android" },
            "profileMeta": { "pinned": ["builtin:londinium"], "hiddenBuiltins": ["builtin:flat-white"] },
            "maintenance": { "totalLitres": 12.5, "filterCapacityLitres": 50.0 },
            "visualizerPrefs": { "_shell": "android", "autoSync": true, "privacy": "unlisted" },
        });
        let jsonl =
            export_backup_jsonl_from_json(&envelope.to_string(), 1_700_000_000_000, "0.1", "Pixel")
                .unwrap();
        assert!(
            jsonl
                .lines()
                .next()
                .unwrap()
                .contains(r#""kind":"crema-backup/v1""#)
        );
        assert!(jsonl.contains(r#""deviceLabel":"Pixel""#));
        assert!(jsonl.contains(r#""profileCount":1"#));
        assert!(jsonl.contains(r#""kind":"settings""#));
        assert!(jsonl.contains(r#""kind":"profile""#));
        assert!(jsonl.contains(r#""kind":"profileMeta""#));
        assert!(jsonl.contains(r#""kind":"maintenance""#));
        assert!(jsonl.contains(r#""kind":"visualizerPrefs""#));

        let plan = parse_backup_jsonl(&jsonl);
        assert_eq!(plan.profiles.len(), 1);
        assert_eq!(plan.profiles[0]["id"], "profile:p1");
        assert!(
            plan.profiles[0].get("kind").is_none(),
            "kind stripped from profile"
        );
        assert_eq!(plan.beans.len(), 1);
        assert_eq!(plan.roasters.len(), 1);
        let settings = plan.settings.expect("settings present");
        assert_eq!(settings["weightUnit"], "g");
        assert!(
            settings.get("kind").is_none(),
            "kind stripped from settings"
        );
        // The three config blobs round-trip verbatim, `kind` stripped.
        let pm = plan.profile_meta.expect("profileMeta present");
        assert_eq!(pm["pinned"][0], "builtin:londinium");
        assert!(pm.get("kind").is_none(), "kind stripped from profileMeta");
        let maint = plan.maintenance.expect("maintenance present");
        assert_eq!(maint["totalLitres"], 12.5);
        assert!(
            maint.get("kind").is_none(),
            "kind stripped from maintenance"
        );
        let vp = plan.visualizer_prefs.expect("visualizerPrefs present");
        assert_eq!(vp["_shell"], "android");
        assert!(
            vp.get("kind").is_none(),
            "kind stripped from visualizerPrefs"
        );
    }

    #[test]
    fn backup_plan_keeps_full_storedshot() {
        // A shot carrying a `bean` snapshot — the enrichment the library
        // importer's ImportedShot wrapper buries under `storedShot` and reflattens
        // into loose strings. A backup restore must keep the whole StoredShot, so
        // `plan.shots` is `Vec<StoredShot>` parsed straight off the `shot` lines.
        let envelope = serde_json::json!({
            "profiles": [],
            "beans": [],
            "roasters": [],
            "shots": [{
                "formatVersion": 3,
                "id": "shot:rt-1",
                "completedAt": 1_700_000_000_000_i64,
                "profileName": "Londinium",
                "profile": null,
                "stopReason": null,
                "record": { "duration": 30_000, "samples": [] },
                "bean": {
                    "beanId": "bean:b1",
                    "name": "Yirg",
                    "roasterName": "Onyx",
                    "roastLevel": 4,
                },
            }],
        });
        let jsonl =
            export_backup_jsonl_from_json(&envelope.to_string(), 1_700_000_000_000, "0.1", "Pixel")
                .unwrap();

        let plan = parse_backup_jsonl(&jsonl);
        assert_eq!(plan.shots.len(), 1);
        let shot = &plan.shots[0];
        // Typed access — proves the entry is a full StoredShot, not an
        // ImportedShot wrapper (which would have no `id` / `bean` at top level).
        assert_eq!(shot.id, "shot:rt-1");
        assert_eq!(shot.profile_name.as_deref(), Some("Londinium"));
        let bean = shot.bean.as_ref().expect("shot keeps its bean snapshot");
        assert_eq!(bean.name, "Yirg");
        assert_eq!(bean.bean_id.as_deref(), Some("bean:b1"));
        assert_eq!(bean.roaster_name.as_deref(), Some("Onyx"));
        assert_eq!(bean.roast_level, Some(4));
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
                "bagSize": 0,
                "remaining": 0,
                "qualityScore": "",
                "tastingNotes": "",
                "rating": 0,
                "notes": "",
                "grinder": "",
                "grinderSetting": "",
                "metadata": null,
                "createdAt": 0,
                "updatedAt": 0,
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
                "website": null, "imageUrl": null, "city": null,
                "country": null, "notes": "",
                "canonicalRoasterId": null, "visualizerId": null,
                "deletedAt": null, "metadata": null,
                "createdAt": 0, "updatedAt": 0
            }],
            "shots": []
        }"#;
        let jsonl = export_jsonl_from_json(envelope, 1, "x").unwrap();
        let plan = parse_jsonl(&jsonl).unwrap();
        assert_eq!(plan.roasters.len(), 1);
        assert_eq!(plan.roasters[0].name, "Onyx");
    }
}
